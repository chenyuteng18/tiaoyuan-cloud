package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S1-5 判定置信度引擎 —— 计算 {@code verdict.confidence}（0–1 连续分）。
 *
 * <h2>合成式（权威源 = 指标规格 §10.4，配置镜像 = config {@code #45}）</h2>
 * <pre>
 *   C = s × d^0.25 × n^0.25 × m^0.50        （加权几何，非算术和）
 * </pre>
 * 四项因子的取值与指数<b>全部</b>从 config {@code #45} 读（含指数本身 ——
 * 见 {@link DerivedMetricProfile.Confidence} 对"为什么指数必须可配"的说明）。
 *
 * <h2>🛑 三条语义红线（规格 §10.4③附 逐字）</h2>
 * <ol>
 *   <li><b>{@code s = 0} 是"不可判"，不是"最低置信档"</b>。
 *       规格原文：「『0』语义 = <b>不可判（挂起）</b>，不是最低置信档：
 *       {@code s = 0 → C = 0} 且<b>强制转人工</b>、{@code effect_verdict = NULL}；
 *       UI 须<b>先判 gate、再映射三档</b>」。
 *       故本引擎把"挂起"与"低置信"做成两种<b>结构上不同</b>的返回
 *       （{@link ConfidenceResult#suspended()} 独立于 {@link ConfidenceBand}），
 *       而不是都塞进 {@code C} 里靠数值大小区分 ——
 *       报告里 {@code C=0.000} 与"挂起"若只靠数值相似性区分，读报告的人会看错。</li>
 *   <li><b>{@code m(Δ)} 以 Δ=3 为唯一谷底</b>（地板 0.15）：
 *       规格 §10.4② 给了完整的分段式与"不得表述为全局严格单调"的告诫。
 *       本引擎按 {@code base + span × min(1, |Δ − delta_gap| / delta_gap)} 实现，
 *       其中 {@code base/span/delta_gap} 全部来自配置。</li>
 *   <li><b>{@code n} 不适用时重归一指数</b>（规格 §10.4①：{@code d : m = 1/3 : 2/3}，
 *       因为 {@code n} 退出合成后几何式的指数必须重新分配）。
 *       若不重归一，任何一次"样本不足"都会把 {@code C} 拖低 ——
 *       而"数据不够"与"结论不可信"是两件事。</li>
 * </ol>
 *
 * <h2>🛑 本类不进 ECC / HSI 权重</h2>
 * config {@code #45} 行注释原文：「⚠️ 不进 ECC、不进 HSI 权重（config {@code #18} 八项不动）
 * —— 仅作协商辅助」。故本类的产物<b>只</b>用于判定协商辅助展示，不得作为考核/分账权重。
 */
public class VerdictConfidenceEngine {

    /**
     * 置信度的标度 —— 与数据字典 {@code confidence decimal(4,3)} 同精度。
     */
    public static final int CONFIDENCE_SCALE = 3;

    /**
     * 内部计算的中间精度（几何式需乘方，先给足位数再收敛到 {@link #CONFIDENCE_SCALE}）。
     *
     * <p><b>结构事实，非业务口径</b>：其取值由"结果标度 + 四个因子连乘的累积舍入"
     * 决定，<b>不来自任何 config 槽位</b>。允许作为源码常量存在，
     * 并由 {@code DerivedEngineContractTest} 显式登记（与门槛 / 权重 / 指数分属两类）。
     */
    public static final int INTERNAL_PRECISION = 12;

    /** 由 {@link #INTERNAL_PRECISION} 构造的中间精度上下文（唯一实例，避免各处重复声明）。 */
    private static final MathContext INTERNAL =
            new MathContext(INTERNAL_PRECISION, RoundingMode.HALF_UP);

    /**
     * 展示层三档（规格 §10.4①：高 ≥0.75 / 中 0.45~0.75 / 低 &lt;0.45）。
     *
     * <p>🛑 <b>它仅作展示层</b>：规格逐字写「三档（…）<b>仅作展示层</b>
     * （连续分可排序 / 可回测；三档阈值化丢信息）」。
     * 阈值化后的档位<b>不得</b>回流进判定逻辑 —— 那是"把丢过信息的数当原始数用"。
     */
    public enum ConfidenceBand {
        HIGH("高"), MEDIUM("中"), LOW("低");

        private final String label;

        ConfidenceBand(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 四项因子的原始输入。
     *
     * @param sameOriginStatus  {@code s} 同源状态：同源 / 缺元数据 / 不可比（取值来自 config {@code #45}）
     * @param answeredCount     实际答题数（{@code d} 的分子）
     * @param expectedDays      应填天数（{@code n} 的分子）；低于门槛则 {@code n} 不适用
     * @param mcidDelta         {@code m} 的 Δ（分数下降为正，与 {@link EffectVerdictEngine} 同号约定）
     */
    public record ConfidenceInput(
            SameOriginStatus sameOriginStatus,
            int answeredCount,
            int expectedDays,
            int mcidDelta) {

        public ConfidenceInput {
            if (sameOriginStatus == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "同源状态必填（同源 / 缺元数据 / 不可比）—— 不得默认按同源处理");
            }
        }
    }

    /** {@code s} 的三值（规格 §10.4① / config {@code #45} 的 {@code s} 段）。 */
    public enum SameOriginStatus {
        /** 同源 = 1。 */
        SAME_ORIGIN,
        /** 缺元数据 = 0.5（不影响可比性，但证据链不完整）。 */
        MISSING_METADATA,
        /** 不可比 = 0 —— 一票否决，强制转人工且 {@code effect_verdict} 置空。 */
        INCOMPARABLE
    }

    /**
     * 置信度结果。
     *
     * @param confidence    0–1 连续分；<b>挂起时为 {@code null}</b>（不是 0.000 —— 见类注释红线①）
     * @param suspended     是否挂起（{@code s} 不可比）
     * @param suspendReason 挂起原因（挂起时必非空）
     * @param band          展示层档位；<b>挂起时为 {@code null}</b>
     * @param factors       四项因子的实际取值（用于留档与回测）；
     *                      <b>{@code n} 退出合成时该键不存在</b>（读取得到 {@code null}）
     * @param exponentsUsed 本次实际使用的指数（{@code n} 不适用时已重归一）
     */
    public record ConfidenceResult(
            BigDecimal confidence,
            boolean suspended,
            String suspendReason,
            ConfidenceBand band,
            Map<String, BigDecimal> factors,
            Map<String, BigDecimal> exponentsUsed) {

        public ConfidenceResult {
            factors = factors == null ? Map.of() : Map.copyOf(factors);
            exponentsUsed = Map.copyOf(exponentsUsed);
        }

        /**
         * 是否必须转人工。
         *
         * <p>两种情形：① 挂起（不可比，规格 §10.4① 明定强制转人工）；
         * ② 置信度落"低"档（协商辅助场景下低置信结论不应单独成立）。
         */
        public boolean requiresHumanReview() {
            return suspended || band == ConfidenceBand.LOW;
        }
    }

    private final DerivedMetricProfile profile;

    public VerdictConfidenceEngine(DerivedMetricProfile profile) {
        if (profile == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "置信度引擎缺少派生口径（config #45 未装载）—— 拒绝按默认指数合成");
        }
        this.profile = profile;
    }

    /**
     * 合成置信度。
     *
     * <p>执行顺序：① {@code s} 一票否决 → ② {@code d} → ③ {@code n}（不适用则重归一）→
     * ④ {@code m} → ⑤ 几何合成 → ⑥ 映射展示档。
     */
    public ConfidenceResult compute(ConfidenceInput input) {
        if (input == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "置信度输入不得为空");
        }
        DerivedMetricProfile.Confidence c = profile.confidence();

        // ① s —— 一票否决。不可比时 C 不是"低置信"，而是"不可判"
        BigDecimal s = switch (input.sameOriginStatus()) {
            case SAME_ORIGIN -> c.sameOrigin();
            case MISSING_METADATA -> c.missingMetadata();
            case INCOMPARABLE -> c.incomparable();
        };
        if (c.isSuspended(s)) {
            // 规格 §10.4①：s=0 → C=0 且强制转人工、effect_verdict=NULL。
            // 此处刻意不返回 0.000 而是返回"挂起"：报告读者必须一眼看出这是"不可判"，
            // 不是"置信度恰好很低"。两者对下游是完全不同的指令。
            return new ConfidenceResult(null, true,
                    "同源状态 = 不可比 → 一票否决（C = 0）：强制转人工且 effect_verdict 置空"
                            + "（指标规格 §10.4① s.incomparable_forces_human_and_null_verdict）",
                    null, Map.of(), Map.copyOf(c.exponents()));
        }

        // ② d —— 题组完成度
        int answered = c.answered();
        if (input.answeredCount() < 0 || input.answeredCount() > answered) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "实际答题数越界: " + input.answeredCount() + "（0–" + answered
                            + "，config #45 d.answered）—— 越界不得截断");
        }
        BigDecimal d = BigDecimal.valueOf(input.answeredCount())
                .divide(BigDecimal.valueOf(answered), INTERNAL);

        // ③ n —— 依从样本量；低于门槛则"不适用"（退出合成 + 指数重归一）
        boolean nApplicable = input.expectedDays() >= c.nMinSampleDays();
        BigDecimal n = null;
        Map<String, BigDecimal> exponents = new LinkedHashMap<>(c.exponents());
        if (nApplicable) {
            BigDecimal ratio = BigDecimal.valueOf(input.expectedDays())
                    .divide(BigDecimal.valueOf(c.expectedDaysDenominator()), INTERNAL);
            n = ratio.min(BigDecimal.ONE);
        } else {
            // 重归一：把 n 的指数按配置的 reweight_when_na 分给 d 与 m
            for (Map.Entry<String, BigDecimal> e : c.reweightWhenNa().entrySet()) {
                if (!exponents.containsKey(e.getKey())) {
                    throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "重归一目标因子未在合成式中: " + e.getKey() + "（config #45 n.reweight_when_na）");
                }
                exponents.put(e.getKey(), e.getValue());
            }
            exponents.remove("n");
        }

        // ④ m —— MCID 裕度（以 delta_gap 为唯一谷底的单谷式）
        BigDecimal m = mcidMargin(input.mcidDelta());

        // ⑤ 几何合成 C = s × d^eD × n^eN × m^eM
        BigDecimal value = s;
        for (Map.Entry<String, BigDecimal> e : exponents.entrySet()) {
            BigDecimal factor = factorOf(e.getKey(), s, d, n, m);
            value = value.multiply(pow(factor, e.getValue()), INTERNAL);
        }
        BigDecimal confidence = value.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);

        Map<String, BigDecimal> factors = new LinkedHashMap<>();
        factors.put("s", s);
        factors.put("d", d);
        // 🛑 n 退出合成时【不放这个键】，而不是放一个 null 值：
        // 本记录用 Map.copyOf 固化，而 Map.copyOf 拒收 null 值（抛 NPE）。
        // 初版写成 factors.put("n", null) ⇒ 每次"应填天数 < 7"（新客户最常见的情形）
        // 都抛 NPE ⇒ 被全局处理器兜成 9001/500。读取侧语义不变：
        // get("n") 对"键不存在"同样返回 null，故"n 退出 ⇒ 读出来是 null"仍然成立。
        if (nApplicable) {
            factors.put("n", n);
        }
        factors.put("m", m);

        // ⑥ 展示档（仅展示层，不得回流判定）
        ConfidenceBand band = confidence.compareTo(c.highMin()) >= 0 ? ConfidenceBand.HIGH
                : (confidence.compareTo(c.mediumMin()) >= 0 ? ConfidenceBand.MEDIUM : ConfidenceBand.LOW);

        return new ConfidenceResult(confidence, false, null, band, factors, Map.copyOf(exponents));
    }

    /**
     * {@code m(Δ)} —— 以 {@code delta_gap} 为唯一谷底的单谷式（规格 §10.4②）。
     *
     * <pre>
     *   m(Δ) = base + span × min(1, |Δ − delta_gap| / delta_gap)
     * </pre>
     * 代入 config {@code #45} 的当前值（{@code base=0.15, span=0.85, delta_gap=3}）即规格的
     * canonical 式 {@code 0.15 + 0.85 · min(1, |Δ − 3| / 3)}。
     *
     * <p>🛑 <b>宽度取"一个 MCID"</b>（= {@code delta_gap}）：规格 §10.4③附 原文
     * 「宽度取 {@code W =} 一个 MCID = 3 —— 使『偏离门槛恰好一个 MCID 即达满置信』，
     * 与 config {@code #33} {@code MCID = 3} <b>同尺</b>，避免另立第二把尺」。
     * 故本式里除 {@code delta_gap} 外<b>没有</b>第二个宽度参数 —— 引入它就会另立一把尺。
     *
     * <p>校验点（规格 §10.4③ 提供，回归用例逐点比对）：
     * {@code Δ=0→1.000 / 1→0.717 / 2→0.433 / 2.9→0.178 / 3→0.150 / 4→0.433 / 5→0.717 / 6→1.000}。
     */
    public BigDecimal mcidMargin(int delta) {
        DerivedMetricProfile.Confidence c = profile.confidence();
        BigDecimal gap = BigDecimal.valueOf(c.mDeltaGap());
        BigDecimal distance = BigDecimal.valueOf(delta).subtract(gap).abs();
        BigDecimal ramp = distance.divide(gap, INTERNAL).min(BigDecimal.ONE);
        return c.mBase().add(c.mSpan().multiply(ramp, INTERNAL)).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    /** 供演示 / 回归展示"口径来自配置"（只读）。 */
    public DerivedMetricProfile profile() {
        return profile;
    }

    // ------------------------------------------------------------------ 内部

    /** 按因子名取本次实际取值。 */
    private static BigDecimal factorOf(String name, BigDecimal s, BigDecimal d, BigDecimal n, BigDecimal m) {
        return switch (name) {
            case "s“ -> s;
            case ”d“ -> d;
            case ”n“ -> {
                if (n == null) {
                    throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            ”因子 n 退出合成后仍被引用 —— 指数重归一未生效（config #45 n.reweight_when_na）");
                }
                yield n;
            }
            case "m“ -> m;
            default -> throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    ”合成式含未登记因子: " + name + "（config #45 composition，仅允许 s/d/n/m）");
        };
    }

    /**
     * 幂运算 {@code base^exp}。
     *
     * <p>用 double 的 {@code pow} 而非 BigDecimal 手写 —— 指数是小数（0.25/0.50），
     * 手写会引入自己的近似策略，反而与规格 §10.4③ 给的校验点对不上。
     * 精度损失由最后收敛到 3 位小数吸收（规格的校验点都只到 3 位）。
     * 底数为 0 时按定义返回 0（几何式的"趋 0 则趋 0"语义）。
     */
    private static BigDecimal pow(BigDecimal base, BigDecimal exp) {
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        double result = Math.pow(base.doubleValue(), exp.doubleValue());
        return BigDecimal.valueOf(result);
    }
}