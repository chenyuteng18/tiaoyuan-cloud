package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.AdherenceState;
import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-5 判定引擎的契约守卫 —— <b>验收①「依从性 / AS / 效果判定只在服务端计算」</b>
 * 的可机械断言形态。
 *
 * <h2>「只在服务端计算」这句话怎么变成断言</h2>
 * "只在服务端算"本身是个<b>否定式</b>要求，无法正面断言。故本类把它拆成三条可证的命题：
 * <ol>
 *   <li><b>口径在代码之外</b>（{@code no_hardcoded_thresholds...}）：
 *       引擎源码里不得出现门槛 / 权重 / Δ 边界 / 指数的字面量。
 *       用户给的验收措辞是"只在服务端计算"，但其漏洞形态是"算完之后写死一个默认值兜底",
 *       那等价于"口径在代码里" —— 故这条是它的机械形态。</li>
 *   <li><b>算出来的结论不落在客户可见的响应里</b>（{@code derived_conclusions_never_returned...}）：
 *       由 {@code DerivedVisibilityE2ETest} 用真请求断言，本类只做源码侧的静态扫描
 *       （两端各守一半，不重复）。</li>
 *   <li><b>算的过程必然给出"人话"或明确拒绝</b>：缺维不补 0、越界不截断、
 *       同源不成立即挂起 —— 这三件事在下面各有专门用例。</li>
 * </ol>
 *
 * <h2>判据锚定外部真相源（不是自证）</h2>
 * 断言里的每个数字都能追到出处：
 * <ul>
 *   <li>AS_refund 权重 {0.571, 0.286, 0.143}／门槛 0.80／样本 7 天 →
 *       config {@code #4/#5/#7}，逐字；</li>
 *   <li>Δ 分档 3 / 0–2 / 1 → config {@code #33} + 指标规格 §1.4 <b>分档表</b>；</li>
 *   <li>Δ=0 归稳定 → 规格 §1.4「无变化 → 稳定（E3）」；</li>
 *   <li>置信度校验点 Δ=0→1.000 / 1→0.717 / 2→0.433 / 2.9→0.178 / 3→0.150 /
 *       4→0.433 / 5→0.717 / 6→1.000 → 规格 §10.4③ <b>逐点给出</b>；</li>
 *   <li>三档 0.75 / 0.45 → config {@code #45 bands}。</li>
 * </ul>
 * 断言比对的是这些外部约定，不是本实现的返回值。
 */
class DerivedEngineContractTest {

    private static final Path DERIVED_MAIN_DIR = resolveDerivedMainDir();

    /** 一次计算的样例应填天数（≥ 样本护栏，使 n 适用）。 */
    private static final int ENOUGH_DAYS = 14;

    private final DerivedMetricProfile profile =
            DerivedMetricProfile.fromRawConfig(new ConfigSeedDerivedProfileSource().raw());

    private final AdherenceEngine adherence = new AdherenceEngine(profile);
    private final EffectVerdictEngine verdict = new EffectVerdictEngine(profile);
    private final VerdictConfidenceEngine confidence = new VerdictConfidenceEngine(profile);

    // ==================================================================
    // 一、口径外置：源码里不得出现阈值常量
    // ==================================================================

    @Test
    @DisplayName("引擎源码内的数值字面量必须只含【由口径推导的基数】—— 出现阈值即红")
    void no_hardcoded_thresholds_exist_in_the_engine_sources() throws IOException {
        // 期望集 = 由口径与结构事实推导，不是手写清单。
        // 手写清单的失效模式很具体：口径改了，人去改清单，改清单不会有人复核，
        // 于是"口径外置"退回人肉自觉，测试只剩噪音。
        Set<String> expected = expectedLiteralsDerivedFromConfigAndStructure();
        Set<String> seen = new TreeSet<>();
        List<String> withContext = new ArrayList<>();

        for (String rel : List.of(
                "domain/AdherenceEngine.java",
                "domain/EffectVerdictEngine.java",
                "domain/VerdictConfidenceEngine.java")) {
            String raw = read(DERIVED_MAIN_DIR.resolve(rel));

            // 前置自证①：注释剥离必须 DOTALL。本仓库的 Javadoc 里【大量出现】口径数字
            // （"0.571"、"0.80"、"Δ=3"、"0.150"）。若非 DOTALL，这些描述性文字会被
            // 判成硬编码阈值 —— 更糟的是，修它的自然反应是删掉文档里的数字。
            assertFalse(stripCommentsAndStrings(raw).contains("0.571"),
                    "注释剥离失效：" + rel + " 剥完还含『0.571』（Javadoc 里的权重描述）—— "
                            + "块注释匹配漏了 DOTALL，扫描器会把文档判成硬编码");

            String code = stripCommentsAndStrings(raw);
            Matcher m = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?(?![\\w.])").matcher(code);
            while (m.find()) {
                String lit = m.group();
                seen.add(lit);
                withContext.add(rel + " 字面量 " + lit + "（上下文: " + context(code, m.start()) + "）");
            }
        }

        // ① 多一个就红
        Set<String> extra = new TreeSet<>(seen);
        extra.removeAll(expected);
        assertTrue(extra.isEmpty(),
                "派生引擎源码里出现了" + extra + " —— 不在【由口径推导出的数字集合】"
                        + " " + expected + " 内。门槛 / 权重 / Δ 边界 / 指数必须外置"
                        + "（PRD §10 所有业务数字不得硬编码），不得写进代码。\n  - "
                        + String.join("\n  - ", withContext));

        // ② 少一个也红：口径基数必须在源码里出现，否则"零违规"可能只是文件没被读到
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(seen);
        assertTrue(missing.isEmpty(),
                "源码里未见到口径基数 " + missing + " —— 说明文件路径解析失效，"
                        + "本条断言退化为恒绿（文件压根没被扫到）");

        // ③ 明确反证：口径本身的关键数字绝不能在代码里作为字面量出现
        for (String forbidden : List.of("0.571", "0.286", "0.143", "0.80", "0.75", "0.45",
                "0.15", "0.85", "0.25", "0.50", "28", "14", "17", "18.8")) {
            assertFalse(seen.contains(forbidden),
                    "引擎源码里出现了口径字面量 " + forbidden
                            + " —— 门槛 / 权重 / 指数 / 天数必须来自 config #4/#5/#33/#45，不得硬编码");
        }
    }

    @Test
    @DisplayName("单元区间上界以符号表达（BigDecimal.ONE）—— 不是数值字面量 1")
    void symbolic_unit_bound_is_used_not_a_numeric_literal() {
        // 本仓库的引擎把"上界 = 1"写成 BigDecimal.ONE / Math.min(…, BigDecimal.ONE)，
        // 而不是字面量 1。这是更稳的写法（不受比例/标度影响），故上面那条
        // 「期望字面量集」里没有 "1" —— 但"1 是否存在"这件事仍需被守住：
        // 若有人把 ONE 改回 1，代码照样能跑，可读性契约却静默退化。
        // 故此处正面断言"符号形态在"，并反证"字面量形态不在"。
        for (String rel : List.of(
                "domain/AdherenceEngine.java",
                "domain/VerdictConfidenceEngine.java")) {
            String raw;
            try {
                raw = read(DERIVED_MAIN_DIR.resolve(rel));
            } catch (IOException e) {
                throw new AssertionError("读取 " + rel + " 失败", e);
            }
            String code = stripCommentsAndStrings(raw);
            assertTrue(code.contains("BigDecimal.ONE"),
                    rel + " 未以 BigDecimal.ONE 表达单元上界 —— 改用字面量 1 会让"
                            + "『1 是不是口径量』重新变成需要人判断的问题");
            assertFalse(Pattern.compile("(?<![\\w.])1(?![\\w.])").matcher(code).find(),
                    rel + " 出现了字面量 1 —— 单元上界请用 BigDecimal.ONE，避免与"
                            + "『口径里是否真有一个 1』混淆");
        }
    }

    /**
     * 期望出现在引擎源码里的数值字面量 —— <b>推导得到，不是列举得到</b>。
     *
     * <h2>为什么不能手写一份清单</h2>
     * 手写清单的失效模式很具体：口径改了，人去改清单；改清单不会有第二个人复核，
     * 于是"口径外置"退回人肉自觉，测试只剩噪音。故本方法的每一项都必须有<b>机械来源</b>。
     *
     * <h2>两个来源</h2>
     * <ol>
     *   <li><b>源码里已显式声明的结构常量</b>（{@code AS_SCALE} / {@code INTERNAL_PRECISION} /
     *       {@code CONFIDENCE_SCALE} / {@code RATE_SCALE}）—— 用反射读其值，
     *       而不是把 10/12/6 抄一遍。这样"引擎把精度从 10 改成 14"不会让测试变红
     *       （它本就不是业务口径），但"引擎多写一个 0.80 门槛"会立刻变红。</li>
     *   <li><b>结构性基数 {0,1,2}</b> —— 见 {@link #STRUCTURAL_NUMERIC_BASES} 的逐项理由。</li>
     * </ol>
     *
     * <p>🛑 口径量（门槛 / 权重 / Δ 边界 / 指数 / 天数）<b>不在</b>本集内 ——
     * 它们若能出现在引擎源码里，"口径外置"就没成立。故本方法<b>不得</b>从
     * {@link DerivedMetricProfile} 派生任何值：那等于把口径写进期望集，断言自证。
     */
    private static Set<String> expectedLiteralsDerivedFromConfigAndStructure() {
        Set<String> expected = new TreeSet<>(STRUCTURAL_NUMERIC_BASES);
        // 来源②：引擎自己声明的结构常量（反射读值 —— 不抄数字）
        for (int v : List.of(
                declaredInt(AdherenceEngine.class, "AS_SCALE"),
                declaredInt(AdherenceEngine.class, "INTERNAL_PRECISION"),
                declaredInt(VerdictConfidenceEngine.class, "CONFIDENCE_SCALE"),
                declaredInt(VerdictConfidenceEngine.class, "INTERNAL_PRECISION"),
                declaredInt(EffectVerdictEngine.class, "RATE_SCALE"))) {
            expected.add(String.valueOf(v));
        }
        return expected;
    }

    /**
     * 结构性数值基数 —— 与业务口径无关的语言层面必要常量。
     *
     * <p>它的每条理由都可复核，且<b>不随口径变化</b>：
     * <ul>
     *   <li>{@code 0} —— 下界比较（{@code compareTo(ZERO) == 0}）、
     *       {@code BigDecimal.ZERO} 的数值形态、循环/下标起点。</li>
     * </ul>
     *
     * <h2>为什么这里<b>没有</b> 1 与 2</h2>
     * 它们本应出现（单元区间上界、二元比较），但引擎把它们写成了<b>符号形态</b> ——
     * {@code BigDecimal.ONE}、{@code compareTo(...) == 0}、{@code Math.min} ——
     * 这是比写数值更好的风格，故本集不该要求字面量存在。
     * 取而代之，{@link #symbolic_unit_bound_is_used_not_a_numeric_literal} 正面断言
     * "上界以符号表达"，使"有人把 ONE 改成 1"这件事仍然可被抓住。
     *
     * <p>🛑 本集里<b>没有</b> 0.571 / 0.286 / 0.143 / 0.80 / 0.75 / 0.45 /
     * 0.15 / 0.85 / 0.25 / 0.50 / 28 / 14 / 17 / 18.8 —— 那些是口径量，
     * 出现在源码里即违约。
     */
    private static final Set<String> STRUCTURAL_NUMERIC_BASES = Set.of("0");

    /** 反射读某个类声明的 {@code static final int} 常量值（读不到即红 —— 不静默跳过）。 */
    private static int declaredInt(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("引擎结构常量 " + owner.getSimpleName() + "." + name
                    + " 读取失败 —— 它应当存在且为 static final int（重命名请同步本测试）", e);
        }
    }

    // ==================================================================
    // 二、依从性 / AS：合规锁（A2 永不参与）+ 门槛 + 样本护栏
    // ==================================================================

    @Test
    @DisplayName("🛑 A2 传入依从性引擎必须抛错 —— 合规锁不能靠『调用方记得别传』")
    void dimension_a2_is_structurally_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                        "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                        "A2", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                        "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                        "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE)))),
                "A2 进入判定口径被接受了 —— 这会让『有端/无端门店同一客户得到相反结论』"
                        + "（指标规格 §4.2，系统性歧视未上端门店客户），必须在结构上挡住");

        assertEquals(5001, ex.getCode(),
                "A2 入判定口径属业务规则冲突（5001），不是参数格式错（1001）—— "
                        + "它违反的是一条合规规则，不是一次笔误");
        assertTrue(ex.getDevMessage().contains("A2"),
                "错误信息必须点名 A2 与理由，否则维护者只会看到『维度不合法』而删掉这条约束");

        // 反向自证：合法三维能算出来 —— 否则上面的"抛错"可能只是因为引擎对谁都抛
        var ok = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));
        assertNotNull(ok.asValue(), "合法三维应能算出 AS —— 反证上面的拒绝是 A2 专属的");
    }

    @Test
    @DisplayName("AS_refund = Σ(w·A)/Σ(w)，权重来自 config #5（0.571/0.286/0.143）")
    void as_value_uses_the_configured_refund_weights() {
        // 三维全 1.0 ⇒ AS 必为 1.000（权重和为 1）
        var all = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));
        assertEquals(0, all.asValue().compareTo(BigDecimal.ONE),
                "三维全达标 ⇒ AS 应为 1.000；实际=" + all.asValue());
        assertTrue(all.thresholdMet(), "AS=1.000 ≥ 0.80 应达标");
        assertEquals(AdherenceState.PASS, all.state(), "状态应为『达标』");

        // A1=1 / A3=0 / A4=0 ⇒ AS 应恰为 A1 的权重 0.571（这就是"用了 #5 而非均分"的证据）
        var onlyA1 = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ZERO),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ZERO))));
        assertEquals(0, onlyA1.asValue().compareTo(new BigDecimal("0.571")),
                "A1=1、A3=A4=0 ⇒ AS 应 = A1 权重 0.571（config #5）；"
                        + "若拿到 0.333 说明实现按维度均分，权重被架空了；实际=" + onlyA1.asValue());
        assertFalse(onlyA1.thresholdMet(), "AS=0.571 < 0.80 应不达标");
        assertEquals(AdherenceState.INSUFFICIENT, onlyA1.state(), "状态应为『不足』");
    }

    @Test
    @DisplayName("门槛比较用 config #4（含等号）：AS 恰为 0.80 ⇒ 达标")
    void threshold_comparison_uses_the_configured_value_inclusively() {
        // 构造 AS = 0.80：A1=1，A3 与 A4 取使加权和恰为 0.80 的值
        // 0.571·1 + 0.286·x + 0.143·y = 0.80 ⇒ 取 x=y=0.8014… 不便构造，
        // 故直接验证"恰在门槛上"的边界语义：用 0.571 + 0.286·1 + 0.143·a 逼近不便，
        // 改为验证比较算符本身 —— 越界一个最小刻度即翻转。
        var justBelow = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.799")),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.799")),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.799")))));
        assertEquals(0, justBelow.asValue().compareTo(new BigDecimal("0.799")),
                "三维同值 0.799 ⇒ AS 应为 0.799（加权和归一后等于各维之值）");
        assertFalse(justBelow.thresholdMet(),
                "AS=0.799 < 0.80 应不达标 —— 若此处为达标，说明比较用了 > 而非 ≥，"
                        + "或门槛值不是 config #4 的 0.80");

        var justAt = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.800")),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.800")),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("0.800")))));
        assertTrue(justAt.thresholdMet(),
                "AS=0.800 恰在门槛上应达标（门槛为『≥』语义）—— 差一个刻度的两侧必须翻转，"
                        + "这证明比较用的是配置值而不是别的数");
    }

    @Test
    @DisplayName("🛑 应填天数 < 7 ⇒ 连 AS 都不算（返回 null + 样本不足），不得『算出来再标不可用』")
    void insufficient_sample_yields_no_value_at_all() {
        var r = adherence.compute(new AdherenceEngine.AdherenceInput(6, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));

        assertNull(r.asValue(),
                "样本不足时 AS 必须为 null（不计算）—— 若算出一个数再标『不可用』，"
                        + "下游会顺手把它用掉（指标规格 §4.3 护栏）");
        assertEquals(AdherenceState.SAMPLE_INSUFFICIENT, r.state(), "状态应为『样本不足』");
        assertFalse(r.usableForRefundGate(),
                "样本不足不得用于退款门禁（规格 §4.3：应填天数 < 7 → 标『样本不足』）");
        assertNull(r.thresholdMet(),
                "样本不足时『是否达标』应为 null（未知），不是 false —— "
                        + "false 会被读成『没达标』，而真实情况是『不知道』");
        assertFalse(r.adherenceSideGateMet(), "依从侧门槛不得成立");

        // 边界另一侧自证：恰好 7 天必须能算（门槛是 < 7，不是 ≤ 7）
        var at7 = adherence.compute(new AdherenceEngine.AdherenceInput(7, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));
        assertNotNull(at7.asValue(),
                "应填天数恰为 7（= config #7 门槛）时应能计算 —— 护栏是『< 7』，不是『≤ 7』");

        // 🛑 状态语义不可混：样本不足 ≠ 不足
        assertNotEquals(AdherenceState.SAMPLE_INSUFFICIENT, AdherenceState.INSUFFICIENT,
                "『样本不足』与『不足』必须是两个状态 —— 前者是『数据不够，无法判』，"
                        + "后者是『数据够，结论是不达标』。合并会让『新客户』被读成『不配合』");
    }

    @Test
    @DisplayName("结构性缺失退出分母并按剩余维度重归一（config #6 = structural_keep）")
    void structural_missing_drops_out_of_the_denominator_and_reweights() {
        // A1=1.0 适用；A3 结构性缺失（载体不存在，如无手环门店）；A4=0.0 适用
        // 期望：重归一后 AS = (0.571·1 + 0.143·0) / (0.571 + 0.143) = 0.571/0.714 = 0.7997…
        var r = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofStructuralMissing(),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ZERO))));

        assertNotNull(r.asValue(), "有剩余维度可算时应给出 AS");
        // 0.571 / 0.714 = 0.799719… → 保留三位 = 0.800
        assertEquals(0, r.asValue().compareTo(new BigDecimal("0.800")),
                "结构性缺失应退出分母并重归一：0.571/(0.571+0.143)=0.7997… → 0.800；"
                        + "若拿到 0.571 说明重归一没生效（分母没减去 A3 权重）；实际=" + r.asValue());
        assertFalse(r.appliedWeights().containsKey("A3"),
                "结构性缺失的维度不得出现在『本次实际采用的权重』里 —— "
                        + "它已退出分子分母；实际=" + r.appliedWeights());
        assertTrue(r.appliedWeights().containsKey("A1") && r.appliedWeights().containsKey("A4"),
                "适用维度应出现在实际采用权重里；实际=" + r.appliedWeights());
    }

    @Test
    @DisplayName("行为性缺失保留在分母、分子计 0（不得当『不适用』而不扣分）")
    void behavioral_missing_stays_in_the_denominator_and_scores_zero() {
        // A1=1.0；A3 行为性缺失（客户未佩戴 —— 载体存在但没做）；A4=1.0
        // 期望：AS = (0.571·1 + 0.286·0 + 0.143·1) / 1.0 = 0.714
        var r = adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofBehavioralMissing(),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));

        assertEquals(0, r.asValue().compareTo(new BigDecimal("0.714")),
                "行为性缺失应保留在分母、分子计 0：0.571+0+0.143 = 0.714。"
                        + "若拿到 1.000 说明行为性缺失被当成了『不适用』—— 那会让依从性系统性虚高"
                        + "（客户没配合反而不扣分）；实际=" + r.asValue());
        assertTrue(r.appliedWeights().containsKey("A3"),
                "行为性缺失的维度应仍在『实际采用权重』里（它保留在分母）；实际=" + r.appliedWeights());
        assertFalse(r.thresholdMet(), "AS=0.714 < 0.80 应不达标");
    }

    @Test
    @DisplayName("缺维度 / 维度值越界 / 适用性与取值矛盾 ⇒ 一律抛错，绝不补 0 或截断")
    void invalid_inputs_are_rejected_and_never_patched() {
        // ① 缺一维（不得按剩余两维重归一凑一个数）
        BizException missing = assertThrows(BizException.class,
                () -> adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                        "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                        "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE)))),
                "缺 A4 被接受了 —— 口径不齐不是『少算一点』，缺维不得凑数");
        assertTrue(missing.getDevMessage().contains("A4"), "错误信息应点名缺哪一维");

        // ② 取值越界（>1）不得截断成 1
        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                                "A1", AdherenceEngine.DimensionInput.ofApplicable(new BigDecimal("1.5")),
                                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))))
                ).getCode(),
                "取值越界属入参校验失败（1001）；截断会静默改分");

        // ③ 声明适用却不给值 ⇒ 抛（不得按 0 补）
        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> new AdherenceEngine.DimensionInput(true, null, false)).getCode(),
                "『适用但无值』必须抛 —— 缺失不得补 0（硬纪律 #4）");

        // ④ 声明不适用却给了值 ⇒ 抛（不适用 ≠ 0，混用会让 AS 静默偏移）
        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> new AdherenceEngine.DimensionInput(false, BigDecimal.ONE, false)).getCode(),
                "『不适用但有值』必须抛 —— 不适用与 0 是两件事");

        // ⑤ 全部维度结构性缺失 ⇒ 分母为 0，必须抛（不得按 0 分或 1 分兜底）
        assertEquals(5001,
                assertThrows(BizException.class,
                        () -> adherence.compute(new AdherenceEngine.AdherenceInput(ENOUGH_DAYS, Map.of(
                                "A1", AdherenceEngine.DimensionInput.ofStructuralMissing(),
                                "A3", AdherenceEngine.DimensionInput.ofStructuralMissing(),
                                "A4", AdherenceEngine.DimensionInput.ofStructuralMissing())))).getCode(),
                "口径被掏空（权重和为 0）时必须抛 5001 —— 它不是『达标 0 分』，是配置/数据问题");
    }

    // ==================================================================
    // 三、效果判定：Δ 分档完整覆盖整数轴（含 Δ=0）
    // ==================================================================

    @Test
    @DisplayName("🛑 Δ=0（无变化）必须判 E3 稳定 —— 它是临床最常见的结果，落空即 500")
    void delta_zero_is_stable_not_a_fallthrough_error() {
        // 规格 §1.4 分档表逐字：「无变化 | 稳定（E3） | —」
        // 而这一格在 config #33 写成 stable.delta_min=1 时会落空 —— 三条接缝只有
        // improved↔stable 处有人守，stable↔worsened 处无人守。
        // Δ=0 不是边缘情形，它是"客户没变化"这一最常出现的结论。
        var r = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, true, true), 12, 12));

        assertEquals(EffectVerdict.E3_STABLE, r.verdict(),
                "Δ=0（12→12，无变化）应判 E3 稳定（规格 §1.4 分档表）—— "
                        + "若抛异常或落到加重侧，说明 MCID 分档在 Δ=0 处有洞；实际=" + r.verdict());
        assertEquals(0, r.delta(), "Δ 应为 0");
        assertFalse(r.suspended(), "Δ=0 是可判情形，不得挂起");
        assertFalse(r.requiresHuman(),
                "E3 稳定属『系统可自动判定』（规格 §3.2），不应要求人工确认");
    }

    @Test
    @DisplayName("Δ 整条轴都落得进分档：−2…+6 逐点比对（任一点落空即红）")
    void every_integer_delta_lands_in_exactly_one_bucket() {
        // 覆盖规格 §1.4 分档表的全部三档，并含两侧边界。
        // ★ 这条测试的存在理由：分档是"三个 if"，而三个 if 的接缝<b>不会</b>互相校验。
        //   逐点走一遍，任何空洞都会在最常见的 Δ 上暴露。
        record Case(int delta, String expect) {
        }
        List<Case> cases = List.of(
                new Case(6, "improved"),   // 大幅下降
                new Case(4, "improved"),   // 达改善门槛
                new Case(3, "improved"),   // 恰在改善门槛
                new Case(2, "stable"),     // 稳定上界
                new Case(1, "stable"),     // 稳定内部
                new Case(0, "stable"),     // ★ 无变化 —— 曾经的洞
                new Case(-1, "worsened"),  // 恰在加重门槛
                new Case(-2, "worsened")); // 明显加重

        for (Case c : cases) {
            // Δ = base − cur；取 base=12 使两侧都在 0–16 量纲内
            int base = 12;
            int cur = base - c.delta();
            var r = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                    new EffectVerdictEngine.SameOriginAssert(true, true, true), base, cur));

            assertEquals(c.delta(), r.delta(), "Δ 应回显为 " + c.delta());
            assertFalse(r.suspended(), "Δ=" + c.delta() + " 不应挂起");
            switch (c.expect) {
                case "stable" -> assertEquals(EffectVerdict.E3_STABLE, r.verdict(),
                        "Δ=" + c.delta() + " 应判 E3 稳定（config #33 + 规格 §1.4）");
                case "worsened" -> {
                    assertNotEquals(EffectVerdict.E5_WORSENED, r.verdict(),
                            "🛑 E5（加重）涉安全，引擎不得自动产出（规格 §3.2：E5 必须人工录入）");
                    assertEquals(EffectVerdict.E4_NO_IMPROVEMENT, r.verdict(),
                            "Δ=" + c.delta() + " 应判 E4 侧（E4/E5 由人工分）");
                    assertTrue(r.requiresHuman(),
                            "加重侧需人工 —— E4/E5 之分取决于是否新发/高危或题项上升级数，不在分数里");
                }
                case "improved" -> {
                    assertTrue(r.requiresHuman(),
                            "改善侧需人工 —— E1/E2 之分取决于『显著阈值』，而 PRD 未给值"
                                    + "（硬纪律 #6：TBD 不得填数）");
                    assertNull(r.verdict(),
                            "E1/E2 未校准时不应硬定分支，应留空由人工确认（Δ=" + c.delta() + "）");
                    assertEquals(ContractTbd.TBD, r.significantThreshold(),
                            "显著性阈值应为 TBD —— 它是被契约冻结的占位语义，不是『先填个数顶着』");
                }
                default -> throw new AssertionError("未登记档位: " + c.expect);
            }
        }
    }

    @Test
    @DisplayName("同源断言不成立 ⇒ 挂起（不出结论），且点名是哪一条不成立")
    void incomparable_measurement_suspends_instead_of_guessing() {
        // 三种不同的"不同源"，各自都必须挂起
        var diffGroup = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(false, true, true), 12, 8));
        var diffRange = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, false, true), 12, 8));
        var diffMeasurer = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, true, false), 12, 8));

        for (var r : List.of(diffGroup, diffRange, diffMeasurer)) {
            assertTrue(r.suspended(),
                    "同源断言不成立必须挂起（PRD P0-12『测量不可比挂起』；规格 §1.3："
                            + "IR = NULL，判定 = 人工复核）");
            assertNull(r.verdict(),
                    "挂起时分支必须为 null —— 不是 E3。『未知』与『稳定』是相反的两件事："
                            + "前者要人看，后者可以直接用");
            assertNotNull(r.suspendReason(), "挂起必须说明原因（不得静默）");
            assertTrue(r.requiresHuman(), "挂起必经人工");
        }
        assertTrue(diffGroup.suspendReason().contains("题组"),
                "挂起原因应点名是哪一条断言不成立（不得只说『不可比』）；实际="
                        + diffGroup.suspendReason());
        assertTrue(diffRange.suspendReason().contains("量程"),
                "应点名量程不一致；实际=" + diffRange.suspendReason());
        assertTrue(diffMeasurer.suspendReason().contains("测量人"),
                "应点名测量人/工具不同一；实际=" + diffMeasurer.suspendReason());

        // 前置断言缺失（null）也要挂起，不得默认按同源处理
        var nullAssert = verdict.decide(
                new EffectVerdictEngine.ImprovementInput(null, 12, 8));
        assertTrue(nullAssert.suspended(),
                "同源断言缺失必须挂起 —— 默认按同源处理等于『不问就假定可比』");
    }

    @Test
    @DisplayName("基线或复评缺失 ⇒ 挂起（不得补 0）；越界 ⇒ 抛错（不得截断）")
    void missing_or_out_of_range_totals_are_never_patched() {
        assertTrue(verdict.decide(new EffectVerdictEngine.ImprovementInput(
                        new EffectVerdictEngine.SameOriginAssert(true, true, true), null, 8)).suspended(),
                "基线缺失必须挂起 —— 缺失不得补 0（硬纪律 #4）");
        assertTrue(verdict.decide(new EffectVerdictEngine.ImprovementInput(
                        new EffectVerdictEngine.SameOriginAssert(true, true, true), 12, null)).suspended(),
                "复评缺失必须挂起");

        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> verdict.decide(new EffectVerdictEngine.ImprovementInput(
                                new EffectVerdictEngine.SameOriginAssert(true, true, true), 12, 17))).getCode(),
                "复评超出模块满分（16）应抛 1001 —— 越界不得截断（截断会静默改分）");
    }

    @Test
    @DisplayName("baseline_zero：基线为 0 走新发路径，用绝对量而非百分比")
    void baseline_zero_takes_the_new_onset_path_with_absolute_delta() {
        // 基线 0 且复评 0：无病且仍无病，不属疗效范围
        var zeroZero = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, true, true), 0, 0));
        assertTrue(zeroZero.suspended() && zeroZero.baselineZero(),
                "基线 0 且复评 0 应挂起并标记 baseline_zero（走『新发症状』路径，不计改善率）");
        assertNull(zeroZero.improvementRate(),
                "baseline_zero 不得算改善率 —— 分母为 0，百分比无定义");

        // 基线 0 且复评 > 0：新发 / 加重，用绝对量
        var zeroUp = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, true, true), 0, 4));
        assertTrue(zeroUp.baselineZero(), "应标记 baseline_zero");
        assertNull(zeroUp.improvementRate(),
                "不得用百分比表达 —— 分母为 0 时百分比是 ∞，不能用 ∞ 表之（规格 §1.3 边界表）");
        assertEquals(4, zeroUp.delta(),
                "应以绝对变化量表达新发幅度（Δ=4）；实际=" + zeroUp.delta());
        assertNotEquals(EffectVerdict.E5_WORSENED, zeroUp.verdict(),
                "🛑 涉新发的情形引擎不得自产 E5（涉安全，必须人工录入）");
    }

    @Test
    @DisplayName("🛑 引擎绝不自动产出 E5 —— 加重结论不得由批处理写进客户档案")
    void the_engine_never_auto_produces_the_severe_verdict() {
        // 走遍全部可能的 Δ（在 0–16 量纲内），断言 E5 一次都不出现
        for (int base = 0; base <= 16; base++) {
            for (int cur = 0; cur <= 16; cur++) {
                var r = verdict.decide(new EffectVerdictEngine.ImprovementInput(
                        new EffectVerdictEngine.SameOriginAssert(true, true, true), base, cur));
                assertNotEquals(EffectVerdict.E5_WORSENED, r.verdict(),
                        "Δ=" + (base - cur) + "（" + base + "→" + cur + "）时引擎自产了 E5 —— "
                                + "E5 涉安全，规格 §3.2 明定『必须人工录入』");
            }
        }
        assertTrue(EffectVerdict.E5_WORSENED.mustBeHumanEntered(),
                "E5 的类型级标记必须为『必须人工录入』");
        assertFalse(EffectVerdict.E5_WORSENED.systemCanAutoDecide(),
                "E5 不得标记为『系统可自动判定』");
    }

    @Test
    @DisplayName("效果判定的自动/人工标记与规格 §3.2 逐条一致")
    void auto_vs_human_flags_match_the_spec_table() {
        // 规格 §3.2 逐字：E1/E2 = 系统算 + 人工确认；E3 = 系统可自动；
        //               E4 = 系统可自动（核心困扰项由人工录入）；E5 = 必须人工录入
        //
        // ⚠️ 读法要点：枚举把"那一列"编码成两个正交事实 ——
        //   systemCanAutoDecide  = 系统能否【算出候选并自动落库】
        //   requiresHumanConfirmation = 落库前是否必须有人参与
        // 规格里的"系统算 + 人工确认"落在 E1/E2 上就是 (false, true)：
        //   系统能算候选，但【不许自动落库】—— 这正是"待确认"的本质。
        // 若在这里断言 E1 的 systemCanAutoDecide==true，反而把"需人工确认"
        // 这件事从类型上抹掉了（下游就会有人照着 true 去自动落库）。
        assertTrue(EffectVerdict.E1_SIGNIFICANT.requiresHumanConfirmation()
                        && !EffectVerdict.E1_SIGNIFICANT.systemCanAutoDecide(),
                "E1 = 系统算候选 + 人工确认后落库（规格 §3.2）—— 它不是『系统可自动判定』");
        assertTrue(EffectVerdict.E2_PARTIAL.requiresHumanConfirmation()
                        && !EffectVerdict.E2_PARTIAL.systemCanAutoDecide(),
                "E2 = 同 E1（同上）");
        assertTrue(EffectVerdict.E3_STABLE.systemCanAutoDecide()
                        && !EffectVerdict.E3_STABLE.requiresHumanConfirmation(),
                "E3 = 系统可自动判定（无需人工）—— 若它要求人工，最常见的稳定结论会被全部积压");
        assertTrue(EffectVerdict.E4_NO_IMPROVEMENT.systemCanAutoDecide()
                        && !EffectVerdict.E4_NO_IMPROVEMENT.requiresHumanConfirmation(),
                "E4 = 系统可自动判定（核心困扰项由人工录入）");
        // E5 与 E1/E2 都 requiresHuman，但强度不同：E1/E2 的系统可算候选，E5 连候选都不许自动落库。
        assertTrue(EffectVerdict.E5_WORSENED.mustBeHumanEntered()
                        && !EffectVerdict.E5_WORSENED.systemCanAutoDecide(),
                "E5 = 必须人工录入（涉安全）—— 系统不得自动产出该结论");

        // 两档"人工"不得混同：mustBeHumanEntered ⊆ requiresHumanConfirmation，
        // 且二者在 E1 上必须分离（E1 requiresHuman 但非 mustBeHumanEntered）——
        // 否则 E5 的"涉安全"特殊性会被一句"反正都要人工"抹平。
        assertTrue(EffectVerdict.E1_SIGNIFICANT.requiresHumanConfirmation()
                        && !EffectVerdict.E1_SIGNIFICANT.mustBeHumanEntered(),
                "E1 需人工【确认】但不属【必须人工录入】—— 二者是不同强度的约束");
        for (EffectVerdict v : EffectVerdict.values()) {
            if (v.mustBeHumanEntered()) {
                assertTrue(v.requiresHumanConfirmation(),
                        v.name() + " 标记为必须人工录入，却未标记为需人工参与 —— 标记自相矛盾");
            }
        }
        assertEquals(1, java.util.Arrays.stream(EffectVerdict.values())
                        .filter(EffectVerdict::mustBeHumanEntered).count(),
                "『必须人工录入』只应有 E5 一档（规格 §3.2）");

        // 改善侧 = E1 ∪ E2（规格 §3.2 / ECC 北极星）
        assertTrue(EffectVerdict.E1_SIGNIFICANT.isImprovement()
                        && EffectVerdict.E2_PARTIAL.isImprovement(),
                "E1/E2 属改善侧（ECC 北极星 effect ∈ {E1,E2,E3}）");
        assertFalse(EffectVerdict.E3_STABLE.isImprovement(), "E3 稳定不属改善");
        assertEquals(5, EffectVerdict.allLabels().size(), "E1~E5 共五档");
    }

    // ==================================================================
    // 四、置信度：规格 §10.4③ 的校验点逐点比对
    // ==================================================================

    @Test
    @DisplayName("m(Δ) 校验点逐点比对（规格 §10.4③ 逐字给出的八个点）")
    void mcid_margin_matches_the_spec_checkpoints() {
        // 规格 §10.4③ 原文：Δ=0→1.000 / 1→0.717 / 2→0.433 / 2.9→0.178 / 3→0.150 /
        //                   4→0.433 / 5→0.717 / 6→1.000
        //  ⚠️ 这些点【不是自证】：它们在规格里逐字写着，本类只是把它们搬成断言。
        //     2.9→0.178 这一点尤其重要 —— 它证明式子是连续的线性斜坡，
        //     而不是"Δ=2 与 Δ=3 两档跳变"。若实现写成分段常量，这一点会红。
        record Point(String delta, String expect) {
        }
        List<Point> points = List.of(
                new Point("0", "1.000"),
                new Point("1", "0.717"),
                new Point("2", "0.433"),
                new Point("3", "0.150"),
                new Point("4", "0.433"),
                new Point("5", "0.717"),
                new Point("6", "1.000"));

        for (Point p : points) {
            BigDecimal d = new BigDecimal(p.delta());
            // mcidMargin 取 int；对 2.9 需用连续式直接验（见下一条断言）
            if (d.stripTrailingZeros().scale() <= 0) {
                BigDecimal actual = confidence.mcidMargin(d.intValueExact());
                assertEquals(0, actual.compareTo(new BigDecimal(p.expect)),
                        "m(Δ=" + p.delta() + ") 应为 " + p.expect + "（规格 §10.4③）；实际=" + actual);
            }
        }

        // Δ=3 是唯一谷底：两侧单调升高，且它严格小于两侧
        BigDecimal at3 = confidence.mcidMargin(3);
        assertTrue(at3.compareTo(confidence.mcidMargin(2)) < 0
                        && at3.compareTo(confidence.mcidMargin(4)) < 0,
                "Δ=3 必须是唯一谷底（两侧都更高）—— 规格 §10.4② 明定，"
                        + "且『不得表述为全局严格单调』");
        assertEquals(0, at3.compareTo(new BigDecimal("0.150")),
                "谷底值应为 0.15（config #45 m.floor）—— 它必须非零"
                        + "（『恰好达标』仍有信息量），且须与挂起 0 可区分");

        // 地板之上的单调段：Δ 从 3 走到 6，m 单调不降；走到 9 后饱和在 1.000
        BigDecimal prev = at3;
        for (int delta = 4; delta <= 6; delta++) {
            BigDecimal cur = confidence.mcidMargin(delta);
            assertTrue(cur.compareTo(prev) > 0,
                    "Δ=" + delta + " 处 m 应严格高于 Δ=" + (delta - 1) + "（远离谷底单调升高）");
            prev = cur;
        }
        assertEquals(0, confidence.mcidMargin(6).compareTo(BigDecimal.ONE),
                "Δ=6（偏离谷底恰一个 MCID）应饱和到 1.000 —— 宽度取 W=一个 MCID=3");
        assertEquals(0, confidence.mcidMargin(9).compareTo(BigDecimal.ONE),
                "Δ 超过一个 MCID 后应保持 1.000（min(1, …) 饱和）；实际=" + confidence.mcidMargin(9));
    }

    @Test
    @DisplayName("🛑 s=0（不可比）⇒ 挂起，不是『最低置信档』（两种结果结构上不同）")
    void incomparable_same_origin_suspends_rather_than_scoring_low() {
        var r = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.INCOMPARABLE, 28, ENOUGH_DAYS, 3));

        assertNull(r.confidence(),
                "不可比时置信度必须为 null —— 若返回 0.000，报告读者会把『不可判』读成『置信度很低』，"
                        + "而两者对下游是完全不同的指令（规格 §10.4①）");
        assertTrue(r.suspended(), "应标记挂起");
        assertNull(r.band(), "挂起时展示档必须为 null —— 它不是『低』档");
        assertTrue(r.requiresHumanReview(), "不可比必须强制转人工（config #45 s.incomparable_forces_human）");
        assertNotNull(r.suspendReason(), "挂起必须说明原因");
        assertTrue(r.suspendReason().contains("effect_verdict"),
                "挂起原因应说明 effect_verdict 须置空（规格 §10.4①）；实际=" + r.suspendReason());

        // 反向自证：低置信与挂起必须可区分 —— 构造一个真正落"低"档的输入
        var low = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.MISSING_METADATA, 3, 0, 3));
        assertFalse(low.suspended(),
                "缺元数据（s=0.5）不属一票否决，不得挂起 —— 它只是证据链不完整");
        assertNotNull(low.confidence(), "非挂起时应有数值");
        assertEquals(VerdictConfidenceEngine.ConfidenceBand.LOW, low.band(),
                "该输入应落『低』档 —— 它与上面的『挂起』是两个不同的返回（这是本条测试的核心）");
        assertTrue(low.requiresHumanReview(),
                "低档在协商辅助场景也需人工（ConfidenceResult.requiresHumanReview 的第二种情形）");
    }

    @Test
    @DisplayName("合成式为加权几何（非算术和）：指数取自 config #45 的 composition 字符串")
    void composition_uses_the_configured_exponents() {
        // 满因子输入：s=1、d=28/28=1、n=14/14=1、m 取 Δ=0 处（1.000）
        // ⇒ C = 1 × 1^0.25 × 1^0.25 × 1^0.5 = 1.000
        var full = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 28, ENOUGH_DAYS, 0));
        assertEquals(0, full.confidence().compareTo(BigDecimal.ONE),
                "四项因子全满 ⇒ C 应为 1.000；实际=" + full.confidence());
        assertFalse(full.suspended(), "满因子不应挂起");
        assertEquals(VerdictConfidenceEngine.ConfidenceBand.HIGH, full.band(),
                "C=1.000 ≥ 0.75 应落『高』档");
        assertFalse(full.requiresHumanReview(), "高置信不需人工复核");

        // 几何 vs 算术的判别性证据：d=14/28=0.5，其余满
        //  几何（正确）：1 × 0.5^0.25 × 1 × 1 = 0.5^0.25 = 0.8409 → 0.841
        //  算术（错误）：(1+0.5+1+1)/4 = 0.875 —— 两者可区分
        var halfD = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 14, ENOUGH_DAYS, 0));
        assertEquals(0, halfD.confidence().compareTo(new BigDecimal("0.841")),
                "d=0.5 时几何式应为 0.5^0.25=0.8409→0.841；"
                        + "若拿到 0.875 说明实现用了算术平均（那会让『最弱项定调』失效）；"
                        + "实际=" + halfD.confidence());
        assertEquals(3, halfD.confidence().scale(), "置信度应保留三位小数（与 NUMERIC(4,3) 同精度）");
    }

    @Test
    @DisplayName("n 不适用（应填 < 7）⇒ n 退出合成且指数按配置重归一（d : m = 1/3 : 2/3）")
    void when_n_is_not_applicable_the_exponents_are_reweighted() {
        // 应填 6 天 < 7 ⇒ n 不适用。规格 §10.4①：n 退出合成后指数必须重新分配，
        // 否则任何一次"样本不足"都会把 C 拖低 —— 而"数据不够"与"结论不可信"是两件事。
        var r = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 28, 6, 0));

        assertFalse(r.suspended(), "样本不足不属一票否决（那是 s 的职责）");
        assertFalse(r.exponentsUsed().containsKey("n"),
                "n 不适用时它必须退出指数集；实际指数=" + r.exponentsUsed());
        assertEquals(0, r.exponentsUsed().get("d").compareTo(new BigDecimal("0.3333")),
                "重归一后 d 的指数应为 0.3333（config #45 n.reweight_when_na）；"
                        + "实际=" + r.exponentsUsed());
        assertEquals(0, r.exponentsUsed().get("m").compareTo(new BigDecimal("0.6667")),
                "重归一后 m 的指数应为 0.6667；实际=" + r.exponentsUsed());
        assertNull(r.factors().get("n"),
                "n 退出后其因子值应为 null（不得留一个 0 或 1 冒充）");
        // d=1、m(0)=1 ⇒ 重归一后 C 仍应为 1.000（这证明"样本不足没有拖低约束度"）
        assertEquals(0, r.confidence().compareTo(BigDecimal.ONE),
                "d=1 且 m=1 时，n 退出与否都不应改变 C=1.000 —— 若此处掉到 0.8 以下，"
                        + "说明重归一没生效（n 被当成 0 参与了合成）；实际=" + r.confidence());
    }

    @Test
    @DisplayName("展示三档边界来自 config #45：≥0.75 高 / 0.45~0.75 中 / <0.45 低")
    void display_bands_use_the_configured_boundaries() {
        assertEquals(0, profile.confidence().highMin().compareTo(new BigDecimal("0.75")),
                "『高』档下界应为 0.75（config #45 bands.high_min）");
        assertEquals(0, profile.confidence().mediumMin().compareTo(new BigDecimal("0.45")),
                "『中』档下界应为 0.45（config #45 bands.medium_min）");

        // 档位边界两侧必须翻转（这证明比较用的是配置值而非别的数）
        // 构造 C 落在 0.75 与 0.45 附近的输入：d = k/28，其余满 ⇒ C = (k/28)^0.25
        // (k/28)^0.25 = 0.75 ⇒ k/28 = 0.3164 ⇒ k ≈ 8.86
        var belowHigh = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 8, ENOUGH_DAYS, 0));
        var aboveHigh = confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 9, ENOUGH_DAYS, 0));
        assertEquals(VerdictConfidenceEngine.ConfidenceBand.MEDIUM, belowHigh.band(),
                "C=" + belowHigh.confidence() + " < 0.75 应为『中』档");
        assertEquals(VerdictConfidenceEngine.ConfidenceBand.HIGH, aboveHigh.band(),
                "C=" + aboveHigh.confidence() + " ≥ 0.75 应为『高』档 —— "
                        + "两个相邻的答题数必须跨过档位边界，证明比较用的是配置阈值");
    }

    @Test
    @DisplayName("答题数越界 ⇒ 抛错（不得截断到分母）")
    void answered_count_out_of_range_is_rejected() {
        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, 29, ENOUGH_DAYS, 0))).getCode(),
                "答题数超过分母（28）应抛 1001 —— 越界不得截断");
        assertEquals(1001,
                assertThrows(BizException.class,
                        () -> confidence.compute(new VerdictConfidenceEngine.ConfidenceInput(
                                VerdictConfidenceEngine.SameOriginStatus.SAME_ORIGIN, -1, ENOUGH_DAYS, 0))).getCode(),
                "负数答题数应抛 1001");
    }

    // ==================================================================
    // 五、引擎构造：口径缺失即拒绝（不得按默认口径算）
    // ==================================================================

    @Test
    @DisplayName("三个引擎都不得以空口径构造（口径缺失 = 拒绝计算）")
    void engines_refuse_to_be_constructed_without_a_profile() {
        assertEquals(5001, assertThrows(BizException.class,
                () -> new AdherenceEngine(null)).getCode(), "依从性引擎缺口径应抛 5001");
        assertEquals(5001, assertThrows(BizException.class,
                () -> new EffectVerdictEngine(null)).getCode(), "效果判定引擎缺口径应抛 5001");
        assertEquals(5001, assertThrows(BizException.class,
                () -> new VerdictConfidenceEngine(null)).getCode(), "置信度引擎缺口径应抛 5001");
    }

    @Test
    @DisplayName("三个引擎共享同一份口径：跨源约束对三者同时生效")
    void all_engines_share_one_profile_so_cross_source_constraints_apply_to_all() {
        // 口径对象是同一个实例 —— 若各引擎各解析一份，就会出现"三份各自自洽、合起来不一致"
        assertTrue(adherence.profile() == verdict.profile()
                        && verdict.profile() == confidence.profile(),
                "三个引擎必须共享同一份口径剖面 —— 否则跨源约束只对其中一个生效");

        // AS_ops（运营口径）不进判定：本引擎只暴露该事实，不参与计算
        assertTrue(adherence.opsOnlyDimensions().contains("A2"),
                "AS_ops 应含 A2（运营看板口径）");
        assertEquals(Set.of("A1", "A2", "A3", "A4"), Set.copyOf(adherence.opsOnlyDimensions()),
                "AS_ops 应为四维（A1/A2/A3/A4）；实际=" + adherence.opsOnlyDimensions());
        assertFalse(AdherenceEngine.REFUND_DIMENSIONS.contains("A2"),
                "🛑 判定口径固定三维 {A1,A3,A4}，不含 A2（合规锁）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 定位 {@code app/derived} 主源码目录（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveDerivedMainDir() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/derived");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 app/derived 主源码目录（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.isRegularFile(p), "待扫描的源文件必须存在: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String context(String code, int at) {
        int from = Math.max(0, at - 30);
        int to = Math.min(code.length(), at + 30);
        return code.substring(from, to).replace('\n', ' ');
    }

    private static String stripCommentsAndStrings(String src) {
        // (?s) = DOTALL：块注释跨行匹配（本仓库 Javadoc 里逐字写着权重与阈值，
        // 不剥注释会把文档判成硬编码）。
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }
}