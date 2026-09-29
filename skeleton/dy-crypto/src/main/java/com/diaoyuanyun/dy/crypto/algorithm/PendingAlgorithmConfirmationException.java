package com.diaoyuanyun.dy.crypto.algorithm;

/**
 * 引用了<b>尚未确认</b>的算法（{@link AlgorithmStatus#PENDING_CONFIRMATION}）时抛出。
 *
 * <p>这是一个<b>专门的异常类型</b>而不是 {@code IllegalArgumentException}，
 * 原因是运维侧需要能区分两类失败：
 * <ul>
 *   <li>"请求了一个不存在的算法"= 配置写错了 → 改配置；</li>
 *   <li>"请求了一个还没被裁定的算法"= <b>合规前置未完成</b> → 去找测评机构，
 *       改配置没有用，因为工程侧无权自行判定。</li>
 * </ul>
 * 混成一种异常，运维看到的是"参数非法"，会去改配置，然后一直改不通。
 *
 * <p>消息里直接带上裁定出处与责任人，使得告警被谁看到都能找到下一步。
 */
public class PendingAlgorithmConfirmationException extends RuntimeException {

    private final transient AlgorithmId algorithmId;

    public PendingAlgorithmConfirmationException(AlgorithmId algorithmId, String rulingRef) {
        super("算法 " + algorithmId.id() + " 的状态为 ⚠️ 待确认（" + rulingRef + "），"
                + "在外部机构给出结论之前不得用于任何加解密路径；本实现 fail-closed，"
                + "不会静默回退到其他算法。处置方式：等待裁定并登记结论，"
                + "而非在调用点旁路。");
        this.algorithmId = algorithmId;
    }

    public AlgorithmId algorithmId() {
        return algorithmId;
    }
}