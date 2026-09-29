package com.diaoyuanyun.dy.app.scale.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-4 计分口径 —— 归一化自配置项 {@code #35}（{@code cfg:scale.range_rule}）。
 *
 * <h2>为什么口径必须外置（S1-4 验收第 3 条）</h2>
 * 开发清单 §一④：「评分口径<b>外置为配置项</b>（代码内 {@code grep} 无硬编码分值）」。
 * 这不是洁癖：PRD §10 的硬性要求是「<b>所有业务数字不得硬编码</b>」，
 * 理由是量程口径已被改判过一次（v1.7：0–3 四级 → <b>0–4 五级</b>），
 * 而那次改判若发生在代码里，就要全仓库找常量；发生在配置里，只需改一行值。
 *
 * <h2>口径数值的权威来源</h2>
 * 本记录<b>不自行定义任何数值</b>，全部来自 {@code #35} 的声明值：
 * <pre>
 * {"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,
 *  "same_origin_group":"age_band_locked_by_02"}
 * </pre>
 * 该值是 config 声明表里的真实初始值（{@code dy-config/.../02_slots_seed.sql} #35 行），
 * 不是本类猜的。{@link #fromConfigJson} 是唯一的构造入口。
 *
 * <h2>严重度分档（{@code 轻度 / 中度 / 较重}）：<b>故意留空</b></h2>
 * PRD 附录 C.1.3 给出了 {@code severity_label} 的三值枚举，但<b>没有给出分档边界</b>
 * （几分为轻度、几分为较重）。边界属"建议值需真实数据校准"范畴 ——
 * 硬纪律 #6「TBD 不得填数」在本处的具体含义就是：
 * <b>宁可让严重度输出 {@code TBD}，也不许编一组边界</b>。
 * 故 {@code severityBands} 缺省为空 Map，{@link ScaleScoringEngine} 遇到它即输出 {@code TBD}，
 * 且<b>不</b>按"看起来合理"的边界兜底。待业务/临床给出经校准的边界后，
 * 只需在配置里补 {@code severity_bands}，代码零改动（这正是"口径外置"要买的东西）。
 */
public record ScaleScoringProfile(
        int itemMin,
        int itemMax,
        int levels,
        int dimensionMax,
        int totalMax,
        int itemsPerDimension,
        int dimensionCount,
        Map<String, Band> severityBands,
        String source) {

    /** 一个严重度档位：{@code [lowerInclusive, upperInclusive]} 的闭合区间。 */
    public record Band(int lowerInclusive, int upperInclusive) {

        public boolean contains(int score) {
            return score >= lowerInclusive && score <= upperInclusive;
        }
    }

    /** PRD 附录 C.1.3 的 {@code severity_label} 三值枚举（顺序即由轻到重）。 */
    public static final List<String> SEVERITY_LABELS = List.of("轻度", "中度", "较重");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ScaleScoringProfile {
        severityBands = severityBands == null ? Map.of() : Map.copyOf(severityBands);
    }

    /**
     * 唯一构造入口：从配置项 {@code #35} 的 JSON 值归一化。
     *
     * <p><b>逐项 fail-closed</b>：任一必需字段缺失即抛，绝不设默认值 ——
     * 设默认值等于把"口径丢失"静默成"口径是默认的那套"，而量程口径正是被改判过的东西。
     */
    public static ScaleScoringProfile fromConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "量表量程口径配置缺失（cfg:scale.range_rule / config #35）—— 拒绝默认值");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(configJson);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "量表量程口径配置不是合法 JSON（config #35）: " + e.getMessage());
        }

        JsonNode range = root.path("range");
        int min = requireInt(range, "min", "range.min");
        int max = requireInt(range, "max", "range.max");
        int levels = requireInt(range, "levels", "range.levels");
        int dimensionMax = requireInt(root, "dimension_max", "dimension_max");
        int totalMax = requireInt(root, "total_max", "total_max");

        // 结构自洽：五级 = max − min + 1；维度满分 = 每维题数 × max；
        // 总分 = 维数 × 维度满分。三处若不自洽，说明配置被改坏了 —— 必须炸，不许"取其一"。
        int expectedLevels = max - min + 1;
        if (levels != expectedLevels) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "量程不自洽：levels=" + levels + " 但 max−min+1=" + expectedLevels
                            + "（config #35 range 段）");
        }
        int itemsPerDimension = ScaleDomain.ITEMS_PER_DIMENSION;
        int dimensionCount = ScaleDomain.DIMENSION_COUNT;
        if (dimensionMax != itemsPerDimension * max) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "维度满分不自洽：dimension_max=" + dimensionMax + " 但 每维题数×单题上限="
                            + (itemsPerDimension * max) + "（config #35）");
        }
        if (totalMax != dimensionCount * dimensionMax) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "总分上限不自洽：total_max=" + totalMax + " 但 维度数×维度满分="
                            + (dimensionCount * dimensionMax) + "（config #35）");
        }
        // 量程下限为 0 是<b>语义</b>要求（症状向自评量表的下限即"无症状"），不是数值偏好。
        // 若允许 >0，则"完全无症状"这一档无法表达；若允许 <0，负分会让维度分失去可解释性。
        if (min != 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "量程下限应为 0（症状向自评量表的下限即『无症状』），实际=" + min);
        }

        return new ScaleScoringProfile(min, max, levels, dimensionMax, totalMax,
                itemsPerDimension, dimensionCount, parseSeverityBands(root), "config#35");
    }

    /**
     * 解析可选的严重度分档。
     *
     * <p><b>缺省即 TBD</b>：{@code severity_bands} 不存在时返回空 Map，
     * 由 {@link ScaleScoringEngine} 输出 {@code TBD}。
     * 🛑 此处<b>绝不允许</b>写"若缺省则用 X/Y 分界"的兜底 —— 见类注释。
     */
    private static Map<String, Band> parseSeverityBands(JsonNode root) {
        JsonNode bands = root.path("severity_bands");
        if (bands.isMissingNode() || !bands.isObject()) {
            return Map.of();
        }
        Map<String, Band> parsed = new LinkedHashMap<>();
        var it = bands.fields();
        while (it.hasNext()) {
            var e = it.next();
            String label = e.getKey();
            if (!SEVERITY_LABELS.contains(label)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "severity_bands 含未登记档位: " + label + "（合法: " + SEVERITY_LABELS + "）");
            }
            JsonNode arr = e.getValue();
            if (!arr.isArray() || arr.size() != 2) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "severity_bands." + label + " 应为 [下界, 上界] 二元数组");
            }
            parsed.put(label, new Band(arr.get(0).asInt(), arr.get(1).asInt()));
        }
        // 三档必须齐备：只给两档时，未给出的那一档会在 score() 里静默落空（或抛"未覆盖"），
        // 而"静默落空"比"报错"危险得多 —— 故此处直接把不齐备判为配置错误。
        if (!parsed.isEmpty() && parsed.size() != SEVERITY_LABELS.size()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "severity_bands 必须三档齐备（轻度/中度/较重），实际仅 " + parsed.keySet()
                            + " —— 部分给出会让未给出的那档静默落空");
        }
        return parsed;
    }

    /**
     * 严重度分档是否已配置。未配置 = 严重度对外一律 TBD（硬纪律 #6）。
     *
     * <p>⚠️ 这里刻意<b>不</b>定义 {@code severityBands()} 访问器：record 已自动生成同名方法，
     * 再定义同名但返回 {@code Optional<Map>} 的版本会因<b>返回类型不同</b>而编译失败。
     * 调用方（计分引擎）的实际需求就是"有没有"，故返回布尔。
     */
    public boolean hasSeverityBands() {
        return !severityBands.isEmpty();
    }

    private static int requireInt(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isInt() && !v.isLong()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "量表量程口径缺字段或类型非整数: " + label + "（config #35）");
        }
        return v.asInt();
    }
}