package com.diaoyuanyun.dy.app.refund.domain;

import java.util.UUID;

/**
 * 立案前置的「引用对象是否存在」端口（域 G1 的入参引用校验）。
 *
 * <h2>🛑 为什么需要它 —— 一个"外键会兜住"的错觉</h2>
 * {@code refund.customer_id} 与 {@code refund.liable_store_id} 在库层都是
 * {@code NOT NULL REFERENCES ...}。于是很容易得出一个结论：
 * <b>"不存在的客户/门店会被数据库拒掉，不必在应用层校验"</b>。
 *
 * <p>那个结论在功能上成立，在<b>契约上不成立</b>：外键违例以
 * {@code DataIntegrityViolationException} 冒到调用方，被全局异常处理器兜成
 * <b>500 · 9001「系统异常」</b>。而契约 G1 逐字声明的是
 * <b>{@code 400 · 1001 参数校验失败}</b>（{@code responses.'400' → ValidationFailed}）。
 * 于是"调用方填错了一个 ID"被回答成"服务端出了故障" ——
 * 调用方看到 500 会去重试、去报警、去查服务健康度，而它真正该做的是核对入参。
 *
 * <h2>🛑 为什么是本端口，而不是直接注入 {@code CustomerLedger} / {@code StoreRepository}</h2>
 * 服务层（{@code app.refund.service}）<b>不得</b>依赖仓储层（{@code app.*.repository}）——
 * 这条由 {@code ArchitectureBoundaryTest} 的 R2/R3 守着，且它不是为了好看：
 * 仓储是"带着租户上下文与 SQL 方言的实现细节"，服务层一旦直连它，
 * 换一种持久化就要改服务层。
 *
 * <p>故存在性查询在本域<b>唯一的抽象点</b>是本接口（domain 层，无实现）。
 * 由 {@code RefundWorkOrderLedger} 用 JDBC + RLS 实现它
 * （{@code SELECT EXISTS (...)} 在 {@code SET LOCAL app.tenant_id} 的事务内执行）。
 *
 * <h2>🛑 隔离由 RLS 承担，故"不存在"与"不属于你"必然合并为一个答案</h2>
 * 本接口的两个方法都<b>只</b>接收 {@code tenantId} 与目标 ID，而查询本身
 * 不带 {@code WHERE tenant_id = ?} —— 隔离完全由 {@code FORCE ROW LEVEL SECURITY} 承担
 * （ADR-02，与 {@code RefundWorkOrderLedger} 的写入路径同一套）。于是：
 * <pre>
 *   客户属于别的租户  →  RLS 下不可见  →  customerExists 返回 false
 *   客户根本不存在    →  不可见        →  customerExists 返回 false
 * </pre>
 * 两者<b>必然</b>得到同一个答案，而这正是想要的：若让它们可区分，
 * 本端点就成了一个<b>跨租户客户 ID 的探测口</b>（拿一个 ID 问一问，
 * 靠 404 与 403 的差别就能枚举出别家租户有哪些客户）。这与 G2 详情的
 * 「不区分『不存在』与『不属于你』」是同一条纪律。
 *
 * <p>⚠️ 代价是：调用方无法从响应里分辨"我打错了 ID"与"这个客户不是我的"。
 * 这是<b>刻意的取舍</b>，取舍的判据是"泄漏跨租户存在性的代价 > 一次排查便利"。
 */
public interface RefundSubjectVerifier {

    /**
     * 该客户在本租户内是否存在（隔离由 RLS 承担，见接口注释）。
     *
     * <p>🛑 本方法<b>不</b>校验客户状态（{@code active} / {@code pending} / …）：
     * "存在"与"合格"是两件事。契约 G1 的 {@code 400} 只对应"引用对象不存在"这一层；
     * 客户状态是否允许立案属另一条业务规则（未来接入时应新增一个语义不同的方法，
     * 而不是让本方法在状态不合格时返回 false —— 那会让调用方把
     * "这个客户已归档"误读成"这个客户不存在"）。
     */
    boolean customerExists(String tenantId, UUID customerId);

    /**
     * 该门店在本租户内是否存在（{@code store.store_id}）。
     *
     * <p>校验的是 {@code liable_store_id}（责任主体 = 签约店）。
     * 🛑 本方法<b>不</b>校验"这家店是不是调用方所属的店"——
     * 那是行级范围（{@code x-row-scope}）的判定，属另一层；此处只回答"这个引用指向的行存在吗"。
     */
    boolean storeExists(String tenantId, UUID storeId);

    /**
     * 测试用替身：<b>总是存在</b>。
     *
     * <p>给"不关心引用存在性"的服务层单测用（它们构造的 ID 是纯符号，
     * 没有真实 customer / store 行）。把它做成静态工厂而不是让每个测试写一遍匿名类，
     * 是为了让"这些单测<b>确实</b>绕过了存在性校验"这件事有一处可被读到的声明 ——
     * 而不是散落在十几处无从分辨的 {@code return true}。
     */
    static RefundSubjectVerifier alwaysPresent() {
        return new RefundSubjectVerifier() {
            @Override
            public boolean customerExists(String tenantId, UUID customerId) {
                return true;
            }

            @Override
            public boolean storeExists(String tenantId, UUID storeId) {
                return true;
            }
        };
    }

    /**
     * 测试用替身：<b>总是不存在</b>。
     *
     * <p>给"引用对象不存在 ⇒ 必须 400·1001 而不是 500·9001"这类用例使用。
     * 它与 {@link #alwaysPresent()} 成对存在，使那条判据可以在<b>不起容器</b>的前提下
     * 被服务层单测钉住；而"真请求形态确实是 400"由 E2E 另行钉住 —— 两者缺一，
     * 症状分别是"装配层悄悄改回 500 没人发现"与"业务层改回 500 没人发现"。
     */
    static RefundSubjectVerifier alwaysAbsent() {
        return new RefundSubjectVerifier() {
            @Override
            public boolean customerExists(String tenantId, UUID customerId) {
                return false;
            }

            @Override
            public boolean storeExists(String tenantId, UUID storeId) {
                return false;
            }
        };
    }
}