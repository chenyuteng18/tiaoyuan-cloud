package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundRetentionRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundStatementRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link RefundWorkOrderPort} 的<b>内存实现</b>（测试替身）。
 *
 * <h2>为什么需要它，而不是直接连真库</h2>
 * 与 {@code InMemoryRefundReceiptPort} 同一条理由，但这里多一层收益：
 * <ul>
 *   <li><b>可断言性</b>：本域最关键的几条性质（"豁免挽留的两类工单不得写挽留记录"
 *       "代录人不得审批""归档后只读"）只有能在<b>不起容器、不连库</b>的前提下反复跑，
 *       才会被人真的反复跑；</li>
 *   <li><b>把 RLS 的功劳与业务纪律分开</b>：真库测试里"查不到"可能是 RLS 生效、
 *       也可能是业务逻辑正确。本实现<b>不做</b>租户隔离（真库才有），
 *       于是"查不到"一定是业务逻辑的结果 —— 两者各自被独立断言。</li>
 * </ul>
 *
 * <h2>🛑 唯一一个 update 动作，且它<b>复刻</b>了仓储的乐观并发语义</h2>
 * {@link #updateOutcome} 必须与 JDBC 实现行为一致（不匹配即抛 4001），
 * 否则会出现"内存测试绿、真库红"这类最难查的分叉 ——
 * 而分叉的那一侧恰好是并发正确性。
 *
 * <h2>🛑 时间戳由本实现模拟库层的 {@code DEFAULT now()}</h2>
 * {@code retention.created_at} 与 {@code refund_statement.recorded_at} 在真库里
 * 一个是库生成（createdAt）、一个由调用方给定（recordedAt）。
 * 本实现据此分派：挽留的 {@code createdAt} 一律用 {@code Instant.now()} 覆盖传入值
 * （模拟库的 DEFAULT），原话的 {@code recordedAt} 沿用传入值。
 * 若这里也照抄传入值，"写路径不得自带时间"这条纪律就无法在内存里被发现。
 */
public class InMemoryRefundWorkOrderPort implements RefundWorkOrderPort {

    private final Map<UUID, RefundWorkOrderRow> workOrders = new LinkedHashMap<>();
    private final Map<UUID, List<RefundRetentionRow>> retentions = new LinkedHashMap<>();
    private final Map<UUID, List<RefundStatementRow>> statements = new LinkedHashMap<>();

    /** 自证计数：写路径被调用过几次（供"某条路径绝不写入"类断言使用）。 */
    private int workOrderInserts;
    private int outcomeUpdates;
    private int retentionInserts;
    private int statementInserts;

    // ==================================================================
    // 工单
    // ==================================================================

    @Override
    public void insertWorkOrder(String tenantId, RefundWorkOrderRow row) {
        workOrderInserts++;
        workOrders.put(row.refundId(), row);
    }

    @Override
    public RefundWorkOrderRow findWorkOrder(String tenantId, UUID refundId) {
        return workOrders.get(refundId);
    }

    /**
     * 推进结局 —— <b>行为必须与 JDBC 实现一致</b>。
     *
     * <p>五条与真库对齐的语义（缺任何一条，测试就发现不了对应的缺陷）：
     * <ol>
     *   <li>目标结局 = 期望结局 → 5001（真库同值写在乐观并发下会静默命中自己）；</li>
     *   <li>目标结局 = 归档 → 5001（真库拒绝经 updateOutcome 抵达归档 —— 归档要走收口路径）；</li>
     *   <li>目标结局 = 继续 而当前已终止 → 5001（<b>逆转</b>：真库的 UPDATE 虽只校验期望值，
     *       但"只向前"是本端口的契约声明，无机械约束即为一句空话）；</li>
     *   <li>当前已是归档 → 4001（真库是 {@code AND outcome &lt;&gt; '归档'} 命中 0 行）；</li>
     *   <li>当前结局 ≠ 期望值 / 工单不存在 → 4001（真库是 {@code WHERE outcome = ?} 命中 0 行）。</li>
     * </ol>
     * 🛑 若这里只做 {@code put}，测试就永远发现不了"归档后仍可写""同值写静默成功"的缺陷。
     */
    @Override
    public void updateOutcome(String tenantId, UUID refundId,
                              RefundOutcome expectedOutcome, RefundOutcome nextOutcome) {
        outcomeUpdates++;
        if (expectedOutcome == nextOutcome) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "[内存替身] 目标结局与期望结局相同（" + nextOutcome.code() + "）—— "
                            + "一次不改动任何东西的写入在乐观并发下会静默命中自己");
        }
        if (nextOutcome.isClosed()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "[内存替身] 不得用 updateOutcome 推进到『归档』—— 归档是收口动作，"
                            + "须走归档路径以同时落 case_archive 与结案清单校验");
        }
        if (nextOutcome == RefundOutcome.CONTINUE) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "[内存替身] 不得把工单退回『继续』—— 本端口只向前推进。"
                            + "一次终止结论被撤销在退款场景里意味着一次审批结论被静默抹掉");
        }
        RefundWorkOrderRow cur = workOrders.get(refundId);
        if (cur == null || cur.outcome() != expectedOutcome || cur.outcome().isClosed()) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "[内存替身] 工单结局推进失败：期望 " + expectedOutcome.code()
                            + "，当前 " + (cur == null ? "（不存在）" : cur.outcome().code()));
        }
        workOrders.put(refundId, withOutcome(cur, nextOutcome));
    }

    /**
     * 测试专用：把工单<b>直接</b>置为归档态，模拟生产侧的归档收口路径。
     *
     * <h2>🛑 它为什么必须存在，且只在测试替身里</h2>
     * 生产侧的归档动作（落 {@code case_archive} + 结案清单校验）尚未落地
     * （见 {@code RefundWorkOrderService} 的"本类不做的事"表）。于是"归档后只读"
     * 这条性质<b>在测试里无从构造</b> —— 而它是"全部强制归档"的下半句。
     * 本方法站在那条未来路径的位置上，让"归档后拒写"可以被真正断言，
     * 而不是靠 {@code RetentionPolicy.assertWritable} 的单元调用间接推想。
     *
     * <p>它<b>刻意不</b>放进 {@link RefundWorkOrderPort}：端口方法集被钉死为 8 个，
     * 加一个"谁都置归档"的方法会让生产实现者也拿到它。
     */
    public void markArchivedForTest(UUID refundId) {
        RefundWorkOrderRow cur = workOrders.get(refundId);
        if (cur == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "[内存替身] 待归档工单不存在: " + refundId);
        }
        workOrders.put(refundId, withOutcome(cur, RefundOutcome.ARCHIVED));
    }

    // ==================================================================
    // 挽留
    // ==================================================================

    @Override
    public void insertRetention(String tenantId, RefundRetentionRow row) {
        retentionInserts++;
        // 🛑 模拟库层 DEFAULT now()：覆盖传入的 createdAt。
        //    这不是"顺手写的"—— 它使"写路径自带时间"这件事在内存里也能被发现。
        Instant dbTime = Instant.now();
        RefundRetentionRow stored = new RefundRetentionRow(
                row.retentionId(), row.refundId(), row.attempts(), row.scriptVersion(),
                row.result(), row.operatorId(), row.analysisJson(), row.communicationJson(),
                dbTime, row.createdBy());
        retentions.computeIfAbsent(row.refundId(), k -> new ArrayList<>()).add(stored);
    }

    @Override
    public List<RefundRetentionRow> findRetentions(String tenantId, UUID refundId) {
        return List.copyOf(retentions.getOrDefault(refundId, List.of()));
    }

    // ==================================================================
    // 客户原话
    // ==================================================================

    @Override
    public void insertStatement(String tenantId, RefundStatementRow row) {
        statementInserts++;
        statements.computeIfAbsent(row.refundId(), k -> new ArrayList<>()).add(row);
    }

    @Override
    public List<RefundStatementRow> findStatements(String tenantId, UUID refundId) {
        List<RefundStatementRow> list = new ArrayList<>(
                statements.getOrDefault(refundId, List.of()));
        list.sort(Comparator.comparing(RefundStatementRow::recordedAt)
                .thenComparing(r -> r.statementId().toString()));
        return List.copyOf(list);
    }

    @Override
    public RefundStatementRow findLatestStatement(String tenantId, UUID refundId) {
        List<RefundStatementRow> list = findStatements(tenantId, refundId);
        // 🛑 与真库的 ORDER BY recorded_at DESC, statement_id DESC LIMIT 1 同一口径
        //    （即"排序后取末元素"）。若这里改成"取第一个插入的"，两处口径就分叉了。
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    // ==================================================================
    // 自证
    // ==================================================================

    public int totalWorkOrders() {
        return workOrders.size();
    }

    public int totalRetentions() {
        return retentions.values().stream().mapToInt(List::size).sum();
    }

    public int totalStatements() {
        return statements.values().stream().mapToInt(List::size).sum();
    }

    public int workOrderInsertCount() {
        return workOrderInserts;
    }

    public int outcomeUpdateCount() {
        return outcomeUpdates;
    }

    public int retentionInsertCount() {
        return retentionInserts;
    }

    public int statementInsertCount() {
        return statementInserts;
    }

    private static RefundWorkOrderRow withOutcome(RefundWorkOrderRow r, RefundOutcome next) {
        return new RefundWorkOrderRow(
                r.refundId(), r.customerId(), r.entry(), r.route(), r.liableStoreId(),
                r.reasonCode(), r.requestedAt(), r.requestedAtClaimed(), r.requestedAtSource(),
                r.requestedAtSourceRef(), r.recordedAt(), r.recordingDelayH(), r.slaDueAt(),
                next, r.amountSplitJson(), r.amountBasis(), r.createdBy());
    }
}