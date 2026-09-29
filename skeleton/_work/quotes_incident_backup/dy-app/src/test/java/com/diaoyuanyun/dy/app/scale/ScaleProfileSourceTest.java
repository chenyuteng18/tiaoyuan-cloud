package com.diaoyuanyun.dy.app.scale;

import com.diaoyuanyun.dy.app.scale.domain.ScaleProfileSource;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringProfile;
import com.diaoyuanyun.dy.app.scale.service.ConfigSeedScaleProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-4「评分口径外置」的来源侧守卫 —— 口径<b>从哪来</b>，以及它断掉时会不会大声失败。
 *
 * <h2>它回答的问题，与 ScaleEngineContractTest 不同</h2>
 * {@code ScaleEngineContractTest} 回答"引擎里有没有写死分值"；
 * 本类回答"<b>那个『外部来源』是否真的存在、真的被读到、断了会报错</b>"。
 * 两者不可互相替代：一个把分值写死在引擎里的实现，只要它同时也读到了种子文件，
 * 就能骗过前者；而一个只读种子文件却把口径当默认值兜底的实现，能骗过后者。
 *
 * <h2>🛑 为什么"文件被改名/移走必须红"要单独断言</h2>
 * {@link ConfigSeedScaleProfileSource} 是<b>过渡实现</b>：它不读 DB，而是解析
 * dy-config 的 {@code 02_slots_seed.sql} 里的 {@code #35} 行。这条路径有个特征 ——
 * <b>它是一根字符串连线</b>（{@code SEED_RESOURCE} 常量 ↔ classpath 上的文件位置）。
 * 这种连线断掉时，若实现选择"读不到就用默认口径"，故障就是<b>静默</b>的：
 * 计分照常返回、分数照常好看，只是口径悄悄换成了旧的（0–3 四级那套）。
 * 故本测试要证明的是：<b>连线断掉 = 立刻抛错</b>，而不是"退化成一个看起来正常的默认值"。
 *
 * <h2>为什么还要断言"来源可解释"</h2>
 * {@code describeSource()} 会被 {@code ImportResult.profileSource} 带到调用方，
 * 使"这次导入的分数是按哪套口径算的"可被追溯。口径被改判过一次（v1.7），
 * 若来源字符串不区分实现（种子文件 / DB），出问题时无法判断当时读的是哪一份。
 */
class ScaleProfileSourceTest {

    /** dy-config 的配置声明文件在 classpath 上的位置（与实现类常量逐字一致）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    private static final String CONFIG_KEY = "cfg:scale.range_rule";

    private final ScaleProfileSource source = new ConfigSeedScaleProfileSource();

    // ==================================================================
    // 一、来源真的存在、真的被读到
    // ==================================================================

    @Test
    @DisplayName("种子文件必须在 classpath 上（dy-app 依赖 dy-config，其 resources 随之可见）")
    void the_seed_file_is_reachable_on_the_classpath() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(is, "classpath 上找不到 " + SEED_RESOURCE
                    + " —— 口径来源断开。若该文件被移动，需同步更新 "
                    + ConfigSeedScaleProfileSource.class.getSimpleName() + ".SEED_RESOURCE");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains(CONFIG_KEY),
                    SEED_RESOURCE + " 里找不到 " + CONFIG_KEY
                            + " —— 声明的键名被改了，而实现仍按旧键抓取");
            assertTrue(sql.contains("(35,") || sql.contains("(35 ,"),
                    SEED_RESOURCE + " 里找不到编号 35 的声明行");
        }
    }

    @Test
    @DisplayName("能解析出 #35 的声明值，且它就是合法的 JSON")
    void the_thirty_fifth_slot_is_parsed_into_real_json() {
        String json = source.rangeRuleJson();
        assertNotNull(json, "解析结果不得为空");
        assertTrue(json.contains("\"range\""),
                "解析出的取值应含 range 段（否则可能抓到了相邻条目的值）。实际: " + json);
        assertTrue(json.startsWith("{") && json.endsWith("}"),
                "取值应是一个完整 JSON 对象（从 '{' 到 '}'）。实际: " + json);
        // 真实解析一次：证明它不是"看起来像 JSON 的字符串"
        ScaleScoringProfile p = ScaleScoringProfile.fromConfigJson(json);
        assertNotNull(p, "该 JSON 必须能被口径归一化器解析");
    }

    // ==================================================================
    // 二、解析出的值就是口径本身（与 config #35 的声明逐项对照）
    // ==================================================================

    @Test
    @DisplayName("解析出的口径与 02_slots_seed.sql 里 #35 的声明值逐项一致")
    void the_parsed_profile_matches_the_declared_values() {
        ScaleScoringProfile p = ScaleScoringProfile.fromConfigJson(source.rangeRuleJson());

        // 声明值（02_slots_seed.sql 第 251 行）：
        // {"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,...}
        assertEquals(0, p.itemMin(), "range.min 应为 0");
        assertEquals(4, p.itemMax(), "range.max 应为 4（0–4 五级）");
        assertEquals(5, p.levels(), "range.levels 应为 5");
        assertEquals(16, p.dimensionMax(), "dimension_max 应为 16（4 题 × 0–4）");
        assertEquals(112, p.totalMax(), "total_max 应为 112（7 维 × 16）");
        assertEquals("config#35", p.source(), "口径来源应标注为 config #35，便于追溯");
    }

    @Test
    @DisplayName("严重度分档在 #35 里缺省 ⇒ 未配置（不得在来源侧偷偷补一组边界）")
    void severity_bands_stay_unconfigured_because_the_seed_does_not_declare_them() {
        ScaleScoringProfile p = ScaleScoringProfile.fromConfigJson(source.rangeRuleJson());
        assertFalse(p.hasSeverityBands(),
                "#35 里没有 severity_bands ⇒ 应为未配置。"
                        + "若此处变成 true，说明有人往来源侧补了边界 —— "
                        + "那是业务/临床未给校准值前不该做的事（硬纪律 #6：TBD 不得填数）");
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
                "orElse(", "getOrDefault", "DEFAULT_", "defaultProfile", "return null;“}) {
            assertFalse(code.contains(bailout),
                    ”实现类里出现兜底写法 '" + bailout + "' —— 口径读不到时必须抛错，"
                            + "不得退化成默认口径（那会让口径丢失静默成『口径是旧的』）");
        }
        // 正向自证：它确实在失败路径上抛 BizException（而不是返回空串/默认值）
        assertTrue(code.contains("throw new BizException"),
                "实现类应在其失败路径上抛 BizException（口径缺失 = 拒绝计分）");
        // 反证扫描不是恒绿：注释里确实讨论过"兜底"这件事，剥完就不该再有
        assertTrue(Files.readString(resolveSource(), StandardCharsets.UTF_8).contains("默认"),
                "前置失效：实现类文档里应讨论过『默认口径』这件事；文件已改动，请复核本断言");
        assertFalse(code.contains("默认"),
                "注释剥离失效：剥完仍含『默认』二字 —— 块注释匹配漏了 DOTALL");
    }

    @Test
    @DisplayName("种子文件里 #35 的取值被改成空 ⇒ 必须抛错（而不是被当作『无口径要求』）")
    void a_blank_declared_value_is_rejected() {
        // 直接对着"空值"这一出口断言：实现类对 blank 有显式分支，必然抛。
        String blankJson = "";
        assertEquals(5001,
                assertThrows(BizException.class,
                        () -> ScaleScoringProfile.fromConfigJson(blankJson)).getCode(),
                "空口径值应属业务规则冲突（5001），不得静默按默认口径继续");
    }

    @Test
    @DisplayName("空 / null 口径值一律拒（口径缺失不得被当作『用默认口径』）")
    void missing_profile_value_is_rejected_not_defaulted() {
        assertEquals(5001,
                assertThrows(BizException.class, () -> ScaleScoringProfile.fromConfigJson(null)).getCode(),
                "null 口径应属业务规则冲突（5001），不是 NPE（9001）");
        assertEquals(5001,
                assertThrows(BizException.class, () -> ScaleScoringProfile.fromConfigJson("   ")).getCode(),
                "空白口径应被拒");
    }

    // ==================================================================
    // 四、来源可解释
    // ==================================================================

    @Test
    @DisplayName("来源自描述必须点名 文件 + #35 + 键名，并声明这是过渡来源")
    void the_source_description_names_the_file_the_slot_and_that_it_is_transitional() {
        String d = source.describeSource();
        assertTrue(d.contains(SEED_RESOURCE), "来源描述应点名文件；实际: " + d);
        assertTrue(d.contains("#35"), "来源描述应点名配置编号 35；实际: " + d);
        assertTrue(d.contains(CONFIG_KEY), "来源描述应点名配置键；实际: " + d);
        assertTrue(d.contains("替换") || d.contains("过渡"),
                "来源描述应声明它是过渡实现（配置真相源落库后须替换）—— "
                        + "否则下游会误以为口径已从 DB 读取；实际: " + d);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 定位实现类源码文件（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveSource() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/service/"
                + "ConfigSeedScaleProfileSource.java");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 ConfigSeedScaleProfileSource 源码（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    private static String stripCommentsAndStrings(String src) {
        // (?s) = DOTALL：块注释跨行匹配（Javadoc 里逐字写着"兜底"二字，
        // 不剥注释会把文档里的讨论当成兜底代码）。
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }
}