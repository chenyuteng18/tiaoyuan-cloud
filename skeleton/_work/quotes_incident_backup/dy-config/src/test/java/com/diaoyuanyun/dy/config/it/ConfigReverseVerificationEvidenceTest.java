package com.diaoyuanyun.dy.config.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 守护 A5 的「反向验证证据」与它证明的那些性质<b>没有悄悄退化</b>。
 *
 * <h2>为什么需要这个类</h2>
 * 反向验证（注入错误 → 断言必须红 → 恢复 → 必须绿）是本次任务的核心质量门禁，
 * 但它的产物一度只落在 {@code dy-config/target/} 下。{@code target} 是构建产物：
 * 一次 {@code mvn clean} 就整目录删除，证据随之消失，"曾经做过反向验证"便无法举证。
 *
 * <p>同类问题在 dy-audit 模块被独立发现并修复（单射性证明脚本从
 * {@code target/design-evidence/} 迁入 {@code src/test/resources/design-evidence/}），
 * 两个模块的结论一致：<b>证据必须与源码同源、受版本控制</b>。
 *
 * <h2>本类守护四件事</h2>
 * <ol>
 *   <li>证据文件在<b>版本化资源目录</b>中，且路径不含 {@code /target/} —— 防止有人把它挪回去；</li>
 *   <li>生产源码中<b>不残留</b>任何 {@code INJECTION} 标记 —— 注入必须被清理干净；</li>
 *   <li>缓存键仍以 {@code tenant_id} 为<b>结构组成部分</b>（注入 2 不能复活）；</li>
 *   <li>配置 DDL 仍对两张租户表同时具备 ENABLE 与 FORCE ROW LEVEL SECURITY
 *       （注入 1 不能复活）。</li>
 * </ol>
 *
 * <h2>为什么这几条不能只靠"证据文件写了"</h2>
 * 证据文件是<b>过去</b>的快照。若有人日后把 {@code FORCE ROW LEVEL SECURITY} 那几行
 * 删掉（理由通常是"本地跑 SQL 图省事"），文件里那句"注入 1 被抓住"仍然成立，
 * 但代码已经不设防了。故本类直接断言<b>当下生产源码</b>里这些性质仍在。
 *
 * <p>本类为纯静态检查，不连数据库，故命名以 {@code Test} 结尾但归入
 * {@code config-truth-source-gate} execution —— 它与 IT 同属"配置真相源门禁"，
 * 应在同一次门禁里跑，而不是被拆到别处。
 */
class ConfigReverseVerificationEvidenceTest {

    private static final String EVIDENCE_RELATIVE =
            "src/test/resources/design-evidence/reverse-verification-2026-09-20.txt";

    private static Path moduleRoot() {
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("dy-config"))
                    && Files.isRegularFile(cur.resolve("pom.xml"))) {
                return cur.resolve("dy-config");
            }
        }
        throw new IllegalStateException("未找到 dy-config 模块目录；当前工作目录=" + p);
    }

    /** 收集 src/main 下的全部文本文件（DDL / Java），供静态性质断言使用。 */
    private static List<Path> mainSources() {
        Path root = moduleRoot().resolve("src/main");
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.endsWith(".java") || n.endsWith(".sql");
                    })
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("遍历 src/main 失败: " + root, e);
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取失败: " + p, e);
        }
    }

    // ------------------------------------------------------------------
    // 1. 证据是版本化资产，不是构建产物
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反向验证证据在版本化资源目录中，不在 target/ 下（clean 不会抹掉它）")
    void reverse_verification_evidence_is_a_versioned_asset_not_a_build_artifact() {
        Path evidence = moduleRoot().resolve(EVIDENCE_RELATIVE);

        assertTrue(Files.isRegularFile(evidence),
                "A5 反向验证证据不在版本化资源目录中: " + evidence
                        + " —— 它曾被放在 target/ 下，一次 clean 就消失，等于证据丢失");

        String abs = evidence.toAbsolutePath().toString().replace('\\', '/');
        assertFalse(abs.contains("/target/"),
                "证据文件位于构建产物目录 target/ 下，会被 clean 删除: " + abs);

        String text = read(evidence);
        // 三栏表的三个要素缺一不可：注入了什么 / 期望在哪失败 / 实际哪条断言失败。
        for (String required : List.of("注入 1", "注入 2", "注入 3",
                "注入内容", "期望失败点", "实际失败断言", "BUILD SUCCESS")) {
            assertTrue(text.contains(required),
                    "证据文件缺少三栏表要素『" + required + "』—— 反向验证记录不完整，"
                            + "无法复核注入是否真的被抓住");
        }
        // 必须留下"恢复后回到绿"的结论，否则无法排除"注入了却没恢复"。
        assertTrue(text.contains("28, Failures: 0, Errors: 0"),
                "证据文件未记录恢复后的绿色基线（28 例全绿）—— "
                        + "无法证明注入已被清理，构建可能带着注入继续跑");
    }

    // ------------------------------------------------------------------
    // 2. 生产源码不残留注入标记
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生产源码不残留 INJECTION 标记（注入必须被清理干净，不得随提交进仓库）")
    void no_injection_marker_survives_in_production_sources() {
        List<String> offenders = new ArrayList<>();
        for (Path f : mainSources()) {
            if (read(f).contains("INJECTION")) {
                offenders.add(moduleRoot().relativize(f).toString().replace('\\', '/'));
            }
        }
        if (!offenders.isEmpty()) {
            fail("生产源码中残留 INJECTION 标记 —— 反向验证的注入没有被恢复干净。这些文件带着"
                    + "刻意引入的缺陷：\n  " + String.join("\n  ", offenders)
                    + "\n请恢复注入前的实现（本模块的注入点："
                    + "db/config/01_truth_source_ddl.sql、cache/ConfigCacheKey.java、"
                    + "service/ConfigServiceImpl.java、repository/JdbcConfigService.java）");
        }
    }

    // ------------------------------------------------------------------
    // 3. 注入 2 不能复活：缓存键必须含 tenant_id
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缓存键仍以 tenant_id 为结构组成部分（注入 2『去掉租户前缀』未复活）")
    void cache_key_still_structurally_contains_tenant_id() {
        Path key = moduleRoot().resolve(
                "src/main/java/com/diaoyuanyun/dy/config/cache/ConfigCacheKey.java");
        assertTrue(Files.isRegularFile(key), "缓存键类不存在: " + key);

        String text = read(key);

        // 必须是含 tenantId 的 record 组件 —— 组件即相等性的一部分，
        // 这是"跨租户命中 = 键不相等"这一结构性事实的来源。
        assertTrue(text.matches("(?s).*record\\s+ConfigCacheKey\\s*\\(\\s*String\\s+tenantId\\s*,"
                        + "\\s*String\\s+configKey\\s*\\).*"),
                "ConfigCacheKey 不再是 (tenantId, configKey) 的 record —— "
                        + "租户前缀若不再是键的结构组成部分，跨租户命中会重新成为可能");

        // fail-closed：空租户必须无法构造键，否则"无上下文"会退化成共享槽位。
        assertTrue(text.contains("tenantId == null || tenantId.isBlank()"),
                "缓存键丢失了 tenantId 非空校验 —— 空租户会退化成一个可被所有租户命中的共享槽位");

        // 不得出现自定义 equals/hashCode：record 的默认语义才保证"组件全参与比较"。
        // 若有人覆写成只比 configKey，注入 2 就复活了。
        assertFalse(text.contains("public boolean equals("),
                "ConfigCacheKey 覆写了 equals —— record 的相等性语义被改写，"
                        + "若只比较 configKey，跨租户会命中同一条缓存（这正是注入 2 的做法）");
        assertFalse(text.contains("public int hashCode()"),
                "ConfigCacheKey 覆写了 hashCode —— 同上，相等性语义被改写");
    }

    // ------------------------------------------------------------------
    // 4. 注入 1 不能复活：配置表必须同时 ENABLE + FORCE RLS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置 DDL 对两张租户表同时具备 ENABLE 与 FORCE ROW LEVEL SECURITY（注入 1 未复活）")
    void config_ddl_still_forces_rls_on_both_tenant_tables() {
        Path ddl = moduleRoot().resolve(
                "src/main/resources/db/config/01_truth_source_ddl.sql");
        assertTrue(Files.isRegularFile(ddl), "配置 DDL 不存在: " + ddl);

        String text = read(ddl);
        // 去掉注释行再判断，避免"注释里提了 FORCE"被误当成"真的写了 FORCE"。
        String effective = text.lines()
                .map(String::trim)
                .filter(l -> !l.startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        for (String table : List.of("app_config", "app_config_history")) {
            assertTrue(effective.contains("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY;"),
                    table + " 缺少 ENABLE ROW LEVEL SECURITY（非注释行）—— RLS 根本没开");
            assertTrue(effective.contains("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY;"),
                    table + " 缺少 FORCE ROW LEVEL SECURITY（非注释行）—— owner 会绕过策略。"
                            + "注入 1 正是删掉这一行，实测让租户读到 90 行（两个租户各 45 全暴露）");
        }

        // 策略必须同时约束读与写：只有 USING 的 INSERT 仍能写入别的租户。
        assertTrue(effective.contains("USING") && effective.contains("WITH CHECK"),
                "租户隔离策略缺少 USING 或 WITH CHECK —— 只写 USING 的话，"
                        + "INSERT/UPDATE 仍能把行写进别的租户（写侧没有校验）");
        assertTrue(effective.contains("NULLIF(current_setting('app.tenant_id', true), '')"),
                "策略未用 NULLIF 归一化空租户上下文 —— 未设置 app.tenant_id 时 "
                        + "current_setting 返回空串，行为会与 NULL 不一致");
    }

    // ------------------------------------------------------------------
    // 5. #42 空号的结构性保障不能被绕过
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#42 空号仍由 CHECK 约束在库层封死（不是靠 Java 判空）")
    void slot_42_stays_vacant_by_a_check_constraint() {
        Path ddl = moduleRoot().resolve(
                "src/main/resources/db/config/01_truth_source_ddl.sql");
        String text = read(ddl);

        assertTrue(text.contains("config_slot_no_42_stays_vacant"),
                "声明表丢失了 #42 空号 CHECK 约束 —— 空号封死将退化为『靠人不填』，"
                        + "而不是数据库层不可绕过");
        assertTrue(text.matches("(?s).*CHECK\\s*\\(\\s*config_no\\s*<>\\s*42\\s*\\).*"),
                "#42 的 CHECK 约束不是 config_no <> 42 —— 空号不再被封死");

        // 域上界也必须钉死，否则编号可以漂移到域外，PRD 的编号会整体错位。
        // 上界 48 = 当前已落 PRD 的最大编号（2026-09-21 由 46 放到 48，因新增 #48）。
        assertTrue(text.matches("(?s).*CHECK\\s*\\(\\s*config_no\\s*>=\\s*1\\s+AND\\s+config_no\\s*<=\\s*48\\s*\\).*"),
                "声明表缺少 config_no ∈ [1,48] 的域约束 —— 编号可漂移，PRD 编号将整体错位");
    }
}