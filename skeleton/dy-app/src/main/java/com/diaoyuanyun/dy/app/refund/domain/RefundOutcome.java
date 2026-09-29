package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 退款工单结局（PRD P0-14「四种结局全部强制归档」；库 CHECK 3 值 + 归档态）。
 *
 * <h2>3 值 + 一句"全部强制归档"的落差，是本次落地最需要解释的一处</h2>
 * PRD 写的是「<b>四种结局</b>全部强制归档」，而库 CHECK 只落 3 值
 * （{@code 继续 | 终止 | 归档}）。这不是漏了一个值，而是两句话在说两件事：
 * <ul>
 *   <li>{@code 继续} / {@code 终止} 是<b>工单的业务结局</b>（挽留成功继续服务 / 进入退款终止）；</li>
 *   <li>{@code 归档} 是<b>结案状态</b>（{@code case_archive} 落地、归档后只读）。</li>
 * </ul>
 * "四种结局全部强制归档"里的"四种"指的是业务结局的四种形态
 * （继续 / 终止-退款 / 终止-非退款 / 健康风险直终止），它们<b>都要</b>走一次归档动作；
 * 而"归档"本身是那四次动作共同写入的状态。故 {@link #ARCHIVED} 不是第四种业务结局，
 * 是"已走完归档"的状态位。
 *
 * <p>🛑 由此推出一条硬约束：<b>不存在"停在继续或终止、永不归档"的合法工单</b>。
 * 若服务层允许 {@code outcome} 在 {@code 继续/终止} 上长期停留而不推进归档，
 * 「全部强制归档」就退化成一句没有排期的话。可执行形态见
 * {@link #requiresArchive()} —— 它恒为 {@code false} 只对 {@link #ARCHIVED} 成立，
 * 即另外两值都是"未结案"，必须由归档动作收口。
 */
public enum RefundOutcome {

    /** 继续（挽留成功，客户接受继续服务或调整后继续）。 */
    CONTINUE("继续"),

    /** 终止（进入退款终止，出口集中：须总部审批）。 */
    TERMINATE("终止"),

    /** 归档（已结案，归档后只读；不是第四种业务结局，是状态位）。 */
    ARCHIVED("归档");

    private final String code;

    RefundOutcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RefundOutcome::code).toList();
    }

    public static RefundOutcome parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "退款工单结局（outcome）必填");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(o -> o.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "退款工单结局不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }

    /**
     * 本结局是否仍属"未结案"（必须被归档动作收口）。
     *
     * <p>PRD P0-14「四种结局全部强制归档」的可执行形态：只有 {@link #ARCHIVED}
     * 不是未结案。服务层据此判定"该工单是否还能被再次改动"。
     */
    public boolean isOpen() {
        return this != ARCHIVED;
    }

    /** 是否已结案（归档后只读，任何写路径都应在此值上 403 / 409 拒绝）。 */
    public boolean isClosed() {
        return this == ARCHIVED;
    }
}