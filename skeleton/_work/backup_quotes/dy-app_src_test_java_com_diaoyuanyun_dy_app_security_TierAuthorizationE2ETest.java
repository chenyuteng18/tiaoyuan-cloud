package com.diaoyuanyun.dy.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-3 验收② · 三级权限越权真请求回归（<b>含换账号复验</b>）。
 *
 * <h2>为什么必须"真请求"而不是直调 guard</h2>
 * 直接调用 {@code OrgScopeGuard.assertCovers(...)} 只能证明<b>判定逻辑</b>正确，
 * 证明不了"这个端点真的挂上了这条判定"。初版骨架就有过这种教训：
 * 239 个测试全绿而应用起不来——因为没人碰过真实的装配链路。
 * 故本类<b>启动真实 Spring 上下文 + 真实 servlet 容器</b>，用<b>真实签名的 JWT</b>
 * 发 HTTP，断言<b>真实 HTTP 状态码</b>。装配漏了拦截器、拦截器顺序不对、
 * 注解读错位置，都会在这里暴露，而不会在纯单测里。
 *
 * <h2>判据锚定外部真相源（不是自证）</h2>
 * <ul>
 *   <li>HTTP 状态码 <b>403</b> 来自契约 §2.0 错误码表（2001/2003 均 403）；</li>
 *   <li>所需层级 <b>HEADQUARTERS</b> 来自 PRD <b>M5</b>（“稽核看板总部可见、门店不可见”）；</li>
 *   <li>角色码 <b>{@code hq}/{@code area}/{@code manager}</b> 来自契约 §2.1 A1（冻结）。</li>
 * </ul>
 * 断言比对的是这些外部约定，不是本实现的返回值。
 *
 * <h2>换账号复验是怎么做的</h2>
 * 同一份请求体，依次用 {@code hq} / {@code area} / {@code manager} 三个账号各发一次。
 * <b>只有把三者的结果放在一起看，才能区分两种实现</b>：
 * "真的按层级判定"（hq 200 / area 403 / manager 403）
 * 与"凡是非总部一律拒绝"（在只有 hq 用例时两者<b>完全无法区分</b>）。
 * 这就是为什么要三个账号而不是一个。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TierAuthorizationE2ETest {

    /** 与 application.yml 的占位密钥一致（本地 dev 默认值）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ------------------------------------------------------------------ 端点层级

    @Test
    @DisplayName("业绩拆分端点: 总部(hq)放行 —— 200, 且真的返回了拆分结果")
    void headquarters_is_allowed_on_settlement_preview() {
        ResponseEntity<String> resp = post("/api/v1/settlement/preview",
                token("hq", "all"), settleBody("ST-A"));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "总部层应放行结算端点（PRD M5）；实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode data = data(resp);
        assertTrue(data.path("splitApplied").asBoolean(),
                "他店占比 5/15 ≈ 33.3% ≥ 30%, 应触发拆分；实际 body=" + resp.getBody());
        assertEquals(15, data.path("totalVisits").asInt(),
                "总次数应为 10 + 5 = 15；实际 body=" + resp.getBody());
    }

    @Test
    @DisplayName("业绩拆分端点: 区域(area)必须 403 —— 区域层不得看跨店业绩拆分")
    void region_is_denied_on_settlement_preview() {
        ResponseEntity<String> resp = post("/api/v1/settlement/preview",
                token("area", "region"), settleBody("ST-A"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "区域层不得访问总部专属的业绩拆分；实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        assertEquals(2001, code(resp), "层级不足应报 2001 VISIBILITY_DENIED（契约 §2.0）");
    }

    @Test
    @DisplayName("业绩拆分端点: 门店(manager)必须 403 —— PRD M5「门店/加盟商不可见」")
    void store_is_denied_on_settlement_preview() {
        ResponseEntity<String> resp = post("/api/v1/settlement/preview",
                token("manager", "own_store"), settleBody("ST-A"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "门店层不得访问总部专属的业绩拆分（PRD M5）；实际: " + resp.getStatusCode());
        assertEquals(2001, code(resp), "层级不足应报 2001");
    }

    @Test
    @DisplayName("跨店异常端点: 区域(area)放行 —— 证明判据按【层级】走, 而不是「非总部全拒」")
    void region_is_allowed_on_anomaly_endpoint() {
        ResponseEntity<String> resp = post("/api/v1/settlement/cross-store-anomaly",
                token("area", "region"), Map.of("distinctStoresIn30d", 2, "otherStoreRatio", 0.35));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "区域层应可看辖区跨店异常信号；实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode root = data(resp);
        assertTrue(root.path("anomaly").path("flagged").asBoolean(), "2 店应标记");
        assertEquals("area", root.path("role").asText(), "响应应回显请求者角色（换账号复验用）");
        assertEquals("region", root.path("rowScope").asText(), "响应应回显行级范围");
    }

    @Test
    @DisplayName("换账号复验: 同一请求体, 三个账号三种结果 —— 三者必须【互不相同地】成立")
    void same_request_three_accounts_produce_tier_specific_results() {
        String body = settleBody("ST-A");

        ResponseEntity<String> hq = post("/api/v1/settlement/preview", token("hq", "all"), body);
        ResponseEntity<String> area = post("/api/v1/settlement/preview", token("area", "region"), body);
        ResponseEntity<String> store = post("/api/v1/settlement/preview", token("manager", "own_store"), body);

        // 三条断言合起来才构成"按层级判定"的证据：
        // 只有 hq 的用例时，"层级判定"与"非总部全拒"无法区分。
        assertEquals(HttpStatus.OK, hq.getStatusCode(), "hq 应放行");
        assertEquals(HttpStatus.FORBIDDEN, area.getStatusCode(), "area 应被拒");
        assertEquals(HttpStatus.FORBIDDEN, store.getStatusCode(), "manager 应被拒");

        // 换账号复验的"真"还体现在：三者用的是不同的 staff_id，不是同一个 token 重放
        assertNotNull(hq.getBody());
        assertTrue(!hq.getBody().equals(area.getBody()), "三种账号的响应体不应相同（否则可能根本没换账号）");
    }

    // ------------------------------------------------------------------ 角色不可识别

    @Test
    @DisplayName("未登记角色必须 403 + 2003 —— fail-closed, 绝不回落成某个层级")
    void unregistered_role_is_rejected_as_tenant_mismatch() {
        ResponseEntity<String> resp = post("/api/v1/settlement/cross-store-anomaly",
                token("HQ_TYPO_ROLE", "all"), Map.of("distinctStoresIn30d", 2, "otherStoreRatio", 0.35));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "未登记角色必须被拒；实际: " + resp.getStatusCode());
        assertEquals(2003, code(resp),
                "角色不可识别属『身份上下文不可信』应报 2003（不是 2001 层级不足）—— "
                        + "两者排查方向完全不同，不得混用；实际 body=" + resp.getBody());
    }

    @Test
    @DisplayName("客户角色(client)不是组织层级, 访问管理端点必须 403")
    void customer_role_is_not_an_org_level_and_is_rejected() {
        ResponseEntity<String> resp = post("/api/v1/settlement/cross-store-anomaly",
                token("client", "own_store"), Map.of("distinctStoresIn30d", 2, "otherStoreRatio", 0.35));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "客户不是组织层级（契约 A1: 客户无 staff_id），必须被拒；实际: " + resp.getStatusCode());
    }

    @Test
    @DisplayName("无 token 访问受保护端点必须 403 —— 不得因『没有上下文』而放行")
    void anonymous_is_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/settlement/preview"),
                HttpMethod.POST, new HttpEntity<>(settleBody("ST-A"), headers), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "匿名访问受层级保护的端点必须被拒；实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        assertEquals(2003, code(resp), "无上下文时角色为 null，属『无法识别』→ 2003");
    }

    // ------------------------------------------------------------------ helpers

    /** 结算请求体: ST-A 结案店 10 次, ST-B 他店 5 次 → 他店占比 5/15 = 33.3% ≥ 30%。 */
    private String settleBody(String closingStoreId) {
        return "“"
                {
                  "closingStoreId": "%s",
                  "visitsByStore": { "ST-A": 10, "ST-B": 5 },
                  "eccUnits": 1,
                  "lossYuan": 900.00
                }
                "”".formatted(closingStoreId);
    }

    private ResponseEntity<String> post(String path, String jwt, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** 读取信封 code（契约 §2.0 四字段之一）。 */
    private int code(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody()).path("code").asInt();
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    private JsonNode data(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody()).path("data");
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    /** 真签名 JWT（HS256）。角色/范围进 token —— 契约 A1：租户与角色上下文只来自 token。 */
    private static String token(String role, String scope) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"S-" + role + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"scope\":\"" + scope + "\""
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