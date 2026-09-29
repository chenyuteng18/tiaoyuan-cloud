package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code visit} 一行（V5 §2.15）—— 服务核销的落库载体（客户维度全局唯一账本 U2）。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 {@code VisitData} + PRD §2.15 / U2「服务次数是客户维度的全局唯一账本」：
 * <pre>
 *   serving_store_id  标记服务门店（跨店通兑时 ≠ 归属店）—— 客户端可见
 *   gate_check_json   四道闸门结果（客户不下发）
 *   customer_confirmed 须客户确认（库层 CHECK 恒 TRUE，未确认不得核销）
 *   visit_no          客户维度全局唯一账本（跨店累计），UNIQUE(customer_id, visit_no)
 *   abnormal_note     异常记录（客户不下发）
 * </pre>
 *
 * <h2>🛑 {@code visit_no} 是"全局唯一账本"而非"门店本地计数"</h2>
 * U2 逐字：「跨店累计，每次核销须客户确认并标记服务门店；『每 7 次触发评估』
 * 只认中央计数，门店本地记录仅作对账」。故本行不带"门店内序号"——
 * {@code visit_no} 是<b>客户维度</b>的递增序号，由服务层在租户内
 * {@code MAX(visit_no)+1} 解算（并发由 {@code UNIQUE(customer_id, visit_no)} 兜底）。
 *
 * <h2>构造期校验（fail-closed）</h2>
 * 复述 V5 的 NOT NULL / CHECK。{@code customer_confirmed} 库层 CHECK 恒 TRUE，
 * 故本行只有"已确认"一种可落形态 —— 未确认的服务<b>根本不该产生 visit 行</b>。
 */
public record VisitRow(
        UUID visitId,
        UUID customerId,
        UUID servingStoreId,
        UUID planId,
        int planVersion,
        String gateCheckJson,
        int visitNo,
        String partMethod,
        Integer durationMin,
        String preFeedback,
        String postFeedback,
        String abnormalNote,
        Instant executedAt,
        String createdBy) {

    public VisitRow {
        requireNonNull(visitId, "核销主键（visit_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        requireNonNull(servingStoreId, "服务门店（serving_store_id）");
        requireNonNull(planId, "方案（plan_id）");
        requireNonBlank(gateCheckJson, "四道闸门结果（gate_check_json）");
        requireNonNull(executedAt, "执行时点（executed_at）");
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "方案版本须 ≥ 1：实际=" + planVersion
                            + "（与 V5 CHECK(plan_version >= 1) 同口径）");
        }
        if (visitNo < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "服务序号须 ≥ 1：实际=" + visitNo
                            + "（客户维度全局唯一账本 U2，0 与负数无对应事实）");
        }
    }

    /**
     * 客户是否已确认 —— 恒 {@code true}（库层 {@code CHECK (customer_confirmed = TRUE)}）。
     *
     * <p>未确认的服务<b>不落 visit 行</b>（这是四道闸门 + 客户确认共同作用后的核销定义），
     * 故本访问器恒定返回 {@code true}：它存在的意义是让"确认"这件事在出参里可读，
     * 而不是真的有两种状态可切换。
     */
    public boolean customerConfirmed() {
        return true;
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static void requireNonBlank(String v, String name) {
        requireNonNull(v, name);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 不得为空白字符串");
        }
    }
}