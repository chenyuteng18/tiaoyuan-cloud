package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * S1-5 依从性引擎 —— 计算 <b>{@code AS_refund}</b>（判定 / 退款门禁口径）与依从状态。
 *
 * <h2>它算的是什么（口径来自指标规格 §4.3，不是自造）</h2>
 * <pre>
 *   AS_refund = Σ( w_i × A_i ) / Σ( w_i )     i ∈ 适用维度
 *   w = config #5 的 AS_refund 权重 = {A1:0.571, A3:0.286, A4:0.143}（和为 1）
 *   达标 = AS_refund ≥ config #4 的 pass_threshold（初始 0.80）
 * </pre>
 *
 * <h2>🛑 三条不可动摇的合规约束</h2>
 * <ol>
 *   <li><b>A2（小程序填报）永不进入本引擎</b>。本类的入参只接受
 *       {@code A1/A3/A4}；传入 {@code A2} 一律抛。理由是指标规格 §4.2 的算例：
 *       同一客户、同一行为，仅因"有没有小程序"就得到相反结论（模式 H 算 0.70 不达标、
 *       模式 L 重归一到 1.00 达标）→ 直接用于退款门禁等于
 *       <b>系统性歧视未上端门店的客户</b>，与 04 表 §四「手环数据不作为单方判定依据」、
 *       06 表 §五「未佩戴不得作为客户不利依据」直接冲突。
 *       规格 §4.3 的结论是"退款口径固定不含 A2，与模式无关"——
 *       这条约束因此不能靠"调用方记得别传"来保证，必须在本类<b>结构上</b>成立。</li>
 *   <li><b>A3 只在"客户同意佩戴"时适用</b>（规格 §4.1：「仅『同意佩戴者』applicable」）。
 *       未同意或不适用时按 {@code applicable=false} 处理并<b>重归一</b>，
 *       而不是记 0 —— 记 0 等于把"没戴手环"当扣分项，与合规精神冲突。</li>
 *   <li><b>样本不足不得用于退款门禁</b>（规格 §4.3 护栏：应填天数 &lt; 7 标"样本不足"）。
 *       本引擎在该情形下<b>连 AS 值都不算</b>（返回 {@code null}），
 *       而不是算出来再标一个"不可用"的标志 —— 后者会被下游顺手用掉。</li>
 * </ol>
 *
 * <h2>缺失值策略（config {@code #6}）在公式里的落地</h2>
 * <ul>
 *   <li>{@code STRUCTURAL_KEEP}（结构性缺失不扣分）：适用性为假的维度
 *       <b>退出分子与分母</b>，剩余权重<b>重归一</b>。规格 §4.1 的 Mode L 算例
 *       （A2 结构性缺失 → A1/A3/A4 重归一）即此。</li>
 *   <li>{@code BEHAVIORAL_ZERO}（行为性缺失计 0）：适用性为假但属行为性的维度
 *       <b>保留在分母</b>、分子计 0。把行为性缺失当"不适用"会让依从性系统性虚高。</li>
 * </ul>
 * 二者的区别由调用方通过 {@link DimensionInput#structuralMissing()} 声明，
 * 因为"这个缺失是结构性的还是行为性的"是<b>数据事实</b>，不是本引擎能从数值推出的。
 *
 * <h2>🛑 本类不含任何阈值常量</h2>
 * 门槛、权重、缺失策略、样本护栏<b>全部</b>来自 {@link DerivedMetricProfile}
 * （由 config {@code #4/#5/#6/#7} 归一化）。
 */
public class AdherenceEngine {

    /** 判定口径涉及的维度码（固定三维，不含 A2）—— 契约/规格 §4.3 逐字：A1 + A3 + A4。 */
    public static final Set<String> REFUND_DIMENSIONS = Set.of("A1", "A3", "A4");

    /**
     * 结构性缺失与行为性缺失都要按策略分流，故本引擎需要知道每一维的<b>缺失性质</b>。
     *
     * <p>{@link #applicable} 为真时 {@code value} 必填（0–1）；
     * 为假时 {@code value} 必<b>不存在</b>（{@code null}）——两者同时给出或同时缺失都是矛盾，
     * 一律抛错。这个矛盾检查抓的是真实错法：把"没戴手环"填成 {@code value=0} 且
     * {@code applicable=true}，会让 AS 被压低、且<b>不报错</b>。
     *
     * @param applicable        该维度对本客户是否适用
     * @param value             适用时的取值（0–1）；不适用时须为 {@code null}
     * @param structuralMissing 缺失是否<b>结构性</b>（载体不存在）；仅 {@code applicable=false} 时有意义
     */
    public record DimensionInput(boolean applicable, BigDecimal value, boolean structuralMissing) {

        public DimensionInput {
            if (applicable && value == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "维度声明为适用却未给取值 —— 适用维度的值不得缺失（硬纪律 #4：缺失不得补 0）");
            }
            if (!applicable && value != null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "维度声明为不适用却给了取值 —— 不适用不等于 0，两者混用会让 AS 静默偏移");
            }
        }

        public static DimensionInput ofApplicable(BigDecimal value) {
            return new DimensionInput(true, value, false);
        }

        /**
         * 结构性缺失（载体不存在）。
         *
         * <p>⚠️ 工厂方法名刻意<b>不</b>叫 {@code structuralMissing()} —— 那会与 record 组件
         * {@code structuralMissing} 的访问器同名（无参同名不同返回类型即编译错误）。
         * 命名冲突在此处是好事：它逼着调用方读清"这是构造"还是"这是读取"。
         */
        public static DimensionInput ofStructuralMissing() {
            return new DimensionInput(false, null, true);
        }

        public static DimensionInput ofBehavioralMissing() {
            return new DimensionInput(false, null, false);
        }
    }

    /**
     * 一次依从性计算的输入。
     *
     * @param expectedDays 应填天数（护栏比较对象；config {@code #7} 的 {@code min_sample_days}）
     * @param dimensions   维度码 → 输入（<b>只允许 A1/A3/A4</b>）
     */
    public record AdherenceInput(int expectedDays, Map<String, DimensionInput> dimensions) {

        public AdherenceInput {
            if (dimensions == null || dimensions.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "依从性维度输入不得为空");
            }
            dimensions = Map.copyOf(dimensions);
        }
    }

    /**
     * 依从性结果。
     *
     * @param asValue        {@code AS_refund}；<b>样本不足时为 {@code null}</b>（不计算，不是"算出来不可用"）
     * @param state          依从状态（达标 / 不足 / 样本不足）
     * @param usableForRefundGate 是否可用于退款门禁（= 非"样本不足"）
     * @param thresholdMet   是否达到门槛；{@code asValue} 为 {@code null} 时为 {@code null}（未知，不是 false）
     * @param appliedWeights 本次实际采用的权重（重归一后的值）—— 留档"这个分是按哪套权重算的"
     * @param expectedDays   应填天数（原样带回，便于举证）
     */
    public record AdherenceResult(
            BigDecimal asValue,
            AdherenceState state,
            boolean usableForRefundGate,
            Boolean thresholdMet,
            Map<String, BigDecimal> appliedWeights,
            int expectedDays) {

        public AdherenceResult {
            appliedWeights = Map.copyOf(appliedWeights);
        }

        /**
         * 依从侧的退款门槛是否成立。
         *
         * <p>⚠️ 它<b>只回答依从侧</b>这一个条件（{@code AS_refund ≥ 门槛} 且样本充足），
         * <b>不是</b>完整的退款判定 —— 完整判定还要叠加效果判定、责任主体、责任认定等
         * （PRD §7.3 / config {@code #10} 入口 A/B 二分通路）。
         * 之所以不在这里叫 {@code refundEligibility}：那会让调用方以为拿到它就是拿到了结论。
         */
        public boolean adherenceSideGateMet() {
            return usableForRefundGate && Boolean.TRUE.equals(thresholdMet);
        }
    }

    /** AS 的标度（0–1 的加权和，保留三位小数 —— 与 V5 {@code as_value NUMERIC(4,3)} 同精度）。 */
    private static final int AS_SCALE = 3;

    /**
     * 内部中间精度 —— <b>结构事实，非业务口径</b>。
     *
     * <p>它在除法收敛到 {@link #AS_SCALE} 之前给足有效位数，防止"先用 3 位算、再截 3 位"
     * 把舍入误差放大进结论。它的取值由"结果标度 + 加权求和的项数"决定，
     * <b>不来自任何 config 槽位</b>，故与门槛 / 权重 / 天数分属两类，
     * 允许作为源码常量存在（并由 {@code DerivedEngineContractTest} 显式登记）。
     */
    private static final int INTERNAL_PRECISION = 10;

    private final DerivedMetricProfile profile;

    public AdherenceEngine(DerivedMetricProfile profile) {
        if (profile == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "依从性引擎缺少派生口径（config #4/#5/#6/#7 未装载）—— 拒绝按默认口径计算");
        }
        this.profile = profile;
    }

    /**
     * 计算 {@code AS_refund} 与依从状态。
     *
     * <p>执行顺序（固定，便于错误可预期）：
     * ① 维度码合法性（拒 A2）→ ② 样本护栏 → ③ 逐维分流与重归一 → ④ 与门槛比较。
     */
    public AdherenceResult compute(AdherenceInput input) {
        if (input == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "依从性输入不得为空");
        }
        Map<String, DimensionInput> dims = input.dimensions();

        // ① 维度码合法性 —— 🛑 A2 必须在这里被物理挡住
        for (String code : dims.keySet()) {
            if (!REFUND_DIMENSIONS.contains(code)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "维度 " + code + " 不属于 AS_refund 判定口径（仅 " + new TreeMap<>(profile.asRefundWeights()).keySet()
                                + "）。🛑 A2（小程序填报）永不参与判定 —— 它只存在于 AS_ops；"
                                + "一旦进入判定口径，有端/无端门店同一客户会得到相反结论"
                                + "（指标规格 §4.2，系统性歧视未上端门店客户）");
            }
        }
        // 三维必须齐备：少一维不是"少算一点"，而是口径不完整（不得按剩余维度凑一个数）
        for (String code : REFUND_DIMENSIONS) {
            if (!dims.containsKey(code)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "AS_refund 口径维度不齐：缺 " + code + "（须齐备 " + REFUND_DIMENSIONS
                                + "；缺维不得按剩余维度重归一凑数）");
            }
        }

        // ② 样本护栏 —— 低于门槛时连值都不算（见类注释约束③）
        int minDays = profile.minSampleDays();
        if (input.expectedDays() < minDays) {
            return new AdherenceResult(null, AdherenceState.SAMPLE_INSUFFICIENT, false, null,
                    Map.of(), input.expectedDays());
        }

        // ③ 逐维分流：结构性缺失按策略退出分母（重归一）/ 行为性缺失计 0
        Map<String, BigDecimal> numerWeights = new LinkedHashMap<>();
        Map<String, BigDecimal> applied = new TreeMap<>();
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;

        for (String code : new TreeMap<>(profile.asRefundWeights()).keySet()) {
            BigDecimal w = profile.asRefundWeights().get(code);
            DimensionInput di = dims.get(code);

            if (di.applicable()) {
                BigDecimal v = di.value();
                assertUnitInterval(v, code);
                numerator = numerator.add(w.multiply(v));
                denominator = denominator.add(w);
                applied.put(code, w);
                continue;
            }
            // 不适用：按缺失策略分流
            if (profile.missingPolicy() == DerivedMetricProfile.MissingPolicy.STRUCTURAL_KEEP
                    && di.structuralMissing()) {
                // 结构性缺失不扣分：退出分子与分母（剩余权重自然重归一）
                continue;
            }
            // 行为性缺失（或策略要求计 0）：保留在分母、分子计 0
            denominator = denominator.add(w);
            applied.put(code, w);
            numerWeights.put(code, w);
        }

        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            // 全部适用维度都因结构性缺失退出 => 口径被掏空。这不可能是"达标 0 分"，
            // 只能是数据/配置问题，必须炸（宁可挂起，也不给一个没有依据的数）。
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "AS_refund 全部维度均不适用 —— 权重和为 0，口径被掏空；"
                            + "不得按 0 分或 1 分兜底（缺失不得补 0，硬纪律 #4）");
        }

        BigDecimal asValue = numerator.divide(denominator, new MathContext(INTERNAL_PRECISION, RoundingMode.HALF_UP))
                .setScale(AS_SCALE, RoundingMode.HALF_UP);

        // ④ 与门槛比较（门槛来自 config #4）
        boolean met = asValue.compareTo(profile.passThreshold()) >= 0;
        AdherenceState state = met ? AdherenceState.PASS : AdherenceState.INSUFFICIENT;

        return new AdherenceResult(asValue, state, state.usableForRefundGate(), met,
                applied, input.expectedDays());
    }

    /** 供演示 / 回归展示"口径来自配置"（只读）。 */
    public DerivedMetricProfile profile() {
        return profile;
    }

    /** {@code AS_ops}（运营口径）只在运营看板用，不进本引擎；此方法仅为把该事实显式化。 */
    public List<String> opsOnlyDimensions() {
        return List.copyOf(profile.asOpsWeights().keySet());
    }

    private static void assertUnitInterval(BigDecimal v, String code) {
        if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "维度 " + code + " 的取值须在 [0, 1] 内：实际=" + v.toPlainString()
                            + "（越界不得截断 —— 截断会静默改分）");
        }
    }
}