package com.diaoyuanyun.dy.app.derived.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 依从状态 —— 契约 §3.1「维度分离原则」里的 <b>{@code adherence_state}</b> 三值枚举。
 *
 * <h2>为什么它是独立枚举，而不是 {@code effect_verdict} 的一个值</h2>
 * 指标规格 §3.1 逐字写明四套词表被混成了 4 个不同维度，必须拆开：
 * <pre>
 *   效果     effect_verdict    E1–E5
 *   依从性   adherence_state  达标 / 不足 / 样本不足
 *   风险     risk_flag        无 / 高危 / 新发 / 同病
 *   处置     disposition      继续原方案 / 调整后继续 / 转基础服务 / 退款终止 / 建议就医
 * </pre>
 * 并逐字给出理由：「PRD 的『依从不足』属 {@code adherence_state}……<b>两者都不进
 * {@code effect_verdict}</b>……混进同一 enum 会使『依从不足』与『无改善』不可区分，
 * 判定与举证都会失效。」
 *
 * <h2>三值的来源（逐条可追溯）</h2>
 * <ul>
 *   <li>{@code 达标} / {@code 不足} —— 指标规格 §4.3：{@code AS_refund} 与
 *       config {@code #4}（{@code cfg:adherence.pass_threshold}，初始 {@code 0.80}）比较得出；</li>
 *   <li>{@code 样本不足} —— 指标规格 §4.3 护栏原文：「应填天数 {@code < 7} → 标『样本不足』，
 *       <b>不得用于退款门禁</b>」，{@code 7} 来自 config {@code #7}
 *       （{@code cfg:adherence.min_sample_days}）。</li>
 * </ul>
 *
 * <h2>🛑 「样本不足」不是「不足」的同义词</h2>
 * 二者对下游是<b>不同</b>的指令：{@code 不足} 表示"算出来了，且没达标"，
 * 可作为判定输入；{@code 样本不足} 表示"<b>没算出来</b>"，按硬纪律 #6 的口径
 * 它必须<b>退出合成</b>（config {@code #45} 的 {@code n.applicable_false_below}），
 * 而不是当作 {@code 0} 分或"未达标"。把两者合并会让"数据不够"静默变成"客户没配合"——
 * 后者是可以被用来对客户不利的结论，前者不能。故本枚举<b>显式</b>保留第三值。
 */
public enum AdherenceState {

    /** 依从性达标（{@code AS_refund ≥ 门槛}），可作为"依从达标但无效"类判定的一半条件。 */
    PASS("达标"),

    /** 依从性不足（{@code AS_refund < 门槛}）。是<b>算出来的</b>结论，不是"没算出来"。 */
    INSUFFICIENT("不足"),

    /**
     * 样本不足（应填天数 &lt; config {@code #7} 的门槛）。
     * <b>不得用于退款门禁</b>，且退出置信度合成（config {@code #45}）。
     */
    SAMPLE_INSUFFICIENT("样本不足");

    private final String label;

    AdherenceState(String label) {
        this.label = label;
    }

    /** 落库 / 契约字面（= DB CHECK 里的值，见 V5 {@code cycle_assessment.adherence_state}）。 */
    public String label() {
        return label;
    }

    public static List<String> allLabels() {
        return Arrays.stream(values()).map(AdherenceState::label).toList();
    }

    /**
     * 按落库字面解析；未知一律 fail-closed。
     *
     * <p>不回落到某个默认状态：落库值写错时，回落到 {@code 不足} 会让一条
     * 数据质量问题变成一条对客户不利的依从结论。
     */
    public static AdherenceState parse(String label) {
        if (label == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "adherence_state 必填（3 值之一: " + allLabels() + "）");
        }
        Optional<AdherenceState> hit = Arrays.stream(values())
                .filter(s -> s.label.equals(label.trim()))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                "adherence_state 不在 3 值枚举内: " + label + "（合法值: " + allLabels() + "）"));
    }

    /** 是否可用于退款门禁 —— {@code 样本不足} 明确不可（指标规格 §4.3 护栏）。 */
    public boolean usableForRefundGate() {
        return this != SAMPLE_INSUFFICIENT;
    }
}