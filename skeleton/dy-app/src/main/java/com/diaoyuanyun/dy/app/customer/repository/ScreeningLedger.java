package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.app.customer.domain.ScreeningRecordRow;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 禁忌筛查记录的 JDBC 账本（V5 §2.7 {@code screening_record}）。
 *
 * <h2>🛑 本类<b>只有</b> INSERT 与 SELECT —— 缺三种方法是刻意的</h2>
 * 契约 B1 的描述逐字：「命中禁忌（result=不通过）→ 客户状态置 REJECTED，
 * 后续 B2/B3 入口 403 GATE_MISSING（missing_items=["screening_result"]）。
 * 记录<b>不可删除</b>。」
 *
 * <p>"不可删除"在代码里若只写成一句注释，它的强度等于零 ——
 * 下一个人想"清掉一条测试数据"时注释拦不住他，而 {@code jdbc.update("DELETE ...")}
 * 只是一个字符串。故本类<b>不提供</b>以下方法，且这不是"还没写"：
 * <pre>
 *   ✗ updateResult(...)     —— 结论是不可变的：一旦落库即为事实。
 *                             且筛查结论进 customer 的状态机（→ REJECTED 终态），
 *                             改它等于篡改一条不可逆跃迁的依据
 *   ✗ deleteByCustomer(...) —— 删除即断链：B2 门禁的判据是『租户内有没有一条通过记录』，
 *                             删掉一行会让一个本该被拒的建档变成合法
 *   ✗ upsertById(...)       —— upsert 是"就地改写"的另一种写法
 * </pre>
 * 🛑 尤其注意第二条：删一条"不通过"的记录，会让 {@code REJECTED} 的成因消失于历史，
 * 而客户的终态<b>不会</b>因此回退 —— 于是档案里出现"处于 REJECTED 但没有任何不通过记录"
 * 这种无法解释的状态。这正是契约要把"记录不可删除"与"状态置 REJECTED"写在同一句里的原因。
 *
 * <h2>隔离机制（与 {@code RefundReceiptLedger} 同一套）</h2>
 * 每次操作开<b>短事务</b>并 {@code SET LOCAL app.tenant_id}，
 * 使"未设租户上下文 = 零行"这条 fail-closed 性质在<b>所有</b>路径上成立。
 * 本类<b>不</b>在 SQL 里写 {@code WHERE tenant_id = ?} —— 隔离完全由 V5 的
 * {@code FORCE ROW LEVEL SECURITY} 承担（ADR-02）。租户 ID 经 {@link #UUID_PATTERN}
 * 白名单后拼接（{@code SET} 不支持绑定参数）。
 */
@Repository
public class ScreeningLedger {

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ScreeningLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、写：只追加（INSERT only）
    // ==================================================================

    private static final String INSERT_SQL =
            "INSERT INTO screening_record ("
                    + " screening_id, tenant_id, customer_id, items_json, result,"
                    + " operator_id, submitted_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::jsonb, ?, ?::uuid, ?, ?)";

    /**
     * 追加一条筛查记录。
     *
     * <p>🛑 {@code operator_id} 必须<b>来自 token</b>：契约 {@code ScreeningCreateRequest.operator_id}
     * 的 description 逐字标注「服务端从 token 覆写」。服务层负责丢弃请求体里的同名字段
     * 并把 token 解出的 staffId 传进来；本类只校验非空（{@link ScreeningRecordRow} 构造期已校验）。
     *
     * <p>{@code items_json} 走 {@code ?::jsonb} 强转：契约它必填且是结构化多选，
     * 而"结构"这件事只由 {@code ContraindicationPolicy} 在应用层解释 ——
     * 库层只保证它是一个合法 JSON 对象。
     */
    public void append(String tenantId, ScreeningRecordRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "筛查记录为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(row.screeningId().toString());
        requireUuid(row.customerId().toString());
        requireUuid(row.operatorId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_SQL,
                row.screeningId().toString(),
                row.customerId().toString(),
                Json.toJson(row.itemsJson()),
                row.result().code(),
                row.operatorId().toString(),
                ts(row.submittedAt()),
                row.createdBy()));
    }

    // ==================================================================
    // 二、读
    // ==================================================================

    private static final String SELECT_COLUMNS =
            " screening_id, customer_id, items_json, result, operator_id, submitted_at, created_by ";

    /**
     * 取某客户的<b>全部</b>筛查记录（按提交时刻稳定排序）。
     *
     * <p>🛑 返回<b>列表</b>而不是"最新一条"：B2 门禁的判据是
     * 「租户内是否存在一条 {@code result=通过} 的记录」（{@link #hasPassing}）——
     * 若这里只返回最新一条，那么"先通过、后因改题重筛未通过"的客户
     * 会被判为不可建档（正确），而"先未通过、后复筛通过"的客户<b>也会</b>被判为不可建档
     * （错误：复筛通过是允许的，且 {@code REJECTED} 只由"当前判定"决定）。
     * 判据落在 {@link #hasPassing} 的 SQL 里，而不是这里的返回形态上。
     *
     * <p>排序必须显式：依赖数据库返回顺序是"今天恰好对"的写法，
     * 它不报错，只在某次执行计划变化后让"先通过后不通过"的时序结论静默倒置。
     */
    public List<ScreeningRecordRow> findByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT" + SELECT_COLUMNS + "FROM screening_record"
                        + " WHERE customer_id = ?::uuid"
                        + " ORDER BY submitted_at, screening_id",
                (rs, i) -> new ScreeningRecordRow(
                        rs.getObject("screening_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        Json.toMap(rs.getString("items_json")),
                        com.diaoyuanyun.dy.app.customer.domain.ScreeningResult.of(rs.getString("result")),
                        rs.getObject("operator_id", UUID.class),
                        toInstant(rs.getTimestamp("submitted_at")),
                        rs.getString("created_by")),
                customerId.toString()));
    }

    /**
     * 该客户是否存在一条 {@code result=通过} 的筛查记录（<b>B2 门禁的唯一判据</b>）。
     *
     * <h2>🛑 判据是"记录存在"，不是任何状态值</h2>
     * 14 态里<b>没有</b>"筛查通过"态（{@code SCREENING} 表示<b>准入未完成</b>，
     * 见 data-dict §2.25 ② 对 {@code CREATED} 的说明）。故"通过过没有"这件事的
     * 唯一载体就是本表里的一行。若有人改成"看 customer.status"，
     * 那么在客户被 {@code REJECTED} 之后（或 {@code ARCHIVED} 之后）
     * 这条判据会与实际不符，而它<b>不报错</b>。
     *
     * <h2>为什么用 {@code exists} 而不是 {@code count(*)}</h2>
     * {@code count} 要把全部匹配行数出来再丢掉（本表按客户 + result 有索引，
     * 但仍会扫全部分区）；{@code EXISTS} 命中即返回。更重要的是语义：
     * 我们问的是"有没有"，不是"有几条"—— 后者会让下一个人顺手把它当计数用。
     */
    public boolean hasPassing(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM screening_record"
                            + " WHERE customer_id = ?::uuid AND result = ?)",
                    Boolean.class, customerId.toString(), "通过");
            return Boolean.TRUE.equals(exists);
        });
    }

    /**
     * 取<b>最近一条</b>筛查记录（B1 的出参来源：{@code screening_id} / {@code result} / {@code submitted_at}）。
     *
     * <p>排序 {@code ORDER BY submitted_at DESC, screening_id DESC}：
     * 后者是稳定次序键，使同一时刻写入的两条记录之间也有确定顺序
     * （否则分页/取一条的语义在并发下不确定）。
     */
    public ScreeningRecordRow findLatest(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<ScreeningRecordRow> rows = jdbc.query(
                    "SELECT" + SELECT_COLUMNS + "FROM screening_record"
                            + " WHERE customer_id = ?::uuid"
                            + " ORDER BY submitted_at DESC, screening_id DESC"
                            + " LIMIT 1",
                    (rs, i) -> new ScreeningRecordRow(
                            rs.getObject("screening_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            Json.toMap(rs.getString("items_json")),
                            com.diaoyuanyun.dy.app.customer.domain.ScreeningResult.of(rs.getString("result")),
                            rs.getObject("operator_id", UUID.class),
                            toInstant(rs.getTimestamp("submitted_at")),
                            rs.getString("created_by")),
                    customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 取单条（B1 写入自证 / E2E 断言用）。 */
    public ScreeningRecordRow findById(String tenantId, UUID screeningId) {
        requireUuid(tenantId);
        requireUuid(screeningId.toString());
        return inTenant(tenantId, () -> {
            List<ScreeningRecordRow> rows = jdbc.query(
                    "SELECT" + SELECT_COLUMNS + "FROM screening_record WHERE screening_id = ?::uuid",
                    (rs, i) -> new ScreeningRecordRow(
                            rs.getObject("screening_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            Json.toMap(rs.getString("items_json")),
                            com.diaoyuanyun.dy.app.customer.domain.ScreeningResult.of(rs.getString("result")),
                            rs.getObject("operator_id", UUID.class),
                            toInstant(rs.getTimestamp("submitted_at")),
                            rs.getString("created_by")),
                    screeningId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ==================================================================
    // 上下文（与 RefundReceiptLedger / ScaleItemBankRepository 同一套）
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
                    "标识非合法 UUID，拒绝执行筛查记录 SQL"
                            + "（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** JSONB 与 {@code Map} 的互转（本域四本账本共用，见 {@link Json} 的类注释）。 */
    static Map<String, Object> mapOrEmpty(String json) {
        return Json.toMap(json);
    }
}