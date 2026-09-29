package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 配置真相源的 JDBC 访问支撑：把"在租户上下文里执行"收敛到一处。
 *
 * <h2>为什么每次读都要重设 SET LOCAL（而不是像业务查询那样只设一次）</h2>
 * 本模块的读路径可能发生在<b>事务之外</b>（配置读是高频只读操作）。
 * {@code SET LOCAL} 只在事务内有效，事务一结束就失效 —— 若在事务外设，
 * 下一次读就又回到"无上下文 = 零行"。所以这里对每个操作都开一个短事务，
 * 事务内先设上下文再执行 SQL。这样"无上下文读到零行"这条 fail-closed 性质
 * 在【所有】路径上都成立，而不是只在被 {@code @RlsScoped} 标注的方法上成立。
 *
 * <h2>为什么 tenant_id 用白名单校验后拼接</h2>
 * PostgreSQL 的 {@code SET} 语句不支持绑定参数（它不是 DML），必须拼接。
 * 拼接前用 {@link #UUID_PATTERN} 做字符集白名单校验（仅 {@code [0-9a-f-]}、
 * 固定 36 位），校验通过后不存在可注入字符。这与
 * {@code dy-tenancy} 的 {@code RlsSessionAspect} 是同一套防护，
 * 不得放宽 —— 放宽该正则前必须先过安全评审。
 */
class JdbcConfigSupport {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** 写入人标识的保守字符集：只允许这些字符，另一方是连接器侧的拼接点。 */
    private static final Pattern ACTOR_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.:@+-]{1,128}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    JdbcConfigSupport(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 读路径：在租户上下文里执行（不带写入人）。 */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        return inTenant(tenantId, null, null, body);
    }

    /**
     * 在租户上下文（+ 写入人 + 操作类型）里执行一段 SQL。
     *
     * @param tenantId 必须是合法 UUID —— 非 UUID 一律拒绝，绝不拼进 SQL
     * @param actor    写入人；DB 触发器读 {@code app.actor} 记历史行的 who
     * @param op       写入类型（{@code UPSERT} / {@code ROLLBACK}）；DB 触发器读 {@code app.config.op}
     */
    <T> T inTenant(String tenantId, String actor, String op, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            if (actor != null && !actor.isBlank()) {
                jdbc.execute("SET LOCAL app.actor = '" + requireActor(actor) + "'");
            }
            if (op != null && !op.isBlank()) {
                jdbc.execute("SET LOCAL app.config.op = '" + requireOp(op) + "'");
            }
            return body.get();
        });
    }

    // ------------------------------------------------------------------
    // 白名单校验（拼接点是这两处的唯一依据）
    // ------------------------------------------------------------------

    static void requireUuid(String tenantId) {
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            // 语义归属：租户标识非法 = 租户不匹配 (2003/403)，不是 500
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID，拒绝执行配置 SQL");
        }
    }

    private static String requireActor(String actor) {
        if (!ACTOR_PATTERN.matcher(actor).matches()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "配置写入人标识含非法字符（仅允许 [A-Za-z0-9_.:@+-] 且 ≤128 位）");
        }
        return actor;
    }

    private static String requireOp(String op) {
        if (!"UPSERT".equals(op) && !"ROLLBACK".equals(op)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "配置写入类型只允许 UPSERT / ROLLBACK，实际: " + op);
        }
        return op;
    }
}