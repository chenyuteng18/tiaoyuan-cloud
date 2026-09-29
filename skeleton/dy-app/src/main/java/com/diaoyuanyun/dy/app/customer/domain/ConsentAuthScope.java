package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 知情同意的<b>分项授权键</b> —— 契约域 B3 的 {@code auth_scope_json}，逐字对齐 data-dict §2.8。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  data-dict §2.8 consent.auth_scope_json: jsonb NOT NULL
 *      枚举: collect_basic / generate_advice / service_record / rights_ack
 *      附注: 分项勾选，可单独拒绝
 *  V5 L197~198: auth_scope_json JSONB NOT NULL
 *      注释: §2.8: 分项勾选，可单独拒绝（collect_basic / generate_advice / service_record / rights_ack）
 * </pre>
 *
 * <h2>🛑 「可单独拒绝」对实现的三条硬含义</h2>
 * <ol>
 *   <li><b>不是"全有或全无"</b>：客户可以只勾其中几项。故 {@code auth_scope_json}
 *       是一个<b>集合</b>（4 键的子集），不是 4 个布尔字段拼成的一个固定结构 ——
 *       后者会让"没勾"与"没这个键"变成两件事，而入库后就再也分不清了。</li>
 *   <li><b>拒绝某项不阻断签署</b>：B3 的成功条件是"签了同意书"（且前置为 PROFILED），
 *       <b>不是</b>"四项全勾"。把"四键齐备"写成本域的校验会让一次合法的
 *       "我只同意基础信息采集"变成 400 —— 那与"可单独拒绝"直接矛盾。</li>
 *   <li><b>拒绝的语义是"不收"，不是"不收但照旧用"</b>：某键缺席即表示该授权未取得。
 *       下游若因某键缺席而无法完成动作，应<b>拒绝该动作</b>，
 *       而不是"先用后补"（那等于绕过同意）。</li>
 * </ol>
 *
 * <h2>🛑 为什么"空集合"是合法的，而"未知键"不是</h2>
 * <pre>
 *   空集合  → 合法：客户可拒绝全部 4 项（"可单独拒绝"的极端情形）
 *   未知键  → 抛 5001：库层是 JSONB、没有 CHECK 兜底，
 *             故这里是"未登记授权键"唯一的拦截点
 * </pre>
 * 空集合合法这一点与"拒戴不得降级服务"同源：客户行使拒绝权<b>不构成</b>流程故障。
 * 但一个拼错的键（例如 {@code collect_basics}）必须被拦住 ——
 * 若静默丢弃它，客户以为自己拒绝了某项、实际系统里这一项<b>从未被记录为拒绝</b>，
 * 而事后从 {@code auth_scope_json} 里也看不出"当时他到底想拒绝什么"。
 */
public enum ConsentAuthScope {

    /** 基础信息采集。 */
    COLLECT_BASIC("collect_basic", "基础信息采集"),

    /** 生成调理建议。 */
    GENERATE_ADVICE("generate_advice", "生成调理建议"),

    /** 服务记录。 */
    SERVICE_RECORD("service_record", "服务记录"),

    /** 权利告知确认。 */
    RIGHTS_ACK("rights_ack", "权利告知确认");

    private final String code;
    private final String label;

    ConsentAuthScope(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 库层 / 契约字面（snake_case）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 全部四键（契约固定顺序）。 */
    public static List<ConsentAuthScope> all() {
        return List.of(values());
    }

    /** 全部四键的码集（供"授权覆盖率"类统计使用，顺序稳定）。 */
    public static Set<String> allCodes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(ConsentAuthScope::code).toList());
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static ConsentAuthScope of(String code) {
        for (ConsentAuthScope s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的授权项键: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记: " + allCodes() + "；权威来源 = data-dict §2.8 auth_scope_json）—— "
                        + "🛑 不得静默丢弃：丢弃会让客户以为他拒绝了某项，"
                        + "而库里从未记录过这次拒绝，事后也无从复原");
    }

    /**
     * 解析一批键 → 有序去重集合；<b>任一未知键即整批拒绝</b>。
     *
     * <p>🛑 刻意<b>不</b>"跳过坏元素、保留好的"：跳过会让一次拼写错误
     * 安静地缩小客户的授权集合，而缩小授权方向上是"更严格"，
     * 于是它既不会报错、也不会被人投诉 —— 只会表现为"某项功能莫名不可用"。
     */
    public static Set<ConsentAuthScope> parseAll(java.util.Collection<String> codes) {
        Set<ConsentAuthScope> out = new LinkedHashSet<>();
        if (codes == null) {
            return out;
        }
        for (String c : codes) {
            out.add(of(c));
        }
        return out;
    }

    /** 集合 → 库层字面集（写入 {@code auth_scope_json} 前的归一）。 */
    public static List<String> codesOf(java.util.Collection<ConsentAuthScope> scopes) {
        Set<String> out = new TreeSet<>();
        if (scopes != null) {
            for (ConsentAuthScope s : scopes) {
                out.add(s.code());
            }
        }
        return List.copyOf(out);
    }
}