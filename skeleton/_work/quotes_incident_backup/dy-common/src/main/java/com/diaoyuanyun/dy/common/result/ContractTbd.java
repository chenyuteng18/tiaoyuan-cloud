package com.diaoyuanyun.dy.common.result;

/**
 * 契约里的 {@code TBD} 占位字面量 —— 硬纪律 #6「TBD 不得填数」的唯一字面源。
 *
 * <h2>为什么它值得单独一个类</h2>
 * {@code TBD} 在本项目<b>不是</b>随手写的临时值，而是一条<b>被契约冻结的对外语义</b>：
 * 契约 §4.2 把 {@code retention_window_days} 的类型写成 {@code int | TBD}，
 * 明定「未探测到 → 返回 {@code TBD}，<b>不得回落为硬编码值</b>」；
 * 手环专章 TL;DR 亦写「N / s / f 一律 <b>TBD 占位</b>」；开发清单 §七把
 * 「TBD 不得填数」列为 10 条硬纪律之一。
 *
 * <p>把它集中在一处，解决三个具体问题：
 * <ol>
 *   <li><b>字面漂移</b>：若各域各写一份，某个域写成 {@code "tbd"} / {@code "TBD "}，
 *       客户端按键值匹配会静默失败（拿不到该分支，既不报错也不显示 TBD）；</li>
 *   <li><b>可扫描</b>：构建期 / 回归脚本 grep 该常量的引用点，即可枚举"当前还有哪些
 *       对外字段处于未探测状态"，不必全文搜字符串；</li>
 *   <li><b>防"顺手补值"</b>：常量是 {@link String} 而非数字 ——
 *       任何算术一旦碰它就会立刻失败，而不是悄悄按某个默认天数回溯。</li>
 * </ol>
 *
 * <h2>它不是什么</h2>
 * 它不是"默认值"。🛑 严禁把 {@code TBD} 当成"取不到时先用这个顶着"的兜底 ——
 * 那正是硬纪律 #6 要防的形态。它的语义是<b>"诚实地说：这个数我现在不知道"</b>。
 */
public final class ContractTbd {

    /** 契约冻结的对外字面量。大小写敏感，不得变体。 */
    public static final String TBD = "TBD";

    private ContractTbd() {
    }

    /** 判断某值是否为 TBD 占位（供回归用例与客户端契约比对使用）。 */
    public static boolean isTbd(Object value) {
        return TBD.equals(value);
    }
}