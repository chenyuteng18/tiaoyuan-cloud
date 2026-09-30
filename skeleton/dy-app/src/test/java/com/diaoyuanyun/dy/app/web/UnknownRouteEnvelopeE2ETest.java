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
 * 这正是它在全量 1214 个测试全绿的情况下长期存活的原因。
 *
 * <h2>🛑 2026-10-01 同族枚举补齐（第 73 条的第七例：修一处同族必须枚举全族）</h2>
 * 第 73 条修完 404 后，按本仓第 61/73 条的纪律，对「请求在到达业务逻辑之前就不合法」的<b>各类</b>
 * 逐一做启动后真请求实测，结果抓出<b>同族的另外两个成员也漏在 {@code handleOther} 里</b>：
 * <pre>
 *   DELETE /api/v1/doc-templates           → 500 · 9001「HttpRequestMethodNotSupportedException」 ← 应 405
 *   GET    /api/v1/customers               → 500 · 9001「HttpRequestMethodNotSupportedException」 ← 应 405
 *   POST   /api/v1/demo/order (text/plain) → 500 · 9001「HttpMediaTypeNotSupportedException」     ← 应 415
 * </pre>
 * ⇒ 若只修被报出来的那一个（404），族里另外两员会继续以 <b>500 姿态</b>告警噪音 ——
 * <b>「同族」不是修辞，是必须逐条跑出来的清单。</b>
 *
 * <h2>🛑 第四员（406）是被【启动日志】抓出来的 —— 这条教训比缺陷本身更重要</h2>
 * 首轮枚举时我把 406 判为"Spring 裸默认、状态码本来就对、无需处理"。
 * 复查启动日志才发现它<b>同样走 handleOther ⇒ 打 ERROR + 满堆栈</b>：
 * <b>状态码碰巧是 406，但留痕级别错</b>，把 {@code Accept} 写错的客户端噪音计入了 error 级告警。
 * ⇒ <b>判据：「HTTP 状态码」与「留痕级别」是两件事，只断言状态码会漏掉一半。</b>
 * 本类现覆盖：404（①②③）、405（⑤）、415（⑥）、406（⑦）、无 code 一致性（⑧）、对照（④）。
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

    // ─────────────────────────────────────────────────────────────────────
    // 同族枚举补齐（2026-10-01）：第 73 条修完 404 后，按「修一处同族必须枚举全族」
    // 对「请求到达业务逻辑之前就不合法」的各类做了真请求实测，抓出另外两个成员
    // （HttpRequestMethodNotSupportedException / HttpMediaTypeNotSupportedException）
    // 同样漏在 handleOther 里 ⇒ 也是 500 · 9001。下面两条把它们钉住。
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🛑 同族：方法不支持 → 405（不是 500·9001）")
    void method_not_supported_is_405_not_500() throws Exception {
        // /api/v1/doc-templates 只有 GET 与 POST（契约 40 path 之一，真实存在）
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/doc-templates"), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);

        assertEquals(405, resp.getStatusCode().value(),
                "路径存在但方法不支持 ⇒ 405 METHOD_NOT_ALLOWED。"
                        + "实测修复前是 500 · 9001「系统异常: HttpRequestMethodNotSupportedException」—— "
                        + "把「客户端用错方法」谎报成「服务端故障」，会让 SDK 对永不成功的请求做退避重试。"
                        + "实际 status=" + resp.getStatusCode().value() + " body=" + resp.getBody());
        assertNotEquals(9001, codeOf(resp),
                "🛑 绝不能是 9001 —— 契约 9001 的 trigger 逐字是「服务端异常」。实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("🛑 同族：媒体类型不支持 → 415（不是 500·9001）")
    void media_type_not_supported_is_415_not_500() throws Exception {
        // /api/v1/demo/order 走匿名通道（不挂权限门禁）⇒ 能真正到达「媒体类型协商」这一步。
        // 若换成需鉴权的端点，会在更早的 filter 里先返回 403，测不到本条要测的东西。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/demo/order"), HttpMethod.POST,
                new HttpEntity<>("x", headers), String.class);

        assertEquals(415, resp.getStatusCode().value(),
                "Content-Type 不被该端点接受 ⇒ 415 UNSUPPORTED_MEDIA_TYPE。"
                        + "实测修复前是 500 · 9001「系统异常: HttpMediaTypeNotSupportedException」。"
                        + "实际 status=" + resp.getStatusCode().value() + " body=" + resp.getBody());
        assertNotEquals(9001, codeOf(resp),
                "🛑 绝不能是 9001。实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("🛑 同族：Accept 不可接受 → 406（且【不得】落 error 级留痕）")
    void not_acceptable_is_406_not_error_logged() throws Exception {
        // 这一条是被【启动日志】抓出来的，不是被状态码抓出来的 —— 实测它的 HTTP 状态
        // 本来就已经是 406，但走的仍是 handleOther ⇒ 打了 ERROR + 满堆栈（4xx 污染 error 告警）。
        // ⇒ 只断言状态码会漏掉它；本用例的核心断言是「不得落 9001 / 不得带 code」，
        //    而留痕级别的机械守护由本类第 ⑦ 例与启动日志巡检共同承担。
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.TEXT_XML));

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/demo/me"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(406, resp.getStatusCode().value(),
                "Accept 不可接受 ⇒ 406。实际 status=" + resp.getStatusCode().value());
        assertNotEquals(9001, codeOf(resp),
                "🛑 不得带 9001 —— 曾落 handleOther，虽状态码碰巧是 406，但留痕打了 ERROR + 满堆栈，"
                        + "把 Accept 头写错的客户端噪音计入 error 级告警指标。实际 body: " + resp.getBody());
    }

    @Test
    @DisplayName("🛑 同族一致性：三类「不可路由」响应都不得带 code（契约未为其定义码）")
    void unroutable_family_must_not_carry_any_code() throws Exception {
        int ghost = codeOf(rest.getForEntity(url(GHOST), String.class));
        int notAllowed = codeOf(rest.exchange(url("/api/v1/doc-templates"), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class));
        HttpHeaders plain = new HttpHeaders();
        plain.setContentType(MediaType.TEXT_PLAIN);
        int badMedia = codeOf(rest.exchange(url("/api/v1/demo/order"), HttpMethod.POST,
                new HttpEntity<>("x", plain), String.class));
        HttpHeaders xml = new HttpHeaders();
        xml.setAccept(java.util.List.of(MediaType.TEXT_XML));
        int notAcceptable = codeOf(rest.exchange(url("/api/v1/demo/me"), HttpMethod.GET,
                new HttpEntity<>(xml), String.class));

        // 契约 §2.0 码表只有 1001/1002/2001-2004/3001/4001/4002/5001/6001/9001 —— 没有 405/415/406。
        // 既然契约从未定义这些情形，任何自造 code 都会让客户端落进契约未声明的分支；
        // 而 1001 的 trigger 逐字是「参数类型 / 必填 / 约束不满足」（说的是【参数的值】），
        // 拿它去答 405/415/406 属于 code 语义挪用。⇒ 四类行为必须一致：回裸状态码、不带 code。
        assertEquals(0, ghost, "未知路由不得带 code（既有自证用例依赖该分辨信号）");
        assertEquals(0, notAllowed, "405 不得带 code —— 契约没有 405 对应的码");
        assertEquals(0, badMedia, "415 不得带 code —— 契约没有 415 对应的码");
        assertEquals(0, notAcceptable, "406 不得带 code —— 契约没有 406 对应的码");
    }
}