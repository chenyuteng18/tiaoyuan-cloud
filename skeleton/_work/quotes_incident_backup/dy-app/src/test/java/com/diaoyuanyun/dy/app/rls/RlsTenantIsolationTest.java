package com.diaoyuanyun.dy.app.rls;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.tenancy.rls.RlsSessionAspect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RLS 租户隔离【真实 PostgreSQL 端到端】门禁测试 —— DoD A2 / ADR-02 第 3 层。
 *
 * <h2>与"断言自己写的常量"的本质区别</h2>
 * 本类<b>不复用被测代码里的任何枚举 / 常量 / 集合</b>作为断言对象，断言对象是
 * <b>真实数据库的真实行为</b>：查询返回的行数、插入被拒的 SQLSTATE、
 * 事务结束后 {@code current_setting} 的实际残留值。断言"零行"是因为 PG <b>真的返回了零行</b>。
 *
 * <h2>为何必须用非超级用户 dy_app（本任务核心陷阱）</h2>
 * PostgreSQL 中<b>超级用户总是绕过 RLS</b>。若用 {@code postgres} 连接跑隔离断言，
 * 所有断言都会"假通过"（读到全表 2 行却以为隔离生效）——这正是 ADR-02 陷阱 3。
 * 故：(a) 业务连接角色固定 {@code dy_app}；(b) {@code @BeforeAll} 第一件事就是自证
 * "当前不是超级用户"（对应 {@code 03_assert.sql} 的 A0）。
 *
 * <h2>为何用 SingleConnectionDataSource 而不是连接池</h2>
 * 它保证<b>整类测试复用同一条物理连接</b>，使"事务结束后会话变量必须已失效"成为
 * {@code SET LOCAL}（而非会话级 {@code SET}）的<b>判别性证据</b>：若是连接池 + 每次新连接，
 * 变量消失可能只是换了条连接，与 SET LOCAL 无关，断言就没有证明力。
 *
 * <h2>缺库即失败（fail-closed，DoD 8）</h2>
 * 按 ADR-02 L3「租户表无对应隔离测试即构建失败」，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}（用于无库的纯编译流水线），绝不静默跳过。
 */
class RlsTenantIsolationTest {

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;
    private static final String SEED_A_ID = RlsGateSupport.SEED_A_ID;
    private static final String SEED_B_ID = RlsGateSupport.SEED_B_ID;

    private static DataSource appDataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static RlsSessionAspect aspect;
    /** 构建级互斥锁：覆盖本类整个执行期，使并发的第二个构建排队而不是拆库（Task #18）。 */
    private static RlsGateSupport.BuildMutex buildMutex;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    static void provision() throws Exception {
        if (RlsGateSupport.gateDisabled()) {
            // 显式逃生阀：abort 会把本类全部测试标记为 skipped，并在报告里可见，不是静默绿
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "RLS 真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }

        // ⓪ 先取【构建级互斥锁】：整个门禁类（provision + 全部断言）都在锁内，
        //    并发的第二个构建会排队等待。仅把 provision 做成幂等是不够的 ——
        //    03_assert.sql 的 A1/A2/A8 硬断言"每租户恰好 1 行"，
        //    而本类会在断言中途插入临时客户行，两个构建的断言阶段重叠就会互相改行数。
        buildMutex = RlsGateSupport.acquireBuildMutex();

        // ① 用被测交付物的【真实迁移脚本】（\i 引用，非手抄）建库 + 建非超级用户角色 + 种子数据。
        //    任何一步 psql 退出码非 0 都会抛异常 → @BeforeAll 失败 → 构建失败（不静默降级）。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-GATE] provision:\n" + provisionLog);

        // ② 用【非超级用户 dy_app】接真库
        // 第 4 参 suppressClose=true：单一物理连接，生命周期由事务管理器掌控
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port() + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER,
                RlsGateSupport.APP_PASSWORD,
                true);
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
    }

    /** 释放构建级互斥锁：让下一个构建（或本 JVM 的下一个门禁类）可以继续。 */
    @AfterAll
    static void releaseBuildMutex() {
        if (buildMutex != null) {
            buildMutex.close();
            buildMutex = null;
        }
    }

    @BeforeEach
    void assertNotSuperuserBeforeEach() {
        // 每例前置再确认一次：避免"某一例改过连接身份"导致后续断言悄悄假通过
        Object user = jdbc.queryForObject("select current_user", String.class);
        assertEquals(RlsGateSupport.APP_USER, user, "连接身份被改动，隔离断言将失去证明力");
        // 复位会话级 GUC：把 app.tenant_id 退回"未定义"状态，使各例的起点严格一致。
        // 不用 RESET 的话，前一例 SET LOCAL 结束后该 GUC 会停在默认值 ''（实测 PG 17 行为），
        // 本类断言虽然仍然成立（'' 与 NULL 都被 NULLIF 归一到零行），但起点不同会让失败更难定位。
        jdbc.execute("RESET app.tenant_id");
    }

    @AfterEach
    void clearTenantContextAndResetSeed() {
        TenantContext.clear();
        // Harness 复位（不参与隔离结论）：让每例面对相同的 2 行种子状态，测试彼此独立、可任意排序。
        // 用超级用户删除非种子行 —— 超级用户绕过 RLS 是 PG 既有语义，故这里能删干净。
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB,
                "DELETE FROM customer WHERE id NOT IN ('" + SEED_A_ID + "', '" + SEED_B_ID + "')");
    }

    // ------------------------------------------------------------------
    // A0-A8：与 CI 跑的是同一条命令、同一个退出码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A0-A8 真库断言脚本必须全绿（psql -v ON_ERROR_STOP=1 退出码 0 即结论）")
    void psql_gate_script_a0_to_a8_must_pass() throws Exception {
        Path script = RlsGateSupport.verificationDir().resolve("03_assert.sql");
        assertTrue(Files.isRegularFile(script), "断言脚本必须存在: " + script);

        // 用【非超级用户】跑：脚本内 A0 会先自证"当前不是超级用户"，否则一律拒跑
        RlsGateSupport.PsqlResult r = RlsGateSupport.runScript(
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, RlsGateSupport.DB, script);

        Files.writeString(RlsGateSupport.gateWorkDir().resolve("gate-baseline.log"),
                "EXIT=" + r.exitCode() + "\n" + r.output(), StandardCharsets.UTF_8);

        // 逐条核对 A0..A7 各自打印了 OK。
        // 这一步不可省：只看退出码，无法区分"断言全过"与"断言被整段删除后脚本顺利跑完"。
        for (int i = 0; i <= 7; i++) {
            if (!r.output().contains("A" + i + " OK")) {
                String why = r.output().contains("A" + i + " 失败")
                        ? "断言失败" : "断言整条消失（脚本被裁剪?）";
                fail("psql 门禁未通过: A" + i + " → " + why + "; psql EXIT=" + r.exitCode()
                        + "\n--- psql 输出 ---\n" + r.tail(40));
            }
        }
        // A8 无单条 NOTICE，以整体收尾标记为准；若 A8 失败，输出里会出现 "A8 失败"
        if (r.output().contains("A8 失败")) {
            fail("psql 门禁未通过: A8 失败\n--- psql 输出 ---\n" + r.tail(40));
        }
        assertTrue(r.output().contains("ALL RLS ASSERTIONS PASSED"),
                "缺少脚本收尾标记 'ALL RLS ASSERTIONS PASSED (A0-A8)'，断言可能未执行到末尾\n"
                        + "--- psql 输出 ---\n" + r.tail(40));
        assertEquals(0, r.exitCode(),
                "psql 门禁退出码必须为 0（CI 直接以此为准）\n--- 输出 ---\n" + r.tail(40));
    }

    // ------------------------------------------------------------------
    // A 写 → 切 B → 零行；对称方向亦零行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("租户A 写入后切到租户B：读回零行；反向亦零行（对称）")
    void tenant_a_writes_then_tenant_b_reads_zero_rows_and_vice_versa() {
        String aOnlyId = "c1a1a1a1-0000-0000-0000-0000000000a1";
        String bOnlyId = "c2b2b2b2-0000-0000-0000-0000000000b2";

        // ---- 阶段 1: 租户 A 上下文下写入一行属于 A 的数据（提交，确保对后续事务可见）----
        Integer aRows = inTenantTx(TENANT_A, () -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) VALUES (?::uuid, ?::uuid, ?, ?)",
                    aOnlyId, TENANT_A, "A-新增", "active");
            return jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
        });
        assertEquals(2, aRows, "租户A 应看到自己的 2 行（种子 1 + 新增 1）");

        // ---- 阶段 2: 切到租户 B —— 必须只看得到 B 自己的 1 行 ----
        Object unused = inTenantTx(TENANT_B, () -> {
            int total = jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
            assertEquals(1, total, "切到租户B 后可见行数应为 1（仅 B 自己的种子行），实际 " + total);

            int cross = jdbc.queryForObject(
                    "SELECT count(*) FROM customer WHERE tenant_id = ?::uuid", Integer.class, TENANT_A);
            assertEquals(0, cross, "租户B 读到了租户A 的 " + cross + " 行 —— 串租户！");

            int byId = jdbc.queryForObject(
                    "SELECT count(*) FROM customer WHERE id = ?::uuid", Integer.class, aOnlyId);
            assertEquals(0, byId, "按主键直查 A 的行也必须零行（RLS 不能只挡列表查询）");
            return null;
        });

        // ---- 阶段 3: 对称方向 —— 租户 B 写入，租户 A 读不到 ----
        inTenantTx(TENANT_B, () -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) VALUES (?::uuid, ?::uuid, ?, ?)",
                    bOnlyId, TENANT_B, "B-新增", "active");
            return null;
        });

        inTenantTx(TENANT_A, () -> {
            int crossB = jdbc.queryForObject(
                    "SELECT count(*) FROM customer WHERE tenant_id = ?::uuid", Integer.class, TENANT_B);
            assertEquals(0, crossB, "租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
            int byId = jdbc.queryForObject(
                    "SELECT count(*) FROM customer WHERE id = ?::uuid", Integer.class, bOnlyId);
            assertEquals(0, byId, "按主键直查 B 的行也必须零行");
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 未设上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未设上下文：不得回落全表，必须零行（fail-closed）")
    void without_context_query_returns_zero_rows_not_the_whole_table() {
        // 不设 TenantContext、不调用切面 —— 模拟"上下文丢失"这一最危险路径
        Integer leak = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class));
        assertEquals(0, leak,
                "未设 app.tenant_id 时读到 " + leak + " 行：fail-closed 被破坏，全表泄漏！");

        // 反证"表非空"：否则上面的 0 行毫无意义（空表也零行）。
        // 两个租户各自都看得见自己那 1 行 → 证明数据真实存在，0 行是策略所致而非无数据。
        assertEquals(1, (int) inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)),
                "租户A 上下文下应可见 1 行（用于反证『表非空』）");
        assertEquals(1, (int) inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)),
                "租户B 上下文下应可见 1 行（用于反证『表非空』）");
    }

    // ------------------------------------------------------------------
    // 空串上下文 → 零行，且不是 500
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空串上下文：NULLIF 归一 → 零行，且不抛 500（不出现 ''::uuid 转换异常）")
    void empty_string_context_yields_zero_rows_and_never_a_500() {
        // 机制：current_setting('app.tenant_id', true) 有两种"无上下文"形态：
        //   形态① 从未设置   → NULL → NULLIF(NULL,'') = NULL
        //   形态② 显式设空串 → ''   → NULLIF('','')  = NULL
        // 两者都收敛到 tenant_id = NULL → UNKNOWN → 零行（fail-closed）。
        // 若去掉 NULLIF：形态② 会抛 invalid input syntax for type uuid → 线上表现为【偶发 500】。
        Integer n = inPlainTx(() -> {
            jdbc.execute("SET LOCAL app.tenant_id = ''");
            try {
                return jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
            } catch (DataAccessException e) {
                SQLException se = rootSqlException(e);
                fail("空串上下文导致 SQL 异常（应为零行）—— NULLIF 归一失效，线上表现为 500。"
                                + " SQLSTATE=" + (se == null ? "?" : se.getSQLState())
                                + " 原文=" + (se == null ? e.toString() : se.getMessage()),
                        e);
                return null;
            }
        });
        assertNotNull(n, "必须返回零行计数而不是异常");
        assertEquals(0, n, "空串上下文读到 " + n + " 行，应为 0 行");

        // 上层路径：切面对空串租户 ID 的处置必须是 403/code=2003（TENANT_MISMATCH），
        // 而【不是】500/9001。断言对象是"真实抛出的错误码"。
        TenantContext.set("", null, null, null);
        try {
            BizException ex = assertThrows(BizException.class,
                    () -> txTemplate.execute(s -> {
                        aspect.applyTenantSession();
                        return null;
                    }),
                    "空串租户 ID 必须被切面拒绝（fail-closed），不得拼接进 SQL");
            assertEquals(2003, ex.getCode(),
                    "空串租户 ID 应为 403/code=2003（租户不匹配），不得是 500/9001；实际=" + ex.getCode());
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("租户A 上下文下插入属于租户B 的行：被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        String victimId = "c0deadbe-0000-0000-0000-0000000000ff";
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO customer (id, tenant_id, name, status) VALUES (?::uuid, ?::uuid, ?, ?)",
                            victimId, TENANT_B, "越权写入", "active");
                    return null;
                }),
                "租户A 上下文下成功插入了租户B 的行 —— WITH CHECK 未生效（写入侧隔离已破）");

        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        // 42501 = insufficient_privilege，是 PG 对 RLS WITH CHECK 违反的标准 SQLSTATE
        assertEquals("42501", se.getSQLState(),
                "跨租户写入应被 RLS 以 42501(insufficient_privilege) 拒绝，实际 SQLSTATE="
                        + se.getSQLState() + " 原文=" + se.getMessage());

        // 反向自证：该行确实【没有】落库（被拒 ≠ 只是抛了个无关异常）
        int n = inTenantTx(TENANT_B, () -> jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE id = ?::uuid", Integer.class, victimId));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 必须是 SET LOCAL（会话变量随事务结束失效），禁止 SET
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SET LOCAL 语义：事务结束后会话变量必须失效（证明用的是 LOCAL 而非会话级 SET）")
    void tenant_session_variable_must_not_survive_the_transaction() {
        // 前置：本类共用【同一条物理连接】(SingleConnectionDataSource)，
        // 故下面的"失效"不可能由"换了连接"解释 —— 这是 SET LOCAL 与 SET 的判别性证据。
        String insideTx = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
        assertEquals(TENANT_A, insideTx, "事务内 app.tenant_id 应为当前租户");

        // 事务外：变量不得再等于刚才注入的租户 —— 这是 SET LOCAL 与会话级 SET 的判别性证据。
        //
        // 实测 PG 17 的精确语义（勿凭直觉写断言）：SET LOCAL app.tenant_id='<A>' 结束后，
        // 该 GUC 并不回到"未定义(NULL)"，而是回到它在本会话的默认值。app.* 是未注册的自定义参数，
        // 其默认值是 ''（空串）—— 实测 current_setting(..., true) 返回 '' 而非 NULL。
        // 本类单例复用同一条物理连接，故事务外观察到的是 ''。若用连接池 + 每次新连接，
        // 这个"残留"会因换连接而消失，断言就失去证明力 —— 这正是本类用单连接的原因。
        String afterTx = inPlainTx(() ->
                jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
        assertFalse(TENANT_A.equals(afterTx),
                "事务结束后 app.tenant_id 仍为租户A —— 说明用的是会话级 SET，"
                        + "连接归还池后会串租户（ADR-02 明令禁止）");

        // 未设上下文（残留 '' 形态）必须零行：fail-closed 真正关心的性质。
        int n = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class));
        assertEquals(0, n, "SET LOCAL 结束后（残留 '" + afterTx + "'）应零行，实际 " + n + " 行");
    }

    @Test
    @DisplayName("未定义形态（全新会话从未设置）：current_setting 返回 NULL，策略零行且不抛 500")
    void undefined_setting_form_yields_null_and_zero_rows() {
        // NULLIF 存在的理由就是【两种】无上下文形态都要归一到零行（见 V1 迁移脚本 L52-L64）：
        //   形态① 从未设置 → NULL   ← 本例（在【尚未设置过的全新连接】上验证）
        //   形态② 设为空串 → ''     ← A4 与 leftover_empty_setting_must_still_yield_zero_rows
        // 两种形态的行为必须都是"零行且不抛异常"，凡把任一形态变成异常/放行/默认租户，都是回归。
        //
        // 为何需要一条【全新连接】：实测 PG 17 下，某会话一旦 SET LOCAL 过该未注册 GUC，
        // 之后 current_setting 会停在 ''（RESET 也回 ''）—— 在"用过"的连接上无法复现形态①。
        SingleConnectionDataSource fresh = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port() + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, true);
        fresh.setAutoCommit(true);
        JdbcTemplate freshJdbc = new JdbcTemplate(fresh);
        try {
            String val = freshJdbc.queryForObject(
                    "SELECT current_setting('app.tenant_id', true)", String.class);
            assertNull(val, "全新会话从未设置该 GUC 时，missing_ok=true 必须返回 NULL；实际='" + val + "'");

            Integer rows;
            try {
                rows = freshJdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
            } catch (DataAccessException e) {
                SQLException se = rootSqlException(e);
                fail("NULL 形态导致 SQL 异常（应为零行）—— 策略退化，线上表现为 500。 SQLSTATE="
                        + (se == null ? "?" : se.getSQLState()) + " 原文="
                        + (se == null ? e.toString() : se.getMessage()), e);
                return;
            }
            assertEquals(0, rows, "未定义(NULL)形态下读到 " + rows + " 行，应为 0 行（fail-closed）");

            // 反证"表非空"：设了上下文就看得见 —— 否则上面的 0 行可能只是"表是空的"
            assertEquals(1, (int) inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)),
                    "租户A 上下文下应可见 1 行 —— 用于反证『表非空』");
        } finally {
            fresh.destroy();
        }
    }

    @Test
    @DisplayName("SET LOCAL 的残留空串不得放行：'' 形态下也必须零行且不抛 500")
    void leftover_empty_setting_must_still_yield_zero_rows() {
        // 这一例专门盯住上面实测到的"SET LOCAL 结束后 GUC 停在 ''（而非 NULL）"形态。
        // 去掉 NULLIF → ''::uuid 抛转换异常（线上 500）；改成 COALESCE 兜底默认租户 → 这里会读到 2 行。
        String leftover = inPlainTx(() ->
                jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
        assertTrue(leftover == null || leftover.isEmpty(),
                "事务外 app.tenant_id 既不是 NULL 也不是空串，而是 '" + leftover + "' —— 会话级残留！");

        Integer rows = inPlainTx(() -> {
            try {
                return jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
            } catch (DataAccessException e) {
                SQLException se = rootSqlException(e);
                fail("残留空串上下文导致 SQL 异常（应为零行）—— NULLIF 归一失效，线上表现为 500。"
                        + " SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
                return null;
            }
        });
        assertEquals(0, rows,
                "SET LOCAL 残留值（" + (leftover == null ? "NULL" : "''") + "）下读到 " + rows
                        + " 行 —— fail-closed 被破坏");
    }

    // ------------------------------------------------------------------
    // 策略元数据 + 行为反证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("策略元数据：ENABLE + FORCE 在位，USING/WITH CHECK 均含 NULLIF（查 pg_policies 真实元数据）")
    void policy_metadata_must_show_enable_force_and_nullif_on_both_sides() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'customer'", Boolean.class),
                "customer 必须 ENABLE ROW LEVEL SECURITY");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'customer'", Boolean.class),
                "customer 必须 FORCE ROW LEVEL SECURITY（防 owner 绕过）");

        // owner 必须是【非超级用户】：若 owner 是超级用户，FORCE 无从证明（A6 会假通过）
        String owner = jdbc.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'customer'", String.class);
        assertEquals(RlsGateSupport.APP_USER, owner,
                "customer 的 owner 应为非超级用户 dy_app（否则 FORCE RLS 未被真实验证）；实际=" + owner);

        List<String> quals = jdbc.queryForList(
                "SELECT qual FROM pg_policies WHERE tablename = 'customer' AND policyname = 'tenant_isolation'",
                String.class);
        List<String> checks = jdbc.queryForList(
                "SELECT with_check FROM pg_policies WHERE tablename = 'customer' AND policyname = 'tenant_isolation'",
                String.class);
        assertEquals(1, quals.size(), "必须恰好存在一个 tenant_isolation 策略");
        assertTrue(quals.get(0) != null && quals.get(0).contains("NULLIF"),
                "USING 必须含 NULLIF（fail-closed 归一），实际: " + quals.get(0));
        assertTrue(checks.get(0) != null && checks.get(0).contains("NULLIF"),
                "WITH CHECK 必须显式含 NULLIF（写入侧校验），实际: " + checks.get(0));

        // 行为反证：策略不得退化为恒真（USING(true) 会让未设上下文读到 2 行）
        int leak = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class));
        assertEquals(0, leak, "未设上下文读到 " + leak + " 行：策略退化为 allow-all（USING(true) 形态）");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private <T> T inTenantTx(String tenantId, Supplier<T> body) {
        TenantContext.set(tenantId, "staff-test", "TENANT_ADMIN", "all");
        try {
            return txTemplate.execute(s -> {
                // 被测切面：应在【当前事务绑定的连接】上执行 SET LOCAL
                aspect.applyTenantSession();
                return body.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 空上下文事务；body 内可自行下发 SQL（用于显式构造 SET LOCAL ''）。 */
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
        public DataSource getIfAvailable() {
            return ds;
        }

        @Override
        public DataSource getIfUnique() {
            return ds;
        }
    }
}