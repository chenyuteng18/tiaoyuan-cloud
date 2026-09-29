package com.diaoyuanyun.dy.tenancy.jwt;

import com.diaoyuanyun.dy.tenancy.context.TenantClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtVerifierTest — 验签行为的契约测试与攻击面回归。
 *
 * <p><b>为何这些断言必须是"攻击能否得手"而非"方法返回值"</b>：
 * 初版 JwtParser 只做 base64 解码，任何测试都"通过"，因为它从不拒绝任何东西。
 * 真正的验收标准是：<b>篡改过的 token 必须被拒</b>。故本测试主体是攻击回归。
 */
class JwtVerifierTest {

    /** 32 字节密钥 (HS256 安全下限)。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private JwtProperties props;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer(ISSUER);
        verifier = new JwtVerifier(props);
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    void valid_token_is_accepted_and_claims_extracted() {
        String token = sign(header("HS256"), payload("t-1", "s-1", "STORE_STAFF", "own_store"), SECRET);
        TenantClaims claims = verifier.verify(token);
        assertEquals("t-1", claims.tenantId());
        assertEquals("s-1", claims.staffId());
        assertEquals("STORE_STAFF", claims.role());
        assertEquals("own_store", claims.scope());
    }

    // ------------------------------------------------------------------ 攻击面回归

    /**
     * 攻击 ①{@code alg:none}：把 header 改成 none 并去掉签名。
     * 这是 JWT 最经典的绕过手法，必须被拒。
     */
    @Test
    void alg_none_must_be_rejected() {
        // 手工构造 alg:none 的 token（第三段为空）
        String noneHeader = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String p = b64(payload("attacker-tenant", "s-1", "SUPER_ADMIN", "all"));
        String forged = noneHeader + "." + p + ".";

        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(forged));
        assertEquals(JwtValidationException.Reason.MALFORMED, ex.getReason());
        assertEquals(401, ex.getCode() == 1002
                ? com.diaoyuanyun.dy.common.result.ErrorCode.UNAUTHENTICATED.getHttpStatus() : -1);
    }

    /**
     * 攻击 ②签名被篡改（内容改了但沿用旧签名）。
     * 攻击者把 tenant_id 改成别人的租户，签名不动 —— 必须被拒。
     */
    @Test
    void tampered_payload_must_be_rejected() {
        String h = header("HS256");
        String original = payload("t-1", "s-1", "STORE_STAFF", "own_store");
        String token = sign(h, original, SECRET);

        // 只改 payload 的一个字符（模拟篡改 tenant_id）
        String tamperedPayload = original.replace("t-1", "t-2");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + b64(tamperedPayload) + "." + parts[2];

        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(tampered));
        assertEquals(JwtValidationException.Reason.SIGNATURE_INVALID, ex.getReason());
    }

    /** 用【错误密钥】签发的 token（如另一系统的密钥）必须被拒。 */
    @Test
    void token_signed_with_wrong_secret_must_be_rejected() {
        String token = sign(header("HS256"), payload("t-1", "s-1", "STORE_STAFF", "own_store"),
                "ffffffffffffffffffffffffffffffff");
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.SIGNATURE_INVALID, ex.getReason());
    }

    /** 攻击 ③算法混淆：header 声称 RS256，实现不得据此改换验签方式。 */
    @Test
    void alg_confusion_rs256_header_must_be_rejected() {
        // 用 HMAC 但仍把 header 标成 RS256 —— 实现必须在 alg 等值判断处就拒掉
        String token = sign(header("RS256"), payload("t-1", "s-1", "STORE_STAFF", "own_store"), SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.ALG_NOT_ALLOWED, ex.getReason());
    }

    /** 过期 token 必须被拒。 */
    @Test
    void expired_token_must_be_rejected() {
        String p = "{\"iss\":\"" + ISSUER + "\",\"tenant_id\":\"t-1\",\"exp\":"
                + (Instant.now().getEpochSecond() - 3600) + "}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.EXPIRED, ex.getReason());
    }

    /** 未到生效时间 (nbf) 的 token 必须被拒。 */
    @Test
    void not_yet_valid_token_must_be_rejected() {
        String p = "{\"iss\":\"" + ISSUER + "\",\"tenant_id\":\"t-1\",\"exp\":"
                + (Instant.now().getEpochSecond() + 7200) + ",\"nbf\":"
                + (Instant.now().getEpochSecond() + 3600) + "}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.NOT_YET_VALID, ex.getReason());
    }

    /** iss 不匹配必须被拒（防跨系统 token 串用）。 */
    @Test
    void issuer_mismatch_must_be_rejected() {
        String p = "{\"iss\":\"some-other-system\",\"tenant_id\":\"t-1\",\"exp\":"
                + (Instant.now().getEpochSecond() + 3600) + "}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.ISSUER_MISMATCH, ex.getReason());
    }

    /** 缺少 tenant_id 的 token 对本系统无意义，必须被拒（防"合法 token 但无租户"漏进上下文）。 */
    @Test
    void token_without_tenant_id_must_be_rejected() {
        String p = "{\"iss\":\"" + ISSUER + "\",\"exp\":"
                + (Instant.now().getEpochSecond() + 3600) + "}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.MISSING_TENANT, ex.getReason());
    }

    /** 缺 exp 视为无法判定过期，必须被拒（不能因为"没写过期"就永久有效）。 */
    @Test
    void token_without_exp_must_be_rejected() {
        String p = "{\"iss\":\"" + ISSUER + "\",\"tenant_id\":\"t-1\"}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(token));
        assertEquals(JwtValidationException.Reason.MALFORMED, ex.getReason());
    }

    /** 两段式（无签名）、空串、乱串一律拒。 */
    @Test
    void malformed_tokens_must_be_rejected() {
        assertThrows(JwtValidationException.class, () -> verifier.verify(null));
        assertThrows(JwtValidationException.class, () -> verifier.verify(""));
        assertThrows(JwtValidationException.class, () -> verifier.verify("only.two"));
        assertThrows(JwtValidationException.class, () -> verifier.verify("not-a-jwt"));
    }

    // ------------------------------------------------------------------ fail-closed 配置

    /** 未配置密钥时，必须拒绝一切 token（而不是跳过验签放行）。 */
    @Test
    void missing_secret_must_reject_all_tokens_not_skip_verification() {
        JwtProperties empty = new JwtProperties();  // secret 为 null
        JwtVerifier noSecret = new JwtVerifier(empty);
        String token = sign(header("HS256"), payload("t-1", "s-1", "STORE_STAFF", "own_store"), SECRET);
        assertThrows(JwtValidationException.class, () -> noSecret.verify(token));
    }

    /** 密钥过短必须在构造期 fail-fast（不等到运行期才发现安全下限不达标）。 */
    @Test
    void short_secret_must_fail_fast_at_construction() {
        JwtProperties weak = new JwtProperties();
        weak.setSecret("tooshort");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new JwtVerifier(weak));
        assertTrue(ex.getMessage().contains("密钥过短"), "应明确指出密钥过短: " + ex.getMessage());
    }

    // ------------------------------------------------------------------ 吊销

    /** 吊销（黑名单命中）必须被拒。 */
    @Test
    void revoked_token_must_be_rejected() {
        JwtVerifier withRevocation = new JwtVerifier(props, (jti, tenant) -> "revoked-jti".equals(jti));
        String p = "{\"iss\":\"" + ISSUER + "\",\"tenant_id\":\"t-1\",\"jti\":\"revoked-jti\",\"exp\":"
                + (Instant.now().getEpochSecond() + 3600) + "}";
        String token = sign(header("HS256"), p, SECRET);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> withRevocation.verify(token));
        assertEquals(JwtValidationException.Reason.REVOKED, ex.getReason());
    }

    /** 未吊销的 token 正常通过（防"吊销检查把正常 token 也拦了"）。 */
    @Test
    void non_revoked_token_passes() {
        JwtVerifier withRevocation = new JwtVerifier(props, (jti, tenant) -> false);
        String token = sign(header("HS256"), payload("t-1", "s-1", "STORE_STAFF", "own_store"), SECRET);
        assertEquals("t-1", withRevocation.verify(token).tenantId());
    }

    // ------------------------------------------------------------------ helpers

    private static String header(String alg) {
        return "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
    }

    private static String payload(String tenant, String staff, String role, String scope) {
        String t = tenant == null ? "null" : "\"" + tenant + "\"";
        String s = staff == null ? "null" : "\"" + staff + "\"";
        String r = role == null ? "null" : "\"" + role + "\"";
        String sc = scope == null ? "null" : "\"" + scope + "\"";
        return "{\"iss\":\"" + ISSUER + "\",\"tenant_id\":" + t + ",\"staff_id\":" + s
                + ",\"role\":" + r + ",\"scope\":" + sc
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
    }

    private static String sign(String header, String payload, String secret) {
        String input = b64(header) + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}