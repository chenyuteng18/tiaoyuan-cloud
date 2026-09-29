package com.diaoyuanyun.dy.app.refund.service;

import com.diaoyuanyun.dy.app.refund.domain.ReceiptInterpreter;
import com.diaoyuanyun.dy.app.refund.domain.ReceiptState;
import com.diaoyuanyun.dy.app.refund.domain.RefundOfflineNoticeRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P0-19 回执服务 —— 把 {@link ReceiptInterpreter} 的两段式决策接到<b>真实推送</b>与留痕账本上，
 * 并守住两条 P0-19 反复强调的硬约束。
 *
 * <h2>🛑 硬约束一：未授权路径下，推送器<b>一次都不被调用</b></h2>
 * {@link ReceiptInterpreter} 用"两段式 API 形状"保证了"推送之后判未授权"没有可写出的调用序列。
 * 但形状本身还留着一个可被绕过的口子：实现者可以<b>先调推送、再调第二段</b>，
 * 把"未授权"这一态<b>永远不给出去</b>（因为第一段被跳过了）。
 * 那种实现下库里全是"已推送 / 推送失败"两种态，覆盖率看着正常 ——
 * 而这正是 P0-19 说的"分母缺一块、系统性偏高且看不出来"。
 *
 * <p>故本服务的推送路径是一个<b>函数式端口</b> {@link PushAttempt}：
 * 调用顺序由本类的代码决定，而不是由调用方决定。于是有一条可被断言的事实：
 * <pre>
 *   未授权路径：attempt 的调用次数恒为 0（推送事件根本没发生 —— 逐字成立，不是比喻）
 *   已授权路径：attempt 恰被调用 1 次
 * </pre>
 * 这不是"我写了 if 所以对"，而是"它没有第二次被调用的机会"。
 *
 * <h2>🛑 硬约束二：漏发视同未回执 —— 但"未告知"是一个<b>待办</b>，不是一个写入期错误</h2>
 * P0-19：「不得因订阅失败而漏发回执（漏发视同未回执）」。
 * 若把这条做成<b>写入期</b>断言（"没有 offline_notice 就不许落库"），
 * 它会造出一个不可满足的顺序：告知是<b>人工动作</b>，而人工动作发生在推送失败<b>之后</b>。
 * 强求同时，实现者只能选一条更坏的路 —— 先写一条假的告知记录再落回执。
 *
 * <p>故本类把它做成<b>两件事</b>：
 * <ol>
 *   <li>{@link #appendOfflineNotice} 之后，{@link #pendingFallbackObligations} 可列出
 *       "哪些工单欠一次人工告知" —— 让职责显式、可派单、可统计；</li>
 *   <li>{@link #assertAllFallbacksSatisfied} 是<b>结案门禁</b>：工单归档 / 出覆盖率报表前调用，
 *       欠告知即抛。断言落在"能判定"的时刻，而不是"还没发生"的时刻。</li>
 * </ol>
 * 这样"漏发"从一个<b>没人看得见的未来状态</b>，变成一个<b>当下就能列出来的待办清单</b> ——
 * 而 P0-19 的原意（不得让报表显示齐备而客户手里空无一物）正是靠这份清单守住的。
 *
 * <h2>为什么覆盖率走仓储的一条 SQL，而不是"先查出全部行再数"</h2>
 * 见 {@link RefundReceiptPort#countByState} 的注释：三态必须来自<b>同一次查询</b>，
 * 否则分子分母来自不同时点，报表在工单持续产生时永远是漂移的。
 */
@Service
public class RefundReceiptService {

    /** 推送通道的模板 ID 由调用方给出；这里只留一个"必须非空"的守卫（见 P0-19「回执独占一个模板 ID」）。 */
    private final RefundReceiptPort ledger;

    public RefundReceiptService(RefundReceiptPort ledger) {
        this.ledger = ledger;
    }

    // ==================================================================
    // 一、推送端口（顺序由本服务决定，不由调用方决定）
    // ==================================================================

    /**
     * 一次真实推送尝试的结果。
     *
     * @param succeeded     是否成功
     * @param failureReason 失败原因（{@code succeeded=false} 时必填）
     * @param pushedAt      成功时刻（{@code succeeded=true} 时必填）
     */
    public record PushOutcome(boolean succeeded, String failureReason, Instant pushedAt) {
    }

    /**
     * 推送端口 —— <b>本服务持有"何时调用它"的决定权</b>。
     *
     * <p>把它做成参数（而不是让调用方自己调推送再传结果进来）是本类最关键的一个设计：
     * 传结果进来的形态下，"先推送、后判额度"是完全合法的调用序列，
     * 而那种序列会让"未授权"这一态在库里永不出现。
     */
    @FunctionalInterface
    public interface PushAttempt {
        PushOutcome attempt(String templateId);
    }

    /**
     * 执行一次回执推送并留痕（<b>两段式</b>：先判额度、再推送）。
     *
     * <h2>调用序列（本方法是唯一入口）</h2>
     * <pre>
     *   ① decideBeforePush(quota)
     *        quota ≤ 0  → 落一条『未授权（转线下）』，**return，push 从未被调用**
     *        quota &gt; 0  → 返回 null，继续
     *   ② push.attempt(templateId)          ← 到这里推送事件才第一次可能发生
     *   ③ decideAfterPush(...)              ← 只有 PUSHED / PUSH_FAILED 两个出口
     *   ④ 落库（INSERT only）
     * </pre>
     *
     * <p>返回的记录携带 {@code offlineFallbackRequired}：为真时该工单欠一次人工告知，
     * 由 {@link #pendingFallbackObligations} 汇总、由 {@link #assertAllFallbacksSatisfied} 收口。
     */
    public ReceiptRecord pushAndRecord(String tenantId,
                                       UUID refundId,
                                       long subscriptionQuota,
                                       String templateId,
                                       PushAttempt push,
                                       UUID operatorId,
                                       String createdBy) {
        RefundReceiptPort.validateTenantId(tenantId);
        requireUuid(refundId, "工单标识");
        requireUuid(operatorId, "操作人");
        requireNonBlank(templateId, "订阅消息模板 ID");
        if (push == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "推送端口为空 —— 未授权路径下推送器不该被调用，但已授权路径必须有一个可调用的推送器；"
                            + "传 null 会让『额度充足』的工单静默不推送而库里没有任何记录");
        }

        Instant decidedAt = Instant.now();

        // ---- ① 第一段：唯一的 UNAUTHORIZED 出口。quota ≤ 0 时 push 永不被调用 ----
        ReceiptInterpreter.ReceiptDecision before =
                ReceiptInterpreter.decideBeforePush(subscriptionQuota, decidedAt);
        if (before != null) {
            ReceiptInterpreter.assertPersistable(before);
            ledger.appendReceipt(tenantId, toRow(refundId, before, operatorId, createdBy));
            return new ReceiptRecord(before, true, 0);
        }

        // ---- ② 推送（到这里，推送事件才第一次可能发生）----
        PushOutcome outcome = push.attempt(templateId);
        if (outcome == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "推送端口返回 null —— 无法判定本次推送的结果。"
                            + "不得把 null 当成失败或成功：那会让一次未观测到的推送"
                            + "被静默记成三态之一，而三态都进覆盖率分母");
        }

        // ---- ③ 第二段：只有 PUSHED / PUSH_FAILED 两个出口 ----
        ReceiptInterpreter.ReceiptDecision after = ReceiptInterpreter.decideAfterPush(
                outcome.succeeded(), outcome.failureReason(), outcome.pushedAt(),
                decidedAt, templateId);
        ReceiptInterpreter.assertPersistable(after);

        // ---- ④ 落库（INSERT only）----
        ledger.appendReceipt(tenantId, toRow(refundId, after, operatorId, createdBy));

        boolean needsFallback = after.requiresOfflineFallback();
        return new ReceiptRecord(after, needsFallback, 1);
    }

    /** 一次回执落库的结果。 */
    public record ReceiptRecord(
            ReceiptInterpreter.ReceiptDecision decision,
            boolean offlineFallbackRequired,
            int pushAttemptedTimes) {

        /** 本记录是否欠一次人工告知。 */
        public boolean owesOfflineNotice() {
            return offlineFallbackRequired;
        }
    }

    // ==================================================================
    // 二、转线下告知（人工动作，独立留痕）
    // ==================================================================

    /**
     * 追加一条转线下告知留痕。
     *
     * <p>校验"该工单确实存在需要兜底的未授权 / 推送失败回执" —— 而不是无条件接受。
     * 理由：一条对"已推送成功"的工单写下的线下告知，是一次<b>无来源的动作</b>，
     * 它会让 {@link #pendingFallbackObligations} 的分母口径失真，
     * 也会在复盘时给出"我们做了线下的电话告知"这一<b>没有对应漏发</b>的证据。
     * 若确实需要额外触达客户（如客户要求纸质回执），那是一次独立的服务动作，
     * 应当走别的记录路径，而不是挤进这张表。
     */
    public void appendOfflineNotice(String tenantId,
                                    UUID refundId,
                                    String channel,
                                    UUID operatorId,
                                    String note,
                                    String createdBy) {
        RefundReceiptPort.validateTenantId(tenantId);
        requireUuid(refundId, "工单标识");
        requireUuid(operatorId, "操作人");
        String legalChannel = RefundOfflineNoticeRow.requireLegalChannel(channel);

        if (!needsFallback(tenantId, refundId)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "该工单没有『未授权（转线下）』或『推送失败』的回执，不接受转线下告知留痕 —— "
                            + "本表是【漏发兜底】的凭证（P0-19「不得因订阅失败而漏发回执」），"
                            + "不是通用触达记录。对一次成功推送的工单写线下告知，"
                            + "会让『欠告知清单』的分母失真，并在复盘时给出一次没有对应漏发的兜底证据");
        }

        ledger.appendOfflineNotice(tenantId, new RefundOfflineNoticeRow(
                UUID.randomUUID(), refundId, legalChannel, Instant.now(),
                operatorId, note, createdBy));
    }

    /** 该工单是否存在需要兜底的回执态（未授权 / 推送失败）。 */
    public boolean needsFallback(String tenantId, UUID refundId) {
        return ledger.findReceipts(tenantId, refundId).stream()
                .anyMatch(r -> ReceiptInterpreter.requiresOfflineFallback(r.state()));
    }

    // ==================================================================
    // 三、欠告知清单与结案门禁
    // ==================================================================

    /** 一条待办的兜底义务。 */
    public record FallbackObligation(UUID refundId, ReceiptState state, Instant decidedAt) {
    }

    /**
     * 列出"欠一次人工告知"的工单（P0-19「漏发视同未回执」的可执行形态）。
     *
     * <p>取数口径刻意用 {@code countByState} 之外的<i>逐工单</i>视图 ——
     * 因为待办是<b>派给某个门店、某个人的</b>，而不是一个聚合数字。
     * 一个"本店有 3 张工单欠告知"的数字无法派单，而工单 ID 列表可以。
     */
    public List<FallbackObligation> pendingFallbackObligations(String tenantId, List<UUID> refundIds) {
        RefundReceiptPort.validateTenantId(tenantId);
        if (refundIds == null || refundIds.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "待办清单的工单集合为空 —— 不得返回空清单冒充『无欠告知』："
                            + "一个没传工单的查询与一个真的没有欠告知的店，在报表上长得一样");
        }
        List<FallbackObligation> out = new ArrayList<>();
        for (UUID id : refundIds) {
            requireUuid(id, "工单标识");
            List<RefundReceiptRow> receipts = ledger.findReceipts(tenantId, id);
            boolean hasNotice = ledger.hasOfflineNotice(tenantId, id);
            if (hasNotice) {
                continue;   // 已有一次人工告知 → 该工单的义务已履行
            }
            receipts.stream()
                    .filter(r -> ReceiptInterpreter.requiresOfflineFallback(r.state()))
                    .findFirst()
                    .ifPresent(r -> out.add(new FallbackObligation(id, r.state(), r.decidedAt())));
        }
        return out;
    }

    /**
     * <b>结案门禁</b>：断言该工单所有须兜底的回执都已有一次人工告知。
     *
     * <p>🛑 它在"能判定"的时刻被调用（归档 / 出报表前），而不是写入期 ——
     * 理由见类注释"硬约束二"。把断言放在写入期会造出一个不可满足的顺序，
     * 而实现者只能靠写假记录来绕过它。
     *
     * <p>报错消息刻意点出「漏发视同未回执」的出处，而不是只说"缺一条记录"：
     * 后者会让下一个人以为这是一条可以放宽的完整性检查。
     */
    public void assertAllFallbacksSatisfied(String tenantId, UUID refundId) {
        RefundReceiptPort.validateTenantId(tenantId);
        requireUuid(refundId, "工单标识");
        List<RefundReceiptRow> receipts = ledger.findReceipts(tenantId, refundId);
        boolean hasNotice = ledger.hasOfflineNotice(tenantId, refundId);
        for (RefundReceiptRow r : receipts) {
            if (ReceiptInterpreter.requiresOfflineFallback(r.state())) {
                ReceiptInterpreter.assertOfflineFallbackRecorded(r.state(), hasNotice);
            }
        }
    }

    // ==================================================================
    // 四、覆盖率（P1-10 口径 · 三数同显）
    // ==================================================================

    /**
     * 覆盖率报告（三态一次出，缺态补零）。
     *
     * <p>{@code null} 时间窗 = 全量。若本期无任何回执记录，
     * {@link ReceiptInterpreter.CoverageReport#assertThreeNumbersShown()} 会被
     * 报表层调用并抛"无样本" —— 而不是显示 0% 或 100%。
     */
    public ReceiptInterpreter.CoverageReport coverage(String tenantId, Instant from, Instant to) {
        Map<ReceiptState, Long> dist = ledger.countByState(tenantId, from, to);
        return ReceiptInterpreter.coverageOfDist(dist);
    }

    /** 全部时间内的覆盖率。 */
    public ReceiptInterpreter.CoverageReport coverage(String tenantId) {
        return coverage(tenantId, null, null);
    }

    /** 三态分布（供看板原样展示三个计数 —— P1-10「三数同显」的取数入口）。 */
    public Map<ReceiptState, Long> stateDistribution(String tenantId, Instant from, Instant to) {
        Map<ReceiptState, Long> dist = ledger.countByState(tenantId, from, to);
        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        for (ReceiptState s : ReceiptState.values()) {
            out.put(s, dist.getOrDefault(s, 0L));
        }
        return out;
    }

    // ==================================================================
    // 五、内部
    // ==================================================================

    private static RefundReceiptRow toRow(UUID refundId,
                                          ReceiptInterpreter.ReceiptDecision d,
                                          UUID operatorId,
                                          String createdBy) {
        return new RefundReceiptRow(
                UUID.randomUUID(), refundId, d.state(), d.channel(), d.templateId(),
                d.decidedAt(), d.pushedAt(), d.failureReason(), operatorId, createdBy);
    }

    private static void requireUuid(UUID v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + "缺失");
        }
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 必填 —— " + (name.contains("模板")
                            ? "P0-19 的『回执独占一个模板 ID』正是化解订阅额度被稀释的方式，"
                              + "不留模板 ID 则将来『模板被挪作他用 / 用量异常』无从排查"
                            : name + "不得为空"));
        }
    }
}