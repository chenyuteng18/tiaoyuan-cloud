package com.diaoyuanyun.dy.app.identity.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令哈希器（PBKDF2-HMAC-SHA256，JDK 原生）—— 凭证哈希的<b>唯一</b>产出与解析点。
 *
 * <h2>为什么 PBKDF2 而不是 bcrypt / argon2</h2>
 * 与 {@code JwtVerifier} / {@code JwtIssuer} 同一纪律：本骨架必须"克隆即可构建"
 * （离线/受限镜像环境也能 BUILD SUCCESS），JDK 的 {@link SecretKeyFactory}
 * 原生提供 PBKDF2WithHmacSHA256，无需第三方依赖。PBKDF2 的算力成本可通过
 * 迭代次数调节 —— 本类取 <b>210_000</b>（OWASP 2023 对 PBKDF2-HMAC-SHA256 的
 * 建议下限，600_000 为更保守档；本仓取下限与"每登录一次真实计算一次"的
 * 服务端吞吐平衡，后续上调<b>只需改常量</b>：旧哈希按其内嵌迭代次数校验，
 * 新哈希按新常量产出，天然支持平滑迁移）。
 *
 * <h2>存储格式（V23 {@code auth_credential.credential_hash} 的唯一合法形态）</h2>
 * <pre>pbkdf2-sha256$&lt;iterations&gt;$&lt;salt_b64&gt;$&lt;hash_b64&gt;</pre>
 * 迭代次数内嵌在格式串里 —— 校验时<b>按行内声明的次数</b>计算，
 * 不读当前常量（否则上调常量后全部旧哈希假失败）。
 *
 * <h2>🛑 比较必须常量时间</h2>
 * 用 {@link MessageDigest#isEqual}（与 {@code JwtVerifier} 验签同款）。
 * {@code equals} 在首个不同字节提前返回，理论上可被计时探测逐字节猜哈希。
 *
 * <h2>🛑 本类永不回显明文</h2>
 * 入参就是明文（必然经过内存），出参只有格式串 —— 不存在任何
 * "取回明文"或"明文落日志"的路径。日志、异常消息、E2E 断言
 * 一律只见哈希格式串与布尔结果。
 */
@Component
public class PasswordHasher {

    /** 算法标识（格式串第一段，逐字）。 */
    static final String ALG_TAG = "pbkdf2-sha256";

    /** 迭代次数（OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议下限）。 */
    static final int ITERATIONS = 210_000;

    /** 盐长度（字节）。16B = 128 bit，NIST SP 800-132 建议。 */
    static final int SALT_BYTES = 16;

    /** 派生密钥长度（bit）。256 bit = 32 字节，与 SHA-256 输出对齐。 */
    private static final int KEY_LENGTH_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    /** 为明文产出存储格式串。salt 随机生成 —— 同一明文两次调用产出不同哈希（防彩虹表）。 */
    public String hash(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("明文口令不得为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(plaintext, salt, ITERATIONS);
        return ALG_TAG + "$" + ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 校验明文是否与存储格式串匹配。
     *
     * <p>🛑 对<b>格式非法</b>的存储串返回 {@code false} 而不是抛异常：
     * 格式串只可能来自库内数据，被篡改的格式串应当表现为"登录失败"
     * （与错口令不可区分 —— 不给攻击者"这条记录坏了"的探测信号），
     * 同时该行由登录服务以 401 拒绝，符合"失败即拒绝、绝不降级"的两侧纪律。
     */
    public boolean matches(String plaintext, String stored) {
        if (plaintext == null || plaintext.isEmpty() || stored == null || stored.isEmpty()) {
            return false;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !ALG_TAG.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0) {
                return false;
            }
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = pbkdf2(plaintext, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] pbkdf2(String plaintext, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // 算法不可用属环境故障, 不得降级为"无哈希存储"
            throw new IllegalStateException("PBKDF2 初始化失败", e);
        }
    }
}
