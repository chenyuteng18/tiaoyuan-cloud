package com.diaoyuanyun.dy.app.refund.repository;

import com.diaoyuanyun.dy.app.refund.domain.AmountBasis;
import com.diaoyuanyun.dy.app.refund.domain.RefundEntry;
import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundReasonCode;
import com.diaoyuanyun.dy.app.refund.domain.RefundRetentionRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundRoute;
import com.diaoyuanyun.dy.app.refund.domain.RefundStatementRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderRow;
import com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource;
import com.diaoyuanyun.dy.app.refund.domain.RetentionResult;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 退款工单及其从属记录（{@code refund} / {@code retention} / {@code refund_statement}）的 JDBC 仓储。
 *
 * <h2>🛑 本类与 {@link RefundReceiptLedger} 的差别，只有一处，但它是全局的</h2>
 * <pre>
 *   RefundReceiptLedger   只有一个「写」动作：INSERT。没有 update / delete。
 *   本类                  有且仅有一个 update 动作：{@link #updateOutcome}。
 * </pre>
 * 这个 update 是<b>必要的</b>：{@code refund.outcome} 是一张工单的业务事实，
 * 它必然从 {@code 继续 / 终止} 演进到 {@code 归档}（P0-14「四种结局全部强制归档」）。
 * 若这里也不给 update，归档动作只能靠"删旧行插新行"实现 —— 而删行会让
 * 挽留、原话、回执三条以 {@code refund_id} 为外键的链一起断掉。
 *
 * <p>故本类的纪律不是"没有 update"，而是<b>"唯一的 update 只改结局，且只向前"</b>：
 * <ol>
 *   <li><b>方法只有一个</b>：{@link #updateOutcome}。想改客户、改原因码、改金额分摊，
 *       都没有可写出的调用序列 —— 那些要重开一张工单（更正留痕而不是改写历史）；</li>
 *   <li><b>方向校验在 SQL 的 WHERE 里</b>：{@code AND outcome = ?}（期望值）。
 *       0 行受影响即抛 {@code VERSION_CONFLICT(4001)}。于是"两端同时操作，
 *       后写覆盖先写"在数据库层面不可能发生 —— 而那在退款场景里
 *       意味着一次审批结论被静默抹掉；</li>
 *   <li><b>归档后拒写</b>由服务层 {@code RetentionPolicy.assertWritable} 做，
 *       本类<b>不</b>重复判定：仓储不判业务，否则同一条规则会有两个实现、必然分叉。</li>
 * </ol>
 *
 * <h2>🛑 为什么写入路径的 SQL <b>不含</b> {@code created_at} / {@code recorded_at}</h2>
 * {@code retention.created_at} / {@code refund_statement.recorded_at} 两列在库里都是
 * {@code DEFAULT now()}。本类刻意把它们从 INSERT 列清单里拿掉：
 * <pre>
 *   若写入时绑定应用服务器的 now()，就出现两个时间源；
 *   而"挽留时长 = retention.created_at − refund.recorded_at"
 *   正是靠这两个时刻相减得到的。两时钟漂移 → 出现负值时，
 *   报表只会显示"这个店响应得特别快"。
 * </pre>
 * 故 {@code refund.recorded_at} 是<b>唯一例外</b>（见 {@link #INSERT_WORK_ORDER_SQL}）：
 * 它是"代录动作"的业务事实、必须与 {@code requested_at} 同一时间源比较，
 * 故由调用方显式给定、库层 {@code DEFAULT now()} 只是兜底。
 *
 * <h2>隔离机制（与 {@code ScaleItemBankRepository} 同一套）</h2>
 * 每次操作开<b>短事务</b> + {@code SET LOCAL app.tenant_id}；不在 SQL 里写
 * {@code WHERE tenant_id = ?} —— 隔离完全由 V5 / V6 的 {@code FORCE ROW LEVEL SECURITY}
 * 承担（ADR-02）。租户 ID 经 {@link #UUID_PATTERN} 白名单后拼接（{@code SET} 不支持绑定参数）。
 */
@Repository
public class RefundWorkOrderLedger implements RefundWorkOrderPort {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RefundWorkOrderLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、工单：INSERT + 唯一一个受控 UPDATE
    // ==================================================================

    /**
     * 新增一张工单。
     *
     * <p>🛑 {@code entry} 绑定的是 {@link RefundEntry#dbCode()}（<b>有空格</b>的
     * {@code 'A 门店代录'}），不是 {@code contractCode()}。V5 §2.20 的 CHECK
     * 只认库侧字面；绑错会以一次 23514（check violation）暴露 ——
     * 而那时错误信息里只有约束名，看不出"两套字面"这件事。
     * 故方向在这里显式写死：<b>入库一律 dbCode</b>。
     */
    private static final String INSERT_WORK_ORDER_SQL =
            "INSERT INTO refund ("
                    + " refund_id, tenant_id, customer_id, entry, refund_route, liable_store_id,"
                    + " reason_code, requested_at, requested_at_claimed, requested_at_source,"
                    + " requested_at_source_ref, recorded_at, recording_delay_h, sla_due_at,"
                    + " outcome, amount_split_json, amount_basis, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    @Override
    public void insertWorkOrder(String tenantId, RefundWorkOrderRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "退款工单为空，拒绝写入");
        }
        requireNonNull(row.refundId(), "工单主键");
        requireNonNull(row.customerId(), "客户标识");
        requireNonNull(row.entry(), "入口（entry）");
        requireNonNull(row.route(), "通路（refund_route）");
        requireNonNull(row.liableStoreId(), "责任主体（签约店）");
        requireNonNull(row.reasonCode(), "原因码（reason_code）");
        requireNonNull(row.outcome(), "结局（outcome）");
        requireNonNull(row.recordedAt(), "代录时间（recorded_at）");

        // 🛑 三字段一致性在【进入 SQL 之前】拦：库层无法表达
        //    「hasSource ⟺ hasRequested」这种跨列等价（CHECK 只能逐行表达可空组合），
        //    而漏掉它会让"时间照填、来源不填"落库成功 —— 那正是 C1-2 拆分被抹掉的方式。
        if ((row.requestedAt() == null) != (row.requestedAtSource() == null)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "requested_at 与 requested_at_source 必须成对出现（P0-19 C1-2）—— "
                            + "当前 requested_at=" + row.requestedAt()
                            + "、source=" + row.requestedAtSource() + "。"
                            + "V6 列注释逐字写着『无来源标注的 requested_at 视为不可核实，不得进 24h 计时』");
        }
        if (row.requestedAtSource() != null && row.requestedAtSource().requiresEvidenceRef()
                && isBlank(row.requestedAtSourceRef())) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "来源标注为『客户自证』但缺凭据引用（requested_at_source_ref）—— "
                            + "P0-19 C1-2：客户可自证的时间须附截图 / 通话记录引用；"
                            + "无凭据的自述时间应走 requested_at_claimed 留档");
        }
        if (row.requestedAt() != null && row.requestedAt().isAfter(row.recordedAt())) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "requested_at 晚于 recorded_at —— 该组合会让代录延迟为负，"
                            + "而下游取绝对值会把一次时间戳写错变成一次合规判定");
        }

        requireUuid(tenantId);
        requireUuid(row.refundId().toString());
        requireUuid(row.customerId().toString());
        requireUuid(row.liableStoreId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_WORK_ORDER_SQL,
                row.refundId().toString(),
                row.customerId().toString(),
                row.entry().dbCode(),
                row.route().code(),
                row.liableStoreId().toString(),
                row.reasonCode().code(),
                ts(row.requestedAt()),
                ts(row.requestedAtClaimed()),
                row.requestedAtSource() == null ? null : row.requestedAtSource().code(),
                row.requestedAtSourceRef(),
                ts(row.recordedAt()),
                row.recordingDelayH(),
                ts(row.slaDueAt()),
                row.outcome().code(),
                row.amountSplitJson(),
                row.amountBasis() == null ? null : row.amountBasis().code(),
                row.createdBy()));
    }

    /**
     * 推进工单结局（<b>乐观并发</b>）。
     *
     * <h2>两条都在 SQL 里，而不是"先读再判再写"</h2>
     * <pre>
     *   AND outcome = ?          ← 期望值（调用方读到的当前值）
     *   AND outcome &lt;&gt; '归档'    ← 已归档的工单不可再被推进
     * </pre>
     * 若改成"先 SELECT 校验、再 UPDATE"，两个请求可以同时通过校验、
     * 后一个覆盖前一个 —— 而本域"后写覆盖先写"的后果是一次审批结论被抹掉。
     * 把条件放进 {@code WHERE} 使判定与写入成为<b>同一条语句</b>，
     * 数据库的锁语义即成为并发正确性的来源，而不是应用层的时序假设。
     *
     * <p>0 行受影响时抛 {@code VERSION_CONFLICT(4001)} 而不是 {@code NOT_FOUND}：
     * 两种原因（工单不存在 / 结局已变）都会导致 0 行，而它们的处置方式不同 ——
     * 前者要调用方核对 ID、后者要调用方重新读取。消息里把期望值与实际可能性都点出来，
     * 使调用方能自己区分，而不是靠猜。
     */
    private static final String UPDATE_OUTCOME_SQL =
            "UPDATE refund SET outcome = ?, updated_at = now() "
                    + " WHERE refund_id = ?::uuid AND outcome = ? AND outcome <> '归档'";

    @Override
    public void updateOutcome(String tenantId, UUID refundId,
                              RefundOutcome expectedOutcome, RefundOutcome nextOutcome) {
        requireUuid(tenantId);
        requireNonNull(refundId, "工单主键");
        requireNonNull(expectedOutcome, "期望结局（乐观并发的比较值）");
        requireNonNull(nextOutcome, "目标结局");

        if (expectedOutcome == nextOutcome) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "目标结局与期望结局相同（" + nextOutcome.code() + "）—— "
                            + "一次不改动任何东西的写入没有业务含义，"
                            + "而它在乐观并发下会静默成功（WHERE 命中自己），"
                            + "让调用方误以为推进发生过");
        }
        // 归档是结案状态位，不是可"推进"到的业务结局：它的入口只有 archiveIfOpen 一条路径。
        if (nextOutcome.isClosed()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "不得用 updateOutcome 把工单推进到『归档』—— 归档是一次收口动作"
                            + "（PRD P0-14『四种结局全部强制归档』），"
                            + "必须走归档路径以便同时落 case_archive 与结案清单校验。"
                            + "若从本方法可达，归档就会退化成一次普通的字段改写");
        }

        int n = inTenant(tenantId, () -> jdbc.update(UPDATE_OUTCOME_SQL,
                nextOutcome.code(), refundId.toString(), expectedOutcome.code()));

        if (n == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "工单结局推进失败：期望值 " + expectedOutcome.code() + " 与库中当前值不符，"
                            + "或该工单不存在，或已被归档。"
                            + "🛑 本判定与写入在同一条 UPDATE 里完成 —— 不得改成"
                            + "『先读、再判、后写』：那会让两个并发请求同时通过校验，"
                            + "而后一个覆盖前一个（在本域意味着一次审批结论被静默抹掉）");
        }
    }

    // ==================================================================
    // 二、工单：读
    // ==================================================================

    private static final String SELECT_WORK_ORDER_SQL =
            "SELECT refund_id, customer_id, entry, refund_route, liable_store_id, reason_code,"
                    + " requested_at, requested_at_claimed, requested_at_source, requested_at_source_ref,"
                    + " recorded_at, recording_delay_h, sla_due_at, outcome, amount_split_json,"
                    + " amount_basis, created_by "
                    + "  FROM refund WHERE refund_id = ?::uuid";

    @Override
    public RefundWorkOrderRow findWorkOrder(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireNonNull(refundId, "工单主键");
        requireUuid(refundId.toString());
        List<RefundWorkOrderRow> rows = inTenant(tenantId, () -> jdbc.query(
                SELECT_WORK_ORDER_SQL,
                (rs, i) -> new RefundWorkOrderRow(
                        rs.getObject("refund_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        // 读路径用 parse（接受两套字面），出站时由服务层取 contractCode ——
                        // 让"宽容留在入口、严格留在出口"。
                        RefundEntry.parse(rs.getString("entry")),
                        RefundRoute.parse(rs.getString("refund_route")),
                        rs.getObject("liable_store_id", UUID.class),
                        RefundReasonCode.parse(rs.getString("reason_code")),
                        toInstant(rs.getTimestamp("requested_at")),
                        toInstant(rs.getTimestamp("requested_at_claimed")),
                        // 🛑 走 parseSourceOrNull 而不是 parse：本列在库里【可空】
                        //    （入口 B 的工单合法地为空），而 parse 对 null 是抛。
                        //    直接把 null 交给 parse 会让一张正常的入口 B 工单在读回时炸掉。
                        parseSourceOrNull(rs.getString("requested_at_source")),
                        rs.getString("requested_at_source_ref"),
                        toInstant(rs.getTimestamp("recorded_at")),
                        rs.getBigDecimal("recording_delay_h"),
                        toInstant(rs.getTimestamp("sla_due_at")),
                        RefundOutcome.parse(rs.getString("outcome")),
                        rs.getString("amount_split_json"),
                        AmountBasis.parse(rs.getString("amount_basis")),
                        rs.getString("created_by")),
                refundId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * {@code RequestedAtSource.parse} 对 {@code null} 是<b>抛</b>（因为它必填），
     * 而本列在库里可空（入口 B 的工单合法地为空）。
     *
     * <p>故读路径不能直接把 {@code null} 交给 {@code parse} —— 那会让一张
     * 完全正常的入口 B 工单在读回时炸掉。本方法把"空"这一种情况显式转成 {@code null}，
     * 而让非空值仍然走 parse（未知字面必须报错，不得静默变成"无来源"）。
     */
    static RequestedAtSource parseSourceOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : RequestedAtSource.parse(raw);
    }

    /**
     * {@code RetentionResult.parse} 对 {@code null} 是抛 —— 而 {@code retention.result}
     * 在库层是 {@code NOT NULL}，故"读到空"只可能是数据损坏，抛错正是想要的（fail-closed）。
     *
     * <p>抽成本方法只为让 {@link #findRetentions} 的 lambda 不必写一行三目，
     * 并让"这里为什么直接 parse"有一个可被读到的落点。
     */
    static RetentionResult retentionResult(String raw) {
        return RetentionResult.parse(raw);
    }

    // ==================================================================
    // 三、挽留（INSERT + 追加序读）
    // ==================================================================

    /**
     * 🛑 列清单里<b>没有</b> {@code created_at}：由库层 {@code DEFAULT now()} 生成。
     * 这一条是"挽留时长不出现负值"的机械保证（见类注释）。
     */
    private static final String INSERT_RETENTION_SQL =
            "INSERT INTO retention ("
                    + " retention_id, tenant_id, refund_id, attempts, script_version, result,"
                    + " operator_id, analysis, communication, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?, ?::uuid, ?::jsonb, ?::jsonb, ?)";

    @Override
    public void insertRetention(String tenantId, RefundRetentionRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "挽留记录为空，拒绝写入");
        }
        requireNonNull(row.retentionId(), "挽留记录主键");
        requireNonNull(row.refundId(), "工单标识");
        requireNonNull(row.result(), "挽留结果");
        requireNonNull(row.operatorId(), "操作人");
        requireNonBlank(row.analysisJson(), "五维原因分析（analysis）");
        requireNonBlank(row.communicationJson(), "沟通记录（communication）");
        if (row.attempts() < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "挽留尝试次数为负（" + row.attempts() + "）—— 库 CHECK 只挡数值范围，"
                            + "而负数在这里的成因是『忘了填就取了默认减一』这类构造错误，"
                            + "报错应指向构造点而不是数据库");
        }
        requireUuid(tenantId);
        requireUuid(row.retentionId().toString());
        requireUuid(row.refundId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_RETENTION_SQL,
                row.retentionId().toString(),
                row.refundId().toString(),
                row.attempts(),
                row.scriptVersion(),
                row.result().code(),
                row.operatorId().toString(),
                row.analysisJson(),
                row.communicationJson(),
                row.createdBy()));
    }

    private static final String SELECT_RETENTIONS_SQL =
            "SELECT retention_id, refund_id, attempts, script_version, result, operator_id,"
                    + " analysis, communication, created_at, created_by "
                    + "  FROM retention WHERE refund_id = ?::uuid "
                    + " ORDER BY created_at, retention_id";

    @Override
    public List<RefundRetentionRow> findRetentions(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireNonNull(refundId, "工单标识");
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_RETENTIONS_SQL,
                (rs, i) -> new RefundRetentionRow(
                        rs.getObject("retention_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getString("script_version"),
                        // 🛑 不用 RetentionResult.parse(null)：该表 result NOT NULL，
                        //    读到空即数据损坏，parse 抛错正是想要的（fail-closed）。
                        retentionResult(rs.getString("result")),
                        rs.getObject("operator_id", UUID.class),
                        rs.getString("analysis"),
                        rs.getString("communication"),
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getString("created_by")),
                refundId.toString()));
    }

    // ==================================================================
    // 四、客户原话（append-only：只有 INSERT 与 SELECT）
    // ==================================================================

    /**
     * 🛑 列清单里同样<b>没有</b> {@code created_at}（库层 DEFAULT），
     * 但<b>有</b> {@code recorded_at} —— 后者是"这句话是什么时候说的"这一业务事实，
     * 与"这一行是什么时候写进去的"（created_at）不同，故由写入路径显式给定。
     */
    private static final String INSERT_STATEMENT_SQL =
            "INSERT INTO refund_statement ("
                    + " statement_id, tenant_id, refund_id, statement_text, statement_source,"
                    + " supersedes_statement_id, recorded_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    // 🛑 recorded_at 用 COALESCE(?, now())：写路径由 RefundStatementRow.forInsert
                    //    产出 null（其契约就是"写路径忽略、读路径由库回填"），而本列是
                    //    NOT NULL DEFAULT now()。PostgreSQL 的 DEFAULT 只在【列被省略】时生效 ——
                    //    显式绑 NULL 会直接违反 NOT NULL，故必须在此兜底成 now()。
                    //    若改成直绑 ?，任何带客户原话的立案都会 100% 失败。
                    + " ?::uuid, ?, ?, ?::uuid, COALESCE(?, now()), ?)";

    /**
     * 追加一条客户原话。
     *
     * <p>🛑 本方法<b>不做 upsert、不吞并冲突</b>：更正靠新行声明
     * {@code supersedesStatementId}，旧行必须原样留着 ——
     * 中山中院 2026-04 判词（凭证须可提交）要求的是<b>全过程</b>，
     * 而"只留最新一版"会让"客户改过口径"这件事在数据里消失。
     */
    @Override
    public void insertStatement(String tenantId, RefundStatementRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "客户原话为空，拒绝写入");
        }
        requireNonNull(row.statementId(), "原话主键");
        requireNonNull(row.refundId(), "工单标识");
        requireNonBlank(row.statementText(), "原话本体（statement_text）");
        String source = RefundStatementRow.requireLegalSource(row.statementSource());
        // 🛑 刻意【不】校验 recorded_at 非空：本字段的写路径契约就是 null
        //    （见 RefundStatementRow 类注释的三条既定事实），由 SQL 的
        //    COALESCE(?, now()) 落成库时间。此前这里有一句 requireNonNull，
        //    与 forInsert 的产出直接冲突 —— 后果是任何带客户原话的立案 100% 失败。
        //    写路径真正需要守的不是"值存在"，而是"来源合法"（上一行已守）。

        // 🛑 自指拒绝：一条原话不能声明"我取代我自己"。
        //    否则读路径沿 supersedes 链回溯时会死循环（而它是证据链的遍历方式）。
        if (row.statementId().equals(row.supersedesStatementId())) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "原话的自指更正（supersedes 指向自身）—— 沿取代链回溯会死循环，"
                            + "而该遍历正是『客户改过几次口径』的取证方式");
        }
        requireUuid(tenantId);
        requireUuid(row.statementId().toString());
        requireUuid(row.refundId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_STATEMENT_SQL,
                row.statementId().toString(),
                row.refundId().toString(),
                row.statementText(),
                source,
                row.supersedesStatementId() == null ? null : row.supersedesStatementId().toString(),
                ts(row.recordedAt()),
                row.createdBy()));
    }

    private static final String SELECT_STATEMENTS_SQL =
            "SELECT statement_id, refund_id, statement_text, statement_source,"
                    + " supersedes_statement_id, recorded_at, created_by "
                    + "  FROM refund_statement WHERE refund_id = ?::uuid "
                    + " ORDER BY recorded_at, statement_id";

    @Override
    public List<RefundStatementRow> findStatements(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireNonNull(refundId, "工单标识");
        requireUuid(refundId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_STATEMENTS_SQL,
                (rs, i) -> new RefundStatementRow(
                        rs.getObject("statement_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getString("statement_text"),
                        rs.getString("statement_source"),
                        rs.getObject("supersedes_statement_id", UUID.class),
                        toInstant(rs.getTimestamp("recorded_at")),
                        rs.getString("created_by")),
                refundId.toString()));
    }

    /**
     * 🛑 取"最新一条原话"用<b>数据库自己的排序</b>，而不是
     * {@link #findStatements} 的末元素。
     *
     * <p>{@code LIMIT 1} 与列表的 {@code ORDER BY recorded_at, statement_id} <b>同键</b>，
     * 故"最新"在两处口径一致。若这里改用列表末元素，两者的差别只在
     * 并发写入（同一时刻两条）时显现 —— 而那时两处会给出不同答案，
     * 且没有任何测试会红（因为它只在竞态下发生）。
     */
    private static final String SELECT_LATEST_STATEMENT_SQL =
            "SELECT statement_id, refund_id, statement_text, statement_source,"
                    + " supersedes_statement_id, recorded_at, created_by "
                    + "  FROM refund_statement WHERE refund_id = ?::uuid "
                    + " ORDER BY recorded_at DESC, statement_id DESC LIMIT 1";

    @Override
    public RefundStatementRow findLatestStatement(String tenantId, UUID refundId) {
        requireUuid(tenantId);
        requireNonNull(refundId, "工单标识");
        requireUuid(refundId.toString());
        List<RefundStatementRow> rows = inTenant(tenantId, () -> jdbc.query(
                SELECT_LATEST_STATEMENT_SQL,
                (rs, i) -> new RefundStatementRow(
                        rs.getObject("statement_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getString("statement_text"),
                        rs.getString("statement_source"),
                        rs.getObject("supersedes_statement_id", UUID.class),
                        toInstant(rs.getTimestamp("recorded_at")),
                        rs.getString("created_by")),
                refundId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================================================================
    // 五、上下文（与 ScaleItemBankRepository 同一套：短事务 + SET LOCAL）
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
                    "标识非合法 UUID，拒绝执行退款工单 SQL（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }

    /** 供服务层在写库前校验租户标识（与回执账本共用同一套白名单）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 不得为空，拒绝写入");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}