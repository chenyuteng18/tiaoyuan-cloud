package com.diaoyuanyun.dy.web.idempotent;

import com.diaoyuanyun.dy.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DoD #3: <b>Redis 不可达时的行为必须显式定义并测试</b>。
 *
 * <h2>选定语义: fail-closed（拒绝服务, 不放行）</h2>
 * 理由（同 {@link IdempotencyStore} 类注释）:
 * <ol>
 *   <li>幂等键挡住的是一次<b>真实的、不可撤销的副作用</b>（重复下单 / 重复扣款 / 重复发放）。</li>
 *   <li>"报错"的代价是一次可重试的失败响应, 且客户端会带<b>同一个键</b>重试 ——
 *       重试在语义上仍是幂等的, 所以报错路径本身不制造重复。</li>
 *   <li>二者代价不对称（不可逆副作用 vs 一次失败）, 故 fail-closed。</li>
 *   <li>若选 fail-open, 故障期系统静默退化成"无幂等", 而监控上一切正常（请求 200、无异常）——
 *       一个<b>看不见的</b>防线失效, 比一次可见的 5xx 危险得多。</li>
 * </ol>
 *
 * <h2>⚠️ 取证边界（必须如实登记, 不得写成"Redis 已验证"）</h2>
 * 本机<b>没有真 Redis</b>（{@code redis-server} 不在 PATH, Docker 不可用）。因此本类用一个
 * <b>总是抛连接异常的假后端</b>（{@link AlwaysFailingBackend}）驱动语义验证。
 * 它证明的是: <b>当后端抛错时, 幂等链路的行为是 fail-closed（可枚举、可断言）</b>。
 * 它<b>不能</b>证明: 真 Redis 的连接失败会不会被 {@link RedisIdempotencyBackend} 的
 * {@code catch (RuntimeException)} 正确接住并转成 {@link IdempotencyStoreUnavailableException}
 * （Lettuce 的具体异常类型与是否被 Spring 包装, 需要真环境才能确认）。
 * 该缺口已在交付报告的"未验证部分"逐条登记。
 */
class IdempotencyBackendFailureTest {

    /**
     * 一个"总是抛连接异常"的假后端。模拟的是 {@link RedisIdempotencyBackend} 在
     * {@code redis.opsForValue().get(...)} 上抛 {@code RedisConnectionFailureException} 之后,
     * 转译出的那个故障。
     *
     * <p>刻意让四个方法<b>全部</b>抛 —— 覆盖读、写、删、清理四条路径。若只测读路径,
     * "读能 fail-closed 但写会静默丢掉一个已受理的响应"这种半截实现会漏网。
     */
    static final class AlwaysFailingBackend implements IdempotencyBackend {

        static final String CAUSE = "RedisConnectionFailureException: Unable to connect to localhost:6379";

        @Override
        public Entry get(String key) {
            throw new IdempotencyStoreUnavailableException("读取幂等键失败 (存储不可达), key=" + key,
                    new RuntimeException(CAUSE));
        }

        @Override
        public void put(String key, Entry entry) {
            throw new IdempotencyStoreUnavailableException("写入幂等键失败 (存储不可达), key=" + key,
                    new RuntimeException(CAUSE));
        }

        @Override
        public void remove(String key) {
            throw new IdempotencyStoreUnavailableException("删除幂等键失败 (存储不可达), key=" + key,
                    new RuntimeException(CAUSE));
        }

        @Override
        public int purgeExpired() {
            throw new IdempotencyStoreUnavailableException("探测 Redis 可用性失败 (存储不可达), key=__probe__",
                    new RuntimeException(CAUSE));
        }

        @Override
        public String describe() {
            return "always-failing(test double)";
        }
    }

    // ------------------------------------------------------- 存储层: 四条路径都不得静默放行

    @Test
    void resolve_does_not_degrade_to_FIRST_when_backend_is_down() {
        IdempotencyStore store = new IdempotencyStore(new AlwaysFailingBackend());

        IdempotencyStoreUnavailableException ex = assertThrows(
                IdempotencyStoreUnavailableException.class,
                () -> store.resolve("k", "h"));

        assertNotNull(ex.getMessage());
        // 关键反向断言: 若实现改成"捕获异常并返回 FIRST", 下面这行不会有东西可断言 ——
        // 它会直接拿到一个 Decision(FIRST)。这就是 fail-open 的样子, 必须被本用例抓住。
        // 用 isInstance 而非 instanceof: 两者无继承关系时 instanceof 是编译期错误, 而这里
        // 恰恰要断言"运行期真的不是 BizException 的子类"（例如有人把它改成继承 BizException）。
        assertFalse(com.diaoyuanyun.dy.common.exception.BizException.class.isInstance(ex),
                "存储故障不是业务规则问题, 不得被 @ExceptionHandler(BizException.class) 误接成 4xx");
    }

    @Test
    void store_and_purge_also_propagate_instead_of_swallowing() {
        IdempotencyStore store = new IdempotencyStore(new AlwaysFailingBackend());

        assertThrows(IdempotencyStoreUnavailableException.class,
                () -> store.store("k", "h", 200, "body"));
        assertThrows(IdempotencyStoreUnavailableException.class, store::purgeExpired);
    }

    @Test
    void remove_path_also_propagates() {
        assertThrows(IdempotencyStoreUnavailableException.class,
                () -> new AlwaysFailingBackend().remove("k"));
    }

    // ------------------------------------------------------- 链路层: 故障必须变成 5xx, 不是 200

    @Test
    void interceptor_lets_storage_failure_escape_to_5xx_instead_of_serving_the_request() throws Exception {
        IdempotencyInterceptor interceptor = new IdempotencyInterceptor(
                new IdempotencyStore(new AlwaysFailingBackend()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo/orders");
        request.setContent("{\"n\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Idempotency-Key", "key-restart-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // preHandle 必须"抛"而不是"返回 true 放行业务" —— 放行即 fail-open
        IdempotencyStoreUnavailableException ex = assertThrows(
                IdempotencyStoreUnavailableException.class,
                () -> interceptor.preHandle(request, response, idempotentHandler()));

        assertNotNull(ex.getMessage());

        // 故障经全局异常处理器落成 500 / 9001（而非 4xx，也非 200）
        ResponseEntity<com.diaoyuanyun.dy.common.result.Result<Void>> entity =
                new GlobalExceptionHandler().handleOther(ex, request);
        assertEquals(500, entity.getStatusCode().value(),
                "幂等存储故障必须表现为 5xx; 若这里是 4xx, 说明异常被当成业务规则问题处理了");
        com.diaoyuanyun.dy.common.result.Result<Void> body = entity.getBody();
        assertNotNull(body);
        assertEquals(9001, body.getCode(),
                "契约里系统级故障的唯一码位是 9001 INTERNAL_ERROR; 不得为此自造码位");
    }

    /** 装配层: 显式选了 redis 但容器里没有 StringRedisTemplate -> 启动即失败, 不静默回落内存。 */
    @Test
    void selecting_redis_without_a_template_fails_at_wiring_time_not_silently_falls_back() {
        IdempotencyConfiguration config = new IdempotencyConfiguration();
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(IdempotencyConfiguration.BACKEND_PROPERTY, IdempotencyConfiguration.REDIS);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> config.idempotencyStore(env, noRedisTemplateProvider()));
        assertTrue(ex.getMessage().contains("StringRedisTemplate"),
                "报错信息必须点明缺的是什么, 否则运维只能靠猜。实际: " + ex.getMessage());
    }

    @Test
    void unknown_backend_value_fails_loudly_instead_of_defaulting_to_memory() {
        IdempotencyConfiguration config = new IdempotencyConfiguration();
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(IdempotencyConfiguration.BACKEND_PROPERTY, "redys"); // 拼写错误

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> config.idempotencyStore(env, noRedisTemplateProvider()));
        assertTrue(ex.getMessage().contains("redys"), "实际: " + ex.getMessage());
    }

    @Test
    void default_backend_is_memory_and_is_wired_without_any_external_resource() {
        IdempotencyConfiguration config = new IdempotencyConfiguration();
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();

        IdempotencyStore store = config.idempotencyStore(env, noRedisTemplateProvider());
        assertTrue(store.backend() instanceof InMemoryIdempotencyBackend,
                "未配置时默认内存后端, 使单实例骨架与本模块测试无需任何外部资源");
        assertEquals(IdempotencyStore.Resolution.FIRST, store.resolve("k", "h").resolution);
    }

    // ------------------------------------------------------- B-2: 生产环境禁内存后端

    /**
     * B-2 收口（2026-09-27）：<b>prod + memory 必须启动失败</b>。
     *
     * <p>这是本轮把"部署文档里的一句话"升级为"启动期硬约束"的核心断言。
     * 反向断言的意义：若有人把自检改成恒不抛（或在 switch 里删掉这一支），
     * 下面第一个 {@code assertThrows} 会退化成"什么都没抛"⇒ 本用例立刻红。
     */
    @Test
    void production_profile_refuses_to_start_with_the_in_memory_backend() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles(IdempotencyConfiguration.PROD_PROFILE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> IdempotencyConfiguration.reject_memory_backend_in_production(
                        env, IdempotencyConfiguration.MEMORY),
                "🛑 prod 下用内存幂等后端必须【启动失败】—— 多实例下它会让重复提交静默通过，"
                        + "而日志与监控全部正常。静默降级比一次可见的启动失败危险得多。");
        // 报错必须点明键名与正确取值，否则运维只能靠猜
        assertTrue(ex.getMessage().contains(IdempotencyConfiguration.BACKEND_PROPERTY),
                "报错信息必须点明配置键名。实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(IdempotencyConfiguration.REDIS),
                "报错信息必须给出正确取值。实际: " + ex.getMessage());
    }

    @Test
    void production_profile_accepts_the_redis_backend() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles(IdempotencyConfiguration.PROD_PROFILE);

        // redis 是 prod 的正确选择，自检不得拦它（拦了就等于"生产没法启动"）
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> IdempotencyConfiguration.reject_memory_backend_in_production(
                        env, IdempotencyConfiguration.REDIS));
    }

    @Test
    void non_production_profiles_keep_defaulting_to_memory() {
        // dev / 本地 / 测试不带 prod profile ⇒ 内存后端必须仍被接受，
        // 否则"骨架无需任何外部资源即可运行"这条既有保证会被本轮改动破坏。
        for (String profile : new String[]{"dev", "test", "local“}) {
            org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> IdempotencyConfiguration.reject_memory_backend_in_production(
                            env, IdempotencyConfiguration.MEMORY),
                    ”profile=" + profile + " 不得被 prod 纪律误伤");
        }

        // 完全无 active profile（单测/骨架默认）同样不得被拦
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> IdempotencyConfiguration.reject_memory_backend_in_production(
                        new org.springframework.mock.env.MockEnvironment(), IdempotencyConfiguration.MEMORY));
    }

    @Test
    void production_profile_check_survives_whitespace_and_case_variants() {
        // profile 名来自环境变量，可能带空白或大小写差异；判据必须归一化，
        // 否则 "Prod" / " prod " 会绕过这条硬约束（正是最危险的失效形态：以为配了纪律，其实没有）。
        for (String variant : new String[]{"Prod", "PROD", " prod “}) {
            org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(variant);
            assertThrows(IllegalStateException.class,
                    () -> IdempotencyConfiguration.reject_memory_backend_in_production(
                            env, IdempotencyConfiguration.MEMORY),
                    ”profile 变体 '" + variant + "' 必须仍被判为 prod，否则可静默绕过");
        }
    }

    // ------------------------------------------------------- B-2b: prod + redis 必须启动期真连得上

    /**
     * B-2b 收口（2026-09-27 · 批次十三）：<b>prod + redis 但 Redis 连不上 ⇒ 必须启动失败</b>。
     *
     * <p>这一条补的是 B-2 立论里此前没被覆盖的另一半。上一条自检只保证"prod 不会是 memory"；
     * 但选对 redis <b>不等于</b> Redis 可用 —— Lettuce 是惰性建连（见 pom 注释逐字），
     * 不在此处拒绝启动，应用会"看似健康"地起来，直到第一个幂等写请求才全线 5xx。
     *
     * <p>反向断言的牙齿：若有人把 {@code verify_redis_is_reachable_at_startup_if_production}
     * 改成空方法（或去掉 try/catch），下面的 {@code assertThrows} 会退化成"什么都没抛" ⇒ 本用例立刻红。
     */
    @Test
    void production_with_unreachable_redis_refuses_to_start() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles(IdempotencyConfiguration.PROD_PROFILE);

        // 一个"每次操作都抛连接异常"的 StringRedisTemplate 子类 —— 模拟 Redis 主机不可达。
        org.springframework.data.redis.core.StringRedisTemplate downTemplate =
                new org.springframework.data.redis.core.StringRedisTemplate() {
                    @Override
                    public Boolean hasKey(String key) {
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                                "Unable to connect to localhost:6379");
                    }
                };

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(
                        env, downTemplate),
                "🛑 prod + redis 但 Redis 不可达必须【启动失败】—— 否则应用会以看似健康的状态起来，"
                        + "直到第一个幂等写请求才全线 5xx。");
        assertTrue(ex.getMessage().contains(IdempotencyConfiguration.REDIS),
                "报错必须点明选择的后端。实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("startup") || ex.getMessage().contains("启动"),
                "报错必须点明'启动阶段'语义。实际: " + ex.getMessage());
    }

    @Test
    void production_with_reachable_redis_starts_normally() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles(IdempotencyConfiguration.PROD_PROFILE);

        org.springframework.data.redis.core.StringRedisTemplate upTemplate =
                new org.springframework.data.redis.core.StringRedisTemplate() {
                    @Override
                    public Boolean hasKey(String key) {
                        return Boolean.FALSE; // 达 -> 探活键不存在，正常返回
                    }
                };

        // 可达时不得拦（拦了就等于"生产没法启动"）
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(
                        env, upTemplate));
    }

    @Test
    void non_production_does_not_probe_redis_at_startup() {
        // 非 prod 环境不探活：本地联调常常没有 Redis，"显式选了 redis 但本地没起 Redis"
        // 不应阻断启动。这是刻意的边界，反向断言它不会被误扩大到所有环境。
        for (String profile : new String[]{"dev", "test", "local“}) {
            org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);

            org.springframework.data.redis.core.StringRedisTemplate downTemplate =
                    new org.springframework.data.redis.core.StringRedisTemplate() {
                        @Override
                        public Boolean hasKey(String key) {
                            throw new org.springframework.data.redis.RedisConnectionFailureException(
                                    ”Unable to connect to localhost:6379");
                        }
                    };

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(
                            env, downTemplate),
                    "profile=" + profile + " 不得被 prod 探活纪律误伤（本地无 Redis 属正常）");
        }
    }

    // ------------------------------------------------------- helpers

    /**
     * 一个真实的 {@link org.springframework.beans.factory.ObjectProvider}, 指向一个
     * <b>没有注册任何 StringRedisTemplate</b> 的 BeanFactory —— 即"配了 redis 但依赖没装配"的现场。
     * 用真实 BeanFactory 而不是手写 stub, 是为了让 provider 的语义（getIfAvailable() 返回 null）
     * 由 Spring 自己保证, 免得替身与真实行为不一致而给出假结论。
     */
    private static org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> noRedisTemplateProvider() {
        return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.data.redis.core.StringRedisTemplate.class);
    }

    static class DummyIdempotentController {
        @Idempotent
        public String create() {
            return "ok";
        }
    }

    private static HandlerMethod idempotentHandler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyIdempotentController(),
                DummyIdempotentController.class.getMethod("create"));
    }
}