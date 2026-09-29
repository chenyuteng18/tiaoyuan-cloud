package com.diaoyuanyun.dy.app.rls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * RLS 真库门禁测试的公共支撑：定位 psql / verification 资产 / 执行脚本并回收退出码。
 *
 * <h2>设计原则：门禁的结论必须是 psql 的【退出码】，不是对文本的观察</h2>
 * 所有断言最终都以 {@code psql -v ON_ERROR_STOP=1 -f <script>} 的进程退出码为准
 * （0 = 全绿；3 = 脚本内有 {@code RAISE EXCEPTION}）。这与 CI 直接跑同一命令的结论完全一致，
 * 不存在"JUnit 绿了但 CI 红"的双标准。
 *
 * <h2>构建间互斥（Task #18）：为什么缺省路径绝不允许 DROP DATABASE</h2>
 * 真库门禁的 provision 若含 {@code DROP DATABASE ... WITH (FORCE)}，它就是<b>破坏性</b>操作：
 * 它会把<b>另一个正在使用同一个库的构建</b>的连接强杀、把它的表删掉。
 * 实测症状（本任务修复前的现场证据）：{@code 42P01: 关系 "customer" 不存在}、
 * {@code CannotCreateTransaction}、以及行数错位（{@code 32 → 33}）——
 * 这些症状<b>看起来像业务代码的 bug</b>，排查成本极高。
 *
 * <p>故本类按 team-lead 裁定的统一约定改造为<b>三层</b>：
 * <ol>
 *   <li><b>构建级互斥锁</b>（{@link #acquireBuildMutex()}）：整个门禁类执行期间，
 *       在一个专用 JDBC 连接上持有<b>会话级</b> {@code pg_advisory_lock}，
 *       key 由库名派生（同一库固定、不同模块的库互不争用）。
 *       并发的第二个构建会<b>排队等待</b>而不是拆库。</li>
 *   <li><b>显式重置开关</b> {@code DY_TESTDB_RESET=1}：<b>只有</b>它为 1 时才
 *       {@code DROP}+重建；缺省走<b>幂等路径</b>——库存在且 schema 哨兵匹配
 *       → 只清数据、不动库对象（{@link #seedEnsureScript()}）。</li>
 *   <li><b>单连接持锁</b>：所有破坏性 / 变更性脚本都在<b>一个 psql 进程</b>里
 *       {@code lock → 变更 → unlock}。🛑 绝不可拆成多条 {@code psql -c}：
 *       {@code -c} 每次都是新连接，锁在命令结束即释放，<b>等于无锁</b>。</li>
 * </ol>
 *
 * <h2>为什么"幂等"要判到"精确种子态"这么严</h2>
 * 只判"库在不在"是不够的：那会留下"看起来跑过了其实没清干净"的假绿。
 * 本类用<b>真实数据库的真实行数</b>做判据：只有 {@code tenant=2 ∧ customer=2 ∧
 * 两行种子 id 都在} 时才零写入；否则 {@code TRUNCATE} 后重灌。
 * 并且 provision 结束时用超级用户做<b>后置校验</b>（{@code extra=0}）——
 * 清不干净就一定红，而不是"顺利跑完"。
 *
 * <h2>schema 哨兵：迁移一改，库就必然被重建</h2>
 * 哨兵放在 {@code COMMENT ON DATABASE}（不给 {@code public} schema 增加任何表，
 * 以免污染"迁移声明的表 == 真库实际的表"这条三方交叉断言）。
 * 哨兵值 = 真实迁移文件字节的 SHA-256。迁移文本一改，哨兵立刻不匹配 → 走重建，
 * 杜绝"库还是上一版迁移建出来的，却拿新断言去测"这种静默错配。
 *
 * <h2>为何要"另生成"一个 015 包装脚本，而不是直接跑 verification/015_apply.sql</h2>
 * {@code verification/015_apply.sql} 里的 {@code \i} 使用了<b>硬编码绝对路径</b>
 * （见该文件 L16，README TODO 已记录此问题）。它是上一轮的<b>证据基线</b>，本任务不得修改，
 * 故这里用 {@code \i :v1} + {@code -v v1=<abs>} 的等价写法在 {@code target/} 下生成包装脚本，
 * 使门禁可在任意检出路径 / CI 上运行。
 *
 * <p><b>关键</b>：包装脚本自身<b>不含任何建表语句</b>——DDL 100% 来自被测交付物
 * {@code dy-app/src/main/resources/db/migration/} 下的真实迁移脚本。
 * 包装脚本只额外做 015 里同样有的"授权 + 移交 owner"两个 Harness 动作。
 *
 * <h2>2026-09-22 改造：从"单文件基线"到"整条迁移链"</h2>
 * 原实现把真实迁移硬编码为单个 {@code V1__baseline_tenant_rls.sql}，
 * 且把 owner 移交写成 4 条硬编码 {@code ALTER TABLE}。B 类实体以 {@code V2__*.sql}
 * 进入迁移链后，这两处都会以<b>看似业务 bug</b>的方式失败（"新表不存在" /
 * "新表没被移交 owner，FORCE 断言失去证明力"）。
 * 现改为：{@link #migrationScripts()} 按文件名顺序列举全部 {@code *.sql}，
 * provision 逐个 {@code \i} 应用、owner 用 {@code \gexec} 遍历移交。
 * <b>此后新增迁移无需再改本类</b>；schema 哨兵也升级为覆盖整条链
 * （{@link #sentinel()}），杜绝"V2 改了但库还是旧 V2"的静默错配。
 */
final class RlsGateSupport {

    /**
     * 门禁库名：<b>本模块专属</b>。
     *
     * <h2>为什么必须加后缀，而不是沿用 verification/01_reset.sql 里的 diaoyuanyun_rls_test</h2>
     * 真库门禁的 provision 是 {@code DROP DATABASE ... WITH (FORCE)} + 重建，属于<b>破坏性</b>操作。
     * 若多个模块共用同一个库名，后跑的模块会把先跑模块刚建好的库整个清掉
     * （实测：{@code dy-config} 的 config_slot/app_config 被随后执行的 dy-app 门禁重建掉，
     * 采证据时表现为 {@code 42P01: 关系 "config_slot" 不存在}，极易被误判成"DDL 有 bug"）。
     * 同一模块被并发跑两个构建时，{@code WITH (FORCE)} 还会强杀另一个正在使用的库，
     * 表现为随机的连接失败与行数错位。
     *
     * <p>故本模块使用 {@code diaoyuanyun_rls_test_app}，与 dy-config 的
     * {@code ..._config}、dy-audit 的 {@code dy_audit_chain_test} 互不干扰。
     * 该改动<b>不改变任何断言语义</b>，只消除跨模块/跨进程的库争用。
     *
     * <p>可用 {@code -Ddy.rls.gate.db=<name>} 覆盖（CI 上按 job 再隔离一层）。
     */
    static final String DB = System.getProperty("dy.rls.gate.db", "diaoyuanyun_rls_test_app");

    /**
     * 业务连接角色：<b>非超级用户</b>。超级用户会绕过 RLS，用它跑断言会"假通过"(A0)。
     *
     * <p>同样需要模块隔离：{@code 01_reset.sql} 里的 {@code DROP ROLE IF EXISTS dy_app} 是
     * <b>集群级</b>操作，若 dy-config 门禁先建了 dy_app-owned 的表而本模块要重建角色，
     * 会因"角色仍拥有对象"而失败。故本模块用 {@code dy_app_rls}。
     */
    static final String APP_USER = System.getProperty("dy.rls.gate.user", "dy_app_rls");
    static final String APP_PASSWORD = "dy_app_rls_local_2026";
    /** 管理角色：仅用于重建库 / 应用迁移，绝不用它跑隔离断言。 */
    static final String SUPER_USER = "postgres";

    static final String TENANT_A = "aaaaaaaa-1111-1111-1111-111111111111";
    static final String TENANT_B = "bbbbbbbb-2222-2222-2222-222222222222";

    /**
     * 种子行主键（来自 {@code verification/02_seed.sql}，该文件是上一轮证据基线，不得修改）。
     *
     * <p>若它俩与 02_seed.sql 实际插入的 id 漂移，provision 的后置校验会以
     * {@code seed=0 extra=2} 直接报红 —— 漂移不会被静默接受。
     */
    static final String SEED_A_ID = "c1111111-0000-0000-0000-000000000001";
    static final String SEED_B_ID = "c2222222-0000-0000-0000-000000000002";

    /** 干净起点的不变量：2 个租户 + 恰好 2 行种子客户（无多余行）。
     *
     * <p>尾部的 {@code cst=… band=…} 属<b>观测项</b>而非硬断言（各隔离测试类自行灌底/清理），
     * 故校验时只比对前四项前缀，见 {@link #provisionRealDatabase()} 的后置校验②。
     */
    static final String EXPECTED_DATA_SUMMARY_PREFIX = "tenant=2 customer=2 seed=2 extra=0";

    private static final AtomicInteger PROBE_SEQ = new AtomicInteger();

    private RlsGateSupport() {
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

    /** 显式逃生阀。缺省 fail-closed：连不上库 = 门禁失败（ADR-02 L3 要求"缺测试即构建失败"）。 */
    static boolean gateDisabled() {
        return Boolean.parseBoolean(System.getProperty("dy.rls.gate.skip", "false"));
    }

    /**
     * 显式重置开关。
     *
     * <p><b>只有值为 {@code 1} 时</b>才允许 {@code DROP DATABASE ... WITH (FORCE)} + 重建。
     * 缺省（未设置 / 任何其它值）一律走<b>幂等路径</b>：库在且哨兵匹配 → 只动数据、不动库对象。
     *
     * <p>两种等价写法（与 dy-config / dy-audit 两侧同约定同语义）：
     * <ul>
     *   <li>环境变量 {@code DY_TESTDB_RESET=1}（约定主口径；会传递给 surefire fork 出的 JVM）</li>
     *   <li>JVM 参数 {@code -Ddy.testdb.reset=1}（CI 里写 {@code -D} 更顺手）</li>
     * </ul>
     */
    static boolean resetRequested() {
        return "1".equals(System.getenv("DY_TESTDB_RESET"))
                || "1".equals(System.getProperty("dy.testdb.reset", ""));
    }

    // ------------------------------------------------------------------
    // 资产定位（从 surefire 的工作目录 dy-app 逐级上溯，不依赖硬编码盘符）
    // ------------------------------------------------------------------

    static Path skeletonRoot() {
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("verification").resolve("03_assert.sql"))
                    && Files.isDirectory(cur.resolve("dy-app"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "未找到 skeleton 根目录（需同时含 verification/03_assert.sql 与 dy-app/）；"
                        + "当前工作目录=" + p);
    }

    static Path verificationDir() {
        return skeletonRoot().resolve("verification");
    }

    /**
     * 被测项目的迁移目录 —— DDL 的唯一真相源，同时也是 schema 哨兵的取值来源。
     *
     * <p><b>2026-09-22 改造（V2 · B 类实体落地）</b>：原实现把"真实迁移脚本"硬编码为
     * 单个 {@code V1__baseline_tenant_rls.sql}。B 类实体（{@code customer_state_transition} /
     * {@code band}）以 {@code V2__*.sql} 进入迁移链后，只应用 V1 会让真库缺这两张表，
     * 而 {@link RlsCoverageGateTest} 的"迁移声明的表 == 真库实际的表"三方交叉断言随即报红 ——
     * 症状看似"业务 DDL 有 bug"，实为 harness 只跑了一半迁移链。
     * 故此处改为<b>按文件名顺序应用目录内全部 {@code *.sql}</b>：
     * 迁移链一扩展，provision 自动跟上，无需再改本类。
     */
    static Path migrationDir() {
        return skeletonRoot().resolve("dy-app/src/main/resources/db/migration");
    }

    /**
     * 迁移链里的全部脚本，按<b>版本号数值</b>排序（{@code V1…} → {@code V2…} → … → {@code V10…}）。
     *
     * <h2>🛑 2026-09-26 修复：不得按文件名 String 排序（V10 引爆的既有缺陷）</h2>
     * 原实现是 {@code .sorted()}（{@link Path} 的 {@code compareTo} = 逐字符比较）。
     * 逐字符比较下 {@code "V10__".compareTo("V1__") < 0} —— 因为第 3 个字符
     * {@code '0'}(0x30) &lt; {@code '_'}(0x5F)，于是排成：
     * <pre>V10, V11, V1, V2, … V9</pre>
     * 即 <b>V10 / V11 被排到 V1 之前</b>。V10 改的是 V3 建的 {@code band_telemetry}
     * ⇒ 会在真库上以"relation does not exist"失败，而症状看起来像"新迁移写错了"，
     * 真因却是 harness 的排序。
     *
     * <p>这个缺陷<b>一直潜伏</b>：V1~V9 都是单字符序号，字符串序恰好等于数值序。
     * V10 是第一个引爆它的迁移（两位数序号）。Flyway 自身按<b>数值</b>解析版本，
     * 故两者在 V10 上首次分叉 —— 真库门禁与 Flyway 对"迁移链顺序"的认知必须一致，
     * 否则门禁的结论就不再代表生产的行为。
     *
     * <p>同款失效模式在本仓已有先例记录（{@code ArchitectureBoundaryTest} 注释：
     * "任何一层漏登记，对应规则就静默失效"）—— 本条属于同一类：
     * <b>测试基础设施自身的一个隐式假设，在新数据下静默崩坏</b>。
     */
    static List<Path> migrationScripts() {
        Path dir = migrationDir();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("迁移目录不存在: " + dir);
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> scripts = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(RlsGateSupport::migrationVersionOf))
                    .toList();
            if (scripts.isEmpty()) {
                throw new IllegalStateException("迁移目录里没有任何 .sql: " + dir);
            }
            // 版本号唯一性：重复版本是静默危险 —— 两份 V5 谁先谁后取决于文件名后缀，
            // 而 Flyway 会以"checksum 冲突"或"版本已存在"报了才算，此处提前拦住。
            for (int i = 1; i < scripts.size(); i++) {
                int prev = migrationVersionOf(scripts.get(i - 1));
                int cur = migrationVersionOf(scripts.get(i));
                if (cur == prev) {
                    throw new IllegalStateException("迁移版本号重复: V" + cur + "（"
                            + scripts.get(i - 1).getFileName() + " 与 "
                            + scripts.get(i).getFileName() + "）—— 版本号必须唯一");
                }
                if (cur < prev) {
                    throw new IllegalStateException("迁移排序未生效: V" + cur + " 排在 V" + prev + " 之后");
                }
            }
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("无法列举迁移目录: " + dir, e);
        }
    }

    /** 迁移文件名里的版本号模式：{@code V<数字>__说明.sql}。 */
    private static final java.util.regex.Pattern MIGRATION_VERSION =
            java.util.regex.Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * 从 {@code V<数字>__说明.sql} 解析版本号。
     *
     * <p>解析失败即抛（fail-closed）：一个不符合命名约定的文件混进迁移目录，
     * 若不报错就会被安静地排到某个位置并执行 —— 而"它该不该执行"没人能判断。
     */
    static int migrationVersionOf(Path script) {
        String name = script.getFileName().toString();
        java.util.regex.Matcher m = MIGRATION_VERSION.matcher(name);
        if (!m.matches()) {
            throw new IllegalStateException("迁移文件名不符合 `V<数字>__说明.sql`: " + name);
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * 兼容保留：迁移链的第一份脚本（V1 基线）。
     *
     * <p>对外仍有少量引用点假定"基线脚本"这个名字存在，故保留此访问器；
     * 但 {@link #sentinel()} 与 {@link #portableApplyScript()} 已改为使用<b>整条链</b>。
     */
    static Path realMigrationScript() {
        return migrationScripts().get(0);
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
        // 最后回退到 PATH（Docker/CI 镜像里 psql 通常就在 PATH 上）
        return Paths.get("psql");
    }

    static Path gateWorkDir() throws IOException {
        Path dir = skeletonRoot().resolve("dy-app/target/rls-gate");
        Files.createDirectories(dir);
        return dir;
    }

    // ------------------------------------------------------------------
    // schema 哨兵 / 库状态探测 / 每库固定锁 key
    // ------------------------------------------------------------------

    /** 哨兵前缀：含版本号，将来哨兵语义变更时可主动让旧库失效（不匹配 → 重建）。 */
    static final String SENTINEL_PREFIX = "dy-rls-gate/v1/";

    /**
     * schema 哨兵 = 前缀 + <b>整条迁移链</b>（按文件名顺序拼接）字节的 SHA-256。
     *
     * <p>它回答的问题是：<i>"当前这个库，是不是由<b>这一版</b>迁移链建出来的？"</i>
     * 任何一份迁移文本改一个字，哨兵就变 → 库被判为不匹配 → 走重建。
     * 这比"表存在就算有效"强得多：后者会让"库停在上一个版本的 schema 上"静默通过。
     *
     * <p><b>为何要覆盖整条链而不是只算第一份</b>：只算 V1 会让"V2 改了但库还是旧的 V2"
     * 静默通过 —— 新表缺失时断言会以"表不存在"报错，看起来像 DDL bug，实为哨兵没覆盖到。
     * 按文件名顺序拼接同时把"迁移的先后次序"绑进哨兵：换了顺序也是不同的 schema。
     */
    static String sentinel() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (Path p : migrationScripts()) {
                // 把文件名也拼进去：改名（V2→V3）同样应使哨兵失效
                buf.write(p.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                buf.write(0);
                buf.write(Files.readAllBytes(p));
                buf.write(0);
            }
            return SENTINEL_PREFIX + sha256Hex(buf.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("无法读取迁移脚本以计算 schema 哨兵: " + migrationDir(), e);
        }
    }

    /**
     * 每库固定的 advisory lock key：由 {@code 库名/tag} 的 SHA-256 前 8 字节派生。
     *
     * <p>用 {@code tag} 区分同一库上的不同用途 —— 构建级互斥锁（{@code "gate"}）与
     * 脚本内临界区锁（{@code "reset"}）必须是<b>不同的 key</b>：
     * PG 的 advisory lock<b>不可跨连接重入</b>，若同一 key，持锁的构建再去执行"取得同 key 的脚本"
     * 会<b>自死锁</b>（表现为构建永久卡住，且看不出原因）。
     */
    static long advisoryKey(String tag) {
        byte[] d = sha256Bytes((DB + "/" + tag).getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[i] & 0xffL);
        }
        v &= Long.MAX_VALUE;
        return v == 0 ? 1L : v;
    }

    /** 真库的探测状态。 */
    enum DbState {
        /** 库不存在。 */
        MISSING,
        /** 库存在且 schema 哨兵与本版迁移一致 → 可走幂等路径。 */
        SENTINEL_OK,
        /** 库存在但哨兵缺失/不匹配（旧版迁移建的、或人工建的）→ 必须重建。 */
        SENTINEL_MISMATCH
    }

    /** 库的 OID；库不存在返回 {@code null}。用于证明"缺省路径没有重建库"。 */
    static String dbOid() {
        String v = queryScalar(SUPER_USER, superPassword(), "postgres",
                "SELECT oid::text FROM pg_database WHERE datname = '" + DB + "'");
        return v.isBlank() ? null : v;
    }

    /** 探测库状态（只读，不需要锁）。 */
    static DbState dbState() {
        String s = queryScalar(SUPER_USER, superPassword(), "postgres",
                "SELECT CASE"
                        + " WHEN NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '" + DB + "') THEN 'MISSING'"
                        + " WHEN (SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = '"
                        + DB + "') = '" + sentinel() + "' THEN 'OK'"
                        + " ELSE 'MISMATCH' END");
        return switch (s) {
            case "OK" -> DbState.SENTINEL_OK;
            case "MISSING" -> DbState.MISSING;
            default -> DbState.SENTINEL_MISMATCH;
        };
    }

    /**
     * 数据层不变量的一句话摘要（用超级用户读，不受 RLS 影响）。
     *
     * <p>期望值 = 前缀 {@link #EXPECTED_DATA_SUMMARY_PREFIX}。{@code extra>0} = 没清干净；
     * {@code seed<2} = 种子缺失。两种情况都必须让 provision 报红，而不是”顺利跑完“。
     *
     * <p><b>2026-09-22</b>：追加 {@code cst} / {@code band} 两个计数（V2 的两张 B 类实体表）。
     * 它们由各隔离测试类自行灌底数据与清理，故此处只要求”非负“——
     * 目的是让”新表的残留“在 provision 日志里<b>可见</b>，
     * 而不是像原来那样只能看见 customer/tenant 两个维度。
     *
     * <p><b>2026-09-23</b>：再追加 {@code sib}（{@code scale_item_bank}，V4 的题库内容资产）。
     * 同一条理由：{@code RlsScaleItemBankIsolationTest} 会灌底/清理，
     * 让它在 provision 摘要里可见，才能在看日志时一眼发现”上一轮残留没清掉“。
     *
     * <p><b>2026-09-24</b>：追加 {@code v5}（V5 余量 24 表在 <b>region</b> 上的合计行数）与
     * {@code bandx}（手环三表合计）。V5 的门禁类 {@code RlsV5EntityIsolationTest} 会
     * 在 24 张表上各灌 2 行（两租户各 1），并在 {@code @AfterAll} 用
     * <b>主键前缀</b>精确清理。把它在 provision 摘要里显形，是为了让”上一轮没清干净"
     * 这类事故在日志里可见 —— 否则它会以"租户 B 无行“之类的<b>误导性症状</b>出现在
     * 后续断言里（同 2026-09-22 那次实测踩坑的形态）。
     *
     * <p><b>2026-09-24（second）</b>：再追加 {@code v6}（V6 退款域三张留痕账本合计，
     * 即 {@code refund_statement} + {@code refund_receipt} + {@code refund_offline_notice}）。
     * 同一条理由 —— {@code RlsV6RefundLedgerIsolationTest} 会在这三张表上灌底/清理，
     * 让它的残留可见，才能在看日志时一眼分离”隔离断言失败“与”上一轮没清干净“。
     *
     * <p><b>2026-09-26</b>：追加 {@code v11k}/{@code v11t}（V11 的三张密钥材料表：
     * {@code tenant_kek} + {@code subject_dek} 合计，及 {@code subject_key_tombstone}）。
     * 🛑 两者<b>刻意分开计数</b>，因为它们的清理语义不同：
     * {@code RlsV11CryptoKeyIsolationTest} 会清理 kek/dek（删除正是 crypto-shredding 的动作），
     * 但<b>无法也刻意不清理墓碑</b>（V11 的 {@code tombstone_no_delete} 规则让 DELETE 静默无效，
     * 这是等保 2.0 三级”审计记录不可删除“的要求）。
     * 若合成一个数，”墓碑在增长“这件事就会被读成”没清干净“——
     * 那是把设计意图误判成缺陷。分开计数，才能让两者各自可判读。
     *
     * <p><b>2026-09-26（second）</b>：追加 {@code v14}（V14 的配置真相源两张租户表
     * {@code app_config} + {@code app_config_history} 合计）。同一条理由 ——
     * {@code RlsV14ConfigTruthSourceIsolationTest} 会灌底/清理，让它的残留可见，
     * 才能在 provision 日志里一眼分离”隔离断言失败“与”上一轮没清干净“。
     * ⚠️ 该类的清理必须先 {@code DISABLE} 历史表的 append-only 触发器再 {@code DELETE}
     * （V14 的 append-only 是 BEFORE 触发器 {@code RAISE 42501} = <b>响亮失败</b>，
     * 与 V11 墓碑表的 RULE {@code DO INSTEAD NOTHING} = <b>静默无效</b>恰好相反）——
     * 故这里若长期看到 {@code v14>0}，应先怀疑那一步的 {@code ENABLE} 恢复有没有跑完整。
     */
    static String dataSummary() {
        return queryScalar(SUPER_USER, superPassword(), DB,
                "SELECT format('tenant=%s customer=%s seed=%s extra=%s cst=%s band=%s sib=%s v5=%s bandx=%s v6=%s v11k=%s v11t=%s v14=%s',"
                        + " (SELECT count(*) FROM tenant),"
                        + " (SELECT count(*) FROM customer),"
                        + " (SELECT count(*) FROM customer WHERE id IN ('" + SEED_A_ID + "','" + SEED_B_ID + "')),"
                        + " (SELECT count(*) FROM customer WHERE id NOT IN ('" + SEED_A_ID + "','" + SEED_B_ID + "')),"
                        + " (SELECT count(*) FROM customer_state_transition),"
                        + " (SELECT count(*) FROM band),"
                        + " (SELECT count(*) FROM scale_item_bank),"
                        + " (SELECT count(*) FROM region),"
                        + " (SELECT count(*) FROM band_sync_probe)"
                        + "   + (SELECT count(*) FROM band_sync_log)"
                        + "   + (SELECT count(*) FROM band_daily_coverage),"
                        + " (SELECT count(*) FROM refund_statement)"
                        + "   + (SELECT count(*) FROM refund_receipt)"
                        + "   + (SELECT count(*) FROM refund_offline_notice),"
                        + " (SELECT count(*) FROM tenant_kek) + (SELECT count(*) FROM subject_dek),"
                        + " (SELECT count(*) FROM subject_key_tombstone),"
                        + " (SELECT count(*) FROM app_config) + (SELECT count(*) FROM app_config_history))");
    }

    // ------------------------------------------------------------------
    // 构建级互斥锁（Task #18 的核心）
    // ------------------------------------------------------------------

    private static volatile BuildMutex heldMutex;

    /**
     * 取得<b>构建级互斥锁</b>：门禁类在 {@code @BeforeAll} 调用它，在 {@code @AfterAll} 关掉。
     *
     * <h2>它解决什么</h2>
     * 两个构建并发跑同一个模块的门禁时，双方都会 provision + 在同一个库上跑断言。
     * 光把 provision 改成幂等<b>还不够</b>：{@code verification/03_assert.sql} 的 A1/A2/A8
     * 硬断言"每个租户恰好 1 行"（该脚本是上一轮证据基线，不得修改），
     * 而 {@code RlsTenantIsolationTest} 会在断言中途插入临时客户行 ——
     * 两个构建的<b>断言阶段</b>重叠时就会互相把对方的行数改掉。
     * 因此互斥必须覆盖<b>整个门禁类</b>，而不只是 provision 那一瞬间。
     *
     * <h2>为什么用 JDBC 连接持有，而不是 psql</h2>
     * 会话级 advisory lock 必须"连在"整个门禁类执行期间。psql 是一次性子进程，
     * 无法在 Java 侧持有它的会话；用一条常驻 JDBC 连接（连到维护库 {@code postgres}）
     * 才是可表达"持锁跨整个测试类"的形态。
     *
     * <h2>为什么锁在维护库而不是被测库</h2>
     * provision 可能需要 {@code DROP DATABASE}，被测库在那一刻可能还不存在 —— 连不上就没法持锁。
     * 维护库 {@code postgres} 永远可用。key 由被测库名派生，故不同模块之间不争用。
     */
    static BuildMutex acquireBuildMutex() {
        BuildMutex m = heldMutex;
        if (m != null && m.isHeld()) {
            return m;
        }
        try {
            m = BuildMutex.acquire();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "无法获取构建级互斥锁（库=" + DB + "，lock key=" + advisoryKey("gate") + "）。\n"
                            + "本锁让并发的第二个构建排队而不是拆库；取不到即 fail-closed，不静默降级。\n"
                            + "排查：① 是否有别的构建正在跑（等待超时时间见 -Ddy.rls.gate.mutex.timeout.seconds，"
                            + "默认 600s）；② postgres 维护库是否可连（见 unreachable 的指引）。", e);
        }
        heldMutex = m;
        return m;
    }

    /** 会话级 advisory lock 的持有者；{@link #close()} 释放锁并关闭连接。 */
    static final class BuildMutex implements AutoCloseable {

        private final Connection conn;
        private final long key;
        private final long waitedMillis;
        private volatile boolean held = true;

        private BuildMutex(Connection conn, long key, long waitedMillis) {
            this.conn = conn;
            this.key = key;
            this.waitedMillis = waitedMillis;
        }

        static BuildMutex acquire() throws SQLException {
            String url = "jdbc:postgresql://" + host() + ":" + port() + "/postgres";
            Connection c = DriverManager.getConnection(url, SUPER_USER, superPassword());
            long key = advisoryKey("gate");
            long timeoutSeconds = 600;
            String prop = System.getProperty("dy.rls.gate.mutex.timeout.seconds");
            if (prop != null && !prop.isBlank()) {
                timeoutSeconds = Long.parseLong(prop.trim());
            }
            long start = System.nanoTime();
            long deadline = start + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            try (Statement st = c.createStatement()) {
                while (true) {
                    // try-lock 而不是阻塞锁：可以给出"等了多久"和明确的超时报错，
                    // 而不是让构建无声地卡住（卡住的构建在 CI 上看不出原因）。
                    try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(" + key + ")")) {
                        rs.next();
                        if (rs.getBoolean(1)) {
                            break;
                        }
                    }
                    if (System.nanoTime() > deadline) {
                        c.close();
                        throw new SQLException("等待构建级互斥锁超时 " + timeoutSeconds + "s（库 " + DB
                                + "，key=" + key + "）：另一个构建正在使用该真库。"
                                + "可用 -Ddy.rls.gate.mutex.timeout.seconds=<秒> 调整等待上限。");
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        c.close();
                        throw new SQLException("等待构建级互斥锁被中断", ie);
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!c.isClosed()) {
                    try {
                        c.close();
                    } catch (SQLException ignore) {
                        // 已经在抛异常路径上，关闭失败不再叠加
                    }
                }
                throw e;
            }
            return new BuildMutex(c, key, (System.nanoTime() - start) / 1_000_000L);
        }

        boolean isHeld() {
            return held;
        }

        /** 本次构建为了拿锁等待了多久（ms）。并发实测时用它证明"确实排队了"。 */
        long waitedMillis() {
            return waitedMillis;
        }

        @Override
        public void close() {
            if (!held) {
                return;
            }
            held = false;
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_advisory_unlock(" + key + ")");
            } catch (SQLException ignore) {
                // 连接已断时锁会随会话结束自动释放，无需也无法再补救
            } finally {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                    // 同上
                }
                if (heldMutex == this) {
                    heldMutex = null;
                }
            }
        }
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
        for (int i = 0; i + 1 < vars.length; i += 2) {
            cmd.add("-v");
            cmd.add(vars[i] + "=" + vars[i + 1]);
        }
        cmd.add("-f");
        cmd.add(script.toAbsolutePath().toString());
        return exec(cmd, password);
    }

    static PsqlResult runSql(String user, String password, String database, String sql) {
        // 🛑 必须走 `-f 文件`，不能走 `-c 字符串` —— 这是【非 ASCII 的必要条件】：
        //    本机 Windows 控制台代码页为 GBK，`psql -c` 携带的中文会被进程层按 GBK 编码送出，
        //    而连接声明的是 UTF8 ⇒ PostgreSQL 报
        //    「无效的 "UTF8" 编码字节顺序: 0xc3 0xe6」并【整条语句被拒】。
        //    失败形态尤其危险：若调用方不检查返回码，看到的是"种子行没进去"而不是"编码错误"
        //    —— 本仓库实测踩到过：RLS 门禁的中文种子行静默缺失，表现为"租户 B 无行"，
        //    排查方向会被引到 RLS 策略上去。
        //    `-f` 走的是文件字节流（UTF-8 写盘，与 PGCLIENTENCODING=UTF8 一致），无此问题；
        //    这也正是 queryScalar 用 `-f` 的同一条理由。
        try {
            Path f = gateWorkDir().resolve("sql-" + PROBE_SEQ.incrementAndGet() + ".sql");
            Files.writeString(f, sql + "\n", StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(
                    psqlExecutable().toString(), "-X", "-h", host(), "-p", port(),
                    "-U", user, "-d", database, "-v", "ON_ERROR_STOP=1",
                    "-f", f.toAbsolutePath().toString()));
            return exec(cmd, password);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入待执行的 SQL 文件", e);
        }
    }

    /**
     * 取单值查询结果。
     *
     * <p>刻意走 {@code -f 文件} 而不是 {@code -c 字符串}：Windows 上命令行参数里的引号
     * 会被进程层改写（已被实测抓到），而这里的 SQL 必须携带单引号字面量
     * （库名 / 哨兵），一旦被改写就会变成"查询结果不对"而不是"命令失败"，极其难查。
     * 顺带每次查询原文都落在 {@code target/rls-gate/probe-N.sql}，可作证据复核。
     *
     * <p><b>为什么用命令行 {@code -t -A} 而不是脚本里的 {@code \pset}</b>：
     * {@code \pset format unaligned} 会往 stdout 打印一行确认语
     * {@code Output format is unaligned.}，它会混进被 {@code strip()} 的返回值里，
     * 使后续的"摘要串比较"永远不等 —— 这条坑已被实测踩到（报错原文就是那行确认语）。
     * 命令行开关不产生任何输出。
     */
    static String queryScalar(String user, String password, String database, String sql) {
        try {
            Path f = gateWorkDir().resolve("probe-" + PROBE_SEQ.incrementAndGet() + ".sql");
            Files.writeString(f, sql + "\n", StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(
                    psqlExecutable().toString(), "-X", "-h", host(), "-p", port(),
                    "-U", user, "-d", database,
                    "-v", "ON_ERROR_STOP=1",
                    "-t",            // tuples only：不打印表头/行数统计
                    "-A",            // unaligned：不填充空白
                    "-f", f.toAbsolutePath().toString()));
            PsqlResult r = exec(cmd, password);
            requireZero("probe(" + oneLine(sql) + ")", r);
            return r.output().strip();
        } catch (IOException e) {
            throw new IllegalStateException("无法写入探针 SQL 文件", e);
        }
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    private static PsqlResult exec(List<String> cmd, String password) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", password);
        // 本机控制台代码页为 GBK；若不钉死 UTF-8，psql 的中文错误原文会在证据文件里变成乱码，
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
    // 生成的脚本（全部在 target/rls-gate 下，每次运行由本类重写 —— 不是手工维护的资产）
    // ------------------------------------------------------------------

    /**
     * 显式重置脚本（<b>破坏性</b>）：{@code lock → DROP DATABASE → DROP ROLE → CREATE ROLE → CREATE DATABASE → unlock}。
     *
     * <p>🛑 必须是<b>一个 psql 进程</b>：{@code psql -f} 默认逐句自动提交，
     * 而会话级 advisory lock 跨语句持续有效，所以整段临界区都在锁内。
     * 若拆成多条 {@code psql -c}，每条命令各自新建连接、命令结束即释放锁 —— <b>等于无锁</b>，
     * 两个并发重置会交错（复现 {@code database "..." already exists} 之类的半成品状态）。
     */
    static Path destructiveResetScript() throws IOException {
        Path out = gateWorkDir().resolve("01_reset_locked.sql");
        // PASSWORD 需要字符串字面量（不能走 psql 的标识符插值，否则 `PASSWORD dy_app_rls` 是语法错误）。
        // 这里直接内联 Java 常量 —— 它是编译期固定的测试口令，非用户输入，无注入面。
        String sql = """
                \\set ON_ERROR_STOP on
                -- 会话级互斥锁：psql 单进程 = 单连接，锁覆盖整段 DROP/CREATE 临界区
                SELECT pg_advisory_lock(:lockkey);
                DROP DATABASE IF EXISTS :dbname WITH (FORCE);
                DROP ROLE IF EXISTS :rolename;
                CREATE ROLE :rolename LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                -- 🛑 库 owner 必须是【非超级用户】应用角色，不能默认落给 postgres：
                --    第 2 步（应用整条迁移链）已改为【用应用角色】执行 —— 这是 V18/V19 的
                --    (a0) 能力守卫所要求的（那些自证的 4 条判据依赖 RLS 真正生效，
                --    而 BYPASSRLS 角色会让它们静默失效）。应用角色要能建表，
                --    前提就是它拥有这个库（或至少对 public schema 有 CREATE）。
                CREATE DATABASE :dbname OWNER :rolename;
                SELECT pg_advisory_unlock(:lockkey);
                \\echo '01-reset-locked OK (DROP+CREATE 在单连接持锁下完成, owner=非超级用户)'
                """.formatted(APP_PASSWORD);
        Files.writeString(out, sql, StandardCharsets.UTF_8);
        return out;
    }

    /**
     * 生成"整条迁移链"的可移植应用脚本：逐个 {@code \i :v1 :v2 …} 指向<b>被测交付物</b>的真实迁移。
     *
     * <p>Harness 动作（授权 + 移交 owner）与 {@code verification/015_apply.sql} L19-L25 一致——
     * 移交 owner 是 {@code FORCE ROW LEVEL SECURITY} 可验证的前提：owner 若是超级用户，
     * 超级用户本就绕过 RLS，FORCE 将无从证明（A6 会假通过）。
     *
     * <p>末尾落 schema 哨兵：<b>只有整条迁移链全部成功才会执行到这一句</b>，
     * 故"哨兵存在且匹配"等价于"这个库确实由这一版迁移链建成"。
     * 放在 {@code COMMENT ON DATABASE} 而不是新建一张标记表，是为了不给 {@code public} schema
     * 增加任何表 —— 否则 {@code RlsCoverageGateTest} 的"迁移声明的表 == 真库实际的表"会红。
     *
     * <p><b>2026-09-22 改造</b>：原实现只 {@code \i :v1}。改为遍历 {@link #migrationScripts()}，
     * 由 Java 侧拼出等量的 {@code \i :vN} 行并逐个传 {@code -v vN=<abs>}。这样新增迁移
     * （如本次的 V2）无需改动本类，也不会出现"只应用了一半链"的静默错配。
     *
     * <p>owner 移交同样<b>遍历</b> {@code pg_class} 而非硬编码表名：硬编码会在
     * "新增一张表却忘记移交 owner"时让那张表的 FORCE 断言失去证明力。
     */
    static Path portableApplyScript() throws IOException {
        Path out = gateWorkDir().resolve("015_apply_portable.sql");
        List<Path> scripts = migrationScripts();
        StringBuilder apply = new StringBuilder();
        for (int i = 0; i < scripts.size(); i++) {
            apply.append("\\echo '-- portable apply: ").append(scripts.get(i).getFileName()).append(" --'\n")
                 .append("\\i :v").append(i + 1).append('\n');
        }
        String sql = """
                \\set ON_ERROR_STOP on
                SELECT pg_advisory_lock(:lockkey);
                \\echo '-- portable apply: applying the REAL migration chain from the deliverable --'
                %s
                GRANT USAGE, CREATE ON SCHEMA public TO :approle;
                -- 遍历移交 owner: 硬编码表名会漏掉新增表, 使 FORCE RLS 无从证明
                SELECT format('ALTER TABLE public.%%I OWNER TO %%I;', c.relname, :'approle')
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relkind = 'r'
                 ORDER BY c.relname
                \\gexec
                COMMENT ON DATABASE :dbname IS :'sentinel';
                SELECT pg_advisory_unlock(:lockkey);
                \\echo '015-portable-locked OK'
                """.formatted(apply.toString());
        Files.writeString(out, sql, StandardCharsets.UTF_8);
        return out;
    }

    /** 组装 {@code -v v1=… -v v2=…} 形式的 psql 变量（供 {@link #portableApplyScript()} 的 {@code \i :vN} 用）。 */
    static String[] migrationVarArgs() {
        List<Path> scripts = migrationScripts();
        String[] args = new String[scripts.size() * 2];
        for (int i = 0; i < scripts.size(); i++) {
            args[i * 2] = "v" + (i + 1);
            args[i * 2 + 1] = scripts.get(i).toAbsolutePath().toString();
        }
        return args;
    }

    /**
     * 数据层幂等脚本（<b>非破坏性</b>：只动数据，绝不 DROP 库对象）：
     * {@code lock → 判是否精确种子态 → （否）TRUNCATE + 重灌 → unlock}。
     *
     * <p>以<b>非超级用户</b>执行：种子写入本身就是"设对上下文时策略是连通的、不是全表拒绝"的证据。
     *
     * <h2>为什么"已是精确种子态"就<b>零写入</b></h2>
     * 这是"同一模块并发两个构建互不破坏"的关键。若每次都 {@code TRUNCATE}+重灌，
     * 那么第二个构建会在第一个构建<b>断言进行中</b>把它的行清掉 —— 症状是随机的行数错位，
     * 而不是一条指向真因的报错。判到精确种子态后零写入，两个走幂等路径的构建就<b>完全不接触</b>对方的数据。
     *
     * <h2>2026-09-22 改造：清库表集合改为<b>从目录派生</b></h2>
     * 原实现硬编码 {@code TRUNCATE customer, tenant}。V2 引入的两张表（{@code customer_state_transition} /
     * {@code band}）都<b>外键引用</b> {@code customer}，于是这条 TRUNCATE 被
     * {@code 0A000 "在一个外键约束中无法删除(truncate)一个表的关联"} 挡住 ——
     * 门禁在 provision 阶段就红，看起来像业务 DDL 有 bug，实为清库语句没跟上 schema。
     *
     * <p>现改为在脚本内查询 {@code pg_constraint}，把 {@code tenant} 的<b>全部传递闭包依赖表</b>
     * 拼进一条 {@code TRUNCATE}：<b>schema 一扩展，清库自动跟上，无需再改本类</b>。
     * 闭包是"包含所有依赖表"的，故不会遗漏；多截断空表无害。
     */
    static Path seedEnsureScript() throws IOException {
        Path out = gateWorkDir().resolve("02_seed_ensure_locked.sql");
        String sql = """
                \\set ON_ERROR_STOP on
                SELECT pg_advisory_lock(:lockkey);
                -- 幂等判据必须【带租户上下文】读 customer：该表是 FORCE RLS，
                -- 未设 app.tenant_id 时 count(*) 恒为 0 —— 若照抄"无上下文 count"，判据永远为假，
                -- 就会退化成"每次构建都 TRUNCATE 掉对方正在断言的数据"，正是本任务要治的病。
                -- （这条坑是实测踩出来的：脚本能跑完、退出码 0、数据也对，但"零写入"分支永不进入。）
                BEGIN;
                SET LOCAL app.tenant_id = :'tA';
                SELECT CASE WHEN (SELECT count(*) FROM customer) = 1
                             AND (SELECT count(*) FROM customer WHERE id = :'seedA'::uuid) = 1
                       THEN 'true' ELSE 'false' END AS a_ok
                \\gset
                COMMIT;
                BEGIN;
                SET LOCAL app.tenant_id = :'tB';
                SELECT CASE WHEN (SELECT count(*) FROM customer) = 1
                             AND (SELECT count(*) FROM customer WHERE id = :'seedB'::uuid) = 1
                       THEN 'true' ELSE 'false' END AS b_ok
                \\gset
                COMMIT;
                -- tenant 不参与 RLS，可无上下文直读；它的行数能兜住"多出一个租户"这种情况
                SELECT CASE WHEN (SELECT count(*) FROM tenant) = 2
                             AND :'a_ok'::boolean AND :'b_ok'::boolean
                       THEN 'true' ELSE 'false' END AS seed_exact
                \\gset
                \\if :seed_exact
                  \\echo '02-ensure: 库已是【精确种子态】-> 零写入（不对并发的另一个构建造成任何改动）'
                \\else
                  \\echo '02-ensure: 库存在但数据非精确种子态 -> TRUNCATE + 重灌（只动数据，不 DROP 库对象）'
                  -- 从目录派生"需清空的表"：tenant 的传递闭包（含所有 FK 引用 tenant 的表，
                  -- 递归下去）。硬编码表名会在 schema 扩展时被外键挡住（见本方法 javadoc）。
                  WITH RECURSIVE dep(relid) AS (
                      SELECT 'public.tenant'::regclass::oid
                    UNION
                      SELECT c.conrelid
                        FROM pg_constraint c
                        JOIN dep d ON c.confrelid = d.relid
                       WHERE c.contype = 'f'
                  )
                  SELECT 'TRUNCATE TABLE ' || string_agg(quote_ident(relname), ', ') || ';'
                    FROM dep d JOIN pg_class cl ON cl.oid = d.relid
                   WHERE cl.relkind = 'r'
                  \\gexec
                  \\i :seed
                \\endif
                SELECT pg_advisory_unlock(:lockkey);
                \\echo '02-ensure-locked OK'
                """;
        Files.writeString(out, sql, StandardCharsets.UTF_8);
        return out;
    }

    // ------------------------------------------------------------------
    // provision
    // ------------------------------------------------------------------

    /**
     * 建库前置（Task #18 三条路径 + 后置校验）。任何一步失败都以退出码/断言报红，绝不静默降级。
     *
     * <table border="1">
     *   <caption>三条路径</caption>
     *   <tr><th>条件</th><th>路径</th><th>动作</th></tr>
     *   <tr><td>{@code DY_TESTDB_RESET=1}</td><td>EXPLICIT_RESET</td>
     *       <td>单连接持锁 DROP+CREATE → 应用迁移 → 清数据+重灌</td></tr>
     *   <tr><td>库缺失 / 哨兵不匹配</td><td>AUTO_REBUILD</td>
     *       <td>同上（不能只"补迁移"：{@code CREATE POLICY} 非幂等，schema 半新半旧时必须重建）</td></tr>
     *   <tr><td>哨兵匹配（缺省）</td><td>IDEMPOTENT</td>
     *       <td><b>不动库对象</b>；数据已是精确种子态则零写入，否则清数据+重灌</td></tr>
     * </table>
     *
     * <p>后置校验两道，专治"看起来跑过了其实没清干净"：
     * <ol>
     *   <li>库 OID：重建路径下必须变（证明 DROP/CREATE 真发生了）；<b>缺省幂等路径下必须不变</b>
     *       （证明缺省真的没有 DROP）。</li>
     *   <li>数据摘要必须以 {@link #EXPECTED_DATA_SUMMARY_PREFIX} 开头（{@code extra=0} 即真清干净了）。</li>
     * </ol>
     *
     * @return 全过程日志（同时落在 {@code target/rls-gate/provision.log} 作证据）
     */
    static String provisionRealDatabase() throws IOException {
        BuildMutex mutex = heldMutex;
        if (mutex == null || !mutex.isHeld()) {
            throw new IllegalStateException(
                    "provision 必须在【构建级互斥锁】内调用（Task #18 构建间互斥）。\n"
                            + "请先在门禁类的 @BeforeAll 里调用 RlsGateSupport.acquireBuildMutex()，"
                            + "并在 @AfterAll 里 close()；\n"
                            + "否则并发的第二个构建会在本门禁的断言进行中重建同一个真库 —— "
                            + "那时症状是随机行数/连接错误，而不是一条指向真因的报错。");
        }

        Path ver = verificationDir();
        StringBuilder log = new StringBuilder();
        String sentinel = sentinel();
        DbState stateBefore = dbState();
        String oidBefore = dbOid();
        boolean explicit = resetRequested();
        boolean destructive = explicit || stateBefore != DbState.SENTINEL_OK;

        String path = explicit
                ? "EXPLICIT_RESET(DY_TESTDB_RESET=1 -> DROP + 重建)"
                : destructive
                ? "AUTO_REBUILD(哨兵缺失/不匹配 -> DROP + 重建)"
                : "IDEMPOTENT(哨兵匹配 -> 只动数据、不动库对象)";
        log.append("[provision] db=").append(DB).append(" role=").append(APP_USER)
                .append(" sentinel=").append(sentinel)
                .append(" state_before=").append(stateBefore)
                .append(" oid_before=").append(oidBefore)
                .append(" DY_TESTDB_RESET=").append(String.valueOf(System.getenv("DY_TESTDB_RESET")))
                .append(" mutex_waited_ms=").append(mutex.waitedMillis())
                .append("\n[provision] 路径=").append(path).append('\n');

        long keyPhase = advisoryKey("reset");

        if (destructive) {
            // 1) 破坏性重置：单连接持锁（lock -> DROP/CREATE -> unlock）
            PsqlResult r1 = runScript(SUPER_USER, superPassword(), "postgres", destructiveResetScript(),
                    "dbname", DB, "rolename", APP_USER, "lockkey", Long.toString(keyPhase));
            log.append("[01_reset_locked] EXIT=").append(r1.exitCode()).append('\n').append(r1.output());
            requireZero("01_reset_locked(单连接持锁)", r1);

            // 2) 应用【被测交付物】的真实迁移链（V1 + V2 + …），并落 schema 哨兵。
            //
            // 🛑🛑 **必须用【非超级用户】应用角色执行，不能用超级用户**（2026-09-27 修正）。
            //    原实现用 SUPER_USER 应用整条链，一直是"能跑通"的 —— 直到 V18/V19
            //    引入了 (a0) 能力守卫：
            //        实测（本轮全量回归）：V18 自证以 (a0) 报红，消息逐字是
            //        "当前角色 postgres 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 4 条判据
            //         全部失去效力】…"
            //    那条红是**正确**的：V18/V19 的自证里，(e3) 不落库 / (e5) 零行 /
            //    (e5) 对照 / (e8) 可见性 这 4 条判据全部建立在"当前角色受 RLS 约束"之上；
            //    用 BYPASSRLS 角色跑，它们会静默失效 —— 那时自证产出的是一份
            //    "看起来跑了、实际什么都没验"的证据。
            //    ⇒ 正确处置是【换角色】，不是放宽 (a0)、也不是改被测函数。
            //      （已实测：应用角色跑整条链 EXIT=0，V18/V19 自证全部通过。
            //        owner 移交与 GRANT 仍按原样保留，故 FORCE RLS 的可验证前提不变。）
            //
            //    顺带说明为什么这样更"对"：provisionRealDatabase 的其余步骤
            //    （seed / 断言）本来就都用 APP_USER；唯独这一步用 super 只是因为
            //    "建表历史上需要"。现在库的 owner 已是应用角色（01_reset 里建库时指定），
            //    建表不需要 super ⇒ 整条 provision 路径里不再有任何 BYPASSRLS 角色
            //    参与"被测交付物"的应用。
            List<String> applyVars = new ArrayList<>(List.of(
                    "approle", APP_USER, "dbname", DB, "sentinel", sentinel,
                    "lockkey", Long.toString(keyPhase)));
            applyVars.addAll(List.of(migrationVarArgs()));
            PsqlResult r2 = runScript(APP_USER, APP_PASSWORD, DB, portableApplyScript(),
                    applyVars.toArray(new String[0]));
            log.append("[015_apply_locked] EXIT=").append(r2.exitCode()).append('\n').append(r2.output());
            requireZero("015_apply_locked(单连接持锁)", r2);
        }

        // 3) 数据层幂等（三条路径都要走；非超级用户执行）
        PsqlResult r3 = runScript(APP_USER, APP_PASSWORD, DB, seedEnsureScript(),
                "seed", ver.resolve("02_seed.sql").toAbsolutePath().toString(),
                "lockkey", Long.toString(keyPhase),
                "tA", TENANT_A, "tB", TENANT_B,
                "seedA", SEED_A_ID, "seedB", SEED_B_ID);
        log.append("[02_seed_ensure_locked] EXIT=").append(r3.exitCode()).append('\n').append(r3.output());
        requireZero("02_seed_ensure_locked(单连接持锁)", r3);

        // --- 后置校验 ①：库 OID 必须与路径一致（两个方向都有牙齿） ---
        String oidAfter = dbOid();
        if (destructive) {
            if (Objects.equals(oidBefore, oidAfter)) {
                throw new IllegalStateException("重建路径下库 OID 未变化(" + oidBefore
                        + ") —— DROP/CREATE 实际没有发生，属假绿。");
            }
        } else if (!Objects.equals(oidBefore, oidAfter)) {
            throw new IllegalStateException("缺省幂等路径不得重建库，但 OID 从 " + oidBefore
                    + " 变成了 " + oidAfter + " —— 幂等路径退化成了无条件 DROP。");
        }
        log.append("[post-oid] before=").append(oidBefore).append(" after=").append(oidAfter)
                .append(" 变化=").append(!Objects.equals(oidBefore, oidAfter)).append('\n');

        // --- 后置校验 ②：customer/tenant 维度必须真的干净（extra=0），否则"顺利跑完"就是假绿 ---
        //     尾部 cst=… band=… 是观测项：各隔离测试类自行灌底/清理, 故只比对前缀。
        String summary = dataSummary();
        log.append("[post-data] ").append(summary).append(" (期望前缀 ")
                .append(EXPECTED_DATA_SUMMARY_PREFIX).append(")\n");
        if (!summary.startsWith(EXPECTED_DATA_SUMMARY_PREFIX)) {
            throw new IllegalStateException("provision 后置校验失败：期望前缀 " + EXPECTED_DATA_SUMMARY_PREFIX
                    + "，实际 " + summary + "\n"
                    + "extra>0 说明非种子行没清干净（后续断言会建立在一个被污染的起点上）；"
                    + "seed<2 说明种子缺失。两种情况都必须报红，不许\"顺利跑完\"。");
        }

        Path evidence = gateWorkDir().resolve("provision.log");
        Files.writeString(evidence, log.toString(), StandardCharsets.UTF_8);
        return log.toString();
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
                "RLS 真库门禁无法连接到 PostgreSQL (jdbc:postgresql://" + host() + ":" + port() + ") —— "
                        + "本门禁按 ADR-02 L3 采用 fail-closed：连不上库即视为『缺少租户隔离测试』，构建必须失败。\n"
                        + "启动真库两种方式：\n"
                        + "  ① 本机 PG：确认 PostgreSQL 服务已启动，且 postgres 口令可用（DY_PG_SUPER_PASSWORD）\n"
                        + "  ② Docker：docker run -d --name dy-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16\n"
                        + "确需在无库环境跳过（例如纯编译流水线），显式传 -Ddy.rls.gate.skip=true。",
                cause);
    }

    static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ------------------------------------------------------------------
    // 摘要工具
    // ------------------------------------------------------------------

    private static String sha256Hex(byte[] data) {
        byte[] d = sha256Bytes(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] sha256Bytes(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
