package com.diaoyuanyun.dy.app.assessment;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3-1 证人套件 · 契约域 C（C1~C3）的<b>真请求回归</b>。
 *
 * <h2>为什么域 C 的三行必须"真请求"</h2>
 * 域 C 的领域层/单测覆盖"规则写对了"（migratable 四要素推导、维度分/总分自洽校验、
 * 题组拉取的枚举 fail-closed）。但下面这些事<b>单测看不见</b>：
 * <pre>
 *  ① 路由：C1/C2/C3 的真实路径是否为契约 paths（基路径 /api/v1 靠 servers 承载）
 *  ② 权限：C2 的可调用角色集是否为 [therapist, meridian, admin]（客户/匿名被权限层拒），
 *     C1/C3 含 client 是否真的放行 —— 🛑 本次最关键：新立的 assessment:write 码
 *     是否有主（否则 C2 对全员 403，与域 B 第 29 条同型）
 *  ③ 装配：TenantContextFilter 是否把 token 写进 TenantContext（C2 的 created_by 从它解算）
 *  ④ 出站：C3 的 migratable 是否真的按客户【不下发】（键缺席而非 false）
 *  ⑤ 装备：@Service 是否被扫描（域 C 是 @Service 自注册）
 *  ⑥ 域 C 特有：C2 的 422 归因（分龄锁定缺失 → 5001），C3 的 404 归因（查无）
 * </pre>
 *
 * <h2>🛑 判据设计</h2>
 * <pre>
 *  客户 token 调 C1 / C3        → 200（契约含 client）
 *  客户 token 调 C2             → 403(2001)（权限层：客户不持 assessment:write）
 *  匿名调 C1/C2/C3              → 403(2003 租户缺失)（域 C 全部行要求租户上下文）
 *  调理师/经络师/管理员调 C2    → 200（assessment:write 已发码）
 *  C2 缺分龄锁定                → 422(5001 BUSINESS_RULE_VIOLATED)
 *  C2 scale_id/总分非法         → 参数格式层 400 或 业务层 422（逐项分辨）
 *  C3 查无                      → 404(3001)
 *  C3 客户响应体                → migratable 键【缺席】（不是 false）
 * </pre>
 *
 * <h2>零污染与可重复</h2>
 * 底数据用 {@code e2e00000-} 前缀自建（独占租户），{@code @AfterAll} 按 FK 序清理。
 * 清理也必须在租户上下文内（FORCE RLS 下 DELETE 会挡住无上下文删除）。
 *
 * <h2>🛑 C2 依赖的种子数据</h2>
 * 需要 {@code region → store → staff}（staff 的 FK 链）+ {@code scale}（C2 的 FK scale）。
 * 而 {@code scale} 表 V5 建列是 {@code scale_id / tenant_id / scale_type / scale_version /
 * name / dimension_set_json / status}，本套件种子一条 {@code primary} 量表即可。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("S3-1 · 契约域 C（C1~C3）真请求回归")
class AssessmentDomainCEndpointsE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000c3c3";

    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000c4";
    private static final String S_SCALE = "e2e00000-0000-0000-0000-0000000000c5";
    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000c6";

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
            // 🛑 序：先删结论侧（verdict 的 FK 指向 cycle_assessment），再删依据侧
            jdbc.update("DELETE FROM verdict WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM cycle_assessment WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM baseline_assessment WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM scale WHERE tenant_id = ?::uuid AND scale_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'", TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-S3-1-C域') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        // 🛑 除 tenant 外全部是 FORCE RLS 表 → 必须在租户上下文内插入，
        //    否则"新行违背行级安全策略"（与 DomainB 的教训同源）
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E区') ON CONFLICT DO NOTHING",
                    "e2e00000-0000-0000-0000-0000000000c1", TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'E2E店', '直营') ON CONFLICT DO NOTHING",
                    "e2e00000-0000-0000-0000-0000000000c2", TENANT,
                    "e2e00000-0000-0000-0000-0000000000c1");
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, "e2e00000-0000-0000-0000-0000000000c2");
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'E2E客户', 'CREATED') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            jdbc.update("INSERT INTO scale (scale_id, tenant_id, scale_type, scale_version, name, dimension_set_json, status) "
                            + "VALUES (?::uuid, ?::uuid, 'primary', 'v1', 'E2E量表', '{}', 'active') ON CONFLICT DO NOTHING",
                    S_SCALE, TENANT);
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
    // C2 请求体构造
    // ==================================================================

    /** 合法 C2 请求体（7 维分 = 各 8 → 总分 56）。 */
    private static String validBaselineBody() {
        return "{"
                + "\"scale_id\":\"" + S_SCALE + "\","
                + "\"item_group_id\":\"" + UUID.randomUUID() + "\","
                + "\"age_group_locked\":\"男16-32\","
                + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                + "\"total_score\":56,"
                + "\"measure_operator\":\"" + S_STAFF + "\""
                + "}";
    }

    // ==================================================================
    // HTTP + JWT 工具
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

    // ==================================================================
    // 嵌套测试
    // ==================================================================

    @Nested
    @DisplayName("C1 题库拉取")
    class QuizList {

        @Test
        @DisplayName("客户调 C1 拉取题组 → 200（含 client，不贴码）")
        void client_can_pull_quiz() {
            ResponseEntity<String> resp = get("/api/v1/scale-item-banks?age_group=男16-32", token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户应能拉题组: " + resp.getBody());
            JsonNode body = parse(resp.getBody());
            assertEquals("男16-32", body.at("/data/age_group").asText());
            assertTrue(body.at("/data/items").isArray(), "items 应为数组");
        }

        @Test
        @DisplayName("非法 age_group → 400（参数格式层，不是查无）")
        void invalid_age_group_is_400() {
            ResponseEntity<String> resp = get("/api/v1/scale-item-banks?age_group=不存在组", token("client"));
            assertEquals(400, resp.getStatusCodeValue(), "非法 age_group 应 400: " + resp.getBody());
        }

        @Test
        @DisplayName("匿名调 C1 → 403（缺租户上下文）")
        void anonymous_is_403() {
            ResponseEntity<String> resp = get("/api/v1/scale-item-banks?age_group=男16-32", null);
            assertEquals(403, resp.getStatusCodeValue(), "匿名应 403: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("C2 基线评估提交")
    class BaselineSubmit {

        @Test
        @DisplayName("经络师调 C2 → 200（assessment:write 已发码）")
        void meridian_can_submit() {
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    validBaselineBody(), token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "经络师应能提交基线评估: " + resp.getBody());
            JsonNode body = parse(resp.getBody());
            assertTrue(body.at("/data/migratable").asBoolean(), "四要素齐备（题组ID+版本+测量人+时间戳）应 migratable=true");
            // 🛑 dimension_scores 应逐项回显 7 维（8,8,8,8,8,8,8），总分 56
            assertEquals(7, body.at("/data/dimension_scores").size(),
                    "C2 出参 dimension_scores 应为 7 维: " + resp.getBody());
            assertEquals(8, body.at("/data/dimension_scores/0").asInt(), "第 1 维应为 8");
        }

        @Test
        @DisplayName("客户调 C2 → 403（不持 assessment:write）")
        void client_is_403() {
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    validBaselineBody(), token("client"));
            assertEquals(403, resp.getStatusCodeValue(), "客户应 403: " + resp.getBody());
        }

        @Test
        @DisplayName("缺 item_group_id → 200 但 migratable=false（四要素缺一不可）")
        void missing_item_group_is_migratable_false() {
            String body = "{"
                    + "\"scale_id\":\"" + S_SCALE + "\","
                    + "\"age_group_locked\":\"男16-32\","
                    + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                    + "\"total_score\":56,"
                    + "\"measure_operator\":\"" + S_STAFF + "\""
                    + "}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    body, token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "缺题组ID仍可建档（只是不可迁移）: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertFalse(data.at("/migratable").asBoolean(),
                    "缺题组ID应 migratable=false（四要素齐备才 true）: " + resp.getBody());
        }

        @Test
        @DisplayName("缺分龄锁定 → 422（契约点名 5001 提交阻断）")
        void missing_age_group_is_422() {
            String body = "{"
                    + "\"scale_id\":\"" + S_SCALE + "\","
                    + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                    + "\"total_score\":56,"
                    + "\"measure_operator\":\"" + S_STAFF + "\""
                    + "}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    body, token("meridian"));
            assertEquals(422, resp.getStatusCodeValue(), "缺分龄锁定应 422: " + resp.getBody());
        }

        @Test
        @DisplayName("维度分与总分不自洽 → 422")
        void inconsistent_scores_is_422() {
            String body = "{"
                    + "\"scale_id\":\"" + S_SCALE + "\","
                    + "\"age_group_locked\":\"男16-32\","
                    + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                    + "\"total_score\":55,"
                    + "\"measure_operator\":\"" + S_STAFF + "\""
                    + "}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    body, token("meridian"));
            assertEquals(422, resp.getStatusCodeValue(), "总分≠Σ维度分应 422: " + resp.getBody());
        }

        @Test
        @DisplayName("scale_id 不存在 → 422（引用完整性，业务规则）")
        void unknown_scale_is_422() {
            String body = "{"
                    + "\"scale_id\":\"" + UUID.randomUUID() + "\","
                    + "\"age_group_locked\":\"男16-32\","
                    + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                    + "\"total_score\":56,"
                    + "\"measure_operator\":\"" + S_STAFF + "\""
                    + "}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    body, token("meridian"));
            assertEquals(422, resp.getStatusCodeValue(), "未知 scale_id 应 422: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("C3 评估详情")
    class AssessmentDetail {

        private String submitAndGetId() {
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/assessments/baseline",
                    validBaselineBody(), token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "预置评估应成功: " + resp.getBody());
            return parse(resp.getBody()).at("/data/assessment_id").asText();
        }

        @Test
        @DisplayName("客户调 C3 → 200 且 migratable 键【缺席】")
        void client_sees_no_migratable() {
            String assessmentId = submitAndGetId();
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/assessments/" + assessmentId,
                    token("client"));
            assertEquals(200, resp.getStatusCodeValue(), "客户应能看自己评估: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertFalse(data.has("migratable"),
                    "客户响应体不得出现 migratable（x-visible-to 不含 client）: " + data);
        }

        @Test
        @DisplayName("经络师调 C3 → 200 且 migratable 可见")
        void staff_sees_migratable() {
            String assessmentId = submitAndGetId();
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/assessments/" + assessmentId,
                    token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "经络师应能看评估: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertNotNull(data.get("migratable"), "staff 响应体应有 migratable");
        }

        @Test
        @DisplayName("查无评估 → 404")
        void missing_assessment_is_404() {
            ResponseEntity<String> resp = get("/api/v1/customers/" + S_CUSTOMER + "/assessments/"
                            + UUID.randomUUID(), token("meridian"));
            assertEquals(404, resp.getStatusCodeValue(), "查无应 404: " + resp.getBody());
        }
    }

    @Nested
    @DisplayName("C4 周期评估提交（两阶段模型阶段一）")
    class CycleAssessmentSubmit {

        /** 合法 C4 请求体（依从四维 + 模块分）。 */
        private String validCycleBody(String cycleId) {
            return "{"
                    + "\"cycle_id\":\"" + cycleId + "\","
                    + "\"sequence_no\":1,"
                    + "\"adherence\":{"
                    + "  \"expected_days\":14,"
                    + "  \"dimensions\":{\"A1\":{\"applicable\":true,\"value\":0.9},"
                    + "                  \"A3\":{\"applicable\":true,\"value\":0.8},"
                    + "                  \"A4\":{\"applicable\":true,\"value\":0.85}}},"
                    + "\"module_scores\":{\"M1\":8,\"M2\":8,\"M3\":8,\"M4\":8,\"M5\":8},"
                    + "\"band_trend_note\":\"E2E 手环备注\""
                    + "}";
        }

        @Test
        @DisplayName("经络师调 C4 → 200 且 branch=null / phase=awaiting_verdict（只落依据）")
        void meridian_can_submit_cycle() {
            String cycleId = UUID.randomUUID().toString();
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    validCycleBody(cycleId), token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "经络师应能提交周期评估: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertEquals(cycleId, data.at("/cycle_id").asText(), "应回显 cycle_id");
            assertEquals("awaiting_verdict", data.at("/phase").asText(),
                    "C4 只落依据 ⇒ 阶段一: " + data);
            assertTrue(data.at("/branch").isNull(),
                    "🛑 branch 必须为 null（判定尚未发生）—— 回填『人工复核』是错的: " + data);
            assertNotNull(data.at("/threshold_version").asText(),
                    "流程一号：threshold_version 由服务端填（非空）");
        }

        @Test
        @DisplayName("🛑 库层落行：cycle_assessment.verdict IS NULL + 依据四列已落满")
        void cycle_row_lands_with_null_verdict() {
            String cycleId = UUID.randomUUID().toString();
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    validCycleBody(cycleId), token("meridian"));
            assertEquals(200, resp.getStatusCodeValue(), "提交应成功: " + resp.getBody());

            inTenant(() -> {
                assertNull(jdbc.queryForObject(
                        "SELECT verdict FROM cycle_assessment WHERE cycle_id = ?::uuid",
                        String.class, cycleId),
                        "🛑 V8 起 C4 落的行 verdict 必须为 NULL（待判定）—— "
                                + "这是两阶段模型在库层的锚点");
                // 依据四列必须落满（PRD §C.1.9 硬约束②③）
                for (String col : new String[]{"metric_snapshot", "as_dimensions_json",
                        "module_scores", "threshold_version"}) {
                    Object v = jdbc.queryForObject(
                            "SELECT " + col + " FROM cycle_assessment WHERE cycle_id = ?::uuid",
                            Object.class, cycleId);
                    assertNotNull(v, "🛑 依据列 " + col + " 不得为空（C4 阶段就算得出）");
                }
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM verdict WHERE cycle_id = ?::uuid", Integer.class, cycleId),
                        "🛑 C4 不得落 verdict 行 —— 结论由 F1 产出");
                return null;
            });
        }

        @Test
        @DisplayName("🛑 同一 cycle_id 重复提交 → 409（body code=4001，不静默改写）")
        void duplicate_cycle_is_version_conflict() {
            String cycleId = UUID.randomUUID().toString();
            ResponseEntity<String> first = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    validCycleBody(cycleId), token("meridian"));
            assertEquals(200, first.getStatusCodeValue(), "首次提交应成功: " + first.getBody());

            ResponseEntity<String> second = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    validCycleBody(cycleId), token("meridian"));
            // 🛑 HTTP 状态取自 ErrorCode.getHttpStatus()（4001 VERSION_CONFLICT → 409），
            //    业务码 4001 在响应体里。断言两者，避免"只看 HTTP 码"漏掉业务码回归。
            assertEquals(409, second.getStatusCodeValue(),
                    "🛑 重复提交必须拒（409 = VERSION_CONFLICT）—— cycle_id 是 PK，"
                            + "重复会让『这一轮的依据以哪次为准』失去唯一答案: " + second.getBody());
            assertEquals(4001, parse(second.getBody()).at("/code").asInt(),
                    "业务码必须是 4001（VERSION_CONFLICT），不得退化为 1001 或 5001: " + second.getBody());
        }

        @Test
        @DisplayName("客户调 C4 → 403（不持 assessment:write）")
        void client_is_403() {
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    validCycleBody(UUID.randomUUID().toString()), token("client"));
            assertEquals(403, resp.getStatusCodeValue(), "客户应 403: " + resp.getBody());
        }

        @Test
        @DisplayName("缺 cycle_id → 400（参数格式层）")
        void missing_cycle_id_is_400() {
            String body = "{"
                    + "\"sequence_no\":1,"
                    + "\"module_scores\":{\"M1\":8}"
                    + "}";
            ResponseEntity<String> resp = post("/api/v1/customers/" + S_CUSTOMER + "/cycle-assessments",
                    body, token("meridian"));
            assertEquals(400, resp.getStatusCodeValue(), "缺 cycle_id 应 400: " + resp.getBody());
        }
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }
}