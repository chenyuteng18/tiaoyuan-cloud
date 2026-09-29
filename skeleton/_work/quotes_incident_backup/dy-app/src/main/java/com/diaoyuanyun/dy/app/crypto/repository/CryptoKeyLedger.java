package com.diaoyuanyun.dy.app.crypto.repository;

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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 密钥材料（{@code tenant_kek} / {@code subject_dek}）的 JDBC 仓储 —— V11 建表。
 *
 * <h2>RLS 是隔离的唯一防线（不写 WHERE tenant_id 做隔离）</h2>
 * 与既有各仓储同款：隔离全由 V11 的 {@code FORCE RLS} 承担。
 * 密钥表比业务表【更】需要这条 —— 越权读到别租户的 wrappedDek 是密钥材料泄漏。
 *
 * <h2>🛑 本类不做加解密，只做"材料的存取"</h2>
 * 把"用 KEK 解出 DEK"这件事留给 {@code DbSubjectKeyStore}。理由是失败面不同：
 * 仓储的失败是"取不到材料"（{@code SubjectKeyNotFoundException}），
 * 密码学层的失败是"材料对不上"（{@code CipherAuthenticationException}）。
 * 混在一层里会让"密钥丢了"和"密钥被换过"呈现成同一个症状。
 *
 * <h2>🛑 并发：用 PG 咨询锁把"同主体的创建"串行化</h2>
 * {@link InMemorySubjectKeyStore} 用 {@code ConcurrentHashMap.compute} 的每键锁。
 * 数据库版没有"每键锁"这个概念，故此处用
 * {@code pg_advisory_xact_lock(hashtext(subjectKey))} ——
 * 语义等价：同一主体串行、不同主体并发；事务结束自动释放（不会漏解锁）。
 * <b>为什么必须串行</b>：若不串行，两个并发线程都会算出 {@code next = 当前 + 1}，
 * 于是同一个 {@code dek_version} 对应两把不同的 DEK —— 按版本号解密只取得到第一条，
 * 另一条写入的密文【永久不可解】。这不是理论风险：内存版曾实测出
 * 12 线程并发首取产生 12 把 v1。
 *
 * <p>用 {@code hashtext} 而非"直接拿 UUID 转 bigint"：主体键是
 * {@code tenant/subjectType/subjectId} 三元组，不是 UUID。哈希碰撞会让两个不同主体
 * 短暂串行（性能影响），但【不会】让它们共用密钥（那是 {@code SubjectRef} 的 AAD 绑定
 * 与主键共同保证的）。这个方向的失效是可接受的。
 */
@Repository
public class CryptoKeyLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CryptoKeyLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 租户 KEK
    // ==================================================================

    /** {@code tenant_kek} 一行（裸 KEK 不在此 —— 它由 masterKey 解出，从不落库）。 */
    public record KekRow(String tenantId, String kekId, int kekSeq, String algorithmId,
                         byte[] wrapNonce, byte[] wrappedBytes, String aadText,
                         Instant createdAt, String createdBy, Instant destroyedAt) {

        /** 该代 KEK 是否已被租户级销毁。 */
        public boolean isDestroyed() {
            return destroyedAt != null;
        }
    }

    /** 取当前代 KEK（{@code kek_seq} 最大且未销毁）。 */
    public Optional<KekRow> findCurrentKek(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, kek_id, kek_seq, algorithm_id, wrap_nonce, wrapped_bytes,"
                        + " aad_text, created_at, created_by, destroyed_at"
                        + " FROM tenant_kek"
                        + " WHERE destroyed_at IS NULL"
                        + " ORDER BY kek_seq DESC LIMIT 1",
                (rs, i) -> mapKek(rs)).stream().findFirst());
    }

    /** 按 {@code kekId} 取（解密历史材料时必须按包裹时的那一代取，不是"当前"）。 */
    public Optional<KekRow> findKek(String tenantId, String kekId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, kek_id, kek_seq, algorithm_id, wrap_nonce, wrapped_bytes,"
                        + " aad_text, created_at, created_by, destroyed_at"
                        + " FROM tenant_kek WHERE kek_id = ?",
                (rs, i) -> mapKek(rs), kekId).stream().findFirst());
    }

    /** 枚举某租户全部 KEK 版本（证据/盘点用，含已销毁）。 */
    public List<KekRow> listKeks(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, kek_id, kek_seq, algorithm_id, wrap_nonce, wrapped_bytes,"
                        + " aad_text, created_at, created_by, destroyed_at"
                        + " FROM tenant_kek ORDER BY kek_seq",
                (rs, i) -> mapKek(rs)));
    }

    /**
     * 新增一代 KEK。
     *
     * <p>🛑 {@code kek_seq} 的值由调用方在<b>同一事务</b>内通过
     * {@link #nextKekSeq} 取得 —— 两步都必须在 {@link #inTenant} 的事务里，
     * 否则"算 seq"与"插入"之间会插进另一个轮换，两个 KEK 争同一个 seq，
     * 由 {@code uq_tenant_kek_seq} 拒掉（数据安全，但表现为随机失败）。
     */
    public void insertKek(String tenantId, int kekSeq, String kekId, String algorithmId,
                          byte[] wrapNonce, byte[] wrappedBytes, String aadText, String createdBy) {
        requireUuid(tenantId);
        inTenant(tenantId, () -> jdbc.update(
                "INSERT INTO tenant_kek (tenant_id, kek_id, kek_seq, algorithm_id, wrap_nonce,"
                        + " wrapped_bytes, aad_text, created_by)"
                        + " VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, kekId, kekSeq, algorithmId, wrapNonce, wrappedBytes, aadText, createdBy));
    }

    /**
     * 下一个 KEK 序号（当前最大 + 1；无 KEK 时为 1）。
     *
     * <p>用 {@code FOR UPDATE} 锁住当前代那一行：并发的两次轮换会串行，
     * 后者读到前者已提交的新 seq。锁的是"当前代"这一行而非全表 ——
     * 因为轮换只会追加在末代之后，末代就是唯一的争用点。
     * 无行可锁时（首次初始化）由 {@code uq_tenant_kek_seq} 唯一约束兜底。
     */
    public int nextKekSeq(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            // 🛑 用 query(...) 取列表而非 queryForObject：无 KEK 时 queryForObject 会抛
            //    EmptyResultDataAccessException —— 而"该租户还没有 KEK"是完全正常的首次初始化，
            //    不是错误。把它报成异常会让"初始化"被迫写成 try/catch 的补丁。
            List<Integer> max = jdbc.query(
                    "SELECT kek_seq FROM tenant_kek WHERE tenant_id = ?::uuid"
                            + " ORDER BY kek_seq DESC LIMIT 1 FOR UPDATE",
                    (rs, i) -> rs.getInt(1), tenantId);
            return max.isEmpty() ? 1 : max.get(0) + 1;
        });
    }

    /** 租户级销毁：标记【全部】历史代 KEK 为已销毁（删除权必须穿透到历史代）。 */
    public int destroyAllKeks(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.update(
                "UPDATE tenant_kek SET destroyed_at = now()"
                        + " WHERE tenant_id = ?::uuid AND destroyed_at IS NULL", tenantId));
    }

    /** 该租户是否已有全部代均被销毁（供 fail-closed 判断）。 */
    public boolean allKeksDestroyed(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            // 同 nextKekSeq：用 query(...) 避免空结果集异常
            List<Integer> total = jdbc.query(
                    "SELECT count(*) FROM tenant_kek WHERE tenant_id = ?::uuid",
                    (rs, i) -> rs.getInt(1), tenantId);
            List<Integer> live = jdbc.query(
                    "SELECT count(*) FROM tenant_kek WHERE tenant_id = ?::uuid AND destroyed_at IS NULL",
                    (rs, i) -> rs.getInt(1), tenantId);
            int t = total.isEmpty() ? 0 : total.get(0);
            int l = live.isEmpty() ? 0 : live.get(0);
            return t > 0 && l == 0;
        });
    }

    // ==================================================================
    // 主体 DEK
    // ==================================================================

    /** {@code subject_dek} 一行。 */
    public record DekRow(String tenantId, String subjectType, String subjectId, int dekVersion,
                         String kekId, String algorithmId, byte[] wrapNonce, byte[] wrappedBytes,
                         String aadText, Instant createdAt, String createdBy) {
    }

    public Optional<DekRow> findDek(String tenantId, String subjectType, String subjectId, int version) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, subject_type, subject_id, dek_version, kek_id, algorithm_id,"
                        + " wrap_nonce, wrapped_bytes, aad_text, created_at, created_by"
                        + " FROM subject_dek"
                        + " WHERE subject_type = ? AND subject_id = ? AND dek_version = ?",
                (rs, i) -> mapDek(rs), subjectType, subjectId, version).stream().findFirst());
    }

    /** 取该主体最新一版 DEK。 */
    public Optional<DekRow> findLatestDek(String tenantId, String subjectType, String subjectId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, subject_type, subject_id, dek_version, kek_id, algorithm_id,"
                        + " wrap_nonce, wrapped_bytes, aad_text, created_at, created_by"
                        + " FROM subject_dek"
                        + " WHERE subject_type = ? AND subject_id = ?"
                        + " ORDER BY dek_version DESC LIMIT 1",
                (rs, i) -> mapDek(rs), subjectType, subjectId).stream().findFirst());
    }

    /** 枚举该主体全部版本（备份清单 / 删除 DAG 用）。 */
    public List<DekRow> listDeks(String tenantId, String subjectType, String subjectId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id, subject_type, subject_id, dek_version, kek_id, algorithm_id,"
                        + " wrap_nonce, wrapped_bytes, aad_text, created_at, created_by"
                        + " FROM subject_dek"
                        + " WHERE subject_type = ? AND subject_id = ?"
                        + " ORDER BY dek_version",
                (rs, i) -> mapDek(rs), subjectType, subjectId));
    }

    /**
     * 🛑 <b>P0 并发原语</b>：锁定该主体，使"算下一版本 → 生成 → 插入"三步不可被插入。
     *
     * <p>PG 咨询锁（事务级）。必须在 {@link #inTenant} 开启的事务内调用 ——
     * 事务提交时自动释放，因此不存在"异常路径上忘记解锁"。
     *
     * <p>不锁的话：两个并发线程都读到"当前最大版本 = 1"，都算出 next = 2，
     * 各生成一把 DEK，其中一把被 {@code PRIMARY KEY} 拒掉（数据安全），
     * 但被拒的那次加密已经发生在应用层 —— 若调用方没把异常当致命错误处理，
     * 就会把"用 A 密钥加密的数据"连同"密钥 B 的版本号"一起写库 ⇒ 永久不可解。
     */
    public void lockSubject(String tenantId, String subjectType, String subjectId) {
        requireUuid(tenantId);
        // pg_advisory_xact_lock 返回 void，用 queryForObject 取一个常量以便统一取连接
        inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) AS _lock",
                Integer.class, tenantId + "/" + subjectType + "/" + subjectId));
    }

    /** 该主体的下一个 DEK 版本（无则 1）。<b>必须在 {@link #lockSubject} 之后、同一事务内调用。</b> */
    public int nextDekVersion(String tenantId, String subjectType, String subjectId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<Integer> max = jdbc.query(
                    "SELECT max(dek_version) FROM subject_dek"
                            + " WHERE tenant_id = ?::uuid AND subject_type = ? AND subject_id = ?",
                    (rs, i) -> rs.getInt(1), tenantId, subjectType, subjectId);
            // max() 在无行时返回 NULL ⇒ getInt 得 0（wasNull 未判，此处 0 即"无版本"，与 1 相邻）
            int m = max.isEmpty() ? 0 : max.get(0);
            return m + 1;
        });
    }

    public void insertDek(String tenantId, String subjectType, String subjectId, int dekVersion,
                          String kekId, String algorithmId, byte[] wrapNonce, byte[] wrappedBytes,
                          String aadText, String createdBy) {
        requireUuid(tenantId);
        inTenant(tenantId, () -> jdbc.update(
                "INSERT INTO subject_dek (tenant_id, subject_type, subject_id, dek_version, kek_id,"
                        + " algorithm_id, wrap_nonce, wrapped_bytes, aad_text, created_by)"
                        + " VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, subjectType, subjectId, dekVersion, kekId, algorithmId,
                wrapNonce, wrappedBytes, aadText, createdBy));
    }

    /**
     * 销毁该主体的全部 DEK 材料（crypto-shredding 的"删材料"这一步）。
     *
     * <p>🛑 顺序纪律：调用方必须<b>先</b>写墓碑再调本方法。
     * 反过来的话，若两步之间进程崩溃，"密钥已删但无墓碑"会让读取路径把
     * "已依法删除"呈现为"从未创建"（一个逻辑错误，会给用户看到 500 而不是明确的删除状态）。
     */
    public int deleteAllDeks(String tenantId, String subjectType, String subjectId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.update(
                "DELETE FROM subject_dek"
                        + " WHERE tenant_id = ?::uuid AND subject_type = ? AND subject_id = ?",
                tenantId, subjectType, subjectId));
    }

    // ==================================================================
    // 上下文 / 映射
    // ==================================================================

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    private static KekRow mapKek(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp destroyed = rs.getTimestamp("destroyed_at");
        return new KekRow(
                rs.getString("tenant_id"),
                rs.getString("kek_id"),
                rs.getInt("kek_seq"),
                rs.getString("algorithm_id"),
                rs.getBytes("wrap_nonce"),
                rs.getBytes("wrapped_bytes"),
                rs.getString("aad_text"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
                destroyed == null ? null : destroyed.toInstant());
    }

    private static DekRow mapDek(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DekRow(
                rs.getString("tenant_id"),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getInt("dek_version"),
                rs.getString("kek_id"),
                rs.getString("algorithm_id"),
                rs.getBytes("wrap_nonce"),
                rs.getBytes("wrapped_bytes"),
                rs.getString("aad_text"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"));
    }

    private static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户标识非合法 UUID，拒绝执行密钥 SQL");
        }
    }

    /** 供装配层做启动期自检用（不暴露给业务）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    /** 便捷：把 {@code UUID} 转成仓储约定的小写字符串形式。 */
    public static String uuidText(UUID id) {
        return id.toString();
    }
}