package com.diaoyuanyun.dy.tenancy.context;

import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.jwt.JwtProperties;
import com.diaoyuanyun.dy.tenancy.jwt.JwtVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TenantContextFilterTest — 过滤器级行为测试（真实驱动 {@code doFilter}）。
 *
 * <p><b>为何必须单独做过滤器级测试</b>：旧测试只直接调用静态方法
 * {@code assertTenantConsistency}，证明不了过滤器在真实请求下的行为。
 * 而过滤器有一个隐蔽陷阱：它在 {@code DispatcherServlet} <b>之前</b>执行，
 * 因此<b>抛异常不会被 {@code @RestControllerAdvice} 捕获</b>，会退化成容器错误页。
 * 只有真实驱动 doFilter 并检查响应体，才能确认"401/403 也是标准信封"。
 */
class TenantContextFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";
    private static final String TENANT = "aaaaaaaa-1111-1111-1111-111111111111";

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer(ISSUER);
        filter = new TenantContextFilter(new JwtVerifier(props));
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 匿名请求

    /**
     * 回归守护 (安全): <b>无 token 时, 不得用 X-Tenant-Id 请求头建立租户上下文。</b>
     * 初版 {@code headerTenant != null ? headerTenant : tokenTenant} 是严重越权入口。
     */
    @Test
    void no_token_must_not_establish_context_from_header() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", "attacker-chosen-tenant");   // 只有头, 无 Authorization
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        AtomicReference<String> inChain = new AtomicReference<>("UNSET");
        filter.doFilter(req, res, (rq, rs) -> {
            chainCalled.set(true);
            inChain.set(TenantContext.tenantId());
        });

        assertEquals(true, chainCalled.get(), "无 token 应作为匿名请求放行 (由下游决定是否要求认证)");
        assertNull(inChain.get(), "无 token 时租户上下文必须为 null, 绝不可采用 X-Tenant-Id 头");
    }

    // ---------------------------------------------------------------- 非法 token

    /**
     * <b>核心断言</b>：带非法 token 的请求必须 401，且响应体是标准四字段信封。
     * 这一条同时守住两个契约：①认证失败必须拒绝（不得降级为匿名放行）；
     * ②过滤器写出的错误也必须是 {@code {code,message,data,trace_id}} 信封。
     */
    @Test
    void invalid_token_must_return_401_with_standard_envelope() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer not.a.valid-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.doFilter(req, res, (rq, rs) -> chainCalled.set(true));

        assertEquals(401, res.getStatus(), "非法 token 必须返回 401");
        assertEquals(false, chainCalled.get(), "非法 token 绝不允许进入业务链");

        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":1002"), "信封 code 应为 1002 UNAUTHENTICATED: " + body);
        assertTrue(body.contains("\"trace_id\""), "信封必须是 snake_case 的 trace_id: " + body);
        assertTrue(body.contains("\"message\""), "信封必须含 message: " + body);
        // 契约 §2.0: "code != 0 时 data 为空" —— 失败信封【不应】出现 data 键
        assertTrue(!body.contains("\"data\""), "失败信封不应含 data (契约: code != 0 时 data 为空): " + body);
    }

    /** Authorization 头格式不对（缺 Bearer 前缀）也必须 401，而不是静默当匿名。 */
    @Test
    void malformed_authorization_header_must_return_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Token abcdef");   // 前缀不是 Bearer
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> { });

        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":1002"));
    }

    // ---------------------------------------------------------------- 租户头不一致

    /** X-Tenant-Id 与 token 不一致 → 403 + 2003，且必须返回信封。 */
    @Test
    void tenant_header_mismatch_must_return_403_envelope() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + validToken(TENANT));
        req.addHeader("X-Tenant-Id", "bbbbbbbb-2222-2222-2222-222222222222");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> { });

        assertEquals(403, res.getStatus());
        assertEquals(ErrorCode.TENANT_MISMATCH.getHttpStatus(), res.getStatus());
        assertTrue(res.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":2003"));
    }

    // ---------------------------------------------------------------- 正常路径

    /** 合法 token + 一致的头 → 建立上下文，链内可见，出口清理。 */
    @Test
    void valid_token_establishes_context_and_clears_after() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + validToken(TENANT));
        req.addHeader("X-Tenant-Id", TENANT);
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<String> inChain = new AtomicReference<>();
        filter.doFilter(req, res, (rq, rs) -> inChain.set(TenantContext.tenantId()));

        assertEquals(TENANT, inChain.get(), "链内必须能看到 token 中的租户");
        assertNull(TenantContext.tenantId(), "请求结束必须清理上下文 (防线程复用串租户)");
    }

    /** 只带 token 不带 X-Tenant-Id 头也应正常（头是可选的冗余校验）。 */
    @Test
    void token_without_header_is_accepted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + validToken(TENANT));
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<String> inChain = new AtomicReference<>();
        filter.doFilter(req, res, (rq, rs) -> inChain.set(TenantContext.tenantId()));

        assertEquals(TENANT, inChain.get());
    }

    // ---------------------------------------------------------------- helpers

    private static String validToken(String tenant) {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"" + ISSUER + "\",\"tenant_id\":\"" + tenant
                + "\",\"staff_id\":\"s-1\",\"role\":\"STORE_STAFF\",\"scope\":\"own_store\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}");
        String input = header + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return input + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}