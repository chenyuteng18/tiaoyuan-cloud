package com.diaoyuanyun.dy.app.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护断言: <b>dy-app 测试运行本身不得把日志写进源码树</b>。
 *
 * <h2>守的是什么缺陷（已确定性复现, 不是假想）</h2>
 * 修复前, dy-app 没有 {@code src/test/resources/}, 于是测试 classpath 上唯一的 logback
 * 配置是 {@code dy-web/src/main/resources/logback.xml}, 其默认值是
 * {@code ${LOG_DIR:-logs}}。而全仓库<b>没有任何地方设置</b> {@code LOG_DIR}
 * （无 surefire 配置、无测试 {@code System.setProperty}）—— 于是默认值 {@code logs}
 * 生效, surefire 的工作目录又是模块 basedir, 结果每跑一次 dy-app 测试就在源码树里
 * 生成 {@code dy-app/logs/{dy-app.log, dy-audit.log}}。
 * 本仓库既无 {@code .gitignore} 也非 git 仓库, 所以这是<b>实打实的源码树污染</b>,
 * 而不是"反正会被忽略"。dy-web 那份配置的注释里承诺过"LOG_DIR 走系统属性/变量,
 * 是为了让测试能把输出重定向到临时目录"—— 但当时那个缓解措施并不存在,
 * <b>承诺与事实不符</b>, 且没有任何断言会发现这件事。
 *
 * <h2>两类污染源必须分开（这是本类第二版才想清楚的关键）</h2>
 * 源码树里出现 {@code logs/} <b>有两个完全不同的生产者</b>, 早期版本的断言把两者混为一谈:
 * <ol>
 *   <li><b>D1 — 测试运行</b>（surefire 工作目录 = 模块 basedir）：
 *       {@code mvn test} 本身往源码树写日志。这是本类要守的缺陷。已由
 *       {@code dy-app/src/test/resources/logback-test.xml} 修掉（输出改道 {@code target/test-logs/}）。</li>
 *   <li><b>D2 — 开发期手工启动</b>（{@code mvn spring-boot:run} 在源码树里执行）：
 *       这类启动同样命中 {@code ${LOG_DIR:-logs}} 默认值, 同样在源码树里留下
 *       {@code dy-app/logs/}（实测证据: 一次手工启动留下 24756 字节的
 *       {@code Starting DyAppApplication … Tomcat 60518→18080}, 时间戳早于当次测试运行 30 分钟）。
 *       D2 的修复点在入口（dy-app 的 {@code spring-boot:run} 显式注入 {@code LOG_DIR=target/logs}）,
 *       <b>不在本断言里</b> —— 见下方"为什么必须是运行内证据"。</li>
 * </ol>
 *
 * <h2>为什么断言必须是【运行内证据】而不是【目录存在性】（本类第一版的真实缺陷）</h2>
 * 第一版断言的是 {@code Files.exists(logs/)}。这个判据<b>不是 hermetic 的</b>:
 * 它读的是"磁盘上现在有没有那个目录", 而那是<b>全历史所有操作</b>的累积状态,
 * 与"本次测试运行有没有写错地方"无关。后果是双向的, 两头都错:
 * <ul>
 *   <li><b>假红 / 门禁漂移</b>: 只要此前有人手工启动过一次（D2）, 目录就一直在,
 *       之后<b>每一次</b>测试运行都变红 —— 即使本次运行完全正常。门禁红的原因
 *       指向不到任何一行代码, 只能靠人工考古时间戳才能定位。这正是"测试时红时绿"的
 *       典型形态, 是商用门禁最不能有的性质。</li>
 *   <li><b>假绿</b>: 若 surefire 的工作目录不是模块 basedir（改 pom / CI 换了工作目录）,
 *       相对路径 {@code logs} 就落在别处, 断言恒绿 —— 污染照写, 门禁不响。</li>
 * </ul>
 * 正确判据是<b>因果判据</b>: 本次运行注入一个 uniqu 探针标记, 然后断言
 * <b>该标记不得出现在源码树的任何日志文件里</b>。这条判据:
 * <ul>
 *   <li>只依赖本次运行 → 不受任何历史残留影响 → <b>不会漂移</b>;</li>
 *   <li>若输出改道被破坏, 标记必然落在源码树 {@code logs/} 下 → <b>一定变红</b>
 *       （已用反向验证实测: 见本类 {@code 反向验证} 一节）。</li>
 * </ul>
 *
 * <h2>为什么还要带"正向对照"（关键, 否则是个假绿门禁）</h2>
 * 若只断言"标记不在源码树里", 那么<b>把日志全部关掉</b>也能让本用例通过 ——
 * 一个"什么都不写"的实现同样满足"没写错地方"。这种门禁在配置被改坏到
 * "一个 appender 都没生效"时仍然全绿, 毫无价值。
 * 故本用例先<b>确认日志确实写进了 target/</b>（正向对照: 文件存在且含本次标记）,
 * 再断言源码树里没有该标记。两段都过, 才说明"输出发生了, 且发生在了正确的位置"。
 *
 * <h2>为什么断言工作目录必须等于 surefire basedir（把假绿变成显式红）</h2>
 * "标记不在相对路径 {@code logs} 下"这条判据成立的前提是"相对路径 {@code logs}
 * 正好指源码树"。若这个前提不成立（工作目录被改）, 判据会<b>静默</b>变成假绿。
 * 故本用例先断言 {@code System.getProperty("basedir")} 存在且等于当前工作目录 ——
 * 前提一旦被破坏, 得到的是<b>一条说明清楚的红</b>, 而不是一个说不清为什么绿的门禁。
 *
 * <p><b>本用例刻意不清理</b>源码树的 {@code logs/}：清理会把唯一的证据抹掉, 让红态变成
 * "跑完什么也没留下"从而无法留证。红态下应当保留现场由人查看。
 *
 * <h2>反向验证（不是自证）</h2>
 * <ol>
 *   <li>把 {@code dy-app/src/test/resources/logback-test.xml} 临时改名,
 *       使测试 classpath 退回 dy-web 的 {@code ${LOG_DIR:-logs}} 默认值;</li>
 *   <li>单跑本类 → 期望: <b>主断言变红</b>, 失败信息里给出命中标记的源码树文件路径;</li>
 *   <li>恢复配置 → 复跑 → 期望: 全绿。</li>
 * </ol>
 * 三步都实际执行过, 记录在 S1-3 的反向验证三栏表里。第 2 步红说明本门禁<b>有牙齿</b>;
 * 只看第 3 步的绿是"自证", 不能作为证据。
 */
class TestLogOutputIsolationTest {

    /** 相对模块 basedir, 即 {@code dy-app/logs} —— 两类污染源都往这里写。 */
    private static final Path SOURCE_TREE_LOG_DIR = Path.of("logs");

    /** 相对模块 basedir —— test-scoped logback 配置把输出导向这里。 */
    private static final Path REDIRECTED_LOG_FILE = Path.of("target", "test-logs", "dy-app.log");

    /**
     * 走 root logger（无显式绑定的 logger 名）, 以证明 <b>root 的 appender</b> 指向 target/。
     * 用探针专属 logger 名而不是本测试类的名字, 是为了不受任何类名绑定规则影响。
     */
    private static final Logger probe = LoggerFactory.getLogger("dy.app.log-isolation-probe");

    @Test
    void dy_app_tests_write_into_target_and_never_into_the_source_tree() throws Exception {
        String marker = "log-isolation-probe-" + System.nanoTime();

        // ---- 前提断言: 相对路径 logs 必须真的指源码树, 否则下面的判据会静默假绿
        assertSourceTreeAnchorHolds();

        probe.info("日志落点探针 {}", marker);
        flushLogback();

        // ---- 主断言（放最前）: 本次运行的标记不得出现在源码树任何日志文件里
        // 必须放在正向对照【之前】。否则红态下正向对照会先失败并抛出, 主断言根本没被执行 ——
        // 于是"主断言有没有牙齿"这件事永远无法从红态里看出来（本用例第一版就是这个顺序,
        // 反向验证时暴露出来的）。
        List<String> polluted = sourceTreeLogFilesContaining(marker);
        assertTrue(polluted.isEmpty(),
                "dy-app 测试【不得】把日志写进源码树。本次运行注入的标记 " + marker
                        + " 出现在下列源码树文件中: " + polluted
                        + "。根因只有两种可能: ① D1 —— dy-app/src/test/resources/logback-test.xml "
                        + "被删除/改名/改坏, 于是退回 dy-web 的 ${LOG_DIR:-logs} 默认值; "
                        + "② surefire 工作目录被改成源码树内某处。修复方向见本类类注释, "
                        + "不要修改本断言来让它变绿。");

        // ---- 正向对照: 输出确实发生了, 且落在 target/ 下
        // 它防的是"什么都不写"这种假绿: 把日志全部关掉也能让上面的主断言通过。
        assertTrue(Files.exists(REDIRECTED_LOG_FILE),
                "正向对照失败: 测试期日志必须落在 " + REDIRECTED_LOG_FILE
                        + "（test-scoped logback 配置的职责）。它不存在意味着配置没加载或没有 appender 生效 ——"
                        + " 那样'源码树里没标记'就是无意义的假绿。");
        String text = Files.readString(REDIRECTED_LOG_FILE, StandardCharsets.UTF_8);
        assertTrue(text.contains(marker),
                "正向对照失败: " + REDIRECTED_LOG_FILE + " 里必须含本次探针标记 " + marker
                        + "，否则不能证明这条日志真的写进了 target/。");
    }

    /**
     * 结构层守护: test-scoped 配置必须存在, 且必须<b>不</b>复刻审计/应用日志分离规则。
     *
     * <p>两条都必要:
     * <ol>
     *   <li><b>存在性</b>：文件被删掉后, 上面的行为断言只有在"恰好还有别的 test 配置"时才红;
     *       直接检查存在性能给出更明确的失败原因。</li>
     *   <li><b>不复刻分离规则</b>：分离规则的真相源是
     *       {@code dy-web/src/main/resources/logback.xml}, 由 dy-web 的
     *       {@code AuditLogSeparationTest} 在 dy-web 模块内对其结构事实做断言。
     *       若 dy-app 的测试配置里再写一份 {@code DY.AUDIT}/{@code DY.APP} 与
     *       {@code additivity="false"}, 就出现两份会各自漂移的规则 ——
     *       而"两个真相源"比"没有规则"更危险: 改了一处、另一处不报错。
     *       本用例把"不要在这里复刻"变成可执行的约束, 而不是一句口头纪律。</li>
     * </ol>
     */
    @Test
    void test_scoped_config_exists_and_does_not_fork_the_audit_separation_rule() throws Exception {
        Path config = Path.of("src", "test", "resources", "logback-test.xml");
        assertTrue(Files.exists(config),
                "必须存在 test-scoped 配置 " + config.toAbsolutePath() + "，否则测试期会退回 dy-web 的 "
                        + "${LOG_DIR:-logs} 默认值并污染源码树");

        String xml = Files.readString(config, StandardCharsets.UTF_8);

        // 只检查【生效的 XML】, 不检查注释 —— 本文件的注释里会解释"为什么不复刻
        // additivity 那套规则", 那段文字本身含 "additivity"。用原始文本做包含判断会把
        // 解释性注释误判成违规（本用例第一版就踩了这个, 是它自己把自己抓出来的）。
        // 剥掉注释后剩下的才是"配置真正声明了什么"。
        String effectiveXml = stripXmlComments(xml);

        // 输出必须被导向 target/, 而不是模块根
        assertTrue(effectiveXml.contains("target/test-logs"),
                "test-scoped 配置必须把日志导向 target/ 下（且必须在生效的 XML 里, 不能只在注释里）。"
                        + "生效的 XML:\n" + effectiveXml);

        // 不得在这里复刻分离规则（真相源唯一）
        assertFalse(effectiveXml.contains("DY.AUDIT"),
                "dy-app 的测试配置不得声明 DY.AUDIT logger —— 审计/应用日志分离规则的真相源是 "
                        + "dy-web/src/main/resources/logback.xml（由 dy-web 的 AuditLogSeparationTest 守护）。"
                        + " 在这里再写一份会产生两份会各自漂移的规则。生效的 XML:\n" + effectiveXml);
        assertFalse(effectiveXml.contains("DY.APP"),
                "dy-app 的测试配置不得声明 DY.APP logger（同上, 真相源唯一）。生效的 XML:\n" + effectiveXml);
        assertFalse(effectiveXml.contains("additivity"),
                "dy-app 的测试配置不得出现 additivity —— 那是 dy-web 侧分离规则的结构事实, "
                        + "本文件只需要'把输出导向 target/ 且可解析'这一件事。生效的 XML:\n" + effectiveXml);
    }

    /** 剥掉 XML 注释, 只留生效内容; 同时把注释内容排除在结构断言之外（见调用处说明）。 */
    private static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 断言"相对路径 {@code logs} = 源码树"这一前提成立。
     *
     * <p>surefire 会把模块 basedir 注入系统属性 {@code basedir}。若该属性缺失,
     * 或它不等于当前工作目录, 说明本门禁的核心前提不再成立 —— 此时必须<b>报错</b>,
     * 而不是让主断言在错误的路径上恒绿。这是"把静默假绿转成显式红"的关键一步。
     */
    private static void assertSourceTreeAnchorHolds() {
        String basedir = System.getProperty("basedir");
        assertNotNull(basedir,
                "surefire 未注入系统属性 basedir, 无法确定源码树位置 —— 本门禁的核心前提"
                        + "（相对路径 logs 指向源码树）无法成立, 与其静默假绿不如显式失败。");

        Path workDir = Path.of("").toAbsolutePath().normalize();
        Path expected = Path.of(basedir).toAbsolutePath().normalize();
        assertTrue(workDir.equals(expected),
                "surefire 工作目录与模块 basedir 不一致, 本门禁的路径前提不成立。"
                        + " 工作目录=" + workDir + ", basedir=" + expected
                        + "。若不修正, '源码树里没有标记'会变成一句没有意义的真话。");
    }

    /**
     * 在源码树 {@code logs/} 下递归找出所有包含本次探针标记的日志文件。
     *
     * <p>返回命中路径列表（而非布尔值）是刻意的: 红态下失败信息必须直接指出
     * <b>哪个文件</b>被污染了, 否则定位要重新做一遍考古。
     */
    private static List<String> sourceTreeLogFilesContaining(String marker) throws IOException {
        if (!Files.isDirectory(SOURCE_TREE_LOG_DIR)) {
            return List.of();
        }
        List<String> hit = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SOURCE_TREE_LOG_DIR)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (Files.readString(p, StandardCharsets.UTF_8).contains(marker)) {
                    hit.add(p.toAbsolutePath().toString());
                }
            }
        }
        return hit;
    }

    /**
     * 让 Logback 立即把缓冲写盘。
     *
     * <p>FileAppender 默认 {@code immediateFlush=true}，正常情况下每个事件都已落盘；
     * 这里再显式 stop/start 一次是为了把"依赖某个默认值"变成"不依赖"——
     * 万一将来有人把 immediateFlush 关掉，本用例不会因为读到空的/不存在的文件
     * 而给出假结论（那种失败会表现为正向对照的断言失败，而不是静默通过）。
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