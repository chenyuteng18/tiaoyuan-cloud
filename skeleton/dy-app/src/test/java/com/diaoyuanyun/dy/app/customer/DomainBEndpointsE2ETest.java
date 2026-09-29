package com.diaoyuanyun.dy.app.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-10 证人套件 · 契约域 B（B1~B6）的<b>真请求回归</b>。
 *
 * <h2>为什么域 B 的七类断言必须"真请求"</h2>
 * 域 B 的领域层与单测已经覆盖了"规则写对了"：
 * {@code CustomerGateGuard} 的顺序、{@code CustomerFieldVisibility} 的 include 判据、
 * {@code ContraindicationPolicy} 的推导、{@code IntakeProfileLedger} 的 append-only。
 * 但下面这些事<b>单测看不见</b>（与域 A 那套同源的五类 + 域 B 特有的两类）：
 * <pre>
 *  ① 路由：六行的真实路径是否为契约 paths（不含基路径 /api/v1，靠 servers 承载）
 *  ② 权限：【本次最重要】B1/B2/B3/B6 的可调用角色集是否与契约 x-callable-roles 一致
 *     —— 它对应的权限码在落码时是 customer:write，而该码从未授予 therapist/meridian
 *  ③ 装配：TenantContextFilter 是否真的把 token 的 role / staff_id 写进 TenantContext
 *     （B1 的 operator_id 与 B2 的 owner_store_id 都从它解算，装配断了会表现为 1001 而非门禁错）
 *  ④ 出站：B4 的裁剪是否真的在<b>响应体</b>上生效（而不是只在内部分支里）
 *  ⑤ 装备：@Service 是否被扫描到（域 B 是 @Service 自注册，与 A2 的显式 @Bean 不同）
 *  ⑥ 域 B 特有：门禁的 403 是否真的以 GATE_MISSING(2002) + 逐字 missing_items 出站
 *     —— B1 声明集【无 403】，B2/B3 声明【403 GateMissing】，B5/B6 声明【仅 200】
 *  ⑦ 域 B 特有：B5/B6 在"客户不存在 / 未建档"时是否真的走 200 而不是 404/403
 *     —— 这两行只声明 200，"顺手加一个 404"是极自然的错误
 * </pre>
 *
 * <h2>🛑 判据设计一：401 / 403 / 200 的分辨力</h2>
 * 域 B 六行的拒绝语义<b>不是同一个码</b>，必须逐行分辨：
 * <pre>
 *  无 token 调 B4                → 403(TENANT_MISMATCH 2003) 而非 401
 *                                  —— 域 B 全部行都要求租户上下文，且 B4 声明集无 401
 *  客户 token 调 B1/B2/B3/B6     → 403(VISIBILITY_DENIED 2001) ⇒ 服务层 requireCallable 显式点名
 *  客户 token 调 B4/B5           → 200（契约含 client；字段级裁剪是另一层）
 *  调理师/经络师调 B1/B2/B3/B6   → 200 ⇒ 🛑 本次最关键的断言：这四行【必须】对一线角色放行
 *  缺前置（无通过筛查 / 未建档） → 403(GATE_MISSING 2002) + 逐字 missing_items
 *  查无此客户（B4）              → 404(NOT_FOUND 3001) ⇒ B4 声明了 404
 *  查无此客户（B5）              → 200 + 空 data ⇒ B5 只声明 200
 * </pre>
 *
 * <h2>🛑 判据设计二：本套件要抓住的那个具体缺陷</h2>
 * 落码时 B1/B2/B3/B6 贴的是 {@code @RequirePermission("customer:write")}，
 * 而 {@code PermissionRegistry} 的 {@code customer:write} 持有者是
 * <b>管理层级 + 遗留大写码</b>，<b>不含</b> therapist / meridian。
 * 于是契约逐字声明可调的四个端点，对契约点名的两个一线角色<b>全部 403</b>。
 *
 * <p>它躲过了<b>全部</b>既有门禁的原因很具体：
 * <b>{@code customer:write} 是"有主"的</b>（manager/area/hq 持有），
 * 故 {@code PermissionCodeRegistrationGateTest} 的"每个码必须有主"那条<b>全程是绿的</b>。
 * 那个门禁在类注释里已明说自己是<b>码级</b>而非<b>角色级</b> ——
 * 本次缺口恰好落在它宣称不覆盖的那一格。
 * 抓住它的唯一方式是"用真 JWT 以契约角色码发真 HTTP"。
 *
 * <h2>🛑 判据设计三：响应体形态本身是断言对象</h2>
 * <ul>
 *   <li>B2/B3 的 403 必须带 {@code data.missing_items} 且逐字等于上游字面
 *       （{@code screening_result} / {@code PROFILED}）—— 前端按它做分支，
 *       报一个风格统一的假字面会让匹配静默落空；</li>
 *   <li>B4 的客户响应里 {@code owner_store_id} / {@code serving_store_id}
 *       <b>必须不存在该键</b>（不是 null、不是空串）—— 契约 description 逐字「客户不下发」，
 *       它不是派生字段，入站拦截器与出口兜底都<b>不会</b>管它，只有域 B 自己的裁剪管它；</li>
 *   <li>B5 的 {@code role_used_for_callable_check} 必须回显<b>端角色码</b>，
 *       使"这一行是谁调的"在响应里可核。</li>
 * </ul>
 *
 * <h2>零污染与可重复</h2>
 * 底数据全部用 {@code e2e00000-} 前缀自建（本套件独占一个租户），
 * {@code @AfterAll} 按"子 → 父"FK 序在租户上下文内清理。
 * 🛑 清理也必须在租户上下文内（FORCE RLS 下 DELETE 的 {@code WITH CHECK}
 * 会挡住无上下文时的删除，静默返回 0 行）—— 这是 S2-5 / S2-9 的既有教训。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S2-10 · 契约域 B（B1~B6）真请求回归")
class DomainBEndpointsE2ETest {

    /** 与 application.yml 的 dev 占位密钥一致。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本套件自建的租户（与既有 E2E 的租户 ID 零交集，便于精确清理）。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000b2b2";

    /** 一个辖区 + 两家门店（归属店用同一家，便于断言 owner/serving 的差异）。 */
    private static final String S_REGION = "e2e00000-0000-0000-0000-0000000000b1";
    private static final String S_STORE_OWNER = "e2e00000-0000-0000-0000-0000000000b2";
    private static final String S_STORE_SERVING = "e2e00000-0000-0000-0000-0000000000b3";

    /** 员工：锚点在归属店（B2 的 owner 解算与 A3 同源）。 */
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000b4";

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

    /**
     * 清理：<b>子 → 父</b> FK 序，全部在租户上下文内。
     *
     * <p>序的依据是 FK 方向：
     * {@code intake_profile_revision → intake_profile → consent / screening_record /
     * customer_state_transition → customer → staff → store → region → tenant}。
     * 颠倒任一处会以 FK 违反失败（而不是静默），但会留下半清理的库 —— 故此处逐条列出。
     */
    @AfterAll
    static void cleanup() {
        if (jdbc == null) {
            return;
        }
        inTenant(() -> {
            jdbc.update("DELETE FROM intake_profile_revision WHERE tenant_id = ?::uuid"
                    + " AND customer_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM intake_profile WHERE tenant_id = ?::uuid"
                    + " AND customer_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM consent WHERE tenant_id = ?::uuid"
                    + " AND customer_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM screening_record WHERE tenant_id = ?::uuid"
                    + " AND customer_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM customer_state_transition WHERE tenant_id = ?::uuid"
                    + " AND customer_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid"
                    + " AND id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'",
                    TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    /** 灌底：租户 + 1 辖区 + 2 门店 + 1 员工（锚点在归属店）。 */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S2-10') "
                + "ON CONFLICT DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E域B辖区') ON CONFLICT DO NOTHING",
                    S_REGION, TENANT);
            for (String[] s : List.of(
                    new String[]{S_STORE_OWNER, "E2E域B-归属店"},
                    new String[]{S_STORE_SERVING, "E2E域B-服务店"})) {
                jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, '直营') ON CONFLICT DO NOTHING",
                        s[0], TENANT, S_REGION, s[1]);
            }
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, S_STORE_OWNER);
            return null;
        });
    }

    /** 在租户上下文内执行（{@code SET LOCAL app.tenant_id}）——FORCE RLS 下读写的前提。 */
    private static <T> T inTenant(Supplier<T> body) {
        return tx.execute(s -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // 一、B1 POST /screening-records（硬门禁①）
    // ==================================================================

    @Nested
    @DisplayName("一 · B1 /screening-records（禁忌筛查提交）")
    class ScreeningRecords {

        /**
         * 🛑 本套件的<b>第一条关键断言</b>。
         *
         * <p>契约 B1 的 {@code x-callable-roles: [therapist, meridian, admin]}
         * 逐字含调理师与经络师。若拿到 403，成因几乎必然是
         * 「{@code @RequirePermission} 的码没授予这两个角色」——
         * 而 {@code PermissionCodeRegistrationGateTest} 的"无主码"断言<b>不会红</b>
         * （该码有主，manager/area/hq 持有）。只有这条真请求能抓住它。
         */
        @Test
        @DisplayName("🛑 契约声明的两个一线角色调 B1 → 200（否则是权限码未授予该角色）")
        void contract_callable_frontline_roles_reach_b1() {
            String custId = newCustomerKey();
            for (String role : List.of("therapist", "meridian", "manager", "area", "hq")) {
                ResponseEntity<String> resp = post("/api/v1/screening-records",
                        "{\"customer_id\":\"" + custId + "\",\"items_json\":{\"pregnancy\":false}}",
                        token(role, "own_store"));
                assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "角色 " + role + " 调 B1 必须 200（契约 B1 x-callable-roles: "
                                + "[therapist, meridian, admin]，admin 展开 = manager/area/hq）。\n"
                                + "🛑 若拿到 403，最常见的成因是 B1 贴的 @RequirePermission 码"
                                + "（customer:write）没有授予 therapist/meridian ——"
                                + "而码级门禁看不见这类『角色级缺失』。实际: "
                                + resp.getStatusCode() + " body=" + resp.getBody());
                assertEquals(0, code(resp), "信封 code 应为 0（成功）。body=" + resp.getBody());

                JsonNode d = data(resp);
                assertTrue(d.has("screening_id"), "B1 响应缺 screening_id。data=" + d);
                assertTrue(d.has("result"), "B1 响应缺 result。data=" + d);
                assertTrue(d.has("submitted_at"), "B1 响应缺 submitted_at。data=" + d);
                assertTrue(List.of("通过", "不通过").contains(d.path("result").asText()),
                        "result 必须是契约 enum 字面 [通过, 不通过]（中文字面）。实际: "
                                + d.path("result"));
            }
        }

        /**
         * 🛑 B1 的声明集 = {@code 200 + 400}，<b>没有 403</b>。
         *
         * <p>故"客户调 B1"必须走服务层的 {@code requireCallable} → 2001，
         * 而那个 403 的实现落点是 {@code VISIBILITY_DENIED}，
         * 不是权限拦截器（拦截器与 B1 的注解同码，但语义不同）。
         * 本断言把"是服务层的显式点名拒绝在生效"变成可核事实。
         */
        @Test
        @DisplayName("客户调 B1 → 403(2001) 显式点名拒绝（服务层 requireCallable 生效）")
        void client_is_denied_on_b1_by_explicit_role_check() {
            String custId = newCustomerKey();
            ResponseEntity<String> resp = post("/api/v1/screening-records",
                    "{\"customer_id\":\"" + custId + "\",\"items_json\":{\"pregnancy\":false}}",
                    token("client", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "客户调 B1 必须 403（契约 B1 x-callable-roles 不含 client）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(2001, code(resp),
                    "应报 2001 VISIBILITY_DENIED（契约 §2.0：门禁缺失与可见性不足统一 403）。实际 code="
                            + code(resp));
        }

        @Test
        @DisplayName("B1 缺 items_json → 400(1001)（唯一可报的码；无 403/无 500）")
        void b1_missing_items_is_400_not_403() {
            String custId = newCustomerKey();
            ResponseEntity<String> resp = post("/api/v1/screening-records",
                    "{\"customer_id\":\"" + custId + "\"}", token("meridian", "own_store"));
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                    "缺 items_json 必须 400（契约 B1 responses 含 400 ValidationFailed）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(1001, code(resp), "应报 1001 VALIDATION_FAILED");
        }

        @Test
        @DisplayName("B1 记录 append-only：同一客户两次提交产生两条记录（不可覆盖）")
        void b1_appends_rather_than_overwrites() {
            String custId = newCustomerKey();
            for (int i = 0; i < 2; i++) {
                ResponseEntity<String> resp = post("/api/v1/screening-records",
                        "{\"customer_id\":\"" + custId + "\",\"items_json\":{\"pregnancy\":false}}",
                        token("meridian", "own_store"));
                assertEquals(HttpStatus.OK, resp.getStatusCode(), "第 " + (i + 1) + " 次提交应 200");
            }
            Integer n = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM screening_record WHERE customer_id = ?::uuid",
                    Integer.class, custId));
            assertEquals(2, n,
                    "🛑 两次提交必须留下【两条】记录 —— 契约 B1 description 逐字「记录不可删除」，"
                            + "且筛查结论是准入链的证据。若为 1，说明有人把它写成了 upsert："
                            + "那会让『复筛』静默覆盖首筛结论，而首筛结论正是 REJECTED 的判据。实际: " + n);
        }
    }

    // ==================================================================
    // 二、B2 POST /customers（建档）
    // ==================================================================

    @Nested
    @DisplayName("二 · B2 /customers（建档 · 门禁 GateMissing）")
    class Customers {

        @Test
        @DisplayName("🛑 一线角色调 B2 → 200 且出参四键齐备（customer_id / status / 两个门店）")
        void contract_callable_frontline_roles_reach_b2() {
            for (String role : List.of("therapist", "meridian", "manager", "area", "hq")) {
                String custId = newCustomerKey();
                String screeningId = submitScreening(custId, "meridian");

                ResponseEntity<String> resp = post("/api/v1/customers",
                        "{\"name\":\"E2E域B客户\",\"gender\":\"女\",\"age\":33,"
                                + "\"phone\":\"" + phoneFor(custId) + "\",\"screening_id\":\""
                                + screeningId + "\"}",
                        token(role, "own_store"));
                assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "角色 " + role + " 调 B2 必须 200（契约 B2 x-callable-roles: "
                                + "[therapist, meridian, admin]，admin 展开 = manager/area/hq）。\n"
                                + "🛑 若拿到 403 且 missing_items=[\"PROFILED\"]，那是【自环门禁】："
                                + "B2 的产出正是『建档』，故调用它时客户必然尚未 PROFILED ——"
                                + "把 assertProfiled 当作 B2 的前置会让 B2 100% 不可用。实际: "
                                + resp.getStatusCode() + " body=" + resp.getBody());

                JsonNode d = data(resp);
                assertEquals(custId, d.path("customer_id").asText(), "customer_id 必须回显被建档的客户键");
                assertEquals("PROFILED", d.path("status").asText(),
                        "🛑 status 必须是 5 值权威枚举里的 PROFILED（data-dict §2.25 ② 的 14→5 映射）。"
                                + "若出现 'pending'，说明某条写入路径依赖了库层 DEFAULT 'pending' ——"
                                + "而它不在权威 5 值内，任何按 5 值分组的报表都会静默漏掉这批行。实际: "
                                + d.path("status"));
                assertEquals(S_STORE_OWNER, d.path("owner_store_id").asText(),
                        "🛑 owner_store_id 必须由服务端从 token 锚点解算（staff.store_id = 归属店）——"
                                + "契约/data-dict §2.6「归属/首诊店，锁定档案」，客户端不得自选。实际: "
                                + d.path("owner_store_id"));
                assertTrue(d.has("serving_store_id"),
                        "serving_store_id 必须存在（契约 CustomerCreateData 四键之一；未开始服务时为 null）。data=" + d);
            }
        }

        /**
         * 🛑 B2 的 403 是 {@code GateMissing}（2002），<b>不是</b> 2001。
         *
         * <p>两者都是 403，但 {@code data.missing_items} 的有无把它们分开：
         * 门禁缺失带 {@code missing_items}，可见性不足带 {@code denied_fields}。
         * 报错一个会让前端拿到空数组而做不出分支。
         */
        @Test
        @DisplayName("🛑 无通过筛查 → 403(2002) + missing_items 逐字 [\"screening_result\"]")
        void b2_without_passing_screening_is_gate_missing_with_literal() {
            String custId = newCustomerKey();   // 建键但【不】提交筛查

            ResponseEntity<String> resp = post("/api/v1/customers",
                    "{\"name\":\"E2E未筛查\",\"gender\":\"男\",\"age\":40,"
                            + "\"phone\":\"" + phoneFor(custId) + "\",\"screening_id\":\""
                            + UUID.randomUUID() + "\"}",
                    token("meridian", "own_store"));

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "无通过的筛查记录调 B2 必须 403。实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());
            assertEquals(2002, code(resp),
                    "🛑 必须报 2002 GATE_MISSING，而【不是】2001 VISIBILITY_DENIED —— "
                            + "契约 B2 的 403 是 components.responses.GateMissing。实际 code=" + code(resp));
            assertEquals(List.of("screening_result"), stringList(data(resp).path("missing_items")),
                    "🛑 missing_items 必须逐字等于上游字面 ['screening_result']（契约 B1 description 逐字）。"
                            + "前端按它做分支，报一个风格统一的假字面（如全大写）会让匹配静默落空。实际: "
                            + data(resp).path("missing_items"));
        }

        @Test
        @DisplayName("🛑 B2 无 404：查不到 screening_id 时归入门禁（403），不是资源不存在")
        void b2_unknown_screening_is_gate_missing_not_404() {
            ResponseEntity<String> resp = post("/api/v1/customers",
                    "{\"name\":\"E2E幽灵\",\"gender\":\"男\",\"age\":40,"
                            + "\"phone\":\"" + phoneFor(UUID.randomUUID().toString()) + "\",\"screening_id\":\""
                            + UUID.randomUUID() + "\"}",
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "🛑 契约 B2 的 responses 声明集 = 200 + 403(GateMissing)，【无 404】——"
                            + "一次『资源不存在』的 404 会让客户端落进未声明分支。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(2002, code(resp), "应报 2002 GATE_MISSING");
        }

        @Test
        @DisplayName("B2 命中禁忌：客户处于 REJECTED 终态 → 403(2002) + 逐字 [\"screening_result\"]")
        void rejected_customer_stays_blocked_on_b2() {
            String custId = newCustomerKey();
            // 先提交一次【通过】的筛查（使 hasPassing=true），这样 B2 才会走到
            // "当前态 = REJECTED" 这条分支，而不是"查不到筛查记录"那条。
            String screeningId = submitScreening(custId, "meridian");
            // 🛑 config #8 的禁忌清单当前为空（{count:9, items:[]}），推导结论恒为"通过"，
            //    故无法经真实 B1 造出 REJECTED。这里直接落一条符合状态机的跃迁
            //    （SCREENING → REJECTED，V2 的 CHECK 允许；operator 指向已灌的底数据 staff）。
            inTenant(() -> {
                jdbc.update("INSERT INTO customer_state_transition"
                                + " (transition_id, tenant_id, customer_id, from_state, to_state, is_current,"
                                + "  trigger_event, guard_result, operator_id, occurred_at, created_by)"
                                + " VALUES (?::uuid, ?::uuid, ?::uuid, 'SCREENING', 'REJECTED', true,"
                                + "  'B1:screening_contraindication_hit', 'passed', ?::uuid, now(), 'e2e')",
                        UUID.randomUUID().toString(), TENANT, custId, S_STAFF);
                jdbc.update("UPDATE customer SET status = 'REJECTED' WHERE id = ?::uuid", custId);
                return null;
            });

            ResponseEntity<String> resp = post("/api/v1/customers",
                    "{\"name\":\"E2E拒诊\",\"gender\":\"男\",\"age\":40,"
                            + "\"phone\":\"" + phoneFor(custId) + "\",\"screening_id\":\""
                            + screeningId + "\"}",
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "REJECTED 终态的客户调 B2 必须 403（契约 B1：命中禁忌 → 后续 B2/B3 入口 403）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(List.of("screening_result"), stringList(data(resp).path("missing_items")),
                    "🛑 REJECTED 的缺失项名必须是 screening_result（契约 B1 逐字点名该情形）；"
                            + "且它必须优先于任何其它缺失项（筛查未过时不该报 PROFILED）。实际: "
                            + data(resp).path("missing_items"));
        }

        @Test
        @DisplayName("客户调 B2 → 403(2001)（服务层 requireCallable；契约不含 client）")
        void client_is_denied_on_b2() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            ResponseEntity<String> resp = post("/api/v1/customers",
                    "{\"name\":\"E2E域B客户\",\"gender\":\"女\",\"age\":33,"
                            + "\"phone\":\"" + phoneFor(custId) + "\",\"screening_id\":\"" + screeningId + "\"}",
                    token("client", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "客户调 B2 必须 403。实际: " + resp.getStatusCode());
            assertEquals(2001, code(resp), "应报 2001（服务层显式点名，非门禁缺失）");
        }
    }

    // ==================================================================
    // 三、B3 POST /customers/{id}/consents（门禁 G1）
    // ==================================================================

    @Nested
    @DisplayName("三 · B3 /customers/{id}/consents（签知情同意书 · 门禁 G1）")
    class Consents {

        @Test
        @DisplayName("🛑 已建档客户 + 一线角色调 B3 → 200 且回显 consent_id")
        void front_line_role_can_sign_consent_after_profiled() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            profileCustomer(custId, screeningId);

            ResponseEntity<String> resp = post("/api/v1/customers/" + custId + "/consents",
                    "{\"auth_scope\":[\"collect_basic\",\"generate_advice\",\"service_record\",\"rights_ack\"],"
                            + "\"band_willingness\":\"自愿佩戴\",\"evidence_hash\":\"e2e-hash-0001\","
                            + "\"data_source\":\"self-report\"}",
                    token("therapist", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "调理师对已建档客户签同意书必须 200（契约 B3 x-callable-roles: "
                            + "[therapist, meridian, admin]，且门禁 G1 已满足）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());

            JsonNode d = data(resp);
            assertTrue(d.has("consent_id"), "B3 响应缺 consent_id。data=" + d);

            String state = inTenant(() -> jdbc.queryForObject(
                    "SELECT to_state FROM customer_state_transition WHERE customer_id = ?::uuid"
                            + " AND is_current = true", String.class, custId));
            assertEquals("CONSENTED", state,
                    "🛑 签同意书后当前态必须是 CONSENTED（data-dict §2.25 ① 的 PROFILED → CONSENTED 边）。"
                            + "若仍为 PROFILED，说明跃迁没写（或写了但不是 current）。实际: " + state);
        }

        /**
         * 🛑 G1 门禁的缺失项字面是 <b>大写</b> {@code PROFILED}，
         * 而 B2 的门禁字面是 <b>snake_case</b> {@code screening_result}。
         *
         * <p>两个字面拼写风格不同，但<b>都必须逐字照抄</b>
         * （前者来自 data-dict §2.25 ③ G1，后者来自契约 B1 description）。
         * 把它们"统一风格"会让响应体与上游文档对不上。
         */
        @Test
        @DisplayName("🛑 未建档（未 PROFILED）签同意书 → 403(2002) + missing_items 逐字 [\"PROFILED\"]")
        void b3_before_profiled_is_gate_missing_with_uppercase_literal() {
            String custId = newCustomerKey();   // 建键但未建档

            ResponseEntity<String> resp = post("/api/v1/customers/" + custId + "/consents",
                    "{\"auth_scope\":[\"collect_basic\"],\"band_willingness\":\"自愿佩戴\","
                            + "\"evidence_hash\":\"e2e-hash-0002\",\"data_source\":\"self-report\"}",
                    token("meridian", "own_store"));

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "未建档客户签同意书必须 403（data-dict §2.25 ③ G1）。实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());
            assertEquals(2002, code(resp), "应报 2002 GATE_MISSING");
            assertEquals(List.of("PROFILED"), stringList(data(resp).path("missing_items")),
                    "🛑 missing_items 必须逐字等于 data-dict §2.25 ③ G1 的字面 ['PROFILED']（大写下划线），"
                            + "而【不是】snake_case 的 'profiled'。实际: " + data(resp).path("missing_items"));
        }

        @Test
        @DisplayName("🛑 拒戴不得降级服务：band_willingness=暂不佩戴 → 200（无任何分支读它）")
        void declined_band_does_not_downgrade_service() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            profileCustomer(custId, screeningId);

            ResponseEntity<String> resp = post("/api/v1/customers/" + custId + "/consents",
                    "{\"auth_scope\":[\"collect_basic\"],\"band_willingness\":\"暂不佩戴\","
                            + "\"evidence_hash\":\"e2e-hash-0003\",\"data_source\":\"self-report\"}",
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 V5 附注逐字「拒戴不得降级服务」——『暂不佩戴』必须走与『自愿佩戴』"
                            + "完全相同的路径（200），没有任何分支读它。若这里报错，"
                            + "说明有人把 band_willingness 读成了服务门槛。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
        }
    }

    // ==================================================================
    // 四、B4 GET /customers/{id}（可见性裁剪重点接口）
    // ==================================================================

    @Nested
    @DisplayName("四 · B4 /customers/{id}（可见性裁剪 · 含 404）")
    class CustomerDetail {

        @Test
        @DisplayName("🛑 客户调 B4 → 200 且 owner_store_id / serving_store_id 【键不存在】")
        void client_sees_detail_but_without_the_two_store_fields_entirely() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            profileCustomer(custId, screeningId);

            ResponseEntity<String> resp = get("/api/v1/customers/" + custId, token("client", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "客户调 B4 必须 200（契约 B4 x-callable-roles 含 client）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());

            JsonNode d = data(resp);
            assertFalse(d.has("owner_store_id"),
                    "🛑 客户的 B4 响应里 owner_store_id 必须【不存在该键】——"
                            + "契约 description 逐字「客户不下发」。🛑 它是【非派生】字段："
                            + "入站拦截器与出口兜底都【不会】管它，只有域 B 自己的裁剪管它。"
                            + "下发一个 null 也【不行】：null 会告诉客户端『存在这个字段但为空』。"
                            + "实际 data=" + d);
            assertFalse(d.has("serving_store_id"),
                    "🛑 同上：serving_store_id 对客户必须【不存在该键】。实际 data=" + d);
            assertTrue(d.has("name"), "客户必须能看到自己的 name。data=" + d);

            // 反向自证：staff 侧必须【能】看到这两个字段（否则"客户看不到"可能只是"对谁都裁掉了"）
            JsonNode staff = data(get("/api/v1/customers/" + custId, token("meridian", "own_store")));
            assertTrue(staff.has("owner_store_id"),
                    "🛑 反向自证：经络师必须【能】看到 owner_store_id —— 否则『客户看不到它』"
                            + "就无法区分『因为他是客户』（正确）与『这个字段对谁都裁掉了』（错误）。"
                            + "实际 staff data=" + staff);
            assertTrue(staff.has("serving_store_id"), "经络师必须能看到 serving_store_id。data=" + staff);
        }

        @Test
        @DisplayName("🛑 客户 ?include=verdict → 403(2001) + denied_fields 逐字 [effect_verdict, as_value]")
        void client_include_verdict_is_denied_with_contract_field_names() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            profileCustomer(custId, screeningId);

            ResponseEntity<String> resp = get("/api/v1/customers/" + custId + "?include=verdict",
                    token("client", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "🛑 契约 B4 description 逐字：「客户 token 请求含派生字段的 query（如 ?include=verdict）"
                            + "→ 403 VISIBILITY_DENIED」。实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());
            assertEquals(2001, code(resp), "应报 2001 VISIBILITY_DENIED");
            assertEquals(List.of("effect_verdict", "as_value"),
                    stringList(data(resp).path("denied_fields")),
                    "🛑 契约 B4 逐字给出的是这两个【字段名】（verdict 组别名的展开结果），"
                            + "不是组别名 verdict —— 回显组名会让客户端只能靠猜。实际: "
                            + data(resp).path("denied_fields"));
        }

        @Test
        @DisplayName("staff 带 include=verdict → 200（越权判据是角色，不是 include 参数本身）")
        void staff_may_include_verdict() {
            String custId = newCustomerKey();
            String screeningId = submitScreening(custId, "meridian");
            profileCustomer(custId, screeningId);

            ResponseEntity<String> resp = get("/api/v1/customers/" + custId + "?include=verdict",
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 反向自证：经络师带 include=verdict 必须 200 —— 否则『客户被 403』"
                            + "就无法区分『因为他是客户』与『这个 include 谁都拒』。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
        }

        @Test
        @DisplayName("🛑 查无此客户 → 404(3001)（B4 声明了 404，与 B5/B6 刻意不同）")
        void b4_unknown_customer_is_404() {
            ResponseEntity<String> resp = get("/api/v1/customers/" + UUID.randomUUID(),
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                    "🛑 契约 B4 的 responses 含 404(NotFound) —— 与 B2/B5/B6 的处置刻意不同。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(3001, code(resp), "应报 3001 NOT_FOUND");
        }
    }

    // ==================================================================
    // 五、B5 / B6 intake-profile（只声明 200 的两行）
    // ==================================================================

    @Nested
    @DisplayName("五 · B5/B6 /customers/{id}/intake-profile（只声明 200 · append-only）")
    class IntakeProfiles {

        @Test
        @DisplayName("🛑 B5 查无此客户 → 200 + 空 data（【不是】404 —— B5 只声明 200）")
        void b5_unknown_customer_is_200_with_empty_data() {
            ResponseEntity<String> resp = get(
                    "/api/v1/customers/" + UUID.randomUUID() + "/intake-profile",
                    token("meridian", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 契约 B5 的 responses【仅 200】—— 故『客户不存在』与『尚未建档』"
                            + "都必须是 200 + 空 data，报 404 会让客户端落进契约未声明的分支。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(0, code(resp), "信封 code 应为 0");
            assertTrue(data(resp).isObject() && data(resp).size() == 0,
                    "🛑 查无此客户时 data 必须为空对象。实际: " + data(resp));
        }

        @Test
        @DisplayName("🛑 B6 首次写入即建档（revision_no=1），未建档【不】报 403（只声明 200）")
        void b6_first_write_creates_profile_with_revision_one() {
            String custId = newCustomerKey();

            ResponseEntity<String> resp = patch("/api/v1/customers/" + custId + "/intake-profile",
                    "{\"height_cm\":170,\"weight_kg\":65.5}", token("meridian", "own_store"));
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 契约 B6 的 responses【仅 200】—— 故对『未建档』客户首次写入必须成功"
                            + "（revision_no=1），不得报 403（报它会引入未声明的码）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());

            JsonNode d = data(resp);
            assertEquals(custId, d.path("customer_id").asText(), "customer_id 必须回显");
            assertEquals(1, d.path("revision_no").asInt(),
                    "首次写入的 revision_no 必须是 1。实际: " + d.path("revision_no"));
            assertTrue(d.has("delta_vs_previous"), "B6 响应缺 delta_vs_previous。data=" + d);
            assertTrue(d.has("recorded_at"), "B6 响应缺 recorded_at。data=" + d);
        }

        @Test
        @DisplayName("🛑 B6 append-only：同一客户二次修订 → revision_no=2 且历史留两行")
        void b6_second_write_appends_revision_history() {
            String custId = newCustomerKey();
            patch("/api/v1/customers/" + custId + "/intake-profile",
                    "{\"height_cm\":170,\"weight_kg\":65.5}", token("meridian", "own_store"));
            ResponseEntity<String> second = patch("/api/v1/customers/" + custId + "/intake-profile",
                    "{\"weight_kg\":66.0}", token("meridian", "own_store"));
            assertEquals(2, data(second).path("revision_no").asInt(),
                    "第二次修订的 revision_no 必须是 2。实际: " + data(second).path("revision_no"));

            Integer n = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM intake_profile_revision WHERE customer_id = ?::uuid",
                    Integer.class, custId));
            assertEquals(2, n,
                    "🛑 两次修订必须在 intake_profile_revision 留下【两行】（append-only 留痕，不可覆盖）。"
                            + "若为 1，说明某条路径改写了历史行 —— 而契约 B6 逐字要求 append-only。实际: " + n);

            Integer proj = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM intake_profile WHERE customer_id = ?::uuid",
                    Integer.class, custId));
            assertEquals(1, proj,
                    "🛑 intake_profile 是【当前投影】，一个客户【一行】（uq_intake_profile_customer）。实际: " + proj);
        }

        @Test
        @DisplayName("客户调 B5 → 200（契约含 client）；客户调 B6 → 403(2001)（契约不含 client）")
        void client_can_read_b5_but_not_write_b6() {
            String custId = newCustomerKey();
            patch("/api/v1/customers/" + custId + "/intake-profile",
                    "{\"height_cm\":170}", token("meridian", "own_store"));

            ResponseEntity<String> read = get("/api/v1/customers/" + custId + "/intake-profile",
                    token("client", "own_store"));
            assertEquals(HttpStatus.OK, read.getStatusCode(),
                    "🛑 客户调 B5 必须 200（契约 B5 x-callable-roles 含 client）。实际: "
                            + read.getStatusCode() + " body=" + read.getBody());

            ResponseEntity<String> write = patch("/api/v1/customers/" + custId + "/intake-profile",
                    "{\"height_cm\":171}", token("client", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, write.getStatusCode(),
                    "🛑 客户调 B6 必须 403（契约 B6 x-callable-roles 不含 client）。实际: "
                            + write.getStatusCode() + " body=" + write.getBody());
            assertEquals(2001, code(write), "应报 2001 VISIBILITY_DENIED");
        }
    }

    // ==================================================================
    // 六、自描述与装备（@Service 自注册是否真的生效）
    // ==================================================================

    @Nested
    @DisplayName("六 · 自描述与装备（免权限的契约元信息）")
    class SelfDescription {

        @Test
        @DisplayName("🛑 自描述端点可达 → @Service 自注册生效（域 B 与 A2 的显式 @Bean 不同）")
        void contract_self_description_is_available() {
            ResponseEntity<String> resp = get("/api/v1/customers/contract", null);
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 自描述端点必须可达（免权限）。它不可达意味着 @Service 没被扫描到 ——"
                            + "而域 B 全靠 @Service 自注册（与 A2 的显式 @Bean 不同）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());

            JsonNode d = data(resp);
            assertEquals("B-客户与档案", d.path("domain").asText(), "domain 必须逐字对齐契约 tag");

            List<String> annotations = stringList(d.path("annotations"));
            assertTrue(annotations.stream().anyMatch(s -> s.contains("customer:archive")),
                    "🛑 自描述必须写明 B1/B2/B3/B6 贴的是 customer:archive（而非 customer:write）。"
                            + "实际:" + annotations);

            List<String> roles = stringList(d.path("callable_roles"));
            assertTrue(roles.stream().anyMatch(s -> s.startsWith("B1 [therapist, meridian, admin]")),
                    "B1 的可调角色必须逐字对齐契约 x-callable-roles。实际: " + roles);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 建一个客户键（B1 前置：screening_record.customer_id 需要既有 customer 行）。 */
    private static String newCustomerKey() {
        String id = "e2e00000-0000-0000-0000-" + String.format("%012d", SEQ.incrementAndGet());
        inTenant(() -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status, owner_store_id) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'CREATED', ?::uuid)",
                    id, TENANT, "E2E域B-" + id.substring(id.length() - 4), S_STORE_OWNER);
            return null;
        });
        return id;
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 为每个客户键生成唯一 phone（租户内唯一索引 uq_customer_tenant_phone）。 */
    private static String phoneFor(String customerId) {
        return "139" + customerId.substring(customerId.length() - 8);
    }

    /** 提交一次通过筛查，返回 screening_id。 */
    private String submitScreening(String custId, String role) {
        ResponseEntity<String> resp = post("/api/v1/screening-records",
                "{\"customer_id\":\"" + custId + "\",\"items_json\":{\"pregnancy\":false}}",
                token(role, "own_store"));
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "前置失败：筛查提交应 200。实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        return data(resp).path("screening_id").asText();
    }

    /** 走完 B2 建档，使客户进入 PROFILED（B3 的 G1 前置）。 */
    private void profileCustomer(String custId, String screeningId) {
        ResponseEntity<String> resp = post("/api/v1/customers",
                "{\"name\":\"E2E域B客户\",\"gender\":\"女\",\"age\":33,"
                        + "\"phone\":\"" + phoneFor(custId) + "\",\"screening_id\":\"" + screeningId + "\"}",
                token("meridian", "own_store"));
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "前置失败：建档应 200。实际: " + resp.getStatusCode() + " body=" + resp.getBody());
    }

    /** 读取信封 code（契约 §2.0 四字段之一）。 */
    private int code(ResponseEntity<String> resp) {
        try {
            return MAPPER.readTree(resp.getBody()).path("code").asInt();
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /** 读取信封 data。 */
    private JsonNode data(ResponseEntity<String> resp) {
        try {
            JsonNode root = MAPPER.readTree(resp.getBody());
            assertNotNull(root, "响应体为空: " + resp.getStatusCode());
            return root.path("data");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private ResponseEntity<String> get(String path, String jwt) {
        return exchange(HttpMethod.GET, path, null, jwt);
    }

    private ResponseEntity<String> post(String path, String body, String jwt) {
        return exchange(HttpMethod.POST, path, body, jwt);
    }

    private ResponseEntity<String> patch(String path, String body, String jwt) {
        return exchange(HttpMethod.PATCH, path, body, jwt);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return rest.exchange("http://127.0.0.1:" + port + path, method, entity, String.class);
    }

    /** 真签名 JWT（HS256）。 */
    private static String token(String role, String scope) {
        return token(role, scope, S_STAFF);
    }

    private static String token(String role, String scope, String staffId) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + staffId + "\""
                + ",\"role\":\"" + role + "\""
                + (scope == null ? "" : ",\"scope\":\"" + scope + "\"")
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
        return sign(header, payload);
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
}