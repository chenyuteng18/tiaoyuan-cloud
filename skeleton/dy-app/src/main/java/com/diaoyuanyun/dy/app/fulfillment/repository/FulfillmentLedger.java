package com.diaoyuanyun.dy.app.fulfillment.repository;

import com.diaoyuanyun.dy.app.fulfillment.domain.DailyReportRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.DeviceDispatchRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanReviewRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.VisitRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 履约域（契约域 D）的 JDBC 仓储 —— {@code visit} / {@code daily_report} 的写读，
 * 及四道闸门所依赖的 {@code screening_record} / {@code consent} / {@code agreement} /
 * {@code plan} 的存在性断言。
 *
 * <h2>范围（为什么只访问这六张表的<b>局部</b>）</h2>
 * 本仓储只服务 D1~D6。四道闸门的三张非本域表（screening/consent/agreement）只做
 * {@code EXISTS} 断言，<b>不</b>读完整行 —— 它们的权威在 customer 域各自的账本，
 * 本域只问"是否满足"，不复制"怎么满足"。
 *
 * <h2>RLS 是隔离的唯一防线（不写 WHERE tenant_id）</h2>
 * 与既有各仓储同款：隔离完全由 V5 的 {@code FORCE RLS} 承担，本类不在 SQL 里写租户过滤。
 * 写入时 {@code tenant_id} 取 {@code current_setting('app.tenant_id', true)}，
 * 与策略的 {@code USING/WITH CHECK} 同源。
 *
 * <h2>🛑 {@code visit_no} 的解算与并发</h2>
 * 客户维度全局唯一账本（U2）：服务层在<b>单事务内</b>做
 * {@code SELECT coalesce(max(visit_no),0)+1} 再 INSERT，
 * 并发冲突由 {@code UNIQUE(customer_id, visit_no)} 兜底（4002/409 重试）。
 * 本类提供 {@link #nextVisitNo}（事务内取号）与 {@link #insertVisit}（写）。
 */
@Repository
public class FulfillmentLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FulfillmentLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ------------------------------------------------------------------
    // 四道闸门（存在性断言）
    // ------------------------------------------------------------------

    /** ① 禁忌是否通过（存在 result=通过 的筛查记录）。 */
    public boolean screeningPassed(String tenantId, UUID customerId) {
        return exists(tenantId,
                "SELECT EXISTS (SELECT 1 FROM screening_record"
                        + " WHERE customer_id = ?::uuid AND result = '通过')",
                customerId.toString());
    }

    /** ② 知情同意书是否已签（存在 consent 行）。 */
    public boolean consentSigned(String tenantId, UUID customerId) {
        return exists(tenantId,
                "SELECT EXISTS (SELECT 1 FROM consent WHERE customer_id = ?::uuid)",
                customerId.toString());
    }

    /** ③ 调理协议是否已签（存在 agreement 行）。 */
    public boolean agreementSigned(String tenantId, UUID customerId) {
        return exists(tenantId,
                "SELECT EXISTS (SELECT 1 FROM agreement WHERE customer_id = ?::uuid)",
                customerId.toString());
    }

    /** ④ 方案是否已确认且在有效期（存在 status=approved 的 plan）。 */
    public boolean planApproved(String tenantId, UUID customerId) {
        return exists(tenantId,
                "SELECT EXISTS (SELECT 1 FROM plan WHERE customer_id = ?::uuid AND status = 'approved')",
                customerId.toString());
    }

    private boolean exists(String tenantId, String sql, String arg) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(sql, Boolean.class, arg);
            return Boolean.TRUE.equals(e);
        });
    }

    // ------------------------------------------------------------------
    // visit 写读（客户维度全局唯一账本）
    // ------------------------------------------------------------------

    private static final String INSERT_VISIT_SQL =
            "INSERT INTO visit ("
                    + " visit_id, tenant_id, customer_id, serving_store_id, plan_id,"
                    + " plan_version, gate_check_json, customer_confirmed,"
                    + " executed_at, visit_no, part_method, duration_min,"
                    + " pre_feedback, post_feedback, abnormal_note, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, TRUE, ?, ?, ?, ?, ?, ?, ?, ?)";

    public void insertVisit(String tenantId, VisitRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_VISIT_SQL,
                row.visitId().toString(),
                row.customerId().toString(),
                row.servingStoreId().toString(),
                row.planId().toString(),
                row.planVersion(),
                row.gateCheckJson(),
                Timestamp.from(row.executedAt()),
                row.visitNo(),
                row.partMethod(),
                row.durationMin(),
                row.preFeedback(),
                row.postFeedback(),
                row.abnormalNote(),
                row.createdBy()));
    }

    /** 取下一客户维度服务序号（事务内 {@code MAX+1}；并发由唯一索引兜底）。 */
    public int nextVisitNo(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Integer max = jdbc.queryForObject(
                    "SELECT coalesce(max(visit_no), 0) FROM visit WHERE customer_id = ?::uuid",
                    Integer.class, customerId.toString());
            return (max == null ? 0 : max) + 1;
        });
    }

    private static final String SELECT_VISIT_COLUMNS =
            "visit_id, customer_id, serving_store_id, plan_id, plan_version,"
                    + " gate_check_json, executed_at, visit_no, part_method, duration_min,"
                    + " pre_feedback, post_feedback, abnormal_note, created_by";

    /** D2 —— 客户维度服务记录（按 visit_no 升序，全局账本逐次）。 */
    public List<VisitRow> findByCustomer(String tenantId, UUID customerId, int page, int pageSize) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT " + SELECT_VISIT_COLUMNS + " FROM visit"
                        + " WHERE customer_id = ?::uuid"
                        + " ORDER BY visit_no"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new VisitRow(
                        rs.getObject("visit_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getObject("serving_store_id", UUID.class),
                        rs.getObject("plan_id", UUID.class),
                        rs.getInt("plan_version"),
                        rs.getString("gate_check_json"),
                        rs.getInt("visit_no"),
                        rs.getString("part_method"),
                        rs.getObject("duration_min", Integer.class),
                        rs.getString("pre_feedback"),
                        rs.getString("post_feedback"),
                        rs.getString("abnormal_note"),
                        rs.getTimestamp("executed_at").toInstant(),
                        rs.getString("created_by")),
                customerId.toString(), pageSize, (page - 1) * pageSize));
    }

    /** D2 —— 客户维度服务总次数（与 items 同一份 WHERE/排序源）。 */
    public int countByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM visit WHERE customer_id = ?::uuid",
                Integer.class, customerId.toString()));
    }

    // ------------------------------------------------------------------
    // daily_report 写读
    // ------------------------------------------------------------------

    private static final String INSERT_REPORT_SQL =
            "INSERT INTO daily_report ("
                    + " report_id, tenant_id, customer_id, date, answers_json,"
                    + " source, submitted_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?::jsonb, ?, ?, ?)";

    public void insertDailyReport(String tenantId, DailyReportRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_REPORT_SQL,
                row.reportId().toString(),
                row.customerId().toString(),
                Date.valueOf(row.date()),
                row.answersJson(),
                row.source(),
                Timestamp.from(row.submittedAt()),
                row.createdBy()));
    }

    /** 该客户该业务日是否已填报（唯一键 uq_daily_report_customer_date 的前置判断）。 */
    public boolean reportExists(String tenantId, UUID customerId, LocalDate date) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM daily_report"
                            + " WHERE customer_id = ?::uuid AND date = ?)",
                    Boolean.class, customerId.toString(), Date.valueOf(date));
            return Boolean.TRUE.equals(e);
        });
    }

    private static final String SELECT_REPORT_COLUMNS =
            "report_id, customer_id, date, answers_json, source, submitted_at, created_by";

    /** D4 —— 客户填报记录（按 date 降序，最新在前）。 */
    public List<DailyReportRow> findReports(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT " + SELECT_REPORT_COLUMNS + " FROM daily_report"
                        + " WHERE customer_id = ?::uuid"
                        + " ORDER BY date DESC",
                (rs, i) -> new DailyReportRow(
                        rs.getObject("report_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getObject("date", java.sql.Date.class).toLocalDate(),
                        rs.getString("answers_json"),
                        rs.getString("source"),
                        rs.getTimestamp("submitted_at").toInstant(),
                        rs.getString("created_by")),
                customerId.toString()));
    }

    /**
     * 客户<b>本周</b>（周一为起点）的填报天数 —— D3 出参 {@code weekly_count} 的"X/7"里的 X。
     *
     * <p>契约 {@code DailyReportData.weekly_count} 逐字「本周 X/7 天（正向计数）」
     * —— 是本周期内已填报的<b>天数</b>，不是累计次数。🛑 用天数而非次数：
     * 同一客户同一日不可能有两条（唯一键 uq_daily_report_customer_date），
     * 故"条数"与"天数"在本表恰好等价，但语义上应表述为天。
     */
    public int weeklyReportDayCount(String tenantId, UUID customerId, LocalDate monday) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM daily_report"
                            + " WHERE customer_id = ?::uuid AND date >= ?",
                    Integer.class, customerId.toString(), Date.valueOf(monday));
            return n == null ? 0 : n;
        });
    }

    // ------------------------------------------------------------------
    // plan（方案，版本不可覆盖）/ plan_review（复核留痕）/ device_dispatch（设备下发）
    // ------------------------------------------------------------------

    private static final String INSERT_PLAN_SQL =
            "INSERT INTO plan ("
                    + " plan_id, tenant_id, customer_id, version, treatment_json,"
                    + " lifestyle_json, intent_params, status, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)";

    public void insertPlan(String tenantId, PlanRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_PLAN_SQL,
                row.planId().toString(),
                row.customerId().toString(),
                row.version(),
                row.treatmentJson(),
                row.lifestyleJson(),
                row.intentParams(),
                row.status(),
                row.createdBy()));
    }

    /** D5-b 取方案（按 plan_id + version）。 */
    public PlanRow findPlan(String tenantId, UUID planId, int version) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<PlanRow> rows = jdbc.query(
                    "SELECT plan_id, customer_id, version, treatment_json, lifestyle_json,"
                            + " intent_params, status, created_at, created_by"
                            + " FROM plan WHERE plan_id = ?::uuid AND version = ?",
                    (rs, i) -> new PlanRow(
                            rs.getObject("plan_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getInt("version"),
                            rs.getString("treatment_json"),
                            rs.getString("lifestyle_json"),
                            rs.getString("intent_params"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("created_by")),
                    planId.toString(), version);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** D6 前置：方案是否已审核通过（status=approved）。 */
    public boolean planApproved(String tenantId, UUID planId, int version) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM plan WHERE plan_id = ?::uuid AND version = ? AND status = 'approved')",
                    Boolean.class, planId.toString(), version);
            return Boolean.TRUE.equals(e);
        });
    }

    /** 方案当前最大版本（D5-c 审核通过后触发"变更 → 新版本"用）。 */
    public int planCurrentVersion(String tenantId, UUID planId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Integer v = jdbc.queryForObject(
                    "SELECT coalesce(max(version), 0) FROM plan WHERE plan_id = ?::uuid",
                    Integer.class, planId.toString());
            return v == null ? 0 : v;
        });
    }

    /** 更新方案 status（审核通过 draft→approved / 退回 reviewing；不覆盖内容）。 */
    public void updatePlanStatus(String tenantId, UUID planId, int version, String status) {
        inTenant(tenantId, () -> jdbc.update(
                "UPDATE plan SET status = ? WHERE plan_id = ?::uuid AND version = ?",
                status, planId.toString(), version));
    }

    private static final String INSERT_REVIEW_SQL =
            "INSERT INTO plan_review ("
                    + " review_id, tenant_id, plan_id, plan_version, reviewer_id,"
                    + " result, reason, second_confirm, reviewed_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?)";

    public void insertPlanReview(String tenantId, PlanReviewRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_REVIEW_SQL,
                row.reviewId().toString(),
                row.planId().toString(),
                row.planVersion(),
                row.reviewerId().toString(),
                row.result(),
                row.reason(),
                row.secondConfirm(),
                Timestamp.from(row.reviewedAt()),
                row.createdBy()));
    }

    private static final String INSERT_DISPATCH_SQL =
            "INSERT INTO device_dispatch ("
                    + " dispatch_id, tenant_id, plan_id, plan_version, store_id,"
                    + " device_id, param_snapshot, result, failed_reason, event, dispatched_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?::uuid, ?::uuid, ?::jsonb, ?, ?, ?, ?, ?)";

    public void insertDeviceDispatch(String tenantId, DeviceDispatchRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_DISPATCH_SQL,
                row.dispatchId().toString(),
                row.planId().toString(),
                row.planVersion(),
                row.storeId().toString(),
                row.deviceId().toString(),
                row.paramSnapshot(),
                row.result(),
                row.failedReason(),
                row.event(),
                Timestamp.from(row.dispatchedAt()),
                row.createdBy()));
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
                    "租户/标识非合法 UUID，拒绝执行履约 SQL");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }
}