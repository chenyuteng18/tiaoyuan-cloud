package com.diaoyuanyun.dy.web.observability;

import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 logs 支柱: <b>日志真的带上 trace / tenant 字段了吗</b>。
 *
 * <h2>为什么"MDC.put 成功"不等于"日志上有这个字段"</h2>
 * MDC 是一个线程本地的字典。值进了字典, 并不代表它出现在日志行上 ——
 * 只有当<b>日志格式（pattern）显式引用了那个键</b>时才会被渲染。
 * 所以本用例不看 MDC 字典, 而是<b>捕获真实渲染输出</b>
 * （测试期 root appender 写 {@code target/test-logs/dy-app.log}）,
 * 断言那行字里真的有 {@code traceId=xxx tenant=yyy method=POST path=/...}。
 *
 * <p>这是一个典型的"两种通过形态不等价": 断言 {@code MDC.get("traceId") != null} 是绿的,
 * 而运维打开日志文件却看不到 traceId —— 两个结论可以同时成立。故只认<b>渲染后的输出</b>。
 *
 * <p><b>取证边界</b>：这里的"日志文件"是<b>测试期 appender</b>（{@code logback-test.xml}）
 * 写出的, 用于证明"渲染链路上这些字段确实被引用、且值正确传递"。它与生产 appender 的
 * 落地路径是同一套 Logback 语义, 但不是同一份配置文件（生产那份是
 * {@code dy-web/src/main/resources/logback.xml} + 应用侧覆盖）。已在交付报告"未验证部分"登记。
 */
class ObservabilityMdcFilterTest {

    /**
     * 探针 logger 刻意用 {@link DyLoggers#APP} 而不是本测试类自己的 logger 名。
     *
     * <p>理由: 本用例要断言的是"日志行上渲染出了 traceId/tenant/method/path"，
     * 而这份渲染发生在<b>应用日志</b>这条路径上（DY_PATTERN + APP_FILE）。
     * 若用测试类自己的 logger 名，日志会走另一条绑定，测到的是另一条渲染路径 ——
     * 那条路径绿了，也不能说明应用日志里有这些字段。
     */
    private static final Logger log = LoggerFactory.getLogger(DyLoggers.APP);

    private static final Path APP_LOG = Path.of("target", "test-logs", "dy-app.log");

    @AfterEach
    void cleanUp() {
        // MDC / TenantContext 都是线程本地的: 不清会让别的用例看到残留值（假绿/假红的来源）
        MDC.clear();
        TenantContext.clear();
    }

    @Test
    void mdc_fields_reach_the_rendered_log_line() throws Exception {
        String marker = "mdcfields-" + System.nanoTime();

        String logText = runAndReadAppLog(marker, () -> {
            TenantContext.set("tenant-a", "staff-1", "role-1", "scope-1");
            MDC.put("traceId", "trace-for-test-0123456789abcdef");
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/order");
            req.addHeader("X-Entry-Channel", "h5");
            new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> log.info(marker));
        });

        String line = lineContaining(logText, marker);
        assertTrue(line.contains("traceId=trace-for-test-0123456789abcdef"),
                "日志行必须渲染出 traceId（这是'日志与 trace 可关联'的唯一证据）。实际: " + line);
        assertTrue(line.contains("tenant=tenant-a"),
                "日志行必须渲染出租户字段, 否则排障无法定位'哪个租户的哪次请求'。实际: " + line);
        assertTrue(line.contains("method=POST"), "日志行必须渲染出请求方法。实际: " + line);
        assertTrue(line.contains("path=/api/v1/order"), "日志行必须渲染出请求路径。实际: " + line);
    }

    /**
     * <b>log injection 防护</b>：带换行/回车的请求头若被原样写入 MDC, 攻击者可以在一条日志里
     * 伪造出完整的假日志行（例如伪装成"另一个租户发生了某事"）。本用例断言控制字符被净化。
     *
     * <h2>为什么注入点选 entry 头</h2>
     * 三个注入面（{@code dy.method} / {@code dy.path} / {@code dy.entry}）里, 只有
     * {@code dy.entry} 直接来自<b>客户端可控的请求头</b>，且不经过任何容器校验。
     * {@code dy.path} 也客户端可控, 但容器会把 URI 规范化, 换行的注入难度与形态不同 ——
     * 用 entry 头能精确地把"未净化的原始头字节"喂进来, 使这条防线真的被考验到。
     */
    @Test
    void control_characters_in_headers_cannot_forge_extra_log_lines() throws Exception {
        String marker = "loginject-" + System.nanoTime();
        String forged = "evil-tenant\n2026-01-01 00:00:00 INFO [x] [tenant=victim] FAKE AUDIT LINE";
        String forgedCr = "carriage\rreturn";

        String logText = runAndReadAppLog(marker, () -> {
            TenantContext.set("tenant-real", "s1", "r", "sc");
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");
            req.addHeader("X-Entry-Channel", forged);
            new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> log.info(marker));
        });

        // 一条日志必须只对应一行（文件里还有别的用例的行, 故只数含本次标记的行）
        long markerLines = logText.lines().filter(l -> l.contains(marker)).count();
        assertEquals(1, markerLines,
                "一条日志必须只对应一行 —— 控制字符被净化后不得再拆出第二行（否则可在日志里伪造结构）");

        String line = lineContaining(logText, marker);
        // 精确断言: 伪造内容只能作为 entry 字段【值的一部分】出现, 不得被提升为日志结构。
        // 注意不能简单断言 line 不含 "tenant=victim" —— 净化后它仍会作为 entry 的值文本存在
        // （值里的方括号不会凭空消失, 那是替换而非删除）。要证明的是它【没有逃出值的边界】:
        // 它必须出现在 "entry=" 之后, 而不是跑到了行首或别的字段位置上。
        int entryIdx = line.indexOf("entry=");
        assertTrue(entryIdx > 0, "前置条件失败: 行里必须有 entry 字段。实际: " + line);
        int positionInLine = line.indexOf("tenant=victim");
        assertTrue(positionInLine > entryIdx,
                "伪造的 'tenant=victim' 只能作为 entry 字段值的一部分出现, 不得出现在 entry= 之前"
                        + "（出现在之前 = 被提升为日志字段, 即注入成功）。实际: " + line);
        assertTrue(line.contains("evil-tenant_"),
                "换行必须被替换为下划线（保留可追溯性, 同时不可伪造结构）。实际: " + line);
        // 净化的第二个受害者: 换行后紧跟的内容必须留在同一行的 entry 字段里, 而不是跑到新行开头
        assertTrue(line.contains("entry=evil-tenant_2026-01-01"),
                "净化的第二个受害者: 换行后紧跟的内容必须留在同一行的 entry 字段里, 而不是跑到新行开头");

        // 回车单独覆盖一次: 只测 \n 会漏掉只过滤 \n 的实现, 而 \r 在部分采集器上同样会切行
        String crMarker = "crinject-" + System.nanoTime();
        String crLogText = runAndReadAppLog(crMarker, () -> {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");
            req.addHeader("X-Entry-Channel", forgedCr);
            new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> log.info(crMarker));
        });
        assertEquals(1, crLogText.lines().filter(l -> l.contains(crMarker)).count(),
                "回车同样不得拆行 —— 只过滤 \\n 的实现会在这条上变红");
        assertTrue(lineContaining(crLogText, crMarker).contains("entry=carriage_return"),
                "回车必须被替换为下划线。实际: " + lineContaining(crLogText, crMarker));
    }

    /**
     * 出口清理: 请求结束必须把本类写入的键全部摘掉, 否则线程池复用会让<b>下一个租户的请求</b>
     * 顶着上一个租户的 tenantId 写日志 —— 串租户的日志比没有日志更有害。
     */
    @Test
    void mdc_keys_are_removed_after_the_request_finishes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/p");
        TenantContext.set("tenant-tmp", "h5", "r", "sc");

        new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            // 请求体内: 这些键应当有值（保证"清理"不是靠一开始就没写入而侥幸通过）
            assertNotNull(MDC.get(ObservabilityMdcFilter.MDC_METHOD));
            assertNotNull(MDC.get(ObservabilityMdcFilter.MDC_TENANT));
        });

        assertNull(MDC.get(ObservabilityMdcFilter.MDC_METHOD), "请求结束后 dy.method 必须被清理");
        assertNull(MDC.get(ObservabilityMdcFilter.MDC_PATH), "请求结束后 dy.path 必须被清理");
        assertNull(MDC.get(ObservabilityMdcFilter.MDC_ENTRY), "请求结束后 dy.entry 必须被清理");
        assertNull(MDC.get(ObservabilityMdcFilter.MDC_TENANT), "请求结束后 tenantId 必须被清理");
    }

    /**
     * 租户字段必须来自 {@link TenantContext}（已由权威过滤器校验过的上下文）, 而不是
     * 直接读 {@code X-Tenant-Id} 头。本用例给一个与上下文不一致的头, 断言日志里出现的是
     * <b>上下文里的值</b> —— 这样"日志里的租户"与"实际写库的租户"永远是同一个。
     */
    @Test
    void tenant_field_comes_from_validated_context_not_from_the_raw_header() throws Exception {
        String marker = "tenantsource-" + System.nanoTime();

        String logText = runAndReadAppLog(marker, () -> {
            TenantContext.set("tenant-from-token", "s1", "r", "sc");
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/y");
            req.addHeader("X-Tenant-Id", "attacker-supplied-tenant");
            new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> log.info(marker));
        });

        String line = lineContaining(logText, marker);
        assertTrue(line.contains("tenant=tenant-from-token"),
                "日志里的租户必须来自已校验的 TenantContext。实际: " + line);
        assertFalse(line.contains("attacker-supplied-tenant"),
                "客户端可控的头不得进入日志字段。实际: " + line);
    }

    /** 无租户上下文（匿名请求）时不得写入空值, 且过滤器不得因此抛异常。 */
    @Test
    void anonymous_request_logs_without_tenant_and_does_not_fail() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        new ObservabilityMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { /* no-op */ });

        assertNull(MDC.get(ObservabilityMdcFilter.MDC_TENANT), "匿名请求不得写入空租户值");
    }

    /** 顺序约束: 必须晚于 TenantContextFilter(HIGHEST+100), 否则永远读不到租户上下文。 */
    @Test
    void filter_order_is_later_than_tenant_context_filter() {
        Order order = ObservabilityMdcFilter.class.getAnnotation(Order.class);
        assertNotNull(order, "必须显式声明 @Order, 否则执行顺序由组件扫描决定（不可预测）");
        assertTrue(order.value() > Ordered.HIGHEST_PRECEDENCE + 100,
                "必须晚于 TenantContextFilter(HIGHEST+100), 否则租户上下文尚未建立, 日志会永远缺 tenantId（静默丢字段）");
        assertTrue(order.value() > Ordered.HIGHEST_PRECEDENCE + 10,
                "必须晚于 TraceIdFilter(HIGHEST+10), 否则日志无法与 trace 关联");
    }

    // ---------------------------------------------------------------- helpers

    private interface ThrowingBody {
        void run() throws Exception;
    }

    /**
     * 跑一段请求体, 然后从应用日志文件里读回整份内容。
     *
     * <p>用文件而不是内存 appender, 是为了让"落地"这件事本身被覆盖 ——
     * 内存里拿到字符串只能证明 pattern 会渲染, 证明不了条目真的写进了文件。
     */
    private static String runAndReadAppLog(String marker, ThrowingBody body) throws Exception {
        body.run();
        flushLogback();
        assertTrue(Files.exists(APP_LOG),
                "前置条件失败: 应用日志文件必须存在（测试期 root appender 指向 " + APP_LOG + "）");
        String text = Files.readString(APP_LOG, StandardCharsets.UTF_8);
        assertTrue(text.contains(marker), "前置条件失败: 日志文件里找不到本次用例的标记 " + marker);
        return text;
    }

    /**
     * 让 Logback 立即把缓冲写盘。
     *
     * <p>FileAppender 默认 {@code immediateFlush=true}，正常情况下每个事件都已落盘；
     * 这里再显式 stop/start 一次是为了把"依赖某个默认值"变成"不依赖"——
     * 万一将来有人把 immediateFlush 关掉，本用例不会因为读到空文件而给出假结论
     * （那种失败会表现为"找不到标记"的断言失败，而不是静默通过）。
     *
     * <p>获取 appender 的路径：{@code LoggerContext} 本身不暴露全局 appender 表，
     * 但每个 logger 都持有自己引用的 appender，故遍历 logger 层级即可覆盖全部。
     */
    private static void flushLogback() {
        ch.qos.logback.classic.LoggerContext ctx =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        for (ch.qos.logback.classic.Logger logger : ctx.getLoggerList()) {
            java.util.Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                    logger.iteratorForAppenders();
            while (it.hasNext()) {
                flush(it.next());
            }
        }
    }

    private static void flush(ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        if (a instanceof ch.qos.logback.core.FileAppender<?> f && f.isStarted()) {
            f.stop();
            f.start();
        }
    }

    private static String lineContaining(String text, String marker) throws IOException {
        return text.lines().filter(l -> l.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("日志里找不到标记行: " + marker));
    }
}