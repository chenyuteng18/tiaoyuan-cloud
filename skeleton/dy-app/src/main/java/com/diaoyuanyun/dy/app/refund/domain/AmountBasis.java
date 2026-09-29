package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 退款让步金额的核定依据（库 CHECK {@code amount_basis} 3 值）。
 *
 * <h2>它为什么与"退款三层结构"的关系最容易被搞混</h2>
 * PRD P0-14 的第三层是「<b>让步金额 = 按已消耗次数的服务成本分段阈值上收</b>」，
 * 而配置 {@code #29}（{@code cfg:refund.concession_approval_threshold}）逐字写着
 * 「按已消耗次数对应的服务成本分段（<b>非按售价</b>）」。
 * 于是"金额是怎么定下来的"必须留痕，且三种依据的<b>审批路径不同</b>：
 * <ul>
 *   <li>{@link #AGREEMENT}（协议）：有在先约定，金额不可争议，属证据完备态；</li>
 *   <li>{@link #OWNER_JUDGEMENT}（负责人判定）：单方判定，须留判定人；</li>
 *   <li>{@link #NEGOTIATION}（双方协商）：效果类通路的常态，须留协商记录。</li>
 * </ul>
 *
 * <p>🛑 与 {@link RefundRoute#FULFILLMENT} 的关系要写清：履约类走<b>规则直退</b>
 * （{@code remaining_course_count * unit_price_paid}，配置 {@code #38}），
 * 其金额由公式算出、<b>不</b>经让步审批 —— 此时 {@code amount_basis} 应留空或标
 * {@link #AGREEMENT}（若合同有在先约定的计价方式）。把规则直退的金额标成
 * {@link #NEGOTIATION} 会让它看起来像一次协商让步，进而被"超阈值上收总部"拦下 ——
 * 而履约类直退<b>本就不该被阈值拦</b>（阈值只管让步、不管资格，见 P0-14 三层结构）。
 */
public enum AmountBasis {

    /** 协议（合同 / 协议书中的在先约定）。 */
    AGREEMENT("协议"),

    /** 负责人判定（单方判定，须留判定人身份）。 */
    OWNER_JUDGEMENT("负责人判定"),

    /** 双方协商（效果类通路的常态）。 */
    NEGOTIATION("双方协商");

    private final String code;

    AmountBasis(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(AmountBasis::code).toList();
    }

    public static AmountBasis parse(String code) {
        if (code == null || code.isBlank()) {
            return null;   // 库列可空，且规则直退场景下确实无须依据
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(b -> b.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "让步金额核定依据不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }

    /** 是否为"须留协商记录"的依据（效果类协商工单的常态）。 */
    public boolean requiresNegotiationRecord() {
        return this == NEGOTIATION;
    }
}