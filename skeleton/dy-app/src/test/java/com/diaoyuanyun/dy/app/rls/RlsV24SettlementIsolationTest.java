package com.diaoyuanyun.dy.app.rls;

import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.tenancy.rls.RlsSessionAspect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V24 · {@code settlement_statement}（跨店通兑结算单）的真库隔离门禁。
 *
 * <h2>与 {@link RlsV6RefundLedgerIsolationTest} 同款纪律（缩到单表）</h2>
 * 结算单是<b>钱</b>的账本：一张单写着"哪几家店各分多少业绩与退款损失"。
 * 它串租户的后果不是"看到别人的数据"这种抽象伤害，而是<b>总部财务把 A 店的
 * 账结到 B 店头上</b> —— 故隔离断言必须真库实测，不能停留在"DDL 里写了 RLS"。
 *
 * <h2>覆盖面</h2>
 * <ol>
 *   <li>跨租户互不可见（两方向对称）+ 按主键直查他租户零行；</li>
 *   <li>未设 / 空串上下文 → 零行（fail-closed，NULLIF 归一）；</li>
 *   <li>跨租户写入被 WITH CHECK 拒绝（42501）且确认未落库；</li>
 *   <li>策略元数据：ENABLE + FORCE + owner=非超级用户 + USING/WITH CHECK 双 NULLIF；</li>
 *   <li>幂等键 {@code UNIQUE (tenant_id, request_hash)} 真有牙齿（23505）；</li>
 *   <li>业务 CHECK（period 格式 / 数量正 / 金额非负）逐条有牙齿。</li>
 * </ol>
 *
 * <h2>探针零残留</h2>
 * 期望被接受的写入走显式回滚事务（断言有效、数据不落库）；期望被拒的写入由
 * 异常路径自动回滚。主键第 4 段后缀 {@code 0005}/{@code 0006}（A/B 租户），
 * 与邻类（0000/0001/0002/0003/0004）零交集，清理按前缀精确圈定。
 */
class RlsV24SettlementIsolationTest {

    /** V24 覆盖的表（本批交付范围恰 1 张）。 */
    private static final List<String> TABLES = List.of("settlement_statement");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;

    /** 本类底数据主键的 UUID 第 4 段后缀（A = 0005 / B = 0006），与邻类零交集。 */
    private static final String SFX_A = "0005";
    private static final String SFX_B = "0006";

    private static DataSource appDataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static RlsSessionAspect aspect;
    private static RlsGateSupport.BuildMutex buildMutex;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    static void provision() throws Exception {
        if (RlsGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "RLS 真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }
        buildMutex = RlsGateSupport.acquireBuildMutex();

        // ① 真实迁移链（V1+…+V24）建库 + 非超级用户角色 + 基础种子
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V24-GATE] provision:\n" + provisionLog);

        // ② 非超级用户接真库
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port() + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, true);
        ds.setAutoCommit(true);
        appDataSource = ds;

        jdbc = new JdbcTemplate(ds);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
        aspect = new RlsSessionAspect(new FixedProvider(ds));

        try {
            String whoAmI = jdbc.queryForObject("select current_user", String.class);
            assertEquals(RlsGateSupport.APP_USER, whoAmI,
                    "门禁必须用非超级用户执行；实际连接用户=" + whoAmI);
            Boolean isSuper = jdbc.queryForObject(
                    "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
            assertEquals(Boolean.FALSE, isSuper,
                    "当前连接是超级用户 → 会绕过 RLS → 所有隔离断言将『假通过』(ADR-02 陷阱 3)");
        } catch (RuntimeException e) {
            throw RlsGateSupport.unreachable(e);
        }

        provisionSeeds();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB, buildCleanupSql());
        } finally {
            if (buildMutex != null) {
                buildMutex.close();
                buildMutex = null;
            }
        }
    }

    @BeforeEach
    void assertNotSuperuserBeforeEach() {
        Object user = jdbc.queryForObject("select current_user", String.class);
        assertEquals(RlsGateSupport.APP_USER, user, "连接身份被改动，隔离断言将失去证明力");
        jdbc.execute("RESET app.tenant_id");
    }

    @AfterAll
    static void clearTenantContextAfterAll() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 断言 1: 跨租户互不可见（两方向对称 + 主键直查）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结算单: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
    void tenant_rows_are_invisible_across_tenants_in_both_directions() {
        // 阶段 1: 租户 A 上下文下可见自己的底数据（反证"表非空"，空表的 0 行毫无意义）
        Integer aRows = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_A));
        assertTrue(aRows >= 1,
                "settlement_statement: 租户A 上下文下应可见自己的底数据, 实际 " + aRows + " 行 —— 策略退化为全拒?");

        // 阶段 2: 切到租户 B，必须看不到 A 的任何行（A 的账结到 B 店头上 = 财务事故）
        Integer crossA = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_A));
        assertEquals(0, crossA, "settlement_statement: 租户B 读到了租户A 的 " + crossA + " 行 —— 串租户！");

        // 阶段 3: 对称方向
        Integer crossB = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_B));
        assertEquals(0, crossB, "settlement_statement: 租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
    }

    @Test
    @DisplayName("结算单: 按主键直查他租户的行也必须零行（RLS 不能只挡列表查询）")
    void direct_pk_lookup_of_other_tenant_is_also_zero_rows() {
        // ⚠️ 取"租户B 真实存在的主键"必须在租户B 上下文内读（FORCE RLS 下无上下文即零行）
        String bPk = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT statement_id::text FROM settlement_statement LIMIT 1", String.class));
        assertNotNull(bPk, "前置: 租户B 应有至少 1 行 settlement_statement 底数据");

        Integer hit = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE statement_id = ?::uuid",
                        Integer.class, bPk));
        assertEquals(0, hit, "settlement_statement: 按主键直查租户B 的行竟然命中 " + hit + " 行");

        Integer bSelf = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE statement_id = ?::uuid",
                        Integer.class, bPk));
        assertEquals(1, bSelf, "前置反证: 该主键在租户B 上下文下应可见 1 行");
    }

    // ------------------------------------------------------------------
    // 断言 2: 未设上下文 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结算单: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        Integer leak = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement", Integer.class));
        assertEquals(0, leak,
                "settlement_statement: 未设 app.tenant_id 时读到 " + leak + " 行 —— 全表泄漏！");
    }

    @Test
    @DisplayName("结算单: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
    void empty_string_context_yields_zero_rows_and_never_a_500() {
        Integer n = inPlainTx(() -> {
            jdbc.execute("SET LOCAL app.tenant_id = ''");
            return jdbc.queryForObject("SELECT count(*) FROM settlement_statement", Integer.class);
        });
        assertNotNull(n, "必须返回零行计数而不是异常");
        assertEquals(0, n, "空串上下文读到 " + n + " 行，应为 0 行");
    }

    // ------------------------------------------------------------------
    // 断言 3: 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结算单: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（42501），且未落库")
    void cross_tenant_insert_is_rejected_by_with_check() {
        String victim = "00000000-0000-0000-" + SFX_A + "-00000000de01";
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(insertStatementSql(), victim, TENANT_B, HASH_PROBE);
                    return null;
                }),
                "settlement_statement: 租户A 上下文下成功插入了租户B 的行 —— WITH CHECK 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("42501", se.getSQLState(),
                "跨租户写入应被 RLS 以 42501(insufficient_privilege) 拒绝，实际 " + se.getSQLState());

        // 反向自证：该行确实没有落库（必须在租户B 上下文内 count —— 无上下文 count=0 会假绿）
        Integer n = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_statement WHERE statement_id = ?::uuid",
                        Integer.class, victim));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据 + 幂等键 + 业务 CHECK 的牙齿
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结算单: ENABLE+FORCE 在位、owner=非超级用户、USING/WITH CHECK 双 NULLIF、幂等键真唯一")
    void policy_metadata_idempotency_key_and_checks_have_teeth() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'settlement_statement'",
                        Boolean.class),
                "settlement_statement 必须 ENABLE ROW LEVEL SECURITY");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'settlement_statement'",
                        Boolean.class),
                "settlement_statement 必须 FORCE ROW LEVEL SECURITY（防 owner 绕过）");

        String owner = jdbc.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'settlement_statement'",
                String.class);
        assertEquals(RlsGateSupport.APP_USER, owner,
                "settlement_statement 的 owner 应为非超级用户；实际=" + owner);

        List<String> quals = jdbc.queryForList(
                "SELECT qual FROM pg_policies WHERE tablename = 'settlement_statement' "
                        + "AND policyname = 'tenant_isolation'", String.class);
        List<String> checks = jdbc.queryForList(
                "SELECT with_check FROM pg_policies WHERE tablename = 'settlement_statement' "
                        + "AND policyname = 'tenant_isolation'", String.class);
        assertEquals(1, quals.size(), "必须恰好存在一个 tenant_isolation 策略");
        assertTrue(quals.get(0) != null && quals.get(0).contains("NULLIF"),
                "USING 必须含 NULLIF（fail-closed 归一），实际: " + quals.get(0));
        assertTrue(checks.get(0) != null && checks.get(0).contains("NULLIF"),
                "WITH CHECK 必须显式含 NULLIF，实际: " + checks.get(0));

        // 幂等键：同租户同 request_hash 的第二次插入必须被 23505 拒绝
        // （正常路径由 ON CONFLICT DO NOTHING 吞掉；库层唯一约束是最后一道防线）
        DataAccessException dup = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(insertStatementSql(),
                            "00000000-0000-0000-" + SFX_A + "-00000000db01", TENANT_A, HASH_PROBE);
                    jdbc.update(insertStatementSql(),
                            "00000000-0000-0000-" + SFX_A + "-00000000db02", TENANT_A, HASH_PROBE);
                    return null;
                }),
                "同 (tenant, request_hash) 的第二行被接受了 —— 幂等唯一约束未生效");
        SQLException dupSe = rootSqlException(dup);
        assertNotNull(dupSe, "被拒原因应来自数据库");
        assertEquals("23505", dupSe.getSQLState(),
                "幂等键冲突应以 23505(unique_violation) 拒绝，实际 " + dupSe.getSQLState());

        // 覆盖数自证
        assertEquals(1, TABLES.size(), "本类声明的表数必须恰为 1（V24 交付范围）");
    }

    @Test
    @DisplayName("结算单: 业务 CHECK 逐条有牙齿（period 格式 / 数量为正 / 金额非负）")
    void business_constraints_have_teeth() {
        // ① period 不是 YYYY-MM → 23514（ck_settlement_period）
        assertCheckRejected("ck_settlement_period",
                insertWithPeriod("2026.10"));
        // ② stores_involved = 0 → 23514（ck_settlement_stores）
        assertCheckRejected("ck_settlement_stores",
                insertStatementCustom("2026-10",
                        "00000000-0000-0000-" + SFX_A + "-00000000cb01", TENANT_A,
                        "00000000-0000-0000-" + SFX_A + "-000000000002", 0, 0,
                        "10.00", "0.00", "0.0000"));
        // ③ ecc_units 为负 → 23514（ck_settlement_amounts）
        assertCheckRejected("ck_settlement_amounts",
                insertStatementCustom("2026-10",
                        "00000000-0000-0000-" + SFX_A + "-00000000cb02", TENANT_A,
                        "00000000-0000-0000-" + SFX_A + "-000000000002", 2, 5,
                        "-0.01", "0.00", "0.0000"));

        // ④ 反证 CHECK 不是全拒：一行完全合法的探针写入应当被接受（回滚，零残留）
        try {
            inRollbackTx(TENANT_A, () -> {
                jdbc.update(insertStatementSql(),
                        "00000000-0000-0000-" + SFX_A + "-000000000a01", TENANT_A, HASH_LEGAL);
                return null;
            });
        } catch (DataAccessException e) {
            SQLException se = rootSqlException(e);
            fail("完全合法的结算单写入被拒绝 —— CHECK/约束打偏了。SQLSTATE="
                    + (se == null ? "?" : se.getSQLState()), e);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 探针哈希（只要求 64 位十六进制形态，与内容无关）。 */
    private static final String HASH_PROBE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_LEGAL =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static String insertStatementSql() {
        return "INSERT INTO settlement_statement "
                + "(statement_id, tenant_id, period, closing_store_id, stores_involved, "
                + "visits_total, ecc_units, loss_yuan, split_applied, other_store_ratio, "
                + "payload, request_hash, created_by) "
                + "VALUES (?::uuid, ?::uuid, '2026-10', "
                + "'" + SEED_STORE + "', 2, 5, '10.00', '3.00', true, '0.4000', "
                + "'{\"probe\":true}'::jsonb, ?, 'rls-v24-gate')";
    }

    private static String insertWithPeriod(String period) {
        return "INSERT INTO settlement_statement "
                + "(statement_id, tenant_id, period, closing_store_id, stores_involved, "
                + "visits_total, ecc_units, loss_yuan, split_applied, other_store_ratio, "
                + "payload, request_hash, created_by) "
                + "VALUES ('00000000-0000-0000-" + SFX_A + "-000000009991', '" + TENANT_A
                + "'::uuid, '" + period + "', '" + SEED_STORE + "', 2, 5, '10.00', '3.00', "
                + "true, '0.4000', '{\"probe\":true}'::jsonb, '" + HASH_LEGAL + "', 'rls-v24-gate')";
    }

    private static String insertStatementCustom(String period, String statementId, String tenant,
                                                String storeId, int stores, int visits,
                                                String ecc, String loss, String ratio) {
        return "INSERT INTO settlement_statement "
                + "(statement_id, tenant_id, period, closing_store_id, stores_involved, "
                + "visits_total, ecc_units, loss_yuan, split_applied, other_store_ratio, "
                + "payload, request_hash, created_by) "
                + "VALUES ('" + statementId + "', '" + tenant + "'::uuid, '" + period + "', '"
                + storeId + "', " + stores + ", " + visits + ", '" + ecc + "', '" + loss
                + "', true, '" + ratio + "', '{\"probe\":true}'::jsonb, '" + HASH_LEGAL
                + "', 'rls-v24-gate')";
    }

    /** 底数据里的结案店（本类自建的最小依赖链中的门店）。 */
    private static final String SEED_STORE =
            "00000000-0000-0000-" + SFX_A + "-000000000002";

    private void assertCheckRejected(String constraint, String sql) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.execute(sql);
                    return null;
                }),
                "该行被接受了 —— 约束 " + constraint + " 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("23514", se.getSQLState(),
                "应被 CHECK 以 23514 拒绝，实际 " + se.getSQLState());
        assertTrue(se.getMessage() != null && se.getMessage().contains(constraint),
                "被拒的约束不是预期的 " + constraint + " —— 断言打偏了。实际消息: " + se.getMessage());
    }

    private <T> T inTenantTx(String tenantId, Supplier<T> body) {
        TenantContext.set(tenantId, "staff-test", "TENANT_ADMIN", "all");
        try {
            return txTemplate.execute(s -> {
                aspect.applyTenantSession();
                return body.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T inRollbackTx(String tenantId, Supplier<T> body) {
        TenantContext.set(tenantId, "staff-test", "TENANT_ADMIN", "all");
        try {
            return txTemplate.execute(s -> {
                try {
                    aspect.applyTenantSession();
                    return body.get();
                } finally {
                    s.setRollbackOnly();
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T inPlainTx(Supplier<T> body) {
        TenantContext.clear();
        return txTemplate.execute(s -> body.get());
    }

    private static SQLException rootSqlException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException se) {
                return se;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 底数据灌入 / 清理
    // ------------------------------------------------------------------

    private static void provisionSeeds() {
        String cleanup = buildCleanupSql();
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, cleanup);
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_A, SFX_A));
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_B, SFX_B));
    }

    private static String pkPrefixFilter() {
        return " (statement_id::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR statement_id::text LIKE '00000000-0000-0000-" + SFX_B + "-%')";
    }

    private static String buildCleanupSql() {
        return "\\set ON_ERROR_STOP on\n"
                + "DELETE FROM settlement_statement WHERE" + pkPrefixFilter() + ";\n"
                + "DELETE FROM store WHERE store_id::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR store_id::text LIKE '00000000-0000-0000-" + SFX_B + "-%';\n"
                + "DELETE FROM region WHERE region_id::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR region_id::text LIKE '00000000-0000-0000-" + SFX_B + "-%';\n";
    }

    /**
     * 为某租户灌最小底数据：region→store 依赖链（closing_store_id 的 FK 目标）
     * + 1 行结算单。region/store 的 FK 只要求 tenant 存在（RlsGateSupport 已建基础租户）。
     */
    private static String seedSqlFor(String tenant, String sfx) {
        String p = "00000000-0000-0000-" + sfx + "-0000000000";
        return "\\set ON_ERROR_STOP on\n"
                + ins("region", "region_id, tenant_id, name",
                        q(p + "01") + ", " + q(tenant) + ", '区域V24'")
                + ins("store", "store_id, tenant_id, region_id, name, franchise_type",
                        q(p + "02") + ", " + q(tenant) + ", " + q(p + "01") + ", '门店V24', '直营'")
                + "INSERT INTO settlement_statement "
                + "(statement_id, tenant_id, period, closing_store_id, stores_involved, "
                + "visits_total, ecc_units, loss_yuan, split_applied, other_store_ratio, "
                + "payload, request_hash, created_by) VALUES ("
                + q(p + "11") + ", " + q(tenant) + ", '2026-10', " + q(p + "02") + ", "
                + "2, 5, '10.00', '3.00', true, '0.4000', "
                + "'{\"allocations\":[{\"storeId\":\"" + p + "02\",\"eccShare\":\"10.00\"}]}', "
                + "'" + HASH_SEED + "', 'rls-v24-gate') ON CONFLICT DO NOTHING;\n";
    }

    private static final String HASH_SEED =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    private static String ins(String table, String cols, String values) {
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + values + ") ON CONFLICT DO NOTHING;\n";
    }

    private static String q(String v) {
        return "'" + v + "'";
    }

    /** 固定返回同一 DataSource 的 ObjectProvider（ObjectProvider 非函数式接口，不能用 lambda）。 */
    private static final class FixedProvider implements ObjectProvider<DataSource> {
        private final DataSource ds;

        FixedProvider(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public DataSource getObject() throws BeansException {
            return ds;
        }

        @Override
        public DataSource getObject(Object... args) throws BeansException {
            return ds;
        }

        @Override
        public DataSource getIfAvailable() throws BeansException {
            return ds;
        }

        @Override
        public DataSource getIfUnique() throws BeansException {
            return ds;
        }
    }
}
