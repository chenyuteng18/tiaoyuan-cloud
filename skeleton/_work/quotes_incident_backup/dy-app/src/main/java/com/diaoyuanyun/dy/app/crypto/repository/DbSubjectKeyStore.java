package com.diaoyuanyun.dy.app.crypto.repository;

import com.diaoyuanyun.dy.app.crypto.domain.KeyMaterialCipher;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.envelope.AadBinding;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.key.KeyBackupUnavailableException;
import com.diaoyuanyun.dy.crypto.key.SubjectDek;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyStore;
import com.diaoyuanyun.dy.crypto.key.TenantKekProvider;
import com.diaoyuanyun.dy.crypto.key.WrappedDek;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link SubjectKeyStore} 的持久化实现 —— DEK 包裹材料落地到 {@code subject_dek}（V11）。
 *
 * <h2>为什么需要一个 Db 版而不是继续用 InMemory</h2>
 * {@code InMemorySubjectKeyStore} 的类头已明确声明它「用于本轮的骨架验证与反向验证，
 * <b>非生产 KMS 存储</b>」，密钥材料在进程内存里。对已加密的数据而言，
 * 密钥一丢就等于数据永久不可读 ⇒ 只有 Db 版才能让"加密真正生效"这条结论成立。
 *
 * <h2>语义逐条对齐 InMemorySubjectKeyStore（不得少任何一条）</h2>
 * <table border="1">
 *   <tr><th>纪律</th><th>内存版的实现方式</th><th>本实现的方式</th></tr>
 *   <tr><td>per-subject 粒度</td><td>map key = 三元组</td><td>主键 = 三元组</td></tr>
 *   <tr><td>加密"取或建"、解密"只取不建"</td><td>{@code getOrCreateDek} vs {@code wrappedOf}</td>
 *       <td>同（本类不提供任何"解密时自动造密钥"的路径）</td></tr>
 *   <tr><td>创建前必须过备份门</td><td>{@code requireBackupReady}</td>
 *       <td>同（且 {@link DbTenantKekProvider#backupReady} 是真实可解性检查）</td></tr>
 *   <tr><td>墓碑检查在锁内</td><td>{@code compute} 内查墓碑</td>
 *       <td>咨询锁内查墓碑（见 {@link #lockAndCheckDestroyed}）</td></tr>
 *   <tr><td>销毁 = 先记墓碑、再删材料</td><td>同一 compute 内两步</td>
 *       <td>同一事务内两步（顺序不可反，理由见 {@link #destroySubjectKey}）</td></tr>
 *   <tr><td>版本号判定与写入不可被插入</td><td>{@code compute} 的每键锁</td>
 *       <td>PG 咨询锁（{@code pg_advisory_xact_lock}）</td></tr>
 *   <tr><td>按信封里的 DEK 版本取密钥</td><td>{@code wrappedOf(subject, version)}</td>
 *       <td>同（{@code findDek(...version)}）</td></tr>
 *   <tr><td>KEK 轮换后老 DEK 仍可解</td><td>wrappedDek 记包裹时的 kekId</td>
 *       <td>同（{@code subject_dek.kek_id}）</td></tr>
 * </table>
 *
 * <h2>🛑 一处<纠>刻意的差异</h2>
 * 内存版用 {@code ConcurrentHashMap.compute} 的<b>每键锁</b>；数据库版用
 * {@code pg_advisory_xact_lock}。两者语义等价（同主体串行、异主体并发），
 * 但数据库版的锁粒度是"每个事务一把（不同主体各自一把）"，且<b>事务结束自动释放</b> ——
 * 因此不存在"异常路径上忘记解锁"这种内存锁实现常见的泄漏。
 */
public final class DbSubjectKeyStore implements SubjectKeyStore {

    private final CryptoKeyLedger ledger;
    private final TenantKekProvider keks;
    private final DbShredTombstoneStore tombstones;
    private final AlgorithmRegistry algorithms;
    private final String wrapAlgorithmId;
    private final TransactionTemplate tx;
    private final String actor;

    public DbSubjectKeyStore(CryptoKeyLedger ledger, TenantKekProvider keks,
                             DbShredTombstoneStore tombstones,
                             AlgorithmRegistry algorithms, String wrapAlgorithmId,
                             DataSource dataSource, String actor) {
        this.ledger = ledger;
        this.keks = keks;
        this.tombstones = tombstones;
        this.algorithms = algorithms;
        this.wrapAlgorithmId = wrapAlgorithmId;
        this.tx = new TransactionTemplate(new org.springframework.jdbc.datasource
                .DataSourceTransactionManager(dataSource));
        this.actor = actor;
        algorithms.resolve(wrapAlgorithmId);
    }

    // ==================================================================
    // 取用 / 创建
    // ==================================================================

    @Override
    public SubjectDek getOrCreateDek(SubjectRef subject) {
        return tx.execute(status -> {
            lockAndCheckDestroyed(subject);

            // ② 已有版本 → 沿用最新那一版，不新增
            Optional<CryptoKeyLedger.DekRow> latest =
                    ledger.findLatestDek(subject.tenantId(), subject.subjectType(), subject.subjectId());
            if (latest.isPresent()) {
                return decode(subject, latest.get());
            }

            // ③ 备份门（DoD ④）：创建【新】DEK 之前必须确认备份就绪。
            //    顺序不可换：先过门再生成密钥 —— 反过来的话，一把没被备份的 DEK
            //    已经生成并可能被用于加密，事后才发现就晚了。
            requireBackupReady(subject);

            // ④ 生成 v1 并落库（锁已持有，版本号计算与插入不可被插入）
            int version = ledger.nextDekVersion(
                    subject.tenantId(), subject.subjectType(), subject.subjectId());
            return createVersion(subject, version);
        });
    }

    @Override
    public SubjectDek rotateDek(SubjectRef subject) {
        return tx.execute(status -> {
            lockAndCheckDestroyed(subject);
            requireBackupReady(subject);
            int next = ledger.nextDekVersion(
                    subject.tenantId(), subject.subjectType(), subject.subjectId());
            return createVersion(subject, next);
        });
    }

    // ==================================================================
    // 还原
    // ==================================================================

    @Override
    public byte[] unwrap(WrappedDek wrappedDek) {
        SubjectRef subject = new SubjectRef(wrappedDek.tenantId(), wrappedDek.subjectType(),
                wrappedDek.subjectId());

        // 先查墓碑 —— 这是"删除后不可恢复"的关键位置。
        // 若这里不查，密钥材料虽然从在线存储删掉了，但只要有人手里有备份的 wrappedDek
        // 与 KEK，就能绕过本类自行还原 DEK。墓碑把这条路也堵上。
        if (tombstones.isDestroyed(subject)) {
            throw new SubjectKeyDestroyedException(SubjectKeyRef.of(subject, wrappedDek.version()));
        }

        // 按 wrappedDek 里记的 kekId 取 KEK，而不是"当前"KEK：
        // KEK 轮换后，老密文必须用老 KEK 才能解开。
        byte[] kek = keks.kekVersion(wrappedDek.tenantId(), wrappedDek.kekId());
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
        return ledger.findLatestDek(subject.tenantId(), subject.subjectType(), subject.subjectId())
                .map(DbSubjectKeyStore::toWrapped);
    }

    @Override
    public Optional<WrappedDek> wrappedOf(SubjectRef subject, int dekVersion) {
        if (tombstones.isDestroyed(subject)) {
            return Optional.empty();
        }
        return ledger.findDek(subject.tenantId(), subject.subjectType(), subject.subjectId(), dekVersion)
                .map(DbSubjectKeyStore::toWrapped);
    }

    // ==================================================================
    // crypto-shredding
    // ==================================================================

    /**
     * 销毁主体密钥。
     *
     * <h2>🛑 顺序不可反：先记墓碑、再删材料 —— 且两步在同一事务内</h2>
     * 与 {@code InMemorySubjectKeyStore} 完全同款，两个理由：
     * <ol>
     *   <li>若两步之间进程崩溃，"密钥已删但无墓碑"会让读取路径把
     *       "已依法删除"呈现为"从未创建"（逻辑错误）—— 用户看到 500 而非明确的删除状态；</li>
     *   <li>并发窗口：由于 {@link #getOrCreateDek} / {@link #rotateDek} 也在
     *       <b>同一把主体锁</b>内先查墓碑再写材料，两种顺序组合出的唯一安全语义是
     *       "销毁一旦开始，同主体的创建就再也进不来"。
     *       反过来的话，在两步之间取得锁的创建线程会读到"无墓碑、无材料"，
     *       于是生成一把新 DEK —— <b>已删除主体复活</b>。</li>
     * </ol>
     */
    @Override
    public void destroySubjectKey(SubjectKeyRef ref) {
        SubjectRef subject = new SubjectRef(ref.tenantId(), ref.subjectType(), ref.subjectId());
        tx.execute(status -> {
            ledger.lockSubject(ref.tenantId(), ref.subjectType(), ref.subjectId());
            // ① 记墓碑（幂等：重复销毁不覆盖首次销毁时间）
            tombstones.record(ref, java.time.Instant.now(), "PIPL 删除权（crypto-shredding）");
            // ② 删材料
            ledger.deleteAllDeks(ref.tenantId(), ref.subjectType(), ref.subjectId());
            return null;
        });
    }

    @Override
    public boolean isDestroyed(SubjectRef subject) {
        return tombstones.isDestroyed(subject);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 取得主体锁，并在同一事务内重做墓碑检查。
     *
     * <p>🛑 不能在锁外查完墓碑就进锁 —— 那样"查墓碑"与"写密钥"之间存在窗口，
     * 一次并发销毁会插进来，导致已依法销毁的主体在销毁之后又被写入一把新 DEK
     * （删除权被并发写入静默撤销）。
     */
    private void lockAndCheckDestroyed(SubjectRef subject) {
        ledger.lockSubject(subject.tenantId(), subject.subjectType(), subject.subjectId());
        if (tombstones.isDestroyed(subject)) {
            throw new SubjectKeyDestroyedException(
                    new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), 0));
        }
    }

    private void requireBackupReady(SubjectRef subject) {
        if (!keks.backupReady(subject.tenantId())) {
            throw new KeyBackupUnavailableException(
                    new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), 0),
                    "租户 " + subject.tenantId() + " 的密钥备份检查返回 false"
                            + "（本实现会真实尝试解出当前代 KEK；失败即拒绝新建 DEK）");
        }
    }

    /**
     * 生成一版 DEK、用租户 KEK 包裹、落库。
     *
     * <p>{@code kekId} 记的是<b>包裹时</b>的活跃 KEK，不是"当前"版本 ——
     * 这是 KEK 轮换后旧材料仍能被解开的前提。
     */
    private SubjectDek createVersion(SubjectRef subject, int version) {
        byte[] kek = keks.currentKek(subject.tenantId());
        String kekId = keks.currentKekId(subject.tenantId());

        AlgorithmProvider provider = algorithms.resolve(wrapAlgorithmId);
        byte[] dek = provider.newKey();

        // DEK 的包裹也用 AAD 绑定归属 + 版本：把 wrappedDek 挪到别的租户/主体名下
        // 必然包裹解不开（与业务字段同一套纪律）
        byte[] aad = AadBinding.ofDekWrap(subject, version, kekId);
        AlgorithmProvider.EncryptResult enc = provider.encrypt(kek, dek, aad);

        ledger.insertDek(subject.tenantId(), subject.subjectType(), subject.subjectId(), version,
                kekId, wrapAlgorithmId, enc.nonce(), enc.ciphertext(),
                new String(aad, StandardCharsets.UTF_8), actor);

        WrappedDek wrapped = new WrappedDek(subject.tenantId(), subject.subjectType(),
                subject.subjectId(), version, kekId, wrapAlgorithmId, enc.nonce(), enc.ciphertext());
        return SubjectDek.restore(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                version, dek, wrapped);
    }

    /** 从库里取出材料并还原成 {@link SubjectDek}（裸 DEK 由 KEK 现解，不落库）。 */
    private SubjectDek decode(SubjectRef subject, CryptoKeyLedger.DekRow row) {
        WrappedDek wrapped = toWrapped(row);
        byte[] rawDek = unwrap(wrapped);
        // 🛑 用 restore（带身份校验的具名工厂）而不是构造器：
        //    构造器是包级私有（防"凭空造密钥"），且它没有"应当是什么"这个参照，
        //    无法校验"库里这行的归属"与"subject 参数"是否一致。
        return SubjectDek.restore(row.tenantId(), row.subjectType(), row.subjectId(),
                row.dekVersion(), rawDek, wrapped);
    }

    private static WrappedDek toWrapped(CryptoKeyLedger.DekRow row) {
        return new WrappedDek(row.tenantId(), row.subjectType(), row.subjectId(), row.dekVersion(),
                row.kekId(), row.algorithmId(), row.wrapNonce(), row.wrappedBytes());
    }

    /** 主体当前最新 DEK 版本（证据输出用）。 */
    public OptionalInt currentVersionOf(SubjectRef subject) {
        return ledger.findLatestDek(subject.tenantId(), subject.subjectType(), subject.subjectId())
                .stream().mapToInt(CryptoKeyLedger.DekRow::dekVersion).findFirst();
    }

    /** 某主体全部 DEK 版本清单（备份清单枚举用）。 */
    public List<Integer> versionsOf(SubjectRef subject) {
        return ledger.listDeks(subject.tenantId(), subject.subjectType(), subject.subjectId())
                .stream().map(CryptoKeyLedger.DekRow::dekVersion).toList();
    }

    /** 供装配层做启动期自检：算法位与 KEK 包裹算法是否一致。 */
    public String wrapAlgorithmId() {
        return wrapAlgorithmId;
    }
}