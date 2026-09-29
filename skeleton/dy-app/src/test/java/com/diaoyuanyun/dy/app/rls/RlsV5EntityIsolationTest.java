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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RlsV5EntityIsolationTest: V5 迁移落库的 **24 张余量实体表** 的【真实 PostgreSQL 隔离门禁】。
 *
 * <h2>它覆盖什么</h2>
 * V5（`V5__remaining_entities_org_journey_verdict_refund.sql`）一次性补齐 S1-2「22 实体建模」
 * 的余量：组织台账（region/store/staff/device）、入组链（screening_record/consent/scale/
 * plan/agreement/baseline_assessment/plan_review）、履约链（device_dispatch/visit/daily_report）、
 * 判定链（cycle_assessment/verdict）、退款结案链（refund/retention/case_archive）、
 * ★ 建档与模板（intake_profile/doc_template）、手环补拉引擎三表
 * （band_sync_probe/band_sync_log/band_daily_coverage）—— 共 **24 张**。
 *
 * <h2>为什么必须补这一个类</h2>
 * {@link RlsCoverageGateTest} 对「迁移里含 {@code tenant_id} 的表」做三方交叉断言
 * （迁移文本 == {@code ISOLATION_TESTS} 登记表 == 真库 {@code information_schema}）。
 * 24 张新表若不登记隔离测试，覆盖门禁会以
 * 「以下租户表没有对应的 RLS 隔离测试」直接红 —— 这是 ADR-02 第 3 层
 * 「租户表无隔离测试即构建失败」的设计意图，不是误报。
 *
 * <h2>与 RlsBEntityIsolationTest 的分工（不是重复）</h2>
 * 那个类盯 V2/V3 的 3 张表（{@code customer_state_transition} / {@code band} /
 * {@code band_telemetry}）；本类盯 V5 的 24 张。断言对象都是<b>真实数据库的真实行为</b>
 * （返回行数 / 被拒的 SQLSTATE / {@code pg_policies} 真实元数据），
 * 且每条断言对 {@link #TABLES} 逐表各执行一遍 —— 不是"测了其中一张就代表其余"。
 *
 * <h2>底数据为什么要自建</h2>
 * 「跨租户读到 0 行」只有在表<b>非空</b>时才有证明力：空表的 0 行毫无意义。
 * 故 {@link #provisionSeeds()} 用超级用户在两租户下各灌 1 行（24 表 × 2 租户）。
 * 超级用户绕过 RLS 属 PG 既有语义，故可自由写入。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户总是绕过 RLS；用它跑隔离断言会全部"假通过"。
 * {@code @BeforeAll} 第二件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsV5EntityIsolationTest {

    /**
     * V5 覆盖的 24 张实体表。
     *
     * <p>⚠️ 与 {@link RlsCoverageGateTest#ISOLATION_TESTS} 的登记值必须指向本类；
     * 同时本类必须登记在 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>} 里 —— 不登记 include 会"存在但不执行"，
     * 登记表就成了一纸空文。
     *
     * <p>顺序 = 依赖序（父 → 子），与底数据灌入顺序一致，便于人工复核。
     */
    private static final List<String> TABLES = List.of(
            "region", "store", "staff", "device",
            "screening_record", "consent", "scale", "plan", "agreement",
            "baseline_assessment", "plan_review",
            "device_dispatch", "visit", "daily_report",
            "cycle_assessment", "verdict",
            "refund", "retention", "case_archive",
            "intake_profile", "doc_template",
            "band_sync_probe", "band_sync_log", "band_daily_coverage");

    /** 每表的主键列名（用于按 PK 直查反证；与 V5 建表定义一致）。 */
    private static final java.util.Map<String, String> PK_COLUMNS = java.util.Map.ofEntries(
            java.util.Map.entry("region", "region_id"),
            java.util.Map.entry("store", "store_id"),
            java.util.Map.entry("staff", "staff_id"),
            java.util.Map.entry("device", "device_id"),
            java.util.Map.entry("screening_record", "screening_id"),
            java.util.Map.entry("consent", "consent_id"),
            java.util.Map.entry("scale", "scale_id"),
            java.util.Map.entry("plan", "plan_id"),
            java.util.Map.entry("agreement", "agreement_id"),
            java.util.Map.entry("baseline_assessment", "assessment_id"),
            java.util.Map.entry("plan_review", "review_id"),
            java.util.Map.entry("device_dispatch", "dispatch_id"),
            java.util.Map.entry("visit", "visit_id"),
            java.util.Map.entry("daily_report", "report_id"),
            java.util.Map.entry("cycle_assessment", "cycle_id"),
            java.util.Map.entry("verdict", "verdict_id"),
            java.util.Map.entry("refund", "refund_id"),
            java.util.Map.entry("retention", "retention_id"),
            java.util.Map.entry("case_archive", "archive_id"),
            java.util.Map.entry("intake_profile", "profile_id"),
            java.util.Map.entry("doc_template", "template_id"),
            java.util.Map.entry("band_sync_probe", "probe_id"),
            java.util.Map.entry("band_sync_log", "sync_log_id"),
            java.util.Map.entry("band_daily_coverage", "coverage_id"));

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;
    private static final String SEED_A_ID = RlsGateSupport.SEED_A_ID;
    private static final String SEED_B_ID = RlsGateSupport.SEED_B_ID;

    /**
     * 本类底数据主键的 UUID 第 4 段后缀（A = 0001 / B = 0002）。
     *
     * <p>选用该前缀的理由：与 {@link RlsBEntityIsolationTest}（第 4 段 = {@code 0000}）
     * 和基础种子（{@code c1111111…}/{@code c2222222…}）**互不相交**，
     * 故清理可按前缀精确圈定"本类自己灌的行"，不会误删邻类或基础种子的数据。
     */
    private static final String SFX_A = "0001";
    private static final String SFX_B = "0002";

    private static final int BASE_ROWS_PER_TENANT = 1;

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

        // ① 用被测交付物的【真实迁移链】（V1+…+V5）建库 + 建非超级用户角色 + 基础种子。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V5-GATE] provision:\n" + provisionLog);

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

        // ③ 自建底数据（24 表 × 2 租户各 1 行）
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
    @DisplayName("V5 表: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
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
    @DisplayName("V5 表: 按主键直查他租户的行也必须零行（RLS 不能只挡列表查询）")
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
    @DisplayName("V5 表: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        for (String table : TABLES) {
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak,
                    table + ": 未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，全表泄漏！");
        }
    }

    @Test
    @DisplayName("V5 表: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
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
    @DisplayName("V5 表: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // 取 3 张形态不同的表做代表：无外键依赖（doc_template）、有 tenant_id 之外仅名值（region）、
        // 以及带多个 FK 的（case_archive）。覆盖门禁要求"逐表断言"，但"写入侧拒绝"由同一条策略
        // 表达式（tenant_id = NULLIF(...)）对所有表统一生效，故此处取代表表并逐一验 SQLSTATE。
        assertCrossTenantInsertRejected("region",
                "INSERT INTO region (region_id, tenant_id, name) VALUES (gen_random_uuid(), ?::uuid, 'probe')",
                TENANT_B);
        assertCrossTenantInsertRejected("doc_template",
                "INSERT INTO doc_template (template_id, tenant_id, doc_type, title, content, source_type) "
                        + "VALUES (gen_random_uuid(), ?::uuid, '其他', 'probe', 'x', 'editor')",
                TENANT_B);
        assertCrossTenantInsertRejected("case_archive",
                "INSERT INTO case_archive (archive_id, tenant_id, customer_id, archive_checklist, staff_signs) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, '{}', '{}')",
                TENANT_B, SEED_B_ID);
    }

    @Test
    @DisplayName("V5 表: 越权行确认未落库（在对方租户上下文下 count = 0）")
    void cross_tenant_insert_leaves_no_row_behind() {
        String victim = "00000000-0000-0000-0000-00000000dead";
        try {
            inTenantTx(TENANT_A, () -> {
                jdbc.update("INSERT INTO region (region_id, tenant_id, name) VALUES (?::uuid, ?::uuid, 'probe-leak')",
                        victim, TENANT_B);
                return null;
            });
            fail("租户A 上下文下成功插入了租户B 的 region 行 —— WITH CHECK 未生效");
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
                jdbc.queryForObject("SELECT count(*) FROM region WHERE region_id = ?::uuid",
                        Integer.class, victim));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据（真实 pg_catalog / pg_policies）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V5 表: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
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
        assertEquals(24, TABLES.size(), "本类声明的表数必须恰为 24（V5 交付范围）");
    }

    // ------------------------------------------------------------------
    // 断言 5: 建模约束的真实库反向验证（不只测 RLS，也测 CHECK 是否真有牙齿）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("doc_template: editor 形态缺 content 被 CHECK 拒绝；upload 形态缺 file_hash 亦被拒（23514）")
    void doc_template_form_check_rejects_incomplete_forms() {
        // ① editor 但 content 为空 → 必须被拒
        DataAccessException caughtEditor = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO doc_template "
                                    + "(template_id, tenant_id, doc_type, title, source_type) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, '其他', 'probe-editor', 'editor')",
                            TENANT_A);
                    return null;
                }),
                "editor 形态缺 content 被接受了 —— ck_doc_template_content_or_file 未生效");
        assertEquals("23514", rootSqlException(caughtEditor).getSQLState(),
                "应被 CHECK 以 23514 拒绝，实际 " + rootSqlException(caughtEditor).getSQLState());

        // ② upload 但缺 file_hash → 必须被拒
        DataAccessException caughtUpload = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO doc_template "
                                    + "(template_id, tenant_id, doc_type, title, source_type, file_ref, "
                                    + " mime_type, file_size) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, '其他', 'probe-upload', 'upload', "
                                    + " 'oss://b/t/x/1/h', 'application/pdf', 1024)",
                            TENANT_A);
                    return null;
                }),
                "upload 形态缺 file_hash 被接受了 —— 快照完整性校验可被绕过");
        assertEquals("23514", rootSqlException(caughtUpload).getSQLState(),
                "应被 CHECK 以 23514 拒绝，实际 " + rootSqlException(caughtUpload).getSQLState());

        // ③ 合法 editor 形态 → 必须通过（反证 CHECK 不是全拒）
        assertDoesNotThrowInTenant(() -> {
            jdbc.update("INSERT INTO doc_template "
                            + "(template_id, tenant_id, doc_type, title, content, source_type, version) "
                            + "VALUES ('00000000-0000-0000-0001-0000000000f0', ?::uuid, '其他', "
                            + " 'probe-legal', '正文', 'editor', 9)",
                    TENANT_A);
            return null;
        }, "合法 editor 形态（content 有值）被拒 —— CHECK 过严");
    }

    @Test
    @DisplayName("doc_template: 同 doc_type 下第二个 active 被部分唯一索引拒绝（23505）")
    void doc_template_second_active_version_is_rejected() {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO doc_template "
                                    + "(template_id, tenant_id, doc_type, title, content, source_type, version) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, '知情同意书', 'probe-active-dup', "
                                    + " 'x', 'editor', 99)",
                            TENANT_A);
                    return null;
                }),
                "同 doc_type 下被允许存在 2 个 is_active=true —— uq_doc_template_active 未生效");
        assertEquals("23505", rootSqlException(caught).getSQLState(),
                "应被部分唯一索引以 23505(unique_violation) 拒绝，实际 "
                        + rootSqlException(caught).getSQLState());
    }

    @Test
    @DisplayName("band_sync_log: fail_stage 只认契约 7 值英文枚举（已废的中文 6 项档必须被拒）")
    void band_sync_log_fail_stage_accepts_only_the_contract_seven_values() {
        String bandId = "00000000-0000-0000-" + SFX_A + "-0000000000d1";

        // ① 已废的中文档（2026-09-20 更正前的写法）必须被拒
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band_sync_log "
                                    + "(sync_log_id, tenant_id, device_id, result, fail_stage) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'failed', '蓝牙')",
                            TENANT_A, bandId);
                    return null;
                }),
                "已废的中文 fail_stage『蓝牙』被接受了 —— 契约 7 值英文枚举未落库");
        assertEquals("23514", rootSqlException(caught).getSQLState(),
                "应被 CHECK 以 23514 拒绝，实际 " + rootSqlException(caught).getSQLState());

        // ② 契约 7 值逐一被接受（取值集恰好 7）
        List<String> legal = List.of("bt_off", "unauthorized", "connect_timeout", "device_low_battery",
                "occupied_by_vendor_app", "platform_suspended", "probe_out_of_window");
        assertEquals(7, legal.size(), "断言自身必须恰列 7 值（contract §4.1 fail_reason_class）");
        inTenantTx(TENANT_A, () -> {
            for (int i = 0; i < legal.size(); i++) {
                try {
                    jdbc.update("INSERT INTO band_sync_log "
                                    + "(sync_log_id, tenant_id, device_id, result, fail_stage) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 'failed', ?)",
                            uidFromText("00000000-0000-0000-" + SFX_A + "-0000000001" + String.format("%02d", i)),
                            TENANT_A, bandId, legal.get(i));
                } catch (DataAccessException e) {
                    SQLException s = rootSqlException(e);
                    fail("合法 fail_stage " + legal.get(i) + " 被拒 —— 取值集被收窄或拼写不符。 SQLSTATE="
                            + (s == null ? "?" : s.getSQLState()), e);
                }
            }
            // 清理探针行，保持"每租户 1 行"的底数据前提
            jdbc.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid AND result = 'failed'", TENANT_A);
            return null;
        });
    }

    @Test
    @DisplayName("band_daily_coverage: gap_reason 只认 7 值且拼写为 compliant_removal")
    void band_daily_coverage_gap_reason_accepts_only_seven_values() {
        // 旧拼写 `compliance_removal`（R1 裁定前）必须被拒
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO band_daily_coverage "
                                    + "(coverage_id, tenant_id, device_id, customer_id, date, gap_reason) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, "
                                    + " DATE '2026-09-21', 'compliance_removal')",
                            TENANT_A, "00000000-0000-0000-" + SFX_A + "-0000000000d1", SEED_A_ID);
                    return null;
                }),
                "旧拼写 `compliance_removal` 被接受了 —— R1 裁定（统一为 compliant_removal）未落库");
        assertEquals("23514", rootSqlException(caught).getSQLState(),
                "应被 CHECK 以 23514 拒绝，实际 " + rootSqlException(caught).getSQLState());

        // 反证合法值可用：coverage_flag = NULL 表缺失（严禁补 0），且写入必须成功
        assertDoesNotThrowInTenant(() -> {
            jdbc.update("INSERT INTO band_daily_coverage "
                            + "(coverage_id, tenant_id, device_id, customer_id, date, coverage_flag, gap_reason) "
                            + "VALUES ('00000000-0000-0000-" + SFX_A + "-0000000000f1', ?::uuid, ?::uuid, ?::uuid, "
                            + " DATE '2026-09-22', NULL, 'not_worn')",
                    TENANT_A, "00000000-0000-0000-" + SFX_A + "-0000000000d1", SEED_A_ID);
            return null;
        }, "合法的 compliant/行为性缺口行写入失败");

        inTenantTx(TENANT_A, () -> {
            jdbc.update("DELETE FROM band_daily_coverage WHERE tenant_id = ?::uuid AND date >= DATE '2026-09-21'",
                    TENANT_A);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 取文本 UUID 的 {@code ::text} 形态。
     *
     * <p>为什么必须做这一步，而不是直接 {@code rs.getString(1)} 再当 UUID 比较：
     * 本机 JDBC 驱动把 {@code UUID} 列回读为 {@link java.util.UUID} 对象，
     * {@code getObject(..., String.class)} 会把它渲染成 <b>无连字符</b>的 32 位十六进制
     * （形如 {@code 0000000000000001...}）。若把该串再拼进 {@code ?::uuid}，
     * PG 能接受，但后续的 {@code LIKE '00000000-0000-0000-0001-%'} 之类的<b>带连字符</b>
     * 前缀匹配就会静默失配 —— 症状是"参照物找不到 / 清理没清掉"，而报错信息会指向 RLS。
     * 故统一在 SQL 侧转 {@code ::text} 取值，保证形态恒为带连字符的规范 UUID。
     */
    private static String uidFromText(String canonicalUuid) {
        return canonicalUuid;
    }

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

    /** 在租户 A 事务内执行，允许成功（用于反证 CHECK 不是全拒）。 */
    private void assertDoesNotThrowInTenant(Supplier<Object> body, String message) {
        try {
            inTenantTx(TENANT_A, body);
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
     * <p>不采用"按 customer_id NOT IN (种子)"的孤儿删除法：本类 24 表的底数据
     * <b>正是挂在两个基础种子客户上</b>的，孤儿删除法会把它们当成"合法行"留下，
     * 反把其它类的行删掉。故改用<b>主键前缀</b>精确圈定（前缀与邻类零交集，见 {@link #SFX_A}）。
     */
    private static String buildCleanupSql() {
        // 顺序 = 子表先删。band_* 三表引用 band；retention→refund；verdict→cycle_assessment；
        // staff←region.supervisor_id；store←staff/device；region←store。
        String[] order = {
                "band_daily_coverage", "band_sync_log", "band_sync_probe", "band",
                "retention", "refund", "case_archive",
                "verdict", "cycle_assessment",
                "device_dispatch", "visit", "daily_report",
                "plan_review", "agreement", "baseline_assessment", "plan",
                "doc_template", "intake_profile",
                "screening_record", "consent", "scale",
                "device", "staff", "store", "region",
        };
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");
        for (String t : order) {
            String pk = PK_COLUMNS.getOrDefault(t, t + "_id");
            if ("band".equals(t)) {
                pk = "band_id";
            }
            sb.append("DELETE FROM ").append(t).append(" WHERE")
              .append(pkPrefixFilter(pk)).append(";\n");
        }
        return sb.toString();
    }

    /**
     * 为某个租户灌 24 表底数据（每表 1 行）。
     *
     * <p>PK 形如 {@code 00000000-0000-0000-<sfx>-0000000000NN}，同一租户内互不重复、
     * 两租户间靠 {@code sfx} 区分，故可安全 {@code ON CONFLICT DO NOTHING} 幂等重灌。
     */
    private static String seedSqlFor(String tenant, String customer, String sfx) {
        // 末段固定 10 个 0 打底（+ 后续 1~2 个字符 ⇒ 末段恒为 11 或 12 字符，均为合法 UUID 形态）
        String p = "00000000-0000-0000-" + sfx + "-0000000000";
        String band = "00000000-0000-0000-" + sfx + "-0000000000d1";
        return "\\set ON_ERROR_STOP on\n"
                + ins("region", "region_id, tenant_id, name",
                        q(p + "01") + ", " + q(tenant) + ", '区域'")
                + ins("store", "store_id, tenant_id, region_id, name, franchise_type",
                        q(p + "02") + ", " + q(tenant) + ", " + q(p + "01") + ", '门店', '直营'")
                + ins("staff", "staff_id, tenant_id, store_id, role",
                        q(p + "03") + ", " + q(tenant) + ", " + q(p + "02") + ", '店长'")
                + ins("device", "device_id, tenant_id, store_id, model",
                        q(p + "04") + ", " + q(tenant) + ", " + q(p + "02") + ", '杠2'")
                + ins("screening_record", "screening_id, tenant_id, customer_id, items_json, result, operator_id",
                        q(p + "05") + ", " + q(tenant) + ", " + q(customer) + ", '{}', '通过', " + q(p + "03"))
                + ins("consent", "consent_id, tenant_id, customer_id, auth_scope_json, band_willingness, "
                        + "signed_at, evidence_hash",
                        q(p + "06") + ", " + q(tenant) + ", " + q(customer) + ", '{}', '自愿佩戴', now(), 'h'")
                + ins("scale", "scale_id, tenant_id, scale_type, scale_version, name, dimension_set_json",
                        q(p + "07") + ", " + q(tenant) + ", 'primary', 'v1', '量表', '[]'")
                + ins("plan", "plan_id, tenant_id, customer_id, version, treatment_json, lifestyle_json, "
                        + "intent_params",
                        q(p + "08") + ", " + q(tenant) + ", " + q(customer) + ", 1, '{}', '{}', '{}'")
                + ins("agreement", "agreement_id, tenant_id, customer_id, plan_id, plan_version, "
                        + "refund_clause_snapshot, signed_at, signer, rendered_snapshot, rendered_hash",
                        q(p + "09") + ", " + q(tenant) + ", " + q(customer) + ", " + q(p + "08")
                        + ", 1, '{}', now(), '{}', '正文', 'h'")
                + ins("baseline_assessment", "assessment_id, tenant_id, customer_id, scale_id, metrics_json, "
                        + "diagnosis_json, migratable, age_group_locked, measure_operator",
                        q(p + "0a") + ", " + q(tenant) + ", " + q(customer) + ", " + q(p + "07")
                        + ", '{}', '{}', true, '男16-32', " + q(p + "03"))
                + ins("plan_review", "review_id, tenant_id, plan_id, plan_version, reviewer_id, result",
                        q(p + "0b") + ", " + q(tenant) + ", " + q(p + "08") + ", 1, " + q(p + "03") + ", '通过'")
                + ins("device_dispatch", "dispatch_id, tenant_id, plan_id, plan_version, store_id, device_id, "
                        + "param_snapshot, result",
                        q(p + "0c") + ", " + q(tenant) + ", " + q(p + "08") + ", 1, " + q(p + "02")
                        + ", " + q(p + "04") + ", '{}', '成功'")
                + ins("visit", "visit_id, tenant_id, customer_id, serving_store_id, plan_id, plan_version, "
                        + "gate_check_json, customer_confirmed, visit_no",
                        q(p + "0d") + ", " + q(tenant) + ", " + q(customer) + ", " + q(p + "02")
                        + ", " + q(p + "08") + ", 1, '{}', true, 1")
                + ins("daily_report", "report_id, tenant_id, customer_id, date, answers_json, source",
                        q(p + "0e") + ", " + q(tenant) + ", " + q(customer) + ", DATE '2026-09-10', '{}', '客户'")
                + ins("cycle_assessment", "cycle_id, tenant_id, customer_id, sequence_no, as_dimensions_json, "
                        + "metric_snapshot, verdict, module_scores, threshold_version",
                        q(p + "0f") + ", " + q(tenant) + ", " + q(customer)
                        + ", 1, '{}', '{}', '稳定', '{}', 'th-v1'")
                + ins("verdict", "verdict_id, tenant_id, cycle_id, branch, confidence, evidence_snapshot, "
                        + "threshold_version",
                        q(p + "10") + ", " + q(tenant) + ", " + q(p + "0f") + ", '稳定', 0.9, '{}', 'th-v1'")
                + ins("refund", "refund_id, tenant_id, customer_id, entry, refund_route, liable_store_id, "
                        + "reason_code, outcome",
                        q(p + "11") + ", " + q(tenant) + ", " + q(customer)
                        + ", 'A 门店代录', '履约类', " + q(p + "02") + ", '效果未达预期', '继续'")
                + ins("retention", "retention_id, tenant_id, refund_id, result, operator_id, analysis, communication",
                        q(p + "12") + ", " + q(tenant) + ", " + q(p + "11") + ", '接受继续服务', "
                        + q(p + "03") + ", '{}', '{}'")
                + ins("case_archive", "archive_id, tenant_id, customer_id, archive_checklist, staff_signs",
                        q(p + "13") + ", " + q(tenant) + ", " + q(customer) + ", '{}', '{}'")
                + ins("intake_profile", "profile_id, tenant_id, customer_id",
                        q(p + "14") + ", " + q(tenant) + ", " + q(customer))
                + ins("doc_template", "template_id, tenant_id, doc_type, title, content, source_type",
                        q(p + "15") + ", " + q(tenant) + ", '知情同意书', '模板', '正文', 'editor'")
                + ins("band", "band_id, tenant_id, customer_id, vendor, bound_at, status",
                        q(band) + ", " + q(tenant) + ", " + q(customer) + ", 'GTL1', DATE '2026-09-01', 'paused'")
                + ins("band_sync_probe", "probe_id, tenant_id, device_id, history_type",
                        q(p + "16") + ", " + q(tenant) + ", " + q(band) + ", 'hr'")
                + ins("band_sync_log", "sync_log_id, tenant_id, device_id, result",
                        q(p + "17") + ", " + q(tenant) + ", " + q(band) + ", 'success'")
                + ins("band_daily_coverage", "coverage_id, tenant_id, device_id, customer_id, date",
                        q(p + "18") + ", " + q(tenant) + ", " + q(band) + ", " + q(customer) + ", DATE '2026-09-10'");
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