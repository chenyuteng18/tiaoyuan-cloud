package com.diaoyuanyun.dy.app.fulfillment;

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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3-2a 证人套件 · 契约域 D（D1~D4）的<b>真请求回归</b>。
 *
 * <h2>为什么 D1 的四道闸门必须"真请求"</h2>
 * 四道闸门（禁忌/同意书/协议/方案）依赖<b>四张跨域表</b>的存在性断言，
 * 单测直调服务层能覆盖"规则写对"，但<b>看不见</b>：
 * <pre>
 *  ① 装配：TenantContextFilter 是否把 token 的 role/staff_id 写进上下文
 *  ② 权限：D1 的 fulfillment:write 是否有主（否则 D1 对全员 403）
 *  ③ 四道闸门的 403 是否真以 GATE_MISSING(2002) + 逐字 missing_items 出站
 *  ④ 客户维度全局账本 visit_no 是否跨次递增
 *  ⑤ D2 的 gate_check_json/abnormal_note 客户是否【不下发】
 * </pre>
 *
 * <h2>判据设计</h2>
 * <pre>
 *  经络师调 D1（四道闸门全过）      → 200，visit_no 递增
 *  经络师调 D1（缺同意书等）        → 403(2002) + data.missing_items 逐字
 *  客户调 D1                        → 403（不持 fulfillment:write）
 *  客户调 D2                        → 200 且 gate_check_json 键【缺席】
 *  客户/经络师调 D3/D4              → 200
 *  D3 同日重复                      → 409
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S3-2a · 契约域 D（D1~D4）真请求回归")
class FulfillmentDomainDEndpointsE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000d1d1";

    private static final String S_REGION = "e2e00000-0000-0000-0000-0000000000d1";
    private static final String S_STORE = "e2e00000-0000-0000-0000-0000000000d2";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000d3";
    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000d4";
    private static final String S_PLAN = "e2e00000-0000-0000-0000-0000000000d5";

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
    void seedIfNeeded() {
        if (dataSource == null) {
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
        if (jdbc == null || dataSource == null) {
            return;
        }
        inTenant(() -> {
            jdbc.update("DELETE FROM daily_report WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM visit WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM agreement WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM plan WHERE tenant_id = ?::uuid AND plan_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM consent WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM screening_record WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    /** 灌底：四道闸门全过的客户（禁忌通过 + 同意书 + 协议 + 方案 approved）。 */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S3-2-D域') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) VALUES (?::uuid, ?::uuid, 'E2E区') "
                    + "ON CONFLICT DO NOTHING", S_REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 'E2E店', '直营') ON CONFLICT DO NOTHING",
                    S_STORE, TENANT, S_REGION);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, S_STORE);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                    + "VALUES (?::uuid, ?::uuid, 'E2E客户', 'CONSENTED') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            // ① 禁忌通过
            jdbc.update("INSERT INTO screening_record (screening_id, tenant_id, customer_id, items_json, result, operator_id) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, '{}', '通过', ?::uuid) ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), TENANT, S_CUSTOMER, S_STAFF);
            // ② 知情同意书已签
            jdbc.update("INSERT INTO consent (consent_id, tenant_id, customer_id, auth_scope_json, band_willingness, signed_at, evidence_hash) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, '[]', '自愿佩戴', now(), 'hash') ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), TENANT, S_CUSTOMER);
            // ④ 方案 approved（visit 依赖 plan_id）
            jdbc.update("INSERT INTO plan (plan_id, tenant_id, customer_id, version, treatment_json, lifestyle_json, intent_params, status) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 1, '{}', '{}', '{}', 'approved') ON CONFLICT DO NOTHING",
                    S_PLAN, TENANT, S_CUSTOMER);
            // ③ 调理协议已签（FK plan(plan_id, version)）
            jdbc.update("INSERT INTO agreement (agreement_id, tenant_id, customer_id, plan_id, plan_version, refund_clause_snapshot, signed_at, signer, rendered_snapshot, rendered_hash) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, '{}', now(), '{}', 'rendered', 'hash') ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), TENANT, S_CUSTOMER, S_PLAN);
            return null;
        });
    }

    private static <T> T inTenant(Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // HTTP + JWT
    // ==================================================================

    private ResponseEntity<String> get(String path, String jwt) {
        return exchange(HttpMethod.GET, path, null, jwt);
    }

    private ResponseEntity<String> post(String path, String body, String jwt) {
        return exchange(HttpMethod.POST, path, body, jwt);
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

    private static String token(String role) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + S_STAFF + "\""
                + ",\"role\":\"" + role + "\""
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

    private static String visitBody() {
        return "{\"serving_store_id\":\"" + S_STORE + "\",\"plan_id\":\"" + S_PLAN
                + "\",\"plan_version\":1}";
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    // ==================================================================
    // 嵌套测试
    // ==================================================================

    @Nested
    @DisplayName("D1 服务核销（四道闸门）")
    class Visit {

        @Test
        @DisplayName("经络师调 D1（四道闸门全过）→ 200 且 visit_no 递增")
        void meridian_creates_visit() {
            ResponseEntity<String> r1 = post("/api/v1/customers/" + S_CUSTOMER + "/visits",
                    visitBody(), token("meridian"));
            assertEquals(200, r1.getStatusCodeValue(), "四闸门全过应 200: " + r1.getBody());
            int no1 = parse(r1.getBody()).at("/data/visit_no").asInt();

            ResponseEntity<String> r2 = post("/api/v1/customers/" + S_CUSTOMER + "/visits",
                    visitBody(), token("meridian"));
            assertEquals(200, r2.getStatusCodeValue(), "第二次核销应 200: " + r2.getBody());
            int no2 = parse(r2.getBody()).at("/data/visit_no").asInt();

            assertTrue(no2 > no1, "visit_no 应跨次递增（客户维度全局账本 U2）: " + no1 + " → " + no2);
        }

        @Test
        @DisplayName("客户调 D1 → 403（不持 fulfillment:write）")
        void client_is_403() {
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/visits",
                    visitBody(), token("client"));
            assertEquals(403, resp.getStatusCodeValue(), "客户应 403: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("D1 四道闸门缺失")
    class GateMissing {

        @Test
        @DisplayName("缺同意书 → 403 GATE_MISSING + missing_items 逐字")
        void missing_gate_is_403_with_names() {
            // 删掉该客户的 consent，制造"缺同意书"
            inTenant(() -> {
                jdbc.update("DELETE FROM consent WHERE tenant_id = ?::uuid AND customer_id = ?::uuid",
                        TENANT, S_CUSTOMER);
                return null;
            });
            try {
                ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/visits",
                        visitBody(), token("meridian"));
                assertEquals(403, resp.getStatusCodeValue(), "缺同意书应 403: " + resp.getBody());
                JsonNode body = parse(resp.getBody());
                assertTrue(body.at("/data/missing_items").isArray(),
                        "应回显 missing_items 数组: " + resp.getBody());
                assertTrue(body.at("/data/missing_items").toString().contains("informed_consent"),
                        "缺失项应含 informed_consent: " + resp.getBody());
            } finally {
                // 还原同意书，避免污染其它用例
                inTenant(() -> {
                    jdbc.update("INSERT INTO consent (consent_id, tenant_id, customer_id, auth_scope_json, band_willingness, signed_at, evidence_hash) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '[]', '自愿佩戴', now(), 'hash') ON CONFLICT DO NOTHING",
                            UUID.randomUUID(), TENANT, S_CUSTOMER);
                    return null;
                });
            }
        }
    }

    @Nested
    @DisplayName("D2 服务记录")
    class VisitList {

        @Test
        @DisplayName("客户调 D2 → 200 且 gate_check_json 键缺席")
        void client_sees_no_gate_check() {
            post("/api/v1/customers/" + S_CUSTOMER + "/visits", visitBody(), token("meridian"));
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/visits?page=1&page_size=10",
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户能看服务记录: " + resp.getBody());
            JsonNode items = parse(resp.getBody()).at("/data/items");
            assertTrue(items.isArray() && items.size() > 0, "应有至少一条服务记录");
            assertFalse(items.get(0).has("gate_check_json"),
                    "客户响应体不得出现 gate_check_json（x-visible-to 不含 client）: " + resp.getBody());
        }

        /**
         * 🛑 D2 分页越界必须【拒】(400)，不得静默夹逼（本仓第 58 条）。
         *
         * <p>本方法此前实现为 {@code Math.min(Math.max(pageSize, 1), 100)}，
         * 对 {@code page_size=101} 会把它悄悄改成 100 后返回 200 —— 与
         * A3 {@code GET /stores} 的「超界直接拒」在同一份契约下各走一路，
         * 且两者 200 响应体、{@code tsc}、门禁全绿，客户端无从察觉。
         *
         * <p>契约依据：{@code x-api-protocol.pagination.over-range-policy: reject-400}
         * + {@code x-error-codes.VALIDATION_FAILED.trigger}「约束不满足」。
         */
        @Test
        @DisplayName("D2 page_size 越界 → 400 VALIDATION_FAILED（拒，不夹逼）")
        void oversized_page_size_is_rejected_not_clamped() {
            ResponseEntity<String> resp = get(
                    "/api/v1/customers/" + S_CUSTOMER + "/visits?page=1&page_size=101",
                    token("client"));
            assertEquals(400, resp.getStatusCodeValue(),
                    "page_size=101 越界必须拒（400），而不是夹逼为 100 后返回 200: " + resp.getBody());
            JsonNode body = parse(resp.getBody());
            assertEquals(1001, body.at("/code").asInt(),
                    "越界应报 VALIDATION_FAILED(1001): " + resp.getBody());
            assertFalse(body.at("/data").isArray(),
                    "失败信封不得带正常分页 data: " + resp.getBody());
        }

        /** 页码越界同样必须【拒】，不得「当作没传」兜底成第 1 页。 */
        @Test
        @DisplayName("D2 page=0 → 400（不得当作没传兜底成第 1 页）")
        void zero_page_is_rejected_not_defaulted() {
            ResponseEntity<String> resp = get(
                    "/api/v1/customers/" + S_CUSTOMER + "/visits?page=0&page_size=10",
                    token("client"));
            assertEquals(400, resp.getStatusCodeValue(),
                    "page=0 必须拒 —— 把它当作没传会让『第 0 页』与『第 1 页』"
                            + "拿到同一结果而不报错: " + resp.getBody());
            assertEquals(1001, parse(resp.getBody()).at("/code").asInt(),
                    "越界应报 VALIDATION_FAILED(1001): " + resp.getBody());
        }

        /** 边界：{@code page_size} 恰好等于上界 ⇒ 合法（判据不得写成 ≥，第 55 条）。 */
        @Test
        @DisplayName("D2 page_size=100（恰等上界）→ 200，回显 100")
        void boundary_page_size_is_accepted() {
            ResponseEntity<String> resp = get(
                    "/api/v1/customers/" + S_CUSTOMER + "/visits?page=1&page_size=100",
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(),
                    "page_size 恰好等于上界必须合法: " + resp.getBody());
            assertEquals(100, parse(resp.getBody()).at("/data/page_size").asInt(),
                    "回显的 page_size 应是生效值 100: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("D3/D4 每日填报")
    class DailyReport {

        @Test
        @DisplayName("客户调 D3 → 200 且 source=客户")
        void client_submits_report() {
            String today = java.time.LocalDate.now().toString();
            String body = "{\"date\":\"" + today + "\",\"answers_json\":{\"q1\":\"a\"},\"source\":\"客户\"}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/daily-reports",
                    body, token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户应能填报: " + resp.getBody());
            assertEquals("客户", parse(resp.getBody()).at("/data/source").asText());
        }

        @Test
        @DisplayName("D3 同日重复 → 409（唯一键 uq_daily_report_customer_date）")
        void duplicate_same_day_is_409() {
            String today = java.time.LocalDate.now().toString();
            String body = "{\"date\":\"" + today + "\",\"answers_json\":{\"q1\":\"a\"},\"source\":\"客户\"}";
            post("/api/v1/customers/" + S_CUSTOMER + "/daily-reports", body, token("client"));
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/daily-reports",
                    body, token("client"));
            assertEquals(409, resp.getStatusCodeValue(), "同日重复应 409: " + resp.getBody());
        }
    }
}