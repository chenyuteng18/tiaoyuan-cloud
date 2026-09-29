package com.diaoyuanyun.dy.app.band.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>敏感登记表 ↔ 迁移 SQL ↔ V3 的 metric 词表 三方交叉门禁</b>（B-1 收尾）。
 *
 * <h2>这个门禁堵的是什么（一个真实发生过的分叉）</h2>
 * 敏感清单在<b>两处</b>独立存在，且<b>必须恒等</b>：
 * <pre>
 *   ① Java 侧：{@code TelemetrySensitivity.BY_METRIC} / {@code NOT_REGISTERED}
 *   ② SQL 侧：{@code V12} 的 {@code ck_bt_value_enc_envelope} 两个 OR 分支里的 metric 清单
 * </pre>
 * 它们一旦分叉，症状是<b>某类 metric 的写入每次都被 23514 拒绝</b>，而错误信息里
 * 只看得出"约束违规"、看不出是"两份清单漂移"——这正是本项目登记的失效模式
 * （{@code RlsCoverageGateTest} 的"三方交叉断言"用同一个思路堵另一种同类洞）。
 *
 * <p>还有第三份：{@code V3} 的 {@code metric IN (...)} 13 值 CHECK —— 它是
 * "metric 可以取哪些值"的<b>最上游真相源</b>。若 Java 侧登记了一个 V3 里不存在的
 * metric（如曾被误登记的 {@code 'sport'}），那登记项永远不会被任何数据命中，
 * 而"合计应当等于 13"这条断言会立刻变红。本门禁把三者放在一起断言。
 *
 * <h2>为什么用文本断言而不是连真库</h2>
 * 与 {@code BandSyncLogMigrationGateTest} 同款：本类答"文件里写了什么"（静态、快、
 * 无论真库是否可达都能跑）。"迁移真的生效了吗"由 {@code BandTelemetryEncryptionTest}
 * 在真库上答。两者不可互相替代：
 * <ul>
 *   <li>只跑本类 ⇒ 漏"迁移没被执行"；</li>
 *   <li>只跑真库类 ⇒ 漏"文件被改坏了但当前数据恰好不需那一条"。</li>
 * </ul>
 *
 * <h2>🛑 路径基准</h2>
 * 测试工作目录 = 模块目录（{@code dy-app}），故 {@code Paths.get("..")} 上溯到 {@code skeleton} 根。
 * 与 {@code BandSyncLogMigrationGateTest} / {@code VerdictPersistenceBoundaryTest} 逐字一致。
 */
@DisplayName("B-1 · 敏感登记表 ↔ V12 门禁 ↔ V3 词表 三方交叉")
class TelemetrySensitivityMigrationGateTest {

    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();
    private static final Path MIGRATION_DIR = SKEL.resolve("dy-app/src/main/resources/db/migration");

    private static final Path V3 = MIGRATION_DIR.resolve("V3__band_telemetry_metric_long_table.sql");
    private static final Path V12 =
            MIGRATION_DIR.resolve("V12__band_telemetry_sensitivity_aligned_envelope_guard.sql");

    /** V12 里敏感侧清单的整段字面量（用于定位与提取）。 */
    private static final Pattern V12_SENSITIVE_BLOCK = Pattern.compile(
            "metric IN \\(([^)]*)\\)\\s*\\n?\\s*AND value_enc LIKE 'dy1:%'");

    /** V12 里非敏感侧清单的整段字面量。 */
    private static final Pattern V12_NONSENSITIVE_BLOCK = Pattern.compile(
            "metric IN \\(([^)]*)\\)\\s*\\n?\\s*AND value_enc ~ ");

    /** V3 的 metric CHECK 块（{@code CHECK (metric IN ( ... ))}）。 */
    private static final Pattern V3_METRIC_BLOCK = Pattern.compile(
            "metric\\s+VARCHAR\\(32\\)\\s+NOT NULL CHECK \\(metric IN \\(([^)]*)\\)\\)", Pattern.DOTALL);

    /**
     * 单引号包起来的 metric 标识符。
     *
     * <p>🛑 字符类必须含 {@code 0-9}：metric 词表里有 {@code 'spo2'}，
     * 而 {@code [a-z_]+} 会把数字吃掉 ⇒ 该值被静默漏掉，于是"两侧清单相等"这条断言
     * 会在两侧都漏掉同一个值的情况下<b>假绿</b>。本门禁首轮运行正是被这个缺陷骗过
     * （表现为 SQL 侧少 1 项、V3 侧 12 ≠ 13）—— 记在这里，因为下一个改本正则的人
     * 很可能想当然地把它写回 {@code [a-z_]}。
     */
    private static final Pattern QUOTED = Pattern.compile("'([a-z_0-9]+)'");

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "迁移文件不存在: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 剥掉注释行，只留可执行 SQL。
     *
     * <p>🛑 这一步是必需的，不是洁癖：V12 的文件头<b>刻意</b>逐字引用了被否决的写法
     * （{@code metric NOT IN (...)}），因为"为什么不用它"必须写在会读到它的地方。
     * 若直接对全文做反面断言，注释里的反面教材会被当成违规 ——
     * 于是下一个人会把注释删掉来让测试变绿，恰好丢掉最该保留的说明。
     * 与 {@code RlsMigrationScriptTest} 的做法同款。
     */
    private static String code(String sql) {
        return sql.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static Set<String> quotedIn(String block) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = QUOTED.matcher(block);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> extract(Pattern p, String sql, String what) {
        Matcher m = p.matcher(sql);
        assertTrue(m.find(), "在迁移里定位不到" + what + "的清单 —— "
                + "要么约束写法改了，要么本门禁的正则需同步更新（不要放宽正则，先确认约束本身是否应改）");
        return quotedIn(m.group(1));
    }

    // ==================================================================
    // 一、V12 的两条 SQL 清单 must 恒等于 Java 侧两侧
    // ==================================================================

    @Test
    @DisplayName("🛑 V12 的敏感侧清单 == TelemetrySensitivity.BY_METRIC 键集（逐值相等）")
    void sql_sensitive_list_equals_java_registry() throws IOException {
        Set<String> sqlSide = extract(V12_SENSITIVE_BLOCK, read(V12), "敏感侧");
        assertEquals(new TreeSet<>(TelemetrySensitivity.encryptedMetrics()), new TreeSet<>(sqlSide),
                "🛑 SQL 门禁的敏感清单与 Java 登记表 BY_METRIC 分叉 —— "
                        + "症状是某类 metric 的写入每次被 23514 拒绝，而错误里看不出是清单漂移。"
                        + "两侧必须同步修改。SQL=" + sqlSide
                        + " Java=" + TelemetrySensitivity.encryptedMetrics());
    }

    @Test
    @DisplayName("🛑 V12 的非敏感侧清单 == TelemetrySensitivity.NOT_REGISTERED 键集（逐值相等）")
    void sql_non_sensitive_list_equals_java_registry() throws IOException {
        Set<String> sqlSide = extract(V12_NONSENSITIVE_BLOCK, read(V12), "非敏感侧");
        assertEquals(new TreeSet<>(TelemetrySensitivity.notEncryptedMetrics()), new TreeSet<>(sqlSide),
                "🛑 SQL 门禁的非敏感清单与 Java 登记表 NOT_REGISTERED 分叉。SQL=" + sqlSide
                        + " Java=" + TelemetrySensitivity.notEncryptedMetrics());
    }

    // ==================================================================
    // 二、两侧并集 must 恒等于 V3 的 13 值词表（最上游真相源）
    // ==================================================================

    @Test
    @DisplayName("🛑 登记项 ∪ 未登记项 == V3 的 metric 13 值（不多不少，逐值相等）")
    void java_union_equals_v3_metric_check() throws IOException {
        Set<String> v3 = extract(V3_METRIC_BLOCK, read(V3), "V3 metric CHECK");
        assertEquals(13, v3.size(), "V3 的 metric CHECK 应为 13 值。实际=" + v3);
        assertEquals(new TreeSet<>(v3), new TreeSet<>(TelemetrySensitivity.allMetrics()),
                "🛑 敏感登记表（两侧合计）与 V3 的 metric 词表不相等 —— "
                        + "多出来的项永远不会被数据命中（如曾被误登记的 'sport' 就不是 metric 取值）；"
                        + "少掉的项会以明文落库而无人知晓。V3=" + v3
                        + " 登记表=" + TelemetrySensitivity.allMetrics());
    }

    @Test
    @DisplayName("🛑 两侧 must 不相交（一个 metric 不能既加密又不加密）")
    void the_two_sides_are_disjoint() {
        Set<String> encrypted = TelemetrySensitivity.encryptedMetrics();
        Set<String> plain = TelemetrySensitivity.notEncryptedMetrics();
        Set<String> overlap = new TreeSet<>(encrypted);
        overlap.retainAll(plain);
        assertTrue(overlap.isEmpty(),
                "🛑 同一个 metric 同时出现在加密侧与不加密侧 —— "
                        + "sensitiveFieldOf 会命中加密侧（先查 BY_METRIC），故不加密侧的登记是死代码，"
                        + "而它给出的理由会误导审阅者。交集=" + overlap);
    }

    @Test
    @DisplayName("两侧计数为 6 / 7（13 值的具体切分——改任一侧都必须重新论证）")
    void counts_are_six_and_seven() {
        assertEquals(6, TelemetrySensitivity.encryptedMetrics().size(),
                "加密侧应为 6（hr/resting_hr/spo2/steps/workout/sleep）");
        assertEquals(7, TelemetrySensitivity.notEncryptedMetrics().size(),
                "不加密侧应为 7（bp/temp/pressure/met/mai/respiration/exercise）");
    }

    // ==================================================================
    // 三、V12 必须【不放宽】安全不变量（防"用放宽 CHECK 来让测试变绿"）
    // ==================================================================

    @Test
    @DisplayName("🛑 V12 必须保留 dy1 信封判据（不得为省事把门禁放宽成任意文本）")
    void v12_keeps_the_envelope_requirement() throws IOException {
        String s = read(V12);
        assertTrue(s.contains("value_enc LIKE 'dy1:%'"),
                "🛑 V12 丢了 dy1 信封判据 —— 敏感字段的加密会从『库层强制』退回『大家记得遵守』");
        assertTrue(s.contains("ck_bt_value_enc_envelope"),
                "V12 必须重建同名约束（名字变了会让下游断言与运维脚本对不上）");
        // 反面：不得出现"信封 or 任意含数字的字符串"这种把非敏感侧的判据也放松的写法。
        // 在剥掉注释后的代码上断言，避免误伤 V12 文件头里逐字引用的反面教材。
        assertFalse(code(s).contains("LIKE 'dy1:%' OR value_enc ~ '[0-9]'"),
                "🛑 V12 把门禁写成了『信封或任意含数字』—— 非敏感侧失去『必须是数字文本』的钉住，"
                        + "任何字符串（含被遗忘的明文健康数据）都能落库");
    }

    @Test
    @DisplayName("🛑 V12 的非敏感侧必须是【正向列举】，不得写成 NOT IN")
    void v12_non_sensitive_side_is_an_explicit_whitelist() throws IOException {
        String c = code(read(V12));
        assertFalse(c.toUpperCase().contains("METRIC NOT IN"),
                "🛑 非敏感侧写成 NOT IN 会让【将来新增的 metric】自动落入明文分支 ⇒ 静默不加密。"
                        + "必须正向列举，使新 metric 两侧都不含 ⇒ 写入被拒 ⇒ 症状立刻可见");
        assertTrue(c.contains("respiration"),
                "非敏感侧清单应显式含 respiration（最需复核项）");
    }

    @Test
    @DisplayName("🛑 V12 必须【不动】sleep_json 的门禁（其敏感性不由 metric 决定）")
    void v12_does_not_touch_sleep_json_guard() throws IOException {
        String s = read(V12);
        // 允许提及（注释里解释为何不动），但不得有对 sleep_json 约束的 ADD/DROP 动作
        assertFalse(s.contains("DROP CONSTRAINT IF EXISTS ck_bt_sleep_json_envelope")
                        || s.contains("ADD CONSTRAINT ck_bt_sleep_json_envelope"),
                "🛑 V12 改动了 sleep_json 的门禁 —— 它的敏感性由『列非空』决定而非 metric，"
                        + "把它并入 value_enc 的分流会在 metric=steps 且带 sleep_json 时漏加密");
        assertTrue(s.contains("ck_bt_sleep_json_envelope"),
                "V12 应显式核对 sleep_json 门禁仍要求信封（自证块第 (d) 条）");
    }

    // ==================================================================
    // 四、V12 必须具备与前序迁移同口径的自证与幂等特征
    // ==================================================================

    @Test
    @DisplayName("V12 有自证块 + RAISE EXCEPTION + 幂等写法 + 版本登记")
    void v12_has_self_verification_and_idempotency() throws IOException {
        String s = read(V12);
        assertTrue(s.contains("DO $$"), "V12 必须有自证块（与前序迁移同口径）");
        assertTrue(s.contains("RAISE EXCEPTION"), "自证失败必须 RAISE EXCEPTION（中断迁移）");
        assertTrue(s.contains("DROP CONSTRAINT IF EXISTS"), "约束必须 DROP ... IF EXISTS 后再 ADD（可重复执行）");
        assertTrue(s.contains("INSERT INTO schema_migration") && s.contains("'V12'"),
                "V12 必须登记进 schema_migration（版本可追溯）");
        assertTrue(s.contains("convalidated"),
                "自证块应检查约束是否 validated —— NOT VALID 的 CHECK 不校验存量行，"
                        + "会让『迁移通过』变成假象");
    }

    @Test
    @DisplayName("🛑 门禁必须同时钉住两侧：只断言『约束存在』会让『改回一律信封』静默通过")
    void v12_self_check_asserts_both_sides() throws IOException {
        String s = read(V12);
        assertTrue(s.contains("NOT LIKE '%hr%'"),
                "自证块应断言定义里出现敏感侧清单代表项 —— 否则约束被改回「一律信封」也检测不到");
        assertTrue(s.contains("NOT LIKE '%pressure%'"),
                "自证块应断言定义里出现非敏感侧清单代表项");
    }
}