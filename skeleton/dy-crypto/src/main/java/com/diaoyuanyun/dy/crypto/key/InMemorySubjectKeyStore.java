package com.diaoyuanyun.dy.crypto.key;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.envelope.AadBinding;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider.EncryptResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主体密钥库的进程内实现 —— <b>用于本轮的骨架验证与反向验证，非生产 KMS 存储</b>。
 *
 * <h2>本轮实现的真实状态（不得含糊陈述）</h2>
 * <ul>
 *   <li>✅ 已验证：per-subject DEK 粒度、租户 KEK 包裹、DEK 销毁后不可恢复、
 *       备份未就绪即拒绝加密、DEK 轮换后历史密文仍可解、墓碑读取。</li>
 *   <li>❌ <b>未接入生产 KMS</b>。Sprint1 清单把"KMS（信封加密）对接方式已确认"
 *       列为 A8 的<b>前置</b>，并允许"暂无则先以本地可替换接口占位"。
 *       本轮走的是"占位"这一条，因此：密钥材料在<b>进程内存</b>里，
 *       生产落地必须替换 {@link TenantKekProvider} 的实现。</li>
 *   <li>⚠️ {@link #backupReady} 在本实现里是一个<b>可设置的标志</b>，它表达的是
 *       "外部备份系统报告就绪"这一信号，<b>本类不验证备份是否真的存在</b>
 *       （它没有能力验证）。测试用它来证明门是承重的；生产要求这个标志
 *       由真实备份系统的健康检查驱动。</li>
 * </ul>
 *
 * <h2>为什么把这个实现写成真实可用的类，而不是测试里的 mock</h2>
 * 见 {@link InMemoryShredTombstoneStore} 的同类理由：mock 会与实现同构地自证，
 * 而本模块要证明的恰是"哪些机制是承重的"。有一个真实实现，才能做
 * "把机制拆掉、看结论是否反转"这类反向验证。
 */
public final class InMemorySubjectKeyStore implements SubjectKeyStore {

    private final TenantKekProvider kekProvider;
    private final ShredTombstoneStore tombstones;
    private final AlgorithmRegistry algorithms;
    private final String wrapAlgorithmId;

    /** key = tenantId\0subjectType\0subjectId → 该主体全部版本的 DEK（按版本有序）。
     *
     *  <p><b>value 恒为不可变 List</b>：任何写入都用「构造新 List + 整体替换」的方式，
     *  绝不对已有 List 就地 {@code add}。这样读者遍历到时拿到的是一份不会再变的快照，
     *  不会出现 {@link java.util.ConcurrentModificationException}，也不会读到半成品。
     *
     *  <p><b>并发纪律（P0）</b>：本类的「读—判—写」三件事必须在<b>同一把键锁</b>内完成 ——
     *  即全部通过 {@link ConcurrentHashMap#compute}（或 {@link ConcurrentHashMap#computeIfAbsent}）
     *  以该主体的 key 为粒度串行化。{@code compute} 在单键上是原子的：
     *  同一主体同时只有一个 lambda 在执行，不同主体互不阻塞。
     *  过去三个方法各自读 map、再各自写 map（且 {@code createVersion} 在 map 操作之外
     *  对裸 List 做 {@code add}），导致并发下"当前版本"与"下一版本"的判定完全未序列化 ——
     *  8 线程轮换会算出同一个版本号、并发首取会为同一主体生成多把 DEK。
     */
    private final Map<String, List<StoredDek>> bySubject = new ConcurrentHashMap<>();

    public InMemorySubjectKeyStore(TenantKekProvider kekProvider,
                                   ShredTombstoneStore tombstones,
                                   AlgorithmRegistry algorithms,
                                   String wrapAlgorithmId) {
        this.kekProvider = kekProvider;
        this.tombstones = tombstones;
        this.algorithms = algorithms;
        this.wrapAlgorithmId = wrapAlgorithmId;
    }

    // ------------------------------------------------------------------
    // DoD ① ② ④：取用 / 创建
    // ------------------------------------------------------------------

    @Override
    public SubjectDek getOrCreateDek(SubjectRef subject) {
        // 【P0 关键】整个"查墓碑 → 取当前版本 / 备份门 → 生成 v1 → 放回"必须在
        // 同一把键锁（该主体的 compute）内完成。放在锁外的话，多个线程会同时
        // 判定"该主体还没有 DEK"，各自生成一把 v1 —— 12 线程并发首取会得到
        // 12 把不同的 DEK（实测版本分布 {1=10, 2=2}），于是每个线程只用得到
        // 自己那把，别人写入的密文按版本号只取得到"第一条 v1"，永久不可解。
        List<StoredDek> after = bySubject.compute(key(subject), (k, existing) -> {
            // ① 锁内重做墓碑检查：不能在锁外查完就进锁 —— 那样"查墓碑"与"写密钥"
            //    之间存在窗口，一次并发销毁会插进来，导致已依法销毁的主体
            //    在销毁之后又被写入一把新 DEK（删除权被并发写入静默撤销）。
            if (tombstones.isDestroyed(subject)) {
                throw new SubjectKeyDestroyedException(
                        new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), 0));
            }

            // ② 已有版本 → 沿用当前（最新）那一版，不新增。
            if (existing != null && !existing.isEmpty()) {
                return existing;
            }

            // ③ 备份门（DoD ④）：创建【新】DEK 之前必须确认备份就绪。
            //    顺序很重要：必须先过备份门再去生成密钥 —— 反过来的话，
            //    一把没被备份的 DEK 已经被生成并可能被用于加密，事后才发现就晚了。
            requireBackupReady(subject);

            // ④ 生成 v1，并以【新的不可变 List】整体放回（不就地 add）。
            return java.util.List.of(createVersion(subject, 1));
        });
        return toDek(subject, after.get(after.size() - 1));
    }

    @Override
    public SubjectDek rotateDek(SubjectRef subject) {
        // 【P0 关键】同 getOrCreateDek：版本号计算与 append 必须在同一把键锁内。
        // 放在锁外时，8 个线程都读到"当前版本=1"、都算出 next=2，
        // 于是同一个版本号对应多把 DEK —— 按版本号解密只取得到第一条，
        // 其余线程写入的密文永久不可解。
        List<StoredDek> after = bySubject.compute(key(subject), (k, existing) -> {
            if (tombstones.isDestroyed(subject)) {
                throw new SubjectKeyDestroyedException(
                        new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), 0));
            }
            requireBackupReady(subject);

            List<StoredDek> current = (existing == null) ? java.util.List.of() : existing;
            int next = current.isEmpty() ? 1 : current.get(current.size() - 1).version + 1;

            // 整体替换成新 List：老快照对并发读者仍然可用（他们不会被改动影响）。
            List<StoredDek> appended = new ArrayList<>(current.size() + 1);
            appended.addAll(current);
            appended.add(createVersion(subject, next));
            return java.util.List.copyOf(appended);
        });
        return toDek(subject, after.get(after.size() - 1));
    }

    private void requireBackupReady(SubjectRef subject) {
        if (!kekProvider.backupReady(subject.tenantId())) {
            throw new KeyBackupUnavailableException(
                    new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), 0),
                    "租户 " + subject.tenantId() + " 的密钥备份检查返回 false");
        }
    }

    /**
     * 生成一版 DEK 并用租户 KEK 包裹。
     *
     * <p>{@code wrappedDek} 的 {@code kekId} 记的是<b>包裹时</b>的活跃 KEK 版本，
     * 不是"当前"版本 —— 这是 KEK 轮换后旧 wrappedDek 仍能被解开的前提。
     *
     * <p><b>本方法【不】触碰 {@code bySubject}</b>：版本号的判定与整体放回由调用方
     * 在同一把键锁（{@code bySubject.compute}）内完成。这样做的两个理由：
     * <ol>
     *   <li>若在这里再对 map 做写操作（哪怕 {@code computeIfAbsent}），
     *       就是<b>在 compute 的 lambda 内嵌套操作同一个 bin</b> ——
     *       {@link ConcurrentHashMap} 的每-bin 锁不可重入，会直接死锁；</li>
     *   <li>把"生成密钥材料"这件纯计算与"改存储"这件事分开，
     *       才能在锁内一次性完成"算 next → 生成 → 整体替换"，中间不被插入。
     * </ol>
     */
    private StoredDek createVersion(SubjectRef subject, int version) {
        byte[] kek = kekProvider.currentKek(subject.tenantId());
        String kekId = kekProvider.currentKekId(subject.tenantId());

        AlgorithmProvider provider = algorithms.resolve(wrapAlgorithmId);
        byte[] dek = provider.newKey();

        // DEK 的包裹也用 AAD 绑定归属 + 版本，使得"把 wrappedDek 挪到别的租户/主体名下"
        // 必然包裹解不开（与业务字段同一套纪律）。
        byte[] aad = AadBinding.ofDekWrap(subject, version, kekId);
        EncryptResult enc = provider.encrypt(kek, dek, aad);

        WrappedDek wrapped = new WrappedDek(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                version, kekId, wrapAlgorithmId, enc.nonce(), enc.ciphertext());

        return new StoredDek(version, dek, wrapped);
    }

    // ------------------------------------------------------------------
    // DoD ② ⑤：按 kekId / 算法位还原
    // ------------------------------------------------------------------

    @Override
    public byte[] unwrap(WrappedDek wrappedDek) {
        SubjectRef subject = new SubjectRef(wrappedDek.tenantId(), wrappedDek.subjectType(),
                wrappedDek.subjectId());

        // 先查墓碑 —— 这是"删除后不可恢复"的关键位置。
        // 若这里不查，密钥材料虽然从在线存储删掉了，但只要有人手里有备份的 wrappedDek
        // 与 KEK，就能绕过 SubjectKeyStore 自行还原 DEK。墓碑把这条路也堵上。
        if (tombstones.isDestroyed(subject)) {
            throw new SubjectKeyDestroyedException(SubjectKeyRef.of(subject, wrappedDek.version()));
        }

        // 按 wrappedDek 里记的 kekId 取 KEK，而不是 currentKek：
        // KEK 轮换后，老密文必须用老 KEK 才能解开。
        byte[] kek = kekProvider.kekVersion(wrappedDek.tenantId(), wrappedDek.kekId());
        AlgorithmProvider provider = algorithms.resolve(wrappedDek.algorithmId());
        byte[] aad = AadBinding.ofDekWrap(subject, wrappedDek.version(), wrappedDek.kekId());
        return provider.decrypt(kek, wrappedDek.nonce(), wrappedDek.wrappedBytes(), aad);
    }

    @Override
    public Optional<WrappedDek> wrappedOf(SubjectRef subject) {
        if (tombstones.isDestroyed(subject)) {
            // 已销毁 → 不再暴露任何包裹材料。返回空而不是抛异常，
            // 因为"枚举备份清单"这类调用方需要能跳过已销毁主体。
            return Optional.empty();
        }
        List<StoredDek> versions = bySubject.get(key(subject));
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1).wrapped);
    }

    @Override
    public Optional<WrappedDek> wrappedOf(SubjectRef subject, int dekVersion) {
        if (tombstones.isDestroyed(subject)) {
            return Optional.empty();
        }
        // 【读者不见写入】这里拿到的是当前快照（value 恒为不可变 List，
        // 写入方一律整体替换而非就地修改），因此本循环不会抛 CME，
        // 也不会读到"加到一半"的版本列表。
        List<StoredDek> versions = bySubject.get(key(subject));
        if (versions == null) {
            return Optional.empty();
        }
        for (StoredDek sd : versions) {
            if (sd.version == dekVersion) {
                return Optional.of(sd.wrapped);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // DoD ③：crypto-shredding
    // ------------------------------------------------------------------

    @Override
    public void destroySubjectKey(SubjectKeyRef ref) {
        SubjectRef subject = new SubjectRef(ref.tenantId(), ref.subjectType(), ref.subjectId());

        // 【P0 关键】"记墓碑"与"删密钥材料"必须在同一把键锁内完成，
        // 且顺序是【先记墓碑、再删材料】。
        //
        // 顺序的第一个理由（原有）：若两步之间进程崩溃，"密钥已删但无墓碑"会让
        // 读取路径把"已依法删除"呈现为"从未创建"（逻辑错误）。
        //
        // 顺序的第二个理由（P0 新增，这是并发场景下才暴露的窗口）：
        // 由于 getOrCreateDek / rotateDek 也在【同一把键锁】内先查墓碑再写材料，
        // 两种顺序组合出来的唯一安全语义是"销毁一旦开始，同主体的创建就再也进不来"：
        //   · 先记墓碑再删材料 ⇒ 并发的创建线程要么排在销毁之前（被紧随的删除清掉），
        //     要么排在销毁之后（进锁即查墓碑 → 拒绝），不会出现"销毁完成后又被写入新 DEK"。
        //   · 若反过来（先删材料、后记墓碑），在两步之间取得锁的创建线程会
        //     读到"无墓碑、无材料"，于是生成一把新 DEK —— 已删除主体复活。
        bySubject.compute(key(subject), (k, existing) -> {
            // ① 记墓碑（幂等：重复销毁不覆盖首次销毁时间，见 InMemoryShredTombstoneStore）。
            tombstones.record(ref, java.time.Instant.now(),
                    "PIPL 删除权（crypto-shredding）");
            // ② 返回 null ⇒ ConcurrentHashMap 移除该 key，密钥材料就此消失。
            //    返回 null 而不是"返回一个空 List"，是为了让"该主体在本库中不存在"
            //    与"存在但无版本"这两种状态不混同。
            return null;
        });
    }

    @Override
    public boolean isDestroyed(SubjectRef subject) {
        return tombstones.isDestroyed(subject);
    }

    /** 备份清单枚举用：当前持有密钥材料的主体数（已销毁的不计入）。 */
    public int liveKeyCount() {
        return bySubject.size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private SubjectDek toDek(SubjectRef subject, StoredDek stored) {
        return new SubjectDek(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                stored.version, stored.dek, stored.wrapped);
    }

    private static String key(SubjectRef s) {
        return s.tenantId() + "\u0000" + s.subjectType() + "\u0000" + s.subjectId();
    }

    private static final class StoredDek {
        private final int version;
        private final byte[] dek;
        private final WrappedDek wrapped;

        private StoredDek(int version, byte[] dek, WrappedDek wrapped) {
            this.version = version;
            this.dek = dek;
            this.wrapped = wrapped;
        }
    }

    /** 诊断用：当前所有活跃主体的 wrappedDek 清单（不含密钥材料）。 */
    public Map<String, WrappedDek> inventoryForBackupAudit() {
        Map<String, WrappedDek> out = new LinkedHashMap<>();
        bySubject.forEach((k, list) -> {
            if (!list.isEmpty()) {
                out.put(k.replace('\u0000', '/'), list.get(list.size() - 1).wrapped);
            }
        });
        return out;
    }
}