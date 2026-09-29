package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * S1-5 效果判定引擎 —— 由<b>模块分数变化</b>推出 {@code effect_verdict} <b>候选</b>。
 *
 * <h2>它算的与它<b>不</b>算的（这条边界是本类的核心）</h2>
 * 指标规格 §3.2 给每个分支标注了"自动 / 人工"：
 * <pre>
 *   E1 显著改善   系统算 + 人工确认
 *   E2 部分改善   系统算 + 人工确认
 *   E3 稳定       系统可自动判定
 *   E4 无明显改善 系统可自动判定（核心困扰项由人工录入）
 *   E5 加重       必须人工录入（涉安全）
 * </pre>
 * 故本引擎的产物是 {@link VerdictCandidate} —— 一个<b>候选 + 是否需要人</b>的二元事实，
 * <b>不是</b>可直接落库的结论。它<b>不会</b>、也<b>不应当</b>产出 E5：
 * E5 涉安全（加重），必须由人工录入。若本引擎能算出 E5，
 * 一条加重的安全结论就会由某个批处理自动写进客户档案。
 *
 * <h2>判定量：Δ = S_base − S_cur（分数下降为正）</h2>
 * 分档来自 config {@code #33}（{@link DerivedMetricProfile.Mcid}）：
 * <pre>
 *   Δ ≥ improved.delta_min（3）        → 改善候选（E1 或 E2，需人工区分）
 *   stable.delta_min ≤ Δ ≤ delta_max   → E3 稳定（可自动）
 *   Δ ≤ −worsened.delta_rise_min（−1） → 加重侧（E4 或 E5，需人工）
 * </pre>
 * 🛑 <b>为什么 Δ ≥ 3 不能直接定为 E1</b>：E1/E2 的分界是"是否达到<b>显著</b>阈值"，
 * 而显著阈值在 PRD 里<b>没有给值</b>（规格 §3.2 只说"模块 IR ≥ 显著阈值"，
 * §1.4 的 MCID 只给了"改善"门槛）。这属"建议值需真实数据校准"范畴 ⇒
 * 按硬纪律 #6「TBD 不得填数」，本引擎<b>不编一个显著阈值</b>，
 * 而是输出 TBD 并标记"需人工确认"。待业务/临床给出校准值 → 配置里补 → 代码零改动。
 *
 * <h2>🛑 四项前置断言不满足即挂起（不计算）</h2>
 * 规格 §1.3 原文：「同源 = (基线题组 ID == 复评题组 ID) AND (量程 == 0–4) AND (测量人/工具同一)；
 * 若同源 == False → IR = NULL，判定 = 人工复核（PRD P0-12『测量不可比挂起』）」
 * 与 config {@code #32}（{@code cfg:improvement.calc_rule}）的
 * {@code same_origin_assert} 段逐字一致。本引擎把这四个断言做成入参，
 * 不满足即输出"挂起"而不是算一个数 —— 用两把尺量出来的"改善"是不可举证的结论。
 *
 * <h2>baseline_zero：不计入改善率（规格 §1.3 边界表第 1 行）</h2>
 * {@code S_base = 0} 时"无病可改善"，本不属疗效范围 → 转"新发症状"路径。
 * 若 {@code S_base = 0 且 S_cur > 0}，判加重侧并用<b>绝对变化量</b>表达
 * （不能用 ∞ 表百分比）。两种情形本引擎都显式返回，不折算成百分比。
 */
public class EffectVerdictEngine {

    /**
     * 同源前置断言（规格 §1.3 / config {@code #32} 的 {@code same_origin_assert}）。
     *
     * @param sameItemGroup    基线题组 ID 与复评题组 ID 是否<b>同源</b>
     * @param rangeMatches     量程是否与口径一致（config {@code #35} 的 {@code 0–4}）
     * @param sameMeasurer     测量人 / 工具是否同一
     */
    public record SameOriginAssert(boolean sameItemGroup, boolean rangeMatches, boolean sameMeasurer) {

        /** 四断言合一（规格 §1.3 的 AND 语义）。 */
        public boolean holds() {
            return sameItemGroup && rangeMatches && sameMeasurer;
        }

        /** 未通过的是哪一条（用于报错点名，不得只说"不可比"）。 */
        public List<String> failedAssertions() {
            List<String> failed = new java.util.ArrayList<>();
            if (!sameItemGroup) {
                failed.add("基线题组 ID ≠ 复评题组 ID");
            }
            if (!rangeMatches) {
                failed.add("量程与量程口径不一致");
            }
            if (!sameMeasurer) {
                failed.add("测量人 / 工具不同一");
            }
            return List.copyOf(failed);
        }
    }

    /**
     * 一次判定计算的原输入。
     *
     * @param sameOrigin   同源断言（不成立即挂起）
     * @param baseTotal    基线模块总分（0–{@code module_total_max}）；{@code null} 表示基线缺失（挂起）
     * @param currentTotal 复评模块总分（同量纲）；{@code null} 表示复评缺失（挂起）
     */
    public record ImprovementInput(SameOriginAssert sameOrigin, Integer baseTotal, Integer currentTotal) {
    }

    /**
     * 判定候选 —— <b>候选</b>而非结论（见类注释）。
     *
     * @param verdict            候选分支；<b>挂起时为 {@code null}</b>（不是 E3 —— 未知不等于稳定）
     * @param suspended          是否挂起（同源不成立 / 基线或复评缺失 / baseline_zero 走新发路径）
     * @param requiresHuman      该候选是否必须经人工才可落库
     * @param suspendReason      挂起原因（人话；挂起时必非空）
     * @param delta              分数变化量 Δ = base − current；挂起时为 {@code null}
     * @param improvementRate    改善率（bp 前的小数）；{@code baseline_zero} 或挂起时为 {@code null}
     * @param baselineZero       是否命中"基线为 0"（规格 §1.3 第一行）
     * @param significantThreshold {@code TBD} —— 显著性阈值未校准（硬纪律 #6）
     */
    public record VerdictCandidate(
            EffectVerdict verdict,
            boolean suspended,
            boolean requiresHuman,
            String suspendReason,
            Integer delta,
            BigDecimal improvementRate,
            boolean baselineZero,
            Object significantThreshold) {
    }

    /** 改善率的小数标度（展示用；举证的权威量是 Δ 与两个原始分）。 */
    /**
     * 改善率 IR 的标度 —— <b>展示/落库精度事实，非业务口径</b>。
     *
     * <p>规格 §1.3 只规定 IR 是「(S_base − S_cur) / S_base」这个<b>比值语义</b>，
     * 未规定保留位数；本引擎按 {@code NUMERIC(4,3)} 同族的六位标度输出，
     * 使下游做区间比较时不因截断产生假相等。它不来自任何 config 槽位。
     */
    public static final int RATE_SCALE = 6;

    private final DerivedMetricProfile profile;

    public EffectVerdictEngine(DerivedMetricProfile profile) {
        if (profile == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "效果判定引擎缺少派生口径（config #33 未装载）—— 拒绝按默认口径判定");
        }
        this.profile = profile;
    }

    /**
     * 推候选分支。
     *
     * <p>执行顺序：① 同源断言 → ② 缺失检查 → ③ 量纲校验 → ④ baseline_zero 分流 → ⑤ 分档。
     */
    public VerdictCandidate decide(ImprovementInput input) {
        if (input == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "效果判定输入不得为空");
        }

        // ① 同源断言 —— 不可比即挂起（PRD P0-12）
        if (input.sameOrigin() == null || !input.sameOrigin().holds()) {
            String reason = input.sameOrigin() == null
                    ? "同源断言缺失（未声明题组/量程/测量人是否同一）"
                    : "测量不可比：" + String.join("；", input.sameOrigin().failedAssertions());
            return suspended(reason);
        }

        // ② 基线 / 复评缺失即挂起（不得补 0 —— 硬纪律 #4）
        if (input.baseTotal() == null || input.currentTotal() == null) {
            return suspended("基线或复评模块分缺失（" 
                    + (input.baseTotal() == null ? "基线缺" : "")
                    + (input.currentTotal() == null ? (input.baseTotal() == null ? "、" : "") + "复评缺" : "")
                    + "）—— 缺失不得补 0，判定挂起转人工");
        }

        // ③ 量纲校验（越界不得截断）
        int base = input.baseTotal();
        int cur = input.currentTotal();
        int max = profile.mcid().moduleTotalMax();
        assertWithinModule(base, "基线", max);
        assertWithinModule(cur, "复评", max);

        // ④ baseline_zero 分流（规格 §1.3 边界表第 1、2 行）
        if (base == 0) {
            if (cur == 0) {
                // 基线 0 且复评 0：无病且仍无病 —— 不属疗效范围，仍是"不计算改善率"
                return new VerdictCandidate(null, true, false,
                        "基线模块总分为 0（无该模块症状）→ 该模块不进入改善率计算（baseline_zero，走『新发症状』路径）",
                        null, null, true, ContractTbd.TBD);
            }
            // 基线 0 且复评 > 0：新发 / 加重，用绝对量表达（不能用 ∞ 表百分比）
            int absDelta = cur;
            return new VerdictCandidate(EffectVerdict.E4_NO_IMPROVEMENT, true, true,
                    "基线为 0 且复评 > 0 → 新发症状（风险标签 new_onset）；"
                            + "用绝对变化量 Δ=" + absDelta + " 表达，不做百分比（不能以 ∞ 表之）。"
                            + "涉新发须人工确认分支（E4 / E5）",
                    absDelta, null, true, ContractTbd.TBD);
        }

        // ⑤ 分档（阈值全部来自 config #33）
        int delta = base - cur;   // 分数下降为正
        DerivedMetricProfile.Mcid mcid = profile.mcid();
        BigDecimal rate = BigDecimal.valueOf(delta)
                .divide(BigDecimal.valueOf(base), RATE_SCALE, RoundingMode.HALF_UP);

        if (delta >= mcid.improvedDeltaMin()) {
            // 改善侧：E1 还是 E2 取决于"是否达到显著阈值"，而显著阈值未校准 ⇒ TBD + 人工
            return new VerdictCandidate(null, false, true,
                    "达 MCID 改善门槛（Δ=" + delta + " ≥ " + mcid.improvedDeltaMin()
                            + "），但 E1（显著改善）与 E2（部分改善）的分界需『显著阈值』——"
                            + "该阈值在 PRD 中未给值，按硬纪律 #6 输出 TBD，分支由人工确认",
                    delta, rate, false, ContractTbd.TBD);
        }
        if (delta >= mcid.stableDeltaMin()) {
            // 稳定：系统可自动判定（E3）
            return new VerdictCandidate(EffectVerdict.E3_STABLE, false, false, null,
                    delta, rate, false, ContractTbd.TBD);
        }
        if (delta <= mcid.worsenedDeltaThreshold()) {
            // 加重侧：E4 与 E5 的区别是"是否出现新发/高危或题项上升 ≥2 级"——
            // 该信息不在分数里，必须人工判断；且 🛑 本引擎绝不自产 E5（涉安全）
            return new VerdictCandidate(EffectVerdict.E4_NO_IMPROVEMENT, false, true,
                    "分数上升（Δ=" + delta + " ≤ " + mcid.worsenedDeltaThreshold()
                            + "）→ 无明显改善 / 加重侧。E4 与 E5 之分取决于是否新发/高危"
                            + "或任一题项上升 ≥" + mcid.noItemRiseGe() + " 级 —— 须人工判断；"
                            + "🛑 E5（加重）涉安全，本引擎不得自动产出",
                    delta, rate, false, ContractTbd.TBD);
        }
        // 落在稳定档与加重档之间的空档：分档本应首尾相接，此处若到达说明口径被改坏
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "Δ=" + delta + " 落不进任何 MCID 分档（stable=[" + mcid.stableDeltaMin()
                        + "," + mcid.stableDeltaMax() + "]、improved≥" + mcid.improvedDeltaMin()
                        + "、worsened≤" + mcid.worsenedDeltaThreshold()
                        + "）—— config #33 分档出现空洞，必须修正配置而不是由代码兜底");
    }

    /** 供演示 / 回归展示"口径来自配置"（只读）。 */
    public DerivedMetricProfile profile() {
        return profile;
    }

    private static VerdictCandidate suspended(String reason) {
        return new VerdictCandidate(null, true, true, reason, null, null, false, ContractTbd.TBD);
    }

    private static void assertWithinModule(int score, String label, int max) {
        if (score < 0 || score > max) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    label + "模块总分越界: " + score + "（量纲 0–" + max
                            + "；越界不得截断 —— 截断会静默改分）");
        }
    }

    /** 只读口径视图（供断言"引擎里没有硬编码阈值"时比对来源）。 */
    public Map<String, Integer> mcidBoundaries() {
        DerivedMetricProfile.Mcid m = profile.mcid();
        return Map.of(
                "improvedDeltaMin", m.improvedDeltaMin(),
                "stableDeltaMin", m.stableDeltaMin(),
                "stableDeltaMax", m.stableDeltaMax(),
                "worsenedDeltaThreshold", m.worsenedDeltaThreshold(),
                "moduleTotalMax", m.moduleTotalMax());
    }
}