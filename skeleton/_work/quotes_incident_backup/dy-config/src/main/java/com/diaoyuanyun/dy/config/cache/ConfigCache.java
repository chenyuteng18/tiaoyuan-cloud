package com.diaoyuanyun.dy.config.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 配置值缓存（进程内）。
 *
 * <h2>它为什么必须由 tenant_id 参与键构造</h2>
 * 见 {@link ConfigCacheKey}：只用 config_key 做键会让租户 B 命中租户 A 的条目，
 * 而数据库侧的 RLS 对此毫无察觉（B 的 SELECT 真的返回 0 行，隔离本身没坏）。
 * 隔离必须同时成立在【DB 层】和【进程缓存层】。
 *
 * <h2>为什么缓存的是"整个租户的条目"而不是"单键"</h2>
 * 这里不做任何隐式回填：{@link #put} 要求调用方明确给出租户，
 * {@link #get} 也要求明确给出租户。没有"不带租户的读"这种重载，
 * 于是"忘了带租户"在类型上就编译不过。
 */
public class ConfigCache {

    private final ConcurrentMap<ConfigCacheKey, String> store = new ConcurrentHashMap<>();

    public Optional<String> get(String tenantId, String configKey) {
        return Optional.ofNullable(store.get(new ConfigCacheKey(tenantId, configKey)));
    }

    public void put(String tenantId, String configKey, String value) {
        store.put(new ConfigCacheKey(tenantId, configKey), value);
    }

    /** 某租户某键的失效（改值后必须调用，否则读到旧值）。 */
    public void invalidate(String tenantId, String configKey) {
        store.remove(new ConfigCacheKey(tenantId, configKey));
    }

    /** 缓存条目数 —— 供测试证明"租户间条目互不覆盖"。 */
    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}