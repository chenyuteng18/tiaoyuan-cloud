package com.diaoyuanyun.dy.app.band.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * S1-7 契约 §4.2（E5 {@code POST /api/v1/band/available-dates}）的<b>入参</b>。
 *
 * <h2>🛑 为什么每个字段都要显式标 {@code @JsonProperty}（真实缺陷防线）</h2>
 * 本仓库<b>没有</b>全局 snake_case 命名策略 —— {@code Result.java} 的 {@code trace_id}
 * 就是靠显式注解修正的，这是既有惯例。契约 §4.2 的入参出参<b>全部是 snake_case</b>。
 * 若依赖默认命名，反序列化时会读不到 {@code valid_history_dates}（Jackson 认的是
 * {@code validHistoryDates}）⇒ 该字段恒为 {@code null} ⇒ 探测窗口恒为 TBD，
 * 而<b>不报错</b>。故逐字显式标注，不留默认。
 *
 * <h2>两个扩展入参的来历</h2>
 * 契约 §4.2 的入参表列了 4 个字段，但出参 {@code pull_start_date} 的公式
 * 需要 {@code last_synced_date}（属服务端已有状态），错误码 1001 的成立条件需要
 * 「非首次绑定」这一标志（契约原文即含该条件）。二者契约表格未列，本记录显式承接，
 * 并在 {@code BandAvailableDatesTest} 里钉住"缺它们时不猜，输出 TBD / 拒入"：
 * <ul>
 *   <li>{@code last_synced_date} 缺失 → {@code pull_start_date} 输出 {@code TBD}（不猜）；</li>
 *   <li>{@code first_binding} 缺失 → 空日期数组按"非首次绑定"处理（1001，fail-closed）。</li>
 * </ul>
 *
 * @param deviceId          客户级设备 ID（契约必填）
 * @param historyType       厂商 HistoryType（13 条之一，契约必填）
 * @param validHistoryDates {@code getValidHistoryDates} 原样返回的设备端实际存在日期（契约必填）
 * @param probedAt          探测时刻（契约必填）
 * @param lastSyncedDate    扩展入参：上一次同步到的日期
 * @param firstBinding      扩展入参：是否首次绑定
 */
public record BandProbeRequest(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("history_type") String historyType,
        @JsonProperty("valid_history_dates") List<String> validHistoryDates,
        @JsonProperty("probed_at") String probedAt,
        @JsonProperty("last_synced_date") String lastSyncedDate,
        @JsonProperty("first_binding") Boolean firstBinding) {
}