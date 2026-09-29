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
 * RlsV14ConfigTruthSourceIsolationTest: V14 迁移落库的
 * <b>配置真相源两张租户表</b>的【真实 PostgreSQL 隔离门禁 + 编号域与留痕纪律门禁】。
 *
 * <pre>
 *   app_config          租户层【生效值】—— 配置的运行时唯一读取来源
 *   app_config_history  变更历史 who / when / before / after（支撑回滚与举证）
 * </pre>
 *
 * <h2>🛑 为什么这两张表「不是又两张普通业务表」（三件事比业务表更值得断言）</h2>
 * <ol>
 *   <li><b>它们的 RLS 一旦缺失，泄漏的不是"一条记录"而是「租户的全部政策参数」</b>。
 *       配置值直接驱动判定阈值、退款闸门、可见性矩阵 —— 读到别租户的 {@code app_config}
 *       等于拿到对方的合规边界。故这里对两表逐一执行与业务表<b>同构</b>的隔离断言。</li>
 *   <li><b>{@code app_config_history} 的"只追加"实现手段与 V11 墓碑表<b>恰好相反</b></b>：
 *       V11 的墓碑用 RULE {@code DO INSTEAD NOTHING}（UPDATE / DELETE <b>静默无效</b>，
 *       影响行数 0、不抛异常）；V14 的历史表用 BEFORE 触发器 {@code RAISE ERRCODE 42501}
 *       （<b>响亮失败</b>）。两种形态的可观测行为完全不同：
 *       <ul>
 *         <li>{@code RlsV11CryptoKeyIsolationTest} 断言"影响行数 = 0 且原值未变"；</li>
 *         <li>本类断言"抛 DataAccessException，SQLSTATE = 42501"。</li>
 *       </ul>
 *       若照抄 V11 的断言形态，本类会对一条<b>真正生效</b>的保护给出假绿
 *       （"没抛异常"被读成"保护生效"，而实际上触发器把异常抛在了别处）。</li>
 *   <li><b>同一个"静默 / 响亮"的差异<b>反向</b>决定了清理策略</b>：
 *       墓碑表的 DELETE 静默无效 ⇒ V11 干脆不清理（并把它写成断言）；
 *       历史表的 DELETE 抛 42501 ⇒ 本类的清理<b>必须先临时禁用那个触发器</b>，
 *       否则 {@code psql -v ON_ERROR_STOP=1} 退出码非 0，provision 在
 *       {@code @AfterAll} 就红 —— 而那条失败看起来像"表有约束问题"，
 *       真因却是"清理语句没跟上被保护的语义"。见 {@link #buildCleanupSql()}。</li>
 * </ol>
 *
 * <h2>为什么必须补这一个类（而不是并进邻类）</h2>
 * {@link RlsV5EntityIsolationTest} / {@link RlsV6RefundLedgerIsolationTest} /
 * {@link RlsV7IntakeProfileRevisionIsolationTest} / {@link RlsV11CryptoKeyIsolationTest}
 * 各自在内部断言自己的表数与交付范围；把 V14 的 2 张塞进去会让它们的名字与自证开始说谎。
 * 配置真相源既不是客户级业务实体，也不是密钥材料 —— 它是<b>驱动全部判定的政策参数载体</b>。
 *
 * <p>按 ADR-02 第 3 层「租户表无隔离测试即构建失败」，两张表必须同时：
 * ① 登记进 {@link RlsCoverageGateTest#ISOLATION_TESTS}；
 * ② <b>登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution 的
 * {@code <include>}</b>（同时加入 {@code default-test} 的 {@code <exclude>}，
 * 否则会被"默认执行"一遍并与 ② 争用同一条库）；
 * ③ 有真实数据库的读写隔离断言（本类）。
 *
 * <h2>🛑 {@code config_slot} 为什么<b>不在</b>本类的 TABLES 里（这不是遗漏）</h2>
 * {@code config_slot} 是总部层【声明表】：编号 / 键 / 值类型 / 允许值 / 初始值。它
 * <b>不含 {@code tenant_id}</b>、<b>不启用 RLS</b> —— 性质同 {@code tenant} /
 * {@code schema_migration} 这类框架表（对所有租户同一份，没有"租户的行"可隔离）。
 * 若把塞进 {@link RlsCoverageGateTest#ISOLATION_TESTS}，覆盖门禁会以"僵尸登记"报红；
 * 若塞进 {@code NON_TENANT_TABLES} 豁免，又会造出"总部声明表无人值守"的形态。
 * 故它的纪律由 {@link #config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed()}
 * 承载 —— <b>断言"它不在迁移解析器的租户表集合里、且它守住了自己的编号域"</b>，
 * 而不是硬把它登记成一张"租户表"。
 *
 * <h2>底数据：{@code app_config_history} <b>不直接灌</b></h2>
 * 历史表的 FK 指向 {@code app_config (tenant_id, config_no)}，且历史行<b>只由触发器写入</b>
 * （{@code app_config_record_history_trg} —— "由触发器而不是应用层写入"是 V14 逐字记载的设计：
 * 否则 DBA 手敲 UPDATE、迁移脚本批量改值都不会留痕，而恰恰是这几种路径最需要留痕）。
 * 因此本类只灌 {@code app_config}，历史行<b>由灌入动作本身产生</b>（{@code op='SEED'}）。
 * 这带来一个额外好处：{@link #tenant_rows_are_invisible_across_tenants_in_both_directions()}
 * 对历史表的"每租户 ≥ N 行"断言，同时也是<b>"留痕触发器真的在工作"</b>的证明。
 *
 * <h2>探针写入为什么一律走【显式回滚】的事务</h2>
 * 与 {@link RlsV6RefundLedgerIsolationTest} / {@link RlsV11CryptoKeyIsolationTest} 同源：
 * ① 元数据断言依赖"每租户恰若干行"这一精确前提，探针残留会污染它；
 * ② 重跑即失败（固定主键的探针行残留会撞 23505，报错看起来像"配置表约束有问题"）。
 * 故：<b>期望被拒</b>的写入走普通事务（PG 让语句失败即中止事务，异常上抛 → 自动回滚）；
 * <b>期望被接受</b>的写入走 {@link #inRollbackTx}（显式 {@code setRollbackOnly}）。
 *
 * <h2>与邻类的隔离（避免把别人灌的数据当成自己的证据）</h2>
 * 本类只使用门禁共用的两个租户（{@code RlsGateSupport.TENANT_A/TENANT_B}）
 * 与本类专属的<b>配置编号</b> {@code #1}/{@code #2}/{@code #23}/{@code #46}。
 * 编号这一维即本类的"前缀"：邻类（V5/V6/V7/V11）从不触碰 {@code app_config}，
 * 故按租户整删等价于"精确圈定本类底数据"。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户<b>总是</b>绕过 RLS；用它跑隔离断言会全部"假通过"。
 * {@code @BeforeAll} 第二件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsV14ConfigTruthSourceIsolationTest {

    /**
     * V14 覆盖的 2 张<b>带 tenant_id 的</b>表（{@code config_slot} 不在其中，见类注释）。
     *
     * <p>⚠️ 与 {@link RlsCoverageGateTest#ISOLATION_TESTS} 的登记值必须指向本类；
     * 同时本类必须登记在 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>} 里 —— 不登记 include 会「存在但不执行」。
     */
    private static final List<String> TABLES = List.of("app_config", "app_config_history");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;

    /**
     * 本类使用的<b>已声明</b>配置编号（都必须存在于 V14 内联的 46 条声明里）。
     *
     * <p>选取依据 —— 每一条都要覆盖一个不同的取值类型分支，
     * 这样 {@link #value_validation_is_enforced_by_the_database()} 才能把
     * {@code app_config_enforce_slot()} 的四个分支都走到：
     * <pre>
     *   #1   INT     初始值 '7'
     *   #2   INT     初始值 '2'（第二个 INT，用于"改成合法值"的接受侧反证）
     *   #23  BOOL    初始值 'true'
     *   #35  JSON    取值必须能被 ::jsonb 解析
     *   #46  BOOL    初始值 'true'（第二个 BOOL，避免接受侧与拒绝侧撞同一行）
     * </pre>
     */
    private static final int NUM_INT = 1;
    private static final int NUM_INT_ALT = 2;
    private static final int NUM_BOOL = 23;
    private static final int NUM_JSON = 35;
    private static final int NUM_BOOL_ALT = 46;

    /**
     * 每表每租户的底数据行数下界（"反证表非空"的期望下限）。
     *
     * <p>= 2 而不是 1：{@code app_config} 每租户灌 2 行（{@code #1} 与 {@code #23}），
     * 各产生一行历史（{@code op='SEED'}）。取 2 能让"只灌进去了一条"这件事<b>当场可见</b> ——
     * 而"底数据少了一条"若不被断言拦住，会以"某张表看起来是空的"这类<b>误导性症状</b>
     * 出现在后续断言里（本仓实测踩过同形态的坑）。
     */
    private static final int BASE_ROWS_PER_TENANT = 2;

    /** 底线之内的"历史预留空号"（PRD §10：容器内部技术配置项，不进主配置表）。 */
    private static final int VACANT_42 = 42;

    /** 底线之内的"暂缺待落盘"编号（已由 optin-arch 线预定、尚未落 PRD，不得被永久封死）。 */
    private static final int PENDING_47 = 47;

    /** V14 内联的声明行数（46 条非空配置 = #1~#41 + #43~#46 + #48）。 */
    private static final int DECLARED_SLOT_COUNT = 46;

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

        // ① 用被测交付物的【真实迁移链】（V1+…+V14）建库 + 建非超级用户角色 + 基础种子。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V14-GATE] provision:\n" + provisionLog);

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

        // ③ 自建底数据（app_config 在两租户下各 2 行；历史行由触发器随之产生）
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
        jdbc.execute("RESET app.actor");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 断言 1: 跨租户互不可见（两方向对称）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 配置表: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
    void tenant_rows_are_invisible_across_tenants_in_both_directions() {
        for (String table : TABLES) {
            // 阶段 1: 租户 A 上下文下可见自己的底数据（反证"表非空"）
            Integer aRows = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertTrue(aRows >= BASE_ROWS_PER_TENANT,
                    table + ": 租户A 上下文下应可见 " + BASE_ROWS_PER_TENANT + " 条底数据, 实际 " + aRows
                            + " 行 —— 策略退化为全拒？或底数据没灌进去（历史表还可能是留痕触发器没工作）？");

            // 阶段 2: 切到租户 B，必须看不到 A 的任何行
            Integer crossA = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_A));
            assertEquals(0, crossA,
                    table + ": 租户B 读到了租户A 的 " + crossA + " 行 —— 串租户！"
                            + "配置表串租户的后果是泄漏对方的政策参数与合规边界");

            // 阶段 3: 对称方向
            Integer crossB = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                            Integer.class, TENANT_B));
            assertEquals(0, crossB, table + ": 租户A 读到了租户B 的 " + crossB + " 行 —— 对称性被破坏！");
        }
    }

    // ------------------------------------------------------------------
    // 断言 2: 无上下文 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 配置表: 未设上下文 / 空串上下文 → 零行（fail-closed，NULLIF 归一）")
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
    // 断言 3: 跨租户写入被 WITH CHECK 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 配置表: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // app_config —— 探针行除 tenant_id 外每个维度都合法（编号已声明、取值合法、
        // updated_by 非空），故 42501 的归因是干净的。
        // 🛑 若取值不合法，BEFORE 触发器 app_config_enforce_slot() 会先以 23514/23503 拒绝，
        //    那时得到的失败【不是 RLS 的失败】，用例就有两个失败理由、证据不可用。
        assertCrossTenantInsertRejected("app_config",
                "INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) "
                        + "VALUES (?::uuid, ? , '7', 1, 'probe-cross')",
                TENANT_B, NUM_INT);

        // app_config_history —— 历史表也 FORCE RLS；此处直接越租户写入历史行，
        // 验证"历史行不能跨租户伪造"。
        // ⚠️ 它的 FK 指向 app_config (tenant_id, config_no)：用租户 B 的行做父行，
        //    而租户 B 的 #1 底数据确实存在 ⇒ FK 可满足，42501 的归因仍然干净。
        assertCrossTenantInsertRejected("app_config_history",
                "INSERT INTO app_config_history (tenant_id, config_no, config_key, who,"
                        + " before_value, after_value, op, version) "
                        + "VALUES (?::uuid, ?, 'cfg:assessment.cycle_service_count', 'probe-cross',"
                        + " NULL, '7', 'UPSERT', 9)",
                TENANT_B, NUM_INT);
    }

    @Test
    @DisplayName("V14 配置表: 越权行确认未落库（在对方租户上下文下 count = 0）")
    void cross_tenant_insert_leaves_no_row_behind() {
        // ⚠️ 必须在租户B 上下文内 count：两表 FORCE RLS，无上下文一律零行，
        //    "无上下文 count = 0" 会因策略挡行而假绿，与被拒本身无关。
        Integer nConfig = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config "
                                + "WHERE tenant_id = ?::uuid AND config_no = ? AND updated_by = 'probe-cross'",
                        Integer.class, TENANT_B, NUM_INT));
        assertEquals(0, nConfig, "越权 app_config 行竟然落库了 " + nConfig + " 行");

        Integer nHistory = inTenantTx(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config_history "
                                + "WHERE tenant_id = ?::uuid AND who = 'probe-cross'",
                        Integer.class, TENANT_B));
        assertEquals(0, nHistory, "越权 app_config_history 行竟然落库了 " + nHistory + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据（真实 pg_catalog / pg_policies）+ 表结构要素
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 配置表: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
    void policy_metadata_shows_enable_force_and_nullif_on_both_sides() {
        for (String table : TABLES) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                            "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table),
                    table + " 必须 ENABLE ROW LEVEL SECURITY");
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                            "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table),
                    table + " 必须 FORCE ROW LEVEL SECURITY（防 owner 绕过 —— 配置是审计证据的载体）");

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

        // app_config 的主键必须含 tenant_id（缺它会让不同租户的同一配置项互相顶掉）
        String pk = jdbc.queryForObject(
                "SELECT string_agg(a.attname, ',' ORDER BY k.ord) "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                        + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                        + "WHERE t.relname = 'app_config' AND c.contype = 'p'",
                String.class);
        assertEquals("tenant_id,config_no", pk,
                "app_config 主键必须是 (tenant_id, config_no) —— 缺 tenant_id 会让不同租户的"
                        + "同一配置项互相顶掉，而报错看起来像『配置编号冲突』、与租户毫无关联。实际: " + pk);

        // 历史表的"父行约束"必须存在：跨租户留痕在结构上不成立
        List<String> histCons = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'app_config_history'::regclass "
                        + "AND contype IN ('f','c') ORDER BY conname",
                String.class);
        assertTrue(histCons.contains("app_config_history_parent"),
                "app_config_history 必须保留 app_config_history_parent 外键 —— "
                        + "它保证历史行挂在同一租户的同一配置项上。实际: " + histCons);
        assertTrue(histCons.contains("app_config_history_op_known"),
                "app_config_history 必须保留 app_config_history_op_known CHECK。实际: " + histCons);

        // 覆盖数自证（防"表名打错所以查不到策略"的假绿）
        assertEquals(2, TABLES.size(), "本类声明的表数必须恰为 2（V14 的租户表交付范围）");
    }

    // ------------------------------------------------------------------
    // 断言 5: 编号域 —— 46 条声明 + #42 永久空号 + #47 暂缺（两种性质不同）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 config_slot: 46 条声明在位；#42 被永久封死、#47 暂缺但未被封死（两种裁定性质不同）")
    void config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed() {
        // ① 声明行数必须恰为 46（46 条非空配置 = #1~#41 + #43~#46 + #48）
        Integer declared = jdbc.queryForObject(
                "SELECT count(*) FROM config_slot", Integer.class);
        assertEquals(DECLARED_SLOT_COUNT, declared,
                "config_slot 的声明行数必须是 " + DECLARED_SLOT_COUNT + "，实际 " + declared
                        + " —— 少一行会让某个配置项在租户层写不进去（外键 23503），"
                        + "而那种失败看起来像『配置表被锁』而不是『声明缺了一条』");

        // ② #42 与 #47 都必须【没有】声明行
        for (int vac : new int[] {VACANT_42, PENDING_47}) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM config_slot WHERE config_no = ?", Integer.class, vac);
            assertEquals(0, n, "#" + vac + " 不得有声明行（实际 " + n + " 行）");
        }

        // ③ 两者的机制【不同】，必须分别断言 —— 这是本测试的关键区分：
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'config_slot'::regclass "
                        + "AND contype = 'c' ORDER BY conname",
                String.class);
        assertTrue(checks.contains("config_slot_no_42_stays_vacant"),
                "🛑 #42 必须由 CHECK 约束【永久封死】（PRD §10：容器内部技术配置项、不进主配置表）—— "
                        + "缺这条约束，一条 INSERT 就能占用 #42，所有既有编号引用会静默错位。实际: " + checks);
        assertTrue(checks.contains("config_slot_no_in_domain"),
                "config_slot 必须有编号域 CHECK。实际: " + checks);

        String domain = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'config_slot'::regclass AND conname = 'config_slot_no_in_domain'",
                String.class);
        assertNotNull(domain, "缺 config_slot_no_in_domain");
        assertTrue(domain.contains("48"),
                "编号域上界应为 48（#48 health_pnl_visibility 已落 PRD）。实际: " + domain);

        // ④ #42 与 #47 在【写入路径】上都写不进去（外键 / 触发器 23503）。
        //    🛑 但拒绝理由不同：#42 是"永久空号"，#47 是"暂缺待落盘"——
        //       若将来 #47 落 PRD，本测试应当【被改写而不是被删除】：
        //       它会红，从而强迫落盘者显式表态。这正是"暂缺"与"空号"的差别。
        for (int vac : new int[] {VACANT_42, PENDING_47}) {
            DataAccessException caught = assertThrows(DataAccessException.class,
                    () -> inTenantTx(TENANT_A, () -> {
                        jdbc.update("INSERT INTO app_config"
                                        + " (tenant_id, config_no, value, version, updated_by) "
                                        + "VALUES (?::uuid, ?, '7', 1, 'probe-vacant')",
                                TENANT_A, vac);
                        return null;
                    }),
                    "#" + vac + " 被成功写入 app_config —— 编号裁定形同虚设");
            SQLException se = rootSqlException(caught);
            assertNotNull(se, "#" + vac + " 被拒原因应来自数据库，实际异常链: " + caught);
            assertEquals("23503", se.getSQLState(),
                    "#" + vac + " 应以未声明编号（23503）被拒，实际 " + se.getSQLState());
        }

        // ⑤ config_slot 的"不启 RLS"前提必须成立：它没有 tenant_id，且 relrowsecurity = false。
        //    🛑 这条断言的价值在于把"为什么不登记它"从注释变成机器可验证的事实 ——
        //       若将来有人给它加了 tenant_id 却忘了登记，这里会先红。
        Integer slotTidCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='config_slot' AND column_name='tenant_id'",
                Integer.class);
        assertEquals(0, slotTidCols,
                "config_slot 不得有 tenant_id —— 它是总部层声明表（对所有租户同一份）。"
                        + "若真加了，它就必须被当作租户表登记；本条会先红以避免'漏登记'");
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'config_slot'", Boolean.class),
                "config_slot 不应启用 RLS —— 没有可隔离的租户维度");

        // ⑥ 声明表的键唯一性（config_key 全局唯一）—— 重复键会让读路径取到哪一行变成不确定
        List<String> slotCons = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'config_slot'::regclass "
                        + "AND contype IN ('p','u') ORDER BY conname",
                String.class);
        assertTrue(slotCons.contains("config_slot_pk") && slotCons.contains("config_slot_key_uniq"),
                "config_slot 必须保留主键与 config_key 唯一约束。实际: " + slotCons);
    }

    // ------------------------------------------------------------------
    // 断言 6: 取值校验（数据库层 fail-closed，四个类型分支都要走到）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 app_config: 非法取值被 CHECK 触发拒绝（INT/BOOL/JSON/ENUM 四分支逐一验证）")
    void value_validation_is_enforced_by_the_database() {
        // ① INT 分支：非整数必须被拒
        assertValueRejected(NUM_INT, "abc", "INT 分支（#1 应为非负整数）");

        // ② BOOL 分支：非 true/false 必须被拒
        assertValueRejected(NUM_BOOL, "yes", "BOOL 分支（#23 应为 true/false）");

        // ③ BOOL 分支的拼写边界：'TRUE' 大写也必须被拒（取值集是精确的，不是 LIKE 匹配）
        assertValueRejected(NUM_BOOL_ALT, "TRUE", "BOOL 分支（#46 大小写必须精确匹配 true/false）");

        // ④ JSON 分支：非法 JSON 必须被拒（函数内 PERFORM NEW.value::jsonb）
        assertValueRejected(NUM_JSON, "{not-json", "JSON 分支（#35 必须是合法 JSON）");

        // ⑤ ENUM 分支 —— 禁止组合优先于允许集合：
        //    #44 的 forbidden_values 含"小程序后台自动采集"这一【代码组合】
        //    （微信 requiredBackgroundModes 无 bluetooth，技术上不可能，§2.8.2 P-2）。
        //    它必须被拒，且拒绝理由来自 forbidden_values 而不是"不在允许集合内"。
        assertValueRejected(44, "小程序后台自动采集", "ENUM 分支 forbidden_values（#44 禁止组合）");
        assertValueRejected(44, "MINIPROGRAM_BACKGROUND_AUTO", "ENUM 分支 forbidden_values（代码形态）");

        // ⑥ ENUM 分支 —— 不在允许集合内的取值
        assertValueRejected(44, "M-SOMETHING-ELSE", "ENUM 分支 allowed_values（#44 允许集合）");

        // ⑦ 反证（接受侧）：合法取值必须被接受，否则上面的"拒绝"可能是因为别的原因。
        //    🛑 这一步不能省 —— 只断言"非法值被拒"无法区分"校验在工作"与"表整体写不进去"。
        inRollbackTx(TENANT_A, () -> {
            int n1 = jdbc.update("INSERT INTO app_config"
                            + " (tenant_id, config_no, value, version, updated_by) "
                            + "VALUES (?::uuid, ?, '999', 1, 'probe-accept')",
                    TENANT_A, NUM_INT_ALT);
            assertEquals(1, n1, "合法的 INT 取值被拒 —— 校验过宽，正常配置将无法下发");

            int n2 = jdbc.update("INSERT INTO app_config"
                            + " (tenant_id, config_no, value, version, updated_by) "
                            + "VALUES (?::uuid, ?, 'false', 1, 'probe-accept')",
                    TENANT_A, NUM_BOOL_ALT);
            assertEquals(1, n2, "合法的 BOOL 取值被拒 —— 校验过宽");

            int n3 = jdbc.update("INSERT INTO app_config"
                            + " (tenant_id, config_no, value, version, updated_by) "
                            + "VALUES (?::uuid, ?, '{\"a\":1}', 1, 'probe-accept')",
                    TENANT_A, NUM_JSON);
            assertEquals(1, n3, "合法的 JSON 取值被拒 —— 校验过宽");
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 断言 7: 留痕纪律 —— 由触发器写入 + append-only（响亮失败，与 V11 静默相反）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 app_config_history: 变更由触发器留痕（who/before/after/op），且历史行不可改不可删（42501）")
    void history_is_append_only_and_written_by_the_trigger() {
        // ① 触发器必须真实在位（这是"全部写入路径都留痕"的机械载体）
        List<String> trgs = jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'app_config'::regclass AND NOT tgisinternal "
                        + "ORDER BY tgname",
                String.class);
        assertTrue(trgs.contains("app_config_record_history_trg"),
                "app_config 上必须有留痕触发器 —— 若留痕由应用层写入，DBA 手敲 UPDATE、"
                        + "迁移脚本批量改值都不会留痕，而恰恰是这几种路径最需要留痕。实际: " + trgs);
        assertTrue(trgs.contains("app_config_enforce_slot_trg"),
                "app_config 上必须有取值校验触发器。实际: " + trgs);

        // ② 灌入动作本身必须已产生 SEED 历史行（before_value 为 NULL —— 首次写入没有前值）
        Integer seedRows = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config_history "
                                + "WHERE tenant_id = ?::uuid AND op = 'SEED' AND before_value IS NULL"
                                + " AND config_no = ?",
                        Integer.class, TENANT_A, NUM_BOOL));
        assertEquals(1, seedRows,
                "首次写入应以 op='SEED' 且 before_value IS NULL 留痕恰 1 行，实际 " + seedRows
                        + " 行 —— 留痕触发器未生效，或底数据没灌进去");

        // ③ 更新必须留痕：who / when / before / after / op / version 六项齐全且可追溯。
        //    用显式回滚事务承载"应当成功"的探针写入（见类注释）。
        String probeActor = "v14-probe-admin";
        inRollbackTx(TENANT_A, () -> {
            // who 取自 app.actor（应用在事务内绑定写入人）；这里显式绑定以证明它真的被读到
            jdbc.execute("SET LOCAL app.actor = '" + probeActor + "'");

            String before = jdbc.queryForObject(
                    "SELECT value FROM app_config WHERE tenant_id = ?::uuid AND config_no = ?",
                    String.class, TENANT_A, NUM_BOOL);
            assertNotNull(before, "前置: 租户A 的 #" + NUM_BOOL + " 应有底数据");
            String after = "true".equals(before) ? "false" : "true";

            int affected = jdbc.update("UPDATE app_config SET value = ?, version = version + 1,"
                            + " updated_by = ? WHERE tenant_id = ?::uuid AND config_no = ?",
                    after, probeActor, TENANT_A, NUM_BOOL);
            assertEquals(1, affected, "合法的配置更新竟然影响了 " + affected + " 行");

            List<Map<String, Object>> hist = jdbc.queryForList(
                    "SELECT who, before_value, after_value, op, version FROM app_config_history "
                            + "WHERE tenant_id = ?::uuid AND config_no = ? ORDER BY id DESC LIMIT 1",
                    TENANT_A, NUM_BOOL);
            assertEquals(1, hist.size(), "更新配置后应新增一行历史 —— 未留痕的配置变更无法举证");
            Map<String, Object> h = hist.get(0);
            assertEquals(probeActor, h.get("who"),
                    "历史行的 who 必须取自 app.actor（应用绑定的写入人），实际=" + h.get("who"));
            assertEquals(before, h.get("before_value"), "before_value 必须是被改前的旧值");
            assertEquals(after, h.get("after_value"), "after_value 必须是新值");
            assertEquals("UPSERT", h.get("op"),
                    "未设 app.config.op 时 UPDATE 应记为 'UPSERT'，实际=" + h.get("op"));
            assertEquals(2L, ((Number) h.get("version")).longValue(),
                    "历史行的 version 必须跟随被写入的版本号（递增到 2），实际=" + h.get("version"));
            return null;
        });

        // ④ 回滚标记：显式设 app.config.op = 'ROLLBACK' 时必须被如实记录
        //    （服务层回滚走的就是这条路径 —— 它是"回滚也要留痕"的证据）
        inRollbackTx(TENANT_A, () -> {
            jdbc.execute("SET LOCAL app.actor = '" + probeActor + "'");
            jdbc.execute("SET LOCAL app.config.op = 'ROLLBACK'");
            jdbc.update("UPDATE app_config SET value = 'true', version = version + 1, updated_by = ? "
                    + "WHERE tenant_id = ?::uuid AND config_no = ?", probeActor, TENANT_A, NUM_BOOL);

            String op = jdbc.queryForObject(
                    "SELECT op FROM app_config_history WHERE tenant_id = ?::uuid AND config_no = ? "
                            + "ORDER BY id DESC LIMIT 1",
                    String.class, TENANT_A, NUM_BOOL);
            assertEquals("ROLLBACK", op,
                    "app.config.op 未被留痕触发器读取 —— 回滚与常规更新在证据上无法区分，实际=" + op);
            return null;
        });

        // ⑤ append-only：UPDATE 必须【响亮失败】（SQLSTATE 42501），
        //    🛑 与 V11 墓碑表的"静默无效"刻意相反 —— 见类注释第 2 条。
        DataAccessException onUpdate = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("UPDATE app_config_history SET after_value = 'tampered' "
                            + "WHERE tenant_id = ?::uuid", TENANT_A);
                    return null;
                }),
                "历史行的 UPDATE 被接受了 —— append-only 纪律未生效（配置变更历史可被篡改）");
        SQLException seUpdate = rootSqlException(onUpdate);
        assertNotNull(seUpdate, "被拒原因应来自数据库，实际异常链: " + onUpdate);
        assertEquals("42501", seUpdate.getSQLState(),
                "历史行 UPDATE 应以触发器 42501 拒绝，实际 SQLSTATE=" + seUpdate.getSQLState());

        // ⑥ append-only：DELETE 同样必须【响亮失败】
        DataAccessException onDelete = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("DELETE FROM app_config_history WHERE tenant_id = ?::uuid", TENANT_A);
                    return null;
                }),
                "历史行的 DELETE 被接受了 —— 删除权出现绕行路径（删掉历史就再也举不了证）");
        SQLException seDelete = rootSqlException(onDelete);
        assertNotNull(seDelete, "被拒原因应来自数据库");
        assertEquals("42501", seDelete.getSQLState(),
                "历史行 DELETE 应以触发器 42501 拒绝，实际 SQLSTATE=" + seDelete.getSQLState());

        // ⑦ 两条纪律的机械载体必须在位（触发器名逐字断言，防"策略被换名后静默失效"）
        List<String> historyTrgs = jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'app_config_history'::regclass "
                        + "AND NOT tgisinternal ORDER BY tgname",
                String.class);
        assertTrue(historyTrgs.contains("app_config_history_append_only_trg"),
                "app_config_history 必须保留 append-only 触发器。实际: " + historyTrgs);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 断言"在租户A 上下文下写入租户B 的行"以 42501 被拒（且归因干净）。 */
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
                        + "（若为 23514/23503 说明探针本身不合法，归因不干净 —— "
                        + "那种失败证明的是校验触发器在工作，不是 RLS 在工作）");
    }

    /** 断言某个已声明编号上的非法取值以校验类错误被拒。 */
    private void assertValueRejected(int configNo, String badValue, String what) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update("INSERT INTO app_config"
                                    + " (tenant_id, config_no, value, version, updated_by) "
                                    + "VALUES (?::uuid, ?, ?, 1, 'probe-bad')",
                            TENANT_A, configNo, badValue);
                    return null;
                }),
                what + ": 非法取值 " + badValue + " 被接受了 —— 数据库层取值校验未生效");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, what + " 被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals("23514", se.getSQLState(),
                what + " 应以 check_violation (23514) 拒绝，实际 " + se.getSQLState());
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
     * 否则固定主键的探针行第二次运行会撞 23505，报错看起来像"配置表约束有问题"。
     *
     * <p>⚠️ 被触发器写入的历史行<b>同样随本事务回滚</b>：留痕与业务写在同一事务里，
     * 这正是"配置是审计证据的载体，必须与审计链在同一可事务化存储"（ADR-08）的落地形态。
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
        requireOk("v14 cleanup", RlsGateSupport.runSql(RlsGateSupport.SUPER_USER,
                RlsGateSupport.superPassword(), RlsGateSupport.DB, buildCleanupSql()));
        requireOk("v14 seed A", RlsGateSupport.runSql(RlsGateSupport.SUPER_USER,
                RlsGateSupport.superPassword(), RlsGateSupport.DB, seedSqlFor(TENANT_A)));
        requireOk("v14 seed B", RlsGateSupport.runSql(RlsGateSupport.SUPER_USER,
                RlsGateSupport.superPassword(), RlsGateSupport.DB, seedSqlFor(TENANT_B)));
    }

    /**
     * {@code runSql} 的失败不再被静默忽略。
     *
     * <p>🛑 这是一处<b>刻意的严格化</b>：若清理或灌底写入失败而不报错，症状会是
     * "表看起来是空的" / "上一轮残留没清掉"，而那种症状<b>看起来像 RLS 策略退化</b> ——
     * 排查方向会被整体带偏（本仓已实测踩到过同形态的坑：中文种子因编码失败静默缺失，
     * 表现为"租户 B 无行"）。故此处把非零退出码直接变成异常。
     */
    private static void requireOk(String label, RlsGateSupport.PsqlResult r) {
        if (!r.ok()) {
            throw new IllegalStateException(label + " 失败: psql EXIT=" + r.exitCode()
                    + "\n--- 输出 ---\n" + r.output());
        }
    }

    /**
     * 清理 SQL：按租户删掉本类灌入的 {@code app_config} 行，以及由触发器产生的历史行。
     *
     * <h2>🛑🛑 为什么必须先临时 DISABLE 那个 append-only 触发器（与 V11 的处置相反）</h2>
     * {@code app_config_history} 的 {@code app_config_history_append_only_trg} 是
     * BEFORE UPDATE OR DELETE → {@code RAISE ERRCODE '42501'}。
     * 也就是说：<b>DELETE 是"响亮失败"的，不是"静默无效"的</b>。
     * 直接写 {@code DELETE FROM app_config_history} 会让 psql 以非零退出码结束，
     * provision 在 {@code @AfterAll} 就红 —— 而那条失败看起来像"表有约束问题"，
     * 真因却是"清理语句没有跟上被保护的语义"。
     *
     * <p>对照记（这不是笔误，是本仓两种纪律实现手段的真实差异）：
     * <pre>
     *   V11 subject_key_tombstone   RULE DO INSTEAD NOTHING  → 静默无效 → 干脆不清理，并写成断言
     *   V14 app_config_history      TRIGGER RAISE 42501      → 响亮失败 → 清理前临时禁用，事后恢复
     * </pre>
     * 两者都不能"照抄对方的写法"。禁用触发器需要表 owner 权限，故本脚本以
     * {@code SUPER_USER} 执行（见 {@link #provisionSeeds()}）。
     *
     * <h2>为什么可以按租户整删（而不是像 V11 那样加主键前缀）</h2>
     * {@code app_config} 的主键只有 {@code (tenant_id, config_no)} 两维，
     * <b>没有可加前缀的第三维</b>（V11 的 {@code subject_id} 是业务属性，可加前缀）。
     * 而 {@code TENANT_A}/{@code TENANT_B} 是门禁专用租户（基础种子提供、
     * 仅隔离测试使用），故按租户整删等价于"精确圈定本类底数据"。
     *
     * <p>⚠️ 若将来有别的门禁类也给这两个租户灌 {@code app_config}，两者会互删 ——
     * 届时必须改为按"编号白名单"圈定，并把白名单登记在本类顶部常量里。
     */
    private static String buildCleanupSql() {
        return "\\set ON_ERROR_STOP on\n"
                // ① 先禁 append-only 触发器（否则下一条 DELETE 会以 42501 中止整段清理）
                + "ALTER TABLE app_config_history DISABLE TRIGGER app_config_history_append_only_trg;\n"
                // ② 顺序必须"子表先删"：history 的外键指向 app_config
                + "DELETE FROM app_config_history WHERE tenant_id IN ('" + TENANT_A + "', '"
                + TENANT_B + "');\n"
                + "DELETE FROM app_config WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "');\n"
                // ③ 恢复触发器 —— 必须恢复，否则"清理过一次"就永久关掉了本表的防篡改保护，
                //    而后续断言会读到"UPDATE 居然成功了"这种假红/假绿
                + "ALTER TABLE app_config_history ENABLE TRIGGER app_config_history_append_only_trg;\n";
    }

    /**
     * 为某个租户灌底数据：{@code app_config} 2 行（{@code #1} / {@code #23}），
     * 历史行由 {@code app_config_record_history_trg} 随之产生（{@code op='SEED'}）。
     *
     * <p>幂等写法用 {@code ON CONFLICT (tenant_id, config_no) DO NOTHING}：
     * <ul>
     *   <li>{@code app_config} 上只有<b>触发器</b>（BEFORE 校验 + AFTER 留痕），
     *       没有 INSERT/UPDATE <b>RULE</b>。PG 只对"具有 INSERT/UPDATE 规则的表"
     *       拒绝 {@code ON CONFLICT}（V11 的墓碑表就是这样被拦住的），
     *       触发器不影响其可用性 —— 故此处可以安全使用。</li>
     *   <li>命中冲突时不发生实际插入 ⇒ AFTER 触发器不触发 ⇒ <b>不产生重复历史行</b>，
     *       重跑天然幂等。</li>
     * </ul>
     *
     * <p>⚠️ 取值必须是<b>合法值</b>：{@code app_config_enforce_slot_trg} 对超级用户同样生效，
     * 写成 {@code '#1 = abc'} 会让灌底在 provision 阶段就红，而那条失败
     * 看起来像"表被锁住"。此处用与声明一致的 {@code '7'} 与 {@code 'true'}。
     *
     * <p>⚠️ {@code app_config} 的 FK 指向 {@code tenant}，而两租户由基础种子
     * （{@code verification/02_seed.sql}）提供 —— 故不需要（也不应该）在此自建租户：
     * 自建租户会让 provision 的 {@code tenant=2} 后置校验报红。
     */
    private static String seedSqlFor(String tenant) {
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");
        sb.append("INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) VALUES (")
                .append(q(tenant)).append(", ").append(NUM_INT).append(", '7', 1, 'seed')")
                .append(" ON CONFLICT (tenant_id, config_no) DO NOTHING;\n");
        sb.append("INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) VALUES (")
                .append(q(tenant)).append(", ").append(NUM_BOOL).append(", 'true', 1, 'seed')")
                .append(" ON CONFLICT (tenant_id, config_no) DO NOTHING;\n");
        return sb.toString();
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
}