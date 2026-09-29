package com.diaoyuanyun.dy.crypto.algorithm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 算法标识 → 实现的注册表。<b>这是 DoD ⑤ "可替换位"的唯一装配点。</b>
 *
 * <h2>为什么需要一个注册表，而不是在 FieldCipher 里 new 一个实现</h2>
 * 如果 {@code FieldCipher} 直接持有 {@code new AesGcmAlgorithmProvider()}，那么"替换算法"
 * 就等于"改 FieldCipher"，而 FieldCipher 是所有业务路径的必经之地 ——
 * 一次替换要动的是承重墙。有了注册表，替换是<b>加一行装配</b>，承重墙不动。
 *
 * <h2>{@link #standard()} 的 fail-closed 语义（本类最要紧的一条）</h2>
 * 注册表按信封里的 {@link AlgorithmId} 解析实现，分三种情形处置：
 * <table border="1">
 *   <caption>解析结果</caption>
 *   <tr><th>情形</th><th>处置</th><th>为什么</th></tr>
 *   <tr>
 *     <td>已注册且状态 {@code IMPLEMENTED}</td>
 *     <td>返回实现</td><td>正常路径</td>
 *   </tr>
 *   <tr>
 *     <td>未注册</td>
 *     <td>抛 {@link IllegalArgumentException}</td>
 *     <td>配置/数据损坏，需人工介入</td>
 *   </tr>
 *   <tr>
 *     <td>状态为 {@code PENDING_CONFIRMATION}（即 SM4）</td>
 *     <td>抛 {@link PendingAlgorithmConfirmationException}</td>
 *     <td><b>绝不回退到 AES</b>。回退会让"待确认项"在运行期被静默越过，
 *         对外声称的算法与实际的算法不一致 —— 这正是 H.4-1 要防的事</td>
 *   </tr>
 * </table>
 */
public final class AlgorithmRegistry {

    /** H.4-1 的出处，写进异常消息，让读到告警的人能直接找到裁定上下文。 */
    public static final String SM4_RULING_REF =
            "ADR 十五 H.4-1，架构规格书 §十一，⚠️ 待测评机构确认";

    private final Map<AlgorithmId, AlgorithmProvider> providers = new LinkedHashMap<>();

    private AlgorithmRegistry() {
    }

    /**
     * 装配标准注册表：目前只有 {@link AlgorithmId#AES_256_GCM} 有实现。
     *
     * <p>{@link AlgorithmId#SM4_GCM} <b>有意不注册</b> —— 注册了就等于工程侧
     * 自行认定它可以用了。它保持"未注册 + 状态为待确认"的双重保护。
     */
    public static AlgorithmRegistry standard() {
        return of(new AesGcmAlgorithmProvider());
    }

    /**
     * 用指定实现装配注册表 —— <b>这是"替换算法"的正式入口</b>。
     *
     * <p>存在的理由：{@link #register(AlgorithmProvider)} 对同一算法的重复注册会抛异常
     * （有意如此，防止装配事故被静默覆盖）。但"替换实现"恰恰是<b>合法的</b>重复注册场景
     * —— 换供货商、换国密实现、测试注入替代实现都属于这一种。
     * 与其让调用方去猜"该先清空还是该先卸载"，不如给一个显式的装配入口：
     * 一次调用即为完整的实现集合，语义无歧义。
     *
     * <p>典型用法（生产换 SM4 实现时）：
     * {@code AlgorithmRegistry.of(new Sm4GcmAlgorithmProvider())}
     */
    public static AlgorithmRegistry of(AlgorithmProvider... providerSet) {
        AlgorithmRegistry r = new AlgorithmRegistry();
        for (AlgorithmProvider p : providerSet) {
            r.register(p);
        }
        return r;
    }

    /** 注册一个实现。同一算法重复注册即视为装配错误（不静默覆盖）。 */
    public void register(AlgorithmProvider provider) {
        AlgorithmProvider prev = providers.put(provider.id(), provider);
        if (prev != null) {
            throw new IllegalStateException("算法 " + provider.id().id() + " 被重复注册 —— "
                    + "装配错误；静默覆盖会让『实际用的是哪个实现』变得不可判定");
        }
    }

    /**
     * 按标识解析实现。见类注释的三种情形表 —— <b>未知与待确认都必须抛，不得返回 null</b>。
     *
     * @throws PendingAlgorithmConfirmationException 算法状态为 ⚠️ 待确认
     * @throws IllegalArgumentException              算法未登记
     */
    public AlgorithmProvider resolve(AlgorithmId id) {
        if (id == null) {
            throw new IllegalArgumentException("算法标识为 null —— 信封缺少算法位，拒绝猜测");
        }
        if (id.status() == AlgorithmStatus.PENDING_CONFIRMATION) {
            // 这一支必须【在查表之前】。若先查表，就会得到"未注册"，
            // 而"未注册"与"待确认"是两件完全不同的事（一个是 bug，一个是合规前置未完成）。
            throw new PendingAlgorithmConfirmationException(id, SM4_RULING_REF);
        }
        AlgorithmProvider p = providers.get(id);
        if (p == null) {
            throw new IllegalArgumentException("算法 " + id.id() + " 已登记但无实现 —— "
                    + "装配缺失，不得回退到默认算法");
        }
        return p;
    }

    /** 按信封里的标识串解析。字符串无法映射到任何 {@link AlgorithmId} 时视为信封损坏。 */
    public AlgorithmProvider resolve(String algorithmId) {
        AlgorithmId id = AlgorithmId.of(algorithmId);
        if (id == null) {
            throw new IllegalArgumentException("信封中的算法标识 '" + algorithmId
                    + "' 未在本模块登记 —— 信封可能被篡改，或来自更新版本的写入方");
        }
        return resolve(id);
    }

    /** 当前已注册（即真正可用）的算法集合，供自检/诊断端点使用。 */
    public Set<AlgorithmId> registered() {
        return Set.copyOf(providers.keySet());
    }
}