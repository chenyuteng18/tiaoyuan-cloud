package com.diaoyuanyun.dy.app.band.controller;

import com.diaoyuanyun.dy.app.band.domain.SyncBatchRow;
import com.diaoyuanyun.dy.app.band.domain.TelemetryRow;
import com.diaoyuanyun.dy.app.band.service.BandService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手环数据域控制器 —— 契约域 E 的 E1/E2/E3/E6 四个端点（E4/E5 已在 S1-5/S1-7 落地）。
 *
 * <h2>契约逐字</h2>
 * <pre>
 *  E1 POST /band/sync-batches              x-callable-roles: [client]  200/400/409/429
 *  E2 POST /band/telemetry                 x-callable-roles: [client]  200/409/422
 *  E3 GET  /customers/{id}/band/telemetry  x-callable-roles: [client,therapist,meridian,admin]  200/403
 *  E6 GET  /customers/{id}/band/sync-status x-callable-roles: [client]  200
 * </pre>
 *
 * <h2>🛑 全部端点<b>不贴</b> {@code @RequirePermission}</h2>
 * E1/E2/E6 只含 client，E3 含 client —— 注册表刻意不登记 client，贴任何码都会让
 * 合法客户端请求恒 403（与 A2/E5/B4/C1 同款"含 client 的端点刻意缺第三层"）。
 * E3 的字段级裁剪（{@code gap_reason} 客户不下发）由 {@link BandService#gapReasonVisibleTo} 承担。
 */
@RestController
@RequestMapping("/api/v1")
public class BandController {

    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BandService service;

    public BandController(BandService service) {
        this.service = service;
    }

    // ==================================================================
    // E1 · POST /band/sync-batches
    // ==================================================================

    public record SyncBatchRequest(
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("batch_no") String batchNo,
            @JsonProperty("trigger") String trigger,
            @JsonProperty("state") String state,
            @JsonProperty("synced_at") String syncedAt,
            @JsonProperty("last_success_date") String lastSuccessDate,
            @JsonProperty("fail_reason_class") String failReasonClass,
            @JsonProperty("next_action") String nextAction) {
    }

    @PostMapping("/band/sync-batches")
    public Result<Map<String, Object>> reportSyncBatch(@RequestBody(required = false) SyncBatchRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        BandService.SyncBatchRequest req = body == null ? null
                : new BandService.SyncBatchRequest(
                        body.deviceId(), body.customerId(), body.batchNo(),
                        body.trigger(), body.state(),
                        body.syncedAt() == null ? null : Instant.parse(body.syncedAt()),
                        body.lastSuccessDate() == null ? null : LocalDate.parse(body.lastSuccessDate()),
                        body.failReasonClass(), body.nextAction());

        SyncBatchRow row = service.reportSyncBatch(tenantId, req, TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sync_log_id", row.syncLogId().toString());
        data.put("batch_no", row.batchNo());
        data.put("state", row.state());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // E2 · POST /band/telemetry
    // ==================================================================

    public record TelemetryRequest(
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("metric") String metric,
            @JsonProperty("date") String date,
            @JsonProperty("hour") Integer hour,
            @JsonProperty("minute") Integer minute,
            @JsonProperty("value") BigDecimal value,
            @JsonProperty("sleep_json") String sleepJson,
            @JsonProperty("is_wear") Integer isWear,
            @JsonProperty("current_sport_id") String currentSportId) {
    }

    @PostMapping("/band/telemetry")
    public Result<Map<String, Object>> upsertTelemetry(@RequestBody(required = false) TelemetryRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        BandService.TelemetryRequest req = body == null ? null
                : new BandService.TelemetryRequest(
                        body.deviceId(), body.customerId(), body.metric(),
                        body.date() == null ? null : LocalDate.parse(body.date()),
                        body.hour(), body.minute(), body.value(),
                        body.sleepJson(), body.isWear(), body.currentSportId());

        TelemetryRow row = service.upsertTelemetry(tenantId, req, TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("telemetry_id", row.telemetryId().toString());
        data.put("metric", row.metric());
        data.put("date", row.date().toString());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // E3 · GET /customers/{id}/band/telemetry
    // ==================================================================

    @GetMapping("/customers/{id}/band/telemetry")
    public Result<Map<String, Object>> listTelemetry(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        List<TelemetryRow> rows = service.listTelemetry(tenantId, customerId);
        boolean gapVisible = BandService.gapReasonVisibleTo(
                VisibilityRole.tryOf(TenantContext.role()).orElse(null));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("data_source", rows.isEmpty() ? "未接入" : "手环");
        data.put("collected_days", rows.stream().map(TelemetryRow::date).distinct().count());
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (TelemetryRow r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("metric", r.metric());
            one.put("date", r.date().toString());
            one.put("value", r.valueNum());
            if (gapVisible && r.gapReason() != null) {
                one.put("gap_reason", r.gapReason());
            }
            metrics.add(one);
        }
        data.put("metrics", metrics);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // E6 · GET /customers/{id}/band/sync-status
    // ==================================================================

    @GetMapping("/customers/{id}/band/sync-status")
    public Result<Map<String, Object>> syncStatus(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        UUID customerId = uuid(id, "id");

        // 🛑 E6 语义：先按客户反查 active 手环 device_id，再取最近同步日志四态。
        //    无 active 手环 = "未接入"（契约 BandSyncStatusData 的四态不含"未佩戴"，
        //    但 data 侧无手环时应如实返回 syncing 且不编造 synced_date）。
        Map<String, Object> data = new LinkedHashMap<>();
        UUID deviceId = service.activeDeviceOf(tenantId, customerId);
        if (deviceId == null) {
            data.put("state", "syncing");
            data.put("synced_date", null);
            data.put("next_action", null);
            data.put("fail_reason_class", null);
            return Result.ok(data, MDC.get("traceId"));
        }
        SyncBatchRow latest = service.syncStatus(tenantId, deviceId);
        if (latest == null) {
            data.put("state", "syncing");
            data.put("synced_date", null);
            data.put("next_action", null);
            data.put("fail_reason_class", null);
            return Result.ok(data, MDC.get("traceId"));
        }
        data.put("state", latest.state());
        data.put("synced_date", latest.lastSuccessDate() == null ? null : latest.lastSuccessDate().toString());
        data.put("next_action", latest.nextAction());
        data.put("fail_reason_class", latest.failReasonClass());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述
    // ==================================================================

    @GetMapping("/band/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "E-设备与手环数据（本类实现 E1/E2/E3/E6；E4 在 derived、E5 在 band）");
        m.put("endpoints", List.of(
                "E1 POST /band/sync-batches",
                "E2 POST /band/telemetry",
                "E3 GET /customers/{id}/band/telemetry",
                "E4 GET /customers/{id}/band/derived（derived 模块）",
                "E5 POST /band/available-dates（S1-7）",
                "E6 GET /customers/{id}/band/sync-status"));
        m.put("annotation_policy", "E1/E2/E3/E6 均不贴码（含 client）");
        // A-5（2026-09-26）之前这里登记了两条 schema 缺口；V9 迁移以契约为准已收口。
        // 保留字段本身（而非删除）是为了让自描述仍能回答"这块曾经有过什么问题、
        // 现在怎么解决的" —— 删除会让下一次 review 只能从 git 历史里翻。
        m.put("schema_gaps", List.of());
        m.put("schema_gaps_closed", List.of(
                "A-5 → V9：band_sync_log 补 contract_state / contract_trigger / batch_no 三列"
                        + "（契约 §4.1 四态 + 五值触发源 + 幂等键），旧中文两态列保留给 §4.6 尝试日志产线",
                "A-5 → V9：E1 写路径落库（此前只做内存幂等，服务重启即丢）；"
                        + "幂等权威改由库层 (tenant_id,batch_no) 部分唯一索引承担"));
        m.put("word_table_divergence",
                "契约 §4.1（英文四态/五值）与 PRD §4.6（中文两态/四值）是【两条产线】的两套词表，"
                        + "取值集大小不同 ⇒ 并列登记而非合并；E6 读契约侧列，旧行走有损回落（登记于 BandLedger）");
        m.put("idempotency", "E2 双分支幂等键（日型 12 + 游标 1，契约 §4.3）");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
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