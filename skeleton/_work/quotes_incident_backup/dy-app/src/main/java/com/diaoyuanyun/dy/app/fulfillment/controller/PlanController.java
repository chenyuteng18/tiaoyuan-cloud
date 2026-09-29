package com.diaoyuanyun.dy.app.fulfillment.controller;

import com.diaoyuanyun.dy.app.fulfillment.domain.DeviceDispatchRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanReviewRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.PlanRow;
import com.diaoyuanyun.dy.app.fulfillment.service.PlanService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 方案审核流 + 设备下发控制器 —— 契约域 D 的 D5/D6 四个端点。
 *
 * <h2>契约逐字</h2>
 * <pre>
 *  D5-a POST /plans                x-callable-roles: [therapist, meridian, admin]  200
 *  D5-b GET  /plans/{id}           x-callable-roles: [client, therapist, meridian, admin]  200 + 404
 *  D5-c POST /plans/{id}/reviews   x-callable-roles: [meridian, admin]  200 + 409
 *  D6   POST /device-dispatches    x-callable-roles: [therapist, meridian, admin]  200 + 403(GateMissing)
 * </pre>
 *
 * <h2>🛑 权限码只贴给不含 client 的三行（D5-a/D5-c/D6）</h2>
 * 三者 {@code x-callable-roles} 不含 client，贴 {@code fulfillment:write}。
 * D5-b 含 client 故不贴码（与 B5/C3 同款）。
 *
 * <h2>🛑 D6 的"方案已审核"是四道闸门④（plan_approved）的延伸</h2>
 * D6 与 D1 共享"方案须 approved"这一前置 —— 但错误码不同：D6 契约声明 403 GateMissing，
 * 故 {@link PlanService#createDispatch} 在方案未审核时报 GATE_MISSING(2002)+plan_approved。
 */
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    // ==================================================================
    // D5-a · POST /plans
    // ==================================================================

    public record PlanRequest(
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("treatment_json") String treatmentJson,
            @JsonProperty("lifestyle_json") String lifestyleJson,
            @JsonProperty("intent_params") String intentParams) {
    }

    @RequirePermission("fulfillment:write")
    @PostMapping("/plans")
    public Result<Map<String, Object>> createPlan(@RequestBody(required = false) PlanRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        PlanService.CreatePlanRequest req = body == null ? null
                : new PlanService.CreatePlanRequest(
                        body.customerId(), body.treatmentJson(),
                        body.lifestyleJson(), body.intentParams());

        PlanRow row = service.createPlan(tenantId, req, TenantContext.staffId());
        return Result.ok(planData(row), MDC.get("traceId"));
    }

    // ==================================================================
    // D5-b · GET /plans/{id}
    // ==================================================================

    @GetMapping("/plans/{id}")
    public Result<Map<String, Object>> getPlan(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        PlanRow row = service.getPlan(tenantId, uuid(id, "id"));
        return Result.ok(planData(row), MDC.get("traceId"));
    }

    // ==================================================================
    // D5-c · POST /plans/{id}/reviews
    // ==================================================================

    public record ReviewRequest(
            @JsonProperty("result") String result,
            @JsonProperty("reason") String reason,
            @JsonProperty("second_confirm") Boolean secondConfirm) {
    }

    @RequirePermission("fulfillment:write")
    @PostMapping("/plans/{id}/reviews")
    public Result<Map<String, Object>> reviewPlan(@PathVariable("id") String id,
                                                   @RequestBody(required = false) ReviewRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        PlanService.ReviewPlanRequest req = body == null ? null
                : new PlanService.ReviewPlanRequest(body.result(), body.reason(), body.secondConfirm());

        // issuerId = 出方案人（此处即当前 staff —— 同人自助通过判定用）
        String staffId = TenantContext.staffId();
        UUID issuer = staffId == null || staffId.isBlank() ? null : uuid(staffId, "staff_id");

        PlanReviewRow review = service.reviewPlan(tenantId, uuid(id, "id"), req,
                staffId, issuer);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("review_id", review.reviewId().toString());
        data.put("plan_id", review.planId().toString());
        data.put("plan_version", review.planVersion());
        data.put("result", review.result());
        data.put("reviewed_at", review.reviewedAt().atOffset(java.time.ZoneOffset.UTC).toString());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // D6 · POST /device-dispatches
    // ==================================================================

    public record DispatchRequest(
            @JsonProperty("plan_id") String planId,
            @JsonProperty("plan_version") Integer planVersion,
            @JsonProperty("store_id") String storeId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("param_snapshot") String paramSnapshot,
            @JsonProperty("result") String result,
            @JsonProperty("failed_reason") String failedReason,
            @JsonProperty("event") String event) {
    }

    @RequirePermission("fulfillment:write")
    @PostMapping("/device-dispatches")
    public Result<Map<String, Object>> createDispatch(@RequestBody(required = false) DispatchRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        PlanService.CreateDispatchRequest req = body == null ? null
                : new PlanService.CreateDispatchRequest(
                        body.planId(), body.planVersion(), body.storeId(), body.deviceId(),
                        body.paramSnapshot(), body.result(), body.failedReason(), body.event());

        DeviceDispatchRow row = service.createDispatch(tenantId, req, TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dispatch_id", row.dispatchId().toString());
        data.put("plan_id", row.planId().toString());
        data.put("plan_version", row.planVersion());
        data.put("device_id", row.deviceId().toString());
        data.put("result", row.result());
        data.put("dispatched_at", row.dispatchedAt().atOffset(java.time.ZoneOffset.UTC).toString());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述（只读）
    // ==================================================================

    @GetMapping("/plans/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "D-服务与履约（本类实现 D5/D6）");
        m.put("endpoints", List.of(
                "D5-a POST /plans",
                "D5-b GET /plans/{id}",
                "D5-c POST /plans/{id}/reviews",
                "D6 POST /device-dispatches"));
        m.put("callable_roles", Map.of(
                "D5-a", List.of("therapist", "meridian", "admin"),
                "D5-b", List.of("client", "therapist", "meridian", "admin"),
                "D5-c", List.of("meridian", "admin"),
                "D6", List.of("therapist", "meridian", "admin")));
        m.put("annotation_policy", List.of(
                "D5-a/D5-c/D6 贴 @RequirePermission(\"fulfillment:write\")",
                "D5-b 不贴码（含 client）"));
        m.put("review_rules", List.of(
                "退回必填 reason（库层 ck_plan_review_reject_requires_reason + 领域构造器）",
                "同人自助通过须 second_confirm=true（US-2）",
                "审核结论驱动状态迁移：通过→approved / 退回→reviewing"));
        m.put("dispatch_precondition", "方案 status=approved 方可下发（D6 四道闸门④，403 GATE_MISSING）");
        m.put("version_semantics", "方案版本不可覆盖；变更 → 新 version + 强制回炉审核 + 客户重签");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
    }

    // ==================================================================
    // 出站构造与入参解算
    // ==================================================================

    private static Map<String, Object> planData(PlanRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan_id", r.planId().toString());
        m.put("customer_id", r.customerId().toString());
        m.put("version", r.version());
        m.put("status", r.status());
        m.put("treatment", safeParse(r.treatmentJson()));
        m.put("lifestyle", safeParse(r.lifestyleJson()));
        m.put("intent_params", safeParse(r.intentParams()));
        return m;
    }

    private static Object safeParse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of("_unparsable", true);
        }
    }

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH, "请求未携带租户上下文");
        }
        return tenantId;
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 必填");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 不是合法 UUID: '" + raw + "'");
        }
    }
}