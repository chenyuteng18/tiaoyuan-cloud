package com.diaoyuanyun.dy.crypto.algorithm;

/**
 * 算法的<b>登记状态</b> —— 与国家安全测评机构的确认进度对齐。
 *
 * <p>存在的理由：本任务有一条不该被代码悄悄抹平的事实 ——
 * <b>国密 SM4 是否强制尚未确认</b>（ADR 十五 H.4-1，标 ⚠️）。
 * 如果代码里只有"可用的算法"一个概念，那么"待确认"就只活在文档里；
 * 文档会被读漏，而枚举不会 —— 引用一个 {@code PENDING_CONFIRMATION} 的算法
 * 会当场抛异常，这是可执行的约束。
 */
public enum AlgorithmStatus {

    /** 已实现、可被 {@link AlgorithmRegistry} 解析并用于加解密。 */
    IMPLEMENTED,

    /**
     * ⚠️ <b>待外部机构确认</b>：结论未回来之前，<b>任何</b>加解密路径都不得使用它。
     *
     * <p>登记为"待确认"而不是"未实现"是有意的措辞区别：
     * "未实现"暗示"只是还没写，随手补上即可"；"待确认"指的是
     * <b>写不写由外部裁定决定，工程侧无权自行决定</b>。
     */
    PENDING_CONFIRMATION
}