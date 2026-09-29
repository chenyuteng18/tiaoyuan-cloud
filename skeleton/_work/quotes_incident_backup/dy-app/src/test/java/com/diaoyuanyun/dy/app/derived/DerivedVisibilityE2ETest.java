package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.band.domain.BandProbeResult;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.visibility.DerivedFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-5 验收② + 验收③ · <b>真请求</b>回归 ——「客户端请求派生字段一律 403（非前端不渲染）」
 * 与「派生字段不出现在任何响应体」。
 *
 * <h2>为什么这两条验收必须"真请求"</h2>
 * 用户给的措辞里有一句关键的限定：<b>「真请求断言，非前端不渲染」</b>。
 * 直调 {@code DerivedFields} / {@code DerivedResponseBodyAdvice} 只能证明<b>规则对</b>，
 * 证明不了<b>规则被挂在了正确的链路上</b>。中间有三处会静默失守：
 * <ul>
 *   <li>{@code WebConfig} 忘了注册入站拦截器 → 规则恒不执行，单测全绿；</li>
 *   <li>拦截器顺序不对 → 更粗粒度的判定先返回，调用方拿到的是"没有 customer:read"
 *       而不是"你索取了 effect_verdict"（错误信息量倒退）；</li>
 *   <li>出口兜底没被 Spring MVC 采纳（{@code ResponseBodyAdvice} 需要装配正确）→
 *       字段照旧出站。</li>
 * </ul>
 * 故本类<b>启动真实 Spring 上下文 + 真实 servlet 容器</b>，用<b>真签名 JWT</b> 发 HTTP，
 * 断言<b>真实 HTTP 状态码与真实响应体</b>。同 {@code TierAuthorizationE2ETest} 的理由与形态。
 *
 * <h2>🛑 为什么注入一个"测试专用探针端点"</h2>
 * 验收③（派生字段不出现在任何响应体）有一个天然的观测困难：如果所有生产端点都<b>本来</b>
 * 就不返回派生字段，那么"没观测到派生字段"就无法区分
 * <b>「兜底生效了」</b>与<b>「本来就没有」</b>——后者会让本条验收退化成恒绿。
 * 故本类注入一个{@link ProbeController}：它<b>故意</b>在响应里塞满派生字段
 * （含嵌套对象与数组元素里的），然后断言客户端拿到的响应里它们<b>全部消失</b>。
 * 这就是"注入错误必须被测试抓住"的反向验证：
 * <ul>
 *   <li>若出口兜底被摘掉 → 探针响应会带着 {@code as_value} 出去 → 本类红；</li>
 *   <li>若兜底退化成"包含即命中" → 探针里的 {@code as_value_ops}（运营口径，另一个字段）
 *       会被误摘 → 本类红。</li>
 * </ul>
 * 探针只在测试类路径上（{@code @TestConfiguration} + {@code @Import}），不进生产包。
 *
 * <h2>判据锚定外部真相源</h2>
 * <ul>
 *   <li>403 + 2001 → 契约 §2.0 错误码表 / §3.2「服务端 403 硬阻断」；</li>
 *   <li>{@code data.denied_fields=["effect_verdict","as_value"]} → 契约 §2.1 B4 逐字；</li>
 *   <li>「整体不存在（不是 null、不是空串、不是 ""）」→ 契约 §3.2 逐字；</li>
 *   <li>{@code x-callable-roles: [therapist, meridian, admin]} +
 *       {@code x-client-explicitly-denied: true} → 契约 E4（{@code BandDerivedData}）；</li>
 *   <li>角色码 {@code client}/{@code therapist}/{@code meridian}/{@code hq} → 契约根级 {@code x-roles}。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DerivedVisibilityE2ETest.ProbeController.class)
class DerivedVisibilityE2ETest {

    /** 与 application.yml 的占位密钥一致（本地 dev 默认值）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    /** ④ 派生结果字段全集（契约 BandDerivedData 的五个属性）—— 客户响应里必须全部消失。 */
    private static final List<String> BAND_DERIVED_FIELDS = List.of(
            "a3_applicable", "a3_value", "as_value", "effect_verdict", "refund_eligibility");

    /** ③ + 判定链落库列里的派生字段名 —— 探针会一并塞入，客户端响应里也应全部消失。 */
    private static final List<String> OTHER_CLIENT_FORBIDDEN = List.of(
            "gap_reason", "adherence_state", "adherence_score", "mcid",
            "confidence", "evidence_snapshot", "improvement_rate");

    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ==================================================================
    // 验收② · 入站：客户端索取派生字段一律 403（真请求）
    // ==================================================================

    @Test
    @DisplayName("🛑 验收②：客户端调 E4 → 403 + 2001，且 data.denied_fields 回显五个派生字段")
    void client_calling_e4_is_hard_blocked_with_2001() {
        ResponseEntity<String> resp = get("/api/v1/customers/C-1/band/derived", token("client", "own_store"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "客户调 E4 必须 403（契约 E4 x-client-explicitly-denied: true）；"
                        + "实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        assertEquals(2001, code(resp),
                "应报 VISIBILITY_DENIED(2001)，不是 1001/2003 —— 排查方向完全不同");

        JsonNode denied = data(resp).path("denied_fields");
        assertTrue(denied.isArray() && denied.size() > 0,
                "403 必须回显 denied_fields，否则调用方无从判断该去掉哪个参数（P0-08 不得模糊报错）；"
                        + "实际 data=" + data(resp));
        for (String f : BAND_DERIVED_FIELDS) {
            assertTrue(containsText(denied, f),
                    "被拒字段清单漏了 " + f + " —— 契约 E4 的 clientDeniedFields 是这五项全套；"
                            + "实际: " + denied);
        }
    }

    @Test
    @DisplayName("🛑 契约 §2.1 B4 逐字：客户端 ?include=verdict → 403 且回显 [effect_verdict, as_value]")
    void client_include_verdict_returns_the_two_contract_field_names() {
        ResponseEntity<String> resp = get(
                "/api/v1/customers/C-1/band/derived?include=verdict", token("client", "own_store"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "契约 B4 逐字要求此请求 403；实际: " + resp.getStatusCode());
        assertEquals(2001, code(resp));

        JsonNode denied = data(resp).path("denied_fields");
        assertEquals(List.of("effect_verdict", "as_value"), toStringList(denied),
                "契约 B4 逐字给出的是这两个【字段名】，不是组别名 verdict —— "
                        + "回显组名会让客户端只能靠猜；实际: " + denied);
    }

    @Test
    @DisplayName("客户端按参数名 / 体键名点名派生字段 → 403（三种点名写法各验一次）")
    void client_naming_derived_fields_by_param_or_body_is_denied() {
        // ① 参数名
        ResponseEntity<String> byName = get(
                "/api/v1/customers/C-1/band/derived?as_value=1", token("client", "own_store"));
        assertEquals(HttpStatus.FORBIDDEN, byName.getStatusCode(), "参数名点名未被拦");
        assertEquals(2001, code(byName));

        // ③ 请求体键名（POST 需要幂等键，否则会被幂等拦截器先拒）
        HttpHeaders headers = jsonHeaders(token("client", "own_store"));
        headers.set("Idempotency-Key", "e2e-derived-body-1");
        ResponseEntity<String> byBody = rest.exchange(url("/api/v1/band/available-dates"),
                HttpMethod.POST,
                new HttpEntity<>("{\"as_value\": 1, \"days\": 3}", headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, byBody.getStatusCode(),
                "请求体里出现派生键名未被拦 —— 换个体字段名就能带进去；"
                        + "实际: " + byBody.getStatusCode() + " body=" + byBody.getBody());
        assertEquals(2001, code(byBody));
    }

    // ==================================================================
    // 🔴 2026-09-25（S2-10）：E5 缺口 B ——「合法 client 探测必须 200」的守护
    // ==================================================================

    @Test
    @DisplayName("🔴 E5 反向自证：合法 client 探测（无派生键）必须 200 —— 否则 E5 对全部真客户端恒 403")
    void legitimate_client_probe_without_derived_keys_must_reach_200() {
        // 🛑 本用例守的是一类【门禁看不见】的缺口：端点贴了一个"有主但与契约角色不匹配"的码。
        //    成因与形态（本仓库第六次同型复发，S2-10 修复）：
        //      · 契约 L829-862 的 E5 x-callable-roles = [client]，responses = 200/400/422，【没有 403】；
        //      · 而 BandAvailableDatesController 此前贴了 @RequirePermission("customer:write")；
        //      · 注册表【刻意不登记 client】（client 无任何码，这是既有基线）⇒ 任何码都会让
        //        合法客户端请求被 PermissionInterceptor 拦成 403 VISIBILITY_DENIED(2001)。
        //    为什么它躲过了全部既有门禁：
        //      · 码级门禁（PermissionCodeRegistrationGateTest）只问"这个码【有没有主】"，
        //        customer:write 有主（manager 等）⇒ 绿；
        //      · 上文 client_naming_derived_fields_by_param_or_body_is_denied 也断言 403 + 2001，
        //        但它命中的是【派生字段拦截器】（排在权限拦截器【之前】，因请求体含 as_value），
        //        与权限层无关 —— 两条完全不同的路径归同一个 2001，把缺口掩盖了。
        //    故本用例的关键设计是：请求体【不含任何派生键】，使请求能穿过派生拦截器，
        //    真正落到权限层 —— 这是唯一能区分"两码事"的构造。
        HttpHeaders headers = jsonHeaders(token("client", "own_store"));
        // 🛑 请求体不含 as_value / effect_verdict 等派生键，也不含 gap_reason 等客户不可见字段名。
        //    history_type 必须取 13 条枚举之一（否则会是 5001/422，见 HistoryType 的合法值清单），
        //    因为本用例要断言的失败模式是"权限层 403"而【不是】"入参校验失败" ——
        //    用一个非法枚举会让"本应 200"与"枚举写错"两种红看起来一样，失去诊断力。
        String legitBody = "{"
                + "\"device_id\":\"dev-e5-legit-1\","
                + "\"history_type\":\"STEP\","
                + "\"valid_history_dates\":[\"2026-09-20\",\"2026-09-21\",\"2026-09-23\"],"
                + "\"probed_at\":\"2026-09-25T10:00:00Z\","
                + "\"last_synced_date\":\"2026-09-19\","
                + "\"first_binding\":false"
                + "}";
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/band/available-dates"),
                HttpMethod.POST, new HttpEntity<>(legitBody, headers), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "🛑 合法客户端探测【必须 200】（契约 E5 x-callable-roles: [client]，"
                        + "responses 只有 200/400/422，没有 403）。拿到 403 最常见的原因是"
                        + "本端点被贴了一个 @RequirePermission 码 —— 而注册表刻意不登记 client，"
                        + "故任何码都会让真客户端恒 403。E5 是客户小程序的关键路径，"
                        + "此缺口会让整个设备探测功能对客户完全不可用。"
                        + "实际: " + resp.getStatusCode() + " body=" + resp.getBody());

        // 且真的返回了探测结果（而不是"200 但空壳"）。
        JsonNode d = data(resp);
        assertNotNull(d, "200 但 data 为空 —— 端点虽通但没干活；实际 body=" + resp.getBody());
        assertTrue(d.has("retention_window_days"),
                "E5 出参必须有 retention_window_days（N 为运行时探测值）；实际 data=" + d);

        // 🛑 反向自证：把上面那条请求体的派生键加回去，必须【重新】变成 403 ——
        //    证明本端点的防护仍在（摘码不等于放弃越权防护，防护由派生字段拦截器独立承担）。
        String tamperedBody = "{"
                + "\"device_id\":\"dev-e5-legit-1\","
                + "\"history_type\":\"STEP\","
                + "\"valid_history_dates\":[\"2026-09-20\"],"
                + "\"probed_at\":\"2026-09-25T10:00:00Z\","
                + "\"as_value\":1"
                + "}";
        ResponseEntity<String> tampered = rest.exchange(url("/api/v1/band/available-dates"),
                HttpMethod.POST, new HttpEntity<>(tamperedBody, headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, tampered.getStatusCode(),
                "🛑 摘掉权限码不等于放弃越权防护：请求体带派生键时仍必须 403（由派生字段拦截器承担）。"
                        + "实际: " + tampered.getStatusCode());
        assertEquals(2001, code(tampered));
    }

    // ==================================================================
    // 🔴 2026-09-27（批次五 · A-9）：E5「缺第三层」安全评审的【出参方向】补课
    // ==================================================================

    /**
     * <b>A-9 安全评审补课</b>：E5 端点的设计前提是"<b>无第三层（功能权限）+ 无受限出参</b>"，
     * 而此前唯一守护 {@code legitimate_client_probe_without_derived_keys_must_reach_200}
     * <b>只覆盖入参方向</b>（派生键入参被拒）。
     *
     * <h2>🛑 为什么"入参方向被覆盖"不等于"出参方向安全"</h2>
     * 两者是<b>两条独立的失败模式</b>，由不同代码承担：
     * <ul>
     *   <li><b>入参</b>：请求体带 {@code as_value} → {@code DerivedVisibilityInterceptor}
     *       （请求侧）→ 403。被 {@code client_naming_derived_fields_by_param_or_body_is_denied}
     *       与上一条用例守护。</li>
     *   <li><b>出参</b>：响应体<b>多出</b>了受限字段 → {@code DerivedResponseBodyAdvice}
     *       （响应侧）→ 摘除。<b>没有任何一条用例断言过</b> "E5 的 200 响应里不含受限字段"。</li>
     * </ul>
     * 若某天 E5 的实现"顺手"在响应里带上 {@code gap_reason}（例如为了客户端提示未同步原因），
     * 那么：请求侧仍然 403（入参用例照绿）、端点仍然 200（合法探测用例照绿），
     * 而受限字段已经<b>下发给了客户</b> —— 这正是 A-9 登记的风险原文
     * 「无第三层 + 有敏感字段 = 裸端点」。本用例就是那条原文的机械防线。
     *
     * <h2>🛑 为什么用"契约出参字段集"作为判据锚点</h2>
     * 不看实现、只看契约：{@code BandAvailableDatesData} 的 5 个属性
     * （{@code probe_id} / {@code retention_window_days} / {@code pull_start_date} /
     * {@code earliest_available} / {@code latest_available}）<b>全部</b>
     * {@code x-visible-to: [client]}。故：
     * <ol>
     *   <li>响应的 {@code data} 里<b>不得出现</b>任何 ③④ 受限字段
     *       （{@code DerivedFields.derivedAndAdjacent()} 是唯一真相源，不手抄清单）；</li>
     *   <li>且<b>反向自证</b>：响应的键集必须真的<b>非空</b>且含契约点名的字段 ——
     *       否则"没有受限字段"可能只是"响应是空壳"（那会让本条退化恒绿）。</li>
     * </ol>
     */
    @Test
    @DisplayName("🔴 A-9 出参方向：E5 合法探测的 200 响应里【不含任何】受限字段（③④ + 判定链列）")
    void e5_success_response_carries_no_client_forbidden_field() {
        HttpHeaders headers = jsonHeaders(token("client", "own_store"));
        String body = "{"
                + "\"device_id\":\"dev-e5-outparam-1\","
                + "\"history_type\":\"STEP\","
                + "\"valid_history_dates\":[\"2026-09-20\",\"2026-09-21\",\"2026-09-23\"],"
                + "\"probed_at\":\"2026-09-25T10:00:00Z\","
                + "\"last_synced_date\":\"2026-09-19\","
                + "\"first_binding\":false"
                + "}";
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/band/available-dates"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "前置：合法客户端探测必须 200（否则本用例判据不成立）；"
                        + "实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode d = data(resp);
        assertNotNull(d, "200 但 data 为空 —— 空壳响应会让本用例退化恒绿；实际 body=" + resp.getBody());

        // ① 出参里不得出现任何 ③④ 受限字段 —— 真相源是 DerivedFields，不是手抄清单。
        //    🛑 用 predicates 的 has() 语义（对显式 null 也 true）：契约 §3.2 要求"整体不存在"
        //    （不是 null、不是空串），故 has() 恰好是正确判据。
        for (String f : DerivedFields.derivedAndAdjacent()) {
            assertFalse(d.has(f),
                    "🛑 E5 的 200 响应里出现了受限字段 " + f + " —— 这正是 A-9 登记的风险："
                            + "本端点【刻意不带】端点级权限注解（契约 x-callable-roles: [client]，"
                            + "而注册表不登记 client ⇒ 贴任何码都会让合法客户端 403），"
                            + "其安全性【全部】建立在『不下发任何受限字段』这一前提上。"
                            + "一旦响应多出受限字段，它就变成『无第三层 + 有敏感字段』的裸端点。"
                            + "实际 data=" + d);
        }
        // gap_reason 单列点名（它是 E5 相关域里最容易被"顺手带上"的一个，
        // 且属契约 §3.1 注 ③ 客户恒 403 的硬约束字段 —— 配置不得放开）。
        assertFalse(d.has("gap_reason"),
                "🛑 E5 响应里出现了 gap_reason —— 它是契约 §3.1 中客户【恒 403】的 ③ 字段"
                        + "（硬约束，配置不得放开），且 E5 的出参 schema 里根本没有它。"
                        + "实际 data=" + d);

        // ② 反向自证：响应的键集必须非空且含契约点名的字段 ——
        //    否则"没有受限字段"可能只是"响应是空壳"。
        assertTrue(d.fieldNames().hasNext(),
                "🛑 E5 响应 data 是空对象 —— 本用例的『不含受限字段』会退化成恒绿。"
                        + "实际 data=" + d);
        for (String must : List.of("probe_id", "retention_window_days", "retention_window_probed")) {
            assertTrue(d.has(must),
                    "🛑 E5 响应缺字段 " + must + "（契约 BandAvailableDatesData / 扩展位）—— "
                            + "空壳式响应不该被本用例当成『安全』。实际 data=" + d);
        }

        // ③ 出参字段集与契约声明的 5 个属性对齐 —— 多出的必须是**已登记的扩展位**。
        //    🛑 本断言在 2026-09-27（A-9 补课）首次运行时【抓到一处真实差异】：
        //       实现下发了 history_type，而契约 BandAvailableDatesData 只声明 5 个属性
        //       （history_type 在契约里只出现在【入参】BandAvailableDatesRequest）。
        //       它不是受限字段（不属 ③④），但是一个「契约未声明的出参」——
        //       按本项目「字段一个不多一个不少」的纪律，必须【显式登记】而非静默放过。
        //    🛑 故这里用闭合白名单 + 逐项理由：新增扩展位必须来此处登记，
        //       登记本身就是「为什么它可以出站」的一次书面决定。
        Set<String> declared = Set.of("probe_id", "retention_window_days", "pull_start_date",
                "earliest_available", "latest_available");
        Map<String, String> allowedExtensions = Map.of(
                // 扩展位 ①：显式暴露「探测成败」，使客户端无需字符串比较即可分辨真值与 TBD。
                // 契约的 retention_window_days 本身仍严格按契约输出（数值 / "TBD"）。
                "retention_window_probed", "探测成败布尔位（BandProbeResult javadoc 已登记）",
                // 扩展位 ②：回显入参 history_type，便于客户端把响应与请求配对。
                // ⚠️ 契约【未】在出参 schema 声明它 —— 它是实现侧扩展，故必须在此登记。
                "history_type", "回显入参 history_type 以便客户端配对（契约出参未声明，属实现侧扩展）");
        List<String> unexpected = new ArrayList<>();
        d.fieldNames().forEachRemaining(name -> {
            if (!declared.contains(name) && !allowedExtensions.containsKey(name)) {
                unexpected.add(name);
            }
        });
        assertTrue(unexpected.isEmpty(),
                "🛑 E5 响应出现了【未登记】的字段: " + unexpected + "。\n"
                        + "契约 BandAvailableDatesData 只声明 5 个属性；已登记的扩展位仅 "
                        + allowedExtensions.keySet() + "。\n"
                        + "🛑 新增出参字段必须显式登记到本用例的 allowedExtensions —— "
                        + "未登记的新字段如果恰好承载了内部口径，就会绕过全部可见性审查。"
                        + "实际 data=" + d);
    }

    // ==================================================================
    // 验收② 的"反向自证"：staff 必须能拿到派生字段
    // ==================================================================

    @Test
    @DisplayName("🛑 反向自证：调理师(meridian)调 E4 → 200 且【真的返回了】派生字段")
    void staff_receives_the_derived_payload_so_the_block_is_client_specific() {
        // 这一条是本类最重要的一条。没有它，"客户被 403"无法区分
        // 「因为他是客户」（正确）与「这个端点对谁都拒」（错误但会通过验收②）。
        ResponseEntity<String> resp = get("/api/v1/customers/C-1/band/derived", token("meridian", "own_store"));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "经络师应能读到 E4 派生结果（契约 x-callable-roles: [therapist, meridian, admin]）；"
                        + "实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode d = data(resp);
        assertNotNull(d);
        assertTrue(d.has("as_value"),
                "staff 的响应里没有 as_value —— E4 对 staff 也失效了，客户被拒就不再是『客户专属』的；"
                        + "实际 data=" + d);
        assertTrue(d.has("effect_verdict"), "staff 响应缺少 effect_verdict；实际 data=" + d);
        assertTrue(d.has("refund_eligibility"), "staff 响应缺少 refund_eligibility；实际 data=" + d);

        // 且这些值真的来自配置口径 —— AS 满分应为 1.000（桩入参三维全 1.0 × config #5 权重）
        assertEquals(0, d.path("as_value").decimalValue().compareTo(java.math.BigDecimal.ONE),
                "staff 拿到的 AS 与配置口径算出的值不一致；实际: " + d.path("as_value"));
    }

    @Test
    @DisplayName("E4 的契约自描述端点：staff 200，且【不含任何阈值数字】")
    void e4_contract_self_description_omits_threshold_numbers() {
        ResponseEntity<String> resp = get("/api/v1/customers/C-1/band/derived/contract",
                token("hq", "all"));
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "总部角色应能读契约自描述；实际: " + resp.getStatusCode() + " body=" + resp.getBody());

        String body = resp.getBody();
        for (String leaked : List.of("0.571", "0.286", "0.143", "0.80", "0.75", "0.45")) {
            assertFalse(body.contains(leaked),
                    "自描述里出现了口径数值 " + leaked + " —— 它会变成配置值的第二份副本，"
                            + "配置改了它不改时不会报错");
        }
        assertEquals(true, data(resp).path("thresholds_from_config").asBoolean(),
                "自描述应声明口径来自 config，使『这些数来自配置』成为可读的运行时事实");
    }

    // ==================================================================
    // 验收③ · 出口：派生字段不出现在任何响应体（含反向注入探针）
    // ==================================================================

    @Test
    @DisplayName("🛑 验收③（反向注入）：探针端点故意下发全部派生字段 —— 客户端响应里必须【整体不存在】")
    void derived_fields_are_stripped_from_a_client_response_that_tries_to_emit_them() {
        ResponseEntity<String> resp = get("/api/v1/_probe/derived-emission", token("client", "own_store"));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "探针端点是合法请求，应 200（不是 403）—— 客户被拒的是【索取】，"
                        + "不是所有响应都不许返回；实际: " + resp.getStatusCode());
        JsonNode d = data(resp);

        // 「整体不存在」——用 has() 而不是 isNull()：has() 对显式 null 也返回 true，
        // 故 assertFalse(has(x)) 恰好证明"不是 null、不是空串、不是 ''"。
        for (String f : BAND_DERIVED_FIELDS) {
            assertFalse(d.has(f),
                    "④ 派生字段 " + f + " 出现在客户端响应体里 —— 契约 §3.2 要求『整体不存在』"
                            + "（不是 null、不是空串）。若置为 null，客户端能从字段存在性推断"
                            + "『这个接口有 AS 判定，只是对我不显示』；实际 data=" + d);
        }
        for (String f : OTHER_CLIENT_FORBIDDEN) {
            assertFalse(d.has(f),
                    "客户不可见字段 " + f + " 出现在响应体里；实际 data=" + d);
        }
        // 嵌套对象与数组元素里的同名键也必须被摘到（递归）
        assertFalse(d.path("nested").has("mcid"),
                "嵌套对象里的 mcid 未被摘到 —— 换一层包装就能带出去；实际 nested=" + d.path("nested"));
        assertFalse(d.path("items").isArray() && d.path("items").size() > 0
                        && d.path("items").get(0).has("confidence"),
                "数组元素里的 confidence 未被摘到；实际 items=" + d.path("items"));

        // 🛑 精准性反向验证：as_value_ops（运营口径，另一个字段）必须【活下来】。
        // 若兜底退化成"包含 as_value 即摘"，它会被误摘 —— 误拦比漏拦更糟，
        // 因为它会逼着维护者去放宽整张表。
        assertTrue(d.has("as_value_ops"),
                "as_value_ops 被连带摘掉了 —— 说明匹配退化成了『包含即命中』（误拦）；"
                        + "实际 data=" + d);
        // 客户本就可见的 ①② 字段必须完好
        assertEquals(8620, d.path("step_count").asInt(),
                "① 手环原始数据对客户可见，不该被摘；实际 data=" + d);
        assertEquals("张三", d.path("customer_name").asText());
        // 信封四字段不得因摘除而受损（trace_id 是 @JsonInclude(ALWAYS) 的）
        assertTrue(hasText(root(resp).path("trace_id")),
                "摘除派生字段后 trace_id 丢失了 —— 契约 §2.0 要求信封四字段不得增删；"
                        + "实际信封=" + root(resp));
    }

    @Test
    @DisplayName("🛑 验收③ 的同一条探针给 staff：派生字段必须【原样保留】")
    void the_same_probe_keeps_derived_fields_for_staff() {
        // 出口兜底必须按【角色】判，不能全局剥 —— 全局剥会把 E4 一起废掉，
        // 且症状是"接口 200 但字段没了"，很容易被误诊为契约不一致。
        ResponseEntity<String> resp = get("/api/v1/_probe/derived-emission", token("therapist", "own_store"));

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "staff 调探针应 200");
        JsonNode d = data(resp);
        for (String f : BAND_DERIVED_FIELDS) {
            assertTrue(d.has(f),
                    "staff 响应里 " + f + " 被剥掉了 —— 出口兜底不该按字段全局剥；实际 data=" + d);
        }
        assertTrue(d.path("nested").has("mcid"), "staff 的嵌套字段也被剥了；实际 nested=" + d.path("nested"));
    }

    @Test
    @DisplayName("验收③：客户成功响应（无派生字段）信封完好，字段一个不多一个不少")
    void clean_client_responses_are_byte_intact() {
        // 用真实生产端点（题库口径元信息，免权限）验"没触发摘除时响应不被改写"——
        // 兜底若把不涉及的响应也改写一遍，会引入一类难以定位的字段漂移。
        ResponseEntity<String> resp = get("/api/v1/scale/profile", token("client", "own_store"));
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "该端点免权限，客户应可读；实际: " + resp.getStatusCode() + " body=" + resp.getBody());

        JsonNode d = data(resp);
        assertTrue(d.has("item_min") && d.has("total_max"),
                "响应体的正常字段缺失 —— 兜底改写了不该改的响应；实际 data=" + d);
        assertFalse(d.has("age_groups") && d.path("age_groups").isEmpty(),
                "age_groups 被清空了 —— 兜底不应触碰非派生字段");
        assertTrue(hasText(root(resp).path("trace_id")), "信封 trace_id 缺失");
        assertTrue(hasText(root(resp).path("message")), "信封 message 缺失");
        assertEquals(0, code(resp), "成功响应 code 应为 0");
    }

    // ==================================================================
    // 防线独立性：两条路径不能被互相替代
    // ==================================================================

    @Test
    @DisplayName("🛑 换账号复验：同一探针请求，client 与 therapist 结果【必须不同】")
    void same_request_two_roles_differ_proving_role_based_filtering() {
        String path = "/api/v1/_probe/derived-emission";
        ResponseEntity<String> asClient = get(path, token("client", "own_store"));
        ResponseEntity<String> asStaff = get(path, token("therapist", "own_store"));

        assertEquals(HttpStatus.OK, asClient.getStatusCode());
        assertEquals(HttpStatus.OK, asStaff.getStatusCode());

        // 把两者放一起看，才能区分"按角色过滤"与"一律剥光/一律放行"：
        // 只看 client 的用例时，"按角色过滤"与"一律剥光"完全无法区分。
        assertFalse(data(asClient).has("as_value"), "client 侧未被摘除");
        assertTrue(data(asStaff).has("as_value"), "staff 侧被误摘");
        assertFalse(asClient.getBody().equals(asStaff.getBody()),
                "两个角色的响应体完全相同 —— 说明根本没按角色判，"
                        + "而是要么都剥、要么都不剥");
    }

    @Test
    @DisplayName("🛑 匿名（无 token）调探针：按客户处理，派生字段一律不下发")
    void anonymous_is_treated_as_a_client_and_gets_nothing_derived() {
        // 匿名走"不建立上下文"（fail-closed），role=null → isClientLike(null)=true。
        // 若匿名被当作"未知角色"跳过过滤，就得到一个可被利用的组合：
        // 把 Authorization 头去掉，反而看见了派生字段。
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/_probe/derived-emission"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "探针免权限，匿名应 200（不是 403）；实际: " + resp.getStatusCode() + " body=" + resp.getBody());
        JsonNode d = data(resp);
        for (String f : BAND_DERIVED_FIELDS) {
            assertFalse(d.has(f),
                    "匿名请求拿到了派生字段 " + f + " —— 去掉 Authorization 头即可绕过出口防线；"
                            + "实际 data=" + d);
        }
        assertTrue(d.has("customer_name"), "非派生字段不该被摘；实际 data=" + d);
    }

    // ==================================================================
    // 测试专用探针端点（故意下发派生字段，用于反向验证出口兜底）
    // ==================================================================

    /**
     * 探针控制器本体。
     *
     * <p>⚠️ 它是 {@code @TestConfiguration} 的内嵌类，只经 {@code @Import} 注册，
     * 不进组件扫描 —— 故它不会出现在生产包，也不会污染其他测试上下文。
     */
    @RestController
    @RequestMapping("/api/v1/_probe")
    static class ProbeController {

        @GetMapping("/derived-emission")
        public Result<Object> derivedEmission() {
            Map<String, Object> d = new LinkedHashMap<>();

            // ④ 派生结果五项（契约 BandDerivedData）
            d.put("a3_applicable", true);
            d.put("a3_value", 1.0);
            d.put("as_value", 1.000);
            d.put("effect_verdict", "E3稳定");
            d.put("refund_eligibility", true);

            // ③ 缺口原因 + 判定链落库列里的派生结论
            d.put("gap_reason", "not_worn");
            d.put("adherence_state", "达标");
            d.put("adherence_score", 1.000);
            d.put("improvement_rate", 0.333);
            d.put("evidence_snapshot", "{\"note\":\"内部\"}");

            // 嵌套对象与数组 —— 递归摘除的观测点
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("mcid", 3);
            nested.put("confidence", 0.9);
            nested.put("basis_note", "可读");
            d.put("nested", nested);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("confidence", 0.8);
            item.put("label", "可读");
            d.put("items", List.of(item));

            // 🛑 必须【活下来】的两个字段：
            //   as_value_ops = 运营口径的另一个字段（"包含即命中"的假阳性陷阱）
            //   step_count / customer_name = 客户本就可见的 ① 与普通字段
            d.put("as_value_ops", 0.72);
            d.put("step_count", 8620);
            d.put("customer_name", "张三");

            return Result.ok(d, org.slf4j.MDC.get("traceId"));
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = jsonHeaders(jwt);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders jsonHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private JsonNode root(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON 信封: " + resp.getBody(), e);
        }
    }

    private int code(ResponseEntity<String> resp) {
        return root(resp).path("code").asInt();
    }

    private JsonNode data(ResponseEntity<String> resp) {
        return root(resp).path("data");
    }

    private static boolean containsText(JsonNode array, String text) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode n : array) {
            if (text.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static boolean hasText(JsonNode n) {
        return n != null && !n.isMissingNode() && !n.isNull() && !n.asText().isBlank();
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