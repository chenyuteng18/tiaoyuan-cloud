package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 退款原因码（PRD P0-14「原因分析：分结构化必填」；库 CHECK 6 值）。
 *
 * <h2>为什么"必填"必须在代码里是一条硬约束，而不只是表单校验</h2>
 * PRD 逐字：「<b>未经原因分析不可进入终止</b>；原因分类为结构化必填」。
 * 这条在库层已被 {@code NOT NULL + CHECK} 挡住，但库层只挡得住"没写原因"，
 * 挡不住"在服务层把原因码当成可省字段、写个 {@code '其他'} 兜底"。
 * 故本枚举<b>不提供"未知 / 其他"成员</b>：没有兜底项，就没有兜底路径 ——
 * 想省掉原因，只能改这里，那是一次可见的代码变更。
 *
 * <h2>6 值的语义不是标签，是路由依据</h2>
 * 排序即用途：
 * <ul>
 *   <li>{@link #EFFECT_BELOW_EXPECTATION} / {@link #SYMPTOM_WORSENED} —— 落在<b>效果类</b>通路，
 *       走协商工单、人在环。其中"症状加重或出现新不适"另有一条分支：
 *       PRD P0-14「<b>健康风险事件类不走挽留直接终止</b>」——
 *       它不是"客户不满意"，是<b>可能的安全事件</b>，走挽留流程等于劝客户继续接受一个
 *       可能有害的服务。</li>
 *   <li>{@link #SERVICE_EXPERIENCE} / {@link #TIME_COST_FAMILY} / {@link #LOW_ADHERENCE}
 *       / {@link #TRUST_OR_PRICE} —— 落在可协商区间，挽留方案（调整方案 / 补服务 / 分段让步）
 *       有实际着力点。</li>
 * </ul>
 *
 * <p>🛑 字面与库 CHECK（V5 §2.20）及契约 {@code RefundCreateRequest.reason_code} 枚举
 * <b>逐字一致</b>（含 {@code 时间·经济·家庭原因} 里的间隔点 {@code ·}，不是顿号、
 * 不是中点以外的任何字符）。三处任一处被"顺手改得更好看"，端侧逐字比对即红。
 */
public enum RefundReasonCode {

    EFFECT_BELOW_EXPECTATION("效果未达预期", false, false),
    SYMPTOM_WORSENED("症状加重或出现新不适", true, false),
    SERVICE_EXPERIENCE("服务体验或沟通问题", false, false),
    TIME_COST_FAMILY("时间·经济·家庭原因", false, false),
    LOW_ADHERENCE("配合度不足导致无明显变化", false, true),
    TRUST_OR_PRICE("信任或价格异议", false, false);

    private final String code;
    private final boolean healthRiskEvent;
    private final boolean blamesCustomer;

    RefundReasonCode(String code, boolean healthRiskEvent, boolean blamesCustomer) {
        this.code = code;
        this.healthRiskEvent = healthRiskEvent;
        this.blamesCustomer = blamesCustomer;
    }

    /** 结构化原因码字面（库 CHECK / 契约枚举逐字一致）。 */
    public String code() {
        return code;
    }

    /**
     * 是否属健康风险事件类 —— PRD P0-14「健康风险事件类不走挽留直接终止」。
     *
     * <p>🛑 这条若只写进注释不进代码，最可能的失效方式是"挽留流程对全部工单一视同仁"：
     * 客户刚说"做完反而更疼了"，系统弹出一张挽留话术表让他"再试两个疗程"。
     * 那不是流程瑕疵，是把安全事件当成销售机会。
     */
    public boolean isHealthRiskEvent() {
        return healthRiskEvent;
    }

    /**
     * 该原因码是否把归因指向客户（如"配合度不足"）。
     *
     * <p>存在理由是它会影响挽留话术与原因分析的措辞边界 ——
     * 把归因写成客户的错，在后续争议中会被当作"门店推责"的证据。
     * 本标记<b>不</b>用于自动生成任何对外文案（对外文案一律中性）。
     */
    public boolean isCustomerAttributed() {
        return blamesCustomer;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RefundReasonCode::code).toList();
    }

    public static RefundReasonCode parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "退款原因码（reason_code）必填 —— PRD P0-14：未经原因分析不可进入终止");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(r -> r.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "退款原因码不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }
}