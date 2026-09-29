package com.diaoyuanyun.dy.web.idempotent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等<b>语义</b>测试 (ADR-10), 与存储介质无关。
 *
 * <p>本类在两个后端上跑<b>同一组语义断言</b>（{@link InMemoryIdempotencyBackend} 真实可用；
 * {@link RedisIdempotencyBackend} 用一个"只实现内存语义"的测试替身驱动<b>编码/解码层</b>，
 * 见 {@link #redis_encoding_roundtrip_preserves_field_boundaries()} 的说明）。
 * 目的是把"语义正确"与"介质能用"拆成两件可分别取证的事 —— 避免出现
 * "拧了一个真实 Redis 才好用"的结论，也避免介质故障把语义测试一起染红。
 */
class IdempotencyBackendSemanticsTest {

    // ---------------------------------------------------------------- 内存后端: 真实可用

    @Test
    void in_memory_backend_first_replay_conflict_and_expiry() {
        IdempotencyStore store = new IdempotencyStore(new InMemoryIdempotencyBackend());

        assertEquals(IdempotencyStore.Resolution.FIRST, store.resolve("k", "h1").resolution);

        store.store("k", "h1", 200, "body-1");
        IdempotencyStore.Decision replay = store.resolve("k", "h1");
        assertEquals(IdempotencyStore.Resolution.REPLAY, replay.resolution);
        assertEquals(200, replay.status);
        assertEquals("body-1", replay.responseBody);

        assertEquals(IdempotencyStore.Resolution.CONFLICT, store.resolve("k", "h2").resolution);
    }

    @Test
    void in_memory_backend_treats_expired_entry_as_absent() {
        InMemoryIdempotencyBackend backend = new InMemoryIdempotencyBackend();
        // 直接写入一个已过期条目（expireAt 在过去）——这是唯一能确定性测"过期"的办法,
        // 靠 sleep 等 24h TTL 不现实
        backend.put("k", new IdempotencyBackend.Entry("h", "old", 200, System.currentTimeMillis() - 1));

        assertNull(backend.get("k"), "已过期条目必须按'不存在'返回, 否则过期后重放会返回陈旧响应");
        assertEquals(0, backend.purgeExpired(), "读时已顺手回收, 故清理点为 0");
    }

    @Test
    void in_memory_backend_refuses_to_store_already_expired_entry() {
        InMemoryIdempotencyBackend backend = new InMemoryIdempotencyBackend();
        // 已过期的条目等价于不写（否则会留下"短暂可见"的窗口, 与 Redis 侧 TTL<=0 直接跳过不一致）
        backend.put("k", new IdempotencyBackend.Entry("h", "b", 200, System.currentTimeMillis() - 1000));
        assertAbsent(backend, "k");
    }

    // ---------------------------------------------------------------- 重启语义（DoD #2）

    /**
     * DoD #2: <b>重启后同键仍幂等</b>。
     *
     * <p>做法：<b>同一个后端对象</b>喂给两个 {@link IdempotencyStore} 实例。第二个 store 不共享
     * 第一个 store 的任何字段/内存，它唯一能"记得" key 的途径就是后端 —— 这精确复现了
     * "进程重启后接上同一个共享存储"的形状。
     *
     * <p>⚠️ 本用例证明的是<b>语义不会因为 store 实例重建而丢失</b>, 以及后端接口
     * <b>确实</b>是唯一的键状态载体。它<b>不是</b>真 Redis 端到端验证（本机无 Redis,
     * 见 IdempotencyBackendFailureTest 类注释与交付报告"未验证部分"）。
     */
    @Test
    void same_backend_across_two_store_instances_survives_restart() {
        InMemoryIdempotencyBackend backend = new InMemoryIdempotencyBackend();

        IdempotencyStore beforeRestart = new IdempotencyStore(backend);
        assertEquals(IdempotencyStore.Resolution.FIRST, beforeRestart.resolve("key-r", "h").resolution);
        beforeRestart.store("key-r", "h", 201, "{\"created\":true}");

        // "重启": 全新的 store 实例, 只共享后端
        IdempotencyStore afterRestart = new IdempotencyStore(backend);

        IdempotencyStore.Decision d = afterRestart.resolve("key-r", "h");
        assertEquals(IdempotencyStore.Resolution.REPLAY, d.resolution,
                "重启后同键同体必须仍判 REPLAY, 否则重启窗口内会发生重复提交");
        assertEquals(201, d.status);
        assertEquals("{\"created\":true}", d.responseBody);

        // 且"重启后仍幂等"必须包含冲突分支
        assertEquals(IdempotencyStore.Resolution.CONFLICT, afterRestart.resolve("key-r", "other-hash").resolution,
                "重启后同键不同体必须仍判 CONFLICT");
    }

    @Test
    void store_instances_do_not_share_state_through_a_private_map() {
        // 反向守护: 若将来有人把 map 又加回 IdempotencyStore 内部, 本用例会红
        InMemoryIdempotencyBackend backendA = new InMemoryIdempotencyBackend();
        InMemoryIdempotencyBackend backendB = new InMemoryIdempotencyBackend();
        IdempotencyStore s1 = new IdempotencyStore(backendA);
        IdempotencyStore s2 = new IdempotencyStore(backendB);

        s1.store("k", "h", 200, "x");
        assertEquals(IdempotencyStore.Resolution.FIRST, s2.resolve("k", "h").resolution,
                "两个独立后端之间不得共享任何键状态");
    }

    // ---------------------------------------------------------------- Redis 编码层（可离线验证的部分）

    /**
     * Redis 后端的<b>编解码层</b>是唯一能在无 Redis 环境下确定性验证的部分, 也必须验证 ——
     * 因为它有一个很容易写错、且写错后<b>不报错只出错数据</b>的点：
     * 响应体含换行 / 制表符时, 若按"字段=一行"拼装, 记录会被切成多行, 读回时字段错位,
     * 于是客户端会收到一个"看起来是 JSON、内容却属于另一个字段"的响应体。
     *
     * <p>覆盖的边界值: 换行、回车换行、制表符、NUL、非 BMP 字符(emoji)、空串、
     * 以及分隔符本身出现在字段里。
     */
    @Test
    void redis_encoding_roundtrip_preserves_field_boundaries() {
        String[] nastyBodies = {
                "",                                             // 空响应体（204 就是空的）
                "plain json",
                "line1\nline2",                                 // 换行 —— 会破坏"字段=一行"的朴素实现
                "crlf\r\nand\r\nmore",                          // CRLF
                "tab\there",                                    // 制表符
                "delim\nv=1\nfake-looking",                     // 字段里伪造出分隔符+版本头
                "\u0000nul\u0000",                              // NUL
                "中文注释与 emoji 😀 混排",                       // 非 ASCII / 非 BMP
                "🛒".repeat(50),                                 // 长非 BMP 串（确认无字节长度假设）
        };
        for (String body : nastyBodies) {
            IdempotencyBackend.Entry original =
                    new IdempotencyBackend.Entry("hash-abc", body, 200, 1_700_000_000_000L);
            String encoded = RedisIdempotencyBackend.encode(original);
            IdempotencyBackend.Entry decoded = RedisIdempotencyBackend.decode(encoded, "k");

            assertEquals(original.bodyHash(), decoded.bodyHash(), "bodyHash 错位, body=" + preview(body));
            assertEquals(original.status(), decoded.status(), "status 错位, body=" + preview(body));
            assertEquals(original.expireAt(), decoded.expireAt(), "expireAt 错位, body=" + preview(body));
            assertEquals(original.responseBody(), decoded.responseBody(), "响应体错位, body=" + preview(body));
        }
    }

    /**
     * 反向断言: 损坏 / 未知版本的数据<b>必须抛异常</b>, 不得返回 null。
     *
     * <p>理由（写进断言的失败消息里）: 返回 null 会被 {@link IdempotencyStore#resolve} 读成 FIRST，
     * 于是"数据坏了"被伪装成"从没来过" -> 重复提交防线静默放行。这正是 fail-closed 要排除的形态。
     */
    @Test
    void redis_decoding_rejects_corrupt_and_unknown_version_instead_of_returning_null() {
        String key = "k";

        // 字段数不足（例如写入时被截断）
        IdempotencyStoreUnavailableException truncated = assertThrows(
                IdempotencyStoreUnavailableException.class,
                () -> RedisIdempotencyBackend.decode("v=1\nonly-two", key));
        assertNotNull(truncated.getMessage());

        // 未知格式版本: 不得"尽力按旧格式解析"
        IdempotencyStoreUnavailableException unknownVersion = assertThrows(
                IdempotencyStoreUnavailableException.class,
                () -> RedisIdempotencyBackend.decode("v=2\na\nb\nc\nd", key));
        assertNotNull(unknownVersion.getMessage());

        // Base64 损坏 / 数字不可解析
        assertThrows(IdempotencyStoreUnavailableException.class,
                () -> RedisIdempotencyBackend.decode("v=1\n!!!not-base64!!!\nQQ==\nQQ==\nQQ==", key));
        assertThrows(IdempotencyStoreUnavailableException.class,
                () -> RedisIdempotencyBackend.decode("v=1\nQQ==\nbm90LWEtbnVtYmVy\nQQ==\nQQ==", key));
    }

    @Test
    void store_rejects_null_backend_instead_of_silently_creating_an_unusable_store() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyStore(null));
    }

    // ---------------------------------------------------------------- helpers

    private static void assertAbsent(InMemoryIdempotencyBackend backend, String key) {
        assertNull(backend.get(key), "已过期条目不得落库");
    }

    private static String preview(String s) {
        return s.length() > 24 ? s.substring(0, 24) + "…(" + s.length() + ")" : s;
    }
}