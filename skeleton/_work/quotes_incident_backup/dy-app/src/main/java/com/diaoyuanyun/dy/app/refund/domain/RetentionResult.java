package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 挽留结果（PRD P0-14「挽留 → 成功继续 / 失败终止」；库 CHECK 3 值）。
 *
 * <h2>三值为什么不是"成功 / 失败"两值加一个备注</h2>
 * 库 CHECK 落的是 {@code 接受继续服务 | 接受但需调整 | 不接受进入退款终止}。
 * 中间那一值（{@link #ACCEPT_WITH_ADJUSTMENT}）是<b>业务上最常发生</b>的结果：
 * 客户不是要退钱，是要改方案（频次 / 手法 / 项目组合）。
 * 把它折进"接受继续服务"，会让"挽留后方案被改动"这件事在数据里不可见 ——
 * 而它恰恰是评估挽留质量的关键：一个只会说"继续吧"的门店，
 * 和一个能把方案改到客户愿意继续的门店，报表上会完全一样。
 *
 * <h2>🛑 挽留的适用边界有两条硬约束（不在本枚举内，但由本枚举的取值触发）</h2>
 * <ol>
 *   <li><b>入口 B 不经挽留</b>（首周期双不达标 → 主动终止退款）。向一个"我们已判定
 *       服务无效"的客户做挽留，语义上自相矛盾。</li>
 *   <li><b>健康风险事件类不走挽留直接终止</b>（见
 *       {@link RefundReasonCode#isHealthRiskEvent()}）。</li>
 * </ol>
 * 两条都由 {@code RetentionPolicy} 机械判定，不依赖调用方自觉。
 */
public enum RetentionResult {

    /** 接受继续服务（原方案继续）。 */
    ACCEPT_CONTINUE("接受继续服务"),

    /** 接受但需调整（改频次 / 手法 / 项目组合后继续）—— 挽留质量的关键观测点。 */
    ACCEPT_WITH_ADJUSTMENT("接受但需调整"),

    /** 不接受进入退款终止（挽留失败 → 出口集中，须总部审批）。 */
    REJECT_ENTER_TERMINATION("不接受进入退款终止");

    private final String code;

    RetentionResult(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RetentionResult::code).toList();
    }

    public static RetentionResult parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "挽留结果（retention.result）必填 —— 挽留记录不得只留沟通痕迹而不留结论");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(r -> r.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "挽留结果不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }

    /** 挽留是否成功（客户留在服务关系内，无论是否调整方案）。 */
    public boolean isSuccess() {
        return this != REJECT_ENTER_TERMINATION;
    }

    /** 是否须转入终止流程（并由此触发"出口集中 · 总部审批"）。 */
    public boolean requiresTermination() {
        return this == REJECT_ENTER_TERMINATION;
    }
}