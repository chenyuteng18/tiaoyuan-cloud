package com.diaoyuanyun.dy.tenancy.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 校验配置 (ADR-02 第 1 层 / ADR-05)。
 *
 * <p><b>为什么密钥不放进配置中心（ADR-08 的边界）</b>：ADR-08 规定"DB 表是<b>业务配置</b>的唯一真相源"，
 * 但 JWT 密钥是<b>机密</b>而非业务配置 —— 它必须走环境变量 / 密钥管理（KMS），
 * <b>绝不能进 DB 配置表</b>（那张表有明文读权限面，进表即等于泄密）。
 * 这是 ADR-08"业务配置"与"机密"分界的落地示例。
 *
 * <p><b>fail-fast 铁律</b>：{@link #secret} 为空时 {@link JwtVerifier} <b>启动即失败</b>，
 * 不允许"密钥没配就跳过验签"这种降级 —— 那等于把验签开关交给了配置疏漏。
 */
@Component
@ConfigurationProperties(prefix = "dy.jwt")
public class JwtProperties {

    /**
     * HMAC 密钥 (HS256)。生产必须由环境变量/密钥管理注入，<b>禁止明文入库、禁止提交仓库</b>。
     * 长度须 >= 32 字节（HS256 安全下限，见 JwtVerifier 启动校验）。
     */
    private String secret;

    /** 期望签发方。token 的 iss 必须与之一致（防跨系统 token 串用）。 */
    private String issuer = "diaoyuanyun";

    /** 是否强校验 iss。默认 true；仅在对接外部签发方且已知其无 iss 时才可关。 */
    private boolean requireIssuer = true;

    /** 时钟偏移容忍（秒）。用于 exp / nbf 判定，吸收集群间时钟漂移。 */
    private long clockSkewSeconds = 60;

    /** 允许的签名算法。仅允许 HS256；<b>不得加入 none</b>（见 JwtVerifier 的 alg 混淆防护）。 */
    private String algorithm = "HS256";

    /**
     * 访问 token 有效期（秒）。默认 12h —— 与三端"一个工作日一次登录"的使用节奏对齐。
     * 生产可经 {@code DY_JWT_TTL_SECONDS} 覆盖；<b>不得设为 0 或负数</b>
     * （那会签出"出生即过期"的 token，由 {@code JwtProperties} 启动自检拒绝）。
     */
    private long ttlSeconds = 12 * 3600;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public boolean isRequireIssuer() {
        return requireIssuer;
    }

    public void setRequireIssuer(boolean requireIssuer) {
        this.requireIssuer = requireIssuer;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }
}