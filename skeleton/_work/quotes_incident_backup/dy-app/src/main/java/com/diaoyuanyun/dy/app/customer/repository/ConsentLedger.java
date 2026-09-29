package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.app.customer.domain.BandWillingness;
import com.diaoyuanyun.dy.app.customer.domain.ConsentAuthScope;
import com.diaoyuanyun.dy.app.customer.domain.ConsentDataSource;
import com.diaoyuanyun.dy.app.customer.domain.ConsentRow;
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
 * 知情同意书的 JDBC 账本（V5 §2.8 {@code consent}）。
 *
 * <h2>🛑 本类<b>只有</b> INSERT 与 SELECT</h2>
 * 与 {@link ScreeningLedger} 同源纪律：同意书是<b>证据链</b>的一部分
 * （V5 L204 {@code evidence_hash}「证据链哈希」；P0-27 文书版本纪律）。
 * 故本类<b>不提供</b> {@code updateAuthScope} / {@code deleteByCustomer} /
 * {@code upsertById}，且这不是"还没写"。
 *
 * <p>🛑 <b>为什么同意书尤其不能更新</b>：一次同意是一个<b>历史事实</b> ——
 * "他在某时某刻同意了什么、拒绝了什么"。若允许就地改写 {@code auth_scope_json}，
 * 那么"事后会不会有人把一次『只同意基础采集』改成『同意全部』"这个问题
 * 在技术上就有了答案，而证据链的全部意义就是让这个问题只有一个答案。
 * 客户改变主意时，正确的动作是<b>再签一份</b>（新行），
 * 而不是覆盖旧的那份 —— 见 {@link #findByCustomer} 返回<b>列表</b>的理由。
 *
 * <h2>🛑 操作人的唯一载体是本表的审计列 {@code created_by}</h2>
 * 与 {@code screening_record} <b>不同</b>：那张表有独立的 {@code operator_id}
 * （NOT NULL REFERENCES staff），而 {@code consent} 表<b>没有</b> {@code operator_id} 列，
 * 只有审计列 {@code created_by}。这是上游两张表的<b>结构差异</b>，不是实现的疏漏。
 * 故 {@link #append} 的签名多一个 {@code createdBy} 参数 —— 它不是"可选的备注"，
 * 而是本表里"谁签的"这个问题的唯一答案。服务层从 token 取操作人后传入。
 *
 * <h2>🛑 契约 B3 <b>没有</b> requestBody，而本表有四列 NOT NULL</h2>
 * 契约 B3 只有 path 参数，但 {@code auth_scope_json} / {@code band_willingness} /
 * {@code signed_at} / {@code evidence_hash} 四列 NOT NULL。
 * 本类<b>忠实承载</b>四值并强校验非空（{@link ConsentRow} 构造期），
 * <b>不发明</b>它们的来源 —— 来源属服务层的取参策略，且那是一条<b>已登记的缺口</b>。
 * 🛑 不得为了让 B3 "看起来完整"而在契约里补一个 requestBody：契约是三方冻结件。
 *
 * <h2>隔离机制（与 {@link ScreeningLedger} 同一套）</h2>
 * 每次操作开<b>短事务</b>并 {@code SET LOCAL app.tenant_id}，使"未设租户上下文 = 零行"
 * 这条 fail-closed 性质在<b>所有</b>路径上成立。本类<b>不</b>在 SQL 里写
 * {@code WHERE tenant_id = ?} —— 隔离完全由 V5 的 {@code FORCE ROW LEVEL SECURITY}
 * 承担（ADR-02）。租户 ID 经 {@link #UUID_PATTERN} 白名单后拼接。
 */
@Repository
public class ConsentLedger {

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConsentLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、写：只追加（INSERT only）
    // ==================================================================

    private static final String INSERT_SQL =
            "INSERT INTO consent ("
                    + " consent_id, tenant_id, customer_id, auth_scope_json, band_willingness,"
                    + " signed_at, evidence_hash, data_source, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::jsonb, ?, ?, ?, ?, ?)";

    /**
     * 追加一份知情同意书。
     *
     * <p>🛑 {@code auth_scope_json} 走 {@code ?::jsonb} 且<b>空集写成 {@code "[]"}</b>：
     * data-dict §2.8「分项勾选，<b>可单独拒绝</b>」的极端情形就是"拒绝了全部四项"，
     * 它是一个<b>合法且有意义</b>的值。若把空集写成 SQL NULL，
     * 事后从库里就再也分不清"客户明确拒绝了全部"与"系统没记"——
     * 而这正是 {@link Json#toJsonArray} 刻意返回 {@code "[]"} 而非 {@code null} 的原因。
     *
     * <p>🛑 {@code data_source} 显式给出，<b>不</b>依赖库层 {@code DEFAULT 'self-report'}：
     * 本域已有一次库层默认值造成偏差的先例（{@code customer.status} 的
     * {@code DEFAULT 'pending'} 不在权威 5 值内，属已登记待改项）。
     *
     * @param createdBy 操作人（本表"谁签的"的唯一载体，见类注释）
     */
    public void append(String tenantId, ConsentRow row, String createdBy) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "知情同意书为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(row.consentId().toString());
        requireUuid(row.customerId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_SQL,
                row.consentId().toString(),
                row.customerId().toString(),
                Json.toJsonArray(ConsentAuthScope.codesOf(row.authScope())),
                row.bandWillingness().code(),
                ts(row.signedAt()),
                row.evidenceHash(),
                row.dataSource().code(),
                createdBy));
    }

    // ==================================================================
    // 二、读
    // ==================================================================

    private static final String SELECT_COLUMNS =
            " consent_id, customer_id, auth_scope_json, band_willingness,"
                    + " signed_at, evidence_hash, data_source ";

    /**
     * 取某客户的<b>全部</b>同意书（按签署时刻稳定排序）。
     *
     * <p>🛑 返回<b>列表</b>而不是"最新一份"，这是刻意的：客户可以<b>改变主意</b>
     * —— 先签一份"只同意基础采集"，之后重签一份"不同意服务记录"。
     * 两份都是事实，都要留。若只返回最新一份，"他曾经同意过什么"这个问题
     * 就永远答不出来，而证据链要求的正是"任一时点当时的状态"。
     *
     * <p>从当前生效的同意（B3 / B4 的实际依据）用 {@link #findLatest}。
     * 两个方法的分工不可混淆：本方法回答"签过几次、分别签了什么"，
     * {@code findLatest} 回答"现在生效的是哪份"。
     *
     * <p>排序必须显式：依赖数据库返回顺序是"今天恰好对"的写法，
     * 它不报错，只在某次执行计划变化后让"哪份是后来的"静默倒置。
     */
    public List<ConsentRow> findByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT" + SELECT_COLUMNS + "FROM consent"
                        + " WHERE customer_id = ?::uuid"
                        + " ORDER BY signed_at, consent_id",
                ConsentLedger::mapRow,
                customerId.toString()));
    }

    /**
     * 取<b>当前生效</b>的同意书（最近一份；B3 / B4 的 {@code band_willingness} 与授权项来源）。
     *
     * <p>排序 {@code ORDER BY signed_at DESC, consent_id DESC}：后者是稳定次序键，
     * 使同一时刻签署的两份之间也有确定顺序（否则并发下"当前生效的是哪份"不确定 ——
     * 而这是个会真实发生的场景：同一分钟内补签）。
     */
    public ConsentRow findLatest(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<ConsentRow> rows = jdbc.query(
                    "SELECT" + SELECT_COLUMNS + "FROM consent"
                            + " WHERE customer_id = ?::uuid"
                            + " ORDER BY signed_at DESC, consent_id DESC"
                            + " LIMIT 1",
                    ConsentLedger::mapRow,
                    customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 该客户是否已签署过任何一份同意书（B3 重复签署判定 / B4 出参分支的取数入口）。
     *
     * <p>🛑 用 {@code EXISTS} 而非 {@code count(*)}：我们问的是"签没签"，
     * 不是"签了几份"。写 {@code count} 会让下一个人顺手把它当计数用，
     * 而计数语义与"是否存在"在并发下是两个问题。
     */
    public boolean hasConsent(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM consent WHERE customer_id = ?::uuid)",
                    Boolean.class, customerId.toString());
            return Boolean.TRUE.equals(exists);
        });
    }

    // ==================================================================
    // 行映射
    // ==================================================================

    /**
     * 一行 → {@link ConsentRow}。
     *
     * <p>🛑 三处解析一律走<b>严格</b>入口（{@code of} / {@code parseAll}），
     * 未登记字面即抛：
     * <pre>
     *   auth_scope_json   → ConsentAuthScope.parseAll（未知键整批拒，不静默丢弃）
     *   band_willingness  → BandWillingness.of（不回落为"自愿佩戴"）
     *   data_source       → ConsentDataSource.of（不回落为 self-report）
     * </pre>
     * 三处都<b>不</b>回落，理由各自写在各枚举的 Javadoc 里，共同点是：
     * 回落会让"库里有个不认识的值"静默变成一次正常判定，
     * 而这三个值都参与下游计算（授权范围 / A3 适用性 / 证据链强度）。
     *
     * <p>{@code auth_scope_json} 用 {@link Json#toStringList} 而非 {@code toMap}：
     * 该列的形态是<b>字符串数组</b>（键集合），不是对象。用错方法会得到一个
     * 张冠李戴的 Map，而 {@code parseAll} 拿到它只会报"未知键"——
     * 一个把"列形态读错"伪装成"数据值有问题"的错误。
     */
    private static ConsentRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ConsentRow(
                rs.getObject("consent_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                ConsentAuthScope.parseAll(Json.toStringList(rs.getString("auth_scope_json"))),
                BandWillingness.of(rs.getString("band_willingness")),
                toInstant(rs.getTimestamp("signed_at")),
                rs.getString("evidence_hash"),
                ConsentDataSource.of(rs.getString("data_source")));
    }

    // ==================================================================
    // 上下文（与 ScreeningLedger / RefundReceiptLedger 同一套）
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
                    "标识非合法 UUID，拒绝执行知情同意 SQL"
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
}