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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code scale_item_bank}（V4 迁移，S1-4 题库内容资产）的【真实 PostgreSQL 隔离门禁】。
 *
 * <h2>为什么独立成类，而不并进 {@link RlsBEntityIsolationTest}</h2>
 * 那个类的名字与 Javadoc 写着"<b>B 类实体表</b>"（{@code customer_state_transition} /
 * {@code band} / {@code band_telemetry} —— 都是<b>客户级业务实体</b>）。
 * {@code scale_item_bank} 是<b>总部维护的题库内容资产</b>（字典 §2.24 · 附录 C.1.8 · ★），
 * 与"客户级实体"是两种东西：它<p>没有 {@code customer_id}</p>、按年龄组而非按客户组织、
 * 且版本化不可覆盖。把资产表塞进"B 类实体"里会让那个类的名字开始说谎 ——
 * 而名字说谎的测试是下一个人的陷阱。故另立本类，登记进
 * {@link RlsCoverageGateTest#ISOLATION_TESTS}，并加入 {@code dy-app/pom.xml}
 * 的 {@code rls-isolation-gate} execution（<b>不加 include 则该类根本不会被 surefire 执行</b>，
 * 门禁会以"登记了迁移中不存在的表"之外的形态静默失效）。
 *
 * <h2>它与"题库功能能不能用"是两件事</h2>
 * 本类只回答一个问题：<b>这张表的租户隔离与结构约束在真库里是否真的生效</b>。
 * "224 题可导入 / 可组卷 / 可计分"由 {@code ScaleItemBankServiceTest} 等类回答。
 * 两者不可互相替代：服务层用 mock 之外的真库跑，也绕不过 RLS 缺位这件事。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户总是绕过 RLS。用 {@code postgres} 连接跑隔离断言，所有断言都会"假通过"。
 * {@code @BeforeAll} 第一件事就是自证"当前不是超级用户"。
 *
 * <h2>容量断言为何从约束正文抠，而不是抄 8/7/4</h2>
 * 抄一遍 8/7/4 只能证明"我写的数 == 我写的数"。本类用
 * {@code pg_get_constraintdef()} 读<b>真库里这条 CHECK 的实际正文</b>，
 * 数其中的字符串字面量个数 —— 约束被收窄（例如误删一个年龄组）时，
 * 字面量个数立刻不等于 8，容量断言随即红。这是"从数据库事实推导"，不是自证。
 */
class RlsScaleItemBankIsolationTest {

    private static final String TABLE = "scale_item_bank";

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;

    /** 本类自建底数据的固定主键（两租户各 1 题）。 */
    private static final String SEED_SIB_A = "00000000-0000-0000-0000-0000000000d1";
    private static final String SEED_SIB_B = "00000000-0000-0000-0000-0000000000d2";

    /** 两租户各 1 行（"反证表非空"的期望下限）。 */
    private static final int BASE_ROWS_PER_TENANT = 1;

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
        buildMutex = RlsGateSupport.acquireBuildMutex();
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-SIB-GATE] provision:\n" + provisionLog);

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

        seedBaseRows();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            // 清理本类自建的全部探针数据（超级用户绕过 RLS 是 PG 既有语义）。
            // 🛑 必须是【该两租户的全量清理】，不能只按主键删两行种子：
            //    本类的用例会写入多种探针行（item_no 边界探针、8×7=56 枚举组合、v2 版本行、
            //    非法值探针……）。只删种子的话，这些残留会在【下一次运行】撞上
            //    uq_sib_business（同 租户/年龄组/维度/题序/版本）→ 23505，
            //    表现为"合法插入被拒"这种与本次改动毫无关系的红。
            //    实测：漏删致 56 行 enum-probe 残留，类失去可重复运行性。
            //    本表在门禁库里的内容完全由本类独占（无其它测试写 scale_item_bank），
            //    故"按两租户全量删"不会误伤他人数据。
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                    "DELETE FROM " + TABLE + " WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')");
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
    // 1) 跨租户读：双向零行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
    void tenant_rows_are_invisible_across_tenants_in_both_directions() {
        Integer aRows = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_A));
        assertTrue(aRows >= BASE_ROWS_PER_TENANT,
                "租户A 上下文下应可见自己的底数据, 实际 " + aRows + " 行 —— 策略退化为全拒?");

        Integer crossA = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_A));
        assertEquals(0, crossA, "租户B 读到了租户A 的 " + crossA + " 行题库 —— 串租户！");

        Integer crossB = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_B));
        assertEquals(0, crossB, "租户A 读到了租户B 的 " + crossB + " 行题库 —— 对称性被破坏！");
    }

    @Test
    @DisplayName("scale_item_bank: 按主键直查他租户的题也必须零行（RLS 不能只挡列表查询）")
    void direct_pk_lookup_of_other_tenant_is_also_zero_rows() {
        // ⚠️ "取出一个租户B 真实存在的主键"必须在【租户B 的上下文内】读：
        //    本表 FORCE RLS，无上下文即零行，若在无上下文下读会拿不到参照物，
        //    断言就会因为"压根没读到东西"而变成假绿。
        String bItemId = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT item_id::text FROM " + TABLE + " LIMIT 1", String.class));
        assertNotNull(bItemId, "前置: 租户B 应有至少 1 行题库底数据");

        Integer hit = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE item_id = ?::uuid",
                        Integer.class, bItemId));
        assertEquals(0, hit, "按主键直查租户B 的题竟然命中 " + hit + " 行 —— RLS 只挡了列表查询");

        Integer bSelf = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE item_id = ?::uuid",
                        Integer.class, bItemId));
        assertEquals(1, bSelf, "前置反证: 该 item_id 在租户B 上下文下应可见 1 行");
    }

    // ------------------------------------------------------------------
    // 2) 未设 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        Integer leak = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class));
        assertEquals(0, leak,
                "未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，题库全表泄漏！");
    }

    @Test
    @DisplayName("scale_item_bank: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
    void empty_string_context_yields_zero_rows_and_never_a_500() {
        Integer n = inPlainTx(() -> {
            jdbc.execute("SET LOCAL app.tenant_id = ''");
            try {
                return jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class);
            } catch (DataAccessException e) {
                SQLException se = rootSqlException(e);
                fail("空串上下文导致 SQL 异常（应为零行）—— NULLIF 归一失效，线上表现为 500。"
                        + " SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
                return null;
            }
        });
        assertNotNull(n, "必须返回零行计数而不是异常");
        assertEquals(0, n, "空串上下文读到 " + n + " 行，应为 0 行");

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
    // 3) 跨租户写：被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 租户A 上下文下插入属于租户B 的题 → 被 WITH CHECK 拒绝（42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        String victim = "00000000-0000-0000-0000-00000000cafe";
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    insertMinimal(victim, TENANT_B, "男16-32", "体能精力", 1);
                    return null;
                }),
                "租户A 上下文下成功插入了租户B 的题库行 —— WITH CHECK 未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("42501", se.getSQLState(),
                "跨租户写入应被 RLS 以 42501(insufficient_privilege) 拒绝，实际 SQLSTATE="
                        + se.getSQLState() + " 原文=" + se.getMessage());

        // 反向自证：该行确实没落库。必须在租户B 上下文内 count（本表 FORCE RLS）。
        Integer n = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE item_id = ?::uuid",
                        Integer.class, victim));
        assertEquals(0, n, "越权题库行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 4) 策略元数据（真 pg_catalog / pg_policies，不是代码常量）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
    void policy_metadata_shows_enable_force_and_nullif_on_both_sides() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, TABLE),
                TABLE + " 必须 ENABLE ROW LEVEL SECURITY");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, TABLE),
                TABLE + " 必须 FORCE ROW LEVEL SECURITY（防 owner 绕过）");

        String owner = jdbc.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = ?", String.class, TABLE);
        assertEquals(RlsGateSupport.APP_USER, owner,
                TABLE + " 的 owner 应为非超级用户（否则 FORCE RLS 未被真实验证）；实际=" + owner);

        List<String> quals = jdbc.queryForList(
                "SELECT qual FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                String.class, TABLE);
        List<String> checks = jdbc.queryForList(
                "SELECT with_check FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                String.class, TABLE);
        assertEquals(1, quals.size(), TABLE + " 必须恰好存在一个 tenant_isolation 策略");
        assertTrue(quals.get(0) != null && quals.get(0).contains("NULLIF"),
                TABLE + " 的 USING 必须含 NULLIF（fail-closed 归一），实际: " + quals.get(0));
        assertTrue(checks.get(0) != null && checks.get(0).contains("NULLIF"),
                TABLE + " 的 WITH CHECK 必须显式含 NULLIF（写入侧校验），实际: " + checks.get(0));

        Integer leak = inPlainTx(() ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class));
        assertEquals(0, leak, TABLE + " 未设上下文读到 " + leak + " 行：策略退化为 allow-all（USING(true)）");
    }

    // ------------------------------------------------------------------
    // 5) 版本化不可覆盖：业务唯一键（含 version）必须成立
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 同 (租户/年龄组/维度/题序/版本) 重复插入被唯一键拒绝（23505）")
    void duplicate_business_key_is_rejected_so_items_cannot_be_silently_overwritten() {
        String first = "00000000-0000-0000-0000-00000000e001";
        inTenantTx(TENANT_A, () -> {
            insertMinimal(first, TENANT_A, "女29-35", "睡眠质量", 2);
            return null;
        });

        // 同一业务键、不同主键 → 必须被 uq_sib_business 挡住。
        // 🛑 语义是"该题该版本已存在"：按 P0-20「旧版本不可覆盖」，正确处置是改用新 version。
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    insertMinimal("00000000-0000-0000-0000-00000000e002",
                            TENANT_A, "女29-35", "睡眠质量", 2);
                    return null;
                }),
                "同业务键被允许重复插入 —— uq_sib_business 未生效，题目将被静默覆盖");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库");
        assertEquals("23505", se.getSQLState(),
                "应被唯一键以 23505(unique_violation) 拒绝，实际 SQLSTATE=" + se.getSQLState());

        // 换 version 后必须可插入（证明"改题 = 插新版本"这条路是通的，不是被一刀切死）
        inTenantTx(TENANT_A, () -> {
            insertMinimalWithVersion("00000000-0000-0000-0000-00000000e003",
                    TENANT_A, "女29-35", "睡眠质量", 2, "v2");
            return null;
        });
        Integer bothVersions = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE
                                + " WHERE tenant_id = ?::uuid AND age_group = '女29-35'"
                                + " AND dimension = '睡眠质量' AND item_no = 2 AND version IN ('v1','v2')",
                        Integer.class, TENANT_A));
        assertEquals(2, bothVersions,
                "两个版本应并存（版本化不可覆盖 = 新旧并存，不是覆盖），实际可见 " + bothVersions + " 行");
    }

    // ------------------------------------------------------------------
    // 6) item_direction 仅 symptom（正向题不得计分）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: item_direction 非 symptom 被 CHECK 拒绝（23514）")
    void non_symptom_item_direction_is_rejected_by_check() {
        for (String bad : List.of("positive", "POSITIVE", "正向", "symptom ")) {
            DataAccessException caught = assertThrows(DataAccessException.class,
                    () -> inTenantTx(TENANT_A, () -> {
                        jdbc.update("INSERT INTO " + TABLE
                                        + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                                        + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                                        + "  item_direction, version, reviewer_id, reviewed_at) "
                                        + "VALUES (gen_random_uuid(), ?::uuid, '男33-40', '记忆专注', 3,"
                                        + "  'probe', 'a0','a1','a2','a3','a4', ?, 'v1', 'reviewer', now())",
                                TENANT_A, bad);
                        return null;
                    }),
                    "非 symptom 的 item_direction 被接受了: " + bad
                            + " —— 正向表述题会把『症状减轻』算成『分数上升』");
            SQLException se = rootSqlException(caught);
            assertNotNull(se, "被拒原因应来自数据库");
            assertEquals("23514", se.getSQLState(),
                    "应被 CHECK 以 23514 拒绝，实际 SQLSTATE=" + se.getSQLState());
        }

        // 省略 item_direction 时 DEFAULT 'symptom' 必须可用（DEFAULT 与 CHECK 自洽）
        inTenantTx(TENANT_A, () -> {
            jdbc.update("INSERT INTO " + TABLE
                            + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                            + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                            + "  version, reviewer_id, reviewed_at) "
                            + "VALUES (gen_random_uuid(), ?::uuid, '男33-40', '记忆专注', 4,"
                            + "  'default-direction probe', 'a0','a1','a2','a3','a4', 'v1', 'reviewer', now())",
                    TENANT_A);
            return null;
        });
        String dflt = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT item_direction FROM " + TABLE
                                + " WHERE tenant_id = ?::uuid AND item_text = 'default-direction probe'",
                        String.class, TENANT_A));
        assertEquals("symptom", dflt, "省略 item_direction 时默认值应为 symptom（DEFAULT 与 CHECK 不自洽）");
    }

    // ------------------------------------------------------------------
    // 7) 224 题容量：从真库约束正文推导（不是抄 8/7/4）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 容量 = 8 年龄组 × 7 维度 × 4 题序 = 224（从 CHECK 正文推导）")
    void capacity_equals_two_hundred_twenty_four_derived_from_the_real_check_definitions() {
        int ageGroups = literalCountInCheck("scale_item_bank_age_group_check");
        int dimensions = literalCountInCheck("scale_item_bank_dimension_check");
        assertEquals(8, ageGroups,
                "age_group CHECK 的取值字面量应为 8 个（男 4 组 + 女 4 组），实际 " + ageGroups
                        + " —— 约束被收窄或加宽都会让 224 容量失真");
        assertEquals(7, dimensions,
                "dimension CHECK 的取值字面量应为 7 个（7 维枚举），实际 " + dimensions);

        // item_no 域 = 1..4（4 个值）—— 用生成器实测，不抄字面
        Integer itemNoValues = jdbc.queryForObject(
                "SELECT count(*) FROM generate_series(1, 4) g", Integer.class);
        assertEquals(4, itemNoValues, "item_no 域应为 1..4（4 个值）");

        assertEquals(224, ageGroups * dimensions * itemNoValues,
                "结构容量必须恰好 224 题（PRD 附录 C.1 / 开发清单 §一④），不得多也不得少");

        // 结构自洽：维度满分 16 = 4 题 × 0–4；总分 112 = 7 维 × 16。
        // 这两条与 config #35 的声明值一致，但此处只证明"表结构足以承载"，
        // 分值口径的权威源仍是 config（由 ScaleScoringProfile/Engine 的测试守着）。
        assertEquals(112, dimensions * 4 * 4, "7 维 × 4 题 × 单题上限 4 = 112（PRD 附录 C.1.5）");
    }

    @Test
    @DisplayName("scale_item_bank: item_no 域边界行为 —— 1..4 接受，0 与 5 被拒（23514）")
    void item_no_domain_boundaries_are_enforced_behaviourally() {
        inTenantTx(TENANT_A, () -> {
            for (int no : new int[]{1, 2, 3, 4}) {
                try {
                    jdbc.update("INSERT INTO " + TABLE
                                    + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                                    + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                                    + "  version, reviewer_id, reviewed_at) "
                                    + "VALUES (gen_random_uuid(), ?::uuid, '男41-48', '代谢体态消化', ?,"
                                    + "  'boundary probe', 'a0','a1','a2','a3','a4', 'v1', 'reviewer', now())",
                            TENANT_A, no);
                } catch (DataAccessException e) {
                    SQLException s = rootSqlException(e);
                    fail("合法 item_no " + no + " 被拒 —— 域被收窄。 SQLSTATE="
                            + (s == null ? "?" : s.getSQLState()), e);
                }
            }
            return null;
        });

        for (int bad : new int[]{0, 5, -1}) {
            DataAccessException caught = assertThrows(DataAccessException.class,
                    () -> inTenantTx(TENANT_A, () -> {
                        jdbc.update("INSERT INTO " + TABLE
                                        + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                                        + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                                        + "  version, reviewer_id, reviewed_at) "
                                        + "VALUES (gen_random_uuid(), ?::uuid, '男49以上', '情绪抗压与抵抗力', ?,"
                                        + "  'boundary probe bad', 'a0','a1','a2','a3','a4', 'v1', 'reviewer', now())",
                                TENANT_A, bad);
                        return null;
                    }),
                    "越界 item_no " + bad + " 被接受了 —— 1..4 的域未生效");
            SQLException se = rootSqlException(caught);
            assertNotNull(se, "被拒原因应来自数据库");
            assertEquals("23514", se.getSQLState(),
                    "越界 item_no 应被 CHECK 以 23514 拒绝，实际 SQLSTATE=" + se.getSQLState());
        }
    }

    // ------------------------------------------------------------------
    // 8) 年龄组 / 维度枚举：非法值被拒，合法值全覆盖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 非法 age_group / dimension 被 23514 拒绝，8×7 合法组合全通")
    void illegal_age_group_or_dimension_is_rejected_while_all_legal_combinations_pass() {
        List<String> badAges = List.of("男16-33", "女50以上", "16-32", "男");
        for (int i = 0; i < badAges.size(); i++) {
            String badAge = badAges.get(i);
            // 🛑 不能用 badAge.hashCode() 拼主键：中文串的 hashCode 可能是【负数】
            //    （例如 "女50以上" = -324857965），拼出来是 "00000000-0000-0000-0000-00000000f000-324857965"
            //    —— 非法 UUID ⇒ 抛 22P02(invalid_text_representation) 而不是本条要断言的 23514。
            //    那样断言会红，而红的原因与"CHECK 是否生效"毫无关系。故用序号 + 固定长度 hex。
            String itemId = String.format("00000000-0000-0000-0000-00000000f%03x", i);
            // 前置自证：拼出来的主键必须是合法 UUID 形状，否则下面测的是 UUID 解析而不是 CHECK
            assertTrue(itemId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                    "探针主键形状非法: " + itemId);
            String badAgeFinal = badAge;
            DataAccessException caught = assertThrows(DataAccessException.class,
                    () -> inTenantTx(TENANT_A, () -> {
                        insertMinimalWithVersion(itemId,
                                TENANT_A, badAgeFinal, "体能精力", 1, "bad-age");
                        return null;
                    }),
                    "非法 age_group 被接受: " + badAge);
            SQLException se = rootSqlException(caught);
            assertNotNull(se, "被拒原因应来自数据库");
            assertEquals("23514", se.getSQLState(),
                    "非法 age_group 应被 23514 拒绝，实际 SQLSTATE=" + se.getSQLState()
                            + "（若是 22P02，说明探针主键本身非法，与本断言无关）");
        }

        DataAccessException caughtDim = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    insertMinimalWithVersion("00000000-0000-0000-0000-00000000f1ff",
                            TENANT_A, "男16-32", "不存在的维度", 1, "bad-dim");
                    return null;
                }),
                "非法 dimension 被接受");
        SQLException seDim = rootSqlException(caughtDim);
        assertNotNull(seDim, "被拒原因应来自数据库");
        assertEquals("23514", seDim.getSQLState(), "非法 dimension 应被 23514 拒绝");

        // 合法 8×7 组合逐一通过（在 version 维度上错开，避免撞唯一键）
        List<String> ages = List.of("男16-32", "男33-40", "男41-48", "男49以上",
                "女14-28", "女29-35", "女36-42", "女43-49以上");
        List<String> dims = List.of("体能精力", "面部气色肤质", "肩颈腰背筋骨", "睡眠质量",
                "记忆专注", "代谢体态消化", "情绪抗压与抵抗力");
        assertEquals(8, ages.size(), "断言自身必须恰列 8 个年龄组");
        assertEquals(7, dims.size(), "断言自身必须恰列 7 个维度");

        inTenantTx(TENANT_A, () -> {
            int i = 0;
            for (String age : ages) {
                for (String dim : dims) {
                    try {
                        insertMinimalWithVersion("00000000-0000-0000-0000-0000000f" + String.format("%04d", i++),
                                TENANT_A, age, dim, 1, "enum-probe");
                    } catch (DataAccessException e) {
                        SQLException s = rootSqlException(e);
                        fail("合法组合 " + age + " / " + dim + " 被拒。 SQLSTATE="
                                + (s == null ? "?" : s.getSQLState()), e);
                    }
                }
            }
            return null;
        });

        Integer probeRows = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM " + TABLE
                        + " WHERE tenant_id = ?::uuid AND version = 'enum-probe'", Integer.class, TENANT_A));
        assertEquals(56, probeRows, "8 × 7 = 56 个合法组合应全部落库，实际 " + probeRows);
    }

    // ------------------------------------------------------------------
    // 9) 结构边界：题库不得退化成"客户级作答记录"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scale_item_bank: 不带 customer_id / store_id（题库≠作答记录，两本台账不得合并）")
    void scale_item_bank_must_not_carry_customer_or_store_scope_columns() {
        for (String forbidden : List.of("customer_id", "store_id")) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='" + TABLE
                            + "' AND column_name='" + forbidden + "'",
                    Integer.class);
            assertEquals(0, n, TABLE + " 出现了 " + forbidden
                    + " —— 题库是总部维护的内容资产，不是客户级作答记录（那属 scale 表，两本台账不得合并）");
        }

        String pk = jdbc.queryForObject(
                "SELECT string_agg(a.attname, ',' ORDER BY a.attname) "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN unnest(c.conkey) k ON true "
                        + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k "
                        + "WHERE t.relname = ? AND c.contype = 'p'", String.class, TABLE);
        assertEquals("item_id", pk, TABLE + " 主键应为 item_id，实际=" + pk);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 从真库里读某条 CHECK 约束的正文，数其中被单引号包住的字面量个数。
     *
     * <p>它把"取值集基数"从<b>数据库事实</b>导出，而不是读 Java 常量 ——
     * 这正是本类不做自证的关键一步。
     */
    private static int literalCountInCheck(String constraintName) {
        String def = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = ?::regclass AND conname = ?",
                String.class, TABLE, constraintName);
        assertNotNull(def, "真库中找不到 CHECK 约束: " + constraintName
                + "（约束被改名/删除会让容量推导失去依据）");
        Matcher m = Pattern.compile("'([^']*)'").matcher(def);
        List<String> literals = new ArrayList<>();
        while (m.find()) {
            literals.add(m.group(1));
        }
        return literals.size();
    }

    /** 插一行最小合法题（仅用于隔离/约束探针，version 固定 v1）。 */
    private static void insertMinimal(String itemId, String tenant, String ageGroup,
                                      String dimension, int itemNo) {
        insertMinimalWithVersion(itemId, tenant, ageGroup, dimension, itemNo, "v1");
    }

    /** 插一行最小合法题，指定 version。 */
    private static void insertMinimalWithVersion(String itemId, String tenant, String ageGroup,
                                                 String dimension, int itemNo, String version) {
        jdbc.update("INSERT INTO " + TABLE
                        + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                        + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                        + "  item_direction, version, reviewer_id, reviewed_at) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, 'a0','a1','a2','a3','a4',"
                        + "  'symptom', ?, 'reviewer:probe', TIMESTAMPTZ '2026-09-23 00:00:00+08')",
                itemId, tenant, ageGroup, dimension, itemNo, "probe " + ageGroup + "/" + dimension, version);
    }

    /**
     * 用超级用户写入两租户各 1 行底数据。
     *
     * <p>幂等：<b>先按主键删除再插入</b>。若只靠 {@code ON CONFLICT (主键)} 兜底，
     * 上一次运行残留的、主键不同但撞 {@code uq_sib_business}（同 租户/年龄组/维度/题序/版本）
     * 的行会以 23505 让 seed 阶段报红 —— 看起来像业务 bug，实为 seed 不够干净。
     */
    private static void seedBaseRows() {
        // 先做【该两租户全量清理】再插种子：使本类可重复运行。
        // 理由见 releaseBuildMutexAndCleanup 的注释（只按主键删两行会让探针残留撞唯一键）。
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "DELETE FROM " + TABLE + " WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')");

        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO " + TABLE
                        + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                        + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                        + "  item_direction, version, reviewer_id, reviewed_at) "
                        + "VALUES ('" + SEED_SIB_A + "', '" + TENANT_A + "', '男16-32', '体能精力', 1,"
                        + "  'seed item A', 'a0','a1','a2','a3','a4', 'symptom', 'v1',"
                        + "  'reviewer:seed', TIMESTAMPTZ '2026-09-23 00:00:00+08')");
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(), RlsGateSupport.DB,
                "INSERT INTO " + TABLE
                        + " (item_id, tenant_id, age_group, dimension, item_no, item_text,"
                        + "  anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                        + "  item_direction, version, reviewer_id, reviewed_at) "
                        + "VALUES ('" + SEED_SIB_B + "', '" + TENANT_B + "', '女14-28', '面部气色肤质', 2,"
                        + "  'seed item B', 'a0','a1','a2','a3','a4', 'symptom', 'v1',"
                        + "  'reviewer:seed', TIMESTAMPTZ '2026-09-23 00:00:00+08')");

        // 🛑 后置自证：两个租户的底数据都必须【真的在里面】。
        //    不写这一段时，种子失败会以一个完全无关的症状暴露（例如某个用例报
        //    "EmptyResultDataAccess / expected 1 actual 0"），排查方向被引到 RLS 策略上；
        //    而真实原因可能是 SQL 根本没执行（本项目实测到过：中文 SQL 走 `psql -c`
        //    在 GBK 控制台下被按 GBK 送出，PG 报编码错并整条拒绝）。
        //    故此处直接向超级用户核对行数，让"种子没进去"以它本来的样子失败。
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            String n = RlsGateSupport.queryScalar(RlsGateSupport.SUPER_USER,
                    RlsGateSupport.superPassword(), RlsGateSupport.DB,
                    "SELECT count(*) FROM " + TABLE + " WHERE tenant_id = '" + tenant + "'");
            assertEquals(String.valueOf(BASE_ROWS_PER_TENANT), n,
                    "底数据未落库：租户 " + tenant + " 期望 " + BASE_ROWS_PER_TENANT + " 行，实际 " + n
                            + " 行 —— 失败发生在 seedBaseRows（可能是 SQL 未执行/被编码拒），"
                            + "不是 RLS 策略问题。请先看本类 @BeforeAll 打印的 provision 日志。");
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