package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 手环佩戴意愿 —— 契约域 B3/B4 的 {@code band_willingness}，逐字对齐 V5 {@code consent} 的 CHECK。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5 L201: band_willingness VARCHAR(16) NOT NULL CHECK (band_willingness IN ('自愿佩戴', '暂不佩戴'))
 *  data-dict §2.8: 自愿佩戴 / 暂不佩戴（决定 A3 applicable）
 *  契约 CustomerDetailData.band_willingness: enum [自愿佩戴, 暂不佩戴]
 *  V5 L200 与 §2.8 附注: 🛑 拒戴不得降级服务
 * </pre>
 *
 * <h2>🛑 「暂不佩戴」不是"拒绝服务"，也不是"数据缺失"</h2>
 * V5 该列注释与 data-dict §2.8 逐字写着「<b>拒戴不得降级服务</b>」，
 * 且 data-dict 附注其变更「<b>只能单向放宽扣分、不能收紧</b>」（brief §5.3）。
 * 这两条对本枚举的直接含义是：
 * <ul>
 *   <li>{@link #DECLINED} <b>不得</b>被读作"应当扣分"或"服务降级"的开关 ——
 *       它是一个<b>意愿事实</b>，扣分口径由依从性模型另行处理，且只能放宽；</li>
 *   <li>它<b>不影响</b>客户能否继续准入链路（B3 签同意书不因拒戴而 403）。</li>
 * </ul>
 *
 * <h2>🛑 {@code consent} 表整表 NOT NULL 对"未签同意书"的表达</h2>
 * 「未签同意书」在库层<b>没有</b> {@code band_willingness} 的容身之处 ——
 * 该列 NOT NULL 且隶属于 {@code consent} 行。即：<b>"有没有这一行"</b>才是
 * "签没签"的真相，而不是"该列取什么值"。故本枚举<b>刻意不提供</b>
 * {@code UNKNOWN} / {@code NOT_SIGNED} 之类的第三值 —— 加了它就会有人拿它表示
 * "还没签"，于是"签没签"变成两个来源（行存在性 vs 列取值），而两者必然分叉。
 */
public enum BandWillingness {

    /** 自愿佩戴。契约 / 库层字面 {@code 自愿佩戴}。 */
    WILLING("自愿佩戴", "自愿佩戴", true),

    /**
     * 暂不佩戴（拒戴）。
     *
     * <p>🛑 <b>拒戴不得降级服务</b>（V5 L200 / data-dict §2.8 / brief §5.3 逐字）。
     * 本值是意愿事实，不是服务档位，也不是判定扣分的开关。
     */
    DECLINED("暂不佩戴", "暂不佩戴", false);

    private final String code;
    private final String label;
    private final boolean wearing;

    BandWillingness(String code, String label, boolean wearing) {
        this.code = code;
        this.label = label;
        this.wearing = wearing;
    }

    /** 契约 / 库层字面（中文两值）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 意愿是否为"佩戴"。
     *
     * <p>⚠️ 调用方注意：它<b>只</b>表达意愿，<b>不得</b>用作"是否降级服务"
     * 或"是否扣分"的判据（见类注释）。它的合法用途是回答 A3 的
     * {@code applicable} 这一类"是否适用"的问题。
     */
    public boolean isWillingToWear() {
        return wearing;
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static BandWillingness of(String code) {
        for (BandWillingness w : values()) {
            if (w.code.equals(code)) {
                return w;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的佩戴意愿字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记: 自愿佩戴 / 暂不佩戴；权威来源 = V5 consent.band_willingness 的 CHECK）—— "
                        + "🛑 不得回落：回落会让一个拼错的意愿静默变成一次『自愿佩戴』，"
                        + "而该值决定手环数据是否进入 A3 计算口径");
    }
}