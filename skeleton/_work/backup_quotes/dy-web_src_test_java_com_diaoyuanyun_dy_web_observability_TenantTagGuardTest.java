package com.diaoyuanyun.dy.web.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 硬门禁: <b>任何指标都不得携带租户维度标签</b>。
 *
 * <h2>理由（本条写进测试, 因为它就是门禁的立法理由）</h2>
 * <ol>
 *   <li><b>基数爆炸</b>：时序库为每个标签组合维护一条独立时间序列。{@code tenant_id} 的取值
 *       随租户数增长, 指标条数 = 指标数 × 租户数。监控后端会因门店增长而过载 ——
 *       而症状是"监控自己挂了", 恰在最需要它的时候。</li>
 *   <li><b>跨租户信息泄漏面</b>：指标端点通常不带租户鉴权（抓取方是监控系统）。一旦
 *       {@code tenant_id} 成为标签, 任何能读 {@code /actuator/prometheus} 的主体都能枚举出
 *       全平台租户清单和租户粒度业务量。租户隔离必须在<b>所有</b>出平台上成立,
 *       不能因为"这是监控数据"就开例外。</li>
 * </ol>
 *
 * <h2>为什么自动发现的 Meter 不够, 还要补一个"带租户标签"的注入用例</h2>
 * 自动发现只能证明"<b>当前代码</b>恰好没写租户标签"。它证明不了门禁本身有效 ——
 * 如果断言写错（比如查的是 {@code tenantId} 却漏了 {@code tenant_id}）、或者
 * {@code getMeters()} 根本没被调用, 自动发现这一路仍然会绿。
 * 故 {@link #injected_tenant_tag_is_detected_red_then_green_after_removal()} 用
 * <b>同一条断言方法</b>跑一次注入态, 要求它<b>必须变红</b>: 这才证明门禁有牙齿。
 */
class TenantTagGuardTest {

    /** 三种大小写/分隔写法都查, 因为命名由调用方自由书写（见 TenantTagPolicy）。 */
    private static final String[] FORBIDDEN_VARIANTS = {"tenant_id", "tenantId", "tenant“};

    // ---------------------------------------------------------------- 正门禁: 真实注册表

    /**
     * 正门禁: 在<b>真实</b> registry 上建一批常见的 Meter, 然后断言无一携带租户标签。
     *
     * <p>用 {@link PrometheusMeterRegistry}（生产实际用的那类）而不是 simple registry,
     * 使”指标 → 暴露面“的链路尽量接近生产（Prometheus registry 会把标签名规范化, 这个转换
     * 有可能引入意料之外的标签, 必须被覆盖到）。
     *
     * <p><b>并且先套上生产的公共标签装配</b>（{@link ObservabilityConfiguration#dyCommonTags}）。
     * 这一点是关键: 公共标签会应用到<b>每一个</b> Meter 上, 因而也是”顺手加一个租户维度"
     * 最可能发生的位置。若本用例只建几个手工 Meter 而不走公共标签这条路,
     * 那处最可能出问题的注入点就完全没被覆盖 —— 会出现"门禁绿着, 但生产指标里已经带了租户标签“。
     */
    @Test
    void real_prometheus_registry_registers_no_tenant_tagged_meter() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            // 走生产的公共标签装配路径（注入最可能发生的地方）
            new ObservabilityConfiguration()
                    .dyCommonTags(”diaoyuanyun", 8080)
                    .customize(registry);

            // 建一批典型 Meter, 覆盖 counter / gauge / timer / distribution summary
            registry.counter("dy.http.requests", "method", "POST", "path", "/api/v1/order", "status", "200");
            registry.gauge("dy.band.connected", 1);
            registry.timer("dy.http.latency", "path", "/api/v1/order");
            registry.summary("dy.order.amount", "channel", "h5");

            List<TenantTagPolicy.Violation> violations = TenantTagPolicy.findViolations(registry);

            assertTrue(violations.isEmpty(),
                    "指标不得携带租户维度标签（基数爆炸 + 跨租户信息泄漏面）。违规项: " + violations);
            assertFalse(registry.getMeters().isEmpty(), "前置条件失败: 注册表里必须有 Meter 才谈得上门禁");

            // 前置: 公共标签确实生效（否则"无违规"可能是因为公共标签根本没打上, 属于假绿）
            assertTrue(registry.getMeters().stream()
                            .allMatch(m -> "diaoyuanyun".equals(m.getId().getTag("application"))),
                    "前置条件失败: 公共标签 application 必须已应用到所有 Meter, 否则本用例是假绿");
        } finally {
            registry.close();
        }
    }

    /** 空注册表也要跑一次: 保证"0 个 Meter"不会因为断言写法的边界而误判为通过。 */
    @Test
    void empty_registry_reports_no_violation_but_is_explicitly_a_degenerate_case() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            assertEquals(0, registry.getMeters().size());
            assertTrue(TenantTagPolicy.findViolations(registry).isEmpty(),
                    "空注册表: 无违规是正确结论, 但本用例只证明'不误报', 不构成门禁有效性的证据");
        } finally {
            registry.close();
        }
    }

    // ---------------------------------------------------------------- 反向验证（注入必须变红）

    /**
     * ★ 反向验证：<b>临时注册一个带 {@code tenant_id} 标签的 Meter, 确认门禁变红;
     * 移除后确认转绿。</b>
     *
     * <p>本用例把"红态"与"绿态"都固化成断言。只做红态（注入后断言违规非空）是不够的 ——
     * 那样一个"永远报违规"的坏门禁也能通过。两侧都断, 才能证明检测是<b>有区分力的</b>:
     * 有租户标签时报告, 无租户标签时不报告。
     *
     * <p>三种命名变体各跑一次。只测 {@code tenant_id} 会漏掉"有人写成 {@code tenantId}"
     * 的情形 —— 而 camelCase 恰恰是 Java 侧最可能被顺手写出来的形式。
     */
    @Test
    void injected_tenant_tag_is_detected_red_then_green_after_removal() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            for (String variant : FORBIDDEN_VARIANTS) {
                String meterName = "dy.injected.probe." + variant.replace("_", "").toLowerCase();

                // ---- 绿态: 注册一个【不带】租户标签的同名 Meter
                registry.counter(meterName, "channel", "h5");
                Meter cleanMeter = findMeter(registry, meterName);
                assertNotNull(cleanMeter, "前置条件失败: 未找到刚注册的 Meter " + meterName);
                assertTrue(TenantTagPolicy.findViolations(cleanMeter).isEmpty(),
                        "【绿态】不带租户标签的 Meter 必须判合规, 否则门禁是无区分力的常红门禁");

                // ---- 红态: 注入一个【带着】租户标签的 Meter
                Meter tagged = io.micrometer.core.instrument.Counter
                        .builder(meterName + ".tagged")
                        .tag(variant, "tenant-0001")
                        .register(registry);
                List<TenantTagPolicy.Violation> redViolations = TenantTagPolicy.findViolations(tagged);
                assertFalse(redViolations.isEmpty(),
                        "【红态】注入标签 '" + variant + "' 后门禁必须变红 —— 若这里没红, 说明门禁对 "
                                + variant + " 这种写法根本不生效（有牙齿的门禁才能拦住基数爆炸与租户泄漏）");
                assertEquals(variant, redViolations.get(0).tagKey(),
                        "违规项必须精确报出是哪个标签键, 否则无法定位到代码位置");

                // ---- 移除后转绿: 摘掉该 Meter, 同一断言方法回到空
                registry.remove(tagged);
                assertTrue(TenantTagPolicy.findViolations(registry).stream()
                                .noneMatch(v -> v.meterName().contains(variant.replace("_", "").toLowerCase())),
                        "移除后必须转绿 —— 红态若不可逆, 门禁就会变成一次注入即永久红灯");
            }
        } finally {
            registry.close();
        }
    }

    /**
     * 说明性用例: 门禁的<b>全量枚举面</b>必须覆盖"所有已注册 Meter", 而不只是某个前缀。
     * 用一个会污染全表的标签注入验证。
     */
    @Test
    void guard_scans_every_registered_meter_not_just_a_name_prefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            // 一堆无辜的 Meter
            for (int i = 0; i < 20; i++) {
                registry.counter("dy.business.metric." + i, "kind", "k" + i);
            }
            assertTrue(TenantTagPolicy.findViolations(registry).isEmpty(), "前置: 全部合规");

            // 只污染其中一个
            registry.counter("zzz.last.one", "tenant_id", "t-1");
            List<TenantTagPolicy.Violation> violations = TenantTagPolicy.findViolations(registry);

            assertEquals(1, violations.size(), "必须恰好抓到那 1 个违规 Meter, 实际: " + violations);
            assertEquals("zzz.last.one", violations.get(0).meterName());
        } finally {
            registry.close();
        }
    }

    // ---------------------------------------------------------------- 策略本身的边界

    @Test
    void policy_matches_case_insensitively_and_ignores_unrelated_keys() {
        for (String variant : FORBIDDEN_VARIANTS) {
            assertTrue(TenantTagPolicy.isForbidden(variant), variant + " 必须被禁");
        }
        assertTrue(TenantTagPolicy.isForbidden("TENANT_ID"), "标签名大小写不同不该改变结论");
        assertTrue(TenantTagPolicy.isForbidden("TenantId"), "标签名大小写不同不该改变结论");

        // 不能误伤: 这些是合法维度, 且与 tenant 仅表面相似
        for (String legit : new String[]{"application", "instance_id", "method", "path", "status", "channel“}) {
            assertFalse(TenantTagPolicy.isForbidden(legit), legit + ” 是合法维度, 不得误伤");
        }
        assertFalse(TenantTagPolicy.isForbidden(null), "null 键不得抛异常");
    }

    /**
     * 公共标签装配本身也不得引入租户维度: 这是最容易"顺手加上去"的位置
     * （因为装配点就在那里, 且加一个标签看起来很自然）。
     */
    @Test
    void common_tag_customizer_does_not_inject_tenant_dimension() {
        MeterRegistry registry = new SimpleMeterRegistry();
        try {
            new ObservabilityConfiguration()
                    .dyCommonTags("diaoyuanyun", 8080)
                    .customize(registry);
            registry.counter("dy.probe", "kind", "x");

            List<TenantTagPolicy.Violation> violations = TenantTagPolicy.findViolations(registry);
            assertTrue(violations.isEmpty(), "公共标签不得含租户维度, 违规: " + violations);

            // 正向: 公共标签确实打上了（否则"无违规"是因为标签根本没生效, 属于假绿）
            Meter probe = findMeter(registry, "dy.probe");
            assertNotNull(probe);
            assertTrue(probe.getId().getTags().stream().anyMatch(t -> "application".equals(t.getKey())),
                    "公共标签 application 必须真的存在, 否则本用例的'无违规'是无意义的假绿");
            assertTrue(probe.getId().getTag("instance_id") != null,
                    "instance_id 必须存在（多实例下才能定位是哪个实例）");
        } finally {
            registry.close();
        }
    }

    /** 装配期就拒绝一个 0/负端口导致的空标签值: 空值标签在时序库里是无意义的维度。 */
    @Test
    void instance_id_falls_back_to_local_when_port_is_absent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        try {
            new ObservabilityConfiguration().dyCommonTags("diaoyuanyun", 0).customize(registry);
            registry.counter("dy.probe2");
            Meter probe = findMeter(registry, "dy.probe2");
            assertNotNull(probe);
            assertEquals("diaoyuanyun:local", probe.getId().getTag("instance_id"));
        } finally {
            registry.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Meter findMeter(MeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(m -> name.equals(m.getId().getName()))
                .findFirst()
                .orElse(null);
    }

    /** 防御性: 被禁键集合非空, 否则所有断言都会平凡通过。 */
    @Test
    void forbidden_key_set_is_not_empty() {
        assertFalse(TenantTagPolicy.FORBIDDEN_TAG_KEYS.isEmpty(),
                "禁令集合为空 -> 所有断言平凡通过（门禁形同不存在）");
        assertEquals(3, TenantTagPolicy.FORBIDDEN_TAG_KEYS.size());
        assertThrows(UnsupportedOperationException.class,
                () -> TenantTagPolicy.FORBIDDEN_TAG_KEYS.add("x"),
                "禁令集合必须不可变, 否则调用方能在运行期把它清空");
    }
}