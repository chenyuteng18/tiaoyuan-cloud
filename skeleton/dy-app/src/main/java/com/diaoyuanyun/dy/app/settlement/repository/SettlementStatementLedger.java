package com.diaoyuanyun.dy.app.settlement.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code settlement_statement} 结算单台账（V24 迁移建表）—— 跨店通兑对账报表的持久层。
 *
 * <h2>唯一生产写入方（ProvisioningBoundaryGateTest 已登记 44 张账）</h2>
 * 本表唯一的 INSERT 路径在 {@link #insert}。预演端点 {@code /settlement/preview}
 * 依旧只读不落库 —— "预演"与"落账"是两个显式动作，混在一起就会有人把预演当结算。
 *
 * <h2>租户宿主表：短事务 + SET LOCAL，隔离交给 RLS</h2>
 * 与 {@code StoreRepository} 同款：每次操作开短事务并 {@code SET LOCAL app.tenant_id}，
 * 行级隔离由 V24 的 {@code tenant_isolation} 策略承担（库层兜底），
 * API 层另有 {@code @RequireOrgLevel(HEADQUARTERS)} 把守（M5：总部可见、门店不可见）。
 *
 * <h2>幂等语义落在库层</h2>
 * {@code UNIQUE (tenant_id, request_hash)} —— 同一租户同一份结算输入只可能有一张单。
 * {@link #insert} 用 {@code ON CONFLICT DO NOTHING} + 回读，把
 * {@code IDEMPOTENT_REPLAY}(4002) 的语义做成事实而不是承诺。
 *
 * <h2>出站走窄记录</h2>
 * 列表/导出面用 {@link StatementRow}（不含 payload JSONB）；payload 仅在
 * {@link #payloadOf} 定点读取 —— 防止"随手把整行塞进响应"造成响应膨胀。
 */
@Repository
public class SettlementStatementLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F-]{36}$");

    private static final String INSERT_SQL = """
            INSERT INTO settlement_statement
                (statement_id, tenant_id, period, closing_store_id, stores_involved,
                 visits_total, ecc_units, loss_yuan, split_applied, other_store_ratio,
                 payload, request_hash, created_by)
            VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (tenant_id, request_hash) DO NOTHING
            """;

    private static final String FIND_HASH_SQL =
            "SELECT statement_id FROM settlement_statement "
                    + "WHERE tenant_id = ?::uuid AND request_hash = ?";

    private static final String LIST_SQL = """
            SELECT statement_id, period, closing_store_id, stores_involved, visits_total,
                   ecc_units, loss_yuan, split_applied, other_store_ratio, request_hash,
                   created_by, created_at
              FROM settlement_statement
             WHERE tenant_id = ?::uuid AND period = ?
             ORDER BY created_at, statement_id
            """;

    private static final String PAYLOAD_SQL =
            "SELECT payload::text FROM settlement_statement "
                    + "WHERE tenant_id = ?::uuid AND statement_id = ?::uuid";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SettlementStatementLedger(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 在租户上下文里执行（短事务 + SET LOCAL）。
     * 与 {@code StoreRepository.inTenant} 同一条纪律：非法租户 ID 不开事务。
     */
    private <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /**
     * 插入结算单。命中幂等键时不覆盖、不报错 —— 返回 0 行受影响，
     * 由调用方回读既有 {@code statement_id}（返回值不撒谎：0 = 已存在，1 = 新建）。
     */
    public int insert(String tenantId, NewStatement s) {
        return inTenant(tenantId, () -> jdbc.update(INSERT_SQL,
                s.statementId(), tenantId, s.period(), s.closingStoreId(),
                s.storesInvolved(), s.visitsTotal(), s.eccUnits(), s.lossYuan(),
                s.splitApplied(), s.otherStoreRatio(), s.payload(),
                s.requestHash(), s.createdBy()));
    }

    /** 回读幂等键对应的既有单据 ID（{@code ON CONFLICT DO NOTHING} 的另一半）。 */
    public Optional<UUID> findIdByRequestHash(String tenantId, String requestHash) {
        return inTenant(tenantId, () -> jdbc.query(FIND_HASH_SQL,
                (rs, i) -> UUID.fromString(rs.getString(1)), tenantId, requestHash)
                .stream().findFirst());
    }

    /** 按周期列对账清单（窄记录，不含 payload）。 */
    public List<StatementRow> listByPeriod(String tenantId, String period) {
        return inTenant(tenantId, () -> jdbc.query(LIST_SQL, (rs, i) -> new StatementRow(
                UUID.fromString(rs.getString("statement_id")),
                rs.getString("period"),
                rs.getString("closing_store_id"),
                rs.getInt("stores_involved"),
                rs.getInt("visits_total"),
                rs.getBigDecimal("ecc_units"),
                rs.getBigDecimal("loss_yuan"),
                rs.getBoolean("split_applied"),
                rs.getBigDecimal("other_store_ratio"),
                rs.getString("request_hash"),
                rs.getString("created_by")), tenantId, period));
    }

    /** 定点读取 payload 快照（JSON 文本）。 */
    public Optional<String> payloadOf(String tenantId, UUID statementId) {
        return inTenant(tenantId, () -> jdbc.query(PAYLOAD_SQL,
                (rs, i) -> rs.getString(1), tenantId, statementId.toString())
                .stream().findFirst());
    }

    static void requireUuid(String tenantId) {
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    com.diaoyuanyun.dy.common.result.ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID，拒绝执行结算单操作");
        }
    }

    /** 落库参数（写侧载体）。 */
    public record NewStatement(String statementId, String period, String closingStoreId,
                               int storesInvolved, int visitsTotal,
                               BigDecimal eccUnits, BigDecimal lossYuan,
                               boolean splitApplied, BigDecimal otherStoreRatio,
                               String payload, String requestHash, String createdBy) {
    }

    /** 对账清单行（读侧窄记录，🛑 不含 payload）。 */
    public record StatementRow(UUID statementId, String period, String closingStoreId,
                               int storesInvolved, int visitsTotal,
                               BigDecimal eccUnits, BigDecimal lossYuan,
                               boolean splitApplied, BigDecimal otherStoreRatio,
                               String requestHash, String createdBy) {
    }
}
