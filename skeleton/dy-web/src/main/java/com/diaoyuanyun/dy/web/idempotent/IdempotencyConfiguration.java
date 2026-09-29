package com.diaoyuanyun.dy.web.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.Locale;

/**
 * 幂等存储装配 (ADR-10)。决定 {@link IdempotencyStore} 用哪个 {@link IdempotencyBackend}。
 *
 * <h2>默认是内存, 不是 Redis —— 这是刻意的</h2>
 * "默认自动探测到 Redis 就用 Redis"看起来更聪明, 但它把一个<b>部署期决策</b>藏进了自动配置:
 * 在多实例环境里, 若 Redis 因配置错误而未装配, 系统会静默回落到内存后端 ——
 * 幂等防线跨实例失效, 而所有监控指标正常。这类静默降级的成本远高于一次果决的失败。
 * 故本类要求<b>显式</b>选择: {@code dy.idempotency.backend=redis}。
 *
 * <h2>选 redis 而 Redis 不可用时的行为</h2>
 * 装配阶段就报错（{@code StringRedisTemplate} 不存在 -> IllegalStateException），
 * 让"配错了"在<b>启动时</b>暴露, 而不是在第一个需要幂等的请求上。启动即失败优于运行期静默降级。
 *
 * <p>键名: {@code dy.idempotency.backend}，取值 {@code memory}（默认） | {@code redis}。
 *
 * <h2>🛑 B-2（2026-09-27）：为什么 prod 环境下 memory 必须<b>启动失败</b></h2>
 * 上面那条"默认 memory 是刻意的"针对的是<b>单实例 / 本地</b>环境（骨架与本模块测试
 * 无需任何外部资源即可跑）。但它有一个此前未覆盖的副作用：<b>多实例生产环境里，
 * 只要有人漏配 {@code dy.idempotency.backend}，系统就会以内存后端启动</b> ——
 * 而内存后端<b>只能挡住同一实例内的重复提交</b>。多实例（负载均衡 + 多副本）下，
 * 同一个幂等键打到两个实例上时，两边都判 {@code FIRST}，于是<b>重复下单 / 重复扣款 /
 * 重复发放</b>照常发生，而日志、指标、健康检查<b>全部正常</b>。
 *
 * <p>这与本类开头"把部署期决策藏进自动配置"的批评是<b>同一个失效模式</b>：
 * 静默降级的成本远高于一次果决的失败。故本轮把"prod 不得用 memory"从
 * <b>部署文档里的一句话</b>升级为<b>启动期硬约束</b>：
 * active profile 含 {@code prod} 而解析结果为 {@code memory} ⇒ 抛
 * {@link IllegalStateException}，应用<b>拒绝启动</b>。
 *
 * <p>为什么是"启动失败"而不是"自动改用 redis"：自动改需要一个可用的
 * {@code StringRedisTemplate}，而 prod 缺配时它往往也不在 —— 那时又回到"静默降级"。
 * 让配置错误在<b>启动阶段</b>以一条点明键名的异常暴露，是唯一不会被忽略的处置。
 *
 * <p>为什么判据用 <b>active profile</b> 而不是某个自定义开关：profile 是部署时
 * <b>已经在用</b>的、无法"忘记打开"的既有机制（{@code SPRING_PROFILES_ACTIVE} /
 * {@code spring.profiles.active}）。新增一个 {@code dy.idempotency.strict=true}
 * 开关，等于把"必须记得设它"这件事又加了一层。
 *
 * <p>dev / 本地 / 测试不受影响：它们不带 {@code prod} profile，默认仍是 memory，
 * 骨架无需任何外部资源即可运行（{@code IdempotencyBackendFailureTest} 仍全绿）。
 */
@Configuration
public class IdempotencyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConfiguration.class);

    public static final String BACKEND_PROPERTY = "dy.idempotency.backend";
    public static final String MEMORY = "memory";
    public static final String REDIS = "redis";

    /**
     * 生产 profile 名。命中它时 {@code memory} 后端将被拒绝（见类注释的 B-2 段）。
     *
     * <p>与 {@code application.yml} 的 prod 段（{@code activate.on-profile: prod}）逐字一致。
     */
    public static final String PROD_PROFILE = "prod";

    @Bean
    public IdempotencyStore idempotencyStore(Environment env,
                                             ObjectProvider<StringRedisTemplate> redisProvider) {
        String mode = env.getProperty(BACKEND_PROPERTY, MEMORY).trim().toLowerCase();
        reject_memory_backend_in_production(env, mode);
        IdempotencyBackend backend;
        switch (mode) {
            case REDIS -> {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis == null) {
                    throw new IllegalStateException(
                            "配置 " + BACKEND_PROPERTY + "=redis, 但容器里没有 StringRedisTemplate。"
                                    + " 常见原因: spring-boot-starter-data-redis 未装配。"
                                    + " 此处选择启动失败而不是回落内存后端 —— 静默回落会让多实例下的幂等防线失效,"
                                    + " 且监控上完全看不出来。");
                }
                verify_redis_is_reachable_at_startup_if_production(env, redis);
                backend = new RedisIdempotencyBackend(redis);
            }
            case MEMORY -> backend = new InMemoryIdempotencyBackend();
            default -> throw new IllegalArgumentException(
                    "未知的 " + BACKEND_PROPERTY + " 取值: '" + mode + "', 只接受 " + MEMORY + " | " + REDIS);
        }
        log.info("幂等存储后端已装配: {} (由 {}={} 决定)", backend.describe(), BACKEND_PROPERTY, mode);
        return new IdempotencyStore(backend);
    }

    /**
     * B-2 启动自检：生产 profile 下不得使用内存幂等后端。
     *
     * <p>抽成独立方法而非内联 switch 里的一支，有两个理由：
     * <ol>
     *   <li><b>可独立取证</b>：{@code IdempotencyBackendFailureTest} 直接调用它来断言
     *       "prod + memory 必抛 / prod + redis 通过 / dev + memory 不抛"，不必构造完整的
     *       {@code idempotencyStore(...)} 装配链（那条链还需要 StringRedisTemplate）。</li>
     *   <li><b>语义独立于后端构造</b>：这条纪律守的是"环境 × 后端"的组合，
     *       与后端对象怎么造出来无关；混在 switch 里会让人以为它是 redis 分支的一部分。</li>
     * </ol>
     *
     * <p>判定用 {@link Environment#getActiveProfiles()}：这个 API 返回的是
     * <b>已被激活</b>的 profile（而非所有可能来源的候选），故对
     * {@code SPRING_PROFILES_ACTIVE=prod}、{@code spring.profiles.active=prod}、
     * 以及 {@code @ActiveProfiles("prod")} 三种注入方式一致生效。
     */
    static void reject_memory_backend_in_production(Environment env, String mode) {
        boolean prodActive = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> PROD_PROFILE.equalsIgnoreCase(p == null ? null : p.trim()));
        if (!prodActive || !MEMORY.equals(mode)) {
            return;
        }
        throw new IllegalStateException(
                "prod 环境不允许使用内存幂等后端（" + BACKEND_PROPERTY + "=" + MEMORY + "）。"
                        + "内存后端只能挡住同一实例内的重复提交 —— 多实例（负载均衡 + 多副本）下，"
                        + "同一幂等键打到两个实例会双边判 FIRST，重复下单 / 重复扣款 / 重复发放照常发生，"
                        + "而日志与监控完全正常。"
                        + "请在 prod 显式配置 " + BACKEND_PROPERTY + "=" + REDIS
                        + "（并确保 spring-boot-starter-data-redis 已装配）。"
                        + "此处选择启动失败而不是静默降级 —— 一个看不见的防线失效，"
                        + "比一次可见的启动失败危险得多。");
    }

    /**
     * B-2b 启动自检（2026-09-27 批次十三）：<b>prod + redis 时，启动阶段必须真连一次 Redis</b>。
     *
     * <h2>为什么 {@code reject_memory_backend_in_production} 不足以覆盖这一半</h2>
     * 上一条自检只保证"prod 不会是 memory"。但选对了 {@code redis} <b>不等于</b> Redis 可用：
     * {@code spring-boot-starter-data-redis} 的 {@code LettuceConnectionFactory} 是
     * <b>惰性建连</b>的（本仓 {@code dy-web/pom.xml} 的注释逐字写着"afterPropertiesSet 阶段不建连接，
     * 故障只发生在真正使用的那一刻"）。其后果是一个<b>更隐蔽</b>的失效：
     * <ul>
     *   <li>prod 配了 {@code redis}，但 Redis 主机写错 / 未启动 / 网络策略不通；</li>
     *   <li>应用<b>照常启动成功</b>（健康检查在 memory→redis 分流下也只是 DOWN，不阻断）；</li>
     *   <li>直到<b>第一个带 {@code Idempotency-Key} 的写请求</b>到达，才在
     *       {@link RedisIdempotencyBackend} 处抛 {@link IdempotencyStoreUnavailableException} ⇒
     *       <b>全线 5xx</b>。</li>
     * </ul>
     * 也就是说：配置错误被推迟到了"业务已经在跑"的时刻才暴露。这与本类开头
     * "让配置错误在<b>启动阶段</b>以一条点明键名的异常暴露，是唯一不会被忽略的处置"
     * 是同一条纪律 —— 故此处把这条纪律补完。
     *
     * <h2>为什么只对 prod 探活（而不是所有环境）</h2>
     * dev / test / 单实例骨架走 memory 后端，压根不会进 redis 分支；而本模块的单测
     * 又刻意"不依赖任何外部资源"。若对所有环境都探活，会让"显式配了 redis 的本地联调"
     * 变得无法启动（本地常常没有 Redis）。故探活<b>只在 prod profile 下执行</b> ——
     * 那里 Redis 本就是硬依赖，连不上就该拒绝启动。
     *
     * <h2>为什么用 {@code hasKey} 而不是 {@code ping}</h2>
     * {@link StringRedisTemplate} 面向字符串数据，不暴露 {@code PING}；用一次
     * 只读的 {@code hasKey(哨兵键)} 即可触发真实建连（{@code EXISTS} 命令），
     * 且不写入任何业务键空间。
     *
     * <p>抽成独立 static 方法（同 {@code reject_memory_backend_in_production} 的理由）：
     * 可被单测直接驱动"可达 / 不可达"两条路径，不必构造完整装配链与真 Redis。
     */
    static void verify_redis_is_reachable_at_startup_if_production(Environment env,
                                                                   StringRedisTemplate redis) {
        boolean prodActive = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> PROD_PROFILE.equalsIgnoreCase(p == null ? null : p.trim()));
        if (!prodActive) {
            return; // 非 prod：redis 只是"某人显式选的后端"，本地无 Redis 属正常，不阻断启动
        }
        try {
            redis.hasKey(PROBE_KEY);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "prod 环境已选择 " + BACKEND_PROPERTY + "=" + REDIS
                            + "，但启动阶段无法连接 Redis（探活键 " + PROBE_KEY + "）。"
                            + "Lettuce 是惰性建连，若不在此处拒绝启动，应用会以'看似健康'的状态起来，"
                            + "直到第一个带 Idempotency-Key 的写请求才全线 " + "5xx"
                            + "（重复下单 / 重复扣款 / 重复发放的防线整体失效）。"
                            + "请核对 spring.data.redis.host/port/password 与网络策略后重启。",
                    e);
        }
        log.info("prod 幂等后端连通性自检通过：Redis 可达（探活键 {}）", PROBE_KEY);
    }

    /** 探活用的哨兵键：只读一次 EXISTS，不写业务键空间。 */
    static final String PROBE_KEY = RedisIdempotencyBackend.KEY_PREFIX + "__startup_probe__";
}