package com.diaoyuanyun.dy.app.refund.repository;

import com.diaoyuanyun.dy.app.refund.domain.ReceiptState;
import com.diaoyuanyun.dy.app.refund.domain.RefundOfflineNoticeRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 回执三态留痕与转线下告知的 JDBC 仓储（V6 建表）。
 *
 * <h2>🛑 本类<b>只有</b> INSERT 与 SELECT —— 缺四种方法是刻意的</h2>
 * {@code refund_receipt} / {@code refund_offline_notice} 都是 <b>append-only 账本</b>
 * （P0-19「随工单落库、<b>不可删除</b>」；中山中院 2026-04 判词：凭证须可提交）。
 * "不可删除"在代码里若只写成一句注释，它的强度等于零 ——
 * 下一个人想"修正一条写错的状态"时，注释拦不住他，而 {@code jdbc.update("DELETE ...")}
 * 只是一个字符串。故本类<b>不提供</b>以下四类方法，且这不是"还没写"：
 * <pre>
 *   ✗ updateState(...)      —— 状态是不可变的：三态之一一旦落库即为事实
 *   ✗ deleteByRefund(...)   —— 删除即断链
 *   ✗ upsertById(...)       —— upsert 是"就地改写"的另一种写法
 *   ✗ saveOrUpdate(...)     —— 同上，只是名字看起来温和
 * </pre>
 * <p>真正让它"不可绕过"的机械保证有三层，缺一层都不够：
 * <ol>
 *   <li><b>本类方法集</b>：没有可写出的调用序列（这一层是"做不到错"）；</li>
 *   <li><b>库层</b>：三张账本表刻意<b>不带 {@code updated_at}</b> 列，
 *       "就地改写"没有看起来正常的位置（{@code RlsV6RefundLedgerIsolationTest} 断言该列不存在）；</li>
 *   <li><b>门禁</b>：{@code RefundReceiptLedgerAppendOnlyTest} 反射扫描本类的
 *       全部方法名与全部 SQL 字面量，出现 {@code update/delete/upsert/merge} 即红。
 *       第 3 层存在的理由很实际：本类<b>将来可能被改</b>，而前两层的强度依赖于
 *       "改的人仍然遵守它们"。</li>
 * </ol>
 *
 * <h2>隔离机制（与 {@code ScaleItemBankRepository} 同一套）</h2>
 * 每次操作开一个<b>短事务</b>并 {@code SET LOCAL app.tenant_id}，
 * 使"未设租户上下文 = 零行"这条 fail-closed 性质在<b>所有</b>路径上成立，
 * 而不只在被 {@code @RlsScoped} 标注的方法上成立。
 * 本类<b>不</b>在 SQL 里写 {@code WHERE tenant_id = ?} —— 隔离完全由 V6 的
 * {@code FORCE ROW LEVEL SECURITY} 承担（ADR-02）。租户 ID 经
 * {@link #UUID_PATTERN} 白名单后拼接（{@code SET} 不支持绑定参数）。
 *
 * <h2>🛑 为什么覆盖率聚合要放在仓储里，而不是"查出全部行再在服务层数"</h2>
 * 出于两个都很具体的理由：
 * <ol>
 *   <li><b>分母不能靠"内存里有什么"</b>：若服务层查的是"某段时间的行"，
 *       一次时间窗写错会让分母少一块 —— 而聚合 SQL 把"哪一段时间、哪三态"
 *       写在同一个 {@code GROUP BY} 里，改窗口时三态一起改，
 *       不存在"改了 where 但忘了另一态"。</li>
 *   <li><b>三态必须来自同一次查询</b>：分开查三次（分别 count 三个状态）
 *       会让两态之间的事务边界产生一张"漂移"的报告 ——
 *       分母 = 三次查询结果的拼接，而三次之间工单还在产生。
 *       P1-10 要求的是<b>同一时点</b>的分子分母。</li>
 * </ol>
 * 故 {@link #countByState} 用一条 {@code GROUP BY state} 查询返回分布，
 * 且在返回前<b>补零</b>到三态齐备 —— 缺态补 0 而不是缺键，
 * 使下游 {@code coverageOfDist} 不必自己判断"到底少了哪一态"。
 */
@Repository
public class RefundReceiptLedger implements RefundReceiptPort {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RefundReceiptLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、写：只追加（INSERT only）
    // ==================================================================

    private static final String INSERT_RECEIPT_SQL =
            "INSERT INTO refund_receipt ("
                    + " receipt_id, tenant_id, refund_id, receipt_state, channel, template_id,"
                    + " decided_at, pushed_at, failure_reason, operator_id, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?)";

    /**
     * 追加一条回执留痕。
     *
     * <p>🛑 本方法<b>不做 upsert、不做冲突吞并</b>：一张工单可以有多条回执记录
     * （先"未授权（转线下）"、后补一次实际推送，两条都是事实，都要留）。
     * 若这里写成"同一工单只保留最新一条"，则第一次的"未授权"会消失 ——
     * 而它正是授权覆盖率的分母成员。
     *
     * <p>{@code operator_id} 强制非空并在应用层先拦：证据链要求"谁写的"，
     * 而库层该列<b>可空</b>（历史行可能来自系统）。应用层写入路径<b>不得</b>
     * 利用这个可空性 —— 若此处放行，一条无操作人的"已推送"记录在复盘时
     * 无法回答"谁确认它发出去了"。
     */
    @Override
    public void appendReceipt(String tenantId, RefundReceiptRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "回执留痕为空，拒绝写入");
        }
        if (row.receiptId() == null || row.refundId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执留痕缺主键或工单标识 —— 缺任一项则该行无法被关联到任何工单，留痕即失效");
        }
        if (row.state() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执留痕缺状态 —— P0-19 要求『三态之一，且三态都要落库』，"
                            + "缺状态的记录在覆盖率上既不在分子也不在分母，等于没留痕");
        }
        if (row.operatorId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执留痕缺操作人 —— 证据链（中山中院 2026-04 判词：凭证须可提交）"
                            + "要求能回答『谁确认它发出去了』；库层该列可空是为了容纳历史系统行，"
                            + "写入路径不得利用这个可空性");
        }
        requireUuid(tenantId);
        requireUuid(row.receiptId().toString());
        requireUuid(row.refundId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_RECEIPT_SQL,
                row.receiptId().toString(),
                row.refundId().toString(),
                row.state().code(),
                row.channel(),
                row.templateId(),
                ts(row.decidedAt()),
                ts(row.pushedAt()),
                row.failureReason(),
                row.operatorId().toString(),
                row.createdBy()));
    }

    private static final String INSERT_NOTICE_SQL =
            "INSERT INTO refund_offline_notice ("
                    + " notice_id, tenant_id, refund_id, channel, noticed_at, operator_id, note, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?::uuid, ?, ?)";

    /**
     * 追加一条转线下告知留痕。
     *
     * <p>通道合法性由 {@link RefundOfflineNoticeRow#requireLegalChannel} 在<b>进入 SQL 之前</b>校验：
     * 库层 CHECK 会挡住非法通道，但把"为什么『订阅消息』不能写进这张表"这句话
     * 交付给约束名，下一个接手的人只会看到 {@code refund_offline_notice_channel_check}。
     */
    @Override
    public void appendOfflineNotice(String tenantId, RefundOfflineNoticeRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "转线下告知留痕为空，拒绝写入");
        }
        if (row.noticeId() == null || row.refundId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "转线下告知缺主键或工单标识 —— 缺任一项则该告知无法被关联到工单，"
                            + "P0-19『漏发视同未回执』的判定将误判为『已兜底』");
        }
        if (row.operatorId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "转线下告知缺操作人 —— 留痕三要素是『已转线下告知 + 操作人 + 时间』，"
                            + "缺操作人则该行证明不了有人做过这件事");
        }
        if (row.noticedAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "转线下告知缺告知时刻 —— 留痕三要素缺『时间』时无法证明告知发生在漏发之后");
        }
        String channel = RefundOfflineNoticeRow.requireLegalChannel(row.channel());
        requireUuid(tenantId);
        requireUuid(row.noticeId().toString());
        requireUuid(row.refundId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_NOTICE_SQL,
                row.noticeId().toString(),
                row.refundId().toString(),
                channel,
                ts(row.noticedAt()),
                row.operatorId().toString(),
                row.note(),
                row.createdBy()));
    }

    // ==================================================================
    // 二、读：按工单取留痕（三态与告知分别取，绝不 JOIN 合并）
    // ==================================================================

    private static final String SELECT_RECEIPTS_SQL =
            "SELECT receipt_id, refund_id, receipt_state, channel, template_id,"
                    + " decided_at, pushed_at, failure_reason, operator_id, created_by "
                    + "  FROM refund_receipt "
                    + " WHERE refund_id = ?::uuid "
                    + " ORDER BY decided_at, receipt_id";

    /**
     * 取某工单的全部回执留痕（按判定时刻稳定排序）。
     *
     * <p>🛑 排序<b>必须</b>显式给出。依赖数据库返回顺序是"今天恰好对"的写法：
     * 它不报错，只在某次执行计划变化后让"第一条是未授权、第二条是已推送"这类
     * 时序结论静默倒置 —— 而"先判无额度、后补推送"正是要证明的时序。
     *
     * <p>读回时逐行做 {@link ReceiptState#parse}（见 {@link RefundReceiptRow} 的类注释：
     * 让一个拼错的状态在进内存的<b>第一道</b>就被挡下，而不是静默成为一个第四态）。
     */
    @Override
    public List<RefundReceiptRow> findReceipts(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_RECEIPTS_SQL,
                (rs, i) -> new RefundReceiptRow(
                        rs.getObject("receipt_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        ReceiptState.parse(rs.getString("receipt_state")),
                        rs.getString("channel"),
                        rs.getString("template_id"),
                        toInstant(rs.getTimestamp("decided_at")),
                        toInstant(rs.getTimestamp("pushed_at")),
                        rs.getString("failure_reason"),
                        rs.getObject("operator_id", UUID.class),
                        rs.getString("created_by")),
                refundId.toString()));
    }

    private static final String SELECT_NOTICES_SQL =
            "SELECT notice_id, refund_id, channel, noticed_at, operator_id, note, created_by "
                    + "  FROM refund_offline_notice "
                    + " WHERE refund_id = ?::uuid "
                    + " ORDER BY noticed_at, notice_id";

    /** 取某工单的全部转线下告知留痕。 */
    public List<RefundOfflineNoticeRow> findOfflineNotices(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_NOTICES_SQL,
                (rs, i) -> new RefundOfflineNoticeRow(
                        rs.getObject("notice_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getString("channel"),
                        toInstant(rs.getTimestamp("noticed_at")),
                        rs.getObject("operator_id", UUID.class),
                        rs.getString("note"),
                        rs.getString("created_by")),
                refundId.toString()));
    }

    /**
     * 该工单是否已有转线下告知留痕（{@link ReceiptInterpreter#assertOfflineFallbackRecorded}
     * 的取数入口）。
     *
     * <p>🛑 它只查 {@code refund_offline_notice}，<b>不</b>查 {@code refund_receipt} ——
     * 这正是分表的意义：若把两者合并，"有没有人工告知"会退化成
     * "有没有一行回执记录"，而每次推送尝试都会写一行，于是它<b>永远为真</b>。
     */
    @Override
    public boolean hasOfflineNotice(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM refund_offline_notice WHERE refund_id = ?::uuid",
                    Integer.class, refundId.toString());
            return n != null && n > 0;
        });
    }

    // ==================================================================
    // 三、覆盖率取数（三态一次出，缺态补零）
    // ==================================================================

    /**
     * 统计三态分布（<b>一条 SQL 出三数</b>，缺态补 0）。
     *
     * <p>{@code from} / {@code to} 为 {@code null} 表示不设边界（全量）。
     * 时间窗是<b>可选</b>参数而不是"服务层自己拼 where"：
     * 让服务层拼字符串就等于把"分母的时间窗"交给调用方，
     * 而"改了窗口但忘了另一态"是本域最典型的一类静默错误。
     *
     * <p>返回的 Map <b>三态齐备</b>（缺的补 0）。若某态在库里是 0 行，
     * 返回 {@code 0} 而不是"键不存在" —— 后者会让下游不得不用
     * {@code getOrDefault} 兜底，而兜底的默认值一旦写错（例如默认 1）
     * 会直接给分母注入一根假数据。
     */
    @Override
    public Map<ReceiptState, Long> countByState(String tenantId, Instant from, Instant to) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT receipt_state, count(*) AS n FROM refund_receipt WHERE 1 = 1");
            List<Object> args = new java.util.ArrayList<>();
            if (from != null) {
                sql.append(" AND decided_at >= ?");
                args.add(ts(from));
            }
            if (to != null) {
                sql.append(" AND decided_at < ?");
                args.add(ts(to));
            }
            sql.append(" GROUP BY receipt_state");

            Map<ReceiptState, Long> out = new LinkedHashMap<>();
            // 先补零：保证三态都在（缺态 = 0 行，不是"键缺失"）
            for (ReceiptState s : ReceiptState.values()) {
                out.put(s, 0L);
            }
            for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
                // 🛑 库里的字面必须能被 parse，否则抛 —— 静默忽略会让一个
                //    未知状态从分母里消失，而"分母少一块"正是 P0-19 的点名后果。
                ReceiptState s = ReceiptState.parse(String.valueOf(r.get("receipt_state")));
                out.put(s, ((Number) r.get("n")).longValue());
            }
            return out;
        });
    }

    /** 全部时间内的三态分布。 */
    public Map<ReceiptState, Long> countByState(String tenantId) {
        return countByState(tenantId, null, null);
    }

    /** 某工单的回执条数（写入自证用）。 */
    public int countReceipts(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM refund_receipt WHERE refund_id = ?::uuid",
                    Integer.class, refundId.toString());
            return n == null ? 0 : n;
        });
    }

    // ==================================================================
    // 上下文（与 ScaleItemBankRepository 同一套：短事务 + SET LOCAL）
    // ==================================================================

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "标识非合法 UUID，拒绝执行退款留痕 SQL（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}