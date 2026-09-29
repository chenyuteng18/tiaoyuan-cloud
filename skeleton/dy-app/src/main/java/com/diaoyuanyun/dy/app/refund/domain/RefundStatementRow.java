package com.diaoyuanyun.dy.app.refund.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一条客户原话（{@code refund_statement} 的一行）—— <b>append-only 账本</b>。
 *
 * <h2>🛑 {@code recordedAt} 的三个既定事实（勿再加"非空校验"）</h2>
 * <ol>
 *   <li>本字段的语义是「<b>读路径</b>由库回填，<b>写路径</b>忽略」；
 *   <li>{@link #forInsert} 的契约就是产出 {@code recordedAt = null}；
 *   <li>库列 {@code refund_statement.recorded_at} 为 {@code TIMESTAMPTZ NOT NULL DEFAULT now()}。
 * </ol>
 * 三者共同要求：写路径的 SQL 必须以 {@code COALESCE(?, now())} 绑定本字段，
 * 使 {@code null} 落成库的 {@code now()}。
 *
 * <p>🛑 <b>不得</b>在写入校验里要求本字段非空 —— 那会与上面三条同时冲突：
 * {@code forInsert} 产出的行会被自己的写入校验拒掉，于是<b>任何带客户原话的立案
 * 100% 失败</b>（而"客户原话必填、append-only、不可编辑"是 P0-19 的硬约束）。
 * 这一处缺陷曾被真请求 E2E 抓出，其报错为
 * 「原话记录时刻（recorded_at） 缺失，拒绝写入」。注意 PostgreSQL 的
 * {@code DEFAULT} 只在<b>列被省略</b>时生效 —— 显式绑 {@code NULL} 会直接违反
 * {@code NOT NULL}，故"库有默认值"并不能替代 {@code COALESCE}。
 *
 * <p>（本注释留痕：该缺陷的成因是"两处对同一字段的契约相反"——写校验要求必填、
 * 构造器明确给 null，而<b>两侧各自的注释都写得很充分</b>。这类缺陷无法靠读单侧发现，
 * 只能靠"让两侧在真实链路里相遇"。）
 *
 * <h2>🛑 更正靠"新行声明取代谁"，不靠"改旧行"</h2>
 * P0-19 逐字：「已提交原话<b>不可编辑</b>（如确需更正，追加更正记录而非覆盖）」。
 * 库里为此留了 {@link #supersedesStatementId} —— 由<b>新行</b>指向被取代者。
 *
 * <p>为什么不反过来（旧行指向新行）？因为反向指针要求一次 {@code UPDATE}：
 * 写新行时同时修改旧行，那正是"不可编辑"被破的口子 —— 而它一旦存在，
 * 后续每一次"就改一个字"都会有先例可援。正向指针则让更正<b>只做 INSERT</b>，
 * 于是"不可编辑"不是一个承诺，而是一个没有对应 SQL 动作的事实。
 *
 * <h2>🛑 {@code statementSource} 与 {@code requestedAtSource} 是两件事</h2>
 * 两者字面相近（都是三取值、都涉及"谁说的"），但登记的是不同对象：
 * <pre>
 *   requestedAtSource      是为【时间】标注来源 —— "这个时刻凭什么算数"
 *   statementSource        是为【原话】标注取得方式 —— "这段话是谁的嘴、经谁的手"
 * </pre>
 * 同一张工单可以"先有调理师转交原话、后有经络师受理转述"：
 * 原话有两条、来源各不同，而 {@code requested_at} 只归一成一个。
 * 合并两者会让第二段原话的时间标注被第一段的来源覆盖，
 * 而"客户原话"与"经手人转述"在争议中的证明力完全不同。
 *
 * <h2>取值字面与库 CHECK 逐字一致</h2>
 * {@code 客户原话 / 调理师转交 / 经络师受理转述} —— 注意第三项是
 * 「经络师受理<b>转述</b>」，比 {@link RequestedAtSource#MERIDIAN_ACCEPTED} 的
 * 「经络师受理」多一个"转述"。两处字面不同是刻意的：一处说时间点、一处说内容形态。
 * 🛑 不得"统一"成同一个常量 —— 那会让端侧逐字比对与库 CHECK 同时失效。
 *
 * @param statementId          原话主键
 * @param refundId             所属退款工单
 * @param statementText        客户原话本体（逐字，不得摘改）
 * @param statementSource      取得方式（{@code 客户原话 / 调理师转交 / 经络师受理转述}）
 * @param supersedesStatementId 本行取代了哪一行（可空 = 全新原话，不是更正）
 * @param recordedAt           记录时刻（读路径由库回填；写路径忽略，见 {@link #forInsert}）
 * @param createdBy            创建者标识
 */
public record RefundStatementRow(
        UUID statementId,
        UUID refundId,
        String statementText,
        String statementSource,
        UUID supersedesStatementId,
        Instant recordedAt,
        String createdBy) {

    /**
     * 原话取得方式的合法取值（与 V6 {@code refund_statement.statement_source} 的 CHECK 逐字一致）。
     *
     * <p>本域<b>不</b>为它建枚举，与 {@code requested_at_source} 的处理不同 —— 理由：
     * 前者的三取值参与过计时逻辑判定（{@code isVerifiableInEarlySet}），
     * 而本字段<b>不参与任何判定</b>，只进证据链展示。为它建枚举会引入一个
     * "看起来需要维护的判定入口"，而它其实没有任何判定可做。
     * 但合法清单仍须显式声明：库层 CHECK 报错只会给出约束名，
     * 调用方需要的是"哪三个值是合法的"。
     */
    public static final List<String> SOURCES = List.of("客户原话", "调理师转交", "经络师受理转述");

    /**
     * 校验取得方式合法并返回归一后的值。
     *
     * <p>🛑 不接受 {@code null}、空白，也不接受"两者都不是"的第三值 ——
     * 空来源会让一条原话在证据链里"没有出处"，而证据链的整个意义就是可追溯。
     */
    public static String requireLegalSource(String source) {
        if (source == null || source.isBlank()) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    com.diaoyuanyun.dy.common.result.ErrorCode.VALIDATION_FAILED,
                    "客户原话缺取得方式（statement_source）—— 无出处的原话在证据链中不可采信；"
                            + "合法值: " + SOURCES);
        }
        String s = source.trim();
        if (!SOURCES.contains(s)) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    com.diaoyuanyun.dy.common.result.ErrorCode.VALIDATION_FAILED,
                    "客户原话取得方式不在允许值内: '" + source + "'（合法值: " + SOURCES + "）");
        }
        return s;
    }

    /** 本行是否是一次更正（取代了某条在先原话）。 */
    public boolean isCorrection() {
        return supersedesStatementId != null;
    }

    /**
     * 构造一条<b>待写入</b>的原话（{@code recordedAt} 置空，交由库层生成）。
     *
     * <p>与 {@link RefundRetentionRow#forInsert} 同一条理由：让"写路径不带时间"
     * 成为默认姿势，避免应用时钟与库时钟两个时间源进入证据链 ——
     * 原话的 {@code recorded_at} 恰恰是"这句话是什么时候说的"的唯一凭证。
     */
    public static RefundStatementRow forInsert(UUID statementId,
                                               UUID refundId,
                                               String statementText,
                                               String statementSource,
                                               UUID supersedesStatementId,
                                               String createdBy) {
        return new RefundStatementRow(statementId, refundId, statementText,
                requireLegalSource(statementSource), supersedesStatementId, null, createdBy);
    }

    /** 全部合法取得方式（供错误消息与门禁断言使用）。 */
    public static List<String> allSources() {
        return SOURCES;
    }
}