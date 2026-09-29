package com.diaoyuanyun.dy.app.band;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3-3 证人套件 · 契约域 E（E1/E2/E3/E6）的<b>真请求回归</b>。
 *
 * <h2>判据设计</h2>
 * <pre>
 *  客户上报 E1 同步批次（state=synced）   → 200
 *  客户上报 E2 遥测（日型 metric）         → 200
 *  客户读 E3 遥测                          → 200，gap_reason 键【缺席】
 *  经络师读 E3                             → 200，gap_reason 可见（若有）
 *  客户读 E6 同步状态卡                    → 200
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S3-3 · 契约域 E（E1/E2/E3/E6）真请求回归")
class BandEndpointsE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000eeee";

    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000f1";
    private static final String S_BAND = "e2e00000-0000-0000-0000-0000000000f2";

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
            jdbc.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band_daily_coverage WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band WHERE tenant_id = ?::uuid AND band_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'", TENANT);
            // 🛑 V11（B-1）起：密钥材料必须先于租户删除。
            //    `tenant_kek.tenant_id` → `tenant.id` 有 FK（防孤立密钥），
            //    故带密钥材料的租户【无法】被静默删除 —— 这是刻意的 fail-closed：
            //    删租户而不删密钥会让"租户已注销"与"其密文仍可解"同时成立。
            //    真实运维含义（值得登记）：【租户注销流程必须显式处置密钥材料】。
            //    ⚠️ 本测试未销毁过主体密钥，故 subject_key_tombstone 为空；
            //       而墓碑表是【删除免疫】的（V11 的 RULE DO INSTEAD NOTHING），
            //       任何写过的墓碑都会永久留在库中 —— 这是等保对审计记录的要求，
            //       不是缺陷。测试里不写墓碑，故此处无需（也无法）清理它。
            jdbc.update("DELETE FROM subject_dek WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM tenant_kek WHERE tenant_id = ?::uuid", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S3-3-E域') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                    + "VALUES (?::uuid, ?::uuid, 'E2E客户', 'CONSENTED') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, model, bound_at, status) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', 'model-x', CURRENT_DATE, 'active') ON CONFLICT DO NOTHING",
                    S_BAND, TENANT, S_CUSTOMER);
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

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    // ==================================================================
    // 嵌套
    // ==================================================================

    @Nested
    @DisplayName("E1 同步批次")
    class SyncBatch {

        @Test
        @DisplayName("客户上报 synced 批次 → 200")
        void client_reports_synced_batch() {
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"batch_no\":\"" + UUID.randomUUID() + "\",\"trigger\":\"on_show_hot\","
                    + "\"state\":\"synced\",\"synced_at\":\"2026-09-26T10:00:00Z\","
                    + "\"last_success_date\":\"2026-09-26\"}";
            assertEquals(200, post("/api/v1/band/sync-batches", body, token("client")).getStatusCodeValue(),
                    "客户上报 synced 批次应 200");
        }

        @Test
        @DisplayName("同一 batch_no 重复上报 → 409（幂等）")
        void duplicate_batch_no_is_409() {
            String batchNo = UUID.randomUUID().toString();
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"batch_no\":\"" + batchNo + "\",\"trigger\":\"on_show_hot\","
                    + "\"state\":\"synced\",\"synced_at\":\"2026-09-26T10:00:00Z\","
                    + "\"last_success_date\":\"2026-09-26\"}";
            assertEquals(200, post("/api/v1/band/sync-batches", body, token("client")).getStatusCodeValue());
            assertEquals(409, post("/api/v1/band/sync-batches", body, token("client")).getStatusCodeValue(),
                    "重复 batch_no 应 409 幂等重放");
        }

        @Test
        @DisplayName("🛑 A-5：E1 真落库 —— 契约侧三列（contract_state/trigger/batch_no）写入且旧列同派生")
        void e1_really_lands_with_contract_side_columns() {
            String batchNo = UUID.randomUUID().toString();
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"batch_no\":\"" + batchNo + "\",\"trigger\":\"on_show_cold\","
                    + "\"state\":\"synced\",\"synced_at\":\"2026-09-26T10:00:00Z\","
                    + "\"last_success_date\":\"2026-09-26\"}";
            assertEquals(200, post("/api/v1/band/sync-batches", body, token("client")).getStatusCodeValue(),
                    "E1 上报应 200");

            inTenant(() -> {
                // 契约侧三列（V9 新增）必须落满 —— 这是"E1 真的落库了"的机械证据
                java.util.Map<String, Object> r = jdbc.queryForMap(
                        "SELECT contract_state, contract_trigger, batch_no, trigger_source, result"
                                + " FROM band_sync_log WHERE batch_no = ?::uuid", batchNo);
                assertEquals("synced", r.get("contract_state"),
                        "契约侧 contract_state 必须为契约四态之一（不是旧两态）");
                assertEquals("on_show_cold", r.get("contract_trigger"),
                        "契约侧 contract_trigger 必须为契约五值之一 —— 冷/热启动在此【可区分】，"
                                + "而旧列 onShow 区分不了");
                // 旧列由契约值【派生】（单点派生 ⇒ 不可能分叉）
                assertEquals("success", r.get("result"),
                        "旧列 result 必须由 contract_state 派生（synced→success）");
                assertEquals("onShow", r.get("trigger_source"),
                        "旧列 trigger_source 必须由 contract_trigger 派生（on_show_cold→onShow）");
                return null;
            });
        }

        @Test
        @DisplayName("🛑 A-5：state=no_data_today 不得被记成 failed（旧 result 落 null）")
        void no_data_today_is_not_a_failure() {
            String batchNo = UUID.randomUUID().toString();
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"batch_no\":\"" + batchNo + "\",\"trigger\":\"daily_report\","
                    + "\"state\":\"no_data_today\",\"synced_at\":\"2026-09-26T10:00:00Z\"}";
            assertEquals(200, post("/api/v1/band/sync-batches", body, token("client")).getStatusCodeValue(),
                    "no_data_today 应被接受（它不是失败）: ");

            inTenant(() -> {
                java.util.Map<String, Object> r = jdbc.queryForMap(
                        "SELECT contract_state, result, fail_stage FROM band_sync_log"
                                + " WHERE batch_no = ?::uuid", batchNo);
                assertEquals("no_data_today", r.get("contract_state"));
                assertNull(r.get("result"),
                        "🛑『今日无数据』不得写成 failed —— 那会把一次正常的技术性缺失"
                                + "变成一条故障记录。旧 result 只有成败两态，故本态下必须落 null");
                assertNull(r.get("fail_stage"), "no_data_today 不是失败，fail_stage 不得有值");
                return null;
            });
        }

        @Test
        @DisplayName("🛑 A-5：E6 只读批次行（contract_state 非空），尝试日志行不得污染四态卡")
        void e6_ignores_attempt_log_rows() {
            // 造一条「尝试日志」形态的行：contract_state 为 null、trigger_source 中文两态
            String attemptLogId = "e2e00000-0000-0000-0000-0000000000f9";
            inTenant(() -> {
                jdbc.update("INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id,"
                                + " attempt_at, trigger_source, result, contract_state, contract_trigger)"
                                + " VALUES (?::uuid, ?::uuid, ?::uuid, now() + interval '1 hour',"
                                + " '到店核销', 'success', NULL, NULL)"
                                + " ON CONFLICT (sync_log_id) DO NOTHING",
                        attemptLogId, TENANT, S_BAND);
                return null;
            });

            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/band/sync-status",
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(),
                    "🛑 E6 必须 200 —— 若它把中文触发源/无契约态的尝试日志行当批次行读，"
                            + "SyncBatchRow 的 fail-closed 校验会在读路径抛异常（一条历史数据搞挂接口）: "
                            + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertTrue(data.at("/state").isTextual(), "应有 state");
            // 清理探针行，避免污染同租户其他用例
            inTenant(() -> {
                jdbc.update("DELETE FROM band_sync_log WHERE sync_log_id = ?::uuid", attemptLogId);
                return null;
            });
        }
    }

    @Nested
    @DisplayName("E2 遥测上行")
    class Telemetry {

        @Test
        @DisplayName("客户上报日型遥测 → 200")
        void client_reports_daily_telemetry() {
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"metric\":\"steps\",\"date\":\"2026-09-26\",\"value\":8500}";
            assertEquals(200, post("/api/v1/band/telemetry", body, token("client")).getStatusCodeValue(),
                    "客户上报日型遥测应 200");
        }
    }

    @Nested
    @DisplayName("E3 遥测读取")
    class TelemetryRead {

        @Test
        @DisplayName("客户读 E3 → 200 且 gap_reason 键缺席")
        void client_reads_no_gap_reason() {
            // 种一条【带 gap_reason】的遥测（缺口归因仅门店/管理端可见）；hour 递增使幂等键唯一
            // 🛑 V10 起 value_enc 只接受密文信封（DB 层 CHECK LIKE 'dy1:%'），而本行
            //    刻意【不给值】：gap_reason='not_worn'（未佩戴）本就意味着没有测量值，
            //    补一个数反而与缺口归因自相矛盾（硬纪律 #4：NULL = 缺失，严禁补 0）。
            //    本测试断言的是 gap_reason 的可见性，与 value 无关 ——
            //    真正的加解密往返由 BandTelemetryEncryptionTest 用真密钥栈断言。
            inTenant(() -> {
                jdbc.update("INSERT INTO band_telemetry (telemetry_id, tenant_id, customer_id, device_id,"
                                + " metric, date, hour, data_source, gap_reason, sync_state, synced_at) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'steps', CURRENT_DATE, 1,"
                                + " '手环', 'not_worn', 'synced', now())",
                        UUID.randomUUID(), TENANT, S_CUSTOMER, S_BAND);
                return null;
            });
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/band/telemetry",
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户读 E3 应 200: " + resp.getBody());
            JsonNode metrics = parse(resp.getBody()).at("/data/metrics");
            assertTrue(metrics.isArray() && metrics.size() > 0, "应有遥测数据");
            // 🛑 断言【所有】条目都不含 gap_reason —— 只看第一条会让"泄漏发生在别的条目"被漏掉
            for (JsonNode m : metrics) {
                assertFalse(m.has("gap_reason"),
                        "gap_reason 客户不下发（任一条目泄出即违约）: " + resp.getBody());
            }
        }

        @Test
        @DisplayName("经络师读 E3 → 200 且 gap_reason 可见")
        void staff_reads_gap_reason() {
            inTenant(() -> {
                jdbc.update("INSERT INTO band_telemetry (telemetry_id, tenant_id, customer_id, device_id,"
                                + " metric, date, hour, data_source, gap_reason, sync_state, synced_at) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'steps', CURRENT_DATE, 2,"
                                + " '手环', 'not_worn', 'synced', now())",
                        UUID.randomUUID(), TENANT, S_CUSTOMER, S_BAND);
                return null;
            });
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/band/telemetry",
                    token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "经络师读 E3 应 200: " + resp.getBody());
            JsonNode metrics = parse(resp.getBody()).at("/data/metrics");
            assertTrue(metrics.isArray() && metrics.size() > 0, "应有遥测数据");
            // 🛑 断言【至少有一条】含 gap_reason（staff 可见），而非只看第一条
            boolean sawGap = false;
            for (JsonNode m : metrics) {
                if (m.has("gap_reason") && "not_worn".equals(m.get("gap_reason").asText())) {
                    sawGap = true;
                }
            }
            assertTrue(sawGap,
                    "staff 响应体应有 gap_reason=not_worn（x-visible-to 含 staff）: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("E6 同步状态卡")
    class SyncStatus {

        @Test
        @DisplayName("客户读 E6 → 200（四态卡）")
        void client_reads_sync_status() {
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/band/sync-status",
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户读 E6 应 200: " + resp.getBody());
            assertTrue(parse(resp.getBody()).at("/data/state").isTextual(), "应有 state");
        }
    }
}