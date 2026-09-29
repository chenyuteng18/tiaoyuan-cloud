package com.diaoyuanyun.dy.crypto.key;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内墓碑存储 —— <b>测试与演示用，不具备抗回滚能力</b>。
 *
 * <h2>为什么仍然要实现它（而不是只留一个接口）</h2>
 * 没有可用实现的接口，在测试里就只能被 mock；而 mock 的墓碑<b>永远记着</b>，
 * 于是"墓碑是否真的被读取"这件事无法被验证 —— 测试会与实现同构地自证。
 * 有一个真实（哪怕简陋）的实现，反向验证才可信：删掉墓碑 → 数据必须又能解密，
 * 这一步恰好证明"墓碑是承重的"，而不是装饰。
 *
 * <h2>⚠️ 生产禁用</h2>
 * 本实现把墓碑放在进程内存里：<b>进程重启即丢失</b>，
 * 丢失之后已被依法销毁的数据会重新变得可读。生产必须换成
 * append-only / WORM 的落点（见 {@link ShredTombstoneStore} 类注释）。
 * 本类名带 {@code InMemory} 前缀是为了让这个事实在引用点就可见。
 */
public final class InMemoryShredTombstoneStore implements ShredTombstoneStore {

    private final Map<String, Tombstone> tombstones = new ConcurrentHashMap<>();

    @Override
    public void record(SubjectKeyRef ref, Instant destroyedAt, String reason) {
        // 幂等：computeIfAbsent 让重放不会覆盖"首次销毁时间"。
        // 首次时间在删除权的证据链里是有意义的（"我们何时响应了请求"），
        // 被一次重试改写会让合规时间线失真。
        tombstones.computeIfAbsent(key(ref.tenantId(), ref.subjectType(), ref.subjectId()),
                k -> new Tombstone(ref, destroyedAt, reason));
    }

    @Override
    public boolean isDestroyed(com.diaoyuanyun.dy.crypto.envelope.SubjectRef subject) {
        return tombstones.containsKey(key(subject.tenantId(), subject.subjectType(), subject.subjectId()));
    }

    @Override
    public Optional<Tombstone> find(com.diaoyuanyun.dy.crypto.envelope.SubjectRef subject) {
        return Optional.ofNullable(
                tombstones.get(key(subject.tenantId(), subject.subjectType(), subject.subjectId())));
    }

    /** 当前墓碑数量（测试与诊断用）。 */
    public int size() {
        return tombstones.size();
    }

    /**
     * 清空墓碑 —— <b>仅供测试</b>。
     *
     * <p>存在这个方法是有意的：反向验证需要"把墓碑撤掉，看数据是否又变得可读"，
     * 那是证明"墓碑承重"的唯一办法。但它同时也是最危险的方法
     * （清空墓碑 = 让已删除数据复活），故注释写明用途。
     */
    public void clearForTest() {
        tombstones.clear();
    }

    private static String key(String tenantId, String subjectType, String subjectId) {
        return tenantId + "\u0000" + subjectType + "\u0000" + subjectId;
    }
}