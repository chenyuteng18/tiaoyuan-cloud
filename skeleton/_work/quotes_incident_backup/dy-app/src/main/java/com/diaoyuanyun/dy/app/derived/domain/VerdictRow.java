package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code verdict} 一行 —— <b>判定结论</b>的落库载体（V5 §2.19）。
 *
 * <h2>🛑 本记录最重要的一个字段是恒 {@code false} 的那个</h2>
 * {@code visible_to_customer} 在 V5 里定义为
 * {@code BOOLEAN NOT NULL DEFAULT FALSE CHECK (visible_to_customer = FALSE)} ——
 * 即<b>库层已经把"结论不对外直出"钉死</b>。这条来自 Q10 口径②：
 * 「{@code verdict.visible_to_customer=false}，仅作对内证据链」。
 *
 * <p>本记录<b>不提供</b>设置它的途径：{@link #visibleToCustomer()} 恒返回 {@code false}，
 * 且 {@code VerdictLedger} 的 INSERT 列清单<b>显式包含</b>它并绑 {@code false}
 * （而不是省略该列让 {@code DEFAULT} 生效）。这个选择有一条具体理由：
 * 显式绑定时，若有人把 {@code false} 改成 {@code true}，那条 INSERT 会被
 * 库层 CHECK 拒成 23514 —— 而省略列则让"改 DEFAULT"成为唯一的攻击面，
 * 那需要一次迁移。把可攻击面从"数据库变更"降到"一行 Java 常量并立刻被拒"，
 * 是这条防线值得多写一列的全部理由。
 *
 * <h2>🛑 它也<b>不</b>持有 {@code disposition}</h2>
 * PRD §C.1.7 L1794 把 {@code disposition} 列为 {@code verdict} 的组合出口字段。
 * <p><b>V8 起本列已建</b>（见 {@link #disposition()}）：原先"V5 未建列"的缺口已由
 * V8 补齐（裁定 = 归属 {@code verdict}，依据 PRD L1794 的权威表述）。
 * 由组合出口（{@code effect_verdict} × {@code adherence_state} × {@code risk_flag}）
 * 推出，系统一次写入、不可覆盖。
 *
 * @param verdictId          判定主键（PK）
 * @param cycleId            所属周期评估（FK → {@code cycle_assessment}，{@code NOT NULL}）
 * @param branch             判定分支（5 值，与 {@code cycle_assessment.verdict} 同域）
 * @param confidence         置信度 0–1；🛑 <b>可空</b>（V8 起）—— {@code null} = "不可判"
 *                           （测量不可比），与 {@code 0.000}（"最低置信"）<b>结构上不同</b>
 * @param evidenceSnapshotJson 判定依据快照（{@code NOT NULL}，<b>不可覆盖</b>）
 * @param thresholdVersion   阈值版本（{@code NOT NULL}，<b>不可覆盖</b>）
 * @param decidedAt          判定时刻（写入路径显式给定；库层 {@code DEFAULT now()} 只是兜底）
 * @param effectVerdict      E1–E5；{@code null} = 未定（挂起时合法）
 * @param adherenceState     达标 / 不足 / 样本不足；{@code null} = 未算
 * @param riskFlag           风险标签；{@code null} = 未录入（🛑 不得默认"无"）
 * @param disposition        组合出口（V8 新增列）；{@code null} = 未推出
 * @param createdBy          操作人
 */
public record VerdictRow(
        UUID verdictId,
        UUID cycleId,
        VerdictBranch branch,
        BigDecimal confidence,
        String evidenceSnapshotJson,
        String thresholdVersion,
        Instant decidedAt,
        EffectVerdict effectVerdict,
        AdherenceState adherenceState,
        RiskFlag riskFlag,
        Disposition disposition,
        String createdBy) {

    public VerdictRow {
        requireNonNull(verdictId, "判定主键（verdict_id）");
        requireNonNull(cycleId, "所属周期评估（cycle_id）");
        requireNonNull(branch, "判定分支（branch）");
        requireNonBlank(evidenceSnapshotJson, "判定依据快照（evidence_snapshot）");
        requireNonBlank(thresholdVersion, "阈值版本（threshold_version）");

        // 🛑 置信度（V8 起可空）—— 但"可空"不等于"可以不提"：
        //    这里只校验【非空值】的范围，不对 null 报错（null 是 D5 挂起态的合法形态）。
        //    🛑 置信度越界不得截断（与 EffectVerdictEngine / AdherenceEngine 同一条纪律）。
        //       截断在这里尤其危险：0.97 → 1.0 会让"接近满分"与"满分"不可区分，
        //       而置信度是判定协商辅助的排序键。
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "置信度须在 [0,1] 内：实际=" + confidence.toPlainString()
                            + "（越界不得截断 —— 截断会静默改分，"
                            + "而置信度是判定协商辅助的排序键）");
        }
        // 🛑 库层对 risk_flag 无 CHECK（见 RiskFlag 类注释）：
        //    防线在应用层，故这里不靠"值域"而是靠"类型"——
        //    传进来的已是 RiskFlag 枚举，解析已在入口 fail-closed 过一遍。
        //    此处额外拦的是"挂起分支却带了风险标签"这种语义矛盾：
        if (branch == VerdictBranch.HUMAN_REVIEW && riskFlag != null
                && riskFlag.triggersFullAssessment()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "分支为『人工复核』却带着触发全面评估的风险标签（" + riskFlag.dbLabel() + "）—— "
                            + "PRD §7.3 D4：命中 {高危,新发,同病} 即应落『全面评估』而非挂起。"
                            + "该组合会让一次安全信号以『等人工』的形态被静默搁置");
        }
        // 🛑 E5 必须人工录入：库层无此断言（risk_flag 无 CHECK，effect_verdict 有 CHECK 但
        //    只查值域）。故"谁写的"这件事必须在应用层拦住 —— 系统自动产出的路径不得写 E5。
        if (effectVerdict != null && effectVerdict.mustBeHumanEntered() && isBlank(createdBy)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    effectVerdict.label() + "（加重）必须人工录入，而本行未记录操作人"
                            + "（created_by 为空）—— 系统自动产出 E5 是本项目明令禁止的一类事故"
                            + "（指标规格 §3.2：E5 涉安全，必须人工录入）");
        }
        // 🛑 V8 新立的不变式：**非挂起 ⇒ 置信度必非空**
        //
        //    论证：confidence == null 的【唯一】成因是 s = 不可比
        //    （VerdictConfidenceEngine 只有 INCOMPARABLE 那一支返回 suspended=true 且不产出数值），
        //    而"不可比"会在路由的 D5 前置处被拦成『人工复核』。
        //    故非挂起分支若带着 null 置信度，说明路由与置信度之间有一步被改坏了。
        //    库层已不再拦它（V8 放宽了 NOT NULL），故这条防线**必须**留在应用层 ——
        //    否则一次"挂起却落了结论行"会静默通过（那正是 A-4 缺口要防的事）。
        if (branch != VerdictBranch.HUMAN_REVIEW && confidence == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "分支为『" + branch.dbLabel() + "』（非挂起）但置信度为 null —— "
                            + "该组合不该出现：置信度为空只可能来自 s=不可比，"
                            + "而那会在路由的 D5 前置处就返回『人工复核』。"
                            + "V8 放宽了库层 NOT NULL，故这条不变式现在只由应用层守");
        }
    }

    /**
     * 结论是否对客户可见 —— <b>恒 {@code false}</b>。
     *
     * <p>它不是配置、不是参数、也不是"本版暂时为 false"。Q10 口径② 已定：
     * 「{@code verdict.visible_to_customer=false}，仅作对内证据链」；
     * 库层 CHECK 把它钉死。故本方法返回常量而非字段 ——
     * 一个可设置的字段会让"某次写入把它设成 true"在编译期看起来完全正常。
     */
    public boolean visibleToCustomer() {
        return false;
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static void requireNonBlank(String v, String name) {
        requireNonNull(v, name);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 不得为空白字符串（业务含义与缺失相同，但在库里会落成一个看起来有值的空串）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}