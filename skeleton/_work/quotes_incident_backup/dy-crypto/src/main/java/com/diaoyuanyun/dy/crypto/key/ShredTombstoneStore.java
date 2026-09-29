package com.diaoyuanyun.dy.crypto.key;

import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;

import java.time.Instant;

/**
 * crypto-shredding 的<b>墓碑存储</b> —— "该主体密钥已依法销毁"这一事实的落点。
 *
 * <h2>墓碑为什么是 crypto-shredding 的承重件</h2>
 * 只有"删掉密钥材料"是不够的，因为密钥材料存在于<b>备份</b>里。设想：
 * <ol>
 *   <li>T0：某主体的 DEK 被创建，且按 DoD ④ 被认真备份（正是我们要求的）；</li>
 *   <li>T1：该主体行使删除权 → 我们销毁在线存储里的 wrappedDek；</li>
 *   <li>T2：某个运维事故后，从 T0 的备份恢复数据库。</li>
 * </ol>
 * 如果没有任何"已销毁"的痕迹，T2 之后该主体的数据<b>又变得可读了</b> ——
 * 删除权被一次备份恢复静默撤销。
 *
 * <p>反过来，只要墓碑记录在<b>不受该次回滚影响</b>的地方，
 * 恢复后读取路径就能查到"这把 DEK 已被销毁"，从而拒绝解密 ——
 * 密文虽然回到了库里，但<b>不可恢复</b>这一性质被保住了。
 *
 * <h2>⚠️ 本模块默认实现的能力边界（必须如实陈述）</h2>
 * 默认的 {@link InMemoryShredTombstoneStore} 是<b>进程内</b>的，
 * 它<b>不具备</b>抗回滚能力，也<b>不是</b>持久化真相源。
 * 它的作用是把这个要求变成一个<b>显式的接口边界</b>，使生产落地时
 * 有一个明确的挂载点（对应架构规格书 §8.4 的"删除 DAG"与 ADR-09 的 append-only 审计日志）。
 *
 * <p>生产落地时的合理选择（本模块不代为选定）：
 * <ul>
 *   <li>写入 ADR-09 的 append-only 审计日志（已有哈希链与权限撤销保障）；</li>
 *   <li>或写入带对象锁（WORM）的独立存储 —— 备份恢复不会覆盖它。</li>
 * </ul>
 *
 * <p><b>不得</b>把墓碑只写进会被一起恢复的业务库 —— 那等于没有墓碑。
 */
public interface ShredTombstoneStore {

    /** 记一条销毁墓碑。重复记同一主体必须幂等（不报错、不覆盖首次时间）。 */
    void record(SubjectKeyRef ref, Instant destroyedAt, String reason);

    /** 该主体是否已有墓碑。读取路径靠它拒绝解密已被依法删除的数据。 */
    boolean isDestroyed(SubjectRef subject);

    /** 取墓碑详情（用于 404 语义之外的诊断与审计对账）。 */
    java.util.Optional<Tombstone> find(SubjectRef subject);

    /** 墓碑记录。{@code reason} 让"为什么销毁"（删除权 / 退租 / 误操作）可追溯。 */
    record Tombstone(SubjectKeyRef ref, Instant destroyedAt, String reason) {
    }
}