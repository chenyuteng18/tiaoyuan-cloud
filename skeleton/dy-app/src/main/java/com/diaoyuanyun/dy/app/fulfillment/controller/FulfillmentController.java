package com.diaoyuanyun.dy.app.fulfillment.controller;

import com.diaoyuanyun.dy.app.fulfillment.domain.DailyReportRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.VisitRow;
import com.diaoyuanyun.dy.app.fulfillment.service.FulfillmentService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.common.page.PageQuery;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 履约域控制器 —— 契约域 D 的 D1~D4 四个端点。
 *
 * <h2>契约逐字</h2>
 * <pre>
 *  D1 POST /customers/{id}/visits           x-callable-roles: [therapist, meridian, admin]
 *                                           200(VisitData) + 403(GateMissing) + 409(VersionConflict)
 *  D2 GET  /customers/{id}/visits           x-callable-roles: [client, therapist, meridian, admin]
 *                                           200 + 分页
 *  D3 POST /customers/{id}/daily-reports    x-callable-roles: [client, therapist, meridian, admin]
 *                                           200(DailyReportData) + 400(ValidationFailed)
 *  D4 GET  /customers/{id}/daily-reports    x-callable-roles: [client, therapist, meridian, admin]
 *                                           200
 * </pre>
 *
 * <h2>🛑 权限码只贴给<b>不含 client</b> 的 D1</h2>
 * D1 的 {@code x-callable-roles} 不含 client，是标准"给 staff 发码"形态 ⇒ 贴
 * {@code fulfillment:write}。D2/D3/D4 含 client 故<b>不贴码</b>（与 B4/B5/C1/C3 同款：
 * 含 client 的端点刻意缺第三层，否则客户被权限层拒掉）。
 * <p>D2/D4 的"客户能看什么"由字段级裁剪（{@code gate_check_json}/{@code abnormal_note}
 * 客户不下发）承担，而非端点级关门。
 *
 * <h2>🛑 控制器不承载业务判定</h2>
 * 全部顺序与错误码归属在 {@link FulfillmentService}。
 */
@RestController
@RequestMapping("/api/v1")
public class FulfillmentController {

    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FulfillmentService service;

    public FulfillmentController(FulfillmentService service) {
        this.service = service;
    }

    // ==================================================================
    // D1 · POST /customers/{id}/visits
    // ==================================================================

    /**
     * D1 请求体（契约未冻结 requestBody，字段名按域内语义冻结为 snake_case）。
     */
    public record VisitRequest(
            @JsonProperty("serving_store_id") String servingStoreId,
            @JsonProperty("plan_id") String planId,
            @JsonProperty("plan_version") Integer planVersion,
            @JsonProperty("part_method") String partMethod,
            @JsonProperty("duration_min") Integer durationMin,
            @JsonProperty("pre_feedback") String preFeedback,
            @JsonProperty("post_feedback") String postFeedback,
            @JsonProperty("abnormal_note") String abnormalNote) {
    }

    @RequirePermission("fulfillment:write")
    @PostMapping("/customers/{id}/visits")
    public Result<Map<String, Object>> createVisit(@PathVariable("id") String id,
                                                    @RequestBody(required = false) VisitRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        FulfillmentService.CreateVisitRequest req = body == null ? null
                : new FulfillmentService.CreateVisitRequest(
                        body.servingStoreId(), body.planId(), body.planVersion(),
                        body.partMethod(), body.durationMin(),
                        body.preFeedback(), body.postFeedback(), body.abnormalNote());

        VisitRow row = service.createVisit(tenantId, customerId, req, TenantContext.staffId());
        return Result.ok(visitData(row, true), MDC.get("traceId"));
    }

    // ==================================================================
    // D2 · GET /customers/{id}/visits
    // ==================================================================

    /**
     * D2 —— 客户维度服务记录分页。
     *
     * <p>🛑 分页参数越界一律 400（{@code VALIDATION_FAILED}），<b>不夹逼</b>：
     * 唯一校验单点是 {@link PageQuery}（契约 {@code x-api-protocol.pagination}，
     * {@code over-range-policy: reject-400}）。本方法此前用
     * {@code Math.min(Math.max(pageSize, 1), 100)} 静默夹逼后返回 200 ——
     * 与 A3 {@code GET /stores} 的「超界直接拒」在同一份契约下<b>各走一路</b>，
     * 且两端 200 响应体、{@code tsc}、门禁全绿（本仓第 58 条）。
     */
    @GetMapping("/customers/{id}/visits")
    public Result<Map<String, Object>> listVisits(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        // 🛑 越界【拒】（400），不夹逼 —— 唯一校验单点在 dy-web PageQuery（本仓第 58 条）
        PageQuery pq = PageQuery.of(page, pageSize);
        int p = pq.page();
        int s = pq.pageSize();

        List<VisitRow> rows = service.listVisits(tenantId, customerId, p, s);
        int total = service.countVisits(tenantId, customerId);

        // 🛑 客户不下发 gate_check_json / abnormal_note
        boolean internal = FulfillmentService.visitInternalVisibleTo(
                VisibilityRole.tryOf(TenantContext.role()).orElse(null));

        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (VisitRow r : rows) {
            items.add(visitData(r, internal));
        }
        data.put("items", items);
        data.put("total", total);
        data.put("page", p);
        data.put("page_size", s);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // D3 · POST /customers/{id}/daily-reports
    // ==================================================================

    public record DailyReportRequest(
            @JsonProperty("date") String date,
            @JsonProperty("answers_json") Map<String, Object> answersJson,
            @JsonProperty("source") String source) {
    }

    @PostMapping("/customers/{id}/daily-reports")
    public Result<Map<String, Object>> submitDailyReport(@PathVariable("id") String id,
                                                          @RequestBody(required = false) DailyReportRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        String answersJson;
        try {
            Map<String, Object> answers = body == null ? null : body.answersJson();
            answersJson = MAPPER.writeValueAsString(answers == null ? Map.of() : answers);
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "answers_json 无法序列化: " + e.getMessage());
        }

        LocalDate date;
        try {
            date = body == null || body.date() == null ? null : LocalDate.parse(body.date().trim());
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "date 不是合法日期（yyyy-MM-dd）: '" + (body == null ? null : body.date()) + "'");
        }

        FulfillmentService.SubmitDailyReportRequest req =
                new FulfillmentService.SubmitDailyReportRequest(
                        date, answersJson, body == null ? null : body.source());

        DailyReportRow row = service.submitDailyReport(tenantId, customerId, req,
                TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report_id", row.reportId().toString());
        data.put("date", row.date().toString());
        data.put("source", row.source());
        // 🛑 weekly_count = 本周已填报天数（正向计数，X/7）；不是"服务次数 % 7"
        data.put("weekly_count", service.weeklyReportDayCount(tenantId, customerId, row.date()));
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // D4 · GET /customers/{id}/daily-reports
    // ==================================================================

    @GetMapping("/customers/{id}/daily-reports")
    public Result<Map<String, Object>> listDailyReports(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        List<DailyReportRow> rows = service.listDailyReports(tenantId, customerId);
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (DailyReportRow r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("report_id", r.reportId().toString());
            one.put("date", r.date().toString());
            one.put("source", r.source());
            one.put("answers", parseAnswers(r.answersJson()));
            items.add(one);
        }
        data.put("items", items);
        data.put("count", items.size());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述（只读、免权限、不含数据）
    // ==================================================================

    @GetMapping("/customers/fulfillment/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "D-服务与履约（本类实现 D1~D4）");
        m.put("endpoints", List.of(
                "D1 POST /customers/{id}/visits",
                "D2 GET /customers/{id}/visits",
                "D3 POST /customers/{id}/daily-reports",
                "D4 GET /customers/{id}/daily-reports"));
        m.put("callable_roles", Map.of(
                "D1", List.of("therapist", "meridian", "admin"),
                "D2", List.of("client", "therapist", "meridian", "admin"),
                "D3", List.of("client", "therapist", "meridian", "admin"),
                "D4", List.of("client", "therapist", "meridian", "admin")));
        m.put("annotation_policy", List.of(
                "D1 贴 @RequirePermission(\"fulfillment:write\")（x-callable-roles 不含 client）",
                "D2/D3/D4 不贴码（含 client，注册表刻意不登记 client）"));
        m.put("gate_literals",
                com.diaoyuanyun.dy.app.fulfillment.domain.FulfillmentGateGuard.missingItemLiterals());
        m.put("gate_note",
                "PRD P0-08 四道闸门：禁忌通过 / 知情同意书已签 / 调理协议已签 / 方案已确认且有效。"
                        + "D1 缺任一 403 GATE_MISSING + data.missing_items[] 逐字");
        m.put("visit_no_semantics",
                "客户维度全局唯一账本（U2）：跨店累计，只认中央计数，门店本地记录仅作对账");
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

    private static Map<String, Object> visitData(VisitRow r, boolean internalVisible) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visit_id", r.visitId().toString());
        m.put("visit_no", r.visitNo());
        m.put("executed_at", r.executedAt().atOffset(java.time.ZoneOffset.UTC).toString());
        m.put("serving_store_id", r.servingStoreId().toString());
        m.put("customer_confirmed", r.customerConfirmed());
        // 🛑 客户不下发（x-visible-to 不含 client）
        if (internalVisible) {
            m.put("gate_check_json", parseAnswers(r.gateCheckJson()));
            if (r.abnormalNote() != null) {
                m.put("abnormal_note", r.abnormalNote());
            }
        }
        return m;
    }

    private static Map<String, Object> parseAnswers(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of("_unparsable", true, "_note", "快照无法解析: " + e.getMessage());
        }
    }

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— 履约域不设无租户通道");
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
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }
}