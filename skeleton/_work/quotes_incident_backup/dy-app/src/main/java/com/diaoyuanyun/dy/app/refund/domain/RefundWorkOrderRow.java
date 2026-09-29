package com.diaoyuanyun.dy.app.refund.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 一张退款工单（{@code refund} 的一行）—— 仓储与领域<b>共用</b>的值对象。
 *
 * <h2>🛑 它承载的是 {@link RefundEntry}，不是 {@code String}</h2>
 * 库里 {@code entry} 列落的是 {@code 'A 门店代录'}（<b>有空格</b>），而契约出入站字面是
 * {@code 'A门店代录'}（<b>无空格</b>）。两套字面都已冻结、都不是笔误（见
 * {@link RefundEntry} 的类注释）。
 *
 * <p>若本 record 把 {@code entry} 定为 {@code String}，那么"读出的是哪一套"就没人管：
 * 一个从库里读回的 {@code 'A 门店代录'} 被原样渲染进响应体时，端侧逐字比对会红 ——
 * 而红的那个端侧测的正是壳层，真正的问题（两套字面在内存里混流）反而被掩盖。
 * 故本 record 用枚举把<b>方向</b>钉死：进来的永远是枚举，出去的由
 * {@link RefundEntry#contractCode()} / {@link RefundEntry#dbCode()} 各取所需。
 *
 * <h2>🛑 三个时间字段必须同在（P0-19 C1-2）</h2>
 * <pre>
 *   requestedAt        最早且可核实 —— 24h 计时的【唯一】起点
 *   requestedAtClaimed 客户主张 —— 仅留存，【永不】进 24h 计时
 *   requestedAtSource  来源标注 —— 无来源标注的 requested_at 视为不可核实
 * </pre>
 * 三者若被折成一个 {@code Instant}，"客户说了更早的时间"与"我们核实到的更早时间"
 * 就再也分不开，而 {@code RefundRecordingPolicy.assertBundleCanBePersisted} 的五条
 * 拒绝理由全部依赖这三者各自独立存在。故本 record 一个不省。
 *
 * <p>🛑 三个时间字段<b>全部可空</b>（{@code null} = 尚未归一）。这不是"允许缺失"：
 * 入口 B（首周期双不达标，系统自动触发）根本不存在"客户什么时候提出的"这一问，
 * 其 {@code requested_at} 合法地为空。故落库前的判定由
 * {@code RefundRecordingPolicy} 按入口分别做，而不是由本 record 一刀切拒收。
 *
 * @param refundId            工单主键
 * @param customerId          客户（FK {@code customer}）
 * @param entry               入口（{@code A 门店代录} / {@code B 首周期}，双字面见 {@link RefundEntry}）
 * @param route               通路（{@code 履约类} / {@code 效果类}）
 * @param liableStoreId       责任主体 = 签约店（FK {@code store}）
 * @param reasonCode          原因码（必填；未记录原因不可结案）
 * @param requestedAt         客户提出时间（最早且可核实；24h 计时唯一起点；可空）
 * @param requestedAtClaimed  客户主张时间（仅留存，永不进计时；可空）
 * @param requestedAtSource   {@code requestedAt} 的来源标注（可空；有值时必须与 requestedAt 同时有值）
 * @param requestedAtSourceRef 来源凭据引用（客户自证时必填；可空）
 * @param recordedAt          代录时间（库默认 {@code now()}；写入路径必须显式给值）
 * @param recordingDelayH     代录延迟小时数（小数；>24h → 异常名单 + 自动升级）
 * @param slaDueAt            SLA 倒计时截止（可空）
 * @param outcome             结局（{@code 继续 / 终止 / 归档}）
 * @param amountSplitJson     损失按次数分摊的明细（JSONB 原样文本；可空）
 * @param amountBasis         让步金额核定依据（可空 —— 履约类规则直退无须依据）
 * @param createdBy           创建者标识（审计用）
 */
public record RefundWorkOrderRow(
        UUID refundId,
        UUID customerId,
        RefundEntry entry,
        RefundRoute route,
        UUID liableStoreId,
        RefundReasonCode reasonCode,
        Instant requestedAt,
        Instant requestedAtClaimed,
        RequestedAtSource requestedAtSource,
        String requestedAtSourceRef,
        Instant recordedAt,
        BigDecimal recordingDelayH,
        Instant slaDueAt,
        RefundOutcome outcome,
        String amountSplitJson,
        AmountBasis amountBasis,
        String createdBy) {

    /**
     * 该工单是否已归一 {@code requested_at}（三字段的"起点侧"齐备）。
     *
     * <p>🛑 判据是「{@code requestedAt} 与 {@code requestedAtSource} <b>同时</b>有值」——
     * 只看前者会让"时间照填、来源不填"这种写法通过，
     * 而 P0-19 C1-2 拆三字段的全部收益恰好可以被这一种写法一次抹掉
     * （见 {@code RefundRecordingPolicy.RecordingBundle} 的第 2 条拒绝理由）。
     */
    public boolean hasResolvedRequestedAt() {
        return requestedAt != null && requestedAtSource != null;
    }

    /**
     * 是否属"须走 24h 代录计时"的工单。
     *
     * <p>只在入口 A 成立：入口 B 由首周期双不达标自动触发，不存在"客户何时提出"。
     * 把它写成 {@code true} 会让入口 B 的工单被算进"超 24h 未代录"的异常名单 ——
     * 而它根本没有一个可比的起点，报表上会出现一批永远超时的单子。
     */
    public boolean subjectToDeputyWindow() {
        return entry != null && entry.isStoreDeputyEntry();
    }
}