package com.diaoyuanyun.dy.app.identity;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-9 证人套件 · 契约域 A（A2 {@code GET /auth/me} + A3 {@code GET /stores}）
 * 的<b>真请求回归</b>。
 *
 * <h2>为什么领域层已经有 29 条断言，还必须再写这一套</h2>
 * {@code BandVisibilityMatrixTest} 证明的是「<b>解算规则写对了</b>」。
 * 它对下面这一整类失守<b>完全看不见</b>：
 * <pre>
 *   ① 路由：端点真实路径是不是 /api/v1/auth/me（契约 paths 不含基路径，靠 servers 承载）
 *   ② 权限：@RequirePermission 的码有没有登记（A3 贴 store:read；A2 刻意不贴）
 *   ③ 装配：拦截器顺序、过滤器是否真的把 token 角色写进 TenantContext
 *   ④ 出站：DerivedResponseBodyAdvice 会不会把 band_visibility 的键当派生字段摘掉
 *   ⑤ 口径源：config #43 到底有没有被 ConfigSeedBandProfileSource 解析出来
 * </pre>
 * 这五类失守的共同形态是「单测全绿、门禁全 PASS、BUILD SUCCESS，而端点不可用或越权」——
 * 本仓库已经因此漏过<b>三次</b>（角色码大小写分叉 / refund:* 从未登记 /
 * 客户请求派生字段未拦）。故本套件的价值不在"再断言一遍四档布尔值"，
 * 而在把上面五件事变成<b>可判定的真请求事实</b>。
 *
 * <h2>🛑 判据设计一：用「401 vs 403 vs 200」分辨拒绝发生在哪一层</h2>
 * <pre>
 *   无 token 调 A2                 → 401(1002)  ⇒ 契约 A2【只声明 200/401】
 *   客户 token 调 A2               → 200        ⇒ 🛑 这一条是本套件最关键的断言：
 *                                                 A2 刻意不贴权限码，就是为了让客户
 *                                                 能查自己的可见性档位（三端 UI 靠它定渲染分支）
 *   客户 token 调 A3               → 403(2001)  ⇒ 显式点名拒绝，而非依赖"他碰巧没 store:read"
 *   therapist/meridian/manager 调 A3 → 200       ⇒ 装配链真的放行了契约声明的可调角色
 * </pre>
 *
 * <h2>🛑 判据设计二：A2 的响应体形态本身就是断言对象</h2>
 * <ul>
 *   <li>{@code band_visibility} 的四个键必须<b>齐备</b>（含 false）—— 缺键会让客户端拿到
 *       {@code undefined}，而它与 {@code false} 是两条不同的渲染分支；</li>
 *   <li>客户的 {@code refund_visibility} / {@code store_scope} <b>必须不存在</b>（不是 null、不是 false）——</li>
 *   <li>staff 侧相反：{@code store_scope} 必须在，且 {@code row_level} 逐角色对齐
 *       （manager→own_store / area→region / hq→all）。</li>
 * </ul>
 *
 * <h2>🛑 判据设计三：A3 的 {@code total} 与 {@code items} 必须自洽</h2>
 * 分页接口最典型的静默缺陷是"total 用无条件计数"：客户端算出"还有 N 页"，
 * 翻过去拿空数组 —— <b>它不报错</b>，只是列表看起来少了一截。
 * 故本套件断言 {@code total >= items.size()} 且 {@code total <= 租户内门店总数}，
 * 并在 own_store 角色下断言 {@code total} <b>恰好等于 1</b>
 * （行级过滤真的生效，而不只是"返回了一些行"）。
 *
 * <h2>零污染与可重复</h2>
 * 底数据全部用 {@code e2e00000-} 前缀自建（本套件独占一个租户），
 * {@code @AfterAll} 按"子 → 父"FK 序在租户上下文内清理。
 * 🛑 清理也必须在租户上下文内（FORCE RLS 下 DELETE 的 {@code WITH CHECK}
 * 会挡住无上下文时的删除，静默返回 0 行）—— 这一点是 S2-5 那套的既有教训。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S2-9 · 契约域 A（A2 /auth/me + A3 /stores）真请求回归")
class DomainAEndpointsE2ETest {

    /** 与 application.yml 的 dev 占位密钥一致。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本套件自建的租户（与 RLS 门禁 / 既有 E2E 的租户 ID 零交集，便于精确清理）。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000a2a2";

    /** 两家门店（同辖区）—— 用来区分 own_store（1 家）与 region / all（2 家）。 */
    private static final String S_REGION = "e2e00000-0000-0000-0000-0000000000a1";
    private static final String S_STORE_A = "e2e00000-0000-0000-0000-0000000000a2";
    private static final String S_STORE_B = "e2e00000-0000-0000-0000-0000000000a3";
    /** 另一辖区（用来证伪"region 过滤其实没生效"）。 */
    private static final String S_REGION_2 = "e2e00000-0000-0000-0000-0000000000a4";
    private static final String S_STORE_C = "e2e00000-0000-0000-0000-0000000000a5";

    /** 员工：锚点在 S_STORE_A（own_store / region 都从这里算）。 */
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000a6";
    /** 一个"档案里没有门店"的员工 —— 用来钉住"缺锚点不得回落全量"。 */
    private static final String S_STAFF_NO_STORE = "e2e00000-0000-0000-0000-0000000000a7";

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

    @AfterAll
    static void cleanup() {
        if (jdbc == null) {
            return;
        }
        inTenant(() -> {
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

    /**
     * 灌底数据：租户内两个辖区共三家门店 + 两个员工（一个有锚点、一个没有）。
     *
     * <p>🛑 "三家门店分属两个辖区"是刻意的：{@code region} 过滤若被写成"全租户"，
     * 断言（region 角色只看到 2 家）就会红 —— 而如果全部门店同属一个辖区，
     * "过滤没生效"与"过滤生效"会给出<b>完全相同的数字</b>。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S2-9') "
                + "ON CONFLICT DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E辖区一') ON CONFLICT DO NOTHING",
                    S_REGION, TENANT);
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E辖区二') ON CONFLICT DO NOTHING",
                    S_REGION_2, TENANT);
            for (String[] s : List.of(
                    new String[]{S_STORE_A, S_REGION, "E2E门店A-直营", "直营"},
                    new String[]{S_STORE_B, S_REGION, "E2E门店B-加盟", "加盟"},
                    new String[]{S_STORE_C, S_REGION_2, "E2E门店C-直营", "直营"})) {
                jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?) ON CONFLICT DO NOTHING",
                        s[0], TENANT, s[1], s[2], s[3]);
            }
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, S_STORE_A);
            // 无门店归属的员工（store_id 为空）—— 用来验证"缺锚点不得回落全量"
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, NULL, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF_NO_STORE, TENANT);
            return null;
        });
    }

    /** 在租户上下文内执行（{@code SET LOCAL app.tenant_id}）——FORCE RLS 下读写的前提。 */
    private static <T> T inTenant(java.util.function.Supplier<T> body) {
        return tx.execute(s -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // 一、A2 GET /auth/me
    // ==================================================================

    @Nested
    @DisplayName("一 · A2 /auth/me（可见性档位唯一权威下发点）")
    class AuthMe {

        /**
         * 🛑 本套件的<b>第一条关键断言</b>：客户必须能查到自己的档位。
         *
         * <p>理由：A2 的 {@code x-callable-roles} 含 {@code client}，而
         * {@code PermissionRegistry} 刻意不登记 client。A2 之所以"不贴任何权限码"，
         * 唯一的依据就是这条组合。若哪天有人给 A2 补了一个 {@code @RequirePermission}
         * （哪怕是有主的 {@code customer:read}），客户就会拿到 403 ——
         * 而 {@code PermissionCodeRegistrationGateTest} 的"无主码"断言<b>不会红</b>
         * （{@code customer:read} 有主）。故只有这条真请求能抓住它。
         */
        @Test
        @DisplayName("客户调 A2 → 200 且 band_visibility 四键齐备（含 false）—— 缺权限码是【刻意】的")
        void client_can_read_its_own_declaration() {
            ResponseEntity<String> resp = get("/api/v1/auth/me", token("client", "own_store"));

            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "🛑 客户调 A2 必须 200。A2 的 x-callable-roles 含 client，"
                            + "而它刻意不贴 @RequirePermission（注册表刻意不登记 client）——"
                            + "它就是客户端用来决定『渲染哪些区块』的接口，对它关掉等于让客户端"
                            + "什么都渲染不出来。\n实际: " + resp.getStatusCode()
                            + " body=" + resp.getBody());

            JsonNode data = data(resp);
            assertEquals("client", data.path("role").asText(),
                    "data.role 必须是契约端角色码 client");

            JsonNode band = data.path("band_visibility");
            assertTrue(band.isObject(), "band_visibility 必须是对象。实际 data=" + data);
            for (String key : List.of("field_group_1_raw", "field_group_2_status",
                    "field_group_3_gap_reason", "field_group_4_derived")) {
                assertTrue(band.has(key),
                        "🛑 band_visibility 必须包含 " + key + " —— 四个键带 "
                                + "x-visible-to: [client, therapist, meridian, admin]，"
                                + "对客户也必须【完整下发】（含 false）。缺键会让客户端拿到 "
                                + "undefined，而它与 false 是两条不同的渲染分支。实际: " + band);
            }
            assertTrue(band.path("field_group_1_raw").asBoolean(),
                    "客户 ① 手环原始数据必须可见（他自己戴的表）");
            assertTrue(band.path("field_group_2_status").asBoolean(), "客户 ② 采集状态必须可见");
            assertFalse(band.path("field_group_3_gap_reason").asBoolean(),
                    "客户 ③ 缺口原因必须为 false（硬锁）");
            assertFalse(band.path("field_group_4_derived").asBoolean(),
                    "客户 ④ 派生结果必须为 false（硬锁）");
        }

        /**
         * 🛑 「不下发」与「下发 false」是两件事 —— 必须用 {@code has()} 判，不能用取值判。
         *
         * <p>{@code refund_visibility} 对客户下发 {@code false} 等于告诉他
         * "存在这个档位"；而契约的 {@code x-visible-to: [meridian, admin]}
         * 语义是"该键对他不存在"。{@code store_scope} 更危险：
         * 下发一个空对象会被读成"我没有范围限制"，方向与事实完全相反
         * （客户是 n/a，仅本人）。
         */
        @Test
        @DisplayName("客户调 A2 → refund_visibility 与 store_scope 【键不存在】（不是 null、不是 false）")
        void client_response_omits_the_two_role_scoped_keys_entirely() {
            JsonNode data = data(get("/api/v1/auth/me", token("client", "own_store")));
            assertFalse(data.has("refund_visibility"),
                    "🛑 客户的响应里 refund_visibility 必须【不存在该键】——"
                            + "契约 x-visible-to: [meridian, admin] 的语义是『对他不存在』。"
                            + "下发 false 等于告诉他『存在这个档位』。实际 data=" + data);
            assertFalse(data.has("store_scope"),
                    "🛑 客户的响应里 store_scope 必须【不存在该键】——"
                            + "契约 x-visible-to: [therapist, meridian, admin]，freeze 逐字"
                            + "『客户 n/a（仅本人）』。下发空对象会被读成『我没有范围限制』，"
                            + "方向与事实【完全相反】。实际 data=" + data);
            assertEquals(2, data.size(),
                    "客户的 A2 响应体恰好 {role, band_visibility} 两项 —— "
                            + "契约 A2 只下发档位布尔值，不下发任何业务字段。实际 keys=" + data);
        }

        @Test
        @DisplayName("调理师调 A2 → 200；有 store_scope（row_level=own_store）但【无】refund_visibility")
        void therapist_gets_store_scope_but_no_refund_visibility() {
            JsonNode data = data(get("/api/v1/auth/me", token("therapist", "own_store")));
            assertEquals("therapist", data.path("role").asText());

            assertTrue(data.has("store_scope"),
                    "调理师必须有 store_scope（x-visible-to 含 therapist）");
            assertEquals("own_store", data.path("store_scope").path("row_level").asText(),
                    "调理师的行级范围必须是 own_store（契约 A2 映射）");
            assertEquals(List.of(S_STORE_A), toList(data.path("store_scope").path("store_ids")),
                    "own_store 的 store_ids 必须是【锚点门店】——"
                            + "它由服务端从 staff.store_id 解算，客户端无从提供");

            assertFalse(data.has("refund_visibility"),
                    "🛑 调理师的响应里 refund_visibility 必须【不存在该键】——"
                            + "契约的 x-visible-to 只有 [meridian, admin]。"
                            + "他在 #40 里的值是 false，但那是『告诉存在但为假』，"
                            + "而契约选的是『对他不存在』");
        }

        @Test
        @DisplayName("经络师调 A2 → 200 且 refund_visibility=true（#40 与 #43 两份口径同时生效）")
        void meridian_gets_refund_visibility_true() {
            JsonNode data = data(get("/api/v1/auth/me", token("meridian", "own_store")));
            assertEquals("meridian", data.path("role").asText());
            assertTrue(data.has("refund_visibility"),
                    "经络师必须有 refund_visibility（x-visible-to 含 meridian）");
            assertTrue(data.path("refund_visibility").asBoolean(),
                    "🛑 经络师的退款可见性必须是 true —— 它由 config #40 解算"
                            + "（meridian_therapist 行的 visible 值）。"
                            + "这条断言同时钉住【两份口径没有被合并】："
                            + "#40 管退款（角色级二分）、#43 管手环数据（角色 × 字段组四档），"
                            + "PRD / freeze §3.3 C-2 逐字『独立配置、不得合并』");
            assertEquals(4, countTrue(data.path("band_visibility")),
                    "经络师四档 hand环数据全可见（#43 的 meridian_therapist 行）");
        }

        @Test
        @DisplayName("三个管理角色调 A2 → row_level 逐角色对齐 own_store / region / all")
        void admin_roles_get_their_own_row_level() {
            record Case(String role, String expectedRowLevel) {
            }
            for (Case c : List.of(
                    new Case("manager", "own_store"),
                    new Case("area", "region"),
                    new Case("hq", "all"))) {
                JsonNode data = data(get("/api/v1/auth/me", token(c.role(), null)));
                assertEquals("admin", data.path("role").asText(),
                        c.role() + " 的端角色码必须是 admin（契约把三个 token 角色折叠成一个端角色）");
                assertEquals(c.expectedRowLevel(),
                        data.path("store_scope").path("row_level").asText(),
                        c.role() + " 的 row_level 必须是 " + c.expectedRowLevel()
                                + "（契约 A2 映射：manager→own_store / area→region / hq→all）");
                assertTrue(data.path("refund_visibility").asBoolean(),
                        c.role() + " 的退款可见性为 true（#40 的三个管理受众键都为 true）");
            }
        }

        /**
         * 🛑 {@code row_level=all} ⇒ {@code store_ids=[]} 是一个<b>显式决定</b>。
         *
         * <p>把全租户门店枚举进 A2 会让：① 一次登录相关调用退化成"拉全量组织台账"；
         * ② 下发一份<b>会过期的快照</b>（门店新增后客户端拿到的仍是旧列表，
         * 而它<b>不会报错</b>，只会少显示几家）；③ 与 A3 形成两个"门店列表真相源"。
         * 本断言防的是"将来有人顺手把它补全成一次全量枚举"。
         */
        @Test
        @DisplayName("hq 的 store_ids 必须是【空数组】—— 『全量、不枚举』，真实列表只在 A3")
        void headquarters_gets_empty_store_ids_on_purpose() {
            JsonNode data = data(get("/api/v1/auth/me", token("hq", null)));
            JsonNode ids = data.path("store_scope").path("store_ids");
            assertTrue(ids.isArray(), "store_ids 必须是数组（全量时为空数组，不是缺键）。实际: " + ids);
            assertEquals(0, ids.size(),
                    "🛑 row_level=all ⇒ store_ids=[]，语义是『全量、不枚举』。"
                            + "若这里出现了门店清单，说明有人把它补全成了全量枚举 —— "
                            + "那会下发一份会过期的组织快照（门店新增后客户端不报错、只少显示几家），"
                            + "并与 A3 形成两个『门店列表真相源』。实际: " + ids);
        }

        @Test
        @DisplayName("A2 契约声明只有 200/401：无 token → 401(1002)，不是 403")
        void anonymous_request_gets_401_not_403() {
            ResponseEntity<String> resp = get("/api/v1/auth/me", null);
            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                    "🛑 无 token 调 A2 必须 401 —— 契约 A2 只声明 '200' 与 '401'（没有 '403'），"
                            + "components.responses.Unauthenticated 逐字："
                            + "『401 UNAUTHENTICATED（无 token / token 过期）』。"
                            + "报 403 会让客户端落进契约未声明的分支。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(1002, code(resp), "应报 1002 UNAUTHENTICATED（契约 §2.0 错误码表）");
        }

        @Test
        @DisplayName("验签失败的 token → 401（绝不降级为匿名）")
        void tampered_signature_is_rejected_with_401() {
            String bad = tamperSignature(token("client", "own_store"));
            ResponseEntity<String> resp = get("/api/v1/auth/me", bad);
            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                    "验签失败必须 401，绝【不】降级为匿名请求 ——"
                            + "否则『验签失败』与『未登录』不可区分，"
                            + "攻击者只要带一个坏 token 就能绕过鉴权。实际: " + resp.getStatusCode());
        }

        @Test
        @DisplayName("自描述端点：连它自己都写明『不贴权限码 / 不贴 StaffOnly / 只有 200+401』")
        void contract_self_description_matches_the_decisions() {
            JsonNode desc = data(get("/api/v1/auth/me/contract", null));
            assertEquals(false, desc.path("permission_code_attached").asBoolean(),
                    "A2 必须自证『不贴权限码』—— 理由见端点类注释");
            assertEquals(false, desc.path("staff_only_attached").asBoolean(),
                    "A2 必须自证『不贴 @StaffOnly』—— x-client-forbidden: false "
                            + "与 x-client-explicitly-denied: true 是【两个不同的键】");
            assertEquals(List.of("200", "401"), toList(desc.path("declared_responses")),
                    "A2 的契约声明响应集必须逐字为 [200, 401]");
            assertEquals(List.of("client", "therapist", "meridian", "admin"),
                    toList(desc.path("callable_roles")),
                    "A2 的可调角色必须逐字对齐契约 x-callable-roles");
        }
    }

    // ==================================================================
    // 二、A3 GET /stores
    // ==================================================================

    @Nested
    @DisplayName("二 · A3 /stores（按行级 scope 过滤的门店分页）")
    class Stores {

        @Test
        @DisplayName("客户调 A3 → 403(2001)（契约 x-callable-roles 不含 client，显式点名拒绝）")
        void client_is_denied_on_stores() {
            ResponseEntity<String> resp = get("/api/v1/stores", token("client", "own_store"));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "🛑 客户调 A3 必须 403 —— A3 的 x-callable-roles: [therapist, meridian, admin] "
                            + "不含 client。客户对门店台账没有数据面（他只能看本人）。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(2001, code(resp), "应报 2001 VISIBILITY_DENIED（契约 A3 声明 '403'）");

            // ─────────────────────────────────────────────────────────────────
            // 🛑 2026-10-01 补：契约「不得模糊报错」的【人可读那一半】此前零覆盖
            // ─────────────────────────────────────────────────────────────────
            // 上游契约 §1.2 逐字：「403 语义：…message 必须【给出缺失项名称 / 档位名称】
            // （PRD P0-08 / §7.2：不得模糊报错）」。
            // 🛑 判据形态的教训（本仓第 55 条，此处首跑就复发了一次）：
            //   初版断言 message 必须含 "client" 或 "客户" ⇒ 首跑即红
            //   （实测 message=「权限不足: store:read」）。但那**不是缺陷** ——
            //   A3 的真实拒绝来自 `PermissionInterceptor`（`@RequirePermission("store:read")`），
            //   而契约要的「名称」既可以是**缺失项名称**（最常见的正是权限码名）
            //   也可以是**档位名称**。把判据写死成"必须出现角色名"，
            //   就是把本仓自己规定的合法形态判红 = 判据太窄。
            //   ⇒ 正确判法是断言【非空 + 真的点名了某个标识符】，而不是点名某个特定词。
            String message = message(resp);
            assertFalse(message.isBlank(), "403 的 message 不得为空（契约：不得模糊报错）");
            assertTrue(message.contains(":"),
                    "🛑 message 必须【点名】缺失项 / 档位名 —— 上游契约 §1.2 逐字要求"
                            + "「message 必须给出缺失项名称 / 档位名称（不得模糊报错）」。"
                            + "只说「无权访问」会让调用方无从判断该补哪个码或换哪个角色。"
                            + "本仓既有形态为「<原因>: <名称>」（如「权限不足: store:read」）。"
                            + "实际 message=" + message);
        }

        @Test
        @DisplayName("🛑 A3 角色级拒绝的响应形态：无 data.denied_fields（与字段级拒绝刻意不同）")
        void a3_role_level_rejection_carries_no_denied_fields() {
            // 🛑 本条钉住一个【刻意的设计边界】，同时登记一个【契约表述缺口】。
            // ------------------------------------------------------------------
            // A3 的真实拒绝来自 `PermissionInterceptor`（控制器贴 `@RequirePermission("store:read")`），
            // 即**权限码准入**；而契约里另有一条同码的 `StoreListService#requireCallable`
            // 角色级判据（"客户不可调 A3"显式条款）作为第二道。
            // 契约里两类 2001 被拒的东西根本不同：
            //   ① 字段级拒绝：客户索取派生字段（include=derived）⇒ 被拒的是【字段】，
            //      故 data.denied_fields["effect_verdict","as_value"] 有明确内容可报
            //      （契约 §3.2 逐字；由 DerivedVisibilityE2ETest 强守护）；
            //   ② 权限码/角色级拒绝（本条）：被拒的是【调用能力本身】，
            //      **没有"被拒字段名"这回事**。
            // 硬塞一个 denied_fields 是【语义挪用】—— 与一路在修的那类
            // "把 A 情形的码/字段拿来答 B 情形" 是同一个错误。
            //
            // 🛑 契约表述缺口（登记待裁，本用例只钉"现状是有意为之"，不代拍）：
            //   x-error-codes 里 2001 的 trigger 逐字是「该角色对请求【字段组】无可见性档位」
            //   —— 说的是**字段组**；而"该角色不可调该端点"是另一回事。
            //   契约未为后者另立 code ⇒ 同一 2001 承载两种语义。
            //   裁定权在契约 owner（补 code？还是收窄 2001 的语义？），不由实现侧代拍。
            // ------------------------------------------------------------------
            ResponseEntity<String> resp = get("/api/v1/stores", token("client", "own_store"));
            assertEquals(2001, code(resp));

            JsonNode d = data(resp);
            assertFalse(d.has("denied_fields"),
                    "🛑 A3 是【角色级】拒绝（被拒的是整个端点，不是某个字段）⇒ 刻意【不带】"
                            + "denied_fields。若这里出现了字段名，说明有人把它和【字段级】拒绝混为一谈 —— "
                            + "那会让客户端以为「去掉某个参数就能重试」，而实际上换任何参数都不会通过。"
                            + "实际 data=" + d);
            assertTrue(d.isMissingNode() || d.isNull(),
                    "🛑 A3 角色级拒绝的 data 应为缺省 —— 契约 envelope-rule 与既有实现一致。"
                            + "实际 data=" + d);
        }

        @Test
        @DisplayName("契约声明的三个端角色调 A3 → 200（装配链真的放行）")
        void contract_callable_roles_reach_the_stores_endpoint() {
            for (String role : List.of("therapist", "meridian", "manager", "area", "hq")) {
                ResponseEntity<String> resp = get("/api/v1/stores", token(role, null));
                assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "角色 " + role + " 调 A3 必须 200（契约 x-callable-roles: "
                                + "[therapist, meridian, admin]，admin 展开 = manager/area/hq）。\n"
                                + "🛑 若拿到 403，最常见的成因是 @RequirePermission 的码 "
                                + "store:read 未登记进 PermissionRegistry ——"
                                + "而那正是本仓库已发生过三次的同类缺陷。实际: "
                                + resp.getStatusCode() + " body=" + resp.getBody());
            }
        }

        /**
         * 🛑 判据取"<b>恰好 1 家</b>"而不是"非空"：
         * {@code total} 比 {@code items} 更能证明过滤生效 ——
         * 若只断言"有数据"，"过滤没生效（返回全部 3 家）"也会通过。
         */
        @Test
        @DisplayName("行级过滤：own_store → 恰好 1 家；region → 恰好 2 家；all → 3 家")
        void row_level_filtering_is_actually_applied() {
            // 调理师 / 经络师 / 门店负责人 → own_store（锚点在 S_STORE_A）
            for (String role : List.of("therapist", "meridian", "manager")) {
                JsonNode d = data(get("/api/v1/stores", token(role, null)));
                assertEquals(1, d.path("total").asInt(),
                        role + " 的行级范围是 own_store ⇒ 只能看到锚点那 1 家。"
                                + "🛑 若 total=3，说明行级过滤没生效（他在看全租户门店台账）——"
                                + "而那不会报错，只会让他多看到两家。实际 total=" + d.path("total"));
                assertEquals(S_STORE_A, d.path("items").path(0).path("store_id").asText(),
                        role + " 看到的必须是他的锚点门店 S_STORE_A");
            }

            // 区域督导 → region（锚点辖区 = S_REGION，含 A / B 两家）
            JsonNode area = data(get("/api/v1/stores", token("area", null)));
            assertEquals(2, area.path("total").asInt(),
                    "🛑 区域督导的辖区只有 2 家门店（S_STORE_A / S_STORE_B）。"
                            + "若 total=3，说明 region 过滤被写成了『全租户』—— "
                            + "而三家门店分属两个辖区的底数据正是为了让这个错误【可被看见】："
                            + "若全部门店同属一个辖区，『过滤生效』与『过滤没生效』会给出相同的数字。"
                            + "实际 total=" + area.path("total"));

            // 总部 → all
            JsonNode hq = data(get("/api/v1/stores", token("hq", null)));
            assertEquals(3, hq.path("total").asInt(),
                    "总部 hq 的行级范围是 all ⇒ 看到租户内全部 3 家门店");
        }

        @Test
        @DisplayName("分页：page_size=1 时 items 恰好 1 条，且 total 仍是过滤后的总数（两者同源）")
        void pagination_total_and_items_come_from_the_same_where_clause() {
            JsonNode d = data(get("/api/v1/stores?page=1&page_size=1", token("hq", null)));
            assertEquals(1, d.path("items").size(), "page_size=1 ⇒ 本页恰好 1 条");
            assertEquals(3, d.path("total").asInt(),
                    "🛑 total 必须是【过滤后的总数】3，而不是本页条数 1 ——"
                            + "若 total 用了无条件计数或本页计数，客户端会算出错误的页数，"
                            + "翻过去拿空数组而【不报错】，只是列表看起来少了一截");
            assertEquals(1, d.path("page").asInt(), "page 必须回显【生效值】1");
            assertEquals(1, d.path("page_size").asInt(), "page_size 必须回显【生效值】1");

            JsonNode p2 = data(get("/api/v1/stores?page=2&page_size=1", token("hq", null)));
            assertEquals(1, p2.path("items").size(), "第 2 页同样有 1 条（共 3 家）");
            assertFalse(d.path("items").path(0).path("store_id").asText()
                            .equals(p2.path("items").path(0).path("store_id").asText()),
                    "🛑 第 1 页与第 2 页的第一条必须是【不同的门店】——"
                            + "若相同，说明 OFFSET 没生效（分页形同虚设，且它不报错）");
        }

        @Test
        @DisplayName("出站字段恰好契约 Store 的三项（不多不少）")
        void store_item_shape_matches_the_contract_schema_exactly() {
            JsonNode item = data(get("/api/v1/stores", token("hq", null)))
                    .path("items").path(0);
            assertEquals(3, item.size(),
                    "🛑 每项恰好 store_id / name / franchise_type 三项 —— "
                            + "契约 Store schema 只有这三项。多下发 region_id / device_model 之类"
                            + "会让『响应体多一个键』这件事在类型层面不可见（用 Map 装配时尤其如此）。"
                            + "实际 keys=" + item);
            assertTrue(item.has("store_id") && item.has("name") && item.has("franchise_type"),
                    "三项必须齐备，实际: " + item);
            assertTrue(List.of("直营", "加盟").contains(item.path("franchise_type").asText()),
                    "franchise_type 必须是契约 enum 字面 [直营, 加盟]（中文字面，"
                            + "不是布尔、不是英文）—— 客户端按枚举做的分支依赖它。实际: "
                            + item.path("franchise_type"));
        }

        @Test
        @DisplayName("page_size 越界 → 400(1001)【拒绝】而非静默夹逼")
        void out_of_range_page_size_is_rejected_not_clamped() {
            ResponseEntity<String> resp = get("/api/v1/stores?page=1&page_size=500", token("hq", null));
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                    "🛑 page_size=500 必须 400（契约 parameters.PageSize.maximum: 100）。"
                            + "夹逼到 100 看起来更友好，代价是客户端无从知道自己拿到的是第几页 —— "
                            + "它以为自己要了 500 条、实际拿到 100 条，于是把"
                            + "『还有 400 条』当成『只有 100 条』。这类静默改动不报错。实际: "
                            + resp.getStatusCode() + " body=" + resp.getBody());
            assertEquals(1001, code(resp), "应报 1001 VALIDATION_FAILED");

            ResponseEntity<String> zero = get("/api/v1/stores?page=0", token("hq", null));
            assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode(),
                    "🛑 page=0 必须 400 —— 契约 parameters.Page.minimum: 1。"
                            + "把 0 当作『没传』来兜底是另一种静默改动："
                            + "『我要了第 0 页』与『我要了第 1 页』会拿到同一个结果而不报错。实际: "
                            + zero.getStatusCode());
            assertEquals(1001, code(zero), "page=0 应报 1001");
        }

        @Test
        @DisplayName("缺省分页：不带参数 → page=1 / page_size=20（契约默认回显生效值）")
        void default_pagination_is_echoed_as_effective_values() {
            JsonNode d = data(get("/api/v1/stores", token("hq", null)));
            assertEquals(1, d.path("page").asInt(), "缺省 page = 1");
            assertEquals(20, d.path("page_size").asInt(), "缺省 page_size = 20");
        }

        @Test
        @DisplayName("🛑 需锚点而缺锚点 → 拒绝，【绝不】回落为全量")
        void missing_anchor_is_rejected_and_never_falls_back_to_all() {
            // 该员工 store_id 为空；token 声明 own_store（与角色默认一致）
            ResponseEntity<String> resp = get("/api/v1/stores",
                    token("meridian", "own_store", S_STAFF_NO_STORE));
            assertTrue(resp.getStatusCode().is4xxClientError(),
                    "🛑 档案里没有门店归属的员工调 A3 必须被拒（403），"
                            + "而【绝不能】拿到全租户门店 —— 把『缺锚点』读成"
                            + "『那就给他全量吧』是把一次【数据不完整】变成一次【越权】的最短路径。"
                            + "实际: " + resp.getStatusCode() + " body=" + resp.getBody());
            assertNotSuccess(resp);
        }

        @Test
        @DisplayName("契约自描述：贴 store:read、不贴 StaffOnly、客户被显式点名拒、越界拒而非夹逼")
        void contract_self_description_matches_the_decisions() {
            JsonNode desc = data(get("/api/v1/stores/contract", null));
            assertEquals("store:read", desc.path("permission_code").asText());
            assertEquals(false, desc.path("staff_only_attached").asBoolean(),
                    "A3 必须自证『不贴 @StaffOnly』—— x-client-forbidden: false ≠ "
                            + "x-client-explicitly-denied: true");
            assertEquals(false, desc.path("client_callable").asBoolean(),
                    "A3 必须自证客户不可调");
            assertEquals(List.of("200", "403"), toList(desc.path("declared_responses")),
                    "A3 的契约声明响应集必须逐字为 [200, 403]（没有 401）");
            assertEquals("拒绝（400）而非夹逼 —— 夹逼是静默改动用户输入",
                    desc.path("out_of_range_handling").asText());
        }
    }

    // ==================================================================
    // 三、口径来源（config #43 真的被解析了吗）
    // ==================================================================

    @Nested
    @DisplayName("三 · 口径来源（config #43 → 运行期档位）")
    class ConfigSource {

        /**
         * 🛑 本组把"口径真的来自 config #43"变成运行时事实。
         *
         * <p>它的证明逻辑：{@code ConfigSeedBandProfileSource} 解析的是
         * {@code dy-config} 的 {@code 02_slots_seed.sql}。若那条链断在任一处
         * （文件改名 / 正则失配 / 矩阵硬锁被改坏），应用<b>起不来</b> ——
         * 本套件连上下文都建不起来。故"本套件能跑"<b>本身就是</b>链路成立的证据。
         * 这里再把它的可观测产物固定下来。
         */
        @Test
        @DisplayName("#43 的四个可观测产物逐一落地（客户 ①②✓③④✗ / 经络师四✓ / 门店客服四✗）")
        void config_43_values_are_visible_through_the_endpoint() {
            JsonNode client = data(get("/api/v1/auth/me", token("client", "own_store")))
                    .path("band_visibility");
            assertEquals(List.of(true, true, false, false),
                    List.of(client.path("field_group_1_raw").asBoolean(),
                            client.path("field_group_2_status").asBoolean(),
                            client.path("field_group_3_gap_reason").asBoolean(),
                            client.path("field_group_4_derived").asBoolean()),
                    "🛑 客户档位必须逐字等于 config #43 的 customer 行"
                            + "（raw_data:true, capture_status:true, gap_reason:false, derived_result:false）。"
                            + "若不符，说明运行期口径与声明文件分叉了 —— 而分叉的方向若是『③④ 变 true』，"
                            + "那就是内部信息外泄。实际: " + client);

            JsonNode meridian = data(get("/api/v1/auth/me", token("meridian", "own_store")))
                    .path("band_visibility");
            assertEquals(4, countTrue(meridian),
                    "经络师四档全可见（#43 的 meridian_therapist 行）");
        }

        @Test
        @DisplayName("两份口径独立：#43（手环四档）与 #40（退款二分）在同一响应里各自生效且不互相干扰")
        void the_two_matrices_are_independent() {
            JsonNode meridian = data(get("/api/v1/auth/me", token("meridian", "own_store")));
            assertEquals(4, countTrue(meridian.path("band_visibility")),
                    "#43 给经络师四档全可见");
            assertTrue(meridian.path("refund_visibility").asBoolean(),
                    "#40 给经络师 visible=true");

            JsonNode therapist = data(get("/api/v1/auth/me", token("therapist", "own_store")));
            assertEquals(4, countTrue(therapist.path("band_visibility")),
                    "🛑 #43 给【调理师】也是四档全可见（therapist 行 = 全 true）——"
                            + "这【不】矛盾：调理师对 ④ 是可见的，"
                            + "『不作对客户不利依据』是业务使用纪律（derived_not_adverse_to_customer），"
                            + "不是接口可见性。本域刻意不把那条纪律读成档位收窄"
                            + "（读错方向会让调理师的判定工作无法开展）");
            assertFalse(therapist.has("refund_visibility"),
                    "而 #40 对调理师不下发（x-visible-to 不含 therapist）——"
                            + "两份口径的裁剪规则各自独立");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

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

    /**
     * 读取信封 message —— 用于断言上游契约 §1.2「message 必须给出缺失项名称 / 档位名称
     * （不得模糊报错）」那条纪律的<b>人可读方向</b>。
     */
    private String message(ResponseEntity<String> resp) {
        try {
            return MAPPER.readTree(resp.getBody()).path("message").asText("");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /** 断言信封 code != 0（用于"必须失败"的负向用例）。 */
    private void assertNotSuccess(ResponseEntity<String> resp) {
        int c = code(resp);
        assertTrue(c != 0, "该请求必须失败（code != 0），实际 code=" + c
                + " body=" + resp.getBody());
    }

    private static List<String> toList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static long countTrue(JsonNode obj) {
        long n = 0;
        for (java.util.Iterator<JsonNode> it = obj.elements(); it.hasNext(); ) {
            if (it.next().asBoolean()) {
                n++;
            }
        }
        return n;
    }

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
    }

    /** 真签名 JWT（HS256），staff_id 由角色确定 —— 与既有 E2E 同口径。 */
    private static String token(String role, String scope) {
        return token(role, scope, staffIdFor(role));
    }

    /** 指定 staff_id 的 token（用于"无门店归属的员工"这类用例）。 */
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

    /**
     * token 里的 {@code staff_id}。
     *
     * <p>🛑 必须<b>合法 UUID</b>（与 S2-5 那套同一条教训）：A2/A3 都用它做
     * <b>锚点解算</b>的输入，非 UUID 会抛 {@code TENANT_MISMATCH(2003)} ——
     * 那会把"行级过滤对不对"的问题掩盖成一次无关的格式校验失败。
     *
     * <p>🛑 但<b>不能</b>用 {@code UUID.nameUUIDFromBytes("staff:" + role)}
     * 这样"人人不同的合成 ID"：那在库里的 {@code staff} 表里查无此人 ⇒ 锚点为空 ⇒
     * own_store 角色会撞上"缺锚点即拒"，于是<b>全部</b>用例都变成 403，
     * 而它们想问的是"过滤对不对"。故这里的映射是<b>显式</b>指到已灌的底数据上。
     */
    private static String staffIdFor(String role) {
        return S_STAFF;
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
     * 构造一个<b>必然验签失败</b>的 token。
     *
     * <p>🛑 改签名段的<b>首个</b>字符，而不是最后一个：HS256 的 32 字节签名经
     * base64url 编码为 43 字符，最后一个字符的有效位数不满 8 ——
     * 改动它可能解码出<b>完全相同的字节</b>，于是"篡改"没发生、请求变成 200，
     * 而断言会把它读成"验签被绕过"。这是本项目已记录过的真实陷阱。
     */
    private static String tamperSignature(String jwt) {
        int lastDot = jwt.lastIndexOf('.');
        String head = jwt.substring(0, lastDot + 1);
        String sig = jwt.substring(lastDot + 1);
        char first = sig.charAt(0);
        char swapped = first == 'A' ? 'B' : 'A';
        return head + swapped + sig.substring(1);
    }
}