package com.diaoyuanyun.dy.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>文档「全量回归计数」锚点守护</b> —— 让「文档声称 TOTAL N / failures=0，而真实 N 不是这个数」
 * 这件事自己变红。
 *
 * <h1>为什么要有这个测试：这不是假想，是本轮刚刚发生过的事</h1>
 * 2026-09-30 一次收口（本仓第 58、59 条）里，后端全量回归的真实结果是
 * <b>{@code TOTAL 1211 failures=0 errors=0 skipped=0}</b>，而我在 {@code README.md} 与
 * {@code frontends/README.md} 的共 <b>4 处</b>锚点上写的是 <b>1204</b>（少算 7 例）。
 * 少算的原因很典型：我在预估值里<b>只加了"分页纪律门禁 4 例"</b>，
 * 漏掉了同一批改动里的 {@code ContractFreezeGateTest}（+1）、
 * {@code FulfillmentDomainDEndpointsE2ETest}（+3）、{@code ProbabilisticAssertionGateTest}（+3）。
 * <p>🛑 <b>而当时全部门禁都是绿的</b> —— 测试绿、构建 {@code BUILD SUCCESS}、
 * 前端三端自检绿、反向验证 13/13。为什么？因为
 * <b>{@code TOTAL N failures=0} 是一句【活断言】（它声称"全量回归通过、且计数为 N"），
 * 但全仓没有任何一个判据在守它</b>。这与本仓第 32 条的形态完全同构：
 * <b>一个恒真的断言，等于没有断言</b>；一个没人守的断言，等于没有断言。
 *
 * <h1>它和 dy-crypto 的同名门禁是什么关系（互补，不是重复）</h1>
 * 本仓已有 {@code com.diaoyuanyun.dy.crypto.gate.DocTestCountAnchorGateTest}，
 * 它守的是 {@code dy-crypto/} 模块<b>自己的</b>「{@code 应为 **N**}」锚点（模块内计数）。
 * 本类守的是<b>全仓</b>的「{@code TOTAL N failures=... errors=... skipped=...}」锚点
 * （跨 8 个模块的求和）。二者的锚点形态不同、覆盖面不同、都被需要。
 *
 * <h1>判据：三件事</h1>
 * <ol>
 *   <li><b>计数口径可信前提</b> —— 全仓 {@code dy-*&#47;src/test} 下<b>不得</b>出现
 *       {@code @Disabled} / {@code @ParameterizedTest} / {@code @TestFactory} /
 *       {@code @RepeatedTest}。一旦出现，「源码 {@code @Test} 数 == 实际执行数」这一前提就不成立，
 *       本类的判据会失准 —— 故出现即红，并提示"计数口径已改变"。</li>
 *   <li><b>锚点 vs 实测</b> —— 每份被守护文档里，<b>最后一次</b>出现的 TOTAL 锚点（即"最新口径"，
 *       = 最新一条缺陷记录）必须<b>等于</b>当前源码级计数；每一次出现的锚点，
 *       其 {@code failures} / {@code errors} / {@code skipped} <b>必须全为 0</b>；
 *       且整份文档内的锚点值<b>必须单调不降</b>（用例只增不减，锚点写小只可能是漏加）。</li>
 *   <li><b>可复现</b> —— 两次独立扫描结果必须一致，证明它不是遍历顺序/缓存产物。</li>
 * </ol>
 *
 * <h2>为什么只对「最后一次」锚点要求"等于实测"，而更早的只要求"单调不降"</h2>
 * 🛑 这是一个刻意的设计，不这样会逼着文档失去记录历史的能力：
 * 条目表是<b>按时间追加</b>的，第 57 条那一行写的是 {@code TOTAL 1200}（当时确实是 1200）、
 * 第 58 条那一行写的是 {@code TOTAL 1208}（当时确实是 1208）。
 * 若要求每一处都等于"今天的值"，那么<b>每加一条测试都要回去改历史记录</b>，
 * 结果是文档再也不能如实记录"当时是多少"。
 * 故本类的判据是：<b>"最新一条"必须等于实测（这是活断言），"更早的"只需单调不降（这是历史）</b>。
 *
 * <h2>为什么主输入是「源码级计数」而不是「surefire 报告的求和」</h2>
 * 与 dy-crypto 同名门禁同理，且这里更强：surefire 是<b>逐 execution 写报告</b>的，
 * 本仓 {@code dy-config} 有 {@code default-test} + {@code config-truth-source-gate} 两个 execution、
 * {@code dy-app} 有 {@code default-test} + {@code rls-isolation-gate} 两个 —— 把报告求和当主判据，
 * 会把"同一批用例被执行两次的重复"也算进去（本类的 {@code MODULES} 口径是"每个类算一次"）。
 * 源码级计数与执行顺序、execution 划分都无关，故作主判据。
 *
 * <h2>本类做不到的事（如实声明）</h2>
 * 若有人把本类<b>整个文件删掉</b>，守护随之消失，而"没有守护"这件事本身不会被任何在跑的测试发现
 * （进程内无法证明自己的缺席）。这是进程内自检的固有上限，唯一的补法是 CI 层面断言
 * "本类必须出现在 surefire 报告里"，本类不做超出能力的声称。
 * <p>另：本类自己的方法数也计入 {@code dy-app} 的计数，故<b>增删本类的方法也要同步改文档锚点</b>。
 * 这是有意的 —— 本类承载的正是"计数类文档的守护"，它的方法数本来就属于被守护的计数。
 */
class DocTestCountAnchorGateTest {

    /**
     * 文档锚点的<b>唯一可解析形态</b>：{@code TOTAL <N> failures=<F> errors=<E> skipped=<S>}。
     * 刻意要求 failures/errors/skipped <b>也一起写出</b> ——
     * 只写 {@code TOTAL 1211} 而不写 {@code failures=0}，就丢掉了"回归通过"这半句断言。
     */
    private static final Pattern ANCHOR = Pattern.compile(
            "TOTAL\\s+(\\d+)\\s+failures=(\\d+)\\s+errors=(\\d+)\\s+skipped=(\\d+)");

    /** 被守护的文档（相对仓库根）。加文档时必须同步改这里。 */
    private static final List<String> GUARDED_DOCS = List.of(
            "README.md",
            "frontends/README.md");

    /**
     * 参与求和的模块，<b>顺序即文档里「逐模块 a/b/…/h」的书写顺序</b>。
     * 少一个模块 = 求和系统性偏小（正是本轮 1204 vs 1211 的成因之一），故本类会断言
     * "磁盘上存在、且非空"的模块集与该清单<b>完全一致</b>。
     */
    private static final List<String> MODULES = List.of(
            "dy-common", "dy-tenancy", "dy-security", "dy-web",
            "dy-audit", "dy-config", "dy-crypto", "dy-app");

    /** 会让「源码 @Test 数 ≠ 实际执行数」的标注；出现即红。 */
    private static final List<String> COUNT_BREAKING_ANNOTATIONS = List.of(
            "@Disabled", "@ParameterizedTest", "@TestFactory", "@RepeatedTest");

    // ==================================================================
    // ① 主判据：文档锚点 == 源码级计数
    // ==================================================================

    @Test
    void total_anchors_in_docs_match_the_real_test_count() throws IOException {
        Path root = resolveRepoRoot();

        // ---- 计数口径的可信前提：没有会打破"@Test 数 == 执行数"的标注 ----
        List<String> breaking = findCountBreakingAnnotations(root);
        assertTrue(breaking.isEmpty(),
                "测试树下出现了会打破计数口径的标注 —— 「源码 @Test 数 == 实际执行数」这一前提不再成立，"
                        + "本类的锚点守护会失准。处置：要么去掉该标注，要么改本类让它按新口径计数。命中:\n  - "
                        + String.join("\n  - ", breaking));

        // ---- 模块集必须与 MODULES 完全一致（少一个 = 求和偏小；多一个 = 有模块没被守护）----
        Map<String, Integer> perModule = countModules(root);
        assertEquals(MODULES.size(), perModule.size(),
                "参与求和的模块数与 MODULES 不一致 —— 磁盘上存在却未被计入（或反之）的模块会让求和系统性偏小。"
                        + "实测=" + perModule.keySet() + " ; MODULES=" + MODULES);
        for (String m : MODULES) {
            assertTrue(perModule.containsKey(m), "MODULES 里的模块在磁盘上不存在或测试目录为空: " + m);
            assertTrue(perModule.get(m) > 0, "模块 " + m + " 数出 0 个 @Test —— 扫描口径坏了，而不是真的没测试");
        }
        int total = perModule.values().stream().mapToInt(Integer::intValue).sum();

        // ---- 逐份文档核对锚点 ----
        List<String> problems = auditAnchors(root, MODULES, GUARDED_DOCS);

        // ---- 可复现：两次独立扫描必须一致 ----
        Map<String, Integer> again = countModules(root);
        assertEquals(perModule, again, "两次独立扫描得到的逐模块计数不一致 —— 计数不可复现（依赖遍历顺序/缓存）");

        System.out.println("[DY-DOC-TOTAL-ANCHOR] 源码级逐模块=" + perModule + " 合计=" + total);

        assertTrue(problems.isEmpty(),
                "文档的「全量回归计数」锚点与实测脱钩 —— 请更新文档锚点"
                        + "（最新一条应写 `TOTAL " + total + " failures=0 errors=0 skipped=0`）。\n"
                        + "⇒ 为什么需要这一步：本仓 2026-09-30 已发生过一次 —— 真实 TOTAL 1211，"
                        + "而 4 处锚点写的是 1204（只加了分页纪律 4 例，漏了 ContractFreeze +1 /"
                        + " D2 E2E +3 / 概率型门禁 +3），当时全部门禁仍绿。\n"
                        + "⇒ 处置：改文档锚点（不要改本测试的期望值去迁就旧文档）。\n"
                        + "命中:\n  - " + String.join("\n  - ", problems));
    }

    // ==================================================================
    // ② 反向验证：把锚点改错必须被抓到；改对必须放行（两个方向都证）
    // ==================================================================

    @Test
    void gate_turns_red_when_doc_total_drifts_and_green_when_it_matches(@TempDir Path tmp) throws IOException {
        List<String> modules = List.of("dy-common", "dy-app");
        List<String> docs = List.of("README.md");

        // 造一棵合成仓：dy-common 2 例 + dy-app 3 例 = 5
        writeTest(tmp, modules.get(0), "demo/ATest.java", 2);
        writeTest(tmp, modules.get(1), "demo/BTest.java", 3);
        int expected = 5;
        assertEquals(expected, countModules(tmp, modules).values().stream().mapToInt(Integer::intValue).sum(),
                "合成仓的源码级计数与构造意图不符 —— 反向验证的前提不成立");

        // ---- 方向一：锚点正确 ⇒ 必须放行 ----
        writeDoc(tmp, "README.md", "TOTAL 5 failures=0 errors=0 skipped=0");
        List<String> ok = auditAnchors(tmp, modules, docs);
        assertTrue(ok.isEmpty(), "锚点写对了却仍被判红 —— 判据是坏的（假红），反向验证失败: " + ok);

        // ---- 方向二：锚点漂移（少算）⇒ 必须被抓到，且要指名文档与两个数字 ----
        writeDoc(tmp, "README.md", "TOTAL 4 failures=0 errors=0 skipped=0");
        List<String> drifted = auditAnchors(tmp, modules, docs);
        assertFalse(drifted.isEmpty(), "锚点写成 TOTAL 4（实测 5）却仍然放行 —— 判据没有牙齿！");
        String joined = String.join("\n", drifted);
        assertTrue(joined.contains("README.md"), "失败信息里没指名是哪份文档: " + joined);
        assertTrue(joined.contains("4") && joined.contains("5"),
                "失败信息里没给出「锚点值」与「实测值」两个数字，无法照它修: " + joined);

        // ---- 方向三：锚点声称"回归有失败"⇒ 必须被抓到（failure 半句也是活断言）----
        writeDoc(tmp, "README.md", "TOTAL 5 failures=1 errors=0 skipped=0");
        List<String> withFailures = auditAnchors(tmp, modules, docs);
        assertFalse(withFailures.isEmpty(), "锚点自称 failures=1 却仍放行 —— 「回归通过」这半句没有被守护");

        // ---- 方向四：历史锚点倒退（后面比前面小）⇒ 必须被抓到 ----
        writeDoc(tmp, "README.md",
                "TOTAL 5 failures=0 errors=0 skipped=0\nTOTAL 11 failures=0 errors=0 skipped=0\n"
                        + "TOTAL 6 failures=0 errors=0 skipped=0");
        List<String> regressed = auditAnchors(tmp, modules, docs);
        assertFalse(regressed.isEmpty(), "锚点值 5→11→6 倒退却仍放行 —— 单调性没有被守护");

        // ---- 方向五：一个锚点都没有 ⇒ 按【失败】处理，不按通过处理 ----
        writeDoc(tmp, "README.md", "本文件里没有任何计数锚点");
        List<String> missing = auditAnchors(tmp, modules, docs);
        assertFalse(missing.isEmpty(), "文档里一个锚点都解析不到却仍放行 —— 解析不到必须按失败处理");
    }

    // ==================================================================
    // 判据实现
    // ==================================================================

    /** 一个锚点：四个数字 + 行号 + 原文。 */
    private record Anchor(int total, int failures, int errors, int skipped, int line, String raw) {
        String describe() {
            return "L" + line + " `" + raw + "`";
        }
    }

    /**
     * 核对「锚点 vs 实测」。返回的问题清单为空 = 通过。
     * 刻意做成"纯函数 + 返回问题清单"（而不是直接抛断言），
     * 这样反向验证可以在合成仓上复用<b>同一段</b>判据 ——
     * 若反向验证另写一套判定，它证明的就不是"真判据有牙齿"，而是"另写的判定有效"。
     */
    private static List<String> auditAnchors(Path root, List<String> modules, List<String> docs) throws IOException {
        int total = countModules(root, modules).values().stream().mapToInt(Integer::intValue).sum();
        List<String> problems = new ArrayList<>();

        for (String rel : docs) {
            Path doc = root.resolve(rel);
            if (!Files.isRegularFile(doc)) {
                problems.add(rel + " : 被守护的文档不存在 —— 文档被删/改名后锚点无法被守护，必须同步改 GUARDED_DOCS");
                continue;
            }
            List<Anchor> anchors = parseAnchors(Files.readString(doc, StandardCharsets.UTF_8));
            if (anchors.isEmpty()) {
                problems.add(rel + " : 一个锚点都解析不到（期望形态 `TOTAL " + total
                        + " failures=0 errors=0 skipped=0`）—— 解析不到按【失败】处理，不按通过处理");
                continue;
            }
            for (Anchor a : anchors) {
                if (a.failures() != 0 || a.errors() != 0 || a.skipped() != 0) {
                    problems.add(rel + " : " + a.describe()
                            + " 声称本轮回归有失败/错误/跳过 —— 「全量回归通过」是这句锚点的另一半，"
                            + "留着它就是留着一句假断言");
                }
            }
            for (int i = 0; i + 1 < anchors.size(); i++) {
                if (anchors.get(i).total() > anchors.get(i + 1).total()) {
                    problems.add(rel + " : 锚点值倒退 —— " + anchors.get(i).describe()
                            + " 大于其后的 " + anchors.get(i + 1).describe()
                            + "（用例只增不减，" + anchors.get(i).total() + " → " + anchors.get(i + 1).total()
                            + " 只可能是漏加或写错）");
                }
            }
            Anchor latest = anchors.get(anchors.size() - 1);
            if (latest.total() != total) {
                problems.add(rel + " : 最新锚点写的 `TOTAL " + latest.total()
                        + "`（" + latest.describe() + "），实测源码级合计是 " + total
                        + "（差 " + (total - latest.total()) + "）");
            }
        }
        return problems;
    }

    /** 解析文档里全部 TOTAL 锚点（保序，含行号）。 */
    private static List<Anchor> parseAnchors(String text) {
        List<Anchor> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ANCHOR.matcher(lines[i]);
            while (m.find()) {
                out.add(new Anchor(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), i + 1, m.group()));
            }
        }
        return out;
    }

    /** 逐模块数 {@code @Test}（保 MODULES 顺序，便于照抄进文档的「逐模块」括注）。 */
    private static Map<String, Integer> countModules(Path root) {
        return countModules(root, MODULES);
    }

    private static Map<String, Integer> countModules(Path root, List<String> modules) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String m : modules) {
            Path tree = root.resolve(m).resolve("src/test");
            assertTrue(Files.isDirectory(tree), "模块测试目录不存在: " + tree);
            int n = 0;
            for (Path f : listJavaFiles(tree)) {
                n += countTestAnnotations(readString(f));
            }
            if (n > 0) {
                out.put(m, n);
            }
        }
        return out;
    }

    /**
     * 数 {@code @Test}。刻意<b>不</b>用笼统的 {@code contains("@Test")}：
     * 那会把 {@code @TestFactory} / 注释里出现的 {@code @Test} 一起算进去。
     * 与 dy-crypto 同名门禁的计数实现保持一致（同口径，两处结论才可互相印证）。
     */
    private static int countTestAnnotations(String javaSource) {
        Matcher m = Pattern.compile("(?m)^\\s*@Test\\b(?!\\w)").matcher(javaSource);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 找出会打破计数口径的标注（{@code @Disabled} 等），返回 {@code 模块/文件:行 注解} 形式。 */
    private static List<String> findCountBreakingAnnotations(Path root) {
        List<String> hits = new ArrayList<>();
        for (String m : MODULES) {
            Path tree = root.resolve(m).resolve("src/test");
            if (!Files.isDirectory(tree)) {
                continue;
            }
            for (Path f : listJavaFiles(tree)) {
                String[] lines = readString(f).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String trimmed = lines[i].stripLeading();
                    // 只认"注解使用"——行首的 @Xxx；注释里提到不算（第 55 条：判据不得把
                    // 「源码里描述某标注」判成「使用了该标注」）
                    if (!trimmed.startsWith("@")) {
                        continue;
                    }
                    for (String a : COUNT_BREAKING_ANNOTATIONS) {
                        if (trimmed.startsWith(a)
                                && (trimmed.length() == a.length()
                                || !Character.isJavaIdentifierPart(trimmed.charAt(a.length())))) {
                            hits.add(m + "/" + f.getFileName() + ":" + (i + 1) + " "
                                    + trimmed.split("\\(")[0].trim());
                        }
                    }
                }
            }
        }
        return hits;
    }

    // ==================================================================
    // 反向验证用的小工具（合成仓）
    // ==================================================================

    private static void writeTest(Path root, String module, String rel, int count) throws IOException {
        Path f = root.resolve(module).resolve("src/test/java").resolve(rel);
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder("class Demo {\n");
        for (int i = 0; i < count; i++) {
            sb.append("    @Test\n    void t").append(i).append("() {}\n");
        }
        sb.append("}\n");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeDoc(Path root, String rel, String body) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body + "\n", StandardCharsets.UTF_8);
    }

    private static String readString(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + f, e);
        }
    }

    private static List<Path> listJavaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历失败: " + root, e);
        }
    }

    // ==================================================================
    // 仓库根解析（cwd 无关：surefire 的 cwd 是模块 basedir，不是仓库根）
    // ==================================================================

    /**
     * 定位仓库根（= 同时含 {@code dy-crypto/} 与 {@code verification/} 的那一层，即 {@code skeleton/}）。
     * 两级回退，全部失败<b>直接红</b>（不 skip、不静默通过）：
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
        Path start = Path.of("").toAbsolutePath().normalize();
        Path cursor = start;
        for (int i = 0; i < 4 && cursor != null; i++, cursor = cursor.getParent()) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
        }
        throw new AssertionError("无法定位仓库根（需同时存在 dy-crypto/ 与 verification/）。"
                + "请用 -Ddy.repo.root=<abs> 显式指定。cwd=" + start);
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isDirectory(p.resolve("dy-crypto"))
                && Files.isDirectory(p.resolve("verification"));
    }
}