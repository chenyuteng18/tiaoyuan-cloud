package com.diaoyuanyun.dy.web.idempotent;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 幂等拦截器 (ADR-10)。处理 {@code Idempotency-Key} 头:
 * <ul>
 *   <li>同键重放 -> 返回原响应 + {@code X-Idempotent-Replayed: true}</li>
 *   <li>同键不同体 -> 409 IDEMPOTENT_CONFLICT</li>
 *   <li>键非法 -> 400 IDEMPOTENT_KEY_ILLEGAL</li>
 * </ul>
 * 请求体/响应体缓存由 {@link IdempotencyBodyCacheFilter} 提供（请求侧用
 * {@link CachedBodyRequestWrapper} 保证体可重读 —— 否则本类在 preHandle 读尽体后,
 * 控制器 {@code @RequestBody} 会读到 EOF 而报 HttpMessageNotReadableException）。
 *
 * <p>存储介质已抽象为 {@link IdempotencyBackend}：单实例默认内存，多实例用
 * {@link RedisIdempotencyBackend}（由 {@code dy.idempotency.backend} 显式选择）。
 * 本类<b>不感知介质</b>，只调用 {@link IdempotencyStore} 的语义方法。
 *
 * <p><b>存储故障（fail-closed, 行为已定）</b>：后端不可达时 {@link IdempotencyStore} 抛
 * {@link IdempotencyStoreUnavailableException}，本类<b>不捕获</b> —— 它向上冒到
 * {@code GlobalExceptionHandler.handleOther} 变成 500/9001。这是刻意的：幂等是重复提交防线，
 * "存储坏了就放行"会让防线静默失效（详见 {@link IdempotencyStore} 类注释的代价对比）。
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final String ATTR_KEY = "dy.idempotency.key";
    private static final String ATTR_HASH = "dy.idempotency.hash";
    private static final String ATTR_WRES = "dy.idempotency.wrappedResponse";

    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm) || hm.getMethodAnnotation(Idempotent.class) == null) {
            return true;
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            // 未携带幂等键的方法不强制 (骨架宽松); 如需强制可在此抛 IDEMPOTENT_KEY_ILLEGAL
            return true;
        }
        if (!isValidKey(key)) {
            throw new IdempotencyKeyIllegalException(key);
        }
        // 🛑 multipart 请求【不得】依赖 Idempotency-Key（B-4 定下的约束）
        //
        // 理由：multipart 请求体（见 IdempotencyBodyCacheFilter 的类注释）无法在不破坏
        // 容器 multipart 解析的前提下预先缓存 ⇒ 这里的体哈希只能用原始请求体字节算，
        // 而其中的 boundary 是随请求生成的 ⇒ 【同一份文件】在上传两次时会被算成两个不同哈希
        // ⇒ 幂等静默失效（而且失效方向是"放行重复"，不是"多报错"）。
        //
        // 故此处【显式拒绝】而不是让它悄悄退化：宁可让调用方在开发期就收到一次明确的 400，
        // 也不要让"重复上传"在生产里以任何形式依赖一个不可靠的哈希。
        // 业务幂等请走内容寻址 —— I3 的幂等键 = (tenant_id, doc_type, file_hash)。
        if (request.getContentType() != null
                && request.getContentType().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("multipart/")) {
            throw new IdempotencyKeyIllegalException(
                    "multipart 请求不支持 Idempotency-Key（boundary 每次不同 ⇒ 体哈希不稳定）；"
                            + "请改用内容寻址幂等（如 I3 的 tenant_id + doc_type + file_hash）");
        }

        String bodyHash = hashBody(request);
        IdempotencyStore.Decision decision = store.resolve(key, bodyHash);
        switch (decision.resolution) {
            case REPLAY -> {
                response.setStatus(decision.status);
                response.setHeader("X-Idempotent-Replayed", "true");
                response.setContentType("application/json");
                response.getWriter().write(decision.responseBody);
                return false; // 短路, 不再执行控制器
            }
            case CONFLICT -> throw new IdempotencyConflictException();
            case FIRST -> {
                request.setAttribute(ATTR_KEY, key);
                request.setAttribute(ATTR_HASH, bodyHash);
                request.setAttribute(ATTR_WRES, response);
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String key = (String) request.getAttribute(ATTR_KEY);
        String hash = (String) request.getAttribute(ATTR_HASH);
        if (key == null || hash == null) {
            return;
        }
        // 仅成功/可重放的状态码落库 (骨架简化: 2xx 落库)
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            String body = extractResponseBytes(response);
            store.store(key, hash, status, body);
        }
    }

    private String extractResponseBytes(HttpServletResponse response) {
        if (response instanceof ContentCachingResponseWrapper w) {
            return new String(w.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        return "";
    }

    private String hashBody(HttpServletRequest request) throws Exception {
        if (request instanceof CachedBodyRequestWrapper w) {
            // 体已在过滤器阶段读入内存; 直接取用, 不消费流 —— 控制器稍后仍能完整读到同一份体
            return sha256Hex(w.getCachedBody());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try (ServletInputStream is = request.getInputStream()) {
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        return sha256Hex(bos.toByteArray());
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("幂等键体哈希失败", e);
        }
    }

    private boolean isValidKey(String key) {
        // 骨架规则: 1~128 位, 仅允许可见 ASCII 安全字符
        return key.length() <= 128 && key.matches("[\\x21-\\x7E]+");
    }
}
