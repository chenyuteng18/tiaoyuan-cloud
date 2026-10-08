package com.diaoyuanyun.dy.tenancy.jwt;

import com.diaoyuanyun.dy.tenancy.context.TenantClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtIssuer} 签发器门禁 —— 核心判据是<b>签发/校验闭环</b>：
 * 本类签出的每一个 token 都必须能被 {@link JwtVerifier} 原样接受；
 * 反过来，任何"签发口径与校验口径漂移"（少 iss、缺 exp、claims 改名）
 * 都会在这里以 401 形态暴露，而不是商用首日的全线 401。
 *
 * <h2>判据清单（7 例）</h2>
 * <ol>
 *   <li><b>往返闭环</b> —— sign → verify，三 claims（tenant/staff/role/scope）逐字一致；</li>
 *   <li><b>必备声明齐备</b> —— iss / exp / nbf / jti / iat 都在，且 exp = now + ttl；</li>
 *   <li><b>jti 唯一</b> —— 同参数连签两枚，jti 必不同（吊销键的前提）；</li>
 *   <li><b>防篡改</b> —— payload 任一字节改动 ⇒ SIGNATURE_INVALID；</li>
 *   <li><b>匿名客户形态</b> —— staffId=null 可签，verify 后 staff_id 为 null；</li>
 *   <li><b>fail-fast（密钥缺失）</b> —— 构造期即 IllegalStateException，绝不签废票；</li>
 *   <li><b>fail-fast（ttl 非正 / 空租户）</b> —— 非正有效期构造期拒；空租户签发期拒。</li>
 * </ol>
 */
@DisplayName("JwtIssuer · 签发/校验闭环与 fail-fast")
class JwtIssuerTest {

    /** 32 字节合法密钥（与 dev 占位同长度，非同值）。 */
    private static final String SECRET = "issuer-test-secret-0123456789abcdef01";

    private static JwtProperties props(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    @DisplayName("往返闭环：sign → verify，claims 逐字一致")
    void sign_then_verify_round_trip() {
        JwtIssuer issuer = new JwtIssuer(props(SECRET));
        JwtVerifier verifier = new JwtVerifier(props(SECRET),
                (jti, tenantId) -> false);

        JwtIssuer.SignedToken signed =
                issuer.sign("11111111-2222-3333-4444-555555555555", "staff-1", "meridian", "own_store");
        TenantClaims claims = verifier.verify(signed.token());

        assertEquals("11111111-2222-3333-4444-555555555555", claims.tenantId());
        assertEquals("staff-1", claims.staffId());
        assertEquals("meridian", claims.role());
        assertEquals("own_store", claims.scope());
    }

    @Test
    @DisplayName("必备声明齐备：iss/exp/nbf/jti/iat 都在，exp = iat + ttl")
    void all_required_claims_present() {
        JwtProperties p = props(SECRET);
        p.setTtlSeconds(1800);
        JwtIssuer issuer = new JwtIssuer(p);

        JwtIssuer.SignedToken signed =
                issuer.sign("11111111-2222-3333-4444-555555555555", "s", "client", null);

        var payload = JwtIssuer.readPayloadUnverified(signed.token());
        assertEquals("diaoyuanyun", payload.path("iss").asText());
        assertNotNull(payload.path("exp").asLong(0), "exp 必备（校验端对缺 exp 直接拒）");
        assertNotNull(payload.path("nbf").asLong(0));
        assertNotNull(payload.path("jti").asText(null));
        assertEquals(1800L, payload.path("exp").asLong() - payload.path("iat").asLong(),
                "exp - iat 必须等于配置 ttl");
        assertEquals(1800L, signed.expiresIn());
    }

    @Test
    @DisplayName("jti 唯一：同参数连签两枚 jti 必不同")
    void jti_unique_across_signings() {
        JwtIssuer issuer = new JwtIssuer(props(SECRET));
        String t1 = issuer.sign("t", null, "client", null).token();
        String t2 = issuer.sign("t", null, "client", null).token();
        assertNotEquals(t1, t2);
        assertNotEquals(
                JwtIssuer.readPayloadUnverified(t1).path("jti").asText(),
                JwtIssuer.readPayloadUnverified(t2).path("jti").asText(),
                "同参数连签的两枚 token 的 jti 必须不同（吊销黑名单按 jti 定位的前提）");
    }

    @Test
    @DisplayName("防篡改：payload 任一字节改动 ⇒ SIGNATURE_INVALID")
    void tampered_payload_rejected() {
        JwtIssuer issuer = new JwtIssuer(props(SECRET));
        JwtVerifier verifier = new JwtVerifier(props(SECRET), (jti, tenantId) -> false);

        String token = issuer.sign("t-tenant", null, "client", null).token();
        String[] parts = token.split("\\.");
        String forgedPayload = new String(java.util.Base64.getUrlEncoder().withoutPadding()
                .encode("{\"iss\":\"diaoyuanyun\",\"tenant_id\":\"other-tenant\",\"exp\":9999999999}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(forged));
        assertEquals(JwtValidationException.Reason.SIGNATURE_INVALID, ex.getReason(),
                "改了 payload（换租户）的 token 必须被验签拒绝");
    }

    @Test
    @DisplayName("匿名客户形态：staffId=null 可签，verify 后 staff_id 为 null")
    void anonymous_customer_without_staff_id() {
        JwtIssuer issuer = new JwtIssuer(props(SECRET));
        JwtVerifier verifier = new JwtVerifier(props(SECRET), (jti, tenantId) -> false);

        TenantClaims claims = verifier.verify(
                issuer.sign("t-tenant", null, "client", null).token());
        assertEquals("t-tenant", claims.tenantId());
        assertEquals("client", claims.role());
        assertEquals(null, claims.staffId(), "匿名客户的 staff_id 必须是 null（不是空串、不是 \"anonymous\"）");
    }

    @Test
    @DisplayName("fail-closed：密钥缺失时构造可用但 sign 一律拒绝（与 JwtVerifier『verify 一律拒』镜像）")
    void missing_secret_rejects_signing() {
        JwtIssuer issuer = new JwtIssuer(new JwtProperties());
        assertThrows(IllegalStateException.class,
                () -> issuer.sign("t", "s", "client", null),
                "密钥未配置时签发端必须拒绝工作 —— 绝不产出校验端必然拒绝的废票");
    }

    @Test
    @DisplayName("fail-fast：ttl 非正构造期拒；空租户签发期拒")
    void bad_ttl_and_blank_tenant_rejected() {
        JwtProperties p = props(SECRET);
        p.setTtlSeconds(0);
        assertThrows(IllegalStateException.class, () -> new JwtIssuer(p),
                "ttl=0 会签出出生即过期的 token，构造期必须拒绝");

        JwtIssuer issuer = new JwtIssuer(props(SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> issuer.sign(" ", "s", "client", null),
                "无租户的 token 对本系统无意义，签发期必须拒绝");
        assertTrue(true);
    }
}
