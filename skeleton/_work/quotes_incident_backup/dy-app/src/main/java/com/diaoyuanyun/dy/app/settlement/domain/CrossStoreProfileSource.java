package com.diaoyuanyun.dy.app.settlement.domain;

/**
 * 跨店通兑域口径的<b>来源</b>抽象（端口）。
 *
 * <h2>它为什么存在（与 {@code RefundProfileSource} / {@code BandVisibilitySource} 同源）</h2>
 * <ol>
 *   <li><b>依赖方向</b>：{@code dy-app} 依赖 {@code dy-config}，技术上可直接注入配置服务。
 *       但配置真相源（{@code app_config} / {@code config_slot}）当前只建在 dy-config 的
 *       独立门禁库 + 应用库（V14 已补），而<b>真正消费侧</b>仍走 {@code ConfigSeed*}
 *       过渡实现（它们的类头逐字写着"配置真相源落库后应替换为 DB 读取实现"）。</li>
 *   <li><b>可断言</b>：测试可以注入"配置里把阈值改成 0.40"的实现，
 *       断言 {@link CrossStoreThresholds#fromRawConfig} 立刻产出 0.40，
 *       并断言结算行为随之改变 —— 这不必起数据库，就能验证"总部可改"这句话<b>真的成立</b>。</li>
 *   <li><b>可切换</b>：收尾令完成后换一个读 {@code app_config} 的实现，
 *       结算核心（{@code CrossStoreSettlement}）与控制器<b>零改动</b>。</li>
 * </ol>
 *
 * <h2>🛑 不得返回"默认口径"</h2>
 * {@link #raw()} 的失败语义是<b>抛</b>，不是返回兜底值。
 * 本域两段都直接改变钱的分法：{@code #21}（拆分阈值）的默认值会让
 * 「总部唯一可改」退化成「总部改不了」—— 而那种退化不会有任何报错，
 * 只会让配置页面上改的数字长期不生效。故 {@link #raw()} 取不到任何一段时一律抛
 * {@link com.diaoyuanyun.dy.common.result.ErrorCode#BUSINESS_RULE_VIOLATED}，
 * 并在消息里点名缺失段（{@link CrossStoreRawConfig#missingKeys()}）。
 *
 * <p>本接口放在 domain 包：它是一个<b>端口</b>（不含实现依赖），
 * 使 service 依赖 domain，而不是 service 依赖 service 自身。
 */
public interface CrossStoreProfileSource {

    /**
     * 返回跨店通兑域口径的两段原始声明值（未解析）。
     *
     * @throws com.diaoyuanyun.dy.common.exception.BizException
     *         任一段缺失 / 来源断开时抛出（fail-closed，绝不返回默认值）
     */
    CrossStoreRawConfig raw();

    /** 口径来源的自描述（进日志 / 证据，便于回溯"这次结算是按哪份口径做的"）。 */
    String describeSource();
}