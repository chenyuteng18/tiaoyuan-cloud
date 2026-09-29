package com.diaoyuanyun.dy.app.fulfillment.service;

import com.diaoyuanyun.dy.app.fulfillment.domain.DeviceDispatchRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanReviewRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanRow;
import com.diaoyuanyun.dy.app.fulfillment.repository.FulfillmentLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * 方案审核流 + 设备下发（契约域 D · D5/D6）的<b>唯一业务落点</b>。
 *
 * <h2>端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  D5-a POST /plans                → {@link #createPlan}（方案出具，回炉审核语义）
 *  D5-b GET  /plans/{id}           → {@link #getPlan}（方案查阅，含 client）
 *  D5-c POST /plans/{id}/reviews   → {@link #reviewPlan}（审核，退回必填 reason）
 *  D6   POST /device-dispatches    → {@link #createDispatch}（需方案已审核）
 * </pre>
 *
 * <h2>🛑 错误码（对齐各端点 responses，失效模式 18）</h2>
 * <pre>
 *  D5-a = 200                              ⇒ 仅 200（无 403/404/409）
 *  D5-b = 200 + 404                        ⇒ 查无报 404
 *  D5-c = 200 + 409(VersionConflict)       ⇒ 退回/审核并发冲突 409
 *  D6   = 200 + 403(GateMissing)           ⇒ 方案未审核 403 GATE_MISSING
 * </pre>
 *
 * <h2>🛑 方案"审核通过"才是可下发/可执行的前置（D6 / 四道闸门④的落点）</h2>
 * PRD P0-06 / US-2「多级方案审核流」：
 * <pre>
 *   审核人为独立状态跃迁 + 签名（非同意书勾选框）
 *   退回必填原因；同一账号自助通过需二次确认并留痕
 *   审核留痕上链到方案版本；变更后强制回炉审核 + 客户重签
 * </pre>
 * 故 D6 在方案 {@code status != 'approved'} 时 403 GATE_MISSING（缺失项 = plan_approved）。
 *
 * <h2>🛑 版本不可覆盖</h2>
 * 方案变更 = 插入新 {@code version}，旧版本行原样保留（历史可对比）。
 * 本类 {@link #createPlan} 只走 INSERT，绝不 UPDATE 内容。
 *
 * <h2>🛑 D5-c 的同人自助通过二次确认</h2>
 * 审核人与出方案人相同（都可从当前 staff 推导）时，若要"通过"须带 {@code second_confirm=true}，
 * 否则 409（VERSION_CONFLICT）—— 不允许无痕自助通过。
 */
@Service
public class PlanService {

    private final FulfillmentLedger ledger;

    public PlanService(FulfillmentLedger ledger) {
        this.ledger = ledger;
    }

    // ==================================================================
    // D5-a 方案出具
    // ==================================================================

    /**
     * D5-a 入参 —— 契约 D5-a 无 requestBody（未冻结），字段名由本类冻结。
     *
     * <p>🛑 契约 D5-a 只声明了 200 响应，没有 requestBody —— 与 F1 同款的
     * "已登记契约缺口"。故入参形状在服务层记录体冻结，字段名用 snake_case。
     */
    public record CreatePlanRequest(
            String customerId,
            String treatmentJson,
            String lifestyleJson,
            String intentParams) {
    }

    /**
     * D5-a 方案出具。
     *
     * <p>顺序：解析 customer → 组装 PlanRow（version=1, status=draft）→ 落库。
     * 🛑 只 INSERT（版本不可覆盖），且新方案从 {@code draft} 起步（须经 D5-c 审核才可执行）。
     *
     * @return 落库后的方案
     */
    public PlanRow createPlan(String tenantId, CreatePlanRequest req, String createdBy) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "方案请求体缺失");
        }
        UUID customerId = uuid(req.customerId(), "customer_id");

        PlanRow row = new PlanRow(
                UUID.randomUUID(), customerId, 1,
                req.treatmentJson() == null ? "{}" : req.treatmentJson(),
                req.lifestyleJson() == null ? "{}" : req.lifestyleJson(),
                req.intentParams() == null ? "{}" : req.intentParams(),
                PlanRow.STATUS_DRAFT, Instant.now(), createdBy);

        ledger.insertPlan(tenantId, row);
        return row;
    }

    // ==================================================================
    // D5-b 方案查阅
    // ==================================================================

    /**
     * D5-b 取方案（按 plan_id）。
     *
     * <p>契约 D5-b 路径 {@code /plans/{id}} 的 {@code id} 即 plan_id，取<b>当前最大版本</b>。
     * 查无 → 404（契约声明了 404）。含 client（不在此做角色门）。
     */
    public PlanRow getPlan(String tenantId, UUID planId) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (planId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "plan_id 缺失");
        }
        int version = ledger.planCurrentVersion(tenantId, planId);
        if (version == 0) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "方案不存在: " + planId + "（或不属于当前租户）");
        }
        PlanRow row = ledger.findPlan(tenantId, planId, version);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不存在: " + planId);
        }
        return row;
    }

    // ==================================================================
    // D5-c 方案审核
    // ==================================================================

    /**
     * D5-c 入参。
     *
     * @param result        通过 / 退回
     * @param reason        退回必填（领域层 PlanReviewRow 再次校验）
     * @param secondConfirm 同人自助通过须二次确认
     */
    public record ReviewPlanRequest(
            String result,
            String reason,
            Boolean secondConfirm) {
    }

    /**
     * D5-c 方案审核。
     *
     * <p>顺序：
     * <ol>
     *   <li>解析方案（不存在 404）；</li>
     *   <li>组装 PlanReviewRow（退回必填 reason 由领域构造器挡）；</li>
     *   <li>同人自助通过须二次确认（未带 second_confirm → 409）；</li>
     *   <li>落复核留痕 + 更新方案 status（通过 → approved / 退回 → reviewing）。</li>
     * </ol>
     *
     * @return 复核留痕 + 方案最终 status
     */
    public PlanReviewRow reviewPlan(String tenantId, UUID planId,
                                    ReviewPlanRequest req, String reviewerId, UUID issuerId) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (planId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "plan_id 缺失");
        }
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "审核请求体缺失");
        }
        int version = ledger.planCurrentVersion(tenantId, planId);
        if (version == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不存在: " + planId);
        }
        PlanRow plan = ledger.findPlan(tenantId, planId, version);

        boolean approve = PlanReviewRow.RESULT_APPROVED.equals(req.result());
        UUID reviewer = uuid(reviewerId, "reviewer_id");
        // 🛑 同人自助通过须二次确认（US-2：不允许同一账号无痕自助通过）
        if (approve && issuerId != null && issuerId.equals(reviewer)) {
            if (!Boolean.TRUE.equals(req.secondConfirm())) {
                throw new BizException(ErrorCode.VERSION_CONFLICT,
                        "出方案人与审核人相同，自助通过须二次确认（second_confirm=true）—— "
                                + "PRD US-2 不允许同一账号无痕自助通过");
            }
        }

        PlanReviewRow review = new PlanReviewRow(
                UUID.randomUUID(), planId, version,
                reviewer,
                req.result(), req.reason(), Boolean.TRUE.equals(req.secondConfirm()),
                Instant.now(), reviewerId);

        ledger.insertPlanReview(tenantId, review);
        // 审核结论驱动方案状态迁移
        ledger.updatePlanStatus(tenantId, planId, version,
                approve ? PlanRow.STATUS_APPROVED : PlanRow.STATUS_REVIEWING);

        return review;
    }

    // ==================================================================
    // D6 设备参数下发
    // ==================================================================

    /**
     * D6 入参。
     */
    public record CreateDispatchRequest(
            String planId,
            Integer planVersion,
            String storeId,
            String deviceId,
            String paramSnapshot,
            String result,
            String failedReason,
            String event) {
    }

    /**
     * D6 设备参数下发（门店级调理设备，下行可追责）。
     *
     * <p>顺序：
     * <ol>
     *   <li>方案已审核（status=approved）—— 否则 403 GATE_MISSING；</li>
     *   <li>组装 DeviceDispatchRow（失败必填 reason 由领域构造器挡）；</li>
     *   <li>落库。</li>
     * </ol>
     */
    public DeviceDispatchRow createDispatch(String tenantId, CreateDispatchRequest req, String createdBy) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "下发请求体缺失");
        }
        UUID planId = uuid(req.planId(), "plan_id");
        int planVersion = req.planVersion() == null ? 1 : req.planVersion();
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "plan_version 须 ≥ 1：实际=" + planVersion);
        }

        // 🛑 方案须已审核通过（PRD P0-16：审核通过事件自动触发下发）
        if (!ledger.planApproved(tenantId, planId, planVersion)) {
            throw new GateMissingException(List.of("plan_approved"),
                    "设备下发前方案须已审核通过（approved）；当前方案未过审核，禁止下发（PRD P0-16）");
        }

        DeviceDispatchRow row = new DeviceDispatchRow(
                UUID.randomUUID(), planId, planVersion,
                uuid(req.storeId(), "store_id"),
                uuid(req.deviceId(), "device_id"),
                req.paramSnapshot() == null ? "{}" : req.paramSnapshot(),
                req.result() == null ? DeviceDispatchRow.RESULT_SUCCESS : req.result(),
                req.failedReason(), req.event(), Instant.now(), createdBy);

        ledger.insertDeviceDispatch(tenantId, row);
        return row;
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 缺失");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }
}