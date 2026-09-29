package com.diaoyuanyun.dy.app.derived.domain;

/**
 * 派生口径的<b>来源</b>抽象（端口）—— 让"依从性 / AS / 效果判定的口径从配置来"这件事
 * 可被替换与断言（S1-5 验收①「只在服务端计算」的配置侧一半）。
 *
 * <h2>为什么需要接口（三条具体理由，不是形式主义）</h2>
 * <ol>
 *   <li><b>依赖方向</b>：{@code dy-app} 依赖 {@code dy-config}，故技术上可直接注入配置服务。
 *       但配置真相源还<b>没有</b>落到应用库（{@code app_config} / {@code config_slot} 当前
 *       只存在于 dy-config 的独立门禁库），落应用库属已登记的收尾令。
 *       若现在硬绑 DB 配置服务，S1-5 会因一条<b>无关的</b>收尾令而无法开工 ——
 *       而 S1-5 的验收只要求"派生字段只在服务端计算、客户端一律 403、响应体不含"，
 *       不要求"配置已落库"。</li>
 *   <li><b>可断言</b>：接口只有一个方法，测试可以注入一个"配置里坏掉"的实现，
 *       断言引擎 fail-closed 抛错；也可以注入"某一段缺失"的实现，断言报错信息
 *       点名了缺失的段。这些断言不必起数据库。</li>
 *   <li><b>可切换</b>：收尾令完成后，只需换一个实现类（读 {@code app_config}），
 *       依从性引擎 / 判定引擎 / 置信度引擎<b>零改动</b>。</li>
 * </ol>
 *
 * <h2>🛑 它不得返回"默认口径"</h2>
 * {@link #raw()} 的失败语义是<b>抛</b>，不是返回兜底值。
 *
 * <p>本条对派生口径比对齐分口径<b>更硬</b>，理由是后果不同：
 * 分档口径丢失只会让严重度显示 {@code TBD}；而 AS 门槛（{@code #4}）或
 * MCID 门槛（{@code #33}）丢失若被兜底成某个"看起来合理"的数，
 * 会让一个未经校准的阈值直接进入<b>退款门禁</b>与<b>效果判定</b> ——
 * 即"客户能不能退款"这件事被一个没人复核过的默认数决定。
 *
 * <p>故 {@link #raw()} 取不到任何一段时一律抛
 * {@link com.diaoyuanyun.dy.common.result.ErrorCode#BUSINESS_RULE_VIOLATED}，
 * 并在消息里点名缺失的段（{@link DerivedRawConfig#missingKeys()}）。
 *
 * <p>本接口放在 domain 包：它是一个<b>端口</b>（不含任何实现依赖），
 * 使 service 依赖 domain、而不是 service 依赖 service 自身。
 */
public interface DerivedProfileSource {

    /**
     * 返回派生口径的六段原始声明值（未解析）。
     *
     * @throws com.diaoyuanyun.dy.common.exception.BizException 任一段缺失 / 来源断开时抛出（fail-closed，绝不返回默认值）
     */
    DerivedRawConfig raw();

    /** 口径来源的自描述（进日志/证据，便于回溯"这个 AS 是按哪份口径算的"）。 */
    String describeSource();
}