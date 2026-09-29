package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.GateMissingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FulfillmentGateGuard} 的四道闸门语义 —— 域 D 最核心的领域逻辑。
 *
 * <h2>它守什么</h2>
 * PRD P0-08 四道闸门：禁忌通过 / 知情同意书已签 / 调理协议已签 / 方案已确认且有效。
 * 缺任一 → 403 GATE_MISSING + 逐字 missing_items[]（非模糊报错）。
 */
@DisplayName("四道闸门守卫")
class FulfillmentGateGuardTest {

    private static FulfillmentGateGuard.GateState state(
            boolean screening, boolean consent, boolean agreement, boolean plan) {
        return new FulfillmentGateGuard.GateState(screening, consent, agreement, plan);
    }

    @Test
    @DisplayName("四闸门全过 → 不抛")
    void all_passed_no_throw() {
        FulfillmentGateGuard.assertAllGatesPassed("D1", state(true, true, true, true));
    }

    @Test
    @DisplayName("缺禁忌 → 抛 GATE_MISSING 且 missing_items=[screening_result]")
    void missing_screening() {
        GateMissingException e = assertThrows(GateMissingException.class,
                () -> FulfillmentGateGuard.assertAllGatesPassed("D1", state(false, true, true, true)));
        assertEquals(java.util.List.of("screening_result"), e.getMissingItems());
    }

    @Test
    @DisplayName("缺同意书 → missing_items=[informed_consent]")
    void missing_consent() {
        GateMissingException e = assertThrows(GateMissingException.class,
                () -> FulfillmentGateGuard.assertAllGatesPassed("D1", state(true, false, true, true)));
        assertEquals(java.util.List.of("informed_consent"), e.getMissingItems());
    }

    @Test
    @DisplayName("缺协议 → missing_items=[agreement_signed]")
    void missing_agreement() {
        GateMissingException e = assertThrows(GateMissingException.class,
                () -> FulfillmentGateGuard.assertAllGatesPassed("D1", state(true, true, false, true)));
        assertEquals(java.util.List.of("agreement_signed"), e.getMissingItems());
    }

    @Test
    @DisplayName("缺方案 → missing_items=[plan_approved]")
    void missing_plan() {
        GateMissingException e = assertThrows(GateMissingException.class,
                () -> FulfillmentGateGuard.assertAllGatesPassed("D1", state(true, true, true, false)));
        assertEquals(java.util.List.of("plan_approved"), e.getMissingItems());
    }

    @Test
    @DisplayName("缺多项 → 一次性报全部（不短路）")
    void missing_multiple_reported_all() {
        GateMissingException e = assertThrows(GateMissingException.class,
                () -> FulfillmentGateGuard.assertAllGatesPassed("D1", state(false, false, true, false)));
        // 顺序即业务顺序：禁忌 → 同意书 → 方案
        assertEquals(java.util.List.of("screening_result", "informed_consent", "plan_approved"),
                e.getMissingItems());
    }

    @Test
    @DisplayName("缺失项顺序 = 四道门业务顺序（screening→consent→agreement→plan）")
    void order_matches_business_flow() {
        assertEquals(java.util.List.of("screening_result", "informed_consent", "agreement_signed", "plan_approved"),
                FulfillmentGateGuard.missingItemLiterals());
    }
}