package com.diaoyuanyun.dy.app.scale.domain;

/**
 * 评分口径的<b>来源</b>抽象（端口）—— 让"口径从配置来"这件事可被替换与断言。
 *
 * <h2>为什么需要一个接口，而不是直接注入配置服务</h2>
 * 三条具体理由，都不是形式主义：
 * <ol>
 *   <li><b>依赖方向</b>：{@code dy-app} 依赖 {@code dy-config}，故技术上可以直接注入。
 *       但配置真相源还<b>没有</b>落到应用库（{@code app_config} / {@code config_slot}
 *       目前只存在于 dy-config 的独立门禁库，落应用库属已登记的收尾令
 *       「配置真相源落库（{@code ConfigServiceImpl} 仍内存实现）」）。
 *       若现在就硬绑 DB 配置服务，S1-4 会因一个<b>无关的</b>收尾令而无法开工 ——
 *       而 S1-4 的验收只要求"口径外置为配置项"，不要求"配置已落库"。</li>
 *   <li><b>可断言</b>：接口只有一个方法，测试可以注入一个"配置里没有分档"的实现，
 *       直接断言引擎输出 {@code TBD}；也可以注入一个"配置里坏掉"的实现，
 *       断言它 fail-closed 抛错。这些断言不必起数据库。</li>
 *   <li><b>可切换</b>：收尾令完成后，只需换一个实现类（读 {@code cfg:scale.range_rule}），
 *       计分引擎与题库服务<b>零改动</b> —— 这正是"口径外置"要买的东西。</li>
 * </ol>
 *
 * <h2>🛑 它不得返回"默认口径"</h2>
 * {@link #rangeRuleJson()} 的失败语义是<b>抛</b>，不是返回兜底值。
 * 量程口径是被改判过的东西（v1.7：0–3 四级 → 0–4 五级），
 * 任何"取不到就用默认"的实现都会让"口径丢失"静默成"口径是旧的"。
 *
 * <p>本接口放在 domain 包：它是一个<b>端口</b>（不含任何实现依赖），
 * 使 service 依赖 domain、而不是 service 依赖 service 自身。
 */
public interface ScaleProfileSource {

    /**
     * 返回 config {@code #35}（{@code cfg:scale.range_rule}）的<b>原始 JSON 值</b>。
     *
     * <p>返回原始 JSON 而不是已解析对象，是为了让"解析与自洽校验"这一件事
     * 只有唯一实现（{@link ScaleScoringProfile#fromConfigJson(String)}），
     * 避免每个实现各解析一遍、各漏一个校验。
     *
     * @throws com.diaoyuanyun.dy.common.exception.BizException 口径缺失 / 非法时抛出（fail-closed，不返回默认值）
     */
    String rangeRuleJson();

    /** 口径来源的自描述（进日志/证据，便于回溯"这个分是按哪份口径算的"）。 */
    String describeSource();
}