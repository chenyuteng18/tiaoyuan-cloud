package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 风险标签 —— 契约 §3.1「维度分离原则」里的 <b>{@code risk_flag}</b> 四值枚举。
 *
 * <h2>四值的来源</h2>
 * PRD §7.3 的 D4 行逐字：「经络师录入标签 ∈ {<b>高危, 新发, 同病</b>}」→ 全面评估。
 * 加上"无标签"这一正常态即四值。指标规格 §3.1 的维度分离表同样逐字列出
 * 「风险 {@code risk_flag} 无 / 高危 / 新发 / 同病」。
 *
 * <h2>🛑 三值里只有 {@link #NONE} 是"系统能自己知道的"</h2>
 * {@link #HIGH_RISK} / {@link #NEW_ONSET} / {@link #COMORBID} 全部属
 * <b>临床判断，必须人工录入</b>（PRD §7.3 D4 行"人工录入依赖"列逐字）。
 * 这是本枚举把"能不能自动得出"编码成属性的理由：若只写在文档里，
 * 就会出现"某个统计任务发现某维度分数上升，顺手标一个 {@code 新发}"——
 * 而 {@link EffectVerdictEngine} 的 {@code baseline_zero} 分支已经明确：
 * 分数上升只是"疑新发"，须人工确认分支（E4 / E5），不得自动定标签。
 *
 * <h2>🛑 库层对 {@code risk_flag} 的约束：<b>V5 无 CHECK，V8 补上 CHECK</b>（两阶段事实）</h2>
 * 这条历史分两段，缺任一段都会读错：
 * <ol>
 *   <li><b>V5 建表时</b>：{@code verdict.risk_flag} 定义为 {@code VARCHAR(32)}（<b>无 CHECK</b>），
 *       与同表另外三个枚举列（{@code branch} / {@code effect_verdict} / {@code adherence_state}）
 *       都带 CHECK 形成对照。这不是疏漏：{@code risk_flag} 是<b>当时尚未收口的临床词表</b>
 *       （D4 的标签集合由经络师录入侧定义），故库层<b>刻意</b>没把它钉死。
 *       ⇒ 彼时"这个列只能装四个值"<b>没有任何数据库层保证</b>，唯一防线的位置就在本类
 *       的 {@link #parse} 上。这条历史事实由 {@code VerdictPersistenceBoundaryTest} 的
 *       {@code v5_risk_flag_has_no_check_but_others_do} 守护（断言 V5 里这一列确实无 CHECK）。</li>
 *   <li><b>V8 补上 CHECK</b>（两阶段对齐迁移，第 4 节）：{@code verdict_risk_flag_values}
 *       把值域钉为 {@code NULL OR IN ('无','高危','新发','同病')}。
 *       ⇒ 此后库层是<b>第二道闸</b>，与本类的 {@link #parse} 构成<b>纵深防御两层</b>。
 *       🛑 补 CHECK <b>不撤销</b>应用层防线：V8 只拦"写库的值域"，
 *       拦不住"解析期把一个未登记标签悄悄改成 {@link #NONE}"——那是本类的职责。
 *       V8 的 CHECK 由 {@code VerdictPersistenceBoundaryTest} 的
 *       {@code v8_risk_flag_check_is_present_and_matches_enum_labels} 守护。</li>
 * </ol>
 *
 * <p>∅ 可空性两段一致：{@code null} 表示「<b>未录入</b>」（未录入 ⇒ 路由落 D5），
 * 这是一个<b>合法的业务状态</b>，故 CHECK 写成 {@code risk_flag IS NULL OR risk_flag IN (...)}
 * 而非 {@code NOT NULL + IN (...)}。</p>
 *
 * <h2>fail-closed：未知一律抛，绝不回落到 {@link #NONE}</h2>
 * 回落到 {@code 无} 是最危险的一种：它把"出现了一个没人认识的临床标签"
 * 静默改写为"这个客户没有风险标签" —— 后者会让 D4（全面评估）本应触发而不触发。
 * 这正是 PRD §7.3 定调句所防的那类事故：「系统必须保证『该重定方案的不被拖过去』」。
 */
public enum RiskFlag {

    /** 无风险标签（正常态）。系统可自动得出 —— 其余三值都不可。 */
    NONE("无", false),

    /** 高危 —— 临床判断，必须人工录入（PRD §7.3 D4）。 */
    HIGH_RISK("高危", true),

    /**
     * 新发 —— 临床判断，必须人工录入。
     *
     * <p>⚠️ 与 {@link EffectVerdictEngine} 的 {@code baseline_zero} 分支相邻但不同：
     * 那里输出的是"<b>疑</b>新发"（{@code baselineZero=true} + 需人工确认），
     * 本枚举的这一值才是"已由经络师确认的新发"。两者不得互相赋值。
     */
    NEW_ONSET("新发", true),

    /** 同病 —— 临床判断，必须人工录入（PRD §7.3 D4）。 */
    COMORBID("同病", true);

    private final String dbLabel;
    private final boolean clinicalJudgement;

    RiskFlag(String dbLabel, boolean clinicalJudgement) {
        this.dbLabel = dbLabel;
        this.clinicalJudgement = clinicalJudgement;
    }

    /** 落库字面（{@code verdict.risk_flag} 的取值）。 */
    public String dbLabel() {
        return dbLabel;
    }

    /**
     * 该标签是否属<b>临床判断</b>（必须人工录入）。
     *
     * <p>仅 {@link #NONE} 为假。PRD §7.3 D4 行逐字：
     * 「『高危/新发/同病』属临床判断，必须人工录入」。
     */
    public boolean requiresClinicalInput() {
        return clinicalJudgement;
    }

    /**
     * 是否触发 D4（全面评估）。
     *
     * <p>PRD §7.3 D4 的 IF 条件逐字：{@code 经络师录入标签 ∈ {高危, 新发, 同病}}。
     * 故本方法对三个临床标签为真、对 {@link #NONE} 为假 ——
     * 它不是"有标签就触发"，而是"有<b>这三个</b>标签才触发"。
     */
    public boolean triggersFullAssessment() {
        return clinicalJudgement;
    }

    /** D4 的触发集合（供路由与门禁逐字比对 PRD 的 {@code {高危, 新发, 同病}}）。 */
    public static Set<RiskFlag> d4TriggerSet() {
        return Set.of(HIGH_RISK, NEW_ONSET, COMORBID);
    }

    /** 全部四值的落库字面（顺序固定，供契约与门禁比对）。 */
    public static List<String> allDbLabels() {
        return Arrays.stream(values()).map(RiskFlag::dbLabel).toList();
    }

    /**
     * 按落库字面解析；未知一律 fail-closed。
     *
     * <p>🛑 不回落到 {@link #NONE}（见类注释）：回落到"无"会让一个未登记标签
     * 变成"无风险"，从而让 D4 本应触发而不触发。
     */
    public static RiskFlag parse(String dbLabel) {
        if (dbLabel == null || dbLabel.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "risk_flag 必填（4 值之一: " + allDbLabels()
                            + "）—— 缺标签时给『无』是调用方的决定，不得由解析器代劳");
        }
        String v = dbLabel.trim();
        Optional<RiskFlag> hit = Arrays.stream(values())
                .filter(f -> f.dbLabel.equals(v))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                "risk_flag 不在 4 值枚举内: " + dbLabel + "（合法值: " + allDbLabels() + "）。"
                        + "🛑 不得回落为『无』—— 回落会把一个未登记的临床标签"
                        + "静默改写为『无风险标签』，使 D4（全面评估）该触发而不触发"));
    }
}