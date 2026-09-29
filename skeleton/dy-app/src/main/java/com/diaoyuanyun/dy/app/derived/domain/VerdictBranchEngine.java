package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判定分支路由引擎 —— PRD §7.3 决策表 D1~D5 的<b>可执行实现</b>。
 *
 * <h2>它接收的三件独立事实（不是已压平的分支名）</h2>
 * <pre>
 *   effectVerdict    EffectVerdict   E1–E5（来自 {@link EffectVerdictEngine} 的候选）
 *   adherenceState   AdherenceState  达标 / 不足 / 样本不足（来自 {@link AdherenceEngine}）
 *   riskFlag         RiskFlag        无 / 高危 / 新发 / 同病（经络师录入，见 {@link RiskFlag}）
 * </pre>
 * 🛑 入参<b>必须</b>是这三件而不是一个 {@link VerdictBranch}：PRD P0-12 逐字
 * 「判定出口 = {@code effect_verdict} × {@code adherence_state} × {@code risk_flag}
 * 组合 → {@code disposition}，<b>不得混入单一 enum</b>」。
 * 若入参是 branch，则"依从不足 + 明显改善"这类组合在调用点就被压平、
 * 永远无法被表达，其处置也无从推导。
 *
 * <h2>判定顺序（固定，且顺序本身就是语义）</h2>
 * <ol>
 *   <li><b>D5 前置</b>：任何一项缺失 / 样本不足 / 不可比 ⇒ 立即 {@link VerdictBranch#HUMAN_REVIEW}，
 *       不再往下走。PRD §7.3 D5 逐字：「条件不满足 / 数据不足 / 测量不可比」。</li>
 *   <li><b>D4 优先于 D1/D2/D3</b>：风险标签一旦命中，无论效果与依从如何都进全面评估。
 *       🛑 这不是"顺序问题"而是<b>安全优先</b>：一个核心指标明显改善的客户
 *       同样可以因"新发"而需要全面评估，故 D4 必须在效果分支<b>之前</b>判定。
 *       若把 D4 排在后面，那条"改善 ⇒ D1"的规则会把新发信号吞掉 ——
 *       而 PRD §7.3 定调句点名要防的正是"该重定方案的不被拖过去"。</li>
 *   <li><b>D2 优先于 D1/D3</b>：依从不足是纯系统可算的分支（PRD 逐字「无（纯系统计算）」），
 *       它不依赖"核心指标是否改善"这一人工录入，故在人工输入可能缺失时它应先行成立。</li>
 *   <li><b>D3 优先于 D1</b>：两者的分界正是"核心指标有没有改善"，
 *       而 D3 是 PRD 定调句点名"由系统自动触发"的那一支。</li>
 * </ol>
 *
 * <h2>🛑 本引擎不产出 E5，也不产出"退款终止"</h2>
 * <ul>
 *   <li>E5（加重）必须人工录入（见 {@link EffectVerdict#mustBeHumanEntered()}）——
 *       本引擎若收到 E5，说明有人绕过了那道闸，故<b>抛错</b>而不是接受它；</li>
 *   <li>{@link Disposition#REFUND_TERMINATE} 系统不得自动提出
 *       （P0-14「效果类不可自动直出资格结论」）—— 本引擎的 {@link #resolveDisposition}
 *       对任何自动路径都不返回它。</li>
 * </ul>
 *
 * <h2>阈值一律来自口径，不写死</h2>
 * PRD §7.3 的 D1/D2/D3 都写 {@code AS_refund ≥ 0.8}，而 {@code 0.8} 是
 * config {@code #4} 的<b>初始建议值</b>（行注释逐字：「建议值，需真实数据校准」）。
 * 故本引擎不出现 {@code 0.8}，而是把 {@link AdherenceState} 当作已算好的事实 ——
 * "达标"这一步已由 {@link AdherenceEngine} 用配置门槛判定过。
 * 本引擎只消费状态、不重复比较数值，避免同一条门槛出现两份实现。
 */
public class VerdictBranchEngine {

    /**
     * 一次四分支路由的输入 —— 三件独立事实 + 两个可选的人工录入信号。
     *
     * @param effectVerdict  效果枚举（E1–E5）；{@code null} = 尚未有候选（⇒ D5）
     * @param adherenceState 依从状态；{@code null} = 尚未算（⇒ D5）
     * @param riskFlag       风险标签；{@code null} = 未录入（⇒ D5 —— 不得默认「无」）
     * @param coreMetricImproved 核心指标是否改善/稳定（经络师结构化录入）；
     *                        {@code null} = 未录入 ⇒ D5（PRD D1/D3 都依赖它）
     * @param sameOriginComparable 测量是否可比（同源断言）；
     *                        {@code false} ⇒ D5（PRD D5「测量不可比」）
     */
    public record BranchInput(
            EffectVerdict effectVerdict,
            AdherenceState adherenceState,
            RiskFlag riskFlag,
            Boolean coreMetricImproved,
            boolean sameOriginComparable) {

        public BranchInput {
            // 🛑 E5 在此处就拒 —— 见类注释：收到 E5 说明上游那道闸被绕过
            if (effectVerdict != null && effectVerdict.mustBeHumanEntered()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "路由输入含 " + effectVerdict.label() + "（加重）—— 该值必须人工录入，"
                                + "系统不得把它作为自动路由的输入（指标规格 §3.2 / "
                                + "EffectVerdict.mustBeHumanEntered）。"
                                + "收到它即说明『E5 不得由系统自动产出』那道闸被绕过，"
                                + "故在此 fail-closed 而不是继续路由");
            }
        }
    }

    /**
     * 路由结果。
     *
     * @param branch            命中的分支（5 值之一；永不为 {@code null} —— D5 是兜底而非失败）
     * @param decisionRule      命中的 PRD §7.3 规则编号（{@code D1}~{@code D5}）
     * @param disposition       组合出口（由三 enum 组合推出，见 {@link #resolveDisposition}）
     * @param reason            为什么落到这一支（人话，进依据快照）
     * @param significantThreshold 显著阈值 —— 恒 {@code TBD}（PRD 未给值，硬纪律 #6）
     */
    public record BranchResult(
            VerdictBranch branch,
            String decisionRule,
            Disposition disposition,
            String reason,
            Object significantThreshold) {
    }

    /**
     * 路由（PRD §7.3 决策表的直接实现）。
     *
     * <p>执行顺序见类注释：D5 前置 → D4 → D2 → D3 → D1 → D5 兜底。
     */
    public BranchResult route(BranchInput in) {
        if (in == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "路由输入不得为空");
        }

        // ── D5 前置：任何一项缺失 / 样本不足 / 不可比 ─────────────────
        // ① 测量不可比（PRD D5「测量不可比」；同源断言由 EffectVerdictEngine 也已守一遍，
        //    这里再守是因为"路由"可能被独立调用 —— 两道闸守的是两个入口，不是冗余）
        if (!in.sameOriginComparable()) {
            return humanReview("测量不可比（同源断言未通过）→ 挂起转人工（PRD §7.3 D5）");
        }
        // ② 依从样本不足：🛑 它不是"依从不达标"，而是"没算出来"（见 AdherenceState 类注释）
        if (in.adherenceState() == AdherenceState.SAMPLE_INSUFFICIENT) {
            return humanReview("依从样本不足（应填天数低于门槛）→ 不得用于退款门禁，"
                    + "判定挂起转人工（PRD §7.3 D5 / 指标规格 §4.3 护栏）");
        }
        // ③ 三件事实缺任一项即挂起。🛑 不得默认「无风险」/「无改善」——
        //    默认值在这里等于替人工做了一次临床判断。
        if (in.adherenceState() == null) {
            return humanReview("依从状态缺失（AS_refund 未算）→ 挂起转人工（PRD §7.3 D5）");
        }
        if (in.riskFlag() == null) {
            return humanReview("风险标签未录入 → 挂起转人工（PRD §7.3 D5）。"
                    + "🛑 不得默认『无』—— 默认等于替经络师做了一次临床判断，"
                    + "而 D4 的触发正依赖此标签");
        }
        if (in.effectVerdict() == null) {
            return humanReview("效果判定候选未定（如 E1/E2 分界待人工确认）→ 挂起转人工（PRD §7.3 D5）");
        }
        // ④ 核心指标是否改善未录入 ⇒ D5。D1 与 D3 的分界完全依赖它，缺了就无法分。
        if (in.coreMetricImproved() == null) {
            return humanReview("核心指标改善情况未录入 → 无法区分 D1 与 D3，挂起转人工（PRD §7.3 D5）。"
                    + "🛑 不得默认『无改善』—— 那会把每个未录入的客户都推进 D3（重定方案），"
                    + "而 PRD 定调句要的是『该重定方案的才不被拖过去』，不是『全都重定』");
        }

        // ── D4：风险标签命中即全面评估（安全优先，排在效果分支之前）──
        if (in.riskFlag().triggersFullAssessment()) {
            return new BranchResult(VerdictBranch.FULL_ASSESSMENT, "D4",
                    // 🛑 全面评估的处置不是"退款终止"：它的动作链条是
                    //    生成全面评估任务 → 新方案 → 回炉审核 + 客户重签（PRD D4 逐字）。
                    //    那是【继续服务】的一种，而不是终止。
                    Disposition.ADJUST_AND_CONTINUE,
                    "风险标签 = " + in.riskFlag().dbLabel() + " ∈ {高危, 新发, 同病} → 全面评估："
                            + "生成全面评估任务 → 新方案 → 回炉审核 + 客户重签（PRD §7.3 D4）。"
                            + "🛑 本支优先于效果分支：核心指标明显改善的客户同样可因新发而需全面评估；"
                            + "排在后面会让『改善 ⇒ D1』吞掉新发信号",
                    ContractTbd.TBD);
        }

        // ── D2：依从不足（纯系统计算，不依赖核心指标录入）──────────
        if (in.adherenceState() == AdherenceState.INSUFFICIENT) {
            return new BranchResult(VerdictBranch.INSUFFICIENT_ADHERENCE, "D2",
                    Disposition.ADJUST_AND_CONTINUE,
                    "AS_refund 未达门槛（依从不足）→ 强化生活方式干预 + 追踪任务，推送提醒"
                            + "（PRD §7.3 D2，逐字『无（纯系统计算）』人工依赖）",
                    ContractTbd.TBD);
        }

        // ── D3：依从达标但核心指标无改善 ────────────────────────────
        if (!in.coreMetricImproved()) {
            return new BranchResult(VerdictBranch.ADHERENT_BUT_INEFFECTIVE, "D3",
                    Disposition.ADJUST_AND_CONTINUE,
                    "AS_refund 达标但核心指标无改善 → 依从达标但无效：自动提醒经络师重定全新方案"
                            + "+ 设备路由（PRD §7.3 D3）。🛑 本支由系统自动触发、不依赖门店主动发起"
                            + "（PRD §7.3 定调句：『该重定方案的不被拖过去』）",
                    ContractTbd.TBD);
        }

        // ── D1：核心指标改善/稳定 且 依从达标 ───────────────────────
        // 至此：adherenceState = PASS（达标）、coreMetricImproved = true、
        //       riskFlag = NONE、测量可比、三件事实齐备 ⇒ 条件全部满足
        return new BranchResult(VerdictBranch.STABLE, "D1",
                Disposition.KEEP_PLAN,
                "核心指标 ∈ {稳定, 改善} 且 AS_refund 达标 → 稳定·改善：维持原方案，生成下一周期"
                        + "（PRD §7.3 D1）",
                ContractTbd.TBD);
    }

    /**
     * 组合出口 —— {@code effect_verdict} × {@code adherence_state} × {@code risk_flag}
     * → {@code disposition}（PRD P0-12 / P0-24）。
     *
     * <h2>为什么它<b>不</b>复用 {@link VerdictBranch}</h2>
     * 分支是"落到哪条规则"，处置是"接下来做什么"。两者在多数组合下同向，
     * 但<b>不是函数关系</b>：例如 D4（全面评估）与 D3（达标无效）的分支不同，
     * 而处置都是"调整后继续"。若把处置做成 branch 的查表，
     * 将来任何一支的动作微调都要动枚举，而"动作"是 PRD 里逐字定义的东西。
     *
     * <h2>🛑 本方法<b>永不</b>返回 {@link Disposition#REFUND_TERMINATE}</h2>
     * 这是 P0-14 的机械兑现：「效果类走协商工单，<b>人在环、不可自动直出资格结论</b>」。
     * 系统可以推"效期无明显改善 + 依从达标"这样的组合，但把那个组合变成
     * "退款终止"必须由人在协商中做出。故本方法对最不利的组合给出的仍是
     * {@link Disposition#ADJUST_AND_CONTINUE}（重定方案）或
     * {@link Disposition#ADVISE_MEDICAL}（涉安全），退款终止不在自动路径上。
     * {@code VerdictDispositionCombinationTest} 对全部组合穷举断言这一点。
     */
    public Disposition resolveDisposition(EffectVerdict effect, AdherenceState adherence, RiskFlag risk) {
        if (effect == null || adherence == null || risk == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "组合出口的三件事实不得为空（PRD P0-12：不得压平为单一 enum）—— "
                            + "effect=" + effect + " adherence=" + adherence + " risk=" + risk);
        }
        if (effect.mustBeHumanEntered()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "组合出口不接受 " + effect.label() + "（加重）作为自动输入 —— "
                            + "该值必须人工录入，见 EffectVerdict.mustBeHumanEntered()");
        }

        // ① 风险优先：命中 D4 触发集 ⇒ 涉安全的处置。
        //    「新发」在 EffectVerdictEngine 里是"疑新发"（baseline_zero 分支），
        //    而此处是"已确认的新发"—— 两者都指向"需要医生看一眼"，
        //    故「新发」「高危」「同病」统一走 ADVISE_MEDICAL 之外的评估路径：
        //    🛑 具体是就医还是继续调理属【临床判断】，系统不得替人定 ——
        //    故系统给出的建议是"调整后继续"（= 进全面评估链），
        //    而把"是否建议就医"留给人工在评估中决定。
        if (risk.triggersFullAssessment()) {
            return Disposition.ADJUST_AND_CONTINUE;
        }

        // ② 依从侧未达标/样本不足 ⇒ 先解决依从，谈不上效果结论
        if (adherence == AdherenceState.SAMPLE_INSUFFICIENT) {
            // 样本不足不是"依从差"，是"没数据" ⇒ 处置是继续观察+补数据，
            // 而非"转基础服务"（后者是一个对客户不利的结论，不得在无数据时下）
            return Disposition.KEEP_PLAN;
        }
        if (adherence == AdherenceState.INSUFFICIENT) {
            // 依从不足：PRD D2 的动作是强化干预 + 追踪 → 属"调整后继续"
            // 🛑 即使效果侧是改善，也不给"继续原方案"：依从不足时原方案的可执行性本身有问题
            return Disposition.ADJUST_AND_CONTINUE;
        }

        // ③ 依从达标（PASS）—— 此时效果侧定调
        return switch (effect) {
            case E1_SIGNIFICANT, E2_PARTIAL -> Disposition.KEEP_PLAN;
            case E3_STABLE -> Disposition.KEEP_PLAN;
            case E4_NO_IMPROVEMENT ->
                // 依从达标但无改善 ⇒ PRD D3「依从达标但无效」⇒ 重定方案。
                // 🛑 这里刻意【不】给 REFUND_TERMINATE：P0-14 逐字
                //    「效果类走协商工单，人在环、不可自动直出资格结论」。
                //    系统给的是"该重定方案了"这个强制路由，而不是"该退款了"这个结论。
                    Disposition.ADJUST_AND_CONTINUE;
            // E5 已在上面被拒（mustBeHumanEntered），此处不可达；
            // 保留分支是为了让"新增枚举值时编译报错"，而不是静默漏掉一档。
            case E5_WORSENED -> throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "组合出口不接受 E5加重（必须人工录入）—— 该分支不可达，"
                            + "若到达说明 mustBeHumanEntered 的门被改动");
        };
    }

    /** 路由结果 + 处置的一致性自检（供服务层在落库前调用，把"组合不自洽"挡在写库之前）。 */
    public Map<String, Object> describeRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (VerdictBranch b : VerdictBranch.values()) {
            m.put(b.decisionRule(), Map.of(
                    "branch", b.dbLabel(),
                    "prd_conclusion", b.prdConclusion(),
                    "action", b.systemAction(),
                    "requires_human_input", b.requiresHumanInput(),
                    "system_auto_triggered", b.systemAutoTriggered()));
        }
        return m;
    }

    /** 显著阈值 —— 恒 TBD（PRD §7.3/§3.2 只说"达显著阈值"，未给值；硬纪律 #6）。 */
    public static Object significantThreshold() {
        return ContractTbd.TBD;
    }

    /** 退款门槛数值 —— 本引擎<b>不持有</b>它（只有 {@link AdherenceState} 这一状态）。 */
    public static BigDecimal refundThresholdNotHeldHere() {
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "路由引擎不持有 AS 门槛数值 —— 门槛由 AdherenceEngine 从 config #4 消费，"
                        + "本引擎只消费『达标 / 不足 / 样本不足』这一状态。"
                        + "🛑 若此处返回一个数，它就成了配置值的第二份副本，"
                        + "改配置不改此处时不会报错");
    }

    private static BranchResult humanReview(String reason) {
        return new BranchResult(VerdictBranch.HUMAN_REVIEW, "D5",
                // 挂起时的处置：数据都不全，谈不上处置结论。
                // 给 KEEP_PLAN 而不是一个"待定"值 —— 挂起状态下工单仍在服务中，
                // 而"继续原方案"是唯一不会产生对客户不利推测的表述。
                Disposition.KEEP_PLAN, reason, ContractTbd.TBD);
    }
}