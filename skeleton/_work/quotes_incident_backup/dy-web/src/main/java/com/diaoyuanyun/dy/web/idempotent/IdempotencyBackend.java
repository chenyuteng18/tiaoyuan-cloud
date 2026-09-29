package com.diaoyuanyun.dy.web.idempotent;

/**
 * 幂等键「键 -> 条目」的存储后端 (ADR-10)。把存储介质从幂等语义里剥出来, 目的是让
 * <b>幂等语义</b>（首次 / 重放 / 冲突 / 过期）与<b>存储介质</b>（单机内存 / Redis）能各自独立演进与测试。
 *
 * <h2>为什么必须抽象</h2>
 * 初版 {@code IdempotencyStore} 把 {@code ConcurrentHashMap} 直接写在类里。这在多实例部署下是
 * 一个静默的<b>正确性缺陷</b>, 而不是"性能待优化"：幂等键在实例 A 上首次落库后, 同键重放若被
 * 负载均衡打到实例 B, 实例 B 的内存 Map 里没有该键 -> 判定 FIRST -> <b>业务操作被执行第二次</b>。
 * 本产品是多门店 SaaS, 前端横向扩容是常态, 因此"键->条目"必须落在进程外的共享存储里。
 *
 * <h2>失败语义（重要, 不在这里决定）</h2>
 * 本接口的实现【必须】在自己的方法体内捕获底层故障（连接失败 / 序列化损坏等）并转成
 * {@link IdempotencyStoreUnavailableException}, <b>不得</b>返回 {@code null} 或静默吞掉。
 * 理由：{@code get} 返回 {@code null} 的语义是"这个键从没出现过(或已过期)" —— 调用方据此判定
 * {@code FIRST} 并放行业务操作。若用 {@code null} 表达"存储坏了", 存储故障就被伪装成"首次请求",
 * 幂等防线静默失效。故存储故障必须是一个<b>可区分的异常</b>, 由上层按 fail-closed 处理
 * （见 {@link IdempotencyStore} 的类注释）。
 *
 * <h2>实现清单</h2>
 * <ul>
 *   <li>{@link InMemoryIdempotencyBackend} —— 默认实现, 单实例 / 单元测试用。</li>
 *   <li>{@link RedisIdempotencyBackend} —— 多实例生产用 (spring-data-redis)。</li>
 * </ul>
 */
public interface IdempotencyBackend {

    /**
     * 一个幂等条目。{@code expireAt} 由调用方计算, 是<b>语义上的权威过期时刻</b>：
     * 介质侧的 TTL 只是加速回收, 判定过期一律以本字段为准（两个后端行为一致）。
     */
    record Entry(String bodyHash, String responseBody, int status, long expireAt) {
    }

    /**
     * 读取条目。语义：
     * <ul>
     *   <li>键存在且未过期 -> 返回条目；</li>
     *   <li>键不存在 / 已过期 -> 返回 {@code null}（"从没出现过", 调用方判 FIRST）；</li>
     *   <li>存储不可达 / 条目无法解码 -> <b>抛</b> {@link IdempotencyStoreUnavailableException}，<b>绝不返回 null</b>。</li>
     * </ul>
     */
    Entry get(String key);

    /**
     * 落库条目（含介质侧 TTL = {@code entry.expireAt() - now}）。若条目已过期, 允许直接跳过写入
     * （等价于"不存在", 与内存后端的可观察行为一致）。
     *
     * @throws IdempotencyStoreUnavailableException 存储不可达
     */
    void put(String key, Entry entry);

    /**
     * 删除条目（过期清理或显式废弃）。
     *
     * @throws IdempotencyStoreUnavailableException 存储不可达
     */
    void remove(String key);

    /**
     * 清理已过期条目, 返回清理条数。Redis 后端由原生 TTL 自动回收, 返回 0 是正确行为。
     *
     * @throws IdempotencyStoreUnavailableException 存储不可达
     */
    int purgeExpired();

    /** 介质标识, 仅用于启动日志与测试断言（不含任何敏感信息）。 */
    String describe();
}