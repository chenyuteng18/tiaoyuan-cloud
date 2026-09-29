package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 客户服务主状态机的<b>可达性</b>与<b>闭合判据</b> —— 逐行照抄 data-dict §2.25 ① 的「上游 → 下游」列。
 *
 * <h2>🛑 本类<b>不是</b>门禁守卫，两者的错误码刻意不同</h2>
 * <pre>
 *   门禁守卫（{@link CustomerGateGuard}）  = 上游明文写成条款的前置缺失 ⇒ 403 GATE_MISSING
 *   本类（可达性校验）                     = "有人把状态写到了一个不该到的态" ⇒ 5001
 * </pre>
 * 混淆两者的后果很具体：把"跃迁表里没有这条边"报成 403，会让一个<b>代码缺陷</b>
 * 表现为一次"门禁拒绝"，于是排查方向被引到"是不是缺了什么前置"，
 * 而真相是"某处代码把 {@code to_state} 写错了"。故 {@link #assertTransitionAllowed}
 * 报 {@code BUSINESS_RULE_VIOLATED(5001)}（口径断裂），与 403 严格分开。
 *
 * <h2>二、跃迁表逐行来源（data-dict §2.25 ① 的"PRD §7.2 上游 → 下游"列）</h2>
 * <pre>
 *   SCREENING       → PROFILED（通过）/ REJECTED（有禁忌）
 *   REJECTED        → （无）—— 终态、不可逆
 *   PROFILED        → CONSENTED
 *   CONSENTED       → ASSESS_BASE
 *   ASSESS_BASE     → PLAN_APPROVED
 *   PLAN_APPROVED   → AGREEMENT_SIGNED
 *   AGREEMENT_SIGNED→ CONFIRMED
 *   CONFIRMED       → IN_TREATMENT
 *   IN_TREATMENT    ↔ CYCLE_ASSESS / REFUND_REVIEW
 *   CYCLE_ASSESS    → IN_TREATMENT / PLAN_REVISING / REFUND_REVIEW
 *   PLAN_REVISING   → PLAN_APPROVED（须回炉审核 + 重签）
 *   REFUND_REVIEW   → IN_TREATMENT（挽留成功）/ TERMINATED
 *   CLOSED          → （无）—— 终态
 *   TERMINATED      → （无）—— 终态
 * </pre>
 *
 * <h2>🛑 三处"上游未定义"的部分，本类一律<b>不发明</b>，且都留下可查的登记</h2>
 * <ol>
 *   <li><b>{@code CLOSED} 的<b>入边</b>未逐项列出</b> —— data-dict ①#13 只给了
 *       <b>触发条件</b>（"已核销次数 ≥ {@code plan.planned_sessions}"），
 *       而 {@code IN_TREATMENT} / {@code CYCLE_ASSESS} 两行的下游箭头里<b>都没有 CLOSED</b>。
 *       故本类的跃迁表里 {@code CLOSED} 的入边<b>为空</b>（与两个终态一致），
 *       这只表达"上游没写这条边"，<b>不</b>表达"业务上不可能关闭"。
 *       🛑 这是一处真实缺口：若不登记，下一位实现"闭合动作"的人会自行补一条边，
 *       而那条边在上游文档里查无出处。故 {@link #closedInboundEdgesUndefined()}
 *       以可断言的形式把它写成事实。</li>
 *   <li><b>{@code trigger_event} 的取值集</b> —— V2 注释逐字：§2.25 字段表指向的
 *       "下表 ③"其实是<b>跃迁守卫表</b>而非取值表，该 CHECK 引用<b>悬空</b>，
 *       权威件未逐项枚举。故本类（及 {@link StateTransitionDraft}）<b>只落 NOT NULL</b>，
 *       把 {@code trigger_event} 当自由文本，<b>不建枚举</b>。</li>
 *   <li><b>{@code CLOSED} 触发条件的口径性质</b> —— PRD §7.2 自标"推荐默认值，
 *       需业务确认"；技术侧处置（data-dict §2.25 ① 附注）是
 *       "触发判据<b>不写进 DDL 硬约束</b>，由服务层守卫表达"。
 *       故判据落在 {@link #isClosedTriggeredBySessionCount}，并在消息里标明
 *       <b>"临时口径 · 待业务确认"</b>（该标注是附注的明文要求）。</li>
 * </ol>
 *
 * <h2>四、首条跃迁</h2>
 * data-dict §2.25 字段表：{@code from_state} 可空，"建档首条为 NULL"。
 * 首条的 {@code to_state} 即入口态 —— 依据 ①#1 的"入口"字样确定为
 * {@link CustomerState#SCREENING}。见 {@link #entryState()}。
 */
public final class CustomerStateMachine {

    private CustomerStateMachine() {
    }

    /**
     * 跃迁表 —— 逐行照抄 data-dict §2.25 ①，一行不多、一行不少。
     *
     * <p>三个终态（{@code REJECTED} / {@code CLOSED} / {@code TERMINATED}）的出边为空集合。
     * {@code CLOSED} 的入边亦为空 —— 见类注释第 ① 条（上游未定义，本类不发明）。
     */
    private static final Map<CustomerState, Set<CustomerState>> ALLOWED_TRANSITIONS = buildTransitions();

    private static Map<CustomerState, Set<CustomerState>> buildTransitions() {
        Map<CustomerState, Set<CustomerState>> m = new EnumMap<>(CustomerState.class);
        m.put(CustomerState.SCREENING, EnumSet.of(CustomerState.PROFILED, CustomerState.REJECTED));
        m.put(CustomerState.REJECTED, EnumSet.noneOf(CustomerState.class));
        m.put(CustomerState.PROFILED, EnumSet.of(CustomerState.CONSENTED));
        m.put(CustomerState.CONSENTED, EnumSet.of(CustomerState.ASSESS_BASE));
        m.put(CustomerState.ASSESS_BASE, EnumSet.of(CustomerState.PLAN_APPROVED));
        m.put(CustomerState.PLAN_APPROVED, EnumSet.of(CustomerState.AGREEMENT_SIGNED));
        m.put(CustomerState.AGREEMENT_SIGNED, EnumSet.of(CustomerState.CONFIRMED));
        m.put(CustomerState.CONFIRMED, EnumSet.of(CustomerState.IN_TREATMENT));
        m.put(CustomerState.IN_TREATMENT,
                EnumSet.of(CustomerState.CYCLE_ASSESS, CustomerState.REFUND_REVIEW));
        m.put(CustomerState.CYCLE_ASSESS,
                EnumSet.of(CustomerState.IN_TREATMENT, CustomerState.PLAN_REVISING,
                        CustomerState.REFUND_REVIEW));
        m.put(CustomerState.PLAN_REVISING, EnumSet.of(CustomerState.PLAN_APPROVED));
        m.put(CustomerState.REFUND_REVIEW,
                EnumSet.of(CustomerState.IN_TREATMENT, CustomerState.TERMINATED));
        // ⚠️ 入边未定义（上游 ①#13 只给触发条件，两处上游态的下游箭头里都没有它）
        m.put(CustomerState.CLOSED, EnumSet.noneOf(CustomerState.class));
        m.put(CustomerState.TERMINATED, EnumSet.noneOf(CustomerState.class));
        return Map.copyOf(m);
    }

    static {
        assertTableIsTotal();
    }

    /** 机械保证：14 态在跃迁表里<b>都有条目</b>（哪怕出边为空）。 */
    private static void assertTableIsTotal() {
        StringBuilder missing = new StringBuilder();
        for (CustomerState s : CustomerState.values()) {
            if (!ALLOWED_TRANSITIONS.containsKey(s)) {
                missing.append(missing.isEmpty() ? "" : "、").append(s.code());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "跃迁表不完整，缺少状态: " + missing + "。"
                            + "🛑 全新状态若无条目，{@link #isAllowedTransition} 会把它的【每一条】出边判为非法 —— "
                            + "而那是静默的：表现为『某个新状态的跃迁动作永远 5001』，"
                            + "排查者看到的是『业务规则违反』，不是『跃迁表漏登记』。"
                            + "权威来源 = data-dict §2.25 ① 的『上游 → 下游』列");
        }
    }

    /** 入口态（首条跃迁的 {@code to_state}，{@code from_state} 为 NULL）。 */
    public static CustomerState entryState() {
        return CustomerState.SCREENING;
    }

    /** 某状态的全部合法后继（上游 ① 表逐行；终态为空集）。 */
    public static Set<CustomerState> allowedNextStates(CustomerState from) {
        if (from == null) {
            // 首条跃迁：唯一合法目标是入口态
            return EnumSet.of(entryState());
        }
        Set<CustomerState> next = ALLOWED_TRANSITIONS.get(from);
        return next == null ? Set.of() : Set.copyOf(next);
    }

    /**
     * 该跃迁是否在上游 ① 表里存在这条边。
     *
     * <p>{@code from == null} 表示首条跃迁 ⇒ 仅 {@code to == SCREENING} 合法。
     */
    public static boolean isAllowedTransition(CustomerState from, CustomerState to) {
        if (to == null) {
            return false;
        }
        return allowedNextStates(from).contains(to);
    }

    /**
     * 断言这条跃迁存在（否则报 {@code 5001}）。
     *
     * <p>🛑 报 5001 而不是 403，理由见类注释首段。本方法用于<b>写入路径</b>：
     * 一次跃迁被写进 {@code customer_state_transition} 之前，先确认这条边是上游写下的。
     *
     * <p>⚠️ 本方法<b>不</b>替代门禁：门禁回答"缺什么前置"（403），
     * 本方法回答"这条边存不存在"（5001 口径断裂）。两者不可互相替代。
     */
    public static void assertTransitionAllowed(String ref, CustomerState from, CustomerState to) {
        if (!isAllowedTransition(from, to)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    ref + ": 非法状态跃迁 " + (from == null ? "<首条>" : from.code())
                            + " → " + (to == null ? "<null>" : to.code())
                            + "。上游合法后继 = " + codesOf(allowedNextStates(from))
                            + "（权威来源 = data-dict §2.25 ① 的『上游 → 下游』列）。"
                            + "🛑 本错误报 5001 而非 403：它不是『前置门禁缺失』而是『有人把状态"
                            + "写到了一个不该到的态』—— 若报 403，排查会被引向『是不是缺了什么前置』，"
                            + "而真相是某处代码把 to_state 写错了。"
                            + "🛑 若这条边在业务上确实应当存在，正确的动作是【回写 data-dict §2.25 ①】"
                            + "并走评审，而不是在这里放宽断言");
        }
    }

    /**
     * {@code CLOSED} 的闭合判据（PRD §7.2 推荐默认值）。
     *
     * <h2>🛑 该判据当前为<b>临时口径 · 待业务确认</b>，此标注是强制的</h2>
     * PRD §7.2 对"已核销次数 ≥ 方案总次数"自标"推荐默认值，<b>需业务确认</b>"；
     * data-dict §2.25 ① 附注逐字要求："业务方确认前，该触发条件为<b>临时口径</b>，
     * <b>须在交付物与接口文档中显式标注『临时口径 · 待业务确认』</b>"。
     * 故本方法的诊断消息<b>必须</b>带上该标注 —— 见 {@link #CLOSED_TRIGGER_CAVEAT}。
     *
     * <h2>为什么把它放在服务层可调用的纯函数里，而不是写进 DDL</h2>
     * data-dict 附注的处置逐字："{@code CLOSED} 的<b>触发判据不写进 DDL 硬约束</b>，
     * 而由<b>服务层守卫</b>表达 —— 以便业务方确认后<b>改守卫不动表结构</b>"。
     * 故本方法是那条守卫的判据落点，DDL 里只有 14 态取值 CHECK。
     *
     * @param redeemedSessions 已核销次数（Σ 已核销；由服务层取数）
     * @param plannedSessions  {@code plan.planned_sessions}（方案总次数）
     * @return 是否应闭合
     */
    public static boolean isClosedTriggeredBySessionCount(int redeemedSessions, int plannedSessions) {
        if (plannedSessions <= 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "方案总次数非正（" + plannedSessions + "），无法判定 CLOSED —— "
                            + "占位闸门：本判据的分母是 plan.planned_sessions，"
                            + "一个非正的分母会让『已核销 ≥ 总次数』对任何客户恒真，"
                            + "即把所有在服务客户误判为可关闭");
        }
        return redeemedSessions >= plannedSessions;
    }

    /** {@code CLOSED} 触发条件必须随附的口径标注（data-dict §2.25 ① 附注明文要求）。 */
    public static final String CLOSED_TRIGGER_CAVEAT = "临时口径 · 待业务确认";

    /**
     * {@code CLOSED} 的入边在上游<b>未被定义</b> —— 以可断言的事实形式登记该缺口。
     *
     * <p>返回值恒为空集。它存在的意义是：让"本类没有为 {@code CLOSED} 定义入边"
     * 成为一条<b>可被测试读到的声明</b>，而不是一句埋在注释里、只有读源码才知道的话。
     * 若哪天有人补上了入边，这条断言会红 —— 而那一刻正需要有人回查上游是否已定义。
     */
    public static Set<CustomerState> closedInboundEdgesUndefined() {
        Set<CustomerState> inbound = new TreeSet<>();
        for (Map.Entry<CustomerState, Set<CustomerState>> e : ALLOWED_TRANSITIONS.entrySet()) {
            if (e.getValue().contains(CustomerState.CLOSED)) {
                inbound.add(e.getKey());
            }
        }
        // 实测应为空 —— 若不为空，说明有人给 CLOSED 加了入边
        return inbound;
    }

    /** 只读跃迁表（供测试逐行比对"与 data-dict §2.25 ① 一致"）。 */
    public static Map<CustomerState, Set<CustomerState>> transitions() {
        return ALLOWED_TRANSITIONS;
    }

    /** 全 14 态的"状态 → 合法后继"摘要（诊断 / 自描述用；顺序固定）。 */
    public static Map<String, Set<String>> describeTransitions() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (CustomerState s : CustomerState.all()) {
            out.put(s.code(), codesOf(allowedNextStates(s)));
        }
        return out;
    }

    private static Set<String> codesOf(Set<CustomerState> states) {
        Set<String> out = new TreeSet<>();
        for (CustomerState s : states) {
            out.add(s.code());
        }
        return out;
    }
}