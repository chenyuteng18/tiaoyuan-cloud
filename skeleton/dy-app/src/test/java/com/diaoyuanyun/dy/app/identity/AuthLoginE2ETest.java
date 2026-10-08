package com.diaoyuanyun.dy.app.identity;

import com.diaoyuanyun.dy.app.identity.controller.AuthLoginController;
import com.diaoyuanyun.dy.app.identity.service.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 {@code POST /api/v1/auth/login} 端到端门禁（真库 + 真 HTTP）——
 * 商用登录闭环的<b>可执行验收</b>：凭证入库 → 登录签发 → 拿 token 调 A2 的全链路。
 *
 * <h2>🛑 为什么必须有这条 E2E（不是单测可替代）</h2>
 * 登录的失效模式全部发生在<b>集成面</b>上，单测直调控制器一个都测不到：
 * <ul>
 *   <li>{@code TenantContextFilter} 对无 Authorization 请求的放行路径
 *       （若过滤器误拦匿名 POST，登录 401 —— 只在真 servlet 链上可见）；</li>
 *   <li>V23 迁移真建表 + {@code $v23_guard$} 守卫与 {@code RlsCoverageGateTest}
 *       豁免登记的一致性（表不存在/带策略都会在这里以 500/401 形态暴露）；</li>
 *   <li>签发的 token 与 dev 密钥、{@code JwtVerifier} 三方闭环
 *       （签发口径与校验口径漂移 = 拿到 token 下一跳 401）。</li>
 * </ul>
 *
 * <h2>判据清单（8 例）</h2>
 * <ol>
 *   <li>员工登录 200：data 四键不多不少，token 可调 A2 得 200；</li>
 *   <li>客户登录 200（mp 端）：token 调 A2 且 refund_visibility 键<b>不存在</b>；</li>
 *   <li>错口令 401；</li>
 *   <li>未知账号 401 —— 且与错口令的 message <b>逐字相同</b>（账号枚举防护）；</li>
 *   <li>停用账号 401（active 之外按不存在处理）；</li>
 *   <li>缺 credential 400；</li>
 *   <li>client_end 越界 400；</li>
 *   <li>同一账号连登两枚 token 的 jti 不同（吊销键前提，全链路形态）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A1 /auth/login · 真库登录闭环（V23 凭证 → 签发 → A2 闭环）")
class AuthLoginE2ETest {

    /** 专属租户（与其它 E2E 的 UUID 段不重叠）。 */
    private static final String TENANT = "e2e7a100-0000-0000-0000-00000000a001";
    private static final String STAFF_REF = "e2e7a100-0000-0000-0000-00000000a002";
    private static final String CUSTOMER_REF = "e2e7a100-0000-0000-0000-00000000a003";
    private static final String ACCOUNT_HQ = "e2e-a1-hq";
    private static final String ACCOUNT_CLIENT = "e2e-a1-client";
    private static final String ACCOUNT_DISABLED = "e2e-a1-disabled";
    private static final String PASSWORD = "A1-e2e-password-2026";

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordHasher hasher;

    @Autowired
    com.diaoyuanyun.dy.app.identity.service.CredentialProvisioningService credentialService;

    private static JdbcTemplate jdbc;

    @BeforeAll
    void provision() {
        jdbc = new JdbcTemplate(dataSource);
        // 租户宿主表（无 RLS）幂等插入
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-A1登录') "
                + "ON CONFLICT DO NOTHING", TENANT);
        ensureCredentials();
    }

    /**
     * 🛑 每个测试方法前重插凭证（自愈）：
     * 全量回归中 RLS 门禁套件会以互斥锁重建真库（迁移重放），本类的凭证行会被清掉 ——
     * 行消失后登录 401，与"凭证不匹配"不可区分（这正是账号枚举防护的副作用：
     * 连测试自己都拿不到区分信号）。故把"数据在"作为每例前置条件逐例保证，
     * 而不是依赖 @BeforeAll 的类级时序。
     */
    @BeforeEach
    void ensureCredentials() {
        // 走生产写入方（CredentialProvisioningService）—— 顺带验证该通路真实可用；
        // 已存在时返回 ALREADY_EXISTS，幂等无害。
        // hq：ROLE_DEFAULT scope=all，无需门店锚定（meridian 的 own_store 会要求 staff.store_id 锚定，
        // 而本测试不插组织树 —— 门店锚定链由 DomainA/OrganizationProvisioning 套件覆盖）
        credentialService.createCredential(TENANT, ACCOUNT_HQ, "hq",
                PASSWORD, "staff", STAFF_REF);
        credentialService.createCredential(TENANT, ACCOUNT_CLIENT, "client",
                PASSWORD, "customer", CUSTOMER_REF);
        // disabled 形态生产通路未提供状态变更方法（账号停用端点属契约外），测试直插
        insertRaw(ACCOUNT_DISABLED, "therapist", null, null, "disabled");
    }

    @AfterAll
    void cleanup() {
        // 只清本类插入的凭证行（tenant 保留幂等无害；凭证含哈希不留测试残渣）
        jdbc.update("DELETE FROM auth_credential WHERE account IN (?, ?, ?)",
                ACCOUNT_HQ, ACCOUNT_CLIENT, ACCOUNT_DISABLED);
    }

    private void insertRaw(String account, String role, String refId, String refKind, String status) {
        jdbc.update("DELETE FROM auth_credential WHERE account = ?", account);
        jdbc.update("INSERT INTO auth_credential "
                        + "(credential_id, account, credential_hash, tenant_id, role, ref_kind, ref_id, status) "
                        + "VALUES (?::uuid, ?, ?, ?::uuid, ?, ?, ?::uuid, ?)",
                UUID.randomUUID().toString(), account, hasher.hash(PASSWORD),
                TENANT, role, refKind, refId, status);
    }

    @Test
    @DisplayName("员工登录 200：四键 data，token 可调 A2 得 200（签发/校验闭环）")
    void staff_login_returns_token_usable_on_auth_me() {
        ResponseEntity<String> resp = post(ACCOUNT_HQ, PASSWORD, "web");
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "正确凭证必须 200。实际: " + resp.getStatusCode() + " body=" + resp.getBody());

        Map<String, Object> data = data(resp);
        assertTrue(data.containsKey("token"), "data 必须含 token");
        assertTrue(data.containsKey("expires_in"), "data 必须含 expires_in（契约 LoginData）");
        assertEquals("hq", data.get("role"), "role 必须回凭证行的 token 角色码");
        assertEquals("web", data.get("client_end"), "client_end 必须回显请求值");
        assertEquals(4, data.size(),
                "契约 LoginData 恰四键（token/expires_in/role/client_end），不得增删。实际键: " + data.keySet());

        // 拿签发的 token 调 A2 —— 证明签发口径与校验口径同源
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(data.get("token").toString());
        ResponseEntity<String> me = rest.exchange(
                "http://127.0.0.1:" + port + "/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, me.getStatusCode(),
                "登录签发的 token 调 A2 必须 200 —— 若这里 401，说明签发/校验口径漂移。body=" + me.getBody());
        assertTrue(AuthLoginController.invocationCount() > 0,
                "自证计数 > 0：请求确实打到了登录控制器（而非被某层拦截器短路）");
    }

    @Test
    @DisplayName("客户登录 200（mp 端）：token 调 A2 且 refund_visibility 键不存在")
    void client_login_on_mp_end() {
        ResponseEntity<String> resp = post(ACCOUNT_CLIENT, PASSWORD, "mp");
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "客户凭证必须 200（A1 的 x-callable-roles 含 client）。body=" + resp.getBody());
        Map<String, Object> data = data(resp);
        assertEquals("client", data.get("role"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(data.get("token").toString());
        ResponseEntity<String> me = rest.exchange(
                "http://127.0.0.1:" + port + "/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, me.getStatusCode(), "客户 token 调 A2 必须 200");
        assertFalse(me.getBody().contains("refund_visibility"),
                "客户的 A2 响应不得出现 refund_visibility 键（x-visible-to: [meridian, admin]，"
                        + "『不下发』与『下发 false』是两件事）");
    }

    @Test
    @DisplayName("错口令 401")
    void wrong_password_rejected() {
        ResponseEntity<String> resp = post(ACCOUNT_HQ, "wrong-password", "web");
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(1002, code(resp), "401 的业务码必须是 1002 UNAUTHENTICATED");
    }

    @Test
    @DisplayName("未知账号 401，且 message 与错口令逐字相同（账号枚举防护）")
    void unknown_account_same_message_as_wrong_password() {
        ResponseEntity<String> unknown = post("no-such-account-e2e", PASSWORD, "web");
        ResponseEntity<String> wrongPassword = post(ACCOUNT_HQ, "wrong-password", "web");

        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals(1002, code(unknown));
        assertEquals(message(wrongPassword), message(unknown),
                "『账号不存在』与『口令不匹配』必须同文案 —— 区分即向攻击者广播账号存在性");
    }

    @Test
    @DisplayName("停用账号 401（status != active 按不存在处理）")
    void disabled_account_rejected() {
        ResponseEntity<String> resp = post(ACCOUNT_DISABLED, PASSWORD, "app");
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "被停用凭证必须 401；且与未知账号同文案（同上，防枚举）");
        assertEquals(message(post("no-such-account-e2e", PASSWORD, "app")), message(resp));
    }

    @Test
    @DisplayName("缺 credential 400（1001）")
    void missing_credential_is_400() {
        ResponseEntity<String> resp = post(ACCOUNT_HQ, null, "web");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(1001, code(resp), "参数缺失的业务码必须是 1001 VALIDATION_FAILED");
    }

    @Test
    @DisplayName("client_end 越界 400（1001）")
    void invalid_client_end_is_400() {
        ResponseEntity<String> resp = post(ACCOUNT_HQ, PASSWORD, "desktop");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "client_end 必须是 mp/app/web（契约 enum），越界即 400");
        assertEquals(1001, code(resp));
    }

    @Test
    @DisplayName("同一账号连签两枚 token，jti 不同（吊销黑名单按 jti 定位的前提）")
    void two_logins_produce_distinct_tokens() {
        String t1 = data(post(ACCOUNT_HQ, PASSWORD, "web")).get("token").toString();
        String t2 = data(post(ACCOUNT_HQ, PASSWORD, "web")).get("token").toString();
        assertNotEquals(t1, t2, "同账号两次登录必须得到不同 token（jti 唯一）");
    }

    // ───────────────────────── 工具 ─────────────────────────

    private ResponseEntity<String> post(String account, String credential, String clientEnd) {
        StringBuilder json = new StringBuilder("{");
        if (account != null) {
            json.append("\"account\":\"").append(account).append("\",");
        }
        if (credential != null) {
            json.append("\"credential\":\"").append(credential).append("\",");
        }
        if (clientEnd != null) {
            json.append("\"client_end\":\"").append(clientEnd).append("\"");
        } else if (json.charAt(json.length() - 1) == ',') {
            json.deleteCharAt(json.length() - 1);
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.deleteCharAt(json.length() - 1);
        }
        json.append("}");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://127.0.0.1:" + port + "/api/v1/auth/login",
                HttpMethod.POST, new HttpEntity<>(json.toString(), headers), String.class);
    }

    /** 取信封的 data 字段 —— 契约信封四字段 {code,message,data,trace_id}，业务数据在 data 里。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<String> resp) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            Map<String, Object> envelope = mapper.readValue(resp.getBody(), Map.class);
            Object inner = envelope.get("data");
            if (!(inner instanceof Map)) {
                throw new IllegalStateException("信封无 data 或 data 非对象: " + resp.getBody());
            }
            return (Map<String, Object>) inner;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON: " + resp.getBody(), e);
        }
    }

    private static int code(ResponseEntity<String> resp) {
        try {
            Map<String, Object> envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getBody(), Map.class);
            return ((Number) envelope.get("code")).intValue();
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON: " + resp.getBody(), e);
        }
    }

    private static String message(ResponseEntity<String> resp) {
        try {
            Map<String, Object> envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getBody(), Map.class);
            return String.valueOf(envelope.get("message"));
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON: " + resp.getBody(), e);
        }
    }
}
