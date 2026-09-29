package com.diaoyuanyun.dy.web.idempotent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版幂等后端（默认实现）。单实例部署 / 单元测试用。
 *
 * <p><b>它能证明什么、不能证明什么</b>：它能证明幂等<b>语义</b>（首次 / 重放 / 冲突 / 过期）实现正确，
 * 且在多线程下不丢条目。它<b>不能</b>提供跨实例可见性 —— 后者是 {@link RedisIdempotencyBackend}
 * 的职责，也是本抽象存在的理由（见 {@link IdempotencyBackend} 类注释）。
 *
 * <p>过期判定以 {@link IdempotencyBackend.Entry#expireAt()} 为准，读到已过期条目时按
 * "不存在"返回 {@code null} 并顺手删除。这与 Redis 后端"语义过期为准、介质 TTL 只做加速回收"
 * 的口径一致，故两个后端的可观察行为相同（幂等语义与介质无关）。
 *
 * <p>本实现不依赖任何外部资源，故 {@link IdempotencyStoreUnavailableException} 不会由它抛出。
 */
public class InMemoryIdempotencyBackend implements IdempotencyBackend {

    private final ConcurrentMap<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public Entry get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (e.expireAt() < System.currentTimeMillis()) {
            // 语义已过期: 视为不存在, 顺手回收 (Redis 后端由原生 TTL 完成同样的事)
            map.remove(key, e);
            return null;
        }
        return e;
    }

    @Override
    public void put(String key, Entry entry) {
        if (entry.expireAt() < System.currentTimeMillis()) {
            // 写入一个已经过期的条目等价于不写; 显式跳过而非先写后删, 免得留下"短暂可见"的窗口
            return;
        }
        map.put(key, entry);
    }

    @Override
    public void remove(String key) {
        map.remove(key);
    }

    @Override
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int[] removed = {0};
        map.entrySet().removeIf(en -> {
            boolean expired = en.getValue().expireAt() < now;
            if (expired) {
                removed[0]++;
            }
            return expired;
        });
        return removed[0];
    }

    @Override
    public String describe() {
        return "in-memory(ConcurrentHashMap)";
    }

    /** 仅测试用: 直接观察底层条目数（不触发过期回收），用于验证"未落库"这类负向断言。 */
    Map<String, Entry> rawView() {
        return Map.copyOf(map);
    }
}