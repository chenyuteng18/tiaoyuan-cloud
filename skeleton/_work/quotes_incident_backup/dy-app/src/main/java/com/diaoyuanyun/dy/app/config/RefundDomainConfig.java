package com.diaoyuanyun.dy.app.config;

import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundRecordingPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.domain.RetentionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 退款域装配（S2-4）—— 把七段口径的<b>解析与自洽校验</b>做成启动期一次，
 * 而不是每次接单都解析一遍。
 *
 * <h2>🛑 为什么是"启动期解析"，而不是懒加载</h2>
 * 与 {@code DerivedEngineConfig} 同一条纪律，但本域的后果更重：
 * {@code RefundRawConfig} 的七段里有五段直接决定"客户能不能退、退多少、谁看得见"。
 * 其中三段的自洽校验会在<b>有人把配置改坏时</b>抛：
 * <pre>
 *   #10 effect 通路被改成 direct        → 效果类开始自动出结论（本不该）
 *   #38 effect_judgement_allowed=true  → 履约类直退开始吃效果判断（行业刻意避开）
 *   #40 客户可见性被改成 true           → 客户看得见退款字样（§2.2 定值冻结）
 * </pre>
 * 懒加载会让这三种坏配置在<b>第一次有人提出退款诉求时</b>才炸 ——
 * 而那一刻门店正对着一个真实客户。启动期解析则让应用<b>起不来</b>：
 * 起不来是安全的，带着坏口径跑起来是不安全的。
 *
 * <h2>🛑 四个 Bean 共享同一份 {@link RefundPolicy} 与同一份矩阵</h2>
 * <pre>
 *   RefundPolicy            ← #10 / #26 / #27 / #28 / #29 / #38 六段归一
 *   RefundVisibilityMatrix  ← #40 单独归一（它有独立的硬锁与键集校验）
 *   RefundRecordingPolicy   ← 消费 RefundPolicy（24h 窗口 = #10 的 record_within_hours）
 *   RetentionPolicy         ← 消费 RefundPolicy（挽留必要性 = #10 双入口；审批落点 = #29 行为）
 * </pre>
 * 共享的意义：{@code RefundPolicy.fromRawConfig} 在构造期已把跨段约束断言过一遍
 * （如 24h 常量必须与 {@code #10} 声明同值）。若让每个策略各自解析一份，
 * 就会出现"四份口径各自自洽、合起来不一致"的形态 —— 而那正是配置分叉最难查的一种：
 * 没有一处报错，只有两个模块对同一个客户给出不同答案。
 *
 * <h2>🛑 矩阵为什么用 {@code #40} 的原始 JSON 单独构造，而不从 RefundPolicy 取</h2>
 * 两者在 {@code RefundRawConfig} 里是<b>分开的两段</b>（{@code #40} 与其他六段），
 * 且矩阵有自己的一整套硬锁（客户 / 调理师 / 门店客服恒不可见）。
 * 从 {@code RefundPolicy} 里"顺手带出来"会让矩阵的构造期校验被跳过 ——
 * 而它的校验恰恰是"客户看不见退款字样"这条定值冻结的机械落点。
 */
@Configuration
public class RefundDomainConfig {

    private static final Logger log = LoggerFactory.getLogger(RefundDomainConfig.class);

    /**
     * 退款口径 —— 唯一实例，由三个策略共享。
     *
     * <p>解析失败即抛，应用起不来。日志只记来源与自描述（<b>不含取值</b>）：
     * 取值里含可见性矩阵与直退公式的构成，属内部口径；
     * 整段进日志会让"日志泄漏内部规则"成为一条难以撤销的事实。
     */
    @Bean
    public RefundPolicy refundPolicy(RefundProfileSource source) {
        RefundPolicy policy = RefundPolicy.fromRawConfig(source.raw());
        log.info("退款口径已装载: source={} policy={}", source.describeSource(), policy);
        return policy;
    }

    /**
     * 退款可见性矩阵 —— 从 {@code #40} 的原始声明值构造。
     *
     * <p>🛑 构造期即做完三件事（见 {@code RefundVisibilityMatrix.fromConfigJson}）：
     * 七键缺一即抛 / 硬锁被破即抛 / 含未登记角色键即抛。
     * 故本 Bean 的存在本身即是"客户看不见退款字样"的证据之一 ——
     * 若配置把 {@code customer} 改成 {@code true}，应用<b>起不来</b>。
     */
    @Bean
    public RefundVisibilityMatrix refundVisibilityMatrix(RefundProfileSource source) {
        RefundVisibilityMatrix matrix =
                RefundVisibilityMatrix.fromConfigJson(source.raw().visibilityJson());
        log.info("退款可见性矩阵已装载: source={} 可见角色数={} 可审批角色数={} 可稽核角色数={}",
                source.describeSource(),
                countTrue(matrix.visible()),
                matrix.approvers().size(),
                matrix.auditors().size());
        return matrix;
    }

    /** 24h 代录纪律策略（消费 {@link RefundPolicy}：窗口 = {@code #10} 的 record_within_hours）。 */
    @Bean
    public RefundRecordingPolicy refundRecordingPolicy(RefundPolicy policy) {
        return RefundRecordingPolicy.of(policy);
    }

    /** 挽留与审批策略（消费 {@link RefundPolicy}：必要性 = 双入口；落点 = {@code #29} 行为）。 */
    @Bean
    public RetentionPolicy retentionPolicy(RefundPolicy policy) {
        return RetentionPolicy.of(policy);
    }

    /** 统计 map 里为 {@code true} 的项数（<b>只进日志</b>，不参与任何判定）。 */
    private static long countTrue(java.util.Map<?, Boolean> m) {
        return m.values().stream().filter(Boolean.TRUE::equals).count();
    }
}