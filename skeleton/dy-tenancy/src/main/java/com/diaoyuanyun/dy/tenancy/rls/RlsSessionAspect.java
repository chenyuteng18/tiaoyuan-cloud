package com.diaoyuanyun.dy.tenancy.rls;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * RLS 会话变量设置切面 (ADR-02 第 2 层, 强制)。
 *
 * <p>在进入被 {@link RlsScoped} 标记的数据访问方法前, 于<b>当前事务绑定的那条连接</b>上执行
 * <b>{@code SET LOCAL app.tenant_id = '<uuid>'}</b>。
 *
 * <h2>为何 {@code SET LOCAL} 而非 {@code SET}</h2>
 * {@code SET LOCAL} 随事务结束自动失效, 避免连接归还池后上下文残留造成跨请求串租户 (ADR-02 强制要求)。
 * 必须在事务内生效, 否则 PG 报 {@code SET LOCAL can only be used in transaction blocks}。
 *
 * <h2>初版实现的两个致命 bug（已修正, 勿回退）</h2>
 * <ol>
 *   <li><b>连接不一致</b>：初版用 {@code ds.getConnection()} <b>另取一条连接</b>执行 SET LOCAL,
 *       而业务 SQL 走的是 Spring 事务管理的<b>另一条</b>连接。SET LOCAL 是事务级变量,
 *       设在 A 连接上 B 连接读不到 → RLS 策略永远读到空 tenant_id → <b>所有业务查询永远零行</b>。
 *       修正：用 {@link DataSourceUtils#getConnection(DataSource)} 取<b>当前事务绑定</b>的连接。</li>
 *   <li><b>参数占位符</b>：初版写 {@code prepareStatement("SET LOCAL app.tenant_id = ?")}。
 *       <b>PostgreSQL 的 SET 语句不支持绑定参数</b>（它不是 DML），会直接抛 SQL 语法错误。
 *       修正：tenant_id 必须经<b>UUID 白名单校验</b>后拼接字符串。</li>
 * </ol>
 *
 * <h2>注入面说明</h2>
 * {@code SET LOCAL app.tenant_id = '<值>'} 的值是 SQL 拼接点。这里用
 * {@link #UUID_PATTERN} 做<b>字符集白名单</b>校验（仅 {@code [0-9a-f-]} 且固定 36 位），
 * 校验通过后拼接是安全的——因为不存在可注入的字符。任何放宽该正则的改动都必须先过安全评审。
 */
@Aspect
@Component
public class RlsSessionAspect {

    /** 严格 UUID 形态（8-4-4-4-12 小写十六进制）。白名单校验, 是注入防护的唯一依据。 */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ObjectProvider<DataSource> dataSourceProvider;

    public RlsSessionAspect(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @Before("@annotation(com.diaoyuanyun.dy.tenancy.rls.RlsScoped)")
    public void applyTenantSession() {
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null || !TenantContext.isSet()) {
            return;
        }
        String tenantId = TenantContext.tenantId();
        if (tenantId == null) {
            return;
        }
        // fail-closed: 租户 ID 非合法 UUID 一律拒绝, 绝不拼进 SQL
        if (!UUID_PATTERN.matcher(tenantId).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID, 拒绝注入 RLS 上下文");
        }
        // SET LOCAL 必须在本事务内执行
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "@RlsScoped 方法必须在事务内调用 (SET LOCAL 需事务上下文)");
        }

        // 取当前事务绑定的连接 —— 与业务 SQL 是同一条, 这是本切面正确性的关键
        Connection conn = DataSourceUtils.getConnection(ds);
        try (Statement st = conn.createStatement()) {
            st.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        } catch (SQLException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "RLS 会话变量设置失败: " + e.getMessage(), e);
        }
        // 注意: 不得在此关闭连接 —— 它由 Spring 事务管理, 关闭会破坏事务
    }
}