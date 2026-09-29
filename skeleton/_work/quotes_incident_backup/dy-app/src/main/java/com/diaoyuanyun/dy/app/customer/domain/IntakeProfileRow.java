package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 建档扩展档案一行 —— 逐字段对齐 V5 {@code intake_profile} DDL（L770~L804）与 data-dict §2.23。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5 L770~L804: profile_id / tenant_id / customer_id / job_tag / height_cm / weight_kg /
 *                waist_cm / bp_sys / bp_dia / hr / glucose / uric_acid / sleep / diet /
 *                exercise / thermal / pain_sites / bowel / female_special / meridian_self_report
 *  V5 L806~L808: UNIQUE(tenant_id, customer_id) —— 一客户一份建档本体
 *  data-dict §2.23: 同列集合
 * </pre>
 *
 * <h2>🛑 三条库层语义，本 record 逐条镜像为构造期校验</h2>
 * <ol>
 *   <li><b>{@code height_cm} / {@code weight_kg} / {@code waist_cm}：{@code NULL OR > 0}</b>
 *       （V5 L779~L781 逐字）。data-dict 附注：「三项体测值均 &gt; 0（可空 = 未填，
 *       <b>缺失不得补 0</b>）」。故本 record <b>不接受 0 或负数</b>，
 *       且明确区分 {@code null}（未填）与 0（非法）—— 这是本 record 最要紧的一条。
 *       补 0 的后果很具体：{@code 0} 在大量体检口径里意味着"零值存在"，
 *       某天有人算 BMI 时会得到一个除零或一个荒谬的"极瘦"，而它看起来是一个正常数字。</li>
 *   <li><b>血压 / 心率 / 血糖 / 尿酸为普通数值列，无 CHECK</b> —— 本 record 不额外加范围校验。
 *       🛑 尤其注意 V5 L786~L788 的登记：「GTL1 <b>无血糖/尿酸能力</b>（字典 §4.8 能力溢出防误用）。
 *       字段按字典现值落库，但<b>不构成"已具备该能力"的表态</b>；bp/glucose/uric_acid
 *       的取舍属<b>待业务裁定项</b>（data-spec §1.4 / R7），本文件<b>不代拍</b>」。
 *       故本 record 承载它们，但<b>不</b>在此处表达它们"已具备"、"必填"或"有合理区间"。</li>
 *   <li><b>9 个多选字段为 JSONB、无 CHECK</b>（V5 L791 逐字：「枚举见 01 表；
 *       <b>此处不冻结取值集，避免代拍未定口径</b>」）。故本 record 用 {@code Map<String,Object>}
 *       承载，<b>不建枚举、不校验取值</b> —— 这是对上游"未冻结"的忠实表达。</li>
 * </ol>
 *
 * <h2>🛑 与 {@code screening_record} 属【同一次提交链】</h2>
 * V5 L786 前后附注（data-dict §2.23 / 契约 C.1）逐字：「🛑 与 {@code screening_record}
 * 属【<b>同一次提交链</b>】；<b>仅 {@code screening_record.result=通过} 才允许提交</b>。
 * 该门禁属<b>服务层顺序约束</b>，库层只保证『一客户一份』与字段域」。
 *
 * <p>对本 record 的直接含义：它<b>不</b>自己判断"筛查过没过" —— 那由服务层在调用前
 * 经 {@link CustomerGateGuard#assertAdmissionChain} 断言。把它做成 record 的构造参数
 * 会让"一次合法的读路径"（B5 读档案）也被迫先查筛查记录。
 *
 * <h2>append-only 修订的语义（B6）</h2>
 * 契约 B6 逐字：「补充 + 修订（<b>append-only 留痕，不可覆盖</b>）」。
 * 🛑 本 record <b>不含</b> {@code updatedAt} / {@code version} 之类的"就地修改"标记 ——
 * 因为"不可覆盖"意味着修订是<b>另存一行（或另存一条留痕）</b>，而不是改这一行。
 * 具体承载方式（新行 vs 留痕表）属实现细节，见服务层与仓储注释；
 * 本 record 只保证<b>携带足以重建"当时是什么样"的全部字段</b>。
 */
public record IntakeProfileRow(
        UUID profileId,
        UUID customerId,
        Map<String, Object> jobTag,
        BigDecimal heightCm,
        BigDecimal weightKg,
        BigDecimal waistCm,
        Integer bpSys,
        Integer bpDia,
        Integer hr,
        BigDecimal glucose,
        BigDecimal uricAcid,
        Map<String, Object> sleep,
        Map<String, Object> diet,
        Map<String, Object> exercise,
        Map<String, Object> thermal,
        Map<String, Object> painSites,
        Map<String, Object> bowel,
        Map<String, Object> femaleSpecial,
        Map<String, Object> meridianSelfReport,
        String createdBy) {

    /** 构造期校验 —— 与 V5 的列约束一一对应（见类注释）。 */
    public IntakeProfileRow {
        if (profileId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "建档档案缺主键 profile_id");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档档案缺 customer_id —— V5 的 UNIQUE(tenant_id, customer_id) "
                            + "以它为『一客户一份』的判据，缺它则该约束无从成立");
        }
        heightCm = requirePositiveOrNull(heightCm, "height_cm");
        weightKg = requirePositiveOrNull(weightKg, "weight_kg");
        waistCm = requirePositiveOrNull(waistCm, "waist_cm");
    }

    /**
     * {@code NULL OR > 0} 的镜像实现。
     *
     * <p>🛑 报 {@code 1001 VALIDATION_FAILED}（不是 5001）：这是<b>入参校验</b>，
     * 与"库里出现脏值"是两件事。库层 CHECK 会在写入时以约束名报错，
     * 而应用层先拦可以把消息写成可操作的（"缺失不得补 0"），
     * 而不是把成因交付给约束名 {@code intake_profile_height_cm_check}。
     */
    private static BigDecimal requirePositiveOrNull(BigDecimal v, String column) {
        if (v == null) {
            return null;   // 未填是合法的（可空 = 未填）
        }
        if (v.signum() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档档案 " + column + " 必须为 NULL 或 > 0，实际: " + v
                            + " —— V5 该列 CHECK (… IS NULL OR … > 0)，data-dict §2.23 附注逐字"
                            + "『三项体测值均 > 0（可空 = 未填，【缺失不得补 0】）』。"
                            + "🛑 0 与 NULL 是两件事：NULL = 客户未填，0 = 一个非法的测量值。"
                            + "把未填补成 0 会在下游被读成『存在且为零』（例如 BMI 计算得到荒谬结果，"
                            + "而它看起来只是一个普通数字）");
        }
        return v;
    }

    /** 是否三围体测值全部未填（B5 出参的常见分支；≠ 全部为 0）。 */
    public boolean hasNoBodyMeasure() {
        return heightCm == null && weightKg == null && waistCm == null;
    }
}