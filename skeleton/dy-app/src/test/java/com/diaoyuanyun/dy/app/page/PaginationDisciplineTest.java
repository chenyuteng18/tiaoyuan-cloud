package com.diaoyuanyun.dy.app.page;

import com.diaoyuanyun.dy.common.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页纪律门禁（本仓第 58 条）—— 分页越界语义全局只有一个实现，且与契约同源。
 *
 * <h2>它堵的洞</h2>
 * 契约 {@code x-global-conventions.pagination} 原句只声明<b>约束</b>：
 * 「请求 {@code ?page=<int>&page_size=<int≤100>}；响应 {@code data.{items[], total, page, page_size}}」
 * —— <b>没说越界怎么办</b>。于是同一份契约下两个列表端点各走一路：
 * <pre>
 *   A3 GET /stores   page_size=101 ⇒ 400 VALIDATION_FAILED（超界直接拒）
 *   D2 GET /visits   page_size=101 ⇒ Math.min(Math.max(101, 1), 100) = 100，
 *                                    静默夹逼后返回 200
 * </pre>
 * 两者的 200 响应体、{@code tsc}、{@code vite}、全部既有门禁<b>都绿</b>，
 * 客户端请求了 101 条、拿到 100 条、<b>无从察觉</b>。这与第 57 条 {@code X-Trace-Id}
 * 同族：<b>契约写下的协议与各端实现的协议是两件事，中间丢项不报错。</b>
 *
 * <h2>四道断言</h2>
 * <ol>
 *   <li>{@link #no_production_code_hand_rolls_pagination_clamping()} —— 生产源码里
 *       不得再有第二处夹逼 / 静默纠正（唯一实现是 {@link PageQuery}）；</li>
 *   <li>{@link #page_query_constants_agree_with_the_contract()} —— {@link PageQuery}
 *       的上下界与缺省值必须逐字等于契约 {@code x-api-protocol.pagination}；</li>
 *   <li>{@link #page_query_rejects_instead_of_clamping()} —— 行为断言：越界抛
 *       {@code VALIDATION_FAILED}，合法值原样通过，{@code null} 走缺省；</li>
 *   <li>{@link #scanner_turns_red_when_clamping_is_injected()} —— <b>反向验证</b>：
 *       把夹逼实现现写进 {@code @TempDir}，用<b>同一个扫描器</b>求值，
 *       断言它确实变红。证明断言 ① 不是恒绿摆设（硬纪律 #7）。</li>
 * </ol>
 */
class PaginationDisciplineTest {

    private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";

    // ------------------------------------------------------------------
    // 一、生产源码不得手写夹逼 / 静默纠正
    // ------------------------------------------------------------------

    /**
     * 扫描器 —— 返回"手写分页夹逼 / 静默纠正"的违规行（{@code 文件:行} 文本）。
     *
     * <p>🛑 为什么必须写成可测函数而不是内联在断言里：第 ④ 条反向验证要用
     * <b>同一个扫描器</b>跑注入副本。若扫描器只作用于真实仓库，
     * "它到底有没有牙齿"就无法证明 —— 只能在报告里自述，自述不算证据。
     *
     * <p>判别模式（逐条对应历史上出现过或被本仓规则禁止的写法）：
     * <ul>
     *   <li>{@code Math.min(Math.max(...page...))} —— 夹逼（D2 原实现）；</li>
     *   <li>{@code Math.max(Math.min(...page...))} —— 反写夹逼；</li>
     *   <li>{@code page < 1 ?} —— 静默纠正页码；</li>
     *   <li>{@code page == null ? 1} / {@code pageSize == null ? 20} —— 手写分页缺省
     *       （缺省值必须来自契约，不得各处写死）。</li>
     * </ul>
     *
     * @param repoRoot 仓库根
     * @return 违规描述列表（空 = 干净）
     */
    static List<String> scanForHandRolledPagination(Path repoRoot) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path dir : productionSourceRoots(repoRoot)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String raw = lines.get(i);
                        // 剥掉行注释与块注释起止行，避免"注释里讲到夹逼"被判红（第 55 条：太窄 ⇒ 假红）
                        String code = stripComment(raw);
                        if (code.isBlank()) {
                            continue;
                        }
                        // 唯一合法实现豁免
                        if (f.getFileName().toString().equals("PageQuery.java")) {
                            continue;
                        }
                        if (CLAMP_MIN_MAX.matcher(code).find()
                                || CLAMP_MAX_MIN.matcher(code).find()
                                || SILENT_PAGE_FIX.matcher(code).find()
                                || HAND_ROLLED_DEFAULT.matcher(code).find()) {
                            violations.add(repoRoot.relativize(f) + ":" + (i + 1) + "  " + raw.trim());
                        }
                    }
                }
            }
        }
        return violations;
    }

    /** {@code Math.min(Math.max(...)} 且该行涉及 page / pageSize。 */
    private static final Pattern CLAMP_MIN_MAX =
            Pattern.compile("Math\\.min\\s*\\(\\s*Math\\.max\\s*\\([^)]*[Pp]age");

    /** {@code Math.max(Math.min(...)} 且该行涉及 page / pageSize。 */
    private static final Pattern CLAMP_MAX_MIN =
            Pattern.compile("Math\\.max\\s*\\(\\s*Math\\.min\\s*\\([^)]*[Pp]age");

    /** {@code page < 1 ?} —— 静默纠正页码为 1。 */
    private static final Pattern SILENT_PAGE_FIX =
            Pattern.compile("\\bpage\\w*\\s*<\\s*1\\s*\\?");

    /** {@code page == null ? 1} / {@code pageSize == null ? 20} —— 手写分页缺省。 */
    private static final Pattern HAND_ROLLED_DEFAULT =
            Pattern.compile("\\bpage\\w*\\s*[!=]=\\s*null\\s*\\?\\s*\\d");

    /**
     * 剥行注释与块注释。
     *
     * <p>🛑 三次形态都要处理，否则就是第 55 条（判据太窄 ⇒ 假红 ⇒ 判据被删）：
     * <ol>
     *   <li>行首为 {@code //} 的行注释 → 整行丢弃；</li>
     *   <li>行首为 {@code *} 的 <b>块注释 / Javadoc 内部行</b> → 整行丢弃。
     *       本条判据首跑就因漏了这一形态而红：命中行是
     *       {@code FulfillmentController} 的 Javadoc 里那句
     *       「{@code Math.min(Math.max(pageSize, 1), 100)} 静默夹逼后返回 200」——
     *       那是<b>描述缺陷</b>的注释，不是缺陷本身；</li>
     *   <li>行内 {@code //} / {@code /*} 之后的部分 → 截断。</li>
     * </ol>
     */
    private static String stripComment(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
            return "";
        }
        int idx = line.indexOf("//");
        String s = idx >= 0 ? line.substring(0, idx) : line;
        int star = s.indexOf("/*");
        if (star >= 0) {
            s = s.substring(0, star);
        }
        return s;
    }

    @Test
    void no_production_code_hand_rolls_pagination_clamping() throws IOException {
        Path root = locateRepoRoot();
        List<String> violations = scanForHandRolledPagination(root);
        assertTrue(violations.isEmpty(),
                "生产源码里出现了手写的分页夹逼 / 静默纠正 —— 分页越界处置全局只允许"
                        + "一个实现（dy-common 的 PageQuery，契约 over-range-policy=reject-400）。"
                        + "手写夹逼会让客户端拿到被悄悄改小的页大小而不报错（本仓第 58 条）：\n  "
                        + String.join("\n  ", violations));
    }

    // ------------------------------------------------------------------
    // 二、唯一实现与契约同源
    // ------------------------------------------------------------------

    @Test
    void page_query_constants_agree_with_the_contract() throws IOException {
        Map<String, Object> doc = loadContract();
        Map<String, Object> proto = cast(doc.get("x-api-protocol"));
        Map<String, Object> pag = cast(proto.get("pagination"));

        assertEquals((int) pag.get("page-size-max"), PageQuery.MAX_PAGE_SIZE,
                "PageQuery.MAX_PAGE_SIZE 与契约 pagination.page-size-max 不一致 ⇒ "
                        + "实现与契约有两个上界，必分叉");
        assertEquals((int) pag.get("page-size-default"), PageQuery.DEFAULT_PAGE_SIZE,
                "PageQuery.DEFAULT_PAGE_SIZE 与契约 pagination.page-size-default 不一致");
        assertEquals((int) pag.get("page-min"), PageQuery.MIN_PAGE,
                "PageQuery.MIN_PAGE 与契约 pagination.page-min 不一致");
        assertEquals((int) pag.get("page-default"), PageQuery.DEFAULT_PAGE,
                "PageQuery.DEFAULT_PAGE 与契约 pagination.page-default 不一致");

        // 越界处置：实现必须是"拒"，而不是任何形式的夹逼
        String policy = String.valueOf(pag.get("over-range-policy"));
        assertEquals("reject-400", policy,
                "契约 pagination.over-range-policy 必须是 reject-400（本仓第 58 条唯一合法取值）");
    }

    // ------------------------------------------------------------------
    // 三、行为断言（不依赖真库 —— PageQuery 是纯函数）
    // ------------------------------------------------------------------

    @Test
    void page_query_rejects_instead_of_clamping() {
        // ① 越界：page_size > 上界 —— 必须拒，绝不得夹逼成上界
        com.diaoyuanyun.dy.common.exception.BizException overSize =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.diaoyuanyun.dy.common.exception.BizException.class,
                        () -> PageQuery.of(1, 101),
                        "page_size=101 越界必须拒（不得静默夹逼为 100）");
        assertEquals(com.diaoyuanyun.dy.common.result.ErrorCode.VALIDATION_FAILED.getCode(),
                overSize.getCode(),
                "越界应报 VALIDATION_FAILED（契约 pagination.over-range-error）");

        // ② 越界：page_size < 下界
        org.junit.jupiter.api.Assertions.assertThrows(
                com.diaoyuanyun.dy.common.exception.BizException.class,
                () -> PageQuery.of(1, 0),
                "page_size=0 越界必须拒");

        // ③ 越界：page < 下界 —— 不得"当作没传"兜底成 1
        org.junit.jupiter.api.Assertions.assertThrows(
                com.diaoyuanyun.dy.common.exception.BizException.class,
                () -> PageQuery.of(0, 20),
                "page=0 必须拒（把它当作没传是另一种静默改动：会让『第 0 页』与『第 1 页』"
                        + "拿到同一结果而不报错）");

        // ④ 合法值原样通过
        PageQuery ok = PageQuery.of(3, 50);
        assertEquals(3, ok.page(), "合法 page 必须原样通过");
        assertEquals(50, ok.pageSize(), "合法 page_size 必须原样通过");

        // ⑤ 真没传 ⇒ 缺省
        PageQuery def = PageQuery.of(null, null);
        assertEquals(PageQuery.DEFAULT_PAGE, def.page(), "page 缺省应为契约 page-default");
        assertEquals(PageQuery.DEFAULT_PAGE_SIZE, def.pageSize(),
                "page_size 缺省应为契约 page-size-default");

        // ⑥ 边界：恰好等于上界 ⇒ 合法（> 才拒，>= 不拒）
        assertEquals(PageQuery.MAX_PAGE_SIZE, PageQuery.of(1, PageQuery.MAX_PAGE_SIZE).pageSize(),
                "page_size 恰好等于上界必须合法 —— 判据不得写成 ≥（第 55 条：太窄 ⇒ 假红）");
    }

    // ------------------------------------------------------------------
    // 四、反向验证 —— 证明断言 ① 有牙齿
    // ------------------------------------------------------------------

    @Test
    void scanner_turns_red_when_clamping_is_injected(@TempDir Path tmp) throws IOException {
        // 造一个"仓库外形"：<tmp>/dy-app/src/main/java/.../Injected.java
        Path pkg = tmp.resolve("dy-app/src/main/java/com/injected");
        Files.createDirectories(pkg);

        // 注入 ① 夹逼（D2 的原实现形态）
        Files.writeString(pkg.resolve("Clamp.java"),
                "package com.injected;\n"
                        + "class Clamp {\n"
                        + "  int s(Integer pageSize) {\n"
                        + "    return pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);\n"
                        + "  }\n"
                        + "}\n", StandardCharsets.UTF_8);

        // 注入 ② 静默纠正页码
        Files.writeString(pkg.resolve("FixPage.java"),
                "package com.injected;\n"
                        + "class FixPage {\n"
                        + "  int p(Integer page) { return page == null || page < 1 ? 1 : page; }\n"
                        + "}\n", StandardCharsets.UTF_8);

        List<String> violations = scanForHandRolledPagination(tmp);
        assertTrue(violations.size() >= 2,
                "注入两份夹逼实现后扫描器只抓到 " + violations.size() + " 处 —— 判据没有牙齿。"
                        + "抓到: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Clamp.java")),
                "扫描器未抓到 Math.min(Math.max(...)) 夹逼: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("FixPage.java")),
                "扫描器未抓到 page < 1 ? 的静默纠正: " + violations);

        // 注入 ③ 注释里提到夹逼 ⇒ **不得**被判红（第 55 条：太窄 ⇒ 假红）
        Path clean = tmp.resolve("dy-web/src/main/java/com/clean");
        Files.createDirectories(clean);
        Files.writeString(clean.resolve("DocOnly.java"),
                "package com.clean;\n"
                        + "class DocOnly {\n"
                        + "  // 不要写 Math.min(Math.max(pageSize, 1), 100) 这种夹逼\n"
                        + "  // page < 1 ? 1 : page 也是禁止的\n"
                        + "  int noop() { return 0; }\n"
                        + "}\n", StandardCharsets.UTF_8);

        // 注入 ④ Javadoc / 块注释形态 —— 本条判据首跑的复发形态。
        //   真实踩点：FulfillmentController 的 Javadoc 里有一句
        //   「{@code Math.min(Math.max(pageSize, 1), 100)} 静默夹逼后返回 200」，
        //   那是**描述缺陷**的注释。判据不剥 Javadoc 内部行（以 ` * ` 开头）就会把它判红。
        Files.writeString(clean.resolve("JavadocOnly.java"),
                "package com.clean;\n"
                        + "class JavadocOnly {\n"
                        + "  /**\n"
                        + "   * 本方法此前用\n"
                        + "   * {@code Math.min(Math.max(pageSize, 1), 100)} 静默夹逼后返回 200。\n"
                        + "   * 也曾经写成 page == null || page < 1 ? 1 : page。\n"
                        + "   */\n"
                        + "  int noop() { return 0; }\n"
                        + "}\n", StandardCharsets.UTF_8);

        List<String> after = scanForHandRolledPagination(tmp);
        assertTrue(after.stream().noneMatch(v -> v.contains("DocOnly.java")),
                "注释里讨论夹逼被判红 = 判据太窄（第 55 条：假红 ⇒ 判据被删）。"
                        + "命中的行: " + after);
        assertTrue(after.stream().noneMatch(v -> v.contains("JavadocOnly.java")),
                "🛑 Javadoc / 块注释（以 ` * ` 开头）里提到夹逼被判红 —— 这正是本条判据"
                        + "首跑复发的形态（第 55 条第二次）：命中 FulfillmentController 的"
                        + "Javadoc 里那句描述缺陷的 {code Math.min(Math.max(...))}。"
                        + "命中的行: " + after);
        assertTrue(after.size() >= 2,
                "加入两份纯注释文件后，先前注入的两处真夹逼仍须被抓到（不得因放宽注释而变钝）: "
                        + after);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 生产源码根清单 —— <b>只含各模块的 {@code src/main/java}</b>。
     *
     * <p>🛑 刻意不扫 {@code src/test}（测试里会故意构造夹逼做反向验证）、
     * 不扫 {@code target}（编译产物），也不扫 {@code _work}（历史备份区 ——
     * 里面存着<b>修复前</b>的旧副本，扫它会制造恒定假红，正是第 55 条的形态）。
     */
    static List<Path> productionSourceRoots(Path repoRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        Path skeleton = repoRoot.resolve("skeleton");
        Path base = Files.isDirectory(skeleton) ? skeleton : repoRoot;
        try (Stream<Path> s = Files.list(base)) {
            for (Path m : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
                String name = m.getFileName().toString();
                if (name.startsWith("_") || name.equals("target")
                        || name.equals("frontends") || name.equals("node_modules")) {
                    continue;
                }
                Path src = m.resolve("src/main/java");
                if (Files.isDirectory(src)) {
                    roots.add(src);
                }
            }
        }
        return roots;
    }

    private static Path locateRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve(OPENAPI_REL))) {
                return p;
            }
            p = p.getParent();
        }
        throw new AssertionError("无法定位仓库根（需存在 " + OPENAPI_REL + "），"
                + "起点 user.dir=" + System.getProperty("user.dir"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadContract() throws IOException {
        Path root = locateRepoRoot();
        try (InputStream in = Files.newInputStream(root.resolve(OPENAPI_REL))) {
            Object o = new Yaml().load(in);
            assertNotNull(o, "契约解析为空 —— 不得静默退化成空集通过");
            return (Map<String, Object>) o;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        assertNotNull(o, "契约结构缺失（期望 Map）");
        if (!(o instanceof Map)) {
            throw new AssertionError("契约结构类型不符（期望 Map，实为 "
                    + o.getClass().getName() + "）");
        }
        Map<String, Object> m = (Map<String, Object>) o;
        // 保持顺序，便于断言信息可读
        return new LinkedHashMap<>(m);
    }
}