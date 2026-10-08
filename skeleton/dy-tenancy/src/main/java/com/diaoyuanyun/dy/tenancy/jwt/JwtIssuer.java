package com.diaoyuanyun.dy.tenancy.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * JWT 签发器 (HS256) —— 与 {@link JwtVerifier} 同一密钥、同一 claims 口径的<b>唯一签发端</b>。
 *
 * <p><b>为什么放在 dy-tenancy 而不是 dy-app</b>：签发与校验必须共用同一份密钥配置
 * （{@link JwtProperties}），分放两个模块会制造"两处密钥来源"的分叉机会 ——
 * 密钥换错一侧 = 全员 401 而登录正常（或反之），是最难排查的静默故障。
 * 与 {@link JwtVerifier} 同包相邻，"签发口径 = 校验口径"由物理布局钉死。
 *
 * <h2>claims 口径（与 {@link JwtVerifier#verify} 逐字对齐，缺一即被校验端拒绝）</h2>
 * <ul>
 *   <li>{@code tenant_id} —— <b>必备</b>（校验端第 6 步：缺失即 MISSING_TENANT 拒绝）；</li>
 *   <li>{@code exp} —— <b>必备</b>（校验端第 3 步：缺失即 MALFORMED 拒绝 ——
 *       "无法判断过期的 token"本身就是可疑物）；</li>
 *   <li>{@code iss} —— {@code requireIssuer=true} 时必备（校验端第 4 步 ISSUER_MISMATCH）；</li>
 *   <li>{@code nbf} —— 签发即生效（now），校验端含时钟偏移容忍；</li>
 *   <li>{@code jti} —— 每 token 唯一（UUID），吊销检查（{@code TokenRevocationChecker}）按它定位；</li>
 *   <li>{@code staff_id} / {@code role} / {@code scope} —— 与 {@link com.diaoyuanyun.dy.tenancy.context.TenantClaims}
 *       三字段同名透传（客户为匿名身份时 staff_id 可空）。</li>
 * </ul>
 *
 * <h2>失败即失败（fail-closed），与校验端镜像</h2>
 * 与 {@link JwtVerifier} 分两档对齐：
 * <ul>
 *   <li><b>密钥缺失</b> —— 与校验端同形态：构造可用但 {@link #sign} 一律抛
 *       {@code IllegalStateException}（"未配置 = 拒绝工作"，不炸启动、也绝不产出废票）；</li>
 *   <li><b>配置了但错</b>（密钥短于 {@link JwtVerifier#MIN_SECRET_BYTES} /
 *       算法不是 HS256 / ttl 非正）—— <b>构造期即抛</b> fail-fast：
 *       这类是"配置的人以为配对了"，必须部署期就炸出来。</li>
 * </ul>
 * 校验端对"带了 token 但非法"绝不降级放行；签发端对"未配置或配置错"绝不降级签发，两侧同律。
 */
@Component
public class JwtIssuer {

    private static final Logger log = LoggerFactory.getLogger(JwtIssuer.class);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final JwtProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secretBytes;
    private final SecureRandom random = new SecureRandom();

    /**
     * Spring 注入入口。
     *
     * <p>与 {@link JwtVerifier} 同理：多构造器下必须显式 {@code @Autowired}，
     * 否则 Spring 退化到无参构造 ⇒ 密钥永远为空 ⇒ 签发端永远拒绝工作（服务静默不可用）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JwtIssuer(JwtProperties properties) {
        this.properties = properties;
        String s = properties.getSecret();
        if (s == null || s.isBlank()) {
            // fail-fast: 密钥缺失不允许"签发一个必然被拒的 token"。
            this.secretBytes = null;
            return;
        }
        this.secretBytes = s.getBytes(StandardCharsets.UTF_8);
        if (this.secretBytes.length < JwtVerifier.MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥过短: " + this.secretBytes.length + " 字节, HS256 要求 >= "
                            + JwtVerifier.MIN_SECRET_BYTES + " 字节 (RFC 7518)。请检查环境变量配置。");
        }
        if (!"HS256".equalsIgnoreCase(properties.getAlgorithm())) {
            throw new IllegalStateException("本骨架仅支持 HS256, 当前配置: " + properties.getAlgorithm());
        }
        if (properties.getTtlSeconds() <= 0) {
            throw new IllegalStateException(
                    "JWT ttlSeconds 必须为正数, 当前: " + properties.getTtlSeconds()
                            + " —— 非正有效期会签出出生即过期的 token");
        }
        log.info("JwtIssuer 已就绪: alg=HS256 iss={} ttl={}s",
                properties.getIssuer(), properties.getTtlSeconds());
    }

    /**
     * 签发一个访问 token。
     *
     * @param tenantId 租户（必备，null/blank 即 {@code IllegalArgumentException} —— 签发端不产出废票）
     * @param staffId  员工 id（客户匿名身份可空）
     * @param role     token 角色码（client / therapist / meridian / manager / area / hq / SUPER_ADMIN）
     * @param scope    行级范围声明（可空）
     * @return 裸 JWT（不含 {@code Bearer } 前缀）
     * @throws IllegalStateException 密钥未配置（构造期已 fail-fast，此处为防御性冗余）
     */
    public SignedToken sign(String tenantId, String staffId, String role, String scope) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant_id 不得为空 —— 无租户的 token 对本系统无意义");
        }
        if (secretBytes == null) {
            throw new IllegalStateException("JWT 密钥未配置, 签发端拒绝工作 (fail-fast)");
        }
        long now = Instant.now().getEpochSecond();
        long ttl = properties.getTtlSeconds();
        long jtiSeed = random.nextLong();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("iss", properties.getIssuer());
        payload.put("sub", staffId == null ? "anonymous" : staffId);
        payload.put("tenant_id", tenantId);
        if (staffId != null && !staffId.isBlank()) {
            payload.put("staff_id", staffId);
        }
        if (role != null && !role.isBlank()) {
            payload.put("role", role);
        }
        if (scope != null && !scope.isBlank()) {
            payload.put("scope", scope);
        }
        payload.put("iat", now);
        payload.put("nbf", now);
        payload.put("exp", now + ttl);
        payload.put("jti", new UUID(now, jtiSeed).toString().replace("-", ""));

        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = base64Url(payload.toString());
        String signingInput = header + "." + body;
        String signature = base64Url(hmacSha256(signingInput));
        return new SignedToken(signingInput + "." + signature, ttl, now + ttl);
    }

    private byte[] hmacSha256(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretBytes, HMAC_SHA256));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 初始化失败", e);
        }
    }

    private String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 签发结果：裸 token + 有效期（秒）+ 过期时刻（epoch 秒）。
     *
     * <p>契约 A1 的 {@code LoginData.expires_in} 直接取 {@link #expiresIn}，
     * {@code expires_at} 不下发（契约未声明该键 —— 不得增删）。
     */
    public record SignedToken(String token, long expiresIn, long expiresAtEpochSecond) {

        public String token() {
            return token;
        }
    }

    /** 供测试与运维诊断：解析（不验签）payload 的 exp，确认口径一致。 */
    static JsonNode readPayloadUnverified(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            String pad = parts[1];
            while (pad.length() % 4 != 0) {
                pad += "=";
            }
            String json = new String(Base64.getUrlDecoder().decode(pad), StandardCharsets.UTF_8);
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("非 JWT 形态", e);
        }
    }
}
