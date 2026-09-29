package com.diaoyuanyun.dy.web.idempotent;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 为幂等拦截器提供请求体/响应体缓存 (dy-app 注册在 IdempotencyInterceptor 之前)。
 *
 * <p>请求侧用 {@link CachedBodyRequestWrapper}（可重读）把体读入内存,
 * 使 {@link IdempotencyInterceptor} 能在 {@code preHandle} 读体算哈希<b>之后</b>,
 * 控制器仍能用 {@code @RequestBody} 正常解析 —— 二者互不干扰。
 * 响应侧用 Spring 的 {@link ContentCachingResponseWrapper}, 供拦截器在
 * {@code afterCompletion} 取响应体落库（并 {@code copyBodyToResponse()} 回写）。
 *
 * <h2>为什么请求侧【不能】用 Spring 的 ContentCachingRequestWrapper（已实证）</h2>
 * 它虽名为 caching, 但 {@code getInputStream()} 返回同一个透传流、<b>无 reset/回放</b>:
 * 拦截器读尽后控制器只能读到 EOF ⇒ 带 {@code Idempotency-Key} 的 POST 一律 500
 * （{@code HttpMessageNotReadableException}）。详见 {@link CachedBodyRequestWrapper} 类注释。
 *
 * <p>响应侧不存在该问题: {@code ContentCachingResponseWrapper} 把控制器写的字节
 * 缓存起来, 由本过滤器在 {@code finally} 里 {@code copyBodyToResponse()} 回写,
 * 语义是"业务先写、拦截器后读", 与它的设计用途一致。
 *
 * <h2>🛑 multipart 必须【跳过】请求侧包装 —— B-4 实测事故</h2>
 * {@link CachedBodyRequestWrapper} 在<b>构造时</b>就把 {@code getInputStream()} 读尽
 * （{@code readAllBytes()}）。而 Servlet 容器解析 {@code multipart/form-data} 时，
 * 必须去读<b>原始请求</b>的流：一旦流已被包装器读走，容器的 multipart 解析就得不到任何
 * part ⇒ 带 {@code @RequestParam("file") MultipartFile} 的端点一律抛
 * {@code MissingServletRequestPartException}（表现为 500）。
 *
 * <p>这不是"某个端点没写好"，而是<b>基础设施级的冲突</b>：I3 上传（契约域 I）与
 * 幂等体哈希机制在同一层争夺同一个流。实测由 {@code DocFileE2ETest} 抓出
 * （首轮 5 个上传用例全部 500 MissingServletRequestPartException）。
 *
 * <h3>为什么"跳过"是安全且完整的（而不是留下一个洞）</h3>
 * <ul>
 *   <li><b>对 multipart：不缓存 = 不破坏。</b> 拦截器侧本就有兜底路径 ——
 *       {@code IdempotencyInterceptor.hashBody} 对非包装请求会自行读流算哈希
 *       （见该方法的 {@code if (request instanceof CachedBodyRequestWrapper)} 分支）。
 *       故"跳过包装"与"跳过幂等"不是一回事。</li>
 *   <li><b>不缓存 multipart 体是有意的性能取向：</b> I3 允许 10 MiB，
 *       把每个上传请求整体再驻留一份堆内存（且多数请求根本不带 {@code Idempotency-Key}）
 *       是无谓的放大。</li>
 *   <li><b>⚠️ 一处必须知道的限制（已登记）：</b> 带 {@code Idempotency-Key} 的
 *       multipart 请求，其哈希会用"原始请求体的字节"计算。对同一份文件而言字符串
 *       boundary 相同（测试与多数客户端行为），哈希稳定；但<b>流式上传的 boundary
 *       每次不同</b>时，同内容会被算成不同哈希 ⇒ 幂等失效。故推出下列约束：
 *       <b>multipart 端点不得依赖 {@code Idempotency-Key} 做幂等</b>，
 *       业务幂等必须走内容寻址 —— I3 正是这么做的（幂等键 = tenant + doc_type + file_hash，
 *       见 {@code DocFileService.upload}）。该约束由 {@code IdempotencyInterceptor} 的
 *       显式拒绝兜底（带 Key 的 multipart 直接 400，而不是静默换来一个不可靠的哈希）。</li>
 * </ul>
 *
 * <p>响应侧包装不受影响（与请求体无关），故 multipart 分支只跳过<b>请求</b>包装。
 */
@Component
public class IdempotencyBodyCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        // 🛑 multipart 不包装请求（理由见类注释：包装器会读尽流，使容器无法解析 part）
        ServletRequest reqToPass = isMultipart(req) ? request : new CachedBodyRequestWrapper(req);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper((HttpServletResponse) response);
        try {
            chain.doFilter(reqToPass, wrappedRes);
        } finally {
            wrappedRes.copyBodyToResponse();
        }
    }

    /** 是否为 multipart/form-data 请求（按 Content-Type 前缀判定，大小写不敏感）。 */
    static boolean isMultipart(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase(java.util.Locale.ROOT)
                .startsWith("multipart/");
    }
}
