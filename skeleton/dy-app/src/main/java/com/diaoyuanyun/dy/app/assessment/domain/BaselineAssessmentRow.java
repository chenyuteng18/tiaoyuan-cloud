package com.diaoyuanyun.dy.app.assessment.domain;

import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code baseline_assessment} 一行（V5 §2.11）—— 基线评估的落库载体。
 *
 * <h2>基线评估是什么（它区别于周期评估 {@code cycle_assessment}）</h2>
 * 期初参照系：入组后、方案出具前，按"同源分龄题组（28 题 = 7 维 × 4）"测出
 * {@code 维度分（0–16）× 7 + 总分（0–112）}。后续每一次周期评估的改善率，
 * 都以本行作分母/期初。故本行 {@code locked = true}（V5 库层 CHECK 恒真）：
 * <b>基线一旦落库即只读</b>，否则"复评对比谁"这件事会在数据层面漂移。
 *
 * <h2>🛑 {@code migratable} 四要素（PRD P-3 / 数据字典 §2.11）</h2>
 * <ol>
 *   <li>题组 ID（{@code item_group_id}）</li>
 *   <li>量程版本（由 {@code scale_id} 反查 {@code scale.scale_version}）</li>
 *   <li>测量人（{@code measure_operator}，V5 NOT NULL ⇒ 恒有）</li>
 *   <li>时间戳（{@code assessed_at}，DEFAULT now() ⇒ 恒有）</li>
 * </ol>
 * 四要素齐备 ⇒ {@code migratable = true}；缺任一 ⇒ {@code false} ⇒ 后续
 * {@code effect_verdict = NULL} ⇒ 计入 ECC 分母、不计入分子（只扣一次）。
 * 🛑 此字段<b>不是</b>本行自己填的"自我声明"——它由服务层根据四要素
 * <b>推导</b>（{@code item_group_id != null && scaleVersion != null}），
 * 这与 {@code threshold_version} 由口径算出、不由调用方给是同一条纪律：
 * 一个可推导的事实不该由调用方声明，否则声明可以漂移到与事实相反。
 *
 * <h2>🛑 两个已登记的 schema 缺口（不臆造、用标记诚实承载）</h2>
 * <ol>
 *   <li>{@code diagnosis_json} 库层 NOT NULL，但契约 C2 的
 *       {@code BaselineAssessmentRequest} <b>没有</b> diagnosis 输入字段；
 *   <li>契约 C3 响应 {@code BaselineAssessmentData.baseline_conclusion.haozhuan}
 *       （enum {@code 好转 / 稳定 / 下降}）在 C2 入参里<b>也没有</b>对应输入。
 * </ol>
 * 两者同源："结构化主诉 + 优先级"这一块在契约里从未冻结入参形状。
 * 故本行<b>不</b>编造诊断/主诉，落库值与响应值都由服务层以显式标记
 * （{@code _missing}）承载，并把缺口登记进自描述端点。理由见
 * {@code AssessmentService} 的「两个 schema 缺口」一节。
 *
 * <h2>构造期校验（fail-closed，报错指向成因而非库层 23502/23514）</h2>
 * 逐项复述 V5 的 CHECK 与 NOT NULL，使"错了什么"在进入 SQL 前被点名，
 * 而不是留给数据库报一个只有约束名的错误。
 */
public record BaselineAssessmentRow(
        UUID assessmentId,
        UUID customerId,
        UUID scaleId,
        UUID itemGroupId,
        String ageGroupLocked,
        String metricsJson,
        String diagnosisJson,
        boolean migratable,
        UUID measureOperator,
        UUID assistOperator,
        Instant assessedAt,
        boolean legacy,
        String createdBy) {

    public BaselineAssessmentRow {
        requireNonNull(assessmentId, "基线评估主键（assessment_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        requireNonNull(scaleId, "量表标识（scale_id）");
        requireNonBlank(metricsJson, "维度分/总分依据（metrics_json）");
        requireNonBlank(diagnosisJson, "结构化主诉（diagnosis_json）");
        requireNonNull(measureOperator, "测量人（measure_operator）");
        requireNonNull(assessedAt, "评估时点（assessed_at）");
        // 分龄锁定必须在 8 组枚举内 —— 与 V5 CHECK 同字面（ScaleDomain.AgeGroup 是唯一真相源）
        // 🛑 紧凑构造器不能给 final 字段重赋值，故先校验合法性、再交由调用方持有归一化后的值。
        //     这里用 parse 只做"是否合法"的 fail-closed 校验；归一化（trim）由服务层在构造前完成。
        ScaleDomain.AgeGroup.parse(ageGroupLocked);
    }

    /** {@code locked} 与 {@code legacy} 不进构造参：前者由库层 CHECK 恒 true、后者由服务层显式给 false。 */
    public boolean locked() {
        return true;
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
                    name + " 不得为空白字符串 —— 空白与缺失在库层表现不同"
                            + "（空白会落成一个看起来有值的空串），但业务含义同样是缺依据");
        }
    }
}