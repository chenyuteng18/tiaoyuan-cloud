package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;

/**
 * 客户<b>服务主状态机</b>的 14 个状态 —— 逐字对齐 V2 迁移与 data-dict §2.25 ①。
 *
 * <h2>权威来源（三处一致，本枚举逐项照抄）</h2>
 * <pre>
 *  ① V2__b_entities_state_machine_and_band.sql L62~L69：
 *     from_state / to_state 的 CHECK 均逐项列出这 14 个值
 *  ② data-dict §2.25 ① 状态枚举（14 值 · 逐项落库，直接取自 PRD §7.2）
 *  ③ PRD §7.2 客户服务主状态机（14 态：11 活跃 + 3 终态）
 * </pre>
 *
 * <h2>🛑 14 态是唯一权威，5 值 {@code customer.status} 是<b>派生聚合</b></h2>
 * data-dict §2.25 ② 映射纪律逐字：「<b>14 态是唯一权威、5 值是派生聚合</b>；
 * {@code customer.status} 由状态机实体<b>推导刷新</b>（在跃迁同事务内更新），
 * <b>不得反向由 5 值推断 14 态</b>」。故本枚举是"态"的唯一落点，
 * {@link CustomerStatus} 只是它的<b>下游投影</b>（见 {@link CustomerStatus#of(CustomerState)}）。
 *
 * <h2>🛑 三个终态：REJECTED / CLOSED / TERMINATED</h2>
 * <pre>
 *   REJECTED    —— 禁忌筛查命中，<b>不建档</b>，不可逆（V2 注释 / data-dict ①#2）
 *   CLOSED      —— 已核销次数 ≥ plan.planned_sessions（data-dict ①#13）
 *   TERMINATED  —— 首周期双不达标 或 挽留失败（data-dict ①#14）
 * </pre>
 * <p>三者同属终态，但<b>只有后两者</b>归入 {@code ARCHIVED}；{@code REJECTED}
 * 有自己的独立 5 值（data-dict §2.25 ② 映射表）—— 这一条必须逐字照着实现，
 * 因为"把 REJECTED 也归 ARCHIVED"是个看起来更整齐、却在语义上错误的改法：
 * REJECTED 是<b>从未进入服务</b>，ARCHIVED 是<b>服务已结束</b>。
 *
 * <h2>⚠️ CLOSED 的触发条件属"临时口径"</h2>
 * data-dict §2.25 ① 附注逐字：{@code CLOSED} 的触发条件（"已核销 ≥ 方案总次数"）
 * 在 PRD §7.2 自标"推荐默认值，<b>需业务确认</b>"。技术侧处置是：
 * <b>触发判据不写进 DDL 硬约束，由服务层守卫表达</b>，以便业务确认后改守卫不动表结构。
 * 本枚举只落<b>态本身</b>，不落触发判据 —— 触发判据的落点见
 * {@link CustomerStateMachine#nextStateOnCycleClosed}。
 */
public enum CustomerState {

    /** ① 禁忌筛查中（入口态）。通过 → PROFILED；有禁忌 → REJECTED。 */
    SCREENING("SCREENING", "禁忌筛查", false),

    /** ② 已拒绝（<b>终态</b>，不建档，不可逆）。 */
    REJECTED("REJECTED", "禁忌筛查不通过", true),

    /** ③ 已建档。 */
    PROFILED("PROFILED", "已建档", false),

    /** ④ 已签知情同意书。 */
    CONSENTED("CONSENTED", "已签同意书", false),

    /** ⑤ 基线评估完成。 */
    ASSESS_BASE("ASSESS_BASE", "基线评估完成", false),

    /** ⑥ 方案已审核。 */
    PLAN_APPROVED("PLAN_APPROVED", "方案已审核", false),

    /** ⑦ 协议已签署。 */
    AGREEMENT_SIGNED("AGREEMENT_SIGNED", "协议已签署", false),

    /** ⑧ 已确认（开始服务前）。 */
    CONFIRMED("CONFIRMED", "已确认", false),

    /** ⑨ 服务中。 */
    IN_TREATMENT("IN_TREATMENT", "服务中", false),

    /** ⑩ 周期评估中。 */
    CYCLE_ASSESS("CYCLE_ASSESS", "周期评估", false),

    /** ⑪ 方案修订中（须回炉审核 + 重签后回 PLAN_APPROVED）。 */
    PLAN_REVISING("PLAN_REVISING", "方案修订中", false),

    /** ⑫ 退款复核中（由门店代录触发；挽留成功 → IN_TREATMENT，失败 → TERMINATED）。 */
    REFUND_REVIEW("REFUND_REVIEW", "退款复核", false),

    /** ⑬ 已关闭（<b>终态</b>）。 */
    CLOSED("CLOSED", "已关闭", true),

    /** ⑭ 已终止（<b>终态</b>）。 */
    TERMINATED("TERMINATED", "已终止", true);

    private final String code;
    private final String label;
    private final boolean terminal;

    CustomerState(String code, String label, boolean terminal) {
        this.code = code;
        this.label = label;
        this.terminal = terminal;
    }

    /** 库层字面（大写下划线，与 V2 的 CHECK 逐字一致）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 是否终态（REJECTED / CLOSED / TERMINATED）。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 全部 14 态（V2 / data-dict 固定顺序）。 */
    public static List<CustomerState> all() {
        return List.of(values());
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static CustomerState of(String code) {
        for (CustomerState s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的客户状态字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记 14 态见 V2 customer_state_transition 的 CHECK / data-dict §2.25 ①）—— "
                        + "🛑 不得回落为任一态：状态决定门禁与可做的动作，静默猜一个会让"
                        + "『该不该 403』失去依据");
    }

    /** 宽容解析：未登记返回 {@code null}（供"这一行是不是历史脏数据"的诊断场景）。 */
    public static CustomerState tryOf(String code) {
        for (CustomerState s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }
}