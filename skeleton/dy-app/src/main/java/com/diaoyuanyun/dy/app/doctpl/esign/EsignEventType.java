package com.diaoyuanyun.dy.app.doctpl.esign;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 电子签回调的<b>事件枚举</b>（B-5）—— 契约 H1 三条已冻结形态之一。
 *
 * <h2>契约逐字</h2>
 * <pre>
 * ① 事件枚举 event_type ∈ {signed, rejected, expired}；
 *    signed        推进文书状态 → 放行 06 屏门禁（PLAN_APPROVED → AGREEMENT_SIGNED）；
 *    rejected / expired 保持未签、不推进门禁，仅登记事实。
 * </pre>
 *
 * <h2>🛑 本枚举只落"三值 + 是否推进门禁"，不落任何载荷结构</h2>
 * 契约 H1 的 {@code x-not-frozen} 明确列出：字段名 / 载荷结构 / 厂商签名算法 /
 * {@code provider} 取值域 / 投递语义 / 多租户反解的厂商透传字段名。
 * <b>未冻结的东西写进代码，等于用实现替厂商做了裁定</b>，将来厂商接口一到就要返工，
 * 而返工时的"最小改动"往往是就地打补丁 —— 那种补丁最容易留下绕过门禁的路径。
 *
 * <h2>🛑 三值里的两个"false"是安全属性，不是"暂不支持"</h2>
 * {@code rejected} / {@code expired} 的 {@link #advancesGate()} 返回 false，
 * 语义是<b>绝不推进门禁</b>。它的反面（例如"过期视同放弃、顺手推进"）看起来更"顺滑"，
 * 但会让一份<b>未签署</b>的协议把客户推进到已签状态 —— 那正是合规上最不能出的错。
 * 故这两个 false 由测试钉死，不随"体验优化"改动。
 */
public enum EsignEventType {

    /** 已签署 —— 唯一允许推进门禁的事件。 */
    SIGNED("signed", true),

    /** 已拒签 —— 保持未签，仅登记事实。 */
    REJECTED("rejected", false),

    /** 已过期 —— 保持未签，仅登记事实。 */
    EXPIRED("expired", false);

    private final String wireName;
    private final boolean advancesGate;

    EsignEventType(String wireName, boolean advancesGate) {
        this.wireName = wireName;
        this.advancesGate = advancesGate;
    }

    /** 契约里的小写字面量（投递报文中的取值）。 */
    public String wireName() {
        return wireName;
    }

    /** 该事件是否允许推进门禁（🛑 仅 {@link #SIGNED} 为 true）。 */
    public boolean advancesGate() {
        return advancesGate;
    }

    /** 严格解析；未知值一律抛出（fail-closed，不返回默认值）。 */
    public static EsignEventType parse(String raw) {
        if (raw != null) {
            for (EsignEventType t : values()) {
                if (t.wireName.equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED,
                "未知的电子签事件类型: '" + raw + "'（契约 H1 仅三值: signed / rejected / expired）"
                        + " —— fail-closed：未知事件不得被当作任何一种已登记事件处理");
    }

    /** 描述（供契约自述端点使用）。 */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (EsignEventType t : values()) {
            m.put(t.wireName, t.advancesGate ? "推进门禁（PLAN_APPROVED → AGREEMENT_SIGNED）"
                    : "保持未签，仅登记事实");
        }
        return m;
    }

    /** 全部取值（契约冻结的三值，供交叉断言使用）。 */
    public static Set<String> wireNames() {
        return Set.of(SIGNED.wireName, REJECTED.wireName, EXPIRED.wireName);
    }
}