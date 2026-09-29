package com.diaoyuanyun.dy.web.idempotent;

/**
 * 幂等键存储 (ADR-10): 24h TTL, 承载首次 / 重放 / 冲突三段语义。
 *
 * <h2>职责边界（本类只做语义, 不做存储）</h2>
 * "键 -> 条目"的读写全部委托给可注入的 {@link IdempotencyBackend}。本类只负责<b>语义</b>：
 * 键存在与否、请求体哈希是否一致、条目是否过期, 组合出
 * {@link Resolution#FIRST} / {@link Resolution#REPLAY} / {@link Resolution#CONFLICT}。
 *
 * <p><b>为什么这么做</b>：初版把 {@code ConcurrentHashMap} 直接写进本类, 「幂等语义」与
 * 「存储介质」被绑成一个原子, 结果是<b>换介质就要改语义代码</b>，而多实例下内存介质又是错的
 * （同键重放打到另一个实例会判 FIRST）。拆开后, 默认仍是内存（单实例 / 单测可用），
 * 生产换 {@link RedisIdempotencyBackend} 时本类一行不必改。
 *
 * <h2>存储故障语义：fail-closed（已定, 可测）</h2>
 * 后端不可用时, {@link #resolve} / {@link #store} 会抛出后端转来的
 * {@link IdempotencyStoreUnavailableException}, 由上层放行到全局异常处理器变成 5xx。
 *
 * <p><b>为什么选 fail-closed 而不是"存储坏了就放行"</b>：
 * <ul>
 *   <li>幂等键是<b>重复提交防线</b>。本产品里被它挡住的是一次重复下单 / 重复扣款 / 重复发放权益。
 *       放行的代价是一次真实的、不可撤销的副作用。</li>
 *   <li>报错的代价是一次失败响应, 且客户端会<b>带着同一个键重试</b> —— 重试在语义上仍然是幂等的,
 *       所以"报错"这条路径本身不会制造重复。</li>
 *   <li>二者的代价不对称（不可逆副作用 vs 一次可重试的失败）, 故选择 fail-closed。</li>
 *   <li>反面论证：若选 fail-open, 故障期间系统会静默退化成"无幂等", 而监控上看起来一切正常
 *       （请求 200、无异常）—— 一个<b>看不见的</b>防线失效比一次可见的 5xx 危险得多。</li>
 * </ul>
 * 该语义由 {@code IdempotencyBackendFailureTest} 用"总是抛连接异常的假后端"驱动, 不是口头约定。
 *
 * <p>契约后果（显式登记）：存储不可达时 {@code POST} 会被幂等拦截器在 {@code preHandle} 阶段
 * 抛出 -> 当前实现下请求变成 5xx。这不是新引入的对外行为（内存介质在主进程内不会"不可达",
 * 所以此前该分支不存在）, 而是把"新增了一种故障"的语义定义清楚。
 */
public final class IdempotencyStore {

    /** 24 小时 TTL (毫秒) */
    public static final long TTL_MS = 24L * 3600 * 1000;

    public enum Resolution {
        /** 首次请求, 尚无记录 */
        FIRST,
        /** 同键重放, 返回原响应 */
        REPLAY,
        /** 同键不同体, 冲突 */
        CONFLICT
    }

    public static final class Decision {
        public final Resolution resolution;
        public final int status;
        public final String responseBody;

        Decision(Resolution resolution, int status, String responseBody) {
            this.resolution = resolution;
            this.status = status;
            this.responseBody = responseBody;
        }
    }

    private final IdempotencyBackend backend;

    /**
     * 默认构造: 使用进程内内存后端。单实例骨架与既有单元测试走这条路径,
     * 既有 4 例语义用例的构造方式（{@code new IdempotencyStore()}）保持不变。
     */
    public IdempotencyStore() {
        this(new InMemoryIdempotencyBackend());
    }

    /** 注入后端。生产装配传入 {@link RedisIdempotencyBackend}；测试可传入"同一后端对象"模拟进程重启。 */
    public IdempotencyStore(IdempotencyBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("IdempotencyBackend 不得为 null（没有后端就无法实现幂等）");
        }
        this.backend = backend;
    }

    public IdempotencyBackend backend() {
        return backend;
    }

    /**
     * 解析一次幂等键命中的结果。
     *
     * @param key      幂等键
     * @param bodyHash 请求体哈希
     * @return FIRST / REPLAY(携带原响应) / CONFLICT
     * @throws IdempotencyStoreUnavailableException 后端不可达（fail-closed，见类注释）
     */
    public Decision resolve(String key, String bodyHash) {
        IdempotencyBackend.Entry e = backend.get(key);
        if (e == null) {
            // 不存在, 或已过期（过期条目由后端按"不存在"返回, 语义与初版"remove 后判 FIRST"一致）
            return new Decision(Resolution.FIRST, 0, null);
        }
        if (e.bodyHash().equals(bodyHash)) {
            return new Decision(Resolution.REPLAY, e.status(), e.responseBody());
        }
        return new Decision(Resolution.CONFLICT, 0, null);
    }

    /**
     * 落库条目（TTL 从当前时刻起算 {@link #TTL_MS}）。
     *
     * @throws IdempotencyStoreUnavailableException 后端不可达（fail-closed）
     */
    public void store(String key, String bodyHash, int status, String responseBody) {
        backend.put(key, new IdempotencyBackend.Entry(
                bodyHash, responseBody, status, System.currentTimeMillis() + TTL_MS));
    }

    /** 清理过期条目 (生产由调度/Redis TTL 负责; 单元测试不需要) */
    public void purgeExpired() {
        backend.purgeExpired();
    }
}