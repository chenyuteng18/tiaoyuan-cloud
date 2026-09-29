package com.diaoyuanyun.dy.crypto.algorithm;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * {@link AlgorithmId#AES_256_GCM} 的实现 —— JDK 原生的 AES-256-GCM（AEAD）。
 *
 * <p>选它的直接原因：它是<b>JDK 自带</b>的认证加密，不需要引入第三方密码库
 * （少一个依赖就少一处供应链风险，也少一处"其实用的是别的算法"的可能）。
 * 它<b>不是</b>对 SM4 的结论，SM4 的裁定见 ADR 十五 H.4-1（⚠️ 待测评机构确认）。
 *
 * <h2>三个必须做对、否则安全性悄悄归零的点</h2>
 * <ol>
 *   <li><b>nonce 每次必须随机</b>：GCM 在 nonce 复用下会同时丢机密性并泄露认证子密钥。
 *       故 nonce 由本类在<b>加密时</b>用 {@link SecureRandom} 生成，永不作为入参。</li>
 *   <li><b>tag 长度 128 位</b>：{@code GCMParameterSpec(128, nonce)}。用默认值"能跑通"
 *       但会让认证强度不自明 —— 认证强度是安全命题的一部分，不该由默认值决定。</li>
 *   <li><b>认证失败必须抛异常</b>：{@code doFinal} 在 tag 不符时抛 {@code AEADBadTagException}。
 *       本类把它转成 {@link com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException}，
 *       <b>绝不</b>吞掉并返回空数组 —— 那会让"篡改检测"变成"篡改后得到空字符串"。</li>
 * </ol>
 *
 * <h2>关于 AAD（associated data）</h2>
 * 调用方传入的 AAD 被原样喂给 GCM 认证，但<b>不</b>参与加密。本模块用它把
 * "这份密文属于哪个租户/哪个主体"绑定进认证范围 —— 若有人把 A 主体的密文行
 * 挪到 B 主体名下，认证会失败（见 {@link com.diaoyuanyun.dy.crypto.envelope.AadBinding}）。
 * 这把归属关系从"数据库行里的一个字段"提升为"密码学上不可替换的绑定"。
 */
public final class AesGcmAlgorithmProvider implements AlgorithmProvider {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    @Override
    public AlgorithmId id() {
        return AlgorithmId.AES_256_GCM;
    }

    @Override
    public EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
        requireKeyLength(key);
        byte[] nonce = new byte[id().nonceBytes()];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (associatedData != null && associatedData.length > 0) {
                c.updateAAD(associatedData);
            }
            return new EncryptResult(c.doFinal(plaintext), nonce);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM 加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) {
        requireKeyLength(key);
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (associatedData != null && associatedData.length > 0) {
                c.updateAAD(associatedData);
            }
            return c.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException e) {
            // 认证失败的唯一正确处置：抛出可识别的类型。此处【不】返回 null / 空数组 / 原文。
            throw new com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException(
                    "AEAD 认证失败：密钥不正确、密文被篡改，或 AAD 绑定不匹配（三者本层无法区分，"
                            + "这是 AEAD 的固有性质，不应假装能分辨）", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM 解密失败（非认证原因）", e);
        }
    }

    @Override
    public byte[] newKey() {
        byte[] k = new byte[id().keyBytes()];
        random.nextBytes(k);
        return k;
    }

    /**
     * 校验密钥长度。<b>存在的理由</b>：AES 在 JDK 里接受 16/24/32 三种长度，
     * 而本枚举登记的是 256 位。若不校验，一把 128 位密钥会"正常工作"，
     * 于是"我们用 256 位"这句话在运行期是假的 —— 强度声明与实现脱钩，
     * 且只会在有人专门去量密钥长度时才发现。
     */
    private void requireKeyLength(byte[] key) {
        if (key == null || key.length != id().keyBytes()) {
            throw new IllegalArgumentException("密钥长度必须为 " + id().keyBytes() + " 字节（"
                    + id().id() + "），实际=" + (key == null ? "null" : key.length + " 字节"));
        }
    }
}