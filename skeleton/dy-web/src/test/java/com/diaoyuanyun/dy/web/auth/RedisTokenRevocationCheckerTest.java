package com.diaoyuanyun.dy.web.auth;

import com.diaoyuanyun.dy.tenancy.context.TenantClaims;
import com.diaoyuanyun.dy.tenancy.jwt.JwtIssuer;
import com.diaoyuanyun.dy.tenancy.jwt.JwtProperties;
import com.diaoyuanyun.dy.tenancy.jwt.JwtValidationException;
import com.diaoyuanyun.dy.tenancy.jwt.JwtVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTokenRevocationChecker} 门禁（7 例）—— 吊销黑名单的读/写/故障三面。
 *
 * <h2>核心判据（取舍见实现类注释，这里钉行为）</h2>
 * <ol>
 *   <li>读命中 ⇒ revoked；未命中 ⇒ 未吊销；</li>
 *   <li>无 jti ⇒ 未吊销（外部签发方无 jti，无从吊销 ≠ 已吊销）；</li>
 *   <li><b>fail-open</b> —— Redis 故障时按未吊销放行（exp 兜底），不抛不拒；</li>
 *   <li>写路径：成功 / ttl 非正不写 / 故障返回 false（不抛 —— 对称 fail-open）；</li>
 *   <li><b>端到端</b> —— 经 setter 注入 JwtVerifier 后，已吊销 token 必须被
 *       {@code verify} 以 REVOKED 拒绝（装配接线的可执行证明）。</li>
 * </ol>
 */
@DisplayName("RedisTokenRevocationChecker · 吊销黑名单读/写/故障三面")
class RedisTokenRevocationCheckerTest {

    private static final String SECRET = "revocation-test-secret-01234567890abcdef";

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisReturning(boolean exists) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(exists);
        return redis;
    }

    @Test
    @DisplayName("吊销键存在 ⇒ isRevoked=true")
    void revoked_key_detected() {
        assertTrue(new RedisTokenRevocationChecker(redisReturning(true))
                .isRevoked("jti-1", "tenant-1"));
    }

    @Test
    @DisplayName("吊销键不存在 ⇒ 未吊销")
    void unknown_key_not_revoked() {
        assertFalse(new RedisTokenRevocationChecker(redisReturning(false))
                .isRevoked("jti-1", "tenant-1"));
    }

    @Test
    @DisplayName("无 jti ⇒ 未吊销（无从吊销 ≠ 已吊销）")
    void blank_jti_not_revoked() {
        assertFalse(new RedisTokenRevocationChecker(redisReturning(true)).isRevoked(null, "t"));
        assertFalse(new RedisTokenRevocationChecker(redisReturning(true)).isRevoked(" ", "t"));
    }

    @Test
    @DisplayName("fail-open：Redis 故障时按未吊销放行（exp 兜底），绝不抛异常给验证热路径")
    void redis_failure_fails_open() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        assertFalse(new RedisTokenRevocationChecker(broken).isRevoked("jti-1", "tenant-1"),
                "Redis 挂掉必须 fail-open（取舍见实现类注释），而非抛异常让全线 500");
    }

    @Test
    @DisplayName("revoke 写入：成功 true；ttl 非正不写（已过期 token 验签层会拒）")
    void revoke_writes_with_ttl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        assertTrue(new RedisTokenRevocationChecker(redis).revoke("jti-1", "tenant-1", 3600));
        org.mockito.Mockito.verify(ops).set(anyString(), eq("1"), eq(Duration.ofSeconds(3600)));

        assertFalse(new RedisTokenRevocationChecker(redis).revoke("jti-1", "tenant-1", 0),
                "ttl<=0 的 token 已过期，无需吊销键");
        assertFalse(new RedisTokenRevocationChecker(redis).revoke(null, "tenant-1", 3600));
    }

    @Test
    @DisplayName("revoke 写失败：返回 false 不抛（fail-open 对称面，token 存活到自然过期）")
    void revoke_failure_returns_false_not_throws() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        assertFalse(new RedisTokenRevocationChecker(redis).revoke("jti-1", "t", 3600));
    }

    @Test
    @DisplayName("端到端：setter 注入 JwtVerifier 后，已吊销 token 以 REVOKED 拒绝（装配接线证明）")
    void injected_checker_rejects_revoked_token_via_verify() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(true);
        RedisTokenRevocationChecker checker = new RedisTokenRevocationChecker(redis);

        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        JwtVerifier verifier = new JwtVerifier(props);
        verifier.setRevocationChecker(checker);

        JwtIssuer.SignedToken signed = new JwtIssuer(props)
                .sign("tenant-1", "staff-1", "client", null);
        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> verifier.verify(signed.token()));
        assertEquals(JwtValidationException.Reason.REVOKED, ex.getReason(),
                "经 setter 注入后，吊销键命中的 token 必须被验证层以 REVOKED 拒绝");

        TenantClaims claims = new JwtVerifier(props).verify(signed.token());
        assertEquals("tenant-1", claims.tenantId(),
                "未注入 checker 的实例（默认放行）同一 token 照常通过 —— 对照组");
    }
}
