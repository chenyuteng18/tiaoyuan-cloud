package com.diaoyuanyun.dy.tenancy.rls;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RlsSessionAspectTest: 验证 RLS 切面把 {@code SET LOCAL} 执行在
 * <b>与业务 SQL 同一条连接</b>上 (ADR-02)。
 *
 * <h2>为何必须写这个测试</h2>
 * 骨架初版的 {@code RlsSessionAspect} 用 {@code ds.getConnection()} <b>另取一条连接</b>
 * 执行 SET LOCAL, 而业务 SQL 走 Spring 事务管理的另一条连接。SET LOCAL 是事务级变量,
 * 设在 A 连接上 B 连接读不到 → <b>RLS 策略永远读到空 tenant_id → 所有业务查询永远零行</b>。
 *
 * <p>这个 bug 与 RLS 本身无关, <b>用任何 JDBC 都能测</b> —— 因此"H2 不支持 RLS 故无法测试"
 * 不是跳过它的理由。本测试通过 Spring 事务同步机制把一条<b>被监视的连接</b>绑定到当前事务,
 * 再断言切面执行 SET LOCAL 时用的就是这条连接。
 */
class RlsSessionAspectTest {

    /** 记录所有被执行过的 SQL 与承载它的 Connection 身份 (identity)。 */
    private static final class RecordingConnection {
        final Connection proxy;
        final List<String> executedSql = new ArrayList<>();

        RecordingConnection(Connection real) {
            this.proxy = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                            if ("createStatement".equals(m.getName())) {
                                Statement st = (Statement) m.invoke(real, args);
                                return Proxy.newProxyInstance(
                                        Statement.class.getClassLoader(),
                                        new Class<?>[]{Statement.class},
                                        (sp, sm, sa) -> {
                                            if ("execute".equals(sm.getName()) && sa != null && sa.length > 0) {
                                                executedSql.add(String.valueOf(sa[0]));
                                                // 不真正下发 (无需真实 DB); 直接返回 true
                                                return Boolean.TRUE;
                                            }
                                            if ("close".equals(sm.getName())) {
                                                return null; // 不关真实 statement
                                            }
                                            return sm.invoke(st, sa);
                                        });
                            }
                            if ("close".equals(m.getName())) {
                                return null; // 不关真实连接 (由事务管理)
                            }
                            return m.invoke(real, args);
                        }
                    });
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private RlsSessionAspect aspectOf(DataSource ds) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ds);
        return new RlsSessionAspect(provider);
    }

    @Test
    void set_local_runs_on_the_same_connection_bound_to_the_transaction() throws Exception {
        // 用 H2 内存库作为"真实连接"载体 —— 这里只需要一个能 createStatement 的 JDBC 连接,
        // 并不依赖 H2 支持 RLS (被测行为是"用哪条连接", 不是"RLS 是否生效")。
        DriverManagerDataSource realDs = new DriverManagerDataSource(
                "jdbc:h2:mem:rlstest;DB_CLOSE_DELAY=-1", "sa", "");
        realDs.setDriverClassName("org.h2.Driver");
        Connection real = realDs.getConnection();

        RecordingConnection recorder = new RecordingConnection(real);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        // 把被监视的连接绑定为当前事务的资源 —— 这正是 DataSourceUtils.getConnection 会取到的那条。
        // 注意: 必须是 ConnectionHolder (Spring 的资源约定), 绑裸 Connection 会 ClassCastException。
        ConnectionHolder holder = new ConnectionHolder(recorder.proxy);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(realDs, holder);

        TenantContext.set("11111111-2222-3333-4444-555555555555", "s-1", "SUPER_ADMIN", "all");
        try {
            aspectOf(realDs).applyTenantSession();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(realDs);
        }

        assertEquals(1, recorder.executedSql.size(), "应恰好执行一条 SET LOCAL");
        String sql = recorder.executedSql.get(0);
        assertTrue(sql.startsWith("SET LOCAL app.tenant_id"),
                "必须是 SET LOCAL (非会话级 SET), 实际: " + sql);
        assertTrue(sql.contains("11111111-2222-3333-4444-555555555555"),
                "必须注入当前租户 UUID, 实际: " + sql);
    }

    /**
     * 关键回归断言: 切面<b>不得</b>从 DataSource 另开新连接。
     * 做法: 让 DataSource 在"另开连接"时抛异常 —— 只要切面正确地走了事务绑定连接, 就不会触发它。
     */
    @Test
    void aspect_must_not_open_a_separate_connection() throws Exception {
        DriverManagerDataSource realDs = new DriverManagerDataSource(
                "jdbc:h2:mem:rlstest2;DB_CLOSE_DELAY=-1", "sa", "");
        realDs.setDriverClassName("org.h2.Driver");
        Connection real = realDs.getConnection();
        RecordingConnection recorder = new RecordingConnection(real);

        // 包装 DataSource: 任何 getConnection() (即"另开连接") 直接爆炸
        DataSource exploding = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        throw new AssertionError("切面不得另开连接 (SET LOCAL 会因连接不一致而失效)");
                    }
                    return m.invoke(realDs, a);
                });

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        ConnectionHolder holder2 = new ConnectionHolder(recorder.proxy);
        holder2.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(realDs, holder2);

        TenantContext.set("11111111-2222-3333-4444-555555555555", null, null, null);
        try {
            // 若切面调用了 exploding.getConnection() 会抛 AssertionError → 测试失败
            aspectOf(exploding).applyTenantSession();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(realDs);
        }
        assertEquals(1, recorder.executedSql.size());
    }

    @Test
    void non_uuid_tenant_is_rejected_before_sql_concatenation() {
        // 注入防护: 非 UUID 形态一律拒绝 (绝不拼进 SQL)
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:x", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        TenantContext.set("'; DROP TABLE customer; --", null, null, null);
        BizException ex = assertThrows(BizException.class, () -> aspectOf(ds).applyTenantSession());
        assertEquals(2003, ex.getCode());
    }

    @Test
    void requires_active_transaction() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:y", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        // 不激活事务
        TenantContext.set("11111111-2222-3333-4444-555555555555", null, null, null);
        BizException ex = assertThrows(BizException.class, () -> aspectOf(ds).applyTenantSession());
        assertEquals(9001, ex.getCode());
    }

    @Test
    void no_tenant_context_is_a_noop() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:z", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        TenantContext.clear();
        // 无上下文: 不报错、不执行 SQL (RLS 层随后以"零行"实现 fail-closed)
        aspectOf(ds).applyTenantSession();
    }
}