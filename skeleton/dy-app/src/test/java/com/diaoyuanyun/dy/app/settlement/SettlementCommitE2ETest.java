package com.diaoyuanyun.dy.app.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算落账与对账报表的端到端门禁（真库 + 真 HTTP）—— 商用开发第二批 E1 的
 * <b>可执行验收</b>：预演算出的结论 → 落账（幂等）→ 周期对账 → CSV 导出。
 *
 * <h2>🛑 为什么必须有这条 E2E</h2>
 * 落账的失效模式全在集成面：V24 迁移真建表 + FK（closing_store_id → store）
 * + RLS 策略与租户上下文的三方咬合；服务端重算把关（篡改结果必须被拒）；
 * 幂等键跨 HTTP 的 CREATED/ALREADY_EXISTS 语义；M5 的"总部专属"在
 * {@code RequireOrgLevel} 上的真 403。单测一个都替代不了。
 *
 * <h2>🛑 每个测试用独立输入（本轮实测教训）</h2>
 * JUnit 方法执行序<b>不等于</b>声明序。若多个测试共用同一份结算输入，
 * 先跑的那个会落账，后跑的全部变成 ALREADY_EXISTS —— 断言与实现都对，
 * 输入撞车而已。故每个测试分配互不重叠的 visits/金额组合
 * （request_hash 的敏感性由 {@link SettlementStatementHashTest} 钉住）。
 *
 * <h2>🛑 库内断言必须带租户上下文</h2>
 * 本类数据源是 RLS 用户（非超级用户）；FORCE RLS 下无上下文 count 恒为 0
 * （fail-closed）—— 库内断言一律走 {@link #inTenantTx}（短事务 SET LOCAL）。
 *
 * <h2>判据清单（7 例）</h2>
 * <ol>
 *   <li>总部落账 200：CREATED + statement_id，库里真有行（输入组 A：4/1 次）；</li>
 *   <li>同输入重复落账 → ALREADY_EXISTS 且 statement_id 相同（输入组 B：5/1 次）；</li>
 *   <li>周期对账清单含该单，窄记录无 payload（输入组 C：6/1 次）；</li>
 *   <li>CSV 导出：表头冻结 + 含该行 + text/csv（输入组 D：7/1 次）；</li>
 *   <li>篡改结算结果（多给一家店 1 分钱）→ 422（输入组 E：9/1 次）；</li>
 *   <li>未来月份 → 422（不得给未来记账）；</li>
 *   <li>客户 token 调落账 → 403，换总部 token 放行（M5 归因；输入组 F：14/1 次）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("E1 结算落账 · 真库闭环（V24 → commit → 幂等 → 对账 → CSV）")
class SettlementCommitE2ETest {

    /** 专属租户（与其它 E2E 的 UUID 段不重叠）。 */
    private static final String TENANT = "e2e7b200-0000-0000-0000-00000000b001";
    private static final String STAFF_REF = "e2e7b200-0000-0000-0000-00000000b002";
    private static final String CUSTOMER_REF = "e2e7b200-0000-0000-0000-00000000b003";
    private static final String REGION = "e2e7b200-0000-0000-0000-00000000b004";
    private static final String STORE = "e2e7b200-0000-0000-0000-00000000b005";
    private static final String STORE_OTHER = "e2e7b200-0000-0000-0000-00000000b006";
    private static final String ACCOUNT_HQ = "e2e-e1-hq";
    private static final String ACCOUNT_CLIENT = "e2e-e1-client";
    private static final String PASSWORD = "E1-e2e-password-2026";
    private static final String PERIOD =
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource dataSource;

    @Autowired
    com.diaoyuanyun.dy.app.identity.service.CredentialProvisioningService credentialService;

    @Autowired
    CrossStoreSettlement settlement;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeAll
    void provision() {
        jdbc = new JdbcTemplate(dataSource);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // tenant 是租户宿主表（无 RLS），直插幂等
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-E1结算') "
                + "ON CONFLICT DO NOTHING", TENANT);
        // 🛑 region/store 是 RLS 租户表：应用数据源是 RLS 用户（非超级用户），
        //    无上下文直插会被 WITH CHECK 拒（新行违反策略）——
        //    必须短事务 + SET LOCAL app.tenant_id（与生产仓储同一条纪律）。
        inTenantTx(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) VALUES (?::uuid, ?::uuid, 'E1区域') "
                    + "ON CONFLICT DO NOTHING", REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'E1结案店', '直营') ON CONFLICT DO NOTHING",
                    STORE, TENANT, REGION);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'E1他店', '直营') ON CONFLICT DO NOTHING",
                    STORE_OTHER, TENANT, REGION);
            return null;
        });
        ensureCredentials();
    }

    @BeforeEach
    void ensureCredentials() {
        // 🛑 每例自愈（与 AuthLoginE2ETest 同因：RLS 套件重建库会清凭证行）
        credentialService.createCredential(TENANT, ACCOUNT_HQ, "hq",
                PASSWORD, "staff", STAFF_REF);
        credentialService.createCredential(TENANT, ACCOUNT_CLIENT, "client",
                PASSWORD, "customer", CUSTOMER_REF);
    }

    @AfterAll
    void cleanup() {
        // settlement_statement / store / region 都是 RLS 表 —— 清理同样要在租户上下文里
        inTenantTx(() -> {
            jdbc.update("DELETE FROM settlement_statement WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM store WHERE store_id IN (?::uuid, ?::uuid)", STORE, STORE_OTHER);
            jdbc.update("DELETE FROM region WHERE region_id = ?::uuid", REGION);
            return null;
        });
        // auth_credential 豁免 RLS（V23），直删
        jdbc.update("DELETE FROM auth_credential WHERE account IN (?, ?)",
                ACCOUNT_HQ, ACCOUNT_CLIENT);
    }

    @Test
    @DisplayName("总部落账 200：CREATED + statement_id，库里真有行（输入组 A：4/1 次）")
    void commit_creates_statement() {
        String token = login(ACCOUNT_HQ);
        ResponseEntity<String> resp = postJson("/api/v1/settlement/commit",
                bodyFor(4, "10.00", "3.00"), token);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "落账必须 200。body=" + resp.getBody());
        Map<String, Object> data = data(resp);
        assertEquals("CREATED", data.get("status"), "首次落账必须 CREATED");
        assertNotNull(data.get("statement_id"), "必须回 statement_id");

        // 按 statement_id 定点计数（租户内其它测试组的行不参与本断言口径）
        Integer rows = countById(data.get("statement_id").toString());
        assertEquals(1, rows, "本单必须在库里恰有 1 行（输入组 A）");
    }

    @Test
    @DisplayName("同输入重复落账 → ALREADY_EXISTS 且 statement_id 相同（输入组 B：5/1 次）")
    void repeated_commit_is_idempotent() {
        String token = login(ACCOUNT_HQ);
        String first = data(postJson("/api/v1/settlement/commit",
                bodyFor(5, "12.00", "4.00"), token)).get("statement_id").toString();
        ResponseEntity<String> second = postJson("/api/v1/settlement/commit",
                bodyFor(5, "12.00", "4.00"), token);
        Map<String, Object> d2 = data(second);
        assertEquals("ALREADY_EXISTS", d2.get("status"),
                "同输入第二次必须 ALREADY_EXISTS。body=" + second.getBody());
        assertEquals(first, d2.get("statement_id").toString(),
                "幂等命中必须返回既有单据 ID —— 返回新 ID = 产出了第二份账");

        Integer rows = countById(first);
        assertEquals(1, rows, "输入组 B 的单必须在库里恰有 1 行（无第二份账）");
    }

    @Test
    @DisplayName("周期对账清单含该单（窄记录，不含 payload 键）（输入组 C：6/1 次）")
    void statements_list_returns_the_row() {
        String token = login(ACCOUNT_HQ);
        String id = data(postJson("/api/v1/settlement/commit",
                bodyFor(6, "14.00", "5.00"), token)).get("statement_id").toString();

        ResponseEntity<String> resp = getJson(
                "/api/v1/settlement/statements?period=" + PERIOD, token);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "清单必须 200。body=" + resp.getBody());
        assertTrue(resp.getBody().contains(id), "清单必须含刚落账的单");
        assertTrue(resp.getBody().contains("closing_store_id"),
                "清单行必须含结案店标识（对账锚点）");
        assertTrue(!resp.getBody().contains("payload"),
                "清单是窄记录面，不得携带 payload 快照（定点走 /statements/{id}/payload）");
    }

    @Test
    @DisplayName("CSV 导出：列头冻结 + 含该行 + text/csv（输入组 D：7/1 次）")
    void csv_export_contains_row() {
        String token = login(ACCOUNT_HQ);
        postJson("/api/v1/settlement/commit", bodyFor(7, "16.00", "6.00"), token);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange(
                "http://127.0.0.1:" + port + "/api/v1/settlement/statements.csv?period=" + PERIOD,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getHeaders().getContentType().toString().startsWith("text/csv"),
                "必须 text/csv。实际: " + resp.getHeaders().getContentType());
        assertTrue(resp.getBody().startsWith("statement_id,period,closing_store_id"),
                "CSV 列头冻结（对账方按列序机器解析），实际: " + resp.getBody().split("\r\n")[0]);
        assertTrue(resp.getBody().contains(PERIOD), "CSV 必须含本周期行");
    }

    @Test
    @DisplayName("篡改结算结果（多给一家店 1 分钱）→ 422，账本只收可复算的账（输入组 E：9/1 次）")
    void tampered_result_is_rejected() {
        String token = login(ACCOUNT_HQ);
        // 诚实输入，但 result 里给每家店的 eccShare 多加 0.01 —— 服务端重算必须识破
        Map<String, Integer> visits = visitsFor(9);
        CrossStoreSettlement.SettlementResult honest = resultFor(visits, "20.00", "8.00");
        var tampered = new CrossStoreSettlement.SettlementResult(
                honest.splitApplied(), honest.otherStoreVisitRatio(), honest.totalVisits(),
                honest.allocations().stream()
                        .map(a -> new CrossStoreSettlement.StoreAllocation(
                                a.storeId(), a.visitCount(),
                                a.eccShare().add(new BigDecimal("0.01")), a.lossShare(),
                                a.splitApplied()))
                        .toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", PERIOD);
        body.put("closing_store_id", STORE);
        body.put("visits_by_store", visits);
        body.put("ecc_units", new BigDecimal("20.00"));
        body.put("loss_yuan", new BigDecimal("8.00"));
        body.put("result", tampered);

        ResponseEntity<String> resp = postJson("/api/v1/settlement/commit", body, token);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(),
                "篡改结果必须 422。body=" + resp.getBody());
        assertEquals(5001, code(resp), "业务码必须是 5001 BUSINESS_RULE_VIOLATED");
    }

    @Test
    @DisplayName("未来月份 → 422（不得给未来记账）")
    void future_period_rejected() {
        String token = login(ACCOUNT_HQ);
        ResponseEntity<String> resp = postJson("/api/v1/settlement/commit",
                bodyFor("2099-12", 11, "30.00", "9.00"), token);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(),
                "未来月份必须 422。body=" + resp.getBody());
        assertEquals(5001, code(resp));
    }

    @Test
    @DisplayName("客户 token 调落账 → 403，换总部 token 放行（M5 归因；输入组 F：14/1 次）")
    void client_token_is_403() {
        String clientToken = login(ACCOUNT_CLIENT);
        ResponseEntity<String> resp = postJson("/api/v1/settlement/commit",
                bodyFor(13, "40.00", "11.00"), clientToken);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "客户凭证必须 403。body=" + resp.getBody());

        // 同一请求换总部 token 必须放行 —— 证明 403 来自层级判定，不是请求本身坏
        // （🛑 独立输入组 14：防止与其它测试的幂等键撞车 —— 403 那次没落账，但总部这次会）
        String hqToken = login(ACCOUNT_HQ);
        ResponseEntity<String> hqResp = postJson("/api/v1/settlement/commit",
                bodyFor(14, "42.00", "12.00"), hqToken);
        assertEquals(HttpStatus.OK, hqResp.getStatusCode(),
                "同一形态的请求换总部 token 必须 200。body=" + hqResp.getBody());
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 第 v1 组 visits（结案店 v1 次 / 他店 1 次）。 */
    private Map<String, Integer> visitsFor(int v1) {
        Map<String, Integer> visits = new LinkedHashMap<>();
        visits.put(STORE, v1);
        visits.put(STORE_OTHER, 1);
        return visits;
    }

    /** 用被测 Bean 对同一份输入真算"诚实结果"（服务端重算比对用的同源结论）。 */
    private CrossStoreSettlement.SettlementResult resultFor(Map<String, Integer> visits,
                                                            String ecc, String loss) {
        return settlement.allocate(
                CrossStoreSettlement.contributions(visits, STORE),
                new BigDecimal(ecc), new BigDecimal(loss));
    }

    /**
     * 落账请求体（当月）：输入与结果<b>严格同源</b> —— 服务端会用请求里的输入重算，
     * 与 result 比对。构造体不分开传，就从根上杜绝"输入与结果错位"的假失败。
     */
    private Map<String, Object> bodyFor(int closingVisits, String ecc, String loss) {
        return bodyFor(PERIOD, closingVisits, ecc, loss);
    }

    private Map<String, Object> bodyFor(String period, int closingVisits, String ecc, String loss) {
        Map<String, Integer> visits = visitsFor(closingVisits);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", period);
        body.put("closing_store_id", STORE);
        body.put("visits_by_store", visits);
        body.put("ecc_units", new BigDecimal(ecc));
        body.put("loss_yuan", new BigDecimal(loss));
        body.put("result", resultFor(visits, ecc, loss));
        return body;
    }

    /** 按 statement_id 定点计数（必须带租户上下文 —— FORCE RLS 下无上下文恒 0）。 */
    private Integer countById(String statementId) {
        return inTenantTx(() -> jdbc.queryForObject(
                "SELECT count(*) FROM settlement_statement WHERE statement_id = ?::uuid",
                Integer.class, statementId));
    }

    /** 短事务 + SET LOCAL（本类 RLS 表写入/清理/库内断言的统一通道）。 */
    private <T> T inTenantTx(Supplier<T> body) {
        return txTemplate.execute(s -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    private String login(String account) {
        Map<String, Object> body = Map.of("account", account, "credential", PASSWORD, "client_end", "web");
        ResponseEntity<String> resp = postJson("/api/v1/auth/login", body, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "前置: 登录必须成功。body=" + resp.getBody());
        return data(resp).get("token").toString();
    }

    private ResponseEntity<String> postJson(String path, Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        try {
            return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    private ResponseEntity<String> getJson(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<String> resp) {
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

    private int code(ResponseEntity<String> resp) {
        try {
            return ((Number) mapper.readValue(resp.getBody(), Map.class).get("code")).intValue();
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON: " + resp.getBody(), e);
        }
    }
}
