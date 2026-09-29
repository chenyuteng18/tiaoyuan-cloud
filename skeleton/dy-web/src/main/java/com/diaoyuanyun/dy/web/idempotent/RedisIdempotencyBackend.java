package com.diaoyuanyun.dy.web.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Redis 版幂等后端（多实例生产用）。键 -> 条目落在进程外共享存储，使"实例 A 首次、实例 B 重放"
 * 仍能判 {@code REPLAY}（见 {@link IdempotencyBackend} 类注释）。
 *
 * <h2>序列化：显式、自描述、不依赖 JDK 原生序列化</h2>
 * 用 {@link StringRedisTemplate} + 手工拼的<b>行分隔文本</b>，而不是
 * {@code RedisTemplate<String,Object>} + {@code JdkSerializationRedisSerializer}：
 * <ul>
 *   <li>JDK 原生序列化绑死类版本（条目字段一改就连旧数据都读不出），且反序列化任意字节流是
 *       一个已知的攻击面；本产品存的是响应体原文，更不该走它。</li>
 *   <li>文本格式可直接用 {@code redis-cli GET} 排查"这个键到底存了什么"，运维可读。</li>
 * </ul>
 *
 * <p><b>编码方式（关键）</b>：{@code responseBody} 是服务端真实 HTTP 响应体，可能含换行、制表符、
 * 甚至任意二进制（如附件流）。若按"字段 = 一行"拼装，换行会把一条记录切成多行，读取时错位
 * —— 这不会报错，只会<b>静默地把错误的响应体重放给客户端</b>。故每个字段都先用
 * <b>Base64</b>（无换行的 RFC 4648 字母表）编码再拼，用 {@code \n} 分隔字段。
 * Base64 字母表里没有 {@code \n}，故分隔符不会在字段内部出现，join/split 是双射。
 *
 * <p><b>格式版本字段</b>：首字段是 {@code v=1}。将来若改格式，读到未知版本应当<b>拒绝</b>
 * （而不是尽力解析）—— 尽力解析在格式演进时会把旧记录解成"看起来合理但其实是错的"条目。
 * 拒绝的正确处置是抛 {@link IdempotencyStoreUnavailableException}（fail-closed）。
 *
 * <p><b>介质 TTL 与语义 TTL 的分工</b>：语义过期一律以条目里的 {@code expireAt} 为准；
 * Redis 的 {@code EX}&nbsp;TTL 只做加速回收（避免冷键永久占内存）。二者取同一个数（本类的
 * {@link #put} 用 {@code expireAt - now} 作为 EX），故不存在"Redis 里已没了、但语义上还没过期"
 * 的窗口；反过来"Redis 里还在、但语义上已过期"由 {@code get} 的显式判定兜住。
 */
public class RedisIdempotencyBackend implements IdempotencyBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyBackend.class);

    /** 键前缀。加前缀是为了让幂等键在共享 Redis 里可辨识、可单独备份/清理，不与别的用途混用同一键空间。 */
    public static final String KEY_PREFIX = "dy:idem:";

    /** 当前只认这一个格式版本；读到别的版本按"条目不可用"处理（见类注释）。 */
    private static final String FORMAT_VERSION = "v=1";

    private static final String FIELD_SEP = "\n";

    private final StringRedisTemplate redis;

    public RedisIdempotencyBackend(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Entry get(String key) {
        String raw;
        try {
            raw = redis.opsForValue().get(redisKey(key));
        } catch (RuntimeException e) {
            // 底层连接/超时/集群故障: 必须转成可辨识的异常, 绝不返回 null（null 的语义是"键不存在"）
            throw unavailable("读取幂等键失败", key, e);
        }
        if (raw == null) {
            return null; // 真的不存在（或已被 Redis TTL 回收）-> 调用方判 FIRST
        }
        Entry e = decode(raw, key);
        if (e.expireAt() < System.currentTimeMillis()) {
            // 语义已过期（Redis TTL 尚未回收, 比如被 PERSIST 过, 或 Redis 时间与本地有偏差）-> 视为不存在
            try {
                redis.delete(redisKey(key));
            } catch (RuntimeException ex) {
                // 删除失败不影响本次判定结果（已按过期处理），记日志后继续, 不升级为请求失败
                log.warn("幂等键语义已过期但删除失败, key={}", key, ex);
            }
            return null;
        }
        return e;
    }

    @Override
    public void put(String key, Entry entry) {
        long ttlMillis = entry.expireAt() - System.currentTimeMillis();
        if (ttlMillis <= 0) {
            // 已过期的条目等价于不写（与内存后端一致）
            return;
        }
        try {
            redis.opsForValue().set(redisKey(key), encode(entry), ttlMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            throw unavailable("写入幂等键失败", key, e);
        }
    }

    @Override
    public void remove(String key) {
        try {
            redis.delete(redisKey(key));
        } catch (RuntimeException e) {
            throw unavailable("删除幂等键失败", key, e);
        }
    }

    @Override
    public int purgeExpired() {
        // Redis 端由原生 EX TTL 回收, 本进程无需扫描（也无从扫描全库而不影响他人键空间）。
        // 返回 0 是正确语义, 不是"没实现"。
        try {
            // 仍然探一次连通性，使"Redis 挂了"能在定时清理点被发现，而不是等到真实请求才暴露
            redis.hasKey(redisKey("__probe__"));
        } catch (RuntimeException e) {
            throw unavailable("探测 Redis 可用性失败", "__probe__", e);
        }
        return 0;
    }

    @Override
    public String describe() {
        return "redis(StringRedisTemplate, prefix=" + KEY_PREFIX + ", format=" + FORMAT_VERSION + ")";
    }

    // ------------------------------------------------------------------ 编码

    private static String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    /**
     * 编码为 Base64 行（每字段一行）。见类注释：Base64 字母表不含 {@code \n}，故分隔无歧义。
     * 字段顺序固定为 {@code v, bodyHash, status, expireAt, responseBody}。
     */
    static String encode(Entry e) {
        return String.join(FIELD_SEP,
                FORMAT_VERSION,
                b64(e.bodyHash()),
                b64(String.valueOf(e.status())),
                b64(String.valueOf(e.expireAt())),
                b64(e.responseBody()));
    }

    /**
     * 解码。任何形状异常（字段数不对 / 版本未知 / Base64 损坏 / 数字不可解析）都抛
     * {@link IdempotencyStoreUnavailableException} —— 不允许"尽力解析"或返回 null。
     * 理由：返回 null 会被上层读成 FIRST，即把"数据坏了"伪装成"从没来过"，属于静默放行。
     */
    static Entry decode(String raw, String key) {
        String[] parts = raw.split(FIELD_SEP, -1);
        if (parts.length != 5) {
            throw new IdempotencyStoreUnavailableException(
                    "幂等条目字段数异常(期望 5, 实得 " + parts.length + "), key=" + key
                            + " —— 拒绝解析, 避免把损坏数据当成第一次请求");
        }
        if (!FORMAT_VERSION.equals(parts[0])) {
            throw new IdempotencyStoreUnavailableException(
                    "幂等条目格式版本未知: " + parts[0] + " (本进程只认 " + FORMAT_VERSION + "), key=" + key
                            + " —— 拒绝按旧格式尽力解析");
        }
        try {
            String bodyHash = unb64(parts[1]);
            int status = Integer.parseInt(unb64(parts[2]));
            long expireAt = Long.parseLong(unb64(parts[3]));
            String responseBody = unb64(parts[4]);
            return new Entry(bodyHash, responseBody, status, expireAt);
        } catch (RuntimeException ex) {
            throw new IdempotencyStoreUnavailableException(
                    "幂等条目解码失败, key=" + key + " —— 拒绝以 null 表达损坏(会静默放行重复提交)", ex);
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static IdempotencyStoreUnavailableException unavailable(String what, String key, Throwable cause) {
        return new IdempotencyStoreUnavailableException(
                what + " (存储不可达), key=" + key + " —— fail-closed, 不静默放行", cause);
    }
}