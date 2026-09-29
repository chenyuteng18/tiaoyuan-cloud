package com.diaoyuanyun.dy.web.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性装配 (A7 DoD #1): 给所有 Meter 打上<b>公共标签</b>, 使多实例/多版本的数据可区分。
 *
 * <h2>这里必须有共同标签, 但绝不能有租户标签</h2>
 * 多实例部署下, 若指标不带任何实例标识, 抓到的数据就无法回答"是哪一个实例在抖"。
 * 故打上 {@code application} 与 {@code instance_id}。
 *
 * <p><b>但租户维度一律不得出现在这里</b>，理由见 {@link TenantTagPolicy} 类注释
 * （基数爆炸 + 跨租户信息泄漏面）。这条禁令由 {@code TenantTagGuardTest} 机械守住 ——
 * 本类之所以<b>没有</b>一个 {@code tenant} 标签, 不是疏忽, 而是被那条测试钉住的结论。
 *
 * <h2>instance_id 为什么取自应用名 + 端口, 而不是随机 UUID</h2>
 * 随机 UUID 每次重启都变, 指标时间序列会不断累积新实例（同样是一种基数增长），
 * 且"上次是哪个实例"完全不可追。用"应用名 + 端口"在同一部署形态下稳定、可读,
 * 又能区分同机不同端口的实例。真正的实例唯一性交给采集侧（JVM 已有 process 指标）。
 */
@Configuration
public class ObservabilityConfiguration {

    /**
     * 公共标签。{@code application} 由 {@code spring.application.name} 提供, 缺省值
     * {@code diaoyuanyun} 使单元/切片测试无需完整环境也能装配。
     *
     * <p>{@code instance_id} 的默认值用 {@code local}：在测试里 {@code server.port} 不存在,
     * 若写成空串会产生一个"值为空的标签"（在时序库里表现为一个无意义的维度值）。
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> dyCommonTags(
            @Value("${spring.application.name:diaoyuanyun}") String applicationName,
            @Value("${server.port:0}") int port) {
        String instanceId = applicationName + ":" + (port > 0 ? port : "local");
        return registry -> registry.config().commonTags("application", applicationName, "instance_id", instanceId);
    }
}