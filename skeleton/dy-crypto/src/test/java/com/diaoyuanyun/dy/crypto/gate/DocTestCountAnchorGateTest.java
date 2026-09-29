package com.diaoyuanyun.dy.crypto.gate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>文档计数锚点守护</b> —— 让"文档里的计数与实测计数脱钩"这件事自己变红。
 *
 * <h1>为什么要有这个测试（这不是假想，是刚发生过的事）</h1>
 * 2026-09-22 给本模块补了 {@code SubjectKeyStoreConcurrencyGateTest}（3 例，任务 #68），
 * 模块计数由 <b>23 抬到 26</b>。测试是绿的、构建是 SUCCESS 的，
 * 但<b>引用这个计数的 4 份文档全部停留在旧值</b>：
 * <ul>
 *   <li>{@code dy-crypto/DATA-MAP-AND-DELETION-DAG.md} 的「验收依据（唯一）」表 ——
 *       这是一条<b>活断言</b>，它声称"验收只认这一行"，而那一行当时已经说的是错数字；</li>
 *   <li>{@code verification/crypto/README.md} 的「应为 23」与「日常 23 例绿」；</li>
 *   <li>{@code verification/crypto/evidence/red-green/INDEX.md} 的盲点声明 ——
 *       它声称"模块自测零并发代码"，而该盲点<b>已被关闭</b>。</li>
 * </ul>
 * 换句话说：<b>一处改进让另外四处的陈述变成了假的，而没有任何机器会因此变红。</b>
 * 这正是本类要堵的洞。它是团队自己定的纪律的直接落点 ——
 * 「一个从未变红的判据等于没有判据」。
 *
 * <h1>判据：文档锚点 ↔ 源码级计数</h1>
 * 本测试从 {@code dy-crypto/src/test/**}{@code /*.java} 里数出 <b>{@code @Test} 方法数</b>
 * （下称 {@code S}），再与文档里形如 <b>{@code 应为 **N**}</b> 的机器锚点比对。
 * 任何一侧变了而另一侧没跟上，本测试立刻红，并在失败信息里<b>指名</b>是哪份文档的哪个锚点。
 *
 * <h2>为什么主输入是「源码级计数」而不是「surefire 报告的求和」</h2>
 * 🛑 这是一个必须写明的实现约束，否则下一个人会以为主输入选错了：
 * surefire 是<b>逐类</b>写报告的，且默认按类名顺序执行。本类名 {@code DocTestCountAnchorGateTest}
 * 排在 {@code FieldCryptoGateTest} 之后、{@code SubjectKeyStoreConcurrencyGateTest} <b>之前</b> ——
 * <b>本类运行时，排在它后面的测试类的报告还不存在</b>。若把"报告求和"当主判据，
 * 计数会被系统性算少，结果是<b>恒红</b>（或更坏：逼着我们把锚点写成错误的偏小值）。
 * 故：源码级计数作<b>主</b>（它在编译期就已确定、与执行顺序无关），
 * 报告求和只作<b>附加</b>观察，且只在报告确实比测试源码更新时才纳入比对。
 *
 * <h2>为什么源码级计数在"实际执行数"上是可信的</h2>
 * 源码里的 {@code @Test} 数 == 实际执行数，前提是<b>没有</b>
 * {@code @Disabled}（声明了却跳过）、{@code @ParameterizedTest} / {@code @TestFactory}
 * （一个方法跑多次，源码只算 1）。故本测试同时断言这三种标注在整个
 * {@code dy-crypto/src/test} 下<b>一个都不存在</b> —— 一旦出现，立即红，
 * 提示"计数口径已改变，不能再用 @Test 数代表执行数"。
 *
 * <h2>⚠️ 本测试的自指性（如实声明，不掩饰）</h2>
 * 本测试类自己也在 {@code S} 里，所以锚点值<b>包含本类的方法数</b>。
 * 这不是"作弊"（算术是诚实的，maven 的 {@code Tests run} 总数就是这么多），
 * 但它带来一个必须知道的后果：
 * <b>往本类里增删测试方法，也要同步改文档锚点</b>，否则本测试红。
 * 这是有意的 —— 本类承载的是"计数类文档的守护"，它的方法数本来就属于被守护的计数。
 *
 * <h2>本测试做不到的事（同样如实声明）</h2>
 * 若有人把本测试<b>整个文件删掉</b>，守护就随之消失，而"没有守护"这件事本身
 * <b>不会被任何在跑的测试发现</b>（进程内无法证明自己的缺席）。
 * 这是进程内自检的固有上限，唯一的补法是 CI 层面断言"本类必须出现在 surefire 报告里"，
 * 本类不做超出能力的声称。
 */
class DocTestCountAnchorGateTest {

    /** 文档锚点的<b>唯一可解析形态</b>：{@code 应为 **N**}（N 为十进制整数）。 */
    private static final Pattern ANCHOR = Pattern.compile("应为\\s*\\*\\*(\\d+)\\*\\*");

    /** 被守护的文档（相对仓库根）。加文档时必须同步改这里。 */
    private static final List<String> GUARDED_DOCS = List.of(
            "dy-crypto/DATA-MAP-AND-DELETION-DAG.md",
            "verification/crypto/README.md");

    /**
     * 模块计数下界：本模块的既有用例不得被"顺手删掉"。
     * 23 例是任务 #68 之前的基线，+3（并发门禁）= 26。下界取 26 而非当前总数：
     * 它是"不许掉下去"的底线，不是"必须等于"的目标（等于多少由文档锚点判）。
     */
    private static final int MIN_EXPECTED = 26;

    /** 会让「源码 @Test 数 ≠ 实际执行数」的标注；出现即红。 */
    private static final List<String> COUNT_BREAKING_ANNOTATIONS = List.of(
            "@Disabled", "@ParameterizedTest", "@TestFactory", "@RepeatedTest");

    // ==================================================================
    // ① 主判据：文档锚点 == 源码级计数
    // ==================================================================

    @Test
    void document_count_anchors_match_the_real_test_count() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path moduleRoot = repoRoot.resolve("dy-crypto");

        Counted counted = countTestsInSources(moduleRoot.resolve("src/test"));

        // ---- 逐份文档核对锚点 ----
        Map<String, List<Integer>> anchorsByDoc = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String rel : GUARDED_DOCS) {
            Path doc = repoRoot.resolve(rel);
            assertTrue(Files.isRegularFile(doc),
                    "被守护的文档不存在 —— 文档被删/改名后锚点无法被守护，必须同步改 GUARDED_DOCS: " + doc);
            String text = Files.readString(doc, StandardCharsets.UTF_8);

            List<Integer> found = new ArrayList<>();
            Matcher m = ANCHOR.matcher(text);
            while (m.find()) {
                found.add(Integer.parseInt(m.group(1)));
            }
            anchorsByDoc.put(rel, found);

            if (found.isEmpty()) {
                problems.add(rel + " : 一个锚点都解析不到（期望形态 `应为 **" + counted.total() + "**`）"
                        + " —— 解析不到按【失败】处理，不按通过处理");
                continue;
            }
            for (int v : found) {
                if (v != counted.total()) {
                    problems.add(rel + " : 锚点写的 `应为 **" + v + "**`，实测源码级计数是 " + counted.total()
                            + "（差 " + (counted.total() - v) + "）");
                }
            }
        }

        // ---- 计数口径的可信前提：没有会打破"@Test 数 == 执行数"的标注 ----
        List<String> breaking = findCountBreakingAnnotations(moduleRoot.resolve("src/test"));
        assertTrue(breaking.isEmpty(),
                "dy-crypto/src/test 下出现了会打破计数口径的标注 —— "
                        + "「源码 @Test 数 == 实际执行数」这一前提不再成立，锚点守护会失准。"
                        + "处置：要么去掉该标注，要么改本类让它按新口径计数。命中:\n  - "
                        + String.join("\n  - ", breaking));

        // ---- 报告求和（附加观察；只在报告确实更新时纳入）----
        ReportSum report = sumFreshSurefireReports(moduleRoot, counted.newestSourceMtime());

        System.out.println("[DY-CRYPTO-DOC-ANCHOR] 源码级计数 S=" + counted.total()
                + " 明细=" + counted.perClass()
                + " ; 锚点=" + anchorsByDoc
                + " ; 报告求和(新鲜)=" + report.describe()
                + " ; 模块计数下界=" + MIN_EXPECTED);

        assertTrue(problems.isEmpty(),
                "文档计数锚点与实测计数脱钩 —— 请更新文档锚点（`应为 **" + counted.total() + "**`）。\n"
                        + "⇒ 为什么需要这一步：本项目已发生过一次同类漂移 —— 任务 #68 把模块计数\n"
                        + "   由 23 抬到 26，测试全绿、构建 SUCCESS，但 4 份引用该计数的文档全部停留在\n"
                        + "   旧值，其中 `DATA-MAP-AND-DELETION-DAG.md` §6.1 是【活断言】。\n"
                        + "⇒ 处置：改文档锚点（不要改本测试的期望值去迁就旧文档）。\n"
                        + "命中:\n  - " + String.join("\n  - ", problems));

        // 模块用例不得被顺手删光：计数下界
        assertTrue(counted.total() >= MIN_EXPECTED,
                "dy-crypto 的用例数掉到 " + counted.total() + "，低于下界 " + MIN_EXPECTED
                        + " —— 有测试被删/被改名脱离 surefire 扫描范围。既有用例（含任务 #68 的"
                        + " 3 例并发门禁）不得被静默移除。");
    }

    // ==================================================================
    // ② 计数可复现（两次独立扫描必须一致）—— 证明它不是随机/缓存产物
    // ==================================================================

    @Test
    void count_is_reproducible_across_independent_scans() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path testTree = repoRoot.resolve("dy-crypto").resolve("src/test");

        Counted first = countTestsInSources(testTree);
        Counted second = countTestsInSources(testTree);

        assertEquals(first.total(), second.total(),
                "两次独立扫描得到的计数不一致 —— 说明计数依赖文件遍历顺序或缓存，不可复现");
        assertEquals(first.perClass(), second.perClass(), "两次独立扫描的逐类明细必须完全一致");

        // 逐类明细必须覆盖全部含测试的类，且每类都 > 0
        for (Map.Entry<String, Integer> e : first.perClass().entrySet()) {
            assertTrue(e.getValue() > 0,
                    "类 " + e.getKey() + " 被计入明细却是 0 例 —— 明细口径不一致");
        }
        // 本类自己必须在明细里（否则说明扫描漏了文件，"守护自己不被守护"）
        assertTrue(first.perClass().containsKey(getClass().getSimpleName()),
                "扫描明细里没有本类 " + getClass().getSimpleName()
                        + " —— 源码扫描漏了文件，计数不可信。实际明细=" + first.perClass());

        System.out.println("[DY-CRYPTO-DOC-ANCHOR] 可复现性 OK：两次扫描一致，S=" + first.total()
                + "，类数=" + first.perClass().size());
    }

    // ==================================================================
    // 计数实现
    // ==================================================================

    /** 一次扫描的结论：总数 + 逐类明细 + 被测源码的最新 mtime（供报告新鲜度过滤）。 */
    private record Counted(int total, Map<String, Integer> perClass, long newestSourceMtime) {
    }

    /** 数 {@code src/test} 下每个 {@code .java} 文件里的 {@code @Test} 方法数。 */
    private static Counted countTestsInSources(Path testTree) {
        assertTrue(Files.isDirectory(testTree), "测试源码目录不存在: " + testTree);
        Map<String, Integer> perClass = new TreeMap<>();
        int total = 0;
        long newest = 0L;

        List<Path> files = listJavaFiles(testTree);
        for (Path f : files) {
            String text;
            try {
                text = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取测试源码失败: " + f, e);
            }
            int n = countTestAnnotations(text);
            if (n > 0) {
                perClass.put(f.getFileName().toString().replace(".java", ""), n);
                total += n;
            }
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
            } catch (IOException e) {
                throw new UncheckedIOException("读取测试源码 mtime 失败: " + f, e);
            }
        }

        assertFalse(files.isEmpty(), "测试源码目录下一个 .java 都没有: " + testTree);
        assertTrue(total > 0, "整个测试树下数出 0 个 @Test —— 说明扫描口径坏了，而不是真的没有测试");
        return new Counted(total, java.util.Collections.unmodifiableMap(new TreeMap<>(perClass)), newest);
    }

    /**
     * 数 {@code @Test}。刻意<b>不</b>用笼统的 {@code contains("@Test")}：
     * 那会把 {@code @TestFactory} / 注释里出现的 {@code @Test} 一起算进去。
     * 本实现要求 {@code @} 之前只能是行首空白或 {@code (} 之前的非标识符字符，
     * 且 {@code @Test} 之后不能紧跟标识符字符。
     */
    private static int countTestAnnotations(String javaSource) {
        Matcher m = Pattern.compile("(?m)^\\s*@Test\\b(?!\\w)").matcher(javaSource);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 找出会打破计数口径的标注（{@code @Disabled} 等），返回 {@code 文件:行} 形式。 */
    private static List<String> findCountBreakingAnnotations(Path testTree) {
        List<String> hits = new ArrayList<>();
        for (Path f : listJavaFiles(testTree)) {
            String text;
            try {
                text = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取测试源码失败: " + f, e);
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.stripLeading();
                // 只认"注解使用"——行首的 @Xxx；注释里提到不算
                if (!trimmed.startsWith("@")) {
                    continue;
                }
                for (String a : COUNT_BREAKING_ANNOTATIONS) {
                    if (trimmed.startsWith(a)
                            && (trimmed.length() == a.length()
                            || !Character.isJavaIdentifierPart(trimmed.charAt(a.length())))) {
                        hits.add(f.getFileName() + ":" + (i + 1) + " " + trimmed.split("\\(")[0].trim());
                    }
                }
            }
        }
        return hits;
    }

    private static List<Path> listJavaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历测试源码失败: " + root, e);
        }
    }

    // ==================================================================
    // 报告求和（附加观察，非主判据）
    // ==================================================================

    /** surefire 报告的求和结论。{@code fresh} 为 false 时表示"本轮尚未写出报告"，不参与断言。 */
    private record ReportSum(boolean fresh, int total, int fileCount, String note) {
        String describe() {
            return fresh ? (total + "（" + fileCount + " 个已更新的报告）") : ("未纳入（" + note + "）");
        }
    }

    /**
     * 把 {@code target/surefire-reports/*.txt} 的逐类计数求和。
     *
     * <p>🛑 <b>只统计比测试源码更新的报告</b>（{@code mtime > 源码最新 mtime}）：
     * {@code target/} 里的报告可能是<b>上一轮</b>构建留下的陈旧文件，
     * 把它们算进来会在"有人删了测试"时给出偏大的和，从而造成<b>假红</b>。
     * 陈旧报告一律不纳入，且本方法<b>不</b>对结果作任何断言 ——
     * 主判据是源码级计数（理由见类注释）。
     */
    private static ReportSum sumFreshSurefireReports(Path moduleRoot, long newestSourceMtime) {
        Path reports = moduleRoot.resolve("target").resolve("surefire-reports");
        if (!Files.isDirectory(reports)) {
            return new ReportSum(false, 0, 0, "target/surefire-reports 不存在");
        }
        Pattern line = Pattern.compile("^Tests run:\\s*(\\d+),");
        int total = 0;
        int files = 0;
        try (Stream<Path> walk = Files.list(reports)) {
            List<Path> txts = walk.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
            for (Path p : txts) {
                if (Files.getLastModifiedTime(p).toMillis() <= newestSourceMtime) {
                    continue; // 陈旧报告（上一轮构建留下的）—— 纳入会造成假红
                }
                files++;
                for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    Matcher m = line.matcher(l.strip());
                    if (m.find()) {
                        total += Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 surefire 报告失败: " + reports, e);
        }
        if (files == 0) {
            return new ReportSum(false, 0, 0, "没有比测试源码更新的报告（本轮报告尚未写出）");
        }
        return new ReportSum(true, total, files, "");
    }

    // ==================================================================
    // 仓库根解析（cwd 无关：surefire 的 cwd 是模块 basedir，不是仓库根）
    // ==================================================================

    /**
     * 定位仓库根（= 同时含 {@code dy-crypto/} 与 {@code verification/} 的那一层）。
     * 三级回退，全部失败<b>直接红</b>（不 skip、不静默通过）：
     * <ol>
     *   <li>{@code -Ddy.repo.root=<abs>} / {@code -Ddy.docs.root=<abs>}（CI 显式喂入）；</li>
     *   <li>从 {@code user.dir}（= surefire 的 cwd = 模块 basedir）<b>逐级向上</b>最多 4 层。</li>
     * </ol>
     */
    private static Path resolveRepoRoot() {
        for (String key : List.of("dy.repo.root", "dy.docs.root")) {
            String explicit = System.getProperty(key);
            if (explicit != null && !explicit.isBlank()) {
                Path p = Path.of(explicit).toAbsolutePath().normalize();
                assertTrue(isRepoRoot(p), "-D" + key + " 指向的不是仓库根（缺 dy-crypto/ 或 verification/）: " + p);
                return p;
            }
        }
        Path cursor = Path.of("").toAbsolutePath().normalize();
        Path start = cursor;
        for (int i = 0; i < 4 && cursor != null; i++, cursor = cursor.getParent()) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
        }
        throw new AssertionError(
                "无法定位仓库根（需同时存在 dy-crypto/ 与 verification/）。"
                        + "请用 -Ddy.repo.root=<abs> 显式指定。cwd=" + start);
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isDirectory(p.resolve("dy-crypto"))
                && Files.isDirectory(p.resolve("verification"));
    }
}