package com.diaoyuanyun.dy.app.doctpl.controller;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.app.doctpl.service.DocTemplateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文书模板域控制器 —— 契约域 I 的 I1/I2/I4/I5/I6/I8 六端点（I3 上传 / I7 下载另立文件通道）。
 *
 * <h2>契约逐字</h2>
 * 域 I 全部 {@code x-callable-roles: [admin]}（I1~I7）、I8 {@code []}（内部，无客户端可见面）。
 * 契约标 {@code x-frontier: 占位待冻结}（字段未冻结）。
 *
 * <h2>🛑 权限码 {@code doc:write}（仅 admin 三端）</h2>
 * 文书模板是总部维护的内容资产（同 scale_item_bank）。I1~I6 贴 {@code doc:write}；
 * I8（渲染）是 {@code x-client-forbidden: true} 内部端点，贴 {@code doc:write} 且无客户端可见面。
 *
 * <h2>🛑 版本不可覆盖 + 发布独占</h2>
 * I4 新版本 = 插入新行（不覆盖）；I6 发布 = 同 doc_type 唯一 active（部分唯一索引）。
 */
@RestController
@RequestMapping("/api/v1")
public class DocTemplateController {

    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final DocTemplateService service;

    public DocTemplateController(DocTemplateService service) {
        this.service = service;
    }

    // ==================================================================
    // I1 · GET /doc-templates
    // ==================================================================

    @RequirePermission("doc:write")
    @GetMapping("/doc-templates")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "doc_type", required = false) String docType,
            @RequestParam(value = "is_active", required = false) Boolean isActive) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        List<DocTemplateRow> rows = service.list(tenantId, docType, isActive);
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocTemplateRow r : rows) {
            items.add(toListItem(r));
        }
        data.put("items", items);
        data.put("count", items.size());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // I2 · POST /doc-templates（editor）
    // ==================================================================

    public record CreateRequest(
            @JsonProperty("doc_type") String docType,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("placeholder_schema") String placeholderSchema) {
    }

    @RequirePermission("doc:write")
    @PostMapping("/doc-templates")
    public Result<Map<String, Object>> create(@RequestBody(required = false) CreateRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        if (body == null || body.docType() == null || body.content() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "doc_type/title/content 必填");
        }
        DocTemplateRow row = service.createEditor(tenantId, body.docType(), body.title(),
                body.content(), body.placeholderSchema(), TenantContext.staffId());
        return Result.ok(toDetail(row), MDC.get("traceId"));
    }

    // ==================================================================
    // I4 · POST /doc-templates/{id}/versions
    // ==================================================================

    public record NewVersionRequest(
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("placeholder_schema") String placeholderSchema,
            @JsonProperty("change_reason") String changeReason) {
    }

    @RequirePermission("doc:write")
    @PostMapping("/doc-templates/{id}/versions")
    public Result<Map<String, Object>> newVersion(@PathVariable("id") String id,
                                                   @RequestBody(required = false) NewVersionRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        DocTemplateRow row = service.newVersion(tenantId, uuid(id, "id"),
                body == null ? null : body.title(),
                body == null ? null : body.content(),
                body == null ? null : body.placeholderSchema(),
                body == null ? null : body.changeReason(),
                TenantContext.staffId());
        return Result.ok(toDetail(row), MDC.get("traceId"));
    }

    // ==================================================================
    // I5 · GET /doc-templates/{id}/versions
    // ==================================================================

    @RequirePermission("doc:write")
    @GetMapping("/doc-templates/{id}/versions")
    public Result<Map<String, Object>> listVersions(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        List<DocTemplateRow> rows = service.listVersions(tenantId, uuid(id, "id"));
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocTemplateRow r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("template_id", r.templateId().toString());
            one.put("version", r.version());
            one.put("source_type", r.sourceType());
            one.put("file_hash", r.fileHash());
            one.put("change_reason", r.changeReason());
            one.put("is_active", r.isActive());
            items.add(one);
        }
        data.put("items", items);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // I6 · POST /doc-templates/{id}/versions/{version}/publish
    // ==================================================================

    @RequirePermission("doc:write")
    @PostMapping("/doc-templates/{id}/versions/{version}/publish")
    public Result<Map<String, Object>> publish(@PathVariable("id") String id,
                                               @PathVariable("version") int version) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        service.publish(tenantId, uuid(id, "id"), version);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template_id", id);
        data.put("version", version);
        data.put("published", true);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // I8 · POST /agreements/{id}/render
    // ==================================================================

    public record RenderRequest(
            @JsonProperty("template_id") String templateId,
            @JsonProperty("version") Integer version,
            @JsonProperty("placeholders") Map<String, String> placeholders) {
    }

    @RequirePermission("doc:write")
    @PostMapping("/agreements/{id}/render")
    public Result<Map<String, Object>> render(@PathVariable("id") String id,
                                               @RequestBody(required = false) RenderRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        if (body == null || body.templateId() == null || body.version() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "template_id/version 必填");
        }
        Map<String, Object> result = service.render(tenantId, uuid(body.templateId(), "template_id"),
                body.version(), body.placeholders());
        return Result.ok(result, MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述
    // ==================================================================

    @RequirePermission("doc:write")
    @GetMapping("/doc-templates/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "I-文书模板与渲染（本类实现 I1/I2/I4/I5/I6/I8；I3 上传 / I7 下载另立文件通道）");
        m.put("endpoints", List.of(
                "I1 GET /doc-templates",
                "I2 POST /doc-templates（editor）",
                "I4 POST /doc-templates/{id}/versions",
                "I5 GET /doc-templates/{id}/versions",
                "I6 POST /doc-templates/{id}/versions/{v}/publish",
                "I8 POST /agreements/{id}/render"));
        m.put("frontier", "占位待冻结（契约 x-frontier）—— 字段未冻结，入参形状由本域冻结");
        m.put("render_rules", List.of(
                "① 占位符只从 placeholder_schema 白名单取值，未声明零占位符 fail-closed",
                "② 未在白名单 → 2004 PLACEHOLDER_OUT_OF_SCOPE，不静默留空",
                "③ customer.phone 强制脱敏（前 3 后 4）",
                "④ 渲染结果落快照 + SHA-256"));
        m.put("version_semantics", "版本不可覆盖；I6 发布 = 同 doc_type 唯一 active（部分唯一索引）");
        m.put("pending", "I3 上传（multipart + 对象存储）/ I7 下载（字节流 + 超管）属文件系统通道，待对象存储接入");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
    }

    private static Map<String, Object> toListItem(DocTemplateRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", r.templateId().toString());
        m.put("title", r.title());
        m.put("doc_type", r.docType());
        m.put("version", r.version());
        m.put("is_active", r.isActive());
        m.put("source_type", r.sourceType());
        return m;
    }

    private static Map<String, Object> toDetail(DocTemplateRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", r.templateId().toString());
        m.put("doc_type", r.docType());
        m.put("title", r.title());
        m.put("version", r.version());
        m.put("is_active", r.isActive());
        m.put("source_type", r.sourceType());
        m.put("content", r.content());
        m.put("placeholder_schema", r.placeholderSchema());
        return m;
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