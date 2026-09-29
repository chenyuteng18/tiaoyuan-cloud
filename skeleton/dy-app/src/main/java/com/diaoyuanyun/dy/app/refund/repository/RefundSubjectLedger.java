package com.diaoyuanyun.dy.app.refund.repository;

import com.diaoyuanyun.dy.app.refund.domain.RefundSubjectVerifier;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link RefundSubjectVerifier} 的 JDBC 实现 —— 立案前置的引用存在性查询。
 *
 * <h2>🛑 为什么这条 SQL <b>不带</b> {@code WHERE tenant_id = ?}</h2>
 * 与 {@link RefundWorkOrderLedger} 的写入路径同一套纪律（ADR-02）：
 * 隔离完全由 {@code FORCE ROW LEVEL SECURITY} 承担，而不是由应用层拼条件。
 * 若在这里也写一次 {@code tenant_id = ?}，就会出现<b>两处隔离口径</b> ——
 * 而分叉的那一侧（应用层漏了某个查询）不会报错，只会安静地返回跨租户的行。
 *
 * <h2>🛑 为什么用 {@code EXISTS} 而不是 {@code count(*) > 0}</h2>
 * 两者语义相同，但 {@code EXISTS} 让数据库在<b>找到第一行时就停</b>：
 * 本查询在退款立案的热路径上（每次 G1 调用两次），且 {@code refund} 是本域写入最频繁的表。
 * 这是次要理由；主要理由是它让"我们只关心存在性、不关心有多少"这件事写在 SQL 里，
 * 而不是写在调用方的 {@code > 0} 里 —— 后者会诱导下一个人顺手改成 {@code count}。
 *
 * <h2>🛑 两个方法都<b>不</b>校验状态</h2>
 * 见 {@link RefundSubjectVerifier} 的接口注释：本类只回答"这一行在不在"。
 * 客户是否 {@code active}、门店是否在调用方范围内，都是另两层的事 ——
 * 把它们混进来会让调用方把"状态不合格"误读成"不存在"。
 */
@Repository
public class RefundSubjectLedger implements RefundSubjectVerifier {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /**
     * 🛑 {@code id} —— customer 表的主键列名，<b>不是</b> {@code customer_id}。
     *
     * <p>V1 的建表语句是 {@code CREATE TABLE customer (id UUID PRIMARY KEY, ...)}；
     * 而 {@code refund.customer_id} 是<b>引用方</b>的列名。
     * 两者极易混（本域里 {@code refund} / {@code retention} / {@code refund_statement}
     * 三张表的主键都叫 {@code <实体>_id}，唯独 customer 是裸 {@code id}）——
     * 写错列名的表现是一次 {@code BadSqlGrammarException}（500·9001），
     * 而不是一次可读的参数错误。故此处把列名单独抽成常量并注明来源。
     */
    private static final String CUSTOMER_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM customer WHERE id = ?::uuid)";

    /** {@code store.store_id} —— 与 customer 不同，门店表用的是带前缀的主键列名。 */
    private static final String STORE_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM store WHERE store_id = ?::uuid)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RefundSubjectLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public boolean customerExists(String tenantId, UUID customerId) {
        return exists(tenantId, customerId, "客户", CUSTOMER_EXISTS_SQL);
    }

    @Override
    public boolean storeExists(String tenantId, UUID storeId) {
        return exists(tenantId, storeId, "门店", STORE_EXISTS_SQL);
    }

    /**
     * 两个方法共用的执行器。
     *
     * <p>抽出来不只是为了少写几行：{@code SET LOCAL} 的拼接、UUID 白名单、事务边界
     * 这三件事一旦有两份写法，就会出现"一处校验了、一处没校验"——
     * 而这里恰恰是 {@code SET LOCAL app.tenant_id = '<拼接值>'} 的唯一防线
     * （{@code SET} 不支持绑定参数，故白名单是防止拼接注入的<b>全部</b>措施）。
     */
    private boolean exists(String tenantId, UUID id, String label, String sql) {
        requireUuid(tenantId, "租户标识");
        if (id == null) {
            // 空 ID 不查库：一次 `WHERE id = NULL::uuid` 恒返回 false，
            // 而那会让"字段缺失"与"客户不存在"得到同一个错误 —— 两者该由不同的码回答。
            throw new BizException(ErrorCode.VALIDATION_FAILED, label + "标识缺失，拒绝存在性查询");
        }
        requireUuid(id.toString(), label + "标识");
        Boolean found = inTenant(tenantId, () -> jdbc.queryForObject(sql, Boolean.class, id.toString()));
        return Boolean.TRUE.equals(found);
    }

    private <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId, "租户标识");
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /**
     * UUID 白名单 —— {@code SET LOCAL} 拼接前的唯一防护。
     *
     * <p>与 {@link RefundWorkOrderLedger#requireUuid} 的判据逐字相同，但<b>刻意各留一份</b>：
     * 两者是同一道防线在两个类上的落点，而合并需要一方依赖另一方（本类是只读侧、
     * 那个类是读写侧）。共用一份会让"改了这个类的正则"意外影响退款工单的写入路径。
     * 两个类各自持有，使任一处的放宽都只能影响自己。
     *
     * <p>非合法 UUID 时报 {@code TENANT_MISMATCH(2003)} 而不是 {@code 1001}：
     * 与写入路径保持同码，避免同一个调用方在"查是否存在"与"真的去写"两步上
     * 拿到两个不同的错误码，从而以为是两个不同的问题。
     */
    private static void requireUuid(String value, String label) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    label + " 非合法 UUID，拒绝执行存在性查询（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }
}