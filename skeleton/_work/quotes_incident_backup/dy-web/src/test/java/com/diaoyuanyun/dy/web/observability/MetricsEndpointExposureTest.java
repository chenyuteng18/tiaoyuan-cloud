package com.diaoyuanyun.dy.web.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 DoD #1 的<b>装配层</b>取证: metrics 端点真的会被暴露吗。
 *
 * <h2>为什么这一条不能靠"pom 里加了 actuator"来声称</h2>
 * 引入 actuator + prometheus registry 只保证 {@link MeterRegistry} 被装配、指标在采集。
 * 但 Spring Boot <b>默认只暴露 {@code health}</b> 端点, 于是 {@code /actuator/prometheus}
 * 返回 404 —— 构建绿、启动绿、日志绿, 而"可采集"这件事在<b>可采集</b>这一步就是不成立的。
 * 这是一种典型的"配置存在但功能不存在"沉默失效, 只能靠断言端点暴露清单来排除。
 *
 * <h2>本类能证明什么 / 不能证明什么</h2>
 * <ul>
 *   <li>能证明: 默认暴露清单<b>包含</b> {@code prometheus} 与 {@code metrics};
 *       且应用侧显式配置时<b>可以覆盖</b>（库不劫持应用配置）。</li>
 *   <li>能证明: {@link PrometheusMeterRegistry} 在当前依赖组合下<b>真的能被实例化</b>,
 *       且能把指标渲染成 Prometheus 文本格式（这是"抓取"的实质内容, 不是端点名）。</li>
 *   <li><b>不能</b>证明: 一个真实启动的 HTTP 服务器上 {@code GET /actuator/prometheus}
 *       返回 200。那需要一个完整的 Spring Boot 上下文 + 内嵌容器, 越过 dy-web 模块边界,
 *       且会与其它并行构建抢资源。已在交付报告"未验证部分"逐条登记。</li>
 * </ul>
 */
class MetricsEndpointExposureTest {

    @Test
    void default_exposure_list_includes_metrics_and_prometheus() {
        ConfigurableEnvironment env = new StandardEnvironment();
        // 模拟真实的"环境准备完成"时点（应用还没加自己的配置）
        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        String exposed = env.getProperty("management.endpoints.web.exposure.include");
        assertTrue(exposed != null && exposed.contains("prometheus"),
                "默认暴露清单必须含 prometheus, 否则 /actuator/prometheus 404 而构建全绿。实际: " + exposed);
        assertTrue(exposed.contains("metrics"),
                "默认暴露清单必须含 metrics。实际: " + exposed);
        assertTrue(exposed.contains("health"),
                "存活/就绪探针不得因为引入 metrics 而被挤掉。实际: " + exposed);
    }

    /**
     * 库不得劫持应用配置: 应用侧显式写了暴露清单时, 必须以应用侧的为准。
     *
     * <p>这条是 {@code addLast}（而非 {@code addFirst}）的行为证明。若有人把
     * {@code addLast} 改成 {@code addFirst}, 应用侧的生产配置会被静默架空 ——
     * 那是一个很难排查的形态（配置明明写了, 就是不生效）。
     */
    @Test
    void application_side_configuration_overrides_the_default_instead_of_being_hijacked() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "application.yml(simulated)",
                Map.of("management.endpoints.web.exposure.include", "health")));

        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        assertEquals("health", env.getProperty("management.endpoints.web.exposure.include"),
                "应用侧显式配置必须优先 —— 库的默认值只能兜底, 不能覆盖应用");
    }

    /** 默认值来源必须可辨识（出现在 property source 名里）, 便于 --debug 时定位"这个值从哪来"。 */
    @Test
    void default_property_source_is_named_and_identifiable() {
        ConfigurableEnvironment env = new StandardEnvironment();
        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        assertTrue(env.getPropertySources().contains(ObservabilityDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME),
                "属性源必须挂上可辨识的名字, 实际来源: " + env.getPropertySources());
    }

    // ================================================================
    // ★ 跨模块副作用（A3 引入 redis 依赖 -> actuator 自动注册 Redis 健康指示器）
    //   见 ObservabilityDefaultsEnvironmentPostProcessor 类注释的"★ 被本类顺带修掉"一节。
    // ================================================================

    /**
     * 默认（幂等走内存）: Redis 健康指示器必须<b>关闭</b>。
     *
     * <h2>为什么这条断言值得单独立一个用例</h2>
     * A3 引入 {@code spring-boot-starter-data-redis}（compile 作用域）之后,
     * {@code RedisConnectionFactory} 会进 dy-app 容器, actuator 的
     * {@code RedisHealthContributorAutoConfiguration} 随即注册一个 Redis 健康指示器。
     * 本机没有 Redis, 于是 {@code /actuator/health} 会从 {@code UP} 变成 {@code DOWN} ——
     * <b>没人动 dy-app, 它此前一直绿的端点却被改红了</b>。
     *
     * <p>而 Redis 在默认装配下是<b>可选</b>依赖（幂等走内存后端, 应用完全可用）。
     * 把可用实例标成不可用就是撒谎, 且会让编排层（K8s/自建 LB）把它摘掉 —— 一次
     * "库引入依赖"导致的静默可用性事故。故默认必须关。
     */
    @Test
    void redis_health_indicator_is_disabled_by_default_so_an_optional_dependency_cannot_take_down_health() {
        ConfigurableEnvironment env = new StandardEnvironment();
        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        assertEquals("false", env.getProperty(ObservabilityDefaultsEnvironmentPostProcessor.REDIS_HEALTH_KEY),
                "默认装配下 Redis 是可选依赖, 不得让它的健康检查把整个应用标 DOWN");
    }

    /**
     * 显式选了 {@code dy.idempotency.backend=redis} 时, Redis 成为<b>硬依赖</b>,
     * 健康指示器必须<b>打开</b>。
     *
     * <p>理由: 该模式下幂等存储不可达会让整条写入链路 fail-closed（见 {@code IdempotencyStore}）。
     * 一个连不上 Redis 的实例确实不可用, 不应该被负载均衡当成健康的。
     * 两个开关由同一处推导, 因此不可能互相矛盾。
     */
    @Test
    void redis_health_indicator_is_enabled_when_redis_is_the_selected_idempotency_backend() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "simulated-user-config",
                Map.of("dy.idempotency.backend", "redis")));

        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        assertEquals("true", env.getProperty(ObservabilityDefaultsEnvironmentPostProcessor.REDIS_HEALTH_KEY),
                "redis 是硬依赖时必须让健康检查报出来, 否则不可用实例会被当成健康实例");
    }

    /** 应用侧可以覆盖这两个默认值（库不劫持配置）。 */
    @Test
    void application_side_can_override_the_redis_health_default() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "simulated-user-config",
                Map.of(ObservabilityDefaultsEnvironmentPostProcessor.REDIS_HEALTH_KEY, "true")));

        new ObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, new org.springframework.boot.SpringApplication());

        assertEquals("true", env.getProperty(ObservabilityDefaultsEnvironmentPostProcessor.REDIS_HEALTH_KEY),
                "应用侧的显式配置必须优先");
    }

    /**
     * 防止口径漂移: 环境后处理器里内联了 {@code "redis"} 字面量（为了零类型依赖）,
     * 它必须与 {@code IdempotencyConfiguration.REDIS} 常量一致。
     */
    @Test
    void inlined_redis_literal_matches_the_idempotency_configuration_constant() {
        assertEquals(com.diaoyuanyun.dy.web.idempotent.IdempotencyConfiguration.REDIS, "redis",
                "两处口径必须一致, 否则会出现'选了 redis 但健康检查仍关闭'的静默不一致");
    }

    /**
     * 抓取面的<b>内容</b>: Prometheus registry 必须能真的把 Meter 渲染成文本格式。
     *
     * <p>这是"端点可用"里更有价值的那一半 —— 端点名对了但渲染失败（比如采集格式版本不兼容）
     * 同样会让抓取失败, 而那种失败在只看端点清单时是看不出来的。
     */
    @Test
    void prometheus_registry_renders_meters_into_the_scrape_text_format() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().commonTags("application", "diaoyuanyun", "instance_id", "diaoyuanyun:local");
            registry.counter("dy.probe.scrape", "kind", "unit-test").increment(3);

            String scrape = registry.scrape();

            assertTrue(scrape.contains("dy_probe_scrape"),
                    "抓取文本必须含该指标（Micrometer 会把点号规范化为下划线）。实际抓取文本:\n" + scrape);
            assertTrue(scrape.contains("# TYPE"), "抓取文本必须是 Prometheus 文本格式（含 # TYPE 元数据行）");
            assertTrue(scrape.contains("application=\"diaoyuanyun\""),
                    "公共标签必须出现在抓取文本里（多实例区分的前提）");
            assertTrue(scrape.contains("3.0") || scrape.contains("3"),
                    "计数值必须出现在抓取文本里。实际:\n" + scrape);
        } finally {
            registry.close();
        }
    }

    /**
     * 反向守护: 抓取文本里也不得有租户维度。
     *
     * <p>与 {@link TenantTagGuardTest} 的区别: 那条查的是 Meter 的标签集合（注册时）；
     * 这条查的是<b>渲染后的抓取文本</b>（暴露时）。两处都查, 是因为标签可能在渲染阶段
     * 被追加（例如某个自定义 registry 或格式化器注入维度）—— 只查注册时就会漏掉这一路。
     */
    @Test
    void scrape_text_contains_no_tenant_dimension() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().commonTags("application", "diaoyuanyun", "instance_id", "diaoyuanyun:local");
            registry.counter("dy.probe.tenantleak", "kind", "unit-test").increment();

            String scrape = registry.scrape();
            for (String forbidden : TenantTagPolicy.FORBIDDEN_TAG_KEYS) {
                assertTrue(!scrape.contains(forbidden + "="),
                        "抓取文本不得含租户维度标签 '" + forbidden + "'（基数爆炸 + 跨租户信息泄漏面）。实际:\n" + scrape);
            }
        } finally {
            registry.close();
        }
    }
}