package com.diaoyuanyun.dy.app.scale;

import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.app.scale.domain.ScaleProfileSource;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringEngine;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringProfile;
import com.diaoyuanyun.dy.app.scale.service.ConfigSeedScaleProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-4「可计分」的契约守卫：<b>评分口径外置</b> + <b>响应不含派生结论</b> + <b>缺题不补 0</b>。
 *
 * <h2>本类盯的三件事，以及它们各自为什么必须被机械断言</h2>
 * <ol>
 *   <li><b>代码里无硬编码分值</b>（开发清单 §一④ 原话：评分口径外置为配置项，
 *       代码内 {@code grep} 无硬编码分值）。这是 PRD §10「所有业务数字不得硬编码」的子项，
 *       其现实理由很具体：量程口径被改判过一次（v1.7：0–3 四级 → <b>0–4 五级</b>），
 *       若分值散落在代码里，那次改判就要全仓库找常量。故这里<b>扫源码</b>，
 *       而不是"看一眼觉得没问题"。</li>
 *   <li><b>计分响应不含派生结论字段</b>（{@code effect_verdict} / 改善率 / 达标评价 /
 *       {@code as_value} / 退款资格）。这些是判定引擎（S1-5）的产物，且按 PRD P0-13
 *       与 §2.2「退款是内部事务」<b>只对内</b>。计分接口把它们带出去，
 *       等于把内部判定材料交给客户端 —— 而这个错误<b>不会报错</b>，
 *       只会静静出现在响应 JSON 里。</li>
 *   <li><b>缺题不得补 0</b>（硬纪律 #4）。少答一题若被当 0 分，维度分被压低 →
 *       "更严重"看起来像"有改善"，且不报错。故引擎必须抛错，本类断言它抛。</li>
 * </ol>
 *
 * <h2>为什么静态扫描不用"允许清单"，而用"从 config 推导出的集合"</h2>
 * 引擎里确实<b>存在</b>正当的整数：{@code 0}（下标起点、量程下界的语义校验）、
 * {@code 1}（闭合区间端点计数偏移）、{@code 2}（{@code Band} 二元组的分量数）、
 * {@code 4}/{@code 7}/{@code 8}（每维题数 / 维数 / 年龄组数）。
 *
 * <p>起初这里放了一张手写的"允许清单"（{@code 0,1,2,4,7,8} + 理由）。但那样等于
 * <b>把口径抄进了测试</b>：清单是死的，口径是活的。若有人把 config {@code #35} 改成
 * 0–5 六级、8 维，源码里出现 {@code 5}/{@code 8} 就会红，而此时正确的反应是
 * <b>去改那张清单</b> —— 改清单是个纯仪式：加一行、编个理由、绿灯。
 * 于是"口径外置"这件事又回到了人肉自觉，测试只增加了噪音。
 *
 * <p>故改为：<b>把源码里出现的每一个数字字面量，与"从 config {@code #35} 归一化后
 * 推导出来的数字集合"逐项比对</b>（{@link #expectedLiteralsDerivedFromConfigAndStructure()}）。
 * 判据从"这个数字在不在我列的清单里"变成"这个数字是不是口径自身的基数"。
 * 口径改了，期望集<b>自动</b>跟着改 —— 此时若源码没跟着改（例如把 16 写死），
 * 它会红，而那是<b>真话</b>；若源码与口径一致，它自动绿，不需要任何人改测试。
 *
 * <p>另外两个数字（{@code 2}/{@code 1}）也不手写：{@code 2} 由
 * {@code Band} 记录的<b>分量数</b>反射取得（分档数组的长度必须等于二元组的分量数），
 * {@code 1} 由 {@code levels − (max − min)} 推导（闭合区间的端点计数偏移）。
 * 二者都是<b>结构事实</b>，不是抄数。
 */
class ScaleEngineContractTest {

    private static final Path SCALE_MAIN_DIR = resolveScaleMainDir();

    /** 严重度三值枚举的来源（PRD 附录 C.1.3）。 */
    private static final List<String> EXPECTED_SEVERITY_LABELS = List.of("轻度", "中度", "较重");

    /**
     * 期望出现在引擎/口径源码里的整数字面量集合 —— <b>推导得到，不是列举得到</b>。
     *
     * <p>两段来源，都不是手抄的数：
     * <ul>
     *   <li><b>(A) 口径基数</b>：从 config {@code #35} 经 {@link ScaleScoringProfile#fromConfigJson}
     *       归一化后取值 —— 量程下界 / 每维题数 / 维度数 / 年龄组数。
     *       口径被改判（例如 0–4 五级 → 0–5 六级）时，这个集合自动变化，
     *       而源码若没跟着变就会红 —— 那正是我们想要的报红。</li>
     *   <li><b>(B) 结构基数</b>：由代码自身的结构事实推出 ——
     *       {@code Band} 记录的分量数（= 分档数组应有的长度）、
     *       闭合区间的端点计数偏移（{@code levels − span}）。</li>
     * </ul>
     * 若把这套推导换回一张手写清单，"口径外置"就会退回人肉自觉：
     * 口径一变，人来改清单，改清单不会有人复核 —— 测试只剩噪音。
     */
    private static Set<String> expectedLiteralsDerivedFromConfigAndStructure() {
        ScaleScoringProfile p = ScaleScoringProfile.fromConfigJson(
                new ConfigSeedScaleProfileSource().rangeRuleJson());

        Set<String> expected = new TreeSet<>();
        // (A) 口径基数
        expected.add(String.valueOf(p.itemMin()));                  // 量程下界（余数 / 累加初值 / 下标起点）
        expected.add(String.valueOf(p.itemsPerDimension()));        // 每维题数 → 4
        expected.add(String.valueOf(p.dimensionCount()));           // 维度数 → 7
        expected.add(String.valueOf(ScaleDomain.AGE_GROUP_COUNT));  // 年龄组数 → 8
        // (B) 结构基数
        expected.add(String.valueOf(
                ScaleScoringProfile.Band.class.getRecordComponents().length));  // 档位二元组分量数 → 2
        expected.add(String.valueOf(
                p.levels() - (p.itemMax() - p.itemMin())));         // 闭合区间端点计数偏移 → 1
        return expected;
    }

    // ==================================================================
    // 一、口径外置：源码里不得出现分值常量
    // ==================================================================

    @Test
    @DisplayName("计分引擎/口径源码内的整数字面量必须与【config #35 推导出的集合】一致（多一个即红）")
    void no_hardcoded_score_thresholds_exist_in_the_engine_source() throws IOException {
        Set<String> expected = expectedLiteralsDerivedFromConfigAndStructure();
        Set<String> seen = new TreeSet<>();
        List<String> withContext = new ArrayList<>();

        for (String rel : List.of(
                "domain/ScaleScoringEngine.java",
                "domain/ScaleScoringProfile.java",
                "domain/ScaleDomain.java")) {
            String raw = read(SCALE_MAIN_DIR.resolve(rel));

            // 前置自证①：注释剥离必须用 DOTALL。
            // 本仓库的 Javadoc 里【大量出现】口径数字（"总分 0–112"、"量程 0–4 五级"、
            // "维度满分 0–16"、"224 题"、"35"）。若非 DOTALL，这些<b>描述性文字</b>
            // 会被当成代码里的硬编码分值 —— 扫描器会把"文档里说明口径"判成"代码里写死口径"。
            // 更糟的是，修它的自然反应是删掉文档里的数字，等于让文档去迁就扫描器。
            assertFalse(stripCommentsAndStrings(raw).contains("0–112"),
                    "注释剥离失效：" + rel + " 剥完还含『0–112』（Javadoc 里的口径描述）—— "
                            + "块注释匹配漏了 DOTALL，扫描器会把文档判成硬编码");

            String code = stripCommentsAndStrings(raw);
            Matcher m = Pattern.compile("(?<![\\w.])\\d+(?![\\w.])").matcher(code);
            while (m.find()) {
                String lit = m.group();
                seen.add(lit);
                withContext.add(rel + " 字面量 " + lit
                        + "（上下文: " + context(code, m.start()) + "）");
            }
        }

        // ① 多一个就红：源码里出现任何"口径/结构之外"的数字 = 把口径写进了代码
        Set<String> extra = new TreeSet<>(seen);
        extra.removeAll(expected);
        assertTrue(extra.isEmpty(),
                "计分相关源码里出现了" + extra + " —— 它们不在【由 config #35 推导出的数字集合】"
                        + " " + expected + " 内。量程口径必须外置（PRD §10 / 开发清单 §一④），"
                        + "不得写进代码。\n  若这确实是新的结构常量，正确做法不是改本测试的期望集，"
                        + "而是把它作为【从口径推导的量】表达出来（见 "
                        + "expectedLiteralsDerivedFromConfigAndStructure 的两段来源）。\n  - "
                        + String.join("\n  - ", withContext));

        // ② 少一个也红：口径基数必须在源码里出现，否则"零违规"可能只是文件没被读到
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(seen);
        assertTrue(missing.isEmpty(),
                "源码里未见到口径基数 " + missing + " —— 说明文件路径解析失效，"
                        + "本条断言退化为恒绿（文件压根没被扫到）");

        // ③ 明确反证：口径里的关键分值本身绝不能在代码里作为字面量出现。
        //    ⚠️ "期望集"里本来就<b>不含</b> 16 / 112 / 224（它们是乘出来的口径量，
        //    不是基数）—— 但这里再单独断言一次，是为了让"112 不许出现"这件事
        //    在失败信息里有一句人话，而不是靠集合差集的泛化提示。
        for (String forbidden : List.of("16", "112", "224")) {
            assertFalse(seen.contains(forbidden),
                    "源码里出现了分值字面量 " + forbidden
                            + " —— 维度满分 / 总分上限 / 题库容量必须来自 config #35，不得硬编码");
        }
    }

    // ==================================================================
    // 二、响应不含派生结论
    // ==================================================================

    @Test
    @DisplayName("计分响应不含任何派生结论字段（effect_verdict / 改善率 / 达标 / as_value / 退款）")
    void scoring_response_contains_no_derived_conclusion_fields() throws IOException {
        Set<String> forbidden = Set.of(
                "effect_verdict", "improvement_rate", "as_value", "verdict",
                "refund", "refund_eligibility", "migratable", "达标", "改善率", "好转判断");

        // ① 源码层：计分结果记录类型（ScoreResult）的字段名不得命中禁表
        String engineSrc = read(SCALE_MAIN_DIR.resolve("domain/ScaleScoringEngine.java"));
        int idx = engineSrc.indexOf("public record ScoreResult(");
        assertTrue(idx > 0, "找不到 ScoreResult 定义 —— 本断言的对象不见了，必须先去更新它");
        String body = engineSrc.substring(idx, engineSrc.indexOf(") {", idx));
        for (String f : forbidden) {
            assertFalse(body.contains(f),
                    "ScoreResult 含派生结论字段 '" + f + "' —— 计分响应会把它带出去。"
                            + "这些字段属判定引擎且只对内（PRD P0-13 / §2.2）");
        }

        // ② 控制器层：计分端点的【响应构造体】里不得命中禁表。
        //    🛑 判据只能是响应体，不能是整段方法 —— 方法注释里会逐条声明
        //    「本响应不含效果判定 / 改善率 / 达标评价」，那是【文档在声明不含】。
        //    若对整段方法（含注释）扫禁表，那句声明本身会被判成违规：
        //    扫描器把"声明不含"读成"含有"。故这里按花括号配对切出方法体，
        //    再只取 m.put(...) 到 return Result.ok(m 之间的响应构造段。
        String controllerSrc = read(resolveControllerFile("ScaleItemBankController.java"));
        String scoreMethod = methodBody(controllerSrc, "Result<Object> score(");
        // 🛑 这里只能剥【注释】，不能剥【字符串字面量】：
        //    响应体的键本身就是字符串字面量（m.put("total_score", …)）。
        //    若连字符串一起剥，键名会被替换成 "" —— 于是"响应里没有 effect_verdict"
        //    这条断言会永远通过（它把一个关于键的断言，用"键已被抹掉"来满足）。
        String scoreCode = stripCommentsOnly(scoreMethod);
        int from = scoreCode.indexOf("m.put(");
        int to = scoreCode.indexOf("return Result.ok(m");
        assertTrue(from >= 0 && to > from,
                "找不到计分端点的响应构造体（m.put… / return Result.ok(m）—— 断言对象变了，需先去更新它");
        String responseBuild = scoreCode.substring(from, to);

        for (String f : forbidden) {
            assertFalse(responseBuild.contains(f),
                    "计分端点响应里出现 '" + f + "' —— 把内部判定材料交给客户端了");
        }
        // 正向自证：必须显式声明"不含派生结论"，使调用方与回归用例都能读到这个事实
        assertTrue(responseBuild.contains("derived_conclusions_included"),
                "计分响应应显式带 derived_conclusions_included=false 作为可断言的事实");
        assertTrue(responseBuild.contains("derived_conclusions_included\", false")
                        || responseBuild.contains("derived_conclusions_included\", Boolean.FALSE"),
                "derived_conclusions_included 必须置为 false");
        // 反证：方法注释里确实写着这些词（否则上面的"响应体里没有"可能只是词压根不存在）。
        // 这一段刻意扫【含注释的原文】：它证明"改进率/派生结论"这些词在源码里存在，
        // 只是位于注释（= 声明"不含"）而非响应构造体（= 声明"含"）。
        assertTrue(scoreMethod.contains("改善率") || scoreMethod.contains("派生结论"),
                "前置失效：计分端点的注释应逐条声明『不含派生结论』；"
                        + "文件已改动，请复核本断言与上面的切片口径");
    }

    // ==================================================================
    // 三、量程边界：越界不得截断
    // ==================================================================

    @Test
    @DisplayName("越界得分必须被拒（不得截断成边界值，截断会静默改分）")
    void out_of_range_scores_are_rejected_and_never_truncated() {
        ScaleScoringEngine engine = engineWithRealProfile();

        // 高于上限
        BizException high = assertThrows(BizException.class,
                () -> engine.score(submission(fill(engine.profile().itemMax() + 1))),
                "得分高于量程上限被接受了 —— 必须拒，不得截断");
        assertEquals(1001, high.getCode(), "越界属入参校验失败（1001），实际=" + high.getCode());

        // 低于下限
        BizException low = assertThrows(BizException.class,
                () -> engine.score(submission(fill(engine.profile().itemMin() - 1))),
                "得分低于量程下限被接受了 —— 必须拒，不得截断");
        assertEquals(1001, low.getCode(), "越界属入参校验失败（1001），实际=" + low.getCode());

        // 边界值本身必须可用（上下限闭区间）
        ScaleScoringEngine.ScoreResult atMax = engine.score(submission(fill(engine.profile().itemMax())));
        assertEquals(engine.profile().totalMax(), atMax.totalScore(),
                "全部取上限时应恰得总分上限（上下限为闭区间）");
        ScaleScoringEngine.ScoreResult atMin = engine.score(submission(fill(engine.profile().itemMin())));
        assertEquals(0, atMin.totalScore(), "全部取下限时应恰得 0 分");
    }

    // ==================================================================
    // 四、缺题 / 缺维不得补 0
    // ==================================================================

    @Test
    @DisplayName("缺维度 / 缺题一律抛错，绝不按 0 分计入（硬纪律 #4）")
    void missing_answers_are_rejected_and_never_counted_as_zero() {
        ScaleScoringEngine engine = engineWithRealProfile();

        // ① 少一整个维度
        Map<String, List<Integer>> nineDims = new LinkedHashMap<>(fill(0));
        nineDims.remove("睡眠质量");
        BizException missingDim = assertThrows(BizException.class,
                () -> engine.score(new ScaleScoringEngine.Submission("男16-32", nineDims, "v1")),
                "缺维度被接受了 —— 缺维不得补 0（补 0 会把『更严重』伪装成『有改善』）");
        assertEquals(1001, missingDim.getCode());
        assertTrue(missingDim.getMessage().contains("睡眠质量"),
                "错误信息必须点名缺的是哪一维，便于排查；实际: " + missingDim.getMessage());

        // ② 某维度少答一题
        Map<String, List<Integer>> shortOne = new LinkedHashMap<>(fill(0));
        shortOne.put("体能精力", List.of(1, 1, 1));
        BizException shortDim = assertThrows(BizException.class,
                () -> engine.score(new ScaleScoringEngine.Submission("男16-32", shortOne, "v1")),
                "某维度少答一题被接受了 —— 缺题不得补 0");
        assertEquals(1001, shortDim.getCode());

        // ③ 某题得分为 null
        Map<String, List<Integer>> withNull = new LinkedHashMap<>(fill(0));
        List<Integer> withNullSlot = new ArrayList<>(List.of(1, 1, 1, 1));
        withNullSlot.set(2, null);
        withNull.put("体能精力", withNullSlot);
        BizException nullScore = assertThrows(BizException.class,
                () -> engine.score(new ScaleScoringEngine.Submission("男16-32", withNull, "v1")),
                "得分为 null 被接受了 —— null 不等于 0");
        assertEquals(1001, nullScore.getCode());

        // 反证：把缺的那一维补 0 之后能算出分 —— 说明上面被拒的原因是"缺题"而非别的
        // （若"补 0"也能算，就更说明必须显式禁止它：它太容易通过且不报错）
        ScaleScoringEngine.ScoreResult zeroFilled =
                engine.score(new ScaleScoringEngine.Submission("男16-32", fill(0), "v1"));
        assertEquals(0, zeroFilled.totalScore(),
                "补 0 的作答会算出 0 分（看起来像『完全无症状』）—— 这正是缺题必须抛错的原因");
    }

    // ==================================================================
    // 五、严重度：未校准即 TBD
    // ==================================================================

    @Test
    @DisplayName("严重度分档未校准时输出 TBD，且响应里不含任何分档边界数字")
    void severity_label_is_TBD_when_bands_are_not_calibrated() {
        ScaleScoringEngine engine = engineWithRealProfile();
        assertFalse(engine.profile().hasSeverityBands(),
                "当前 config #35 不应含 severity_bands（业务/临床未给校准边界）—— "
                        + "若已补上校准值，请更新本断言并确认边界来源");

        ScaleScoringEngine.ScoreResult r = engine.score(submission(fill(0)));
        assertEquals("TBD", r.severityLabel(),
                "分档未校准时应输出 TBD（硬纪律 #6：TBD 不得填数），实际=" + r.severityLabel());
        assertNotEquals(EXPECTED_SEVERITY_LABELS.get(0), r.severityLabel(),
                "不得按『看起来合理』的边界兜底出一个档位");
    }

    @Test
    @DisplayName("分档一旦配置就必须三档齐备，且覆盖全量程（有洞即拒）")
    void severity_bands_must_be_complete_and_cover_the_whole_range_when_configured() {
        // 只给两档 → 配置阶段即拒（未给出的那档会在 score() 里静默落空）
        String twoBands = """
                {"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,
                 "severity_bands":{"轻度":[0,30],"中度":[31,60]}}
                """;
        BizException incomplete = assertThrows(BizException.class,
                () -> ScaleScoringProfile.fromConfigJson(twoBands),
                "只给两档被接受了 —— 未给出的那档会静默落空，比报错危险得多");
        assertEquals(5001, incomplete.getCode(), "配置不自洽属业务规则冲突（5001）");

        // 未登记的档位名 → 拒
        String badLabel = """
                {"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,
                 "severity_bands":{"轻微":[0,30],"中度":[31,60],"较重":[61,112]}}
                """;
        assertThrows(BizException.class, () -> ScaleScoringProfile.fromConfigJson(badLabel),
                "未登记的档位名被接受了 —— 档位枚举来自 PRD 附录 C.1.3，不得自造");

        // 三档齐备但不覆盖全量程 → score() 遇到空洞必须抛错，不得给"未知档"
        String gapped = """
                {"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,
                 "severity_bands":{"轻度":[0,30],"中度":[31,60],"较重":[90,112]}}
                """;
        ScaleScoringEngine gappedEngine =
                new ScaleScoringEngine(ScaleScoringProfile.fromConfigJson(gapped));
        BizException gap = assertThrows(BizException.class,
                () -> gappedEngine.severityOf(70),
                "分档存在空洞时给了档位 —— 配置有洞必须炸，而不是给一个『未知档』");
        assertEquals(5001, gap.getCode());
    }

    // ==================================================================
    // 六、口径自洽（结构推导，不是抄数）
    // ==================================================================

    @Test
    @DisplayName("口径自洽：levels = max−min+1、维度满分 = 每维题数×max、总分 = 维数×维度满分")
    void profile_self_consistency_is_enforced_by_derivation() {
        ScaleScoringProfile p = ScaleScoringProfile.fromConfigJson(
                new ConfigSeedScaleProfileSource().rangeRuleJson());

        assertEquals(p.itemMax() - p.itemMin() + 1, p.levels(),
                "级数必须等于 max−min+1（否则『五级』这个说法与量程对不上）");
        assertEquals(p.itemsPerDimension() * p.itemMax(), p.dimensionMax(),
                "维度满分必须等于 每维题数 × 单题上限（由结构推导）");
        assertEquals(p.dimensionCount() * p.dimensionMax(), p.totalMax(),
                "总分上限必须等于 维数 × 维度满分（由结构推导）");
        assertEquals(0, p.itemMin(), "量程下限应为 0（症状向自评量表的下限即『无症状』）");

        // 口径的整结构必须等于"由配置声明的值"——这些值来自 PRD 附录 C.1.5 的声明
        assertEquals(4, p.itemMax(), "单题上限应为 4（PRD：0–4 五级）");
        assertEquals(5, p.levels(), "级数应为 5（五级）");
        assertEquals(16, p.dimensionMax(), "维度满分应为 16（4 题 × 0–4）");
        assertEquals(112, p.totalMax(), "总分上限应为 112（7 维 × 16）");
    }

    @Test
    @DisplayName("组卷题数由口径推导（7×4=28），不是写死的 28")
    void required_item_count_is_derived_from_the_profile() {
        ScaleScoringEngine engine = engineWithRealProfile();
        assertEquals(28, engine.requiredItemCount(), "7 维 × 4 题 = 28（PRD P0-11 同源复评 28 题）");
        assertEquals(engine.profile().dimensionCount() * engine.profile().itemsPerDimension(),
                engine.requiredItemCount(), "28 必须是推导值，不是常量");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static ScaleScoringEngine engineWithRealProfile() {
        ScaleProfileSource source = new ConfigSeedScaleProfileSource();
        return new ScaleScoringEngine(ScaleScoringProfile.fromConfigJson(source.rangeRuleJson()));
    }

    private static ScaleScoringEngine.Submission submission(Map<String, List<Integer>> answers) {
        return new ScaleScoringEngine.Submission("男16-32", answers, "v1");
    }

    /** 7 维齐备、每维 4 题、全部取同一分值。 */
    private static Map<String, List<Integer>> fill(int value) {
        Map<String, List<Integer>> m = new LinkedHashMap<>();
        for (String dim : List.of("体能精力", "面部气色肤质", "肩颈腰背筋骨", "睡眠质量",
                "记忆专注", "代谢体态消化", "情绪抗压与抵抗力")) {
            m.put(dim, List.of(value, value, value, value));
        }
        return m;
    }

    /**
     * 从源码里切出某个方法的<b>完整方法体</b>（按花括号配对，从签名后的第一个 {@code &#123;} 起）。
     *
     * <p>为什么要配对而不是"取签名后 1500 字符"：后者会把<b>下一个方法</b>也圈进来 ——
     * 于是断言可能在检查一个与目标无关的方法，看起来还"通过了"。
     * 按括号配对使切片边界由语法结构决定。
     */
    private static String methodBody(String src, String signature) {
        int sig = src.indexOf(signature);
        assertTrue(sig > 0, "找不到方法签名: " + signature + " —— 断言对象不见了，需先更新本测试");
        int open = src.indexOf('{', sig);
        assertTrue(open > 0, "方法签名后找不到方法体的 '{': " + signature);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("方法体花括号未配对: " + signature);
    }

    private static String context(String code, int at) {
        int from = Math.max(0, at - 40);
        int to = Math.min(code.length(), at + 30);
        return "…" + code.substring(from, to).replaceAll("\\s+", " ") + "…";
    }

    private static String stripCommentsAndStrings(String src) {
        // (?s) = DOTALL：块注释必须跨行匹配。详见 no_hardcoded_score_thresholds_… 里的前置自证。
        String s = stripCommentsOnly(src);
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }

    /**
     * 只剥注释，保留字符串字面量。
     *
     * <p>用作"响应体键名"类断言的切分前置：那些断言的判据<b>就是</b>字符串键名，
     * 把字符串一起剥掉会让断言永远通过。
     */
    private static String stripCommentsOnly(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return s.replaceAll("//[^\\n]*", " ");
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.isRegularFile(p), "待扫描的源文件必须存在: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 定位 {@code app/scale} 主源码目录（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveScaleMainDir() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/scale");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 app/scale 主源码目录（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    /** 定位 {@code app/scale/controller} 下的某个控制器源文件。 */
    private static Path resolveControllerFile(String fileName) {
        Path p = SCALE_MAIN_DIR.resolve("controller").resolve(fileName);
        assertTrue(Files.isRegularFile(p), "控制器源文件必须存在: " + p);
        return p;
    }
}