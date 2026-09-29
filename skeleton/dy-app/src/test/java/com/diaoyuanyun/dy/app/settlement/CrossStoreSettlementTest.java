package com.diaoyuanyun.dy.app.settlement;

import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement.CrossStoreAnomaly;
import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement.SettlementResult;
import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement.StoreAllocation;
import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement.StoreContribution;
import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement.UnallocatableException;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreThresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨店通兑责任拆分 · 单元测试（S1-3 验收③）。
 *
 * <h2>断言锚定 PRD 原文，不是"实现返回什么就是什么"</h2>
 * 每条断言的期望值都能在 PRD 里找到出处（US-8 / P1-05 / config #21 /
 * PRD §2.9.6「不新建门店损益率聚合指标」）。这样当实现被改坏时，
 * 失败信息会指向<b>违反了哪条业务规则</b>，而不是"期望 3 实际 4"。
 *
 * <h2>重点守三件事</h2>
 * <ol>
 *   <li><b>阈值边界</b>：30% 是"≥"还是"&gt;"——PRD 写 {@code ≥30%}，故恰好 30% 必须<b>触发</b>。</li>
 *   <li><b>钱的守恒</b>：Σ分摊 == 应分总额，<b>精确相等</b>（除不尽时靠余数吸收）。</li>
 *   <li><b>缺失不补 0</b>：次数合计 0 必须抛错，不得返回"各店 0"。</li>
 * </ol>
 */
class CrossStoreSettlementTest {

    /**
     * 测试用口径：与 config {@code #21}/{@code #22} 的<b>声明初值</b>逐字一致。
     *
     * <p>🛑 这里显式写死数字，<b>不是</b>"把配置抄一份进测试"—— 它断言的是
     * "在声明初值下行为如何"。声明与代码的一致性由
     * {@code CrossStoreProfileSourceTest} 单独守着（那一侧读真 seed，
     * 两处若分叉即红）。本类只回答"给定这组阈值，结算逻辑对不对"。
     */
    private final CrossStoreSettlement settlement =
            new CrossStoreSettlement(new CrossStoreThresholds(
                    new BigDecimal("0.30"), 30, 2, 3, 30));

    // ------------------------------------------------------------------ 阈值边界

    @Test
    @DisplayName("他店占比恰好 30% 必须触发拆分 —— PRD 写的是「≥30%」不是「>30%」")
    void exactly_thirty_percent_triggers_split() {
        // ST-A 7 次(结案) / ST-B 3 次 → 他店 3/10 = 30.0%，恰在阈值上
        SettlementResult r = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 7, "ST-B", 3), "ST-A"),
                BigDecimal.ONE, BigDecimal.ZERO);

        assertTrue(r.splitApplied(),
                "他店占比恰好 30% 必须触发拆分（PRD US-8「≥30%」）；若实现写成 > 会漏掉这一个点");
        assertEquals(0, new BigDecimal("0.300000").compareTo(r.otherStoreVisitRatio()),
                "他店占比应为 0.30，实际 " + r.otherStoreVisitRatio());
    }

    @Test
    @DisplayName("他店占比略低于 30% 不得触发 —— 结案店独占")
    void just_below_threshold_does_not_split() {
        // ST-A 71 次(结案) / ST-B 29 次 → 29/100 = 29%
        SettlementResult r = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 71, "ST-B", 29), "ST-A"),
                new BigDecimal("100.00"), new BigDecimal("1000.00"));

        assertFalse(r.splitApplied(), "29% < 30% 不得拆分");
        StoreAllocation closer = alloc(r, "ST-A");
        assertEquals(0, new BigDecimal("100.00").compareTo(closer.eccShare()),
                "未拆分时结案店独占全部 ECC");
        assertEquals(0, BigDecimal.ZERO.compareTo(alloc(r, "ST-B").eccShare()),
                "未拆分时他店 ECC 为 0（此处 0 是真实结论，不是缺失补 0）");
    }

    @Test
    @DisplayName("全部在本店完成: 他店占比 0%，结案店独占")
    void single_store_takes_all() {
        SettlementResult r = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 10), "ST-A"),
                BigDecimal.ONE, BigDecimal.ZERO);

        assertFalse(r.splitApplied(), "无他店服务，不应拆分");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.otherStoreVisitRatio()), "他店占比应为 0");
        assertEquals(0, BigDecimal.ONE.compareTo(alloc(r, "ST-A").eccShare()));
    }

    // ------------------------------------------------------------------ 钱的守恒

    @Test
    @DisplayName("除不尽时: Σ分摊必须【精确等于】应分总额 —— 钱不得凭空多出或少掉")
    void shares_sum_exactly_to_total_even_when_indivisible() {
        // 三家店各 1 次，ECC = 1.00 → 每家 1/3，必然除不尽（0.3333...）
        SettlementResult r = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 1, "ST-B", 1, "ST-C", 1), "ST-A"),
                new BigDecimal("1.00"), new BigDecimal("1.00"));

        assertTrue(r.splitApplied(), "他店占比 2/3 ≈ 66.7% ≥ 30%，应拆分");
        assertEquals(0, new BigDecimal("1.00").compareTo(r.totalEccShare()),
                "Σ ECC 分摊必须精确等于 1.00，实际 " + r.totalEccShare()
                        + " —— 各店独立舍入会让合计与账单差几分钱");
        assertEquals(0, new BigDecimal("1.00").compareTo(r.totalLossShare()),
                "Σ 损失分摊必须精确等于 1.00，实际 " + r.totalLossShare());
    }

    @Test
    @DisplayName("损失分摊按次数占比 —— 不是平均分，也不是全给结案店")
    void loss_is_allocated_by_visit_ratio() {
        // ST-A 75(结案) / ST-B 25 → 他店 25%，低于阈值 → 结案店独占（先验证不拆分的损失归属）
        SettlementResult noSplit = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 75, "ST-B", 25), "ST-A"),
                BigDecimal.ONE, new BigDecimal("400.00"));
        assertEquals(0, new BigDecimal("400.00").compareTo(alloc(noSplit, "ST-A").lossShare()),
                "未拆分时损失全部由结案店承担");

        // ST-A 60(结案) / ST-B 40 → 他店 40% ≥ 30% → 按占比拆
        SettlementResult split = settlement.allocate(
                CrossStoreSettlement.contributions(Map.of("ST-A", 60, "ST-B", 40), "ST-A"),
                BigDecimal.ONE, new BigDecimal("1000.00"));
        assertEquals(0, new BigDecimal("600.00").compareTo(alloc(split, "ST-A").lossShare()),
                "ST-A 占 60% → 损失 600.00，实际 " + alloc(split, "ST-A").lossShare());
        assertEquals(0, new BigDecimal("400.00").compareTo(alloc(split, "ST-B").lossShare()),
                "ST-B 占 40% → 损失 400.00，实际 " + alloc(split, "ST-B").lossShare());
    }

    @Test
    @DisplayName("确定性: 同一输入必得同一输出（可重复 = 可对账）")
    void allocation_is_deterministic() {
        List<StoreContribution> input = CrossStoreSettlement.contributions(
                Map.of("ST-C", 1, "ST-A", 1, "ST-B", 1), "ST-A");
        SettlementResult r1 = settlement.allocate(input, new BigDecimal("1.00"), new BigDecimal("1.00"));
        SettlementResult r2 = settlement.allocate(input, new BigDecimal("1.00"), new BigDecimal("1.00"));

        assertEquals(r1.allocations(), r2.allocations(),
                "两次结算结果必须一致 —— 否则财务无法复现");
        // 余数落在 storeId 最小的那家（并列时取最小）→ 保证确定性
        assertEquals("ST-A", r1.allocations().get(0).storeId(), "结果应按 storeId 升序");
    }

    // ------------------------------------------------------------------ 缺失不得补 0

    @Test
    @DisplayName("服务次数合计为 0 必须抛错 —— 缺失不得补 0（PRD 硬纪律）")
    void zero_total_visits_must_throw_not_return_zeros() {
        UnallocatableException ex = assertThrows(UnallocatableException.class,
                () -> settlement.allocate(
                        CrossStoreSettlement.contributions(Map.of("ST-A", 0, "ST-B", 0), "ST-A"),
                        BigDecimal.ONE, BigDecimal.ZERO),
                "次数合计为 0 是数据缺失，不是『各店贡献为 0』—— 补 0 会永久掩盖『这次结算没有依据』");
        assertTrue(ex.getMessage().contains("缺失"),
                "错误信息应点明这是数据缺失；实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("无门店贡献记录必须抛错，不得静默返回空结果")
    void empty_contributions_must_throw() {
        assertThrows(UnallocatableException.class,
                () -> settlement.allocate(List.of(), BigDecimal.ONE, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("结案门店不恰好 1 个必须抛错 —— 不猜测、不任选其一")
    void wrong_number_of_closing_stores_must_throw() {
        assertThrows(UnallocatableException.class,
                () -> settlement.allocate(List.of(
                        new StoreContribution("ST-A", 5, true),
                        new StoreContribution("ST-B", 5, true)), BigDecimal.ONE, BigDecimal.ZERO),
                "两个结案店是数据错误，任选其一会把错误悄悄固化");
        assertThrows(UnallocatableException.class,
                () -> settlement.allocate(List.of(
                        new StoreContribution("ST-A", 5, false),
                        new StoreContribution("ST-B", 5, false)), BigDecimal.ONE, BigDecimal.ZERO),
                "没有结案店同样是数据错误");
    }

    // ------------------------------------------------------------------ 跨店异常阈值

    @Test
    @DisplayName("跨店异常: 30 天内 2 店标记 / 4 店冻结 / 他店占比 >30% 反刷（PRD P1-05 config #22）")
    void cross_store_anomaly_thresholds_match_prd() {
        CrossStoreAnomaly two = settlement.detectAnomaly(2, new BigDecimal("0.10"));
        assertTrue(two.flagged(), "≥2 店应标记");
        assertFalse(two.frozen(), "2 店不应冻结（冻结线是 >3）");
        assertFalse(two.antiFraudReview(), "他店占比 10% 未超 30%，不触发反刷");

        CrossStoreAnomaly four = settlement.detectAnomaly(4, new BigDecimal("0.10"));
        assertTrue(four.frozen(), ">3 店应冻结");
        assertTrue(four.flagged(), "冻结情形下标记同样成立（三条是独立信号，非递进）");

        CrossStoreAnomaly ratio = settlement.detectAnomaly(1, new BigDecimal("0.35"));
        assertTrue(ratio.antiFraudReview(), "他店占比 35% > 30% 应触发反刷审查");
        assertFalse(ratio.flagged(), "单店不触发『多店』标记");
    }

    // ------------------------------------------------------------------ 阈值来源

    @Test
    @DisplayName("默认阈值必须是 0.30 —— 与 config #21 一致（总部唯一可改）")
    void default_threshold_matches_config_slot_21() {
        assertEquals(0, new BigDecimal("0.30").compareTo(settlement.splitThreshold()),
                "本测试注入的口径为 config #21 的声明初值 0.30");
        assertEquals(0, new BigDecimal("0.30").compareTo(settlement.thresholds().splitThreshold()));
    }

    @Test
    @DisplayName("非法阈值必须拒绝 —— 不静默夹到 [0,1]（越界即配置缺陷，不得夹取）")
    void illegal_threshold_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreThresholds(new BigDecimal("1.5"), 30, 2, 3, 30),
                "阈值 1.5 越界（把百分数当比例）—— 夹到 1.0 会让『每次都按占比拆』静默生效");
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreThresholds(new BigDecimal("-0.1"), 30, 2, 3, 30));
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreThresholds(null, 30, 2, 3, 30));
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreSettlement((CrossStoreThresholds) null),
                "口径不得为 null —— 阈值必须来自配置");
    }

    @Test
    @DisplayName("口径单调性: 冻结线必须严格大于标记线（两条独立信号不得自相矛盾）")
    void freeze_line_must_exceed_mark_line() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreThresholds(new BigDecimal("0.30"), 30, 3, 3, 30),
                "冻结线 == 标记线 ⇒ 先冻后标，两条各自合法的正整数会静默共存");
        assertThrows(IllegalArgumentException.class,
                () -> new CrossStoreThresholds(new BigDecimal("0.30"), 30, 4, 2, 30));
    }

    @Test
    @DisplayName("阈值接线: 给定非 0.30 的配置值，结算行为必须跟着变（『总部唯一可改』真的成立）")
    void threshold_is_actually_wired_from_config_not_hardcoded() {
        // 若阈值是代码常量，下面这组"把阈值改成 0.50"的构造不可能改变判定结果。
        CrossStoreSettlement stricter = new CrossStoreSettlement(new CrossStoreThresholds(
                new BigDecimal("0.50"), 30, 2, 3, 30));

        // 他店占比恰好 40%：在 0.30 阈值下应拆分，在 0.50 阈值下不得拆分
        List<CrossStoreSettlement.StoreContribution> c =
                CrossStoreSettlement.contributions(Map.of("ST-A", 6, "ST-B", 4), "ST-A");

        assertTrue(settlement.allocate(c, BigDecimal.ONE, BigDecimal.ZERO).splitApplied(),
                "0.30 阈值下，40% 他店占比应拆分");
        assertFalse(stricter.allocate(c, BigDecimal.ONE, BigDecimal.ZERO).splitApplied(),
                "🛑 0.50 阈值下 40% 不得拆分 —— 若这里仍然拆分，说明阈值是代码常量，"
                        + "『总部唯一可改』不成立（N-14 回退）");
    }

    @Test
    @DisplayName("反刷阈值来自 config #22（cross_share_pct），不是借用拆分阈值")
    void anti_fraud_threshold_comes_from_slot_22() {
        // 拆分阈值 0.30、反刷占比 60% —— 两者不同值时必须各按各的判
        CrossStoreSettlement s = new CrossStoreSettlement(new CrossStoreThresholds(
                new BigDecimal("0.30"), 30, 2, 3, 60));

        assertFalse(s.detectAnomaly(1, new BigDecimal("0.35")).antiFraudReview(),
                "反刷线 60% 时，35% 不得触发 —— 若仍触发，说明反刷借用了 #21 的 0.30（归属错误）");
        assertTrue(s.detectAnomaly(1, new BigDecimal("0.65")).antiFraudReview(),
                "65% > 60% 应触发反刷审查");
    }

    @Test
    @DisplayName("跨店标记/冻结线来自 config #22，不是代码里的 2/3")
    void anomaly_store_count_thresholds_come_from_slot_22() {
        CrossStoreSettlement s = new CrossStoreSettlement(new CrossStoreThresholds(
                new BigDecimal("0.30"), 30, 3, 5, 30));

        assertFalse(s.detectAnomaly(2, BigDecimal.ZERO).flagged(),
                "标记线改成 3 时，2 店不得标记 —— 若仍标记，说明常量 2 没被替换");
        assertTrue(s.detectAnomaly(3, BigDecimal.ZERO).flagged(), "3 店应标记");
        assertTrue(s.detectAnomaly(6, BigDecimal.ZERO).frozen(), "6 > 5 应冻结");
        assertFalse(s.detectAnomaly(5, BigDecimal.ZERO).frozen(), "5 不 > 5，不得冻结");
    }

    // ------------------------------------------------------------------ helpers

    private static StoreAllocation alloc(SettlementResult r, String storeId) {
        return r.allocations().stream()
                .filter(a -> a.storeId().equals(storeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中缺少门店 " + storeId));
    }
}