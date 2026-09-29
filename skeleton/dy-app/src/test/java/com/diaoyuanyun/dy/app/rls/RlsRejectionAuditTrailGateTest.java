package com.diaoyuanyun.dy.app.rls;

import com.diaoyuanyun.dy.app.audit.TenantRejectionAuditBridge;
import com.diaoyuanyun.dy.audit.chain.ChainVerification;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.audit.service.JdbcAuditLogService;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.tenancy.context.TenantContextFilter;
import com.diaoyuanyun.dy.tenancy.context.TenantRejectionListener;
import com.diaoyuanyun.dy.tenancy.jwt.JwtProperties;
import com.diaoyuanyun.dy.tenancy.jwt.JwtVerifier;
import com.diaoyuanyun.dy.tenancy.rls.RlsSessionAspect;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-3 验收④ · 跨租户查询「存储层直接拒绝 + 审计日志留痕」的<b>真实 PostgreSQL</b> 端到端断言。
 *
 * <h2>为什么必须打真库</h2>
 * 验收④ 的两个动作（存储层拒绝、审计留痕）都只在<b>真实数据库</b>上才成立：
 * <ul>
 *   <li>"存储层直接拒绝"—— RLS 是 PostgreSQL 的<b>执行器</b>行为，不是应用层 if。
 *       用 H2 / mock 测出来的结论与生产无关（本仓库已有 RlsTenantIsolationTest 系列
 *       用真库做这件事，本类不重复其职责）。</li>
 *   <li>"审计日志留痕"—— 写没写进去、链是否自洽，只有真库的 {@code audit_log} 能回答。</li>
 * </ul>
 * 故本类复用 {@link RlsGateSupport}（与 RLS 门禁同一套 provision + 构建级互斥锁），
 * 用<b>非超级用户</b>连同一个真库。
 *
 * <h2>本类与 RLS 隔离测试的分工（不是重复）</h2>
 * <table border="1">
 *   <caption>职责边界</caption>
 *   <tr><th>测试类</th><th>回答的问题</th></tr>
 *   <tr><td>{@code RlsTenantIsolationTest} / {@code RlsV5…}</td>
 *       <td>RLS 策略本身是否有效：跨租户读写是否零行 / 被拒</td></tr>
 *   <tr><td><b>本类</b></td>
 *       <td>端到端链路：一次<b>被拒绝的跨租户请求</b> → 是否在审计表留下<b>可用且链自洽</b>的证据</td></tr>
 * </table>
 *
 * <h2>断言锚定在哪（避免自证）</h2>
 * <ul>
 *   <li>拒绝的 HTTP 语义与错误码 <b>2003</b> 来自契约 §2.0（{@code TENANT_MISMATCH} → 403）；</li>
 *   <li>"必须有审计留痕"来自 S1-3 验收④ 原文与 ADR-09（append-only 审计）；</li>
 *   <li>"环链是否自洽"由 {@link AuditLogService#verifyChain()} 用<b>独立复算</b>回答
 *       （重算 hash 与存储值比对），不是读本类自己写的常量。</li>
 * </ul>
 *
 * <h2>三个反向锚点（本类的真正价值）</h2>
 * 若只断言"某张表多了一行"，把 {@code audit_log} 换成任何一张表都能让用例变绿 ——
 * 那是没有牙齿的门禁。故本类额外钉死三件事：
 * <ol>
 *   <li><b>租户归属必须是"无归属"</b>：{@code tenant_id = 全 0 UUID}。
 *       若实现改成"取 {@code TenantContext} 的租户"，用例必须红 ——
 *       那样等于<b>让攻击者决定审计归属</b>，且会把攻击流量伪装成受害租户自己的行为。</li>
 *   <li><b>actor 必须是 {@code anonymous}</b>：拒绝发生在身份确立之前。
 *       若实现填了 token 里的 staff_id（在验签失败/跨租户场景下<b>不可信</b>），必须红。</li>
 *   <li><b>payload 必须可解析且含错误码</b>，且<b>不得含 token 原文</b> ——
 *       审计表是全租户可读的（T-09 敞口），把凭据写进去等于放大泄漏面。</li>
 * </ol>
 *
 * <h2>方法名与覆盖门禁的关系</h2>
 * 本类<b>刻意不匹配</b> surefire 的 rls-isolation-gate {@code <include>}
 * （{@code RlsTenantIsolationTest} / {@code RlsCoverageGateTest} / {@code RlsBEntityIsolationTest}
 * / {@code RlsScaleItemBankIsolationTest} / {@code RlsV5EntityIsolationTest}），
 * 故运行在 dy-app 的<b>默认</b> execution 下。
 */
class RlsRejectionAuditTrailGateTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** token 里声称的租户（真正要访问的资源属于 TENANT_B）。 */
    private static final String TENANT_TOKEN = RlsGateSupport.TENANT_A;
    /** 请求头里冒充的租户 —— 与 token 不一致，正是被拒的原因。 */
    private static final String TENANT_HEADER = RlsGateSupport.TENANT_B;

    private static DataSource ds;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static JdbcAuditLogService auditService;
    private static TenantRejectionAuditBridge bridge;
    private static TenantContextFilter filter;
    private static JwtVerifier jwtVerifier;
    private static RlsGateSupport.BuildMutex buildMutex;

    /** 记录每个用例的探针路径，便于断言"恰好留了一条痕"。 */
    private static final AtomicInteger PROBE_SEQ = new AtomicInteger();

    @BeforeAll
    static void provision() throws Exception {
        if (RlsGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }

        // 与 RLS 门禁同一把构建级互斥锁：本类也要用同一个真库，必须串行。
        buildMutex = RlsGateSupport.acquireBuildMutex();
        RlsGateSupport.provisionRealDatabase();

        SingleConnectionDataSource single = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port() + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, true);
        // autoCommit=true 是【刻意】的：它正是 servlet 过滤器线程的真实形态 ——
        // 没有外层事务。本类因此能验证"审计桥自己开事务"这条链是活的（见反向验证②）。
        single.setAutoCommit(true);
        ds = single;

        jdbc = new JdbcTemplate(ds);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));

        // 自证：必须是非超级用户，否则 RLS 断言会"假通过"。
        String whoAmI = jdbc.queryForObject("select current_user", String.class);
        assertEquals(RlsGateSupport.APP_USER, whoAmI, "必须用非超级用户执行；实际=" + whoAmI);

        // 审计服务是本交付物的真实实现（非 mock）。表由 V1 迁移建好；ensureSchema 幂等兜底。
        auditService = new JdbcAuditLogService(ds);
        auditService.ensureSchema();

        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer(ISSUER);
        props.setRequireIssuer(true);
        props.setAlgorithm("HS256");
        jwtVerifier = new JwtVerifier(props);

        bridge = new TenantRejectionAuditBridge(
                fixedProvider(auditService), fixedProvider(new DataSourceTransactionManager(ds)));
        filter = new TenantContextFilter(jwtVerifier, fixedProvider(bridge));

        // 前置反证：审计表必须可读（否则"多了一行"的断言建立在一张不存在的表上）。
        assertNotNull(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class),
                "audit_log 必须可读");
    }

    @AfterAll
    static void releaseCleanup() {
        // 清理本类写入的审计行。audit_log 豁免 RLS，故非超级用户可删；
        // 但生产上 UPDATE/DELETE 被 REVOKE（append-only），测试库不设该限制。
        try {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'http_request' AND actor = 'anonymous'");
        } catch (RuntimeException ignore) {
            // 清理失败不应掩盖断言结论；真库门禁每次 provision 也会清数据。
        }
        if (buildMutex != null) {
            buildMutex.close();
            buildMutex = null;
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 主链路：跨租户请求被拒 → 审计留痕 → 链自洽
    // ------------------------------------------------------------------

    @Test
    @DisplayName("跨租户请求被拒(2003) 且 审计表真的留下了这一条 —— 端到端")
    void cross_tenant_request_is_rejected_and_leaves_an_audit_trail() throws Exception {
        String path = "/api/v1/settlement/preview#probe-" + PROBE_SEQ.incrementAndGet();
        int before = auditRowCount();

        MockHttpServletResponse resp = driveFilter(path, token(TENANT_TOKEN, "hq", "all"), TENANT_HEADER);

        // ---- ① 拒绝：HTTP 403 + 契约错误码 2003
        assertEquals(403, resp.getStatus(),
                "X-Tenant-Id 与 token 不一致必须 403；实际=" + resp.getStatus() + " body=" + resp.getContentAsString());
        assertTrue(resp.getContentAsString().contains("\"code\":2003"),
                "响应信封必须携带 2003 TENANT_MISMATCH（契约 §2.0）；实际=" + resp.getContentAsString());

        // ---- ② 留痕：审计表恰好多了一行，且能按路径定位到本次请求
        assertEquals(before + 1, auditRowCount(),
                "一次被拒的跨租户请求必须在 audit_log 留下【恰好一条】记录");

        List<AuditLog> hits = jdbc.query(
                "SELECT id, tenant_id, actor, action, target_type, target_id, payload, prev_hash, hash, created_at "
                        + "FROM audit_log WHERE target_id = ?",
                (rs, i) -> new AuditLog(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("payload"),
                        rs.getString("prev_hash"), rs.getString("hash")),
                path);
        assertEquals(1, hits.size(), "必须能按请求路径精确定位到本次拒绝的审计条目");
        AuditLog row = hits.get(0);

        // ---- ③ 三条反向锚点（换任何一种"看似合理"的实现都会让它们变红）
        assertEquals(TenantRejectionAuditBridge.UNATTRIBUTED_TENANT, row.tenantId(),
                "审计归属必须是『无归属』全 0 UUID —— 读 TenantContext 会把攻击者声称的租户写成归属");
        assertEquals("anonymous", row.actor(),
                "拒绝发生在身份确立之前，actor 必须是 anonymous —— 用 token 里的 staff_id 属不可信来源");
        assertEquals("TENANT_CROSS_ACCESS_REJECTED", row.action(),
                "动作名必须可读地指出『跨租户访问被拒』，而不是裸错误码");
        assertTrue(row.payload() != null && row.payload().contains("2003"),
                "payload 必须含错误码以便复核；实际=" + row.payload());
        assertTrue(!row.payload().contains("Bearer") && !row.payload().contains(SECRET),
                "审计 payload【不得】含 token 或密钥原文 —— 审计表是全租户可读的(T-09 敞口)");

        // ---- ④ 证据链条：包含本条在内的整条链必须自洽（独立复算，非读常量）
        ChainVerification v = auditService.verifyChain();
        assertTrue(v.valid(),
                "留痕后整条审计链必须仍然自洽（否则新增的这条破坏了链）；actual=" + v);
    }

    @Test
    @DisplayName("换算法复验: 第二次跨租户拒绝也留痕, 且链仍自洽（不是只对第一条成立）")
    void a_second_rejection_also_lands_and_the_chain_stays_consistent() throws Exception {
        int before = auditRowCount();

        String p1 = "/api/v1/demo#probe-" + PROBE_SEQ.incrementAndGet();
        String p2 = "/api/v1/demo#probe-" + PROBE_SEQ.incrementAndGet();
        assertEquals(403, driveFilter(p1, token(TENANT_TOKEN, "hq", "all"), TENANT_HEADER).getStatus());
        assertEquals(403, driveFilter(p2, token(TENANT_TOKEN, "hq", "all"), TENANT_HEADER).getStatus());

        assertEquals(before + 2, auditRowCount(),
                "两次拒绝必须留两条痕（不是只对首次成立）");
        assertTrue(auditService.verifyChain().valid(),
                "连续追加后链必须仍自洽 —— 若哈希链的 prev 指针写错, 这里会断");
    }

    @Test
    @DisplayName("反向: 验签失败的请求(401/1002)也必须留痕 —— 拒绝不止跨租户一种")
    void signature_failure_also_leaves_an_audit_trail() throws Exception {
        String path = "/api/v1/demo#probe-" + PROBE_SEQ.incrementAndGet();
        int before = auditRowCount();

        MockHttpServletResponse resp = driveFilter(path, token(TENANT_TOKEN, "hq", "all") + "TAMPERED", null);

        assertEquals(401, resp.getStatus(), "验签失败必须 401（绝不降级为匿名放行）");
        assertTrue(resp.getContentAsString().contains("\"code\":1002"), "必须是 1002 UNAUTHENTICATED");

        assertEquals(before + 1, auditRowCount(), "验签失败同样是一次值得留痕的拒绝");
        String action = jdbc.queryForObject(
                "SELECT action FROM audit_log WHERE target_id = ?", String.class, path);
        assertEquals("AUTH_REJECTED", action, "验签失败的动作名应与跨租户拒绝区分开");
    }

    // ------------------------------------------------------------------
    // 反向验证①: 存储层必须直接拒绝（审计存在不能替代 RLS 拒绝）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反向验证①: 跨租户的【存储层】直查依旧零行 —— 留痕不等于隔离生效")
    void storage_layer_still_rejects_cross_tenant_reads() {
        // 这条断言的存在理由：验收④ 同时要求"存储层直接拒绝"与"审计留痕"。
        // 若只测留痕，一个"只记日志、根本不隔离"的实现也能全绿。
        // 故此处独立验证存储层：在租户 A 的上下文里，读不到租户 B 的种子客户。
        TenantContext.set(TENANT_TOKEN, "staff-x", "hq", "all");
        try {
            Integer leak = txTemplate.execute(s -> {
                new RlsSessionAspect(fixedProvider((DataSource) ds))
                        .applyTenantSession();
                return jdbc.queryForObject(
                        "SELECT count(*) FROM customer WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_HEADER);
            });
            assertEquals(0, leak,
                    "租户A 上下文下读到租户B 的客户 " + leak + " 行 —— 存储层隔离失效（审计留痕无法替代它）");
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 反向验证②: 缺陷可被断言——"漏开事务"必须能被抓住
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反向验证②: 审计写入【必须在事务中】—— 证明『漏开事务』这条缺陷有牙齿")
    void audit_append_outside_transaction_must_fail_loudly() {
        AuditLog entry = new AuditLog(
                java.util.UUID.randomUUID().toString(),
                TenantRejectionAuditBridge.UNATTRIBUTED_TENANT, "anonymous", "PROBE",
                "http_request", "/probe/no-tx", Instant.now(), "{}", null, null);

        // 直连 autoCommit=true：模拟"忘了开事务"的实现形态。
        // JdbcAuditLogService 必须显式拒绝，而不是静默写出一条会自己断的链。
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> auditService.append(entry),
                "审计链写入在无事务时【必须报错】—— 若这里不抛, 说明 pg_advisory_xact_lock "
                        + "的互斥前提被悄悄放弃了");
        assertTrue(ex.getMessage().contains("事务"),
                "异常信息必须点明『必须在事务中』这一根因；实际=" + ex.getMessage());

        // 反向确认：本类的真实路径（审计桥）同样连 autoCommit=true，但【不会】触发它 ——
        // 因为桥自己开了 REQUIRES_NEW 事务。这才是本类要保护的性质。
        bridge.onRejected(ErrorCode.TENANT_MISMATCH.getCode(), "probe", "/probe/with-tx");
        Integer written = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_id = ?", Integer.class, "/probe/with-tx");
        assertEquals(1, written,
                "审计桥必须在无外层事务的过滤器线程里【仍然写成功】—— 靠它自己的 REQUIRES_NEW 事务");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * 驱动真实的 {@link TenantContextFilter}（含真实 JWT 验签 + 审计桥）。
     *
     * <p>用 {@code MockHttpServletRequest} 而非完整 servlet 容器：本类要断言的链路
     * 全在过滤器内部（拒绝判定 + 留痕），不需要 HTTP 层；
     * 而报告"真实 HTTP 状态码"的职责已由 {@code TierAuthorizationE2ETest} 承担。
     * 两类的分工是刻意的：一个测<b>真容器下的 HTTP 语义</b>，一个测<b>真库下的证据落盘</b>。
     */
    private MockHttpServletResponse driveFilter(String path, String jwt, String headerTenant) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.addHeader("Authorization", "Bearer " + jwt);
        if (headerTenant != null) {
            req.addHeader("X-Tenant-Id", headerTenant);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            // 未被拒绝时才会走到这里；本类所有用例都应被拒绝，故走到这里即失败信号。
            // 用 599 这个非标准码把"漏放行"与真实的 4xx 区分开。
            resp.setStatus(599);
        };
        filter.doFilter(req, resp, chain);
        return resp;
    }

    private int auditRowCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
        return n == null ? -1 : n;
    }

    /** 真签名 JWT（HS256），与 application.yml 的 dev 密钥一致。 */
    private static String token(String tenant, String role, String scope) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + tenant + "\""
                + ",\"staff_id\":\"S-" + role + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"scope\":\"" + scope + "\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** ObjectProvider 非函数式接口，不能用 lambda；用这个通用小实现代替。 */
    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new FixedProvider<>(value);
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}