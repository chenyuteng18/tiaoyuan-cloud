package com.diaoyuanyun.dy.app.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>迁移登记纪律门禁</b> —— 把"迁移写完了但登记坏了"这类问题挡在构建期。
 *
 * <h2>这个类为什么存在（两件刚发生过的事，都不是假想）</h2>
 * <ol>
 *   <li><b>V12 的描述超长</b>：写了 309 字符，而 {@code schema_migration.description}
 *       是 {@code VARCHAR(256)}（V1 建列）。真库以 <b>{@code 22001}</b>
 *       （「对于可变字符类型来说，值太长了(256)」）拒绝整条语句，Flyway 的事务性 DDL
 *       把整个 V12 回滚（库保持 V11 状态 —— 没造成损坏，但代价是一次完整构建失败
 *       加一轮人工定位）。这个坑的特征是<b>写迁移的人不会想到去数字符</b>，
 *       且 V10 的描述已有 167 字符（离 256 只剩 89）⇒ 它会重复被踩。</li>
 *   <li><b>V6 / V7 / V8 / V9 <u>从未</u>登记</b>：实测真库 {@code schema_migration}
 *       只有 6 行（V2/V3/V4/V5/V10/V11），四个迁移文件里根本没有登记语句。
 *       Flyway 自己的 {@code flyway_schema_history} 是准的，受损的是<b>项目自有登记表</b> ——
 *       任何"按 schema_migration 对账已应用迁移"的审计或运维脚本都会得出错误答案。
 *       已由 <b>V13</b> 补登记（含 V1）。</li>
 * </ol>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>描述长度 ≤ 256</b>：与列宽对齐。超长 ⇒ 迁移必然在真库失败。</li>
 *   <li><b>每个 V*.sql 的版本都被登记过</b>：要么它自己在文件里登记，要么由后序迁移
 *       （如 V13）补登记。<b>本条没有豁免清单</b> —— 见下方"为什么不用豁免"。</li>
 *   <li><b>登记幂等</b>（{@code ON CONFLICT ... DO NOTHING}）。</li>
 *   <li><b>版本号不重复</b>（重复会让 Flyway 启动即失败）。</li>
 * </ol>
 *
 * <h2>🛑 为什么本类<b>不</b>设"豁免清单"，而用"补登记必须真实存在"来兑现</h2>
 * 本类首版为 V1 设了一条豁免（"V1 执行时尚无登记表，故无登记"）。但豁免的麻烦在于
 * <b>它是静默的</b>：写下一个理由之后，就没有任何机器会去核对那个理由是否还成立 ——
 * 而 {@code RlsCoverageGateTest} 的类头已经把这个教训逐字写下来了
 * （"一条写错理由的豁免，比一条没有理由的豁免更危险"）。
 *
 * <p>故本条改为：<b>任何版本都必须出现在某处登记里</b>（自登记或他处补登记）。
 * 于是 V1/V6~V9 由 V13 兑现，而<b>如果有人把 V13 的补登记删掉，本类立刻红</b>——
 * 豁免变成了有牙齿的断言。副产物是豁免清单可以彻底为空。
 *
 * <h2>为什么用文本断言（与 {@code RlsMigrationScriptTest} 同款）</h2>
 * 这四件事都只在 SQL 字面量层面存在，缺失时没有运行时信号（除"迁移整体失败"这一
 * 代价高昂的信号）。文本断言能把它提前到 {@code mvn test}。
 *
 * <p>🛑 <b>本类不替代真库迁移</b>：它只保证"描述不超长、版本都被登记、写法幂等"，
 * 不保证"迁移能跑通"。后者由真库集成测试（如 {@code BandTelemetryEncryptionTest}
 * 启动时的 Flyway 迁移）回答。分工与 {@code BandSyncLogMigrationGateTest} 类头一致。
 */
@DisplayName("迁移登记纪律：description ≤ 256 · 版本全登记 · 幂等 · 版本号唯一")
class MigrationRegistryGateTest {

    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();
    private static final Path MIGRATION_DIR = SKEL.resolve("dy-app/src/main/resources/db/migration");

    /** {@code schema_migration.description} 的列宽（V1 建列；勿与 Flyway 表混淆）。 */
    private static final int DESCRIPTION_MAX = 256;

    /** 文件名里的版本号：{@code V12__name.sql} → {@code 12}。 */
    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * {@code schema_migration} 里的一个登记元组：{@code ('V12', 'desc')}。
     *
     * <p>描述允许由多个字面量用 {@code ||} 拼接（V5 就是这么写的），故第二个捕获组
     * 是一个或多个字符串字面量（中间可含 {@code ||}）。
     *
     * <p>🛑 用"逐元组"而不是"定位整个 INSERT 块"来解析，是因为 V13 一个文件里有
     * <b>两段</b> {@code INSERT}（第 1 节补 5 个版本、第 3 节登记自己）。
     * 按"块边界"解析会把两段混在一起，从而把 5 条描述拼成一条超长串 ⇒ 假红。
     * 逐元组解析对"一条 INSERT 多行 VALUES"和"多条 INSERT"都成立。
     */
    private static final Pattern REGISTRY_TUPLE = Pattern.compile(
            "\\(\\s*'(V\\d+)'\\s*,\\s*((?:'(?:[^']|'')*'\\s*(?:\\|\\|\\s*)?)+)\\)");

    /** 元组第二个字段里的单个字符串字面量。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    /** 剥掉注释行，只留可执行 SQL（与 {@code RlsMigrationScriptTest} 同款做法）。 */
    private static String code(String sql) {
        return sql.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 全部 {@code V*.sql}（排除 {@code .bak-*} 备份件 —— 它们不被 Flyway 扫描）。 */
    private static List<Path> migrations() {
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            List<Path> out = new ArrayList<>();
            s.filter(Files::isRegularFile)
                    .filter(p -> FILE_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一个登记元组的解析结果。 */
    private record Registry(String version, String description, String sourceFile) {
    }

    /** 某迁移文件里全部的登记元组（已剥注释 ⇒ V1 的注释示例不会被误取）。 */
    private static List<Registry> registriesIn(Path p) {
        String c = code(read(p));
        List<Registry> out = new ArrayList<>();
        Matcher t = REGISTRY_TUPLE.matcher(c);
        while (t.find()) {
            String version = t.group(1);
            StringBuilder desc = new StringBuilder();
            Matcher lit = STRING_LITERAL.matcher(t.group(2));
            while (lit.find()) {
                desc.append(lit.group(1).replace("''", "'"));
            }
            out.add(new Registry(version, desc.toString(), p.getFileName().toString()));
        }
        return out;
    }

    /** 全仓登记过的版本号集合（自登记 ∪ 他处补登记）。 */
    private static Set<Integer> allRegisteredVersions() {
        Set<Integer> out = new TreeSet<>();
        for (Path p : migrations()) {
            for (Registry r : registriesIn(p)) {
                out.add(Integer.parseInt(r.version.substring(1)));
            }
        }
        return out;
    }

    // ==================================================================
    // 一、description 长度
    // ==================================================================

    @Test
    @DisplayName("🛑 每个登记的 description 必须 ≤ 256 字符（schema_migration.description 的列宽）")
    void every_registry_description_fits_the_column() {
        List<String> offenders = new ArrayList<>();
        Map<String, Integer> widths = new LinkedHashMap<>();
        int counted = 0;
        for (Path p : migrations()) {
            for (Registry r : registriesIn(p)) {
                counted++;
                int len = r.description().length();
                widths.put(r.version() + "@" + r.sourceFile(), len);
                if (len > DESCRIPTION_MAX) {
                    offenders.add(r.version() + "@" + r.sourceFile() + " = " + len + " 字符");
                }
            }
        }
        // 防退化：一个登记都没解析到就"全绿"是本类最坏的失效模式（空跑即绿）
        assertTrue(counted >= 11,
                "只解析到 " + counted + " 条登记 —— 正则或目录基准可能已失效。"
                        + "这条断言防止本门禁退化成'解析不到东西所以永远绿'");
        assertTrue(offenders.isEmpty(),
                "🛑 以下登记的描述超过 " + DESCRIPTION_MAX + " 字符，真库会以 22001 拒绝整条语句"
                        + "（Flyway 事务性 DDL 会回滚整个迁移）：" + offenders
                        + " 全部长度=" + widths
                        + "。请压缩描述 —— 详细理由写在迁移文件头，不要塞进 description。");
    }

    // ==================================================================
    // 二、版本登记完整性（无豁免清单）
    // ==================================================================

    @Test
    @DisplayName("🛑 每个 V*.sql 的版本都必须被登记过（自登记或由后序迁移补登记，无豁免）")
    void every_migration_version_is_registered_somewhere() {
        Set<Integer> registered = allRegisteredVersions();
        List<String> missing = new ArrayList<>();
        for (Path p : migrations()) {
            Matcher fm = FILE_VERSION.matcher(p.getFileName().toString());
            // 🛑 必须先 matches() 再取 group(1)：未成功匹配前调用 group() 会抛
            //    IllegalStateException，把"断言失败"变成"测试自身报错"，掩盖真实结论
            //    （本门禁首轮正是这样失败的）。
            assertTrue(fm.matches(), "文件名未被版本正则匹配（migrations() 的过滤条件已失效）: " + p);
            int v = Integer.parseInt(fm.group(1));
            if (!registered.contains(v)) {
                missing.add(p.getFileName().toString() + "（V" + v + " 未出现在任何登记里）");
            }
        }
        assertTrue(missing.isEmpty(),
                "🛑 以下迁移版本未被登记 ⇒ 项目自有的 schema_migration 会缺行，"
                        + "而『已应用迁移』对账会给出错误答案（Flyway 表对、登记表错，"
                        + "两者分叉后没人知道该信哪个）：" + missing
                        + " || 全仓已登记版本=" + registered
                        + "。修法：在新迁移里补登记（勿改已应用迁移的文件 —— 校验和不可变，"
                        + "改了会让 Flyway 启动即失败）。参考 V13__backfill_schema_migration_registry.sql。");
    }

    @Test
    @DisplayName("🛑 补登记必须真实存在：V13 的补登记被删掉时本条必须红（豁免要有牙齿）")
    void historical_backfill_is_still_in_place() {
        // 这条断言的存在意义：上面那条"都必须被登记过"如果靠一条豁免清单来放行
        // V1/V6~V9，那豁免就是静默的 —— 删掉补登记也没人知道。
        // 现在豁免被换成"补登记必须真实存在"，本用例就是那个核查点。
        Set<Integer> registered = allRegisteredVersions();
        for (int v : new int[]{1, 6, 7, 8, 9}) {
            assertTrue(registered.contains(v),
                    "V" + v + " 不在任何登记里 —— 它历史欠债，靠 V13 补登记兑现；"
                            + "若 V13 被删或被改坏，本条会先红，从而保住'登记表 = Flyway 表'。"
                            + "全仓已登记版本=" + registered);
        }
    }

    // ==================================================================
    // 三、登记写法
    // ==================================================================

    @Test
    @DisplayName("登记必须是幂等写法（ON CONFLICT DO NOTHING），否则重跑即撞主键")
    void registry_insert_is_idempotent() {
        for (Path p : migrations()) {
            String c = code(read(p));
            if (!c.contains("INSERT INTO schema_migration")) {
                continue;
            }
            assertTrue(c.contains("ON CONFLICT"),
                    p.getFileName() + " 的登记缺 ON CONFLICT —— 同版本重复登记会撞主键");
            assertTrue(c.contains("DO NOTHING"),
                    p.getFileName() + " 的 ON CONFLICT 应为 DO NOTHING（登记只写一次，不改写既有行）");
        }
    }

    @Test
    @DisplayName("🛑 登记描述不得为空（空描述等于没有登记）")
    void registry_descriptions_are_not_blank() {
        List<String> blanks = new ArrayList<>();
        for (Path p : migrations()) {
            for (Registry r : registriesIn(p)) {
                if (r.description().isBlank()) {
                    blanks.add(r.version() + "@" + r.sourceFile());
                }
            }
        }
        assertTrue(blanks.isEmpty(),
                "以下登记的描述为空 —— schema_migration 的价值全在于描述，空描述等于没有登记：" + blanks);
    }

    // ==================================================================
    // 四、版本号唯一
    // ==================================================================

    @Test
    @DisplayName("🛑 迁移版本号不得重复（重复会让 Flyway 启动即失败）")
    void migration_versions_are_unique() {
        List<Path> all;
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
        Set<String> nonMatching = new LinkedHashSet<>();
        for (Path p : all) {
            Matcher m = FILE_VERSION.matcher(p.getFileName().toString());
            if (!m.matches()) {
                nonMatching.add(p.getFileName().toString());
                continue;
            }
            byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new ArrayList<>())
                    .add(p.getFileName().toString());
        }
        List<String> dup = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " → " + e.getValue())
                .toList();
        assertTrue(dup.isEmpty(), "🛑 迁移版本号重复（Flyway 会以 'Found more than one migration "
                + "with version' 拒绝启动）：" + dup);

        // ⚠️ 已知残留：V1__baseline_tenant_rls.sql.bak-20260924 与 V1__*.sql 同版本号段。
        //    它以 .bak-* 结尾，不匹配 ^V\d+__.*\.sql$ ⇒ 不被本类与 Flyway 扫描。
        //    这里显式断言它确实落在"非匹配"集合里，把这个依赖写下来而不是留给运气：
        //    一旦有人把它改名为 .sql，migration_versions_are_unique 会立刻红。
        assertEquals(1, byVersion.getOrDefault(1, List.of()).size(),
                "版本 1 有多份候选迁移文件 —— 若 .bak 件被改名为 .sql，Flyway 会启动即失败");
        assertTrue(nonMatching.stream().anyMatch(n -> n.startsWith("V1__") && n.contains(".bak")),
                "预期的 V1 .bak 残留未被识别为非迁移文件（非匹配集合=" + nonMatching
                        + "）。若该文件已被清理，请更新本条断言 —— "
                        + "它存在的意义是证明'同版本号 .bak 不会污染 Flyway 扫描'这件事仍成立");
    }
}