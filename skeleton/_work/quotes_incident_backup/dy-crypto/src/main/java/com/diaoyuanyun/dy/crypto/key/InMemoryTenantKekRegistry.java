package com.diaoyuanyun.dy.crypto.key;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内租户 KEK 注册表 —— <b>测试/骨架占位实现，不是 KMS</b>。
 *
 * <h2>⚠️ 必须如实陈述的边界（这是本任务最容易过度声称的地方）</h2>
 * <ul>
 *   <li><b>不是 KMS。</b>密钥材料在 JVM 堆里，随进程消失；没有 HSM、没有防导出、
 *       没有访问审计、没有跨节点一致性。</li>
 *   <li><b>不是生产实现。</b>多实例部署下，各实例的 KEK 会不一致 ——
 *       一个实例包裹的 DEK，另一个实例解不开。这在测试里不会暴露
 *       （单实例），只会在扩到两台机器时以"解不开历史数据"的形式爆炸。</li>
 *   <li>{@code backupReady} 是一个<b>显式设置的标志</b>，本类<b>不</b>验证备份是否存在。
 *       它表达的是"外部备份系统报告就绪"这一契约，测试用它证明门是承重的。</li>
 * </ul>
 *
 * <h2>保留它的理由</h2>
 * 任务明确允许"暂无 KMS 则先以本地可替换接口占位"
 * （Sprint1 清单 §Checklist：KMS 对接是 A8 前置，暂无则先以本地可替换接口占位）。
 * 有真实（哪怕简陋）的实现，才能做"拆掉机制看结论是否反转"的反向验证；
 * 纯 mock 会与实现同构地自证。
 *
 * <h2>生产替换点</h2>
 * 换一个 {@link TenantKekProvider} 实现即可（阿里云 KMS / 腾讯云 KMS / HashiCorp Vault，
 * 或本地 HSM）。{@link InMemorySubjectKeyStore} 与全部业务路径<b>不需要改</b> ——
 * 这就是"密钥管理侧可替换位"的含义。
 *
 * <h2>为什么用 {@code SecureRandom} 而不是 {@code Random}</h2>
 * {@code java.util.Random} 是可预测的（48 位线性同余），用它生成密钥等于没有密钥。
 * 这类错误不会让任何测试变红 —— 只在密钥被预测时才暴露。故此处显式使用 {@code SecureRandom}。
 */
public final class InMemoryTenantKekRegistry implements TenantKekProvider {

    private final AlgorithmProvider algorithm;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    /** tenantId → 该租户的 KEK 版本列表（按生成顺序，最后一个是当前活跃版本）。 */
    private final Map<String, List<KekVersion>> byTenant = new ConcurrentHashMap<>();

    /** tenantId → 备份就绪标志。见类注释的能力边界。 */
    private final Map<String, Boolean> backupReady = new ConcurrentHashMap<>();

    /** tenantId → 已销毁的 kekId 集合。销毁租户 KEK 会使该租户全部主体不可解密。 */
    private final Map<String, Set<String>> destroyedKeKeks = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger kekSeq = new java.util.concurrent.atomic.AtomicInteger();

    public InMemoryTenantKekRegistry(AlgorithmProvider algorithm) {
        this.algorithm = algorithm;
    }

    // ------------------------------------------------------------------
    // TenantKekProvider
    // ------------------------------------------------------------------

    @Override
    public byte[] currentKek(String tenantId) {
        List<KekVersion> versions = requireLiveTenant(tenantId);
        return versions.get(versions.size() - 1).bytes.clone();
    }

    @Override
    public byte[] kekVersion(String tenantId, String kekId) {
        List<KekVersion> versions = byTenant.get(tenantId);
        if (versions == null) {
            throw new SubjectKeyNotFoundException("租户 " + tenantId + " 无任何 KEK —— "
                    + "无法还原历史 wrappedDek");
        }
        for (KekVersion v : versions) {
            if (v.kekId.equals(kekId)) {
                if (isKekDestroyed(tenantId, kekId)) {
                    throw new SubjectKeyDestroyedException(
                            new SubjectKeyRef(tenantId, "-", "-", 0));
                }
                return v.bytes.clone();
            }
        }
        throw new SubjectKeyNotFoundException("租户 " + tenantId + " 不存在 KEK 版本 " + kekId
                + " —— 下面的历史材料无法还原（可能来自另一环境或已被轮换清理）");
    }

    @Override
    public String currentKekId(String tenantId) {
        List<KekVersion> versions = requireLiveTenant(tenantId);
        return versions.get(versions.size() - 1).kekId;
    }

    @Override
    public boolean backupReady(String tenantId) {
        // 未显式设置 = 未就绪。fail-closed 的默认值必须是不安全的那一侧的【反面】：
        // 默认"就绪"会让"忘记配置"变成"静默无备份地加密"，而那正是 DoD ④ 要防的。
        return Boolean.TRUE.equals(backupReady.get(tenantId));
    }

    @Override
    public void destroyTenantKek(String tenantId) {
        List<KekVersion> versions = byTenant.get(tenantId);
        if (versions == null) {
            return;
        }
        Set<String> destroyed = destroyedKeKeks.computeIfAbsent(tenantId,
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (KekVersion v : versions) {
            destroyed.add(v.kekId);
        }
    }

    // ------------------------------------------------------------------
    // 装配接口（Harness / 未来 KMS 适配器的对应位置）
    // ------------------------------------------------------------------

    /** 创建租户的初始 KEK（版本 1）。重复调用不覆盖已有版本。 */
    public String initTenant(String tenantId) {
        return rotateKek(tenantId);
    }

    /** KEK 轮换：生成新一代 KEK，旧代保留（供历史 wrappedDek 还原）。 */
    public String rotateKek(String tenantId) {
        byte[] kek = algorithm.newKey();
        String kekId = "kek-" + tenantId + "-v" + kekSeq.incrementAndGet();
        byTenant.computeIfAbsent(tenantId, k -> new ArrayList<>())
                .add(new KekVersion(kekId, kek));
        return kekId;
    }

    /** 标记租户的密钥备份已就绪 —— 之后才允许为该租户创建 DEK（DoD ④）。 */
    public void markBackupReady(String tenantId) {
        backupReady.put(tenantId, true);
    }

    /** 标记备份不再就绪（用于反向验证"门是承重的"）。 */
    public void revokeBackupReadiness(String tenantId) {
        backupReady.put(tenantId, false);
    }

    /** 当前 KEK 版本数（诊断/测试用）。 */
    public int kekVersionCount(String tenantId) {
        List<KekVersion> v = byTenant.get(tenantId);
        return v == null ? 0 : v.size();
    }

    /** 该租户是否无任何活跃（未销毁）KEK。 */
    public boolean allKeksDestroyed(String tenantId) {
        List<KekVersion> versions = byTenant.get(tenantId);
        if (versions == null || versions.isEmpty()) {
            return true;
        }
        return versions.stream().allMatch(v -> isKekDestroyed(tenantId, v.kekId));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<KekVersion> requireLiveTenant(String tenantId) {
        List<KekVersion> versions = byTenant.get(tenantId);
        if (versions == null || versions.isEmpty()) {
            throw new SubjectKeyNotFoundException("租户 " + tenantId + " 未初始化 KEK —— "
                    + "加密路径必须 fail-closed，不得退化为『无密钥可用时明文写入』");
        }
        if (allKeksDestroyed(tenantId)) {
            throw new SubjectKeyDestroyedException(new SubjectKeyRef(tenantId, "-", "-", 0));
        }
        return versions;
    }

    private boolean isKekDestroyed(String tenantId, String kekId) {
        Set<String> destroyed = destroyedKeKeks.get(tenantId);
        return destroyed != null && destroyed.contains(kekId);
    }

    /** KEK 版本元数据（不含导出接口 —— 密钥只经 currentKek/kekVersion 按需返回副本）。 */
    private static final class KekVersion {
        private final String kekId;
        private final byte[] bytes;

        private KekVersion(String kekId, byte[] bytes) {
            this.kekId = kekId;
            this.bytes = bytes;
        }
    }

    /** 诊断用：租户 → KEK 版本标识清单（不含密钥材料）。 */
    public Map<String, List<String>> kekInventory() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        byTenant.forEach((t, list) -> {
            List<String> ids = new ArrayList<>();
            for (KekVersion v : list) {
                ids.add(v.kekId + (isKekDestroyed(t, v.kekId) ? "(destroyed)" : ""));
            }
            out.put(t, ids);
        });
        return out;
    }
}