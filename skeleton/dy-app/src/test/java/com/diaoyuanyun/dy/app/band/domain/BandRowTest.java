package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code TelemetryRow} / {@code SyncBatchRow} 的构造期校验 —— 复述 V3/V5 CHECK。
 * 重点守契约 §4.3 双分支幂等键与四态的失败项必填。
 */
@DisplayName("手环数据行对象构造校验")
class BandRowTest {

    // ===== TelemetryRow =====

    @Test
    @DisplayName("metric 13 值校验（sport 仅游标型）")
    void telemetry_metric_enum() {
        for (String m : new String[]{"sleep", "steps", "hr", "resting_hr", "spo2", "workout",
                "bp", "temp", "pressure", "met", "mai", "respiration", "exercise"}) {
            new TelemetryRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), m,
                    LocalDate.now(), null, null, null, null, null, "手环", null, "synced",
                    Instant.now(), null, null, null, null);
        }
        assertThrows(BizException.class, () -> new TelemetryRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "heart_rate",
                LocalDate.now(), null, null, null, null, null, "手环", null, "synced",
                Instant.now(), null, null, null, null),
                "未登记的 metric 应拒（heart_rate 非 13 值，合法是 hr）");
    }

    @Test
    @DisplayName("is_wear 仅 1/0/-1/255")
    void telemetry_is_wear_enum() {
        assertThrows(BizException.class, () -> new TelemetryRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "steps",
                LocalDate.now(), null, null, null, null, null, "手环", null, "synced",
                Instant.now(), 2, null, null, null),
                "is_wear=2 应拒（仅 1/0/-1/255）");
    }

    // ===== SyncBatchRow =====

    @Test
    @DisplayName("四态枚举 + synced 必填 last_success_date")
    void sync_batch_state_enum() {
        // 合法 synced 带 last_success_date
        new SyncBatchRow(UUID.randomUUID(), UUID.randomUUID(), "b1", "on_show_hot", "synced",
                Instant.now(), LocalDate.now(), null, null, Instant.now(), null);
        // synced 缺 last_success_date → 拒
        assertThrows(BizException.class, () -> new SyncBatchRow(
                UUID.randomUUID(), UUID.randomUUID(), "b1", "on_show_hot", "synced",
                Instant.now(), null, null, null, Instant.now(), null));
        // 非法四态
        assertThrows(BizException.class, () -> new SyncBatchRow(
                UUID.randomUUID(), UUID.randomUUID(), "b1", "on_show_hot", "worn",
                Instant.now(), null, null, null, Instant.now(), null));
    }

    @Test
    @DisplayName("sync_failed 必填 fail_reason_class + next_action")
    void sync_failed_requires_reason_and_action() {
        assertThrows(BizException.class, () -> new SyncBatchRow(
                UUID.randomUUID(), UUID.randomUUID(), "b1", "on_show_hot", "sync_failed",
                Instant.now(), null, null, "retry", Instant.now(), null),
                "缺 fail_reason_class 应拒");
        assertThrows(BizException.class, () -> new SyncBatchRow(
                UUID.randomUUID(), UUID.randomUUID(), "b1", "on_show_hot", "sync_failed",
                Instant.now(), null, "bt_off", null, Instant.now(), null),
                "缺 next_action 应拒");
    }

    @Test
    @DisplayName("trigger 枚举校验")
    void trigger_enum() {
        assertThrows(BizException.class, () -> new SyncBatchRow(
                UUID.randomUUID(), UUID.randomUUID(), "b1", "onShow", "synced",
                Instant.now(), LocalDate.now(), null, null, Instant.now(), null),
                "trigger=onShow 应拒（契约英文枚举 on_show_cold/on_show_hot/checkin/daily_report/manual）");
    }
}