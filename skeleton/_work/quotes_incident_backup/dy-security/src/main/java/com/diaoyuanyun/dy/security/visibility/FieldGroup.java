package com.diaoyuanyun.dy.security.visibility;

import java.util.List;

/**
 * 字段组（四档）—— 契约 {@code x-field-groups} 的逐字映射。
 *
 * <h2>权威来源</h2>
 * 契约 {@code contract/openapi-v1.0.0.yaml} 根级 {@code x-field-groups}（§3.1）：
 * <pre>
 *   ① {@code raw_data}        手环原始数据: 睡眠 / 步数 / 心率 / 静息心率 / 血氧 / 运动类型
 *   ② {@code capture_status}  采集状态:     已采集天数 / 同步时间（精确到日）/ 接入状态
 *   ③ {@code gap_reason}      缺口原因分类: gap_reason（7 值内部枚举）
 *   ④ {@code derived_result}  派生结果:     依从性维度分（含 A3）/ AS 值 / effect_verdict / 退款资格
 * </pre>
 * 与 config {@code #43}（{@code cfg:band.visibility}）同一口径，四源一致（契约 §3.1 附注）。
 *
 * <h2>④ 是本任务（S1-5）的全部对象</h2>
 * 客户对 ③④ 恒不可见，且是<b>硬约束、不得通过配置放开</b>（契约 §3.1 附注：
 * "放开属『变更业务裁定』"）。故 {@link #isDerived()} 是 S1-5 的判定基点：
 * 只有 ④ 走"入站 403 + 出口不落字段"，①② 对客户正常可见，③ 是不可见但不属"派生结论"
 * （它是缺口<i>分类</i>，不是判定<i>结论</i>——两者拒绝方式相同、语义不同）。
 *
 * <h2>与 config #48（健康资产损益）不得合并</h2>
 * {@code #48} 的"退款类"清单（A3 佩戴率 / AS 值 / 依从性维度分 / effect_verdict /
 * improvement_rate / MCID 判定 / 退款资格 / 达标·未达标评价 / 门槛数字）<b>跨</b>本枚举的
 * ②③④ 三档。config #48 原文逐字要求"与 #40 / #43 独立配置、不得互相替代、不得合并成一张矩阵"。
 * 故本枚举<b>不</b>把 #48 的清单并进来；它们的交集（AS 值 / effect_verdict / 退款资格）
 * 是"同一个字段被两个口径各自覆盖"，由各自的机制分别守住。
 */
public enum FieldGroup {

    /** ① 手环原始数据。契约 {@code x-field-group: raw_data}。 */
    RAW_DATA("raw_data", "① 手环原始数据", false),

    /** ② 采集状态。契约 {@code x-field-group: capture_status}。 */
    CAPTURE_STATUS("capture_status", "② 采集状态", false),

    /** ③ 缺口原因分类。契约 {@code x-field-group: gap_reason}。客户恒不可见（非派生结论）。 */
    GAP_REASON("gap_reason", "③ 缺口原因分类", false),

    /** ④ 派生结果。契约 {@code x-field-group: derived_result}。客户恒不可见（硬约束）。 */
    DERIVED_RESULT("derived_result", "④ 派生结果", true);

    private final String code;
    private final String label;
    private final boolean derived;

    FieldGroup(String code, String label, boolean derived) {
        this.code = code;
        this.label = label;
        this.derived = derived;
    }

    /** 契约 {@code x-field-group} 取值（对外字面，snake_case）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 是否属"派生结论"档（④）。S1-5 的判定基点。 */
    public boolean isDerived() {
        return derived;
    }

    /** 全部四档（契约固定顺序）。 */
    public static List<FieldGroup> all() {
        return List.of(values());
    }

    /** 按契约码解析；未登记抛错（fail-closed），绝不回落。 */
    public static FieldGroup of(String code) {
        for (FieldGroup g : values()) {
            if (g.code.equals(code)) {
                return g;
            }
        }
        throw new IllegalArgumentException(
                "未登记的字段组码: " + (code == null ? "<null>" : "\"" + code + "\"")
                        + "（已登记: " + List.of(values()) + "）");
    }
}