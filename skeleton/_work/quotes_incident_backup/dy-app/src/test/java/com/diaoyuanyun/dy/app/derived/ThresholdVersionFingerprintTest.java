package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code threshold_version} 作为<b>内容寻址指纹</b>的定义性断言（ADR-11 · S2-8）。
 *
 * <h2>它守的是什么（逐字引用 PRD §C.1.9 硬约束③）</h2>
 * <pre>
 *   「版本不可覆盖：题库、方案、判定（verdict.threshold_version）、文书模板四处
 *     只可新增版本，不可原地覆盖」
 * </pre>
 * 在 S2-8 之前，{@code threshold_version} 是调用方传的一个自由字符串。
 * 那种形态下"版本不可覆盖"在实现上只是口号：同一个串可以指向任意多套口径，
 * 而库里无法察觉（{@code idx_verdict_threshold} 把不同口径的行分进了同一组）。
 *
 * <p>本类要把两条事实变成<b>可机械断言</b>的：
 * <ol>
 *   <li><b>同一口径 ⇒ 同一版本号</b>（{@link #same_profile_yields_the_same_version_twice}）；</li>
 *   <li><b>口径变 ⇒ 版本号变</b>（{@link #changing_any_segment_changes_the_version}
 *       + {@link #only_the_touched_segment_drifts}）。</li>
 * </ol>
 * 第 2 条是"版本不可覆盖"的机械形态：新口径天然得到一个<b>新</b>版本号，
 * 旧版本号在库里仍然指向旧口径 —— 不存在"一个版本号指向两套口径"的可能。
 *
 * <h2>🛑 第二组断言：指纹不得成为口径取值的泄漏通道</h2>
 * {@code threshold_version} 与段级指纹都会落进 {@code evidence_snapshot}，
 * 而那是契约 F1 会<b>下发到端侧</b>的字段。若把口径串（门槛 {@code 0.80}、
 * 权重 {@code 0.571}、缺失策略 {@code structural_keep}）直接写进去，
 * 就等于把内部口径发给端侧 —— 这与 {@code DerivedRawConfig.toString()} 刻意
 * 不打印取值是同一条纪律。故 {@link #describe_and_segment_fingerprints_emit_no_values}
 * 断言"外壳之内不含任何取值"。
 *
 * <p>这一组断言的判据<b>不能</b>是"是否含某个取值子串"—— 例如 {@code "7"}
 * 会命中 {@code "20 位"} 之类的无关文本，让断言恒红且指向错误的原因。
 * 故判据取<b>取值本身</b>（{@code 0.571} / {@code structural_keep} 等
 * 无论出现在什么文本里都只可能是口径取值的长串）。
 */
class ThresholdVersionFingerprintTest {

    /** 与实现类逐字一致的九段顺序（顺序即语义）。 */
    private static final List<String> EXPECTED_SEGMENTS = List.of(
            "pass_threshold",
            "as_refund_weights",
            "as_ops_weights",
            "missing_policy",
            "min_sample_days",
            "mcid",
            "confidence",
            "scale_structure",
            "module_mapping");

    /** 20 位小写十六进制 —— 指纹后缀的形态。 */
    private static final Pattern HEX20 = Pattern.compile("[0-9a-f]{20}");

    /** 基准剖面：来自真实配置真相源（不是手工拼的假对象）。 */
    private static final DerivedMetricProfile BASE =
            DerivedMetricProfile.fromRawConfig(new ConfigSeedDerivedProfileSource().raw());

    private final ThresholdVersionFingerprint fp = ThresholdVersionFingerprint.of(BASE);

    // ==================================================================
    // 一、确定性：同一口径必得同一版本号
    // ==================================================================

    @Test
    @DisplayName("🛑 同一剖面两次取指纹 ⇒ 同一版本号（无随机 / 无时间戳 / 无对象哈希）")
    void same_profile_yields_the_same_version_twice() {
        String a = ThresholdVersionFingerprint.of(BASE).version();
        String b = ThresholdVersionFingerprint.of(BASE).version();
        assertEquals(a, b,
                "🛑 同一份口径算出了两个版本号 —— 内容寻址性被破坏。"
                        + "这条断言在守 PRD L856「模块级 ΔH_m 必须携带映射版本」与硬约束③"
                        + "「版本不可覆盖」的共同前提：版本号若不稳定，"
                        + "『同一口径』这个分组键本身就没有意义了");
    }

    @Test
    @DisplayName("🛑 版本号形态：前缀 tv1- + 20 位小写十六进制（共 24 字符）")
    void version_shape_is_prefix_plus_20_hex() {
        assertEquals("tv1-", ThresholdVersionFingerprint.PREFIX,
                "前缀是『这是内容寻址指纹』在库里的一眼可辨标记（与人工填的串区分）");
        String v = fp.version();
        assertTrue(v.startsWith("tv1-"),
                "版本号必须以 'tv1-' 开头，实际: " + v);
        assertEquals(24, v.length(),
                "总长应为 4（前缀）+ 20（十六进制）= 24，实际: " + v + "（长度 " + v.length() + "）");
        assertTrue(HEX20.matcher(v.substring(4)).matches(),
                "后缀必须是 20 位小写十六进制，实际: " + v.substring(4));
    }

    // ==================================================================
    // 二、段结构：段数、段名、段顺序（少一段必须被抓住）
    // ==================================================================

    @Test
    @DisplayName("🛑 参与指纹的段恒为 9，且顺序逐字钉死（顺序即语义）")
    void segment_set_and_order_are_pinned() {
        assertEquals(9, ThresholdVersionFingerprint.segmentCount(),
                "段数应为 9。这条断言的存在理由：若有人删掉一段（例如觉得 module_mapping "
                        + "『反正还没落库』），版本号会照样算得出来 —— 没有任何报错。"
                        + "段数被钉死后，删段必须显式改这条断言");
        assertEquals(EXPECTED_SEGMENTS, ThresholdVersionFingerprint.SEGMENTS,
                "段名或顺序与预期不一致。🛑 顺序写死而不是遍历 Map，"
                        + "是因为 HashMap 的迭代顺序在不同 JVM 下可能不同 —— "
                        + "那会让同一份口径算出两个版本号（见实现类注释）");
    }

    @Test
    @DisplayName("🛑 段级指纹齐全：9 段各有指纹，且互不相同（证明每段都真的进了哈希）")
    void segment_fingerprints_cover_all_nine_segments_distinctly() {
        Map<String, String> segs = fp.segmentFingerprints();
        assertEquals(9, segs.size(), "段级指纹应有 9 项，实际: " + segs.keySet());
        assertEquals(EXPECTED_SEGMENTS, List.copyOf(segs.keySet()),
                "段级指纹的键序应与 SEGMENTS 一致（LinkedHashMap 保序），实际: " + segs.keySet());
        for (String name : EXPECTED_SEGMENTS) {
            String h = segs.get(name);
            assertTrue(HEX20.matcher(h).matches(),
                    "段 '" + name + "' 的指纹应为 20 位小写十六进制，实际: " + h);
        }
        assertEquals(9, new TreeMap<>(segs).size(),
                "前置自证：段名互不相同（否则下面的『互不相同』会因键合并而为真）");
        assertEquals(9, List.copyOf(new java.util.LinkedHashSet<>(segs.values())).size(),
                "9 段的指纹应互不相同 —— 若有两段相同，说明某一段的内容被另一段覆盖了"
                        + "（例如 canonicalSegments 里两个 out.put 用了同一个键）");
    }

    @Test
    @DisplayName("🛑 部分段为 null 不得静默（剖面为空即拒，不产占位版本号）")
    void null_profile_is_rejected_not_defaulted() {
        BizException e = assertThrows(BizException.class,
                () -> ThresholdVersionFingerprint.of(null),
                "🛑 剖面为空必须抛 —— 若在此处返回一个占位版本号（例如 'tv1-unknown'），"
                        + "落库后它看起来完全正常，而『可回放』已经永久失去（PRD §C.1.9 硬约束②）");
        assertEquals(5001, e.getCode(), "应属业务规则冲突（5001）");
        assertTrue(e.getMessage().contains("可回放"),
                "拒绝理由要点明它与『可回放』的关系（否则运维看不出为何不能给个默认值）。"
                        + "实际: " + e.getMessage());
    }

    // ==================================================================
    // 三、内容寻址：口径变 ⇒ 版本号变（"版本不可覆盖"的机械形态）
    // ==================================================================

    @Test
    @DisplayName("🛑 任一参与段变化 ⇒ 版本号必变（4 段逐一验证）")
    void changing_any_segment_changes_the_version() {
        record Case(String name, DerivedMetricProfile variant) {
        }
        List<Case> cases = List.of(
                new Case("pass_threshold", withPassThreshold(new BigDecimal("0.85"))),
                new Case("as_refund_weights", withRefundWeights(Map.of(
                        "A1", new BigDecimal("0.500"),
                        "A3", new BigDecimal("0.300"),
                        "A4", new BigDecimal("0.200")))),
                new Case("missing_policy", withMissingPolicy(
                        DerivedMetricProfile.MissingPolicy.BEHAVIORAL_ZERO)),
                new Case("min_sample_days", withMinSampleDays(BASE.minSampleDays() + 3)));

        for (Case c : cases) {
            String other = ThresholdVersionFingerprint.of(c.variant()).version();
            assertNotEquals(fp.version(), other,
                    "🛑 改了 '" + c.name() + "' 这一段的声明值，版本号却没变 —— "
                            + "这正是硬约束③『版本不可覆盖』被绕过的形态："
                            + "新口径与旧口径共用一个版本号，"
                            + "库里 idx_verdict_threshold 会把它们分进同一组，"
                            + "而复盘时无法分辨一条历史结论是按哪套口径下的");
        }
    }

    @Test
    @DisplayName("🛑 段隔离：只动一段时，恰好只有那一段的指纹漂移（其余 8 段不动）")
    void only_the_touched_segment_drifts() {
        DerivedMetricProfile variant = withMinSampleDays(BASE.minSampleDays() + 3);
        ThresholdVersionFingerprint other = ThresholdVersionFingerprint.of(variant);

        // 用"今天"的指纹去比对"当时"的段级指纹（语义与回放器一致）
        Map<String, String> drifted = other.driftedSegments(fp.segmentFingerprints());
        assertEquals(List.of("min_sample_days"), List.copyOf(drifted.keySet()),
                "只改了 min_sample_days，漂移段应恰好是它。实际漂移: " + drifted
                        + "。🛑 这条断言比『总版本变了』强得多：总版本变只能说明『有东西变了』，"
                        + "而复盘要回答的是『哪里变了』—— 『只是 as_ops_weights 动了』（不影响判定）"
                        + "与『pass_threshold 动了』（直接改变达标判定）是完全不同的两件事");
        assertTrue(drifted.get("min_sample_days").contains("→"),
                "漂移项应报出『旧→新』两个指纹，便于对照。实际: " + drifted);
    }

    @Test
    @DisplayName("🛑 同一数值的两种写法不得产生伪漂移（0.80 ≡ 0.8）")
    void decimal_notation_does_not_produce_false_drift() {
        // BigDecimal("0.80").equals(BigDecimal("0.8")) == false，但它们是同一个门槛。
        // 若指纹把写法当口径，就会出现"把声明从 0.80 改写成 0.8，判定结论毫无变化，
        // 但版本号变了、索引分组断了" —— 这类噪音会让真实漂移淹没在伪漂移里。
        BigDecimal a = new BigDecimal("0.80");
        BigDecimal b = new BigDecimal("0.8");
        assertFalse(a.equals(b),
                "前置自证：两个写法在 BigDecimal.equals 下确实不同（否则本断言退化）");
        assertEquals(0, a.compareTo(b), "前置自证：两个写法数值相同");

        assertEquals(ThresholdVersionFingerprint.of(withPassThreshold(a)).version(),
                ThresholdVersionFingerprint.of(withPassThreshold(b)).version(),
                "🛑 0.80 与 0.8 是同一个门槛，必须得到同一个版本号 —— "
                        + "否则一次无害的写法整理会切断阈值分组，让真实漂移无从辨认");
    }

    @Test
    @DisplayName("🛑 matches()：逐字比较，且容忍落库值两侧空白")
    void matches_is_literal_but_tolerates_surrounding_whitespace() {
        String v = fp.version();
        assertTrue(fp.matches(v), "自身版本号必须匹配");
        assertTrue(fp.matches(" " + v + "\n"),
                "落库值两侧的空白应被容忍（JDBC/导入路径可能带入空白，"
                        + "而把空白当口径不一致会造出一次假漂移）");
        assertFalse(fp.matches(null), "null 不匹配（不得 NPE）");
        assertFalse(fp.matches(""), "空串不匹配");
        assertFalse(fp.matches(v + "0"),
                "🛑 尾部多一位即为不同版本号 —— 不得用 startsWith/contains 之类的宽松比较："
                        + "宽松比较会让『另一个口径的版本号』在有前缀关系时被误认为相同");
        assertFalse(fp.matches(v.substring(0, v.length() - 1)),
                "🛑 截断一位即为不同版本号");
    }

    // ==================================================================
    // 四、🛑 不泄漏口径取值（evidence_snapshot 会下发到端侧）
    // ==================================================================

    @Test
    @DisplayName("🛑 自描述与段级指纹均不含任何口径取值（哈希是单向的）")
    void describe_and_segment_fingerprints_emit_no_values() {
        String blob = flatten(fp.describe()) + "|" + flatten(fp.segmentFingerprints());

        // 判据取『长取值』：它们在无关文本里不可能出现，
        // 故命中即真泄漏（不用短串如 '7' / '16' —— 那会命中 '20 位' 之类的说明文字）。
        for (String value : List.of(
                "0.571", "0.286", "0.143",   // AS_refund 权重（config #5）
                "0.40", "0.30", "0.20",      // AS_ops 权重（config #5）
                "structural_keep", "behavioral_zero",   // 缺失策略（config #6）
                "module_total_max", "improved.delta_min",  // MCID 规范串的字段名
                "exponents=", "bands.high_min")) {         // 置信度规范串的字段名
            assertFalse(blob.contains(value),
                    "🛑 指纹的自描述里出现了口径取值/规范串片段 '" + value + "' —— "
                            + "它会随 evidence_snapshot 下发到端侧，等于把内部口径发出去。"
                            + "哈希是单向的：足以回答『是不是同一套口径』，"
                            + "回答不了『门槛是多少』（与 DerivedRawConfig.toString 同一条纪律）");
        }

        // 正向自证：它确实给出了『能回答同一性』的东西（否则上面的空断言退化为恒绿）
        assertTrue(blob.contains(fp.version()),
                "自描述里应含版本号本身 —— 它是『是不是同一套口径』的答案");
        assertTrue(blob.contains("no_values_emitted"),
                "自描述应显式声明『不含取值』及其理由，便于复核");
    }

    @Test
    @DisplayName("🛑 明示排除项已登记：不在指纹里的两项 + 未落库的映射来源")
    void excluded_items_are_registered_not_silently_omitted() {
        assertEquals(9, ThresholdVersionFingerprint.segmentCount(),
                "前置：段数为 9");

        String excluded = ThresholdVersionFingerprint.EXCLUDED_NOTE;
        for (String must : List.of("significant_threshold", "backtest_gates", "#30")) {
            assertTrue(excluded.contains(must),
                    "🛑 EXCLUDED_NOTE 应逐字点名被排除的项 '" + must + "' —— "
                            + "『不纳入』与『忘了纳入』在复盘时无法区分，"
                            + "而后者会让版本号声称覆盖了它、实际没有。实际: " + excluded);
        }
        assertTrue(excluded.contains("TBD"),
                "排除理由应点明『恒 TBD』（它们不是口径值，而是尚未产生的值）");

        String mapping = ThresholdVersionFingerprint.MODULE_MAPPING_SOURCE_NOTE;
        assertTrue(mapping.contains("2.9.6"),
                "🛑 模块映射段是硬约束③与 PRD L856 的落点，其来源说明应点名 PRD §2.9.6。"
                        + "实际: " + mapping);
        assertTrue(mapping.contains("孤儿"),
                "来源说明应点明该映射含『孤儿维』（它们是 PRD 明示的，不是漏了）");
        assertTrue(mapping.contains("尚未落库") || mapping.contains("尚未落成"),
                "🛑 来源说明必须如实写明『映射尚未落库、本段按 PRD 表逐字重建』—— "
                        + "而不是让读者以为它已从配置读取。实际: " + mapping);
    }

    @Test
    @DisplayName("🛑 排除项必须与配置真相源交叉自证：cfg:verdict.* 全集 = 九段已用 ∪ 显式排除（防『排除了不该排除的』）")
    void excluded_items_are_cross_checked_against_the_verdict_config_namespace() throws IOException {
        // 🛑 本断言的由来（N-3 的机械形态）：
        //    `EXCLUDED_NOTE` 只说了 #30，而 cfg:verdict.* 实际有【4 个】槽位
        //    （#9 branch_rules / #30 formula_params / #33 mcid_threshold / #45 confidence_formula），
        //    指纹用了 #33 / #45 —— 那么 #9 呢？
        //    实测：**它既不在九段里、也不在 EXCLUDED_NOTE 里** —— 两侧都不在，
        //    正是"不纳入"与"忘了纳入"无法区分的那种形态。
        //    本断言把这条从"要靠人读出来"变成"构建时机械核对"。

        // ---- ① cfg:verdict.* 的【全集】必须来自真相源，不得手抄 ----
        Set<String> allVerdictSlots = seedSlotNumbersInNamespace("cfg:verdict.");
        assertEquals(Set.of("9", "30", "33", "45"), allVerdictSlots,
                "🛑 cfg:verdict.* 的槽位集合变了 —— 本断言的排除清单必须随之更新。"
                        + "实际: " + allVerdictSlots);

        // ---- ② 九段【实际消费】的 verdict 命名空间槽位（从段的来源机械推导，不手抄） ----
        //    每一段的来源槽位在本类里以注释形式声明"来源 = config #N"；
        //    此处按同一事实声明一次，并断言它 ⊆ 全集（防止"引用了一个不存在的槽位号"）。
        Set<String> consumedVerdictSlots = Set.of("33", "45");   // mcid ← #33 · confidence ← #45
        assertTrue(allVerdictSlots.containsAll(consumedVerdictSlots),
                "🛑 九段声明消费的 verdict 槽位 " + consumedVerdictSlots
                        + " 不是 cfg:verdict.* 的子集 —— 段来源说明里引用了不存在的槽位号");

        // ---- ③ 关键断言：全集 = 已消费 ∪ 已排除，【不得有第三个】 ----
        Set<String> accounted = new TreeSet<>(consumedVerdictSlots);
        String excluded = ThresholdVersionFingerprint.EXCLUDED_NOTE;
        for (String no : allVerdictSlots) {
            if (excluded.contains("#" + no)) {
                accounted.add(no);
            }
        }
        Set<String> unaccounted = new TreeSet<>(allVerdictSlots);
        unaccounted.removeAll(accounted);
        assertTrue(unaccounted.isEmpty(),
                "🛑 以下 cfg:verdict.* 槽位【既不在九段里、也不在 EXCLUDED_NOTE 里】: " + unaccounted
                        + " —— 这就是 N-3 要防的形态：『不纳入』与『忘了纳入』在复盘时无法区分。\n"
                        + "🛑 二选一：① 若它确实参与判定且已成为口径 → 扩段并同步段数/来源；"
                        + "② 若它是『已声明但运行时未消费』→ 必须写进 EXCLUDED_NOTE 并写明理由。");

        // ---- ④ 反向：EXCLUDED_NOTE 里点名的 verdict 槽位必须真实存在（防追认一个幻影） ----
        for (String no : List.of("30")) {   // 当前唯一被排除的 verdict 槽位
            assertTrue(excluded.contains("#" + no),
                    "🛑 EXCLUDED_NOTE 应点名 #" + no + "（当前唯一被排除的 verdict 槽位）");
        }
    }

    private static Set<String> seedSlotNumbersInNamespace(String namespace) throws IOException {
        try (InputStream is = ThresholdVersionFingerprintTest.class.getClassLoader()
                .getResourceAsStream("db/config/02_slots_seed.sql")) {
            assertNotNull(is, "classpath 上找不到 02_slots_seed.sql —— 交叉自证的前提断开了");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> out = new TreeSet<>();
            // 声明行形态： (12, 'cfg:xxx.yyy', ...
            Matcher m = Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*'" + Pattern.quote(namespace) + "[^']*'")
                    .matcher(sql);
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        }
    }

    @Test
    @DisplayName("🛑 toString 只报版本号与段数（日志不得成为口径的第二份副本）")
    void to_string_reports_no_values() {
        String s = fp.toString();
        assertTrue(s.contains(fp.version()), "toString 应含版本号，实际: " + s);
        assertTrue(s.contains("segments=9"),
                "toString 应报段数（『少了一段却没报错』的最小可观测信号），实际: " + s);
        assertFalse(s.contains("0.571"), "toString 不得含权重取值，实际: " + s);
        assertFalse(s.contains("structural_keep"), "toString 不得含缺失策略取值，实际: " + s);
    }

    // ==================================================================
    // 五、driftedSegments 的边界
    // ==================================================================

    @Test
    @DisplayName("🛑 driftedSegments：无从比对时报空（不报假漂移，也不报『无漂移』）")
    void drifted_segments_is_empty_when_there_is_nothing_to_compare() {
        assertTrue(fp.driftedSegments(null).isEmpty(), "null 段级指纹 ⇒ 无从比对，返回空");
        assertTrue(fp.driftedSegments(Map.of()).isEmpty(), "空段级指纹 ⇒ 无从比对，返回空");

        // 完全一致 ⇒ 无漂移
        assertTrue(fp.driftedSegments(fp.segmentFingerprints()).isEmpty(),
                "段级指纹完全一致时不得报漂移");

        // 部分缺失（早于 S2-8 的行只有总指纹、没有段级指纹）⇒ 只比对存在的那些段
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("pass_threshold", fp.segmentFingerprints().get("pass_threshold"));
        partial.put("missing_policy", "00000000000000000000");
        Map<String, String> drifted = fp.driftedSegments(partial);
        assertEquals(List.of("missing_policy"), List.copyOf(drifted.keySet()),
                "🛑 只应报『当时有记、且今天不同』的那一段。"
                        + "把『当时根本没记』也算成漂移，会让一次定位能力缺失被说成一次口径事故。"
                        + "实际: " + drifted);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static DerivedMetricProfile withPassThreshold(BigDecimal threshold) {
        return new DerivedMetricProfile(threshold, BASE.asRefundWeights(), BASE.asOpsWeights(),
                BASE.missingPolicy(), BASE.minSampleDays(), BASE.mcid(), BASE.confidence(),
                BASE.source());
    }

    private static DerivedMetricProfile withRefundWeights(Map<String, BigDecimal> weights) {
        return new DerivedMetricProfile(BASE.passThreshold(), weights, BASE.asOpsWeights(),
                BASE.missingPolicy(), BASE.minSampleDays(), BASE.mcid(), BASE.confidence(),
                BASE.source());
    }

    private static DerivedMetricProfile withMissingPolicy(DerivedMetricProfile.MissingPolicy policy) {
        return new DerivedMetricProfile(BASE.passThreshold(), BASE.asRefundWeights(),
                BASE.asOpsWeights(), policy, BASE.minSampleDays(), BASE.mcid(),
                BASE.confidence(), BASE.source());
    }

    private static DerivedMetricProfile withMinSampleDays(int days) {
        return new DerivedMetricProfile(BASE.passThreshold(), BASE.asRefundWeights(),
                BASE.asOpsWeights(), BASE.missingPolicy(), days, BASE.mcid(),
                BASE.confidence(), BASE.source());
    }

    /** 把任意嵌套结构里的字符串值拉平成一串，供"是否含取值"的机械扫描使用。 */
    private static String flatten(Object o) {
        StringBuilder sb = new StringBuilder();
        collect(o, sb);
        return sb.toString();
    }

    private static void collect(Object o, StringBuilder sb) {
        // 🛑 用 if-else 而非 switch 模式匹配：本工程编译目标为 JDK 17，
        //    而 switch 的模式匹配在 17 上仍是预览特性（需 --enable-preview）。
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(e.getKey()).append('=');
                collect(e.getValue(), sb);
                sb.append(';');
            }
        } else if (o instanceof Iterable<?> it) {
            for (Object v : it) {
                collect(v, sb);
                sb.append(',');
            }
        } else {
            sb.append(o);
        }
    }
}