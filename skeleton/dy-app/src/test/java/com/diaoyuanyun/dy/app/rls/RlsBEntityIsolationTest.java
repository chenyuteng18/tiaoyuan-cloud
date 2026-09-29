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
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RlsBEntityIsolationTest: V2/V3 迁移新增的 B 类实体表的【真实 PostgreSQL 隔离门禁】——
 * {@code customer_state_transition}（14 态状态机实体，data-dict §2.25 / BD-3·F-4）、
 * {@code band}（客户级手环实体，data-dict §2.26 / BD-3·F-3）、
 * {@code band_telemetry}（客户级手环遥测长表，data-dict §2.17【已定案 · F-2】· V3）。
 *
 * <h2>为什么必须补这一个类</h2>
 * {@link RlsCoverageGateTest} 把"迁移里含 {@code tenant_id} 的表"与"登记了隔离测试的表"
 * 做三方交叉断言（迁移文本 == 登记表 == 真库 {@code information_schema}）。
 * V2 新增两张带 {@code tenant_id} 的表后，若不补隔离测试，
 * 门禁会以"以下租户表没有对应的 RLS 隔离测试"直接红 —— 这正是 ADR-02 第 3 层
 * 「租户表无隔离测试即构建失败」的设计意图，不是误报。
 *
 * <h2>与 RlsTenantIsolationTest 的关系（分工，不是重复）</h2>
 * 那个类盯 {@code customer}（V1 基线的示范表，且承担 A0-A8 psql 脚本门禁）；
 * 本类盯 V2 / V3 的新表。两类的断言对象都是<b>真实数据库的真实行为</b>
 * （返回行数 / 被拒的 SQLSTATE / {@code pg_policies} 的真实元数据），
 * 每条断言对登记的表<b>各自执行一遍</b>（
 * {@link #TABLES}），不是"测了其中一张就代表另一张"。
 *
 * <h2>为何必须用非超级用户</h2>
 * PostgreSQL 中超级用户总是绕过 RLS。若用 {@code postgres} 连接跑隔离断言，
 * 所有断言都会"假通过"。{@code @BeforeAll} 第一件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsBEntityIsolationTest {

    /**
     * 本类覆盖的 B 类实体表（V2 两张 + V3 一张）。
     *
     * <p>⚠️ 历史记录：{@code band_telemetry} 在 F-2 未定案前刻意不在其中（该表尚未建）。
     * 2026-09-23 技术负责人拍定 F-2 = 方案 A 长表，V3 迁移建出该表 ⇒ 必须纳入本数组并
     * 登记进 {@link RlsCoverageGateTest}，否则门禁会红。
     */
    private static final List<String> TABLES =
            List.of("customer_state_transition", "band", "band_telemetry");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;
    private static final String SEED_A_ID = RlsGateSupport.SEED_A_ID;
    private static final String SEED_B_ID = RlsGateSupport.SEED_B_ID;

    /** 底数据中两租户的 band 主键（供 band_telemetry 的 device_id 引用）。 */
    private static final String SEED_BAND_A = "00000000-0000-0000-0000-0000000000b1";
    private static final String SEED_BAND_B = "00000000-0000-0000-0000-0000000000b2";

    /** 本类自建底数据时，每个租户在每张表下的行数（"反证表非空"的期望下限）。 */
    private static final int BASE_ROWS_PER_TENANT = 1;

    // ------------------------------------------------------------------
    // band_telemetry 的敏感值必须是合法【密文信封】（V10 起由 DB 层强制）
    // ------------------------------------------------------------------

    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    /**
     * 生成一个格式合法、能被 {@code CipherEnvelope.parse()} 认出的字段级加密信封。
     *
     * <h2>为什么造数必须造【真信封】而不是随便填个字符串</h2>
     * V10 迁移在 DB 层加了 {@code CHECK (... LIKE 'dy1:%')} —— 这正是它存在的意义：
     * 任何绕过 dy-crypto 的写入（含测试里的手工 INSERT）都会被数据库以 23514 拒绝。
     * 若本类继续写裸数字，测试会因"列不存在"或"CHECK 拒绝"而红，
     * 那说明门禁<b>测错了对象</b>：它想验的是 RLS 隔离，却把"没写密文"当成了隔离失效。
     *
     * <h2>🛑 为什么此处自造信封，而不是调用 dy-crypto 的 FieldCipher</h2>
     * 本类的被测对象是 <b>RLS 租户隔离</b>，不是加解密。走 FieldCipher 需要一整套
     * 密钥栈（TenantKekProvider + SubjectKeyStore + TombstoneStore + 租户 KEK 初始化），
     * 于是"RLS 是否生效"这一结论会依赖"密钥栈是否装配正确"—— 两种不相干的失败
     * 会被混成同一个症状，定位成本翻倍。故此处只产出<b>格式合法、且不会被解密路径触碰</b>
     * 的占位信封；真正的加解密往返由 {@code BandTelemetryEncryptionTest}
     * 用真实密钥栈与真实 HTTP 请求断言。
     *
     * @param plaintext 该行原本要表达的值（仅让证据可读，如 steps=8000 → "8000"）
     */
    private static String fakeEnvelope(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RNG.nextBytes(nonce);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            // DEK 内容无所谓：本信封不会进入解密路径。用全零 32 字节避免依赖随机源。
            c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.Base64.Encoder b64 = java.util.Base64.Encoder.class.cast(java.util.Base64.getEncoder());
            // 段序必须与 CipherEnvelope.serialize() 逐字一致：前缀:算法:DEK版本:nonce:密文
            return "dy1:AES-256-GCM:1:" + b64.encodeToString(nonce) + ":" + b64.encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("生成测试用密文信封失败", e);
        }
    }

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
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "RLS 真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }

        // ⓪ 构建级互斥锁：整个门禁类都在锁内（provision + 全部断言），
        //    并发的第二个构建排队等待，而不是互相拆库。
        buildMutex = RlsGateSupport.acquireBuildMutex();

        // ① 用被测交付物的【真实迁移链】（V1+V2+…）建库 + 建非超级用户角色 + 种子数据。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-B-GATE] provision:\n" + provisionLog);

        // ② 用的【非超级用户】接真库（SingleConnectionDataSource：整类复用同一条物理连接）
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

        // ③ 自建底数据：两张表在每个租户下各 1 行（用超级用户写，不受 RLS 约束）。
        //    它使"跨租户读到 0 行"具备证明力 —— 否则表是空的话 0 行毫无意义。
        seedBaseRows();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            // 清理本类自建的底数据（超级用户绕过 RLS 是 PG 既有语义）
            // 顺序：先删 band_telemetry（它 FK 引用 band），再删 band / cst。
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB,
                    "DELETE FROM band_telemetry WHERE customer_id NOT IN ('"
                            + SEED_A_ID + "', '" + SEED_B_ID + "')");
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB,
                    "DELETE FROM customer_state_transition WHERE customer_id NOT IN ('"
                            + SEED_A_ID + "', '" + SEED_B_ID + "')");
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB,
                    "DELETE FROM band WHERE customer_id NOT IN ('"
                            + SEED_A_ID + "', '" + SEED_B_ID + "')");
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
    // 断言 1: 租户 A 写入 → 租户 B 读到零行；对称方向亦零行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B 表: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
    void tenant_rows_are_invisible_across_tenants_in_both_directions() {
        for (String table : TABLES) {
            // ---- 阶段 1: 租户 A 的底数据在 A 上下文下可见（反证"表非空"）----
            Integer aRows = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertTrue(aRows >= BASE_ROWS_PER_TENANT,
                    table + ": 租户A 上下文下应可见自己的底数据, 实际 " + aRows + " 行 —— 策略退化为全拒?");

            // ---- 阶段 2: 切到租户 B，必须看不到 A 的任何行 ----
            Integer crossA = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertEquals(0, crossA, table + ": 租户B 读到了租户A 的 " + crossA + " 行 —— 串租户！");

            // ---- 阶段 3: 对称方向 —— 租户 A 看不到 B 的行 ----
            Integer crossB = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_B));
            assertEquals(0, crossB, table + ": 租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
        }
    }

    @Test
    @DisplayName("B 表: 按主键直查他租户的行也必须零行（RLS 不能只挡列表查询）")
    void direct_pk_lookup_of_other_tenant_is_also_zero_rows() {
        // ⚠️ "取出一个租户B 真实存在的主键"这一步【必须在租户B 的上下文内读】：
        //    本表是 FORCE RLS 且未设上下文即零行，若在无上下文下读，会拿到 0 行
        //    （queryForObject 直接抛 EmptyResultDataAccessException），
        //    或更糟 —— 断言"直查他租户得 0 行"因为压根没读到参照物而变成假绿。
        String bTransitionId = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject(
                        "SELECT transition_id::text FROM customer_state_transition LIMIT 1", String.class));
        assertNotNull(bTransitionId, "前置: 租户B 应有至少 1 行 customer_state_transition 底数据");

        Integer hit = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer_state_transition WHERE transition_id = ?::uuid",
                        Integer.class, bTransitionId));
        assertEquals(0, hit, "按主键直查租户B 的行竟然命中 " + hit + " 行 —— RLS 只挡了列表查询");

        // 反证参照物确实存在且对 B 可见（否则上面的 0 行无意义）
        Integer bSelf = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer_state_transition WHERE transition_id = ?::uuid",
                        Integer.class, bTransitionId));
        assertEquals(1, bSelf, "前置反证: 该 transition_id 在租户B 上下文下应可见 1 行");

        String bBandId = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT band_id::text FROM band LIMIT 1", String.class));
        assertNotNull(bBandId, "前置: 租户B 应有至少 1 行 band 底数据");

        Integer hitBand = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM band WHERE band_id = ?::uuid",
                        Integer.class, bBandId));
        assertEquals(0, hitBand, "按主键直查租户B 的 band 竟然命中 " + hitBand + " 行");

        Integer bBandSelf = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM band WHERE band_id = ?::uuid",
                        Integer.class, bBandId));
        assertEquals(1, bBandSelf, "前置反证: 该 band_id 在租户B 上下文下应可见 1 行");
    }

    // ------------------------------------------------------------------
    // 断言 2: 未设上下文 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B 表: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        for (String table : TABLES) {
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak,
                    table + ": 未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，全表泄漏！");
        }
    }

    @Test
    @DisplayName("B 表: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
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

        // 上层路径：切面对空串租户 ID 的处置必须是 403/code=2003，而不是 500/9001
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
    // 断言 3: 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B 表: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // customer_state_transition
        String victimTransition = "00000000-0000-0000-0000-00000000dead";
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO customer_state_transition "
                                    + "(transition_id, tenant_id, customer_id, from_state, to_state, trigger_event) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, NULL, 'SCREENING', 'x')",
                            victimTransition, TENANT_B, SEED_B_ID);
                    return null;
                }),
                "租户A 上下文下成功插入了租户B 的 customer_state_transition 行 —— WITH CHECK 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("42501", se.getSQLState(),
                "跨租户写入应被 RLS 以 42501(insufficient_privilege) 拒绝，实际 SQLSTATE="
                        + se.getSQLState() + " 原文=" + se.getMessage());

        // 反向自证：该行确实【没有】落库。
        // ⚠️ 必须在租户B 上下文内 count：本表 FORCE RLS，无上下文一律零行，
        //    "无上下文 count = 0" 会因为行被策略挡住而假绿，与被拒本身无关。
        Integer n = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer_state_transition WHERE transition_id = ?::uuid",
                        Integer.class, victimTransition));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");

        // band
        String victimBand = "00000000-0000-0000-0000-00000000beef";
        DataAccessException caughtBand = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', DATE '2026-09-01', 'active')",
                            victimBand, TENANT_B, SEED_B_ID);
                    return null;
                }),
                "租户A 上下文下成功插入了租户B 的 band 行 —— WITH CHECK 未生效");
        SQLException seBand = rootSqlException(caughtBand);
        assertNotNull(seBand, "被拒原因应来自数据库，实际异常链: " + caughtBand);
        assertEquals("42501", seBand.getSQLState(),
                "band 跨租户写入应被 RLS 以 42501 拒绝，实际 SQLSTATE=" + seBand.getSQLState());

        Integer nBand = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM band WHERE band_id = ?::uuid",
                        Integer.class, victimBand));
        assertEquals(0, nBand, "越权 band 行竟然落库了 " + nBand + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据（真实 pg_catalog / pg_policies，不是代码常量）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B 表: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
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
    }

    // ------------------------------------------------------------------
    // 断言 5: 14 态 CHECK 的真实库反向验证（B2 的 Java 侧对偶）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("customer_state_transition: 非法状态被 14 态 CHECK 拒绝（SQLSTATE 23514）")
    void illegal_state_is_rejected_by_the_fourteen_state_check() {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO customer_state_transition "
                                    + "(transition_id, tenant_id, customer_id, from_state, to_state, trigger_event) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, NULL, 'NOT_A_REAL_STATE', 'probe')",
                            TENANT_A, SEED_A_ID);
                    return null;
                }),
                "非法 to_state 被接受了 —— 14 态 CHECK 未生效");

        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("23514", se.getSQLState(),
                "非法状态应被 CHECK 以 23514(check_violation) 拒绝，实际 SQLSTATE="
                        + se.getSQLState() + " 原文=" + se.getMessage());
    }

    @Test
    @DisplayName("customer_state_transition: 14 个合法态逐一被接受（取值集恰好 14）")
    void all_fourteen_states_are_accepted() {
        List<String> states = List.of("SCREENING", "REJECTED", "PROFILED", "CONSENTED", "ASSESS_BASE",
                "PLAN_APPROVED", "AGREEMENT_SIGNED", "CONFIRMED", "IN_TREATMENT",
                "CYCLE_ASSESS", "PLAN_REVISING", "REFUND_REVIEW", "CLOSED", "TERMINATED");
        assertEquals(14, states.size(), "断言自身必须恰列 14 个态");

        inTenantTx(TENANT_A, () -> {
            for (String st : states) {
                try {
                    jdbc.update("INSERT INTO customer_state_transition "
                                    + "(transition_id, tenant_id, customer_id, from_state, to_state, trigger_event) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, NULL, ?, 'legal_probe')",
                            TENANT_A, SEED_A_ID, st);
                } catch (DataAccessException e) {
                    SQLException se = rootSqlException(e);
                    fail("合法态 " + st + " 被拒 —— 取值集被收窄。 SQLSTATE="
                            + (se == null ? "?" : se.getSQLState()) + " 原文="
                            + (se == null ? e.toString() : se.getMessage()), e);
                }
            }
            // 清理探针行，保持每个租户的底行数为 1（本类内部一致性）
            jdbc.update("DELETE FROM customer_state_transition "
                    + "WHERE tenant_id = ?::uuid AND trigger_event = 'legal_probe'", TENANT_A);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 断言 6: 两表的部分唯一索引（数据建模约束，非租户隔离但同属本迁移的语义）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("band: 1 客户 : 1 active 手环 由部分唯一索引强制")
    void only_one_active_band_per_customer_is_enforced() {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'GTL1', DATE '2026-09-20', 'active')",
                            TENANT_A, SEED_A_ID);
                    return null;
                }),
                "同一客户被允许存在 2 条 active 手环 —— uq_band_active_customer 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库");
        assertEquals("23505", se.getSQLState(),
                "应被部分唯一索引以 23505(unique_violation) 拒绝，实际 SQLSTATE=" + se.getSQLState());
    }

    @Test
    @DisplayName("customer_state_transition: 每客户最多 1 条 is_current=true 由部分唯一索引强制")
    void only_one_current_state_per_customer_is_enforced() {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO customer_state_transition "
                                    + "(transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'SCREENING', 'PROFILED', true, 'probe')",
                            TENANT_A, SEED_A_ID);
                    return null;
                }),
                "同一客户被允许存在 2 条 is_current=true —— uq_cst_current 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库");
        assertEquals("23505", se.getSQLState(),
                "应被部分唯一索引以 23505 拒绝，实际 SQLSTATE=" + se.getSQLState());
    }

    // ------------------------------------------------------------------
    // 断言 7: band 与 device 未合并（数据建模边界的机械守卫）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("band 与 device 未合并：band 主键=band_id 且不带 store_id")
    void band_is_not_merged_with_the_store_level_device_table() {
        String pk = jdbc.queryForObject(
                "SELECT string_agg(a.attname, ',' ORDER BY a.attname) "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN unnest(c.conkey) k ON true "
                        + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k "
                        + "WHERE t.relname = 'band' AND c.contype = 'p'", String.class);
        assertEquals("band_id", pk, "band 主键应为 band_id，不得复用 device.device_id");

        Integer storeCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='band' AND column_name='store_id'",
                Integer.class);
        assertEquals(0, storeCols,
                "band 出现了 store_id —— 把客户级手环错误地做成了门店级台账（与 device 合并）");

        Integer custCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='band' AND column_name='customer_id'",
                Integer.class);
        assertEquals(1, custCols, "band 缺 customer_id —— 无法证明它是客户级台账");
    }

    // ------------------------------------------------------------------
    // 断言 8: band_telemetry (V3 · §2.17 已定案 F-2) —— gap_reason 7 值 CHECK 的反向验证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("band_telemetry: 非法 gap_reason 被 7 值 CHECK 拒绝（SQLSTATE 23514）")
    void illegal_gap_reason_is_rejected_by_the_seven_value_check() {
        String probeBand = seedBandIdFor(TENANT_A, SEED_A_ID);
        assertNotNull(probeBand, "前置: 租户A 应有至少 1 条 active band 作为 device_id 参照");
        String probeCustomer = SEED_A_ID;

        // ① 旧拼写 `compliance_removal`（R1 裁定前契约写法）必须被拒 —— 证明拼写已统一到 `compliant_removal`
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band_telemetry "
                                    + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, "
                                    + " value_enc, synced_at, sync_state, gap_reason) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'steps', "
                                    + " DATE '2026-09-12', ?, now(), 'synced', 'compliance_removal')",
                            TENANT_A, probeCustomer, probeBand, fakeEnvelope("1"));
                    return null;
                }),
                "旧拼写 `compliance_removal` 被接受了 —— R1 裁定（统一为 `compliant_removal`）未落库");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("23514", se.getSQLState(),
                "非法 gap_reason 应被 CHECK 以 23514 拒绝，实际 SQLSTATE=" + se.getSQLState());

        // ② 7 个合法值逐一被接受（取值集恰好 7、拼写为 compliant_removal）
        List<String> legal = List.of("no_open", "sync_failed", "not_worn", "compliant_removal",
                "involuntary_technical", "beyond_retention_window", "unknown");
        assertEquals(7, legal.size(), "断言自身必须恰列 7 值（R1 裁定后取值集）");
        inTenantTx(TENANT_A, () -> {
            for (String gr : legal) {
                try {
                    jdbc.update("INSERT INTO band_telemetry "
                                    + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, hour, "
                                    + " value_enc, synced_at, sync_state, gap_reason) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'steps', "
                                    + " DATE '2026-09-13', ?, ?, now(), 'synced', ?)",
                            TENANT_A, probeCustomer, probeBand, legal.indexOf(gr),
                            fakeEnvelope("1"), gr);
                } catch (DataAccessException e) {
                    SQLException s = rootSqlException(e);
                    fail("合法 gap_reason " + gr + " 被拒 —— 取值集被收窄或拼写不符。 SQLSTATE="
                            + (s == null ? "?" : s.getSQLState()), e);
                }
            }
            jdbc.update("DELETE FROM band_telemetry "
                    + "WHERE tenant_id = ?::uuid AND date = DATE '2026-09-13'", TENANT_A);
            return null;
        });
    }

    @Test
    @DisplayName("band_telemetry: 幂等键 (device_id, metric, date, hour, minute) 对日聚合型 NULL 时点也生效")
    void band_telemetry_idempotency_key_holds_even_when_hour_minute_are_null() {
        String probeBand = seedBandIdFor(TENANT_A, SEED_A_ID);
        assertNotNull(probeBand, "前置: 租户A 应有至少 1 条 active band");

        // 关键陷阱：日聚合型 hour / minute = NULL，而 PG 的 UNIQUE 视 NULL 互不相等。
        // 若用裸 UNIQUE(device_id, metric, date, hour, minute)，同一日同指标可插多行 → 幂等被静默破坏。
        // V3 用表达式唯一索引 COALESCE(hour,-1),COALESCE(minute,-1) 堵这个洞，本断言证明它生效。
        inTenantTx(TENANT_A, () -> {
            jdbc.update("INSERT INTO band_telemetry "
                            + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, "
                            + " value_enc, synced_at, sync_state) "
                            + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'steps', "
                            + " DATE '2026-09-14', ?, now(), 'synced')",
                    TENANT_A, SEED_A_ID, probeBand, fakeEnvelope("100"));
            return null;
        });

        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band_telemetry "
                                    + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, "
                                    + " value_enc, synced_at, sync_state) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'steps', "
                                    + " DATE '2026-09-14', ?, now(), 'synced')",
                            TENANT_A, SEED_A_ID, probeBand, fakeEnvelope("200"));
                    return null;
                }),
                "同一 (device, metric, date) 在 hour/minute=NULL 时被允许重复插入 —— 幂等键对日聚合型失效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库");
        assertEquals("23505", se.getSQLState(),
                "应被表达式唯一索引以 23505(unique_violation) 拒绝，实际 SQLSTATE=" + se.getSQLState());

        inTenantTx(TENANT_A, () -> {
            jdbc.update("DELETE FROM band_telemetry "
                    + "WHERE tenant_id = ?::uuid AND date = DATE '2026-09-14'", TENANT_A);
            return null;
        });
    }

    @Test
    @DisplayName("band_telemetry: device_id 必须指向 band（V3 闭合 FK，悬空外键已关闭）")
    void band_telemetry_device_id_fk_is_closed_to_band() {
        String owner = jdbc.queryForObject(
                "SELECT confrelid::regclass::text FROM pg_constraint "
                        + "WHERE conrelid = 'band_telemetry'::regclass AND contype = 'f' "
                        + "AND conname = 'band_telemetry_device_id_fkey'", String.class);
        assertEquals("band", owner,
                "band_telemetry.device_id 的 FK 目标应为 band（R-4 悬空外键闭合），实际=" + owner);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 读本租户 context 下的一条 active band_id（用于给 band_telemetry 提供合法 device_id）。 */
    private static String seedBandIdFor(String tenant, String customerId) {
        // 底数据由 seedBaseRows() 以固定主键灌入，故可直接返回对应租户的常量。
        if (TENANT_A.equals(tenant)) {
            return SEED_BAND_A;
        }
        if (TENANT_B.equals(tenant)) {
            return SEED_BAND_B;
        }
        return null;
    }

    /**
     * 用超级用户写入两租户各 1 行底数据（超级用户绕过 RLS 是 PG 既有语义，故可自由写入）。
     *
     * <p>幂等要点：两张表都有【部分唯一索引】（{@code uq_cst_current} /
     * {@code uq_band_active_customer}），它们与主键是<b>不同的</b>冲突维度。
     * 若只靠 {@code ON CONFLICT (主键)} 兜底，上一次运行残留的、主键不同但
     * {@code is_current=true} / {@code status='active'} 的行会以 23505 撞在部分唯一索引上，
     * 使门禁在"seed 阶段"随机报红（看起来像业务 bug）。故这里<b>先按主键删除再插入</b>，
     * 并把非种子行一并清掉，保证"每租户 1 行"的起点严格可复现。
     */
    private static void seedBaseRows() {
        String cstA = "00000000-0000-0000-0000-0000000000a1";
        String cstB = "00000000-0000-0000-0000-0000000000a2";
        String bandA = SEED_BAND_A;
        String bandB = SEED_BAND_B;
        String btA = "00000000-0000-0000-0000-0000000000c1";
        String btB = "00000000-0000-0000-0000-0000000000c2";

        // 先清干净：非种子行 + 本类要用的那两行（按主键删）
        // band_telemetry 先删（FK 引用 band）。
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "DELETE FROM band_telemetry WHERE customer_id NOT IN ('"
                        + SEED_A_ID + "', '" + SEED_B_ID + "') "
                        + "OR telemetry_id IN ('" + btA + "', '" + btB + "')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "DELETE FROM customer_state_transition WHERE customer_id NOT IN ('"
                        + SEED_A_ID + "', '" + SEED_B_ID + "') "
                        + "OR transition_id IN ('" + cstA + "', '" + cstB + "')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "DELETE FROM band WHERE customer_id NOT IN ('" + SEED_A_ID + "', '" + SEED_B_ID + "') "
                        + "OR band_id IN ('" + bandA + "', '" + bandB + "')");

        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO customer_state_transition "
                        + "(transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event) "
                        + "VALUES ('" + cstA + "', '" + TENANT_A + "', '" + SEED_A_ID
                        + "', NULL, 'SCREENING', true, 'seed')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO customer_state_transition "
                        + "(transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event) "
                        + "VALUES ('" + cstB + "', '" + TENANT_B + "', '" + SEED_B_ID
                        + "', NULL, 'SCREENING', true, 'seed')");

        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO band (band_id, tenant_id, customer_id, vendor, model, bound_at, status) "
                        + "VALUES ('" + bandA + "', '" + TENANT_A + "', '" + SEED_A_ID
                        + "', 'GTL1', 'GTL1-Pro', DATE '2026-09-01', 'active')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO band (band_id, tenant_id, customer_id, vendor, model, bound_at, status) "
                        + "VALUES ('" + bandB + "', '" + TENANT_B + "', '" + SEED_B_ID
                        + "', 'GTL1', 'GTL1-Pro', DATE '2026-09-05', 'active')");

        // band_telemetry (V3 · §2.17 已定案 F-2): 每租户 1 行日型 (metric='steps')。
        // ⚠️ device_id 必须指向本租户的 band (V3 自带 FK device_id -> band(band_id));
        //    故意用"对方租户的 band_id"会被 FK 挡住 (23503), 那是 FK 而非 RLS 的拒绝,
        //    会让跨租户断言测错对象 —— 故此处各指本租户。
        // 🛑 value_enc 必须是合法信封：V10 起由 DB 层 CHECK (LIKE 'dy1:%') 强制，
        //    裸数字会被 23514 拒绝 —— 那时红的会是"没按加密口径造数"，而不是 RLS。
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO band_telemetry "
                        + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, "
                        + " value_enc, synced_at, sync_state) "
                        + "VALUES ('" + btA + "', '" + TENANT_A + "', '" + SEED_A_ID + "', '" + bandA
                        + "', 'steps', DATE '2026-09-10', '" + fakeEnvelope("8000") + "', now(), 'synced')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO band_telemetry "
                        + "(telemetry_id, tenant_id, customer_id, device_id, metric, date, "
                        + " value_enc, synced_at, sync_state) "
                        + "VALUES ('" + btB + "', '" + TENANT_B + "', '" + SEED_B_ID + "', '" + bandB
                        + "', 'steps', DATE '2026-09-11', '" + fakeEnvelope("9000") + "', now(), 'synced')");
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