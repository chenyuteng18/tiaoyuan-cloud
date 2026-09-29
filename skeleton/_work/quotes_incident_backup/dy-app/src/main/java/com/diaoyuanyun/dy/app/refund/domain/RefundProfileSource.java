package com.diaoyuanyun.dy.app.refund.domain;

/**
 * 退款域口径的<b>来源</b>抽象（端口）。
 *
 * <h2>它为什么存在（三条理由，与 S1-4 / S1-5 同源但后果更重）</h2>
 * <ol>
 *   <li><b>依赖方向</b>：{@code dy-app} 依赖 {@code dy-config}，技术上可直接注入配置服务。
 *       但配置真相源<b>还没有</b>落到应用库（{@code app_config} / {@code config_slot}
 *       当前只存在于 dy-config 的独立门禁库），落应用库属已登记的收尾令。
 *       若现在硬绑 DB 配置服务，退款域会因一条<b>无关的</b>收尾令而无法开工 ——
 *       而本域的验收要求是"口径外置、口径只有一个来源"，不是"配置已落库"。</li>
 *   <li><b>可断言</b>：测试可以注入"配置里把 effect 改成 direct"的实现，
 *       断言 {@link RefundPolicy#fromRawConfig} 立刻抛错 —— 这不必起数据库，
 *       也不必等一个真实客户来提出退款诉求才能验证"效果类不会自动出结论"。</li>
 *   <li><b>可切换</b>：收尾令完成后换一个读 {@code app_config} 的实现，
 *       纪律 / 挽留 / 审批 / 可见性四块策略<b>零改动</b>。</li>
 * </ol>
 *
 * <h2>🛑 不得返回"默认口径"</h2>
 * {@link #raw()} 的失败语义是<b>抛</b>，不是返回兜底值。
 * 本域七段中，{@code #10}（通路模式）、{@code #38}（直退公式与"不得引入效果判断"）、
 * {@code #40}（可见性矩阵）三段的默认值都会直接改变面客行为：
 * 一个"看起来合理"的默认可见性矩阵，会让"客户看得见退款字样"这件事
 * 以配置缺省的形式静默发生 —— 而那正是 PRD §2.2 用「两项定值冻结」要锁死的东西。
 *
 * <p>故 {@link #raw()} 取不到任何一段时一律抛
 * {@link com.diaoyuanyun.dy.common.result.ErrorCode#BUSINESS_RULE_VIOLATED}，
 * 并在消息里点名缺失段（{@link RefundRawConfig#missingKeys()}）。
 *
 * <p>本接口放在 domain 包：它是一个<b>端口</b>（不含实现依赖），
 * 使 service 依赖 domain，而不是 service 依赖 service 自身。
 */
public interface RefundProfileSource {

    /**
     * 返回退款域口径的七段原始声明值（未解析）。
     *
     * @throws com.diaoyuanyun.dy.common.exception.BizException
     *         任一段缺失 / 来源断开时抛出（fail-closed，绝不返回默认值）
     */
    RefundRawConfig raw();

    /** 口径来源的自描述（进日志 / 证据，便于回溯"这次判定是按哪份口径做的"）。 */
    String describeSource();
}