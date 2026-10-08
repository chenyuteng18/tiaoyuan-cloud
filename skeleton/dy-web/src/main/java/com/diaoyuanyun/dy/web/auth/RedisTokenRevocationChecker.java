package com.diaoyuanyun.dy.web.auth;

import com.diaoyuanyun.dy.tenancy.jwt.JwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版 token 吊销检查器（{@link JwtVerifier.TokenRevocationChecker} 的生产实现）——
 * 登出 / 强制下线的落地面。
 *
 * <h2>装配形态：显式配置才启用（缺省 none = 不装配）</h2>
 * {@code @ConditionalOnProperty(name = "dy.auth.revocation", havingValue = "redis")}：
 * 配置了 {@code dy.auth.revocation=redis} 才会成为 bean，经 {@code JwtVerifier} 的
 * setter 注入生效。<b>缺省不启用</b>—— 启用吊销等于引入"Redis 可用性 → 登录可用性"
 * 的依赖，这个权衡必须由部署方显式做出（与幂等后端 prod 拒绝 memory 的立场不同：
 * 那里不选 = 语义错误；这里不选 = 仅不启用一项可选能力，验签与 exp 兜底不受影响）。
 *
 * <h2>🛑 可用性取舍：fail-open + exp 兜底（本类的核心决策，改它前先读）</h2>
 * Redis 不可达时 {@link #isRevoked} 返回 {@code false}（视为未吊销）+ WARN 留痕，
 * <b>不</b>抛异常、<b>不</b>拒绝请求。理由：
 * <ul>
 *   <li>吊销黑名单是<b>加速器</b>，不是唯一防线 —— 真正的失效兜底是 token 的
 *       {@code exp}（默认 12h，见 {@code JwtProperties.ttlSeconds}）。Redis 挂掉时
 *       fail-open 的后果是"已登出 token 最多还能用到自然过期"，而 fail-closed 的
 *       后果是<b>全系统对每个请求 500</b> —— 两者不成比例；</li>
 *   <li>与 {@code IdempotencyBackend} 的 fail-closed 不同源：幂等失败的下游是
 *       "重复下单"（资损），吊销检查失败的下游是"登出延迟生效"（体验），语义不同档；</li>
 *   <li>⚠️ 已登记的边界：若产品要求"管理员强制踢人必须即时且强一致"，
 *       那是产品决策 —— 显式改造本类为 fail-closed 并接受 Redis 单点依赖，
 *       同时必须配对"Redis 健康检查进应用启动探活"（参照幂等 Redis 的先例）。</li>
 * </ul>
 *
 * <h2>键与 TTL</h2>
 * 键 = {@code dy:revoke:<tenantId>:<jti>}（租户段隔离不同租户的同 jti 碰撞 ——
 * jti 是 UUID 全局唯一概率上不相撞，但键空间按租户分段让"清理某租户全部吊销键"
 * 成为可能）；TTL = token 剩余寿命 —— 过期 token 本来就会被验签拒绝，
 * 其吊销键在库里的存在只会浪费内存，自然回收。
 */
@Component
@ConditionalOnProperty(name = "dy.auth.revocation", havingValue = "redis")
public class RedisTokenRevocationChecker implements JwtVerifier.TokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocationChecker.class);

    public static final String KEY_PREFIX = "dy:revoke:";

    private final StringRedisTemplate redis;

    public RedisTokenRevocationChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isRevoked(String jti, String tenantId) {
        if (jti == null || jti.isBlank()) {
            // 无 jti 的 token（外部签发方）无从吊销 —— 不是"已吊销"
            return false;
        }
        try {
            Boolean exists = redis.hasKey(redisKey(tenantId, jti));
            return Boolean.TRUE.equals(exists);
        } catch (RuntimeException e) {
            // fail-open + WARN（取舍见类注释）：Redis 故障不升级为请求失败
            log.warn("吊销检查不可达（fail-open，按未吊销放行，exp 兜底仍有效）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 吊销一个 token（登出 / 强制下线的写入点）。
     *
     * <p>🛑 本方法<b>不抛</b> Redis 异常 —— 吊销写失败时请求方需要知道但不需要失败：
     * 返回 {@code false} 让调用方决定提示口径（"已退出"或"退出未完全生效，建议稍后再试"）。
     * 写失败的 token 将存活到自然过期（fail-open 的对称面）。
     *
     * @return true = 已写入吊销键；false = 参数无效或 Redis 写失败
     */
    public boolean revoke(String jti, String tenantId, long ttlSeconds) {
        if (jti == null || jti.isBlank() || ttlSeconds <= 0) {
            // ttl <= 0 的 token 已过期，验签层会拒绝 —— 无需吊销键
            return false;
        }
        try {
            redis.opsForValue().set(redisKey(tenantId, jti), "1", Duration.ofSeconds(ttlSeconds));
            return true;
        } catch (RuntimeException e) {
            log.warn("吊销写入失败（token 将存活到自然过期）: {}", e.getMessage());
            return false;
        }
    }

    private static String redisKey(String tenantId, String jti) {
        return KEY_PREFIX + (tenantId == null ? "-" : tenantId) + ":" + jti;
    }
}
