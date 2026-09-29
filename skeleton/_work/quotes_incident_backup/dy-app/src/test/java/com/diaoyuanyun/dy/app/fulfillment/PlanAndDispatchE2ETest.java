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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3-2b 证人套件 · 契约域 D（D5/D6）的<b>真请求回归</b>。
 *
 * <h2>判据设计</h2>
 * <pre>
 *  经络师调 D5-a（出方案）            → 200，version=1，status=draft
 *  客户调 D5-b（查方案）              → 200（含 client）
 *  经络师调 D5-c（审核退回，缺 reason）→ 400（领域构造器挡退回必填）
 *  经络师调 D5-c（审核通过）          → 200，status→approved
 *  经络师调 D6（方案未审核）          → 403 GATE_MISSING + plan_approved
 *  经络师调 D6（方案已审核）          → 200
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S3-2b · 契约域 D（D5/D6）真请求回归")
class PlanAndDispatchE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000d6d6";

    private static final String S_REGION = "e2e00000-0000-0000-0000-0000000000e1";
    private static final String S_STORE = "e2e00000-0000-0000-0000-0000000000e2";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000e3";
    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000e4";
    private static final String S_DEVICE = "e2e00000-0000-0000-0000-0000000000e5";

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
            jdbc.update("DELETE FROM device_dispatch WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM plan_review WHERE tenant_id = ?::uuid", TENANT);
            // 🛑 plan_id 是 UUID.randomUUID()（随机前缀），不能按 'e2e00000-%' 过滤；
            //    本测试独占 TENANT，直接按租户全删。
            jdbc.update("DELETE FROM plan WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM device WHERE tenant_id = ?::uuid AND device_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S3-2-D5/D6') "
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
            jdbc.update("INSERT INTO device (device_id, tenant_id, store_id, model, status) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, '杠2', 'active') ON CONFLICT DO NOTHING",
                    S_DEVICE, TENANT, S_STORE);
            return null;
        });
    }

    private static <T> T inTenant(Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

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

    private static String planBody() {
        return "{\"customer_id\":\"" + S_CUSTOMER + "\",\"treatment_json\":\"{}\","
                + "\"lifestyle_json\":\"{}\",\"intent_params\":\"{}\"}";
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    private String createPlanId() {
        ResponseEntity<String> resp = post("/api/v1/plans", planBody(), token("meridian"));
        assertEquals(200, resp.getStatusCodeValue(), "出方案应 200: " + resp.getBody());
        return parse(resp.getBody()).at("/data/plan_id").asText();
    }

    // ==================================================================
    // 嵌套测试
    // ==================================================================

    @Nested
    @DisplayName("D5 方案出具与查阅")
    class Plan {

        @Test
        @DisplayName("经络师出方案 → 200 version=1 status=draft")
        void create_plan() {
            ResponseEntity<String> resp = post("/api/v1/plans", planBody(), token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "出方案应 200: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertEquals(1, data.at("/version").asInt());
            assertEquals("draft", data.at("/status").asText());
        }

        @Test
        @DisplayName("客户查方案 → 200（含 client，不贴码）")
        void client_get_plan() {
            String planId = createPlanId();
            ResponseEntity<String> resp = get("/api/v1/plans/" + planId, token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户应能查方案: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("D5-c 方案审核")
    class Review {

        @Test
        @DisplayName("退回缺 reason → 400（领域构造器挡）")
        void reject_without_reason_is_400() {
            String planId = createPlanId();
            ResponseEntity<String> resp = post("/api/v1/plans/" + planId + "/reviews",
                    "{\"result\":\"退回\"}", token("meridian"));
            assertEquals(400, resp.getStatusCodeValue(), "退回必填 reason 应 400: " + resp.getBody());
        }

        @Test
        @DisplayName("同人自助通过缺 second_confirm → 409（US-2 生效）")
        void self_approve_without_confirm_is_409() {
            String planId = createPlanId();
            ResponseEntity<String> resp = post("/api/v1/plans/" + planId + "/reviews",
                    "{\"result\":\"通过\"}", token("meridian"));
            assertEquals(409, resp.getStatusCodeValue(),
                    "同人自助通过缺二次确认应 409: " + resp.getBody());
        }

        @Test
        @DisplayName("审核通过（带 second_confirm）→ 200 status→approved")
        void approve_plan() {
            String planId = createPlanId();
            ResponseEntity<String> resp = post("/api/v1/plans/" + planId + "/reviews",
                    "{\"result\":\"通过\",\"second_confirm\":true}", token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "审核通过应 200: " + resp.getBody());
            // 复查状态
            ResponseEntity<String> check = get("/api/v1/plans/" + planId, token("meridian"));
            assertEquals("approved", parse(check.getBody()).at("/data/status").asText());
        }
    }

    @Nested
    @DisplayName("D6 设备下发")
    class Dispatch {

        @Test
        @DisplayName("方案未审核下发 → 403 GATE_MISSING + plan_approved")
        void dispatch_unapproved_plan_is_403() {
            String planId = createPlanId();
            String body = "{\"plan_id\":\"" + planId + "\",\"plan_version\":1,"
                    + "\"store_id\":\"" + S_STORE + "\",\"device_id\":\"" + S_DEVICE + "\","
                    + "\"param_snapshot\":\"{}\",\"result\":\"成功\"}";
            ResponseEntity<String> resp = post("/api/v1/device-dispatches", body, token("meridian"));
            assertEquals(403, resp.getStatusCodeValue(), "方案未审核应 403: " + resp.getBody());
            assertTrue(parse(resp.getBody()).at("/data/missing_items").toString().contains("plan_approved"),
                    "缺失项应含 plan_approved: " + resp.getBody());
        }

        @Test
        @DisplayName("审核后下发 → 200")
        void dispatch_approved_plan() {
            String planId = createPlanId();
            post("/api/v1/plans/" + planId + "/reviews",
                    "{\"result\":\"通过\",\"second_confirm\":true}", token("meridian"));
            String body = "{\"plan_id\":\"" + planId + "\",\"plan_version\":1,"
                    + "\"store_id\":\"" + S_STORE + "\",\"device_id\":\"" + S_DEVICE + "\","
                    + "\"param_snapshot\":\"{}\",\"result\":\"成功\"}";
            ResponseEntity<String> resp = post("/api/v1/device-dispatches", body, token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "审核后下发应 200: " + resp.getBody());
        }
    }
}