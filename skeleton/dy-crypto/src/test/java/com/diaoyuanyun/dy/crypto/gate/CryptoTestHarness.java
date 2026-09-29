package com.diaoyuanyun.dy.crypto.gate;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmId;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.field.FieldCipher;
import com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore;
import com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore;
import com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyStore;
import com.diaoyuanyun.dy.crypto.key.TenantKekProvider;
import com.diaoyuanyun.dy.crypto.shred.DeletionDag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A8 加密门禁的公共支撑：装配被测栈、定位模块目录、累积反向验证证据。
 *
 * <h2>测试密钥纪律（本任务明确要求）</h2>
 * 本 Harness 中的全部密钥都由 {@link InMemoryTenantKekRegistry} 在<b>运行时用
 * {@code SecureRandom}</b> 生成，<b>不</b>使用任何固定字节串、<b>不</b>读取
 * 环境变量里的生产密钥、<b>不</b>把密钥写进任何资源文件。
 *
 * <p>理由：把密钥写进测试常量是"测试通过但密钥泄露"的常见来源 ——
 * 一旦某个测试常量被复制到生产配置，"测试密钥"就变成了真实密钥。
 * 用随机生成则不存在这个问题，且代价为零（生成一把 256 位密钥是微秒级）。
 *
 * <p>由此还得到一个好处：测试<b>无法</b>依赖"密钥是某个已知值"，
 * 因此任何"解密成功"都必须来自真实的密钥流 —— 不可能靠常量比对假通过。
 */
public final class CryptoTestHarness {

    /** 测试用租户（可丢弃）。 */
    public static final String TENANT_A = "tnt-aaaa-0001";
    public static final String TENANT_B = "tnt-bbbb-0002";

    /** 测试用主体 id。 */
    public static final String CUSTOMER_1 = "cust-0001";
    public static final String CUSTOMER_2 = "cust-0002";

    private CryptoTestHarness() {
    }

    /** 装配一套完整的被测栈（全部为进程内实现，见各实现类的能力边界说明）。 */
    public static Stack newStack() {
        return newStack(AlgorithmId.AES_256_GCM.id());
    }

    public static Stack newStack(String writeAlgorithmId) {
        AlgorithmRegistry registry = AlgorithmRegistry.standard();
        InMemoryTenantKekRegistry keks = new InMemoryTenantKekRegistry(
                registry.resolve(AlgorithmId.AES_256_GCM));
        InMemoryShredTombstoneStore tombstones = new InMemoryShredTombstoneStore();
        SubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                keks, tombstones, registry, AlgorithmId.AES_256_GCM.id());
        FieldCipher cipher = new FieldCipher(keyStore, registry, writeAlgorithmId);
        return new Stack(registry, keks, tombstones, (InMemorySubjectKeyStore) keyStore,
                cipher, new DeletionDag(keyStore));
    }

    /** 被测栈的把手集合。 */
    public record Stack(AlgorithmRegistry algorithms,
                        InMemoryTenantKekRegistry keks,
                        InMemoryShredTombstoneStore tombstones,
                        InMemorySubjectKeyStore keyStore,
                        FieldCipher cipher,
                        DeletionDag deletionDag) {

        /** 初始化租户并标记备份就绪 —— 这是"可以开始加密"的前置。 */
        public void provisionTenant(String tenantId) {
            keks.initTenant(tenantId);
            keks.markBackupReady(tenantId);
        }
    }

    /**
     * <b>错误投递 KEK 的 provider</b> —— 反向验证 ① 的注入器。
     *
     * <p>它把所有调用原样转发给真实的 KEK 注册表，但对某个指定 {@code kekId}
     * 返回<b>另一把密钥</b>。模拟的现实场景：KEK 轮换错代、跨环境恢复、
     * 或"拿 B 租户的 KEK 去解 A 租户的 DEK"。
     *
     * <p>为什么不在测试里"直接把 wrappedDek 的 kekId 改掉"来制造这个场景：
     * 那样会同时改变 AAD 里的 kekId，于是失败原因变成"<b>AAD 不匹配</b>"
     * 而不是"<b>密钥不对</b>"，两者是同一个异常类，结论会变得含糊。
     * 本 provider 只换密钥、不动 AAD，因此失败<b>只可能</b>来自密钥本身 ——
     * 这正是反向验证要的精确归因。
     */
    public static final class MisdeliveringKekProvider implements TenantKekProvider {

        private final TenantKekProvider delegate;
        private final java.util.Map<String, byte[]> wrongKeys = new java.util.HashMap<>();

        public MisdeliveringKekProvider(TenantKekProvider delegate) {
            this.delegate = delegate;
        }

        /** 让该 kekId 返回一把错误的密钥。 */
        public void misdeliver(String kekId, byte[] wrongKey) {
            wrongKeys.put(kekId, wrongKey);
        }

        @Override
        public byte[] currentKek(String tenantId) {
            return delegate.currentKek(tenantId);
        }

        @Override
        public byte[] kekVersion(String tenantId, String kekId) {
            byte[] wrong = wrongKeys.get(kekId);
            return wrong != null ? wrong.clone() : delegate.kekVersion(tenantId, kekId);
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

    /**
     * <b>测试专用替代算法</b> —— 反向验证/可替换位验证的注入器。
     *
     * <p>它用 JDK 自带的 ChaCha20-Poly1305，密钥长度恰好也是 32 字节
     * （与 AES-256 一致），因此可以在<b>不改动任何业务调用点</b>的前提下
     * 顶替掉 AES 实现。
     *
     * <p>它<b>不是</b>候选生产算法，也<b>不</b>构成对 SM4 结论的任何暗示 ——
     * 它的唯一用途是证明"实现是可替换的、且替换后 FieldCipher 无需改动"。
     * 类名带 {@code TestOnly} 前缀就是为了让这一点在引用点可见。
     */
    public static final class TestOnlyAlternateAlgorithmProvider implements AlgorithmProvider {

        private final java.security.SecureRandom random = new java.security.SecureRandom();
        private int encryptCalls;

        @Override
        public AlgorithmId id() {
            return AlgorithmId.AES_256_GCM;
        }

        /** 被调用次数 —— 用来断言"用的确实是这个替代实现"，而不是标准实现被顺手调用了。 */
        public int encryptCalls() {
            return encryptCalls;
        }

        @Override
        public EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
            encryptCalls++;
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                        new javax.crypto.spec.IvParameterSpec(nonce));
                if (associatedData != null && associatedData.length > 0) {
                    c.updateAAD(associatedData);
                }
                return new EncryptResult(c.doFinal(plaintext), nonce);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("测试替代算法加密失败", e);
            }
        }

        @Override
        public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) {
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
                c.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                        new javax.crypto.spec.IvParameterSpec(nonce));
                if (associatedData != null && associatedData.length > 0) {
                    c.updateAAD(associatedData);
                }
                return c.doFinal(ciphertext);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException(
                        "测试替代算法：AEAD 认证失败", e);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("测试替代算法解密失败", e);
            }
        }

        @Override
        public byte[] newKey() {
            byte[] k = new byte[32];
            random.nextBytes(k);
            return k;
        }
    }

    // ------------------------------------------------------------------
    // 目录与证据
    // ------------------------------------------------------------------

    /** 模块根目录（从 surefire 工作目录逐级上溯，不依赖硬编码盘符）。 */
    public static Path moduleRoot() {
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("dy-crypto"))
                    && Files.isRegularFile(cur.resolve("pom.xml"))) {
                return cur.resolve("dy-crypto");
            }
        }
        throw new IllegalStateException("未找到 dy-crypto 模块目录；当前工作目录=" + p);
    }

    public static Path workDir() throws IOException {
        Path dir = moduleRoot().resolve("target/a8-crypto-gate");
        Files.createDirectories(dir);
        return dir;
    }

    /** 累积反向验证的"注入内容 / 期望 / 实际"三栏证据。 */
    public static void record(StringBuilder evidence, String injection, String expected, String actual) {
        evidence.append("[反向验证] 注入内容: ").append(injection).append('\n');
        evidence.append("           期望结果: ").append(expected).append('\n');
        evidence.append("           实际结果: ").append(actual).append('\n').append('\n');
        System.out.println("[A8-CRYPTO-GATE] 注入[" + injection + "] 期望[" + expected + "] 实际[" + actual + "]");
    }

    public static void dumpEvidence(String fileName, StringBuilder evidence) {
        if (evidence.length() == 0) {
            return;
        }
        try {
            Path out = workDir().resolve(fileName);
            Files.writeString(out, evidence.toString(), StandardCharsets.UTF_8);
            System.out.println("[A8-CRYPTO-GATE] 证据已写入 " + out);
        } catch (IOException e) {
            System.out.println("[A8-CRYPTO-GATE] 证据落盘失败: " + e);
        }
    }
}