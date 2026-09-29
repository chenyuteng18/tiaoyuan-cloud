package com.diaoyuanyun.dy.web.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 为 metrics / health 端点提供<b>最低优先级</b>默认值 (A7 DoD #1)。
 *
 * <h2>为什么需要它: "接上了 registry" 和 "端点真的能抓" 是两回事</h2>
 * 引入 actuator + prometheus registry 之后, {@code PrometheusMeterRegistry} 会被自动装配、
 * 指标也确实在采集 —— 但 Spring Boot 默认<b>只暴露 {@code health} 端点</b>。
 * 于是 {@code /actuator/prometheus} 返回 404, "三支柱可采集"在<b>可采集</b>这一步就不成立,
 * 而构建、启动、日志全都正常（典型的"配置正确但功能不存在"沉默失效）。
 *
 * <h2>为什么用 addLast（最低优先级）</h2>
 * dy-web 是一个<b>库模块</b>: 它有权规定自己的默认值, 但无权夺走应用的配置主权。
 * {@code addLast} 把默认值挂在属性源链最末 —— 应用侧 {@code application.yml}、命令行参数、
 * 环境变量里任何一处显式写了同名属性都会覆盖它。若写成 {@code addFirst}, 应用侧就再也改不动,
 * 等于库反过来劫持应用。
 *
 * <h2>为什么用 EnvironmentPostProcessor 而不是 @Configuration + @ConditionalOnMissingProperty</h2>
 * actuator 端点的暴露判定发生在自动配置早期, 读的就是环境里的属性值。
 * 环境后处理器能保证"默认值在任何自动配置读取之前就已就位", 避免"有时生效有时不生效"的顺序敏感性。
 *
 * <h2>★ 被本类顺带修掉的一个跨模块副作用（必须登记）</h2>
 * A3 引入 {@code spring-boot-starter-data-redis}（compile 作用域, 见 dy-web/pom.xml 的注释）之后,
 * {@code RedisConnectionFactory} 会出现在 dy-app 的类路径与容器里。于是 actuator 的
 * {@code RedisHealthContributorAutoConfiguration}（{@code @ConditionalOnClass(RedisConnectionFactory)}）
 * 会注册一个 <b>Redis 健康指示器</b>。而 {@code management.health.redis.enabled} 默认为 {@code true}。
 *
 * <p>后果: 本机/多数环境没有 Redis, {@code /actuator/health} 会从 {@code UP} 变成 {@code DOWN}。
 * 这是<b>没人动 dy-app 却被改掉行为</b>的一类改动 —— 而且症状出现在一个此前一直绿的端点上。
 *
 * <p>处置（按"Redis 到底是不是硬依赖"分流, 与 {@code dy.idempotency.backend} 保持一致）:
 * <ul>
 *   <li>{@code dy.idempotency.backend != redis}（默认）: Redis 是<b>可选</b>依赖
 *       （幂等走内存后端）。此时代谢健康检查把整个应用标 DOWN 是<b>撒谎</b> ——
 *       应用完全可以用, 却报不可用。故默认关掉该指示器。</li>
 *   <li>{@code dy.idempotency.backend == redis}: Redis 变成<b>硬依赖</b>
 *       （存储不可达时幂等链路整体 fail-closed, 见 {@code IdempotencyStore}）。
 *       此时健康检查<b>必须</b>报出来, 故默认打开 —— 一个连不上 Redis 的实例
 *       不应该被负载均衡当成健康的。</li>
 * </ul>
 * 两个开关由同一处推导, 因此不可能互相矛盾（这也正是把它写进代码而不是写进文档的理由）。
 */
public class ObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Spring Boot 的属性源名, 出现在 --debug 的 property source 列表里, 便于定位"这个默认值从哪来"。 */
    public static final String PROPERTY_SOURCE_NAME = "dy-web-observability-defaults";

    public static final String EXPOSURE_INCLUDE_KEY = "management.endpoints.web.exposure.include";
    public static final String REDIS_HEALTH_KEY = "management.health.redis.enabled";

    /** 默认暴露的端点: 业务可读的 metrics + prometheus 抓取面 + 存活/就绪探针。 */
    private static final String DEFAULT_EXPOSED_ENDPOINTS = "health,info,metrics,prometheus";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(EXPOSURE_INCLUDE_KEY, DEFAULT_EXPOSED_ENDPOINTS);
        // Redis 健康指示器默认值随"Redis 是不是硬依赖"派生 (见类注释)
        defaults.put(REDIS_HEALTH_KEY, String.valueOf(isRedisIdempotencySelected(environment)));

        // addLast: 应用侧显式配置一律优先 (见类注释)
        environment.getPropertySources().addLast(
                new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * 幂等后端是否显式选了 Redis。
     *
     * <p>此处<b>不</b>引用 {@code IdempotencyConfiguration.REDIS} 常量, 而是内联字面量 ——
     * 这类环境后处理器在自动配置期极早运行, 保持"零类型依赖 + 常量字面量"可以让它在
     * 任何类加载顺序下都不会因为触发别的类初始化而出问题。为避免两处口径漂移,
     * 由 {@code IdempotencyConfigurationTest} 断言常量值与这里的字面量一致。
     */
    private static boolean isRedisIdempotencySelected(ConfigurableEnvironment environment) {
        String mode = environment.getProperty("dy.idempotency.backend", "memory");
        return "redis".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}