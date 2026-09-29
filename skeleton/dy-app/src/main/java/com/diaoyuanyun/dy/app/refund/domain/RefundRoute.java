package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 退款通路二分（PRD P0-14「通路二分」，字段 {@code refund.refund_route}）。
 *
 * <h2>它与"入口位置"无关 —— 这是最容易被读丢的一句</h2>
 * PRD 原文：「<b>入口位置变了，两条通路的划分不变</b>」。
 * v1.9 把退款入口从客户端撤到门店，<b>没有</b>改变通路的划分：
 * 履约类（未交付 / 错交付 / 停交付）走规则直退，效果类（改善不明显 / 与购买前期待不符）
 * 走协商工单。把"入口在客户端 = 效果类"读成通路定义，会让本次改造把通路二分重构掉。
 *
 * <h2>{@link #configKey()} 为什么必须存在</h2>
 * 「哪条通路走直退、哪条走协商」不是代码该决定的，而是 config {@code #10}
 * （{@code cfg:refund.gate_rules}）的 {@code routes} 段声明：
 * <pre>
 *   "routes":[{"route":"fulfillment","mode":"direct"},
 *             {"route":"effect","mode":"negotiation_workorder","human_in_loop":true}]
 * </pre>
 * 本枚举只提供"中文通路 ↔ 配置键"的映射，模式（direct / negotiation_workorder）
 * 一律从配置读（见 {@code RefundPolicy}）。🛑 若在代码里写死"履约类 = direct"，
 * 配置把 effect 改成 direct 时会<b>静默失效</b> —— 配置看着改了、行为没变，
 * 而"效果类不自动出结论"这条约束会静默消失（见本类 {@link #EFFECT} 注释）。
 */
public enum RefundRoute {

    /** 履约类：未交付 / 错交付 / 停交付 → 规则直退（可自动、无需人审）。 */
    FULFILLMENT("履约类", "fulfillment"),

    /**
     * 效果类：改善不明显 / 与购买前期待不符 → 协商工单。
     *
     * <p>🛑 <b>人在环、不可自动直出资格结论</b>。依据不是产品偏好，而是行业事实：
     * 全行业把退款锚在<b>履约状态</b>而非<b>效果</b>（附录 C.7）；业务方 2026-09-16 原话
     * 「经络师再根据客户得数据看是否同意退款事宜」。故本通路下"自动出结论"属违规，
     * 由 {@code RefundPolicy} 在配置解析期机械堵口（{@code effect.mode} 必须为
     * {@code negotiation_workorder} 且 {@code human_in_loop=true}）。
     */
    EFFECT("效果类", "effect");

    private final String code;
    private final String configKey;

    RefundRoute(String code, String configKey) {
        this.code = code;
        this.configKey = configKey;
    }

    /** 持久化 / 出站字面（契约与库两处逐字一致：{@code 履约类 | 效果类}）。 */
    public String code() {
        return code;
    }

    /** config {@code #10} {@code routes[].route} 的键（{@code fulfillment | effect}）。 */
    public String configKey() {
        return configKey;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RefundRoute::code).toList();
    }

    public static RefundRoute parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "退款通路（refund_route）必填 —— 缺失即无法判定「走规则直退还是协商工单」");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(r -> r.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "退款通路不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }

    public static RefundRoute ofConfigKey(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #10 routes 段存在空 route 键 —— 通路模式无从匹配");
        }
        String k = configKey.trim();
        return Arrays.stream(values())
                .filter(r -> r.configKey.equals(k))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 routes 段含未知通路键: '" + configKey + "'"
                                + "（已知键: " + Arrays.stream(values()).map(RefundRoute::configKey).toList() + "）"));
    }

    /**
     * 本通路是否要求"人在环"（不得自动直出资格结论）。
     *
     * <p>⚠️ 本方法<b>不是</b>模式真相源 —— 真相源是 config {@code #10} 的
     * {@code mode} / {@code human_in_loop}。本方法只表达"该通路在需求语义上是否必然人在环"，
     * 供测试断言配置未被改坏（若 {@code #10} 把 effect 写成 direct，
     * 断言会说"配置与需求语义冲突"，而不是让引擎静默按 direct 执行）。
     */
    public boolean requiresHumanInLoop() {
        return this == EFFECT;
    }
}