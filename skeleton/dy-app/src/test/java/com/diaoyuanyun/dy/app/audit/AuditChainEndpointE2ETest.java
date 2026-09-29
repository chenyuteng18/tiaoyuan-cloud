package com.diaoyuanyun.dy.app.audit;

import com.diaoyuanyun.dy.audit.chain.ChainVerification;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.JdbcAuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-1 证人套件 · 审计日志哈希链校验端点（{@code GET /audit/log-chain}）真请求回归。
 *
 * <h1>🛑 本套件是【只读】的 —— 这条纪律是本套件首版用真实事故换来的</h1>
 *
 * <p>首版把"改写一行 / 删一行"的注入直接做在<b>应用库</b>的 {@code audit_log} 上，
 * 配以"全表快照 → 测完还原"的清理。实测后果：
 * <ol>
 *   <li>{@code audit_log} 是<b>全局 append-only 单链</b>（刻意豁免 RLS / T-09），
 *       并行跑的其他套件（RLS 拒绝审计、加密门禁等）会往链上<b>持续追加</b>行；</li>
 *   <li>于是"快照 → 注入 → 还原"之间必然夹进了别人的新行，
 *       还原时"删掉快照外的本租户行"就会<b>删到链的中间</b>；</li>
 *   <li>删中间行会让链<b>永久断链</b> —— 后续所有用例（含其他套件的）都开始读到
 *       {@code PREV_HASH_MISMATCH}，整套真库门禁连锁变红。
 *       <b>我自己就成了那个攻击者。</b>（实测：dev 库被断在两处，须手工清理链尾才恢复）</li>
 * </ol>
 *
 * <p><b>结论：对一张跨模块共享、append-only、链式校验的全局表，"快照 + 还原来做破坏性注入"
 * 在原理上就是错的 —— 它不是"可能竞态"，而是"必然污染"。</b>
 * 破坏性注入只允许发生在<b>本套件独占的库</b>里。
 *
 * <p>故职责划分：
 * <ul>
 *   <li><b>本套件（dy-app，应用库）</b>：只做<b>读</b>——可调性矩阵、信封与能力边界、
 *       以及"端点输出与 {@code verifyChain()} 逐字一致"的<b>穿透保真</b>断言。
 *       唯一的写操作是 {@code append}（追加），因为追加<b>不破坏链</b>，符合该表的语义。</li>
 *   <li><b>破坏性注入（改写 / 删中间行 / <u>删尾部</u> / 级联重算）</b>：
 *       全部落在 {@code dy-audit} 的 {@code AuditChainGateTest} ——
 *       它跑在<b>独占库</b> {@code dy_audit_chain_test} 上，并以 psql 扮演"有 DDL 权限的攻击者"。
 *       其中"<b>删尾部不被发现</b>"这条<b>本轮新发现</b>的盲区已补进该门禁。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("C-1 · 审计日志哈希链校验端点（GET /audit/log-chain）真请求回归（只读）")
class AuditChainEndpointE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本套件专用租户 —— 只用于<b>追加</b>自己的审计行（不删除链上任何行）。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000c1c1";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000c1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JdbcAuditLogService auditService;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource springDataSource;

    @BeforeEach
    void initOnce() {
        if (dataSource == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            auditService = new JdbcAuditLogService(dataSource);
            // 🛑 本套件不注册任何 @AfterAll 清理：审计表是 append-only，
            //    "测完删掉自己写的行"本身就是一次对链的破坏。追加进来的行就让它留着 ——
            //    这符合该表的语义（审计不可删），也让本套件不留下任何需要逆操作的痕迹。
            appendAudit("C1-端点套件-追加标记");
        }
    }

    private static void appendAudit(String action) {
        inTx(() -> auditService.append(new AuditLog(
                UUID.randomUUID().toString(), TENANT, S_STAFF, action, "tenant", TENANT,
                Instant.now(), "{\"suite\":\"C1\"}", null, null)));
    }

    private static <T> T inTx(Supplier<T> body) {
        return tx.execute(status -> body.get());
    }

    // ==================================================================
    // 一、可调性矩阵（唯一可调角色 = hq）
    // ==================================================================

    @Nested
    @DisplayName("一 · 可调性矩阵")
    class Callability {

        @Test
        @DisplayName("hq 调用 → 200，且 shape 与 verifyChain() 逐字一致（穿透保真）")
        void hq_reaches_the_endpoint_with_exact_passthrough_fidelity() {
            // 🛑 直接调服务层拿到权威结论，再与 HTTP 输出逐项比对。
            //    这是"控制器只是穿透、没有自己发明字段/改名"的机械证据 ——
            //    比人工核对 JSON 键名可靠，且不依赖任何破坏性注入。
            ChainVerification direct = inTx(() -> auditService.verifyChain());

            ResponseEntity<String> resp = get(token("hq"));
            assertEquals(200, resp.getStatusCodeValue(), "hq 应可调: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");

            assertEquals(direct.valid(), data.at("/valid").asBoolean(),
                    "HTTP 的 valid 必须与 verifyChain() 一致");
            assertEquals(direct.checked(), data.at("/checked").asInt(),
                    "HTTP 的 checked 必须与 verifyChain() 一致");
            if (direct.brokenAt() == null) {
                assertFalse(data.has("broken_at"),
                        "🛑 链有效时 broken_at 必须【不出现】—— 恒返 null 会让消费方"
                                + "分不清『链有效』与『未校验』。body: " + resp.getBody());
                assertTrue(data.at("/reason").isNull(),
                        "链有效时 reason 应为 JSON null（字段出现、值为空）: " + data.at("/reason"));
            } else {
                assertEquals(direct.brokenAt(), data.at("/broken_at").asText(),
                        "HTTP 的 broken_at 必须与 verifyChain() 一致");
                assertEquals(direct.reason(), data.at("/reason").asText(),
                        "HTTP 的 reason 必须与 verifyChain() 一致");
            }
            assertTrue(data.at("/checked").asInt() > 0,
                    "🛑 checked 必须 > 0：一个把表读成 0 行再报 valid=true 的校验器是灾难性假通过。"
                            + "本套件已追加过自己的行，故 checked 必然 > 0。body: " + resp.getBody());
        }

        @Test
        @DisplayName("🛑 area / manager / therapist / meridian → 403·2001（角色不可调）")
        void non_hq_roles_are_denied_by_the_permission_layer() {
            for (String role : List.of("area", "manager", "therapist", "meridian")) {
                ResponseEntity<String> resp = get(token(role));
                assertEquals(403, resp.getStatusCodeValue(),
                        "🛑 " + role + " 不得调用审计链校验 —— audit_log 是全局单链"
                                + "（跨租户串联），不属于任何单一租户，而契约 F3 的 x-row-scope"
                                + "『门店仅本店 / 区域仅辖区』预设了『对象属于某租户』。"
                                + "放进来的话会造出『本店 scope 的身份读全局对象』的语义裂缝。"
                                + "实际: " + resp.getStatusCode() + " " + resp.getBody());
                assertEquals(2001, parse(resp.getBody()).at("/code").asInt(),
                        role + " 应报 2001（档位缺失）: " + resp.getBody());
            }
        }

        @Test
        @DisplayName("client → 403（被拒绝）")
        void client_is_rejected() {
            ResponseEntity<String> resp = get(token("client"));
            assertEquals(403, resp.getStatusCodeValue(),
                    "客户不得调用审计链校验: " + resp.getBody());
        }

        @Test
        @DisplayName("无 token → 403·2001（🛑 既有基线：401 只对『带了非法 token』）")
        void anonymous_is_denied_by_the_permission_layer() {
            // 🛑 本用例首版断言 401，实测得 403 —— 这是【既有基线】，不是本端点的缺陷：
            //    ① TenantContextFilter 的 401 只用于「带了 Authorization 但格式错 / 验签失败」；
            //    ② 无 Authorization 时它【不拒绝】，让请求以「无上下文」继续，
            //       由 @StaffOnly / @RequirePermission 在权限层拦下（2001）。
            //    这与 RefundDomainGEndpointsE2ETest 的「伪签名 token 必须 401，绝不降级为匿名」
            //    恰好互补：坏 token ⇒ 401（不降级）；无 token ⇒ 权限层 403（本就没有身份可降级）。
            //    🛑 本用例钉住【现状口径】而非修正它 —— 401/403 的分界是 TenantContextFilter
            //    的既有裁定，改它会影响全部 45 个端点，不属于 C-1 的范围。
            HttpHeaders h = new HttpHeaders();
            ResponseEntity<String> resp = rest.exchange(url("/api/v1/audit/log-chain"),
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertEquals(403, resp.getStatusCodeValue(),
                    "无凭据落到权限层应 403·2001（若变 401，说明 TenantContextFilter 的口径被改了）: "
                            + resp.getBody());
            assertEquals(2001, parse(resp.getBody()).at("/code").asInt(),
                    "应为 2001 档位缺失: " + resp.getBody());
        }

        @Test
        @DisplayName("篡改过的 token → 401（绝不降级为匿名请求）")
        void tampered_token_is_unauthenticated() {
            String good = token("hq");
            String bad = good.substring(0, good.length() - 4) + "AAAA";
            ResponseEntity<String> resp = get(bad);
            assertEquals(401, resp.getStatusCodeValue(),
                    "🛑 验签失败必须 401，绝不降级为匿名 —— 否则攻击者只要带一个坏 token "
                            + "就能把自己降级成『匿名』再绕过某一层。实际: " + resp.getBody());
        }

        @Test
        @DisplayName("正确签发但角色未知 → 403（不得被当作 hq）")
        void unknown_role_is_denied_not_treated_as_hq() {
            ResponseEntity<String> resp = get(token("hq_typo"));
            assertEquals(403, resp.getStatusCodeValue(),
                    "未登记角色不得通过（fail-closed）: " + resp.getBody());
        }
    }

    // ==================================================================
    // 二、能力边界随响应下发
    // ==================================================================

    @Nested
    @DisplayName("二 · 能力边界随响应下发（不止留在注释里）")
    class Envelope {

        @Test
        @DisplayName("🛑 capability_envelope 四条齐全：能证明 / 级联重算 / 尾部截断 / 外锚定未实现")
        void capability_envelope_covers_all_four_items() {
            JsonNode data = parse(get(token("hq")).getBody()).at("/data");
            assertTrue(data.at("/capability_envelope").isArray(),
                    "capability_envelope 应为数组: " + data.at("/capability_envelope"));
            assertEquals(4, data.at("/capability_envelope").size(),
                    "摊平为四条：能证明 / 不能证明①级联重算 / 不能证明②尾部截断 / 外锚定未实现。"
                            + "实际: " + data.at("/capability_envelope"));

            boolean mentionsCascade = false;
            boolean mentionsTail = false;
            for (JsonNode n : data.at("/capability_envelope")) {
                if (n.asText().contains("逐行重算")) {
                    mentionsCascade = true;
                }
                if (n.asText().contains("链尾") && n.asText().contains("截断")) {
                    mentionsTail = true;
                }
            }
            assertTrue(mentionsCascade,
                    "🛑 必须明说『级联重算后本端点返回 valid=true』——"
                            + "这句话若不在响应里，读端点的人就无从知道它。实际: "
                            + data.at("/capability_envelope"));
            assertTrue(mentionsTail,
                    "🛑 必须明说『链尾被截断时本端点返回 valid=true』—— 这是本轮真请求实测"
                            + "挖出的盲区（既有门禁只覆盖『删中间行』），而擦掉最近的操作痕迹"
                            + "恰是攻击者的首选动作。实际: " + data.at("/capability_envelope"));
        }

        @Test
        @DisplayName("🛑 not_proven 必须给出可用的表述边界")
        void not_proven_states_the_usable_wording() {
            JsonNode data = parse(get(token("hq")).getBody()).at("/data");
            String note = data.at("/not_proven").asText();
            assertTrue(note.contains("不可篡改"),
                    "必须点名被禁的表述『审计链不可篡改』: " + note);
            assertTrue(note.contains("不可悄然篡改"),
                    "并给出可用表述『不可悄然篡改』: " + note);
            assertTrue(note.contains("尾部截断"),
                    "🛑 且须说明『悄然』的边界已因尾部截断而收窄 —— "
                            + "一个只写『不可悄然篡改』而不限定边界的版本会过度承诺: " + note);
        }

        @Test
        @DisplayName("🛑 scope_note 必须点明『全局单链』（否则被误读为本租户的链）")
        void scope_note_declares_the_global_chain() {
            JsonNode data = parse(get(token("hq")).getBody()).at("/data");
            String note = data.at("/scope_note").asText();
            assertTrue(note.contains("全局单链"),
                    "🛑 必须点明校验对象是跨租户的全局链。实际: " + note);
            assertTrue(note.contains("非本租户子集"),
                    "并须显式排除『只看本租户』这一误读。实际: " + note);
        }

        @Test
        @DisplayName("自描述端点：不在契约 45 端点内，且 client_denied_fields 读自注解")
        void contract_endpoint_is_self_describing() {
            ResponseEntity<String> resp = get(token("hq"), "/api/v1/audit/log-chain/contract");
            assertEquals(200, resp.getStatusCodeValue(), "自描述端点应可调: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertFalse(data.at("/in_frozen_contract").asBoolean(),
                    "🛑 必须自认不在契约内 —— 这是『造端点』与『内部自描述端点』的分界");
            assertTrue(data.at("/distinct_from").isArray(),
                    "必须与 F3 / F4 显式区分（同前缀、不同面）: " + resp.getBody());
            assertEquals(2, data.at("/distinct_from").size(),
                    "应逐条列出 F3 与 F4: " + data.at("/distinct_from"));
            boolean namesF3 = false;
            boolean namesF4 = false;
            for (JsonNode n : data.at("/distinct_from")) {
                if (n.asText().contains("F3")) {
                    namesF3 = true;
                }
                if (n.asText().contains("F4")) {
                    namesF4 = true;
                }
            }
            assertTrue(namesF3 && namesF4,
                    "distinct_from 必须逐条点名 F3 与 F4: " + data.at("/distinct_from"));
            assertTrue(data.at("/client_denied_fields").isArray()
                            && data.at("/client_denied_fields").size() > 0,
                    "🛑 client_denied_fields 必须【从注解读回】，不能是手抄清单"
                            + "（手抄的会在某次改注解时静默分叉）: " + resp.getBody());
            assertFalse(data.at("/writes_data").asBoolean(),
                    "🛑 本端点只读 —— 若它变成写操作，@Idempotent 才该出现");
        }

        @Test
        @DisplayName("🛑 自描述里的 callable_roles 必须恰为 [hq]（钉住『不开给区域/门店』）")
        void contract_endpoint_declares_hq_only() {
            JsonNode data = parse(get(token("hq"), "/api/v1/audit/log-chain/contract").getBody())
                    .at("/data");
            assertEquals(1, data.at("/callable_roles").size(),
                    "可调角色应恰一个: " + data.at("/callable_roles"));
            assertEquals("hq", data.at("/callable_roles").get(0).asText(),
                    "必须是 hq（总部）: " + data.at("/callable_roles"));
            assertNotNull(data.at("/pending_ruling").asText(),
                    "必须登记『区域督导自检』的待裁项（不代拍）");
        }
    }

    // ==================================================================
    // 三、不被 F3 / F4 误伤（同前缀、不同面）
    // ==================================================================

    @Nested
    @DisplayName("三 · 与契约 F3 / F4 的边界")
    class DistinctFromF3F4 {

        @Test
        @DisplayName("🛑 /audit/signals 与 /audit/coverage 仍是『未实现』（本端点不得顺手把它们做出来）")
        void f3_and_f4_remain_unimplemented() {
            // 🛑 这条用例的作用是防"顺手"：C-1 落在 /audit/* 前缀下，
            //    极易让人以为"既然都到 audit 了，顺便把 F3/F4 也补上"。
            //    但 F3/F4 依赖"稽核信号 / 覆盖率"的上游业务口径，其登记状态是
            //    「未立项 + 需契约 owner 裁定」，不是"没人写"。
            //    故本端点只做 log-chain，且本用例把 F3/F4 的未实现状态钉成可执行事实。
            for (String path : List.of("/api/v1/audit/signals", "/api/v1/audit/coverage")) {
                ResponseEntity<String> resp = get(token("hq"), path);
                assertTrue(resp.getStatusCodeValue() >= 400,
                        "🛑 " + path + " 应仍未实现（404 或 405）而【不是】200。"
                                + "若它变 200，说明有人顺手把 F3/F4 也做了 —— 那需要先有契约 owner "
                                + "对『稽核信号 / 覆盖率』上游口径的裁定，不属于 C-1。"
                                + "实际: " + resp.getStatusCodeValue() + " " + resp.getBody());
            }
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private ResponseEntity<String> get(String jwt) {
        return get(jwt, "/api/v1/audit/log-chain");
    }

    private ResponseEntity<String> get(String jwt, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), String.class);
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
}