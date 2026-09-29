package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code device_dispatch} 一行（V5 §2.14）—— 设备参数下发（下行、可追责）。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约 D6 + PRD §2.14 / P0-16「门店级设备台账与参数下发」：
 * <pre>
 *   store_id    门店（设备归属）
 *   device_id   FK device（门店级调理设备；⚠️ 非客户手环 band —— 两本台账不得合并）
 *   param_snapshot 下发参数快照
 *   result      CHECK 成功 / 失败
 *   failed_reason 失败必填（ck_dispatch_failed_requires_reason）
 *   event       device_push_failed（可追责）
 * </pre>
 *
 * <h2>🛑 下行失败可追责（≠ 手环上行失败"不得作不利依据"）</h2>
 * 契约 D6 description 逐字强调 device 与 band 两本台账不得合并。本 record 携带
 * {@code deviceId}（门店设备），语义是"设备端没执行到位可追责"——
 * 与 band 的"未佩戴不记不利"（PRD §C.1.9）是相反的两个方向，故分表分语义。
 *
 * <h2>构造期校验</h2>
 * 复述 V5 NOT NULL 与 CHECK。失败必填 reason（库层约束的应用层镜像）。
 */
public record DeviceDispatchRow(
        UUID dispatchId,
        UUID planId,
        int planVersion,
        UUID storeId,
        UUID deviceId,
        String paramSnapshot,
        String result,
        String failedReason,
        String event,
        Instant dispatchedAt,
        String createdBy) {

    public static final String RESULT_SUCCESS = "成功";
    public static final String RESULT_FAILED = "失败";

    public DeviceDispatchRow {
        requireNonNull(dispatchId, "下发主键（dispatch_id）");
        requireNonNull(planId, "方案标识（plan_id）");
        requireNonNull(storeId, "门店（store_id）");
        requireNonNull(deviceId, "设备（device_id）");
        requireNonBlank(paramSnapshot, "参数快照（param_snapshot）");
        requireNonNull(dispatchedAt, "下发时点（dispatched_at）");
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "方案版本须 ≥ 1：实际=" + planVersion);
        }
        if (!RESULT_SUCCESS.equals(result) && !RESULT_FAILED.equals(result)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "下发结果非法：'" + result + "'（合法: 成功/失败）");
        }
        // 🛑 失败必填 reason（库层 ck_dispatch_failed_requires_reason 的应用层镜像）
        if (RESULT_FAILED.equals(result)
                && (failedReason == null || failedReason.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "下发失败必须填写原因（failed_reason）—— 下行可追责（PRD P0-16），"
                            + "无原因的失败无法定位设备端问题");
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