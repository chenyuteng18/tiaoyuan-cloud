package com.diaoyuanyun.dy.crypto.algorithm;

/**
 * AEAD 算法实现的<b>接口</b> —— 这是 DoD ⑤ "算法可替换位"的承重结构。
 *
 * <p>替换一个算法需要做的事：实现本接口 + 在 {@link AlgorithmRegistry#standard()}
 * 注册。{@link com.diaoyuanyun.dy.crypto.envelope.FieldCipher} 与所有业务调用点
 * <b>完全不改</b> —— 因为它们拿到的永远是本接口，且永远按信封里的
 * {@link AlgorithmId} 来取实现，而不是按编译期常量。
 *
 * <p>接口给的是 {@code byte[]} 而不是字符串：本接口不承担文本编码决策
 * （明文是什么编码、密文怎么落库由上层定），这样替换算法时不会连带改编码约定。
 */
public interface AlgorithmProvider {

    /** 本实现对应的算法标识。注册表用它建立索引，信封用它选实现。 */
    AlgorithmId id();

    /**
     * 用 {@code key} 加密 {@code plaintext}。
     *
     * <p>实现<b>必须</b>自行生成随机 nonce 并把它交给调用方（通过
     * {@link EncryptResult}），不得复用固定 nonce：AEAD 在 nonce 复用下
     * 会同时丢失机密性并泄露认证密钥。
     */
    EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData);

    /**
     * 用 {@code key} 与 {@code nonce} 解密。
     *
     * <p>实现<b>必须</b>在认证失败时抛
     * {@link com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException}，
     * 而不是返回 {@code null} 或部分明文 —— "解不开"与"解开但内容是错的"
     * 必须是可分辨的两种结果，否则删除权与篡改检测都失去判据。
     */
    byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData);

    /** 生成一把符合本算法长度要求的新密钥（用 {@link java.security.SecureRandom}）。 */
    byte[] newKey();

    /** 加密产物：密文 + 本次使用的 nonce（nonce 不是秘密，但必须随密文一起保存）。 */
    record EncryptResult(byte[] ciphertext, byte[] nonce) {
    }
}