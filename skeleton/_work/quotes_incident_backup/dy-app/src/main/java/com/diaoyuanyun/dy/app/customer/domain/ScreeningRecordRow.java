package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 禁忌筛查记录一行 —— 逐字段对齐 V5 {@code screening_record} DDL（L161~L176）与契约 B1。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5 L161~L176: screening_id / tenant_id / customer_id / items_json / result /
 *                operator_id / submitted_at
 *  V5 L171     : operator_id UUID NOT NULL REFERENCES staff(staff_id)
 *  V5 L170     : result VARCHAR(16) NOT NULL CHECK (result IN ('通过','不通过'))
 *  契约 B1     : 命中禁忌（result=不通过）→ 客户状态置 REJECTED；记录【不可删除】
 *  契约 ScreeningData: screening_id / result / submitted_at，三者 x-visible-to 均含 client
 * </pre>
 *
 * <h2>🛑 「记录不可删除」在代码里的形态</h2>
 * 契约 B1 描述逐字以「记录不可删除。」结尾；data-dict §2.7 附注同义
 * （「结论不可删除（禁物理删；软删仅留痕审计）」）。与退款留痕同一套纪律，
 * 三层保证：① 仓储<b>不提供</b> DELETE / UPDATE 方法（做不到错）；
 * ② 本 record 无 {@code deletedAt} 之类可被"就地改写"的成熟位置；
 * ③ 门禁测试反射扫描仓储方法名。
 *
 * <h2>🛑 {@code items_json} 的字段集<b>待业务统一</b> —— 本 record 不冻结</h2>
 * V5 L167~L168 逐字：「⚠️ 字段集【<b>待业务统一</b>】：config #8 为『9 项 + 其他』，
 * 01 表 §六仅 4 项（附录 C.6.b①）→ 见 §9 登记」。
 * 故 {@code itemsJson} 用 {@code Map<String,Object>} 承载，<b>不</b>建枚举、
 * <b>不</b>校验键集 —— 与 {@link IntakeProfileRow} 对 9 个多选字段的处置同源。
 *
 * <h2>🛑 {@code operatorId} 在库层 NOT NULL，故本 record 也强制非空</h2>
 * 契约 {@code ScreeningCreateRequest} 有 {@code operator_id} 字段，但该字段的
 * description 逐字标注「<b>服务端从 token 覆写</b>」。两点叠加：
 * <ul>
 *   <li>库层 NOT NULL ⇒ 必须有一个值，否则插入即失败；</li>
 *   <li>「服务端覆写」⇒ 该值<b>不得</b>采信请求体里客户端传来的那个。</li>
 * </ul>
 * 故本 record 的 {@code operatorId} 由服务层<b>从 token 解出</b>后填入，
 * 并在服务层<b>丢弃</b>请求体里的同名字段。把这条落在服务层而非此处，
 * 是因为"覆写"是一个<b>取自何处</b>的问题，而非"值是否合法"的问题。
 */
public record ScreeningRecordRow(
        UUID screeningId,
        UUID customerId,
        Map<String, Object> itemsJson,
        ScreeningResult result,
        UUID operatorId,
        Instant submittedAt,
        String createdBy) {

    /** 构造期校验 —— 与 V5 的列约束一一对应。 */
    public ScreeningRecordRow {
        if (screeningId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "筛查记录缺主键 screening_id");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "筛查记录缺 customer_id —— 契约 B1 描述与 B2 门禁都以『该客户有没有一条通过记录』为判据");
        }
        if (result == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "筛查记录缺 result —— V5 该列 NOT NULL 且 CHECK ∈ {通过,不通过}；"
                            + "缺它则『能不能建档』这一判定失去输入");
        }
        if (itemsJson == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "筛查记录缺 items_json —— V5 该列 JSONB NOT NULL。"
                            + "🛑 空对象 {} 与 null 是两件事：前者 = 『做了筛查、模块记为无』，"
                            + "后者 = 『一个值都没记』，而契约 B1 的硬门禁结论正是从这份结构化记录推出的");
        }
        if (operatorId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "筛查记录缺 operator_id —— V5 该列 NOT NULL REFERENCES staff。"
                            + "契约 ScreeningCreateRequest.operator_id 的 description 逐字标注"
                            + "『服务端从 token 覆写』⇒ 本值必须由服务层从 token 解出填入，"
                            + "【不得】采信请求体里的同名字段");
        }
    }

    /** 是否"通过"（B2 建档门禁的判据）。 */
    public boolean isPassing() {
        return result != null && result.isPassing();
    }

    /**
     * 出参投影 —— 契约 {@code ScreeningData}（3 个字段，{@code x-visible-to} 均含 client）。
     *
     * <p>B1 的响应体<b>对四类角色完全一致</b>（三个字段的 {@code x-visible-to}
     * 都是 {@code [client, therapist, meridian, admin]}），故本方法<b>不需要</b>角色参数 ——
     * 加一个用不到的参数会让人误以为"这里有裁剪"，而实际上 B1 的裁剪规则是"全给"。
     * 🛑 这恰好与 B4 相反：B4 的响应体<b>按角色变化</b>，故它的投影方法必须带角色。
     * 两者的差别来自契约的 {@code x-visible-to}，不是实现者可以自行统一的。
     */
    public Map<String, Object> toContractData() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("screening_id", screeningId == null ? null : screeningId.toString());
        out.put("result", result == null ? null : result.code());
        out.put("submitted_at", submittedAt == null ? null : submittedAt.toString());
        return out;
    }
}