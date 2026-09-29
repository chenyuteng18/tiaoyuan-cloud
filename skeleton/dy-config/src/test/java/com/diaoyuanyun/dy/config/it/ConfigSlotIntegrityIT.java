package com.diaoyuanyun.dy.config.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置真相源覆盖门禁 —— 「配置表无 FORCE RLS / 无隔离测试即构建失败」。
 *
 * <h2>它堵的是什么洞</h2>
 * 只有 {@link ConfigTruthSourceIT} 时，覆盖是"人肉维护"的：下一个人新增一张
 * 带 {@code tenant_id} 的配置表（例：额度表 / 灰度表），只要不写它的 RLS 与隔离断言，
 * 构建照样 BUILD SUCCESS。本门禁把"覆盖"变成构建期强制事实，三个维度同时校验：
 * <ol>
 *   <li><b>DDL 文本侧</b>：交付物的真实 DDL 里，每一张含 {@code tenant_id} 的表
 *       都必须同时出现 {@code ENABLE} 与 {@code FORCE ROW LEVEL SECURITY} 行。</li>
 *   <li><b>真库元数据侧</b>：每一张这样的表在真实 PostgreSQL 里都必须
 *       {@code relrowsecurity} 与 {@code relforcerowsecurity} 同时为真、且至少有一条策略。</li>
 *   <li><b>三方交叉</b>：DDL 文本声明的租户表 == 真库实际含 tenant_id 的表。</li>
 * </ol>
 *
 * <h2>它为什么不是自证式断言</h2>
 * 第 2 维度断言 {@code pg_class} / {@code pg_policies} 的真实元数据。
 * 第 1 维度断言的不是"我写的常量"，而是「这张表缺 FORCE RLS 行」这一< b>结构性事实</b> ——
 * 它会在新增表忘记加 FORCE 时立刻变红，而那正是本门禁存在的意义。
 */
class ConfigSlotIntegrityIT {

    /** 不承载租户数据、无租户语义的框架表（与 DDL 注释中的口径一致）。 */
    private static final Set<String> NON_TENANT_TABLES = Set.of("tenant", "config_slot");

    private static JdbcTemplate jdbc;
    private static String ddl;

    @BeforeAll
    static void loadArtifacts() throws Exception {
        if (ConfigGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "配置真库门禁被显式跳过: -Ddy.config.gate.skip=true");
        }
        ConfigGateSupport.provisionRealDatabase();

        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:postgresql://" + ConfigGateSupport.host() + ":" + ConfigGateSupport.port()
                        + "/" + ConfigGateSupport.DB,
                ConfigGateSupport.APP_USER, ConfigGateSupport.APP_PASSWORD, true);
        jdbc = new JdbcTemplate(ds);

        // 读【交付物源文件】而不是 classpath 副本：门禁必须盯着真正要交付的那份 DDL。
        assertTrue(Files.isRegularFile(ConfigGateSupport.configDdl()),
                "配置 DDL 必须存在: " + ConfigGateSupport.configDdl());
        ddl = Files.readString(ConfigGateSupport.configDdl(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("DDL 文本：每个含 tenant_id 的表都必须同时声明 ENABLE 与 FORCE ROW LEVEL SECURITY")
    void every_tenant_table_declares_enable_and_force_in_the_ddl() {
        Set<String> tenantTables = tenantTablesFromDdl();
        assertFalse(tenantTables.isEmpty(),
                "未能从 DDL 中解析出任何租户表 —— 解析器失效，门禁形同虚设");

        List<String> problems = new ArrayList<>();
        String code = stripComments(ddl);
        for (String table : tenantTables) {
            if (!mentions(code, table, "ENABLE")) {
                problems.add(table + ": DDL 中未见 ENABLE ROW LEVEL SECURITY");
            }
            if (!mentions(code, table, "FORCE")) {
                problems.add(table + ": DDL 中未见 FORCE ROW LEVEL SECURITY（owner 可绕过隔离）");
            }
        }
        assertTrue(problems.isEmpty(),
                "配置 DDL 的 RLS 强制要素不完整:\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("真库元数据：每个配置租户表都必须 ENABLE + FORCE RLS 且至少有 1 条策略")
    void every_tenant_table_has_force_rls_in_the_real_database() {
        Set<String> tenantTables = tenantTablesFromDdl();
        List<String> problems = new ArrayList<>();

        for (String table : tenantTables) {
            var row = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner) AS owner "
                            + "FROM pg_class WHERE relname = ? AND relkind = 'r'", table);

            if (!Boolean.TRUE.equals(row.get("relrowsecurity"))) {
                problems.add(table + ": 未 ENABLE ROW LEVEL SECURITY");
            }
            if (!Boolean.TRUE.equals(row.get("relforcerowsecurity"))) {
                problems.add(table + ": 未 FORCE ROW LEVEL SECURITY（owner 可绕过）");
            }
            if (!ConfigGateSupport.APP_USER.equals(String.valueOf(row.get("owner")))) {
                problems.add(table + ": owner=" + row.get("owner")
                        + "（应为非超级用户 " + ConfigGateSupport.APP_USER + "，否则 FORCE 无从证明）");
            }
            Integer policies = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, table);
            if (policies == null || policies == 0) {
                problems.add(table + ": 没有任何 RLS 策略（等于未隔离）");
            }
        }

        assertTrue(problems.isEmpty(),
                "配置租户表在真库中的 RLS 强制要素不完整:\n  - " + String.join("\n  - ", problems));
        assertEquals(2, tenantTables.size(),
                "本模块的租户表应为 app_config / app_config_history 两张；实际=" + tenantTables
                        + "（新增租户表必须同步补齐 FORCE RLS 与隔离断言）");
    }

    @Test
    @DisplayName("三方交叉：DDL 声明的租户表 == 真库实际含 tenant_id 的表")
    void ddl_and_real_database_must_agree_on_the_set_of_tenant_tables() {
        Set<String> fromDdl = tenantTablesFromDdl();

        Set<String> fromRealDb = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'tenant_id' "
                        + "ORDER BY table_name", String.class));
        fromRealDb.removeAll(NON_TENANT_TABLES);

        assertEquals(fromDdl, fromRealDb,
                "DDL 声明的租户表与真库实际的租户表不一致 —— 要么 DDL 没被真正应用，"
                        + "要么有表绕过 DDL 被手工创建。DDL=" + fromDdl + " 真库=" + fromRealDb);
        assertFalse(fromDdl.isEmpty(), "三方交叉的租户表集合为空 —— 门禁形同虚设");
    }

    @Test
    @DisplayName("DDL 文本：#42 空号由 CHECK 约束保证（不得只写在 Java 里）")
    void the_vacancy_of_42_is_enforced_by_a_ddl_constraint() {
        String code = stripComments(ddl);
        // 断言的是"数据库侧存在该约束"这一结构性事实，而非某个具体常量值。
        assertTrue(code.contains("config_slot_no_42_stays_vacant"),
                "DDL 中缺少 #42 空号的 CHECK 约束 —— 只写在 Java 里时，一条 INSERT 就能复用空号");
        Pattern p = Pattern.compile(
                "CONSTRAINT\\s+config_slot_no_42_stays_vacant\\s+CHECK\\s*\\(\\s*config_no\\s*<>\\s*42\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher(code).find(),
                "该约束的表达式不是 config_no <> 42 —— 空号保证已被改动");

        // 真库侧同一条约束必须存在（DDL 写了 ≠ 落库了）
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'config_slot_no_42_stays_vacant'",
                Integer.class);
        assertEquals(1, n, "#42 空号约束未落库（DDL 与实际 schema 漂移）");
    }

    @Test
    @DisplayName("DDL 文本：变更历史必须由触发器写入（应用层无权写历史 = 无法不留痕）")
    void history_must_be_trigger_written_and_append_only() {
        String code = stripComments(ddl);
        assertTrue(code.contains("app_config_record_history"),
                "DDL 中缺少历史写入触发器 —— 历史若由应用层写，它也能选择不写");
        assertTrue(code.contains("AFTER INSERT OR UPDATE ON app_config"),
                "历史触发器必须挂在 app_config 的 INSERT 与 UPDATE 上");
        assertTrue(code.contains("app_config_history_append_only"),
                "DDL 中缺少历史表 append-only 触发器 —— 历史可被篡改则审计证据失效");

        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname IN "
                        + "('app_config_record_history_trg','app_config_history_append_only_trg')",
                Integer.class);
        assertEquals(2, n, "两个触发器未全部落库（DDL 与实际 schema 漂移），实际=" + n);
    }

    // ------------------------------------------------------------------
    // DDL 解析
    // ------------------------------------------------------------------

    /**
     * {@code ALTER TABLE <table> ... ENABLE|FORCE ROW LEVEL SECURITY} 的匹配。
     * 刻意保守：只认 {@code ALTER TABLE} 后紧跟表名的形态。
     */
    private static boolean mentions(String code, String table, String keyword) {
        Pattern p = Pattern.compile(
                "ALTER\\s+TABLE\\s+" + Pattern.quote(table) + "\\s+[^;]*?" + keyword
                        + "\\s+ROW\\s+LEVEL\\s+SECURITY",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        return p.matcher(code).find();
    }

    /**
     * 从真实 DDL 里解析"含 tenant_id 列的表"。
     *
     * <p>解析器刻意保守：只认 {@code CREATE TABLE [IF NOT EXISTS] <name> ( ... )}，
     * 且要求建表语句体内出现独立的 {@code tenant_id} 列名。解析不出来比解析错了更安全。
     */
    private static Set<String> tenantTablesFromDdl() {
        Pattern createTable = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Pattern tenantColumn = Pattern.compile("(^|[\\s,(])tenant_id\\s+", Pattern.CASE_INSENSITIVE);

        String code = stripComments(ddl);
        Set<String> found = new LinkedHashSet<>();
        Matcher m = createTable.matcher(code);
        while (m.find()) {
            String table = m.group(1).toLowerCase();
            String body = balancedParenBody(code, m.end() - 1);
            if (body == null || !tenantColumn.matcher(body).find()) {
                continue;
            }
            if (NON_TENANT_TABLES.contains(table)) {
                continue;
            }
            found.add(table);
        }
        return found;
    }

    /** 去掉 {@code --} 行注释，避免把注释里的示例建表语句当成真实表。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    /** 取从 {@code openIdx}（指向 '('）开始的配对括号内容，跳过字符串字面量。 */
    private static String balancedParenBody(String code, int openIdx) {
        if (openIdx < 0 || openIdx >= code.length() || code.charAt(openIdx) != '(') {
            return null;
        }
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '(') {
                depth++;
            } else if (!inQuote && c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(openIdx, i + 1);
                }
            }
        }
        return null;
    }
}