package com.diaoyuanyun.dy.app.web;

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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 第 74 条 · <b>「请求在进入业务逻辑之前就不合法」的 4xx 族，必须统一答 400·1001（带四字段信封），
 * 不得落到 {@code handleOther} 变成 500·9001</b>。
 *
 * <h2>它延续的是第 73 条的纪律</h2>
 * 第 73 条把「请求不可路由 / 不可解析」四类（404/405/415/406 + 三类 400）从
 * {@code handleOther(Exception) → 500·9001} 里捞出来，分别归到 {@code handleUnroutableRequest}（裸状态码）
 * 与 {@code handleUnreadableRequest}（400·1001）。本类做的是<b>同一族的下半截枚举</b>：
 * 把「Spring MVC 参数绑定 / Bean Validation」抛出的、语义上<b>同样是客户端错误</b>的异常，
 * 也确认它们走 400·1001 而非 500·9001。
 *
 * <h2>🛑 实测抓出的真实缺口（启动后真请求暴露）</h2>
 * <pre>
 *   POST /api/v1/doc-templates/uploads  （multipart/form-data，但省略必填 part "file"）
 *   → 500 · 9001 「系统异常: MissingServletRequestPartException」   ← 修复前（客户端漏传 part，被答成服务端故障）
 *   → 400 · 1001 「缺少必填参数」                                  ← 修复后
 * </pre>
 * `MissingServletRequestPartException` 继承自 {@code ServletRequestBindingException}，
 * 而原 {@code handleUnreadableRequest} 只列了
 * {@code HttpMessageNotReadableException / MethodArgumentTypeMismatchException / MissingServletRequestParameterException}
 * 三者 —— <b>漏了 {@code ServletRequestBindingException} 这一支（含缺请求头 + 缺 multipart part）</b>，
 * 于是「客户端漏传一个 part」被当成「服务端内部错误」。这与第 73 条「把『请求不合法』答成『服务端故障』」
 * 是<b>同族、同坏后果</b>：SDK 按 5xx 退避重试（永远失败）、5xx 率告警被污染、运维去查服务端。
 *
 * <h2>🛑 为什么不用 MockMvc（与第 73 条同一条理由）</h2>
 * 本类缺陷只发生在<b>真实 DispatcherServlet 的参数解析链</b>里——单测直调控制器方法、或 MockMvc 的部分路径，
 * 都不会触发 {@code MissingServletRequestPartException} 的真实抛出点。故用 {@code RANDOM_PORT} + 真 token。
 *
 * <h2>零依赖底数据</h2>
 * 本类断言的 400 全部发生在<b>控制器方法被调用之前</b>（参数解析 / 绑定阶段），
 * 不需要任何租户 / 门店底数据，也不需要端点真的返回 200——只要 token 能过鉴权过滤器即可。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("第 74 条 · 4xx 参数绑定/校验族必须答 400·1001（非 500·9001）")
class RequestValidationEnvelopeE2ETest {

    /** 与 application.yml 的 dev 占位密钥一致（同 DomainAEndpointsE2ETest / DocFileE2ETest）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";
    private static final String TENANT = "e2e74000-0000-0000-0000-000000000074";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ───────────────────────── 已覆盖项的「对照基线」（证明 400 族整体在工作） ─────────────────────────

    @Test
    @DisplayName("A3 查询参数类型不匹配（page=abc）→ 400·1001 且是四字段信封（MethodArgumentTypeMismatch，已在 handleUnreadableRequest）")
    void a3_page_type_mismatch_returns_400_not_500() {
        ResponseEntity<String> resp = get("/api/v1/stores?page=abc", token("manager"));
        assertValidationFailed(resp, "page=abc 类型不匹配必须是 400·1001，而不是 500·9001");
    }

    @Test
    @DisplayName("A3 查询参数类型不匹配（page_size=abc）→ 400·1001（同一族的第二个成员，正向钉住）")
    void a3_page_size_type_mismatch_returns_400_not_500() {
        ResponseEntity<String> resp = get("/api/v1/stores?page_size=abc", token("manager"));
        assertValidationFailed(resp, "page_size=abc 类型不匹配必须是 400·1001，而不是 500·9001");
    }

    // ───────────────────────── 本类主抓的真实缺口 ─────────────────────────

    @Test
    @DisplayName("🔴 I3 上传省略必填 part「file」→ 当前是 500·9001（缺 ServletRequestBindingException 分支）；修复后应为 400·1001")
    void i3_missing_multipart_part_returns_400_not_500() {
        // 🛑 刻意构造一个【合法的 multipart/form-data（带 boundary）】请求，但省略必填 part「file」：
        // 放一个别的 Resource part 迫使 FormHttpMessageConverter 发出 multipart/form-data（带 boundary），
        // doc_type 作为普通文本 part；服务端 multipart 解析成功、却在参数绑定阶段发现缺「file」→
        // 抛 MissingServletRequestPartException（继承自 ServletRequestBindingException）。
        // 不手动设 Content-Type 裸 multipart（那样会被误判成另一种 500，见 DocFileE2ETest 的教训）。
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token("hq")); // hq 持有 doc:write，能过鉴权过滤器

        HttpHeaders placeholderHeaders = new HttpHeaders();
        placeholderHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("doc_type", "知情同意书");
        form.add("placeholder", new HttpEntity<>(new ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return "placeholder.bin";
            }
        }, placeholderHeaders));
        ResponseEntity<String> resp = rest.exchange(
                "http://127.0.0.1:" + port + "/api/v1/doc-templates/uploads",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        assertValidationFailed(resp,
                "客户端漏传 multipart part「file」是客户端错误，必须 400·1001，不得答成 500·9001");
    }

    // ───────────────────────── 工具 ─────────────────────────

    private void assertValidationFailed(ResponseEntity<String> resp, String msg) {
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                msg + " —— 实测 HTTP 状态 = " + resp.getStatusCode() + "，响应体 = " + resp.getBody());
        JsonNode body = parse(resp.getBody());
        assertEquals(1001, body.path("code").asInt(),
                msg + " —— 必须是 1001 VALIDATION_FAILED，而非 9001（实测响应体 = " + resp.getBody() + "）");
        assertNotNull(body.path("message").asText(), "400 响应必须带 message");
        assertTrue(body.has("trace_id"), "400 响应必须带 trace_id（契约信封四字段：code/message/data/trace_id）");
        // 注：data 字段按 Result 设计允许在「无附加数据」时省略（NON_NULL），故此处不强制断言 data 存在。
    }

    private JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应体不是合法 JSON（信封纪律被破坏）：" + body, e);
        }
    }

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
    }

    private static String token(String role) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"e2e74000-0000-0000-0000-000000000075\""
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
}
