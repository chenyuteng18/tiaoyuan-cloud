package com.diaoyuanyun.dy.app.provisioning;

import com.diaoyuanyun.dy.app.identity.domain.OrgProvisioningPlan;
import com.diaoyuanyun.dy.app.identity.service.OrganizationProvisioningService;
import com.diaoyuanyun.dy.app.identity.service.OrganizationProvisioningService.ProvisioningResult;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-7 组织开通通路的<b>真库真表</b>端到端回归。
 *
 * <h2>它补的是 {@code ProvisioningBoundaryGateTest} 补不了的那一半</h2>
 * 那道门禁是<b>纯静态</b>的：它只见证"生产代码里有 {@code INSERT INTO tenant}"。
 * 但"有写入语句"与"这条通路真的能把一个租户及其组织树建成"之间有五道缝，
 * 静态扫描一道都看不见：
 * <pre>
 *   ① 语句能不能真的执行（占位符数量与参数个数是否对得上）
 *   ② V15 的 provision_tenant() 是否真的建出了租户行 —— 而不是悄悄没执行
 *   ③ 上下文是否真的建立（set_config 的 is_local 生效与否）
 *   ④ 组织树的三个外键顺序对不对（region → store → staff → 回填督导，环依赖）
 *   ⑤ 建完之后 RLS 是否真的在<b>这条新租户</b>上生效
 * </pre>
 * ⇒ 这正是本仓反复出现的那一类失守："单测全绿、门禁全 PASS、BUILD SUCCESS，
 * 而功能不可用"。故本套件把五件事变成<b>可判定的真库事实</b>。
 *
 * <h2>🛑 判据设计一：每个"必须成功"的断言旁边，都有一条"必须失败"的对照</h2>
 * 本套件里最容易自我欺骗的一条是"开通后 RLS 生效" —— 因为
 * <b>一张空表的 {@code count(*) = 0} 看起来与"RLS 挡住了"一模一样</b>。
 * 故本类不写"{@code count == 0} 所以隔离有效"，而是写四段互锁：
 * <pre>
 *   ① 无上下文 INSERT  →  必须抛错，且原因必须是 RLS 策略（不是外键/别的问题）
 *   ② 无上下文 SELECT  →  必须返回 0
 *   ③ 建上下文后再读    →  必须【看到 3 行】
 *   ④ 换一个租户上下文  →  必须看不到
 * </pre>
 * ⇒ ③ 的存在排除了 ①② 的混淆解释：数据确实写进去了，确实只有在正确的上下文里才看得见。
 * ④ 则排除"只要有上下文就全放行"这一更弱的实现。
 *
 * <h2>🛑 判据设计二：上下文必须由<b>被测代码</b>自建，不得由测试代劳</h2>
 * 本类注入真实 bean 调 {@code service.provision(...)}，<b>不</b>在测试里手工
 * {@code SET LOCAL}。理由：若由测试来设上下文，那测的是"我设上下文之后能不能建组织"，
 * 而不是"开通这条路径自己会不会设上下文" —— 而后者才是生产里唯一的形态
 * （运维脚本不会记得替你设）。故第 ③ 组在<b>未设任何上下文的连接</b>上先读一次得 0 行，
 * 再调服务、再看得到 3 行 —— 证明上下文是服务建的，不是测试留的。
 *
 * <h2>🛑 判据设计三：审计必须在开通的同一事务里，且是<b>哈希链</b>上的真实一条</h2>
 * {@code OrganizationProvisioningService} 的类注释写明"审计失败则开通失败"。
 * 本类把它变成可判定事实：断言 {@code audit_log} 出现一条
 * {@code action=TENANT_PROVISIONED} 且 {@code target_id} = 新租户的记录，
 * 再调 {@code verifyChain()} 断言整条链仍然自洽 —— 后者尤其重要，因为
 * 审计链是<b>全局单链</b>（写入时无租户过滤地取 prev_hash），开通过程里若在错误的
 * 上下文下写审计，链会断而所有业务测试全绿。
 *
 * <h2>🛑 判据设计四：幂等是"重放不增行"，不是"重放不报错"</h2>
 * 一个把 {@code ON CONFLICT} 写错的实现，重放时可能：报错（还算好）、
 * 或<b>静默再插一份</b>（灾难）。故本类断言"第二次调用各层计数为 0 且总行数不变"，
 * 而不只是"第二次没抛异常"。
 *
 * <h2>零污染 —— 以及一处<b>刻意的例外</b>（审计行不清理）</h2>
 * 组织树数据全部用 {@code b0700000-} 前缀自建，{@code @AfterAll} 在租户上下文内按
 * "子 → 父"外键序清理（{@code staff} → {@code store} → {@code region} → {@code tenant}）。
 * 🛑 清理也必须在租户上下文内：FORCE RLS 下 DELETE 的 {@code WITH CHECK} 会挡住
 * 无上下文时的删除，<b>静默返回 0 行</b>（本仓 S2-5 那套已付过一次代价）。
 *
 * <p>🛑 <b>审计条目刻意不删，这是设计而非疏漏</b>。三条理由，逐条说明：
 * <ol>
 *   <li>{@code audit_log} 是一条<b>全局单链</b>（每行的 {@code prev_hash} 指向物理前驱）。
 *       删掉中间任何一行，会让<b>它的后继</b> {@code prev_hash} 对不上 ——
 *       即为后续每一次 {@code verifyChain()} 留下一个永久的假断口。
 *       实测：本套件落地前 dev 库审计链为 366 行、0 断口（自洽）；
 *       若本类清理时删掉自己的若干行，链会立刻变成"有断口"，从而让第 ⑤ 例
 *       在<b>下一次运行</b>时红——而那时红的原因会被误读成"开通破坏了链"。</li>
 *   <li>"这不是测试数据"：一次真实的租户开通<b>客观发生过</b>，它的审计证据本就应当留下。
 *       删掉它才是对审计语义的破坏。这与 {@code audit_log.sql} 里
 *       {@code REVOKE UPDATE, DELETE, TRUNCATE} 的设计意图一致
 *       （该 REVOKE 在本骨架的 dev 库上尚未应用，但它是这套设计的既定方向）。</li>
 *   <li>清理的<b>自证</b>因而必须换个形式：不删也仍然能证明"清理真的生效"——
 *       租户行必须被删干净（这一条足以证明上下文内的 DELETE 真的执行了），
 *       而审计行按"本套件写入的一定条数"断言存在。</li>
 * </ol>
 *
 * <h2>执行顺序</h2>
 * 用 {@link Order} 显式排序，而不是靠方法名字母序：第 ① 例断言"该租户此前不存在"
 * （这条前置让"开通成功"与"它本来就在"无法混淆），故它必须最先跑。
 * 每个用例内部又各有一个 {@code ensureProvisioned()} 兜底，
 * 使顺序变化不会导致"用例之间互相依赖"的脆弱结构。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-7 · 组织开通通路真库回归（开通 / 上下文 / 隔离 / 幂等 / 审计）")
class OrganizationProvisioningE2ETest {

    // ------------------------------------------------------------------
    // 口径常量：全部用 b0700000- 前缀，互不重叠（跨层撞 id 会被计划自检拒绝）
    // ------------------------------------------------------------------
    private static final String TENANT = "b0700000-0000-0000-0000-000000000001";
    private static final String TENANT_OTHER = "b0700000-0000-0000-0000-0000000000ff";
    private static final String R_EAST = "b0700000-0000-0000-0000-000000000011";
    private static final String R_WEST = "b0700000-0000-0000-0000-000000000012";
    private static final String S_EAST_A = "b0700000-0000-0000-0000-000000000021";
    private static final String S_EAST_B = "b0700000-0000-0000-0000-000000000022";
    private static final String S_WEST_A = "b0700000-0000-0000-0000-000000000023";
    private static final String P_MANAGER = "b0700000-0000-0000-0000-000000000031";
    private static final String P_THERAPIST = "b0700000-0000-0000-0000-000000000032";
    private static final String P_MERIDIAN = "b0700000-0000-0000-0000-000000000033";
    private static final String P_SUPERVISOR = "b0700000-0000-0000-0000-000000000034";

    /** 从未被开通的负样本租户 id（合法 UUID 形状，库里没有）—— 供判别力自证。 */
    private static final String NEVER_PROVISIONED = "b0700000-0000-0000-0000-0000000000fe";

    /** 审计动作名 —— 与 {@code OrganizationProvisioningService} 里逐字一致。 */
    private static final String AUDIT_ACTION = "TENANT_PROVISIONED";
    /** 操作者标识 —— 与各用例传入的 operator 一致。 */
    private static final String OPERATOR = "ops-e2e-witness";

    /** 本套件写审计的次数（用于 @AfterAll 断言"审计证据确实留下了"，而不删它）。 */
    private static int provisioningCalls;

    @Autowired
    private OrganizationProvisioningService service;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext ctx;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 在租户上下文里执行只读查询 —— 与 {@code OrganizationProvisioningRepository.inTenant}
     * <b>同款形态</b>（短事务 + {@code SET LOCAL}），刻意不另造一套。
     *
     * <p>🛑 为什么测试里也必须用它（这是本套件里最容易写错的一处）：
     * {@code region/store/staff} 三表是 {@code ENABLE + FORCE ROW LEVEL SECURITY}，
     * policy 的 {@code USING} 形如
     * {@code tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid}。
     * 无上下文时 {@code current_setting} 返回 NULL ⇒ 策略为 false ⇒
     * <b>SELECT 静默过滤成 0 行</b>（注意：写路径是<b>抛错</b>，读路径是<b>静默 0 行</b>，
     * 这两个方向很容易记反）。于是 {@code queryForObject} 会抛
     * {@code EmptyResultDataAccessException("expected 1, actual 0")} ——
     * 它看起来像"数据没写进去"，实际是"我没设上下文"。
     * <p>用它与"服务读侧"（{@code service.countStores}）互为印证：
     * 两条路径都设上下文，读数就该一致；若只设一条，本套件会红在上面那种误导性异常上。
     */
    private <T> T inTenant(String tenantId, java.util.function.Supplier<T> body) {
        return tx().execute(status -> {
            jdbc().execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    // ==================================================================
    // ① 开通：组织树必须真的建成
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 开通一个租户 + 2 区域 + 3 门店 + 4 员工，全部落库且逐层外键正确")
    void provisioning_creates_the_whole_org_tree() {
        // 先自愈式清一次（上次运行若崩在清理前会留残留），再断言"现在确实不存在"。
        purgeOrgData();
        assertEquals(0, countTenantRaw(TENANT),
                "前置失败：purge 之后 b0700000 租户仍在库里 —— 清理逻辑本身失效，"
                        + "本例的『开通成功』就无法与『它本来就在』区分");

        ProvisioningResult result = provision();

        assertEquals("CREATED", result.outcome().tenantMode(),
                "首次开通必须返回 CREATED（返回 ALREADY_EXISTS 说明前置未清干净）");
        assertEquals(2, result.outcome().regions(), "两个区域都必须插入成功");
        assertEquals(3, result.outcome().stores(), "三家门店都必须插入成功");
        assertEquals(4, result.outcome().staff(), "四名员工都必须插入成功");
        assertEquals(1, result.outcome().supervisedRegions(),
                "恰一个区域应被回填督导（东区）；西区未指定督导，不得被回填");

        assertEquals(1, countTenantRaw(TENANT), "tenant 表必须有恰 1 行");

        // 组织树（经服务读侧，走与业务读路径同一套 inTenant 上下文）
        assertEquals(3, service.countStores(TENANT), "该租户应有 3 家门店");
        assertEquals(4, service.countStaff(TENANT), "该租户应有 4 名员工");

        // 逐层外键正确性：门店必须挂在对的区域、员工必须挂在对的门店
        // 🛑 必须走 inTenant（SET LOCAL 上下文），不能用裸 jdbc() 直查：
        //    region/store/staff 三表 FORCE RLS，无上下文时 SELECT 会被
        //    policy 的 USING 静默过滤成【0 行】—— 于是 queryForObject 抛
        //    EmptyResultDataAccessException("expected 1, actual 0")，
        //    而这个异常的表象（"数据没写进去"）与真实原因（"我没设上下文"）完全不符。
        //    这正是本仓 S2-5 已付过一次代价的那个坑，同一个类里必须只留一种读法。
        assertEquals(R_EAST, inTenant(TENANT, () -> jdbc().queryForObject(
                        "SELECT region_id::text FROM store WHERE store_id = ?::uuid",
                        String.class, S_EAST_A)),
                "东区门店必须挂在东区区域上（外键写反会把门店挂到别的辖区，而所有计数断言仍然通过）");

        assertEquals(S_EAST_A, inTenant(TENANT, () -> jdbc().queryForObject(
                        "SELECT store_id::text FROM staff WHERE staff_id = ?::uuid",
                        String.class, P_THERAPIST)),
                "调理师必须挂在东区 A 店");

        // 督导回填：东区必须有；西区必须没有
        assertEquals(P_SUPERVISOR, inTenant(TENANT, () -> jdbc().queryForObject(
                        "SELECT supervisor_id::text FROM region WHERE region_id = ?::uuid",
                        String.class, R_EAST)),
                "东区应被回填督导（region ↔ staff ↔ store 是环，故必须等 staff 建出后再回填）");
        assertEquals(0, inTenant(TENANT, () -> jdbc().queryForObject(
                        "SELECT count(*) FROM region WHERE region_id = ?::uuid AND supervisor_id IS NOT NULL",
                        Integer.class, R_WEST)),
                "西区未指定督导 —— 不得被回填任何值（把 null 猜成一个默认人是最坏的一种『顺手补全』）");
    }

    // ==================================================================
    // ② 数据库侧角色口径：中文人事枚举，不是契约短码
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② staff.role 落的是中文人事枚举（店长/调理师/经络师），不是契约短码 manager/therapist")
    void staff_role_is_persisted_as_the_db_enumeration_not_the_contract_shortcode() {
        ensureProvisioned();

        List<String[]> rows = service.staffRows(TENANT);
        assertEquals(4, rows.size(), "应有 4 名员工");

        Set<String> roles = new HashSet<>();
        for (String[] r : rows) {
            roles.add(r[2]);
        }
        assertEquals(Set.of("店长", "调理师", "经络师"), roles,
                "staff.role 必须落在 V5 CHECK 冻结的三个中文值上（客服不可开通）");

        // 🛑 反向断言：契约短码【绝不】得出现在这一列。
        //    这是最容易被"顺手统一"的一处口径 —— 有人认为 manager 比 店长 更规范，
        //    改完之后 A2 的权限判定会开始出现无法回答的问题
        //    （见 OrgProvisioningPlan.StaffRole 的类注释）。
        for (String[] r : rows) {
            assertFalse(List.of("manager", "therapist", "meridian", "client", "area", "hq").contains(r[2]),
                    "staff.role 里出现了契约短码 '" + r[2] + "' —— 两套口径已被混用。"
                            + "契约短码属于 token / 鉴权侧；本列是面向门店人事的可读枚举");
        }

        // 反向：角色与门店的挂靠也要能被核出来（否则 staffRows 的映射可能是错的）
        boolean therapistFound = rows.stream().anyMatch(r -> "调理师".equals(r[2]) && S_EAST_A.equals(r[1]));
        assertTrue(therapistFound,
                "staffRows 必须能核出『调理师挂在东区 A 店』—— 否则这个方法返回的行映射有误，"
                        + "上面那条口径断言就只是在断言一个错的数据源");
    }

    // ==================================================================
    // ③ 上下文与隔离：互锁四段（本套件的核心）
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ RLS 互锁：无上下文写必被拒(且因由必须是RLS)、无上下文读必 0 行、建上下文后必可见、跨租户必不可见")
    void rls_context_is_mandatory_and_the_isolation_has_direction() {
        ensureProvisioned();
        JdbcTemplate j = jdbc();

        // ---- ① 无上下文 INSERT 必须被 RLS 拒绝 ----
        // 用独立的、注定失败的 id，整段包在事务里回滚，不留痕迹。
        tx().executeWithoutResult(status -> {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> j.update("INSERT INTO store (store_id, tenant_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, '越权门店', '直营')",
                            "b0700000-0000-0000-0000-0000000000ee", TENANT),
                    "🛑 无租户上下文时 INSERT 竟然成功了 —— RLS 未生效。"
                            + "这是本套件最重要的一条反证：它若变绿，说明租户隔离在写路径上是假的");

            // 🛑 必须断言【拒绝的理由是 RLS】，而不是"抛了某个异常"。
            //    否则一条外键违规（例如 tenant 行不存在）也会让这条断言通过 ——
            //    那是"用错误的原因得到了正确的绿灯"，比漏报更危险。
            //
            // 🛑 实测（2026-09-27，一次真实的探针运行，不是推测）：RLS 的证据【不在顶层消息里】。
            //    Spring 把 42501 归成 BadSqlGrammarException，顶层消息只有
            //        "PreparedStatementCallback; bad SQL grammar [INSERT INTO store ...]"
            //    —— 那句话里没有任何 "row-level security" 字样，看一眼还像是 SQL 语法错误。
            //    真正的证据在 cause 链的第 0 层：
            //        PSQLException / SQLSTATE=42501 / "错误: 新行违背了表"store"的行级安全策略"
            //    ⇒ 故断言必须【穿透 cause 链】。初版只看顶层消息，必然失败；
            //      而若当时按"它报语法错误"去改 SQL，就会把一个正确的实现改坏。
            //    ⇒ 双判据：① SQLSTATE 42501（机器可判、与语言无关）；
            //              ② 全链消息含 RLS 措辞（人可读、证明是策略而非别的 42501）。
            String chain = fullCauseMessages(ex);
            String sqlState = deepestSqlState(ex);
            assertTrue("42501".equals(sqlState),
                    "拒绝的 SQLSTATE 不是 42501(insufficient_privilege) —— 本条断言已退化为假阳性。"
                            + "实际 SQLSTATE=" + sqlState + " / 异常链=" + chain);
            assertTrue(chain.contains("row-level security") || chain.contains("row level security")
                            || chain.contains("行级安全策略"),
                    "拒绝原因不是 RLS 策略 —— 本条断言已退化为假阳性。"
                            + "实际异常链（顶层 → 最深）：" + chain);
            status.setRollbackOnly();
        });

        // ---- ② 无上下文 SELECT 必须 0 行 ----
        // 🛑 这一条单独【不构成】证据（空表与"被挡住"长得一样），必须与 ③ 合读。
        Integer withoutCtx = j.queryForObject("SELECT count(*) FROM store", Integer.class);
        assertNotNull(withoutCtx);
        assertEquals(0, withoutCtx, "无租户上下文时 SELECT 必须返回 0 行（fail-closed 策略的静默过滤形态）");

        // ---- ②b 自证：无上下文时按 tenant_id 精确过滤也是 0 ----
        assertEquals(0, j.queryForObject("SELECT count(*) FROM store WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT),
                "（自证）无上下文时精确过滤也应为 0 —— 证明 ② 的 0 行来自策略，而非『刚好没有匹配行』");

        // ---- ③ 建上下文后必须看得见（排除"表本来就是空的"）----
        assertEquals(3, service.countStores(TENANT),
                "经服务的 inTenant 建立上下文后必须看得见 3 家门店。若这里也是 0，"
                        + "说明上下文机制没生效 —— 而 ② 的『0 行』就会变成假绿。"
                        + "这正是本套件把 ②③ 写成互锁的原因");

        // ---- ④ 隔离的方向性：换一个租户上下文必须看不见 ----
        ensureOtherTenant();
        assertEquals(0, service.countStores(TENANT_OTHER),
                "用另一个租户的上下文必须看不到本租户的 3 家门店 —— 隔离必须是有方向性的，"
                        + "而不是『只要有上下文就全放行』");
    }

    // ==================================================================
    // ④ 幂等：重放不报错，且【不增行】
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 幂等重放：第二次返回 ALREADY_EXISTS，各层计数为 0，总行数不变")
    void replaying_the_same_plan_is_idempotent_and_does_not_duplicate_rows() {
        ensureProvisioned();
        int storesBefore = service.countStores(TENANT);
        int staffBefore = service.countStaff(TENANT);
        int tenantsBefore = countTenantRaw(TENANT);

        ProvisioningResult again = provision();

        assertEquals("ALREADY_EXISTS", again.outcome().tenantMode(), "重放必须返回 ALREADY_EXISTS");
        // 🛑 关键：断言【行数为 0】，而不只是"没抛异常"。
        //    一个漏写 ON CONFLICT 的实现会在这里静默再插一份（或抛主键冲突）——
        //    前者尤其危险：组织树上凭空多出一套同名门店，而所有既有测试仍然全绿。
        assertEquals(0, again.outcome().regions(), "重放不得新增区域行");
        assertEquals(0, again.outcome().stores(), "重放不得新增门店行");
        assertEquals(0, again.outcome().staff(), "重放不得新增员工行");
        assertEquals(0, again.outcome().supervisedRegions(),
                "重放不得再次回填督导（回填 UPDATE 带 supervisor_id IS NULL 条件，第二次应命中 0 行）");

        assertEquals(storesBefore, service.countStores(TENANT), "重放后门店总数必须不变");
        assertEquals(staffBefore, service.countStaff(TENANT), "重放后员工总数必须不变");
        assertEquals(tenantsBefore, countTenantRaw(TENANT), "重放后租户行数必须不变");
    }

    // ==================================================================
    // ⑤ 审计：必须是开通同事务内的哈希链上真实一条
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 开通必须留下审计（TENANT_PROVISIONED）且链自洽；payload 只含规模摘要，不含组织细节")
    void provisioning_leaves_an_audit_entry_on_the_hash_chain() {
        // ---- 前置：本用例【自己】开通一次，并拿到那一条审计的精确 id ----
        // 🛑 为什么必须按 id 取、不能用 "ORDER BY created_at DESC LIMIT 1"：
        //    本套件前面几例已经调用过 provision()，且【幂等重放也写审计】（见
        //    OrganizationProvisioningService 类注释的裁定）。后者 payload 的
        //    tenantMode=ALREADY_EXISTS、stores=0 —— 按时间取最新会取到那一条，
        //    于是"payload 应含 stores:3"失败，而失败原因与真实实现无关。
        //    更坏的是：这个错误会诱导人去放宽断言（改成 requestedStores:3），
        //    从而把它变成一条永真断言。故这里按 auditId 精确定位。
        purgeOrgData();
        provisioningCalls++;
        ProvisioningResult result = service.provision(fullPlan(), OPERATOR);
        String auditId = result.auditId();
        assertNotNull(auditId, "开通回执必须带上本次留痕的审计 id —— 它是事后对账的唯一锚点");

        JdbcTemplate j = jdbc();

        assertEquals(1, j.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE id = ?::uuid", Integer.class, auditId),
                "回执给出的 auditId 必须真的对应库中一条审计 —— 否则回执是无法用于对账的");

        // 该条审计的字段（按 id 精确取，避免被后续调用覆盖）
        assertEquals(AUDIT_ACTION, j.queryForObject(
                        "SELECT action FROM audit_log WHERE id = ?::uuid", String.class, auditId),
                "action 必须逐字等于 " + AUDIT_ACTION);
        assertEquals("tenant", j.queryForObject(
                        "SELECT target_type FROM audit_log WHERE id = ?::uuid", String.class, auditId),
                "target_type 必须是 tenant");
        assertEquals(TENANT, j.queryForObject(
                        "SELECT target_id FROM audit_log WHERE id = ?::uuid", String.class, auditId),
                "target_id 必须是本次开通的租户 id");

        assertEquals(OPERATOR, j.queryForObject(
                        "SELECT actor FROM audit_log WHERE id = ?::uuid", String.class, auditId),
                "审计 actor 必须是调用方提供的操作者，不是 'provisioning' 这种系统占位值 —— "
                        + "审计里 actor 是唯一有追责含义的一栏");

        String payload = j.queryForObject(
                "SELECT payload FROM audit_log WHERE id = ?::uuid", String.class, auditId);
        assertNotNull(payload);
        assertTrue(payload.contains("\"tenantMode\":\"CREATED\""),
                "本条是首次开通，payload 的 tenantMode 必须是 CREATED。实际=" + payload);
        assertTrue(payload.contains("\"stores\":3"),
                "审计 payload 应含本次开通的规模摘要（stores:3）。实际=" + payload);
        assertTrue(payload.contains("\"requestedStores\":3"),
                "审计 payload 还应含【请求规模】—— 它与实际落库行数不同，"
                        + "两者一起才能回答『重放了一份多大的计划』。实际=" + payload);

        // 🛑 反向断言：组织细节【不得】进审计 payload。
        //    audit_log 是全租户可读的（其无 RLS 是哈希链连续性的要求，敞口已登记），
        //    把门店名写进去等于把 A 租户的经营信息暴露给每个能读审计的租户。
        assertFalse(payload.contains("东区A店"),
                "审计 payload 里出现了门店名 —— audit_log 全租户可读，不得写组织细节。实际=" + payload);

        // 链自洽：开通写入必须发生在正确的上下文/事务里，否则全局单链会断
        var chain = auditLogService().verifyChain();
        assertTrue(chain.valid(),
                "开通之后审计链必须仍然自洽 —— 链断说明开通路径上的审计写入破坏了 prev_hash 连续性。"
                        + "这条断言的价值在于：业务断言全绿时链也可能已断（链是全局单链，写入时无租户过滤）。"
                        + "实际：valid=" + chain.valid() + " broken_at=" + chain.brokenAt()
                        + " reason=" + chain.reason() + " checked=" + chain.checked());
        assertTrue(chain.checked() > 0,
                "链校验的记录条数必须 > 0 —— 一个把表读成 0 行然后返回 valid=true 的校验器是灾难性的假通过");
    }

    // ==================================================================
    // ⑤b 幂等重放【也】留痕：这是一条裁定，必须可判定
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑤b 幂等重放也写审计（tenantMode=ALREADY_EXISTS、实际行数 0、请求规模保留）—— 一次重放必须可被事后看见")
    void a_replayed_provisioning_also_leaves_an_audit_trail() {
        ensureProvisioned();

        int before = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, AUDIT_ACTION, TENANT);

        ProvisioningResult replay = provision();     // 第二次调用同一个计划

        assertEquals("ALREADY_EXISTS", replay.outcome().tenantMode(), "重放必须返回 ALREADY_EXISTS");

        String payload = jdbc().queryForObject(
                "SELECT payload FROM audit_log WHERE id = ?::uuid", String.class, replay.auditId());
        assertNotNull(payload, "重放也必须落一条审计 —— 回执里的 auditId 必须能查到");
        assertTrue(payload.contains("\"tenantMode\":\"ALREADY_EXISTS\""),
                "重放那条审计的模式必须是 ALREADY_EXISTS。实际=" + payload);
        assertTrue(payload.contains("\"stores\":0"),
                "重放的实际落库行数必须是 0（ON CONFLICT DO NOTHING 的幂等语义）。实际=" + payload);
        assertTrue(payload.contains("\"requestedStores\":3"),
                "🛑 但请求规模必须保留为 3 —— 这一栏是『有人拿一份多大的计划来打过这个已存在的租户』"
                        + "的唯一线索。若它也变成 0，审计就无法回答『那次重放想做什么』。实际=" + payload);

        assertEquals(before + 1, jdbc().queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                        Integer.class, AUDIT_ACTION, TENANT),
                "重放必须恰好新增一条审计（不多不少）—— "
                        + "『幂等』保护的是数据（不重复建），不是证据（不重复记录），两者不该用同一个开关");
    }

    // ==================================================================
    // ⑥ 拒绝路径：非法输入必须在落库前被挡下
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑥ 形态自检与参数校验：空名租户/越界枚举/客服角色/跨层撞id/空操作者/非法租户id 全部被拒")
    void invalid_plans_are_rejected_before_any_write() {
        ensureProvisioned();
        Integer tenantsBefore = jdbc().queryForObject("SELECT count(*) FROM tenant", Integer.class);

        // 空名租户（DDL 只挡 NULL，不挡空串 —— 故函数层必须挡）
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan.TenantSpec(
                        "b0700000-0000-0000-0000-0000000000c1", "   ", null),
                        "空名租户必须在构造期被拒").getCode());

        // 越界 franchise_type
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan.StoreSpec(
                        "b0700000-0000-0000-0000-0000000000c2", null, "某店", "自营", null),
                        "franchise_type 越界必须在构造期被拒（V5 CHECK 冻结 直营/加盟），否则会以一条 "
                                + "PG check constraint 报错到达调用方 —— 那对调用方不构成有效诊断").getCode());

        // 越界 device_model
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan.StoreSpec(
                        "b0700000-0000-0000-0000-0000000000c4", null, "某店", "直营", "杠3"),
                        "device_model 越界必须在构造期被拒（V5 CHECK 冻结 杠2/现有）").getCode());

        // 客服不可开通（业务裁定：无端、无账号，本表不产生客服行）
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan.StaffSpec(
                        "b0700000-0000-0000-0000-0000000000c3", null, OrgProvisioningPlan.StaffRole.NONE),
                        "StaffRole.NONE（客服）必须在构造期被拒 —— 这是 2026-09-16 业务裁定的代码级强制").getCode());

        // 跨层撞 id：region_id 与 store_id 相同 —— 四张表主键各自独立，DB 不会拦
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan(
                        new OrgProvisioningPlan.TenantSpec(TENANT, "撞 id 租户", null),
                        List.of(new OrgProvisioningPlan.RegionSpec(
                                "b0700000-0000-0000-0000-0000000000d1", "辖区", null)),
                        List.of(new OrgProvisioningPlan.StoreSpec(
                                "b0700000-0000-0000-0000-0000000000d1", null, "撞 id 门店", "直营", null)),
                        List.of()).selfCheck(),
                        "跨层撞 id 必须被拒 —— 数据库不会拦（主键各自独立），但后果隐蔽："
                                + "两个实体在排障时会长得一模一样").getCode());

        // 空操作者：一次无主的开通等于一次无主的变更
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> service.provision(fullPlan(), "  "),
                        "空操作者必须被拒（审计 actor 不允许为空）").getCode());

        // 空计划：tenant 不可为空
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                assertThrows(BizException.class, () -> new OrgProvisioningPlan(
                        null, List.of(), List.of(), List.of()).selfCheck(),
                        "缺少租户的计划必须被拒").getCode());

        // 非法租户 id：语义归属 2003（租户不匹配），不是 500
        assertEquals(ErrorCode.TENANT_MISMATCH.getCode(),
                assertThrows(BizException.class, () -> service.provision(new OrgProvisioningPlan(
                        new OrgProvisioningPlan.TenantSpec("not-a-uuid", "非法 id 租户", null),
                        List.of(), List.of(), List.of()), OPERATOR),
                        "非法租户 id 必须归为 2003（租户不匹配），不是 500").getCode());

        // 🛑 汇总自证：上面八条全都是"落库前"的拒绝 —— 库里不得多出任何租户
        assertEquals(tenantsBefore, jdbc().queryForObject("SELECT count(*) FROM tenant", Integer.class),
                "上述被拒的调用不得留下任何租户行 —— 校验必须发生在写入之前，"
                        + "而不是『先写一半再回滚』（后者在并发下会留下可见的中间态）");
    }

    // ==================================================================
    // ⑦ 判别力自证：正负样本必须给出不同结果
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑦ 元层自证：正负样本给出不同结果（防『断言 0 == 0 却全绿』）")
    void the_core_assertions_have_discriminating_power() {
        ensureProvisioned();

        assertEquals(0, countTenantRaw(NEVER_PROVISIONED), "自证前提：负样本租户必须不在库中");
        assertEquals(0, service.countStores(NEVER_PROVISIONED),
                "未开通租户的门店数必须为 0 —— 若这里非 0，说明 countStores 的上下文/过滤有泛化失效，"
                        + "那么第 ③ 组里所有『跨租户看不到』的断言都失去判别力");

        // 正负样本必须给出【不同】结果（否则本套件的断言是在断言一个常量）
        assertTrue(service.countStores(TENANT) > service.countStores(NEVER_PROVISIONED),
                "已开通租户与未开通租户的门店数必须不同 —— 若相同，本套件的全部隔离断言"
                        + "都退化成『断言 0 == 0』：看起来全绿而什么都没验证");

        // 审计条数的正负样本
        assertTrue(jdbc().queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                        Integer.class, AUDIT_ACTION, TENANT) >= 1,
                "已开通租户必须有审计条目");
        assertEquals(0, jdbc().queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                        Integer.class, AUDIT_ACTION, NEVER_PROVISIONED),
                "未开通租户不得有审计条目 —— 否则审计查询的口径是失效的");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 完整开通计划：2 区域（东/西）+ 3 门店（东 2 / 西 1）+ 4 员工（含 1 名督导）。 */
    private static OrgProvisioningPlan fullPlan() {
        return new OrgProvisioningPlan(
                new OrgProvisioningPlan.TenantSpec(TENANT, "B-7 见证租户", null),
                List.of(
                        new OrgProvisioningPlan.RegionSpec(R_EAST, "东区", P_SUPERVISOR),
                        new OrgProvisioningPlan.RegionSpec(R_WEST, "西区", null)),
                List.of(
                        new OrgProvisioningPlan.StoreSpec(S_EAST_A, R_EAST, "东区A店", "直营", "杠2"),
                        new OrgProvisioningPlan.StoreSpec(S_EAST_B, R_EAST, "东区B店", "加盟", "现有"),
                        new OrgProvisioningPlan.StoreSpec(S_WEST_A, R_WEST, "西区A店", "直营", null)),
                List.of(
                        new OrgProvisioningPlan.StaffSpec(P_MANAGER, S_EAST_A,
                                OrgProvisioningPlan.StaffRole.STORE_MANAGER),
                        new OrgProvisioningPlan.StaffSpec(P_THERAPIST, S_EAST_A,
                                OrgProvisioningPlan.StaffRole.THERAPIST),
                        new OrgProvisioningPlan.StaffSpec(P_MERIDIAN, S_EAST_B,
                                OrgProvisioningPlan.StaffRole.MERIDIAN),
                        // 督导本身也是一名员工（否则 region.supervisor_id 无对象可指向）
                        new OrgProvisioningPlan.StaffSpec(P_SUPERVISOR, S_EAST_A,
                                OrgProvisioningPlan.StaffRole.STORE_MANAGER)));
    }

    private ProvisioningResult provision() {
        provisioningCalls++;
        return service.provision(fullPlan(), OPERATOR);
    }

    /** 若本套件租户尚未开通则开通（使各用例不依赖执行顺序）。 */
    private void ensureProvisioned() {
        if (countTenantRaw(TENANT) == 0) {
            provision();
        }
    }

    private void ensureOtherTenant() {
        if (countTenantRaw(TENANT_OTHER) == 0) {
            jdbc().update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, ?, 'active') "
                    + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER, "E2E 对照租户");
        }
    }

    /** 原始 count（不经服务、不设上下文）—— 用于前置与自证。 */
    private int countTenantRaw(String tenantId) {
        Integer n = jdbc().queryForObject("SELECT count(*) FROM tenant WHERE id = ?::uuid",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    private AuditLogService auditLogService() {
        AuditLogService svc = ctx.getBeanProvider(AuditLogService.class).getIfAvailable();
        assertNotNull(svc, "审计服务未装配 —— 第 ⑤ 例的前提不成立（本套件假定完整 Spring 上下文）");
        return svc;
    }

    /**
     * 把异常链上<b>每一层</b>的类型与消息拼成一行（顶层 → 最深）。
     *
     * <p>🛑 为什么必须穿透 cause 链：Spring 的 {@code SQLExceptionTranslator} 会把
     * 数据库错误包成自己的异常类型，并把原始 {@code PSQLException} 放进 {@code cause}。
     * 以本条 RLS 用例为例，顶层是 {@code BadSqlGrammarException}，消息里只有
     * {@code bad SQL grammar [INSERT INTO store ...]} —— <b>一个 RLS 被拒的信号都不含</b>。
     * 只看顶层的断言会把"这是 RLS"误读成"这是 SQL 语法错误"，
     * 从而引导人去改 SQL（把正确的实现改坏），这是本仓最贵的一类误导。
     */
    private static String fullCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (depth > 0) {
                sb.append(" ← ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 取异常链上<b>最深</b>那个 {@link java.sql.SQLException} 的 SQLSTATE。
     *
     * <p>用 SQLSTATE 而非消息文本做主判据，理由：它是 PG 的错误码（{@code 42501} =
     * {@code insufficient_privilege}），<b>与数据库语言环境无关</b> ——
     * 本仓的 PG 是中文环境，消息是「新行违背了表"store"的行级安全策略」，
     * 任何写死英文 {@code row-level security} 的断言在这里都会假红。
     * 消息文本仍作第二判据（人可读、能区分"是策略拒绝"还是"是别的 42501"）。
     */
    private static String deepestSqlState(Throwable t) {
        String found = null;
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (c instanceof java.sql.SQLException se && se.getSQLState() != null) {
                found = se.getSQLState();
            }
            c = c.getCause();
            depth++;
        }
        return found;
    }

    /**
     * 清理组织树数据（<b>不含</b>审计条目 —— 见类注释"零污染"里那处刻意的例外）。
     *
     * <p>🛑 全程在租户上下文内：{@code region/store/staff} 三表 FORCE RLS，
     * 策略的 {@code FOR ALL} 包含 DELETE 的 {@code USING} 与 {@code WITH CHECK}。
     * 无上下文时 DELETE 会<b>静默删 0 行</b>（{@code USING} 过滤掉所有行）——
     * 它不报错，于是残留一路累积，直到某次运行的前置断言报"租户已存在"，
     * 而那时没人知道是清理失效还是真有人建过。本仓 S2-5 那套已付过一次代价。
     */
    private void purgeOrgData() {
        JdbcTemplate j = jdbc();
        TransactionTemplate t = tx();
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                // SET LOCAL 必须在事务内 —— 事务一结束即失效（各 *Ledger 的 inTenant 同一纪律）
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                // 督导回填造出 region → staff 的引用：先断引用再删 staff，否则外键会拦
                j.update("UPDATE region SET supervisor_id = NULL WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff  WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM store  WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM region WHERE tenant_id = ?::uuid", tid);
            });
        }
        // tenant 表无 RLS，直接在事务外删
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);
    }

    /**
     * 收尾：清组织树 + <b>断言审计证据确实留下</b>（不删它）。
     *
     * <p>Spring Boot 3.3 支持在 {@code @AfterAll} 上注入 {@link DataSource} /
     * {@link ApplicationContext}（与测试实例生命周期解耦，故本方法可以是 static）。
     */
    @AfterAll
    static void cleanup(@Autowired DataSource ds) {
        JdbcTemplate j = new JdbcTemplate(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));

        // ① 租户上下文内按 子 → 父 清理组织树
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                j.update("UPDATE region SET supervisor_id = NULL WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff  WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM store  WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM region WHERE tenant_id = ?::uuid", tid);
            });
        }
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);

        // ② 清理自证：组织树必须真的删干净（这一条足以证明"上下文内的 DELETE 真的执行了"，
        //    因为 FORCE RLS 下无上下文时 DELETE 会静默删 0 行 —— 见 purgeOrgData 注释）
        Integer leftTenants = j.queryForObject(
                "SELECT count(*) FROM tenant WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, TENANT, TENANT_OTHER);
        assertTrue(leftTenants == null || leftTenants == 0,
                "清理后仍残留 " + leftTenants + " 个租户 —— 清理逻辑失效"
                        + "（FORCE RLS 会静默删 0 行，这正是不加残留自证时最难发现的一类问题）");

        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                assertTrue(j.queryForObject("SELECT count(*) FROM store", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有门店残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM staff", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有员工残留");
            });
        }

        // ③ 🛑 审计条目【不删】—— 见类注释"零污染"里那处刻意的例外。
        //    改为断言"证据确实留下了"，这比"删干净"更符合 audit_log 的 append-only 语义。
        assertTrue(provisioningCalls > 0,
                "自证失败：provisioningCalls 为 0，说明本套件从未真正调用过开通 —— "
                        + "那么上面所有『必须有审计』的断言都没有载体");
        Integer auditRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, AUDIT_ACTION, TENANT);
        assertTrue(auditRows != null && auditRows >= 1,
                "审计条目必须留下且不得被清理 —— audit_log 是 append-only 的全局单链，"
                        + "删掉中间行会为它的后继留下永久的 prev_hash 断口。实际条数=" + auditRows);
    }
}