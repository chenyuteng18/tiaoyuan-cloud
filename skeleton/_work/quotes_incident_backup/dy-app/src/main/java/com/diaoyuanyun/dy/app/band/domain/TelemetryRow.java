package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code band_telemetry} 一行（V3 §2.17，长表 metric 化）—— 客户端上行遥测的落库载体。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 E2 {@code BandTelemetryRequest} + 契约 §4.3 双分支幂等键：
 * <pre>
 *  metric   13 值（sleep/steps/hr/.../exercise/sport 游标型）
 *  date/hour/minute  按日型幂等键成员；日聚合退化为 NULL
 *  sport_id  运动游标型分支幂等键成员（日型为 NULL）
 *  is_wear   1 佩戴 / 0 脱腕 / (-1,255) 技术性缺失（服务端强制覆写，不判行为性）
 * </pre>
 *
 * <h2>🛑 幂等键双分支（契约 §4.3）</h2>
 * <pre>
 *  按日型 12 条 → (device_id, metric, date, hour, minute)，日聚合退化 (device_id, metric, date)
 *  游标型 1 条  → (device_id, 'sport', current_sport_id)
 * </pre>
 * 由库层两个表达式唯一索引兜底（V3 的 {@code uq_bt_daily_idempotent} /
 * {@code uq_bt_sport_cursor}），服务层做前置判断。
 *
 * <h2>🛑 V10（B-1）起：本 record 承载的是【明文】，落库前才加密</h2>
 * 两个字段的语义已在 V10 明确分离，勿再混同：
 * <table border="1">
 *   <tr><th>本 record 字段</th><th>类型</th><th>落库列</th><th>库里形态</th></tr>
 *   <tr><td>{@code valueNum}</td><td>{@code BigDecimal}</td><td>{@code value_enc}</td>
 *       <td><b>密文信封</b> {@code dy1:...}（TEXT）</td></tr>
 *   <tr><td>{@code sleepJson}</td><td>{@code String}（JSON 文本）</td><td>{@code sleep_json}</td>
 *       <td><b>密文信封</b> {@code dy1:...}（TEXT，V10 由 JSONB 改）</td></tr>
 * </table>
 * 加密发生在 {@code BandService}（写入）与 {@code BandLedger}（读取），
 * 故本 record 在<b>领域层</b>始终是明文形态 —— 领域模型不该知道存储怎么保护它。
 * 也正因如此，{@link #sportId} 这类幂等键成员<b>不在加密范围</b>：
 * 它是查询键，加密会让唯一索引失去作用。这个区分必须写在类型上，不能只写在文档里。
 *
 * <h2>构造期校验</h2>
 * metric 13 值 fail-closed；sync_state 四值；is_wear 仅 1/0/-1/255。
 */
public record TelemetryRow(
        UUID telemetryId,
        UUID customerId,
        UUID deviceId,
        String metric,
        LocalDate date,
        Integer hour,
        Integer minute,
        java.math.BigDecimal valueNum,
        String sleepJson,
        Boolean coverageFlag,
        String dataSource,
        String gapReason,
        String syncState,
        Instant syncedAt,
        Integer isWear,
        Integer localTzOffset,
        String sportId,
        String createdBy) {

    public static final java.util.Set<String> METRICS = java.util.Set.of(
            "sleep", "steps", "hr", "resting_hr", "spo2", "workout",
            "bp", "temp", "pressure", "met", "mai", "respiration", "exercise");

    /**
     * 本行是否需要加密其标量值。
     *
     * <p>转发到 {@link TelemetrySensitivity}（那里是登记表的唯一真相源）。
     * 放在 record 上只作便捷入口，<b>不</b>在 record 内重新判断一次 ——
     * 两处各判一次会让"哪些字段加密"变成两份可以各自漂移的真相。
     */
    public boolean valueRequiresEncryption() {
        return TelemetrySensitivity.requiresEncryption(metric);
    }

    public TelemetryRow {
        requireNonNull(telemetryId, "遥测主键（telemetry_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        requireNonNull(deviceId, "手环设备（device_id）");
        if (metric == null || !METRICS.contains(metric)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "metric 必须是 13 值之一: '" + metric + "'");
        }
        requireNonNull(date, "业务日（date）");
        requireNonNull(syncedAt, "同步时点（synced_at）");
        if (syncState != null && !java.util.Set.of("syncing", "synced", "sync_failed", "no_data")
                .contains(syncState)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "sync_state 非法: '" + syncState + "'");
        }
        if (isWear != null && !java.util.Set.of(-1, 0, 1, 255).contains(isWear)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "is_wear 仅 1/0/-1/255: " + isWear);
        }
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }
}