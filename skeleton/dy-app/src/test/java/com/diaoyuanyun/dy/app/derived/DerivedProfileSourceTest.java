package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.DerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.domain.DerivedRawConfig;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-5「派生口径外置」的来源侧守卫 —— 口径<b>从哪来</b>，以及它断掉时会不会大声失败。
 *
 * <h2>它回答的问题，与 DerivedEngineContractTest 不同</h2>
 * {@code DerivedEngineContractTest} 回答"引擎里有没有写死阈值"；
 * 本类回答"<b>那个『外部来源』是否真的存在、真的被读到、断了会报错</b>"。
 * 两者不可互相替代：一个把阈值写死在引擎里的实现，只要它同时也读到了种子文件，
 * 就能骗过前者（它确实读了文件 —— 只是没用它的值）；而一个只读文件却把口径当默认值
 * 兜底的实现，能骗过后者。
 *
 * <h2>🛑 为什么"文件被改名/移走必须红"要单独断言</h2>
 * {@link ConfigSeedDerivedProfileSource} 是<b>过渡实现</b>：它不读 DB，而是解析
 * dy-config 的 {@code 02_slots_seed.sql} 里的六行声明。这条路径有个特征 ——
 * <b>它是一组字符串连线</b>（六个 {@code (编号, 键)} ↔ classpath 上的文件位置）。
 * 这种连线断掉时，若实现选择"读不到就用默认口径"，故障就是<b>静默</b>的：
 * 判定照常返回、结论照常好看，只是口径悄悄换成了另一套。
 *
 * <p>而本任务的口径比 S1-4 的计分口径更敏感：六段里有四段（门槛 / 权重 / 缺失策略 / MCID）
 * <b>直接决定客户能不能退费</b>。故本类要证明的是：<b>连线断掉 = 立刻抛错</b>，
 * 而不是"退化成一个看起来正常的默认值"。
 *
 * <h2>为什么还要断言"来源可解释"</h2>
 * {@code describeSource()} 会进日志，使"这次判定是按哪套口径算的"可被追溯。
 * 六段来自六个不同编号且分属三种值类型；若来源字符串只说"从配置来"，
 * 出问题时无法判断当时读的是哪一份、少了哪一段。
 */
class DerivedProfileSourceTest {

    /** dy-config 的配置声明文件在 classpath 上的位置（与实现类常量逐字一致）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** 六段声明的 (编号, 键) —— 逐字来自实现类的 SPECS。 */
    private static final List<String[]> SLOTS = List.of(
            new String[]{"4", "cfg:adherence.pass_threshold"},
            new String[]{"5", "cfg:adherence.weights"},
            new String[]{"6", "cfg:adherence.missing_policy"},
            new String[]{"7", "cfg:adherence.min_sample_days"},
            new String[]{"33", "cfg:verdict.mcid_threshold"},
            new String[]{"45", "cfg:verdict.confidence_formula"});

    private final DerivedProfileSource source = new ConfigSeedDerivedProfileSource();

    // ==================================================================
    // 一、来源真的存在、真的被读到
    // ==================================================================

    @Test
    @DisplayName("种子文件必须在 classpath 上，且六行声明都在里面（缺一行即口径不全）")
    void the_seed_file_is_reachable_and_declares_all_six_slots() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(is, "classpath 上找不到 " + SEED_RESOURCE
                    + " —— 派生口径来源断开。若该文件被移动，需同步更新 "
                    + ConfigSeedDerivedProfileSource.class.getSimpleName() + ".SEED_RESOURCE");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String[] slot : SLOTS) {
                String no = slot[0];
                String key = slot[1];
                assertTrue(sql.contains("(" + no + ",") || sql.contains("(" + no + " ,"),
                        SEED_RESOURCE + " 里找不到编号 " + no + " 的声明行");
                assertTrue(sql.contains(key),
                        SEED_RESOURCE + " 里找不到键 " + key
                                + "（编号 " + no + "）—— 键名被改了，而实现仍按旧键抓取");
            }
        }
    }

    @Test
    @DisplayName("六段声明都能被抓出取值，且都是非空字面量")
    void all_six_slots_are_parsed_into_non_blank_values() {
        DerivedRawConfig raw = source.raw();
        assertNotNull(raw, "原始声明记录不得为空");
        assertTrue(raw.isComplete(),
                "六段声明应齐全；缺失项=" + raw.missingKeys()
                        + " —— 任一段缺失都会让判定口径不完整");
        assertTrue(raw.missingKeys().isEmpty(), "齐全时缺失清单应为空，实为 " + raw.missingKeys());
    }

    @Test
    @DisplayName("断言抓取不串行：抓到的 JSON 段必须能被口径归一化器解析成真对象")
    void parsed_json_slots_are_real_json_not_neighbouring_rows() {
        DerivedRawConfig raw = source.raw();

        // ① 权重段：必须含 AS_refund 与 AS_ops 两个键（抓错相邻条目就不会同时有这两个）
        assertTrue(raw.weightsJson().contains("\"AS_refund\""),
                "权重段应含 AS_refund（否则可能抓到了相邻条目的值）。实际: " + raw.weightsJson());
        assertTrue(raw.weightsJson().contains("\"AS_ops\""), "权重段应含 AS_ops");

        // ② MCID 段：必须含 improved/stable/worsened 三段
        for (String seg : List.of("improved", "stable", "worsened")) {
            assertTrue(raw.mcidThresholdJson().contains("\"" + seg + "\""),
                    "MCID 段应含 " + seg + "。实际: " + raw.mcidThresholdJson());
        }

        // ③ 置信度段：必须含 composition（合成式本身，缺失则引擎无从知晓指数）
        assertTrue(raw.confidenceJson().contains("composition"),
                "置信度段应含 composition。实际: " + raw.confidenceJson());

        // ④ 🛑 真正解析一次：证明六段凑在一起能归一化成可用剖面
        //    （单段"看起来像 JSON"不够 —— 跨源一致性断言在此刻才会被执行）
        DerivedMetricProfile profile = DerivedMetricProfile.fromRawConfig(raw);
        assertNotNull(profile, "六段声明必须能被归一化成派生口径剖面");
    }

    // ==================================================================
    // 二、解析出的值就是口径本身（与 02_slots_seed.sql 的声明逐项对照）
    // ==================================================================

    @Test
    @DisplayName("归一化后的口径与 02_slots_seed.sql 的声明值逐项一致")
    void the_normalized_profile_matches_the_declared_values() {
        DerivedMetricProfile p = DerivedMetricProfile.fromRawConfig(source.raw());

        // 声明值（02_slots_seed.sql #4）：'0.80'
        assertEquals(0, p.passThreshold().compareTo(new java.math.BigDecimal("0.80")),
                "依从达标门槛应为 0.80（config #4）");

        // 声明值（#5）：AS_refund = A1 0.571 / A3 0.286 / A4 0.143
        assertEquals(0, p.asRefundWeights().get("A1").compareTo(new java.math.BigDecimal("0.571")),
                "AS_refund.A1 应为 0.571（config #5）");
        assertEquals(0, p.asRefundWeights().get("A3").compareTo(new java.math.BigDecimal("0.286")),
                "AS_refund.A3 应为 0.286（config #5）");
        assertEquals(0, p.asRefundWeights().get("A4").compareTo(new java.math.BigDecimal("0.143")),
                "AS_refund.A4 应为 0.143（config #5）");
        assertFalse(p.asRefundWeights().containsKey("A2"),
                "🛑 AS_refund 不得含 A2 —— 合规锁（指标规格 §4.2/§4.3）");

        // 声明值（#6）：'structural_keep'
        assertEquals(DerivedMetricProfile.MissingPolicy.STRUCTURAL_KEEP, p.missingPolicy(),
                "缺失值策略应为 structural_keep（config #6）");

        // 声明值（#7）：'7'
        assertEquals(7, p.minSampleDays(), "AS 样本护栏应为 7 天（config #7）");

        // 声明值（#33）：module_total_max 16 / improved.delta_min 3 / stable 0–2 / worsened.rise_min 1
        assertEquals(16, p.mcid().moduleTotalMax(), "MCID 模块满分应为 16（config #33）");
        assertEquals(3, p.mcid().improvedDeltaMin(), "改善门槛应为 Δ≥3（config #33）");
        assertEquals(0, p.mcid().stableDeltaMin(),
                "稳定档下界应为 0 —— 规格 §1.4「下降 1~2 分【或不变】→ 稳定（E3）」；"
                        + "若此处为 1，则 Δ=0『无变化』会落不进任何档（见本源守卫的接缝断言）");
        assertEquals(2, p.mcid().stableDeltaMax(), "稳定档上界应为 2（config #33）");
        assertEquals(1, p.mcid().worsenedRiseMin(), "加重档上升门槛应为 1（config #33）");
        assertEquals(-1, p.mcid().worsenedDeltaThreshold(), "加重阈值应为 −1（= −worsenedRiseMin）");

        // 声明值（#45）：指数 0.25/0.25/0.50、s 三值、d.answered 28、n 门槛 7/14、m 0.15/0.85/谷底 3
        assertEquals(0, p.confidence().exponents().get("s").compareTo(java.math.BigDecimal.ONE),
                "s 无 ^ 时指数应为 1（config #45 composition）");
        assertEquals(0, p.confidence().exponents().get("d").compareTo(new java.math.BigDecimal("0.25")),
                "d 指数应为 0.25（config #45）");
        assertEquals(0, p.confidence().exponents().get("m").compareTo(new java.math.BigDecimal("0.50")),
                "m 指数应为 0.50（config #45）");
        assertEquals(28, p.confidence().answered(), "d 分母应为 28（= 7 维 × 4 题，config #45）");
        assertEquals(14, p.confidence().expectedDaysDenominator(), "n 满档天数应为 14（config #45 formula）");
        assertEquals(3, p.confidence().mDeltaGap(), "m 谷底应为 Δ=3（config #45，须与 #33 同源）");
        assertEquals(0, p.confidence().mBase().compareTo(new java.math.BigDecimal("0.15")),
                "m 地板应为 0.15（config #45）");
        assertEquals(0, p.confidence().highMin().compareTo(new java.math.BigDecimal("0.75")),
                "展示层『高』档下界应为 0.75（config #45 bands）");

        // 口径来源自描述应点名七个编号（含 #35 —— 它由 ScaleStructure 间接参与结构校验）
        assertEquals("config#4+#5+#6+#7+#33+#35+#45", p.source(),
                "口径来源应逐项点名参与归一化的配置编号，便于追溯");
    }

    @Test
    @DisplayName("🛑 跨源一致性由归一化器承担：单段自洽不等于合起来自洽")
    void cross_source_consistency_is_enforced_at_normalization() {
        // 六段来自六个编号，且三对必须同源（#7↔#45 n；#33↔#45 m 谷底；#45 d ↔ 结构题数）。
        // 这三条断言在 fromRawConfig 里，故"能构造出剖面"本身就意味着三对已核过。
        DerivedMetricProfile p = DerivedMetricProfile.fromRawConfig(source.raw());
        assertEquals(p.minSampleDays(), p.confidence().nMinSampleDays(),
                "config #7 的样本护栏必须与 config #45 n.min_sample_days 同值");
        assertEquals(p.mcid().improvedDeltaMin(), p.confidence().mDeltaGap(),
                "config #33 的 Δ 必须与 config #45 m.delta_gap 同值（否则『以 Δ=3 为唯一谷底』不成立）");
        assertEquals(16, p.mcid().moduleTotalMax(),
                "MCID 模块满分必须等于『每模块题数 × 单题上限』（量程来自 config #35）");
    }

    // ==================================================================
    // 三、🛑 连线断掉必须大声失败（不得退化为默认口径）
    // ==================================================================

    @Test
    @DisplayName("实现类里不存在任何『取不到就用默认值』的兜底分支（机械扫描，不靠通读）")
    void the_implementation_has_no_default_value_bailout_branch() throws IOException {
        String code = stripCommentsAndStrings(
                Files.readString(resolveSource(), StandardCharsets.UTF_8));

        for (String bailout : new String[]{
                "orElse(", "getOrDefault", "DEFAULT_", "defaultProfile", "return null;"}) {
            assertFalse(code.contains(bailout),
                    "实现类里出现兜底写法 '" + bailout + "' —— 口径读不到时必须抛错，"
                            + "不得退化成默认口径（那会让『门槛/权重/MCID 被换成另一套』静默发生，"
                            + "而它们直接决定客户能不能退费）");
        }
        // 正向自证：它确实在失败路径上抛 BizException（而不是返回空串/默认值）
        assertTrue(code.contains("throw new BizException"),
                "实现类应在其失败路径上抛 BizException（口径缺失 = 拒绝计算）");

        // 反证扫描不是恒绿：注释里确实讨论过"默认口径"这件事，剥完就不该再有
        assertTrue(Files.readString(resolveSource(), StandardCharsets.UTF_8).contains("默认"),
                "前置失效：实现类文档里应讨论过『按默认口径计算』这件事；"
                        + "文件已改动，请复核本断言");
        assertFalse(code.contains("默认"),
                "注释剥离失效：剥完仍含『默认』二字 —— 块注释匹配漏了 DOTALL");
    }

    @Test
    @DisplayName("六段任一段为空白 ⇒ 归一化必须抛错（而不是被当作『无口径要求』）")
    void any_blank_slot_is_rejected_not_defaulted() {
        DerivedRawConfig full = source.raw();

        // 逐项构造"仅缺一段"的记录（六种情形）：每一段都必须让归一化失败
        List<DerivedRawConfig> broken = List.of(
                new DerivedRawConfig(null, full.weightsJson(), full.missingPolicy(),
                        full.minSampleDays(), full.mcidThresholdJson(), full.confidenceJson()),
                new DerivedRawConfig(full.passThreshold(), null, full.missingPolicy(),
                        full.minSampleDays(), full.mcidThresholdJson(), full.confidenceJson()),
                new DerivedRawConfig(full.passThreshold(), full.weightsJson(), null,
                        full.minSampleDays(), full.mcidThresholdJson(), full.confidenceJson()),
                new DerivedRawConfig(full.passThreshold(), full.weightsJson(), full.missingPolicy(),
                        null, full.mcidThresholdJson(), full.confidenceJson()),
                new DerivedRawConfig(full.passThreshold(), full.weightsJson(), full.missingPolicy(),
                        full.minSampleDays(), null, full.confidenceJson()),
                new DerivedRawConfig(full.passThreshold(), full.weightsJson(), full.missingPolicy(),
                        full.minSampleDays(), full.mcidThresholdJson(), null));

        for (int i = 0; i < broken.size(); i++) {
            DerivedRawConfig raw = broken.get(i);
            assertFalse(raw.isComplete(), "第 " + (i + 1) + " 种缺段情形应被判定为不完整");
            assertEquals(1, raw.missingKeys().size(),
                    "缺失清单应恰好点名一项，实为 " + raw.missingKeys());
            assertEquals(5001,
                    assertThrows(BizException.class,
                            () -> DerivedMetricProfile.fromRawConfig(raw)).getCode(),
                    "缺段应属业务规则冲突（5001），不得静默按默认口径继续（缺的是 " + raw.missingKeys() + "）");
        }
    }

    @Test
    @DisplayName("整份声明为空 ⇒ 必须抛错（不是 NPE，也不是按默认算）")
    void a_completely_missing_profile_source_is_rejected() {
        assertEquals(5001,
                assertThrows(BizException.class,
                        () -> DerivedMetricProfile.fromRawConfig(null)).getCode(),
                "null 口径源应属业务规则冲突（5001），不是 NPE（9001）");
    }

    // ==================================================================
    // 四、来源可解释
    // ==================================================================

    @Test
    @DisplayName("来源自描述必须点名 文件 + 六个编号 + 键名，并声明这是过渡来源")
    void the_source_description_names_the_file_all_slots_and_that_it_is_transitional() {
        String d = source.describeSource();
        assertTrue(d.contains(SEED_RESOURCE), "来源描述应点名文件；实际: " + d);
        for (String[] slot : SLOTS) {
            assertTrue(d.contains("#" + slot[0]),
                    "来源描述应点名配置编号 " + slot[0] + "；实际: " + d);
            assertTrue(d.contains(slot[1]),
                    "来源描述应点名配置键 " + slot[1] + "；实际: " + d);
        }
        assertTrue(d.contains("替换") || d.contains("过渡"),
                "来源描述应声明它是过渡实现（配置真相源落库后须替换）—— "
                        + "否则下游会误以为口径已从 DB 读取；实际: " + d);
    }

    @Test
    @DisplayName("原始声明记录的自描述不打印取值（日志不得成为口径的第二份副本）")
    void the_raw_config_to_string_does_not_leak_values() {
        DerivedRawConfig raw = source.raw();
        String s = raw.toString();
        // 若把取值打进日志，它会成为一份随日志散落的阈值副本 —— 改配置不改日志，
        // 复盘时无法判断"当时是哪个值"。排查所需的是"哪一段空/多长"，不是内容。
        //
        // 🛑 判据不能用"是否含某个取值字符串"：取值 '7' 是 '7 chars' 的子串
        //（长度报告里天然会出现同样的字符），那样断言会恒红且指向错误的原因。
        // 另：也不能笼统断言"不含 {" —— toString 自身的类名外壳就是 "DerivedRawConfig{"，
        // 那会把正常的外壳判成泄漏。故只在【外壳之内】找 JSON 结构字符。
        int open = s.indexOf('{');
        String envelope = open < 0 ? "" : s.substring(0, open);
        String payload = open < 0 ? s : s.substring(open + 1);
        assertTrue(envelope.contains("DerivedRawConfig"),
                "前置失效：toString 应以类名外壳开头（否则下面的切片无意义）；实际: " + s);

        for (String value : List.of(raw.weightsJson(), raw.mcidThresholdJson(),
                raw.confidenceJson())) {
            assertTrue(value.contains("{") || value.contains("\""),
                    "前置失效：取值应为 JSON 形态，否则下面的断言退化为恒绿");
        }
        assertFalse(payload.contains("{"),
                "自描述不得含 JSON 取值（只应报段名与长度）；实际: " + s);
        assertFalse(payload.contains("\""),
                "自描述不得含引号包裹的取值；实际: " + s);
        assertFalse(payload.contains(raw.missingPolicy()),
                "自描述不得含枚举取值 '" + raw.missingPolicy() + "'；实际: " + s);
        assertTrue(s.contains("chars"),
                "自描述应报各段长度（排查『哪一段没取到』所需的最小信息）；实际: " + s);
        assertTrue(s.contains("complete=true"),
                "自描述应报完整性，便于一眼看出是否有段没取到；实际: " + s);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 定位实现类源码文件（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveSource() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/service/"
                + "ConfigSeedDerivedProfileSource.java");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 ConfigSeedDerivedProfileSource 源码（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    private static String stripCommentsAndStrings(String src) {
        // (?s) = DOTALL：块注释跨行匹配（Javadoc 里逐字写着"默认口径"四字，
        // 不剥注释会把文档里的讨论当成兜底代码）。
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }
}