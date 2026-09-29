package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VisitRow} / {@code DailyReportRow} 的构造期校验 —— 复述 V5 CHECK/NOT NULL。
 */
@DisplayName("履约域行对象构造校验")
class FulfillmentRowTest {

    private static VisitRow validVisit() {
        return new VisitRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "{}", 1, null, null, null, null, null,
                Instant.now(), null);
    }

    @Test
    @DisplayName("VisitRow：plan_version<1 阻断")
    void visit_plan_version_lt_1_fails() {
        assertThrows(BizException.class, () -> new VisitRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, "{}", 1, null, null, null, null, null,
                Instant.now(), null));
    }

    @Test
    @DisplayName("VisitRow：visit_no<1 阻断（全局账本 U2）")
    void visit_no_lt_1_fails() {
        assertThrows(BizException.class, () -> new VisitRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "{}", 0, null, null, null, null, null,
                Instant.now(), null));
    }

    @Test
    @DisplayName("VisitRow：customerConfirmed 恒 true（库层 CHECK）")
    void visit_customer_confirmed_always_true() {
        assertTrue(validVisit().customerConfirmed());
    }

    @Test
    @DisplayName("DailyReportRow：source 只有 客户/代核")
    void daily_report_source_enum() {
        // 合法
        new DailyReportRow(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(),
                "{}", "客户", Instant.now(), null);
        new DailyReportRow(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(),
                "{}", "代核", Instant.now(), null);
        // 非法
        assertThrows(BizException.class, () -> new DailyReportRow(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(),
                "{}", "代理", Instant.now(), null));
    }

    @Test
    @DisplayName("DailyReportRow：date/answers 缺失阻断")
    void daily_report_null_fails() {
        assertThrows(BizException.class, () -> new DailyReportRow(
                UUID.randomUUID(), UUID.randomUUID(), null,
                "{}", "客户", Instant.now(), null));
        assertThrows(BizException.class, () -> new DailyReportRow(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(),
                "  ", "客户", Instant.now(), null));
    }

    @Test
    @DisplayName("DailyReportRow：合法行可构造，source 原样保留")
    void daily_report_valid() {
        DailyReportRow r = new DailyReportRow(UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 26), "{\"q\":1}", "代核", Instant.now(), "op");
        assertEquals("代核", r.source());
        assertEquals(LocalDate.of(2026, 9, 26), r.date());
    }
}