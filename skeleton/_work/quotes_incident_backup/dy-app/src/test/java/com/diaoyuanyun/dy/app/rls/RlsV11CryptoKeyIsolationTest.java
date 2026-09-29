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
 * RlsV11CryptoKeyIsolationTest: V11 迁移落库的<b>三张密钥材料表</b>的
 * 【真实 PostgreSQL 隔离门禁 + 只追加纪律门禁】。
 *
 * <pre>
 *   tenant_kek            租户级 KEK（被 DY_MASTER_KEY 包裹的材料）
 *   subject_dek           每主体 DEK（被租户 KEK 包裹的材料）
 *   subject_key_tombstone 密钥销毁墓碑（删除权的不可绕过证据）
 * </pre>
 *
 * <h2>🛑 为什么密钥表<b>比业务表更</b>需要这一套断言（而不是"又三张普通表"）</h2>
 * <ol>
 *   <li><b>泄漏的性质不同</b>：业务表越权读到的是一条记录；密钥表越权读到的
 *       <b>是密钥材料</b>。拿到别租户的 {@code wrapped_bytes} 不一定能立刻解密
 *       （还需该租户的 KEK 与 masterKey），但它把攻击面从"一条数据"变成
 *       "整个租户的密钥链"。故 V11 在注释里逐字写明"密钥表比业务表更需要 RLS"，
 *       本类就是那句话的机械证明。</li>
 *   <li><b>三张表各自有一条"只追加 / 不可改"的纪律，且实现手段不同</b>：
 *       墓碑表用 RULE {@code DO INSTEAD NOTHING}（UPDATE / DELETE 双双静默无效），
 *       {@code subject_dek} 只禁 UPDATE，{@code tenant_kek} 用<b>列级触发器</b>
 *       只拦密钥材料字段而<b>放行</b> {@code destroyed_at}。
 *       "静默无效"与"抛异常"是两种完全不同的可观测行为 ——
 *       若只断言其一，另一条纪律在将来被误改时不会有任何信号。</li>
 *   <li><b>墓碑表是<b>删除免疫</b>的，故本类的清理策略与所有邻类都不同</b>
 *       （见下方"清理"一节）。这不是疏忽，而恰是被测语义本身。</li>
 * </ol>
 *
 * <h2>为什么必须补这一个类（而不是并进邻类）</h2>
 * {@link RlsV5EntityIsolationTest} / {@link RlsV6RefundLedgerIsolationTest} /
 * {@link RlsV7IntakeProfileRevisionIsolationTest} 各自在内部断言自己的表数（24 / 3 / 1），
 * 把 V11 的 3 张塞进去会让它们的名字开始说谎。
 * 而 {@link RlsBEntityIsolationTest} 的名字写着"B 类实体"（客户级业务实体），
 * 密钥材料既不是客户级也不是业务实体。
 *
 * <p>按 ADR-02 第 3 层「租户表无隔离测试即构建失败」，三张表必须同时：
 * ① 登记进 {@link RlsCoverageGateTest#ISOLATION_TESTS}；
 * ② <b>登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution 的
 * {@code <include>}</b> —— 不登记 include 会「存在但不执行」，登记表就成了一纸空文；
 * ③ 有真实数据库的读写隔离断言（本类）。
 *
 * <h2>🛑 清理策略：为什么本类<b>不</b>清理墓碑表</h2>
 * 邻类的 {@code @AfterAll} 都用 DELETE 精确清掉自己灌的行。本类<b>不能</b>照抄：
 * V11 给墓碑表建了 {@code tombstone_no_delete} 规则
 * （{@code ON DELETE ... DO INSTEAD NOTHING}），故对它的 DELETE 是<b>静默无效</b>的 ——
 * 写了也是一行不删。这恰好是等保 2.0 三级「审计类记录不可篡改、不可删除」的落地形态。
 *
 * <p>于是本类的策略是：墓碑行用<b>固定主键 + {@code ON CONFLICT DO NOTHING}</b> 灌入，
 * 重跑天然幂等（首销毁时间不被覆盖）；{@code tenant_kek} / {@code subject_dek} 按固定主键
 * DELETE 清理（它们没有被禁删除 —— 删除正是 crypto-shredding 的核心动作）。
 * 若有人"顺手"给墓碑表也加上 DELETE 清理，他会发现删除无效而测试仍绿 ——
 * 那正是本类把这条语义写成断言（{@link #tombstone_rules_make_update_and_delete_silent_noops()}）的原因。
 *
 * <h2>探针写入为什么一律走【显式回滚】的事务</h2>
 * 与 {@link RlsV6RefundLedgerIsolationTest} 同源，两条独立理由：
 * ① 底数据前提被污染（本类的元数据断言依赖"每租户恰若干行"这一精确前提）；
 * ② 重跑即失败（固定主键的探针行残留会撞 23505，报错看起来像"密钥表约束有问题"）。
 * 故：<b>期望被拒</b>的写入走普通事务（PG 让语句失败即中止事务，异常上抛 → 自动回滚）；
 * <b>期望被接受</b>的写入走 {@link #inRollbackTx}（显式 {@code setRollbackOnly}）。
 *
 * <h2>与邻类的隔离（避免把别人灌的数据当自己的证据）</h2>
 * 主键后缀取 {@code 0007}/{@code 0008}（A/B 租户），与
 * V7（{@code 0005}/{@code 0006}）、V6（{@code 0003}/{@code 0004}）、
 * V5（{@code 0001}/{@code 0002}）、B 类（{@code 0000}）、基础种子（{@code c1111111…}）
 * <b>零交集</b>。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户总是绕过 RLS；用它跑隔离断言会全部"假通过"。
 * {@code @BeforeAll} 第二件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsV11CryptoKeyIsolationTest {

    /**
     * V11 覆盖的 3 张表。
     *
     * <p>⚠️ 与 {@link RlsCoverageGateTest#ISOLATION_TESTS} 的登记值必须指向本类；
     * 同时本类必须登记在 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>} 里 —— 不登记 include 会「存在但不执行」。
     */
    private static final List<String> TABLES =
            List.of("tenant_kek", "subject_dek", "subject_key_tombstone");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;

    /**
     * 本类底数据主键的 UUID 第 4 段后缀（A = 0007 / B = 0008）。
     *
     * <p>{@code tenant_kek.kek_id} 与 {@code subject_dek.subject_id} 虽非 UUID，
     * 但本类把它们<b>构造成含同一后缀的字符串</b>，使 {@code LIKE '%0007%'} 能精确圈定
     * 本类灌的行（与邻类的 UUID 前缀法同一思路，只是载体不同）。
     */
    private static final String SFX_A = "0007";
    private static final String SFX_B = "0008";

    /** 每表每租户的底数据行数下界（"反证表非空"的期望下限）。 */
    private static final int BASE_ROWS_PER_TENANT = 1;

    /** 一个格式合法、内容无意义的包裹 nonce（本类被测对象是 RLS 与只追加纪律，不是密码学）。 */
    private static final String NONCE_LITERAL = "'\\x00112233445566778899aabb'::bytea";

    /** 一个格式合法、内容无意义的包裹密文。 */
    private static final String SEALED_LITERAL = "'\\xdeadbeefcafe0011223344556677889900aabbcc'::bytea";

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

        // ① 用被测交付物的【真实迁移链】（V1+…+V11）建库 + 建非超级用户角色 + 基础种子。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V11-GATE] provision:\n" + provisionLog);

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

        // ③ 自建底数据（三张表在两个租户下各 1 行）
        provisionSeeds();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            // 只清 tenant_kek / subject_dek。
            // 🛑 墓碑表【不清理】—— V11 的 tombstone_no_delete 规则让 DELETE 静默无效，
            //    这不是本类的疏忽，而是被测语义（等保 2.0 三级：审计记录不可删除）。
            //    故墓碑用固定主键 + ON CONFLICT DO NOTHING 灌入，重跑天然幂等。
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
    @DisplayName("V11 密钥表: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
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
            assertEquals(0, crossA,
                    table + ": 租户B 读到了租户A 的 " + crossA + " 行 —— 串租户！"
                            + "密钥表串租户的后果不是『多看见一条记录』，而是密钥材料泄漏");

            // 阶段 3: 对称方向
            Integer crossB = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_B));
            assertEquals(0, crossB, table + ": 租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
        }
    }

    @Test
    @DisplayName("V11 密钥表: 未设上下文 / 空串上下文 → 零行（fail-closed，NULLIF 归一）")
    void without_or_with_empty_context_returns_zero_rows() {
        for (String table : TABLES) {
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak,
                    table + ": 未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，全表泄漏！");

            Integer n = inPlainTx(() -> {
                jdbc.execute("SET LOCAL app.tenant_id = ''");
                try {
                    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
                } catch (DataAccessException e) {
                    SQLException se = rootSqlException(e);
                    fail(table + ": 空串上下文导致 SQL 异常（应为零行）—— NULLIF 归一失效，"
                            + "线上表现为 500。 SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
                    return null;
                }
            });
            assertNotNull(n, table + ": 必须返回零行计数而不是异常");
            assertEquals(0, n, table + ": 空串上下文读到 " + n + " 行，应为 0 行");
        }
    }

    // ------------------------------------------------------------------
    // 断言 2: 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V11 密钥表: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // tenant_kek —— 探针行除 tenant_id 外每个维度都合法，故 42501 的归因是干净的
        assertCrossTenantInsertRejected("tenant_kek",
                "INSERT INTO tenant_kek (tenant_id, kek_id, kek_seq, algorithm_id,"
                        + " wrap_nonce, wrapped_bytes, aad_text) "
                        + "VALUES (?::uuid, ?, 41, 'AES-256-GCM', " + NONCE_LITERAL + ", "
                        + SEALED_LITERAL + ", 'kek-wrap-v1|probe')",
                TENANT_B, "kek-probe-cross-0008");

        // subject_dek
        assertCrossTenantInsertRejected("subject_dek",
                "INSERT INTO subject_dek (tenant_id, subject_type, subject_id, dek_version,"
                        + " kek_id, algorithm_id, wrap_nonce, wrapped_bytes, aad_text) "
                        + "VALUES (?::uuid, 'customer', 'probe-cross-0008', 1, 'kek-x', 'AES-256-GCM', "
                        + NONCE_LITERAL + ", " + SEALED_LITERAL + ", 'aad-probe')",
                TENANT_B);

        // subject_key_tombstone
        assertCrossTenantInsertRejected("subject_key_tombstone",
                "INSERT INTO subject_key_tombstone (tenant_id, subject_type, subject_id,"
                        + " destroyed_at, reason) "
                        + "VALUES (?::uuid, 'customer', 'probe-cross-tomb-0008', now(), 'probe')",
                TENANT_B);
    }

    @Test
    @DisplayName("V11 密钥表: 越权行确认未落库（在对方租户上下文下 count = 0）")
    void cross_tenant_insert_leaves_no_row_behind() {
        // ⚠️ 必须在租户B 上下文内 count：三表 FORCE RLS，无上下文一律零行，
        //    "无上下文 count = 0" 会因策略挡行而假绿，与被拒本身无关。
        Integer nKek = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM tenant_kek WHERE kek_id = ?",
                        Integer.class, "kek-probe-cross-0008"));
        assertEquals(0, nKek, "越权 tenant_kek 行竟然落库了 " + nKek + " 行");

        Integer nDek = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM subject_dek WHERE subject_id = ?",
                        Integer.class, "probe-cross-0008"));
        assertEquals(0, nDek, "越权 subject_dek 行竟然落库了 " + nDek + " 行");

        Integer nTomb = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM subject_key_tombstone WHERE subject_id = ?",
                        Integer.class, "probe-cross-tomb-0008"));
        assertEquals(0, nTomb, "越权墓碑行竟然落库了 " + nTomb + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 3: 策略元数据（真实 pg_catalog / pg_policies）+ 表结构要素
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V11 密钥表: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
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

            // tenant_id 列必须存在 —— RLS 策略依赖它；缺列会让策略报错或退化为恒真
            Integer tidCols = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name=? AND column_name='tenant_id'",
                    Integer.class, table);
            assertEquals(1, tidCols, table + " 缺 tenant_id 列 —— RLS 策略失去依赖");
        }

        // 覆盖数自证（防"表名打错所以查不到策略"的假绿）
        assertEquals(3, TABLES.size(), "本类声明的表数必须恰为 3（V11 交付范围）");
    }

    // ------------------------------------------------------------------
    // 断言 4: 本域特有的"只追加 / 不可改"纪律（三种手段逐一生效）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V11 墓碑表: UPDATE 与 DELETE 都是【静默无效】（RULE DO INSTEAD NOTHING）")
    void tombstone_rules_make_update_and_delete_silent_noops() {
        String subject = seedTombstoneSubjectId(TENANT_A, SFX_A);
        assertNotNull(subject, "前置: 租户A 应有至少 1 行墓碑底数据");

        // ① 规则必须真实存在（这是"不可改不可删"的机械载体）
        List<String> rules = jdbc.queryForList(
                "SELECT rulename FROM pg_rules WHERE tablename = 'subject_key_tombstone' "
                        + "AND rulename IN ('tombstone_no_update','tombstone_no_delete') ORDER BY rulename",
                String.class);
        assertEquals(List.of("tombstone_no_delete", "tombstone_no_update"), rules,
                "墓碑表必须恰有两条只追加规则；实际=" + rules);

        // ② UPDATE 静默无效：受影响行数为 0，且原值【逐字未变】。
        //    🛑 只断言"没抛异常"是不够的 —— 一个把规则写错的迁移也能"不抛异常"，
        //       区别在于那一行的 reason 到底有没有被改掉。
        inRollbackTx(TENANT_A, () -> {
            String original = jdbc.queryForObject(
                    "SELECT reason FROM subject_key_tombstone WHERE tenant_id = ?::uuid AND subject_id = ?",
                    String.class, TENANT_A, subject);
            int affected = jdbc.update(
                    "UPDATE subject_key_tombstone SET reason = 'tampered' "
                            + "WHERE tenant_id = ?::uuid AND subject_id = ?", TENANT_A, subject);
            assertEquals(0, affected,
                    "墓碑表的 UPDATE 竟然影响了 " + affected + " 行 —— 只追加规则未生效");
            String after = jdbc.queryForObject(
                    "SELECT reason FROM subject_key_tombstone WHERE tenant_id = ?::uuid AND subject_id = ?",
                    String.class, TENANT_A, subject);
            assertEquals(original, after, "墓碑行的 reason 被改写成 " + after + " —— 审计记录被篡改");
            return null;
        });

        // ③ DELETE 静默无效：受影响行数为 0，且该行【仍在】。
        //    🛑 这一条与②同等重要：若 DELETE 生效，删除权就有了绕行路径 ——
        //       删掉墓碑后，任何人拿到备份 wrappedDek + KEK 都能自行还原 DEK。
        inRollbackTx(TENANT_A, () -> {
            int affected = jdbc.update(
                    "DELETE FROM subject_key_tombstone WHERE tenant_id = ?::uuid AND subject_id = ?",
                    TENANT_A, subject);
            assertEquals(0, affected,
                    "墓碑表的 DELETE 竟然删除了 " + affected + " 行 —— 删除权的绕行路径被打开了");

            Integer still = jdbc.queryForObject(
                    "SELECT count(*) FROM subject_key_tombstone WHERE tenant_id = ?::uuid AND subject_id = ?",
                    Integer.class, TENANT_A, subject);
            assertEquals(1, still, "墓碑行被 DELETE 掉了 —— 只追加纪律被破坏");
            return null;
        });
    }

    @Test
    @DisplayName("V11 subject_dek: UPDATE 静默无效（规则），但 DELETE 必须可用（crypto-shredding 的核心动作）")
    void subject_dek_is_immutable_but_still_deletable() {
        String subject = seedDekSubjectId(TENANT_A, SFX_A);
        assertNotNull(subject, "前置: 租户A 应有至少 1 行 subject_dek 底数据");

        // ① UPDATE 静默无效：改 wrapped material 等于把历史密文与密钥错配，
        //    而错误会以"认证失败"出现，看起来像数据被篡改而不是被误改。
        inRollbackTx(TENANT_A, () -> {
            String original = jdbc.queryForObject(
                    "SELECT encode(wrapped_bytes,'hex') FROM subject_dek "
                            + "WHERE tenant_id = ?::uuid AND subject_id = ? AND dek_version = 1",
                    String.class, TENANT_A, subject);
            int affected = jdbc.update(
                    "UPDATE subject_dek SET wrapped_bytes = '\\x00'::bytea "
                            + "WHERE tenant_id = ?::uuid AND subject_id = ? AND dek_version = 1",
                    TENANT_A, subject);
            assertEquals(0, affected,
                    "subject_dek 的 UPDATE 竟然影响了 " + affected + " 行 —— 不可更新规则未生效");
            String after = jdbc.queryForObject(
                    "SELECT encode(wrapped_bytes,'hex') FROM subject_dek "
                            + "WHERE tenant_id = ?::uuid AND subject_id = ? AND dek_version = 1",
                    String.class, TENANT_A, subject);
            assertEquals(original, after, "持有的密钥材料被就地改写了 —— 历史密文将永远解不开");
            return null;
        });

        // ② DELETE 必须可用 —— 这是与墓碑表<b>刻意不同</b>的一条。
        //    销毁主体密钥（crypto-shredding）就是 DELETE subject_dek 的动作；
        //    若它也变成静默无效，删除权就失去了执行手段（墓碑记住"删过"，
        //    但材料还在库里，备份仍可还原）。
        inRollbackTx(TENANT_A, () -> {
            String probeSubject = "probe-deletable-" + SFX_A;
            jdbc.update("INSERT INTO subject_dek (tenant_id, subject_type, subject_id, dek_version,"
                            + " kek_id, algorithm_id, wrap_nonce, wrapped_bytes, aad_text) "
                            + "VALUES (?::uuid, 'customer', ?, 1, 'kek-probe', 'AES-256-GCM', "
                            + NONCE_LITERAL + ", " + SEALED_LITERAL + ", 'aad-probe')",
                    TENANT_A, probeSubject);
            int deleted = jdbc.update("DELETE FROM subject_dek "
                    + "WHERE tenant_id = ?::uuid AND subject_id = ?", TENANT_A, probeSubject);
            assertEquals(1, deleted,
                    "subject_dek 的 DELETE 被拦住了 —— crypto-shredding 失去了执行手段");
            return null;
        });
    }

    @Test
    @DisplayName("V11 tenant_kek: 密钥材料字段就地改 → 抛异常；但 destroyed_at 必须可写（租户级销毁要留痕）")
    void tenant_kek_material_is_trigger_protected_while_destroyed_at_is_writable() {
        String kekId = seedKekId(TENANT_A);
        assertNotNull(kekId, "前置: 租户A 应有至少 1 行 tenant_kek 底数据");

        // ① 就地改 wrapped_bytes → 触发器 RAISE（SQLSTATE P0001）。
        //    🛑 与墓碑/subject_dek 的"静默无效"刻意不同：KEK 轮换的正确做法是
        //       【新增一行】(kek_seq + 1)，就地改会让历史 wrappedDek 永久解不开 ——
        //       这种误操作必须【响亮地】失败，而不是被安静吞掉。
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("UPDATE tenant_kek SET wrapped_bytes = '\\x00'::bytea "
                            + "WHERE tenant_id = ?::uuid AND kek_id = ?", TENANT_A, kekId);
                    return null;
                }),
                "就地改写 tenant_kek 的密钥材料被接受了 —— 不可变触发器未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("P0001", se.getSQLState(),
                "应以触发器 RAISE(P0001) 拒绝，实际 SQLSTATE=" + se.getSQLState());
        assertTrue(se.getMessage() != null && se.getMessage().contains("不可修改"),
                "拒绝消息应说明『密钥材料字段不可修改』，实际: " + se.getMessage());

        // ② 同款：改 aad_text 也必须被拦（AAD 被改等于认证串漂移，后患同①）
        DataAccessException caughtAad = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("UPDATE tenant_kek SET aad_text = 'tampered' "
                            + "WHERE tenant_id = ?::uuid AND kek_id = ?", TENANT_A, kekId);
                    return null;
                }),
                "改写 tenant_kek 的 aad_text 被接受了 —— AAD 漂移会让历史密文报『解不开』");
        SQLException seAad = rootSqlException(caughtAad);
        assertNotNull(seAad, "被拒原因应来自数据库");
        assertEquals("P0001", seAad.getSQLState(),
                "aad_text 变更应被触发器拒绝，实际 SQLSTATE=" + seAad.getSQLState());

        // ③ 反证：destroyed_at 必须【可写】—— 租户级销毁要留痕。
        //    若触发器把整行都锁死，租户级删除权就没有落地位置。
        inRollbackTx(TENANT_A, () -> {
            int affected = jdbc.update("UPDATE tenant_kek SET destroyed_at = now() "
                    + "WHERE tenant_id = ?::uuid AND kek_id = ?", TENANT_A, kekId);
            assertEquals(1, affected,
                    "destroyed_at 不可写 —— 触发器过宽，租户级销毁无法留痕");
            return null;
        });
    }

    @Test
    @DisplayName("V11 密钥表: 三表的唯一键范围必须含 tenant_id（写错范围不会报错，只会让租户互相顶掉）")
    void unique_keys_are_scoped_to_the_tenant() {
        // ① tenant_kek 的 (tenant_id, kek_seq) 唯一约束必须是 UNIQUE 且覆盖 tenant_id。
        //    若只覆盖 kek_seq（全局唯一），第二个租户的"第 1 代 KEK"就永远建不出来 ——
        //    而报错看起来像"KEK 序号冲突"，与租户毫无关联。
        inTenantTx(TENANT_A, () -> {
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                            + "WHERE conrelid = 'tenant_kek'::regclass AND conname = 'uq_tenant_kek_seq'",
                    String.class);
            assertNotNull(def, "缺 uq_tenant_kek_seq —— KEK 轮换的序号唯一性没有机械保证");
            assertTrue(def.contains("UNIQUE"), "uq_tenant_kek_seq 必须是 UNIQUE。实际: " + def);
            assertTrue(def.contains("tenant_id"),
                    "uq_tenant_kek_seq 必须覆盖 tenant_id —— 否则不同租户的同序号 KEK 会互相顶掉。实际: " + def);
            return null;
        });

        // ② subject_dek 的主键必须是四元组，且含 tenant_id（per-subject 粒度的范围）。
        inTenantTx(TENANT_A, () -> {
            String pk = jdbc.queryForObject(
                    "SELECT string_agg(a.attname, ',' ORDER BY k.ord) "
                            + "FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                            + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                            + "WHERE t.relname = 'subject_dek' AND c.contype = 'p'",
                    String.class);
            assertEquals("tenant_id,subject_type,subject_id,dek_version", pk,
                    "subject_dek 主键必须是 (tenant_id, subject_type, subject_id, dek_version) 四元组 —— "
                            + "缺 tenant_id 会让不同租户的同名主体共用 DEK（跨租户误伤删除权）；"
                            + "缺 dek_version 会让轮换无处容纳历史版本。实际: " + pk);
            return null;
        });

        // ③ subject_key_tombstone 的主键必须是三元组（无版本 —— 墓碑记的是"这个主体被删过"，
        //    不是"某个版本的密钥被删过"；带版本会让重复销毁产生多条墓碑）。
        inTenantTx(TENANT_A, () -> {
            String pk = jdbc.queryForObject(
                    "SELECT string_agg(a.attname, ',' ORDER BY k.ord) "
                            + "FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                            + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                            + "WHERE t.relname = 'subject_key_tombstone' AND c.contype = 'p'",
                    String.class);
            assertEquals("tenant_id,subject_type,subject_id", pk,
                    "subject_key_tombstone 主键必须是 (tenant_id, subject_type, subject_id) 三元组。实际: " + pk);
            return null;
        });

        // ④ 主体类型取值集必须被 DB 层钉住（'customer' / 'therapist'），
        //    并与 dy-crypto SubjectRef 的两个工厂方法一致 —— 否则会出现
        //    "库里有一条 subject_type='user' 的 DEK，而代码永远找不到它"。
        //
        //    ⚠️ 约束名取自 V11 的实测定义（`ck_<表名>_subject_type`），
        //       而不是猜一个 `_subject_type_check` —— 后者会红，
        //       而那条失败信息看起来像"CHECK 不存在"，实际是断言打偏到了错名字上。
        //       故此处先断言"存在一条名为 ck_... 的 CHECK"，再断言它对应当前取值集。
        for (String table : List.of("subject_dek", "subject_key_tombstone")) {
            String checkName = "ck_" + table + "_subject_type";
            List<String> names = jdbc.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c'",
                    String.class, table);
            assertTrue(names.contains(checkName),
                    table + " 缺 " + checkName + " —— subject_type 会接受任意取值。实际: " + names);

            // 约束定义必须恰好钉住两个值（不是三个、也不是 LIKE 模糊匹配）
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                            + "WHERE conrelid = ?::regclass AND conname = ?",
                    String.class, table, checkName);
            assertTrue(def.contains("'customer'") && def.contains("'therapist'"),
                    checkName + " 必须钉住 ('customer','therapist') 两值 —— "
                            + "它必须与 dy-crypto SubjectRef 的两个工厂方法一致。实际: " + def);
        }

        // ⑤ 反向验证：非法 subject_type 确实被拒（证明上面那条 CHECK 有牙齿）
        assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO subject_dek (tenant_id, subject_type, subject_id,"
                                    + " dek_version, kek_id, algorithm_id, wrap_nonce, wrapped_bytes, aad_text) "
                                    + "VALUES (?::uuid, 'user', 'probe-bad-type', 1, 'kek-x', 'AES-256-GCM', "
                                    + NONCE_LITERAL + ", " + SEALED_LITERAL + ", 'aad')",
                            TENANT_A);
                    return null;
                }),
                "subject_type='user' 被接受了 —— 取值集 CHECK 未生效");

        // ⑥ 墓碑的 reason 不得为空白（删除权的证据必须说明理由）
        assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO subject_key_tombstone (tenant_id, subject_type, subject_id,"
                                    + " destroyed_at, reason) "
                                    + "VALUES (?::uuid, 'customer', 'probe-blank-reason', now(), '   ')",
                            TENANT_A);
                    return null;
                }),
                "空白的 reason 被接受了 —— 墓碑将无法说明删除依据");
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
                table + " 跨租户写入应被 RLS 以 42501 拒绝，实际 " + se.getSQLState()
                        + "（若为 23514/23502 说明探针本身不合法，归因不干净）");
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
     * <p>它承载"应当成功"的探针写入，是"探针不污染底数据、且重跑安全"的关键 ——
     * 否则固定主键的探针行第二次运行会撞 23505，报错看起来像"密钥表约束有问题"。
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
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, buildCleanupSql());
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_A, SFX_A));
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_B, SFX_B));
    }

    /**
     * 清理 SQL：按主键精确删除本类灌入的 {@code tenant_kek} / {@code subject_dek} 行。
     *
     * <p>🛑 <b>刻意不含墓碑表</b> —— V11 的 {@code tombstone_no_delete} 规则让 DELETE
     * 静默无效。墓碑用固定主键 + {@code ON CONFLICT DO NOTHING} 灌入，
     * 重跑幂等且首次销毁时间不被覆盖（那正是 V11 逐字要求的语义）。
     */
    private static String buildCleanupSql() {
        return "\\set ON_ERROR_STOP on\n"
                + "DELETE FROM subject_dek WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')"
                + " AND subject_id LIKE 'v11-" + SFX_A + "-%';\n"
                + "DELETE FROM subject_dek WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')"
                + " AND subject_id LIKE 'v11-" + SFX_B + "-%';\n"
                + "DELETE FROM tenant_kek WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')"
                + " AND (kek_id LIKE 'kek-v11-" + SFX_A + "-%' OR kek_id LIKE 'kek-v11-" + SFX_B + "-%');\n";
    }

    /**
     * 为某个租户灌底数据：三张表各 1 行。
     *
     * <p>PK 形态：{@code tenant_kek.kek_id = kek-v11-<sfx>-01}、
     * {@code subject_dek.subject_id = v11-<sfx>-subject}、
     * {@code subject_key_tombstone.subject_id = v11-<sfx>-subject}。
     * 两租户靠 {@code sfx} 区分，故 {@code ON CONFLICT DO NOTHING} 幂等重灌是安全的。
     *
     * <p>⚠️ {@code tenant_kek} 的 FK 指向 {@code tenant}，而两租户由基础种子
     * （{@code verification/02_seed.sql}）提供 —— 故不需要（也不应该）在此自建租户：
     * 自建租户会让 provision 的 {@code tenant=2} 后置校验报红。
     *
     * <h2>🛑🛑 为什么三张表的幂等写法【必须不同】（本类踩过的真实坑）</h2>
     * 底数据要"重跑幂等"，最自然的写法是三张表都用 {@code ON CONFLICT DO NOTHING}。
     * 但那样<b>两张表会整条语句失败</b>，PG 原话：
     * <pre>
     *   错误: 无法对具有INSERT或者UPDATE规则的表使用带有ON CONFLICT子句的INSERT
     * </pre>
     * 因为 V11 给墓碑表建了 {@code tombstone_no_update} 规则、给 {@code subject_dek}
     * 建了 {@code subject_dek_no_update} 规则，而 PG 对"有 INSERT/UPDATE 规则的表"
     * 一律拒绝 {@code ON CONFLICT} 子句（连 {@code DO NOTHING} 也不例外）。
     *
     * <p>故此处按表的规则状况分别对待：
     * <pre>
     *   tenant_kek            无 INSERT/UPDATE 规则  → ON CONFLICT 可用
     *   subject_dek           有 no_update 规则      → 必须 WHERE NOT EXISTS
     *   subject_key_tombstone 有 no_update 规则      → 必须 WHERE NOT EXISTS
     * </pre>
     * 这条差异不是本类的权宜之计 —— 它正是生产代码 {@code DbShredTombstoneStore.record}
     * 曾经踩到的同一个坑（那里曾用 {@code ON CONFLICT} 使删除权在库层不可执行）。
     * 把它写进测试的造数逻辑，是因为<b>造数失败会表现为"表里没有底数据"，
     * 而那种症状看起来像 RLS 策略退化为全拒</b>，排查方向会被整体带偏。
     */
    private static String seedSqlFor(String tenant, String sfx) {
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");

        // ① tenant_kek —— 无 INSERT/UPDATE 规则，ON CONFLICT 可用
        //    （它只有列级触发器，而触发器不影响 ON CONFLICT 的可用性）
        sb.append("INSERT INTO tenant_kek (tenant_id, kek_id, kek_seq, algorithm_id,"
                + " wrap_nonce, wrapped_bytes, aad_text, created_by) VALUES ("
                + q(tenant) + ", " + q("kek-v11-" + sfx + "-01") + ", 1, 'AES-256-GCM', "
                + NONCE_LITERAL + ", " + SEALED_LITERAL + ", "
                + q("kek-wrap-v1|" + tenant + "|kek-v11-" + sfx + "-01|1") + ", 'seed')"
                + " ON CONFLICT DO NOTHING;\n");

        // ② subject_dek —— 🛑 有 subject_dek_no_update 规则 ⇒ 不得用 ON CONFLICT
        sb.append("INSERT INTO subject_dek (tenant_id, subject_type, subject_id, dek_version,"
                + " kek_id, algorithm_id, wrap_nonce, wrapped_bytes, aad_text, created_by) "
                + "SELECT " + q(tenant) + ", 'customer', " + q("v11-" + sfx + "-subject") + ", 1, "
                + q("kek-v11-" + sfx + "-01") + ", 'AES-256-GCM', "
                + NONCE_LITERAL + ", " + SEALED_LITERAL + ", "
                + q("dek-wrap-v1|" + tenant + "|customer|v11-" + sfx + "-subject|1") + ", 'seed' "
                + "WHERE NOT EXISTS (SELECT 1 FROM subject_dek WHERE tenant_id = " + q(tenant)
                + " AND subject_type = 'customer' AND subject_id = " + q("v11-" + sfx + "-subject")
                + " AND dek_version = 1);\n");

        // ③ subject_key_tombstone —— 🛑 有 tombstone_no_update/delete 两条规则 ⇒ 不得用 ON CONFLICT。
        //    destroyed_at 用固定字面量而非 now()：WHERE NOT EXISTS 会保留首行，
        //    时间天然稳定，无需额外处理。
        sb.append("INSERT INTO subject_key_tombstone (tenant_id, subject_type, subject_id,"
                + " destroyed_at, reason) "
                + "SELECT " + q(tenant) + ", 'customer', " + q("v11-" + sfx + "-subject") + ", "
                + "TIMESTAMPTZ '2026-09-26 00:00:00+08', 'PIPL 删除权行使（RLS 门禁底数据）' "
                + "WHERE NOT EXISTS (SELECT 1 FROM subject_key_tombstone WHERE tenant_id = " + q(tenant)
                + " AND subject_type = 'customer' AND subject_id = " + q("v11-" + sfx + "-subject") + ");\n");

        return sb.toString();
    }

    /** 读底数据里的墓碑 subject_id（供只追加断言使用）。 */
    private static String seedTombstoneSubjectId(String tenant, String sfx) {
        return "v11-" + sfx + "-subject";
    }

    /** 读底数据里的 DEK subject_id。 */
    private static String seedDekSubjectId(String tenant, String sfx) {
        return "v11-" + sfx + "-subject";
    }

    /** 读底数据里的 KEK 标识。 */
    private static String seedKekId(String tenant) {
        return "kek-v11-" + SFX_A + "-01";
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
        public DataSource getIfAvailable() {
            return ds;
        }

        @Override
        public DataSource getIfUnique() {
            return ds;
        }
    }

    /** 保留：Map 未被直接使用时的占位（避免 IDE 提示未用导入时误删有用的工具）。 */
}