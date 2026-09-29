package com.diaoyuanyun.dy.config.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 配置真相源真库门禁的公共支撑：定位 psql、装配被测库、执行脚本并回收退出码。
 *
 * <h2>门禁的结论是 psql / JDBC 的【退出码与真实行为】，不是对文本的观察</h2>
 * 建库、灌声明、灌租户值都以 {@code psql -v ON_ERROR_STOP=1} 的进程退出码为准
 * （0 = 全绿；非 0 = 构建红）。断言则走 JDBC，落在真实 PostgreSQL 的真实返回行数、
 * 真实 SQLSTATE、真实 {@code pg_class}/{@code pg_policies} 元数据上。
 *
 * <h2>为什么复用 verification/01_reset.sql 而不是另建一个库</h2>
 * 它 DROP 并重建 {@code diaoyuanyun_rls_test} 与业务角色 {@code dy_app}。
 * 若本模块另建库，当 {@code dy-app} 随后执行同一脚本时，
 * {@code DROP ROLE dy_app} 会因"本模块的库里还有该角色拥有的对象"而失败 ——
 * 一个模块的门禁会因为另一个模块的遗留对象而报红，排查成本极高。
 * 复用同一个库与同一个角色，使 01_reset 的 {@code DROP DATABASE ... WITH (FORCE)}
 * 把本模块的对象一并清掉，模块之间不残留耦合。
 *
 * <p>本类只<b>读</b> {@code verification/} 下的既有脚本，不修改其中任何内容。
 */
final class ConfigGateSupport {

    /** 被测库（由 verification/01_reset.sql 重建）。 */
    static final String DB = "diaoyuanyun_rls_test";
    /** 业务连接角色：<b>非超级用户</b>。超级用户绕过 RLS，用它跑隔离断言会"假通过"。 */
    static final String APP_USER = "dy_app";
    static final String APP_PASSWORD = "dy_app_local_2026";
    static final String SUPER_USER = "postgres";

    static final String TENANT_A = "aaaaaaaa-1111-1111-1111-111111111111";
    static final String TENANT_B = "bbbbbbbb-2222-2222-2222-222222222222";
    /** 未灌任何配置的租户，用于 fail-closed 断言。 */
    static final String TENANT_EMPTY = "cccccccc-3333-3333-3333-333333333333";

    private ConfigGateSupport() {
    }

    static String host() {
        return envOr("DY_PG_HOST", "127.0.0.1");
    }

    static String port() {
        return envOr("DY_PG_PORT", "5432");
    }

    static String superPassword() {
        return envOr("DY_PG_SUPER_PASSWORD", "postgres");
    }

    /** 显式逃生阀。缺省 fail-closed：连不上库 = 门禁失败。 */
    static boolean gateDisabled() {
        return Boolean.parseBoolean(System.getProperty("dy.config.gate.skip", "false"));
    }

    // ------------------------------------------------------------------
    // 资产定位（从 surefire 的工作目录逐级上溯，不依赖硬编码盘符）
    // ------------------------------------------------------------------

    static Path skeletonRoot() {
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("dy-config"))
                    && Files.isRegularFile(cur.resolve("verification").resolve("01_reset.sql"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "未找到 skeleton 根目录（需同时含 dy-config/ 与 verification/01_reset.sql）；"
                        + "当前工作目录=" + p);
    }

    /** 被测交付物的真实配置 DDL —— 建表语句的唯一真相源。 */
    static Path configDdl() {
        return skeletonRoot().resolve("dy-config/src/main/resources/db/config/01_truth_source_ddl.sql");
    }

    /** PRD（配置条目的权威定义，§10 可配置项清单）。只读，不修改。 */
    static Path prdFile() {
        return skeletonRoot().getParent().resolve("prd-health-mgmt-saas-2026-09-16.md");
    }

    static Path configSlotSeed() {
        return skeletonRoot().resolve("dy-config/src/main/resources/db/config/02_slots_seed.sql");
    }

    static Path tenantValueSeed() {
        return skeletonRoot().resolve("dy-config/src/main/resources/db/config/03_seed_tenant_values.sql");
    }

    static Path psqlExecutable() {
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

    static Path gateWorkDir() throws IOException {
        Path dir = skeletonRoot().resolve("dy-config/target/config-gate");
        Files.createDirectories(dir);
        return dir;
    }

    // ------------------------------------------------------------------
    // psql 执行
    // ------------------------------------------------------------------

    record PsqlResult(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }

        String tail(int lines) {
            String[] all = output.split("\\R");
            int from = Math.max(0, all.length - lines);
            return String.join("\n", List.of(all).subList(from, all.length));
        }
    }

    static PsqlResult runScript(String user, String password, String database, Path script, String... vars) {
        List<String> cmd = baseCmd(user, password, database);
        for (int i = 0; i + 1 < vars.length; i += 2) {
            cmd.add("-v");
            cmd.add(vars[i] + "=" + vars[i + 1]);
        }
        cmd.add("-f");
        cmd.add(script.toAbsolutePath().toString());
        return exec(cmd, password);
    }

    static PsqlResult runSql(String user, String password, String database, String sql) {
        List<String> cmd = baseCmd(user, password, database);
        cmd.add("-c");
        cmd.add(sql);
        return exec(cmd, password);
    }

    private static List<String> baseCmd(String user, String password, String database) {
        List<String> cmd = new ArrayList<>();
        cmd.add(psqlExecutable().toString());
        cmd.add("-X");                                    // 忽略 psqlrc，消除环境差异
        cmd.add("-h");
        cmd.add(host());
        cmd.add("-p");
        cmd.add(port());
        cmd.add("-U");
        cmd.add(user);
        cmd.add("-d");
        cmd.add(database);
        cmd.add("-v");
        cmd.add("ON_ERROR_STOP=1");                        // 任一句失败 -> 退出码非 0 -> 门禁红
        // VERBOSITY=verbose: 让 psql 在错误行里带出 SQLSTATE 原文（如 "23514:"），
        // 反向验证的"实际失败断言原文"才能被读到并写进证据，而不是只看到一句中文错误。
        cmd.add("-v");
        cmd.add("VERBOSITY=verbose");
        return cmd;
    }

    private static PsqlResult exec(List<String> cmd, String password) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", password);
        // 本机控制台代码页为 GBK；不钉死 UTF-8 时 psql 的中文错误原文会变乱码，
        // 反向验证的"实际失败断言原文"就不可读了。
        pb.environment().put("PGCLIENTENCODING", "UTF8");
        try {
            Process p = pb.start();
            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new PsqlResult(-1, out + "\n[TIMEOUT] psql 超过 120s 未退出");
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
    // 装配被测库
    // ------------------------------------------------------------------

    /**
     * 建库 -> 应用【被测交付物】的配置 DDL -> 移交 owner -> 灌声明 -> 灌两个租户的生效值。
     *
     * <p>每一步都以退出码断言，任一步失败即抛 —— 门禁不会"部分装载后继续跑"，
     * 那会让后续断言在半个 schema 上得到无意义的结论。
     *
     * @return 全过程日志（写入 target 供证据留存）
     */
    static String provisionRealDatabase() throws IOException {
        Path ver = skeletonRoot().resolve("verification");
        StringBuilder log = new StringBuilder();

        // 1) 重建库 + 建非超级用户角色 dy_app（"非超级用户才不受 RLS 绕过"的前提）
        PsqlResult r1 = runScript(SUPER_USER, superPassword(), "postgres", ver.resolve("01_reset.sql"));
        log.append("[01_reset] EXIT=").append(r1.exitCode()).append('\n').append(r1.output());
        requireZero("01_reset.sql", r1);

        // 2) 以超级用户应用【被测交付物】的配置 DDL，再把四张表 owner 移交给 dy_app。
        //    移交 owner 是 FORCE ROW LEVEL SECURITY 可验证的前提：owner 若是超级用户，
        //    超级用户本就绕过 RLS，FORCE 将无从证明（门禁会假通过）。
        PsqlResult r2 = runScript(SUPER_USER, superPassword(), DB, portableApplyScript(),
                "ddl", configDdl().toAbsolutePath().toString());
        log.append("[config-ddl] EXIT=").append(r2.exitCode()).append('\n').append(r2.output());
        requireZero("config DDL", r2);

        // 3) 灌总部层声明（config_slot 无 RLS、无租户维度，故以超级用户灌）
        PsqlResult r3 = runScript(SUPER_USER, superPassword(), DB, configSlotSeed());
        log.append("[config-slot-seed] EXIT=").append(r3.exitCode()).append('\n').append(r3.output());
        requireZero("config_slot seed", r3);

        // 4) 三个租户行（app_config.tenant_id 有外键指向 tenant）
        //
        // 租户名刻意用 ASCII：psql 的 -c 参数经【操作系统命令行】传递，
        // Windows 下 JVM 以 GBK 编码命令行参数，而 psql 按 PGCLIENTENCODING=UTF8
        // 解析 —— 中文会变成"无效的 UTF8 编码字节顺序"而写入失败。
        // 文件内的中文（DDL / 种子脚本）不受影响，因为 psql 从磁盘按 UTF-8 读。
        PsqlResult r4 = runSql(SUPER_USER, superPassword(), DB,
                "INSERT INTO tenant (id, name) VALUES "
                        + "('" + TENANT_A + "','tenant-A'),"
                        + "('" + TENANT_B + "','tenant-B'),"
                        + "('" + TENANT_EMPTY + "','tenant-empty') "
                        + "ON CONFLICT (id) DO NOTHING");
        log.append("[tenant-rows] EXIT=").append(r4.exitCode()).append('\n').append(r4.output());
        requireZero("tenant rows", r4);

        // 5) 以【非超级用户 dy_app】灌租户生效值 —— 顺带证明"设对上下文时写入是通的"，
        //    而不是"整表拒绝"。这一步必须走事务内 SET LOCAL，否则 tenant_id 取到 NULL。
        for (String tenant : new String[]{TENANT_A, TENANT_B}) {
            PsqlResult r5 = runScript(APP_USER, APP_PASSWORD, DB, tenantSeedWrapper(),
                    "t", tenant, "v3", tenantValueSeed().toAbsolutePath().toString());
            log.append("[tenant-seed ").append(tenant).append("] EXIT=").append(r5.exitCode())
                    .append('\n').append(r5.output());
            requireZero("tenant value seed " + tenant, r5);
        }

        Path evidence = gateWorkDir().resolve("provision.log");
        Files.writeString(evidence, log.toString(), StandardCharsets.UTF_8);
        return log.toString();
    }

    /**
     * 生成可移植的 DDL 应用包装脚本。
     *
     * <p>{@code \i :ddl} 指向<b>被测交付物</b>的真实 DDL 文件（而不是 classpath 副本），
     * 使门禁盯着真正要交付的那份 SQL。包装脚本自身不含任何建表语句。
     */
    static Path portableApplyScript() throws IOException {
        Path out = gateWorkDir().resolve("apply_config_ddl_portable.sql");
        String sql = """
                \\set ON_ERROR_STOP on
                \\echo '-- portable apply: 被测交付物的配置 DDL --'
                \\i :ddl
                GRANT USAGE, CREATE ON SCHEMA public TO dy_app;
                ALTER TABLE tenant               OWNER TO dy_app;
                ALTER TABLE config_slot          OWNER TO dy_app;
                ALTER TABLE app_config           OWNER TO dy_app;
                ALTER TABLE app_config_history   OWNER TO dy_app;
                \\echo 'config-ddl-portable OK'
                """;
        Files.writeString(out, sql, StandardCharsets.UTF_8);
        return out;
    }

    /**
     * 生成租户值灌入包装脚本：必须在【同一个事务】内 SET LOCAL，
     * 否则 {@code SET LOCAL} 超出事务即失效，tenant_id 会取到 NULL 而写入被 NOT NULL 拒绝。
     */
    static Path tenantSeedWrapper() throws IOException {
        Path out = gateWorkDir().resolve("tenant_seed_portable.sql");
        String sql = """
                \\set ON_ERROR_STOP on
                BEGIN;
                SET LOCAL app.tenant_id = :'t';
                SET LOCAL app.actor = 'provisioning';
                \\i :v3
                COMMIT;
                \\echo 'tenant-seed-portable OK'
                """;
        Files.writeString(out, sql, StandardCharsets.UTF_8);
        return out;
    }

    private static void requireZero(String label, PsqlResult r) {
        if (!r.ok()) {
            throw new IllegalStateException(
                    label + " 失败: psql EXIT=" + r.exitCode() + "\n--- 输出 ---\n" + r.output());
        }
    }

    /** 连不通库时给出可执行的修复指引，而不是一个裸的 SQLException。 */
    static IllegalStateException unreachable(RuntimeException cause) {
        return new IllegalStateException(
                "配置真库门禁无法连接到 PostgreSQL (jdbc:postgresql://" + host() + ":" + port() + ") —— "
                        + "本门禁采用 fail-closed：连不上库即视为『缺少配置真相源验证』，构建必须失败。\n"
                        + "启动真库：确认本机 PostgreSQL 服务已启动，且 postgres 口令可用"
                        + "（可用 DY_PG_SUPER_PASSWORD 覆盖）。\n"
                        + "确需在无库环境跳过（例如纯编译流水线），显式传 -Ddy.config.gate.skip=true。",
                cause);
    }

    static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}