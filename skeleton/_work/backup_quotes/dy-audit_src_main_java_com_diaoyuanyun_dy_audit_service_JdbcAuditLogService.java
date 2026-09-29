package com.diaoyuanyun.dy.audit.service;

import com.diaoyuanyun.dy.audit.chain.AuditChainHash;
import com.diaoyuanyun.dy.audit.chain.AuditLogTable;
import com.diaoyuanyun.dy.audit.chain.ChainVerification;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 审计日志的 PostgreSQL 实现：真正的 append-only + SHA-256 哈希链 (ADR-09 / ADR-11)。
 *
 * <h1>写入时如何落链</h1>
 * <pre>
 *   prev_hash = 上一条记录的 hash       （首条 = {@link AuditChainHash#GENESIS_PREV_HASH}）
 *   hash      = SHA-256( canonicalV1(本条字段, prev_hash) )
 * </pre>
 * 规范串与版本标签见 {@link AuditChainHash}（v1 冻结，可被任何语言独立复算）。
 *
 * <h1>并发安全：为什么必须串行化，以及选了哪种串行化</h1>
 *
 * <h2>不串行化会发生什么（这不是理论风险）</h2>
 * "取上一条的 hash → 算自己的 hash → 插入"是一个典型的<b>读-改-写竞争</b>。
 * 两个写者同时读到同一个 tail（比如都在创世状态下读到全 0），就会各自写出
 * {@code prev_hash = 全0} 的两条记录。库里出现<b>分叉</b>：两条记录都是"链首"，
 * 校验时第二条必然 {@code prev_hash} 对不上 —— 而且是<b>并发自己造出来的断链</b>，
 * 不是有人篡改。审计链最大的敌人不是攻击者，而是它自己会随机报假警：
 * 一条会自己断的链，最终会被人以"误报"为由关掉。
 *
 * <h2>候选方案与取舍</h2>
 * <table border="1">
 *   <tr><th>方案</th><th>结论</th><th>理由</th></tr>
 *   <tr><td>数据库行锁 {@code SELECT ... FOR UPDATE} 锁 tail 行</td>
 *       <td><b>否决</b></td>
 *       <td>表为空（链首）时<b>没有行可锁</b>，两个写者同时拿到创世状态；
 *           这是经典的空表锁失效。用 {@code LOCK TABLE} 则粒度太粗，且需要表级权限。</td></tr>
 *   <tr><td>应用内单写者队列（{@code BlockingQueue} + 单线程消费）</td>
 *       <td><b>否决（作为唯一机制）</b></td>
 *       <td>只在<b>单进程</b>内串行化。骨架部署形态是多实例 Pool 模型（ADR-01），
 *           两个实例各有一条队列，跨进程照样分叉。它能让单机测试变绿，
 *           从而把"其实是多实例下的 bug"藏起来 —— 这正是要避免的假通过。</td></tr>
 *   <tr><td>PG 事务级 advisory 锁 {@code pg_advisory_xact_lock(key)}</td>
 *       <td><b>采用</b></td>
 *       <td>锁对象是<b>数据库</b>而非进程，天然跨实例；事务级锁随提交/回滚自动释放，
 *           不存在"异常路径忘了解锁"导致审计写入永久卡死；
 *           且对链首（空表）同样有效 —— 它锁的不是某一行，而是一个命名互斥量。
 *           代价：审计写入被串行化（吞吐受限于单写者）。这个代价是<b>设计选择而非疏漏</b>：
 *           审计是合规证据，宁可慢也不可断；业务热路径不应在事务里同步写审计链，
 *           应由 {@code AuditFillAspect} 风格的后置/异步通道按序投递。</td></tr>
 * </table>
 *
 * <h2>实现细节（决定成败的地方）</h2>
 * <ol>
 *   <li><b>锁必须在与 INSERT 同一个事务里</b>。若锁在事务外取、或取了却没用同一连接，
 *       就退化成一个装饰。故本方法要求已在事务中（{@code Connection} 由
 *       {@link DataSourceUtils} 取当前事务绑定的那条），{@code pg_advisory_xact_lock}
 *       由该事务持有到提交。</li>
 *   <li><b>锁内再读 tail</b>。先锁后读，否则读到的还是被别人抢改的旧值。
 *       锁 + 读 + 算 + 插必须在临界区内一气呵成。</li>
 *   <li><b>用 {@code created_at} 显式赋值而非 {@code DEFAULT now()}</b>。
 *       {@code now()} 是<b>事务开始时刻</b>，锁让写者串行排队，但排在后面的写者其事务
 *       可能在更早的时刻就已经开始，于是 {@code created_at} 会出现<b>乱序</b>，
 *       而"按 created_at 排序重算链"的校验器就会把一个健康的库判成断链。
 *       故这里用 {@code clock_timestamp()}（真实当前时刻，随语句推进），
 *       且由<b>服务端</b>取值 —— 应用时钟不可信，多实例时钟漂移会直接破坏全序。
 *       残留风险：两个写者的 {@code clock_timestamp()} 仍可能相等（同微秒），
 *       故校验器遇到同一 {@code created_at} 会显式报 {@code ORDER_AMBIGUOUS}
 *       而不是随便挑一个顺序然后谎称"链有效"。</li>
 * </ol>
 *
 * <p><b>未做的事（如实记录）</b>：表上<b>没有</b> {@code (created_at, id)} 唯一约束，
 * 因此上面的并列检测发生在校验期而不是写入期。补一个唯一索引可以把不变量前移到
 * 数据库层，属下一轮 DDL 变更（本任务不得改 {@code dy-app} 的迁移脚本与
 * {@code audit_log.sql} 约定之外的 DDL 语义 —— 列名与类型已由 DDL 固定）。
 */
@Service
public class JdbcAuditLogService implements AuditLogService {

    /**
     * advisory 锁的键。取 {@code "dy.audit_log" } 的稳定 64 位哈希，使其
     * 跨实例、跨重启、跨语言一致；用常量对象锁会和别的模块抢同一个键空间，
     * 故把 {@code "audit_log"} 写进键里以示"这把锁专属于审计链"。
     */
    private static final long CHAIN_LOCK_KEY = 0x6479_5F61_7564_6974L; // "dy_audit"

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public JdbcAuditLogService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 供 Host/Harness 幂等建表；生产由 Flyway 迁移负责，此方法只是让本模块可独立起测。 */
    public void ensureSchema() {
        jdbc.execute(AuditLogTable.CREATE_TABLE);
    }

    @Override
    public String append(AuditLog entry) {
        // 链字段由服务端生成；调用方传入的 prevHash/hash 被【有意忽略】——
        // 信任调用方送来的 hash 等于把链的完整性交给写入方自觉（见接口 javadoc）。
        requireNonBlank("tenantId", entry.tenantId());
        requireNonBlank("actor", entry.actor());
        requireNonBlank("action", entry.action());
        requireNonBlank("targetType", entry.targetType());
        requireNonBlank("targetId", entry.targetId());
        // 注意：这里【不】对字段内容做任何字符级拒绝。规范串用"转义 + 空值标记"做成单射
        // （见 AuditChainHash 类注释），含 '|'、换行、反斜杠的值都能安全编码，
        // 故无需为了哈希安全而拒绝合法的业务数据（例如含 '|' 的 JSON payload）。
        // 审计写不进去本身就是合规缺陷，不能拿"拒绝写入"当哈希实现的兜底。

        String id = UUID.randomUUID().toString();

        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            if (conn.getAutoCommit()) {
                // 没有外层事务 → advisory xact 锁会在每条语句后立刻释放，失去互斥意义。
                // 不静默降级为"不锁"，直接拒绝：宁可写入报错，也不要一条会自己断的链。
                throw new IllegalStateException(
                        "审计链写入必须在事务中执行（pg_advisory_xact_lock 的事务级锁是互斥的前提）；"
                                + "当前连接 autoCommit=true。请在 @Transactional 边界内调用 append()。");
            }

            // ① 先锁：跨实例、跨进程的命名互斥量；空表同样有效（这是弃用 FOR UPDATE 的原因）。
            //
            // 三个必须按这个写法来的理由（前两个都在真库上炸过，不是猜测）：
            //
            // (a) 【不要读返回列】pg_advisory_xact_lock 的返回类型是 void。
            //     写成 queryForObject(sql, Long.class, key) 会在客户端报
            //     "不良的类型值 long : " —— 因为 void 列的文本值是空串，驱动无法把它解析成 long。
            //     它报的是客户端错误、SQL 也"看起来"正常，很容易被误读成参数类型问题而绕远路。
            //     故这里用 RowCallbackHandler 只消费结果集、不读任何列。
            //
            // (b) 【参数必须显式声明类型】写 "SELECT pg_advisory_xact_lock(?)" 时，
            //     PostgreSQL 需要从函数的重载（(int,int) 与 (int8)）反推参数类型，
            //     驱动送出的 Java long 会被解析成一个不存在的类型名。写成 ?::bigint 消除歧义。
            //
            // (c) 【锁必须在当前事务绑定的连接上】下面取 tail 与 INSERT 用的是同一个 JdbcTemplate，
            //     由 DataSourceUtils 绑定到本事务的连接 —— 锁与写入同事务，锁才作数（见类 javadoc）。
            jdbc.query("SELECT pg_advisory_xact_lock(?::bigint)",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        // 有意不读列：见上面 (a)
                    },
                    CHAIN_LOCK_KEY);

            // ② 锁内读 tail —— 顺序不能颠倒，否则读到的仍是被别人抢改的旧值。
            Tail tail = readTail();

            // ③ 算链：prev = tail.hash（首条 = 创世值）
            String prevHash = tail.hash == null ? AuditChainHash.GENESIS_PREV_HASH : tail.hash;
            String hash = AuditChainHash.chainHash(
                    entry.tenantId(), entry.actor(), entry.action(),
                    entry.targetType(), entry.targetId(), entry.payload(), prevHash);

            // ④ 插入。created_at 由服务端 clock_timestamp() 取值（见类 javadoc "实现细节"③）
            jdbc.update("“"
                            INSERT INTO audit_log
                                (id, tenant_id, actor, action, target_type, target_id,
                                 payload, prev_hash, hash, created_at)
                            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                            "”",
                    id, entry.tenantId(), entry.actor(), entry.action(),
                    entry.targetType(), entry.targetId(), normalizePayload(entry.payload()),
                    prevHash, hash);
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("读取审计链写入连接的 autoCommit 状态失败", e);
        } catch (DataAccessException e) {
            throw e;
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    @Override
    public List<AuditLog> readAllInOrder() {
        return jdbc.query("“"
                        SELECT id, tenant_id, actor, action, target_type, target_id,
                               payload, prev_hash, hash, created_at
                        FROM audit_log
                        ORDER BY created_at ASC, id ASC
                        "”",
                (rs, rowNum) -> new AuditLog(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("payload"),
                        rs.getString("prev_hash"),
                        rs.getString("hash")));
    }

    @Override
    public ChainVerification verifyChain() {
        List<AuditLog> rows = readAllInOrder();
        int n = rows.size();

        // 全序可判定性：created_at 并列 = 写入顺序不可判定。
        // 此时任何"链有效"的结论都不可靠（换个排序就是一个新结论），故显式拒答。
        for (int i = 1; i < n; i++) {
            Instant prev = rows.get(i - 1).at();
            Instant cur = rows.get(i).at();
            if (prev != null && prev.equals(cur)) {
                return ChainVerification.broken(rows.get(i).id(), ChainVerification.REASON_ORDER_AMBIGUOUS, n);
            }
        }

        String expectedPrev = AuditChainHash.GENESIS_PREV_HASH;
        for (int i = 0; i < n; i++) {
            AuditLog row = rows.get(i);

            // 形状检查先行：字段被写成垃圾/截断时，报"断链"而不是抛异常
            // （校验器面对被破坏的数据必须能给出结论，不能自己崩掉）。
            if (!AuditChainHash.looksLikeHash(row.hash()) || !AuditChainHash.looksLikeHash(row.prevHash())) {
                return ChainVerification.broken(row.id(), ChainVerification.REASON_MALFORMED_HASH, n);
            }

            // ① 接链检查：prev_hash 必须等于链首约定值（i=0）或物理前驱的 hash。
            //    —— 中间删除一行时，它【后继】的这一项会对不上，broken_at 指向后继。
            if (!row.prevHash().equals(expectedPrev)) {
                return ChainVerification.broken(row.id(), ChainVerification.REASON_PREV_HASH_MISMATCH, n);
            }

            // ② 重算检查：用本条自己的字段 + 本条存储的 prev_hash 重算，必须等于存储的 hash。
            //    —— 改写某行 event_data / hash / prev_hash 时，是【它自己】对不上。
            String recomputed = AuditChainHash.chainHash(
                    row.tenantId(), row.actor(), row.action(),
                    row.targetType(), row.targetId(), row.payload(), row.prevHash());
            if (!recomputed.equals(row.hash())) {
                return ChainVerification.broken(row.id(), ChainVerification.REASON_HASH_MISMATCH, n);
            }

            expectedPrev = row.hash();
        }
        // 创世检查单独留痕：链首 prev_hash 非约定值时上面 ① 已捕获，这里补一个可区分的 reason
        if (n > 0 && !rows.get(0).prevHash().equals(AuditChainHash.GENESIS_PREV_HASH)) {
            return ChainVerification.broken(rows.get(0).id(), ChainVerification.REASON_GENESIS_MISMATCH, n);
        }
        return ChainVerification.ok(n);
    }

    private Tail readTail() {
        List<Tail> tails = jdbc.query("“"
                        SELECT hash FROM audit_log ORDER BY created_at DESC, id DESC LIMIT 1
                        "”",
                (rs, rowNum) -> new Tail(rs.getString("hash")));
        return tails.isEmpty() ? new Tail(null) : tails.get(0);
    }

    private record Tail(String hash) {
    }

    private static void requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("审计字段 " + field + " 不可为空（DDL 为 NOT NULL）");
        }
    }

    /**
     * 写入前把空串 payload 归一为 {@code NULL}。
     *
     * <p>理由：{@code payload} 是 {@code TEXT}，而规范串里 {@code null} 与空串是<b>两种不同编码</b>
     * （{@code ~null~} vs {@code ~s~}），差别只有在能区分二者时才有意义。
     * PostgreSQL 的 {@code TEXT} 可以存空串，但本表的语义是"未采到详情"，
     * 空串是同一状态的另一种写法 —— 落到库里就会分叉出两种表示，
     * 而校验端读回来的是 {@code ""}、重算用的是 {@code ""}，
     * 一旦有人按 {@code null} 重算就必然失配。故写入侧就收敛到 {@code NULL} 一种表示。
     */
    private static String normalizePayload(String payload) {
        return (payload != null && payload.isEmpty()) ? null : payload;
    }

    /** 便于 Harness 在断言里核对"重算 vs 存储"，避免测试自己再实现一遍规范串。 */
    public static List<String> recomputeAll(List<AuditLog> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (AuditLog row : rows) {
            out.add(AuditChainHash.chainHash(
                    row.tenantId(), row.actor(), row.action(),
                    row.targetType(), row.targetId(), row.payload(), row.prevHash()));
        }
        return out;
    }
}