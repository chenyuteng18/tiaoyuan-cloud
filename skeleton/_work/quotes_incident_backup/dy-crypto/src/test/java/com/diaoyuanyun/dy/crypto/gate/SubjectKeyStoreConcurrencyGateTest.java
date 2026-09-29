package com.diaoyuanyun.dy.crypto.gate;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmId;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.key.TenantKekProvider;
import com.diaoyuanyun.dy.crypto.key.WrappedDek;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code InMemorySubjectKeyStore} 的<b>并发原子性门禁</b> —— 补上本模块最薄弱的证据环。
 *
 * <h1>为什么必须补这一组测试（这是一个已发生的证据缺口，不是假想）</h1>
 * 在本组测试加入之前，{@code dy-crypto/src/test} 下<b>零并发代码</b>：
 * grep {@code Thread} / {@code Executor} / {@code CountDownLatch} 命中数为 0，
 * 且 {@code rotateDek} 在整个模块自测里<b>从未被调用过</b>。
 * 也就是说 —— 并发正确性当时<b>只</b>由模块外的独立对抗探针
 * （{@code verification/crypto/}，刻意不在任何模块 {@code src/test} 下）来证明。
 * 于是 {@code mvn -pl dy-crypto test} 的 {@code Tests run: 23} 全绿，
 * <b>并不构成</b>"并发是对的"这一结论。
 *
 * <h1>为什么本组测试要按"门控确定性"写，而不是"12 线程一起起跑"</h1>
 * 概率型写法在正确实现上会侥幸全绿 —— 本项目已实测过这个教训：
 * 原对抗探针用"12 线程齐跑"，连跑 6 次有 2 次在<b>坏实现</b>上也全绿。
 * 门禁里"可能假绿"比"恒红"更危险，因为它会在某次 CI 上放行一个仍然坏的实现。
 * 因此本组测试一律使用<b>门控 {@link GatedKekProvider}</b>：
 * 把 {@code currentKek()} 卡住，让"线程已越过'判定需要创建'、但尚未写回存储"
 * 这一竞态窗口被<b>确定性地</b>撑开，再统一放行。
 *
 * <h1>判据落在哪里（决定了红态是否稳定）</h1>
 * 判据一律落在<b>最终可观察状态</b>上（线程各自拿到的 DEK 是否同一把、
 * 版本号是否一一对应、销毁后是否还有材料），<b>不</b>落在"是否所有线程都到达闸门"
 * 上 —— 后者在正确实现下本来就不会全部到达（键锁把其余线程挡在外面），
 * 把它当判据会造成"好实现反而变红"的假红。
 *
 * <h1>本组测试是新写的、<b>没有</b>复制模块外的对抗探针</h1>
 * {@code verification/crypto/AdversarialVerificationProbe.java} 是<b>独立验证者</b>的产物，
 * 刻意不在任何模块 {@code src/test} 下，目的是让实现者的用例与验证者的探针
 * <b>不混进同一个 {@code Tests run} 计数</b>（混进去等于让验证结论自证）。
 * 本组测试是实现者视角的等价并发门禁，写法与断言表述均独立成文，
 * 只借用其"门控 provider"这一手法。
 */
class SubjectKeyStoreConcurrencyGateTest {

    private static final SubjectRef C = SubjectRef.customer(CryptoTestHarness.TENANT_A, "conc-gate-1");

    /** ② 的读压力窗读者数。 */
    private static final int MAX_READERS = 8;

    /**
     * ② 在并发轮换前先堆出的历史版本深度。
     *
     * <p>取 32：读者按版本号取回时要遍历整个版本列表才能命中目标元素，
     * 有一定长度才能让"历史无缺失"这条判据覆盖到真正的多版本情形。
     *
     * <p>取舍说明：曾把该值提到 256、并把读者热循环压成纯遍历（1200ms 内 8 读者
     * 跑出 11,373,576 圈），目的是让 CME 判据在"就地 add"的坏实现上稳定复现 ——
     * <b>实测未能达成（仍为 0 次）</b>。既然加长版本链买不到 CME 的可复现性，
     * 就不再为它付这个编译/运行开销，回到 32。
     */
    private static final int HISTORY_DEPTH = 32;

    /**
     * ② 的读压力窗时长（毫秒）。
     * 取 600ms：足够让 8 个读者跑出百万量级的圈数，又让本测试不显著拖慢模块。
     */
    private static final int READ_WINDOW_MILLIS = 600;

    /**
     * 等待"线程进入创建路径"的上限（毫秒）。
     *
     * <p>取值逻辑：坏实现（读—判—写在锁外）下 N 个线程会在<b>毫秒级</b>全部到达闸门，
     * 1.5 秒已是百倍余量；而正确实现（键锁串行化）下<b>后面那些线程根本到不了</b>，
     * 于是这个 {@code await} 每次都会耗满整个上限 —— 也就是说，在正确实现上
     * 这个数字直接就是本测试的固定开销。故从最初的 3 秒收到 1.5 秒：
     * 判据本来就不落在"是否全部到达"上（见类注释），这个等待只是给坏实现留足余地。
     */
    private static final long GATE_WAIT_MILLIS = 1500;

    // ==================================================================
    // ① 并发 getOrCreateDek 同一主体
    // ==================================================================

    @Test
    @DisplayName("并发① 12 线程并发 getOrCreateDek 同一主体 → 只有 1 把 DEK，且按版本号 1 能取回同一把")
    void concurrent_get_or_create_same_subject_yields_single_dek() throws Exception {
        int n = 12;
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        CountDownLatch allInsideCreate = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger gateHits = new AtomicInteger();

        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            // 门控点：createVersion() 生成密钥材料时必经 currentKek()。
            // 越过此点之后才会把 DEK 写回 bySubject —— 这正是 P0 的竞态窗口。
            gateHits.incrementAndGet();
            allInsideCreate.countDown();
            await(release, 15);
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> keyStore.getOrCreateDek(C).rawDek()));
            }

            // 等待上限 1.5 秒：坏实现（锁外读—判—写）下 12 个线程会在毫秒级全部到达闸门；
            // 正确实现（`bySubject.compute` 单键串行化）下只有 1 个线程能走到 currentKek，
            // 其余被键锁挡住 —— 此时"未全部到达"是【预期】，不是失败。
            // 该布尔量仅作观察值打印，不参与判据（理由见类注释与 GATE_WAIT_MILLIS）。
            boolean allReached = allInsideCreate.await(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            release.countDown();

            Set<String> distinctDeks = new LinkedHashSet<>();
            int failed = 0;
            for (Future<byte[]> f : futures) {
                try {
                    distinctDeks.add(b64(f.get(30, TimeUnit.SECONDS)));
                } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                    failed++;
                }
            }

            // 关键判据：真正决定历史密文能否解开的是【按版本号取回】这条路径 ——
            // 只有"所有线程拿到的 DEK"与"按 v1 还原出的 DEK"是同一把，才算真正正确。
            String viaVersion;
            boolean viaVersionOk;
            try {
                byte[] viaV1 = keyStore.unwrap(keyStore.wrappedOf(C, 1).orElseThrow());
                viaVersion = b64(viaV1);
                viaVersionOk = distinctDeks.size() == 1 && distinctDeks.contains(viaVersion);
            } catch (RuntimeException e) {
                viaVersion = e.getClass().getSimpleName();
                viaVersionOk = false;
            }

            int liveKeys = keyStore.liveKeyCount();

            report("并发①",
                    "线程数=" + n
                            + "; 全部线程都进入创建路径=" + allReached + "(观察值，非判据)"
                            + "; 闸门被命中次数=" + gateHits.get()
                            + "; 【不同 DEK 数=" + distinctDeks.size() + "】(1=正确；>1 ⇒ 同一主体被生成多把 DEK)"
                            + "; 取用失败线程=" + failed
                            + "; wrappedOf(c,v1) 可还原且等于所取 DEK=" + viaVersionOk
                            + "; liveKeyCount=" + liveKeys);

            assertEquals(1, distinctDeks.size(),
                    "同一主体并发首取产生了 " + distinctDeks.size() + " 把不同的 DEK —— "
                            + "per-subject 幂等性在并发下不成立。后果：各线程只用得到自己那把，"
                            + "别人写入的密文按版本号只取得到第一条 v1，永久不可解。");
            assertEquals(0, failed, "有线程未能取到 DEK（异常或超时）");
            assertTrue(viaVersionOk,
                    "按版本号 1 取回并还原出的 DEK 与调用方拿到的不是同一把（还原值=" + viaVersion
                            + "）—— 存储里第一条 v1 与调用方实际使用的那把脱钩");
            assertEquals(1, liveKeys, "同一主体的活跃密钥条目应恰为 1 条");
            assertTrue(gateHits.get() >= 1,
                    "闸门从未被命中 —— 说明门控点没接上创建路径，本测试没有真正压到竞态窗口"
                            + "（这样的'绿'是恒真的假通过）");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // ② 并发 rotateDek 同一主体
    // ==================================================================

    @Test
    @DisplayName("并发② 8 线程并发 rotateDek 同一主体 → 版本号两两不同且与 DEK 一一对应，历史无缺失，无 CME")
    void concurrent_rotate_same_subject_yields_distinct_versions() throws Exception {
        int n = 8;
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        CountDownLatch allInsideCreate = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger gateHits = new AtomicInteger();
        AtomicBoolean armed = new AtomicBoolean(false);

        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (!armed.get()) {
                return; // 造 v1 阶段不设卡
            }
            gateHits.incrementAndGet();
            allInsideCreate.countDown();
            await(release, 15);
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        // 先造出 v1..vHISTORY_DEPTH，使被测路径是"轮换"而不是"首次创建"（两条路径的竞态窗口不同），
        // 同时让"历史版本无缺失"这条判据覆盖到真正的多版本情形：并发轮换产出的
        // 版本号应是 HISTORY_DEPTH+1 .. HISTORY_DEPTH+n，前面 1..HISTORY_DEPTH 必须一条不少。
        for (int i = 0; i < HISTORY_DEPTH; i++) {
            keyStore.rotateDek(C);
        }
        armed.set(true);

        ExecutorService pool = Executors.newFixedThreadPool(n + MAX_READERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stormDone = new AtomicBoolean(false);
        AtomicInteger cmeCount = new AtomicInteger();
        AtomicInteger readerLoops = new AtomicInteger();
        try {
            // ==========================================================
            // 阶段 A：8 线程并发轮换（门控确定性 —— 本测试的判据①从这里来）
            // ==========================================================
            List<Future<int[]>> writers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                writers.add(pool.submit(() -> {
                    start.await();
                    var dek = keyStore.rotateDek(C);
                    return new int[]{dek.version(), dek.rawDek().length};
                }));
            }
            start.countDown();
            boolean allReached = allInsideCreate.await(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);

            // ==========================================================
            // 阶段 B：读压力窗（门控把 8 个写者全部钉在 currentKek 上）
            //
            // ⚠️ 这一段的必要性来自一次实测教训（必须如实记录）：
            // 最初把读者与写者混在同一个窗口 —— 用 allInsideCreate.await(等待上限) 当观察期。
            // 结果观察窗口在【坏实现】下反而最短：坏实现 8 个线程毫秒级就全部到达闸门，
            // 读者只跑了 6~15 圈；而【好实现】下写者被键锁串行化、逐个卡在闸门上，
            // 观察期耗满整个等待上限，读者跑了数十万圈。也就是说 ——
            // 该写法把"最需要观察的坏实现"给了最短的观察期，判据被系统性削弱。
            //
            // 现在改成：写者全部停在闸门上（一个都不放行），读者先跑满固定的读压力窗，
            // 再放行写者。这样三种实现（好 / 变体 I / 变体 M）拿到的观察期完全一致。
            // ==========================================================
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < MAX_READERS; i++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    while (!stormDone.get()) {
                        try {
                            // 热循环只做"按版本号取回"这一步（内部会遍历版本列表命中目标元素）——
                            // 不在这里做 unwrap：一次真实 AES-GCM 解密（≈2µs）会把循环周期
                            // 拉长两个数量级，而遍历只占其中很小一部分，读者几乎从不停在
                            // 遍历窗口内。压成纯遍历可让该占空比接近 1。
                            keyStore.wrappedOf(C, HISTORY_DEPTH);
                            // 每隔 1024 圈补一次真实解包，保留对解密路径的覆盖
                            // （不是为了抓 CME，只是别让读者完全不碰密钥材料路径）。
                            if ((readerLoops.get() & 0x3FF) == 0) {
                                keyStore.wrappedOf(C, HISTORY_DEPTH).ifPresent(keyStore::unwrap);
                            }
                            readerLoops.incrementAndGet();
                        } catch (java.util.ConcurrentModificationException cme) {
                            cmeCount.incrementAndGet();
                        } catch (RuntimeException ignored) {
                            // 版本尚未出现的"不存在"不算 CME
                        }
                    }
                    return null;
                }));
            }

            // 读压力窗：三种实现（好 / 变体 I / 变体 M）拿到的时长完全一致。
            Thread.sleep(READ_WINDOW_MILLIS);

            // 放行写者 → 它们开始 append（坏实现下就地 add，读者可能在此刻抓到 CME）
            release.countDown();

            // 版本号 → 该版本被上报了几把 DEK（坏实现下同一个版本号会被算出多次）
            Map<Integer, Integer> writersPerVersion = new ConcurrentHashMap<>();
            for (Future<int[]> f : writers) {
                int v = f.get(30, TimeUnit.SECONDS)[0];
                writersPerVersion.merge(v, 1, Integer::sum);
            }
            stormDone.set(true);
            for (Future<?> r : readers) {
                r.get(30, TimeUnit.SECONDS);
            }

            // 判据 ①：版本号两两不同（每个版本号恰好对应 1 个写者）
            Set<Integer> versionSet = new java.util.TreeSet<>(writersPerVersion.keySet());
            boolean versionsDistinct = versionSet.size() == n
                    && writersPerVersion.values().stream().allMatch(c -> c == 1);

            // 判据 ②：每个出现过的版本号都能按版本号取回、解包，且各版本 DEK 互不相同
            List<String> byVersionProblems = new ArrayList<>();
            Set<String> distinctDeks = new LinkedHashSet<>();
            for (int v : versionSet) {
                try {
                    WrappedDek w = keyStore.wrappedOf(C, v).orElse(null);
                    if (w == null) {
                        byVersionProblems.add("v" + v + "取不到包裹材料");
                        continue;
                    }
                    distinctDeks.add(b64(keyStore.unwrap(w)));
                } catch (RuntimeException ex) {
                    byVersionProblems.add("v" + v + "→" + ex.getClass().getSimpleName());
                }
            }
            boolean oneToOne = distinctDeks.size() == versionSet.size();

            // 判据 ③：历史版本 1..maxV 无缺失（轮换不得覆盖/丢弃历史密钥）
            int maxV = versionSet.stream().mapToInt(Integer::intValue).max().orElse(1);
            List<Integer> missingHistory = new ArrayList<>();
            for (int v = 1; v <= maxV; v++) {
                if (keyStore.wrappedOf(C, v).isEmpty()) {
                    missingHistory.add(v);
                }
            }

            report("并发②",
                    "线程数=" + n
                            + "; 全部线程都进入创建路径=" + allReached + "(观察值，非判据)"
                            + "; 闸门被命中次数=" + gateHits.get()
                            + "; 【版本号集合=" + versionSet + "】(大小 " + versionSet.size()
                            + "，应为 " + n + ")"
                            + "; 一一对应=" + versionsDistinct
                            + "; 不同 DEK 数=" + distinctDeks.size()
                            + "; 按版本号取回问题=" + (byVersionProblems.isEmpty() ? "无" : byVersionProblems)
                            + "; 缺失历史版本=" + (missingHistory.isEmpty() ? "无" : missingHistory)
                            + "; 【CME 次数=" + cmeCount.get() + "】"
                            + "; 读压力窗=" + READ_WINDOW_MILLIS + "ms 内 " + MAX_READERS + " 读者共 "
                            + readerLoops.get() + " 圈");

            assertTrue(versionsDistinct,
                    "并发轮换后版本号不两两不同（版本号集合=" + versionSet
                            + "，每个版本号的写者数=" + writersPerVersion + "）—— "
                            + "同一版本号对应多把 DEK，解密按版本号只取第一条，其余线程写入的密文永久不可解。"
                            + "这是本测试【确定性】的主判据：坏实现下 8 个线程都被门控钉在判定之后、"
                            + "写回之前，必然算出同一个版本号。");
            assertTrue(byVersionProblems.isEmpty(),
                    "按版本号取回/解包失败: " + byVersionProblems);
            assertTrue(oneToOne, "版本号与 DEK 不是一一对应（版本数=" + versionSet.size()
                    + "，不同 DEK 数=" + distinctDeks.size() + "）");
            assertTrue(missingHistory.isEmpty(),
                    "历史版本缺失 " + missingHistory + " —— 轮换把历史密钥弄丢了，历史密文将不可解");
            // ⚠️ 本判据的定位（必须写明，否则会被读成"有牙齿的确定性门禁"）：
            //
            // 方向一（成立、且正是本例的要求）：绿态下 cmeCount 必须为 0。
            //   这一侧是确定性的 —— 值恒为不可变 List、写入一律整体替换时，
            //   读者遍历到的快照不会再被改动，**不可能**抛 CME。
            //
            // 方向二（**不成立**，如实记录）：它**不能**可靠地在坏实现上变红。
            //   实测（reverse/stability.sh mut 15）：在"就地 add、不整体替换"的注入变体
            //   （reverse/inject_mutating_list.py）上连跑 15 次，CME 读数 **15 次全为 0**；
            //   同一份 class 在"堆到 256 条版本链 + 热循环压成纯遍历"的加强版下
            //   也测过一轮（1200ms 内 8 读者跑出 11,373,576 圈），CME 仍为 0。
            //   原因是写者 get→add 的间隔在纳秒级，读者撞上它的概率极低。
            //   这个窗口无法用门控撑开（它在实现内部；本测试只被允许在
            //   TenantKekProvider 这一层注入，不得为了测试而改写实现）。
            //
            // 因此：真正确定性抓住"就地修改容器"的是上面的 versionsDistinct 判据
            // （实测在变体 I 上 20/20 红、在变体 M 上 15/15 红）。
            // 本判据保留为**附加的观察项**：它守住绿态的正确性下界（绿态恒为 0），
            // 但不应被当作"能抓住就地修改"的证据。
            assertEquals(0, cmeCount.get(),
                    "并发取回期间抛了 " + cmeCount.get() + " 次 ConcurrentModificationException —— "
                            + "在读路径上出现了这种异常，说明存储里的版本列表被就地修改"
                            + "（而不是整体替换），读者看到了半成品");
            assertTrue(gateHits.get() >= 1, "闸门从未被命中 —— 本测试没有真正压到轮换的竞态窗口");
        } finally {
            release.countDown();
            stormDone.set(true);
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // ③ 销毁 × 并发创建交错
    // ==================================================================

    @Test
    @DisplayName("并发③ 销毁 × 并发创建交错 → 销毁完成后该主体不得再有活跃密钥材料（不得复活）")
    void destroy_interleaved_with_concurrent_create_leaves_no_material() throws Exception {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        CountDownLatch insideCreate = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        AtomicInteger gateHits = new AtomicInteger();

        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            // 只卡住第一个创建者：让它在【已越过墓碑检查、已判定需要创建、
            // 但尚未把 DEK 写回存储】的位置上停住，随后由主线程发起销毁。
            if (gateHits.incrementAndGet() == 1) {
                insideCreate.countDown();
                await(releaseCreate, 15);
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> creator = pool.submit(
                    () -> b64(keyStore.getOrCreateDek(C).rawDek()));
            boolean reached = insideCreate.await(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);

            // 放行交给独立线程：正确实现下 destroySubjectKey 会因键锁而【阻塞】在
            // 创建者的 bySubject.compute 上（创建者卡在 currentKek，键锁不释放）。
            // 若让主线程一直等，门控就被挂死了。等 800ms（足够观察"销毁确实被挡住"）
            // 后由守护线程放行创建者 —— 这样既能验证"销毁最终生效、材料不复活"，
            // 又不会把测试卡死。这也让本测试在坏实现（销毁不阻塞）下同样能跑完。
            Thread releaser = new Thread(() -> {
                sleepQuietly(800);
                releaseCreate.countDown();
            });
            releaser.setDaemon(true);
            releaser.start();

            long t0 = System.nanoTime();
            keyStore.destroySubjectKey(SubjectKeyRef.of(C, 1));
            long destroyMillis = (System.nanoTime() - t0) / 1_000_000;
            boolean destroyed = keyStore.isDestroyed(C);

            releaseCreate.countDown(); // 幂等放行
            String createOutcome;
            try {
                creator.get(30, TimeUnit.SECONDS);
                createOutcome = "创建成功返回";
            } catch (ExecutionException ee) {
                createOutcome = ee.getCause().getClass().getSimpleName();
            }

            int liveKeys = keyStore.liveKeyCount();
            boolean materialBack = liveKeys > 0;
            boolean wrappedExposed = keyStore.wrappedOf(C, 1).isPresent();
            boolean anyWrappedExposed = keyStore.wrappedOf(C).isPresent();

            report("并发③",
                    "创建者已越过墓碑检查进入创建=" + reached
                            + "; 销毁调用耗时=" + destroyMillis + "ms(正确实现下为键锁等待，非 0)"
                            + "; 期间墓碑已记=" + destroyed
                            + "; 放行后创建者结果=" + createOutcome
                            + "; 【销毁后仍活跃的密钥条目=" + liveKeys + "】(应为 0)"
                            + "; wrappedOf(c,v1) 仍暴露材料=" + wrappedExposed
                            + "; wrappedOf(c,当前) 仍暴露材料=" + anyWrappedExposed);

            // 注意：创建者"成功返回"本身【不是】缺陷 —— 它先于销毁取得键锁，
            // 等价于"先创建、后销毁"的串行语义，销毁随后把材料删掉即可。
            // 唯一的缺陷定义是：销毁完成后材料【留下了】。故判据只落在最终状态上。
            assertTrue(destroyed, "销毁后墓碑未立 —— 无法区分『已销毁』与『从未创建』");
            assertFalse(materialBack,
                    "销毁完成后该主体仍有 " + liveKeys + " 条活跃密钥材料 —— "
                            + "删除权被并发写入静默撤销（PIPL 意义上的'复活'）");
            assertFalse(wrappedExposed, "销毁后 wrappedOf(c, v1) 仍在暴露包裹材料");
            assertFalse(anyWrappedExposed, "销毁后 wrappedOf(c) 仍在暴露包裹材料");

            // 反向对照：销毁后新的创建请求必须被墓碑拒绝（而不是又建一把）
            try {
                keyStore.getOrCreateDek(C);
                throw new AssertionError("销毁后 getOrCreateDek 竟然成功 —— "
                        + "墓碑未真正阻断创建路径，删除权可被下一次写入撤销");
            } catch (com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException expected) {
                // 预期
            }
        } finally {
            releaseCreate.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 门控 KEK provider —— 本组测试的确定性来源
    // ------------------------------------------------------------------

    /**
     * <b>带回调用闸门的 KEK provider</b>：把所有调用原样转发给真实的
     * {@link com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry}，
     * 但在 {@link #currentKek(String)} 上先触发一次回调。
     *
     * <h3>为什么闸门必须挂在 {@code currentKek()} 上</h3>
     * {@code InMemorySubjectKeyStore.createVersion()} 是密钥材料被生成的那一步，
     * 它内部调用 {@code kekProvider.currentKek()}。而 {@code createVersion()} 的调用点
     * 位于"读—判—写"的最后一环之前 —— 在它之后是"把 DEK 写回存储"。
     * 把闸门设在 {@code currentKek()}，就等价于把线程精确停在
     * <b>"已判定需要创建 / 尚未写回"</b>这个竞态窗口内，从而让窗口的宽度
     * 由测试控制，而不是由调度运气决定。
     *
     * <h3>为什么只包装 {@code currentKek()} 而不包装别的</h3>
     * {@link #kekVersion(String, String)} 是解包路径（读者会高频调用），
     * 包装它会让并发读者也参与闸门计数，使闸门语义含糊。
     * 只卡住 {@code currentKek()} 使"进入创建路径的线程数"与闸门命中次数一一对应。
     *
     * <h3>它与实现的关系</h3>
     * 本类是<b>测试替身</b>，不是对实现的改动；生产替换点（{@link TenantKekProvider}
     * 接口）已经为这类注入预留了位置 —— 这正是"密钥管理侧可替换位"的一处实际应用。
     */
    private static final class GatedKekProvider implements TenantKekProvider {

        private final TenantKekProvider delegate;
        private final Runnable onCurrentKek;

        private GatedKekProvider(TenantKekProvider delegate, Runnable onCurrentKek) {
            this.delegate = delegate;
            this.onCurrentKek = onCurrentKek;
        }

        @Override
        public byte[] currentKek(String tenantId) {
            onCurrentKek.run();
            return delegate.currentKek(tenantId);
        }

        @Override
        public byte[] kekVersion(String tenantId, String kekId) {
            return delegate.kekVersion(tenantId, kekId);
        }

        @Override
        public String currentKekId(String tenantId) {
            return delegate.currentKekId(tenantId);
        }

        @Override
        public boolean backupReady(String tenantId) {
            return delegate.backupReady(tenantId);
        }

        @Override
        public void destroyTenantKek(String tenantId) {
            delegate.destroyTenantKek(tenantId);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把关键数字打出来。<b>只用 stdout，不落盘到 {@code target/}</b>：
     * 本组测试需要能在<b>仓库外</b>被独立复跑（反向验证时喂的是仓库外编译的
     * 注入版本 class），而 {@code CryptoTestHarness.dumpEvidence} 依赖
     * 能上溯到 {@code dy-crypto} 模块目录，在仓库外会抛异常。
     */
    private static void report(String tag, String numbers) {
        System.out.println("[DY-CRYPTO-CONC] " + tag + " " + numbers);
    }
}