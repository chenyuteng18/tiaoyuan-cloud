package com.diaoyuanyun.dy.app.derived.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 效果判定 —— 契约 §3.1 的 canonical 效果枚举 <b>{@code effect_verdict}</b>（E1–E5）。
 *
 * <h2>逐字来自指标规格 §3.2（含"自动 / 人工"那一列）</h2>
 * <table border="1">
 *   <caption>指标规格 §3.2 的四列表</caption>
 *   <tr><th>值</th><th>定义</th><th>自动 / 人工</th></tr>
 *   <tr><td>E1 显著改善</td><td>模块 IR ≥ 显著阈值 <b>且</b> 核心健康困扰复评=明显改善</td>
 *       <td>系统算 + <b>人工确认</b></td></tr>
 *   <tr><td>E2 部分改善</td><td>模块 IR 达 MCID 门槛但未达显著阈值 <b>或</b> 核心困扰=部分改善</td>
 *       <td>系统算 + <b>人工确认</b></td></tr>
 *   <tr><td>E3 稳定</td><td>模块 IR 未达 MCID 且未恶化（含下降 1 分与无变化）</td>
 *       <td><b>系统可自动判定</b></td></tr>
 *   <tr><td>E4 无明显改善</td><td>模块 IR 未达 MCID 且核心困扰=无明显改善</td>
 *       <td><b>系统可自动判定</b>（核心困扰项由人工录入）</td></tr>
 *   <tr><td>E5 加重</td><td>任一题项上升 ≥2 级，或模块总分上升 ≥ MCID，或出现新发/高危</td>
 *       <td><b>必须人工录入</b>（涉安全）</td></tr>
 * </table>
 *
 * <h2>本枚举把"自动 / 人工"编码成属性，而不是留在文档里</h2>
 * 那一列不是描述性的 —— 它是<b>流程约束</b>：E1/E2 未经人工确认不得落库，
 * E5 不得由系统自动写入。若只写在文档里，代码里就会出现"某个批处理顺手把 E5 算出来"，
 * 而等到有人发现时，一条加重的安全结论已经进了客户档案。
 * 故 {@link #requiresHumanConfirmation()} 与 {@link #mustBeHumanEntered()} 是
 * 可被断言的事实，{@link EffectVerdictEngine} 按它拒绝系统自动产出的结论。
 *
 * <h2>落库字面与 DB CHECK 逐字一致</h2>
 * V5 {@code cycle_assessment.effect_verdict} / {@code verdict.effect_verdict} 的 CHECK
 * 用的是<b>同一批字面</b>（{@code 'E1显著改善'}…），故 {@link #label()} <b>就是</b>库里的值。
 * 若此处改成英文 token（如 {@code E1}），会出现"服务层认为合法、库层拒绝"的错位，
 * 表现为 23514 而不是一条清晰的契约错误。
 */
public enum EffectVerdict {

    /** E1 显著改善 —— 系统算 + 人工确认。 */
    E1_SIGNIFICANT("E1显著改善", false, true),

    /** E2 部分改善 —— 系统算 + 人工确认。 */
    E2_PARTIAL("E2部分改善", false, true),

    /** E3 稳定 —— 系统可自动判定。 */
    E3_STABLE("E3稳定", true, false),

    /** E4 无明显改善 —— 系统可自动判定（核心困扰项由人工录入）。 */
    E4_NO_IMPROVEMENT("E4无明显改善", true, false),

    /** E5 加重 —— <b>必须人工录入</b>（涉安全）。 */
    E5_WORSENED("E5加重", false, true);

    private final String label;
    private final boolean systemCanAutoDecide;
    private final boolean requiresHuman;

    EffectVerdict(String label, boolean systemCanAutoDecide, boolean requiresHuman) {
        this.label = label;
        this.systemCanAutoDecide = systemCanAutoDecide;
        this.requiresHuman = requiresHuman;
    }

    /** 落库 / 契约字面（= DB CHECK 里的值）。 */
    public String label() {
        return label;
    }

    /** 系统是否可自动判定该分支（指标规格 §3.2 第 3 列）。 */
    public boolean systemCanAutoDecide() {
        return systemCanAutoDecide;
    }

    /**
     * 是否需要人工参与才可落库。
     *
     * <p>E1/E2 = 需人工<b>确认</b>（系统先算出候选）；E5 = 需人工<b>录入</b>（系统不得自动写）。
     */
    public boolean requiresHumanConfirmation() {
        return requiresHuman;
    }

    /**
     * 是否<b>必须人工录入</b> —— 系统不得自动产出该结论。
     *
     * <p>仅 E5：指标规格 §3.2 原文「<b>必须人工录入</b>（涉安全）」。
     * 与 {@link #requiresHumanConfirmation()} 的区别很重要：E1/E2 是
     * "系统可先算出候选、等人工点头"，E5 是"系统连候选都不许自动落库"。
     */
    public boolean mustBeHumanEntered() {
        return this == E5_WORSENED;
    }

    /** 是否属"改善"侧（E1 ∪ E2）—— PRD 的"稳定改善"展开为 E1∪E2∪E3，此处只取前两者。 */
    public boolean isImprovement() {
        return this == E1_SIGNIFICANT || this == E2_PARTIAL;
    }

    public static List<String> allLabels() {
        return Arrays.stream(values()).map(EffectVerdict::label).toList();
    }

    /** 按落库字面解析；未知一律 fail-closed（不回落到 E3 —— 那会把未知状态说成"稳定"）。 */
    public static EffectVerdict parse(String label) {
        if (label == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "effect_verdict 必填（E1–E5 之一: " + allLabels() + "）");
        }
        Optional<EffectVerdict> hit = Arrays.stream(values())
                .filter(v -> v.label.equals(label.trim()))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                "effect_verdict 不在 E1–E5 枚举内: " + label + "（合法值: " + allLabels() + "）"));
    }
}