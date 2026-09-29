package com.diaoyuanyun.dy.crypto.key;

import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;

import java.util.Optional;

/**
 * 主体密钥库 —— per-subject DEK 的创建、取用、轮换与<b>销毁</b>。
 *
 * <h2>本接口的四个方法各承担一条 DoD</h2>
 * <table border="1">
 *   <caption>方法与 DoD 的对应</caption>
 *   <tr><th>方法</th><th>DoD</th><th>要点</th></tr>
 *   <tr>
 *     <td>{@link #getOrCreateDek}</td><td>① ② ④</td>
 *     <td>按主体创建 DEK（per-subject）→ 用租户 KEK 包裹（信封）→ 创建前必须过备份门</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #unwrap}</td><td>② ⑤</td>
 *     <td>按 wrappedDek 里的 {@code kekId} 与算法位还原 DEK —— KEK 轮换与算法换代的支撑</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #destroySubjectKey}</td><td>③</td>
 *     <td>销毁 DEK → 密文保留但不可恢复</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #wrappedOf}</td><td>④</td>
 *     <td>让"哪些行必须与数据同等备份"可枚举</td>
 *   </tr>
 * </table>
 */
public interface SubjectKeyStore {

    /**
     * 取该主体当前活跃的 DEK；不存在则创建（用租户 KEK 包裹后落库/落存储）。
     *
     * <p><b>先过备份门</b>：见 {@link TenantKekProvider#backupReady(String)}。
     * 备份未就绪时抛 {@link KeyBackupUnavailableException} —— 见该方法，
     * 门设在写入侧是有意的。
     *
     * @throws KeyBackupUnavailableException 密钥备份未就绪（fail-closed）
     * @throws SubjectKeyDestroyedException  该主体密钥已销毁 —— <b>不得</b>自动重建
     */
    SubjectDek getOrCreateDek(SubjectRef subject);

    /**
     * 把 {@link WrappedDek} 还原成明文 DEK 字节。
     *
     * <p>按 {@code wrappedDek.kekId()} 取对应版本的 KEK（不是"当前"KEK）——
     * 这是 KEK 轮换后历史数据仍可读的唯一依据。
     *
     * @throws SubjectKeyDestroyedException 密钥已销毁 —— 密文不可恢复
     * @throws SubjectKeyNotFoundException  该主体的 wrappedDek 不存在（从未创建过）
     */
    byte[] unwrap(WrappedDek wrappedDek);

    /** 按主体取<b>当前</b>包裹形态（用于备份清单枚举 / 诊断）。已销毁时返回空。 */
    Optional<WrappedDek> wrappedOf(SubjectRef subject);

    /**
     * 按主体 + <b>指定 DEK 版本</b>取包裹形态 —— 解密路径必须走这个方法。
     *
     * <p>为什么不能只提供"当前版本"：DEK 轮换后，同一个主体的历史密文指向老版本。
     * 若解密时只取当前版本，轮换当天所有历史数据会报"解不开"（AAD 冲突），
     * 而真因是"取错了密钥版本"—— 故障现象与"密钥丢失"完全一样，排查方向会被带偏。
     *
     * <p>已销毁时返回 {@code Optional.empty()}：<b>不抛</b>。调用方（{@link com.diaoyuanyun.dy.crypto.field.FieldCipher}）
     * 需要先查 {@link #isDestroyed} 才能给出正确的异常类型 ——
     * "已销毁"与"版本不存在"必须可分辨，否则删除权的结果会被报成逻辑错误。
     */
    Optional<WrappedDek> wrappedOf(SubjectRef subject, int dekVersion);

    /**
     * <b>销毁该主体的 DEK —— crypto-shredding（DoD ③）。</b>
     *
     * <h3>这个方法做两件事，缺一不可</h3>
     * <ol>
     *   <li>删除 {@code wrappedDek} 的密钥材料（不是标记为"已删除"）；
     *       若只打标记而 KEK 仍在，任何人拿到备份里的 wrappedDek 就能还原 DEK
     *       —— 那是"标记删除"，不是 crypto-shredding。</li>
     *   <li>留下 {@code tombstone}</li>（墓碑）：一条"该主体密钥已依法销毁"的记录。
     *       没有它就无法区分"已销毁"与"从未创建"，而这两种状态对业务的含义完全不同
     *       （前者 = 数据已被依法删除 → 404 语义；后者 = 我们从未存过 → 也不该返回 500）。</li>
     * </ol>
     *
     * <h3>幂等</h3>
     * 重复销毁必须成功且不改变结果。删除权请求可能重放（网络重试、用户重复提交），
     * 若第二次调用报错，调用方会误以为首次删除没成功。
     *
     * <h3>⚠️ 能力边界（诚实标注，勿过度声称）</h3>
     * 本方法销毁的是<b>本存储中的</b>密钥材料。历史备份里仍可能存在已包裹的 DEK；
     * 只有当"墓碑"本身<b>不</b>随备份回滚时，恢复备份才无法让数据重新可读。
     * 因此真实的删除权成立条件是：<b>墓碑记录在不受回滚影响的介质里</b>
     * （append-only 审计日志 / 带保留锁的独立存储），且删除 DAG 覆盖的每个衍生存储
     * 都已确认处理完毕（架构规格书 §8.4 / 竞析 E.3 / H.2-10）。
     * 本模块提供一个可注入的 {@link ShredTombstoneStore} 来表达这一要求，
     * 但<b>默认实现是进程内的，不具备抗回滚能力</b> —— 见该接口注释。
     */
    void destroySubjectKey(SubjectKeyRef ref);

    /** 该主体的密钥是否已被销毁（读墓碑）。 */
    boolean isDestroyed(SubjectRef subject);

    /**
     * DEK 轮换：生成新版本 DEK 并用当前 KEK 包裹，旧版本仍然保留（供历史密文解密）。
     *
     * <p>轮换<b>不</b>重写业务密文（那是离线任务），因此轮换后同一个主体会同时存在
     * 多个版本的 DEK —— 这正是 {@link com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope}
     * 必须携带 {@code dekVersion} 的原因。
     */
    SubjectDek rotateDek(SubjectRef subject);
}