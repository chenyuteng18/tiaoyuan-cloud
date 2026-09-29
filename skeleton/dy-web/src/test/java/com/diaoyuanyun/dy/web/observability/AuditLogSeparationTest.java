package com.diaoyuanyun.dy.web.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 DoD #3: <b>审计日志与应用日志物理分离</b>, 且分离被断言守住。
 *
 * <h2>要证明的是"分离", 不是"配置里写了两行"</h2>
 * "配置里有两个 appender、两个 logger" 是一个<b>文本事实</b>, 不是行为事实。
 * 真正会出事的是下面两种形态, 它们都能在"配置看起来完全正确"的前提下发生:
 * <ol>
 *   <li><b>additivity 忘了关</b>：{@code DY.AUDIT} 的事件上抛给 root, 顺着 root 落进应用日志
 *       —— 文件是分了, 内容没分。审计条目在应用日志里也有一份,
 *       于是"审计文件受更严格保留/访问控制"这个承诺失效。</li>
 *   <li><b>写错 logger 名</b>：某个审计写入点写成 {@code LoggerFactory.getLogger("audit")},
 *       而不是 {@link DyLoggers#AUDIT}。那条记录安静地落进应用日志, 审计文件里少一条,
 *       没有任何报错。这类"静默丢审计"是最危险的形态, 因为对账时才发现。</li>
 * </ol>
 * 故本用例的做法是: <b>真的往两个 logger 各写一条带唯一标记的记录, 然后读回两个文件</b>,
 * 断言"该出现的出现了、不该出现的没出现"。标记用 {@code System.nanoTime()} 保证不与
 * 其它用例/历史运行碰撞 —— 否则"文件里已经有一条旧的呢"会让断言平凡通过。
 *
 * <h2>取证边界</h2>
 * 落地路径来自<b>测试期配置</b> {@code dy-web/src/test/resources/logback-test.xml}
 * （root 与 DY.APP 写 {@code target/test-logs/dy-app.log}；DY.AUDIT 只写
 * {@code target/test-logs/dy-audit.log}）。它验证的是<b>同一套 Logback 语义下的分离规则</b>,
 * 不是生产 appender 的最终落地（生产那份是 {@code dy-web/src/main/resources/logback.xml},
 * 可能被应用侧 {@code logback-spring.xml} 覆盖）。已在交付报告"未验证部分"登记。
 */
class AuditLogSeparationTest {

    private static final Path APP_LOG = Path.of("target", "test-logs", "dy-app.log");
    private static final Path AUDIT_LOG = Path.of("target", "test-logs", "dy-audit.log");

    // ---------------------------------------------------------------- 行为分离（核心）

    @Test
    void audit_entry_lands_in_audit_file_and_not_in_app_file() throws Exception {
        String appMarker = "APPONLY-" + System.nanoTime();
        String auditMarker = "AUDITONLY-" + System.nanoTime();

        Logger appLogger = LoggerFactory.getLogger(DyLoggers.APP);
        Logger auditLogger = LoggerFactory.getLogger(DyLoggers.AUDIT);

        appLogger.info("应用日志探针 {}", appMarker);
        auditLogger.info("审计日志探针 {}", auditMarker);
        flushLogback();

        String appText = read(APP_LOG);
        String auditText = read(AUDIT_LOG);

        // ---- 该出现的出现了
        assertTrue(appText.contains(appMarker), "应用日志必须落在应用日志文件里");
        assertTrue(auditText.contains(auditMarker), "审计条目必须落在审计日志文件里");

        // ---- 不该出现的确实没出现（这才是"分离"的证据）
        assertFalse(appText.contains(auditMarker),
                "审计条目【不得】出现在应用日志里 —— 出现了说明 additivity 没关, "
                        + "审计文件更严格的保留/访问控制承诺即失效");
        assertFalse(auditText.contains(appMarker),
                "应用日志【不得】出现在审计文件里 —— 出现了说明审计文件被塞进了非审计内容");
    }

    /**
     * 两个 logger 名必须不同, 且互不为前缀。
     *
     * <p>为什么"互不为前缀"是必须的：Logback 的 logger 层级按<b>名字前缀</b>继承 appender。
     * 若 {@code APP="DY"}、{@code AUDIT="DY.AUDIT"}, 则 AUDIT 是 APP 的子 logger,
     * 会继承 APP 的 appender —— 分离在<b>命名层面</b>就已经不成立了, 后面怎么配都白搭。
     * {@link DyLoggers} 的静态块会做同样的自检并在类加载时失败, 此处再断言一次,
     * 使"改名改成前缀关系"这条路径<b>一定有测试变红</b>。
     */
    @Test
    void logger_names_differ_and_are_not_prefixes_of_each_other() {
        assertNotEquals(DyLoggers.APP, DyLoggers.AUDIT, "两个 logger 名不得相同");
        assertFalse(DyLoggers.APP.startsWith(DyLoggers.AUDIT + "."),
                "APP 不得是 AUDIT 的子 logger（否则会继承审计 appender）");
        assertFalse(DyLoggers.AUDIT.startsWith(DyLoggers.APP + "."),
                "AUDIT 不得是 APP 的子 logger（否则会继承应用 appender）");
        assertEquals("DY.APP", DyLoggers.APP);
        assertEquals("DY.AUDIT", DyLoggers.AUDIT);
    }

    /**
     * 日志文件名本身必须不同。
     *
     * <p>看似显然, 但它守住的是"有人把两个 appender 的 file 指向同一个路径"这一种
     * 看似无害、实则让分离彻底失效的配置错误（两个 appender 写同一文件 = 一个文件里混着两类内容,
     * 保留策略只能取二者之一, 严格的那个必然失效）。
     */
    @Test
    void the_two_log_files_are_distinct_paths() {
        assertNotEquals(APP_LOG.toAbsolutePath(), AUDIT_LOG.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 配置分离（结构层）

    /**
     * 结构层: 生产配置与测试配置都必须把 {@code DY.AUDIT} 的 {@code additivity} 显式设为 false。
     *
     * <p>行为层（上面那条用例）已经证明了"当前确实是分离的"。本条守住的是<b>未来</b>:
     * 行为层用例依赖测试期配置, 若有人把测试配置改对、却把生产配置写漏 additivity=false,
     * 行为层用例仍然绿（它读的是测试配置）—— 生产就静默坏掉了。故必须直接检查生产配置文本。
     *
     * <p>检查文本而非加载配置对象: 生产配置在 dy-web 模块里是"默认分离规则",
     * 应用侧可以用 logback-spring.xml 整体接管。文本检查能明确回答"这份文件里写了没有",
     * 而不会因为加载顺序问题给出模糊结论。
     */
    @Test
    void both_production_and_test_logback_configs_disable_additivity_for_the_audit_logger() throws Exception {
        assertAdditivityDisabled(Path.of("src", "main", "resources", "logback.xml"), "生产（默认分离规则）");
        assertAdditivityDisabled(Path.of("src", "test", "resources", "logback-test.xml"), "测试");
    }

    private static void assertAdditivityDisabled(Path configPath, String which) throws Exception {
        String xml = read(configPath);
        // 只做"标签 + 属性"级别的存在性判定, 不写完整 XML 解析器:
        // 目标是回答"这个开关写在配置里了吗", 而不是复刻 Logback 的解析语义。
        int loggerIdx = xml.indexOf("<logger name=\"DY.AUDIT\"");
        assertTrue(loggerIdx >= 0, which + "配置 " + configPath + " 里必须有一条 DY.AUDIT logger 绑定");

        int tagEnd = xml.indexOf('>', loggerIdx);
        String loggerTag = xml.substring(loggerIdx, tagEnd);
        assertTrue(loggerTag.contains("additivity=\"false\""),
                which + "配置里 DY.AUDIT logger 必须显式 additivity=\"false\"（详见 "
                        + AuditLogSeparationTest.class.getSimpleName() + " 类注释第 1 条）。"
                        + "实际标签: " + loggerTag);
    }

    /** 生产配置必须给审计日志单独的 appender 与单独的文件路径。 */
    @Test
    void production_config_declares_a_separate_audit_appender_and_file() throws Exception {
        String xml = read(Path.of("src", "main", "resources", "logback.xml"));

        assertTrue(xml.contains("name=\"AUDIT_FILE\""), "生产配置必须声明 AUDIT_FILE appender");
        assertTrue(xml.contains("name=\"APP_FILE\""), "生产配置必须声明 APP_FILE appender");
        assertTrue(xml.contains("dy-audit.log"), "审计日志必须有自己的文件路径（dy-audit.log）");
        assertTrue(xml.contains("dy-app.log"), "应用日志必须有自己的文件路径（dy-app.log）");

        // 审计 appender 不得挂到 root 上（挂了就与 APP_FILE 混在一起）
        int rootIdx = xml.indexOf("<root");
        String rootBlock = xml.substring(rootIdx, xml.indexOf("</root>"));
        assertFalse(rootBlock.contains("AUDIT_FILE"),
                "审计 appender 不得挂在 root 上 —— 那会让所有日志都进审计文件（分离失效且审计被噪声淹没）");
    }

    // ---------------------------------------------------------------- helpers

    private static String read(Path p) throws Exception {
        assertTrue(Files.exists(p), "文件必须存在: " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
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
}