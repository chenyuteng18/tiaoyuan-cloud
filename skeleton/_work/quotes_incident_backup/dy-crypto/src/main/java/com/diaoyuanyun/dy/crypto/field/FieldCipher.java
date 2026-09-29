package com.diaoyuanyun.dy.crypto.field;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.envelope.AadBinding;
import com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.key.SubjectDek;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyStore;

import java.nio.charset.StandardCharsets;

/**
 * <b>字段级加密的门面</b> —— 业务侧唯一需要知道的加解密入口。
 *
 * <h2>调用点不含任何算法字面量（DoD ⑤ 的验收点）</h2>
 * 本类全文<b>不出现</b> {@code "AES"}、{@code "SM4"}、{@code "GCM"} 等任何算法名。
 * 算法由两处数据决定：
 * <ul>
 *   <li>KEK/DEK 的生成与包裹 —— 由 {@link SubjectKeyStore} 的实现决定（构造时注入）</li>
 *   <li>字段加密 —— 取<b>当前</b>写入所用算法（{@link #writeAlgorithmId}），
 *       解密时按<b>信封里</b>的算法位取实现</li>
 * </ul>
 * 于是"换算法"= 改一个构造参数 + 注册一个 {@link AlgorithmProvider}，
 * 业务调用点零改动。这是可替换位的<b>可验证</b>形式：
 * 测试可以用一个"假算法"注册进去并断言整个链路照常工作，
 * 若链路上任何一处写死了 AES，那个测试就会失败。
 *
 * <h2>加解密与密钥生命周期的关系</h2>
 * <pre>
 *   encrypt(): subject → 取/建 per-subject DEK → 用 DEK 加密字段 → 产出信封（含算法位/DEK 版本）
 *   decrypt(): 信封 → 按信封里的 DEK 版本还原 DEK → 按信封里的算法位选实现 → 解出明文
 * </pre>
 * 加密走"取或建"，解密<b>只走取</b>（绝不自动创建）——
 * 解密路径会创建密钥是一个非常危险的默认：读取一份不存在的数据时会静默生成
 * 一把没人备份过的密钥，把 DoD ④ 的备份门绕过去。
 */
public final class FieldCipher {

    private final SubjectKeyStore keyStore;
    private final AlgorithmRegistry algorithms;
    private final String writeAlgorithmId;

    public FieldCipher(SubjectKeyStore keyStore, AlgorithmRegistry algorithms, String writeAlgorithmId) {
        this.keyStore = keyStore;
        this.algorithms = algorithms;
        this.writeAlgorithmId = writeAlgorithmId;
        // 构造期就解析一次：算法标识写错必须立刻炸（启动即失败），
        // 而不是等到第一条敏感数据进来才发现没有可用算法。
        algorithms.resolve(writeAlgorithmId);
    }

    // ------------------------------------------------------------------
    // 加密
    // ------------------------------------------------------------------

    /** 加密 UTF-8 文本字段。 */
    public String encryptText(SubjectRef subject, String fieldName, String plaintext) {
        if (plaintext == null) {
            // null 语义必须由调用方决定：本模块不替它把 null 变成"加密后的空串"，
            // 那会让"字段为空"与"字段有值但加密后看着像空"不可分辨。
            throw new IllegalArgumentException("明文字段为 null —— null 的处理由调用方决定，"
                    + "本模块不得自行替换（会掩盖『未采集』与『已采集但为空』的区别）");
        }
        return encryptBytes(subject, fieldName, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** 加密任意字节（jsonb / 二进制均可）。 */
    public String encryptBytes(SubjectRef subject, String fieldName, byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("明文字节为 null");
        }
        SubjectDek dek = keyStore.getOrCreateDek(subject);
        AlgorithmProvider provider = algorithms.resolve(writeAlgorithmId);
        byte[] aad = AadBinding.of(subject, fieldName, dek.version());
        AlgorithmProvider.EncryptResult enc = provider.encrypt(dek.rawDek(), plaintext, aad);
        return new CipherEnvelope(provider.id().id(), dek.version(), enc.nonce(), enc.ciphertext())
                .serialize();
    }

    // ------------------------------------------------------------------
    // 解密
    // ------------------------------------------------------------------

    /**
     * 解密为 UTF-8 文本。
     *
     * @throws com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException 主体密钥已销毁（删除权）
     * @throws com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException 密钥/AAD/密文不匹配
     */
    public String decryptText(SubjectRef subject, String fieldName, String envelopeText) {
        return new String(decryptBytes(subject, fieldName, envelopeText), StandardCharsets.UTF_8);
    }

    /**
     * 解密为字节。
     *
     * <h3>三处"不得偷懒"的地方</h3>
     * <ol>
     *   <li><b>按信封里的算法位取实现</b> —— 而不是 {@code writeAlgorithmId}。
     *       用写入算法去解密历史数据，会让算法换代当天所有老数据报"解不开"，
     *       而真因是"读的时候用了错的算法"，看起来却像"密钥丢了"。</li>
     *   <li><b>按信封里的 DEK 版本取密钥</b> —— DEK 轮换后老密文必须用老版本解。</li>
     *   <li><b>解密路径不创建密钥</b>（见 {@link com.diaoyuanyun.dy.crypto.key.SubjectKeyStore} 的说明）。</li>
     * </ol>
     */
    public byte[] decryptBytes(SubjectRef subject, String fieldName, String envelopeText) {
        CipherEnvelope env = CipherEnvelope.parse(envelopeText);

        // 【第 1 步】已销毁 → 明确报"不可恢复"，这是删除权的预期结果，不是故障。
        // 必须先查这一步：否则下面 wrappedOf(version) 返回空，会被报成"版本不存在"，
        // 把"数据已依法删除"呈现为"逻辑错误"。
        if (keyStore.isDestroyed(subject)) {
            throw new com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException(
                    new com.diaoyuanyun.dy.crypto.key.SubjectKeyRef(
                            subject.tenantId(), subject.subjectType(), subject.subjectId(),
                            env.dekVersion()));
        }

        // 【第 2 步】按信封里的 DEK 版本取包裹材料 —— 不是"当前版本"。
        // 轮换后历史密文指向老版本；取当前版本会以 AAD 不符的形式失败，
        // 那种失败看起来像"密钥丢了"，会把排查带向错误方向。
        byte[] dek = keyStore.unwrap(keyStore.wrappedOf(subject, env.dekVersion())
                .orElseThrow(() -> new com.diaoyuanyun.dy.crypto.key.SubjectKeyNotFoundException(
                        subject.display() + " 不存在 DEK 版本 v" + env.dekVersion()
                                + " —— 密文指向一个本存储没有的密钥版本")));

        // 【第 3 步】按信封里的算法位取实现 —— 算法换代后老数据仍可读。
        AlgorithmProvider provider = algorithms.resolve(env.algorithmId());
        byte[] aad = AadBinding.of(subject, fieldName, env.dekVersion());
        return provider.decrypt(dek, env.nonce(), env.ciphertext(), aad);
    }

    /** 当前写入所用的算法标识（供诊断端点 / 证据输出）。 */
    public String writeAlgorithmId() {
        return writeAlgorithmId;
    }
}