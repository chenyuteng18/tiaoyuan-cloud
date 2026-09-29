package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code plan_review} 一行（V5 §2.13）—— 方案复核留痕（退回必填 reason，同人自助二次确认）。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 D5-c + PRD P0-06 / US-2「多级方案审核流」：
 * <pre>
 *   reviewer_id  复核人（可 ≠ 出方案人）
 *   result       CHECK 通过 / 退回
 *   reason       退回必填（库层 ck_plan_review_reject_requires_reason）
 *   second_confirm 同人自助通过需二次确认留痕
 * </pre>
 *
 * <h2>🛑 退回必填 reason 在【库层】就堵死</h2>
 * V5 的 {@code ck_plan_review_reject_requires_reason} 约束「result=退回 ⇒ reason 非空」。
 * 本 record 在构造期做同一条校验，使报错指向成因而非一次 23514。这与
 * {@code device_dispatch} 的 {@code ck_dispatch_failed_requires_reason} 同族。
 *
 * <h2>构造期校验</h2>
 * 复述 V5 NOT NULL 与 CHECK。{@code result} 两值 fail-closed。
 */
public record PlanReviewRow(
        UUID reviewId,
        UUID planId,
        int planVersion,
        UUID reviewerId,
        String result,
        String reason,
        boolean secondConfirm,
        Instant reviewedAt,
        String createdBy) {

    public static final String RESULT_APPROVED = "通过";
    public static final String RESULT_REJECTED = "退回";

    public PlanReviewRow {
        requireNonNull(reviewId, "复核主键（review_id）");
        requireNonNull(planId, "方案标识（plan_id）");
        requireNonNull(reviewerId, "复核人（reviewer_id）");
        requireNonNull(reviewedAt, "复核时点（reviewed_at）");
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "方案版本须 ≥ 1：实际=" + planVersion);
        }
        if (!RESULT_APPROVED.equals(result) && !RESULT_REJECTED.equals(result)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "复核结果非法：'" + result + "'（合法: 通过/退回）");
        }
        // 🛑 退回必填 reason（库层 ck_plan_review_reject_requires_reason 的应用层镜像）
        if (RESULT_REJECTED.equals(result)
                && (reason == null || reason.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "退回必须填写原因（reason）—— PRD P0-06：退回必填原因，"
                            + "否则驳回无人能知其依据（库层有同名约束，此处提前到构造点）");
        }
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }
}