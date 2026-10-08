package com.diaoyuanyun.dy.app.settlement.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link SettlementStatementService#requestHash} 的纯函数单测 ——
 * 幂等键的<b>确定性</b>是"同输入 = 同一张单"承诺的物理基础，单独钉住：
 * 调用方 Map 的遍历序（LinkedHashMap 插入序 vs 升序）不得影响哈希；
 * 输入变一点，哈希必须变（否则不同账会顶掉同一张单）。
 */
@DisplayName("结算幂等键：规范哈希的确定性与敏感性")
class SettlementStatementHashTest {

    @Test
    @DisplayName("同输入（不同 Map 遍历序）→ 同哈希")
    void same_input_different_iteration_order_same_hash() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("store-b", 2);
        a.put("store-a", 3);

        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("store-a", 3);
        b.put("store-b", 2);

        String h1 = SettlementStatementService.requestHash("2026-10", "store-x",
                a, new BigDecimal("10.00"), new BigDecimal("3.00"));
        String h2 = SettlementStatementService.requestHash("2026-10", "store-x",
                b, new BigDecimal("10.00"), new BigDecimal("3.00"));
        assertEquals(h1, h2, "Map 遍历序不得影响幂等键 —— 否则同账落两张单");
    }

    @Test
    @DisplayName("输入任一分量变化 → 哈希必变（敏感性）")
    void any_input_change_changes_hash() {
        String base = SettlementStatementService.requestHash("2026-10", "store-x",
                Map.of("store-a", 3), new BigDecimal("10.00"), new BigDecimal("3.00"));

        assertNotEquals(base, SettlementStatementService.requestHash("2026-11", "store-x",
                Map.of("store-a", 3), new BigDecimal("10.00"), new BigDecimal("3.00")),
                "period 变了哈希不变 —— 不同周期的账会顶掉同一张单");
        assertNotEquals(base, SettlementStatementService.requestHash("2026-10", "store-y",
                Map.of("store-a", 3), new BigDecimal("10.00"), new BigDecimal("3.00")),
                "结案店变了哈希不变");
        assertNotEquals(base, SettlementStatementService.requestHash("2026-10", "store-x",
                Map.of("store-a", 4), new BigDecimal("10.00"), new BigDecimal("3.00")),
                "次数变了哈希不变");
        assertNotEquals(base, SettlementStatementService.requestHash("2026-10", "store-x",
                Map.of("store-a", 3), new BigDecimal("10.01"), new BigDecimal("3.00")),
                "金额变了哈希不变");
    }

    @Test
    @DisplayName("哈希为 64 位十六进制（SHA-256 形态，库列 VARCHAR(64)）")
    void hash_is_sha256_hex() {
        String h = SettlementStatementService.requestHash("2026-10", "store-x",
                Map.of("store-a", 3), BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(64, h.length(), "SHA-256 十六进制必须 64 字符");
        assertTrue(h.matches("[0-9a-f]{64}"), "必须全十六进制小写: " + h);
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
