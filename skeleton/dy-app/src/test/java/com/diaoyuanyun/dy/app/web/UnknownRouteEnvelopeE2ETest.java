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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 第 73 条 · <b>未知路由</b>的真请求回归：{@code 404}（不是 {@code 500 · 9001}），
 * 且<b>刻意不带 {@code code} 字段</b>（这个「不带」是被一条既有自证用例依赖的分辨信号）。
 *
 * <h2>它钉住的缺陷（启动后真请求实测暴露）</h2>
 * <pre>
 *   GET /api/v1/definitely-not-exist
 *   → 500 · 9001 「系统异常: NoResourceFoundException」   ← 修复前
 *   → 404（无 body / 无 code）                            ← 修复后
 * </pre>
 * Spring Boot 3.2+ 对「无处理器匹配」抛 {@code NoResourceFoundException}，
 * 它此前落到 {@code GlobalExceptionHandler#handleOther} ⇒ 被当作<b>服务端内部错误</b>。
 *
 * <h2>🛑 为什么修复形态是「404 且不带 code」，而不是「404 · 3001」</h2>
 * <ol>
 *   <li><b>契约不覆盖未知路由</b>：契约 §2.0 的 {@code 404 → 3001 NOT_FOUND} 的 trigger 逐字是
 *       「资源不存在（<b>仅限本租户内确实不存在</b>）」—— 限定语指向<b>业务资源</b>（工单/客户/门店…）；
 *       「URL 拼错」不在其列。契约只定义 40 个 path，未知路由<b>不是契约端点</b>，
 *       故「响应必须是四字段信封」这条契约纪律也不适用于它。</li>
 *   <li><b>本仓已有一处测试钉住了这个设计预期</b>：
 *       {@code RefundWritePathMatrixE2ETest$SelfProof#the_self_proof_discriminates_a_nonexistent_path}
 *       逐字断言「不存在的路径<b>不得</b>返回 code=3001」—— 那条自证用例
 *       <b>正是靠「未知路径无 code」来区分「端点根本没实现」与「业务层查无此单」</b>，
 *       因为正向矩阵断言的就是 404：若两者无法区分，「端点没实现」会被误判成「角色被放行」。
 *       让未知路由也返回 3001，会让那条自证用例失去分辨力。<b>本类第 ③ 例把这个信号反过来钉住。</b></li>
 * </ol>
 *
 * <h2>🛑 为什么这仍然是真缺陷（不是「错误码不精确」）</h2>
 * 把「请求根本不合法」答成 5xx 有三个具体坏后果：
 * <ol>
 *   <li><b>端侧行为错</b>：SDK 拿到 5xx 会按「服务端故障」重试（退避 + 熔断），
 *       而这是<b>永远不会成功</b>的重试 —— 正确动作是让开发者改 URL；</li>
 *   <li><b>告警噪音</b>：监控上「5xx 率」是服务健康度核心指标，把每个 URL 笔误
 *       都计入 5xx，会让真实故障淹没在噪音里；</li>
 *   <li><b>排查方向被带偏</b>：消息把运维引去查服务端，而问题在 URL 里。</li>
 * </ol>
 * 这与 C-3 修过的 {@code HttpMessageNotReadableException}（请求体类型错被答成 500 后改为 400·1001）
 * 是<b>同族</b>：凡「请求在进入业务逻辑之前就不合法」的各类，都不该落到 {@code handleOther}。
 *
 * <h2>🛑 为什么用真请求（{@code RANDOM_PORT}）而不是 MockMvc</h2>
 * 本缺陷<b>只在真实 DispatcherServlet 路由解析</b>里出现 ——
 * 单测直调控制器方法<b>不经过路由</b>，永远看不到 {@code NoResourceFoundException}。
 * 这正是它在全量 1217 个测试全绿的情况下长期存活的原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("第 73 条 · 未知路由信封（404、不带 code，不是 500·9001）真请求回归")
class UnknownRouteEnvelopeE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 契约里确定不存在的路径（刻意用 /api/v1 前缀，确保穿过全部 filter 而非被 Tomcat 直接拒）。 */
    private static final String GHOST = "/api/v1/definitely-not-a-route";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** 解析 body 里的 code；无 body / 非 JSON / 无 code 一律得 0（与既有自证用例同口径）。 */
    private int codeOf(ResponseEntity<String> resp) throws Exception {
        String b = resp.getBody();
        if (b == null || b.isBlank()) {
            return 0;
        }
        JsonNode root = MAPPER.readTree(b);
        return root.path("code").asInt(0);
    }

    @Test
    @DisplayName("GET 未知路径 → 404（不是 500·9001：路由不存在不是服务端异常）")
    void get_unknown_route_is_404_not_500() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity(url(GHOST), String.class);

        assertEquals(404, resp.getStatusCode().value(),
                "未知路径必须 404。曾落到 handleOther ⇒ 500 · 9001 —— 那会让 SDK 对 URL 笔误做永不成功的重试，"
                        + "并把噪音计入 5xx 告警指标。");
        assertNotEquals(9001, codeOf(resp),
                "🛑 绝不能是 9001 INTERNAL_ERROR —— 契约里 9001 的 trigger 逐字是「服务端异常」，"
                        + "而 URL 拼错与服务端故障是两件事。实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("POST 未知路径 → 404（不因 HTTP 方法不同而漂移）")
    void post_unknown_route_is_404_not_500() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(url(GHOST), HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertEquals(404, resp.getStatusCode().value(),
                "POST 未知路径同属「无处理器匹配」，必须与 GET 一致 —— "
                        + "若只在 GET 上生效，说明修复挂错了层");
        assertNotEquals(9001, codeOf(resp), "同样不得落到 9001。实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("🛑 边界：未知路径【不得】带 code=3001（既有自证用例依赖该分辨信号）")
    void unknown_route_must_not_carry_contract_not_found_code() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity(url(GHOST), String.class);

        assertNotEquals(3001, codeOf(resp),
                "🛑 未知路由【不得】返回 code=3001 —— 本仓 RefundWritePathMatrixE2ETest$SelfProof 的"
                        + "「端点真实存在」自证用例，正是靠「未知路径无 code」来区分"
                        + "「端点根本没实现」与「业务层查无此单」（正向矩阵断言的就是 404）。"
                        + "这里给出 3001 会让那条自证用例丧失分辨力（第 55 条）。"
                        + "契约 3001 的 trigger 逐字限定「仅限本租户内确实不存在」= 业务资源，不含 URL 拼错。"
                        + "实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("对照：真实存在的端点不得被误判为 404（防「把拒写成找不到」）")
    void real_endpoint_is_not_misreported_as_404() throws Exception {
        // A2 /auth/me 无 token：契约只声明 '200' 与 '401'（无匿名通道）⇒ 必须 401 · 1002
        ResponseEntity<String> resp = rest.getForEntity(url("/api/v1/auth/me"), String.class);

        assertEquals(401, resp.getStatusCode().value(),
                "已知端点绝不能落到 handleNoResource —— 把「未认证」谎报成「路径不存在」，"
                        + "客户端会以为端点不存在而去改 URL，比原缺陷更危险");
        assertEquals(1002, codeOf(resp),
                "契约 A2 只声明 200/401；1002 UNAUTHENTICATED 是这一档的正确码");
    }
}