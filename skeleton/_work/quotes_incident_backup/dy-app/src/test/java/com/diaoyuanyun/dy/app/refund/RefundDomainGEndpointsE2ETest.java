package com.diaoyuanyun.dy.app.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S2-5 证人套件 · 契约域 G（退款与挽留）五端点 <b>G1~G5 的真请求回归</b>。
 *
 * <h2>为什么 S2-4 已经有 55 条断言，还必须再写这一套</h2>
 * S2-4 的 {@code RefundWorkOrderEndpointTest} 直调服务层与控制器对象 ——
 * 它证明的是「<b>判定逻辑写对了</b>」。但它对下面这一整类失守<b>完全看不见</b>：
 * <pre>
 *   装配层（WebConfig 拦截器注册 / 顺序）
 *   @RequirePermission 的权限码是否真的登记在 PermissionRegistry 里
 *   @StaffOnly 是否真的被全局拦截器读到（而不是只贴在类上）
 *   tenant 上下文过滤器是否真的把 token 的角色写进了 TenantContext
 * </pre>
 * 这些失守的共同形态是：<b>239 个单测全绿，而应用起不来 / 端点全 403</b>。
 * 骨架早期正是被这件事抓过一次（见 {@code TierAuthorizationE2ETest} 类注释）。
 *
 * <h2>🛑 本套件抓出的真实缺陷（本类的存在理由）</h2>
 * <b>缺陷：{@code PermissionRegistry} 未登记任何 {@code refund:*} 权限码。</b>
 * S2-4 给控制器贴了 6 处 {@code @RequirePermission("refund:read"/"write"/"approve")}，
 * 而 {@code PermissionRegistry} 里<b>没有任何一个角色持有这些码</b> ——
 * {@code hasPermission} 对全部角色恒 false ⇒ 真实请求下
 * <b>经络师 / 门店负责人 / 区域督导 / 总部运营调 G1~G5 一律 403</b>。
 * 契约逐字写着这五条端点的 {@code x-callable-roles} 含 {@code meridian} 与 {@code admin}，
 * 即<b>契约声明可调的端点，对全部 staff 角色实际不可调</b>。
 *
 * <p>这类缺陷的隐蔽性在于它<b>不报错</b>：服务层单测直调 {@code requireVisible}，
 * 根本不经过 {@code PermissionInterceptor}，于是 55 条断言全绿、
 * 门禁全 PASS、构建 BUILD SUCCESS —— 而线上退款域整体不可用。
 * 唯一能看见它的地方，就是"用真 JWT 发真 HTTP"。
 *
 * <h2>🛑 零落库优先：用「404 vs 403」把装配链变成可判定的对象</h2>
 * 本套件的主体断言<b>不写任何数据</b>：全部用一个<b>不存在的工单 UUID</b>。
 * 这样做的收益是能把"拒绝发生在哪一层"变成<b>可分辨的事实</b>：
 * <pre>
 *   客户调 G2 + 不存在的 ID   → 403(2001)  ⇒ 拒绝发生在【端点级】，与参数无关
 *   经络师调 G2 + 不存在的 ID → 404(3001)  ⇒ 他【走过了】可见性与权限两层，
 *                                            一路到业务层才因"查无此单"失败
 * </pre>
 * 若只断言"经络师拿到 200"，就必须真的落一张工单（引入底数据与清理），
 * 而"404 而非 403"这个判据的证明力更强：它排除了"他被拒了但恰好表现得像放行"。
 * 同时它让本套件可<b>反复跑、零污染</b>。
 *
 * <p>落库往返（G1 立案 → G2 读回）另有一组断言，用它证明真实写链路可跑通，
 * 并钉住"库侧 {@code entry} 字面（有空格）与契约字面（无空格）在出站处必须取契约值"。
 *
 * <h2>判据锚定外部真相源</h2>
 * <ul>
 *   <li>{@code x-client-forbidden: true} → 契约域 G 五条端点行（{@code openapi-v1.0.0.yaml}
 *       L983~L1103）；上游逐字：「本域全部接口：客户端与调理师端一律 403 VISIBILITY_DENIED」；</li>
 *   <li>错误码 2001 / 2003 / 3001 → 契约 §2.0 错误码表；</li>
 *   <li>「区域督导可见、不审批」→ PRD §2.2（也是契约 G4 {@code x-ruling-pending} 的来源）。</li>
 * </ul>
 * 断言比对的是这些外部约定，不是本实现的返回值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S2-5 · 契约域 G 五端点真请求回归")
class RefundDomainGEndpointsE2ETest {

    /** 与 application.yml 的 dev 占位密钥一致。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本套件自建的租户（与 RLS 门禁 / 既有 E2E 的租户 ID 零交集，便于精确清理）。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000e2e0";

    /** 一个<b>必然不存在</b>的工单 ID —— 本套件用它把"拒绝在哪一层"变成可判定的对象。 */
    private static final String ABSENT_REFUND = "e2e00000-0000-0000-0000-0000000000ff";

    /** 本套件落库往返用的固定标识（前缀 e2e 打头，便于精确清理，零撞既有数据）。 */
    private static final String S_REGION = "e2e00000-0000-0000-0000-000000000001";
    private static final String S_STORE = "e2e00000-0000-0000-0000-000000000002";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-000000000003";
    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-000000000004";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource springDataSource;

    // ==================================================================
    // 底数据（仅服务于"落库往返"那一组断言）
    // ==================================================================

    /**
     * 获取数据源并（仅一次）灌底数据。
     *
     * <p>🛑 刻意<b>不</b>用 {@code @BeforeAll static} 注入 {@code DataSource}：
     * 那需要 {@code @TestInstance(PER_CLASS)}，而它与 {@code @Nested} 内嵌类的
     * 实例生命周期规则交互微妙（内嵌类的 {@code @BeforeAll} 语义与顶层不同）。
     * 用一个幂等的实例钩子换取"无论嵌套多深都只灌一次"，比依赖那条规则更不容易被踩坏。
     */
    @BeforeEach
    void ensureSeeded() {
        if (jdbc == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
        if (!seeded) {
            seedOnce();
            seeded = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (jdbc == null) {
            return;
        }
        // 按【子 → 父】FK 序清理本套件自建的行。
        //
        // 🛑 两个盲点，都会让清理"看起来执行了、其实没删掉"：
        //   ① 【本套件的 refund_id 不是它自己生成的】—— 它由服务端 UUID.randomUUID() 产出，
        //      <b>不含</b> e2e 前缀。故不能按 refund_id LIKE 'e2e…' 删子表
        //      （那样一条也删不掉，随后删 customer 会撞 FK）。
        //      正确做法：先用【本租户】圈定 refund 行，再据其 id 删子表。
        //      （本套件独占 TENANT，故"按租户圈定"与"按 e2e 前缀圈定"等价且更可靠 ——
        //        不依赖服务端 ID 生成策略。）
        //   ② 其余表都 FORCE RLS，故清理也必须在租户上下文内进行
        //      （否则 WITH CHECK 会挡住 DELETE 的可见行，删除静默返回 0 行）。
        inTenant(() -> {
            jdbc.update("DELETE FROM refund_statement WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM refund_receipt   WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM refund_offline_notice WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM retention        WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM refund           WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'",
                    TENANT);
            return null;
        });
        // tenant 表无 RLS（它是租户的宿主），可在无上下文时删除。
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    /**
     * 灌底数据：tenant（无 RLS）→ region/store/staff/customer（FORCE RLS，须在租户上下文内写）。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 使其幂等 —— 重跑本套件不会撞主键。
     * 之所以用 Spring 注入的 <b>app 用户</b>（{@code diaoyuanyun}）而不是超级用户：
     * 这样灌底数据这件事本身也走一遍 RLS 的 {@code WITH CHECK}，
     * 若哪天策略被改坏，本套件会以"底数据建不起来"的形式先报出来，
     * 而不是等到断言阶段才发现表是空的。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S2-5') "
                + "ON CONFLICT DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) VALUES (?::uuid, ?::uuid, 'E2E区域') "
                    + "ON CONFLICT DO NOTHING", S_REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'E2E门店', '直营') ON CONFLICT DO NOTHING",
                    S_STORE, TENANT, S_REGION);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, S_STORE);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E客户', 'active') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            return null;
        });
    }

    /** 在租户上下文内执行（{@code SET LOCAL app.tenant_id}）——FORCE RLS 下写入的前提。 */
    private static <T> T inTenant(java.util.function.Supplier<T> body) {
        return tx.execute(s -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // 一、客户一律 403（契约 x-client-forbidden，与参数无关）
    // ==================================================================

    @Nested
    @DisplayName("一 · 客户一律 403（契约 x-client-forbidden）")
    class ClientForbidden {

        /**
         * 🛑 判据是「<b>403 而不是 404</b>」，而不是"被拒了"。
         *
         * <p>客户带的是<b>一个不存在的工单 ID</b>。若端点级防线失守，
         * 请求会一路走到业务层，然后因"查无此单"返回 <b>404</b>。
         * 故只断言"非 200"是不够的 —— 404 也会让"非 200"成立，
         * 而它证明的恰好是<b>相反</b>的事：防线没拦住，只是恰好没数据。
         * 断言 403 才能把"拒绝发生在端点级"钉死。
         */
        @Test
        @DisplayName("G2 详情: 客户 403 而非 404 —— 必须拒在端点级，不是『恰好查无此单』")
        void client_is_rejected_at_the_endpoint_not_by_a_missing_row() {
            ResponseEntity<String> resp = get("/api/v1/refunds/" + ABSENT_REFUND, token("client", "own_store"));

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "客户调 G2 必须 403（契约 x-client-forbidden）。若拿到 404，说明端点级防线没拦住，"
                            + "请求只是恰好因数据不存在而失败 —— 两者是完全不同的失守。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(2001, code(resp), "应报 2001 VISIBILITY_DENIED（契约 §2.0）");
        }

        @Test
        @DisplayName("G1/G3/G4/G5: 客户在四个写端点上全被 403 拦下，且回显 denied_fields")
        void client_is_rejected_on_all_four_write_endpoints_with_named_fields() {
            record Case(String label, String method, String path, String body) {
            }
            List<Case> cases = List.of(
                    new Case("G1 代录", "POST", "/api/v1/refunds", createBody("A门店代录")),
                    // 🛑 G3 的 analysis / communication 传 JSON 对象 —— 端侧最自然的写法。
                    //    曾只传字符串形态（且当时会被 500 拒掉，见 C-3 套件注释）；
                    //    这里改成对象，使"客户被 403"这条断言之外，探针本身就是合法入参。
                    new Case("G3 挽留", "POST", "/api/v1/refunds/" + ABSENT_REFUND + "/retentions",
                            "{\"attempts\":1,\"result\":\"接受继续服务\","
                                    + "\"analysis\":{\"五维\":\"频次不足\"},"
                                    + "\"communication\":{\"渠道\":\"电话\"}}"),
                    new Case("G4 审批", "POST", "/api/v1/refunds/" + ABSENT_REFUND + "/approvals",
                            "{\"decision\":\"approve\"}"),
                    // 🛑 G5 显式带 push_succeeded=true：缺省是 false（Boolean.TRUE.equals(null)），
                    //    那会走"推送失败"分支 —— 该分支在 C-3 修复前恒报 5001。
                    //    本用例断言的是"客户被 403"，探针不应引入无关分支。
                    new Case("G5 回执", "POST", "/api/v1/refunds/" + ABSENT_REFUND + "/receipts",
                            "{\"subscription_quota\":1,\"push_succeeded\":true}"));

            for (Case c : cases) {
                ResponseEntity<String> resp = send(c.method(), c.path(), token("client", "own_store"), c.body());
                assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                        c.label() + ": 客户必须被 403 拦下（契约 x-client-forbidden: true）。实际: "
                                + resp.getStatusCode() + " body=" + resp.getBody());
                assertEquals(2001, code(resp), c.label() + " 应报 2001");

                // 契约 §3.2 + P0-08「不得模糊报错」：被拒必须回显被拒字段名，不能只给一句"无权"。
                JsonNode denied = deniedFields(resp);
                assertTrue(denied.isArray() && denied.size() > 0,
                        c.label() + ": 403 必须回显 denied_fields（P0-08 不得模糊报错）。实际 body="
                                + resp.getBody());
            }
        }
    }

    // ==================================================================
    // 二、装配链：权限码登记 + 可见性两段式（用 404 vs 403 分辨层次）
    // ==================================================================

    @Nested
    @DisplayName("二 · 装配链（权限码 / 可见性 / 上下文）")
    class Wiring {

        /**
         * 🛑 本组是本套件最有价值的一组：它抓的正是"单测全绿但装配失守"。
         *
         * <p>契约 G2 的 {@code x-callable-roles: [meridian, admin]}。故经络师、
         * 门店负责人（manager）、区域督导（area）、总部运营（hq）都必须能<b>调用</b>它。
         * 判据取 <b>404</b>：他走到了业务层（说明权限层与可见性层都放行了他），
         * 只是这个 ID 恰好不存在。
         */
        @Test
        @DisplayName("契约 x-callable-roles 的 staff 角色调 G2 必须【走过装配链】(404, 而非 403)")
        void contract_callable_roles_reach_the_business_layer_on_g2() {
            // meridian / manager / area / hq —— 逐字取自契约 G2 的 x-callable-roles（admin → manager,area,hq）
            for (String role : List.of("meridian", "manager", "area", "hq")) {
                ResponseEntity<String> resp = get("/api/v1/refunds/" + ABSENT_REFUND, token(role, "own_store"));

                assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                        "角色 " + role + " 调 G2 应一路走到业务层后因『查无此单』返回 404（3001）；"
                                + "实际 " + resp.getStatusCode() + " body=" + resp.getBody()
                                + "。🛑 若拿到 403，说明装配链把契约声明可调的端点拒了 —— "
                                + "最常见的成因是 @RequirePermission 的权限码未登记进 PermissionRegistry"
                                + "（单测直调服务层，永远看不见这一层）。");
                assertEquals(3001, code(resp), role + " 应报 3001 NOT_FOUND（而非 2001/2003）");
            }
        }

        @Test
        @DisplayName("可见性档位：调理师与门店客服对退款域无档位 → 403(2001)，与『未登记角色』可区分")
        void roles_without_a_visibility_tier_are_denied_with_2001() {
            // 调理师（therapist）：契约 G 域上游逐字「客户端与调理师端一律 403 VISIBILITY_DENIED」
            ResponseEntity<String> therapist = get("/api/v1/refunds/" + ABSENT_REFUND, token("therapist", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, therapist.getStatusCode(),
                    "调理师对退款域无可见性档位，必须 403。实际: " + therapist.getStatusCode());
            assertEquals(2001, code(therapist), "应报 2001（档位缺失），而不是 2003（身份不可信）");
        }

        /**
         * 🛑 本组唯一的"分层"断言：钉住拦截器链上<b>权限层确实在拦</b>。
         *
         * <p>理由是一条容易被忽略的推论：G2 的 {@code @RequirePermission("refund:read")}
         * 若被删掉（单测直调服务层，看不见这件事），整条装配链上就只剩
         * {@code workOrders.requireVisible(...)} 那一层在挡 {@code therapist}。
         * 届时他的响应形态会从 <b>403「权限不足: refund:read」</b>
         * 变成 <b>404「查无此单」</b> —— 后者<b>看起来像"装配链通了"</b>，
         * 与契约要的"调理师一律 403"正好相反（他反而被放进了业务层）。
         *
         * <p>故这里断言两件事，缺一不可：
         * <ol>
         *   <li>状态是 <b>403</b>（不是 404）—— 他被挡在业务层之外；</li>
         *   <li>消息里含被拒的<b>权限码</b> {@code refund:read} —— 拦他的是归属明确的
         *       那一层，而不是某条兜底防线。</li>
         * </ol>
         * 第 2 条是关键：只断言 403 的话，"权限注解被删、仅靠服务层拒绝"也能通过 ——
         * 而那时"岗位功能权限"这整层对退款域就静默失效了。
         *
         * <p>为什么 therapist 由权限层拦、而 client 由端点级拦？因为拦截器链顺序是
         * 幂等 → 派生可见性（{@code @StaffOnly} 的落点）→ <b>权限</b> → 门禁 → 层级：
         * client 在第二步就被 {@code @StaffOnly} 拦下，therapist 走到第三步才被拦。
         * 两人<b>都</b>该被拒，但"由哪一层拒"不同 —— 本断言把 therapist 这一侧钉住，
         * {@code ClientForbidden} 组把 client 那一侧钉住。
         */
        @Test
        @DisplayName("权限层必须在岗：调理师被拒的必须是【权限码】层面（不是 404『恰好查无此单』）")
        void therapist_is_stopped_by_the_permission_layer_not_by_a_missing_row() {
            ResponseEntity<String> resp = get("/api/v1/refunds/" + ABSENT_REFUND,
                    token("therapist", "own_store"));

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "🛑 调理师调 G2 必须 403。若拿到 404，说明 @RequirePermission 没拦住他，"
                            + "请求被放进了业务层 —— 看起来像『装配链通了』，"
                            + "而契约要的恰恰是『调理师端一律 403』。实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());
            assertTrue(message(resp).contains("refund:read"),
                    "🛑 拦下调理师的必须是【权限层】—— 其消息含被拒权限码 refund:read。"
                            + "若消息是档位类文案，说明权限注解对退款域已失效"
                            + "（岗位功能权限这一层静默退化）。实际消息: " + message(resp));
        }

        @Test
        @DisplayName("未登记的角色码必须 403（fail-closed）—— 且报 2001 档位缺失，绝不是 2003（租户不匹配）")
        void unknown_role_code_is_rejected_as_visibility_denied_not_tenant_mismatch() {
            // 一个系统不认识的 token 角色（把 meridian 加后缀构造）
            ResponseEntity<String> resp = get("/api/v1/refunds/" + ABSENT_REFUND,
                    token("meridian_therapist_TYPO", "own_store"));

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "未登记角色必须被拒（fail-closed，且绝不回落为某个档位）。实际: " + resp.getStatusCode());

            // 🛑 判据锚定契约 §2.0 错误码表，而不是"感觉哪个更像"：
            //   2001 VISIBILITY_DENIED = 该角色对请求字段组无可见性档位
            //   2003 TENANT_MISMATCH    = 跨租户访问 / 租户头与 token 不符
            // 契约里【没有】"未知角色"这一码 —— 未登记角色无法对应到任何受众，
            // 语义上就是"无档位"，故必须落到 2001。若这里报 2003，
            // 说明实现把"角色不认识"误映射成了"租户不匹配"，
            // 排查时会朝错误的方向（查租户头）去找，而真正的问题是配置侧多了个角色码。
            assertEquals(2001, code(resp),
                    "🛑 未登记角色应报 2001（无档位），而【不是】2003（TENANT_MISMATCH = 跨租户）。"
                            + "两者排查方向完全不同。实际 body=" + resp.getBody());
        }

        /**
         * 🛑 匿名必须被拒，且判据同样是「403 而<b>不是</b> 200」。
         *
         * <p>契约 A1 逐字：「租户与角色上下文只来自 token」。故不带 token 时
         * {@code TenantContext.role()} 为 null，派生字段拦截器的 {@code isClientLike(null)}
         * 判为 true（fail-closed 按客户处理）⇒ 端点级 403。
         * 于是"匿名 → 403"与"客户 → 403"落在同一条防线上，这是<b>刻意</b>的：
         * 若匿名被当作"未知角色"而跳过，就会出现"把 token 去掉反而绕过端点级拒绝"的可利用组合。
         */
        @Test
        @DisplayName("匿名调 G2 必须 403 —— 去掉 token 反而绕过防线是不可接受的")
        void anonymous_is_rejected_and_must_not_bypass_the_endpoint_gate() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = rest.exchange(url("/api/v1/refunds/" + ABSENT_REFUND),
                    HttpMethod.GET, new HttpEntity<>(null, headers), String.class);

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "匿名调用退款域端点必须 403（fail-closed）。实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());
        }

        @Test
        @DisplayName("伪签名 token 必须 401 —— 绝不降级为匿名请求")
        void tampered_token_is_rejected_as_unauthenticated_not_downgraded_to_anonymous() {
            String good = token("meridian", "own_store");
            String badSig = tamperSignature(good);

            ResponseEntity<String> resp = get("/api/v1/refunds/" + ABSENT_REFUND, badSig);

            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                    "🛑 验签失败必须 401，绝不降级为匿名请求 —— 否则攻击者只要带一个坏 token "
                            + "就能绕过鉴权。实际: " + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(1002, code(resp), "应报 1002 UNAUTHENTICATED（契约 §2.0）");
        }
    }

    // ==================================================================
    // 三、G4「可见 ≠ 可审批」在真实链路上可复现（契约 x-ruling-pending）
    // ==================================================================

    @Nested
    @DisplayName("三 · G4 审批白名单（契约 x-ruling-pending 的前提在装配链上成立）")
    class ApprovalWhitelist {

        /**
         * 🛑 契约 G4 的 {@code x-ruling-pending} 依赖一个<b>装配层前提</b>，
         * 而这个前提只有真请求能验证：<b>区域督导必须过得了权限层</b>。
         *
         * <p>契约 G4 逐字 {@code x-callable-roles: [admin]}，展开 = {manager, area, hq}；
         * PRD §2.2 逐字写明督导「✓（<b>可见</b>、<b>不审批</b>）」。
         * 两者共同要求的分层是：
         * <pre>
         *   area ──权限层(refund:approve)──▶ 通过 ──可见性层──▶ 通过 ──白名单闸──▶ 拒（不属审批人集合）
         * </pre>
         * 判据取 <b>404</b>：他走到了业务层（权限层与可见性层都放行了他），
         * 只是这个工单 ID 恰好不存在。
         *
         * <h2>🛑 若 {area} 未持有 {@code refund:approve}，本断言会以 403 失败 —— 而那种失败极有价值</h2>
         * 他会收到「权限不足: refund:approve」，即<b>在权限层就被拒</b>；
         * 于是 G4 的白名单闸<b>永远不会被执行</b>，
         * {@code x-ruling-pending} 记录的那条规则在实现里形同不存在 ——
         * 而他的"被拒"看起来却和第 5 组的期望一致。区分这两者正是本断言的存在理由。
         *
         * <h2>为什么"白名单闸真的拒了 area"不在这里断言</h2>
         * 因为它需要一张<b>真实存在的、入口 A + 终止</b>的工单：
         * {@code approve()} 的闸序是 requireVisible → requireExisting(<b>404 先抛</b>)
         * → 闸②审批落点 → 闸③白名单。用不存在的 ID 根本<b>到不了</b>白名单闸。
         * 而 {@code outcome=终止} 需 {@code ESCALATE_HEADQUARTERS}（入口 A）
         * 或 {@code FULL_EXEMPT_FIRST_CYCLE}（入口 B，该分支直接不出总部审批 → 闸②先抛 5001）。
         * 也就是说，在本端点用"不存在 ID + 零落库"是<b>结构性</b>到不了白名单闸的。
         *
         * <p>故本套件在这里只钉<b>前提</b>（area 能进），把白名单闸本身的"真拒"
         * 留给 {@code RefundWorkOrderEndpointTest.ApprovalGate} —— 那组直调服务层，
         * 能造出任意 outcome 的工单并断言消息含「不属退款审批人集合」。
         * <b>分层覆盖：E2E 证前提，单测证规则。</b>
         * 两者缺一，都会留下"看起来全绿但某层从未被执行"的盲区。
         */
        @Test
        @DisplayName("区域督导调 G4 必须【走过权限层】(404, 而非 403 权限不足) —— x-ruling-pending 的装配前提")
        void area_supervisor_passes_the_permission_layer_on_g4() {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.setBearerAuth(token("area", "region"));
            // 带上代录人身份头：本断言要证明"他能进到业务层"，
            // 而不是被"缺身份"这类无关分支挡下（那会掩盖权限层的真实结论）。
            h.set("X-Deputy-Operator-Id", UUID.randomUUID().toString());

            ResponseEntity<String> resp = rest.exchange(
                    url("/api/v1/refunds/" + ABSENT_REFUND + "/approvals"),
                    HttpMethod.POST, new HttpEntity<>("{\"decision\":\"approve\"}", h), String.class);

            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                    "🛑 区域督导调 G4 必须一路走到业务层后因『查无此单』返回 404（3001）；"
                            + "实际 " + resp.getStatusCode() + " body=" + resp.getBody()
                            + "。若拿到 403 且消息为『权限不足: refund:approve』，说明 "
                            + "PermissionRegistry 未把 refund:approve 登记给 area —— "
                            + "于是契约 G4 的 x-callable-roles: [admin] 对督导形同虚设，"
                            + "而那条审批白名单闸（x-ruling-pending 的落点）从未有机会执行。");
            assertEquals(3001, code(resp), "应报 3001 NOT_FOUND（而非 2001）");
        }
    }

    // ==================================================================
    // 四、真实落库往返（G1 立案 → G2 读回）
    // ==================================================================

    @Nested
    @DisplayName("四 · 真实落库往返（G1 立案 → G2 读回）")
    class RealRoundTrip {

        /**
         * 🛑 出站 {@code entry} 必须是<b>契约字面</b>（{@code B首周期}，无空格），
         * 而库侧存的是 {@code 'B 首周期'}（有空格）。
         *
         * <p>两套字面都已冻结、都不是笔误。本断言的价值在于：它把"内存里混流两套字面"
         * 这件事变成一次<b>出站可见的事实</b>。若控制器误取 {@code dbCode()}，
         * 端侧逐字比对会红 —— 而那种红最容易被误判成"契约写错了"。
         */
        @Test
        @DisplayName("G1 立案 → G2 读回: 出站 entry 取【契约字面】，且库侧确为 dbCode（两套字面都被验证）")
        void entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own() {
            // ① G1 立案（入口 B：首周期自动触发，三字段全空 —— 恰好可以走"无 requested_at"的路径）
            ResponseEntity<String> created = post("/api/v1/refunds",
                    token("meridian", "own_store"), createBody("B首周期"));

            assertEquals(HttpStatus.OK, created.getStatusCode(),
                    "经络师代录立案应成功（契约 G1 x-callable-roles 含 meridian）。实际: "
                            + created.getStatusCode() + " body=" + created.getBody());

            String refundId = data(created).path("refund_id").asText();
            assertNotNull(refundId, "立案响应必须回显 refund_id。实际 body=" + created.getBody());
            assertFalse(refundId.isBlank(), "refund_id 不得为空");

            // ② G2 读回（同一个真实工单）
            ResponseEntity<String> detail = get("/api/v1/refunds/" + refundId, token("meridian", "own_store"));
            assertEquals(HttpStatus.OK, detail.getStatusCode(),
                    "刚立案的工单必须能从 G2 读回。实际: " + detail.getStatusCode()
                            + " body=" + detail.getBody());
            assertEquals(refundId, data(detail).path("refund_id").asText(),
                    "G2 读回的工单必须是刚立案的那一张");

            // ③ 契约 RefundData 的五项字段必须齐备（多一项少一项都属契约外）
            JsonNode d = data(detail);
            for (String f : List.of("refund_id", "liable_store_id", "sla_due_at",
                    "recording_delay_h", "outcome")) {
                assertTrue(d.has(f), "契约 RefundData 必须含字段 " + f + "。实际: " + d);
            }

            // ④ 🛑 库侧字面自证：库里存的必须是 dbCode（有空格）。
            //    若仓储误绑 contractCode，这条会红 —— 而那时错误信息里只有约束名，
            //    看不出"两套字面"这件事（见 RefundWorkOrderLedger 的类注释）。
            //
            //    🛑 必须在【租户上下文内】查：refund 是 FORCE RLS 表，
            //    不带 app.tenant_id 时即便拿着正确的 refund_id 也查不到行
            //    （实测报 EmptyResultDataAccessException: expected 1, actual 0）。
            //    这个"必须带上下文才看得见自己的行"本身就是 RLS 生效的正面证据 ——
            //    它顺带证明了隔离不是靠 SQL 里的 WHERE tenant_id 拼出来的。
            String dbEntry = inTenant(() -> jdbc.queryForObject(
                    "SELECT entry FROM refund WHERE refund_id = ?::uuid", String.class, refundId));
            assertEquals("B 首周期", dbEntry,
                    "🛑 库侧 entry 必须是有空格的 dbCode（V5 §2.20 的 CHECK 只认它）。实际=" + dbEntry);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** G1 请求体：{@code entry} 传契约字面（无空格）。 */
    private static String createBody(String entryContractLiteral) {
        return "“"
                {
                  "customer_id": "%s",
                  "entry": "%s",
                  "refund_route": "效果类",
                  "reason_code": "效果未达预期",
                  "liable_store_id": "%s",
                  "customer_statement": "S2-5 往返探针：客户原话"
                }
                "”".formatted(S_CUSTOMER, entryContractLiteral, S_STORE);
    }

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    private ResponseEntity<String> post(String path, String jwt, String body) {
        return send("POST", path, jwt, body);
    }

    private ResponseEntity<String> send(String method, String path, String jwt, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange(url(path), HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** 读取信封 code（契约 §2.0 四字段之一）。 */
    private int code(ResponseEntity<String> resp) {
        try {
            return MAPPER.readTree(resp.getBody()).path("code").asInt();
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /** 读取信封 message 原文 —— 用于把"同码不同因"写进断言失败信息（如 2001 的两个出处）。 */
    @SuppressWarnings("unused")
    private String message(ResponseEntity<String> resp) {
        try {
            return MAPPER.readTree(resp.getBody()).path("message").asText("");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    private JsonNode data(ResponseEntity<String> resp) {
        try {
            return MAPPER.readTree(resp.getBody()).path("data");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /**
     * 读取 403 信封中被拒字段名数组（{@code data.denied_fields}）——P0-08「不得模糊报错」的判据载体。
     *
     * <p>与 {@link #data} 同范式：{@code ObjectMapper.readTree(String)} 抛受检异常，
     * 故在此统一收敛，调用点只面对 {@link AssertionError}。折叠成助手还有一个好处：
     * 若信封形状变更，报错点唯一、易定位。
     */
    private JsonNode deniedFields(ResponseEntity<String> resp) {
        try {
            String body = resp.getBody();
            return MAPPER.readTree(body == null ? "{}" : body).path("data").path("denied_fields");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /** 真签名 JWT（HS256）。角色/范围进 token —— 契约 A1：租户与角色上下文只来自 token。 */
    private static String token(String role, String scope) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + staffIdFor(role) + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"scope\":\"" + scope + "\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
        return sign(header, payload);
    }

    /**
     * token 里的 {@code staff_id} —— 必须是<b>合法 UUID</b>。
     *
     * <h2>🛑 首版用 {@code "S-" + role}（如 {@code "S-area"}），在 G4 上炸出 400 而非预期的 404</h2>
     * 契约 A1 逐字「租户与角色上下文<b>只来自 token</b>」，故 {@code staff_id} 是服务端
     * 唯一的"操作人是谁"的真相源。而 G4 审批把它当 {@code approver_id} 用
     * （请求体里没有 {@code approver_id}）—— 控制器
     * {@code uuid(TenantContext.staffId(), "approver_id")} 在 {@code staff_id} 非 UUID 时
     * 抛 {@code VALIDATION_FAILED(1001)} ⇒ <b>400</b>。
     *
     * <p>那个 400 把本套件真正想问的问题（<b>权限层有没有放行 area</b>）掩盖成一次
     * 无关的参数校验失败：断言期望 404、实得 400，失败信息指向"staff_id 不合法"，
     * 而读者会以为需要修的是测试数据，不是装配链。
     *
     * <p>用 {@link UUID#nameUUIDFromBytes} 使同一个 role 恒得同一个 UUID ——
     * 既天然合法，又<b>确定性</b>（便于跨次运行比对，也避免"每次跑都不同 ID"掩盖断言）。
     */
    private static String staffIdFor(String role) {
        return UUID.nameUUIDFromBytes(("staff:" + role).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sign(String header, String payload) {
        String input = b64(header) + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造一个<b>必然验签失败</b>的 token（保留三段结构与长度，只让签名值变）。
     *
     * <h2>🛑 为什么不能"改签名段最后一个字符"—— 那是一个真实存在、会静默失效的陷阱</h2>
     * HS256 签名是 32 字节 ⇒ base64url 无 padding 编码为 <b>43</b> 字符。
     * 43 字符不是 4 的倍数，故<b>最后一个字符只有高 4 位承载数据、低 2 位被解码器忽略</b>——
     * 于是末位字符构成 4 个一组的<b>等价类</b>：
     * <pre>
     *   {A,E,I,M,Q,U,Y,c,g,k,o,s,w,0,4,8}  ← 低 2 位 = 00
     *   {B,F,J,N,R,V,Z,d,h,l,p,t,x,1,5,9}  ← 低 2 位 = 01
     *   {C,G,K,O,S,W,a,e,i,m,q,u,y,2,6,-}  ← 低 2 位 = 10
     *   {D,H,L,P,T,X,b,f,j,n,r,v,z,3,7,_}  ← 低 2 位 = 11
     * </pre>
     * 若把末位 <em>在等价类内</em>改一个字符（如 {@code B→A}），<b>解码出的签名字节完全相同</b>
     * ⇒ 验签<b>通过</b> ⇒ 这条"必须 401"的断言就会变成一次<b>假绿</b>：
     * 它报告"伪签名被正确拒绝"，而实际上那个 token 是合法签名的。
     * 更坏的是这个假绿<b>依赖当次 token 的末位字符</b>，是 flaky 的 ——
     * 今天红、明天绿，排查时最容易被误判成"环境问题"。
     *
     * <p>故这里改的是<b>签名段首个字符</b>：它在 base64 里承载完整 6 位，
     * 落进第一个签名字节的高 6 位 ⇒ 任何改动都必然改变解码结果。
     * 唯一残留的边界是"新字符与原字符恰好相等"——{@code 'A'→'B'} 与 {@code 'B'→'A'}
     * 两种取值恒不等，故不可能退化。
     *
     * <p>（本注释留痕：该陷阱是撰写本套件时<b>实测踩到</b>的 —— 首版用"改末位"，
     * 断言报的是 {@code 403 权限不足} 而非 {@code 401}，即 token 被验签通过了。）
     */
    private static String tamperSignature(String jwt) {
        int lastDot = jwt.lastIndexOf('.');
        if (lastDot < 0 || lastDot == jwt.length() - 1) {
            throw new IllegalArgumentException("非法 JWT（无签名段）: " + jwt);
        }
        String head = jwt.substring(0, lastDot + 1);
        String sig = jwt.substring(lastDot + 1);
        char first = sig.charAt(0);
        char flipped = first == 'A' ? 'B' : 'A';
        return head + flipped + sig.substring(1);
    }
}