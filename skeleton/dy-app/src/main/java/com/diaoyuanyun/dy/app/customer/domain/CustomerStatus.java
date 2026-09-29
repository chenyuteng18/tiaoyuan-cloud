package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code customer.status} 的 <b>5 值粗粒度派生聚合态</b> —— 逐字对齐 data-dict §2.25 ② 与契约 B2 出参。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  data-dict §2.6 / §2.25 ②：CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED（仅 5 值）
 *  契约 CustomerCreateData.status：enum [CREATED, PROFILED, CONSENTED, REJECTED, ARCHIVED]
 *  契约该字段 description 逐字：「5 值粗粒度派生聚合态，不是服务主状态机（14 态见
 *                                customer_state_transition）」
 * </pre>
 *
 * <h2>🛑 映射表逐字来自 data-dict §2.25 ②（不是我推断的）</h2>
 * <pre>
 *   CREATED   ↔ SCREENING                                        （已建档键、准入未完成。）
 *   PROFILED  ↔ PROFILED                                         （名称同义、一一对应）
 *   CONSENTED ↔ CONSENTED / ASSESS_BASE / PLAN_APPROVED /
 *               AGREEMENT_SIGNED / CONFIRMED / IN_TREATMENT /
 *               CYCLE_ASSESS / PLAN_REVISING / REFUND_REVIEW     （⚠️ 一对多：服务期内
 *                                                                 所有活跃态均归 CONSENTED）
 *   REJECTED  ↔ REJECTED                                         （名称同义、终态）
 *   ARCHIVED  ↔ CLOSED / TERMINATED                              （两个终态均归 ARCHIVED）
 * </pre>
 * <p>🛑 <b>本类刻意不提供反向映射</b>（5 值 → 14 态）。data-dict 映射纪律逐字：
 * 「14 态是唯一权威、5 值是派生聚合……<b>不得反向由 5 值推断 14 态</b>」。
 * 反向映射在数学上也不可能（{@code CONSENTED} 一个值对应 9 个态、
 * {@code ARCHIVED} 对应 2 个态），提供它只会诱使调用方从 5 值反推 —— 而那必然错。
 *
 * <h2>🛑 为什么必须"由 14 态推导"，而不是"各自独立维护"</h2>
 * 两个字段独立维护 ⇒ 它们必然在某次跃迁后不一致，而不一致的那一侧（5 值）
 * 正是<b>下发给出参</b>的那个。故本类的唯一入口是 {@link #of(CustomerState)}，
 * 它把"5 值从哪来"这件事收敛成一跳。
 */
public enum CustomerStatus {

    /** 已建档键、准入未完成。对应 {@link CustomerState#SCREENING}。 */
    CREATED("CREATED", "已建键·准入未完成"),

    /** 名称同义、一一对应 {@link CustomerState#PROFILED}。 */
    PROFILED("PROFILED", "已建档"),

    /**
     * ⚠️ <b>一对多聚合</b>：服务期内所有活跃态均归此值。
     *
     * <p>对应 9 个态（data-dict §2.25 ② 逐字），<b>不含</b> {@code SCREENING}
     * 与 {@code PROFILED}（各有自己的 5 值），<b>不含</b>三个终态。
     */
    CONSENTED("CONSENTED", "服务期内（聚合）"),

    /** 名称同义、终态。对应 {@link CustomerState#REJECTED}。 */
    REJECTED("REJECTED", "筛查不通过"),

    /** 两个终态（{@code CLOSED} / {@code TERMINATED}）均归此值。 */
    ARCHIVED("ARCHIVED", "已归档（终态聚合）");

    private final String code;
    private final String label;

    CustomerStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 库层 / 契约字面（大写）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 全部 5 值（data-dict 固定顺序）。 */
    public static List<CustomerStatus> all() {
        return List.of(values());
    }

    // ==================================================================
    // 唯一映射落点：14 态 → 5 值（data-dict §2.25 ② 逐项照抄）
    // ==================================================================

    /**
     * 状态 → 5 值的映射表。<b>逐项照抄 data-dict §2.25 ②</b>，一项不多、一项不少。
     *
     * <p>用 {@link EnumMap} 而非 switch：<b>14 个键必须齐全</b>这一点由
     * {@link #assertMappingIsTotal()} 在类初始化期机械保证 ——
     * 若哪天有人新增了第 15 个态却忘了在此登记，应用会在<b>启动期</b>就失败，
     * 而不是等到某次跃迁发生时把 5 值算成一个 {@code null}。
     */
    private static final Map<CustomerState, CustomerStatus> STATE_TO_STATUS = buildMapping();

    private static Map<CustomerState, CustomerStatus> buildMapping() {
        Map<CustomerState, CustomerStatus> m = new EnumMap<>(CustomerState.class);
        // --- CREATED ↔ SCREENING（已建档键、准入未完成）---
        m.put(CustomerState.SCREENING, CREATED);
        // --- 一一对应 ---
        m.put(CustomerState.PROFILED, PROFILED);
        m.put(CustomerState.REJECTED, REJECTED);
        // --- CONSENTED ↔ 服务期内 9 个活跃态（一对多聚合）---
        m.put(CustomerState.CONSENTED, CONSENTED);
        m.put(CustomerState.ASSESS_BASE, CONSENTED);
        m.put(CustomerState.PLAN_APPROVED, CONSENTED);
        m.put(CustomerState.AGREEMENT_SIGNED, CONSENTED);
        m.put(CustomerState.CONFIRMED, CONSENTED);
        m.put(CustomerState.IN_TREATMENT, CONSENTED);
        m.put(CustomerState.CYCLE_ASSESS, CONSENTED);
        m.put(CustomerState.PLAN_REVISING, CONSENTED);
        m.put(CustomerState.REFUND_REVIEW, CONSENTED);
        // --- ARCHIVED ↔ 两个终态 ---
        m.put(CustomerState.CLOSED, ARCHIVED);
        m.put(CustomerState.TERMINATED, ARCHIVED);
        return Map.copyOf(m);
    }

    static {
        assertMappingIsTotal();
    }

    /**
     * 机械保证：映射表对 14 态<b>全覆盖</b>。
     *
     * <p>🛑 这不是"防御性编程"，而是一条真实存在的失效路径：新增一个态
     * （例如业务加了一个 {@code PAUSED}）时，漏登记映射的后果是
     * {@link #of(CustomerState)} 返回 {@code null}，随后 {@code customer.status}
     * 被写成 {@code null} —— 而该列 NOT NULL，写入才 500。
     * 也就是说：<b>漏登记的代价会推迟到某次真实业务跃迁时才显现</b>。
     * 把它提前到类加载期，是本类唯一"零成本、零误报"的加固点。
     */
    private static void assertMappingIsTotal() {
        StringBuilder missing = new StringBuilder();
        for (CustomerState s : CustomerState.values()) {
            if (!STATE_TO_STATUS.containsKey(s)) {
                missing.append(missing.isEmpty() ? "" : "、").append(s.code());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "14 态 → 5 值映射表不完整，缺少以下状态: " + missing
                            + "。权威映射见 data-dict §2.25 ②（逐项冻结，不得自行推断）。"
                            + "🛑 补映射时请回查该表，不要按字面相似度猜 —— "
                            + "例如 REJECTED 归 REJECTED（不是 ARCHIVED），"
                            + "因为 REJECTED 是『从未进入服务』而 ARCHIVED 是『服务已结束』");
        }
    }

    /** 状态 → 5 值（唯一入口；表已保证全覆盖，故不会返回 {@code null}）。 */
    public static CustomerStatus of(CustomerState state) {
        if (state == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "无法由空的 14 态推导 5 值 —— 5 值必须来自状态机实体，"
                            + "不得由调用方另给一个（那会造出第二份口径）");
        }
        CustomerStatus s = STATE_TO_STATUS.get(state);
        if (s == null) {
            // 理论不可达（static 块已断言全覆盖），保留是为了让"有人绕过 static 块"也无路可走
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "状态 " + state.code() + " 未登记 5 值映射（见 data-dict §2.25 ②）");
        }
        return s;
    }

    /** 只读映射视图（供测试逐项比对"与 data-dict §2.25 ② 一致"）。 */
    public static Map<CustomerState, CustomerStatus> mapping() {
        return STATE_TO_STATUS;
    }

    /** 给定 5 值，其对应的 14 态<b>集合</b>（用于文档/断言；<b>不</b>用于反推当前态）。 */
    public static List<CustomerState> statesMappingTo(CustomerStatus status) {
        return java.util.Arrays.stream(CustomerState.values())
                .filter(s -> STATE_TO_STATUS.get(s) == status)
                .toList();
    }

    /** 严格解析 5 值字面：未登记一律抛，绝不回落。 */
    public static CustomerStatus ofCode(String code) {
        for (CustomerStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的 5 值状态字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记: " + List.of(values()) + "；权威来源 = data-dict §2.6 / 契约 " 
                        + "CustomerCreateData.status）—— 🛑 注意 V1 迁移的 DEFAULT 'pending' 是"
                        + "骨架占位值，不在权威集内，属已登记的待改项");
    }
}