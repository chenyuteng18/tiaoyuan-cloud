package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S1-5 派生口径 —— 归一化自 config {@code #4 / #5 / #6 / #7 / #33 / #35 / #45}。
 *
 * <h2>为什么口径必须外置（PRD §10 的硬性要求）</h2>
 * PRD §10 对全部 46 个配置项的总要求是「<b>所有业务数字不得硬编码</b>」。
 * 派生口径是其中<b>后果最重</b>的一批：门槛 AS（{@code #4}）、MCID（{@code #33}）、
 * 缺失策略（{@code #6}）、样本护栏（{@code #7}）四项直接决定
 * "客户依从算不算达标""有没有改善""能不能进退款门禁"。
 * 它们若写进代码，业务方每次校准阈值都要走一次发版；写进配置，只改一行值。
 *
 * <h2>本记录不自行定义任何数值</h2>
 * 全部数值来自配置声明值。{@link #fromRawConfig} 是唯一构造入口，
 * 且<b>逐项 fail-closed</b>：任一必需字段缺失即抛，绝不设默认值。
 * 设默认值等于把"口径丢失"静默成"口径是默认的那套" —— 而 AS 门槛与 MCID
 * 正是标注为「建议值，需真实数据校准」的东西，它们<b>还没有</b>一个可信的默认。
 *
 * <h2>三类自洽断言（本类的主要价值所在）</h2>
 * 配置被改坏时，"取到值了但值是坏组合"比"取不到值"危险得多 ——
 * 后者会抛，前者会算出一个看起来正常的数。故本类在构造期强制三类断言：
 * <ol>
 *   <li><b>组内自洽</b>：权重求和为 1、分档首尾相接无洞、
 *       {@code m} 的 {@code base + span = 1}、{@code floor = base}。</li>
 *   <li><b>合规锁</b>：{@code AS_refund} 权重<b>必须</b>恰好含 {@code A1/A3/A4}
 *       且<b>不含 A2</b>，并校验 {@code A2_in_AS_ops_only} 与
 *       {@code AS_refund_mode_independent} 两个标志为真。
 *       🛑 这不是"风格检查"，而是指标规格 §4.2 那个合规风险的机械堵口：
 *       一旦 A2 进入 {@code AS_refund}，"有端/无端门店同一客户得相反结论"
 *       就会静默复活 —— 那是系统性歧视未上端门店的客户（规格 §4.2 逐字）。</li>
 *   <li><b>跨源同源</b>：{@code #7} 的样本护栏与 {@code #45} 的 {@code n}
 *       必须同值；{@code #33} 的 {@code Δ=3} 与 {@code #45} 的 {@code m.delta_gap}
 *       必须同值 —— 配置 {@code #33} 行注释逐字要求
 *       「两处必须同源，改一处必须改另一处，否则 #45 的『以 Δ=3 为唯一谷底』不再成立」。</li>
 * </ol>
 *
 * <h2>🛑 本类不含"看起来合理"的兜底</h2>
 * 例如 {@code #45} 的可选双谷式（规格 §10.4③ 明示"默认不采用"）本类<b>不实现</b>；
 * {@code severity_bands} 式的那种"未配置就编一组边界"在这里同样被禁。
 * 未配置 ⇒ 抛错（配置缺失），而不是 ⇒ 按某个默认算。
 *
 * @param passThreshold   依从达标门槛（config {@code #4}，判定口径 = {@code AS_refund}）
 * @param asRefundWeights {@code AS_refund} 权重：恰好 {@code {A1,A3,A4}}，和为 1（固定不含 A2）
 * @param asOpsWeights    {@code AS_ops} 权重：{@code {A1,A2,A3,A4}}，和为 1（仅运营看板）
 * @param missingPolicy   缺失值处理策略（config {@code #6}）
 * @param minSampleDays   样本护栏：应填天数低于此值标"样本不足"（config {@code #7}）
 * @param mcid            MCID 最小可察觉改善门槛（config {@code #33}）
 * @param confidence      判定置信度合成口径（config {@code #45}）
 * @param source          口径来源自描述（进日志/证据）
 */
public record DerivedMetricProfile(
        BigDecimal passThreshold,
        Map<String, BigDecimal> asRefundWeights,
        Map<String, BigDecimal> asOpsWeights,
        MissingPolicy missingPolicy,
        int minSampleDays,
        Mcid mcid,
        Confidence confidence,
        String source) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 权重和必须为 1 时的可接受误差 —— 逐位精确会让 {@code 0.571+0.286+0.143} 这类十进制写法无法通过。 */
    private static final BigDecimal WEIGHT_SUM_TOLERANCE = new BigDecimal("0.001");

    /**
     * 缺失值处理策略（config {@code #6}）。
     *
     * <h2>两值的语义差别，以及它为什么必须可配</h2>
     * 配置 {@code #6} 原文：「结构性缺失不扣分 / 行为性缺失计 0」。
     * <ul>
     *   <li>{@link #STRUCTURAL_KEEP} —— <b>结构性</b>缺失（载体根本不存在，如无端门店没有
     *       小程序填报这一项）不扣分：它退出分母并重归一。这是 §4.2 那个算例
     *       （Mode L 下 A2 结构性缺失、A1/A3/A4 重归一到 1.00）的机制来源。</li>
     *   <li>{@link #BEHAVIORAL_ZERO} —— <b>行为性</b>缺失（载体存在但客户没做）计 0 分：
     *       它保留在分母里。把行为性缺失也"不扣分"，等于让"客户没配合"变成"不适用"，
     *       依从性指标会系统性虚高。</li>
     * </ul>
     * 二者选错不会报错，只会让 AS 偏一个方向 —— 故它是配置项而不是代码分支。
     */
    public enum MissingPolicy {
        STRUCTURAL_KEEP("structural_keep"),
        BEHAVIORAL_ZERO("behavioral_zero");

        private final String code;

        MissingPolicy(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static List<String> allCodes() {
            return java.util.Arrays.stream(values()).map(MissingPolicy::code).toList();
        }

        public static MissingPolicy parse(String code) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "缺失值处理策略未配置（cfg:adherence.missing_policy / config #6）");
            }
            return java.util.Arrays.stream(values())
                    .filter(p -> p.code.equals(code.trim()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "缺失值处理策略不在允许值内: " + code + "（合法值: " + allCodes() + "）"));
        }
    }

    /**
     * MCID 最小可察觉改善门槛（config {@code #33}）。
     *
     * <p>分档对象是<b>模块总分变化量</b> {@code Δ = S_base − S_cur}（规格 §1.3 的公式取号：
     * 分数<b>下降</b>为正 {@code Δ}）。三档<b>完整覆盖整数轴</b>：
     * <pre>
     *   Δ ≥ improvedDeltaMin              → 改善候选（E1/E2）
     *   stableDeltaMin ≤ Δ ≤ stableDeltaMax → 稳定（E3）
     *   Δ ≤ −worsenedRiseMin              → 无明显改善 / 加重（E4/E5）
     * </pre>
     * 两条接缝都必须<b>刚好相接</b>：
     * {@code improvedDeltaMin == stableDeltaMax + 1}（上接缝）与
     * {@code stableDeltaMin == 1 − worsenedRiseMin}（下接缝）。
     * 任一处断开就会出现"某个 Δ 落不进任何档"的洞 —— 配置可行、算不出来、
     * 运行时才抛 5001。🛑 下接缝的洞尤其隐蔽：{@code stable.delta_min} 若被写成 1，
     * 漏掉的是 {@code Δ = 0}（"无变化"，临床最常见的结果）——
     * 它不是"少算一档"，而是把最常见情形变成一次 500。
     *
     * @param moduleTotalMax 模块满分（规格 §1.4「每模块 4 题（0–16）」）
     * @param improvedDeltaMin 判为改善的最小下降分（规格 §1.4：下降 ≥ 3 分）
     * @param improvedPct     对应的百分比（仅供参考展示；本类不据此判定）
     * @param noItemRiseGe    "无任一题项上升 ≥ N 级"的 N（规格 §1.4：N = 2）
     * @param stableDeltaMin  稳定档下界（规格 §1.4：下降 1–2 分<b>或不变</b>，故当前值为 0）
     * @param stableDeltaMax  稳定档上界
     * @param worsenedRiseMin 判为加重的<b>上升</b>最小分（规格 §1.4：上升 ≥ 1 分）
     */
    public record Mcid(
            int moduleTotalMax,
            int improvedDeltaMin,
            BigDecimal improvedPct,
            int noItemRiseGe,
            int stableDeltaMin,
            int stableDeltaMax,
            int worsenedRiseMin) {

        public Mcid {
            if (improvedDeltaMin != stableDeltaMax + 1) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "MCID 分档不首尾相接：improved.delta_min=" + improvedDeltaMin
                                + " 但 stable.delta_max+1=" + (stableDeltaMax + 1)
                                + " —— 存在落不进任何档的 Δ（config #33）");
            }
            // 🛑 第二处接缝（stable 下界 ↔ worsened 上界）—— 初版只守了上面那一处，
            // 于是 config #33 写成 stable.delta_min=1 而 worsened.delta_rise_min=1 时
            // 无人报错：Δ=0（"无变化"，临床最常见的结果）既不 ≥1 也不 ≤−1，
            // 落不进任何档 → 判定引擎在运行时抛 5001。
            // 缺口不是"少算一档"，而是把最常出现的那个 Δ 变成一次 500。
            // 判据来自规格 §1.4 的分档表（worsened 是 Δ ≤ −worsenedRiseMin，
            // stable 必须从它的下一个整数接起）。
            if (stableDeltaMin != 1 - worsenedRiseMin) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "MCID 分档不首尾相接（下半接缝）：stable.delta_min=" + stableDeltaMin
                                + " 但 1 − worsened.delta_rise_min=" + (1 - worsenedRiseMin)
                                + " —— 两者不等即存在落不进任何档的 Δ"
                                + "（如 Δ=0『无变化』会被漏掉，判定引擎只能抛 5001）。"
                                + "config #33 的 Δ 分档必须完整覆盖整数轴（规格 §1.4）");
            }
            if (worsenedRiseMin < 1) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "MCID 加重档的上升门槛至少为 1（0 会让『无变化』被判成加重）：worsened.delta_rise_min="
                                + worsenedRiseMin + "（config #33）");
            }
            if (stableDeltaMin > stableDeltaMax) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "MCID 稳定档区间为空：delta_min=" + stableDeltaMin
                                + " 大于 delta_max=" + stableDeltaMax
                                + " —— 稳定档必须至少含一个 Δ（config #33）");
            }
        }

        /** 加重侧闸值（Δ ≤ 此值 ⇒ 加重）：{@code −worsenedRiseMin}。 */
        public int worsenedDeltaThreshold() {
            return -worsenedRiseMin;
        }
    }

    /**
     * 判定置信度合成口径（config {@code #45}，细则权威源 = 指标规格 §10.4）。
     *
     * <p>合成式 {@code C = s × d^eD × n^eN × m^eM}（加权<b>几何</b>式）：
     * 任一因子趋 0 则 C 趋 0，与"最弱项定调"一致。指数与各项取值<b>全部</b>从配置读，
     * 本类不写死 {@code 0.25/0.50} —— 它们是 {@code #45} 的
     * {@code composition} 字符串里的声明值。
     *
     * @param exponents    合成式指数：键 {@code s/d/n/m} → 指数（{@code s} 无 {@code ^} 时指数为 1）
     * @param sameOrigin   {@code s} 同源状态取值：一票否决（同源 / 缺元数据 / 不可比）
     * @param incomparableForcesHuman 不可比是否强制转人工且 {@code effect_verdict} 置空（规格 §10.4①）
     * @param answered     题组完成度的分母（实际答题数 / 此值）
     * @param nMinSampleDays 依从样本量门槛（须与 config {@code #7} 同值）
     * @param expectedDaysDenominator {@code n = min(1, 应填天数 / 此值)} 的分母（从 {@code formula} 解析）
     * @param reweightWhenNa {@code n} 不适用时的指数重归一（规格 §10.4①：d : m = 1/3 : 2/3）
     * @param mBase        {@code m} 地板（规格 §10.4②：Δ=3 处的唯一全局最小值）
     * @param mSpan        {@code m} 斜坡幅宽（{@code base + span} 应恰为 1）
     * @param mDeltaGap    {@code m} 的谷底 Δ（须与 config {@code #33} 的 MCID 同值）
     * @param highMin      展示层"高"档下界（规格 §10.4①：≥0.75）
     * @param mediumMin    展示层"中"档下界（≥0.45）
     */
    public record Confidence(
            Map<String, BigDecimal> exponents,
            BigDecimal sameOrigin,
            BigDecimal missingMetadata,
            BigDecimal incomparable,
            boolean incomparableForcesHuman,
            int answered,
            int nMinSampleDays,
            int expectedDaysDenominator,
            Map<String, BigDecimal> reweightWhenNa,
            BigDecimal mBase,
            BigDecimal mSpan,
            int mDeltaGap,
            BigDecimal highMin,
            BigDecimal mediumMin) {

        public Confidence {
            if (mBase.add(mSpan).subtract(BigDecimal.ONE).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "置信度 m 的 base + span 必须为 1（地板 + 斜坡幅宽）：base=" + mBase
                                + " span=" + mSpan + "（config #45 m 段）");
            }
            if (mBase.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "置信度 m 的地板必须严格大于 0 —— 0 会与『挂起 C=0』不可区分（规格 §10.4③附）：base=" + mBase);
            }
            if (incomparable.compareTo(BigDecimal.ZERO) != 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "『不可比』的 s 取值必须为 0（一票否决、C 归 0）：实际=" + incomparable);
            }
            if (!incomparableForcesHuman) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "『不可比』必须强制转人工（config #45 s.incomparable_forces_human_and_null_verdict）—— "
                                + "量程不可比却自动出一个判定，等于用两把尺量出结论");
            }
            if (sameOrigin.compareTo(BigDecimal.ONE) != 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "『同源』的 s 取值必须为 1：实际=" + sameOrigin);
            }
            if (expectedDaysDenominator <= nMinSampleDays) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "依从样本量满档所需天数（" + expectedDaysDenominator
                                + "）必须严格大于最小样本天数（" + nMinSampleDays
                                + "）—— 否则『样本不足』与『满档』在同一区间重叠（config #45 n 段）");
            }
            if (highMin.compareTo(mediumMin) <= 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "置信度展示档界须 high_min > medium_min：high=" + highMin + " medium=" + mediumMin);
            }
            if (!exponents.keySet().containsAll(Set.of("s", "d", "n", "m"))) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "置信度合成式必须含 s/d/n/m 四项因子：实际=" + exponents.keySet()
                                + "（config #45 composition）");
            }
        }

        /** {@code s} 的一票否决语义：{@code s = 0 → C = 0} 且判定强制转人工。 */
        public boolean isSuspended(BigDecimal sValue) {
            return sValue.compareTo(BigDecimal.ZERO) == 0;
        }
    }

    // ==================================================================
    // 唯一构造入口
    // ==================================================================

    /**
     * 唯一构造入口：从六段原始声明值归一化。
     *
     * <p><b>逐项 fail-closed</b>：任一段缺失即抛并点名缺失项，绝不设默认值。
     */
    public static DerivedMetricProfile fromRawConfig(DerivedRawConfig raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "派生口径来源为空 —— 拒绝按默认口径计算依从性 / AS / 效果判定");
        }
        if (!raw.isComplete()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "派生口径声明缺失，缺失项: " + raw.missingKeys()
                            + " —— 拒绝按默认口径计算（门槛/权重/缺失策略/样本护栏/MCID/置信度任一缺失都不得兜底）");
        }

        BigDecimal passThreshold = parseDecimal(raw.passThreshold(), "cfg:adherence.pass_threshold / config #4");
        if (passThreshold.compareTo(BigDecimal.ZERO) <= 0 || passThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "依从达标门槛应在 (0, 1] 内（AS 是 0–1 的加权和）：实际=" + passThreshold + "（config #4）");
        }

        JsonNode weights = readJson(raw.weightsJson(), "cfg:adherence.weights / config #5");
        Map<String, BigDecimal> asRefund = parseWeights(weights.path("AS_refund"), "AS_refund");
        Map<String, BigDecimal> asOps = parseWeights(weights.path("AS_ops"), "AS_ops");

        // ① 合规锁：AS_refund 固定不含 A2（指标规格 §4.2 / §4.3）
        Set<String> refundExpected = new TreeSet<>(Set.of("A1", "A3", "A4"));
        if (!asRefund.keySet().equals(refundExpected)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "AS_refund 权重必须恰为 {A1,A3,A4}：实际=" + asRefund.keySet()
                            + "。🛑 AS_refund 固定不含 A2 —— 一旦 A2 进入判定口径，"
                            + "『有端/无端门店同一客户得相反结论』的合规风险即刻复活"
                            + "（指标规格 §4.2，系统性歧视未上端门店客户）");
        }
        Set<String> opsExpected = new TreeSet<>(Set.of("A1", "A2", "A3", "A4"));
        if (!asOps.keySet().equals(opsExpected)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "AS_ops 权重必须恰为 {A1,A2,A3,A4}：实际=" + asOps.keySet() + "（config #5）");
        }
        requireTrue(weights, "A2_in_AS_ops_only",
                "A2 必须只存在于 AS_ops、不参与判定（指标规格 §4.3）");
        requireTrue(weights, "AS_refund_mode_independent",
                "AS_refund 必须与模式无关（有端/无端门店同一客户得一致结论，指标规格 §4.3）");
        assertWeightsSumToOne(asRefund, "AS_refund");
        assertWeightsSumToOne(asOps, "AS_ops");

        MissingPolicy missingPolicy = MissingPolicy.parse(raw.missingPolicy());
        int minSampleDays = parsePositiveInt(raw.minSampleDays(), "cfg:adherence.min_sample_days / config #7");

        Mcid mcid = parseMcid(readJson(raw.mcidThresholdJson(), "cfg:verdict.mcid_threshold / config #33"));
        Confidence confidence = parseConfidence(
                readJson(raw.confidenceJson(), "cfg:verdict.confidence_formula / config #45"));

        // ③ 跨源同源（配置 #33 / #45 行注释逐字要求）
        if (confidence.nMinSampleDays() != minSampleDays) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "样本护栏不同源：config #7 min_sample_days=" + minSampleDays
                            + " 与 config #45 n.min_sample_days=" + confidence.nMinSampleDays()
                            + " 不一致 —— 两处必须同值（config #7 行注释）");
        }
        if (confidence.mDeltaGap() != mcid.improvedDeltaMin()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "MCID 不同源：config #33 improved.delta_min=" + mcid.improvedDeltaMin()
                            + " 与 config #45 m.delta_gap=" + confidence.mDeltaGap()
                            + " 不一致 —— 配置 #33 行注释要求『两处必须同源，改一处必须改另一处，"
                            + "否则 #45 的以 Δ=3 为唯一谷底不再成立』");
        }
        assertMcidMatchesScaleStructure(mcid);
        if (confidence.answered() != ScaleStructure.requiredItemCount()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "题组完成度分母不同源：config #45 d.answered=" + confidence.answered()
                            + " 但同源复评题数（维度数 × 每维题数）=" + ScaleStructure.requiredItemCount()
                            + "（PRD P0-11 同源复评题数 / config #35）");
        }

        return new DerivedMetricProfile(passThreshold, asRefund, asOps, missingPolicy, minSampleDays,
                mcid, confidence, "config#4+#5+#6+#7+#33+#35+#45");
    }

    // ==================================================================
    // 解析
    // ==================================================================

    private static Mcid parseMcid(JsonNode root) {
        int moduleTotalMax = requireInt(root, "module_total_max", "module_total_max");
        JsonNode improved = root.path("improved");
        JsonNode stable = root.path("stable");
        JsonNode worsened = root.path("worsened");
        return new Mcid(
                moduleTotalMax,
                requireInt(improved, "delta_min", "improved.delta_min"),
                requireDecimal(improved, "pct", "improved.pct"),
                requireInt(improved, "no_item_rise_ge", "improved.no_item_rise_ge"),
                requireInt(stable, "delta_min", "stable.delta_min"),
                requireInt(stable, "delta_max", "stable.delta_max"),
                requireInt(worsened, "delta_rise_min", "worsened.delta_rise_min"));
    }

    private static Confidence parseConfidence(JsonNode root) {
        JsonNode composition = root.path("composition");
        if (!composition.isTextual() || composition.asText().isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "置信度合成式缺失（config #45 composition）—— 不得按默认指数合成");
        }
        Map<String, BigDecimal> exponents = parseExponents(composition.asText());

        JsonNode s = root.path("s");
        JsonNode d = root.path("d");
        JsonNode n = root.path("n");
        JsonNode m = root.path("m");
        JsonNode bands = root.path("bands");

        int nMinSampleDays = requireInt(n, "min_sample_days", "n.min_sample_days");
        int applicableFalseBelow = requireInt(n, "applicable_false_below", "n.applicable_false_below");
        if (applicableFalseBelow != nMinSampleDays) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #45 n 段自相矛盾：applicable_false_below=" + applicableFalseBelow
                            + " 但 min_sample_days=" + nMinSampleDays
                            + " —— 『不适用』的门槛与『最小样本天数』必须是同一条线");
        }
        int expectedDaysDenominator = parseFormulaDenominator(
                requireText(n, "formula", "n.formula"), "n.formula");

        BigDecimal mBase = requireDecimal(m, "base", "m.base");
        BigDecimal floor = requireDecimal(m, "floor", "m.floor");
        if (floor.compareTo(mBase) != 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #45 m 段自相矛盾：floor=" + floor + " 与 base=" + mBase
                            + " 不等 —— 规格 §10.4② 的 m(3) 既是地板也是谷底，两值必须同源");
        }

        return new Confidence(
                exponents,
                requireDecimal(s, "same_origin", "s.same_origin"),
                requireDecimal(s, "missing_metadata", "s.missing_metadata"),
                requireDecimal(s, "incomparable", "s.incomparable"),
                requireBoolean(s, "incomparable_forces_human_and_null_verdict",
                        "s.incomparable_forces_human_and_null_verdict"),
                requireInt(d, "answered", "d.answered"),
                nMinSampleDays,
                expectedDaysDenominator,
                parseWeights(n.path("reweight_when_na"), "n.reweight_when_na"),
                mBase,
                requireDecimal(m, "span", "m.span"),
                requireInt(m, "delta_gap", "m.delta_gap"),
                requireDecimal(bands, "high_min", "bands.high_min"),
                requireDecimal(bands, "medium_min", "bands.medium_min"));
    }

    /**
     * 解析合成式 {@code "s * d^0.25 * n^0.25 * m^0.50"} → {@code {s:1, d:0.25, n:0.25, m:0.50}}。
     *
     * <p>🛑 指数<b>必须</b>从配置字符串读，不得写死在引擎里：
     * 规格 §10.4① 的"几何而非算术和"是靠指数表达的 ——
     * 若有人把 {@code composition} 改成算术和（各指数为 1），
     * 引擎必须跟着变；反过来，引擎里写死 {@code 0.25} 会让那次配置变更<b>静默失效</b>
     * （配置看着改了、算法没变），而"静默失效的配置"比"没有配置"更糟。
     *
     * <p>无 {@code ^} 的因子（如 {@code s}）指数取 1 —— 这是合成式的字面语义，不是默认值。
     */
    private static Map<String, BigDecimal> parseExponents(String composition) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String factor : composition.split("\\*")) {
            String f = factor.trim();
            if (f.isEmpty()) {
                continue;
            }
            String[] parts = f.split("\\^");
            String name = parts[0].trim();
            if (name.isEmpty()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "置信度合成式含无名因子: '" + factor + "'（config #45 composition=" + composition + "）");
            }
            BigDecimal exp = parts.length == 1
                    ? BigDecimal.ONE
                    : parseDecimal(parts[1].trim(), "composition 因子 " + name + " 的指数");
            out.put(name, exp);
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "置信度合成式解析为空: '" + composition + "'（config #45）");
        }
        return Map.copyOf(out);
    }

    /**
     * 从 {@code "min(1, expected_days/14)"} 这类式子解析分母（→ 14）。
     *
     * <p>为什么要解析而不是把 14 写进代码：{@code #45} 的 n 段用"满档所需天数"
     * 表达"样本量到什么程度算充分"。写进代码就等于在配置之外<b>另立一个口径</b>；
     * 解析则让改配置即改口径。
     */
    private static int parseFormulaDenominator(String formula, String label) {
        Matcher m = Pattern.compile("/\\s*(\\d+)").matcher(formula);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "无法从 " + label + " 解析出天数分母: '" + formula
                            + "'（期望形如 min(1, expected_days/N)，config #45）");
        }
        return parsePositiveInt(m.group(1), label + " 的天数分母");
    }

    private static Map<String, BigDecimal> parseWeights(JsonNode node, String label) {
        if (node.isMissingNode() || !node.isObject() || node.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "权重段缺失或为空: " + label + "（config #5）");
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            out.put(e.getKey(), requireDecimal(node, e.getKey(), label + "." + e.getKey()));
        }
        return Map.copyOf(out);
    }

    private static void assertWeightsSumToOne(Map<String, BigDecimal> weights, String label) {
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 权重之和必须为 1：实际=" + sum.toPlainString()
                            + "（明细 " + weights + "，config #5）");
        }
    }

    /**
     * MCID 的模块满分必须等于"每模块题数 × 单题上限"（规格 §1.4 原文：每模块 4 题（0–16））。
     *
     * <p>用的是 {@link ScaleStructure} 里的<b>结构性事实</b>
     * （每维题数与量程，来自 config {@code #35} 与 PRD 附录 C.1.5），
     * 而不是把 16 写进本类 —— 否则量程改判（v1.7 就改过一次：0–3 → 0–4）时
     * 这里会静默失配。
     */
    private static void assertMcidMatchesScaleStructure(Mcid mcid) {
        int derived = ScaleStructure.itemsPerModule() * ScaleStructure.itemMax();
        if (mcid.moduleTotalMax() != derived) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "MCID 模块满分与量程结构不一致：config #33 module_total_max="
                            + mcid.moduleTotalMax() + " 但『每模块题数 × 单题上限』=" + derived
                            + "（规格 §1.4：每模块 4 题、0–4 五级；量程来自 config #35）");
        }
    }

    private static JsonNode readJson(String json, String label) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 不是合法 JSON: " + e.getMessage());
        }
    }

    private static void requireTrue(JsonNode node, String field, String reason) {
        if (!node.path(field).asBoolean(false)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config 标志 " + field + " 必须为 true —— " + reason + "（config #5）");
        }
    }

    private static BigDecimal requireDecimal(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isNumber()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非数值: " + label);
        }
        return v.decimalValue();
    }

    private static int requireInt(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isInt() && !v.isLong()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非整数: " + label);
        }
        return v.asInt();
    }

    private static String requireText(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非文本: " + label);
        }
        return v.asText();
    }

    private static boolean requireBoolean(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isBoolean()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非布尔: " + label);
        }
        return v.asBoolean();
    }

    private static BigDecimal parseDecimal(String s, String label) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 不是合法数值: '" + s + "'");
        }
    }

    private static int parsePositiveInt(String s, String label) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v <= 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        label + " 必须为正整数：实际=" + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 不是合法整数: '" + s + "'");
        }
    }

    // ==================================================================
    // 便捷视图
    // ==================================================================

    /** 门槛的 {@code int} 千分位表示（供数值比较，避免到处 {@code doubleValue()}）。 */
    public BigDecimal passThresholdScale() {
        return passThreshold.setScale(6, RoundingMode.HALF_UP);
    }

    public DerivedMetricProfile {
        asRefundWeights = Map.copyOf(asRefundWeights);
        asOpsWeights = Map.copyOf(asOpsWeights);
    }
}