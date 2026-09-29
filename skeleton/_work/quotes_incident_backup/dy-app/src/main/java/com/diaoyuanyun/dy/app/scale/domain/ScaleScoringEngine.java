package com.diaoyuanyun.dy.app.scale.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-4 计分引擎 —— 对一份作答（分龄题组）算维度分与总分。
 *
 * <h2>它算的是什么（口径，不是自造）</h2>
 * 一份作答 = 该年龄组锁定题组下的 7 维 × 4 题，每题取 {@code 0–4} 五级：
 * <pre>
 *   维度分 = Σ(该维度 4 题得分)          → [0, 16]
 *   总分   = Σ(7 个维度分)               → [0, 112]
 * </pre>
 * 依据 PRD 附录 C.1.5（「每题 <b>0–4</b>；每维度 4 题 = <b>0–16</b>；7 维度 = <b>0–112</b>」）
 * 与 config {@code #35}（{@code dimension_max=16} / {@code total_max=112}）。
 *
 * <h2>🛑 本类不含任何分值常量</h2>
 * 上限、下限、级数、每维题数<b>全部</b>来自 {@link ScaleScoringProfile}（由 config {@code #35} 归一化）。
 * 本类里出现的最大整数是 {@code item_no} 的 1..4（结构常量，见 {@link ScaleDomain}）。
 * S1-4 验收第 3 条「代码内 {@code grep} 无硬编码分值」由
 * {@code ScaleEngineContractTest#no_hardcoded_score_thresholds_exist_in_the_engine_source}
 * 静态扫描保证：它扫本文件源码，把其中出现的<b>每一个数字字面量</b>收集成集合，
 * 与<b>从 config {@code #35} 推导出的数字集合</b>逐项比对 —— 源码里多一个或少一个即失败。
 * 判据是"与配置口径同源"，不是"命中某个黑名单"。
 *
 * <h2>为什么"缺题"必须炸而不是当 0 分（硬纪律 #4 在计分上的落地）</h2>
 * 硬纪律 #4「缺失不得补 0」针对的正是本处最容易犯的错：
 * 某维度少答一题，若把它当 0 分（"没答=无症状"），维度分会被<b>压低</b>，
 * 于是"更严重"看起来像"有改善" —— 而且不会报错。故本引擎对缺题一律返回
 * {@link ErrorCode#VALIDATION_FAILED}，并在消息里点名缺哪一维、缺几题。
 *
 * <h2>严重度：未配置口径即 TBD</h2>
 * {@code severity_label}（轻度/中度/较重）的<b>分档边界</b>在 PRD 里没有值
 * （只给了三值枚举，未给边界），属"建议值需真实数据校准"。故本引擎在
 * {@link ScaleScoringProfile#hasSeverityBands()} 为假时输出 {@link ContractTbd#TBD}，
 * <b>绝不</b>按"看起来合理"的边界兜底。待业务/临床给出校准值 → 配置里补
 * {@code severity_bands} → 代码零改动。
 */
public class ScaleScoringEngine {

    private final ScaleScoringProfile profile;

    public ScaleScoringEngine(ScaleScoringProfile profile) {
        if (profile == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "计分引擎缺少量程口径（config #35 未装载）—— 拒绝按默认口径计分");
        }
        this.profile = profile;
    }

    // ------------------------------------------------------------------
    // 入参 / 出参
    // ------------------------------------------------------------------

    /**
     * 一份作答。
     *
     * @param ageGroup    锁定年龄组（落库字面，8 组之一）
     * @param answers     维度 → 该维度 4 题的得分（顺序 = {@code item_no} 1..4）
     * @param itemVersion 题组版本（改善率比较要求同源，版本是其中的关键成员）
     */
    public record Submission(String ageGroup, Map<String, List<Integer>> answers, String itemVersion) {
    }

    /**
     * 计分结果。
     *
     * @param severityLabel {@code 轻度/中度/较重} 或 {@link ContractTbd#TBD}（口径未配置）
     */
    public record ScoreResult(
            String ageGroup,
            String itemVersion,
            Map<String, Integer> dimensionScores,
            int totalScore,
            Object severityLabel,
            Map<String, Integer> itemMinMax) {
    }

    // ------------------------------------------------------------------
    // 计分
    // ------------------------------------------------------------------

    /**
     * 对一份作答计分。
     *
     * <p>校验顺序（固定，便于错误信息可预期）：
     * ① 年龄组合法 → ② 7 维齐备 → ③ 每维恰好 4 题 → ④ 每题在 {@code [min, max]} 内。
     */
    public ScoreResult score(Submission submission) {
        if (submission == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "作答内容不得为空");
        }
        // ① 年龄组必须是 8 组之一（不自造、不回落到默认组）
        ScaleDomain.AgeGroup ageGroup = ScaleDomain.AgeGroup.parse(submission.ageGroup());

        Map<String, List<Integer>> answers = submission.answers();
        if (answers == null || answers.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "作答不得为空：需含 7 个维度的得分");
        }

        Map<String, Integer> dimensionScores = new LinkedHashMap<>();

        // ② 7 维齐备 —— 缺维不补 0（见类注释：补 0 会把"更严重"伪装成"有改善"）
        for (ScaleDomain.Dimension dim : ScaleDomain.Dimension.values()) {
            List<Integer> items = answers.get(dim.label());
            if (items == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "作答缺维度: " + dim.label() + "（7 维须齐备；缺维不得按 0 分计入 —— 硬纪律 #4）");
            }
            // ③ 每维恰好 4 题 —— 少答不得补 0，多答说明题组被改了
            if (items.size() != profile.itemsPerDimension()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "维度 " + dim.label() + " 应含 " + profile.itemsPerDimension()
                                + " 题，实际 " + items.size() + " 题（缺题不得补 0）");
            }
            int sum = 0;
            for (int i = 0; i < items.size(); i++) {
                Integer v = items.get(i);
                if (v == null) {
                    throw new BizException(ErrorCode.VALIDATION_FAILED,
                            "维度 " + dim.label() + " 第 " + (i + 1) + " 题得分为空（缺题不得补 0）");
                }
                // ④ 量程校验：越界即拒。🛑 不截断（截断会静默改分）
                if (v < profile.itemMin() || v > profile.itemMax()) {
                    throw new BizException(ErrorCode.VALIDATION_FAILED,
                            "维度 " + dim.label() + " 第 " + (i + 1) + " 题得分越界: " + v
                                    + "（量程 " + profile.itemMin() + "–" + profile.itemMax()
                                    + "；越界不得截断）");
                }
                sum += v;
            }
            dimensionScores.put(dim.label(), sum);
        }

        int total = dimensionScores.values().stream().mapToInt(Integer::intValue).sum();
        // 自洽断言：总分不得超过口径上限。此处若真发生，属口径与题组不匹配，必须炸（不许夹到上限）。
        if (total > profile.totalMax()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "总分 " + total + " 超出量程口径上限 " + profile.totalMax()
                            + " —— 题组与口径不匹配（不得夹到上限）");
        }

        return new ScoreResult(
                ageGroup.label(),
                submission.itemVersion(),
                dimensionScores,
                total,
                severityOf(total),
                Map.of("min", profile.itemMin(), "max", profile.itemMax()));
    }

    /**
     * 按配置的分档求严重度；<b>口径未配置即返回 TBD</b>。
     *
     * <p>🛑 这是硬纪律 #6「TBD 不得填数」在本模块的落点：
     * 分档边界属"建议值需真实数据校准"，PRD 未给值 ⇒ 只能说 TBD。
     * 兜底一组边界会让下游把"未校准的分档"当成已校准结果使用。
     */
    public Object severityOf(int totalScore) {
        if (!profile.hasSeverityBands()) {
            return ContractTbd.TBD;
        }
        Map<String, ScaleScoringProfile.Band> bands = profile.severityBands();
        for (String label : ScaleScoringProfile.SEVERITY_LABELS) {
            ScaleScoringProfile.Band band = bands.get(label);
            if (band != null && band.contains(totalScore)) {
                return label;
            }
        }
        // 分档已配置却没覆盖该分值 ⇒ 配置有洞，必须炸而不是给"未知档"
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "严重度分档未覆盖总分 " + totalScore + "（severity_bands 存在缺口）");
    }

    /** 口径只读视图（供演示端点与回归用例展示"分值来自配置"）。 */
    public ScaleScoringProfile profile() {
        return profile;
    }

    /**
     * 组卷所需的最小题数 —— 供 S1-4「可组卷」验收断言使用。
     *
     * <p>值来自口径（{@code 维度数 × 每维题数}），不是写死的 28。
     * 这正是 PRD P0-11「同源复评 28 题」的机器侧来源：28 = 7 × 4 是<b>推导值</b>，不是常量。
     */
    public int requiredItemCount() {
        return profile.dimensionCount() * profile.itemsPerDimension();
    }
}