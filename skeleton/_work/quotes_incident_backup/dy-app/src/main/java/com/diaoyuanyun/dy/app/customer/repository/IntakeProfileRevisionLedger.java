package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRevisionRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 建档档案修订留痕账本（V7 {@code intake_profile_revision}）的 JDBC 仓储 ——
 * <b>契约 B6「append-only 留痕，不可覆盖」的机械落点</b>。
 *
 * <h2>🛑 本类<b>只有</b> INSERT 与 SELECT —— 缺四种方法是刻意的</h2>
 * <pre>
 *   ✗ updateSnapshot(...)     —— 快照是不可变的：一次修订一旦落库即为事实
 *   ✗ deleteByCustomer(...)   —— 删除即断链：supersedes 指针会指向不存在的行
 *   ✗ upsertById(...)         —— upsert 是"就地改写"的另一种写法
 *   ✗ saveOrUpdate(...)       —— 同上，只是名字看起来温和
 * </pre>
 * <p>🛑 <b>为什么"不可覆盖"在这里尤其不能只写成注释</b>：本表的每一行是
 * "客户档案在当时长什么样"的<b>唯一记录</b>。若允许就地改写，
 * 那么"首诊门店锁定后，他店何时补充过什么"（PRD L1310 / U1）这个问题
 * 在技术上就有了第二个答案 —— 而跨店争议的复盘全靠"只有一个答案"。
 * 客户/门店改变档案时，正确的动作是<b>追加一条新修订</b>（并以
 * {@code supersedes_revision_id} 声明取代了谁），而不是覆盖旧的。
 *
 * <h2>🛑 与 {@link IntakeProfileLedger} 的分工：权威在本账本，投影在那边</h2>
 * <pre>
 *   intake_profile_revision（本类） = **权威修订历史**，append-only，永不覆盖
 *   intake_profile        （那边） = 它的**当前投影**（一客户一行，可 coalesce）
 * </pre>
 * 两者的关系写在 V7 文件头第三节。🔑 本类的存在价值恰好在于"投影是易变的、
 * 历史不是" —— 若上游日后裁定"投影本身也不得就地更新"，只需改
 * {@link IntakeProfileLedger} 的投影策略（改为读取时由历史解算），
 * <b>本类零改动</b>。这正是把权威放在账本而不是投影上的价值。
 *
 * <h2>🛑 事务归属：{@link #insertWithin(JdbcTemplate, String, IntakeProfileRevisionRow, String)}</h2>
 * B6 的写入必须"投影与历史同一事务"（否则投影与历史会分叉）。故本类把真正的 INSERT
 * 开成一个<b>接收调用方 {@code JdbcTemplate}</b> 的包级方法，由
 * {@link IntakeProfileLedger} 在<b>它自己的</b>事务里调用 —— 共用一个连接即共用一个事务。
 * 公开的 {@link #append} 只是给它套一层独立短事务，供单表路径（如补录、E2E 直接造数）使用。
 *
 * <h2>隔离机制（与域 B 其余账本同一套）</h2>
 * 短事务 + {@code SET LOCAL app.tenant_id}；<b>不</b>写 {@code WHERE tenant_id = ?}，
 * 隔离完全由 V7 的 {@code FORCE ROW LEVEL SECURITY} 承担（ADR-02）。
 * 租户 ID 经 {@link #UUID_PATTERN} 白名单后拼接。
 */
@Repository
public class IntakeProfileRevisionLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IntakeProfileRevisionLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、写：只追加（INSERT only）
    // ==================================================================

    private static final String INSERT_SQL =
            "INSERT INTO intake_profile_revision ("
                    + " revision_id, tenant_id, customer_id, revision_no, snapshot_json,"
                    + " supersedes_revision_id, reason, recorded_at, operator_id, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?::jsonb, ?::uuid, ?, ?, ?::uuid, ?)";

    /**
     * 追加一条修订留痕（<b>独立短事务</b>；单表路径用）。
     *
     * <p>🛑 B6 的正常写入路径<b>不应</b>调用本方法，而应调用
     * {@link IntakeProfileLedger#appendRevisionAndCoalesce} ——
     * 因为"投影与历史同事务"是那个方法的职责。本方法留给补录与测试造数。
     */
    public void append(String tenantId, IntakeProfileRevisionRow row) {
        requireUuid(tenantId);
        inTenant(tenantId, () -> {
            insertWithin(jdbc, tenantId, row, row.createdBy());
            return null;
        });
    }

    /**
     * 真正的单行 INSERT —— <b>使用调用方传入的 {@code JdbcTemplate}</b>，
     * 从而<b>参与调用方的那个事务</b>（这正是"投影与历史同事务"的实现机制）。
     *
     * <p>🛑 包级可见（不是 {@code private}）：它必须能被 {@link IntakeProfileLedger}
     * 调用，但又<b>不</b>应成为服务层的入口 —— 服务层只应看到
     * {@code appendRevisionAndCoalesce} 这一个原子动作。用一个"名字带 Within、
     * 接收 jdbc 参数"的签名把这条边界写在类型上，比写在注释里可靠。
     *
     * @param jdbc     调用方的事务内 JdbcTemplate（须已 {@code SET LOCAL}）
     * @param tenantId 仅用于租户标识白名单校验（本方法不做 SET LOCAL）
     */
    void insertWithin(JdbcTemplate jdbc, String tenantId, IntakeProfileRevisionRow row, String createdBy) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "档案修订留痕为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(row.revisionId().toString());
        requireUuid(row.customerId().toString());
        if (row.operatorId() == null) {
            // 🛑 虽然是"双保险"（IntakeProfileRevisionRow 构造期已拦），但这里必须重复拦：
            //    本类的 append 是公开方法，而 V7 该列在库层【可空】——
            //    一次绕过 record 构造的调用（例如未来有人加了一个"从行对象直传"的重载）
            //    会让一条无操作人的修订记录落库，而跨店争议复盘靠的正是"谁改的"。
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 operator_id —— V7 该列可空是为容纳历史系统行，"
                            + "写入路径不得利用该可空性");
        }
        if (row.recordedAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 recorded_at —— V7 该列 NOT NULL 且无 DEFAULT，"
                            + "它是『当时发生了什么』的锚点");
        }

        jdbc.update(INSERT_SQL,
                row.revisionId().toString(),
                row.customerId().toString(),
                row.revisionNo(),
                Json.toJson(row.snapshot()),
                row.supersedesRevisionId() == null ? null : row.supersedesRevisionId().toString(),
                row.reason(),
                ts(row.recordedAt()),
                row.operatorId().toString(),
                createdBy);
    }

    // ==================================================================
    // 二、读
    // ==================================================================

    private static final String SELECT_COLUMNS =
            " revision_id, customer_id, revision_no, snapshot_json, supersedes_revision_id,"
                    + " reason, recorded_at, operator_id, created_by ";

    /**
     * 取某客户的<b>全部</b>修订留痕（按修订序号升序 = 时间顺序）。
     *
     * <p>排序用 {@code revision_no} 而不是 {@code recorded_at}：
     * 序号是<b>由 {@link IntakeProfileLedger#nextRevisionNo} 在唯一约束下分配的</b>，
     * 故它与"第几次修订"严格同义；而 {@code recorded_at} 在补录场景下可能与序号相反
     * （补录一条历史修订时，它的 recorded_at 早于已存在的行）。
     * 用时间去排会让"修订顺序"与实际序号不一致 —— 而这种不一致不报错。
     */
    public List<IntakeProfileRevisionRow> findByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT" + SELECT_COLUMNS + "FROM intake_profile_revision"
                        + " WHERE customer_id = ?::uuid ORDER BY revision_no",
                IntakeProfileRevisionLedger::mapRow,
                customerId.toString()));
    }

    /**
     * 取<b>最新一条</b>修订留痕（B6 出参 / 与上一版比对 {@code describeDeltaVersus} 的取数入口）。
     *
     * <p>{@code ORDER BY revision_no DESC} + {@code LIMIT 1}：序号由唯一约束保证唯一，
     * 故不需要次级稳定键（同号两行在 {@code uq_ipr_revision_no} 下不可能）。
     */
    public IntakeProfileRevisionRow findLatest(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<IntakeProfileRevisionRow> rows = jdbc.query(
                    "SELECT" + SELECT_COLUMNS + "FROM intake_profile_revision"
                            + " WHERE customer_id = ?::uuid ORDER BY revision_no DESC LIMIT 1",
                    IntakeProfileRevisionLedger::mapRow,
                    customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 下一个修订序号（{@code max(revision_no) + 1}，无历史时为 {@code 1}）。
     *
     * <h2>🛑 它<b>不算</b>并发安全的分配器 —— 安全性来自唯一约束</h2>
     * 两个并发请求可能都读到同一个 {@code max}，于是都试图写同一序号。
     * 这时 {@code uq_ipr_revision_no} 会让<b>其中一个失败</b>，而这是正确的：
     * 修订序号是"第几次修订"，它<b>必须</b>唯一；静默让两个并发修订共用一个序号，
     * 会让"改过几次"这个问题有一个错误但看起来正常的答案。
     * 故本方法只负责"给出一个候选值"，真正的保证在库层约束 ——
     * 服务层捕获唯一冲突后应当重试，而不是改用 {@code max+1} 之外的算法。
     *
     * <p>用 {@code COALESCE(max(...), 0) + 1} 而不是 {@code count(*) + 1}：
     * {@code count} 在"有删行"（本表不该有，但若历史系统行被清理过）后会算错，
     * 而 {@code max} 只依赖"序号曾经到过哪里"。
     */
    public int nextRevisionNo(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT COALESCE(max(revision_no), 0) + 1 FROM intake_profile_revision"
                            + " WHERE customer_id = ?::uuid",
                    Integer.class, customerId.toString());
            return n == null ? 1 : n;
        });
    }

    /** 该客户已有的修订条数（写入自证 / E2E 断言用）。 */
    public int countRevisions(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM intake_profile_revision WHERE customer_id = ?::uuid",
                    Integer.class, customerId.toString());
            return n == null ? 0 : n;
        });
    }

    // ==================================================================
    // 行映射 / 上下文
    // ==================================================================

    private static IntakeProfileRevisionRow mapRow(ResultSet rs, int i) throws SQLException {
        Object revisionNo = rs.getObject("revision_no");
        return new IntakeProfileRevisionRow(
                rs.getObject("revision_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                revisionNo == null ? 0 : ((Number) revisionNo).intValue(),
                Json.toMap(rs.getString("snapshot_json")),
                rs.getObject("supersedes_revision_id", UUID.class),
                rs.getString("reason"),
                toInstant(rs.getTimestamp("recorded_at")),
                rs.getObject("operator_id", UUID.class),
                rs.getString("created_by"));
    }

    /** 独立短事务（仅供 {@link #append} 的单表路径使用）。 */
    private <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /** 供 {@link IntakeProfileLedger} 在<b>自有事务内</b>复用同一套白名单。 */
    static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "标识非合法 UUID，拒绝执行档案修订 SQL"
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