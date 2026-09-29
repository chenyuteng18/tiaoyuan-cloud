package com.diaoyuanyun.dy.app.refund.domain;

import java.util.List;
import java.util.UUID;

/**
 * 退款工单及其从属记录的<b>持久化端口</b>（DIP：服务层依赖本端口，仓储层实现它）。
 *
 * <h2>为什么它与 {@link RefundReceiptPort} 是<b>两个</b>端口，而不是一个"退款仓储"</h2>
 * 两者的<b>变更语义相反</b>：
 * <pre>
 *   refund / retention / refund_statement  = 有生命周期的工单实体（outcome 会演进）
 *   refund_receipt / refund_offline_notice = 纯追加账本（append-only，永不改写）
 * </pre>
 * 合并成一个端口会带来一个很具体的坏处：append-only 的那一半将暴露在
 * 一个"能改状态"的接口里。实现者看到同一个接口上有 {@code updateOutcome}，
 * 下一次需要"修正一条写错的回执"时就会顺手加一个 {@code updateReceipt} ——
 * 而 P0-19 逐字要求回执"随工单落库、<b>不可删除</b>"。
 *
 * <p>故两者分端口、分实现、分门禁：本端口<b>有</b> {@code updateOutcome}
 * （工单结局是业务事实，会从 {@code 继续 / 终止} 演进到 {@code 归档}），
 * 而 {@link RefundReceiptPort} <b>一个改写方法都没有</b>。
 * "哪个能改、哪个不能"由<b>接口形状</b>回答，而不是靠读注释。
 *
 * <h2>🛑 本端口有两个 {@code find*} 会把"追加序"固定下来</h2>
 * {@link #findRetentions} / {@link #findStatements} 都按<b>追加顺序</b>返回全部行，
 * 且都<b>不提供</b>"只取最新"的语义（除 {@link #findLatestStatement}）。
 * 理由：挽留过程与客户原话的<b>全过程</b>才是证据；一个只返回最新一条的接口
 * 会让"客户改过口径"这件事在数据里消失，而下一个人会以为从来只有一版。
 */
public interface RefundWorkOrderPort {

    /** 新增一张退款工单（INSERT only）。 */
    void insertWorkOrder(String tenantId, RefundWorkOrderRow row);

    /** 按 ID 取工单；不存在返回 {@code null}（由服务层转 {@code NOT_FOUND(3001)}）。 */
    RefundWorkOrderRow findWorkOrder(String tenantId, UUID refundId);

    /**
     * 推进工单结局。
     *
     * <p>🛑 只接受<b>向前</b>的演进，且 {@code 归档} 之后一律拒
     * （P0-14「归档后只读」）。方向与幂等性由 {@code RetentionPolicy.assertWritable}
     * 与 {@code archiveIfOpen} 决定，本方法<b>不自带</b>业务判定 ——
     * 仓储不判业务，否则同一条规则会有两个实现，而它们必然分叉。
     *
     * @param expectedOutcome 乐观并发：调用方读到的当前值；不匹配即抛
     *                        {@code VERSION_CONFLICT(4001)}。
     *                        它存在的理由是这条数据会被两端同时操作
     *                        （门店写挽留结果 / 总部写审批结论），
     *                        而"后写覆盖先写"在退款场景里意味着一次审批结论被抹掉。
     */
    void updateOutcome(String tenantId, UUID refundId,
                       RefundOutcome expectedOutcome, RefundOutcome nextOutcome);

    /** 追加一条挽留记录（{@code retention}）。 */
    void insertRetention(String tenantId, RefundRetentionRow row);

    /** 取某工单的全部挽留记录（<b>追加序</b>；空工单返回空列表而非 null）。 */
    List<RefundRetentionRow> findRetentions(String tenantId, UUID refundId);

    /**
     * 追加一条客户原话（{@code refund_statement}，append-only）。
     *
     * <p>更正一律追加新行并以 {@code supersedesStatementId} 声明被取代者 ——
     * 反向指针（旧行指向新行）会要求一次 UPDATE，那正是"不可编辑"被破的口子。
     */
    void insertStatement(String tenantId, RefundStatementRow row);

    /** 取某工单的全部原话（<b>追加序</b>，含被取代者 —— 证据链要的是全过程，不是只留最新）。 */
    List<RefundStatementRow> findStatements(String tenantId, UUID refundId);

    /**
     * 取某工单的<b>最新</b>一条原话（供详情页展示；{@code null} = 尚无原话）。
     *
     * <p>🛑 它<b>不</b>用 {@code findStatements(...)} 的末元素代替：
     * "最新"的判据是 {@code recorded_at}，而列表的排序键含 {@code statement_id}，
     * 两者在并发写入（同一时刻两条）时会给出不同答案。
     * 让数据库按同一口径排序，是"本地跑对了、线上顺序不同"最不容易发生的做法。
     */
    RefundStatementRow findLatestStatement(String tenantId, UUID refundId);

    /**
     * 端口方法集被钉死为 <b>8</b> 个（3 工单 + 2 挽留 + 3 原话）。
     *
     * <p>供门禁反射断言使用：新增方法必须<b>显式</b>改
     * {@code RefundPersistenceBoundaryTest} 里那条断言 ——
     * 使"给工单端口加一个回执改写入口"变成一次必须表态的改动，而不是一次顺手加方法。
     */
    int PORT_METHOD_COUNT = 8;

    /**
     * 租户标识校验（与 {@link RefundReceiptPort#validateTenantId} 同一套白名单）。
     *
     * <p>两处共用同一套正则的理由：口径分叉处最容易出现"一处放宽了、另一处没放"。
     */
    static void validateTenantId(String tenantId) {
        RefundReceiptPort.validateTenantId(tenantId);
    }
}