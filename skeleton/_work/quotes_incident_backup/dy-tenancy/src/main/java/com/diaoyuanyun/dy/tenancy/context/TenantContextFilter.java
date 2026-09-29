package com.diaoyuanyun.dy.tenancy.context;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;
import com.diaoyuanyun.dy.tenancy.jwt.JwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 租户上下文过滤器 (ADR-02 第 1 层 / ADR-05)。
 *
 * <p>职责:
 * <ol>
 *   <li>从 {@code Authorization: Bearer <jwt>} <b>验签后</b>解析租户声明;</li>
 *   <li>若 {@code X-Tenant-Id} 请求头存在, 必须与 token 内 tenant_id <b>完全一致</b>, 否则 403 TENANT_MISMATCH;</li>
 *   <li>写入 {@link TenantContext}, 请求结束清理 (防线程复用串租户)。</li>
 * </ol>
 *
 * <p><b>安全铁律（ADR-02 / ADR-05）</b>：
 * <ul>
 *   <li><b>租户上下文只能来自 token。</b> {@code X-Tenant-Id} 头<b>仅用于一致性校验</b>，
 *       <b>绝不用它建立上下文</b>（初版写成 {@code headerTenant != null ? headerTenant : tokenTenant}，
 *       导致未认证请求带一个头即可自选租户 —— 严重越权入口）。</li>
 *   <li><b>无 token 则不建立上下文</b>（fail-closed），受保护资源自然拿不到数据。</li>
 *   <li><b>带了 token 但非法 → 401</b>，<b>绝不降级为匿名请求</b>。
 *       否则"验签失败"与"未登录"不可区分，攻击者只要带一个坏 token 就能绕过鉴权。</li>
 * </ul>
 *
 * <p><b>为何本类要自己写响应体（关键陷阱）</b>：
 * Servlet {@link Filter} 在 {@code DispatcherServlet} <b>之前</b>执行，
 * 因此过滤器内抛出的异常<b>不会</b>被 {@code @RestControllerAdvice} 捕获，
 * 而会变成容器默认错误页（HTML 或空体）—— 破坏"所有响应都是
 * {@code {code,message,data,trace_id}} 信封"这一契约。
 * 故本类在此<b>直接写与契约一致的信封</b>，保证 401/403 也是标准四字段。
 *
 * <p><b>为何显式声明 {@link Order}</b>：本类依赖 MDC 中已有的 trace_id 来填充信封。
 * 若不固定顺序，{@code TraceIdFilter} 可能晚于本类执行，导致 401/403 响应里
 * <b>trace_id 为 null</b> —— 而契约要求四字段"不得增删"。
 * 本类排在 {@code TraceIdFilter} 之后（值更大 = 更晚执行），
 * 并额外做 trace_id 兜底生成，双保险。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TenantContextFilter implements Filter {

    private final JwtVerifier jwtVerifier;
    private final TenantRejectionListener rejectionListener;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Spring 注入入口。
     *
     * <p>保留 {@link #TenantContextFilter(JwtVerifier)} 单参构造以便测试直接 new；
     * {@code @Autowired} 确保多构造器下 Spring 选对这一个。
     *
     * <p>{@code ObjectProvider} 让监听器成为<b>可选</b>依赖：dy-app 装配了审计桥就注入，
     * 单测（在 dy-tenancy 内）没装就用 {@link TenantRejectionListener#NOOP}。见接口类注释的依赖方向说明。
     */
    @Autowired
    public TenantContextFilter(JwtVerifier jwtVerifier,
                               ObjectProvider<TenantRejectionListener> rejectionListener) {
        this.jwtVerifier = jwtVerifier;
        this.rejectionListener = rejectionListener.getIfAvailable(() -> TenantRejectionListener.NOOP);
    }

    /** 测试用: 不装配监听器(等价于未接审计桥)。 */
    public TenantContextFilter(JwtVerifier jwtVerifier) {
        this(jwtVerifier, new SimpleObjectProvider<>(TenantRejectionListener.NOOP));
    }

    private static final class SimpleObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        SimpleObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return java.util.List.of(value).iterator();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        try {
            String auth = http.getHeader("Authorization");
            String headerTenant = http.getHeader("X-Tenant-Id");

            // 无 Authorization 头: 匿名请求, 不建立上下文 (fail-closed)
            if (auth == null || auth.isBlank()) {
                chain.doFilter(request, response);
                return;
            }

            // 有 Authorization 但格式不对: 这是"带了 token 却非法", 必须 401 拒绝
            if (!auth.startsWith("Bearer ")) {
                String reason = "Authorization 头格式应为 'Bearer <token>'";
                noteRejection(http, ErrorCode.UNAUTHENTICATED, reason);
                writeError((HttpServletResponse) response, ErrorCode.UNAUTHENTICATED, reason);
                return;
            }

            TenantClaims claims;
            try {
                claims = jwtVerifier.verify(auth.substring(7).trim());
            } catch (BizException ex) {
                // 验签失败 -> 401 (绝不降级为匿名)
                noteRejection(http, ErrorCode.of(ex.getCode()), ex.getDevMessage());
                writeError((HttpServletResponse) response, ErrorCode.of(ex.getCode()), ex.getDevMessage());
                return;
            }

            String tokenTenant = claims.tenantId();

            // X-Tenant-Id 必须与 token 一致 (ADR-05); 仅校验, 不采纳其值
            try {
                assertTenantConsistency(headerTenant, tokenTenant);
            } catch (TenantMismatchException ex) {
                // 跨租户访问：这是 S1-3 验收④ 要求"存储层拒绝 + 审计留痕"的那个拒绝点。
                noteRejection(http, ErrorCode.TENANT_MISMATCH, ex.getDevMessage());
                writeError((HttpServletResponse) response, ErrorCode.TENANT_MISMATCH, ex.getDevMessage());
                return;
            }

            // 上下文只来自 token
            TenantContext.set(tokenTenant, claims.staffId(), claims.role(), claims.scope());

            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 通知监听器：一次拒绝已发生（S1-3 验收④ 的留痕触发点）。
     *
     * <p><b>顺序纪律：先写响应，还是先留痕？</b>
     * 本方法在 {@code writeError} <b>之前</b>调用，但实现方<b>不得</b>依赖这个顺序做正确性判断 ——
     * 留痕失败<b>绝不影响</b>拒绝结果（见 {@link TenantRejectionListener} 硬纪律第 1 条）。
     * 这里额外再包一层 try/catch：即便某个实现违反了"不得抛异常"的约定，
     * 也<b>不能</b>让一个附属动作把 403 变成 500。这一层是防御性的第二道闸。
     */
    private void noteRejection(HttpServletRequest request, ErrorCode code, String reason) {
        try {
            rejectionListener.onRejected(code.getCode(), reason, request.getRequestURI());
        } catch (RuntimeException swallowed) {
            // 有意吞掉：留痕是附属动作，任何失败都不得篡改"拒绝"这一既定结果。
            // 不在此处打日志——本类位于 tenancy 层，日志落点由实现方（审计桥）负责。
        }
    }

    /**
     * 过滤器内直接写契约信封。
     *
     * <p>此处不能靠 {@code @RestControllerAdvice}（过滤器在其之前执行）。
     * 响应用的 HTTP 状态取自 {@link ErrorCode#getHttpStatus()}，与控制器侧共用同一真相源。
     *
     * <p><b>trace_id 必须存在</b>：契约 §2.0 规定信封四字段"不得增删"。
     * 若 MDC 尚未被 {@code TraceIdFilter} 写入（顺序异常），此处<b>兜底生成</b>，
     * 绝不下发 {@code trace_id: null} 或缺字段的信封。
     */
    private void writeError(HttpServletResponse response, ErrorCode code, String devMessage) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            response.setHeader(TraceIdFilterHeader.NAME, traceId);
        }
        Result<Void> body = Result.fail(code.getCode(), devMessage, traceId);
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /** 避免 dy-tenancy → dy-web 的反向依赖，此处内联 trace 头名常量。 */
    private static final class TraceIdFilterHeader {
        static final String NAME = "X-Trace-Id";

        private TraceIdFilterHeader() {
        }
    }

    /**
     * X-Tenant-Id 与 token tenant_id 一致性校验。
     *
     * <p>规则: 仅当两者都非空且不相等时拒绝。测试可直接调用本方法无需 servlet 容器。
     *
     * <p>注意: 本方法<b>只做校验</b>——通过后调用方仍应使用 <b>token 的 tenant_id</b> 建立上下文,
     * 而非 header 的值。
     */
    public static void assertTenantConsistency(String headerTenantId, String tokenTenantId) {
        if (headerTenantId != null && tokenTenantId != null && !headerTenantId.equals(tokenTenantId)) {
            throw new TenantMismatchException();
        }
    }
}