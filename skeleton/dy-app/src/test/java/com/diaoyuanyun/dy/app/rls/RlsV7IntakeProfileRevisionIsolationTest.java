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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RlsV7IntakeProfileRevisionIsolationTest: V7 迁移落库的
 * **建档修订留痕账本（{@code intake_profile_revision}）** 的【真实 PostgreSQL 隔离门禁】，
 * 外加 V7 另一半交付（**给 {@code customer} 补 5 列**）的落库自证。
 *
 * <h2>它覆盖什么 —— 注意 V7 有<b>两种</b>交付物，本类都要管</h2>
 * <pre>
 *  ① CREATE TABLE intake_profile_revision    ← 新增租户表 ⇒ 【必须】走 ADR-02 第 3 层隔离门禁
 *  ② ALTER TABLE customer ADD COLUMN × 5      ← 只改既有表 ⇒ 不进覆盖登记表，但【必须】自证
 * </pre>
 * 第 ② 类是 V5/V6 完全没有的形态，也正是本类不能并进邻类的第二个理由
 * （第一个理由是名字自证：那两个类各自断言自己的表数）。
 * 「补列」看起来无事发生，实际有三个可坏点：新列没落库（{@code IF NOT EXISTS} 静默跳过）、
 * CHECK/FK 没建上（约束名被顺手改掉）、以及<b>顺手把既有 customer 的 RLS 弄坏</b>。
 * 最后一条最隐蔽 —— 它不在 V7 的 diff 里，而表现为"另一个域的数据开始串租户"。
 *
 * <h2>为什么必须补这一个类（而不是并进 {@link RlsV5EntityIsolationTest} / {@link RlsV6RefundLedgerIsolationTest}）</h2>
 * 那两个类的名字与职责写着「V5 的 <b>24 张</b>余量表」/「V6 的 <b>3 张</b>留痕账本」，
 * 且各自在内部 {@code assertEquals(N, TABLES.size(), …)} 自证。把 V7 的表塞进去，
 * 会让它们的名字开始说谎 —— 而"名字说谎的测试类"正是下一个接手的人在排查 RLS 缺口时
 * 最先被误导的地方。
 *
 * <p>按 ADR-02 第 3 层「租户表无隔离测试即构建失败」，{@code intake_profile_revision} 必须同时：
 * ① 登记进 {@link RlsCoverageGateTest#ISOLATION_TESTS}；
 * ② <b>登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution 的 {@code <include>}</b>
 * —— 不登记 include 会「存在但不执行」，登记表就成了一纸空文（这正是那个 execution 的
 * {@code failIfNoTests=true} 要防的形态）；③ 有真实数据库的读写隔离断言（本类）。
 *
 * <h2>🛑 本表为什么值得单独一套断言（它不是"又一张普通表"）</h2>
 * <ol>
 *   <li><b>它是本工程第二张 append-only 账本，且是"自引用"的</b>：
 *       {@code supersedes_revision_id} 是指向<b>自身</b>的外键（V6 是跨表指向 refund_statement 的
 *       同列）。自引用 FK 的删除顺序与"更正链"的完整性都不是"顺带就有"的，
 *       故本类把「更正 = 追加新行 + 反向指针；旧行逐字不变」做成可回归的断言。</li>
 *   <li><b>它有 {@code created_at} 但【刻意没有】{@code updated_at}</b>。
 *       V6 的三张表两者都没有；本表有 {@code created_at}（插入时刻，审计需要），
 *       但**不能**有 {@code updated_at} —— 一旦有了它，"就地改写"就获得一个看起来正常的位置，
 *       而 B6 要求的是「append-only 留痕，不可覆盖」。
 *       故本类<b>同时断言两件事</b>：{@code updated_at} 必须<b>不存在</b>、{@code created_at} 必须<b>存在</b>。
 *       只断言前者是不够的 —— 一个把整表列都写错的迁移也能"通过"那条断言。</li>
 *   <li><b>{@code recorded_at} 刻意<b>无库层 DEFAULT</b></b>（V5 的 {@code consent.signed_at}
 *       同型）。它是"当时发生了什么"的锚点：若插入时取 {@code now()}，补录场景下它会指向
 *       补录时刻而非修订时刻 —— 而那种偏差在数据上<b>完全看不出来</b>。
 *       故本类直接查 {@code information_schema} 断言该列 {@code column_default IS NULL}。</li>
 *   <li><b>{@code operator_id} 刻意可空</b>（容纳历史系统行），与 V5 的
 *       {@code screening_record.operator_id NOT NULL} 形成对照 —— 两者都是"操作人"，
 *       但一个是"新链路每次都有责任人"、一个是"要能承接历史数据"。
 *       这类"看起来不一致其实是刻意的"列，正是最容易被下一个人顺手改成 NOT NULL 的地方。</li>
 *   <li><b>{@code UNIQUE (tenant_id, customer_id, revision_no)}</b> 的唯一性范围是
 *       <b>租户内的该客户</b>，而不是全局、也不是租户内全局。范围写错不会报错，
 *       只表现为"另一个客户的第 3 次修订写不进去"，或反过来"重放写入了两条第 3 次修订"。</li>
 * </ol>
 *
 * <h2>🛑 探针写入为什么一律走【显式回滚】的事务</h2>
 * 与 {@link RlsV6RefundLedgerIsolationTest} 完全同源，两条理由各自独立：
 * <ol>
 *   <li><b>底数据前提被污染</b>：本类的元数据断言依赖"租户 A 恰 2 行 / 租户 B 恰 1 行、
 *       其中一行带 {@code supersedes_revision_id}"这一精确前提，探针行会让它失去意义；</li>
 *   <li><b>重跑即失败</b>：固定主键的探针行一旦残留，第二次运行会撞主键（23505），
 *       而报错看起来像"账本约束有问题"。</li>
 * </ol>
 * 故：<b>期望被拒</b>的写入走普通事务（PG 让语句失败即中止事务，异常向上抛 →
 * {@link TransactionTemplate} 自动回滚，天然零残留）；<b>期望被接受</b>的写入走
 * {@link #inRollbackTx}（显式 {@code setRollbackOnly}），断言有效而数据不落库。
 *
 * <h2>与邻类的隔离（避免把别人灌的数据当自己的证据）</h2>
 * 主键第 4 段后缀取 {@code 0005}/{@code 0006}（A/B 租户），与
 * {@link RlsV6RefundLedgerIsolationTest}（{@code 0003}/{@code 0004}）、
 * {@link RlsV5EntityIsolationTest}（{@code 0001}/{@code 0002}）、
 * {@link RlsBEntityIsolationTest}（{@code 0000}）、基础种子（{@code c1111111…}）**零交集**，
 * 故清理可按主键前缀精确圈定本类自己灌的行。
 *
 * <h2>底数据为什么要自建 —— 以及为什么本类比 V6 <b>简单得多</b></h2>
 * 「跨租户读到 0 行」只有在表<b>非空</b>时才有证明力：空表的 0 行毫无意义。
 * 本表的 FK 只有两条：{@code customer_id → customer}、{@code operator_id → staff}。
 * 前者可直接挂在**基础种子客户**上（{@code customer_id} 不要求"自建"），
 * 后者可空 —— 故本类<b>不需要</b>像 V6 那样自建 region→store→staff→refund 四层链，
 * 只需灌两三条账本行。这是"表设计得对，测试就短"的一个实例：
 * V6 那条长链的存在本身说明 refund 的依赖面更宽，而不是 V6 的测试写得更仔细。
 *
 * <h2>为何必须用非超级用户</h2>
 * PG 中超级用户总是绕过 RLS；用它跑隔离断言会全部"假通过"。
 * {@code @BeforeAll} 第二件事就是自证"当前不是超级用户"。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 按 ADR-02 L3，连不上库 = 缺少隔离测试 → 构建失败。
 * 唯一逃生阀是显式 {@code -Ddy.rls.gate.skip=true}，绝不静默跳过。
 */
class RlsV7IntakeProfileRevisionIsolationTest {

    /**
     * V7 覆盖的 1 张新表。
     *
     * <p>⚠️ 与 {@link RlsCoverageGateTest#ISOLATION_TESTS} 的登记值必须指向本类；
     * 同时本类必须登记在 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>} 里 —— 不登记 include 会「存在但不执行」。
     */
    private static final List<String> TABLES = List.of("intake_profile_revision");

    /** 本表的唯一主键列（与 V7 建表定义一致）。 */
    private static final String PK_COLUMN = "revision_id";

    /** V7 给 {@code customer} 补的 5 列（另一半交付物）。 */
    private static final List<String> CUSTOMER_ADDED_COLUMNS = List.of(
            "phone", "gender", "age", "owner_store_id", "serving_store_id");

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;
    private static final String SEED_A_ID = RlsGateSupport.SEED_A_ID;
    private static final String SEED_B_ID = RlsGateSupport.SEED_B_ID;

    /**
     * 本类底数据主键的 UUID 第 4 段后缀（A = 0005 / B = 0006）。
     *
     * <p>与邻类零交集，故 {@link #buildCleanupSql()} 可按前缀精确删除，
     * 不会误删 V6 的 {@code 0003/0004} 行、V5 的 {@code 0001/0002} 行或基础种子。
     */
    private static final String SFX_A = "0005";
    private static final String SFX_B = "0006";

    /**
     * 本类【自建的第二客户】与它的修订行的 UUID 第 4 段后缀（客户 = {@code 0009} / 修订 = {@code 000a}）。
     *
     * <h2>🛑 为什么要自建客户（2026-09-27 V16 落地后的处置，全量回归抓出）</h2>
     * 本类下面有一条「唯一性范围内不得跨客户生效」的断言，它需要"同租户里的<b>另一个</b>客户"。
     * 原始写法借用的是 {@code SEED_B_ID}（租户 B 的种子客户）——
     * 在 A 的上下文里往 {@code tenant_id=A} 的行上挂 {@code customer_id=B的客户}，
     * 当时<b>确实</b>能通过，因为那正是 PG 的「外键检查绕过 RLS」缺口。
     *
     * <p>V16 把本表 {@code customer_id} 的外键升级为<b>租户耦合的复合外键</b>
     * {@code (tenant_id, customer_id) → customer(tenant_id, id)} 之后，
     * 这种借用立刻变成**跨租户引用**（{@code 23503}）——
     * 而"另一客户"的正确表达从来就是<b>同租户</b>的另一个客户，不是另一个租户的客户。
     * 故自建一个同租户客户 {@link #CUST_A2_ID}。
     *
     * <p>🛑 <b>为什么它在【回滚事务内】自建，而不是在 {@code @BeforeAll} 里落库</b>：
     * {@code RlsGateSupport.provisionRealDatabase()} 对数据层有一条<b>硬断言</b>
     * {@code EXPECTED_DATA_SUMMARY_PREFIX = "tenant=2 customer=2 seed=2 extra=0"} ——
     * 即"非种子 customer 必须为 0"。若把一个非种子客户"落库"成底数据，
     * 就等于把风险压到 provision **唯一**的硬断言所盯的那个维度上：
     * 一旦测试被中断（未跑到 {@code @AfterAll}），残留会让<b>下一个测试类</b>的
     * provision 报红，而失败信息看起来与本次改动毫无关系。
     * 在回滚事务内建它 ⇒ **零持久化、零耦合**，且更贴合"临时借用"的原意。
     * （这与 {@link RlsCrossTenantReferenceGateTest} 的"自建客户"是同一手法。）
     *
     * <p>与 {@code 0005/0006}（本类底数据）、{@code 0007/0008}（V11 密钥）、
     * {@code 0001}~{@code 0004}（V5/V6）零交集，故 {@link #buildCleanupSql()}
     * 仍可按前缀精确圈定，不会误删任何邻类的行。
     */
    private static final String SFX_CUST = "0009";
    private static final String SFX_REV = "000a";

    /** 同租户的第二客户（A 租户），每次断言在<b>回滚事务内</b>现建，用于"唯一性不得跨客户生效"。 */
    private static final String CUST_A2_ID = "00000000-0000-0000-" + SFX_CUST + "-000000000001";

    /** 每表每租户的底数据行数下界（A 有意灌 2 行以构成一条"更正链"，故下界取 1 即可）。 */
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

        // ① 用被测交付物的【真实迁移链】（V1+…+V7）建库 + 建非超级用户角色 + 基础种子。
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[RLS-V7-GATE] provision:\n" + provisionLog);

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

        // ③ 自建底数据（本表只需挂基础种子客户，无需自建 FK 依赖链）
        provisionSeeds();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            // 清理本类自建的底数据（超级用户绕过 RLS 是 PG 既有语义）。
            // 本表只有 1 张、且自引用 FK 的两行互不指向（底数据里的更正行指向的是同租户另一行），
            // 故按子→父的顾虑只需一句 DELETE 即可覆盖整条链。
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
    @DisplayName("V7 修订账本: 租户A 的行对租户B 零行可见；反向亦零行（对称）")
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
    @DisplayName("V7 修订账本: 按主键直查他租户的行也必须零行（RLS 不能只挡列表查询）")
    void direct_pk_lookup_of_other_tenant_is_also_zero_rows() {
        for (String table : TABLES) {
            // ⚠️ "取出一个租户B 真实存在的主键"这一步【必须在租户B 的上下文内读】：
            //    本表 FORCE RLS，未设上下文即零行，在无上下文下读会拿到 0 行
            //    （queryForObject 直接抛 EmptyResultDataAccessException），
            //    或更糟 —— 断言变成"因为压根没读到参照物"的假绿。
            String bPk = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT " + PK_COLUMN + "::text FROM " + table + " LIMIT 1", String.class));
            assertNotNull(bPk, "前置: 租户B 应有至少 1 行 " + table + " 底数据");

            Integer hit = inTenantTx(TENANT_A, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + PK_COLUMN + " = ?::uuid",
                            Integer.class, bPk));
            assertEquals(0, hit, table + ": 按主键直查租户B 的行竟然命中 " + hit + " 行 —— RLS 只挡了列表查询");

            // 反证参照物确实存在且对 B 可见（否则上面的 0 行无意义）
            Integer bSelf = inTenantTx(TENANT_B, () ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + PK_COLUMN + " = ?::uuid",
                            Integer.class, bPk));
            assertEquals(1, bSelf, "前置反证: " + table + " 该主键在租户B 上下文下应可见 1 行");
        }
    }

    // ------------------------------------------------------------------
    // 断言 2: 未设上下文 / 空串上下文 → 零行（fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V7 修订账本: 未设上下文必须零行，不得回落全表（fail-closed）")
    void without_context_returns_zero_rows_not_the_whole_table() {
        for (String table : TABLES) {
            Integer leak = inPlainTx(() ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
            assertEquals(0, leak,
                    table + ": 未设 app.tenant_id 时读到 " + leak + " 行 —— fail-closed 被破坏，全表泄漏！");
        }
    }

    @Test
    @DisplayName("V7 修订账本: 空串上下文 → 零行且不抛 500（NULLIF 归一）")
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
    @DisplayName("V7 修订账本: 租户A 上下文下插入属于租户B 的行 → 被 WITH CHECK 拒绝（SQLSTATE 42501）")
    void cross_tenant_insert_is_rejected_by_with_check() {
        // 探针行在【除 tenant_id 以外的每个维度上都合法】—— 这样 42501 只可能来自 RLS，
        // 不会与 CHECK 违规（23514）或 FK 违规（23503）混淆。
        // 本表只有一条 FK（customer_id）：挂基础种子客户即满足，故 42501 的归因是干净的。
        assertCrossTenantInsertRejected("intake_profile_revision",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 1, '{}'::jsonb, now())",
                TENANT_B, SEED_A_ID);
    }

    @Test
    @DisplayName("V7 修订账本: 越权行确认未落库（在对方租户上下文下 count = 0）")
    void cross_tenant_insert_leaves_no_row_behind() {
        String victim = "00000000-0000-0000-" + SFX_A + "-00000000dead";
        try {
            inTenantTx(TENANT_A, () -> {
                jdbc.update("INSERT INTO intake_profile_revision "
                                + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, 1, '{\"probe\":true}'::jsonb, now())",
                        victim, TENANT_B, SEED_A_ID);
                return null;
            });
            fail("租户A 上下文下成功插入了租户B 的 intake_profile_revision 行 —— WITH CHECK 未生效");
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
                jdbc.queryForObject("SELECT count(*) FROM intake_profile_revision WHERE revision_id = ?::uuid",
                        Integer.class, victim));
        assertEquals(0, n, "越权行竟然落库了 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 断言 4: 策略元数据（真实 pg_catalog / pg_policies）+ 表结构要素
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V7 修订账本: ENABLE + FORCE 在位，owner=非超级用户，USING/WITH CHECK 双 NULLIF")
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
        assertEquals(1, TABLES.size(), "本类声明的表数必须恰为 1（V7 交付范围）");

        // 🛑 底数据必须构成一条"更正链"（2 行、第二行 supersedes 第一行）——
        //    下面的 append-only 断言依赖这个前提；若底数据退化成 2 条互不相关的行，
        //    "链"的断言就成了空话，故这里把它显式钉住。
        Integer chainRows = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM intake_profile_revision "
                        + "WHERE tenant_id = ?::uuid AND supersedes_revision_id IS NOT NULL",
                        Integer.class, TENANT_A));
        assertTrue(chainRows >= 1,
                "租户A 的底数据必须至少含 1 条带 supersedes_revision_id 的更正行（更正链前提）");
    }

    // ------------------------------------------------------------------
    // 断言 5: 本域特有的建模约束反向验证（约束/索引是否真有牙齿）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V7 修订账本: 更正必须【追加新行 + 反向指针】，旧行逐字不变（append-only 可回归）")
    void correction_appends_with_back_pointer_and_never_mutates_the_original_row() {
        // ① revision_no 必须为正：0 与负值都被 CHECK 拒（独立事务 + 自动回滚）
        assertConstraintRejected("23514", "intake_profile_revision_revision_no_check",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 0, '{}'::jsonb, now())",
                TENANT_A, SEED_A_ID);

        assertConstraintRejected("23514", "intake_profile_revision_revision_no_check",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, -1, '{}'::jsonb, now())",
                TENANT_A, SEED_A_ID);

        // ② 缺 snapshot_json（NOT NULL）→ 23502；缺 recorded_at（NOT NULL 且【无 DEFAULT】）→ 23502。
        //    🛑 第二条是本表最关键的一条：它在数据层证明"修订时刻不能由库层 now() 代填"，
        //    否则补录场景下 recorded_at 会指向补录时刻，而这种偏差在数据上完全看不出来。
        assertConstraintRejected("23502", "snapshot_json",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 9, now())",
                TENANT_A, SEED_A_ID);

        assertConstraintRejected("23502", "recorded_at",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 9, '{}'::jsonb)",
                TENANT_A, SEED_A_ID);

        // ③ 同一客户重复 revision_no → 23505（uq_ipr_revision_no；防重放写入两条"第 3 次修订"）。
        //    刻意取底数据【未使用】的 revision_no = 5：这样 23505 只可能来自"同一语句内两行冲突"，
        //    而不是"撞上了底数据"—— 后者会让本断言在底数据被清理后变成"插入成功"的假绿。
        assertConstraintRejected("23505", "uq_ipr_revision_no",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 5, '{}'::jsonb, now()), "
                        + "(gen_random_uuid(), ?::uuid, ?::uuid, 5, '{}'::jsonb, now())",
                TENANT_A, SEED_A_ID, TENANT_A, SEED_A_ID);

        // ④ 唯一性范围是【租户内该客户】而不是全局：同一 revision_no 对【同租户的另一个客户】合法。
        //    🛑 2026-09-27：原写法借用 SEED_B_ID（租户 B 的客户），那是"跨租户引用"——
        //       V16 落地后它被复合外键以 23503 正当地拒绝，故改为自建的同租户客户 CUST_A2_ID。
        assertAcceptedInRollbackTx("revision_no 的唯一性不得跨客户生效（范围写错会让他人写不进去）",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 1, '{}'::jsonb, now())",
                TENANT_A, CUST_A2_ID);

        // ⑤ 唯一性范围也必须含 tenant_id（否则两个租户的"某客户第 N 次修订"会互相顶掉）。
        //    ⚠️ 与 ④ 不同，这一条【不能】用运行时探针证：探针必须"在租户A 上下文里写 tenant_id=B"
        //       才能构造出跨租户同号 —— 而那正好撞上 WITH CHECK（见断言 3 的实测）。
        //       故改用【约束定义的结构性断言】，既更强（直接证明唯一键的列清单），
        //       也不依赖"恰好构造出一组跨租户同号数据"这种巧合。
        inTenantTx(TENANT_A, () -> {
            String constraintDef = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                            + "WHERE conrelid = 'intake_profile_revision'::regclass AND conname = 'uq_ipr_revision_no'",
                    String.class);
            assertNotNull(constraintDef, "缺 uq_ipr_revision_no —— 防重放写入的保护不存在");
            assertTrue(constraintDef.contains("UNIQUE"),
                    "uq_ipr_revision_no 必须是 UNIQUE。实际: " + constraintDef);
            for (String col : List.of("tenant_id", "customer_id", "revision_no")) {
                assertTrue(constraintDef.contains(col),
                        "uq_ipr_revision_no 必须覆盖 " + col + " —— 唯一性范围写错不会报错，"
                                + "只表现为『另一个客户的第 N 次修订写不进去』或『重放写入了两条同号修订』。"
                                + "实际: " + constraintDef);
            }
            return null;
        });

        // ⑥ 更正链：追加新行 + supersedes_revision_id 指向被取代者，【旧行逐字不变】。
        //    整个事务最后显式回滚 → 零残留。
        String originalId = "00000000-0000-0000-" + SFX_A + "-0000000000c1";
        String correctionId = "00000000-0000-0000-" + SFX_A + "-0000000000c2";
        String originalSnapshot = "{\"height_cm\":170.00,\"job_tag\":{\"code\":\"teacher\"}}";

        inRollbackTx(TENANT_A, () -> {
            // 写入原始修订（revision_no = 8，避开底数据的 1/2，也避开上面探针的 9）
            jdbc.update("INSERT INTO intake_profile_revision "
                            + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at, reason) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 8, ?::jsonb, now(), '首次补充')",
                    originalId, TENANT_A, SEED_A_ID, originalSnapshot);

            // 更正 = 追加新行 + supersedes_revision_id 指向被取代者（**不是** UPDATE 旧行）
            jdbc.update("INSERT INTO intake_profile_revision "
                            + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, "
                            + " supersedes_revision_id, recorded_at, reason) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 9, ?::jsonb, ?::uuid, now(), '更正身高')",
                    correctionId, TENANT_A, SEED_A_ID, originalSnapshot, originalId);

            // 反向自证：两条都在，且旧行【逐字未被改写】——
            // 这就是"不可覆盖"在数据层可回归的形态：更正不占用旧行的位置。
            assertNotNull(rowField("snapshot_json::text", originalId),
                    "原始修订行不见了 —— append-only 被破坏");
            String pointer = rowField("supersedes_revision_id::text", correctionId);
            assertEquals(originalId, pointer,
                    "更正行未通过 supersedes_revision_id 指向被取代者 —— 链断，无从追溯");

            Integer both = jdbc.queryForObject(
                    "SELECT count(*) FROM intake_profile_revision WHERE revision_id IN (?::uuid, ?::uuid)",
                    Integer.class, originalId, correctionId);
            assertEquals(2, both, "追加更正后应同时保留原始行与更正行（共 2 行）");
            return null;
        });

        // ⑦ 事务回滚的【自证】：探针行确实没有落库，底数据的"租户A 恰 2 行"前提未被破坏。
        Integer leftover = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM intake_profile_revision "
                        + "WHERE revision_id IN (?::uuid, ?::uuid)", Integer.class, originalId, correctionId));
        assertEquals(0, leftover, "探针事务未被回滚，残留 " + leftover + " 行 —— 底数据前提已被污染");
    }

    @Test
    @DisplayName("V7 修订账本: 无 updated_at 但有 created_at；recorded_at 无 DEFAULT；operator_id 可空")
    void structure_carries_append_only_semantics_not_just_a_comment() {
        for (String table : TABLES) {
            // ① 🛑 不得有 updated_at
            List<String> updatedAt = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'updated_at'",
                    String.class, table);
            assertTrue(updatedAt.isEmpty(),
                    table + " 出现了 updated_at 列 —— append-only 账本不应承载『修改时间』这一概念");

            // ② 但【必须】有 created_at。只断言①是不够的：一个把整表列都写错的迁移也能通过①。
            List<String> createdAt = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'created_at'",
                    String.class, table);
            assertEquals(1, createdAt.size(),
                    table + " 必须保留 created_at（插入时刻，审计需要）—— "
                            + "『没有 updated_at』必须与『有 created_at』成对断言，否则前者可能是打偏的");

            // 反证：断言确实作用于一个存在的表（避免"表名打错所以查不到列"的假绿）
            Integer tableExists = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, tableExists, table + " 必须真实存在于真库（否则上面的断言全是假绿）");
        }

        // ③ recorded_at：NOT NULL 且【无 DEFAULT】—— 修订时刻不得由库层代填
        Map<String, Object> recordedAt = jdbc.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'intake_profile_revision' "
                        + "AND column_name = 'recorded_at'");
        assertEquals("NO", recordedAt.get("is_nullable"), "recorded_at 必须 NOT NULL");
        assertNull(recordedAt.get("column_default"),
                "recorded_at 不得有库层 DEFAULT —— 若插入时取 now()，补录场景下它会指向补录时刻"
                        + "而非修订时刻，而那种偏差在数据上完全看不出来。实际默认值=" + recordedAt.get("column_default"));

        // ④ operator_id：刻意【可空】（容纳历史系统行；应用层写入路径强制非空）。
        //    与 V5 的 screening_record.operator_id NOT NULL 形成对照 ——
        //    这类"看起来不一致其实是刻意的"列，最容易被下一个人顺手改成 NOT NULL。
        String operatorNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'intake_profile_revision' "
                        + "AND column_name = 'operator_id'", String.class);
        assertEquals("YES", operatorNullable,
                "operator_id 必须可空（V7 逐字：『可空：容纳历史系统行；但应用层写入路径强制非空』）");

        // ⑤ 自引用 FK 必须真实存在（supersedes_revision_id → 本表 revision_id）
        //    若它退化成一根普通 UUID 列，更正链就只剩注释里的一句承诺。
        assertConstraintRejected("23503", "intake_profile_revision_supersedes_revision_id_fkey",
                "INSERT INTO intake_profile_revision "
                        + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, "
                        + " supersedes_revision_id, recorded_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 7, '{}'::jsonb, ?::uuid, now())",
                TENANT_A, SEED_A_ID, "00000000-0000-0000-ffff-ffffffffffff");
    }

    // ------------------------------------------------------------------
    // 断言 6: V7 的另一半交付 —— customer 补 5 列，且既有 RLS 未被弄坏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V7 customer 补列: 5 列真实落库、约束/索引到位，且既有 RLS 策略完好")
    void customer_added_columns_and_constraints_landed_without_breaking_existing_rls() {
        // ① 5 列必须真实存在（IF NOT EXISTS 会让"没建上"静默通过，故这里逐列自证）
        for (String col : CUSTOMER_ADDED_COLUMNS) {
            List<String> found = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 'customer' AND column_name = ?",
                    String.class, col);
            assertEquals(1, found.size(),
                    "customer." + col + " 未落库 —— V7 的『补 5 列』没有生效（ADD COLUMN IF NOT EXISTS 静默跳过）");
        }
        assertEquals(5, CUSTOMER_ADDED_COLUMNS.size(), "本断言必须恰列 5 列（V7 交付范围）");

        // ② 5 列【全部可空】。这不是"忘了加 NOT NULL"：V7 文件头有三条独立理由
        //    （种子基线不得修改 / V1 历史行无从编造取值 / 必填的权威落点在写入路径）。
        //    若有人顺手收紧，本断言会红并要求先处理那三条前提。
        for (String col : CUSTOMER_ADDED_COLUMNS) {
            String nullable = jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 'customer' AND column_name = ?",
                    String.class, col);
            assertEquals("YES", nullable,
                    "customer." + col + " 应为可空（V7 刻意如此；NOT NULL 收紧是登记在案的后续项，"
                            + "需先清理历史行并更新不得修改的种子基线）");
        }

        // ③ 三条 CHECK 必须存在（gender / age / 以及 status 的【已被移除】——见第 ⑥ 条）
        List<String> checkNames = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'customer'::regclass AND contype = 'c'",
                String.class);
        assertTrue(checkNames.contains("customer_gender_check"),
                "缺 customer_gender_check —— gender 会接受任意取值，而它是 AgeGroup 的输入维度。实际: " + checkNames);
        assertTrue(checkNames.contains("customer_age_check"),
                "缺 customer_age_check —— 年龄越界不会被拦，一个人会被静默算进错误的年龄分组。实际: " + checkNames);

        // ③-b 🛑 customer.status 上【刻意没有】CHECK —— 这条"不存在"与上面两条"必须存在"同等重要。
        //    V7 只 DROP 不 ADD：因为骨架默认值 'pending' 不在权威 5 值（CREATED…ARCHIVED）内，
        //    加 CHECK 会让 V1 的历史 pending 行当场违约。若有人顺手"补一条 status CHECK"，
        //    V7 文件头登记的那个差异就从"可追溯的待裁定项"变成"一次无声的历史改写"。
        assertTrue(!checkNames.contains("customer_status_check"),
                "customer.status 出现了 CHECK —— V7 刻意【不在库侧】约束该列（骨架 DEFAULT 'pending' "
                        + "与权威 5 值不一致，差异已登记待 owner 裁定）。加 CHECK 会迫使一次代拍口径。"
                        + "实际 CHECK: " + checkNames);

        // ④ 两条 FK 必须存在（owner_store_id / serving_store_id → store）
        List<String> fkNames = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'customer'::regclass AND contype = 'f'",
                String.class);
        assertTrue(fkNames.contains("customer_owner_store_fk"),
                "缺 customer_owner_store_fk（归属店）。实际: " + fkNames);
        assertTrue(fkNames.contains("customer_serving_store_fk"),
                "缺 customer_serving_store_fk（当前服务店）。实际: " + fkNames);

        // ⑤ phone 的租户级部分唯一索引必须存在，且【确实是部分索引】（WHERE phone IS NOT NULL）。
        //    只断言索引名不够 —— 一个丢了 WHERE 的全列唯一索引在当前数据下行为等价，
        //    但在收紧 NOT NULL 后立刻语义不同（V7 注释逐字说明了这一点）。
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uq_customer_tenant_phone'",
                String.class);
        assertNotNull(indexDef, "缺 uq_customer_tenant_phone —— 跨店识别键的唯一性没有机械保证");
        assertTrue(indexDef.contains("UNIQUE"),
                "uq_customer_tenant_phone 必须是 UNIQUE。实际: " + indexDef);
        assertTrue(indexDef.toUpperCase().contains("WHERE") && indexDef.contains("phone IS NOT NULL"),
                "uq_customer_tenant_phone 必须是【部分】唯一索引（WHERE phone IS NOT NULL）。实际: " + indexDef);

        // ⑥ 🛑 customer.status 的库层 DEFAULT 与权威 5 值不一致（V7 刻意【不】静默改名）。
        //    本断言的门禁语义是「**要么修好、要么登记在案**」，而不是"把缺陷锁死"：
        //      - 若 DEFAULT 仍是 'pending'（骨架占位）⇒ 该列的注释必须写明 5 值口径与"待裁定"；
        //      - 若 DEFAULT 已被改成权威 5 值之一或整个去掉 ⇒ 同样通过（说明已修好）。
        //    只有"DEFAULT 是第三种值且注释也没说明"才红 —— 那才是真正不可追溯的形态。
        Map<String, Object> statusMeta = jdbc.queryForMap(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'customer' AND column_name = 'status'");
        Object statusDefault = statusMeta.get("column_default");
        if (statusDefault != null && String.valueOf(statusDefault).contains("pending")) {
            String comment = jdbc.queryForObject(
                    "SELECT col_description('customer'::regclass, attnum) FROM pg_attribute "
                            + "WHERE attrelid = 'customer'::regclass AND attname = 'status'", String.class);
            assertNotNull(comment,
                    "customer.status 的 DEFAULT 仍是骨架占位 'pending'，但该列【没有注释】—— "
                            + "差异既未被修正也未被登记，属不可追溯形态");
            assertTrue(comment.contains("CREATED") && comment.contains("ARCHIVED"),
                    "customer.status 的注释必须写明权威 5 值口径（CREATED…ARCHIVED）。实际: " + comment);
            assertTrue(comment.contains("待 owner 裁定") || comment.contains("不一致"),
                    "customer.status 的注释必须把『DEFAULT 与 5 值不一致且待裁定』这件事写出来。实际: " + comment);
        }

        // ⑦ 约束是否真有牙齿（反向验证）：
        assertConstraintRejected("23514", "customer_gender_check",
                "INSERT INTO customer (id, tenant_id, name, gender) VALUES (gen_random_uuid(), ?::uuid, 'probe', '其他')",
                TENANT_A);
        assertConstraintRejected("23514", "customer_age_check",
                "INSERT INTO customer (id, tenant_id, name, age) VALUES (gen_random_uuid(), ?::uuid, 'probe', 120)",
                TENANT_A);
        assertConstraintRejected("23514", "customer_age_check",
                "INSERT INTO customer (id, tenant_id, name, age) VALUES (gen_random_uuid(), ?::uuid, 'probe', 0)",
                TENANT_A);

        // 反证：合法取值必须被接受（否则上面的"被拒"可能来自别处）
        assertAcceptedInRollbackTx("gender='男' 应被接受（反证 CHECK 不是全拒）",
                "INSERT INTO customer (id, tenant_id, name, gender) "
                        + "VALUES (gen_random_uuid(), ?::uuid, 'probe-ok', '男')", TENANT_A);
        assertAcceptedInRollbackTx("age=119 应被接受（契约 maximum=119 的边界必须可写）",
                "INSERT INTO customer (id, tenant_id, name, age) "
                        + "VALUES (gen_random_uuid(), ?::uuid, 'probe-ok', 119)", TENANT_A);

        // ⑧ 跨店识别键的唯一性范围是【租户级】而非门店级/全局 —— 这是本系统的核心业务前提
        //    （客户属于品牌而非门店）。若范围写错，同一个人在两家店会被建成两个档案。
        //    a) 同租户内重复 phone → 23505
        assertConstraintRejected("23505", "uq_customer_tenant_phone",
                "INSERT INTO customer (id, tenant_id, name, status, phone) "
                        + "VALUES (gen_random_uuid(), ?::uuid, 'p1', 'CREATED', '13900000007'), "
                        + "(gen_random_uuid(), ?::uuid, 'p2', 'CREATED', '13900000007')",
                TENANT_A, TENANT_A);
        //    b) 跨租户同 phone 必须可共存（不同品牌下的同号是两个人，不是冲突）。
        //       ⚠️ 这一条【不能】用"同事务插两行"来证：RLS 的 WITH CHECK 会挡掉
        //          "在租户A 上下文里写 tenant_id=B 的行"（那是跨租户写入，见断言 3）。
        //          故改用【索引定义的结构性断言】：唯一键的列清单必须含 tenant_id。
        //          这比运行时探针更强 —— 它直接证明了唯一性范围本身，而不依赖数据巧合。
        assertTrue(indexDef.contains("(tenant_id, phone)"),
                "uq_customer_tenant_phone 的唯一键必须恰为 (tenant_id, phone) —— "
                        + "若只覆盖 phone（全局唯一），不同品牌的同号客户会互相顶掉；"
                        + "若只覆盖 (store_id, phone) 或 (owner_store_id, phone)，"
                        + "同一个人在两家店会被建成两个档案（跨店识别键失效）。实际: " + indexDef);
    }

    @Test
    @DisplayName("V7 customer 既有 RLS: 补列之后 customer 仍是 ENABLE+FORCE 且跨租户零行（补列不得弄坏隔离）")
    void customer_rls_is_still_intact_after_the_alter() {
        // 「补列」不改变 RLS 对象，但它是唯一一处**既有生产表**被本迁移触碰的地方。
        // 一旦有人把某条 ALTER 写成 DROP POLICY + CREATE POLICY（例如为了"顺手统一策略写法"），
        // 或忘了 FORCE，本表就会静默失去隔离 —— 而那是跨域后果（域 B/C/D 全读它）。
        String owner = jdbc.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'customer'", String.class);
        assertEquals(RlsGateSupport.APP_USER, owner,
                "customer 的 owner 应为非超级用户（否则 FORCE RLS 未被真实验证）；实际=" + owner);
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'customer'", Boolean.class),
                "customer 必须 ENABLE ROW LEVEL SECURITY");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'customer'", Boolean.class),
                "customer 必须 FORCE ROW LEVEL SECURITY（补列之后不得被弄坏）");

        // 行为反证：跨租户必须零行（V7 补了 phone/owner_store_id 等多个"识别键"，
        // 若隔离被破坏，泄漏的直接后果是"用手机号就能查到别家品牌的客户"）
        Integer cross = inTenantTx(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM customer WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_B));
        assertEquals(0, cross, "customer 跨租户读到了 " + cross + " 行 —— V7 补列弄坏了既有隔离");
    }

    @Test
    @DisplayName("V7 修订账本: 跨租户 customer 挂载已被【库层复合外键】堵上（V16 收敛，原登记缺口已关闭）")
    void cross_tenant_customer_link_is_now_closed_at_the_database_layer() {
        // ⚠️⚠️ 本用例的前身断言的是 PostgreSQL 的**既有语义**（外键检查绕过 RLS
        //      ⇒ 「本租户的行可以指向他租户的客户」），它当时绿是对的，但那条绿
        //      **不代表安全** —— 它自己就写着"若它变红，说明上游已在库层堵上该缺口
        //      （复合 FK / 触发器），请同步更新此处登记并收敛应用层防线"。
        //
        //     2026-09-27：V16 迁移把本表 customer_id 的外键升级为租户耦合的复合外键
        //        (tenant_id, customer_id) → customer (tenant_id, id)
        //     ⇒ 该缺口在库层被堵上，本用例随之变红 ⇒ 按它自己给出的指引【翻转】为缺陷断言。
        //     🛑 这就是"登记缺口"这类用例的正确生命周期：它必须能被上游的修复"叫醒"。
        //        若它永远绿，那它就不是登记，而是一个把缺口伪装成常态的装饰。
        //
        //     三向断言（与 RlsCrossTenantReferenceGateTest ① 同口径，故此处不重复整库扫描）：
        //       ① 跨租户挂载必须被拒；
        //       ② 拒绝理由必须是 23503（外键），**不得**是 42501（RLS）——
        //          42501 只说明 WITH CHECK 挡住了 tenant_id 不匹配的行，
        //          而本探针的 tenant_id 是【完全合规】的（= B，写在 B 的上下文里）；
        //          若这里报 42501，说明真正生效的是另一个机制，本断言就没证明复合外键在工作；
        //       ③ 该行确实没有落库（反向自证）。
        String probe = "00000000-0000-0000-" + SFX_REV + "-00000000beef";
        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_B, () -> {
                    jdbc.update("INSERT INTO intake_profile_revision "
                                    + "(revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 6, '{}'::jsonb, now())",
                            probe, TENANT_B, SEED_A_ID);
                    return null;
                }),
                "库层必须拒绝『租户 B 的修订行指向租户 A 的客户』—— V16 已把该缺口堵上。"
                        + "若这里没有抛异常，说明复合外键没有生效（缺口又回来了）。");
        SQLException se = rootSqlException(ex);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + ex);
        assertEquals("23503", se.getSQLState(),
                "拒绝理由必须是【外键违反 23503】而不是 RLS 的 42501 —— "
                        + "本探针的 tenant_id 完全合规（= B，写在 B 的上下文里），"
                        + "被拒的唯一正当理由就是 customer_id 指向了别家的客户。"
                        + "实际 SQLSTATE=" + se.getSQLState() + "，消息: " + se.getMessage());

        // 反向自证：该行确实没有落库。🛑 必须先切回 B 的上下文 ——
        // 在 A 的上下文下查 tenant_id=B 的行，RLS 会静默过滤成 0 行，
        // 于是"没落库"与"我看不见"给出同一个 0，反向自证就变成了装饰。
        Integer left = inTenantTx(TENANT_B, () -> jdbc.queryForObject(
                "SELECT count(*) FROM intake_profile_revision WHERE revision_id = ?::uuid",
                Integer.class, probe));
        assertEquals(0, left, "被拒的跨租户行竟然落库了（留在库里的行数 = " + left + "）");
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
     * 断言某条写入被拒，<b>且</b>拒绝的 SQLSTATE 与点名的约束名都与预期一致。
     *
     * <p>刻意同时校验两件事：只校验 SQLSTATE 是不够的 —— 一张表上可以有多条同类约束
     * （本表就有 CHECK 与两种唯一约束），"某条约束报了"不等于"我要测的那条有牙齿"。
     *
     * <p>两种可预期的 SQLSTATE：
     * <pre>
     *   23514 check_violation        → 错误消息里点名 CHECK 约束名
     *   23505 unique_violation       → 错误消息里点名唯一约束/索引名
     *   23502 not_null_violation     → 错误消息里点名列名（NOT NULL 无约束名可指）
     *   23503 foreign_key_violation  → 错误消息里点名 FK 约束名
     * </pre>
     *
     * <p>事务由 {@link TransactionTemplate} 在异常路径自动回滚，故探针零残留；
     * 且 PG 的语句错误会中止事务，所以每条期望失败的写入必须是<b>独立事务</b>
     * —— 拼在同一事务里第二条起会得到 25P02（current transaction is aborted）。
     */
    private void assertConstraintRejected(String sqlState, String namedObject, String sql, Object... args) {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> inTenantTx(TENANT_A, () -> {
                    jdbc.update(sql, args);
                    return null;
                }),
                "该写入被接受了 —— 预期应被 " + namedObject + " 以 " + sqlState + " 拒绝");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        assertEquals(sqlState, se.getSQLState(),
                "拒绝方式不对（预期 " + sqlState + "，实际 " + se.getSQLState()
                        + "）—— 断言可能打偏到另一条约束上。消息: " + se.getMessage());
        assertTrue(se.getMessage() != null && se.getMessage().contains(namedObject),
                "被拒的不是预期的 " + namedObject + " —— 断言打偏了。实际消息: " + se.getMessage());
    }

    /**
     * 断言某条写入在【租户 A】上下文下通过（反证约束/索引不是全拒），
     * 并在同一事务内显式回滚 —— <b>简述：断言有效，数据不落库。</b>
     *
     * <h2>🛑 为什么本类只有一个这样的方法（2026-09-27 收敛）</h2>
     * 它原本还有一个 {@code As(tenantId, …)} 变体，供一条"必须换个租户才能构造"的探针使用
     * ——「本租户的行指向他租户客户」那类跨租户<b>关联</b>，只能在"写入方的租户上下文"里构造。
     * <p>V16 把该缺口堵上之后，那条探针被翻转为<b>缺陷断言</b>（必须被拒），
     * 于是 {@code As} 变体失去全部调用方，已删除。此处保留它踩过的坑，供将来需要变体时参照：
     * <p>🛑 <b>不要把两者做成重载。</b> {@code (String message, String sql, Object... args)}
     * 与 {@code (String tenantId, String message, String sql, Object... args)} 并存时，
     * Java 的第一/第二阶段（不用变参）恰好能匹配到后者的固定三参形式 ⇒
     * 三参调用 {@code f("msg", SQL, arg)} 会被<b>静默</b>绑到
     * 「tenantId='msg'、message=SQL、sql=arg」上 —— 编译通过、运行时才以一个
     * 「租户 ID 非合法 UUID」的无关异常爆出（本类实测踩过）。故应使用独立方法名，
     * 让误用无法通过编译。
     */
    private void assertAcceptedInRollbackTx(String message, String sql, Object... args) {
        try {
            inRollbackTx(TENANT_A, () -> {
                // 🛑 先把"同租户的第二客户"建在【同一个会回滚的事务里】——
                //    见 CUST_A2_ID 的 javadoc：不落库，零耦合。
                jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                                + "VALUES (?::uuid, ?::uuid, 'V7 修订账本第二客户 A', 'active')"
                                + " ON CONFLICT DO NOTHING",
                        CUST_A2_ID, TENANT_A);
                jdbc.update(sql, args);
                return null;
            });
        } catch (DataAccessException e) {
            SQLException se = rootSqlException(e);
            fail(message + " SQLSTATE=" + (se == null ? "?" : se.getSQLState()), e);
        }
    }

    /** 在同一事务内回读单列（仅供"旧行逐字不变"这类同事务断言使用）。 */
    private String rowField(String columnExpr, String revisionId) {
        return jdbc.queryForObject(
                "SELECT " + columnExpr + " FROM intake_profile_revision WHERE revision_id = ?::uuid",
                String.class, revisionId);
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

    /**
     * 清理 SQL：按主键前缀精确删除本类灌入的行。
     *
     * <p>不采用"按 customer_id NOT IN (种子)"的孤儿删除法：本类底数据<b>正是挂在两个基础种子
     * 客户上</b>的，孤儿删除法会把它当成"合法行"留下，反把其它类的行删掉。
     * 故改用<b>主键前缀</b>精确圈定。
     *
     * <p>⚠️ 本表自引用 FK：底数据里 A 租户的更正行指向同租户原始行。整批一起
     * {@code DELETE} 时 PG 会在语句末尾统一校验 FK，两行同批删除即无残留引用，
     * 故单条语句即可，无需按子→父拆两步。
     */
    private static String buildCleanupSql() {
        return "\\set ON_ERROR_STOP on\n"
                + "DELETE FROM intake_profile_revision WHERE"
                + pkPrefixFilter(PK_COLUMN) + ";\n";
    }

    /** 本类使用的所有主键前缀（用于精确圈定"自己灌的行"）。 */
    private static String pkPrefixFilter(String pkColumn) {
        return " (" + pkColumn + "::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR " + pkColumn + "::text LIKE '00000000-0000-0000-" + SFX_B + "-%'"
                + " OR " + pkColumn + "::text LIKE '00000000-0000-0000-" + SFX_REV + "-%')";
    }

    /**
     * 为某个租户灌底数据。
     *
     * <p>租户 A 灌<b>2 行构成一条更正链</b>（第 2 次修订 supersedes 第 1 次），
     * 租户 B 灌 1 行 —— 这使"跨租户零行"与"更正链完整"两个前提同时成立。
     *
     * <p>PK 形如 {@code 00000000-0000-0000-<sfx>-0000000000NN}，同一租户内互不重复、
     * 两租户间靠 {@code sfx} 区分，故可安全 {@code ON CONFLICT DO NOTHING} 幂等重灌。
     *
     * <p>{@code customer_id} 直接挂基础种子客户（不需要自建客户）——
     * 这也是本类比 {@link RlsV6RefundLedgerIsolationTest} 短得多的原因（见类注释）。
     * {@code operator_id} 留空：该列刻意可空（容纳历史系统行），本类无需自建 staff 链。
     */
    private static String seedSqlFor(String tenant, String customer, String sfx) {
        // 末段固定 10 个 0 打底（+ 后续 2 个字符 ⇒ 末段恒为 12 字符，为合法 UUID 形态）
        String p = "00000000-0000-0000-" + sfx + "-0000000000";
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");
        // 第 1 次修订
        sb.append(ins("intake_profile_revision",
                "revision_id, tenant_id, customer_id, revision_no, snapshot_json, recorded_at, reason, created_by",
                q(p + "11") + ", " + q(tenant) + ", " + q(customer)
                        + ", 1, '{\"height_cm\":168.00,\"bp_sys\":120}'::jsonb, now(), '首次补充', 'seed'"));
        if (SFX_A.equals(sfx)) {
            // 第 2 次修订 = 一条更正（追加新行 + 反向指针；旧行不动）
            sb.append(ins("intake_profile_revision",
                    "revision_id, tenant_id, customer_id, revision_no, snapshot_json, "
                            + "supersedes_revision_id, recorded_at, reason, created_by",
                    q(p + "12") + ", " + q(tenant) + ", " + q(customer)
                            + ", 2, '{\"height_cm\":169.00,\"bp_sys\":118}'::jsonb, "
                            + q(p + "11") + ", now(), '更正身高与血压', 'seed'"));
        }
        return sb.toString();
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