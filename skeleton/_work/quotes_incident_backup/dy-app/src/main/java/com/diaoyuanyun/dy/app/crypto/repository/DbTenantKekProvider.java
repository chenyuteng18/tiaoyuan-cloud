package com.diaoyuanyun.dy.app.crypto.repository;

import com.diaoyuanyun.dy.app.crypto.domain.KeyMaterialCipher;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyNotFoundException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.key.TenantKekProvider;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * {@link TenantKekProvider} 的持久化实现 —— KEK 落地到 {@code tenant_kek}（V11）。
 *
 * <h2>为什么必须有它（仅挂接 dy-crypto 会造出更糟的系统）</h2>
 * dy-crypto 此前只有 {@code InMemoryTenantKekRegistry}，其类头已声明
 * 「❌ 未接入生产 KMS …… 密钥材料在<b>进程内存</b>里」。若只把 dy-crypto 挂进 dy-app
 * 而不持久化，会得到一个<b>看起来加密了、但重启即永久解不开</b>的系统 ——
 * 库里全是密文，密钥已随进程消失。加密数据不可读与数据丢失等价。
 *
 * <h2>🛑 表里存的是"被 DY_MASTER_KEY 加密后的 KEK"，不是裸 KEK</h2>
 * 与 ADR-08「机密不得入 DB 配置表」同一条纪律：真正的主密钥走环境变量
 * （{@code DY_MASTER_KEY}），DB 里只存它加出来的中间态。
 *
 * <h2>诚实登记：本落地形态的安全边界</h2>
 * 拿到「DB 备份 + 环境变量 DY_MASTER_KEY」的人可以还原全部密钥。
 * 这是"DB 表作真相源、不引入外部 KMS 客户端"这一选型（架构文档第 6 条"明确筑底、
 * 拒绝超前"）的固有边界，<b>不是实现疏漏</b>。它相对"完全明文落库"的收益是明确的：
 * 库备份泄漏 / 只读副本泄漏 / SQL 注入读取 —— 这三种最常见的泄漏面不再直接产出
 * 健康数据明文。升级到外部 KMS 后此边界消失；替换点就是本类。
 */
public final class DbTenantKekProvider implements TenantKekProvider {

    private final CryptoKeyLedger ledger;
    private final KeyMaterialCipher cipher;
    private final AlgorithmRegistry algorithms;
    private final String kekAlgorithmId;
    private final TransactionTemplate tx;
    private final String actor;
    /** 是否允许"首次使用时惰性开通 KEK"（见 {@link #currentOrProvision}）。 */
    private final boolean autoProvision;

    public DbTenantKekProvider(CryptoKeyLedger ledger, KeyMaterialCipher cipher,
                               AlgorithmRegistry algorithms, String kekAlgorithmId,
                               DataSource dataSource, String actor, boolean autoProvision) {
        this.ledger = ledger;
        this.cipher = cipher;
        this.algorithms = algorithms;
        this.kekAlgorithmId = kekAlgorithmId;
        this.tx = new TransactionTemplate(new org.springframework.jdbc.datasource
                .DataSourceTransactionManager(dataSource));
        this.actor = actor;
        this.autoProvision = autoProvision;
        // 启动即解析算法位：写错的算法名必须立刻炸，而不是等到第一条敏感数据进来
        algorithms.resolve(kekAlgorithmId);
    }

    @Override
    public byte[] currentKek(String tenantId) {
        return decrypt(currentOrProvision(tenantId));
    }

    @Override
    public byte[] kekVersion(String tenantId, String kekId) {
        CryptoKeyLedger.KekRow row = ledger.findKek(tenantId, kekId)
                .orElseThrow(() -> new SubjectKeyNotFoundException(
                        "租户 " + tenantId + " 不存在 KEK 版本 " + kekId
                                + " —— 其对应的历史 wrappedDek 无法还原"
                                + "（可能来自另一环境，或已被轮换清理）"));
        if (row.isDestroyed()) {
            throw new SubjectKeyDestroyedException(new SubjectKeyRef(tenantId, "-", "-", 0));
        }
        return decrypt(row);
    }

    @Override
    public String currentKekId(String tenantId) {
        return currentOrProvision(tenantId).kekId();
    }

    /**
     * 取当前代 KEK；缺失时<b>惰性开通</b>一代。
     *
     * <h2>🛑 为什么是"首次使用时开通"，而不是"租户创建时开通"</h2>
     * 本项目当前<b>没有租户开通流程</b>（租户由运维/测试直接落 {@code tenant} 表，
     * 全仓 {@code dy-app/src/main} 与 {@code dy-config/src/main} 里没有
     * {@code INSERT INTO tenant}，也没有任何 {@code ApplicationRunner} /
     * {@code CommandLineRunner} 启动钩子）。若强行要求"先开通 KEK 才能加密"，
     * 就得凭空补一个开通流程 —— 那会把 B-1（加密未生效）换成另一个未立项的卡点。
     *
     * <p>惰性开通是 KMS 的标准做法（"首次使用时 provision"），它把
     * 「KEK 何时存在」这个问题变成不可能出错：<b>只要有人要加密，KEK 就必然存在</b>。
     * 而"租户创建时开通"依赖一个调用点，任何绕过该调用点的租户（导入、克隆、
     * 运维手插）都会得到一个"加密即报错"的状态。
     *
     * <h2>它不削弱任何门</h2>
     * 惰性开通只创建 <b>KEK</b>（用主密钥包裹）。创建 <b>DEK</b> 的那道门
     * （{@code requireBackupReady}）依然独立存在，且更严格 ——
     * 见 {@link #backupReady} 的说明。两步分开是有意的：
     * KEK 是"租户密钥空间存在"，DEK 是"某个具体主体的密钥可用"，
     * 前者不该被后者的门挡住，否则备份门在首次使用时必然误报。
     *
     * <h2>并发首用</h2>
     * 两个线程同时首次加密同一租户：都读到"无 KEK"，都尝试插 {@code kek_seq = 1}，
     * 其中一个被 {@code uq_tenant_kek_seq} 拒（23505）。此处捕获该异常并<b>重读</b> ——
     * 因为"另一个线程刚刚建好"与"我建失败"在结果上是同一件事，
     * 把它变成异常会让首次加密在并发下随机失败。
     */
    private CryptoKeyLedger.KekRow currentOrProvision(String tenantId) {
        if (ledger.allKeksDestroyed(tenantId)) {
            throw new SubjectKeyDestroyedException(new SubjectKeyRef(tenantId, "-", "-", 0));
        }
        Optional<CryptoKeyLedger.KekRow> current = ledger.findCurrentKek(tenantId);
        if (current.isPresent()) {
            return current.get();
        }
        if (!autoProvision) {
            throw new SubjectKeyNotFoundException(
                    "租户 " + tenantId + " 未初始化 KEK，且 dy.crypto.auto-provision-tenant-kek=false —— "
                            + "该模式要求由显式开通流程创建 KEK。加密路径 fail-closed，"
                            + "不得退化为『无密钥可用时明文写入』");
        }
        provisionFirstKek(tenantId);
        return ledger.findCurrentKek(tenantId).orElseThrow(() -> new SubjectKeyNotFoundException(
                "租户 " + tenantId + " 的 KEK 开通后仍读不到 —— 开通与读取之间的一致性被破坏"));
    }

    /** 开通首代 KEK（并发安全：唯一约束兜底 + 冲突后重读）。 */
    private void provisionFirstKek(String tenantId) {
        try {
            tx.execute(status -> {
                // 双重检查：进入事务后可能已有别的线程建好
                if (ledger.findCurrentKek(tenantId).isPresent()) {
                    return null;
                }
                int seq = ledger.nextKekSeq(tenantId);
                byte[] rawKek = algorithms.resolve(kekAlgorithmId).newKey();
                String kekId = "kek-" + tenantId + "-v" + seq;
                KeyMaterialCipher.KekSeal seal = cipher.encryptKek(tenantId, kekId, seq, rawKek);
                ledger.insertKek(tenantId, seq, kekId, kekAlgorithmId,
                        seal.nonce(), seal.sealed(), seal.aadText(), actor);
                return null;
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发首用：另一个线程赢了。这不是错误 —— 重读即可。
            // 🛑 只吞这一种异常：其它 DataIntegrityViolation（如 KEK 材料列的非空约束）
            //    都是真缺陷，吞掉会让它们以"KEK 偶尔读不到"的形式间歇出现。
            if (!ledger.findCurrentKek(tenantId).isPresent()) {
                throw e;
            }
        }
    }

    /**
     * 备份就绪信号。
     *
     * <h2>🛑 本实现报告的是"KEK 材料确实可被解密取回"，不是一个可以随便置真的标志</h2>
     * dy-crypto 的门是承重的（DoD ④）：创建<b>新</b> DEK 前必须确认备份就绪，
     * 否则"一把没被备份的 DEK 已用于加密"这件事会在事后才发现。
     *
     * <p>内存版把这个信号做成 {@code markBackupReady()} 的可设置布尔，并诚实声明
     * 「本类不验证备份是否真的存在（它没有能力验证）」。数据库版<b>有</b>能力做一件
     * 有意义的事：<b>真的去解密一次当前代 KEK</b>。理由是——
     * KEK 能不能被 masterKey 解出来，恰好就是"KEK 是否可用"这件事本身；
     * 而 KEK 不可用比 DEK 未备份更致命（它会让该租户<b>全部</b>主体的密文失效）。
     * 故本方法把这个前提做成一次真实检查，而不是让它成为一个永远为 true 的常量。
     *
     * <p>⚠️ 如实登记的局限：这<b>不</b>等价于"异地容灾备份存在"。等保 2.0 三级要求的
     * 备份体系（本地实时 + 异地容灾、每月恢复测试）属于运维域，本类无从验证。
     * 换外部 KMS 后，该方法应改为查询 KMS 的备份健康接口。
     */
    @Override
    public boolean backupReady(String tenantId) {
        try {
            CryptoKeyLedger.KekRow current = currentOrProvision(tenantId);
            // 真解一次：能解出来才说明这把 KEK 的包裹材料是完整、可用、可恢复的
            decrypt(current);
            return true;
        } catch (RuntimeException e) {
            // 🛑 不吞异常、不返回 true。评测失败 ⇒ 拒绝创建新 DEK（fail-closed）
            return false;
        }
    }

    /**
     * 租户级销毁：标记全部历史代 KEK 已销毁。
     *
     * <p>🛑 只标记<b>不删除</b>材料行：保留行才能让后续读取得到明确的
     * "已依法销毁"（{@link SubjectKeyDestroyedException}），而不是
     * "从未存在"（{@link SubjectKeyNotFoundException}）。
     * 把"依法删除"呈现为"逻辑错误"是 dy-crypto 反复强调要避免的失效模式。
     */
    @Override
    public void destroyTenantKek(String tenantId) {
        ledger.destroyAllKeks(tenantId);
    }

    // ------------------------------------------------------------------
    // 初始化 / 轮换（不在 TenantKekProvider 接口上，由装配层显式调用）
    // ------------------------------------------------------------------

    /**
     * 初始化或轮换一代 KEK（幂等：已存在未销毁的当前代则直接返回其 id）。
     *
     * <h2>🛑 "先查再插"必须在同一事务内，且 seq 的计算要与插入相邻</h2>
     * 见 {@link CryptoKeyLedger#nextKekSeq} 与 {@link CryptoKeyLedger#lockSubject} 的说明：
     * 两步分离会让并发轮换争同一个 seq。
     */
    public String initOrRotate(String tenantId) {
        return tx.execute(status -> {
            Optional<CryptoKeyLedger.KekRow> current = ledger.findCurrentKek(tenantId);
            if (current.isPresent()) {
                return current.get().kekId();
            }
            // 无当前代 ⇒ 首次初始化，或全部代已销毁（后者由 destroyTenantKek 标记，
            // 此时再轮换出一把新 KEK 是"租户复活"，必须由调用方明确要求，故本方法只做首次初始化）
            if (ledger.allKeksDestroyed(tenantId)) {
                throw new SubjectKeyDestroyedException(new SubjectKeyRef(tenantId, "-", "-", 0));
            }
            int seq = ledger.nextKekSeq(tenantId);
            byte[] rawKek = algorithms.resolve(kekAlgorithmId).newKey();
            String kekId = "kek-" + tenantId + "-v" + seq;
            KeyMaterialCipher.KekSeal seal = cipher.encryptKek(tenantId, kekId, seq, rawKek);
            ledger.insertKek(tenantId, seq, kekId, kekAlgorithmId,
                    seal.nonce(), seal.sealed(), seal.aadText(), actor);
            return kekId;
        });
    }

    /** 强制轮换出一代新 KEK（即使当前代未销毁）—— 用于 KEK 轮换演练与密钥泄漏处置。 */
    public String forceRotate(String tenantId) {
        return tx.execute(status -> {
            int seq = ledger.nextKekSeq(tenantId);
            byte[] rawKek = algorithms.resolve(kekAlgorithmId).newKey();
            String kekId = "kek-" + tenantId + "-v" + seq;
            KeyMaterialCipher.KekSeal seal = cipher.encryptKek(tenantId, kekId, seq, rawKek);
            ledger.insertKek(tenantId, seq, kekId, kekAlgorithmId,
                    seal.nonce(), seal.sealed(), seal.aadText(), actor);
            return kekId;
        });
    }

    private CryptoKeyLedger.KekRow rawOfCurrent(String tenantId) {
        return currentOrProvision(tenantId);
    }

    /**
     * 解出一行的裸 KEK。
     *
     * <p>🛑 就地用 {@code row.aadText()}（库里存的那一份），而不是用
     * {@link KeyMaterialCipher#kekAadText} 现算 ——
     * 两者在正常情况下必然相等，但若有人改了 AAD 构造规则，
     * "用现算的 AAD 去解老材料"会以认证失败告终，而症状看起来像"主密钥不对"。
     * 用库里那份则能给出准确的错误：材料与 AAD 不匹配。
     */
    private byte[] decrypt(CryptoKeyLedger.KekRow row) {
        return cipher.decryptKek(row.wrapNonce(), row.wrappedBytes(), row.aadText());
    }

    /** 算法位的公开只读视图（证据输出用）。 */
    public String kekAlgorithmId() {
        return kekAlgorithmId;
    }
}