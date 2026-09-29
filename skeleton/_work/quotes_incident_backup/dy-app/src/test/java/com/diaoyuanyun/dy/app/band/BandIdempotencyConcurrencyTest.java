package com.diaoyuanyun.dy.app.band;

import com.diaoyuanyun.dy.app.band.domain.TelemetryRow;
import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.app.band.service.BandService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-2 · 幂等在【并发 / 多实例语义】下的真实行为</b>（真库 + 真并发线程）。
 *
 * <h2>这个类为什么必须存在（它和前两类不重复在哪）</h2>
 * {@link BandEndpointsE2ETest} 断言的是<b>串行</b>幂等（同一 batch_no 发两次 → 409），
 * 而 B-2 这条卡点问的是一个串行用例<b>结构性无法回答</b>的问题：
 * <pre>
 *   "两个请求【同时】到达时，还成立吗？"
 * </pre>
 * 串行用例永远走的是"第一次写成功、第二次查到已存在"这条路径；
 * 并发下的"两次都判不存在、随后一个撞唯一约束"这条路径，它碰不到。
 * 而<b>正是这条路径</b>在 B-2 修复前会让客户端收到 500。
 *
 * <h2>🛑 本类如何在没有真 Redis 的前提下验证"多实例语义"</h2>
 * B-2 的原始表述是"内存 Map ⇒ 多实例部署幂等失效"。要证伪它，
 * 有两种做法，本类<b>两种都做</b>：
 * <ol>
 *   <li><b>真并发</b>（{@code N} 个线程同时对同一幂等键发起写入）——
 *       这是"多实例"在<b>共享数据库</b>这一层的等价物：
 *       多个实例与多个线程，对库而言都是"并发的无协调写入者"。
 *       若幂等权威真在库层，则 N 个线程下必须<b>恰好一行</b>落库、且<b>无一个 500</b>。</li>
 *   <li><b>结构性断言</b>（反射）：断言 {@code BandService} 里<b>不再存在</b>
 *       任何进程内 {@code Map} 字段。这一条针对的是"内存 Map 作为防线"这个<b>形态</b>——
 *       它比"跑一次并发看结果"更本质，因为它不依赖某次运行是否恰好踩中竞态。
 *       （并发是概率性的：一个 100 线程的用例在单核 CI 上也可能全绿而漏洞仍在。
 *       故<b>必须</b>配一条确定性的形态断言。）</li>
 * </ol>
 *
 * <h2>🛑 本类的三条判据</h2>
 * <ol>
 *   <li><b>并发下恰好一行、零异常</b>：N 线程同键并发写入 ⇒ 库里 1 行，
 *       且没有任何线程收到异常（修复前会有 23505 冒到调用方）。</li>
 *   <li><b>幂等重放返回【真实存在】的 id</b>：第二次上报 E2 时，响应的
 *       {@code telemetry_id} 必须在库里真的查得到。
 *       这不是形式要求 —— 修复前它返回的是本次入参新生成的 UUID，库里<b>没有</b>那一行。</li>
 *   <li><b>两个分支的幂等键互不串扰</b>：日型键与游标型键（{@code sport_id}）
 *       在 creator 谓词上必须隔离。修复前的 {@code telemetryIdempotentHit}
 *       在日型分支<b>漏了 {@code sport_id IS NULL}</b> 谓词。</li>
 * </ol>
 *
 * <h2>为什么用真库（而不是 mock BandLedger）</h2>
 * 本类要断言的正是<b>唯一索引的原子性</b>与 {@code ON CONFLICT} 的行为 ——
 * 这两件事都由 PostgreSQL 实现，mock 掉仓储等于把这门课换成"我假设它会这样"。
 * 与 B-1 的 {@code BandTelemetryEncryptionTest} 同一取舍。
 */
@SpringBootTest
@DisplayName("B-2 · 手环域幂等的并发/多实例语义（真库 + 真并发）")
class BandIdempotencyConcurrencyTest {

    /** 本类独立租户，与 e2e…eeee / e2e…cc0c 零交集，避免与邻类互相污染计数。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000b2b2";

    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000a1";
    private static final String S_BAND = "e2e00000-0000-0000-0000-0000000000a2";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    @Autowired
    DataSource springDataSource;

    @Autowired
    BandService bandService;

    @Autowired
    BandLedger bandLedger;

    @BeforeEach
    void seedIfNeeded() {
        if (dataSource == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
        if (!seeded) {
            seedOnce();
            seeded = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (jdbc == null || dataSource == null) {
            return;
        }
        inTenant(() -> {
            jdbc.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM band WHERE tenant_id = ?::uuid AND band_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'",
                    TENANT);
            // 🛑 V11 起：密钥材料必须先于租户删（tenant_kek.tenant_id → tenant.id 有 FK）。
            //    本类会写 E2，故走加密链路 ⇒ 确有密钥行。
            jdbc.update("DELETE FROM subject_dek WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM tenant_kek WHERE tenant_id = ?::uuid", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'B2租户-幂等并发') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B2客户', 'CONSENTED') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, model, bound_at, status) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', 'model-y', CURRENT_DATE, 'active')"
                            + " ON CONFLICT DO NOTHING",
                    S_BAND, TENANT, S_CUSTOMER);
            return null;
        });
    }

    private static <T> T inTenant(Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    private int countTelemetry(String metric, LocalDate date, Integer hour) {
        Integer n = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM band_telemetry WHERE tenant_id = ?::uuid AND metric = ?"
                        + " AND date = ? AND hour IS NOT DISTINCT FROM ?",
                Integer.class, TENANT, metric, java.sql.Date.valueOf(date), hour));
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 判据 1：并发下恰好一行、零异常
    // ==================================================================

    @Nested
    @DisplayName("判据 1 · 并发写同一幂等键")
    class ConcurrentSameKey {

        /**
         * 🛑 <b>本类的核心用例</b>：N 个线程同时写<b>同一个</b> E2 幂等键。
         *
         * <p>修复前的行为（可复现）：两个线程都通过 {@code telemetryIdempotentHit} 的
         * EXISTS 检查（都返回 false），随后其中一个 INSERT 撞
         * {@code uq_bt_daily_idempotent} ⇒ {@code DataIntegrityViolationException} 冒到调用方。
         * 在真 HTTP 链路上那是一个 <b>500</b>。
         *
         * <p>修复后：{@code ON CONFLICT DO NOTHING} 使冲突线程拿到 affected=0 并走
         * "回查已存在行 id"的分支 ⇒ 零异常、库里 1 行。
         *
         * <p>🛑 本用例用真实线程而非 mock 并发，并且<b>用 CountDownLatch 让 N 个线程
         * 尽量同时起跑</b> —— 只是"循环调用 N 次"会退化成串行，测不到竞态。
         */
        @Test
        @DisplayName("32 线程同键并发写入 ⇒ 库里恰好 1 行，且无任何线程收到异常")
        void concurrent_writes_on_same_key_yield_exactly_one_row_and_no_failure() throws Exception {
            final int threads = 32;
            LocalDate date = LocalDate.of(2026, 8, 1);
            // 先清干净，避免与其他用例互扰
            inTenant(() -> {
                jdbc.update("DELETE FROM band_telemetry WHERE tenant_id = ?::uuid AND metric='steps'"
                        + " AND date = ?", TENANT, java.sql.Date.valueOf(date));
                return null;
            });

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGun = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            Set<String> returnedIds = ConcurrentHashMap.newKeySet();
            AtomicInteger okCount = new AtomicInteger();

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGun.await();   // 尽量同时起跑
                            BandService.TelemetryRequest req = new BandService.TelemetryRequest(
                                    S_BAND, S_CUSTOMER, "steps", date, null, null,
                                    new java.math.BigDecimal("8500"), null, null, null);
                            TelemetryRow row = bandService.upsertTelemetry(TENANT, req, "concurrency-test");
                            returnedIds.add(row.telemetryId().toString());
                            okCount.incrementAndGet();
                        } catch (Throwable t) {
                            // 🛑 关键：任何异常都是失败。修复前这里是 23505 ->
                            //    DataIntegrityViolationException（真链路上是 500）。
                            failures.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGun.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "并发写入未在 60s 内完成");
            } finally {
                pool.shutdownNow();
            }

            assertTrue(failures.isEmpty(),
                    "🛑 并发写入出现了 " + failures.size() + " 个异常 —— 幂等在并发下【未被原子承担】。"
                            + "B-2 修复前此处必然非空（唯一索引冲突冒到调用方，真链路 = 500）。"
                            + "首个异常: " + (failures.isEmpty() ? "" : failures.get(0)));

            assertEquals(threads, okCount.get(), "所有线程都应正常返回");

            int rows = countTelemetry("steps", date, null);
            assertEquals(1, rows,
                    "🛑 同一幂等键并发写入后库里应恰好 1 行，实际 " + rows + " 行 —— "
                            + "V3 的 uq_bt_daily_idempotent 未生效，或幂等键成员被改动");

            // 🛑 所有线程必须拿到【同一个】真实 id。若实现返回各自入参里那个新 UUID，
            //    这里会是 32 个不同的 id —— 而其中 31 个在库里不存在。
            assertEquals(1, returnedIds.size(),
                    "🛑 并发调用返回了 " + returnedIds.size() + " 个不同的 telemetry_id —— "
                            + "幂等重放必须返回【已存在那一行】的 id，而不是本次入参新生成的 id。"
                            + "实际 id 集合大小=" + returnedIds.size());
        }

        /**
         * 判据 1 的 E1 侧（{@code batch_no}）：并发上报同一批次 ⇒ 恰好 1 行。
         *
         * <p>与 E2 的区别是<b>对外语义</b>：E1 幂等命中要报 409（契约声明），
         * 故"零异常"在这里<b>不成立也不该成立</b> —— 预期是"1 次成功 + N-1 次 409"。
         * 本用例断言的正是这个分布：<b>成功的恰好 1 个，其余全是 409（而不是 500）</b>。
         */
        @Test
        @DisplayName("32 线程并发上报同一 batch_no ⇒ 恰好 1 次成功，其余全为 409（不得出现 500）")
        void concurrent_reports_on_same_batch_no_yield_one_success_and_rest_409() throws Exception {
            final int threads = 32;
            final String batchNo = UUID.randomUUID().toString();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGun = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger replay409 = new AtomicInteger();
            List<Throwable> unexpected = new CopyOnWriteArrayList<>();

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGun.await();
                            BandService.SyncBatchRequest req = new BandService.SyncBatchRequest(
                                    S_BAND, S_CUSTOMER, batchNo, "on_show_hot", "synced",
                                    Instant.now(), LocalDate.of(2026, 8, 1), null, null);
                            bandService.reportSyncBatch(TENANT, req, "concurrency-test");
                            success.incrementAndGet();
                        } catch (com.diaoyuanyun.dy.common.exception.BizException be) {
                            // BizException 只暴露 getCode()（int），无 getErrorCode()。
                            if (be.getCode() == com.diaoyuanyun.dy.common.result.ErrorCode
                                    .IDEMPOTENT_REPLAY.getCode()) {
                                replay409.incrementAndGet();
                            } else {
                                unexpected.add(be);
                            }
                        } catch (Throwable t) {
                            unexpected.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGun.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "并发上报未在 60s 内完成");
            } finally {
                pool.shutdownNow();
            }

            assertTrue(unexpected.isEmpty(),
                    "🛑 并发上报出现了非 409 的异常 —— E1 幂等在并发下未被库层唯一索引原子承担。"
                            + "修复前可能出现 23505 冒到调用方（真链路 = 500）。"
                            + "首个异常: " + (unexpected.isEmpty() ? "" : unexpected.get(0)));
            assertEquals(1, success.get(),
                    "🛑 同一 batch_no 并发上报应恰好 1 次成功，实际 " + success.get() + " 次 —— "
                            + "说明库层 uq_sync_log_batch_no 未拦住并发双写");
            assertEquals(threads - 1, replay409.get(),
                    "其余 " + (threads - 1) + " 个线程应全部拿到 409 幂等重放，实际 " + replay409.get());

            Integer rows = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM band_sync_log WHERE tenant_id = ?::uuid AND batch_no = ?::uuid",
                    Integer.class, TENANT, batchNo));
            assertEquals(1, rows == null ? 0 : rows,
                    "🛑 同一 batch_no 在库里应恰好 1 行，实际 " + rows + " 行");
        }
    }

    // ==================================================================
    // 判据 2：幂等重放返回真实存在的 id
    // ==================================================================

    @Nested
    @DisplayName("判据 2 · 重放返回的 id 必须真实存在")
    class ReplayReturnsRealId {

        /**
         * 🛑 修复前本条必然红：{@code upsertTelemetry} 直接返回入参构造的 {@code row}，
         * 其 {@code telemetryId} 是本次请求新 {@code randomUUID()} 出来的，
         * <b>而幂等命中时那一行根本没写进库</b>。E2 响应体里就有 {@code telemetry_id}
         * （见 {@code BandController.upsertTelemetry}），故客户端会拿到一个幽灵 id。
         */
        @Test
        @DisplayName("第二次上报同一幂等键 ⇒ 返回的 telemetry_id 在库中确实存在（且与首次相同）")
        void replay_returns_an_id_that_actually_exists_in_the_database() {
            LocalDate date = LocalDate.of(2026, 8, 2);
            BandService.TelemetryRequest req = new BandService.TelemetryRequest(
                    S_BAND, S_CUSTOMER, "hr", date, null, null,
                    new java.math.BigDecimal("77"), null, null, null);

            TelemetryRow first = bandService.upsertTelemetry(TENANT, req, "replay-test");
            // 第二次：同一幂等键（同一 device/metric/date/hour/minute 分支）
            TelemetryRow second = bandService.upsertTelemetry(TENANT, req, "replay-test");

            assertEquals(first.telemetryId(), second.telemetryId(),
                    "🛑 幂等重放返回了不同的 telemetry_id —— 客户端会以为这是另一次受理。"
                            + "first=" + first.telemetryId() + ", second=" + second.telemetryId());

            // 🛑 权威断言：这个 id 必须在库里真的查得到。
            //    修复前 second.telemetryId() 是本次新生成的 UUID，本断言必然失败。
            Integer found = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM band_telemetry WHERE tenant_id = ?::uuid AND telemetry_id = ?::uuid",
                    Integer.class, TENANT, second.telemetryId().toString()));
            assertEquals(1, found == null ? 0 : found,
                    "🛑 E2 幂等重放返回的 telemetry_id 在库里【不存在】—— "
                            + "这是一个幽灵 id（B-2 修复前的确定行为）。id=" + second.telemetryId());

            assertEquals(1, countTelemetry("hr", date, null),
                    "幂等重放不得写第二行");
        }
    }

    // ==================================================================
    // 判据 3：日型 / 游标型两分支不串扰
    // ==================================================================

    @Nested
    @DisplayName("判据 3 · 双分支幂等键互不串扰 + 游标型可达性（缺口哨兵）")
    class BranchIsolation {

        /**
         * 🛑 修复前 {@code telemetryIdempotentHit} 的日型分支漏了 {@code sport_id IS NULL}，
         * 于是"一条 {@code sport_id} 非空的行"与"一条日型行"在
         * device/metric/date/hour/minute 恰好相同时，
         * 前者会被判成后者的幂等命中 —— <b>两个分支的幂等键被混同</b>。
         *
         * <h2>🛑 为什么用 SQL 直插而不是走 API 造数据</h2>
         * 初版本用例试图用 {@code upsertTelemetry(metric="sport", currentSportId=...)}
         * 造一条游标型行，结果<b>造不出来</b>：{@code "sport"} 不在
         * {@code TelemetryRow.METRICS} 的 13 值内，领域构造器直接拒绝。
         * 那次失败暴露了一个<b>独立于本用例目的</b>的真实缺口（见下面
         * {@link #cursor_branch_is_unreachable_through_the_service_layer}），
         * 但也说明：要测<b>仓储谓词本身</b>，就不能经由服务层 ——
         * 否则测的是"服务层能否造出游标型"，而不是"谓词写对了没有"。
         * 故此处直接 INSERT 一行 {@code sport_id} 非空的合法行
         * （{@code metric='workout'} 在 13 值内，{@code sport_id} 让它走游标型索引）。
         */
        @Test
        @DisplayName("游标型行不得被日型键误判为命中（日型谓词必须含 sport_id IS NULL）")
        void sport_cursor_row_is_not_falsely_matched_by_the_daily_key() {
            LocalDate date = LocalDate.of(2026, 8, 3);
            UUID cursorId = UUID.randomUUID();
            final String cursorSportId = "SPORT-CURSOR-1";

            // ① 直插一条游标型行：metric 合法（13 值内）+ sport_id 非空 ⇒ 走 uq_bt_sport_cursor
            inTenant(() -> {
                jdbc.update("INSERT INTO band_telemetry (telemetry_id, tenant_id, customer_id, device_id,"
                                + " metric, date, hour, minute, sport_id, data_source, sync_state, synced_at) "
                                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'workout', ?, 10, 30, ?,"
                                + " '手环', 'synced', now())",
                        cursorId.toString(), TENANT, S_CUSTOMER, S_BAND,
                        java.sql.Date.valueOf(date), cursorSportId);
                return null;
            });
            assertEquals(1, inTenant(() -> jdbc.queryForObject(
                            "SELECT count(*) FROM band_telemetry WHERE tenant_id = ?::uuid"
                                    + " AND sport_id IS NOT NULL", Integer.class, TENANT)),
                    "前提：游标型行应已落库（否则本用例在断言空集）");

            // ② 用【日型行的形状】去查（sport_id 为 null，其余键成员与游标型行逐字相同）
            //    ⇒ 必须查不到。修复前的日型分支缺 `sport_id IS NULL`，这里会误命中。
            TelemetryRow dailyShaped = new TelemetryRow(
                    UUID.randomUUID(), UUID.fromString(S_CUSTOMER), UUID.fromString(S_BAND),
                    "workout", date, 10, 30,
                    new java.math.BigDecimal("30"), null, true,
                    "手环", null, "synced", Instant.now(), null, null, null, "branch-test");

            UUID hit = bandLedger.findTelemetryId(TENANT, dailyShaped);
            assertTrue(hit == null,
                    "🛑 日型键把一条游标型行误判为命中（hit=" + hit + "）—— "
                            + "findTelemetryId 的日型分支漏了 `sport_id IS NULL` 谓词，"
                            + "两个分支的幂等键已混同（契约 §4.3 明令运动数据不得复用按日型幂等键）");

            // ③ 反向：游标型自己的键必须能查到那一行 —— 证明 ② 不是"谓词一概查不到"的假绿
            TelemetryRow cursorShaped = new TelemetryRow(
                    UUID.randomUUID(), UUID.fromString(S_CUSTOMER), UUID.fromString(S_BAND),
                    "workout", date, 10, 30,
                    new java.math.BigDecimal("30"), null, true,
                    "手环", null, "synced", Instant.now(), null, null, cursorSportId, "branch-test");
            UUID cursorHit = bandLedger.findTelemetryId(TENANT, cursorShaped);
            assertNotNull(cursorHit,
                    "🛑 游标型用自己的键查不到刚插入的那一行 —— 谓词写反了，"
                            + "或游标型分支的查询条件与 uq_bt_sport_cursor 不对应");
            assertEquals(cursorId, cursorHit, "游标型查到的应是它自己那一行");
        }

        /**
         * 🛑🛑 <b>缺口哨兵（2026-09-26 · B-2 勘察发现，尚未修复）</b>
         *
         * <h2>缺口：游标型 sport 分支经服务层<b>不可达</b></h2>
         * {@code BandService.upsertTelemetry} 的分支判定是：
         * <pre>
         *   boolean isSport = "sport".equals(req.metric());
         *   Integer sportId = isSport ? 0 : null;
         *   ...
         *   row.sportId()  ← sportId == null ? null : req.currentSportId()
         * </pre>
         * 而 {@code req.metric()} 必须通过 {@code TelemetryRow} 构造器的 13 值校验，
         * 那 13 值里<b>没有</b> {@code "sport"}（V3 的 CHECK 同样没有）。
         * ⇒ {@code isSport} <b>恒为 false</b>，{@code sportId} 恒为 null，
         * 该三元表达式的 else 分支<b>永不可达</b>（死代码）。
         *
         * <h2>为什么这算缺口而不是"刻意不做"</h2>
         * 契约 §4.3 与数据字典 §2.17 都把游标型列为键<b>双分支</b>之一
         * （「按日型 12 条 + 游标型 1 条」，自证"12+1=13"）。
         * V3 迁移选了方案 A（长表 metric 化），把原设计里独立表
         * {@code band_sport_history} 的 {@code current_sport_id} 并进来做成了
         * {@code band_telemetry.sport_id} 列，<b>但没有为游标型行指定一个 metric 取值</b>
         * —— 于是：{@code uq_bt_sport_cursor} 这个唯一索引从未生效过，
         * 而它的部分条件 {@code WHERE sport_id IS NOT NULL} 永远为假。
         * 真库实测（本次勘察）：{@code SELECT count(*) FROM band_telemetry WHERE sport_id IS NOT NULL}
         * = <b>0</b>。
         *
         * <h2>为什么不擅自改</h2>
         * 修它需要一次裁定，因为有三条互斥的走法，各有代价：
         * <ol>
         *   <li><b>把 {@code 'sport'} 加入 metric 枚举</b>（13 → 14 值）：
         *       与契约 {@code BandTelemetryRequest.metric} 的 13 值 enum <b>直接冲突</b>，
         *       且会连带动 V3 的 CHECK、{@code TelemetrySensitivity} 的两侧清单、
         *       以及 B-1 刚建立的三方交叉门禁（{@code TelemetrySensitivityMigrationGateTest}）。</li>
         *   <li><b>用现有 13 值里的某个 metric 承载游标型</b>（如 {@code workout} +
         *       {@code sport_id} 非空）：不改枚举，但需要定义"哪个 metric 走游标型"
         *       的映射规则 —— 这是<b>新口径</b>，须业务确认。</li>
         *   <li><b>恢复独立表 {@code band_sport_history}</b>（回到数据字典 §2.17 原设计）：
         *       与"方案 A 长表已定案"的裁定相反，且要新迁移。</li>
         * </ol>
         * 三者都会改变<b>已冻结的对外契约或已定案的数据模型</b>，故按纪律
         * <b>登记待裁、不擅自改</b>（同 A-5/B-2 一贯处置）。
         *
         * <h2>本哨兵的作用</h2>
         * 它断言的是<b>当前事实</b>（枚举不含 {@code "sport"}、真库无游标型行）。
         * 一旦有人修复（无论走上述哪条路），<b>本用例会变红</b> —— 那是期望的：
         * 它强制修复者回来更新这条登记，而不是让缺口被静默改掉。
         */
        @Test
        @DisplayName("🛑 缺口哨兵：游标型分支经服务层不可达（已登记待裁，修复后本用例应变红并更新）")
        void cursor_branch_is_unreachable_through_the_service_layer() {
            // ① 事实：13 值枚举里没有 "sport" —— 这就是 isSport 的依据
            assertFalse(TelemetryRow.METRICS.contains("sport"),
                    "🛑 metric 枚举里出现了 \"sport\" —— 说明游标型缺口已被修复（走"
                            + "『把 sport 加入枚举』那条路）。请更新本条登记，并同步："
                            + "V3 的 CHECK、TelemetrySensitivity 两侧清单、"
                            + "TelemetrySensitivityMigrationGateTest 的三方交叉断言、"
                            + "契约 BandTelemetryRequest.metric enum。");

            // ② 事实：故 isSport 恒 false ⇒ sportId 恒 null
            //    用"构造器拒绝 sport"来证明这条链路（而不是读源码）——
            //    断言的是可执行行为，不是文本。
            com.diaoyuanyun.dy.common.exception.BizException rejected =
                    assertThrows(com.diaoyuanyun.dy.common.exception.BizException.class,
                            () -> new TelemetryRow(
                                    UUID.randomUUID(), UUID.fromString(S_CUSTOMER), UUID.fromString(S_BAND),
                                    "sport", LocalDate.now(), null, null,
                                    new java.math.BigDecimal("1"), null, true,
                                    "手环", null, "synced", Instant.now(), null, null, "SP", "gap-sentinel"),
                            "🛑 metric=\"sport\" 被领域构造器接受了 —— 若真是如此，"
                                    + "则 isSport 可能真的可达，本哨兵的前提失效，请重新勘察。");
            assertEquals(com.diaoyuanyun.dy.common.result.ErrorCode.VALIDATION_FAILED.getCode(),
                    rejected.getCode(),
                    "拒绝理由应是 VALIDATION_FAILED（metric 不在 13 值内），而不是别的失败");

            // ③ 事实：走服务层写入时，即使客户端明确传了 current_sport_id，
            //    落库的 sport_id 仍然是 NULL —— 这就是"else 分支永不可达"的可执行证据。
            //    🛑 用独立日期，避免与本类其他用例的幂等键相撞。
            LocalDate probeDate = LocalDate.of(2026, 8, 20);
            BandService.TelemetryRequest withSportCursor = new BandService.TelemetryRequest(
                    S_BAND, S_CUSTOMER, "workout", probeDate, 14, 0,
                    new java.math.BigDecimal("25"), null, null, "SPORT-ID-EXPLICITLY-PASSED");
            TelemetryRow persisted = bandService.upsertTelemetry(TENANT, withSportCursor, "gap-sentinel");

            assertTrue(persisted.sportId() == null,
                    "🛑 服务层把 current_sport_id 落进了 sport_id（=" + persisted.sportId() + "）—— "
                            + "说明游标型分支已可达，缺口【已被修复】。"
                            + "请更新本条登记、并同步 uq_bt_sport_cursor 的相关测试。");

            String rawSportId = inTenant(() -> jdbc.queryForObject(
                    "SELECT sport_id FROM band_telemetry WHERE tenant_id = ?::uuid AND telemetry_id = ?::uuid",
                    String.class, TENANT, persisted.telemetryId().toString()));
            assertTrue(rawSportId == null,
                    "🛑 库里的 sport_id 有值（=" + rawSportId + "）—— 游标型分支已可达，缺口已修复");

            // ④ 并证明 uq_bt_sport_cursor 这个索引当前【在选择上】不可能被命中：
            //    真库里不存在 sport_id 非空的行（除了另一用例 SQL 直插的那条探针）。
            //    🛑 不用精确计数（那会依赖用例执行顺序而 flaky），
            //       改为断言"服务层从未产生过游标型行"——用上面③已经有了。
            //       此处只额外确认索引本身仍然存在（防止有人误删它）。
            Integer idxCount = inTenant(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = 'band_telemetry'"
                            + " AND indexname = 'uq_bt_sport_cursor'", Integer.class));
            assertEquals(1, idxCount == null ? 0 : idxCount,
                    "uq_bt_sport_cursor 索引应仍然存在 —— 缺口是『分支不可达』，"
                            + "而不是『索引没建』；删掉索引不会修复任何东西，只会掩盖它");
        }
    }

    // ==================================================================
    // 结构性断言：内存 Map 作为防线必须【不存在】
    // ==================================================================

    @Nested
    @DisplayName("结构性 · 不得存在进程内 Map 充当幂等防线")
    class NoInProcessMap {

        /**
         * 🛑 这是本类<b>唯一一条不依赖并发时序</b>的断言，因此也是最可靠的一条。
         *
         * <p>并发用例是概率性的：一个 N 线程用例在单核 CI、或某个 JIT/调度巧合下
         * 可能全绿而漏洞仍在。故必须有一条<b>确定性</b>断言把"形态"钉住：
         * {@code BandService} 不得声明任何 {@code java.util.Map} 类型的实例字段。
         *
         * <p>为什么钉在 {@code BandService}（而不是泛泛地"全仓不得有 Map"）：
         * 别处可能有正当的 Map 用途（如常量查找表）。本断言只针对
         * <b>承载幂等状态的那个类</b> —— 它一旦长出进程内 Map，B-2 就会复发。
         */
        @Test
        @DisplayName("BandService 不得有任何 Map 类型的实例字段（内存防线不得复发）")
        void band_service_has_no_in_process_map_field() {
            List<String> offenders = new ArrayList<>();
            for (java.lang.reflect.Field f : BandService.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue; // 静态常量查找表不属于"实例状态"
                }
                if (java.util.Map.class.isAssignableFrom(f.getType())) {
                    offenders.add(f.getName() + " : " + f.getType().getName());
                }
            }
            assertTrue(offenders.isEmpty(),
                    "🛑 BandService 出现了进程内 Map 字段 " + offenders + " —— "
                            + "B-2 的缺陷形态正在复发。幂等的权威【必须】在库层唯一索引，"
                            + "任何进程内 Map 都会退化成单实例快路径，"
                            + "并带来'两份真相源 + 语义可漂移'（见 BandService 类头）。");
        }

        /**
         * 🛑 反向断言：确认上面那条"没有 Map"不是因为<b>整个类没有字段</b>而假绿
         * （那种情况下 both 断言都会通过，但它什么都没证明）。
         */
        @Test
        @DisplayName("反证：BandService 确实有 ledger 字段（使上一条不是空集假绿）")
        void band_service_does_have_the_dependency_field_so_the_previous_check_is_not_vacuous() {
            boolean hasLedger = false;
            for (java.lang.reflect.Field f : BandService.class.getDeclaredFields()) {
                if (BandLedger.class.isAssignableFrom(f.getType())) {
                    hasLedger = true;
                }
            }
            assertTrue(hasLedger,
                    "BandService 应有 BandLedger 依赖字段 —— 若连它都没有，"
                            + "上一条'无 Map 字段'就是在对空集断言，属假绿");
        }
    }
}