package com.diaoyuanyun.dy.tenancy.jwt;

import com.diaoyuanyun.dy.tenancy.context.TenantClaims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * JWT 校验器 (HS256) —— 用 JDK 原生密码学实现，不引入第三方 JWT 库。
 *
 * <p><b>为什么自己实现而不引 jjwt</b>：本骨架必须"克隆即可构建"（离线/受限镜像环境也能 BUILD SUCCESS），
 * HS256 的验签逻辑可由 JDK 的 {@link Mac} 直接完成，无需额外依赖。若后续引入 jjwt，
 * <b>必须保留本类的全部防护点</b>（见下），否则会退化。
 *
 * <p><b>本类防住的四类真实攻击</b>：
 * <ol>
 *   <li><b>{@code alg: none} 绕过</b>：攻击者把 header 改成 {@code {"alg":"none"}} 并去掉签名。
 *       防护：{@link #verify} 强制要求 header 的 {@code alg} <b>等于</b> 配置算法，
 *       任何不匹配（含 none、含空）一律 {@code ALG_NOT_ALLOWED}。</li>
 *   <li><b>alg 混淆（算法困惑）攻击</b>：若实现"按 header 里的 alg 选算法"，
 *       RS256 场景下攻击者可用公钥当 HMAC 密钥伪造签名。
 *       防护：<b>算法由配置钉死，绝不从 token header 读取后动态选择</b>。
 *       甚至连"读取 header.alg 用于比较"都在验签之后仍保持只做等值判断，不做分支调度。</li>
 *   <li><b>签名比较时序侧信道</b>：用 {@code equals} 比较签名字节会在首个不同字节提前返回，
 *       理论上可被计时探测逐字节猜签名。防护：用 {@link MessageDigest#isEqual} 做<b>常量时间比较</b>。</li>
 *   <li><b>过期/早于生效窗口</b>：校验 {@code exp} 与 {@code nbf}，并施加可配置时钟偏移容忍。</li>
 * </ol>
 *
 * <p><b>失败即拒绝（fail-closed）</b>：任何一步不通过都抛 {@link JwtValidationException}
 * （HTTP 401 / code=1002），<b>不存在"验签失败但放行"的分支</b>。
 * 初版实现把失败静默成 {@code return null}，那会让"验签失败"与"没带 token"不可区分 ——
 * 一旦上层把 null 当作"匿名请求"放行，鉴权就形同虚设。故本类<b>绝不对"带了 token 但非法"返回 null</b>。
 */
@Component
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

    /** HS256 的安全密钥长度下限（字节）。RFC 7518 要求密钥长度 >= 哈希输出长度（256 bit = 32 字节）。 */
    public static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final JwtProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secretBytes;
    /** 可选的吊销检查（生产接 Redis 黑名单）。默认放行 = 不启用吊销。 */
    private final TokenRevocationChecker revocationChecker;

    /**
     * 无参构造 —— 供单元测试与"仅解析"场景使用。
     *
     * <p><b>警告</b>：此构造下的实例 {@link #verify} 会因密钥为空直接抛
     * {@code ALG_NOT_ALLOWED}（应视为"未配置密钥，拒绝一切 token"），
     * 而非静默跳过验签。<b>生产必须用带参构造并由 Spring 注入配置。</b>
     */
    public JwtVerifier() {
        this(new JwtProperties(), (jti, tenantId) -> false);
    }

    /**
     * Spring 注入入口。
     *
     * <p><b>必须标 {@link Autowired}</b>：本类有多个构造器，不标注时 Spring 无法决定用哪个
     * （会退化为调用无参构造，导致密钥永远为空、拒绝一切 token —— 服务静默不可用）。
     */
    @Autowired
    public JwtVerifier(JwtProperties properties) {
        this(properties, (jti, tenantId) -> false);
    }

    public JwtVerifier(JwtProperties properties, TokenRevocationChecker revocationChecker) {
        this.properties = properties;
        this.revocationChecker = revocationChecker;
        String s = properties.getSecret();
        if (s == null || s.isBlank()) {
            // fail-fast: 密钥缺失不允许"降级放行"。留 null 使 verify 一律拒绝。
            this.secretBytes = null;
            return;
        }
        this.secretBytes = s.getBytes(StandardCharsets.UTF_8);
        if (this.secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥过短: " + this.secretBytes.length + " 字节, HS256 要求 >= " + MIN_SECRET_BYTES
                            + " 字节 (RFC 7518)。请检查环境变量配置。");
        }
        if (!"HS256".equalsIgnoreCase(properties.getAlgorithm())) {
            throw new IllegalStateException("本骨架仅支持 HS256, 当前配置: " + properties.getAlgorithm());
        }
        log.info("JwtVerifier 已就绪: alg=HS256 iss={} requireIssuer={} clockSkew={}s",
                properties.getIssuer(), properties.isRequireIssuer(), properties.getClockSkewSeconds());
    }

    /**
     * 校验并解析 token。<b>校验失败必抛异常</b>，绝不返回 null 表示"未认证"。
     *
     * @param token 不含 {@code Bearer } 前缀的裸 JWT
     * @return 受信任的租户声明
     * @throws JwtValidationException 任何校验失败（映射为 HTTP 401 / 1002）
     */
    public TenantClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException(JwtValidationException.Reason.MALFORMED);
        }
        if (secretBytes == null) {
            // 未配置密钥: 拒绝一切 token（fail-closed），而不是放行
            throw new JwtValidationException(JwtValidationException.Reason.ALG_NOT_ALLOWED);
        }

        // JWT = header.payload.signature —— 三段必须齐全（两段意味着无签名，一律拒）
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new JwtValidationException(JwtValidationException.Reason.MALFORMED);
        }

        // ---------- 1) header: alg 必须等于配置值（防 alg:none 与算法混淆） ----------
        JsonNode header = readJson(parts[0]);
        String alg = header.has("alg") ? header.get("alg").asText(null) : null;
        if (alg == null || !"HS256".equalsIgnoreCase(alg)) {
            // 注意: 这里只做【等值判断】, 不按 alg 动态选择验签算法
            log.warn("JWT 被拒: alg 不被允许 ({})", alg);
            throw new JwtValidationException(JwtValidationException.Reason.ALG_NOT_ALLOWED);
        }
        // 显式拒绝 "none"（即使 alg 大小写/空白变形也已在上面的等值判断中挡住, 此处为防御性冗余）
        if ("none".equalsIgnoreCase(alg)) {
            throw new JwtValidationException(JwtValidationException.Reason.ALG_NOT_ALLOWED);
        }

        // ---------- 2) signature: 常量时间比较（防时序侧信道） ----------
        byte[] expected = hmacSha256(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = base64UrlDecode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException(JwtValidationException.Reason.MALFORMED);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            log.warn("JWT 被拒: 签名不匹配");
            throw new JwtValidationException(JwtValidationException.Reason.SIGNATURE_INVALID);
        }

        // ---------- 3) 时间窗口: exp / nbf（含时钟偏移容忍） ----------
        JsonNode payload = readJson(parts[1]);
        long now = Instant.now().getEpochSecond();
        long skew = properties.getClockSkewSeconds();
        if (payload.has("exp") && payload.get("exp").asLong() + skew < now) {
            throw new JwtValidationException(JwtValidationException.Reason.EXPIRED);
        }
        if (payload.has("nbf") && payload.get("nbf").asLong() - skew > now) {
            throw new JwtValidationException(JwtValidationException.Reason.NOT_YET_VALID);
        }
        // exp 缺失本身即为可疑（无法判断过期），拒绝
        if (!payload.has("exp")) {
            throw new JwtValidationException(JwtValidationException.Reason.MALFORMED);
        }

        // ---------- 4) 签发方 ----------
        if (properties.isRequireIssuer()) {
            String iss = payload.has("iss") ? payload.get("iss").asText(null) : null;
            if (iss == null || !properties.getIssuer().equals(iss)) {
                throw new JwtValidationException(JwtValidationException.Reason.ISSUER_MISMATCH);
            }
        }

        // ---------- 5) 吊销（生产接 Redis 黑名单；本骨架默认不启用） ----------
        String tenantId = text(payload, "tenant_id");
        String jti = text(payload, "jti");
        if (revocationChecker != null && revocationChecker.isRevoked(jti, tenantId)) {
            throw new JwtValidationException(JwtValidationException.Reason.REVOKED);
        }

        // ---------- 6) 必备声明: 无 tenant_id 的 token 对本系统无意义, 拒绝 ----------
        if (tenantId == null || tenantId.isBlank()) {
            throw new JwtValidationException(JwtValidationException.Reason.MISSING_TENANT);
        }

        return new TenantClaims(
                tenantId,
                text(payload, "staff_id"),
                text(payload, "role"),
                text(payload, "scope"));
    }

    private byte[] hmacSha256(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretBytes, HMAC_SHA256));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            // 算法不可用属环境故障, 不得降级放行
            throw new IllegalStateException("HMAC-SHA256 初始化失败", e);
        }
    }

    private JsonNode readJson(String base64Url) {
        try {
            String json = new String(base64UrlDecode(base64Url), StandardCharsets.UTF_8);
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new JwtValidationException(JwtValidationException.Reason.MALFORMED);
        }
    }

    private static byte[] base64UrlDecode(String s) {
        String pad = s;
        while (pad.length() % 4 != 0) {
            pad += "=";
        }
        return Base64.getUrlDecoder().decode(pad);
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** 吊销检查接口。生产实现接 Redis 黑名单 / 短 TTL + 刷新令牌。 */
    public interface TokenRevocationChecker {
        boolean isRevoked(String jti, String tenantId);
    }
}