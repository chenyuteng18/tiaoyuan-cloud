package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code band_sync_log} 一行（V5 §4.6）—— 客户端同步批次（四态）的落库载体。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 E1 {@code BandSyncBatchRequest}（四态 syncing/synced/sync_failed/no_data_today）：
 * <pre>
 *  batch_no    幂等键（Idempotency-Key 同值，UUID）
 *  trigger     on_show_cold / on_show_hot / checkin / daily_report / manual
 *  state       四态（客户端可见的 client_sync_state，不含"未佩戴"取值）
 *  synced_at   精确记录；客户端展示精确到日
 *  fail_reason_class  state=sync_failed 必填（技术类，域内不得出现"未佩戴"语义）
 *  next_action  state=sync_failed 必填（open_bluetooth/grant_permission/retry/none）
 * </pre>
 *
 * <h2>构造期校验</h2>
 * trigger/state/fail_reason_class/next_action 枚举 fail-closed。
 * 失败态须有 {@code last_success_date}（state=synced 必填）—— 见 PRD §4.6。
 */
public record SyncBatchRow(
        UUID syncLogId,
        UUID deviceId,
        String batchNo,
        String trigger,
        String state,
        Instant syncedAt,
        java.time.LocalDate lastSuccessDate,
        String failReasonClass,
        String nextAction,
        Instant attemptAt,
        String createdBy) {

    public static final java.util.Set<String> TRIGGERS = java.util.Set.of(
            "on_show_cold", "on_show_hot", "checkin", "daily_report", "manual");
    public static final java.util.Set<String> STATES = java.util.Set.of(
            "syncing", "synced", "sync_failed", "no_data_today");
    public static final java.util.Set<String> FAIL_REASON_CLASSES = java.util.Set.of(
            "bt_off", "unauthorized", "connect_timeout", "device_low_battery",
            "occupied_by_vendor_app", "platform_suspended", "probe_out_of_window");
    public static final java.util.Set<String> NEXT_ACTIONS = java.util.Set.of(
            "open_bluetooth", "grant_permission", "retry", "none");

    public SyncBatchRow {
        requireNonNull(syncLogId, "同步日志主键（sync_log_id）");
        requireNonNull(deviceId, "手环设备（device_id）");
        requireNonBlank(batchNo, "幂等键（batch_no）");
        if (trigger == null || !TRIGGERS.contains(trigger)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "trigger 非法: '" + trigger + "'（合法: " + TRIGGERS + "）");
        }
        if (state == null || !STATES.contains(state)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "state 非法: '" + state + "'（四态 syncing/synced/sync_failed/no_data_today）");
        }
        requireNonNull(syncedAt, "同步时点（synced_at）");
        // 🛑 state=synced 时 last_success_date 必填（契约 BandSyncBatchRequest 描述）
        if ("synced".equals(state) && lastSuccessDate == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "state=synced 时 last_success_date 必填（契约描述逐字）");
        }
        // 🛑 state=sync_failed 时 fail_reason_class + next_action 必填（不得只给一句"同步失败"）
        if ("sync_failed".equals(state)) {
            if (failReasonClass == null || FAIL_REASON_CLASSES.contains(failReasonClass) == false) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "state=sync_failed 时 fail_reason_class 必填且为技术类枚举: '"
                                + failReasonClass + "'（不得出现『未佩戴』语义）");
            }
            if (nextAction == null || !NEXT_ACTIONS.contains(nextAction)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "state=sync_failed 时 next_action 必填（可执行下一步，不得只给一句『同步失败』）");
            }
        }
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static void requireNonBlank(String v, String name) {
        requireNonNull(v, name);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 不得为空白");
        }
    }
}