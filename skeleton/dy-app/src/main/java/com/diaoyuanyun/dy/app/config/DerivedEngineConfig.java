package com.diaoyuanyun.dy.app.config;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.DerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 派生引擎装配（S1-5）—— 把"口径归一化"这件事做成<b>启动期一次</b>，
 * 而不是每次计算都解析一遍配置声明。
 *
 * <h2>为什么在启动期就要解析（而不是懒加载）</h2>
 * 口径是从 classpath 的配置声明文件解析出来的（见 {@code ConfigSeedDerivedProfileSource}）。
 * 若做成懒加载，那么"配置声明被改坏"这件事会在<b>第一次有人算 AS 时</b>才炸 ——
 * 而那一刻可能正在处理一个真实客户的效果判定。
 * 启动期解析则让"口径不自洽"在<b>应用起不来</b>时暴露：起不来是安全的，
 * 带着坏口径跑起来是不安全的。这与 {@code IdempotencyConfiguration}
 * 「配错了要在启动时暴露，而不是在第一个需要幂等的请求上」是同一条纪律。
 *
 * <h2>🛑 三个引擎共享同一份 {@link DerivedMetricProfile}</h2>
 * 依从性（AS）、效果判定（MCID）、置信度（合成式）三项口径<b>交叉约束</b>：
 * {@code #33} 的 Δ 必须等于 {@code #45} 的谷底、{@code #7} 的样本护栏必须等于
 * {@code #45} 的 n 门槛。{@code DerivedMetricProfile.fromRawConfig} 在构造期已把这些
 * 交叉约束断言过一遍；共享同一份剖面意味着<b>那套断言对三个引擎同时生效</b>。
 * 若让每个引擎各自解析一份，就会出现"三份剖面各自自洽、合起来不一致"的形态。
 */
@Configuration
public class DerivedEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(DerivedEngineConfig.class);

    /**
     * 口径剖面 —— 唯一实例，由三个引擎共享。
     *
     * <p>解析失败即抛，应用起不来。日志记录来源与<b>不含取值的</b>摘要，
     * 使"这份剖面是从哪来的"在任何一次事故复盘里都可查。
     */
    @Bean
    public DerivedMetricProfile derivedMetricProfile(DerivedProfileSource source) {
        DerivedMetricProfile profile = DerivedMetricProfile.fromRawConfig(source.raw());
        log.info("派生口径已装载: source={} profile={}", source.describeSource(), profile.source());
        return profile;
    }

    @Bean
    public AdherenceEngine adherenceEngine(DerivedMetricProfile profile) {
        return new AdherenceEngine(profile);
    }

    @Bean
    public EffectVerdictEngine effectVerdictEngine(DerivedMetricProfile profile) {
        return new EffectVerdictEngine(profile);
    }

    @Bean
    public VerdictConfidenceEngine verdictConfidenceEngine(DerivedMetricProfile profile) {
        return new VerdictConfidenceEngine(profile);
    }

    /**
     * 判定分支路由引擎（PRD §7.3 决策表 D1~D5）。
     *
     * <h2>🛑 它<b>不</b>接收 {@link DerivedMetricProfile}</h2>
     * 与上面三个引擎不同：路由只消费 {@code AdherenceState}（已由 {@link AdherenceEngine}
     * 用配置门槛判好的状态），<b>不自己比较任何数值</b>。
     * 若把剖面注进来，就会出现"门槛的第二个消费点" ——
     * 而两个消费点在某次配置变更后必然分叉（一处改了、另一处没改，且不报错）。
     * 这一点由 {@code VerdictBranchEngine.refundThresholdNotHeldHere()} 显式拒绝兜底。
     */
    @Bean
    public VerdictBranchEngine verdictBranchEngine() {
        return new VerdictBranchEngine();
    }

    /**
     * 判定链服务 —— 契约域 F1/F2 的唯一业务编排点。
     *
     * <p>🛑 它注入的是 {@code VerdictPort}（端口）而非 {@code VerdictLedger}（实现）——
     * DIP：服务层不该知道"判定链存在 JDBC 里"。这也让
     * "判定链能不能被置换存储"成为一个可回答的问题，而不是一次源码搜索。
     *
* <p>🛑 它同时注入三个引擎 + 路由引擎，共 <b>4</b> 个协作者：
 * 每个引擎各自产出一件事实（效果候选 / 依从状态 / 置信度）加一次路由。
 * 服务层<b>不</b>内联任何一项算法 —— 算法与编排分离，
 * 使"算法有没有被改"可被单测独立断言（那是三个引擎各自测试套件的职责）。
 *
 * <p>🛑 <b>S2-8</b>：它额外注入 {@link DerivedMetricProfile}，用于在构造期算出
 * {@code threshold_version} 的内容寻址指纹（见 {@code ThresholdVersionFingerprint}）。
 * 注入的是<b>已归一化的剖面</b>而非 {@code DerivedProfileSource}：
 * 指纹必须由"用于计算的同一份口径"算出 —— 若另取一份原始声明重算，
 * 两份之间任何一次解析差异都会让"版本号说的口径"与"实际用的口径"分叉。
 */
@Bean
public com.diaoyuanyun.dy.app.derived.service.VerdictService verdictService(
        com.diaoyuanyun.dy.app.derived.domain.VerdictPort verdictPort,
        AdherenceEngine adherenceEngine,
        EffectVerdictEngine effectVerdictEngine,
        VerdictConfidenceEngine verdictConfidenceEngine,
        VerdictBranchEngine verdictBranchEngine,
        DerivedMetricProfile derivedMetricProfile) {
    return new com.diaoyuanyun.dy.app.derived.service.VerdictService(
            verdictPort, effectVerdictEngine, verdictConfidenceEngine,
            adherenceEngine, verdictBranchEngine, derivedMetricProfile);
}

/**
 * 判定依据回放器（S2-8 / ADR-11）—— 把 PRD §C.1.9 硬约束②的「可回放」
 * 从口号实现为一次真实的重算（见 {@code ThresholdVersionReplay} 类注释）。
 *
 * <p>它注入 {@code VerdictLedger}（<b>具体类</b>）而非 {@code VerdictPort}（端口）——
 * 这是本项目里唯一一处刻意"依赖实现而非端口"的装配，理由具体：
 * 它需要的是 {@code VerdictLedger.parseEffectOrNull / parseRiskOrNull} 三个
 * <b>{@code public static}</b> 纯函数（缺口 ② 的唯一防线），而那是仓储层的实现细节，
 * 不该被提升到端口上（端口描述的是"判定链能做什么"，不是"怎么解析枚举"）。
 * 抄一份到服务层会让那道防线出现第二个实现 —— 两个实现必然在某次改动后分叉。
 */
@Bean
public com.diaoyuanyun.dy.app.derived.service.ThresholdVersionReplay thresholdVersionReplay(
        com.diaoyuanyun.dy.app.derived.repository.VerdictLedger verdictLedger,
        VerdictBranchEngine verdictBranchEngine,
        DerivedMetricProfile derivedMetricProfile) {
    log.info("判定依据回放器已装配: threshold_version={}",
            com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint
                    .of(derivedMetricProfile).version());
    return new com.diaoyuanyun.dy.app.derived.service.ThresholdVersionReplay(
            verdictLedger, verdictBranchEngine, derivedMetricProfile);
}
}