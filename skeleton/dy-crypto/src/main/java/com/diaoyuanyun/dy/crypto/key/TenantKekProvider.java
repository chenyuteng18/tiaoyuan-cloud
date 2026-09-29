package com.diaoyuanyun.dy.crypto.key;

/**
 * 租户级 <b>KEK</b>（Key Encryption Key）—— 包裹 DEK 的那一层密钥。
 *
 * <h2>本接口是"可替换位"在密钥管理侧的一半</h2>
 * DoD ⑤ 说的是算法可替换，但同一件事在密钥管理侧同样成立：KEK 现在由本地实现提供，
 * 未来要接 KMS（架构规格书 §8.4 写的是"DEK 存 KMS（信封加密）"，
 * Sprint1 清单把 KMS 对接列为 A8 的<b>前置</b>，并允许"暂无则先以本地可替换接口占位"）。
 * 把 KEK 的获取收进本接口，KMS 落地就是换一个实现，业务路径不变。
 *
 * <h2>⚠️ 本轮的真实状态（不得含糊）</h2>
 * 本轮的实现是 {@link InMemoryTenantKekRegistry} —— <b>进程内、测试专用、非生产 KMS</b>。
 * 它<b>不是</b> KMS，不应被当作 KMS。理由见该实现的类注释。
 * 对外陈述只能说到："密钥管理侧的接口边界已就位，KMS 为待接入项"。
 */
public interface TenantKekProvider {

    /**
     * 取某租户当前活跃版本的 KEK。
     *
     * @throws SubjectKeyDestroyedException 该租户的 KEK 已被销毁（整个租户进入不可解密状态）
     */
    byte[] currentKek(String tenantId);

    /**
     * 取指定版本的 KEK —— 用于解密<b>历史的</b> wrappedDek。
     *
     * <p>为什么必须能取旧版本：KEK 轮换后，老 wrappedDek 仍指向老 KEK。
     * 若只提供 {@link #currentKek(String)}，轮换就等于让历史数据不可读
     * （一次"意外的 crypto-shredding"）。
     *
     * @throws SubjectKeyDestroyedException 该版本 KEK 已被销毁
     */
    byte[] kekVersion(String tenantId, String kekId);

    /** 当前活跃 KEK 的标识（写进 {@link WrappedDek#kekId()}）。 */
    String currentKekId(String tenantId);

    /**
     * <b>备份就绪检查（DoD ④ 的可执行化）</b>。
     *
     * <p>返回 {@code false} 时，{@link SubjectKeyStore#getOrCreateDek} 必须
     * <b>拒绝创建新 DEK</b>。
     *
     * <h3>为什么把"备份就绪"做成加密路径的硬前置</h3>
     * 这条约束看起来碍事，但它对应的失败模式是<b>不可逆</b>的：
     * 密钥一旦丢失，密文<b>永久</b>不可读，且没有任何补救手段
     * （这正是 crypto-shredding 的性质 —— 它既能响应删除权，也能在事故中吞掉数据）。
     * 因此"备份好了吗"不能是上线 checklist 上的一条，必须是代码里的一个门。
     * 对照：{@code dy-security} 的字段裁剪也是 fail-closed，思路一致 ——
     * <b>让不安全的默认状态无法运行，而不是让它跑起来再提醒</b>。
     */
    boolean backupReady(String tenantId);

    /**
     * 销毁某租户的 KEK（灾难性操作）。
     *
     * <p>与 per-subject 的 crypto-shredding 不同，销毁租户 KEK 会让<b>该租户全部</b>
     * 主体不可解密。本模块不把它当作删除权的手段（删除权用 per-subject DEK，
     * 见 {@link SubjectKeyStore#destroySubjectKey}），保留它是为了表达
     * "离线/退租时的整体不可读"这一场景，且调用点必须显式确认。
     */
    void destroyTenantKek(String tenantId);
}