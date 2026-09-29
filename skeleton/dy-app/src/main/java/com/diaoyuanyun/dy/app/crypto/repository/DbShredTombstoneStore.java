package com.diaoyuanyun.dy.app.crypto.repository;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.key.ShredTombstoneStore;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link ShredTombstoneStore} 的持久化实现 —— 墓碑落地到 {@code subject_key_tombstone}（V11）。
 *
 * <h2>墓碑的作用不是"记录删除过"，而是"堵死绕行路径"</h2>
 * 若只把密钥材料删掉，任何拿到<b>备份</b> wrappedDek + KEK 的人仍可自行还原 DEK。
 * 墓碑让 {@code SubjectKeyStore.unwrap} 在还原之前先拒绝 —— 删除权因此不可绕过。
 * 这也正是"crypto-shredding 是唯一能穿透不可变备份与仅追加日志的模式"这句话的落点：
 * 材料可以留在备份里，<b>但没人能合法地用它们</b>。
 *
 * <h2>🛑 本表只追加：DB 层用 RULE 禁 UPDATE / DELETE（V11 第 5 节）</h2>
 * 与 {@code audit_log} 的"不可篡改、不可删除"同款（等保 2.0 三级）。
 * 用 RULE 而非触发器，是因为触发器的 RAISE 可被应用层"捕获后忽略"，
 * 而 RULE 直接在计划层改写语句 —— 静默忽略的路径不存在。
 *
 * <h2>幂等：重复销毁不覆盖首次时间</h2>
 * 与 {@code InMemoryShredTombstoneStore.record} 同款（其用
 * {@code computeIfAbsent} 实现）。🛑 覆盖首次销毁时间会让"何时依法删除"这一事实随时间漂移，
 * 而这是审计要问的第一个问题。
 *
 * <h2>🛑🛑 2026-09-26 修复：本表<b>不能</b>用 {@code ON CONFLICT}（曾让删除权在库层不可执行）</h2>
 * 原实现写的是 {@code INSERT ... ON CONFLICT (tenant_id, subject_type, subject_id) DO NOTHING}。
 * 这在真库上<b>每一条都会失败</b>，PG 的原话是：
 * <pre>
 *   错误: 无法对具有INSERT或者UPDATE规则的表使用带有ON CONFLICT子句的INSERT
 *   (cannot use ON CONFLICT with a table that has INSERT or UPDATE rules)
 * </pre>
 *
 * <p>根因是 V11 给本表建了 {@code tombstone_no_update} 规则
 * （{@code ON UPDATE ... DO INSTEAD NOTHING}）—— 而 PG 对"有 UPDATE 规则的表"
 * 一律拒绝 {@code ON CONFLICT} 子句，<b>连 {@code DO NOTHING} 也不例外</b>
 * （PG 无法在计划期排除"冲突时可能需要走 UPDATE 路径"这种可能）。
 *
 * <p>后果的严重性值得逐字写下来：<b>任何一次主体密钥销毁（PIPL 删除权）都会抛 SQL 异常</b>。
 * 这不是"墓碑写不进去"这么轻 —— 删除权在本系统里正是靠"销毁 DEK + 立墓碑"两步完成的，
 * 于是它是本项目<b>合规核心</b>上的一个 P0。
 *
 * <p>修复方式：改用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} —— 单语句原子，
 * 不使用 {@code ON CONFLICT}，故与规则共存。并保留一层
 * {@link org.springframework.dao.DataIntegrityViolationException} 兜底：
 * 并发下两个事务可能同时通过 {@code NOT EXISTS} 判定，后者会撞主键(23505) ——
 * 而那次冲突的<b>正确语义恰好是"已经有一条墓碑了，保留首次时间"</b>，
 * 故视为幂等成功而非失败。用"先 SELECT 再 INSERT 且不处理冲突"是错的：
 * 那会让并发下的一次<b>合法</b>删除以一个无关的 23505 报给使用者。
 *
 * <p>⚠️ 此处不用 {@code ON CONFLICT} 的教训可推广：<b>只要表上有 INSERT/UPDATE 规则，
 * {@code ON CONFLICT} 就不可用</b>。本仓 {@code subject_dek} 同样有
 * {@code subject_dek_no_update} 规则，故对它的一切 upsert 也必须遵守同一条约束。
 */
@Repository
public class DbShredTombstoneStore implements ShredTombstoneStore {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DbShredTombstoneStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void record(SubjectKeyRef ref, Instant destroyedAt, String reason) {
        requireUuid(ref.tenantId());
        if (reason == null || reason.isBlank()) {
            // DB 层有 CHECK (length(btrim(reason)) > 0) 兜底；这里提前给出可读的错。
            // 空 reason 会让墓碑变成一条无法解释的记录 —— 审计看到它却问不到"为什么删的"。
            //
            // 🛑 用 VALIDATION_FAILED(1001) 而不是 VISIBILITY_DENIED(2001)：
            //    身份/可见性是 AuthContext 决定的，不应在此重复。两处各判一次的结果是
            //    "同一件事有两份真相源"，且它们可以分别漂移。租户隔离由 RLS 一次性承担，
            //    此处只做入参合法性校验。
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "销毁墓碑的 reason 不可为空 —— 审计需要能回答『为什么删的』");
        }
        inTenant(ref.tenantId(), () -> {
            try {
                jdbc.update(
                        "INSERT INTO subject_key_tombstone"
                                + " (tenant_id, subject_type, subject_id, destroyed_at, reason) "
                                // 🛑 不能用 ON CONFLICT：本表有 tombstone_no_update 规则，
                                //    PG 会以 0A000 拒绝整条语句（见类注释的实测原文）。
                                //    改为 INSERT ... SELECT ... WHERE NOT EXISTS：
                                //    单语句原子且不需要 ON CONFLICT 子句。
                                + "SELECT ?::uuid, ?, ?, ?, ? "
                                + "WHERE NOT EXISTS ("
                                + "  SELECT 1 FROM subject_key_tombstone"
                                + "   WHERE tenant_id = ?::uuid AND subject_type = ? AND subject_id = ?)",
                        ref.tenantId(), ref.subjectType(), ref.subjectId(),
                        Timestamp.from(destroyedAt), reason,
                        ref.tenantId(), ref.subjectType(), ref.subjectId());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 并发兜底：两个销毁请求同时通过 NOT EXISTS 判定时，后者撞主键(23505)。
                // 🛑 那一次的【正确语义】是"墓碑已存在 ⇒ 保留首次时间"，属幂等成功。
                //    若把它当失败抛出，一次合法的重复删除会以一个无关的 23505 报给使用者 ——
                //    而使用者看到的会是"密钥销毁失败"，进而可能重试或误判删除权失效。
                if (!isDuplicateTombstone(e)) {
                    throw e;
                }
            }
            return null;
        });
    }

    /**
     * 判断异常是否为"墓碑主键冲突"（并发下的幂等命中，而非真实错误）。
     *
     * <p>只认 23505（unique_violation）；其他约束违规（如 reason 空白的 23514）必须照常抛出 ——
     * 把它们一并吞掉会让"reason 为空"这种真实输入缺陷被静默接受。
     */
    private static boolean isDuplicateTombstone(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.sql.SQLException se) {
                return "23505".equals(se.getSQLState());
            }
        }
        return false;
    }

    @Override
    public boolean isDestroyed(SubjectRef subject) {
        if (subject == null || !UUID_PATTERN.matcher(subject.tenantId()).matches()) {
            // 主体三元组非法 ⇒ 无法判定，按"已销毁"处理是过度保守（会误拒合法读取）。
            // 这里的正确选择是抛错：调用方传了非法主体，那是它的问题，
            // 而不是让密钥路径去猜。
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "SubjectRef 的租户标识非法，拒绝查询墓碑");
        }
        return inTenant(subject.tenantId(), () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM subject_key_tombstone"
                            + " WHERE subject_type = ? AND subject_id = ?",
                    Integer.class, subject.subjectType(), subject.subjectId());
            return n != null && n > 0;
        });
    }

    @Override
    public Optional<Tombstone> find(SubjectRef subject) {
        requireUuid(subject.tenantId());
        return inTenant(subject.tenantId(), () -> jdbc.query(
                "SELECT tenant_id, subject_type, subject_id, destroyed_at, reason"
                        + " FROM subject_key_tombstone"
                        + " WHERE subject_type = ? AND subject_id = ?",
                (rs, i) -> new Tombstone(
                        new SubjectKeyRef(rs.getString("tenant_id"), rs.getString("subject_type"),
                                rs.getString("subject_id"), 0),
                        rs.getTimestamp("destroyed_at").toInstant(),
                        rs.getString("reason")),
                subject.subjectType(), subject.subjectId()).stream().findFirst());
    }

    /** 某租户的墓碑总数（证据/盘点用）。 */
    public int countInTenant(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM subject_key_tombstone", Integer.class);
            return n == null ? 0 : n;
        });
    }

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
                    "租户标识非合法 UUID，拒绝执行墓碑 SQL");
        }
    }
}