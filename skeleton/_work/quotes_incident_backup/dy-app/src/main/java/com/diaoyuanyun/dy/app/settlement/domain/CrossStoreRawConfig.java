package com.diaoyuanyun.dy.app.settlement.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨店通兑域口径的<b>原始声明值</b> —— 从配置真相源读出、<b>尚未解析</b>的两段字面量。
 *
 * <h2>为什么必须与 {@code CrossStoreThresholds} 分开（而不是直接返回已解析对象）</h2>
 * 与 {@code RefundRawConfig} / {@code DerivedRawConfig} / {@code ScaleRawConfig} 同一条理由：
 * 把"取值"与"解析 + 自洽校验"分成两件事，使<b>解析与校验只有唯一实现</b>
 * （{@link CrossStoreThresholds#fromRawConfig}）。若每个来源实现各解析一遍，就各漏一个校验 ——
 * 而漏掉的那个校验正是"配置被改坏了却静默按新值执行"的口子。
 *
 * <p>在本域，这个口子的后果是具体的：{@code #21} 决定<b>钱怎么在门店之间分</b>。
 * 若 {@code 0.30} 被改成 {@code 3.0}（多打一个零、或把"30%"误写成"3.0"），
 * 严格校验会当场拒绝；而"取不到就回落默认"会让拆分阈值悄悄退回 0.30，
 * 于是"总部以为改成了 40%、实际仍按 30% 拆"——两个季度后对账才发现。
 *
 * <h2>🛑 本记录不含任何默认值</h2>
 * 字段为 {@code null} / 空白即代表"这一段没取到"，由
 * {@link CrossStoreThresholds#fromRawConfig} 判定为配置缺失并<b>抛出</b>，绝不回落。
 * 本域两段都直接决定金额归属，"取不到就用默认"会让一个无人复核的默认值进入结算。
 *
 * @param splitThreshold    config {@code #21} {@code cfg:crossstore.split_threshold}（DECIMAL 字面，如 {@code "0.30"}）
 * @param anomalyRuleJson   config {@code #22} {@code cfg:crossstore.anomaly_rule}（JSON：窗口 + 门店数阈值 + 跨店占比）
 */
public record CrossStoreRawConfig(String splitThreshold, String anomalyRuleJson) {

    /** 两段声明是否齐全（缺一即不可用于任何结算判定）。 */
    public boolean isComplete() {
        return notBlank(splitThreshold) && notBlank(anomalyRuleJson);
    }

    /**
     * 缺失的段名清单（用于报错点名）。
     *
     * <p>与 {@code RefundRawConfig#missingKeys} 同一条纪律：契约 P0-08「不得模糊报错」
     * 在配置侧同样成立 —— 运维看到「config #21 缺失」比看到「跨店口径缺失」能直接动手。
     */
    public List<String> missingKeys() {
        List<String> missing = new ArrayList<>();
        if (!notBlank(splitThreshold)) {
            missing.add("#21 cfg:crossstore.split_threshold");
        }
        if (!notBlank(anomalyRuleJson)) {
            missing.add("#22 cfg:crossstore.anomaly_rule");
        }
        return missing;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}