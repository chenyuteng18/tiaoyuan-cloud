package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code plan} 一行（V5 §2.12）—— 调理方案的落库载体。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 D5-a/D5-b + PRD §2.12 / P0-06「多级方案审核流」：
 * <pre>
 *   version   版本递增·不可覆盖（变更 → 新版本 + 强制重签）
 *   treatment_json  M1~M5 模块 + 目标 + 经络映射
 *   lifestyle_json  用药 / 饮食 / 运动（三项必填）
 *   intent_params   意图参数（非型号；经 device_dispatch 下发）
 *   status    draft / reviewing / approved / superseded
 * </pre>
 *
 * <h2>🛑 版本不可覆盖</h2>
 * 唯一键 {@code uq_plan_id_version} 兜底。本 record 不携带"更新"操作 ——
 * 方案变更的语义是"插入新 version"，旧版本行原样保留（历史可对比，P0-06）。
 *
 * <h2>构造期校验</h2>
 * 复述 V5 NOT NULL 与 CHECK(version >= 1)。{@code status} 四值 fail-closed。
 */
public record PlanRow(
        UUID planId,
        UUID customerId,
        int version,
        String treatmentJson,
        String lifestyleJson,
        String intentParams,
        String status,
        Instant createdAt,
        String createdBy) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_REVIEWING = "reviewing";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_SUPERSEDED = "superseded";

    public PlanRow {
        requireNonNull(planId, "方案主键（plan_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        requireNonBlank(treatmentJson, "治疗方案（treatment_json）");
        requireNonBlank(lifestyleJson, "生活方式（lifestyle_json）");
        requireNonBlank(intentParams, "意图参数（intent_params）");
        if (version < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "方案版本须 ≥ 1：实际=" + version);
        }
        if (!STATUS_DRAFT.equals(status) && !STATUS_REVIEWING.equals(status)
                && !STATUS_APPROVED.equals(status) && !STATUS_SUPERSEDED.equals(status)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "方案 status 非法：'" + status + "'（合法: draft/reviewing/approved/superseded）");
        }
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static void requireNonBlank(String v, String name) {
        requireNonNull(v, name);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 不得为空白字符串");
        }
    }
}