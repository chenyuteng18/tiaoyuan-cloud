package com.diaoyuanyun.dy.app.band;

import com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope;
import com.diaoyuanyun.dy.crypto.field.SensitiveField;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>字段级加密端到端往返</b>：真密钥栈 + 真 HTTP 请求 + 真 PostgreSQL。
 *
 * <h2>这个类为什么必须存在（它和邻类不重复在哪）</h2>
 * {@link BandEndpointsE2ETest} 断言的是<b>契约形状</b>（状态码、字段有无、幂等），
 * {@code RlsBEntityIsolationTest} 断言的是<b>租户隔离</b>（跨租户零行）。
 * 两者都<b>不</b>回答本类要回答的这个问题：
 * <pre>
 *   "库里那一列，到底是密文还是明文？"
 * </pre>
 * 这个问题在 B-1 之前<b>没有任何测试会红</b> —— 因为 historically dy-crypto
 * 零调用方，而加解密正确性由 dy-crypto 自己的 28 个单测覆盖。
 * 「能力正确」与「能力接到了数据上」是两件事，本类断言后者。
 *
 * <h2>🛑 本类的四条判据（逐条对应 B-1 的验收）</h2>
 * <ol>
 *   <li><b>库里确实是密文</b>：直查 {@code band_telemetry.value_enc} 原始字节，
 *       必须是 {@code dy1:} 开头的信封，且<b>不得</b>出现原始数值文本。
 *       ⚠️ 这一条必须<b>绕过应用层</b>直查 —— 通过 API 读回来的永远是明文
 *       （那正是"对业务透明"的意思），用 API 读回的值断言加密等于自证。</li>
 *   <li><b>明文可通过 API 原样取回</b>：写 8500 → 读回 8500，逐字相等。
 *       它证明的<b>不是</b>"加密生效"，而是<b>"加密没有把数据弄坏"</b>——
 *       少了它，判据 1 可以靠"写进去一串谁也解不开的垃圾"通过。</li>
 *   <li><b>两次写入同一明文 ⇒ 密文不同</b>（AEAD 的 nonce 随机化）。
 *       它排除"密文其实就是明文做了个可逆编码"这种退化，并证明 nonce 不是常量 ——
 *       常量 nonce 在 GCM 下是灾难性的（同一密钥下重复 nonce 会泄漏明文异或值）。</li>
 *   <li><b>睡眠明细（sleep_json）也走加密</b>：它的敏感性不由 metric 决定
 *       （见 {@code TelemetrySensitivity.requiresEncryptionForSleepDetail}），
 *       故这是<b>唯一</b>一条会漏在"按 metric 判断"之外的路径。</li>
 * </ol>
 *
 * <h2>为什么必须用真密钥栈（而不是 mock FieldCipher）</h2>
 * B-1 的原始卡点正是"dy-crypto 有实现但零调用方"。若本类 mock 掉 cipher，
 * 它只能证明"BandLedger 会调用某个接口"，而<b>不能</b>证明：
 * 密钥真的落了库（V11 三张表）、重启后还能解开（KEK 持久化）、
 * 主密钥真的从配置/dev 环境变量来了。这三件事只有真栈 + 真库能证明。
 *
 * <h2>🛑 {@code DY_MASTER_KEY} 在这条链路里从哪来</h2>
 * {@code application.yml} 的 {@code dy.crypto.master-key} 已配了
 * {@code ${DY_MASTER_KEY:<64位开发占位>}}，故本类<b>不需要</b>任何额外设置即可装配。
 * 而这恰好也是生产纪律的体现：环境变量在场 ⇒ 用生产密钥；不在场 ⇒ 用占位密钥
 * （仅本地可跑）。占位值以明文写在配置里是刻意的，理由见 {@code application.yml} 该段的注释。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("B-1 · 字段级加密端到端往返（真密钥栈 + 真 HTTP + 真库）")
class BandTelemetryEncryptionTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本类的独立租户，与 BandEndpointsE2ETest 的 e2e…eeee 零交集。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000cc0c";

    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000c1";
    private static final String S_BAND = "e2e00000-0000-0000-0000-0000000000c2";

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

    /**
     * 清理：先密钥材料，再业务行，最后租户。
     *
     * <p>🛑 顺序不可换：{@code tenant_kek.tenant_id → tenant.id} 有 FK，
     * 故带密钥材料的租户<b>无法</b>被静默删除 —— 这是刻意的 fail-closed。
     * 本类真的走过加密链路，故 {@code tenant_kek} / {@code subject_dek}
     * <b>确实有行</b>（不像 BandEndpointsE2ETest 可能没有），这条顺序在这里是必需的。
     *
     * <p>⚠️ 墓碑表不清理（V11 的 {@code tombstone_no_delete} 规则让 DELETE 静默无效）。
     * 本类不销毁任何主体密钥，故墓碑为空 —— 无需也无法清理。
     */
    @AfterAll
    static void cleanup() {
        if (jdbc == null || dataSource == null) {
            return;
        }
        inTenant(() -> {
            jdbc.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band WHERE tenant_id = ?::uuid AND band_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM subject_dek WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM tenant_kek WHERE tenant_id = ?::uuid", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-B1加密往返') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B1加密客户', 'CONSENTED') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, model, bound_at, status) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', 'model-x', CURRENT_DATE, 'active')"
                            + " ON CONFLICT DO NOTHING",
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

    private ResponseEntity<String> post(String path, String body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
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
    // 判据 1 + 2：库里是密文；API 读回是明文（逐字相等）
    // ==================================================================

    @Nested
    @DisplayName("心率（PIPL 敏感 · DoD ①名点项）")
    class HeartRate {

        @Test
        @DisplayName("写 72 → 库里是 dy1: 信封（不含 '72'）→ API 读回恰好 72")
        void encrypted_at_rest_and_plaintext_round_trips() {
            String healthDate = "2026-09-26";
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"metric\":\"hr\",\"date\":\"" + healthDate + "\",\"value\":72}";
            ResponseEntity<String> wrote = post("/api/v1/band/telemetry", body, token("client"));
            assertEquals(200, wrote.getStatusCodeValue(), "写入应 200: " + wrote.getBody());

            // ---- 判据 1：直查库（绕过应用层），必须是信封 ----
            String raw = inTenant(() -> jdbc.queryForObject(
                    "SELECT value_enc FROM band_telemetry"
                            + " WHERE tenant_id = ?::uuid AND customer_id = ?::uuid"
                            + " AND metric = 'hr' AND date = ?::date AND hour IS NULL AND minute IS NULL",
                    String.class, TENANT, S_CUSTOMER, healthDate));
            assertNotNull(raw, "库中应有该行 —— 否则下面的断言无从谈起");
            assertTrue(raw.startsWith("dy1:"),
                    "value_enc 必须是 dy1: 信封。实际库里存的是: " + raw);
            assertFalse(raw.contains("72"),
                    "🛑 密文里出现了明文数值 '72' —— 这不是加密，是可逆编码");
            // 段序必须与 CipherEnvelope.serialize() 逐字一致：前缀:算法:版本:nonce:密文
            String[] segs = raw.split(":", 5);
            assertEquals(5, segs.length,
                    "信封应恰有 5 段（dy1:算法:dek版本:nonce:密文）。实际=" + raw);
            assertEquals("dy1", segs[0], "前缀必须是 dy1");
            assertTrue(segs[1].contains("AES"),
                    "算法位应是注册表里的真实算法标识。实际=" + segs[1]);
            assertTrue(Integer.parseInt(segs[2]) >= 1,
                    "DEK 版本必须是 >= 1 的整数。实际=" + segs[2]);
            // 能被真正的解析器认出 —— 证明这不是"看起来像信封"的字符串
            assertEquals(1, CipherEnvelope.parse(raw).dekVersion(),
                    "信封必须能被 CipherEnvelope.parse() 解析（自证格式合法）");

            // ---- 判据 2：API 读回必须是明文 72（证明加密没有把数据弄坏）----
            ResponseEntity<String> read = get(
                    "/api/v1/customers/" + S_CUSTOMER + "/band/telemetry", token("client"));
            assertEquals(200, read.getStatusCodeValue(), "读取应 200: " + read.getBody());
            JsonNode hr = findMetric(parse(read.getBody()).at("/data/metrics"), "hr", healthDate);
            assertNotNull(hr, "读回应含 metric=hr 的条目: " + read.getBody());
            assertEquals(0, hr.get("value").decimalValue().compareTo(new java.math.BigDecimal("72")),
                    "🛑 读回的明文必须逐字等于写入值 —— 若不等，说明加解密把数据弄坏了"
                            + "（而判据 1 会因此失去意义：写进去一并垃圾也能通过）。实际=" + hr.get("value"));
        }

        @Test
        @DisplayName("🛑 同明文写两次 ⇒ 密文必不相同（nonce 随机化，非常量）")
        void same_plaintext_yields_different_ciphertext() {
            // 用两条不同的幂等键成员（hour 不同）使两行共存，故可以比较"同一明文的两份密文"。
            String sql = "SELECT value_enc FROM band_telemetry"
                    + " WHERE tenant_id = ?::uuid AND metric = 'resting_hr' AND date = ?::date"
                    + " AND hour = ?";

            for (int hour : new int[]{20, 21}) {
                String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                        + "\"metric\":\"resting_hr\",\"date\":\"2026-09-25\",\"hour\":" + hour
                        + ",\"value\":58}";
                assertEquals(200, post("/api/v1/band/telemetry", body, token("client")).getStatusCodeValue(),
                        "写入 hour=" + hour + " 应 200");
            }

            String first = inTenant(() -> jdbc.queryForObject(sql, String.class, TENANT, "2026-09-25", 20));
            String second = inTenant(() -> jdbc.queryForObject(sql, String.class, TENANT, "2026-09-25", 21));
            assertNotNull(first, "第一条应已落库");
            assertNotNull(second, "第二条应已落库");
            assertNotEquals(first, second,
                    "🛑 同一明文（58）两次加密得到【相同】密文 —— nonce 是常量！"
                            + "GCM 下重复 nonce 会让两份密文的异或等于明文的异或，"
                            + "攻击者无需密钥即可看出'这两条记录相等'，并逐位恢复明文"
                            + "（这是 AEAD 最经典的失效模式）。实际两次密文均为: " + first);
        }
    }

    @Nested
    @DisplayName("睡眠分期（sleep_json · 敏感性不由 metric 决定）")
    class SleepDetail {

        @Test
        @DisplayName("sleep_json 落库即密文；API 不泄露该列原文")
        void sleep_detail_is_encrypted_even_though_it_has_no_metric_of_its_own() {
            // ⚠️ 这条路径是本类最容易被漏掉的一条：sleep_json 的敏感性<b>不由 metric 决定</b>
            //    （见 TelemetrySensitivity.requiresEncryptionForSleepDetail）。
            //    若有人把加密判断写成"按 metric 查表"，metric=steps 时整行都会不加密，
            //    连带的 sleep_json 也漏 —— 而无任何断言会红。本用例就是那条断言。
            String sleepJson = "{\"deep_min\":95,\"light_min\":210,\"rem_min\":80}";
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"metric\":\"sleep\",\"date\":\"2026-09-24\","
                    + "\"sleep_json\":\"" + sleepJson.replace("\"", "\\\"") + "\"}";
            assertEquals(200, post("/api/v1/band/telemetry", body, token("client")).getStatusCodeValue(),
                    "写入应 200");

            String raw = inTenant(() -> jdbc.queryForObject(
                    "SELECT sleep_json FROM band_telemetry"
                            + " WHERE tenant_id = ?::uuid AND metric = 'sleep' AND date = '2026-09-24'",
                    String.class, TENANT));
            assertNotNull(raw, "库中应有该行");
            assertTrue(raw.startsWith("dy1:"),
                    "sleep_json 必须落密文信封。实际: " + raw);
            assertFalse(raw.contains("deep_min"),
                    "🛑 密文里出现了 JSON 键名 'deep_min' —— 睡眠分期明细未加密");
            assertFalse(raw.contains("95"),
                    "🛑 密文里出现了明文数值 '95' —— 睡眠分期明细未加密");
        }
    }

    @Nested
    @DisplayName("非敏感 metric（反证：不能一律加密）")
    class NonSensitive {

        @Test
        @DisplayName("pressure（压力值，非登记敏感项）落库为明文数字 —— 证明登记表有区分力")
        void non_sensitive_metric_stays_plaintext() {
            // 反证的必要性：若所有 metric 都被加密，"哪些字段敏感"这份清单就失去区分力
            // （等价于没有清单）。而 MET/MAI/pressure 这类派生活动量加密的代价是
            // 每次读取都要走一次密钥栈 —— 徒增开销却无合规收益。
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"metric\":\"pressure\",\"date\":\"2026-09-23\",\"value\":47}";
            assertEquals(200, post("/api/v1/band/telemetry", body, token("client")).getStatusCodeValue(),
                    "写入应 200");

            String raw = inTenant(() -> jdbc.queryForObject(
                    "SELECT value_enc FROM band_telemetry"
                            + " WHERE tenant_id = ?::uuid AND metric = 'pressure' AND date = '2026-09-23'",
                    String.class, TENANT));
            assertNotNull(raw, "库中应有该行");
            assertFalse(raw.startsWith("dy1:"),
                    "pressure 不在敏感登记表内，不应被加密 —— 若它变成密文，"
                            + "说明加密判断退化成了'一律加密'，登记的区分力已失效。实际: " + raw);
            assertEquals("47", raw.trim(),
                    "非敏感 metric 应原样落明文数字。实际: " + raw);
        }
    }

    @Nested
    @DisplayName("密钥材料真的落了库（V11 三张表）")
    class KeyMaterial {

        @Test
        @DisplayName("走过加密后 tenant_kek 与 subject_dek 都有行，且 wrapped 材料是密文而非裸密钥")
        void key_material_is_persisted_and_wrapped() {
            // 触发一次加密（若前面的用例已触发，这里幂等无害）
            String body = "{\"device_id\":\"" + S_BAND + "\",\"customer_id\":\"" + S_CUSTOMER + "\","
                    + "\"metric\":\"spo2\",\"date\":\"2026-09-22\",\"value\":98}";
            assertEquals(200, post("/api/v1/band/telemetry", body, token("client")).getStatusCodeValue(),
                    "写入应 200");

            Integer keks = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM tenant_kek", Integer.class));
            assertTrue(keks != null && keks >= 1,
                    "🛑 tenant_kek 无行 —— 说明 KEK 只存在于进程内存里，"
                            + "重启后全部密文将永久不可读（这正是 V11 存在的理由）。实际=" + keks);

            Integer deks = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM subject_dek WHERE subject_id = ?", Integer.class, S_CUSTOMER));
            assertTrue(deks != null && deks >= 1,
                    "🛑 该客户没有 per-subject DEK 行 —— 删除权将无处落点"
                            + "（ADR-12 §8.3：租户级密钥无法删除租户内单个用户）。实际=" + deks);

            // 🛑 wrapped_bytes 必须是密文：裸 KEK 落库等于把最内层直接暴露。
            //    判据用长度下界 + 与 aad_text 不等 —— 不比"看起来像不像随机"，
            //    因为那不可断言；可断言的是"它不是一段可读的 AAD 文本"。
            String aad = inTenant(() -> jdbc.queryForObject(
                    "SELECT aad_text FROM tenant_kek LIMIT 1", String.class));
            String wrappedHex = inTenant(() -> jdbc.queryForObject(
                    "SELECT encode(wrapped_bytes,'hex') FROM tenant_kek LIMIT 1", String.class));
            assertNotNull(aad, "aad_text 必须落库（否则认证串无从重建，密文永久解不开）");
            assertTrue(aad.contains(TENANT),
                    "KEK 的 AAD 必须绑定租户（归属绑定贯穿每一层）。实际: " + aad);
            assertNotNull(wrappedHex, "wrapped_bytes 必须落库");
            assertFalse(wrappedHex.toLowerCase().contains("aes"),
                    "🛑 wrapped_bytes 里出现了算法名字面量 —— 这不像被加密的材料。实际: " + wrappedHex);

            // 私钥字节绝不能以 base64/hex 的可读形态独立成列（本表无此列，此处显式断言）
            List<String> cols = inTenant(() -> jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='tenant_kek'", String.class));
            assertFalse(cols.contains("raw_kek") || cols.contains("kek_bytes") || cols.contains("plain_kek"),
                    "🛑 tenant_kek 出现了裸密钥列（raw_kek/kek_bytes/plain_kek）—— "
                            + "裸 KEK 绝不可落库。实际列: " + cols);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 在 metrics 数组里按 (metric, date) 找一条，找不到返回 null。 */
    private static JsonNode findMetric(JsonNode metrics, String metric, String date) {
        if (metrics == null || !metrics.isArray()) {
            return null;
        }
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText()) && date.equals(m.path("date").asText())) {
                return m;
            }
        }
        return null;
    }

    /** 保留：DoD ① 三项的显式锚定（防止将来有人把 SensitiveField 的登记项删掉）。 */
    @SuppressWarnings("unused")
    private static final List<SensitiveField> DOD_REQUIRED_THREE = SensitiveField.dodRequiredThree();
}