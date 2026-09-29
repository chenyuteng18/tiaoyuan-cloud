package com.diaoyuanyun.dy.app.band;

import com.diaoyuanyun.dy.app.band.domain.BandBindingOutcome;
import com.diaoyuanyun.dy.app.band.domain.BandBindingRecord;
import com.diaoyuanyun.dy.app.band.service.BandBindingService;
import com.diaoyuanyun.dy.app.band.service.BandBindingService.BandBindingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-10 · 手环绑定通路的真库真表端到端回归</b> ——
 * 它见证的是 {@code band} 表从「零生产写入方」到「有写入方」这件事<b>真的把功能救活了</b>。
 *
 * <h2>为什么必须有一个真库门禁，而不是靠 {@code ProvisioningBoundaryGateTest} 的静态扫描</h2>
 * 那道静态门禁只能证明「生产代码里出现了 {@code INSERT INTO band}」。
 * 它与「E1/E2 两个客户端端点真的能写进去了」之间隔着四道缝，静态扫描一道都看不见：
 * <ol>
 *   <li>{@code bind_band()} 的 {@code ON CONFLICT} 目标写错（部分唯一索引必须逐字带 {@code WHERE}）
 *       ⇒ <b>运行期</b>第一次调用才炸，而不是迁移期；</li>
 *   <li>绑定之后，那支 {@code band_id} 是否真的能被 {@code band_sync_log} /
 *       {@code band_telemetry} 的复合外键 {@code (tenant_id, device_id) → band(tenant_id, band_id)}
 *       接受；</li>
 *   <li>绑定与审计是否真的在<b>同一个事务</b>里 —— 这条不变量一旦失效<b>没有任何静态检查会红</b>；</li>
 *   <li>解绑到底是<b>状态迁移</b>还是删行 —— 后者会让「应戴天」分母凭空缩水，
 *       而所有计数断言仍然通过。</li>
 * </ol>
 *
 * <h2>🛑 判据设计一：先证明「缺口真的存在过」，再证明「它被修好了」</h2>
 * 本类最重要的结构是判据 ① 的<b>同一支带子、前后两次</b>对照：
 * <pre>
 *   绑定【之前】：写 band_sync_log / band_telemetry 于该 device_id
 *                 ⇒ 必须失败，且 SQLSTATE 必须是 23503（外键），不是 42501（RLS）
 *   绑定【之后】：同样两条写语句
 *                 ⇒ 必须成功
 * </pre>
 * 两条判据缺一不可，且它们的<b>结果必须不同</b> ——
 * 这同时就是本门禁的<b>判别力自证</b>（见判据 ① 的 ①g）：
 * 若"23503"这条断言在绑定之后也成立，那它就不是在测缺口，而是一条恒真断言（装饰而非门禁）。
 *
 * <h2>🛑 判据设计二：绑定必须由【被测代码】执行，不得由测试夹具代建</h2>
 * 这正是 B-10 这个缺口的成因本身：全仓 9 处 {@code INSERT INTO band} <b>全部</b>在
 * {@code src/test} 的夹具里，于是"带子从哪来"这个问题在测试里永远有一个答案、在生产里没有。
 * ⇒ 本类的每一支手环都由 {@link BandBindingService#bind} 建，
 * 测试自己<b>绝不</b>写 {@code INSERT INTO band}（唯一的例外是判据 ④ 的<b>负样本</b>：
 * 它要证明的是"绕过函数在库层会被外键拒"，那一条必须以裸 SQL 形态出现才成立）。
 *
 * <h2>🛑 判据设计三：每个"必须成功"旁边都有一条"必须失败"，且失败要给出理由</h2>
 * 沿用 {@code OrganizationProvisioningE2ETest} 的比较器纪律（本仓 S6b 已付过一次代价）：
 * 断言必须穿透 cause 链取 <b>SQLSTATE</b>，而不是断"抛了某个异常"。
 * 🛑 因为 <b>23503（外键）与 42501（RLS）都会让"写失败"这件事成立</b>：
 * 若只断言"失败"，一条「我忘了设租户上下文」的实现也能让判据通过 ——
 * 那是"用错误的原因得到了正确的绿灯"，比漏报更危险。
 * 本仓在 {@code OrganizationProvisioningE2ETest} 里已实测过这一课
 * （RLS 的证据不在顶层消息里，顶层只有 {@code BadSqlGrammarException}）。
 *
 * <h2>🛑 判据设计四：审计失败必须让绑定回滚（一条易静默失效的不变量）</h2>
 * {@code BandBindingService} 的类注释逐字写明：绑定与审计同事务，
 * 且特意点出「若有人把 {@code bind()} 的传播行为改成 {@code REQUIRES_NEW}，
 * 这条不变量会<b>静默失效</b>：绑定已落库而审计没有，且没有任何测试会红」。
 * ⇒ 判据 ② 用一个<b>真实的失败模式</b>（超长 {@code operator} 撞
 * {@code audit_log.actor VARCHAR(128)}）去撞这条不变量，断言 {@code band} 行确实被回滚。
 * <p>🛑 为什么用"超长 actor"而不是 mock 掉 {@code AuditLogService}：
 * 替换 bean 会改变装配形态，于是被验的就不再是生产的那套装配。
 * 本仓的既定处置是「用真实存在的失败模式去撞不变量」（同 {@code ReverseVerificationGate} 的纪律）。
 *
 * <h2>零污染 —— 以及一处刻意例外（审计行不清理）</h2>
 * 数据全部用 {@code b1000000-} 前缀自建（与 {@code e2e00000-} / {@code b0700000-} /
 * {@code 17000000-} 均不重叠），{@code @AfterAll} 在租户上下文内按
 * 「子 → 父」外键序清理（{@code band_sync_log} / {@code band_telemetry} → {@code band} →
 * {@code customer} → 密钥材料 → {@code tenant}）。
 * <p>🛑 清理也必须<b>在租户上下文内</b>：这几张表是 {@code FORCE ROW LEVEL SECURITY}，
 * 无上下文时 {@code DELETE} 会<b>静默删 0 行</b>（{@code USING} 过滤掉一切），
 * 于是残留一路累积，直到某次运行的前置断言报"它已存在" —— 而那时没人知道是清理失效。
 * 本仓 S2-5 已付过一次代价，故 {@code @AfterAll} 里带<b>清理自证</b>。
 * <p>🛑 审计条目刻意不删（同 {@code OrganizationProvisioningE2ETest} 的裁定）：
 * {@code audit_log} 是<b>全局单链</b>，删中间行会为它的后继留下永久的 {@code prev_hash} 断口。
 * 改为断言"证据确实留下了"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-10 · 手环绑定通路真库回归（缺口对照 / 四态 / 同事务审计 / 跨租户 / 台账历史）")
class BandBindingGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code b1000000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "b1000000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ④ / ⑦ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "b1000000-0000-0000-0000-0000000000f1";

    // —— 客户（每个判据一组，互不干扰）——
    private static final String CUST_GATE = "b1000000-0000-0000-0000-0000000000a1";
    private static final String CUST_STATE = "b1000000-0000-0000-0000-0000000000a2";
    private static final String CUST_TX = "b1000000-0000-0000-0000-0000000000a3";
    private static final String CUST_CROSS = "b1000000-0000-0000-0000-0000000000a5";

    // —— 手环 ——
    /** 判据 ① / ⑦：绑定前后对照用的那一支。 */
    private static final String BAND_GATE = "b1000000-0000-0000-0000-0000000000b1";
    /** 判据 ③ / ⑤ / ⑥：四态流转的起始带。 */
    private static final String BAND_STATE = "b1000000-0000-0000-0000-0000000000b2";
    /** 判据 ③ / ⑤：换机时作为"另一支"的那一支。 */
    private static final String BAND_STATE2 = "b1000000-0000-0000-0000-0000000000b3";
    /** 判据 ③：解绑后重绑的第二支（证部分唯一索引不阻挡历史行）。 */
    private static final String BAND_STATE3 = "b1000000-0000-0000-0000-0000000000b4";
    /** 判据 ②：审计失败注入用。 */
    private static final String BAND_TX = "b1000000-0000-0000-0000-0000000000b5";
    /** 判据 ④：跨租户引用用。 */
    private static final String BAND_CROSS = "b1000000-0000-0000-0000-0000000000b6";
    /** 一个<b>从不</b>被绑定的 id —— 只作为"不存在"的负样本出现。 */
    private static final String BAND_GHOST = "b1000000-0000-0000-0000-0000000000bf";

    /** 审计动作名与目标类型 —— 与 {@code BandBindingService} 的常量逐字一致（那里是包私有）。 */
    private static final String ACTION_BOUND = "BAND_BOUND";
    private static final String ACTION_UNBOUND = "BAND_UNBOUND";
    private static final String TARGET_TYPE = "band";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-b10-witness";

    /**
     * 🛑 判据 ② 的注入载荷：长度超 {@code audit_log.actor VARCHAR(128)} 的操作者标识。
     *
     * <p>为什么它是一条<b>真实</b>的失败模式而不是"人为抛异常"：
     * {@code BandBindingService.requireOperator()} 只挡 null 与空白
     * （那是"有没有说谁做的"，属业务校验），<b>不</b>挡长度
     * （那是列宽问题，应由库层报 22001，而不是让业务层去猜上限）。
     * ⇒ 一次真实的、可复现的审计写入失败，恰好落在"绑定已写、审计未写"的那条缝上。
     */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /** 真正调用过绑定/解绑的次数（供 {@code @AfterAll} 断言"审计证据确实留下了"，而不删它）。 */
    private static int serviceCalls;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 application.yml 的占位密钥一致（本地 dev 默认值）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    // ==================================================================
    // 二、装配
    // ==================================================================

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

    @Autowired
    BandBindingService service;

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

    /**
     * 种两个租户 + 各自的客户。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code band} 行 —— 那是本类全部判据的载体，
     * 必须由 {@link BandBindingService} 建（见类注释「判据设计二」）。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B10租户-绑定通路', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B10对照租户', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);
        inTenant(TENANT, () -> {
            for (String cid : List.of(CUST_GATE, CUST_STATE, CUST_TX)) {
                jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                        + "VALUES (?::uuid, ?::uuid, ?, 'active') ON CONFLICT DO NOTHING",
                        cid, TENANT, "B10客户-" + cid.substring(cid.length() - 2));
            }
            return null;
        });
        inTenant(TENANT_OTHER, () -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                    + "VALUES (?::uuid, ?::uuid, 'B10跨租户客户', 'active') ON CONFLICT DO NOTHING",
                    CUST_CROSS, TENANT_OTHER);
            return null;
        });
    }

    /**
     * 在指定租户上下文里执行（短事务 + {@code SET LOCAL}）—— 与各 {@code *Ledger.inTenant} 同款形态。
     *
     * <p>🛑 {@code SET LOCAL} 只在事务内有效，故"设上下文 + 执行 SQL"必须收敛成一个事务；
     * 无上下文时读是<b>静默 0 行</b>、写是<b>抛 42501</b>（这两个方向很容易记反）。
     */
    private static <T> T inTenant(String tenantId, Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /** 在租户上下文里执行一条<b>必须成功</b>的写语句。 */
    private static void writeInTenant(String tenantId, String sql, Object... args) {
        inTenant(tenantId, () -> {
            jdbc.update(sql, args);
            return null;
        });
    }

    /**
     * 在租户上下文里执行一条<b>必须失败</b>的写语句，返回最深层的 SQLSTATE。
     *
     * <p>🛑 返回 SQLSTATE 而不是布尔：见类注释「判据设计三」——
     * 23503（外键）与 42501（RLS）都让"失败"成立，只断言"失败"会让
     * "我忘了设上下文"这种实现也通过。
     *
     * @return 失败时的 SQLSTATE；<b>若没失败则返回 {@code null}</b> —— 调用方必须对此断言
     */
    private static String writeExpectingFailure(String tenantId, String sql, Object... args) {
        try {
            inTenant(tenantId, () -> {
                jdbc.update(sql, args);
                return null;
            });
            return null;
        } catch (RuntimeException ex) {
            return deepestSqlState(ex);
        }
    }

    /**
     * 清掉某个客户名下的<b>全部</b>手环及其上报数据。
     *
     * <p>🛑 为什么按客户清而不是按 band_id 清：{@code uq_band_active_customer} 是
     * 「1 客户 : 1 有效手环」，若上一次运行/上一个判据在该客户名下留下了另一支 active 带子，
     * 那么"本判据的绑定必须返回 CREATED"就会变成 CUSTOMER_ALREADY_HAS_ACTIVE_BAND ——
     * 而那时红的原因会被误读成"绑定功能坏了"。故统一按客户清，使前置可靠。
     */
    private static void purgeCustomerBands(String tenantId, String customerId) {
        inTenant(tenantId, () -> {
            jdbc.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid AND device_id IN "
                            + "(SELECT band_id FROM band WHERE tenant_id = ?::uuid AND customer_id = ?::uuid)",
                    tenantId, tenantId, customerId);
            jdbc.update("DELETE FROM band_daily_coverage WHERE tenant_id = ?::uuid AND device_id IN "
                            + "(SELECT band_id FROM band WHERE tenant_id = ?::uuid AND customer_id = ?::uuid)",
                    tenantId, tenantId, customerId);
            jdbc.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid AND device_id IN "
                            + "(SELECT band_id FROM band WHERE tenant_id = ?::uuid AND customer_id = ?::uuid)",
                    tenantId, tenantId, customerId);
            jdbc.update("DELETE FROM band WHERE tenant_id = ?::uuid AND customer_id = ?::uuid",
                    tenantId, customerId);
            return null;
        });
    }

    /** 该支手环在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int bandRows(String tenantId, String bandId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM band WHERE band_id = ?::uuid", Integer.class, bandId));
        return n == null ? 0 : n;
    }

    /** 该支手环的 {@code [status, unbind_reason, unbound_at]}；不存在则 {@code null}。 */
    private static String[] bandState(String tenantId, String bandId) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT status, coalesce(unbind_reason, ''), coalesce(unbound_at::text, '') "
                            + "FROM band WHERE band_id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                    bandId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 该客户名下 {@code active} 手环的行数。 */
    private static int activeRowsOf(String tenantId, String customerId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM band WHERE customer_id = ?::uuid AND status = 'active'",
                Integer.class, customerId));
        return n == null ? 0 : n;
    }

    private static BandBindingRecord bindRecord(String tenantId, String bandId, String customerId,
                                                 boolean rebind) {
        return new BandBindingRecord(tenantId, UUID.fromString(bandId), UUID.fromString(customerId),
                "GTL1", "model-b10", LocalDate.of(2026, 9, 1), rebind, null);
    }

    private BandBindingResult bind(String tenantId, String bandId, String customerId, boolean rebind) {
        serviceCalls++;
        return service.bind(bindRecord(tenantId, bandId, customerId, rebind), OPERATOR);
    }

    private BandBindingResult unbind(String bandId, String reason) {
        serviceCalls++;
        return service.unbind(TENANT, UUID.fromString(bandId), reason, null, OPERATOR);
    }

    // ==================================================================
    // 三、异常链工具（本仓既定形态：判据必须给出【理由】）
    // ==================================================================

    /**
     * 取异常链上<b>最深</b>那个 {@link java.sql.SQLException} 的 SQLSTATE。
     *
     * <p>🛑 用 SQLSTATE 而非消息文本作主判据：它是 PG 的错误码，
     * <b>与数据库语言环境无关</b>。本仓的 PG 是中文环境，RLS 的消息是
     * 「新行违背了表"band_sync_log"的行级安全策略」，任何写死英文
     * {@code row-level security} 的断言在这里都会假红。
     */
    private static String deepestSqlState(Throwable t) {
        String found = null;
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (c instanceof java.sql.SQLException se && se.getSQLState() != null) {
                found = se.getSQLState();
            }
            c = c.getCause();
            depth++;
        }
        return found;
    }

    /** 把异常链上每一层的类型与消息拼成一行（顶层 → 最深），供失败信息可读。 */
    private static String fullCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (depth > 0) {
                sb.append(" ← ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    // ==================================================================
    // 四、HTTP 侧（判据 ① 的后果断言 —— 走真实链路）
    // ==================================================================

    private ResponseEntity<String> post(String path, String body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
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

    /** 一条合法的 E1 上报体（{@code state=synced} 时 {@code last_success_date} 必填）。 */
    private static String e1Body(String deviceId, String customerId) {
        return "{\"device_id\":\"" + deviceId + "\",\"customer_id\":\"" + customerId + "\","
                + "\"batch_no\":\"" + UUID.randomUUID() + "\",\"trigger\":\"daily_report\","
                + "\"state\":\"synced\",\"synced_at\":\"2026-09-27T10:00:00Z\","
                + "\"last_success_date\":\"2026-09-27\"}";
    }

    /**
     * 一条合法的 E2 上报体。
     *
     * <p>🛑 刻意选 {@code metric=exercise}：它在
     * {@code TelemetrySensitivity} 的未登记表中 ⇒ <b>不触发字段级加密</b>。
     * 本类要验证的是"这一行能不能落库"，而不是加解密往返
     * （后者由 {@code BandTelemetryEncryptionTest} 用真密钥栈断言）。
     * 把两条关注点混在一个用例里，会让"加密链未装配"这种失败伪装成"绑定没生效"。
     */
    private static String e2Body(String deviceId, String customerId) {
        return "{\"device_id\":\"" + deviceId + "\",\"customer_id\":\"" + customerId + "\","
                + "\"metric\":\"exercise\",\"date\":\"2026-09-27\",\"value\":1}";
    }

    // ==================================================================
    // 判据 ①
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 缺口对照：未绑定的 device_id 写上报表必 23503（且 HTTP 500），绑定后同一写法必成功（HTTP 200）")
    void the_missing_writer_was_the_real_cause_and_binding_fixes_it() {
        purgeCustomerBands(TENANT, CUST_GATE);

        // ---- ①a 前置：绑定【之前】，这支带子在库里不存在 ----
        assertEquals(0, bandRows(TENANT, BAND_GATE),
                "前置失败：清理之后 BAND_GATE 仍在库里 —— 那么下面『绑定前必 23503』就无法与"
                        + "『它本来就在』区分，本例全部门禁意义丢失");

        // ---- ①b 根因断言：两条上报语句必须被【外键】(23503) 拒 ----
        String e1State = writeExpectingFailure(TENANT,
                "INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                UUID.randomUUID().toString(), TENANT, BAND_GATE);
        assertNotNull(e1State,
                "🛑 未绑定的 device_id 竟然能写进 band_sync_log —— "
                        + "那说明 device_id → band(band_id) 的外键不存在了，"
                        + "B-10 这个缺口的前提已经变了，本门禁必须重新设计");
        assertEquals("23503", e1State,
                "🛑 拒绝理由必须是 23503(foreign_key_violation)，实际=" + e1State
                        + "。若不是 23503，本条断言已退化为假阳性 —— "
                        + "尤其要排除 42501(RLS)：那说明真正的原因是『我没设租户上下文』，"
                        + "而不是『带子不存在』。这是本仓 OrganizationProvisioningE2ETest "
                        + "已付过一次代价的坑");

        String e2State = writeExpectingFailure(TENANT,
                "INSERT INTO band_telemetry (telemetry_id, tenant_id, customer_id, device_id,"
                        + " metric, date, synced_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid,"
                        + " 'exercise', CURRENT_DATE, now())",
                UUID.randomUUID().toString(), TENANT, CUST_GATE, BAND_GATE);
        assertEquals("23503", e2State,
                "🛑 band_telemetry 的 device_id 也必须被 23503 拒（它与 band_sync_log 是"
                        + "两个独立外键，只测其中一个会让另一个的失效漏网）。实际=" + e2State);

        // ---- ①c 后果断言（走真实 HTTP 链路）：客户端上报此时必须失败 ----
        ResponseEntity<String> e1Before = post("/api/v1/band/sync-batches",
                e1Body(BAND_GATE, CUST_GATE), token("client"));
        assertEquals(500, e1Before.getStatusCodeValue(),
                "🛑 E1 在『带子未绑定』时必须失败 —— 这正是缺口的对外表现："
                        + "契约声明 200/400/409/429，实际却是一个 500。实际="
                        + e1Before.getStatusCodeValue() + " body=" + e1Before.getBody());
        assertTrue(e1Before.getBody() != null
                        && e1Before.getBody().contains("DataIntegrityViolationException"),
                "500 的成因必须能看到外键违例的形态（GlobalExceptionHandler.handleOther 回显类名）。"
                        + "body=" + e1Before.getBody());

        // ---- ①d 修复：由【被测代码】建这支带子（测试绝不代建）----
        BandBindingResult created = bind(TENANT, BAND_GATE, CUST_GATE, false);
        assertEquals(BandBindingOutcome.CREATED, created.outcome(),
                "首次绑定必须是 CREATED（返回 ALREADY_BOUND / "
                        + "CUSTOMER_ALREADY_HAS_ACTIVE_BAND 说明前置未清干净）");
        List<String[]> auditRows = jdbc.query(
                "SELECT action, target_type, target_id FROM audit_log WHERE id = ?::uuid",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                created.auditId());
        assertEquals(1, auditRows.size(),
                "回执的 auditId 必须真的对应库中一条审计（实际条数=" + auditRows.size()
                        + "）—— 否则回执无法用于对账");
        assertEquals(BAND_GATE, auditRows.get(0)[2],
                "审计 target_id 必须是本次绑定的手环 id");

        // ---- ①e 绑定【之后】：同样的两条写语句必须成功 ----
        writeInTenant(TENANT, "INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                UUID.randomUUID().toString(), TENANT, BAND_GATE);

        writeInTenant(TENANT, "INSERT INTO band_telemetry (telemetry_id, tenant_id, customer_id, device_id,"
                        + " metric, date, synced_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid,"
                        + " 'exercise', CURRENT_DATE, now())",
                UUID.randomUUID().toString(), TENANT, CUST_GATE, BAND_GATE);

        assertEquals(1, (Integer) inTenant(TENANT, () -> jdbc.queryForObject(
                        "SELECT count(*) FROM band_sync_log WHERE device_id = ?::uuid",
                        Integer.class, BAND_GATE)),
                "🛑 绑定之后 band_sync_log 必须真的多出这一行 —— 上一步没抛错还不够："
                        + "若那条 INSERT 被静默忽略（例如掉进某个 DO NOTHING），"
                        + "『没抛错』这条断言仍会绿。这是本仓反复出现的一类假通过");

        // ---- ①f HTTP 层对照：同一个端点，绑定后必须回到契约声明的 200 ----
        ResponseEntity<String> e1After = post("/api/v1/band/sync-batches",
                e1Body(BAND_GATE, CUST_GATE), token("client"));
        assertEquals(200, e1After.getStatusCodeValue(),
                "🛑 E1 在带子绑定之后必须回到 200 —— 这是 B-10 那条『客户端上报入口是死的』"
                        + "被修好的最终证据。实际=" + e1After.getStatusCodeValue()
                        + " body=" + e1After.getBody());

        ResponseEntity<String> e2After = post("/api/v1/band/telemetry",
                e2Body(BAND_GATE, CUST_GATE), token("client"));
        assertEquals(200, e2After.getStatusCodeValue(),
                "🛑 E2 同理必须回到 200。实际=" + e2After.getStatusCodeValue()
                        + " body=" + e2After.getBody());
        assertTrue(parse(e2After.getBody()).at("/data/telemetry_id").isTextual(),
                "E2 响应体必须给出 telemetry_id（契约 E2）。body=" + e2After.getBody());

        // ---- ①g 🛑 判别力自证：把绑定【撤掉】之后，同一条写语句必须【重新】变红 ----
        //    🛑 这一步是本条判据的元门禁，也是"23503 这条断言对绑定状态真的有反应"的证明。
        //       没有它，一个在任何状态下都返回 23503 的实现同样能让 ①b 全绿 ——
        //       那时的 ①b 就不是在测缺口，而是一条恒真断言（装饰而非门禁）。
        //    🛑 它必须放在 ①c~①f 之后：前面的断言依赖带子【在位】。
        purgeCustomerBands(TENANT, CUST_GATE);
        String afterPurge = writeExpectingFailure(TENANT,
                "INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                UUID.randomUUID().toString(), TENANT, BAND_GATE);
        assertEquals("23503", afterPurge,
                "🛑 判别力自证失败：把 band 行删掉之后，同一条 INSERT 竟然没有回到 23503"
                        + "（实际=" + afterPurge + "）—— 那说明本门禁『绑定前后』的对照是假的，"
                        + "它对『带子在不在』这件事没有反应");
        assertEquals(e1State, afterPurge,
                "🛑 而且它必须与绑定【前】观察到的是同一个 SQLSTATE（绑定前=" + e1State
                        + " 撤销后=" + afterPurge + "）—— 两个不同的错因会让这条对照失去意义");
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 🛑 审计失败必须让绑定【回滚】—— 用真实的 actor 超长（22001）撞这条易静默失效的不变量")
    void a_failed_audit_write_rolls_the_binding_back() {
        purgeCustomerBands(TENANT, CUST_TX);
        assertEquals(0, bandRows(TENANT, BAND_TX), "前置失败：BAND_TX 应已被清理干净");

        // ---- ②a 注入：审计写入必然失败（operator 超 audit_log.actor VARCHAR(128)）----
        serviceCalls++;
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.bind(bindRecord(TENANT, BAND_TX, CUST_TX, false), TOO_LONG_OPERATOR),
                "🛑 审计写不进去时，绑定调用竟然没有抛错 —— "
                        + "那说明审计失败被静默吞掉了，而『台账的每一行都有来源』这条不变量已经失效");

        String sqlState = deepestSqlState(ex);
        assertEquals("22001", sqlState,
                "🛑 本次失败的成因必须是 22001(string_data_right_truncation，actor 超列宽)，实际="
                        + sqlState + "。若成因是别的，那本条用例证明的就不是"
                        + "『审计失败 ⇒ 绑定回滚』，而是其它某处失败导致绑定没发生 —— "
                        + "那是两个完全不同的结论。异常链=" + fullCauseMessages(ex));

        // ---- ②b 核心断言：绑定必须被回滚（不是"没抛错"，而是"库里没有"）----
        assertEquals(0, bandRows(TENANT, BAND_TX),
                "🛑🛑 审计失败之后 band 行【仍在】库里 —— "
                        + "『审计失败则绑定回滚』这条不变量已静默失效，"
                        + "而本仓没有任何其它检查会发现它。最常见的成因是有人把 "
                        + "BandBindingLedger.bind() 的 TransactionTemplate 改成了 "
                        + "PROPAGATION_REQUIRES_NEW（绑定独立提交，审计失败只回滚审计）—— "
                        + "此时绑定已落库而没有任何证据，且业务测试全绿");

        assertEquals(0, activeRowsOf(TENANT, CUST_TX),
                "同上：该客户名下也不得留下任何 active 手环");

        // ---- ②c 对照：同一个客户、同一支带子，用合法操作者必须成功 ----
        BandBindingResult ok = bind(TENANT, BAND_TX, CUST_TX, false);
        assertEquals(BandBindingOutcome.CREATED, ok.outcome(),
                "🛑 换成合法 operator 之后必须成功 —— 否则上面那条『回滚』断言就无法与"
                        + "『这个客户/这支带子本身绑不上』区分（比较器必须对称）");
        assertEquals(1, bandRows(TENANT, BAND_TX),
                "合法路径下 band 行必须真的落库（这是 ②b 的反向对照）");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 绑定四态 + 解绑三态：CREATED/ALREADY_BOUND/不变量胜出/REPLACED → UNBOUND/ALREADY_UNBOUND/NOT_FOUND")
    void the_binding_state_machine_is_complete_and_directionally_correct() {
        purgeCustomerBands(TENANT, CUST_STATE);

        // ---- 态 1：首次绑定 ----
        assertEquals(BandBindingOutcome.CREATED, bind(TENANT, BAND_STATE, CUST_STATE, false).outcome(),
                "首次绑定 = CREATED");

        // ---- 态 2：重放同一支（不是冲突）----
        assertEquals(BandBindingOutcome.ALREADY_BOUND,
                bind(TENANT, BAND_STATE, CUST_STATE, false).outcome(),
                "重放同一支带子必须返回 ALREADY_BOUND（幂等命中），"
                        + "而不是把它当成错误或冲突 —— 契约里没有绑定端点，故没有状态码可对照，"
                        + "这一态是原语与调用方之间的口径");

        // ---- 态 3：客户已有【另一支】有效带子，且未要求换机 ⇒ 不变量胜出 ----
        assertEquals(BandBindingOutcome.CUSTOMER_ALREADY_HAS_ACTIVE_BAND,
                bind(TENANT, BAND_STATE2, CUST_STATE, false).outcome(),
                "客户已有另一支有效手环且本次未换机 ⇒ 返回 CUSTOMER_ALREADY_HAS_ACTIVE_BAND。"
                        + "🛑 这不是错误：uq_band_active_customer 保证了『1 客户 : 1 有效手环』，"
                        + "本态就是它胜出的结果");

        assertEquals(0, bandRows(TENANT, BAND_STATE2),
                "🛑 不变量胜出时，那支【新】带子必须一行都没有 —— "
                        + "若它被写进去了，说明 ON CONFLICT 的推断目标没命中部分唯一索引"
                        + "（那会以『没有与 ON CONFLICT 说明匹配的唯一约束』在运行期炸，"
                        + "而迁移期看不出来）");
        assertEquals("active", bandState(TENANT, BAND_STATE)[0],
                "🛑 且既有那支必须仍然是 active —— 本次调用不得改动它");
        assertEquals(1, activeRowsOf(TENANT, CUST_STATE),
                "该客户名下的 active 手环数必须恰为 1（部分唯一索引的可观测面）");

        // ---- 态 4：换机 ⇒ REPLACED（旧带作废 + 新带 active）----
        assertEquals(BandBindingOutcome.REPLACED, bind(TENANT, BAND_STATE2, CUST_STATE, true).outcome(),
                "rebind=true 时必须返回 REPLACED —— 它与 CREATED 的区别在于"
                        + "『另有一行被改成了 unbound』，那是应戴天分母的一次截断，"
                        + "而 A3 是退款资格的输入之一，故两者在审计上【不得】合并");

        String[] old = bandState(TENANT, BAND_STATE);
        assertEquals("unbound", old[0], "换机后旧带必须被置为 unbound");
        assertEquals("换机", old[1],
                "旧带的 unbind_reason 必须是『换机』—— 该列是区分『主动放弃』与"
                        + "『技术性缺失』(data-spec M6) 的唯一载体");
        assertFalse(old[2].isBlank(), "换机后旧带必须有 unbound_at（应戴天分母的终点）");
        assertEquals("active", bandState(TENANT, BAND_STATE2)[0], "新带必须是 active");
        assertEquals(1, activeRowsOf(TENANT, CUST_STATE), "换机之后该客户仍恰有 1 支有效手环");

        // ---- 态 5：解绑 ----
        assertEquals(BandBindingOutcome.UNBOUND, unbind(BAND_STATE2, "主动放弃").outcome(),
                "解绑必须返回 UNBOUND");

        // ---- 态 6：重放解绑 —— 幂等，且【理由不得被 NULL 覆盖】----
        assertEquals(BandBindingOutcome.ALREADY_UNBOUND, unbind(BAND_STATE2, null).outcome(),
                "重放解绑必须返回 ALREADY_UNBOUND");
        assertEquals("主动放弃", bandState(TENANT, BAND_STATE2)[1],
                "🛑 重放解绑【不得】把既有理由覆盖成 NULL —— "
                        + "这条保证必须由库层 UPDATE 的 `status <> 'unbound'` 谓词给出，"
                        + "不能依赖一次可能过期的读（并发下两个解绑会同时通过那一次读，"
                        + "而第二次会把理由覆盖掉）");

        // ---- 态 7：本租户内看不到这一行 ----
        assertEquals(BandBindingOutcome.NOT_FOUND, unbind(BAND_GHOST, null).outcome(),
                "🛑 措辞刻意是 NOT_FOUND 而不是 NOT_EXISTS：在 FORCE RLS 下，"
                        + "『不存在』与『存在但当前上下文看不见』给出同一个结果，"
                        + "用『不存在』会把后一种情形说成事实");

        // ---- 态 8：解绑后可重绑（部分唯一索引不阻挡历史行）----
        assertEquals(BandBindingOutcome.CREATED, bind(TENANT, BAND_STATE3, CUST_STATE, false).outcome(),
                "🛑 解绑之后重绑必须成功 —— 若失败，说明 uq_band_active_customer 的 WHERE 谓词丢了、"
                        + "变成了『1 客户 : 1 手环（含历史行）』，"
                        + "那会直接掐掉换机与再接入两条业务路径");
    }

    // ==================================================================
    // 判据 ④
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 跨租户引用必须被拒：函数侧给可读业务错误，绕过函数则被外键 23503 拒（不是 42501）")
    void a_cross_tenant_customer_reference_is_refused_with_the_right_reason() {
        // ---- ④a 走函数：V17 显式检查客户是否存在【于本租户内】⇒ 可读的业务错误 ----
        serviceCalls++;
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.bind(bindRecord(TENANT_OTHER, BAND_CROSS, CUST_GATE, false), OPERATOR),
                "🛑 用租户 B 的身份去绑租户 A 的客户，必须被拒 —— "
                        + "否则一次写错租户标识就能把带子挂到别的租户的客户名下");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 函数侧的显式 RAISE 必须是 P0001(raise_exception) —— "
                        + "若它是 23503，那说明本次拦截发生在外键而不是函数里，"
                        + "『函数先给可读原因、库层再兜底』这条设计就不成立了。异常链="
                        + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("不存在"),
                "🛑 拒绝必须是一条【写明原因】的业务错误（客户 X 在租户 Y 内不存在），"
                        + "而不是一条 PostgreSQL 约束名报错 —— 两者都 fail-closed，"
                        + "区别只在诊断成本。异常链=" + fullCauseMessages(ex));

        assertEquals(0, bandRows(TENANT_OTHER, BAND_CROSS),
                "🛑 被拒之后不得留下任何 hander 行（RAISE 会使整个函数调用回滚）");

        purgeCustomerBands(TENANT, CUST_GATE);

        // ---- ④b 绕过函数（裸 SQL）：库层必须用外键兜底 ----
        //     🛑 这是本类唯一一处允许出现裸 INSERT INTO band 的地方，理由正是本判据本身：
        //        要证明"绕过函数也拦得住"，那条语句就必须真的绕过函数。
        String state = writeExpectingFailure(TENANT,
                "INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, 'raw-probe', CURRENT_DATE, 'active')",
                BAND_CROSS, TENANT, CUST_CROSS);
        assertNotNull(state,
                "🛑 裸 SQL 在租户 A 的上下文里成功把 band.customer_id 指向了租户 B 的客户 —— "
                        + "V16 的跨租户引用完整性已被突破");
        assertEquals("23503", state,
                "🛑 拒绝理由必须是 23503（复合外键 band_customer_id_fkey 未命中），实际=" + state
                        + "。🛑 特别要排除 42501 —— 那说明真正的原因是 RLS 拦下了这条 INSERT，"
                        + "于是本判据就没有证明『引用完整性』这件事，只证明了『行归属』"
                        + "（V16 门禁里那条『RLS 只保护行归属、从不保护引用归属』的实测结论）");

        // ---- ④c 对照：同租户的客户必须放行 ----
        writeInTenant(TENANT,
                "INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, 'raw-probe', CURRENT_DATE, 'active')",
                BAND_CROSS, TENANT, CUST_GATE);
        assertEquals(1, bandRows(TENANT, BAND_CROSS),
                "🛑 同租户引用必须放行 —— 否则 ④b 的 23503 就无法与"
                        + "『这条语句在任何情况下都失败』区分（比较器必须对称）");
    }

    // ==================================================================
    // 判据 ⑤
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 解绑是【状态迁移】而非删除：历史行仍在，台账全历史可观测")
    void unbinding_is_a_state_transition_and_the_ledger_history_survives() {
        purgeCustomerBands(TENANT, CUST_STATE);

        bind(TENANT, BAND_STATE, CUST_STATE, false);
        bind(TENANT, BAND_STATE2, CUST_STATE, true);          // 换机：BAND_STATE → unbound

        List<String[]> history = service.historyOf(TENANT, UUID.fromString(CUST_STATE));
        assertEquals(2, history.size(),
                "🛑 换机之后台账里必须有【两行】—— 旧带是 unbound 而【不是被删除】。"
                        + "删掉一行等于抹掉一段应戴天，而 unbound_at 是那段应戴天的终点、"
                        + "A3（应戴天）是退款资格的输入之一。实际行数=" + history.size()
                        + " 内容=" + render(history));

        boolean sawUnbound = false;
        for (String[] row : history) {
            if ("unbound".equals(row[1]) && "换机".equals(row[2]) && !row[4].isBlank()) {
                sawUnbound = true;
            }
        }
        assertTrue(sawUnbound,
                "🛑 历史里必须能看到那条 unbound 行、且带理由『换机』与一个非空 unbound_at —— "
                        + "这是『解绑留下了完整的两端』的唯一可断言载体。实际=" + render(history));
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ 幂等重放与不变量胜出【也】写审计 —— 一次『有人试图重复接入』必须可被事后看见")
    void idempotent_replays_and_invariant_wins_also_leave_an_audit_trail() {
        purgeCustomerBands(TENANT, CUST_STATE);

        // ---- ⑥a 首次绑定：审计 1 条 ----
        BandBindingResult first = bind(TENANT, BAND_STATE, CUST_STATE, false);
        assertAudit(first.auditId(), ACTION_BOUND, BAND_STATE, "\"mode\":\"CREATED\"");

        // ---- ⑥b 重放：库层 ON CONFLICT DO NOTHING 不报错、不改数据、返回一个正常值 ----
        int before = countAudit(ACTION_BOUND, BAND_STATE);
        BandBindingResult replay = bind(TENANT, BAND_STATE, CUST_STATE, false);
        assertEquals(BandBindingOutcome.ALREADY_BOUND, replay.outcome());
        assertAudit(replay.auditId(), ACTION_BOUND, BAND_STATE, "\"mode\":\"ALREADY_BOUND\"");
        assertEquals(before + 1, countAudit(ACTION_BOUND, BAND_STATE),
                "🛑 幂等重放必须【恰好】新增一条审计。理由是本仓 B-7 那条裁定的更强形式："
                        + "库层 DO NOTHING 不报错、不改数据、返回一个正常值 —— "
                        + "『有人试图重复接入』这件事若不留痕，在系统里就完全不可见。"
                        + "一句话边界：幂等保证的是『不重复建』，不是『不重复记录』");

        // ---- ⑥c 不变量胜出（客户已有另一支有效带子）也写审计 ----
        BandBindingResult win = bind(TENANT, BAND_GHOST, CUST_STATE, false);
        assertEquals(BandBindingOutcome.CUSTOMER_ALREADY_HAS_ACTIVE_BAND, win.outcome());
        assertAudit(win.auditId(), ACTION_BOUND, BAND_GHOST,
                "\"mode\":\"CUSTOMER_ALREADY_HAS_ACTIVE_BAND\"");

        // ---- ⑥d 反向断言：经营信息【不得】进审计 payload ----
        String payload = jdbc.queryForObject(
                "SELECT payload FROM audit_log WHERE id = ?::uuid", String.class, first.auditId());
        assertNotNull(payload);
        assertFalse(payload.contains("model-b10"),
                "🛑 审计 payload 里出现了设备型号 —— audit_log 是被【全租户可读】的"
                        + "（它没有 RLS，那是哈希链连续性的要求，敞口已登记）。"
                        + "把 vendor/model 写进去，等于把『这个租户在用哪批设备型号』这类经营信息"
                        + "暴露给每一个能读审计的租户。要查设备细节应查 band 表本身，那里有 RLS。"
                        + "实际 payload=" + payload);
        assertFalse(payload.contains("GTL1"), "同上：厂商也不得进 payload。实际=" + payload);
        assertTrue(payload.contains("\"mutated\":true"),
                "payload 必须写明本次是否真的改动了数据 —— "
                        + "这是区分『变更』与『重放』的唯一一栏。实际=" + payload);

        // ---- ⑥e 解绑侧同上：动作名与目标类型必须正确 ----
        //     🛑 同时验证 payload 的 mutated=false（ALREADY_BOUND/不变量胜出都未改数据）
        String winPayload = jdbc.queryForObject(
                "SELECT payload FROM audit_log WHERE id = ?::uuid", String.class, win.auditId());
        assertTrue(winPayload != null && winPayload.contains("\"mutated\":false"),
                "🛑 不变量胜出【未改动任何数据】，故 payload 的 mutated 必须是 false —— "
                        + "若它是 true，那审计就记录了一次【并不存在的写入】，"
                        + "而这类假记录事后无法被察觉。实际=" + winPayload);

        BandBindingResult unbound = unbind(BAND_STATE, "设备损坏");
        assertAudit(unbound.auditId(), ACTION_UNBOUND, BAND_STATE, "\"mode\":\"UNBOUND\"");
    }

    // ==================================================================
    // 判据 ⑦
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 上下文一致性守卫：已持有别的租户上下文时，绑定必须被拒（不允许静默改写）")
    void an_existing_foreign_tenant_context_blocks_the_binding() {
        purgeCustomerBands(TENANT, CUST_GATE);

        // 🛑 在外层事务里先设一个【别的】租户的上下文，再在同一事务内调 bind。
        //    BandBindingService 的 TransactionTemplate 传播缺省为 REQUIRED ⇒ 它会【加入】本事务，
        //    故 V17 的 current_setting('app.tenant_id') 能读到那个值。
        //    🛑 这正是"跨租户写错台账的最短路径"：set_config(..., true) 是事务级设置，
        //       不会在函数入口自动重置 —— 若在此静默覆盖，函数之后的全部写入都会落在新租户名下，
        //       而调用方仍以为在旧租户名下。
        serviceCalls++;
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> tx.execute(status -> {
                    jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT_OTHER + "'");
                    return service.bind(bindRecord(TENANT, BAND_GATE, CUST_GATE, false), OPERATOR);
                }),
                "🛑 已持有租户上下文 " + TENANT_OTHER + " 时，以租户 " + TENANT
                        + " 的身份绑定竟然成功了 —— 上下文守卫失效，"
                        + "那意味着一次调用可以静默地把写入落到另一个租户名下");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V17 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 而不是那道上下文守卫 —— "
                        + "两者都 fail-closed，但『守卫发现上下文不一致』与"
                        + "『RLS 恰好挡住了』是完全不同的结论。异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("不一致"),
                "🛑 拒绝必须是【写明理由】的一条错误（本事务已持有租户上下文 X，"
                        + "与本次调用传入的租户 Y 不一致），而不是一条随机的 SQL 失败。异常链="
                        + fullCauseMessages(ex));

        assertEquals(0, bandRows(TENANT, BAND_GATE),
                "被拒之后本租户内不得留下该带子的任何行");

        // ---- 对照：换一个【干净的】事务（无既有上下文）必须成功 ----
        BandBindingResult ok = bind(TENANT, BAND_GATE, CUST_GATE, false);
        assertEquals(BandBindingOutcome.CREATED, ok.outcome(),
                "🛑 无既有上下文时同一调用必须成功 —— 否则上面那条『被拒』就无法与"
                        + "『这支带子/这个客户本身绑不上』区分");
    }

    // ==================================================================
    // 五、辅助断言
    // ==================================================================

    private static int countAudit(String action, String targetId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId);
        return n == null ? 0 : n;
    }

    /**
     * 断言某条审计存在、且字段逐项正确、payload 含指定的模式片段。
     *
     * <p>🛑 按 {@code id} 精确取（而不是"按 action 取最新一条"）：后者在
     * 同一次运行里有多条同 action 审计时会取错行，于是"payload 里有没有某个模式"
     * 这条断言就会在别的那一行上成立 —— 一个自己造出来的假通过。
     */
    private static void assertAudit(String auditId, String action, String targetId, String payloadFragment) {
        assertNotNull(auditId, "绑定/解绑回执必须带上本次留痕的审计 id —— 它是事后对账的唯一锚点");
        List<String[]> rows = jdbc.query(
                "SELECT action, target_type, target_id, actor, coalesce(payload, '') "
                        + "FROM audit_log WHERE id = ?::uuid",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)},
                auditId);
        assertEquals(1, rows.size(),
                "回执给出的 auditId 必须在库中恰对应一条审计（实际条数=" + rows.size()
                        + "）—— 否则回执是无法用于对账的");
        String[] r = rows.get(0);
        assertEquals(action, r[0], "action 必须逐字等于 " + action);
        assertEquals(TARGET_TYPE, r[1], "target_type 必须是 band");
        assertEquals(targetId, r[2], "target_id 必须是本次操作的手环 id");
        assertEquals(OPERATOR, r[3],
                "审计 actor 必须是调用方提供的操作者，不是 'band-binding' 这种系统占位值 —— "
                        + "审计里 actor 是唯一有追责含义的一栏");
        assertTrue(r[4].contains(payloadFragment),
                "payload 必须含 " + payloadFragment + "（本次走的是哪条路径）。实际=" + r[4]);
    }

    private static String render(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] r : rows) {
            sb.append(String.join("/", r)).append("; ");
        }
        return sb.append(']').toString();
    }

    // ==================================================================
    // 六、收尾
    // ==================================================================

    /**
     * 收尾：清数据 + <b>断言审计证据确实留下</b>（不删审计）+ 清理自证。
     *
     * <p>Spring Boot 3.3 支持在 {@code @AfterAll} 上注入 {@link DataSource}
     * （与测试实例生命周期解耦，故本方法可以是 static）。
     */
    @AfterAll
    static void cleanup(@Autowired DataSource ds) {
        if (ds == null) {
            return;
        }
        JdbcTemplate j = new JdbcTemplate(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));

        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                // SET LOCAL 必须在事务内 —— 事务一结束即失效（各 *Ledger 的 inTenant 同一纪律）
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                j.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band_daily_coverage WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                // 🛑 V11 起：密钥材料必须先于租户删除（tenant_kek.tenant_id → tenant.id 有 FK）。
                //    本类的 E2 走的是不加密的 metric（exercise），故通常没有密钥行；
                //    DELETE 对 0 行是安全的。
                j.update("DELETE FROM subject_dek WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM tenant_kek WHERE tenant_id = ?::uuid", tid);
            });
        }
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);

        // ---- 清理自证 ----------------------------------------------------
        // 🛑 这一条不是形式主义：FORCE RLS 下无上下文时 DELETE 会【静默删 0 行】，
        //    于是"清理"看起来成功、残留却一路累积。故必须回头数一次。
        Integer leftTenants = j.queryForObject(
                "SELECT count(*) FROM tenant WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, TENANT, TENANT_OTHER);
        assertTrue(leftTenants == null || leftTenants == 0,
                "清理后仍残留 " + leftTenants + " 个租户 —— 清理逻辑失效");
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                assertTrue(j.queryForObject("SELECT count(*) FROM band", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有手环残留（FORCE RLS 会静默删 0 行，"
                                + "这正是最容易被忽略的一类残留）");
                assertTrue(j.queryForObject("SELECT count(*) FROM customer", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有客户残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下 ----------------------------
        // 🛑 audit_log 是一条全局单链，删掉中间任何一行会让【它的后继】prev_hash 对不上，
        //    从而为后续每一次 verifyChain() 留下一个永久的假断口。
        //    且"一次真实的台账变更客观发生过"，它的审计证据本就应当留下（append-only 语义）。
        assertTrue(serviceCalls > 0,
                "自证失败：serviceCalls 为 0，说明本类从未真正走过绑定/解绑路径 —— "
                        + "那么上面所有『必须有审计』的断言都没有载体");
        Integer boundRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_BOUND, BAND_STATE);
        assertTrue(boundRows != null && boundRows >= 1,
                "审计条目必须留下且不得被清理（action=" + ACTION_BOUND + " target_id=" + BAND_STATE
                        + "）—— 实际条数=" + boundRows);
    }
}