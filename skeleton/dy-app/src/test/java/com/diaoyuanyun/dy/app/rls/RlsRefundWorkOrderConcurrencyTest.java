package com.diaoyuanyun.dy.app.rls;

import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.repository.RefundWorkOrderLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-4 · {@code RefundWorkOrderLedger} 的<b>真库并发与改写约束</b>门禁。
 *
 * <h1>为什么必须单独立一个类（而不是并进 {@link RlsV6RefundLedgerIsolationTest}）</h1>
 * 那个类管的是 <b>V6 三张留痕账本的隔离面</b>（跨租户不可见 / fail-closed / 约束面）。
 * 本类管的是<b>另一件事</b>：{@code refund} 表上那**唯一一条受控 UPDATE**
 * （{@code UPDATE ... WHERE refund_id = ? AND outcome = ? AND outcome <> '归档'}）
 * 的乐观并发语义。混进隔离类会让"隔离"与"并发"两个结论互相稀释 ——
 * 而它们失败时的排查方向完全不同（一个查 RLS 策略，一个查 WHERE 子句）。
 *
 * <h1>🛑 本类要证明的三件事，都是"单测全绿也发现不了"的那一类</h1>
 * <ol>
 *   <li><b>乐观并发的判定与写入确实是同一条语句。</b>
 *       若有人把它改成"先 SELECT 校验、再 UPDATE"，在**单线程**测试里永远全绿 ——
 *       因为单线程下"读到的值"和"写入时的值"必然一致。只有让<b>两个真线程同时推进同一张工单</b>，
 *       那个时序窗口才会显形。本类用真线程 + <b>各自独立的数据库连接</b>来开这扇窗。</li>
 *   <li><b>归档后的工单不可再被推进。</b>这条纪律写在 SQL 的 {@code AND outcome <> '归档'} 里。
 *       一个只在 Java 侧判 {@code expectedOutcome.isClosed()} 的实现是**不够的**：
 *       调用方完全可以用 {@code expected=继续} 去推一张<b>库里已是归档</b>的工单
 *       （它读的是过期快照），此时 Java 侧那道判断拦不住，只有 WHERE 里的归档谓词拦得住。</li>
 *   <li><b>0 行受影响时必须报 4001 而不是静默成功。</b>把"结局推进失败"当成"无事发生"
 *       会让调用方以为一次审批结论已落库，而实际上它被并发者覆盖掉了 ——
 *       在本域那意味着一次审批被静默抹掉。</li>
 * </ol>
 *
 * <h1>🛑 为什么并发用例【不能】复用 {@code RlsV6RefundLedgerIsolationTest} 的 SingleConnectionDataSource</h1>
 * 那个类刻意用 {@code SingleConnectionDataSource} —— 它把<b>所有</b> {@code getConnection()}
 * 都返回同一条物理连接，于是"并发"退化成"在一条连接上排队"。
 * 对隔离断言这没问题（甚至更稳），但对本类的核心用例是<b>致命</b>的：
 * 两个线程若共用一条连接，后一个 UPDATE 会等前一个的事务结束，
 * **恰好绕过**我们想观察的那个窗口 —— 用例会恒绿，却什么也没证明。
 * 故本类改用 {@link DriverManagerDataSource}（每次 {@code getConnection()} 新建物理连接），
 * 让两个线程真的落在两条连接上。
 *
 * <h1>断言为什么是确定性的，而不是"看运气"</h1>
 * 三线程等 {@code startGun} 同时放行，确实不能保证它们真的重叠 ——
 * 但结论不依赖重叠：PostgreSQL 在 READ COMMITTED 下，
 * 后到的 UPDATE 会等前一个提交，然后<b>重新求值 WHERE</b>（EvalPlanQual），
 * 于是它必然看到已被改掉的 {@code outcome} 而影响 0 行。
 * 所以无论线程如何交错，结果恒为「恰一个成功、其余全部 4001」。
 * 这正是"把判定放进 WHERE"相对"先读后写"的价值：
 * <b>应用层不用做任何时序假设，并发正确性来自数据库的锁语义。</b>
 */
@DisplayName("C-4 · 退款工单受控 UPDATE 的真库并发与改写约束")
class RlsRefundWorkOrderConcurrencyTest {

    private static final String TENANT_A = RlsGateSupport.TENANT_A;
    private static final String TENANT_B = RlsGateSupport.TENANT_B;

    /** 本类专用主键后缀，与邻类（V5 用 0001/0002、V6 用 0003/0004）零交集。 */
    private static final String SFX_A = "0005";
    private static final String SFX_B = "0006";

    /** 被推进的工单主键（每租户一条，各自全局唯一）。 */
    private static final String REFUND_A = "00000000-0000-0000-" + SFX_A + "-000000000004";
    private static final String REFUND_B = "00000000-0000-0000-" + SFX_B + "-000000000004";

    private static DriverManagerDataSource poolDataSource;
    private static DriverManagerDataSource consoleDataSource;
    private static JdbcTemplate consoleJdbc;
    private static RefundWorkOrderLedger ledger;
    private static RlsGateSupport.BuildMutex buildMutex;
    private static String jdbcUrl;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    static void provision() throws Exception {
        if (RlsGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "RLS 真库门禁被显式跳过: -Ddy.rls.gate.skip=true");
        }

        buildMutex = RlsGateSupport.acquireBuildMutex();
        String provisionLog = RlsGateSupport.provisionRealDatabase();
        System.out.println("[C4-GATE] provision:\n" + provisionLog);

        jdbcUrl = "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port()
                + "/" + RlsGateSupport.DB;

        // 🛑 并发用例必须每线程一条真连接（见类注释），故用 DriverManagerDataSource 而非
        //    SingleConnectionDataSource。它不做池化，每次 getConnection 新建物理连接。
        poolDataSource = new DriverManagerDataSource(jdbcUrl,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD);
        ledger = new RefundWorkOrderLedger(poolDataSource);

        // 观测用连接（读库中真实 outcome、做归档注入）。
        consoleDataSource = new DriverManagerDataSource(jdbcUrl,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD);
        consoleJdbc = new JdbcTemplate(consoleDataSource);

        try {
            String whoAmI = consoleJdbc.queryForObject("select current_user", String.class);
            assertEquals(RlsGateSupport.APP_USER, whoAmI,
                    "门禁必须用非超级用户执行；实际连接用户=" + whoAmI);
            Boolean isSuper = consoleJdbc.queryForObject(
                    "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
            assertEquals(Boolean.FALSE, isSuper,
                    "当前连接是超级用户 → 会绕过 RLS → 断言将『假通过』(ADR-02 陷阱 3)");
        } catch (RuntimeException e) {
            throw RlsGateSupport.unreachable(e);
        }

        provisionSeeds();
    }

    @AfterAll
    static void releaseBuildMutexAndCleanup() {
        try {
            RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                    RlsGateSupport.DB, buildCleanupSql());
        } finally {
            if (buildMutex != null) {
                buildMutex.close();
                buildMutex = null;
            }
        }
    }

    @BeforeEach
    void resetState() {
        TenantContext.clear();
        // 每条用例前把两张工单恢复成「继续」：并发用例会把它们推进掉，
        // 若不重置，第二条用例的期望值就不成立（且会误报成"乐观并发坏了"）。
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, resetOutcomeSql());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 一、乐观并发（真线程 + 独立连接）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("一 · 乐观并发：判定与写入必须是同一条语句")
    class OptimisticConcurrency {

        @Test
        @DisplayName("🛑 两线程同时推进同一工单：恰一个成功，另一个必须 4001（不是静默成功）")
        void two_concurrent_promotions_yield_exactly_one_winner() throws Exception {
            int threads = 3;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGun = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();
            StringBuilder diag = new StringBuilder();

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGun.await();
                            // 每个线程各走一次 updateOutcome：期望「继续」→ 目标「终止」。
                            // 三者读到的是同一个期望值，但只有一个能把它改写掉。
                            ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                                    RefundOutcome.CONTINUE, RefundOutcome.TERMINATE);
                            wins.incrementAndGet();
                        } catch (BizException e) {
                            if (e.getCode() == ErrorCode.VERSION_CONFLICT.getCode()) {
                                conflicts.incrementAndGet();
                            } else {
                                unexpected.incrementAndGet();
                                synchronized (diag) {
                                    diag.append("[意外码] ").append(e.getCode()).append(": ")
                                            .append(e.getMessage()).append('\n');
                                }
                            }
                        } catch (Exception e) {
                            unexpected.incrementAndGet();
                            synchronized (diag) {
                                diag.append("[意外异常] ")
                                        .append(e.getClass().getName()).append(": ")
                                        .append(e.getMessage()).append('\n');
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGun.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "并发线程未在 60s 内结束");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(0, unexpected.get(),
                    "并发推进出现了非 4001 的异常 —— 说明失败路径没有收敛到版本冲突：\n" + diag);
            assertEquals(1, wins.get(),
                    "🛑 必须恰有 1 次推进成功（3 个线程读到的期望值相同，库里的值只能被改一次）。"
                            + "实际成功 " + wins.get() + " 次 —— 若 >1，说明 WHERE 里的 outcome 期望谓词"
                            + "被削弱成了『先读、再判、后写』，并发下会有审批结论被覆盖。");
            assertEquals(threads - 1, conflicts.get(),
                    "🛑 其余全部应报 VERSION_CONFLICT(4001)。实际 " + conflicts.get()
                            + " —— 若为 0，说明失败被静默吞掉，调用方会以为自己的推进成功了");

            // 落库终态必须确凿是「终止」，且只有一次改动发生（updated_at 被刷新过一次）
            String stored = readOutcome(TENANT_A, REFUND_A);
            assertEquals(RefundOutcome.TERMINATE.code(), stored,
                    "库中 outcome 应已被推进为『终止』，实际=" + stored);
        }

        @Test
        @DisplayName("陈旧的期望值 → 4001（调用方读到的是过期快照时，写入必须被拒）")
        void stale_expected_outcome_is_rejected_with_version_conflict() {
            // 库里此刻是「继续」（@BeforeEach 已重置）。用陈旧的期望值「终止」去推进：
            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                            RefundOutcome.TERMINATE, RefundOutcome.CONTINUE),
                    "期望值与库中当前值不符时，必须拒绝而不是静默影响 0 行后返回成功");
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "应报 4001（版本冲突）。4001 而非 404 是刻意的："
                            + "『工单不存在』与『结局已变』处置方式不同，"
                            + "但两者都导致 0 行，故统一 4001 并在消息里请调用方区分。实际=" + e.getCode());

            assertEquals(RefundOutcome.CONTINUE.code(), readOutcome(TENANT_A, REFUND_A),
                    "被拒的写入不得改动库中任何值（现状应仍为『继续』）");
        }

        @Test
        @DisplayName("不存在的工单 → 4001（与『结局不符』合并，避免存在性探测）")
        void absent_work_order_is_reported_as_version_conflict() {
            UUID ghost = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, ghost,
                            RefundOutcome.CONTINUE, RefundOutcome.TERMINATE));
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "🛑 不存在的工单必须与『结局不符』给同一个码（4001）—— "
                            + "若给它一个独立的 404，调用方就能靠错误码区分"
                            + "『这条工单不属于我』与『这条工单不存在』，那正是租户存在性探测的入口。"
                            + "实际=" + e.getCode());
        }
    }

    // ------------------------------------------------------------------
    // 二、归档后不可再推进（SQL 里的归档谓词是承重的）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("二 · 归档后拒写：WHERE 里的归档谓词必须承重")
    class ArchivedIsReadOnly {

        @Test
        @DisplayName("🛑 库里已是『归档』且调用方拿的是【最新】期望值（归档）→ 仍必须 4001")
        void archived_row_cannot_be_promoted_even_with_a_fresh_expected_value() {
            // 🛑 本用例的构造是【唯一】能测出归档谓词承重力的那种，首版写错了必须记下来：
            //    首版用的是【陈旧】期望值（期望「继续」、库已是「归档」）。那样一来，
            //    `AND outcome = ?` 这个期望值谓词自己就不命中，写入本就影响 0 行 ⇒
            //    无论 `AND outcome <> '归档'` 在不在，结果都是 4001 ——
            //    于是反向验证「把归档谓词删掉」时门禁【仍然全绿】，用例什么也没测出来。
            //    这是被反向验证抓出来的真缺陷（不是猜的）。
            //
            //    正确构造：让期望值与库中【一致】（都是「归档」）⇒ 期望值谓词必然命中，
            //    此时唯一还能拦住这次写入的就只剩 `AND outcome <> '归档'`。
            //    删掉归档谓词，本用例立刻会因「未抛异常 = 推进成功了」而变红。
            archiveDirectly(TENANT_A, REFUND_A);

            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                            RefundOutcome.ARCHIVED, RefundOutcome.CONTINUE),
                    "🛑 已归档的工单不得被推进 —— 期望值与库中一致（都是归档）时，"
                            + "期望值谓词必然命中，唯一能拦住它的就是 `AND outcome <> '归档'`。"
                            + "若这里没抛，说明归档谓词没起作用，归档就退化成了一个可被随手改回去的普通字段"
                            + "（归档动作本该同时落 case_archive 与结案清单校验，见 PRD P0-14）");
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "应报 4001（0 行受影响）。实际=" + e.getCode());

            assertEquals(RefundOutcome.ARCHIVED.code(), readOutcome(TENANT_A, REFUND_A),
                    "被拒的写入不得改动归档态");
        }

        @Test
        @DisplayName("陈旧期望值 + 库已归档 → 4001（期望值谓词与归档谓词各自独立承重）")
        void archived_row_with_stale_expected_value_is_also_rejected() {
            // 与上一条互补：这一条走的是 `AND outcome = ?` 这条防线
            // （调用方读的是过期快照，库里早已归档）。两条防线各自独立，
            // 故两个构造都要在，且各自断言 4001 —— 去掉任一条都会让某个构造变绿。
            archiveDirectly(TENANT_A, REFUND_A);

            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                            RefundOutcome.CONTINUE, RefundOutcome.TERMINATE),
                    "已归档的工单 + 陈旧期望值，必须被拒");
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "应报 4001。实际=" + e.getCode());

            assertEquals(RefundOutcome.ARCHIVED.code(), readOutcome(TENANT_A, REFUND_A),
                    "被拒的写入不得改动归档态");
        }

        @Test
        @DisplayName("把工单推进到『归档』必须被拒（归档只能走收口路径，不能是普通字段改写）")
        void promoting_to_archived_through_update_outcome_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                            RefundOutcome.CONTINUE, RefundOutcome.ARCHIVED),
                    "🛑 归档是结案状态位，不是可被『推进』到的业务结局。"
                            + "若从本方法可达，归档就会退化成一次普通字段改写，"
                            + "绕过 case_archive 落地与结案清单校验（PRD P0-14）");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode(),
                    "应报 5001（业务规则）—— 这是**调用契约错误**而非并发冲突，故不是 4001。"
                            + "实际=" + e.getCode());
        }

        @Test
        @DisplayName("目标结局与期望结局相同 → 5001（避免乐观并发下的『静默成功』）")
        void no_op_promotion_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_A),
                            RefundOutcome.CONTINUE, RefundOutcome.CONTINUE),
                    "🛑 一次不改动任何东西的写入没有业务含义，而它在乐观并发下会『静默成功』"
                            + "（WHERE 命中自己），让调用方误以为推进发生过");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode(),
                    "应报 5001。实际=" + e.getCode());
        }
    }

    // ------------------------------------------------------------------
    // 三、跨租户：别人的工单推进不了
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("三 · 跨租户推进必须失败（RLS 在写路径上同样承重）")
    class CrossTenantWrite {

        @Test
        @DisplayName("🛑 用租户A 的上下文推进租户B 的工单 → 4001（0 行，RLS 挡住）")
        void cannot_promote_another_tenants_work_order() {
            // 租户B 的工单在租户A 上下文下不可见 ⇒ WHERE 命不中 ⇒ 0 行 ⇒ 4001。
            BizException e = assertThrows(BizException.class,
                    () -> ledger.updateOutcome(TENANT_A, UUID.fromString(REFUND_B),
                            RefundOutcome.CONTINUE, RefundOutcome.TERMINATE),
                    "🛑 跨租户推进必须失败。若这里成功，等于租户A 能改写租户B 的退款工单 —— "
                            + "写路径上的 RLS 缺口，比读路径的泄漏更严重");
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "应报 4001（0 行）。实际=" + e.getCode());

            // 反证：租户B 的工单确实存在且仍为『继续』（否则上面的失败无意义）
            assertEquals(RefundOutcome.CONTINUE.code(), readOutcome(TENANT_B, REFUND_B),
                    "前置反证：租户B 的工单应仍为『继续』—— 它没被别人改过");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 用【独立连接】读某工单的真实 outcome —— 必须自设租户上下文（resetState 会清掉）。 */
    private static String readOutcome(String tenantId, String refundId) {
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT outcome FROM refund WHERE refund_id = '" + refundId + "'::uuid")) {
                String v = rs.next() ? rs.getString(1) : null;
                c.commit();
                return v;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 outcome 失败: " + refundId, e);
        }
    }

    /** 直接用超级用户把工单置为『归档』（模拟"另一个已完成收口的既有事实"）。 */
    private static void archiveDirectly(String tenantId, String refundId) {
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB,
                "\\set ON_ERROR_STOP on\n"
                        + "UPDATE refund SET outcome = '归档' WHERE refund_id = '"
                        + refundId + "'::uuid AND tenant_id = '" + tenantId + "'::uuid;\n");
    }

    private static String resetOutcomeSql() {
        return "\\set ON_ERROR_STOP on\n"
                + "UPDATE refund SET outcome = '继续' WHERE refund_id IN ('"
                + REFUND_A + "', '" + REFUND_B + "');\n";
    }

    // ------------------------------------------------------------------
    // 底数据灌入 / 清理（与邻类同法：主键前缀精确圈定，不用孤儿删除法）
    // ------------------------------------------------------------------

    private static void provisionSeeds() {
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, buildCleanupSql());
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_A, RlsGateSupport.SEED_A_ID, SFX_A));
        RlsGateSupport.runSql(RlsGateSupport.SUPER_USER, RlsGateSupport.superPassword(),
                RlsGateSupport.DB, seedSqlFor(TENANT_B, RlsGateSupport.SEED_B_ID, SFX_B));
    }

    private static String pkPrefix(String col) {
        return " (" + col + "::text LIKE '00000000-0000-0000-" + SFX_A + "-%'"
                + " OR " + col + "::text LIKE '00000000-0000-0000-" + SFX_B + "-%')";
    }

    /** 按【子 → 父】FK 序删除本类灌入的行（本类只建 region→store→staff→refund，无子表）。 */
    private static String buildCleanupSql() {
        StringBuilder sb = new StringBuilder("\\set ON_ERROR_STOP on\n");
        sb.append("DELETE FROM refund WHERE refund_id::text LIKE '00000000-0000-0000-")
          .append(SFX_A).append("-%' OR refund_id::text LIKE '00000000-0000-0000-")
          .append(SFX_B).append("-%';\n");
        for (String t : List.of("staff", "store", "region")) {
            sb.append("DELETE FROM ").append(t).append(" WHERE").append(pkPrefix(t + "_id")).append(";\n");
        }
        return sb.toString();
    }

    /** region→store→staff→refund 的最小 FK 依赖链；主键落在两个基础种子客户上。 */
    private static String seedSqlFor(String tenant, String customer, String sfx) {
        String p = "00000000-0000-0000-" + sfx + "-0000000000";
        return "\\set ON_ERROR_STOP on\n"
                + ins("region", "region_id, tenant_id, name",
                        q(p + "01") + ", " + q(tenant) + ", '区域C4'")
                + ins("store", "store_id, tenant_id, region_id, name, franchise_type",
                        q(p + "02") + ", " + q(tenant) + ", " + q(p + "01") + ", '门店C4', '直营'")
                + ins("staff", "staff_id, tenant_id, store_id, role",
                        q(p + "03") + ", " + q(tenant) + ", " + q(p + "02") + ", '经络师'")
                + ins("refund", "refund_id, tenant_id, customer_id, entry, refund_route, "
                        + "liable_store_id, reason_code, outcome",
                        q(p + "04") + ", " + q(tenant) + ", " + q(customer)
                        + ", 'A 门店代录', '效果类', " + q(p + "02") + ", '效果未达预期', '继续'");
    }

    private static String ins(String table, String cols, String values) {
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + values
                + ") ON CONFLICT DO NOTHING;\n";
    }

    private static String q(String v) {
        return "'" + v + "'";
    }
}