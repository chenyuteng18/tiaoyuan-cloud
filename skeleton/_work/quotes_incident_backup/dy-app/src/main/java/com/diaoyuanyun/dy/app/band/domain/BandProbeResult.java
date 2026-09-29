package com.diaoyuanyun.dy.app.band.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * S1-7 契约 §4.2（E5）的<b>出参</b>。
 *
 * <h2>为什么 {@code retention_window_days} / {@code pull_start_date} 声明为 {@link Object}</h2>
 * 契约把它们定义为 {@code int | TBD} / {@code date}。用 {@code Integer} 表达不了"探测不到"：
 * ⚠️ {@code Result} 的信封带 {@code @JsonInclude(NON_NULL)}，若用 {@code null} 表示"无值"，
 * 该字段会被<b>整个吞掉</b> —— 客户端取值得到 {@code undefined}，
 * 而契约要求的是"<b>返回 {@code TBD}</b>"。这两种状态对客户端是<b>不同</b>的：
 * {@code undefined} 看起来像"服务端没实现这个字段"，{@code "TBD"} 是"明确未取证"。
 * 故用 {@link Object} 承载"数值 / 日期 / 字面 TBD"三态。
 *
 * <h2>为什么 TBD 是字符串而不是某个数字</h2>
 * 它是让下游<b>无法</b>把它当数字消费的结构性保证：任何算术一碰它就立刻失败，
 * 而不是悄悄按 7 天回溯（契约 §4.2 红线②：不得回溯超过探测到的窗口）。
 *
 * @param probeId               探测记录 ID
 * @param retentionWindowDays   运行时探测值（= 探测到的可用日期跨度）或 {@code TBD}
 * @param pullStartDate         服务端计算的补拉起点，或 {@code TBD}（公式下界缺一时不猜）
 * @param earliestAvailable     探测数组最早日
 * @param latestAvailable       探测数组最晚日
 * @param historyType           回显 history_type（便于客户端配对请求）
 * @param retentionWindowProbed 探测成败标志。<b>非契约字段，属扩展</b>：
 *                              让客户端无需字符串比较即可分辨"真值"与"TBD"；
 *                              {@code retention_window_days} 本身仍严格按契约输出。
 */
public record BandProbeResult(
        @JsonProperty("probe_id") String probeId,
        @JsonProperty("retention_window_days") Object retentionWindowDays,
        @JsonProperty("pull_start_date") Object pullStartDate,
        @JsonProperty("earliest_available") LocalDate earliestAvailable,
        @JsonProperty("latest_available") LocalDate latestAvailable,
        @JsonProperty("history_type") String historyType,
        @JsonProperty("retention_window_probed") boolean retentionWindowProbed) {
}