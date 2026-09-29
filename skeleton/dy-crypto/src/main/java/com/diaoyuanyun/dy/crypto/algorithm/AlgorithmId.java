package com.diaoyuanyun.dy.crypto.algorithm;

/**
 * 加密算法的<b>标识</b>（不是实现）。
 *
 * <h2>为什么算法必须是一个"值"而不是调用点上的字面量</h2>
 * 本任务 DoD ⑤ 要求"预留加密算法可替换位"，而 ADR 十五 <b>H.4-1</b> 把
 * "国密 SM4 是否强制"标为 <b>⚠️ 待测评机构确认</b>，因此<b>不得写死</b>。
 * 若把 {@code "AES/GCM/NoPadding"} 写在 {@code FieldCipher} 里，那么 SM4 结论一旦回来，
 * 需要改动的是业务调用链上的代码 —— 而每改一处就多一次"只改了一半"的机会。
 *
 * <p>把算法收进本枚举后，"用什么算法"变成<b>数据</b>：它随密文一起落进信封
 * （{@link com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope}），解密时按信封里那个值
 * 去 {@link AlgorithmRegistry} 取实现。调用点不含任何算法字面量。
 *
 * <h2>⚠️ 未确认项的处置纪律</h2>
 * {@link #SM4_GCM} 以 {@link AlgorithmStatus#PENDING_CONFIRMATION} 登记：
 * 它在枚举里<b>可见</b>（所以"待确认"这件事在代码里有一等公民的位置，不会只存在于文档里），
 * 但它<b>没有实现</b>。{@link AlgorithmRegistry} 对它一律 fail-closed 抛
 * {@link PendingAlgorithmConfirmationException}，<b>绝不静默回退到 AES</b> ——
 * 静默回退是这类"待确认项"最常见的翻车方式：测评机构问"你们用的是什么"，
 * 而代码说"我们以为是 SM4，其实是 AES"。
 *
 * <p>本枚举的登记依据：架构规格书 §8.3 / §8.4、ADR-12 第十二节、ADR 十五 H.4-1。
 */
public enum AlgorithmId {

    /**
     * 默认算法。选择理由：JDK 原生 AEAD（无需第三方密码库）、认证加密（篡改可检出）、
     * 256 位密钥。它是<b>当前可用的默认</b>，不是"已确认的最终选型" ——
     * SM4 结论回来后可能被替换或并存。
     */
    AES_256_GCM("AES-256-GCM", AlgorithmStatus.IMPLEMENTED, 32, 12),

    /**
     * ⚠️ <b>待测评机构确认</b>（ADR 十五 H.4-1）—— 是否强制国密 SM4 <b>未裁定</b>。
     *
     * <p>保留在枚举里的目的有两个：
     * <ol>
     *   <li>让"未确认"成为代码里可被断言的事实（测试可以断言对它 fail-closed，
     *       而不是靠文档里一句话约束未来的人）；</li>
     *   <li>让结论落地时的改动面收敛为"新增一个 {@code AlgorithmProvider} 并在
     *       {@link AlgorithmRegistry#standard()} 注册"，业务调用点零改动。</li>
     * </ol>
     *
     * <p>本枚举<b>不含</b>结论，也不含"SM4 会/不会落地"的猜测 —— 只有"待确认"这一状态。
     */
    SM4_GCM("SM4-GCM", AlgorithmStatus.PENDING_CONFIRMATION, 16, 16);

    private final String id;
    private final AlgorithmStatus status;
    private final int keyBytes;
    private final int nonceBytes;

    AlgorithmId(String id, AlgorithmStatus status, int keyBytes, int nonceBytes) {
        this.id = id;
        this.status = status;
        this.keyBytes = keyBytes;
        this.nonceBytes = nonceBytes;
    }

    /** 落进信封的算法标识串。约束：不含信封分隔符 {@code ':'}，见 {@code CipherEnvelope}。 */
    public String id() {
        return id;
    }

    public AlgorithmStatus status() {
        return status;
    }

    /** 密钥长度（字节）。DEK 与 KEK 都按它校验，防止"短密钥静默可用"。 */
    public int keyBytes() {
        return keyBytes;
    }

    /** 单次加密使用的 nonce 长度（字节）。 */
    public int nonceBytes() {
        return nonceBytes;
    }

    /** 按标识串查找；未登记返回 {@code null}（由调用方 fail-closed，不在此处抛，避免掩盖真因）。 */
    public static AlgorithmId of(String id) {
        for (AlgorithmId a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}