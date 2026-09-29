package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code PlanReviewRow} / {@code DeviceDispatchRow} / {@code PlanRow} 的构造期校验
 * —— 复述 V5 的"失败/退回必填原因"约束与 status 枚举（应用层镜像，报错指向构造点）。
 */
@DisplayName("方案审核/下发行对象构造校验")
class PlanReviewAndDispatchRowTest {

    // ===== PlanReviewRow =====

    @Test
    @DisplayName("退回缺 reason → 阻断（库层约束的应用层镜像）")
    void review_reject_without_reason_fails() {
        assertThrows(BizException.class, () -> new PlanReviewRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "退回", null, false, Instant.now(), null));
        assertThrows(BizException.class, () -> new PlanReviewRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "退回", "  ", false, Instant.now(), null));
    }

    @Test
    @DisplayName("退回带 reason → 可构造")
    void review_reject_with_reason_ok() {
        PlanReviewRow r = new PlanReviewRow(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), "退回", "不符合执行标准", false, Instant.now(), null);
        assertEquals("退回", r.result());
    }

    @Test
    @DisplayName("通过可无 reason → 可构造")
    void review_approve_ok() {
        PlanReviewRow r = new PlanReviewRow(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), "通过", null, true, Instant.now(), null);
        assertEquals("通过", r.result());
    }

    @Test
    @DisplayName("结果非通过/退回 → 阻断")
    void review_invalid_result_fails() {
        assertThrows(BizException.class, () -> new PlanReviewRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "待定", null, false, Instant.now(), null));
    }

    // ===== DeviceDispatchRow =====

    @Test
    @DisplayName("下发失败缺 reason → 阻断（下行可追责）")
    void dispatch_failed_without_reason_fails() {
        assertThrows(BizException.class, () -> new DeviceDispatchRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                "{}", "失败", null, "device_push_failed", Instant.now(), null));
    }

    @Test
    @DisplayName("下发失败带 reason → 可构造")
    void dispatch_failed_with_reason_ok() {
        DeviceDispatchRow r = new DeviceDispatchRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                "{}", "失败", "设备离线", "device_push_failed", Instant.now(), null);
        assertEquals("失败", r.result());
    }

    @Test
    @DisplayName("下发结果非成功/失败 → 阻断")
    void dispatch_invalid_result_fails() {
        assertThrows(BizException.class, () -> new DeviceDispatchRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                "{}", "进行中", null, null, Instant.now(), null));
    }

    // ===== PlanRow =====

    @Test
    @DisplayName("方案 status 四值校验")
    void plan_status_enum() {
        for (String s : new String[]{"draft", "reviewing", "approved", "superseded“}) {
            new PlanRow(UUID.randomUUID(), UUID.randomUUID(), 1, "{}", "{}", "{}", s,
                    Instant.now(), null);
        }
        assertThrows(BizException.class, () -> new PlanRow(
                UUID.randomUUID(), UUID.randomUUID(), 1, "{}", "{}", "{}", ”archived",
                Instant.now(), null));
    }

    @Test
    @DisplayName("方案 version<1 阻断（版本递增不可覆盖）")
    void plan_version_lt_1_fails() {
        assertThrows(BizException.class, () -> new PlanRow(
                UUID.randomUUID(), UUID.randomUUID(), 0, "{}", "{}", "{}", "draft",
                Instant.now(), null));
    }
}