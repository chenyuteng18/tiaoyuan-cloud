package com.diaoyuanyun.dy.app.archive.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>结案归档清单的 6 个键</b> —— 与迁移
 * {@code V20__case_archive_provisioning.sql} 里 {@code register_case_archive()}
 * 内部的两个键集数组<b>逐字对齐</b>，逐字对齐 PRD C.1.7 / C.3 / P0-25。
 *
 * <h2>🛑🛑 本类存在的第一理由：把「手环类缺项不得反转为阻断」变成类型</h2>
 * PRD 里关于这 6 项有两处口径，而它们<b>说的不是同一件事</b>：
 * <pre>
 *   C.1.7（字段映射表）：case_archive.archive_checklist | map&lt;6bool&gt; | 是
 *                       | <b>缺项→403 阻断结案</b>        ← 一个笼统的汇总口径
 *   C.3（归档完整性校验规则）：14 条 ARC 规则，<b>逐条</b>给出"硬阻断 / 警告"
 * </pre>
 * C.3 的逐条划定里，落在本清单上的 6 条是：
 * <pre>
 *   ARC-01 退款原因未记录                → <b>硬阻断（后端 403）</b>
 *   ARC-09 基线与当前复评未对照           → <b>硬阻断</b>
 *   ARC-10 挽留过程未记录（入口 A）        → <b>硬阻断</b>
 *   ARC-12 最终处理结论未经负责人签字      → <b>硬阻断</b>
 *   ARC-13 客户档案/方案/执行/评估未归档    → <b>硬阻断</b>
 *   ARC-11 手环数据未按"参考之一"口径记录  → <b>警告（不阻断）</b>
 *   （关联的 ARC-07 未佩戴 → 标"未佩戴"、<b>不阻断、不作不利依据</b>；
 *     ARC-08 已同意佩戴却无记录 → <b>警告（不阻断）</b> + 记数据缺口）
 * </pre>
 * ⇒ <b>5 项硬阻断 + 1 项（手环）警告不阻断</b>。
 * <p>而 PRD P0-25 与 README §5.3「三条不得触碰」③ 逐字写着：
 * <pre>
 *   「<b>任何"手环缺项反转为阻断"的写法一律违规</b>（P0-25 + ARC-07/08/11）」
 * </pre>
 * <p>🛑 「把 6 项一律要求 true」这个改动<b>看起来是"更安全、更严格"的</b> ——
 * 它有极大的动机被做出，而它恰好<b>违规</b>：手环数据是"参考之一"（PRD W-3 逐字），
 * 把它变成阻断等于用一条数据缺口去阻止结案。
 * <p>⇒ 故本类<b>不给</b>一个"6 项齐备"的布尔判断，也<b>不给</b>一个
 * {@code allOf(...)} 之类的聚合方法 —— 那正是把红线变成一个"顺手可用"的 API。
 * 而是把两种严格度做成<b>类型上的分野</b>（{@link Gate#HARD_BLOCK} /
 * {@link Gate#WARNING}），让"把它们并到一起"这件事必须显式写出
 * （{@code CaseArchiveGateTest} 会对 {@link #HARD_BLOCKING} 逐项机械断言）。
 *
 * <h2>🛑 为什么"缺键"与"未知键"【都】不合法（与 {@code ConsentAuthScope} 的差别）</h2>
 * {@code ConsentAuthScope} 的推理是"空集合合法、未知键必抛"，因为
 * {@code auth_scope_json} 是<b>客户可单独拒绝的集合</b>（"可单独拒绝"是它的语义）。
 * <p>本清单<b>不是集合</b>，而是一份<b>封闭的完整性清单</b> —— PRD 逐字列出全部 6 项，
 * 每项都要表态。故：
 * <table border="1">
 *   <caption>两种 JSONB 键集的处置差异（不是不一致，是语义不同）</caption>
 *   <tr><th></th><th>{@code consent.auth_scope_json}</th><th>本清单 {@code archive_checklist}</th></tr>
 *   <tr><td>语义</td><td>可单独拒绝的<b>授权集合</b></td><td>封闭的<b>完整性清单</b></td></tr>
 *   <tr><td>缺键</td><td><b>合法</b>（拒绝某项＝不勾该项）</td><td><b>不合法</b>（每项都要表态）</td></tr>
 *   <tr><td>未知键</td><td>抛</td><td>抛</td></tr>
 * </table>
 * <p>🛑 两处都"未知键必抛"是<b>同一条理由</b>（静默丢弃会让一次拼写错误
 * 变成"某项从未被记录"，事后无从复原）；而"缺键"的差别是<b>语义差别</b>，
 * 不是谁更严。这一点必须写清，否则下一个人会以为两处应统一其中一处。
 *
 * <h2>🛑 键名为什么用英文 snake_case（登记为待裁，见 V20 文件头第 2 条）</h2>
 * PRD C.1.7 用<b>中文键名</b>描述这 6 项（{@code map<6bool>：原因已记录/…}），
 * 而本仓既有 JSONB 范式（{@code auth_scope_json}）用的是 snake_case 英文 code，
 * 且逐字要求"未登记即抛，绝不回落"。
 * <p>⇒ 本类<b>采用英文 snake_case</b>（与既有范式一致、对"未知键必抛"更可机械判定），
 * 并把这一选择<b>登记为待裁</b> —— 契约里没有任何地方冻结过这 6 个键的字面。
 * 若将来裁定用中文键名，<b>改的必须是这里 + V20 函数内的两个数组</b>，
 * 而不是散落各处的字符串字面量。
 *
 * <h2>它【不】回答什么（诚实边界）</h2>
 * <ul>
 *   <li><b>不判 ARC-01~06 / ARC-14</b>：那 8 条需要<b>跨表读取</b>才能判定
 *       （refund / intake_profile / consent / baseline_assessment / plan /
 *         visit / cycle_assessment），属服务层，且 ARC-14 目前
 *       <b>没有库层载体</b>（{@code refund.evidence_checklist} 在库里不存在）。
 *       见 V20 文件头「待裁登记」第 1 条；</li>
 *   <li><b>不判 C.3 那 14 条与 6 键的映射是否完备</b> —— 那是待裁项，本类只落 6 键。</li>
 * </ul>
 */
public enum CaseArchiveChecklist {

    // ------------------------------------------------------------------
    // 硬门禁（5 项）：值必须为 true，否则阻断结案（403）
    // ------------------------------------------------------------------

    /** ARC-01 退款原因已记录。 */
    REASON_RECORDED("reason_recorded", "退款原因已记录", Gate.HARD_BLOCK, "ARC-01"),

    /** ARC-09 基线与当前复评已对照。 */
    BASELINE_REVIEW_COMPARED(
            "baseline_review_compared", "基线评估和当前复评已对照", Gate.HARD_BLOCK, "ARC-09"),

    /** ARC-10 挽留过程已记录（入口 A）。 */
    RETENTION_RECORDED("retention_recorded", "挽留过程已记录", Gate.HARD_BLOCK, "ARC-10"),

    /** ARC-12 最终处理结论已经负责人签字。 */
    OWNER_SIGNED("owner_signed", "最终处理结论已经负责人签字", Gate.HARD_BLOCK, "ARC-12"),

    /** ARC-13 客户档案 / 方案 / 执行 / 评估已归档。 */
    ARCHIVE_PLAN_EXEC_ARCHIVED(
            "archive_plan_exec_archived", "客户档案/方案/执行/评估已归档", Gate.HARD_BLOCK, "ARC-13"),

    // ------------------------------------------------------------------
    // 警告门禁（1 项）：缺失或 false 都【不阻断】—— 🛑 合规红线
    // ------------------------------------------------------------------

    /**
     * ARC-11 手环数据已按"参考之一"口径记录。
     *
     * <h2>🛑🛑 这一项的语义必须逐字理解，否则最容易被"顺手改成硬门禁"</h2>
     * <pre>
     *   值 = true  ⇒ 已按"参考之一"口径记录（正常）
     *   值 = false ⇒ <b>未</b>按该口径记录 ⇒ ARC-11「警告（不阻断）」
     *   键  缺席   ⇒ 视同未记录 ⇒ 同上，<b>不阻断</b>
     * </pre>
     * <p>三者都<b>不阻断结案</b>。理由链（四份权威，逐字）：
     * <ol>
     *   <li>PRD C.3 ARC-11：「手环数据未按'参考之一'口径记录 | <b>警告（不阻断）</b>」；</li>
     *   <li>PRD C.3 ARC-07：「手环：客户未佩戴 | 标'未佩戴'、<b>不阻断、不作不利依据</b>」；</li>
     *   <li>PRD P0-25：「警告规则仅记缺口 · <b>手环类缺项不得反转为阻断</b>」；</li>
     *   <li>README §5.3「三条不得触碰」③：
     *       「<b>任何"手环缺项反转为阻断"的写法一律违规</b>（P0-25 + ARC-07/08/11）」
     *       —— 并明确「展示层上线不构成对上述三条的任何豁免」。</li>
     * </ol>
     * <p>🛑 底层理由（为什么这条红线存在）：手环数据是"<b>参考之一</b>"
     * （PRD W-3 逐字：「客户端展示必须同屏带两项告知：①『手环数据为<b>参考之一</b>，
     * 不用于单方判定』」）。把它变成阻断，等于让一条<b>本就不该作为判定依据</b>的数据
     * 去阻止结案 —— 那既违反数据定位，也把门店置于"客户不戴手环就永远无法结案"的境地。
     * <p>🛑 唯一的严格点（见 V20 的 (5c)）：键<b>存在</b>时必须真的是 JSON 布尔。
     * 一个字符串 {@code "true"} 不是笔误级别的问题，而是"这件事根本没被表达成一个是/否"
     * —— 那是口径错误，<b>不是</b>手环缺口，故与"不阻断"不冲突。
     */
    HANDBAND_RECORDED_AS_REFERENCE(
            "handband_recorded_as_reference", "手环数据已按\"参考之一\"口径记录",
            Gate.WARNING, "ARC-11");

    /**
     * 门禁强度 —— 本枚举存在的<b>核心分野</b>。
     *
     * <p>🛑 刻意做成<b>两个常量 + 无第三个</b>：PRD C.3 的划分就是二元的
     * （"硬阻断" 或 "警告/不阻断"）。若将来出现第三种（例如"需总部审批后放行"），
     * 那是一次<b>业务口径变更</b>，必须先在 PRD 上写清；
     * 直接在这里加第三个常量会让它变成一处只存在于代码里的隐性规则。
     */
    public enum Gate {

        /**
         * 硬阻断（缺项或其值非 true ⇒ 阻断结案，应用层抛 403）。
         *
         * <p>口径来源：PRD C.1.7「缺项→403 阻断结案」+ P0-25
         * 「硬阻断规则缺项 → 后端返回 403 且<b>给出缺失项名称</b>」。
         */
        HARD_BLOCK,

        /**
         * 警告（缺失或 false 都<b>不阻断</b>；仅记缺口）。
         *
         * <p>口径来源：PRD C.3 的 ARC-07/08/11 逐条 +
         * P0-25「警告规则仅记缺口」+「手环类缺项不得反转为阻断」。
         */
        WARNING
    }

    private final String code;
    private final String label;
    private final Gate gate;
    private final String arcRule;

    CaseArchiveChecklist(String code, String label, Gate gate, String arcRule) {
        this.code = code;
        this.label = label;
        this.gate = gate;
        this.arcRule = arcRule;
    }

    /** 库层 / JSONB 键字面（snake_case）。 */
    public String code() {
        return code;
    }

    /** 中文标签（供审计 payload 与运维回执；断言不依赖它）。 */
    public String label() {
        return label;
    }

    /** 门禁强度 —— 🛑 决定"缺项是否阻断结案"的唯一来源。 */
    public Gate gate() {
        return gate;
    }

    /** 对应的 PRD C.3 规则号（例如 {@code ARC-12}）—— 供错误消息与审计回溯口径。 */
    public String arcRule() {
        return arcRule;
    }

    /** 是否阻断结案（= {@link Gate#HARD_BLOCK}）。 */
    public boolean blocksClosing() {
        return gate == Gate.HARD_BLOCK;
    }

    /** 全部 6 键（声明顺序 = PRD C.1.7 的列举顺序）。 */
    public static List<CaseArchiveChecklist> all() {
        return List.of(values());
    }

    /** 全部 6 键的码集（顺序稳定）。 */
    public static Set<String> allCodes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(CaseArchiveChecklist::code).toList());
    }

    /**
     * 硬门禁键集（5 项）。
     *
     * <p>🛑 本方法的存在是为了让"硬门禁集合"有一个<b>可被机械断言</b>的来源
     * —— {@code CaseArchiveGateTest} 会断言这里<b>恰好 5 项</b>且<b>不含手环项</b>，
     * 并逐项核对它与 V20 函数内 {@code v_hard_gate_keys} 数组一致。
     * <p>🛑 刻意<b>不</b>提供"把两个集合合并"的方法：那个方法一旦存在，
     * "6 项都阻断"就会变成一个顺手可写的调用，而它是违规的。
     */
    public static List<CaseArchiveChecklist> hardBlocking() {
        return Arrays.stream(values()).filter(CaseArchiveChecklist::blocksClosing).toList();
    }

    /** 警告键集（1 项：手环）。 */
    public static List<CaseArchiveChecklist> warnings() {
        return Arrays.stream(values()).filter(c -> !c.blocksClosing()).toList();
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static CaseArchiveChecklist of(String code) {
        for (CaseArchiveChecklist c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的归档清单键: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记 6 键: " + allCodes() + "；权威来源 = PRD C.1.7 + C.3）—— "
                        + "🛑 不得静默丢弃：丢弃会让一次拼写错误同时产出"
                        + "\"对应合法键被判缺失\"与\"调用方以为已填\"两个后果");
    }
}