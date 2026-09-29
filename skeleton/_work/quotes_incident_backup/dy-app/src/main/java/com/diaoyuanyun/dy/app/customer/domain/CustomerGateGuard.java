package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户准入链的<b>门禁守卫</b> —— 契约域 B 的 403 归因唯一落点。
 *
 * <h2>一、权威来源：只有两条守卫，且都是上游逐字写下的</h2>
 * <pre>
 *  data-dict §2.25 ③「跃迁守卫（T-14 / X-14 · 未满足 → 入口 API 403 + missing_items[]）」：
 *    G1  未 PROFILED（未建档）→ 不得签知情同意书      403 最小 missing_items = ["PROFILED"]
 *    G2  未 CONSENTED（未签同意书）→ 不得进基线评估   403 最小 missing_items = ["CONSENTED"]
 *  契约 B1 description 逐字：
 *    「命中禁忌（result=不通过）→ 客户状态置 REJECTED，后续 B2/B3 入口 403 GATE_MISSING
 *      （missing_items=["screening_result"]）。记录不可删除。」
 * </pre>
 *
 * <h2>🛑 为什么不把"其余跃迁前置"也做成守卫</h2>
 * data-dict §2.25 ③ 的附注逐字：「⚠️ 守卫清单<b>仅列 PRD 明文两条（G1 / G2）</b> ——
 * 其余跃迁的前置条件（如 {@code AGREEMENT_SIGNED} 前须 {@code PLAN_APPROVED}）
 * <b>PRD 未写成门禁条款</b>，本字典<b>不自行发明守卫</b>」。
 *
 * <p>这条纪律对本类有直接的工程后果：{@link GateRequirement} <b>只有三个成员</b>
 * （契约 B1 的 {@code screening_result} + data-dict 的 G1/G2），
 * <b>不得</b>因为"看起来也该有前置"而添加第四个。多一个守卫的代价不是"更严格"，
 * 而是<b>把一个合法的业务动作变成 403</b>，且那条 403 在上游文档里查无出处 ——
 * 接手的研发只能看到一句"门禁缺失"。
 *
 * <h2>🛑 两级门禁的<b>顺序不可颠倒</b>：先答"筛查过没过"，再答"建档没建档"</h2>
 * <pre>
 *   上游：screening_result（有没有一条『通过』的筛查记录）
 *   下游：PROFILED       （状态机是否已越过建档）
 * </pre>
 * B2 / B3 都<b>同时</b>受这两级约束，而它们报的缺失项不同。若先判 PROFILED，
 * 一个"筛查不通过、状态已 REJECTED"的客户会拿到 {@code ["PROFILED"]} ——
 * 排查者于是去帮他补建档，而<b>真实成因是他根本不该被建档</b>。
 * 故 {@link #assertAdmissionChain} 固定按 ①筛查 → ②建档 的顺序判，返回第一个未满足项。
 *
 * <h2>🛑 为什么"筛查通过"的判据是<b>记录存在</b>，而不是某个状态值</h2>
 * 契约 B1 的缺失项名是 {@code screening_result}（筛查<b>结果</b>），
 * 而 {@code customer_state_transition} 里并没有一个"筛查通过"的态
 * （14 态里 {@code SCREENING} 表示"筛查中/准入未完成"）。
 * 也就是说"筛查通过"这件事的唯一载体是 {@code screening_record} 里
 * <b>一条 {@code result=通过} 的行</b>。故本类的判据入参是 {@code hasPassingScreening}（布尔），
 * 由服务层查库得到 —— <b>不由状态推断</b>。
 *
 * <p>而 {@link CustomerState#REJECTED} 是"命中禁忌"的<b>副作用</b>，它<b>单独</b>也足以拒绝
 * （契约 B1 逐字点名了它）。故两个条件任一不满足即拒，且<b>报同一个缺失项名</b> ——
 * 因为在调用方看来，"没有一条通过的筛查结果"与"筛查结果是不过"是同一件事。
 */
public final class CustomerGateGuard {

    private CustomerGateGuard() {
    }

    /**
     * 门禁项 —— 每项对应上游逐字写下的一个 {@code missing_items} 字面。
     *
     * <h2>🛑 两个字面的拼写风格不同，但<b>都必须逐字照抄</b></h2>
     * <pre>
     *   SCREENING_RESULT → "screening_result"（snake_case）—— 契约 B1 逐字
     *   PROFILED         → "PROFILED"        （大写下划线）—— data-dict §2.25 ③ G1 逐字
     *   CONSENTED        → "CONSENTED"       （大写下划线）—— data-dict §2.25 ③ G2 逐字
     * </pre>
     * 把它们"统一风格"（例如全改成大写，或全改成 snake_case）会让响应体与上游文档
     * 对不上 —— 而 {@code missing_items[]} 的全部价值就在于<b>可被上游文档与前端逐一比对</b>。
     * 一个风格统一的假字面会让前端的分支匹配静默落空。
     *
     * <h2>🛑 本域只用到前两个成员；{@code CONSENTED} 属域 C 的入口</h2>
     * G2（未 CONSENTED ⇒ 不得进基线评估）的受guard端点是<b>契约域 C</b>的 baseline 评估，
     * 不在域 B 的六行里。本枚举仍然登记它，理由是"守卫表"是一个整体：
     * 只登记本域用到的两个，会让下一位实现域 C 的人<b>看不到还有一个 G2</b>，
     * 而他会按"域 C 的入口需要什么"自行发明一个 —— 那正是 data-dict 明令禁止的。
     * 登记它 + {@link #assertConsented} 提供现成落点，是抑制"各自发明"的最低成本做法。
     */
    public enum GateRequirement {

        /** 契约 B1：缺失 `screening_result`（无通过的筛查记录，或筛查结论为不通过）。 */
        SCREENING_RESULT("screening_result", "筛查结果（通过）"),

        /** data-dict §2.25 ③ G1：未建档不得签知情同意书。 */
        PROFILED("PROFILED", "已建档"),

        /** data-dict §2.25 ③ G2：未签同意书不得进基线评估（受guard端点在域 C）。 */
        CONSENTED("CONSENTED", "已签同意书");

        private final String missingItemLiteral;
        private final String label;

        GateRequirement(String missingItemLiteral, String label) {
            this.missingItemLiteral = missingItemLiteral;
            this.label = label;
        }

        /** 写进 403 响应体 {@code data.missing_items[]} 的<b>逐字</b>字面。 */
        public String missingItemLiteral() {
            return missingItemLiteral;
        }

        public String label() {
            return label;
        }
    }

    // ==================================================================
    // 一、"已建档"的状态集合（逐项列举，不用 ordinal 比较）
    // ==================================================================

    /**
     * 视为"已越过建档（PROFILED）"的状态集合。
     *
     * <h2>🛑 为什么是显式集合，而不是 {@code current.ordinal() >= PROFILED.ordinal()}</h2>
     * 14 态<b>不是线性的</b>：存在 {@code CYCLE_ASSESS ↔ IN_TREATMENT} 的双向、
     * {@code PLAN_REVISING → PLAN_APPROVED} 的回环、{@code REFUND_REVIEW → TERMINATED} 的短路。
     * 用一个序号比较去表达"越过了某态"，等于<b>假定这些态排成一条直线</b> ——
     * 而这个假定今天恰好成立（ordinal 顺序照抄 V2 的 CHECK 书写序），
     * 明天有人调整枚举书写顺序就会静默失效（编译不报错、测试若只测相邻两态也不报错）。
     *
     * <h2>集合的构成依据（可直接数出来）</h2>
     * <pre>
     *   14 态 − { SCREENING, REJECTED } = 12 态
     *   SCREENING：data-dict §2.25 ② CREATED 的说明逐字「已建档键、<b>准入未完成</b>」⇒ 未越过
     *   REJECTED ：data-dict ①#2 逐字「终态（<b>不建档</b>）」              ⇒ 未越过
     *   其余 12 态：均已在建档之后（PROFILED 之后或其在服务期内的聚合）
     * </pre>
     * 该集合与 data-dict §2.25 ② 的 5 值映射自洽：{@code PROFILED} + {@code CONSENTED} 聚合的
     * 9 态 + {@code ARCHIVED} 聚合的 2 态 = 12 态，恰为"已越过建档"的全部。
     */
    private static final Set<CustomerState> AT_OR_AFTER_PROFILED = EnumSet.of(
            CustomerState.PROFILED,
            CustomerState.CONSENTED,
            CustomerState.ASSESS_BASE,
            CustomerState.PLAN_APPROVED,
            CustomerState.AGREEMENT_SIGNED,
            CustomerState.CONFIRMED,
            CustomerState.IN_TREATMENT,
            CustomerState.CYCLE_ASSESS,
            CustomerState.PLAN_REVISING,
            CustomerState.REFUND_REVIEW,
            CustomerState.CLOSED,
            CustomerState.TERMINATED);

    /**
     * 视为"已越过 CONSENTED"的状态集合（G2 用；受guard端点在域 C）。
     *
     * <p>构成依据同 {@link #AT_OR_AFTER_PROFILED}，再减去 {@code PROFILED} 本身
     * （它明确<b>未</b>签同意书）。
     */
    private static final Set<CustomerState> AT_OR_AFTER_CONSENTED = EnumSet.of(
            CustomerState.CONSENTED,
            CustomerState.ASSESS_BASE,
            CustomerState.PLAN_APPROVED,
            CustomerState.AGREEMENT_SIGNED,
            CustomerState.CONFIRMED,
            CustomerState.IN_TREATMENT,
            CustomerState.CYCLE_ASSESS,
            CustomerState.PLAN_REVISING,
            CustomerState.REFUND_REVIEW,
            CustomerState.CLOSED,
            CustomerState.TERMINATED);

    /** 该状态是否已越过建档。 */
    public static boolean hasProfiled(CustomerState state) {
        return state != null && AT_OR_AFTER_PROFILED.contains(state);
    }

    /** 该状态是否已越过签同意书。 */
    public static boolean hasConsented(CustomerState state) {
        return state != null && AT_OR_AFTER_CONSENTED.contains(state);
    }

    /** 该状态是否被筛查拒绝（{@code REJECTED} 终态）。 */
    public static boolean isScreenRejected(CustomerState state) {
        return state == CustomerState.REJECTED;
    }

    // ==================================================================
    // 二、准入链守卫（B2 / B3 的唯一入口）
    // ==================================================================

    /**
     * 准入链守卫 —— 固定顺序 ① 筛查结果 → ② 已建档；返回<b>第一个</b>未满足项并抛 403。
     *
     * <p>顺序依据见类注释（先答筛查、再答建档）。返回类型刻意是 {@code void} 而非
     * 未满足项：调用方通过它只应得到一个语义 —— "这个动作现在不被允许、缺什么已写在 403 里"。
     *
     * <h2>🔴🔴 2026-09-25（S2-10 真请求 E2E 抓出）—— 本方法是【自环陷阱】，当前<b>零调用点</b></h2>
     * 本方法把 {@link #assertProfiled} 也纳入进来，而 {@code PROFILED}（已建档）
     * <b>正是 B2 建档端点的产出</b>。故任何"用本方法作 B2 前置门禁"的写法都会得到：
     * <pre>
     *   客户有通过筛查 → ① assertScreeningResult 过 → ② assertProfiled 说"尚未建档" ⇒ 403
     *   客户无通过筛查 → ① 403（正确）
     * </pre>
     * 即 <b>B2 对这个客户的每一步都 403、端点 100% 不可用</b>，且报出的缺失项名
     * （{@code PROFILED}）与真实成因<b>完全相反</b>：排查者会去"补建档"，
     * 而正确动作是"这次调用本来就该成功"。
     *
     * <p>这是本仓库第七次同型复发，且形态比前六次（都是权限码）更严重 ——
     * 前六次是"角色拿不到权限"，这次是<b>门禁逻辑本身自相矛盾</b>。
     * 它躲过全部单测的原因是：{@code CustomerGateGuardTest} 逐条断言两个守卫各自的行为，
     * 而"这两个守卫被<b>组合</b>到一个端点上是自环的"这件事，只有<b>在端点上真发一次请求</b>
     * 才会暴露（{@code DomainBEndpointsE2ETest}）。
     *
     * <p>🛑 故本方法<b>保留</b>但标注为陷阱，不删除：
     * <ul>
     *   <li>删除它会让"两个守卫的顺序纪律"那段推理失去载体，下一个人可能重新写一个等价物；</li>
     *   <li>保留 + 此处逐字写明成因，使"组合守卫"这件事必须显式经过本段阅读；
     *       {@code DomainBEndpointsE2ETest} 的 B2 用例是那次阅读的机械保障。</li>
     * </ul>
     * 🔑 正确用法：<b>逐级调用</b> —— B2 用 {@link #assertScreeningResult}（契约 B2 只要求
     * {@code screening_result=通过}）；B3 用 {@link #assertProfiled}（G1 的正确受 guard 端点）。
     * 两级<b>不得</b>组合施加于同一个端点，否则必然有一个守卫指向该端点自己的产出。
     *
     * @deprecated 🔴 自环陷阱（守卫挂在其产出端点上）。改为按端点<b>逐级</b>调用
     *         {@link #assertScreeningResult} 或 {@link #assertProfiled}。当前零调用点，
     *         保留仅为登记该组合为何错。
     */
    @Deprecated
    public static void assertAdmissionChain(String ref,
                                           boolean hasPassingScreening,
                                           CustomerState currentState) {
        assertScreeningResult(ref, hasPassingScreening, currentState);
        assertProfiled(ref, currentState);
    }

    /**
     * ① 筛查结果守卫（契约 B1 逐字）。
     *
     * <p>两个条件任一不满足即拒，<b>报同一个缺失项名</b> {@code screening_result}：
     * <pre>
     *   无通过的筛查记录（含"还没做筛查"）  → 缺 screening_result
     *   当前态 = REJECTED（筛查结论不通过） → 缺 screening_result（契约 B1 逐字点名）
     * </pre>
     * 报同一项是刻意的：对调用方而言这是同一件事 —— <b>该客户没有一份通过的筛查结果</b>。
     * 分成两个字面会让前端多一条永远走不到的分支，而修复动作也完全相同（重新筛查）。
     *
     * @throws GateMissingException {@code missing_items = ["screening_result"]}
     */
    public static void assertScreeningResult(String ref,
                                             boolean hasPassingScreening,
                                             CustomerState currentState) {
        if (isScreenRejected(currentState)) {
            throw gate(ref, GateRequirement.SCREENING_RESULT,
                    "客户当前处于 REJECTED 终态（禁忌筛查结论为不通过）—— "
                            + "契约 B1 逐字点名该情形：『命中禁忌（result=不通过）→ 客户状态置 REJECTED，"
                            + "后续 B2/B3 入口 403 GATE_MISSING（missing_items=[\\\"screening_result\\\"]）』。"
                            + "🛑 本拒绝<b>不可</b>通过补建档绕过：REJECTED 是不可逆终态（data-dict ①#2），"
                            + "且筛查记录<b>不可删除</b>（契约 B1：『记录不可删除』）");
        }
        if (!hasPassingScreening) {
            throw gate(ref, GateRequirement.SCREENING_RESULT,
                    "租户内不存在该客户 {@code result=通过} 的筛查记录 —— "
                            + "契约 B2 逐字『需 screening_result=通过』；"
                            + "缺失项名取契约 B1 的逐字字面 screening_result。"
                            + "🛑 判据是【记录存在】而非某个状态值：14 态里没有『筛查通过』态"
                            + "（SCREENING 表示准入未完成），故该事实的唯一载体是 screening_record 里的一行");
        }
    }

    /**
     * ② 已建档守卫（data-dict §2.25 ③ G1 逐字）。
     *
     * @throws GateMissingException {@code missing_items = ["PROFILED"]}
     */
    public static void assertProfiled(String ref, CustomerState currentState) {
        if (!hasProfiled(currentState)) {
            throw gate(ref, GateRequirement.PROFILED,
                    "客户尚未建档（data-dict §2.25 ③ 守卫 G1：『未 PROFILED（未建档）→ 不得签知情同意书』；"
                            + "PRD v1.6 准入顺序为『禁忌筛查 → 建档 → 签知情同意书』）。"
                            + "当前态 = " + describeState(currentState)
                            + " —— 🛑 缺失项名逐字取 G1 的 [" + GateRequirement.PROFILED.missingItemLiteral()
                            + "]（大写下划线），不得写成建档动作名或中文");
        }
    }

    /**
     * ③ 已签同意书守卫（data-dict §2.25 ③ G2 逐字）。
     *
     * <p>🛑 <b>域 B 的六行端点里没有它的受guard端</b> —— 它属于契约域 C 的基线评估入口。
     * 本方法在此登记，是为了让域 C 的实现者<b>不必自行发明</b>（见 {@link GateRequirement#CONSENTED}）。
     *
     * @throws GateMissingException {@code missing_items = ["CONSENTED"]}
     */
    public static void assertConsented(String ref, CustomerState currentState) {
        if (!hasConsented(currentState)) {
            throw gate(ref, GateRequirement.CONSENTED,
                    "客户尚未签署知情同意书（data-dict §2.25 ③ 守卫 G2："
                            + "『未 CONSENTED（未签同意书）→ 不得进基线评估』）。"
                            + "当前态 = " + describeState(currentState)
                            + " —— 🛑 本守卫的受guard端点在契约域 C（baseline 评估），"
                            + "域 B 六行不含它；此处登记以避免域 C 自行发明一条守卫");
        }
    }

    // ==================================================================
    // 三、不可逆终态断言（B1 的 REJECTED 语义、以及终态不可复用）
    // ==================================================================

    /**
     * 断言客户当前状态<b>不是任何终态</b> —— 供"会被终态短路"的写入动作前置调用。
     *
     * <p>三个终态：{@code REJECTED}（不建档，不可逆）/ {@code CLOSED}（服务结束）/
     * {@code TERMINATED}（服务终止）。它们各自的成因不同，但共同性质是
     * <b>不得再由造成终结的那条链继续写入</b>。
     */
    public static void assertNotTerminal(String ref, CustomerState currentState) {
        if (currentState != null && currentState.isTerminal()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    ref + ": 客户处于不可逆终态 " + currentState.code()
                            + "（" + currentState.label() + "），不得再写入本链。"
                            + "🛑 三个终态按 data-dict §2.25 ① 逐项确定："
                            + "REJECTED（不建档）/ CLOSED（已核销 ≥ 方案总次数）/ TERMINATED（双不达标或挽留失败）。"
                            + "🛑 此处报 5001 而非 403：它不是『前置门禁缺失』（契约 §2.0 forbidden-403 覆盖的两类之一）"
                            + "而是『对一个已终结的客户发起了不适用的动作』——属业务规则违反");
        }
    }

    // ==================================================================
    // 四、辅助
    // ==================================================================

    /**
     * 构造 403 {@code GATE_MISSING}。
     *
     * <p>🛑 使用 {@link GateMissingException} 而不是通用 {@code BizException}：
     * 前者会把 {@code missing_items} 带进响应体（契约 §2.0 {@code forbidden-403} 逐字：
     * 「message 必须给出缺失项名称 / 档位名称（<b>不得模糊报错</b>）」）。
     * 用通用异常会让这一条契约要求落空 —— 而落空的表现是"前端拿不到该项、只能显示一句无权"。
     */
    private static GateMissingException gate(String ref, GateRequirement req, String reason) {
        return new GateMissingException(List.of(req.missingItemLiteral()),
                "[" + ref + "] " + reason);
    }

    /** 状态的可读描述（含 null 情形；诊断消息用）。 */
    private static String describeState(CustomerState state) {
        if (state == null) {
            return "<未知/尚未建立状态机记录>";
        }
        return state.code() + "（" + state.label() + "）";
    }

    /**
     * 全部缺失项字面（供测试逐项比对"与上游文档一致"）。
     *
     * <p>顺序 = {@link GateRequirement} 的声明序，便于断言时按固定顺序比较。
     */
    public static Set<String> allMissingItemLiterals() {
        Set<String> out = new LinkedHashSet<>();
        for (GateRequirement r : GateRequirement.values()) {
            out.add(r.missingItemLiteral());
        }
        return out;
    }
}