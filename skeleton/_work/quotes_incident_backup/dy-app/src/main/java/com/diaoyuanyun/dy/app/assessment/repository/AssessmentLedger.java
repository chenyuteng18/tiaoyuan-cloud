package com.diaoyuanyun.dy.app.assessment.repository;

import com.diaoyuanyun.dy.app.assessment.domain.BaselineAssessmentRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code baseline_assessment} / {@code scale} / {@code staff} 的 JDBC 仓储（V5 建表）。
 *
 * <h2>范围（为什么只有这三张表的<b>局部</b>访问）</h2>
 * 本仓储只服务契约域 C 的 C2（基线评估提交）与 C3（评估详情）：
 * <pre>
 *   写 {@code baseline_assessment}（一行，append 语义）
 *   读 {@code baseline_assessment}（按 assessment_id + customer_id）
 *   校验 {@code scale} 存在性（C2 的 scale_id 反查）
 *   校验 {@code staff} 存在性（C2 的 measure_operator 预检，避免 FK 23503 变 500）
 * </pre>
 * 它<b>不</b>访问 {@code scale_item_bank}（题库内容）—— 那是 {@code scale} 域
 * {@code ScaleItemBankRepository} 的职责。C2 的"题组完整性"校验经服务层调用那个仓储，
 * 而非在这里跨域直连 —— 保持"每个仓储只认自己域的表"，与域 B 各账本同纪律。
 *
 * <h2>RLS 是隔离的唯一防线（不写 WHERE tenant_id）</h2>
 * 与 {@code ScaleItemBankRepository} / {@code CustomerLedger} 同一套：隔离完全由 V5 的
 * {@code FORCE ROW LEVEL SECURITY} 承担，本类不在 SQL 里写租户过滤。
 * 写入时 {@code tenant_id} 取 {@code current_setting('app.tenant_id', true)}，
 * 与策略的 {@code USING/WITH CHECK} 同源 —— 这是"写也受隔离约束"的关键：
 * 若策略失效，跨租户行立刻可见/可写，由 RLS 隔离测试的真库断言守着。
 *
 * <h2>为什么"每次操作都开一个短事务并 SET LOCAL"</h2>
 * {@code SET LOCAL} 只在事务内有效。把它与执行 SQL 收敛进一个短事务，
 * 使「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上成立。
 */
@Repository
public class AssessmentLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AssessmentLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ------------------------------------------------------------------
    // 写：基线评估（append 语义，不可覆盖）
    // ------------------------------------------------------------------

    private static final String INSERT_SQL =
            "INSERT INTO baseline_assessment ("
                    + " assessment_id, tenant_id, customer_id, scale_id,"
                    + " metrics_json, diagnosis_json, locked, migratable, age_group_locked,"
                    + " item_group_id, measure_operator, assist_operator,"
                    + " assessed_at, legacy, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::uuid, ?::jsonb, ?::jsonb, TRUE, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, FALSE, ?)";

    /**
     * 写入一行基线评估。
     *
     * <p>🛑 唯一键冲突（若有）即抛 —— 本表没有唯一键约束在
     * {@code customer_id} 上（同一客户理论上可补做基线），故这里不捕获
     * {@code DuplicateKeyException}；若将来字典为"一客户一基线"加唯一键，
     * 服务层应在此处把冲突翻译成"该客户已存在基线评估（不可覆盖）"。
     */
    public void insertBaseline(String tenantId, BaselineAssessmentRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_SQL,
                row.assessmentId().toString(),
                row.customerId().toString(),
                row.scaleId().toString(),
                row.metricsJson(),
                row.diagnosisJson(),
                row.migratable(),
                row.ageGroupLocked(),
                row.itemGroupId() == null ? null : row.itemGroupId().toString(),
                row.measureOperator().toString(),
                row.assistOperator() == null ? null : row.assistOperator().toString(),
                Timestamp.from(row.assessedAt()),
                row.createdBy()));
    }

    // ------------------------------------------------------------------
    // 读：基线评估（C3 详情）
    // ------------------------------------------------------------------

    private static final String SELECT_BY_ID_SQL =
            "SELECT assessment_id, customer_id, scale_id, item_group_id,"
                    + " age_group_locked, metrics_json, diagnosis_json, migratable,"
                    + " measure_operator, assist_operator, assessed_at, legacy "
                    + "  FROM baseline_assessment "
                    + " WHERE assessment_id = ?::uuid AND customer_id = ?::uuid";

    /**
     * 按 assessment_id + customer_id 取一行基线评估。
     *
     * <p>🛑 同时以 {@code customer_id} 过滤：C3 路径即
     * {@code /customers/{id}/assessments/{assessment_id}}，两段都来自路径。
     * 双键过滤不是冗余 —— 它把"评估属于别的客户"与"评估不存在"在查询层就区分掉，
     * 避免服务层拿回一条跨客户的数据再手工判越权。
     */
    public BaselineAssessmentRow findById(String tenantId, UUID assessmentId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<BaselineAssessmentRow> rows = jdbc.query(SELECT_BY_ID_SQL, (rs, i) ->
                    new BaselineAssessmentRow(
                            rs.getObject("assessment_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getObject("scale_id", UUID.class),
                            rs.getObject("item_group_id", UUID.class),
                            rs.getString("age_group_locked"),
                            rs.getString("metrics_json"),
                            rs.getString("diagnosis_json"),
                            rs.getBoolean("migratable"),
                            rs.getObject("measure_operator", UUID.class),
                            rs.getObject("assist_operator", UUID.class),
                            rs.getTimestamp("assessed_at").toInstant(),
                            rs.getBoolean("legacy"),
                            null),  // created_by 读路径不回填（不是 C3 出参）
                    assessmentId.toString(), customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ------------------------------------------------------------------
    // 存在性校验（C2 预检，避免 FK/外键冲突变成 500）
    // ------------------------------------------------------------------

    /** scale 表是否存在该量表（C2 的 scale_id 反查）。 */
    public boolean scaleExists(String tenantId, UUID scaleId) {
        requireUuid(tenantId);
        requireUuid(scaleId.toString());
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid)",
                    Boolean.class, scaleId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    /** scale 表的版本号（migratable 的"量程版本"要素）；不存在或无版本 → null。 */
    public String scaleVersion(String tenantId, UUID scaleId) {
        requireUuid(tenantId);
        requireUuid(scaleId.toString());
        return inTenant(tenantId, () -> {
            List<String> v = jdbc.query(
                    "SELECT scale_version FROM scale WHERE scale_id = ?::uuid",
                    (rs, i) -> rs.getString("scale_version"),
                    scaleId.toString());
            return v.isEmpty() ? null : v.get(0);
        });
    }

    /** staff 表是否存在该员工（C2 的 measure_operator 预检）。 */
    public boolean staffExists(String tenantId, UUID staffId) {
        requireUuid(tenantId);
        requireUuid(staffId.toString());
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM staff WHERE staff_id = ?::uuid)",
                    Boolean.class, staffId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    private static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户/标识非合法 UUID，拒绝执行评估 SQL");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }
}