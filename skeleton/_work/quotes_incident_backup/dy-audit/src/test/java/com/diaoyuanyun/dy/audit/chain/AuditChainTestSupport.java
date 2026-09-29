package com.diaoyuanyun.dy.audit.chain;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 审计链真库门禁的公共支撑：定位 psql / 建库 / 以非超级用户与超级用户两个身份执行 SQL。
 *
 * <h2>为什么需要两个身份（本任务的核心陷阱）</h2>
 * <ul>
 *   <li><b>非超级用户 {@code dy_audit_app}</b> —— 扮演"应用"。链写入、校验、以及
 *       append-only 断言都必须用它跑。用 {@code postgres} 跑 append-only 断言会
 *       <b>假通过</b>：超级用户本就绕过一切权限检查，{@code REVOKE} 对它无效。</li>
 *   <li><b>超级用户 {@code postgres}</b> —— 扮演"拥有 DDL 能力的攻击者"。有 DDL 能力的人
 *       可以先把权限撤销掉再用超级用户改数据，所以"撤销权限"并不能证明数据不可被改；
 *       它能证明的是一条<b>更弱但更真实</b>的性质：<b>应用自己的凭据不足以篡改</b>。
 *       篡改<b>必然被发现</b>这一性质由哈希链提供，与权限无关 —— 两者是两道独立的防线，
 *       测试里必须分开断言，绝不能拿"撤销权限成功"冒充"篡改会被发现"。</li>
 * </ul>
 *
 * <h2>不修改任何既有资产</h2>
 * 本类自己建库建表，不碰 {@code verification/**}（那是上一轮的证据基线，任务明确禁止改动）
 * 与 {@code dy-app/src/test/**}（另一位工程师的工作区）。表结构来自被测交付物
 * {@code dy-audit/src/main/resources/db/audit_log.sql}，由 {@code JdbcAuditLogService.ensureSchema()}
 * 以<b>同构</b>建表执行（列名/类型与该 DDL 一致）。
 *
 * <h2>缺库即失败（fail-closed）</h2>
 * 连不上库 = 缺少链完整性测试 → 构建必须失败。唯一逃生阀是显式
 * {@code -Ddy.audit.gate.skip=true}，绝不静默跳过。
 */
public final class AuditChainTestSupport {

    /** 被测库名。与 RLS 门禁的库分开，互不干扰（那位的测试会 DROP 它自己的库）。 */
    static final String DB = "dy_audit_chain_test";

    /** 应用角色：<b>非超级用户</b>。所有 append-only / 链断言都用它。 */
    static final String APP_USER = "dy_audit_app";
    static final String APP_PASSWORD = "dy_audit_app_local_2026";

    /** 攻击者角色：仅用于篡改/删行/REVOKE 等 Harness 动作，绝不用它跑链完整性断言。 */
    static final String SUPER_USER = "postgres";

    private AuditChainTestSupport() {
    }

    public static String host() {
        return envOr("DY_PG_HOST", "127.0.0.1");
    }

    public static String port() {
        return envOr("DY_PG_PORT", "5432");
    }

    public static String superPassword() {
        return envOr("DY_PG_SUPER_PASSWORD", "postgres");
    }

    public static boolean gateDisabled() {
        return Boolean.parseBoolean(System.getProperty("dy.audit.gate.skip", "false"));
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + DB;
    }

    /** 应用角色的 DataSource（非超级用户）。 */
    public static DataSource appDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl(), APP_USER, APP_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    // ------------------------------------------------------------------
    // 资产定位（从 surefire 的工作目录 dy-audit 逐级上溯，不依赖硬编码盘符）
    // ------------------------------------------------------------------

    public static Path moduleRoot() {
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("dy-audit"))
                    && Files.isRegularFile(cur.resolve("pom.xml"))) {
                return cur.resolve("dy-audit");
            }
        }
        throw new IllegalStateException("未找到 dy-audit 模块目录；当前工作目录=" + p);
    }

    /** 被测交付物的 DDL —— 唯一的表结构真相源（读出来核对，而不是另抄一份）。 */
    public static Path auditLogDdl() {
        return moduleRoot().resolve("src/main/resources/db/audit_log.sql");
    }

    public static Path workDir() throws IOException {
        Path dir = moduleRoot().resolve("target/audit-chain-gate");
        Files.createDirectories(dir);
        return dir;
    }

    public static Path psqlExecutable() {
        String override = System.getenv("DY_PSQL_EXE");
        if (override != null && !override.isBlank() && Files.isRegularFile(Paths.get(override))) {
            return Paths.get(override);
        }
        String[] candidates = {
                "C:/Program Files/PostgreSQL/17/bin/psql.exe",
                "C:/Program Files/PostgreSQL/16/bin/psql.exe",
                "C:/Program Files/PostgreSQL/15/bin/psql.exe",
                "/usr/bin/psql",
                "/usr/local/bin/psql",
                "/opt/homebrew/bin/psql",
        };
        for (String c : candidates) {
            if (Files.isRegularFile(Paths.get(c))) {
                return Paths.get(c);
            }
        }
        return Paths.get("psql");
    }

    // ------------------------------------------------------------------
    // psql 执行
    // ------------------------------------------------------------------

    public record PsqlResult(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String tail(int lines) {
            String[] all = output.split("\\R");
            int from = Math.max(0, all.length - lines);
            return String.join("\n", List.of(all).subList(from, all.length));
        }
    }

    public static PsqlResult runSql(String user, String password, String database, String sql) {
        // 【为何走 -f 文件而不是 -c 字符串】
        // Windows 上 ProcessBuilder 组装命令行时，参数里的双引号会被吞掉/错引号
        // （已被实测抓到：注入 `'{"n":2,"tampered":"by-attacker"}'` 落库后变成
        //  `{n:2,tampered:by-attacker}` —— 引号消失，注入没生效，测试断言随之失效）。
        // 这类"SQL 文本被 shell/进程层改写"的 bug 极难从断言信息上看出来，
        // 因为它表现为"数据不对"而不是"命令失败"。
        // 写成文件再 -f 引用，参数里就只有路径，引号问题从根上消失；
        // 附带好处是每次注入的 SQL 原文都落在 target/ 下，可作证据复核。
        try {
            Path file = nextSqlFile();
            Files.writeString(file, sql, StandardCharsets.UTF_8);
            return runScriptFile(user, password, database, file);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 Harness SQL 文件", e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger SQL_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static Path nextSqlFile() throws IOException {
        return workDir().resolve("harness-" + SQL_SEQ.incrementAndGet() + ".sql");
    }

    public static PsqlResult runScriptFile(String user, String password, String database, Path script) {
        List<String> cmd = new ArrayList<>(List.of(
                psqlExecutable().toString(), "-X", "-h", host(), "-p", port(),
                "-U", user, "-d", database, "-v", "ON_ERROR_STOP=1",
                "-f", script.toAbsolutePath().toString()));
        return exec(cmd, password);
    }

    /** 以应用角色（非超级用户）执行 SQL —— append-only 断言必须走这条。 */
    public static PsqlResult appSql(String sql) {
        return runSql(APP_USER, APP_PASSWORD, DB, sql);
    }

    /**
     * 以应用角色执行 SQL，并在脚本首行开启 {@code \set VERBOSITY verbose}。
     *
     * <p>psql 默认只打印本地化的错误文案（本机中文："对表 audit_log 权限不够"），
     * <b>不含 SQLSTATE</b>。想看 SQLSTATE 必须显式开 verbose —— 这也是"别拿错误文案做断言"
     * 的原因：文案随语言环境变化，而 42501 不会。
     */
    public static PsqlResult appSqlVerbose(String sql) {
        return appSql("\\set VERBOSITY verbose\n" + sql + "\n");
    }

    /** 以超级用户（攻击者）执行 SQL —— 篡改/删行/REVOKE 等 Harness 动作走这条。 */
    public static PsqlResult attackerSql(String sql) {
        return runSql(SUPER_USER, superPassword(), DB, sql);
    }

    private static PsqlResult exec(List<String> cmd, String password) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", password);
        // 本机控制台代码页为 GBK；不钉死 UTF-8 的话，中文错误原文在证据文件里就是乱码，
        // 反向验证的"实际失败 AssertionError 原文"将不可读。
        pb.environment().put("PGCLIENTENCODING", "UTF8");
        try {
            Process p = pb.start();
            byte[] blob;
            try (InputStream is = p.getInputStream()) {
                blob = is.readAllBytes();
            }
            String out = new String(blob, StandardCharsets.UTF_8);
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new PsqlResult(-1, out + "\n[TIMEOUT] psql 超过 180s 未退出");
            }
            return new PsqlResult(p.exitValue(), out);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法执行 psql（" + psqlExecutable() + "）。可用环境变量 DY_PSQL_EXE 指定路径。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("psql 执行被中断", e);
        }
    }

    // ------------------------------------------------------------------
    // 建库 / 清表 / 授权
    // ------------------------------------------------------------------

    /**
     * 重建一个干净的库：库由<b>超级用户</b>创建、owner 交给应用角色，表再由应用角色建。
     *
     * <p>为何让应用角色当 owner：这是本骨架的部署形态（Flyway 以超级用户应用迁移后移交 owner），
     * 也是<b>唯一</b>能让后续 REVOKE 成为"承重动作"的形态 —— 若应用不是 owner，
     * 它本来就没有 UPDATE/DELETE 权限，撤销与否毫无区别，测试会变成空操作。
     * 该前提已由 {@code src/test/resources/sql/probe_owner_revoke.sql} 真库实测确认。
     */
    public static String provision() {
        StringBuilder log = new StringBuilder();

        try {
            // psql 的 -c 里放 DROP DATABASE ... WITH (FORCE) 在 Windows 上会被引号处理吃掉括号，
            // 故这里为每条管理语句各写一个 .sql 文件再 -f（与 runSql 同理）。
            for (String stmt : new String[]{
                    "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE);",
                    "DROP ROLE IF EXISTS " + APP_USER + ";",
                    "CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD
                            + "' NOSUPERUSER NOCREATEDB NOCREATEROLE;",
                    "CREATE DATABASE " + DB + " OWNER " + APP_USER + ";",
            }) {
                Path f = nextSqlFile();
                Files.writeString(f, stmt + "\n", StandardCharsets.UTF_8);
                PsqlResult r = runScriptFile(SUPER_USER, superPassword(), "postgres", f);
                log.append("[").append(stmt.split(" ")[0]).append("] EXIT=")
                        .append(r.exitCode()).append('\n').append(r.output()).append('\n');
                if (stmt.startsWith("CREATE ")) {
                    requireZero(stmt, r);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 provision SQL 文件", e);
        }

        // 表由【应用角色】创建，使它天然持有 owner 身份（后续 REVOKE 才有意义）。
        // DDL 与被测交付物 src/main/resources/db/audit_log.sql 同构（由 AuditLogTableContractTest 守护）。
        PsqlResult r5 = appSql(DDL_FOR_HARNESS);
        requireZero("create table audit_log", r5);
        log.append("[create table] EXIT=").append(r5.exitCode()).append('\n').append(r5.output());

        return log.toString();
    }

    /**
     * Harness 建表语句 —— 直接引用生产侧的 {@link AuditLogTable#CREATE_TABLE}，
     * <b>不另抄一份</b>。三处（DDL 文档 / 生产幂等建表 / 测试 Harness）共用同一常量，
     * 并由 {@code AuditLogTableContractTest} 逐列比对常量与交付物 DDL 文件，
     * 以堵住"测试建的表和生产不是同一张"这种不会以红点形式暴露的偏差。
     */
    static final String DDL_FOR_HARNESS = AuditLogTable.CREATE_TABLE;

    /**
     * 撤销应用的 UPDATE / DELETE / TRUNCATE（append-only 的数据库层保障）。
     *
     * <p><b>必须有 {@code TRUNCATE}</b>：只撤 UPDATE/DELETE 会留一个更省事的洞 ——
     * TRUNCATE 不需要 WHERE、不需要 DELETE 权限，一条语句清空整条链。测试里专门注入一次
     * TRUNCATE，保证撤销清单不会在将来被人"精简"回两项。
     */
    public static void revokeWritePrivileges() {
        PsqlResult r = attackerSql(String.format(
                AuditLogTable.REVOKE_WRITES_SQL_TEMPLATE, AuditLogTable.NAME, APP_USER));
        requireZero("REVOKE UPDATE, DELETE, TRUNCATE", r);
    }

    /**
     * 复位应用的写权限。
     *
     * <p><b>为什么必须有这个动作</b>：{@code REVOKE} 是<b>库级持久变更</b>，不会随测试方法结束而回滚。
     * 若某一例撤销了权限却不复位，后面依赖"应用改得动数据"的用例就会以"权限不够"失败 ——
     * 而报错信息完全指向不了真因（看起来像那个用例自己写错了）。
     * 更糟的是反过来：一个依赖"撤销已生效"的用例若跑在前一个用例的复位之后，
     * 也可能悄悄拿到错的起点。故撤销与复位必须成对，且复位放在 {@code @AfterEach}。
     */
    public static void restoreWritePrivileges() {
        PsqlResult r = attackerSql("GRANT UPDATE, DELETE, TRUNCATE ON "
                + AuditLogTable.NAME + " TO " + APP_USER);
        requireZero("GRANT UPDATE, DELETE, TRUNCATE (reset)", r);
    }

    /** Harness 清表（用攻击者身份，因为应用身份已无 DELETE 权限 —— 这本身就是 append-only 生效的证据）。 */
    public static void truncateForHarness() {
        PsqlResult r = attackerSql("TRUNCATE audit_log");
        requireZero("TRUNCATE audit_log (harness)", r);
    }

    /** 当前 audit_log 行数（用应用角色读，读权限保留）。 */
    public static int rowCount() {
        try (Connection c = appDataSource().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM audit_log")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("统计 audit_log 行数失败", e);
        }
    }

    private static void requireZero(String label, PsqlResult r) {
        if (!r.ok()) {
            throw new IllegalStateException(
                    label + " 失败: psql EXIT=" + r.exitCode() + "\n--- 输出 ---\n" + r.output());
        }
    }

    /** 连不通库时给出可执行的修复指引，而不是一个裸的 SQLException。 */
    public static IllegalStateException unreachable(RuntimeException cause) {
        return new IllegalStateException(
                "审计链真库门禁无法连接到 PostgreSQL (jdbc:postgresql://" + host() + ":" + port() + ") —— "
                        + "本门禁按 ADR-09 采用 fail-closed：连不上库即视为『缺少链完整性测试』，构建必须失败。\n"
                        + "启动真库两种方式：\n"
                        + "  ① 本机 PG：确认 PostgreSQL 服务已启动，且 postgres 口令可用（DY_PG_SUPER_PASSWORD）\n"
                        + "  ② Docker：docker run -d --name dy-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16\n"
                        + "确需在无库环境跳过（例如纯编译流水线），显式传 -Ddy.audit.gate.skip=true。",
                cause);
    }

    public static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}