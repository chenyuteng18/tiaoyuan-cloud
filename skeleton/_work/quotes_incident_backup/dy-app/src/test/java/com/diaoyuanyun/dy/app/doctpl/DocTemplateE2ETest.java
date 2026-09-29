package com.diaoyuanyun.dy.app.doctpl;

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
 * S3-4 证人套件 · 契约域 I（I1/I2/I4/I5/I6/I8）的<b>真请求回归</b>。
 *
 * <h2>判据设计</h2>
 * <pre>
 *  管理员建模板（I2）       → 200 version=1 is_active=true
 *  管理员新版本（I4）       → 200 version=2，旧版本保留
 *  管理员发布（I6）         → 200，同 doc_type 唯一 active
 *  管理员渲染（I8 白名单）  → 200 且 phone 脱敏
 *  I8 未声明白名单携占位符  → 422（fail-closed）
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S3-4 · 契约域 I（文书模板 I1/I2/I4/I5/I6/I8）真请求回归")
class DocTemplateE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000d0c0";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000d0";

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
            jdbc.update("DELETE FROM doc_template WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S3-4-I域') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            // 管理员角色由 hq token 承担，staff 表仅需 tenant 行（本测试不依赖 staff 锚点）
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, role) "
                    + "VALUES (?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING", S_STAFF, TENANT);
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

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    private String createTemplate(String docType) {
        ResponseEntity<String> resp = post("/api/v1/doc-templates",
                "{\"doc_type\":\"" + docType + "\",\"title\":\"测试模板\",\"content\":\"客户 {{name}} 电话 {{phone}}\","
                        + "\"placeholder_schema\":\"{\\\"fields\\\":[\\\"name\\\",\\\"phone\\\"]}\"}",
                token("hq"));
        assertEquals(200, resp.getStatusCodeValue(), "建模板应 200: " + resp.getBody());
        return parse(resp.getBody()).at("/data/template_id").asText();
    }

    // ==================================================================
    // 嵌套
    // ==================================================================

    @Nested
    @DisplayName("I2 新建模板")
    class Create {

        @Test
        @DisplayName("管理员建模板 → 200 version=1")
        void create_template() {
            ResponseEntity<String> resp = post("/api/v1/doc-templates",
                    "{\"doc_type\":\"知情同意书\",\"title\":\"测试模板\",\"content\":\"正文\","
                            + "\"placeholder_schema\":\"{\\\"fields\\\":[\\\"name\\\"]}\"}",
                    token("hq"));
            assertEquals(200, resp.getStatusCodeValue(), "建模板应 200: " + resp.getBody());
            assertEquals(1, parse(resp.getBody()).at("/data/version").asInt());
            assertTrue(parse(resp.getBody()).at("/data/is_active").asBoolean());
        }
    }

    @Nested
    @DisplayName("I4/I5 版本")
    class Version {

        @Test
        @DisplayName("新版本 → version=2，旧版本保留")
        void new_version() {
            String id = createTemplate("调理协议书");
            ResponseEntity<String> resp = post("/api/v1/doc-templates/" + id + "/versions",
                    "{\"content\":\"新正文\",\"change_reason\":\"修订\"}",
                    token("hq"));
            assertEquals(200, resp.getStatusCodeValue(), "新版本应 200: " + resp.getBody());
            assertEquals(2, parse(resp.getBody()).at("/data/version").asInt());

            // 版本列表应有 2 条
            ResponseEntity<String> list = get("/api/v1/doc-templates/" + id + "/versions", token("hq"));
            assertEquals(2, parse(list.getBody()).at("/data/items").size(),
                    "版本列表应有 2 条（旧版本保留，不可覆盖）");
        }
    }

    @Nested
    @DisplayName("I8 渲染")
    class Render {

        @Test
        @DisplayName("白名单内占位符渲染 → 200 且 phone 脱敏")
        void render_with_mask() {
            String id = createTemplate("隐私与授权须知");
            ResponseEntity<String> resp = post("/api/v1/agreements/g1/render",
                    "{\"template_id\":\"" + id + "\",\"version\":1,"
                            + "\"placeholders\":{\"name\":\"张三\",\"phone\":\"13812345678\"}}",
                    token("hq"));
            assertEquals(200, resp.getStatusCodeValue(), "渲染应 200: " + resp.getBody());
            String rendered = parse(resp.getBody()).at("/data/rendered_snapshot").asText();
            assertTrue(rendered.contains("138****5678"),
                    "phone 应脱敏为 前3后4（138****5678），实际: " + rendered);
            assertTrue(!rendered.contains("13812345678"),
                    "渲染结果不得出现完整手机号");
        }

        @Test
        @DisplayName("未声明白名单携占位符 → 403·2004（fail-closed）")
        void render_no_whitelist_with_placeholder() {
            // 建一个无 placeholder_schema 的模板
            post("/api/v1/doc-templates",
                    "{\"doc_type\":\"到店须知\",\"title\":\"无白名单\",\"content\":\"正文 {{x}}\"}",
                    token("hq"));
            // 找其 template_id（从列表里定位）
            ResponseEntity<String> list = get("/api/v1/doc-templates", token("hq"));
            JsonNode items = parse(list.getBody()).at("/data/items");
            String id = null;
            for (JsonNode it : items) {
                if ("到店须知".equals(it.get("doc_type").asText())) {
                    id = it.get("template_id").asText();
                }
            }
            assertTrue(id != null, "应能找到无白名单模板");
            ResponseEntity<String> resp = post("/api/v1/agreements/g1/render",
                    "{\"template_id\":\"" + id + "\",\"version\":1,\"placeholders\":{\"x\":\"1\"}}",
                    token("hq"));
            assertEquals(403, resp.getStatusCodeValue(),
                    "未声明白名单携占位符应 403·2004（契约 I8 responses 仅 200+403）: " + resp.getBody());
        }

        @Test
        @DisplayName("白名单非空但携白名单外占位符 → 403·2004（规则②）")
        void render_out_of_whitelist() {
            String id = createTemplate("手环数据说明");
            ResponseEntity<String> resp = post("/api/v1/agreements/g1/render",
                    "{\"template_id\":\"" + id + "\",\"version\":1,"
                            + "\"placeholders\":{\"name\":\"张三\",\"not_declared\":\"越界值\"}}",
                    token("hq"));
            assertEquals(403, resp.getStatusCodeValue(),
                    "未在白名单的占位符应 403·2004（契约 I8 规则② 不静默留空）: " + resp.getBody());
        }
    }
}