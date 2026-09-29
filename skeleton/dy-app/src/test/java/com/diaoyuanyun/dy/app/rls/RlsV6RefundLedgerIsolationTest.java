package com.diaoyuanyun.dy.app.rls;

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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RlsV6RefundLedgerIsolationTest: V6 迁移落库的 **退款域 3 张留痕账本表** 的【真实 PostgreSQL 隔离门禁】。
 *
 * <h2>它覆盖什么</h2>
 * V6（{@code V6__refund_domain_alignment_and_ledgers.sql}）为契约域 G（退款与挽留）
 * 补齐的三张 <b>append-only 留痕账本</b>：
 * <ul>
 *   <li>{@code refund_statement} —— 客户原话账本（PRD P0-19「原话全程留痕、不可编辑，更正须追加」）；</li>
 *   <li>{@code refund_receipt} —— 回执三态留痕（已推送 / 未授权（转线下） / 推送失败）；</li>
 *   <li>{@code refund_offline_notice} —— 转线下告知留痕（P0-19 兜底子项）。</li>
 * </ul>
 *
 * <h2>为什么必须补这一个类（而不是并进 {@link RlsV5EntityIsolationTest}）</h2>
 * 那个类的名字与职责写着「V5 的 <b>24 张</b>余量表」，且它内部有
 * {@code assertEquals(24, TABLES.size(), "本类声明的表数必须恰为 24（V5 交付范围）")} 的自证。
 * 把 V6 的三张表塞进去，会让那个类的名字开始说谎 —— 而"名字说谎的测试类"
 * 正是下一个接手的人在排查 RLS 缺口时最先被误导的地方。
 * 故本类独立存在：<b>V5 管 24 张，V6 管 3 张，各自的表数与名称互相印证。</b>
 *
 * <p>按 ADR-02 第 3 层「租户表无隔离测试即构建失败」，这 3 张表必须同时：
 * ① 登记进 {@link RlsCoverageGateTest#ISOLATION_TESTS}；
 * ② <b>登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution 的 {@code <include>}</b>
 * —— 不登记 include 会「存在但不执行」，登记表就成了一纸空文（这正是那个 execution 的
 * {@code failIfNoTests=true} 要防的形态）；③ 有真实数据库的读写隔离断言（本类）。
 *
 * <h2>这三张表为什么值得单独一套断言（它们不是"又三张普通表"）</h2>
 * <ol>
 *   <li><b>{@code refund_receipt} 带一条等价关系 CHECK</b>
 *       （{@code refund_receipt_pushed_at_iff_pushed}：「已推送 ⟺ 有 pushed_at」）。
 *       它把「三态都要落库」这条业务纪律钉进了 schema —— 若只测 RLS 而不测这条，
 *       覆盖率分母（P1-10 = 已推送 + 未授权 + 推送失败）仍可能被写坏。</li>
 *   <li><b>三张表都【刻意不带 {@code updated_at}】</b>。这不是漏写：append-only 账本
 *       一旦有"修改时间"字段，"就地改写"就会获得一个看起来正常的位置，
 *       而 P0-19 要求的是「更正 = 追加新行 + 反向指针」。故本类断言这三张表
 *       <b>在真库里确实没有 updated_at 列</b>，把"结构性不可原地更正"变成可回归的事实。</li>
 *   <li><b>{@code statement_source} / {@code receipt_state} 的字面绑定契约</b>：
 *       {@code '未授权（转线下）'}（含<b>全角括号</b>）与 {@code RefundReceiptData.receipt_state}
 *       逐字一致。库侧一旦被"顺手规范化"成半角括号或简写成 {@code '未授权'}，
 *       契约与库就会静默分叉。</li>
 * </ol>
 *
 * <h2>🛑 探针写入为什么一律走【显式回滚】的事务</h2>
 * 本类有大量"这次写入应当被接受 / 应当被拒"的探针。若让它们落库，会带来两个真实问题：
 * <ol>
 *   <li><b>底数据前提被污染</b>：元数据断言依赖「回执三态各 1 行」这一精确前提，
 *       探针行会让它变成"三态各 N 行"，断言随之失去意义；</li>
 *   <li><b>重跑即失败</b>：固定主键的探针行一旦残留，第二次运行会撞主键（23505），
 *       而报错看起来像"账本约束有问题"。用 {@code gen_random_uuid()} 只能掩盖第一个问题，
 *       仍会留下垃圾行。</li>
 * </ol>
 * 故：<b>期望被拒</b>的写入走普通事务（PG 让语句失败即中止事务，JUnit 侧异常向上抛 →
 * {@link TransactionTemplate} 自动回滚，天然零残留）；<b>期望被接受</b>的写入走
 * {@link #inRollbackTx}（显式 {@code setRollbackOnly}），断言有效而数据不落库。
 *
 * <h2>与邻类的隔离（避免把别人灌的数据当自己的证据）</h2>
 * 主键第 4 段后缀取 {@code 0003}/{@code 0004}（A/B 租户），与
 * {@link RlsV5EntityIsolationTest}（{@code 0001}/{@code 0002}）、
 * {@link RlsBEntityIsolationTest}（{@code 0000}）、基础种子（{@code c1111111…}）**零交集**，
 * 故清理可按主键前缀精确圈定本类自己灌的行。
 *
 * <h2>底数据为什么要自建</h2>
 * 「跨租户读到 0 行」只有在表<b>非空</b>时才有证明力：空表的 0 行毫无意义。
 * 三张表都 FK 引用 {@code refund}，{@code refund} 又引用 {@code store} → {@code region}，
 * 故 {@link #provisionSeeds()} 用超级用户在两租户下各建一条 region→store→staff→refund 的
 * <b>最小依赖链</b>，再灌各自的账本行。超级用户绕过 RLS 属 PG 既有语义，故可自由写入。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户总是绕过 RLS；用它跑隔离断言会全部"假通过"。
 * {@code @BeforeAll} 第二件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsV6RefundLedgerIsolationTest {

    /**
     * V6 覆盖的 3 张留痕账本表。
     *
     * <p>⚠️ 与 {@link RlsCoverageGateTest#ISOLATION_TESTS} 的登记值必须指向本类；
     * 同时本类必须登记在 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>} 里 —— 不登记 include 会「存在但不执行」。
     */
    private static final List<String> TABLES = List.of(
            "refund_statement", "refund_receipt", "refund_offline_notice");

    /** 每表的主键列名（与 V6 建表定义一致）。 */
    private static final Map<String, String> PK_COLUMNS = Map.of(
            "refund_statement", "statement_id",
            "refund_receipt", "receipt_id",
            "refund_offline_notice", "notice_id");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;
    private static final String SEED_A_ID = RlsGateSupport.SEED_A_ID;
    private static final String SEED_B_ID = RlsGateSupport.SEED_B_ID;

    /**
     * 本类底数据主键的 UUID 第 4 段后缀（A = 0003 / B = 0004）。
     *
     * <p>与邻类零交集，故 {@link #buildCleanupSql()} 可按前缀精确删除，
     * 不会误删 V5 的 {@code 0001/0002} 行或基础种子。
     */
    private static final String SFX_A = "0003";
    private static final String SFX_B = "0004";

    /** 每表每租户的底数据行数下界（receipt 有意灌 3 行 = 三态齐备，故下界取 1 即可）。 */
    private static final int BASE_ROWS_PER_TENANT = 1;

    /** 本类为 FK 链自建的依赖行所在的表（不在 {@link #TABLES} 内，但清理时必须一并删除）。 */
    private static final String REFUND_A = "00000000-0000-0000-" + SFX_A + "-000000000004";
    private static final String REFUND_B = "00000000-0000-0000-" + SFX_B + "-000000000004";

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

        // ⓪ 构建级互斥锁：整个门禁类都在锁内（provision + 全部断言）。
        buildMutex = RlsGateSupport.acquireBuildMutex();

        // ① 用被测交付物的【真实迁移链】（V1+…+V6）建库 + 建非超级用户角色 + 基础种子。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V6-GATE] provision:\n" + provisionLog);

        // ② 用【非超级用户】接真库
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

        // ③ 自建底数据（依赖链 + 3 表账本行）
        provisionSeeds();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            // 清理本类自建的底数据（超级用户绕过 RLS 是 PG 既有语义）。
            // 顺序严格按【子 → 父】的 FK 依赖序，否则会被 FK 挡住。
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

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 断言 1: 跨租户互不可见（两方向对称）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V6 账本表: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
    void tenant_rows_are_invisible_across_tenants_in_both_directions() {
        for (String table : TABLES) {
            // 阶段 1: 租户 A 上下文下可见自己的底数据（反证"表非空"）
            Integer aRows = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertTrue(aRows >= BASE_ROWS_PER_TENANT,
                    table + ": 租户A 上下文下应可见自己的底数据, 实际 " + aRows + " 行 —— 策略退化为全拒?");

            // 阶段 2: 切到租户 B，必须看不到 A 的任何行
            Integer crossA = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertEquals(0, crossA, table + ": 租户B 读到了租户A 的 " + crossA + " 行 —— 串租户！");

            // 阶段 3: 对称方向
            Integer crossB = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_B));
            assertEquals(0, crossB, table + ": 租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
        }
    }

    @Test
    @DisplayName("V6 账本表: 按主键直查他租户的行也必须零行（RLS 不能只挡列表查询）")
    void direct_pk_lookup_of_other_tenant_is_also_zero_rows() {
        for (String table : TABLES) {
            String pk = PK_COLUMNS.get(table);
            // ⚠️ "取出一个租户B 真实存在的主键"这一步【必须在租户B 的上下文内读】：
            //    本表 FORCE RLS，未设上下文即零行，在无上下文下读会拿到 0 行
            //    （queryForObject 直接抛 EmptyResultDataAccessException），
            //    或更糟 —— 断言变成"因为压根没读到参照物"的假绿。
            String bPk = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT " + pk + "::text FROM " + table + " LIMIT 1", String.class));
            assertNotNull(bPk, "前置: 租户B 应有至少 1 行 " + table + " 底数据");

            Integer hit = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + pk + " = ?::uuid",
                            Integer.class, bPk));
            assertEquals(0, hit, table + ": 按主键直查租户B 的行竟然命中 " + hit + " 行 —— RLS 只挡了列表查询");

            // 反证参照物确实存在且对 B 可见（否则上面的 0 行无意义）
            Integer bSelf = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + pk + " = ?::uuid",
                            Integer.class, bPk));
            assertEquals(1, bSelf, "前置反证: " + table + " 该主键在租户B 上下文下应可见 1 行");
        }
    }

    // ------------------------------------------------------------------
    // 断言 2: 未设上下文 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V6 账本表: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        for (String table : TABLES) {
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak,
                    table + ": 未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，全表泄漏！");
        }
    }

    @Test
    @DisplayName("V6 账本表: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
    void empty_string_context_yields_zero_rows_and_never_a_500() {
        for (String table : TABLES) {
            Integer n = inPlainTx(() -> {
                jdbc.execute("SET LOCAL app.tenant_id = ''");
                try {
                    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
                } catch (DataAccessException e) {
                    SQLException se = rootSqlException(e);
                    fail(table + ": 空串上下文导致 SQL 异常（应为零行）—— NULLIF 归一失效，线上表现为 500。"
                            + " SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
                    return null;
                }
            });
            assertNotNull(n, table + ": 必须返回零行计数而不是异常");
            assertEquals(0, n, table + ": 空串上下文读到 " + n + " 行，应为 0 行");
        }
    }

    // ------------------------------------------------------------------
    // 断言 3: 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V6 账本表: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // 探针行在【除 tenant_id 以外的每个维度上都合法】—— 这样 42501 只可能来自 RLS，
        // 不会与 CHECK 违规（23514）或 FK 违规（23503）混淆。
        assertCrossTenantInsertRejected("refund_statement",
                "INSERT INTO refund_statement "
                        + "(statement_id, tenant_id, refund_id, statement_text, statement_source) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'probe', '客户原话')",
                TENANT_B, REFUND_A);

        assertCrossTenantInsertRejected("refund_receipt",
                "INSERT INTO refund_receipt "
                        + "(receipt_id, tenant_id, refund_id, receipt_state, channel, pushed_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '已推送', '订阅消息', now())",
                TENANT_B, REFUND_A);

        assertCrossTenantInsertRejected("refund_offline_notice",
                "INSERT INTO refund_offline_notice "
                        + "(notice_id, tenant_id, refund_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '电话')",
                TENANT_B, REFUND_A);
    }

    @Test
    @DisplayName("V6 账本表: 越权行确认未落库（在对方租户上下文下 count = 0）")
    void cross_tenant_insert_leaves_no_row_behind() {
        String victim = "00000000-0000-0000-" + SFX_A + "-00000000dead";
        try {
            inTenantTx(TENANT_A, () -> {
                jdbc.update("INSERT INTO refund_offline_notice "
                                + "(notice_id, tenant_id, refund_id, channel, note) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, '当面', 'probe-leak')",
                        victim, TENANT_B, REFUND_A);
                return null;
            });
            fail("租户A 上下文下成功插入了租户B 的 refund_offline_notice 行 —— WITH CHECK 未生效");
        } catch (DataAccessException expected) {
            SQLException se = rootSqlException(expected);
            assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + expected);
            assertEquals("42501", se.getSQLState(),
                    "跨租户写入应被 RLS 以 42501(insufficient_privilege) 拒绝，实际 " + se.getSQLState());
        }

        // 反向自证：该行确实【没有】落库。
        // ⚠️ 必须在租户B 上下文内 count：本表 FORCE RLS，无上下文一律零行，
        //    "无上下文 count = 0" 会因策略挡行而假绿，与被拒本身无关。
        Integer n = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM refund_offline_notice WHERE notice_id = ?::uuid",
                        Integer.class, victim));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据（真实 pg_catalog / pg_policies） + P1-10 分母三态齐全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V6 账本表: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF，且回执三态齐备")
    void policy_metadata_shows_enable_force_and_nullif_on_both_sides() {
        for (String table : TABLES) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                            "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table),
                    table + " 必须 ENABLE ROW LEVEL SECURITY");
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                            "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table),
                    table + " 必须 FORCE ROW LEVEL SECURITY（防 owner 绕过）");

            String owner = jdbc.queryForObject(
                    "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = ?", String.class, table);
            assertEquals(RlsGateSupport.APP_USER, owner,
                    table + " 的 owner 应为非超级用户（否则 FORCE RLS 未被真实验证）；实际=" + owner);

            List<String> quals = jdbc.queryForList(
                    "SELECT qual FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                    String.class, table);
            List<String> checks = jdbc.queryForList(
                    "SELECT with_check FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                    String.class, table);
            assertEquals(1, quals.size(), table + " 必须恰好存在一个 tenant_isolation 策略");
            assertTrue(quals.get(0) != null && quals.get(0).contains("NULLIF"),
                    table + " 的 USING 必须含 NULLIF（fail-closed 归一），实际: " + quals.get(0));
            assertTrue(checks.get(0) != null && checks.get(0).contains("NULLIF"),
                    table + " 的 WITH CHECK 必须显式含 NULLIF（写入侧校验），实际: " + checks.get(0));

            // 行为反证：策略不得退化为恒真
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak, table + " 未设上下文读到 " + leak + " 行：策略退化为 allow-all（USING(true)）");
        }

        // 覆盖数自证
        assertEquals(3, TABLES.size(), "本类声明的表数必须恰为 3（V6 交付范围）");

        // P1-10 覆盖率分母 = 已推送 + 未授权 + 推送失败；三态必须都能真实落库。
        // （底数据恰好三态各 1 行 → 3 个 distinct 值；探针写入全部回滚，不会污染该前提。）
        List<String> states = inTenantTx(TENANT_A, () -> jdbc.queryForList(
                "SELECT DISTINCT receipt_state FROM refund_receipt ORDER BY receipt_state", String.class));
        assertEquals(3, states.size(),
                "底数据应覆盖回执三态（P1-10 分母不可缺一块），实际: " + states);
    }

    // ------------------------------------------------------------------
    // 断言 5: 本域特有的建模约束反向验证（CHECK 是否真有牙齿）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refund_receipt: 「已推送 ⟺ 有 pushed_at」必须双向成立（且「未授权」无推送时刻可落库）")
    void refund_receipt_pushed_at_must_be_present_exactly_when_state_is_pushed() {
        // ① 已推送 却缺 pushed_at → 拒（证据链上无从证明它真的发过）
        assertCheckRejected("refund_receipt_pushed_at_iff_pushed",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '已推送', '订阅消息')",
                TENANT_A, REFUND_A);

        // ② 未授权（转线下） 却带 pushed_at → 拒（会把一次未发生的推送算成已达标）
        assertCheckRejected("refund_receipt_pushed_at_iff_pushed",
                "INSERT INTO refund_receipt "
                        + "(receipt_id, tenant_id, refund_id, receipt_state, channel, pushed_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '未授权（转线下）', '订阅消息', now())",
                TENANT_A, REFUND_A);

        // ③ 推送失败 却带 pushed_at → 拒（同一条等价关系的另一半）
        assertCheckRejected("refund_receipt_pushed_at_iff_pushed",
                "INSERT INTO refund_receipt "
                        + "(receipt_id, tenant_id, refund_id, receipt_state, channel, pushed_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '推送失败', '订阅消息', now())",
                TENANT_A, REFUND_A);

        // ④ 已推送 + 有 pushed_at → 通过（反证 CHECK 不是全拒）
        assertAcceptedInRollbackTx("已推送配 pushed_at 应被接受",
                "INSERT INTO refund_receipt "
                        + "(receipt_id, tenant_id, refund_id, receipt_state, channel, pushed_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '已推送', '订阅消息', now())",
                TENANT_A, REFUND_A);

        // ⑤ 🛑 本域最关键的一条：未授权（转线下）**无推送时刻也必须能落库**。
        //    若把「未授权」建模成"发送失败的到达状态"，这条就会被 CHECK 拒掉，
        //    而 P0-19 逐字要求它是【推送事件根本没发生】时的一条独立留痕。
        assertAcceptedInRollbackTx("未授权（转线下）无 pushed_at 必须能独立落库（P0-19 核心语义）",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '未授权（转线下）', '订阅消息')",
                TENANT_A, REFUND_A);

        // ⑥ 推送失败 无 pushed_at → 通过（三态齐备的另一半）
        assertAcceptedInRollbackTx("推送失败无 pushed_at 应被接受",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '推送失败', '订阅消息')",
                TENANT_A, REFUND_A);
    }

    @Test
    @DisplayName("refund_receipt: receipt_state / channel 字面与契约逐字绑定（含全角括号，不得被规范化）")
    void refund_receipt_state_and_channel_literals_are_bound_to_the_contract() {
        // ① 半角括号的 '未授权(转线下)'（"顺手规范化"的形态）必须被拒 —— 它与契约字面不同
        assertCheckRejected("refund_receipt_receipt_state_check",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '未授权(转线下)', '订阅消息')",
                TENANT_A, REFUND_A);

        // ② 简写 '未授权' 必须被拒：它丢掉了「转线下」这个兜底动作，是同一状态的不同含义
        assertCheckRejected("refund_receipt_receipt_state_check",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '未授权', '订阅消息')",
                TENANT_A, REFUND_A);

        // ③ 契约里不存在的第四态 '已读' 必须被拒
        assertCheckRejected("refund_receipt_receipt_state_check",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '已读', '订阅消息')",
                TENANT_A, REFUND_A);

        // ④ 通道 '短信' 不在 P0-19 裁定范围内（订阅消息 / 电话 / 当面）→ 拒
        assertCheckRejected("refund_receipt_channel_check",
                "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '推送失败', '短信')",
                TENANT_A, REFUND_A);

        // ⑤ 三个合法通道逐一被接受（反证通道 CHECK 不是全拒）
        List<String> legalChannels = List.of("订阅消息", "电话", "当面");
        assertEquals(3, legalChannels.size(), "断言自身必须恰列 3 通道（P0-19 裁定）");
        for (String channel : legalChannels) {
            assertAcceptedInRollbackTx("合法通道 " + channel + " 应被接受",
                    "INSERT INTO refund_receipt (receipt_id, tenant_id, refund_id, receipt_state, channel) "
                            + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '推送失败', ?)",
                    TENANT_A, REFUND_A, channel);
        }
    }

    // ------------------------------------------------------------------
    // 断言 5b: refund_offline_notice —— 三张账本里【唯一没有字段级断言】的那一张
    //          （补于批次七；缺口由「本类 11 条断言全锚定 RLS/refund_receipt/refund_statement」清点得出）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refund_offline_notice: channel 只认「电话 / 当面」，且【订阅消息必须被拒】（否则线下告知被算成推送）")
    void refund_offline_notice_channel_excludes_push_channels() {
        // ① 🛑 本断言的核心：「订阅消息」是推送通道，**不得**出现在线下告知账本里。
        //    这不是"顺便也测一下枚举"——
        //    若这里放行，记账时「线下告知」就会被算成一次「已推送」，
        //    而 PRD 逐字写着「不得计入推送覆盖率分母」（分母 = 已推送 + 未授权 + 推送失败）。
        //    即：一个通道字面的放行，会直接制造一次【覆盖率虚高】。
        assertCheckRejected("refund_offline_notice_channel_check",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id, channel, note) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '订阅消息', '假推送通道')",
                TENANT_A, REFUND_A);

        // ② 空串（"忘了填"的形态）与 ③ 契约外的第四通道，同样必须被拒。
        assertCheckRejected("refund_offline_notice_channel_check",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '')",
                TENANT_A, REFUND_A);
        assertCheckRejected("refund_offline_notice_channel_check",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '微信')",
                TENANT_A, REFUND_A);

        // ④ 反证「不是全拒」：两个合法通道逐一被接受。
        List<String> legalChannels = List.of("电话", "当面");
        assertEquals(2, legalChannels.size(),
                "断言自身必须恰列 2 通道（P0-19 兜底子项：自动转门店电话 / 当面告知）");
        for (String channel : legalChannels) {
            assertAcceptedInRollbackTx("线下告知合法通道 " + channel + " 应被接受",
                    "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id, channel) "
                            + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?)",
                    TENANT_A, REFUND_A, channel);
        }
    }

    @Test
    @DisplayName("refund_offline_notice: notice_id / tenant_id / refund_id / channel / noticed_at 全为 NOT NULL（缺一即拒）")
    void refund_offline_notice_required_columns_are_not_null() {
        // P0-19 兜底子项要求留痕「已转线下告知 + 操作人 + 时间」——
        // 其中【时间】是 24h 纪律的锚点、【通道】决定它是电话还是当面。
        // 任一列可空，这条证据就退化成"发生了某件事、但说不清何时何地"。
        // 与 refund_receipt 不同：本表**没有**等价关系 CHECK（没有可空的 pushed_at 这类二态字段），
        // 故它的完整性只能靠 NOT NULL 这类**结构性**约束来守 —— 断言它也就更必要。

        // ① tenant_id 省略：RLS 的 NULLIF 会把它判为"无上下文" ⇒ 拒绝（这里期望 42501 而非 23514，
        //    故单独走一条断言，不去混用 assertCheckRejected 的 23514 期望）
        assertCrossTenantInsertRejected("refund_offline_notice",
                "INSERT INTO refund_offline_notice (notice_id, refund_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, '电话')",
                REFUND_A);

        // ② refund_id 省略 → 表的 NOT NULL 拒
        assertNotNullRejected("refund_id",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, '电话')",
                TENANT_A);

        // ③ channel 省略 → 表的 NOT NULL 拒（通道缺失 = 说不清是电话还是当面）
        assertNotNullRejected("channel",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid)",
                TENANT_A, REFUND_A);

        // ④ 反证：四列齐备（noticed_at 走 DEFAULT now()）必须能落库
        assertAcceptedInRollbackTx("四列齐备的线下告知应被接受（noticed_at 由 DEFAULT 提供）",
                "INSERT INTO refund_offline_notice (notice_id, tenant_id, refund_id, channel) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '当面')",
                TENANT_A, REFUND_A);
    }

    @Test
    @DisplayName("refund_offline_notice: 恰有 1 条 CHECK（channel 枚举）—— 多出的 CHECK 必须配一条断言，否则无人守护")
    void refund_offline_notice_has_exactly_one_check_constraint() {
        // 三张账本里，只有 refund_receipt 带【等价关系 CHECK】（pushed_at ⟺ 已推送）。
        // refund_offline_notice 的完整性靠 NOT NULL 与 channel 枚举，**没有**二态字段，
        // 故它不该有第二条 CHECK。
        // 若有人"为了对称"给它加一条——比如 `note` 或 `operator_id` 的条件约束——
        // 本断言会报红，逼迫这次改动**显式表态**：新约束必须配一条对应的测试，
        // 否则它就是一条"存在但无人验证"的语义（与"登记表变一纸空文"同族）。
        Integer checksOnOffline = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "WHERE t.relname = 'refund_offline_notice' AND c.contype = 'c' "
                        + "AND c.conname LIKE '%_check'",
                Integer.class);
        assertEquals(1, checksOnOffline,
                "refund_offline_notice 应恰有 1 条 CHECK（channel 枚举）；实际 " + checksOnOffline
                        + " 条 —— 多出的 CHECK 需要一条对应的断言，否则它是一条无人守护的语义");

        // 反证：断言确实作用于一个存在的表（避免"表名打错所以查到 0 条"的假绿）
        Integer tableExists = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'refund_offline_notice'",
                Integer.class);
        assertEquals(1, tableExists,
                "refund_offline_notice 必须真实存在于真库（否则上面的『恰 1 条 CHECK』是假绿）");
    }

    @Test
    @DisplayName("refund_statement: 更正必须【追加新行 + 反向指针】，旧行逐字不变（append-only 可回归）")
    void refund_statement_correction_appends_and_never_mutates_the_original_row() {
        // ① statement_source 只认 3 值：'客户转述' 不是其一 → 拒（独立事务 + 自动回滚）
        assertCheckRejected("refund_statement_statement_source_check",
                "INSERT INTO refund_statement "
                        + "(statement_id, tenant_id, refund_id, statement_text, statement_source) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'x', '客户转述')",
                TENANT_A, REFUND_A);

        String originalId = "00000000-0000-0000-" + SFX_A + "-0000000000c1";
        String correctionId = "00000000-0000-0000-" + SFX_A + "-0000000000c2";
        String originalText = "客户当时说的是：做完第三次觉得腰更酸了。";

        // ②③④ 必须落在【同一个】事务里：更正行要引用原始行（FK），且"旧行未被改写"
        //      这条断言只有在同事务内回读才有意义。整个事务最后显式回滚 → 零残留。
        inRollbackTx(TENANT_A, () -> {
            // ② 写入原始原话（statement_source = 客户原话）
            jdbc.update("INSERT INTO refund_statement "
                            + "(statement_id, tenant_id, refund_id, statement_text, statement_source) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, '客户原话')",
                    originalId, TENANT_A, REFUND_A, originalText);

            // ③ 更正 = 追加新行 + supersedes_statement_id 指向被取代者（**不是** UPDATE 旧行）
            jdbc.update("INSERT INTO refund_statement "
                            + "(statement_id, tenant_id, refund_id, statement_text, statement_source, "
                            + " supersedes_statement_id) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '更正：是第四次。', '客户原话', ?::uuid)",
                    correctionId, TENANT_A, REFUND_A, originalId);

            // ④ 反向自证：两条都在，且旧行【逐字未被改写】——
            //    这就是"不可编辑"在数据层可回归的形态：更正不占用旧行的位置。
            String text = jdbc.queryForObject(
                    "SELECT statement_text FROM refund_statement WHERE statement_id = ?::uuid",
                    String.class, originalId);
            assertEquals(originalText, text,
                    "原始原话被改写了 —— append-only 被破坏（更正必须追加，不得覆盖）");

            String pointer = jdbc.queryForObject(
                    "SELECT supersedes_statement_id::text FROM refund_statement WHERE statement_id = ?::uuid",
                    String.class, correctionId);
            assertEquals(originalId, pointer,
                    "更正行未通过 supersedes_statement_id 指向被取代者 —— 链断，无从追溯");

            Integer both = jdbc.queryForObject(
                    "SELECT count(*) FROM refund_statement WHERE statement_id IN (?::uuid, ?::uuid)",
                    Integer.class, originalId, correctionId);
            assertEquals(2, both, "追加更正后应同时保留原始行与更正行（共 2 行）");
            return null;
        });

        // ⑤ 事务回滚的【自证】：探针行确实没有落库，底数据的"每租户 2 行"前提未被破坏。
        //    没有这一步，一个"忘了设置 rollbackOnly"的回归会以"底数据莫名变多"的形式
        //    出现在后续断言里，而不是在这里被指出。
        Integer leftover = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM refund_statement WHERE statement_id IN (?::uuid, ?::uuid)",
                        Integer.class, originalId, correctionId));
        assertEquals(0, leftover, "探针事务未被回滚，残留 " + leftover + " 行 —— 底数据前提已被污染");
    }

    @Test
    @DisplayName("三张账本表在真库里都没有 updated_at 列（结构性不可就地更正，而非只靠注释承诺）")
    void ledger_tables_have_no_updated_at_column_so_edits_cannot_pose_as_corrections() {
        // 为什么要断言"某列不存在"：
        //   append-only 一旦有了 updated_at，"就地改写"就获得一个看起来正常的位置 ——
        //   P0-19 要求的是「更正 = 追加新行 + 反向指针」。若某次改动顺手给账本加了
        //   updated_at，本断言会立刻报红，把"结构性约束被软化"变成必须表态的事件。
        for (String table : TABLES) {
            List<String> cols = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'updated_at'",
                    String.class, table);
            assertTrue(cols.isEmpty(),
                    table + " 出现了 updated_at 列 —— append-only 账本不应承载『修改时间』这一概念");
        }

        // 反证：断言确实作用于一个存在的表（避免"表名打错所以查不到列"的假绿）
        for (String table : TABLES) {
            Integer tableExists = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, tableExists, table + " 必须真实存在于真库（否则上面的『无 updated_at』是假绿）");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void assertCrossTenantInsertRejected(String table, String sql, Object... args) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(sql, args);
                    return null;
                }),
                table + ": 租户A 上下文下成功插入了租户B 的行 —— WITH CHECK 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, table + " 被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("42501", se.getSQLState(),
                table + " 跨租户写入应被 RLS 以 42501 拒绝，实际 " + se.getSQLState());
    }

    /**
     * 断言某条 INSERT 被【指定的 CHECK 约束】拒绝。
     *
     * <p>刻意同时校验两件事：SQLSTATE = {@code 23514}（check_violation）<b>且</b>
     * 错误消息里点名的约束名与预期一致。只校验 SQLSTATE 是不够的 ——
     * 一张表上可以有多条 CHECK，"某条 CHECK 报了"不等于"我要测的那条有牙齿"。
     *
     * <p>事务由 {@link TransactionTemplate} 在异常路径自动回滚，故探针零残留；
     * 且 PG 的语句错误会中止事务，所以每条期望失败的写入必须是<b>独立事务</b>
     * —— 拼在同一事务里第二条起会得到 25P02（current transaction is aborted）。
     */
    private void assertCheckRejected(String constraint, String sql, Object... args) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(sql, args);
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

    /**
     * 断言某条 INSERT 因【NOT NULL 约束】被拒（SQLSTATE {@code 23502}）。
     *
     * <p>刻意与 {@link #assertCheckRejected} 分开：只校验"被拒了"是不够的 ——
     * 同一列可能既 NOT NULL 又有 CHECK，而"某条约束报了"不等于"我要测的那条有牙齿"。
     * 故本助手把 SQLSTATE 钉在 {@code 23502}（not_null_violation），并校验错误消息里
     * 点名的列与预期一致。
     */
    private void assertNotNullRejected(String column, String sql, Object... args) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(sql, args);
                    return null;
                }),
                "列 " + column + " 的可空写入被接受了 —— NOT NULL 约束未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("23502", se.getSQLState(),
                "列 " + column + " 应被 NOT NULL 以 23502 拒绝，实际 " + se.getSQLState());
        assertTrue(se.getMessage() != null && se.getMessage().contains(column),
                "被拒的列不是预期的 " + column + " —— 断言打偏了。实际消息: " + se.getMessage());
    }

    /**
     * 断言某条 INSERT 在租户 A 上下文下通过（反证 CHECK / 枚举不是全拒），
     * 并在同一事务内显式回滚 —— <b>简述：断言有效，数据不落库。</b>
     */
    private void assertAcceptedInRollbackTx(String message, String sql, Object... args) {
        try {
            inRollbackTx(TENANT_A, () -> {
                jdbc.update(sql, args);
                return null;
            });
        } catch (DataAccessException e) {
            SQLException se = rootSqlException(e);
            fail(message + " SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
        }
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

    /**
     * 租户上下文内的<b>显式回滚</b>事务：断言照常执行，写入最后被丢弃。
     *
     * <p>与 {@link #inTenantTx} 的唯一差别是函数返回前调用 {@code setRollbackOnly()}。
     * 用它承载"应当成功"的探针写入，是"探针不污染底数据、且重跑安全"的关键 ——
     * 否则固定主键的探针行第二次运行会撞 23505，报错看起来像"账本约束有问题"。
     */
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
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB, cleanup);
        }
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_A, SEED_A_ID, SFX_A));
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_B, SEED_B_ID, SFX_B));
    }

    /** 本类使用的所有主键前缀（用于精确圈定"自己灌的行"）。 */
    private static String pkPrefixFilter(String pkColumn) {
        return " (" + pkColumn + "::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR " + pkColumn + "::text LIKE '00000000-0000-0000-" + SFX_B + "-%')";
    }

    /**
     * 清理 SQL：按【子 → 父】FK 依赖序逐表删除本类灌入的行。
     *
     * <p>不采用"按 customer_id NOT IN (种子)"的孤儿删除法：本类底数据的
     * {@code refund} 行<b>正是挂在两个基础种子客户上</b>的，孤儿删除法会把它当成
     * "合法行"留下，反把其它类的行删掉。故改用<b>主键前缀</b>精确圈定。
     */
    private static String buildCleanupSql() {
        String[] order = {
                "refund_statement", "refund_receipt", "refund_offline_notice",
                "refund", "staff", "store", "region",
        };
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");
        for (String t : order) {
            String pk = PK_COLUMNS.getOrDefault(t, t + "_id");
            sb.append("DELETE FROM ").append(t).append(" WHERE")
              .append(pkPrefixFilter(pk)).append(";\n");
        }
        return sb.toString();
    }

    /**
     * 为某个租户灌底数据：先建 region→store→staff→refund 的最小 FK 依赖链，
     * 再灌三张账本表的行（statement 2 行 / receipt 3 行 = 三态齐备 / offline_notice 1 行）。
     *
     * <p>PK 形如 {@code 00000000-0000-0000-<sfx>-0000000000NN}，同一租户内互不重复、
     * 两租户间靠 {@code sfx} 区分，故可安全 {@code ON CONFLICT DO NOTHING} 幂等重灌。
     */
    private static String seedSqlFor(String tenant, String customer, String sfx) {
        // 末段固定 10 个 0 打底（+ 后续 2 个字符 ⇒ 末段恒为 12 字符，为合法 UUID 形态）
        String p = "00000000-0000-0000-" + sfx + "-0000000000";
        return "\\set ON_ERROR_STOP on\n"
                + ins("region", "region_id, tenant_id, name",
                        q(p + "01") + ", " + q(tenant) + ", '区域V6'")
                + ins("store", "store_id, tenant_id, region_id, name, franchise_type",
                        q(p + "02") + ", " + q(tenant) + ", " + q(p + "01") + ", '门店V6', '直营'")
                + ins("staff", "staff_id, tenant_id, store_id, role",
                        q(p + "03") + ", " + q(tenant) + ", " + q(p + "02") + ", '经络师'")
                + ins("refund", "refund_id, tenant_id, customer_id, entry, refund_route, "
                        + "liable_store_id, reason_code, outcome",
                        q(p + "04") + ", " + q(tenant) + ", " + q(customer)
                        + ", 'A 门店代录', '效果类', " + q(p + "02") + ", '效果未达预期', '继续'")
                + ins("refund_statement", "statement_id, tenant_id, refund_id, statement_text, statement_source",
                        q(p + "11") + ", " + q(tenant) + ", " + q(p + "04") + ", '底数据原话一', '客户原话'")
                + ins("refund_statement", "statement_id, tenant_id, refund_id, statement_text, statement_source",
                        q(p + "12") + ", " + q(tenant) + ", " + q(p + "04") + ", '底数据原话二', '调理师转交'")
                + ins("refund_receipt", "receipt_id, tenant_id, refund_id, receipt_state, channel, "
                        + "template_id, pushed_at",
                        q(p + "21") + ", " + q(tenant) + ", " + q(p + "04")
                        + ", '已推送', '订阅消息', 'tpl-receipt', now()")
                + ins("refund_receipt", "receipt_id, tenant_id, refund_id, receipt_state, channel, template_id",
                        q(p + "22") + ", " + q(tenant) + ", " + q(p + "04")
                        + ", '未授权（转线下）', '订阅消息', 'tpl-receipt'")
                + ins("refund_receipt", "receipt_id, tenant_id, refund_id, receipt_state, channel, failure_reason",
                        q(p + "23") + ", " + q(tenant) + ", " + q(p + "04")
                        + ", '推送失败', '订阅消息', 'timeout'")
                + ins("refund_offline_notice", "notice_id, tenant_id, refund_id, channel, note",
                        q(p + "31") + ", " + q(tenant) + ", " + q(p + "04") + ", '电话', '已电话告知'");
    }

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