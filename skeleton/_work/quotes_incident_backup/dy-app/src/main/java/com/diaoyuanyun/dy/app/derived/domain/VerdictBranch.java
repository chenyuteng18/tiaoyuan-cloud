package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 判定分支 —— PRD §7.3 决策表 D1~D5 的<b>五值落库字面</b>
 * （V5 {@code verdict.branch} / {@code cycle_assessment.verdict} 的 CHECK 逐字）。
 *
 * <h2>五值来自哪一行（逐条可追溯）</h2>
 * <pre>
 *   D1  核心指标 ∈ {稳定,改善} AND AS_refund ≥ 阈值  → 稳定·改善      → '稳定'
 *   D2  AS_refund &lt; 阈值                          → 依从不足        → '依从不足'
 *   D3  AS_refund ≥ 阈值 AND 核心指标无改善          → 依从达标但无效   → '达标无效'
 *   D4  经络师标签 ∈ {高危, 新发, 同病}              → 全面评估        → '全面评估'
 *   D5  条件不满足 / 数据不足 / 测量不可比            → 人工复核        → '人工复核'
 * </pre>
 * 🛑 <b>DB 字面与 PRD 表述不是同一个字符串</b>：PRD D1 的"THEN 结论"写<b>稳定·改善</b>
 * （一个合并表述，含"改善"），而 V5 的 CHECK 只认 {@code '稳定'}。
 * 本枚举的 {@link #dbLabel()} 是入库值、{@link #prdConclusion()} 是 PRD 展示表述 ——
 * 两者分开持有，使"有人说 PRD 写的是稳定·改善，库里怎么只有稳定"这个问题
 * 有一个可读的答案，而不是一次静默的字符串截断。
 *
 * <h2>🛑 config {@code #9} 的分支字面自 <b>A-6</b> 起与本枚举逐字对齐</h2>
 * config {@code #9}（{@code cfg:verdict.branch_rules}）原声明值是：
 * <pre>
 *   "branches": ["improved","stable","no_improvement","worsened"]     ← 4 个英文键（旧）
 * </pre>
 * 而落库分支是 <b>5</b> 个中文值 —— 两套词表当时不可互相映射，且配置<b>漏掉了 D5</b>
 * （{@code 人工复核}），而 D5 是 PRD §7.3 里与 D1~D4 并列的一等分支。
 *
 * <p>🛑 <b>A-6 的处理是把 config 侧改成与落库侧同一套词汇</b>
 * （{@code ["稳定","依从不足","达标无效","全面评估","人工复核"]}），
 * 而不是让枚举去适配一个本就不完整的配置。两条具体理由：
 * <ul>
 *   <li>分支字面的<b>唯一所有者是判定域枚举</b>（它是入库值，受 V5 CHECK 约束）——
 *       配置侧只是它的一个副本。让副本去定义主本，是"两个真相源"的标准成因；</li>
 *   <li>旧配置的四个英文键描述的是<b>效果走向</b>（与 {@code effect_verdict} 的 E1~E5 同族），
 *       而落库的五个值是<b>处置分支</b>。强行建立一对一映射会造出一张假表 ——
 *       例如把 {@code '全面评估'} 映射到 {@code no_improvement}，而 D4 的触发条件
 *       （风险标签）与效果走向无关：一个核心指标明显改善的客户同样可以因"新发"而进全面评估。</li>
 * </ul>
 *
 * <p>🛑 本类仍然<b>不提供</b> {@code configKey()} 之类的映射访问器：
 * 分支字面已经是落库值本身，再配一个英文键只会造出第二个真相源。
 * 该纪律由 {@code VerdictBranchRoutingTest} 断言（方法名不得出现 {@code configKey}）。
 *
 * <h2>判定权分配（PRD §7.3 定调，逐字）</h2>
 * 「系统负责<b>聚合数据 + 结构化评分 + 强制路由</b>，人负责<b>临床判断与终审</b>。
 * 系统绝不自动下"痊愈/无效"的最终结论；但系统必须保证"该重定方案的不被拖过去"
 * —— D3 由系统自动触发，不依赖门店主动发起。」
 *
 * 故本枚举把两件事编码成属性而不是留在文档里：
 * {@link #requiresHumanInput()}（这一支能否成立是否依赖人工录入）与
 * {@link #systemAutoTriggered()}（动作是否由系统主动触发）。
 * 若它们只写在注释里，"某个批处理顺手把 D4 自动判出来"就不会有人发现。
 */
public enum VerdictBranch {

    /**
     * D1 稳定·改善 —— 维持原方案，生成下一周期。
     *
     * <p>人工依赖：核心指标"改善/稳定"由经络师<b>结构化录入</b>；{@code AS_refund} 系统计算。
     */
    STABLE("稳定", "D1", "稳定·改善",
            "维持原方案，生成下一周期", true, true),

    /**
     * D2 依从不足 —— 强化生活方式干预 + 追踪任务，推送提醒。
     *
     * <p>PRD 逐字：「无（纯系统计算）」—— 五支里<b>唯一</b>不依赖任何人工录入的。
     */
    INSUFFICIENT_ADHERENCE("依从不足", "D2", "依从不足",
            "强化生活方式干预 + 追踪任务，推送提醒", false, true),

    /**
     * D3 依从达标但无效 —— 自动提醒经络师重定全新方案 + 设备路由。
     *
     * <p>🛑 这一支是 PRD §7.3 定调句点名的那个："系统必须保证『该重定方案的不被拖过去』
     * —— D3 由系统自动触发，不依赖门店主动发起"。
     * 故 {@link #systemAutoTriggered()} 为真，且它<b>不</b>因为"需经络师确认核心指标"
     * 而变成一支被动等待的分支：确认动作缺失时落 D5（人工复核），
     * 而不是让这张单子静静地停在 D1 的候选位上。
     */
    ADHERENT_BUT_INEFFECTIVE("达标无效", "D3", "依从达标但无效",
            "自动提醒经络师重定全新方案；设备路由（秦皇岛→杠2，其他→现有）", true, true),

    /**
     * D4 全面评估 —— 生成全面评估任务 → 新方案 → <b>回炉审核 + 客户重签</b>。
     *
     * <p>人工依赖：「高危/新发/同病」<b>属临床判断，必须人工录入</b>（PRD 逐字）。
     */
    FULL_ASSESSMENT("全面评估", "D4", "全面评估",
            "生成全面评估任务 → 新方案 → 回炉审核 + 客户重签", true, true),

    /**
     * D5 人工复核 —— 挂起，待经络师决策。
     *
     * <p>它<b>不是</b>"判定失败"：PRD 把它列为五支之一，含义是
     * "条件不满足 / 数据不足 / 测量不可比 ⇒ 系统不猜，交给人"。
     * 故它在本枚举里是一等公民，而不是一个错误分支。
     */
    HUMAN_REVIEW("人工复核", "D5", "人工复核",
            "挂起，待经络师决策", true, false);

    private final String dbLabel;
    private final String decisionRule;
    private final String prdConclusion;
    private final String systemAction;
    private final boolean requiresHumanInput;
    private final boolean systemAutoTriggered;

    VerdictBranch(String dbLabel, String decisionRule, String prdConclusion,
                  String systemAction, boolean requiresHumanInput, boolean systemAutoTriggered) {
        this.dbLabel = dbLabel;
        this.decisionRule = decisionRule;
        this.prdConclusion = prdConclusion;
        this.systemAction = systemAction;
        this.requiresHumanInput = requiresHumanInput;
        this.systemAutoTriggered = systemAutoTriggered;
    }

    /**
     * 落库 / 契约字面（{@code verdict.branch} 与
     * {@code VerdictData.branch} 的 enum 逐字一致）。
     */
    public String dbLabel() {
        return dbLabel;
    }

    /** PRD §7.3 的决策表编号（{@code D1}~{@code D5}）—— 让每条落库结论可回溯到一行规则。 */
    public String decisionRule() {
        return decisionRule;
    }

    /**
     * PRD §7.3 "THEN 结论"列的<b>展示表述</b>。
     *
     * <p>🛑 它与 {@link #dbLabel()} 只在 D1 上不同（{@code 稳定·改善} vs {@code 稳定}）。
     * 单独持有它是为了让这个差异可见：PRD 的表述是对人的，落库字面是对契约与库的。
     */
    public String prdConclusion() {
        return prdConclusion;
    }

    /** PRD §7.3 "系统动作"列逐字。 */
    public String systemAction() {
        return systemAction;
    }

    /** 本分支能否成立是否<b>依赖人工录入</b>（PRD §7.3 最后一列）。 */
    public boolean requiresHumanInput() {
        return requiresHumanInput;
    }

    /**
     * 本分支的动作是否由系统<b>主动触发</b>（不依赖门店发起）。
     *
     * <p>PRD §7.3 定调句只对 D3 逐字点明"由系统自动触发"。
     * 本类把 D2 也标为真 —— 它的动作（推送提醒）同样不需要人先动手。
     * 🛑 D1/D4/D5 为假：D1 要等核心指标录入、D4 要等风险标签录入、
     * D5 本来就是"等人工决策"，把它们标成自动触发会让"判定即动作"变成"系统替人下结论"。
     *
     * <p>⚠️ 自动触发<b>不等于</b>自动出结论：D3 的动作是"提醒经络师重定方案"，
     * 而不是"系统宣布这个客户无效"。这两件事在任何地方都不得混同。
     */
    public boolean systemAutoTriggered() {
        return systemAutoTriggered;
    }

    /** 五值的全部落库字面（顺序固定为 D1→D5，供门禁与契约逐字比对）。 */
    public static List<String> allDbLabels() {
        return Arrays.stream(values()).map(VerdictBranch::dbLabel).toList();
    }

    /**
     * 按落库字面解析；未知一律 fail-closed。
     *
     * <p>🛑 不回落到 {@link #HUMAN_REVIEW}：那看起来"很安全"（未知就交给人），
     * 但它会让一次数据质量问题（库里多了一个没人认识的 branch）静默变成一次
     * 合法的人工复核 —— 而这两件事在稽核视角下完全不同：
     * 前者是"有人写坏了数据"，后者是"系统按规则挂起了一单"。
     * 回落把它们合并，等于把数据损坏伪装成正常业务。
     */
    public static VerdictBranch parse(String dbLabel) {
        if (dbLabel == null || dbLabel.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "branch 必填（5 值之一: " + allDbLabels() + "）");
        }
        String v = dbLabel.trim();
        Optional<VerdictBranch> hit = Arrays.stream(values())
                .filter(b -> b.dbLabel.equals(v))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                "branch 不在 5 值枚举内: " + dbLabel + "（合法值: " + allDbLabels() + "）。"
                        + "🛑 不得回落为『人工复核』—— 那会把一次数据损坏"
                        + "静默伪装成一单正常的人为挂起"));
    }
}