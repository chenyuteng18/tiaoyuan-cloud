package com.diaoyuanyun.dy.app.rls;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>跨租户引用完整性门禁（V16）</b> —— 把「RLS 保护行归属，但不保护引用归属」
 * 这条缺口的修复钉成<b>真实 PostgreSQL</b> 上的可判定事实。
 *
 * <h2>它守的是什么（实测缺口，不是推测）</h2>
 * 本骨架的租户隔离由 RLS 承担，策略形如
 * {@code tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid}。
 * <b>它保护的是「本行的归属」</b>（你写的每一行 tenant_id 必须等于你的上下文），
 * <b>却完全不保护「本行引用的对象归谁」</b>。2026-09-27 实测（应用角色、非超级用户）：
 * <pre>
 *   B 上下文里 A 的 store / customer / band 都【看不见】（各 0 行 —— 证明 RLS 生效）
 *   但 INSERT INTO band (tenant_id=B, customer_id=&lt;A的客户&gt;) 【成功】（INSERT 0 1）
 * </pre>
 * 成因：外键检查在 PG 内以<b>表所有者</b>身份执行，绕过 RLS。
 * 规模：源表与目标表<b>都</b> FORCE RLS、且外键只引用单列的外键 —— <b>47 处</b>，
 * 涉及 12 张被引用表，覆盖全部 30 张 FORCE RLS 源表。
 *
 * <h2>本类的分工（与邻居不重复）</h2>
 * <table border="1">
 *   <caption>职责边界</caption>
 *   <tr><th>测试类</th><th>回答的问题</th></tr>
 *   <tr><td>{@code RlsTenantIsolationTest} / {@code RlsV5…}</td>
 *       <td><b>行</b>的隔离：跨租户读写是否零行 / 被拒</td></tr>
 *   <tr><td><b>本类</b></td>
 *       <td><b>引用</b>的隔离：一行能不能指向别家的行 —— 这是 RLS <b>从不</b>回答的问题</td></tr>
 * </table>
 * 🛑 这两件事必须分开守：V16 之前，前者的全部套件<b>都是绿的</b>，
 * 而缺口一直敞着。只守"行"的套件对"引用"这件事<b>完全沉默</b>。
 *
 * <h2>🛑 本类最重要的一条：拒绝的【理由】必须是外键违反(23503)，而不是 RLS(42501)</h2>
 * 这是本类与"随便断言一个异常就完事"的分水岭。
 * 在 B 上下文里让 {@code band.customer_id} 指向 A 的客户时：
 * <ul>
 *   <li>这一行的 {@code tenant_id = B}，<b>完全符合</b> RLS 策略 ⇒ <b>RLS 会放行它</b>；</li>
 *   <li>拒绝它的是【外键】(V16 的复合外键要求目标行的 tenant_id 也等于 B) ⇒ {@code 23503}。</li>
 * </ul>
 * 若实现退化、断言只检查"抛了异常"，则一条把 {@code band} 表 RLS 策略写错的实现
 * 也能让用例变绿（它会以 42501 拒绝）—— 那是<b>假绿</b>：缺口还在，只是被另一个原因挡住。
 * 故本类断言到 SQLSTATE，并额外给出同租户必须成功的对照。
 *
 * <h2>门禁自证（本仓纪律：注入的错误必须被这条门禁抓住）</h2>
 * 只断言"数量是 0 / 47"是不够的：一个写坏的查询同样返回 0。
 * 故本类对每条计数断言都配一个<b>判别力对照</b>（取掉判据的某个条件后必须数出【不同的】数）：
 * <ul>
 *   <li>余量：加 RLS 过滤 = <b>0</b>，不加过滤 = <b>83</b> —— 两者必须不等；</li>
 *   <li>复合数：三条件 = <b>47</b>，去掉"目标主键单列" = <b>48</b> —— 两者必须不等。</li>
 * </ul>
 * 另有 {@link #injecting_a_single_column_fk_makes_the_gate_red()} ——
 * 在事务里把一处复合外键改回单列，断言上述判据<b>立刻变红</b>，随后整体回滚。
 * 这是"这条门禁有牙齿"的直接证据，而不是一句声明。
 *
 * <h2>为什么必须打真库</h2>
 * 两条核心断言（跨租户被拒、同租户放行）都是 PostgreSQL <b>执行器</b>的行为，
 * 不是应用层 if。用 H2 / mock 测出来的结论与生产无关（ADR-01 的既有口径）。
 * 故本类复用 {@link RlsGateSupport}（同一套 provision + 构建级互斥锁 + 非超级用户连接）。
 *
 * <h2>它【不】登记进 {@code RlsCoverageGateTest.ISOLATION_TESTS}</h2>
 * 那张表的语义是「租户表 → 覆盖它的隔离测试」，而本类<b>不新增租户表</b>：
 * 它守的是"既有表之间的引用形态"。这与 {@code RlsRefundWorkOrderConcurrencyTest}
 * 的先例一致（守"同一条 UPDATE 的并发语义"，同样不登记）。
 * 但必须登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution
 * （并在 {@code default-test} 的 excludes 里排除）—— 否则本类"存在但不执行"，
 * 整个 V16 的修复就再没有任何门禁看着。
 */
@DisplayName("V16 跨租户引用完整性：引用归属必须与行归属一样被数据库强制（真库）")
class RlsCrossTenantReferenceGateTest {

    /**
     * V16 修复后的冻结值：单列跨 RLS 外键余量必须为 0。
     * 🛑 这两个数是<b>期望值</b>，不得为了"让测试变绿"而改 ——
     * 2026-09-27 本迁移的 (b) 判据曾因口径写成"列位置"而报 46，当时的正确动作是修判据。
     */
    private static final int EXPECTED_SINGLE_COLUMN_LEFTOVER = 0;
    // 🛑 2026-10-09：V24 新增 settlement_statement → store 的租户耦合复合 FK（商用开发
    //    第二批 E1），复合总数 47 → 48。该数是"当前库态的期望值"，随边界移动而更新；
    //    V16 迁移自身产生的 47 处由 MIGRATION_COMPOSITE_COUNT 冻结，两口径不混用。
    private static final int EXPECTED_TENANT_COUPLED_COMPOSITE = 48;

    /**
     * 判别力对照值（2026-09-27 在真库实测，V16 应用前/后均成立）。
     * <p>它们的唯一作用是证明上面两个期望值的判据<b>不是恒为零/恒为某数</b>的空断言。
     */
    private static final int DIAGNOSTIC_SINGLE_COLUMN_WITHOUT_RLS_FILTER = 83;
    // 2026-10-09：随 V24 新增复合 FK 同步 +1（48 → 49）
    private static final int DIAGNOSTIC_COMPOSITE_WITHOUT_SINGLE_PK_CONDITION = 49;

    /** 12 张被引用的载体表（V16 第 1 节为它们补 (tenant_id, pk) 唯一约束）。 */
    private static final List<String> CARRIER_TABLES = List.of(
            "customer", "region", "store", "staff", "device", "scale",
            "band", "band_sync_log", "cycle_assessment", "refund",
            "refund_statement", "intake_profile_revision");

    /** 契约冻结的复合外键数 —— 与 V16 自证 (b) 同源，两处必须一致。 */
    private static final int MIGRATION_COMPOSITE_COUNT = 47;

    private static DataSource ds;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static RlsGateSupport.BuildMutex buildMutex;

    @BeforeAll
    static void provision() throws Exception {
        if (RlsGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }

        // 与 RLS 门禁同一把构建级互斥锁（Task #18）：本类也用同一个真库，必须串行。
        buildMutex = RlsGateSupport.acquireBuildMutex();
        RlsGateSupport.provisionRealDatabase();

        SingleConnectionDataSource single = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port()
                        + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, true);
        ds = single;
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        // 自证：必须是非超级用户 —— 超级用户绕过 FORCE RLS，用它跑本类会"假通过"。
        String whoAmI = jdbc.queryForObject("select current_user", String.class);
        assertEquals(RlsGateSupport.APP_USER, whoAmI,
                "必须用非超级用户执行；实际=" + whoAmI + "（超级用户绕过 FORCE RLS，断言会假通过）");

        // 前置：种子行必须在位（跨租户引用探针要有"别家的行"可指）。
        assertNotNull(jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class),
                "tenant 必须可读");
    }

    @AfterAll
    static void releaseMutex() {
        if (buildMutex != null) {
            buildMutex.close();
            buildMutex = null;
        }
    }

    // ==================================================================
    // 一、行为验证：引用归属真的被数据库强制了
    // ==================================================================

    /**
     * 🛑 本类的核心用例。
     *
     * <p>三个方向一起断言，缺一个都不足以证明修复有效：
     * <ol>
     *   <li><b>同租户引用必须成功</b> —— 先建立基线（并且防"一拒了之"式的假修复：把约束写成拒绝一切，同样能让 ② 通过）；</li>
     *   <li><b>跨租户引用必须被拒</b>，且理由必须是 {@code 23503}（外键违反）；</li>
     *   <li><b>被拒理由不得是 {@code 42501}</b>（RLS 违反）—— 这一行的 tenant_id 完全合规，
     *       RLS 本就该放行它；若这里报 42501，说明真正生效的是另一个机制，
     *       那么本用例就<b>没有</b>证明复合外键在工作。</li>
     * </ol>
     *
     * <h2>🛑 为什么指向"自建的 A 客户"而不是 {@code SEED_A_ID}</h2>
     * {@code band} 上有一条<b>部分唯一索引</b>
     * {@code uq_band_active_customer UNIQUE (tenant_id, customer_id) WHERE status = 'active'}
     * （V2 迁移）。若探针指向种子客户 A，而<b>别的测试类</b>（{@code RlsBEntityIsolationTest}
     * 等）恰好在该客户上留了一个 active 手环，本用例的 INSERT 会先撞这条唯一索引报
     * {@code 23505}，于是断言到的 SQLSTATE 不是 {@code 23503} —— <b>假红</b>，
     * 且失败信息会指向"外键没生效"，与真因（残留数据）完全无关。
     * 故本用例在<b>同一个事务里</b>先自建一个 A 的客户（随机 UUID ⇒ 任何残留都不可能指过它），
     * 再用它做跨租户引用。这样本用例与库的其它内容<b>零耦合</b>。
     *
     * <h2>🛑 第一版只改了"跨租户"那一步（2026-09-27 全量回归抓出）</h2>
     * 本用例有三步，第一版只把第 ② 步（跨租户）改成了自建客户，
     * 第 ① 步（同租户基线）<b>仍在用 {@code SEED_A_ID}</b> ——
     * 上面那段"零耦合"的理由写得很清楚，却只落实到了一处。
     * <p>失败形态值得记住：单独跑本类时**全绿**，只有全量回归才失败 ——
     * 因为只有全量回归会让 {@code RlsBEntityIsolationTest}/{@code RlsV7…} 等类
     * 先在 {@code SEED_A_ID} 上留下一个 active 手环，
     * 于是第 ① 步的 INSERT 撞 {@code uq_band_active_customer} 报 {@code 23505}，
     * 而断言消息会指向"同租户引用必须成功" —— 与真因（残留数据）完全无关。
     * <p><b>结论：注释里写对的原则，必须在【每一处】落实；只写在一处等于没写。</b>
     * 而且这个坑**只有全量回归能看见** —— 这就是"单类绿"不能当交付证据的原因。
     */
    @Test
    @DisplayName("① 跨租户引用(band→customer) 必须被外键(23503)拒绝，且同租户放行 —— 三向断言")
    void a_cross_tenant_reference_is_rejected_by_foreign_key_not_by_rls() {
        // ---- ① 同租户引用（基线）：必须成功 ----
        //      🛑 与第 ② 步同样自建客户（随机 UUID）。不得用 SEED_A_ID —— 见类注释。
        String custBase = UUID.randomUUID().toString();
        String bandA = UUID.randomUUID().toString();
        Integer sameTenant = tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, RlsGateSupport.TENANT_A);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'active')",
                    custBase, RlsGateSupport.TENANT_A, "V16 跨租户引用探针基准客户A");
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', CURRENT_DATE, 'active')",
                    bandA, RlsGateSupport.TENANT_A, custBase);
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM band WHERE band_id = ?::uuid", Integer.class, bandA);
            s.setRollbackOnly();
            return n;
        });
        assertEquals(1, sameTenant,
                "同租户引用必须成功 —— 若这里为 0 或抛异常，说明修复把【正常路径】也挡住了"
                        + "（那是另一种坏法：约束写成了拒绝一切）");

        // ---- ② 跨租户引用：必须被拒，且理由必须是外键 ----
        String custA = UUID.randomUUID().toString();
        String bandB = UUID.randomUUID().toString();
        DataAccessException ex = assertThrows(DataAccessException.class, () -> tx.execute(s -> {
            // (a) 在 A 的上下文里自建一个 A 的客户 —— 与库中其它内容零耦合
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, RlsGateSupport.TENANT_A);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'active')",
                    custA, RlsGateSupport.TENANT_A, "V16 跨租户引用探针客户A");
            // (b) 切到 B 的上下文，让 band 指向【A 的客户】
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, RlsGateSupport.TENANT_B);
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', CURRENT_DATE, 'active')",
                    bandB, RlsGateSupport.TENANT_B, custA);
            s.setRollbackOnly();
            return null;
        }), "在租户 B 的上下文里，band.customer_id 指向【租户 A 的客户】必须被拒绝。"
                + "若这里没有抛异常，说明 V16 的复合外键没生效 —— 这正是本迁移要修的那个缺口"
                + "（RLS 一路放行，因为它只看本行的 tenant_id）。");

        String state = sqlStateOf(ex);
        assertEquals("23503", state,
                "拒绝理由必须是【外键违反 23503】。实际 SQLSTATE=" + state + "\n"
                        + "🛑 若看到 42501（row-level security 违反），说明拒绝来自另一个机制 ——"
                        + " 而这一行的 tenant_id = B 本就合规，RLS 不该拒绝它；"
                        + " 此时本用例【没有】证明引用归属被强制住，必须查清真正生效的是什么。\n"
                        + "🛑 若看到 23505（唯一约束违反），说明本用例撞上了残留数据，"
                        + " 断言对象变成了唯一索引而不是外键（见本方法 javadoc）。\n"
                        + "原因原文=" + ex.getMostSpecificCause().getMessage());

        // ---- ③ 同租户引用用【自建客户】再复核一次（末位，防"把表锁死也算修好"）----
        String custB = UUID.randomUUID().toString();
        String bandB2 = UUID.randomUUID().toString();
        Integer bOk = tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, RlsGateSupport.TENANT_B);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'active')",
                    custB, RlsGateSupport.TENANT_B, "V16 同租户引用探针客户B");
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', CURRENT_DATE, 'active')",
                    bandB2, RlsGateSupport.TENANT_B, custB);
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM band WHERE band_id = ?::uuid", Integer.class, bandB2);
            s.setRollbackOnly();
            return n;
        });
        assertEquals(1, bOk, "B 租户指向【自己】的客户必须成功");
    }

    @Test
    @DisplayName("② 替换是『只增不减』：可空引用列在替换后 NULL 语义不变（不会把可空列变成必填）")
    void nullable_reference_columns_keep_their_null_semantics_after_the_upgrade() {
        // 为什么必须单独断言：复合外键是 (tenant_id, ref_col) 两列。若有人"顺手"
        // 给可空引用列加了 NOT NULL 以便"让约束更严"，那些列的既定业务状态
        // （未分配区域 / 未指定督导 / 首次修订无前任）就会被静默改掉 ——
        // 那是在一条"完整性修复"的迁移里顺手改业务口径。
        //
        // 载体：store.region_id 是可空列（V5）。同租户插入 region_id = NULL 必须成功。
        // 🛑 插入与观测必须在【同一个事务】里：本类的探针都会 setRollbackOnly，
        //    若把 INSERT 与 SELECT 拆到两个事务，后一个事务开始时前一个已回滚，
        //    观测到的必然是 0 —— 那不是"可空列坏了"，而是探针把自己的观测对象删掉了。
        //    （本用例第一版就踩了这个坑，报 expected 1 but was 0。）
        String store = UUID.randomUUID().toString();
        Integer n = tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, RlsGateSupport.TENANT_A);
            jdbc.update("INSERT INTO store (store_id, tenant_id, name, franchise_type, region_id) "
                            + "VALUES (?::uuid, ?::uuid, ?, '直营', NULL)",
                    store, RlsGateSupport.TENANT_A, "V16 可空引用探针店");
            Integer cnt = jdbc.queryForObject(
                    "SELECT count(*) FROM store WHERE store_id = ?::uuid", Integer.class, store);
            s.setRollbackOnly();
            return cnt;
        });
        assertEquals(1, n,
                "可空引用列必须仍可为 NULL —— 复合外键在 MATCH SIMPLE 下任一侧为 NULL 即跳过检查。"
                        + "若这里为 0/抛异常，说明替换把可空列变成了事实上的必填（业务口径被改了）。");
    }

    // ==================================================================
    // 二、形态验证：余量必须为 0，且这条判据有判别力
    // ==================================================================

    @Test
    @DisplayName("③ 余量必须 = 0，且判据有判别力（不加 RLS 过滤必须数出不同的数）")
    void no_single_column_fk_between_force_rls_tables_remains() {
        int withFilter = countSingleColumnFk(true);
        assertEquals(EXPECTED_SINGLE_COLUMN_LEFTOVER, withFilter,
                "仍存在 " + withFilter + " 处「源目标都 FORCE RLS、且只引用单列」的外键 —— "
                        + "这类外键允许跨租户引用（RLS 不检查引用归属），正是 V16 要修的东西。"
                        + "V16 之后本值必须为 0；不为 0 说明第 2 节的遍历漏了");

        // 🛑 判别力自证：不给 RLS 过滤时，同一个查询必须数出【不同的】数。
        //    否则"0"可能只是查询写坏了（恒为 0）而不是缺陷真的没了。
        int withoutFilter = countSingleColumnFk(false);
        assertTrue(withoutFilter != withFilter,
                "判据没有判别力：加/不加 RLS 过滤数出同一个数(" + withFilter + ") —— "
                        + "这说明过滤条件根本没起作用，那么上面的『余量为 0』毫无意义。"
                        + "预期不加过滤时 ≈" + DIAGNOSTIC_SINGLE_COLUMN_WITHOUT_RLS_FILTER);
        assertTrue(withoutFilter > withFilter,
                "不加 RLS 过滤时应当【更多】（因为 tenant 等非 RLS 宿主表上的外键也会被数到），"
                        + "实际 withoutFilter=" + withoutFilter + " withFilter=" + withFilter);
    }

    @Test
    @DisplayName("④ 租户耦合复合外键数必须 = 48（V16 的 47 + V24 的 1），且『目标主键单列』这一条有判别力")
    void tenant_coupled_composite_fks_are_the_expected_number() {
        int threeConditions = countTenantCoupledComposite(true);
        assertEquals(EXPECTED_TENANT_COUPLED_COMPOSITE, threeConditions,
                "租户耦合的复合外键数应为 " + EXPECTED_TENANT_COUPLED_COMPOSITE
                        + "（与 V16 自证 (b) 同源）。实际=" + threeConditions
                        + " —— 本断言与第 ③ 例互为反证：③ 只说『没有单列的』，"
                        + "而一个把外键【全删掉】的实现同样满足它。");

        // 🛑 判别力自证：去掉"目标表主键为单列"这一条，必须多出 1 个（既有的
        //    app_config_history → app_config，它的目标主键本身就是复合的 (tenant_id, config_no)，
        //    不属本迁移范畴）。两者不等 ⇒ 说明该条件真的在起作用。
        int withoutSinglePk = countTenantCoupledComposite(false);
        assertTrue(withoutSinglePk != threeConditions,
                "判据没有判别力：去掉『目标主键单列』条件后仍数出 " + threeConditions + " —— "
                        + "说明该条件不起作用，那么上面的 47 就不成立（可能实际是 48 而恰好被放过）。"
                        + "预期去掉后 =" + DIAGNOSTIC_COMPOSITE_WITHOUT_SINGLE_PK_CONDITION);
        assertEquals(DIAGNOSTIC_COMPOSITE_WITHOUT_SINGLE_PK_CONDITION, withoutSinglePk,
                "去掉『目标主键单列』后必须数出 " + DIAGNOSTIC_COMPOSITE_WITHOUT_SINGLE_PK_CONDITION
                        + "（多出的那 1 个是 app_config_history → app_config，"
                        + "它的目标主键是复合的，本迁移【不碰它】）。实际=" + withoutSinglePk);
    }

    @Test
    @DisplayName("⑤ 12 张载体表都必须有 (tenant_id, <pk>) 唯一约束，且判据是结构判定（不认列序）")
    void every_carrier_table_has_a_tenant_coupled_unique_key() {
        List<String> missing = new ArrayList<>();
        for (String t : CARRIER_TABLES) {
            if (!hasTenantCoupledUniqueKey(t)) {
                missing.add(t);
            }
        }
        assertTrue(missing.isEmpty(),
                "以下被引用表缺少 (tenant_id, <pk>) 唯一约束（复合外键的载体）-> " + missing
                        + "\n修复句（对每张缺表执行；<col> 取该表单列主键的列名）："
                        + " ALTER TABLE <表> ADD CONSTRAINT uq_tenant_<表>_<col> UNIQUE (tenant_id, <col>);");

        // 🛑 判据必须是「列集合」而不是「字符串/列序」判定 —— 这里把这一点也钉住。
        //    理由（2026-09-27 实测）：UNIQUE (id, tenant_id) 与 UNIQUE (tenant_id, id)
        //    在约束语义上完全等价，且前者照样让复合外键生效（跨租户引用实测被拒）。
        //    一个用 pg_get_constraintdef LIKE '%(tenant_id, %' 的判据会把前者判为"没有载体"
        //    ⇒ 假阳性误报。故此处额外确认：真实库里每一个载体**都**能被结构判定认出来。
        int structural = 0;
        int textual = 0;
        for (String t : CARRIER_TABLES) {
            if (hasTenantCoupledUniqueKey(t)) {
                structural++;
            }
            if (hasTenantCoupledUniqueKeyByText(t)) {
                textual++;
            }
        }
        assertEquals(CARRIER_TABLES.size(), structural,
                "结构判定必须认出全部 " + CARRIER_TABLES.size() + " 张载体");
        // 说明性断言：两种判定在本库当前应当一致（列序恰好都是 tenant_id 打头）。
        // 若将来有人改成 (id, tenant_id)，textual 会下降而 structural 不变 ——
        // 这正是"该用结构判定"的证据，届时本断言的失败信息会指出这一点。
        assertTrue(textual <= structural,
                "文本判定数(" + textual + ")不应超过结构判定数(" + structural + ")");
    }

    // ==================================================================
    // 三、登记验证：迁移必须自登记，且描述可读
    // ==================================================================

    @Test
    @DisplayName("⑥ 迁移必须自登记：schema_migration 有 V16 一行，且 description 说明『引用归属』这件事")
    void the_migration_registers_itself_with_a_meaningful_description() {
        List<String> desc = jdbc.queryForList(
                "SELECT description FROM schema_migration WHERE version = 'V16'", String.class);
        assertEquals(1, desc.size(),
                "schema_migration 里必须恰有 V16 一行（V16 自证 (c) 也会断言这一点；"
                        + "此处独立复核，防『自证与登记』一起被删掉）");

        String d = desc.get(0);
        assertTrue(d.contains("reference") || d.contains("引用"),
                "description 必须说明本迁移修的是『引用归属』，而不只是『约束』。实际=" + d);
        assertTrue(d.length() <= 256,
                "schema_migration.description 是 VARCHAR(256)，不得超长（本仓已因此失败过一次）。实际长度="
                        + d.length());
    }

    // ==================================================================
    // 四、🛑 门禁自证：注入错误必须被抓住
    // ==================================================================

    /**
     * 把一处复合外键改回单列，断言第 ③ 例的判据<b>立刻变红</b>，随后整体回滚。
     *
     * <h2>为什么这条断言是本类最有价值的部分</h2>
     * 一个门禁若只会在"正确实现"下变绿，它就不是门禁 —— 它是一条永远为真的陈述。
     * 本用例证明第 ③ 例的判据<b>真的有判别力</b>：注入恰好一种错误（漏改一处），
     * 判据就应当从 0 变成 1。这不是声明，是在真库上做出来的事实。
     *
     * <h2>为什么要整体回滚</h2>
     * 本用例在真库上做真实的 DDL（DROP + ADD CONSTRAINT）。门禁库每次 provision 会重建，
     * 但仍必须遵守"探针不留痕"：整个注入 + 观测都跑在一个事务里，末尾强制回滚。
     * 否则本类会把库改成"半修复"状态，让<b>后续用例</b>以莫名其妙的方式失败。
     */
    @Test
    @DisplayName("⑦ 门禁自证：把一处复合外键改回单列，第 ③ 例的判据必须立刻变红（随后回滚）")
    void injecting_a_single_column_fk_makes_the_gate_red() {
        // 前置：当前余量必须是 0（否则说明库不是 V16 之后的形态，本自证失去载体）
        int before = countSingleColumnFk(true);
        assertEquals(0, before,
                "自证前提不成立：注入前余量应为 0，实际=" + before
                        + " —— 库不是 V16 之后的状态，本用例无法证明判据的判别力");

        int during = tx.execute(s -> {
            // ① 摘掉一处复合外键（band → customer）
            jdbc.execute("ALTER TABLE band DROP CONSTRAINT band_customer_id_fkey");
            // ② 换回单列形态 —— 这正是缺口被重新打开的形态
            jdbc.execute("ALTER TABLE band ADD CONSTRAINT band_customer_id_fkey "
                    + "FOREIGN KEY (customer_id) REFERENCES customer (id)");
            // ③ 用【与第 ③ 例完全相同的查询】观测
            int observed = countSingleColumnFk(true);
            s.setRollbackOnly();   // 🛑 探针不留痕：整段注入必须回滚
            return observed;
        });

        assertEquals(1, during,
                "注入一处单列外键后，第 ③ 例的判据必须数出 1（而不是 0）—— "
                        + "若这里仍是 0，说明那条判据没有判别力，它对本迁移唯一要防的错误是【瞎的】。"
                        + "实际=" + during);

        // 回滚后复核：库必须回到注入前的形态（防"回滚没生效"掩盖为"自证通过"）
        assertEquals(0, countSingleColumnFk(true),
                "注入用例的事务未正确回滚 —— 库被改成了半修复状态，后续用例会在污染环境上跑");
    }

    // ==================================================================
    // 查询（与 V16 自证块【同口径】—— 两处判据必须一致，否则门禁与迁移会各说各话）
    // ==================================================================

    /** 单列跨 RLS 外键计数。{@code applyRlsFilter=false} 时用作判别力对照。 */
    private static int countSingleColumnFk(boolean applyRlsFilter) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint con "
                        + "JOIN pg_class s ON s.oid = con.conrelid "
                        + "JOIN pg_class t ON t.oid = con.confrelid "
                        + "JOIN pg_namespace n ON n.oid = s.relnamespace "
                        + "WHERE n.nspname = 'public' AND con.contype = 'f' "
                        + "  AND array_length(con.conkey, 1) = 1 "
                        + (applyRlsFilter ? "  AND s.relrowsecurity AND t.relrowsecurity" : ""),
                Integer.class);
    }

    /**
     * 租户耦合的复合外键计数。
     *
     * <p>{@code requireSinglePk=true} 时条件为：
     * ① {@code conkey} 长度 = 2；② 源端与目标端<b>都</b>含 {@code tenant_id}（列集合判定，
     * <b>位置无关</b>）；③ 目标表主键是单列（排除既有的 {@code app_config_history → app_config}）。
     * 去掉 ③ 用 {@code false}，作判别力对照。
     */
    private static int countTenantCoupledComposite(boolean requireSinglePk) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint con "
                        + "JOIN pg_class s ON s.oid = con.conrelid "
                        + "JOIN pg_class t ON t.oid = con.confrelid "
                        + "JOIN pg_namespace n ON n.oid = s.relnamespace "
                        + "JOIN pg_constraint tp ON tp.conrelid = t.oid AND tp.contype = 'p' "
                        + "WHERE n.nspname = 'public' AND con.contype = 'f' "
                        + "  AND array_length(con.conkey, 1) = 2 "
                        + "  AND s.relrowsecurity AND t.relrowsecurity "
                        + (requireSinglePk ? "  AND array_length(tp.conkey, 1) = 1 " : "")
                        + "  AND EXISTS (SELECT 1 FROM unnest(con.conkey) k(attnum) "
                        + "                JOIN pg_attribute a ON a.attrelid = con.conrelid "
                        + "                     AND a.attnum = k.attnum WHERE a.attname = 'tenant_id') "
                        + "  AND EXISTS (SELECT 1 FROM unnest(con.confkey) k(attnum) "
                        + "                JOIN pg_attribute a ON a.attrelid = con.confrelid "
                        + "                     AND a.attnum = k.attnum WHERE a.attname = 'tenant_id')",
                Integer.class);
    }

    /** 载体判定（结构判定：列集合含 tenant_id + 该表单列主键列，位置无关）。 */
    private static boolean hasTenantCoupledUniqueKey(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c2 "
                        + "WHERE c2.conrelid = to_regclass('public.' || quote_ident(?)) "
                        + "  AND c2.contype IN ('u', 'p') "
                        + "  AND array_length(c2.conkey, 1) = 2 "
                        + "  AND EXISTS (SELECT 1 FROM unnest(c2.conkey) AS k(attnum) "
                        + "                JOIN pg_attribute a ON a.attrelid = c2.conrelid "
                        + "                     AND a.attnum = k.attnum WHERE a.attname = 'tenant_id') "
                        + "  AND EXISTS (SELECT 1 FROM pg_constraint pkc "
                        + "                JOIN unnest(pkc.conkey) AS pk(attnum) ON true "
                        + "                JOIN pg_attribute pka ON pka.attrelid = pkc.conrelid "
                        + "                     AND pka.attnum = pk.attnum "
                        + "               WHERE pkc.conrelid = c2.conrelid AND pkc.contype = 'p' "
                        + "                 AND pka.attname IN (SELECT a2.attname "
                        + "                       FROM unnest(c2.conkey) AS k2(attnum) "
                        + "                       JOIN pg_attribute a2 ON a2.attrelid = c2.conrelid "
                        + "                            AND a2.attnum = k2.attnum))",
                Integer.class, table);
        return n != null && n > 0;
    }

    /**
     * 文本判定的载体检查 —— <b>仅用作对照</b>，证明"结构判定"比它更宽。
     *
     * <p>🛑 不得把它当作主判据：它认列序，会把等价的 {@code UNIQUE (id, tenant_id)}
     * 判为"没有载体"（2026-09-27 实测假阳性）。保留在此是为了让"两者不一致"这件事
     * 在将来可被观测到，而不是让下一个人重新踩一遍。
     */
    private static boolean hasTenantCoupledUniqueKeyByText(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c2 "
                        + "WHERE c2.conrelid = to_regclass('public.' || quote_ident(?)) "
                        + "  AND c2.contype IN ('u', 'p') "
                        + "  AND pg_get_constraintdef(c2.oid) LIKE '%(tenant_id, %'",
                Integer.class, table);
        return n != null && n > 0;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 在指定租户上下文里跑一段动作，结束后<b>强制回滚</b>（探针不留痕）。
     *
     * <p>{@code set_config('app.tenant_id', ?, true)} 的第三参 {@code true} = is_local，
     * 只在当前事务内有效 —— 故上下文与动作必须处在同一个事务里，
     * 否则会出现"设了上下文但动作跑在另一条连接上"的经典错配。
     */
    private static void inRolledBackTx(String tenant, Runnable body) {
        inRolledBackTxWithResult(tenant, () -> {
            body.run();
            return null;
        });
    }

    private static <T> T inRolledBackTxWithResult(String tenant, java.util.function.Supplier<T> body) {
        return tx.execute(s -> {
            // 🛑 用 queryForObject 而不是 update：set_config 是 SELECT。
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenant);
            T r = body.get();
            s.setRollbackOnly();
            return r;
        });
    }

    /** 从异常链里取 PostgreSQL 的 SQLSTATE（5 位）。取不到返回 {@code "<none>"}。 */
    private static String sqlStateOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se && se.getSQLState() != null) {
                return se.getSQLState();
            }
        }
        return "<none>";
    }
}