package com.diaoyuanyun.dy.app.crypto.config;

import com.diaoyuanyun.dy.app.crypto.domain.KeyMaterialCipher;
import com.diaoyuanyun.dy.app.crypto.repository.CryptoKeyLedger;
import com.diaoyuanyun.dy.app.crypto.repository.DbShredTombstoneStore;
import com.diaoyuanyun.dy.app.crypto.repository.DbSubjectKeyStore;
import com.diaoyuanyun.dy.app.crypto.repository.DbTenantKekProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmId;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.field.FieldCipher;
import com.diaoyuanyun.dy.crypto.key.TenantKekProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 字段级加密的装配（B-1 收口 · ADR-12 §8.3 / §8.4）。
 *
 * <h2>这个类存在的唯一理由：让"加密真的生效"成为一个可被构建断言的装配事实</h2>
 * dy-crypto 此前有完整能力但<b>零调用方</b>。零调用方是一种很难被测出来的状态：
 * 模块自己的 28 个测试全绿，因为它测的是"能力正确"；
 * 而"这份能力有没有接到数据上"没有任何测试会红。
 * 装配类把这件事变成可见的：{@code BandService} 构造器要求 {@link FieldCipher}，
 * 拿不到就<b>启动失败</b>（而不是静默走明文路径）。
 *
 * <h2>三层密钥的装配链（自外向内）</h2>
 * <pre>
 *   DY_MASTER_KEY（环境变量）
 *      └─ KeyMaterialCipher ──包裹──▶ 租户 KEK        （tenant_kek 表）
 *                                        └─ DbTenantKekProvider
 *                                             └─ DbSubjectKeyStore ──包裹──▶ 主体 DEK（subject_dek 表）
 *                                                  └─ FieldCipher ──加密──▶ band_telemetry 的敏感字段
 * </pre>
 *
 * <h2>🛑 主密钥缺失时【不】降级 —— 宁可启动失败</h2>
 * 这是本类最重要的一条纪律。若主密钥缺失时退化为"明文写入"，
 * 那么一次环境变量配置事故就会让加密在整个环境静默失效，
 * 而所有测试、所有监控都仍然是绿的（它们不检查"库里这列到底是不是密文"）。
 * 与之相对，启动失败是一个立刻可见、无法被忽略的信号。
 *
 * <h2>算法位不写死</h2>
 * {@link AlgorithmRegistry#standard()} 目前只注册 AES-256-GCM（SM4-GCM 在
 * {@link AlgorithmId} 里登记为 {@code PENDING_CONFIRMATION}，其
 * {@code resolve} 会抛 {@code PendingAlgorithmConfirmationException} ——
 * 即"未确认的算法不得被使用"是被代码强制的，不是靠约定）。
 * 密钥包裹算法与字段加密算法都用同一注册表，故"换算法"= 改一个配置值。
 */
@Configuration
public class CryptoConfiguration {

    /** KEK 的包裹算法。🛑 默认值必须与 {@link AlgorithmId#AES_256_GCM} 的 id 逐字一致。 */
    private static final String DEFAULT_KEK_ALGORITHM = "AES-256-GCM";

    /** 字段加密的写入算法。 */
    private static final String DEFAULT_FIELD_ALGORITHM = "AES-256-GCM";

    /**
     * 算法注册表。
     *
     * <p>只注册"已实现"的算法。SM4-GCM 刻意不在此注册：
     * {@link AlgorithmId#SM4_GCM} 的 status 是 {@code PENDING_CONFIRMATION}，
     * 其 resolve 会拒绝，因此即使误配也不会悄悄用上未确认的算法。
     */
    @Bean
    public AlgorithmRegistry cryptoAlgorithmRegistry() {
        return AlgorithmRegistry.standard();
    }

    /**
     * 密钥材料加密器（用主密钥包裹 KEK）。
     *
     * <p>{@code dy.crypto.master-key} 默认取环境变量 {@code DY_MASTER_KEY}。
     * 未配置即抛（见 {@link KeyMaterialCipher#parseMasterKey}）。
     */
    @Bean
    public KeyMaterialCipher keyMaterialCipher(
            AlgorithmRegistry cryptoAlgorithmRegistry,
            @Value("${dy.crypto.master-key:${DY_MASTER_KEY:}}") String masterKeyRaw,
            @Value("${dy.crypto.kek-algorithm:" + DEFAULT_KEK_ALGORITHM + "}") String kekAlgorithm) {
        return new KeyMaterialCipher(
                KeyMaterialCipher.parseMasterKey(masterKeyRaw),
                cryptoAlgorithmRegistry.resolve(kekAlgorithm));
    }

    @Bean
    public CryptoKeyLedger cryptoKeyLedger(DataSource dataSource) {
        return new CryptoKeyLedger(dataSource);
    }

    @Bean
    public DbShredTombstoneStore dbShredTombstoneStore(DataSource dataSource) {
        return new DbShredTombstoneStore(dataSource);
    }

    @Bean
    public DbTenantKekProvider dbTenantKekProvider(
            CryptoKeyLedger cryptoKeyLedger,
            KeyMaterialCipher keyMaterialCipher,
            AlgorithmRegistry cryptoAlgorithmRegistry,
            DataSource dataSource,
            @Value("${dy.crypto.kek-algorithm:" + DEFAULT_KEK_ALGORITHM + "}") String kekAlgorithm,
            @Value("${dy.crypto.actor:crypto-bootstrap}") String actor,
            @Value("${dy.crypto.auto-provision-tenant-kek:true}") boolean autoProvision) {
        return new DbTenantKekProvider(cryptoKeyLedger, keyMaterialCipher,
                cryptoAlgorithmRegistry, kekAlgorithm, dataSource, actor, autoProvision);
    }

    @Bean
    public DbSubjectKeyStore dbSubjectKeyStore(
            CryptoKeyLedger cryptoKeyLedger,
            TenantKekProvider dbTenantKekProvider,
            DbShredTombstoneStore dbShredTombstoneStore,
            AlgorithmRegistry cryptoAlgorithmRegistry,
            DataSource dataSource,
            @Value("${dy.crypto.wrap-algorithm:" + DEFAULT_FIELD_ALGORITHM + "}") String wrapAlgorithm,
            @Value("${dy.crypto.actor:crypto-bootstrap}") String actor) {
        return new DbSubjectKeyStore(cryptoKeyLedger, dbTenantKekProvider, dbShredTombstoneStore,
                cryptoAlgorithmRegistry, wrapAlgorithm, dataSource, actor);
    }

    /**
     * 业务侧唯一需要的加解密入口（{@code FieldCipher}）。
     *
     * <p>🛑 {@code writeAlgorithmId} 是"写入所用算法"，解密时按<b>信封里</b>的算法位
     * 取实现（见 {@link FieldCipher#decryptBytes}）。两者不同是刻意的：
     * 用写入算法去解历史数据，会让算法换代当天所有老数据报"解不开"，
     * 真因却是"读的时候用了错的算法"。
     */
    @Bean
    public FieldCipher fieldCipher(DbSubjectKeyStore dbSubjectKeyStore,
                                   AlgorithmRegistry cryptoAlgorithmRegistry,
                                   @Value("${dy.crypto.field-algorithm:" + DEFAULT_FIELD_ALGORITHM + "}")
                                   String fieldAlgorithm) {
        return new FieldCipher(dbSubjectKeyStore, cryptoAlgorithmRegistry, fieldAlgorithm);
    }

    /**
     * 启动期自检：主密钥必须可解析、算法位必须可解析。
     *
     * <p>把这些检查提前到启动时（而不是第一条敏感数据进来时），
     * 是为了让"配错了"与"数据来了"两件事在时间上分开 ——
     * 否则一次配置错误会表现为一条业务请求 500，而根因藏在加密层。
     */
    @Bean
    public CryptoBootstrapCheck cryptoBootstrapCheck(
            KeyMaterialCipher keyMaterialCipher,
            AlgorithmRegistry cryptoAlgorithmRegistry,
            @Value("${dy.crypto.kek-algorithm:" + DEFAULT_KEK_ALGORITHM + "}") String kekAlgorithm,
            @Value("${dy.crypto.field-algorithm:" + DEFAULT_FIELD_ALGORITHM + "}") String fieldAlgorithm,
            @Value("${dy.crypto.wrap-algorithm:" + DEFAULT_FIELD_ALGORITHM + "}") String wrapAlgorithm) {
        return new CryptoBootstrapCheck(keyMaterialCipher, cryptoAlgorithmRegistry,
                kekAlgorithm, fieldAlgorithm, wrapAlgorithm);
    }

    /** 启动自检的载体 bean（构造即校验；无需任何方法被调用）。 */
    public static final class CryptoBootstrapCheck {
        public CryptoBootstrapCheck(KeyMaterialCipher cipher, AlgorithmRegistry registry,
                                    String kekAlgorithm, String fieldAlgorithm, String wrapAlgorithm) {
            // 三个算法位都必须能被解析出实现 —— 未实现的算法会在此抛
            // PendingAlgorithmConfirmationException，启动失败即为预期行为
            registry.resolve(kekAlgorithm);
            registry.resolve(fieldAlgorithm);
            registry.resolve(wrapAlgorithm);
            if (cipher == null) {
                throw new IllegalStateException("KeyMaterialCipher 未装配 —— 加密路径不可用");
            }
        }
    }
}