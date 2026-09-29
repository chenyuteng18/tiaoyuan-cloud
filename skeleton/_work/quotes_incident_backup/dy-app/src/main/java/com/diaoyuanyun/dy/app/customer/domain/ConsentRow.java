package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 知情同意书一行 —— 逐字段对齐 V5 {@code consent} DDL（L192~L211）与 data-dict §2.8。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5 L192~L211: consent_id / tenant_id / customer_id / auth_scope_json /
 *                band_willingness / signed_at / evidence_hash / data_source
 *  V5 L198     : auth_scope_json JSONB NOT NULL
 *  V5 L201     : band_willingness NOT NULL CHECK ∈ {自愿佩戴, 暂不佩戴}
 *  V5 L202     : signed_at TIMESTAMPTZ NOT NULL
 *  V5 L204     : evidence_hash VARCHAR(128) NOT NULL（证据链哈希）
 *  V5 L206~207 : data_source NOT NULL DEFAULT 'self-report' CHECK ∈ {self-report, device}
 * </pre>
 *
 * <h2>🛑 四列 NOT NULL 而契约 B3 <b>没有</b> requestBody —— 这是一处已登记的缺口</h2>
 * 契约 B3 的 body <b>缺失</b>（只有 path 参数 {@code CustomerId}），而 {@code consent}
 * 表有四列 NOT NULL。这意味着"这四值从哪来"在契约层<b>无处可查</b>。
 * 与 F1（{@code verdict} 的 {@code branch}/{@code confidence} 同类）属同一形态的缺口，已登记。
 *
 * <p>本 record 的处置是<b>忠实承载四值并强校验非空</b>，但<b>不</b>发明它们的来源 ——
 * 来源归服务层的取参策略（并在服务层注释里标明"契约未声明、属已登记缺口"）。
 * 🛑 不得为了让 B3 "看起来完整"而在契约里补一个 requestBody：契约是三方冻结件，
 * 补字段属 MAJOR 变更、须走「变更业务裁定」。
 *
 * <h2>🛑 {@code evidenceHash} 不可空，且它不是"校验和"那么简单</h2>
 * V5 注释逐字「§2.8: 证据链哈希」。它是"这份同意书当时长什么样"的可验证锚点，
 * 与文书渲染侧的 {@code rendered_hash}（P0-27）同属一条证据链纪律。
 * 空值会让"事后能否证明他到底同意了哪一版"这个问题永远无法回答 ——
 * 故本 record 强制非空，并<b>不做</b>任何"缺失时自算一个"的兜底（那会造出一个假锚点）。
 *
 * <h2>🛑 {@code authScope} 允许为空集（可单独拒绝全部四项）</h2>
 * data-dict §2.8 逐字「分项勾选，<b>可单独拒绝</b>」。故本 record
 * <b>不</b>要求四键齐备；且"空集"与"null"不同 —— 空集 = 客户明确拒绝了全部，
 * null = 一个没记。库层 NOT NULL 已排除后者，本 record 再次显式区分。
 */
public record ConsentRow(
        UUID consentId,
        UUID customerId,
        Set<ConsentAuthScope> authScope,
        BandWillingness bandWillingness,
        Instant signedAt,
        String evidenceHash,
        ConsentDataSource dataSource) {

    /** 构造期校验 —— 与 V5 的列约束一一对应。 */
    public ConsentRow {
        if (consentId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "知情同意缺主键 consent_id");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 customer_id —— 契约 B3 的路径参数即客户标识，且 V5 该列 NOT NULL");
        }
        if (authScope == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 auth_scope_json —— V5 该列 JSONB NOT NULL。"
                            + "🛑 【空集】与【null】必须区分：空集 = 客户明确拒绝了全部四项"
                            + "（data-dict §2.8『可单独拒绝』的极端情形，合法）；"
                            + "null = 一个都没记（不合法）。若把空集也拦下，"
                            + "就等于剥夺客户的拒绝权");
        }
        if (bandWillingness == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 band_willingness —— V5 该列 NOT NULL 且 CHECK ∈ {自愿佩戴, 暂不佩戴}。"
                            + "🛑 不得回落为『自愿佩戴』：该值决定手环数据能否进入 A3 口径");
        }
        if (signedAt == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 signed_at —— V5 该列 NOT NULL。签署时点不可由库层 DEFAULT 承担："
                            + "它与证据哈希同属『当时发生了什么』的锚点，"
                            + "若由库层在插入时取 now()，则补录场景下它会指向补录时刻而非签署时刻");
        }
        if (evidenceHash == null || evidenceHash.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 evidence_hash —— V5 该列 NOT NULL（注释：证据链哈希）。"
                            + "🛑 本处【不做】『缺失时自算一个』的兜底：那会造出一个假锚点，"
                            + "使『能否证明他同意的是哪一版』这个问题永远无法回答");
        }
        if (dataSource == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "知情同意缺 data_source —— V5 该列 NOT NULL。"
                            + "🛑 写入路径必须显式给出它，【不得】依赖库层 DEFAULT 'self-report'："
                            + "本域已有一次库层默认值造成偏差的先例（customer.status 的 DEFAULT 'pending' "
                            + "与权威 5 值不一致，属已登记待改项）");
        }
        authScope = Set.copyOf(authScope);
    }

    /** 是否拒绝了全部授权项（合法的极端情形）。 */
    public boolean declinedAllAuthScopes() {
        return authScope != null && authScope.isEmpty();
    }

    /** 是否授予了某一项授权。 */
    public boolean granted(ConsentAuthScope scope) {
        return authScope != null && authScope.contains(scope);
    }
}