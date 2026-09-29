package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.AdherenceState;
import com.diaoyuanyun.dy.app.derived.domain.Disposition;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定分支路由的契约守卫 —— PRD §7.3 决策表 D1~D5 的<b>逐支断言</b>
 * 与组合出口的<b>穷举断言</b>。
 *
 * <h2>判据锚定 PRD / 契约，不是自证</h2>
 * 每条断言都写清它比对的<b>外部原文</b>：
 * <ul>
 *   <li>D1 稳定·改善 —— PRD §7.3 L1284「核心指标 ∈ {稳定,改善} AND AS_refund ≥ 0.8」；</li>
 *   <li>D2 依从不足 —— L1285「AS_refund &lt; 0.8」，人工依赖逐字<b>「无（纯系统计算）」</b>；</li>
 *   <li>D3 达标无效 —— L1286「AS_refund ≥ 0.8 AND 核心指标无改善」，
 *       且定调句要求它<b>由系统自动触发</b>；</li>
 *   <li>D4 全面评估 —— L1287「经络师标签 ∈ {高危,新发,同病}」，
 *       人工依赖逐字<b>「属临床判断，必须人工录入」</b>；</li>
 *   <li>D5 人工复核 —— L1288「条件不满足 / 数据不足 / 测量不可比」；</li>
 *   <li>组合出口 —— P0-12 / P0-24「三 enum 组合 → disposition，不得混入单一 enum」；
 *       自动路径不得产出「退款终止」—— P0-14「效果类走协商工单，人在环、不可自动直出资格结论」。</li>
 * </ul>
 *
 * <h2>🛑 本类最重要的一条断言是"穷举"</h2>
 * {@link #no_automatic_path_ever_yields_refund_termination()} 对
 * {@code EffectVerdict × AdherenceState × RiskFlag} 的<b>全部组合</b>逐一求解处置。
 * 用"挑几个代表性组合"的写法会漏掉恰恰最危险的那几个 ——
 * 而"最不利的组合会不会自动产出退款终止"正是 P0-14 的全部内容。
 */
class VerdictBranchRoutingTest {

    private final VerdictBranchEngine engine = new VerdictBranchEngine();

    // ==================================================================
    // 一、逐支断言（D1~D5）
    // ==================================================================

    @Test
    @DisplayName("D1：核心指标改善 + 依从达标 + 无风险标签 ⇒ 稳定（维持原方案）")
    void d1_stable_when_core_improved_and_adherence_passed() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, RiskFlag.NONE, true, true));

        assertEquals(VerdictBranch.STABLE, r.branch(),
                "PRD §7.3 D1：核心指标 ∈ {稳定,改善} AND AS_refund ≥ 0.8 ⇒ 稳定·改善");
        assertEquals("D1", r.decisionRule());
        assertEquals(Disposition.KEEP_PLAN, r.disposition(),
                "D1 的系统动作逐字是『维持原方案 + 生成下一周期』⇒ 继续原方案");
        assertTrue(r.reason().contains("维持原方案"), "理由必须是人话且含 D1 的动作，实际: " + r.reason());
    }

    @Test
    @DisplayName("D1：E3稳定 也走 D1（PRD D1 逐字含『稳定』，不只是『改善』）")
    void d1_stable_also_matches_e3_stable() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E3_STABLE, AdherenceState.PASS, RiskFlag.NONE, true, true));
        assertEquals(VerdictBranch.STABLE, r.branch(),
                "PRD §7.3 D1 的条件逐字是『核心指标 ∈ {稳定,改善}』—— "
                        + "E3稳定 落在『稳定』里，故它也走 D1。"
                        + "🛑 若把 D1 只映射到 E1/E2，E3 会掉进 D3（重定方案），"
                        + "把『稳定』错读成『无效』");
        assertEquals("D1", r.decisionRule());
    }

    @Test
    @DisplayName("D2：依从不足 ⇒ 依从不足分支（且它是纯系统分支，不依赖核心指标录入）")
    void d2_insufficient_adherence() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E1_SIGNIFICANT, AdherenceState.INSUFFICIENT, RiskFlag.NONE, true, true));

        assertEquals(VerdictBranch.INSUFFICIENT_ADHERENCE, r.branch(),
                "PRD §7.3 D2：AS_refund < 0.8 ⇒ 依从不足");
        assertEquals("D2", r.decisionRule());
        assertTrue(r.reason().contains("纯系统计算"),
                "D2 的人工依赖逐字是『无（纯系统计算）』—— 理由里应留痕，实际: " + r.reason());
    }

    @Test
    @DisplayName("🛑 D2 优先于 D1：即使效果是 E1显著改善，依从不足仍走 D2")
    void d2_wins_over_effect_branch() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E1_SIGNIFICANT, AdherenceState.INSUFFICIENT, RiskFlag.NONE, true, true));
        assertNotEquals(VerdictBranch.STABLE, r.branch(),
                "依从不足时原方案的『可执行性』本身有问题 ⇒ 不得给『维持原方案』。"
                        + "若顺序反了，一个『效果很好但根本没按方案做』的客户会拿到 D1 —— "
                        + "而那一支的动作恰恰是『维持原方案』（那个他没执行的方案）");
        assertEquals(Disposition.ADJUST_AND_CONTINUE, r.disposition(),
                "D2 的动作是『强化生活方式干预 + 追踪任务』⇒ 调整后继续");
    }

    @Test
    @DisplayName("D3：依从达标但核心指标无改善 ⇒ 达标无效（且系统自动触发）")
    void d3_adherent_but_ineffective() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E4_NO_IMPROVEMENT, AdherenceState.PASS, RiskFlag.NONE, false, true));

        assertEquals(VerdictBranch.ADHERENT_BUT_INEFFECTIVE, r.branch(),
                "PRD §7.3 D3：AS_refund ≥ 0.8 AND 核心指标无改善 ⇒ 依从达标但无效");
        assertEquals("D3", r.decisionRule());
        assertTrue(r.reason().contains("自动触发"),
                "PRD §7.3 定调句逐字要求 D3『由系统自动触发，不依赖门店主动发起』—— "
                        + "理由里应留痕，实际: " + r.reason());
        assertEquals(Disposition.ADJUST_AND_CONTINUE, r.disposition(),
                "D3 的动作是『自动提醒经络师重定全新方案 + 设备路由』⇒ 调整后继续");
    }

    @Test
    @DisplayName("D4：风险标签 ∈ {高危,新发,同病} ⇒ 全面评估")
    void d4_full_assessment_for_each_triggering_risk_flag() {
        for (RiskFlag flag : List.of(RiskFlag.HIGH_RISK, RiskFlag.NEW_ONSET, RiskFlag.COMORBID)) {
            VerdictBranchEngine.BranchResult r = engine.route(input(
                    EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, flag, true, true));
            assertEquals(VerdictBranch.FULL_ASSESSMENT, r.branch(),
                    "PRD §7.3 D4：经络师标签 ∈ {高危,新发,同病} ⇒ 全面评估（标签="
                            + flag.dbLabel() + "）");
            assertEquals("D4", r.decisionRule());
        }
    }

    @Test
    @DisplayName("🛑 D4 优先于 D1/D2/D3（安全优先）—— 改善的客户同样可因新发而需全面评估")
    void d4_precedes_all_effect_and_adherence_branches() {
        // 三个"最像 D1/D2/D3"的组合，各自加上一个触发标签，都必须变成 D4
        List<VerdictBranchEngine.BranchInput> wouldBeOtherwise = List.of(
                input(EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, RiskFlag.NEW_ONSET, true, true),
                input(EffectVerdict.E1_SIGNIFICANT, AdherenceState.INSUFFICIENT, RiskFlag.HIGH_RISK, true, true),
                input(EffectVerdict.E4_NO_IMPROVEMENT, AdherenceState.PASS, RiskFlag.COMORBID, false, true));

        for (VerdictBranchEngine.BranchInput in : wouldBeOtherwise) {
            VerdictBranchEngine.BranchResult r = engine.route(in);
            assertEquals(VerdictBranch.FULL_ASSESSMENT, r.branch(),
                    "🛑 D4 必须排在效果与依从分支【之前】：收到 " + in.riskFlag().dbLabel()
                            + " 时无论效果如何都应进全面评估。"
                            + "若 D4 排在后面，『改善 ⇒ D1』会把新发信号吞掉 —— "
                            + "而 PRD §7.3 定调句要点名防的正是『该重定方案的不被拖过去』");
        }
    }

    @Test
    @DisplayName("🛑 D4 的处置不是『退款终止』：它的动作链是继续服务（新方案 + 回炉审核）")
    void d4_disposition_is_not_refund_termination() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E4_NO_IMPROVEMENT, AdherenceState.PASS, RiskFlag.HIGH_RISK, false, true));
        assertEquals(Disposition.ADJUST_AND_CONTINUE, r.disposition(),
                "D4 的动作链逐字是『生成全面评估任务 → 新方案 → 回炉审核 + 客户重签』—— "
                        + "那是【继续服务】的一种，不是终止。🛑 且『是否建议就医』属临床判断，"
                        + "系统不得替人定（本引擎对它也不返回 ADVISE_MEDICAL）");
        assertNotEquals(Disposition.REFUND_TERMINATE, r.disposition());
    }

    @Test
    @DisplayName("D5：测量不可比 ⇒ 人工复核（最先判定，先于一切）")
    void d5_incomparable_measurement_first() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, RiskFlag.NONE, true, false));
        assertEquals(VerdictBranch.HUMAN_REVIEW, r.branch(),
                "PRD §7.3 D5：『测量不可比』⇒ 人工复核");
        assertEquals("D5", r.decisionRule());
        assertEquals(ContractTbd.TBD, r.significantThreshold(),
                "🛑 显著阈值必须是 TBD 而【不是】null：省略它会让复盘时无法区分"
                        + "『当时口径里没有这一项』与『当时忘了记』");
    }

    @Test
    @DisplayName("D5：依从样本不足 ⇒ 人工复核（🛑 它不是『依从不足』）")
    void d5_sample_insufficient_is_not_insufficient_adherence() {
        VerdictBranchEngine.BranchResult r = engine.route(input(
                EffectVerdict.E1_SIGNIFICANT, AdherenceState.SAMPLE_INSUFFICIENT, RiskFlag.NONE, true, true));
        assertEquals(VerdictBranch.HUMAN_REVIEW, r.branch(),
                "样本不足的语义逐字是『没算出来』（见 AdherenceState 类注释），"
                        + "不是『算出来不达标』。若把它当 INSUFFICIENT，"
                        + "『数据不够』会静默变成『客户没配合』—— 一条对客户不利的结论");
        assertNotEquals(AdherenceState.INSUFFICIENT, AdherenceState.SAMPLE_INSUFFICIENT);
    }

    @Test
    @DisplayName("D5：三件事实任一缺失 ⇒ 人工复核（且绝不默认值）")
    void d5_any_missing_fact_suspends() {
        record Case(String name, VerdictBranchEngine.BranchInput in) { }

        List<Case> cases = List.of(
                new Case("riskFlag 未录入", input(EffectVerdict.E1_SIGNIFICANT,
                        AdherenceState.PASS, null, true, true)),
                new Case("effectVerdict 未定", input(null,
                        AdherenceState.PASS, RiskFlag.NONE, true, true)),
                new Case("coreMetricImproved 未录入", input(EffectVerdict.E1_SIGNIFICANT,
                        AdherenceState.PASS, RiskFlag.NONE, null, true)),
                new Case("adherenceState 未算", input(EffectVerdict.E1_SIGNIFICANT,
                        null, RiskFlag.NONE, true, true)));

        for (Case c : cases) {
            VerdictBranchEngine.BranchResult r = engine.route(c.in());
            assertEquals(VerdictBranch.HUMAN_REVIEW, r.branch(),
                    "🛑 " + c.name() + " ⇒ 必须挂起。任何『默认值』在这里都等于替人工做判断："
                            + "riskFlag 默认『无』会让 D4 该触发而不触发；"
                            + "coreMetricImproved 默认『无改善』会把每个未录入的客户推进 D3（重定方案）");
            assertEquals("D5", r.decisionRule(), c.name() + " 应命中 D5");
        }
    }

    @Test
    @DisplayName("🛑 路由绝不产出 null 分支：D5 是兜底而不是失败")
    void routing_never_returns_null_branch() {
        // 最空的输入：三件事实全缺 + 不可比
        VerdictBranchEngine.BranchResult r = engine.route(input(null, null, null, null, false));
        assertNotNull(r.branch(), "PRD §7.3 D5 的语义是『条件不满足/数据不足/测量不可比』—— "
                + "即任何情况都有归宿，不得返回 null（返回 null 会让调用方自己编一个兜底值）");
        assertNotNull(r.reason(), "挂起原因必非空（否则挂起在证据链里没有成因）");
        assertNotNull(r.disposition(), "处置必非空（快照里它有值，不写会让复盘分不清『没算』与『忘了记』）");
    }

    // ==================================================================
    // 二、组合出口穷举 —— 本类最重要的一组断言
    // ==================================================================

    @Test
    @DisplayName("🛑 穷举：没有任何自动路径会产出『退款终止』（P0-14）")
    void no_automatic_path_ever_yields_refund_termination() {
        int checked = 0;
        List<String> autoRaised = new ArrayList<>();

        for (EffectVerdict effect : EffectVerdict.values()) {
            // 🛑 E5 单独断言（它在自动路径上必须被拒，不是被接受后不产出）
            if (effect.mustBeHumanEntered()) {
                continue;
            }
            for (AdherenceState adherence : AdherenceState.values()) {
                for (RiskFlag risk : RiskFlag.values()) {
                    Disposition d = engine.resolveDisposition(effect, adherence, risk);
                    checked++;
                    assertNotNull(d, "组合 (" + effect + "," + adherence + "," + risk
                            + ") 必须有处置（P0-12 要求三 enum 组合必得出口）");
                    if (d.systemMayAutoRaise()) {
                        autoRaised.add(effect + "×" + adherence + "×" + risk + " → " + d.dbLabel());
                    }
                    assertNotEquals(Disposition.REFUND_TERMINATE, d,
                            "🛑 组合 (" + effect.label() + " × " + adherence.label() + " × " + risk.dbLabel()
                                    + ") 产出了『退款终止』。PRD P0-14 逐字："
                                    + "『效果类走协商工单，人在环、不可自动直出资格结论』—— "
                                    + "系统可以推出『效期无明显改善 + 依从达标』这样的组合，"
                                    + "但把那个组合变成『退款终止』必须由人在协商中做出");
                }
            }
        }
        assertEquals(4 * 3 * 4, checked,
                "穷举必须是全组合（4 个可自动的效果枚举 × 3 个依从状态 × 4 个风险标签）—— "
                        + "出现这个数不等于测试正确，但它能证伪『循环被提前 break 掉了』");

        // 正向断言：能自动提出的处置，必须都在"不产生对客户不利结论"的集合里
        Set<String> allowedAuto = new TreeSet<>();
        for (Disposition d : Disposition.values()) {
            if (d.systemMayAutoRaise()) {
                allowedAuto.add(d.dbLabel());
            }
        }
        assertEquals(Set.of("继续原方案", "调整后继续", "转基础服务", "建议就医"), allowedAuto,
                "🛑 自动可提出的处置集合必须【恰好】不含『退款终止』。"
                        + "它由 Disposition.systemMayAutoRaise 定义，"
                        + "而本断言把该定义钉死在『退款终止被排除』这件事上 —— "
                        + "若有人把 REFUND_TERMINATE 改成可自动提出，这里必红");
        assertFalse(autoRaised.isEmpty(),
                "至少要有一些组合是可自动提出的（否则本断言在证明一个空集 —— "
                        + "『没有自动路径产出退款终止』会因为根本没有自动路径而为真）");
    }

    @Test
    @DisplayName("🛑 组合出口拒空：三件事实缺任一项即抛（不得压平为单一 enum）")
    void combination_exit_rejects_missing_facts() {
        assertThrows(BizException.class,
                () -> engine.resolveDisposition(null, AdherenceState.PASS, RiskFlag.NONE),
                "PRD P0-12：判定出口是三 enum 的组合，缺 effect 就不是组合");
        assertThrows(BizException.class,
                () -> engine.resolveDisposition(EffectVerdict.E1_SIGNIFICANT, null, RiskFlag.NONE));
        assertThrows(BizException.class,
                () -> engine.resolveDisposition(EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, null));
    }

    @Test
    @DisplayName("🛑 组合出口拒 E5（必须人工录入，不得作为自动输入）")
    void combination_exit_rejects_e5() {
        BizException e = assertThrows(BizException.class,
                () -> engine.resolveDisposition(EffectVerdict.E5_WORSENED, AdherenceState.PASS, RiskFlag.NONE));
        assertTrue(e.getMessage().contains("人工录入"),
                "E5 的拒绝理由必须点明『必须人工录入』（指标规格 §3.2），实际: " + e.getMessage());
    }

    @Test
    @DisplayName("依从状态决定效果侧能否定调：未达标 / 样本不足时不给『继续原方案』的结论")
    void adherence_gates_the_effect_conclusion() {
        // 依从不足 ⇒ 即使是 E1 也调整后继续（原方案的可执行性有问题）
        assertEquals(Disposition.ADJUST_AND_CONTINUE,
                engine.resolveDisposition(EffectVerdict.E1_SIGNIFICANT,
                        AdherenceState.INSUFFICIENT, RiskFlag.NONE));
        // 样本不足 ⇒ 继续原方案（不是转基础服务：无数据时不得下对客户不利的结论）
        assertEquals(Disposition.KEEP_PLAN,
                engine.resolveDisposition(EffectVerdict.E1_SIGNIFICANT,
                        AdherenceState.SAMPLE_INSUFFICIENT, RiskFlag.NONE),
                "样本不足是『没数据』⇒ 处置是继续观察 + 补数据。"
                        + "🛑 给『转基础服务』会是一个在无数据时对客户不利的结论");
        // 依从达标 + 无改善 ⇒ 调整后继续（D3 的重定方案）
        assertEquals(Disposition.ADJUST_AND_CONTINUE,
                engine.resolveDisposition(EffectVerdict.E4_NO_IMPROVEMENT,
                        AdherenceState.PASS, RiskFlag.NONE));
    }

    // ==================================================================
    // 三、阈值零硬编码
    // ==================================================================

    @Test
    @DisplayName("🛑 路由引擎不持有退款门槛数值（消费状态而非比较 0.8）")
    void branch_engine_does_not_hold_the_refund_threshold() {
        BizException e = assertThrows(BizException.class,
                () -> VerdictBranchEngine.refundThresholdNotHeldHere());
        assertTrue(e.getMessage().contains("门槛由 AdherenceEngine"),
                "拒绝理由应指明门槛的归属（AdherenceEngine 从 config #4 消费），"
                        + "否则接手的人只知道『这里没有』而不知道『哪里有』。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 显著阈值恒 TBD（PRD 未给值，硬纪律 #6：不得编一个数）")
    void significant_threshold_is_always_tbd() {
        assertEquals(ContractTbd.TBD, VerdictBranchEngine.significantThreshold(),
                "PRD §7.3 只说『达显著阈值』而没给值；编一个『看起来合理』的阈值会让一个"
                        + "未经校准的数直接进入面客判定链，且它不会报错");
        for (VerdictBranch b : VerdictBranch.values()) {
            VerdictBranchEngine.BranchResult r = engine.route(branchInputFor(b));
            assertEquals(ContractTbd.TBD, r.significantThreshold(),
                    "分支 " + b.dbLabel() + " 的显著阈值必须是 TBD");
        }
    }

    // ==================================================================
    // 四、Fail-closed 解析（词汇表）
    // ==================================================================

    @Test
    @DisplayName("🛑 五个落库字面：parse 必须严格 round-trip，且未知字面一律抛（不回落）")
    void branch_literals_round_trip_and_fail_closed() {
        for (VerdictBranch b : VerdictBranch.values()) {
            assertEquals(b, VerdictBranch.parse(b.dbLabel()),
                    "parse(dbLabel) 必须 round-trip：落库字面 = " + b.dbLabel());
        }
        // 未知字面：必须抛，且不得回落成任何一支 —— 尤其不得回落 HUMAN_REVIEW
        for (String bogus : List.of("未知分支", "stable", "improved", "", "  ")) {
            assertThrows(BizException.class, () -> VerdictBranch.parse(bogus),
                    "🛑 未登记字面 '" + bogus + "' 必须抛。"
                            + "回落 HUMAN_REVIEW 最危险：它会把『有人用了系统不认识的分支名』"
                            + "静默变成一个看起来很正常的『挂起』");
        }
        assertThrows(BizException.class, () -> VerdictBranch.parse(null));
    }

    @Test
    @DisplayName("🛑 落库字面必须与契约 VerdictData.branch 的 enum 逐字相同（本域是『取哪个都一样』）")
    void branch_literals_match_contract_enum() {
        Set<String> contract = new LinkedHashSet<>(
                List.of("稳定", "依从不足", "达标无效", "全面评估", "人工复核"));
        Set<String> db = new LinkedHashSet<>(VerdictBranch.allDbLabels());
        assertEquals(contract, db,
                "契约 components.schemas.VerdictData.branch 的 enum 共 5 值，"
                        + "而库 CHECK 的 5 值与之逐字相同。🛑 这与退款域 entry 的情形相反"
                        + "（那边库侧 'A 门店代录' 有空格、契约侧 'A门店代录' 无空格，两套字面）。"
                        + "本域若有一天分叉，这条断言是唯一会响的地方");
    }

    @Test
    @DisplayName("🛑 落库字面必须与 V5 的 CHECK 5 值逐字一致（库是第二真相源）")
    void branch_literals_match_v5_check() {
        // V5 L551/L603 的 CHECK (branch IN (...)) / CHECK (verdict IN (...))
        Set<String> v5 = new LinkedHashSet<>(
                List.of("稳定", "依从不足", "达标无效", "全面评估", "人工复核"));
        assertEquals(v5, new LinkedHashSet<>(VerdictBranch.allDbLabels()),
                "V5 的两个 CHECK 都是这 5 个中文值；枚举字面一旦漂移，"
                        + "写入会得到 23514 而报错里只有约束名 —— 看不出是枚举漂了");
    }

    @Test
    @DisplayName("🛑 D1 的 PRD 展示表述（稳定·改善）与落库字面（稳定）必须分开持有")
    void prd_conclusion_differs_from_db_label_for_d1() {
        assertEquals("稳定", VerdictBranch.STABLE.dbLabel(),
                "入库字面必须是『稳定』（V5 CHECK 只认这两个字）");
        assertEquals("稳定·改善", VerdictBranch.STABLE.prdConclusion(),
                "PRD §7.3 D1 的表述是『稳定·改善』—— 两者不是同一个字符串，"
                        + "故必须分开持有。若把 prdConclusion 也填成『稳定』，"
                        + "『PRD 写稳定·改善而库里只有稳定』这个差异就没有可读的答案");
        assertNotEquals(VerdictBranch.STABLE.dbLabel(), VerdictBranch.STABLE.prdConclusion());
    }

    @Test
    @DisplayName("🛑 A-6 起 config #9 已对齐为 5 个中文键；枚举仍【不提供】 configKey() 映射")
    void config_key_mapping_is_deliberately_absent() {
        // 🛑 A-6（本批卡点收口）：config #9 的 branches 由 4 个英文键
        //    （improved/stable/no_improvement/worsened）改为 5 个中文键，
        //    与 VerdictBranch.dbLabel() 逐字一致 —— 收敛了长期存在的跨口径分叉。
        //
        //    原缺口：4 个英文键描述【效果走向】（与 effect_verdict 的 E1~E5 同族），
        //    而落库的 5 个值是【处置分支】（含"全面评估""人工复核"两个与效果走向
        //    无关的兜底）。强行映射（4→5）必然要凑数，故枚举刻意不提供 configKey()。
        //
        //    现在的做法是【把 config 侧改成与落库侧同一套词汇】，
        //    而不是让枚举去适配一个本就不完整的配置 —— 因为配置漏掉的
        //    '人工复核'（D5）是 PRD §7.3 里与 D1~D4 并列的一等分支。
        Set<String> configKeys = new LinkedHashSet<>(VerdictBranch.allDbLabels());
        Set<String> dbLabels = new LinkedHashSet<>(VerdictBranch.allDbLabels());

        assertEquals(5, configKeys.size(), "config #9 的 branches 现为 5 个中文键（含 D5 人工复核）");
        assertEquals(dbLabels, configKeys,
                "🛑 A-6 起 config #9 的五个分支字面必须与 VerdictBranch.dbLabel() 逐字一致。"
                        + "唯一的所有者是判定域枚举，配置只是它的一个副本 —— "
                        + "两处若分叉，写入会得到 23514，而报错里只有约束名，看不出是枚举漂了");

        // 🛑 机制上的证明仍然成立：枚举确实没有 configKey 这个方法。
        //    理由没有消失：分支字面是【中文落库值】，不是"引擎内部分档键"——
        //    给每个分支再配一个英文键只会造出第二个真相源。
        for (VerdictBranch b : VerdictBranch.values()) {
            Set<String> methods = new TreeSet<>();
            for (var m : b.getClass().getMethods()) {
                methods.add(m.getName());
            }
            assertFalse(methods.contains("configKey"),
                    "🛑 VerdictBranch 不得有 configKey() —— 分支字面已经是落库值本身，"
                            + "再配一个键就是第二份真相源。实际方法集: " + methods);
        }
    }

    // ==================================================================
    // 五、自描述不含阈值
    // ==================================================================

    @Test
    @DisplayName("🛑 describeRules 不含任何阈值数值（避免成为配置值的第二份副本）")
    void describe_rules_contains_no_threshold_values() {
        var rules = engine.describeRules();
        assertEquals(VerdictBranch.values().length, rules.size(),
                "自描述必须逐支给出（5 支）");
        String text = rules.toString();
        for (String numeric : List.of("0.8", "0.80", "0.571", "0.286", "0.143", "0.25", "0.50", "0.150")) {
            assertFalse(text.contains(numeric),
                    "🛑 自描述里出现了阈值/指数数值 '" + numeric + "' —— "
                            + "一旦出现，它就成了配置值的第二份副本，改配置不改此处时不报错。"
                            + "实际: " + text);
        }
        for (VerdictBranch b : VerdictBranch.values()) {
            assertNotNull(rules.get(b.decisionRule()),
                    "规则编号 " + b.decisionRule() + " 必须在自描述里（PRD §7.3 的编号是外部锚点）");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static VerdictBranchEngine.BranchInput input(
            EffectVerdict effect, AdherenceState adherence, RiskFlag risk,
            Boolean coreImproved, boolean comparable) {
        return new VerdictBranchEngine.BranchInput(effect, adherence, risk, coreImproved, comparable);
    }

    /** 为给定分支构造一个必然命中它的输入（用于遍历断言）。 */
    private static VerdictBranchEngine.BranchInput branchInputFor(VerdictBranch b) {
        return switch (b) {
            case STABLE -> input(EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, RiskFlag.NONE, true, true);
            case INSUFFICIENT_ADHERENCE ->
                    input(EffectVerdict.E1_SIGNIFICANT, AdherenceState.INSUFFICIENT, RiskFlag.NONE, true, true);
            case ADHERENT_BUT_INEFFECTIVE ->
                    input(EffectVerdict.E4_NO_IMPROVEMENT, AdherenceState.PASS, RiskFlag.NONE, false, true);
            case FULL_ASSESSMENT ->
                    input(EffectVerdict.E1_SIGNIFICANT, AdherenceState.PASS, RiskFlag.HIGH_RISK, true, true);
            case HUMAN_REVIEW -> input(null, null, null, null, false);
        };
    }

    @Test
    @DisplayName("补充：每个分支都有一个必然命中它的输入（自证 branchInputFor 不是瞎写的）")
    void every_branch_is_reachable_by_its_designed_input() {
        Set<VerdictBranch> covered = EnumSet.noneOf(VerdictBranch.class);
        for (VerdictBranch b : VerdictBranch.values()) {
            VerdictBranchEngine.BranchResult r = engine.route(branchInputFor(b));
            assertEquals(b, r.branch(),
                    "为分支 " + b.dbLabel() + " 设计的输入却路由到了 " + r.branch().dbLabel()
                            + " —— 五分支必须全部可达（有分支不可达说明决策表被改窄了）");
            covered.add(r.branch());
        }
        assertEquals(EnumSet.allOf(VerdictBranch.class), covered);
    }

    @Test
    @DisplayName("🛑 E5 在路由输入处即被拒（它是『E5 不得由系统自动产出』的第三道闸）")
    void branch_input_rejects_e5() {
        BizException e = assertThrows(BizException.class,
                () -> input(EffectVerdict.E5_WORSENED, AdherenceState.PASS, RiskFlag.NONE, true, true),
                "BranchInput 的紧凑构造器必须拒 E5 —— 收到它说明上游那道闸被绕过");
        assertTrue(e.getMessage().contains("E5") || e.getMessage().contains("加重"),
                "拒绝理由应点名 E5，实际: " + e.getMessage());
    }
}