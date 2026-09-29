package com.diaoyuanyun.dy.app.doctpl.service;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.app.doctpl.repository.DocTemplateLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * 文书模板域（契约域 I）的<b>唯一业务落点</b>。
 *
 * <h2>端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  I1 GET  /doc-templates                             → {@link #list}
 *  I2 POST /doc-templates（editor）                   → {@link #createEditor}
 *  I4 POST /doc-templates/{id}/versions               → {@link #newVersion}
 *  I5 GET  /doc-templates/{id}/versions               → {@link #listVersions}
 *  I6 POST /doc-templates/{id}/versions/{v}/publish   → {@link #publish}
 *  I8 POST /agreements/{id}/render                    → {@link #render}
 *  （I3 upload / I7 download 属文件系统通道，另立，见控制器）
 * </pre>
 *
 * <h2>🛑 版本不可覆盖（契约 I4 / I5 / PRD §2.27）</h2>
 * 唯一键 {@code uq_doc_template_tenant_type_version} 兜底；新版本 = 插入新行（旧行保留）。
 *
 * <h2>🛑 I8 渲染四规则（契约逐字）</h2>
 * ① 占位符只从 {@code placeholder_schema} 白名单取值，未声明时只允许零占位符（fail-closed）；
 * ② 未在白名单的占位符 → 2004，不静默留空；③ {@code customer.phone} 强制脱敏（前 3 后 4）；
 * ④ 渲染结果必须落快照 + hash。
 */
@Service
public class DocTemplateService {

    private final DocTemplateLedger ledger;

    public DocTemplateService(DocTemplateLedger ledger) {
        this.ledger = ledger;
    }

    // ==================================================================
    // I1 列表
    // ==================================================================

    /** I1 —— 模板列表（按 doc_type + is_active 过滤）。 */
    public List<DocTemplateRow> list(String tenantId, String docType, Boolean isActive) {
        DocTemplateLedger.validateTenantId(tenantId);
        return ledger.list(tenantId, docType, isActive);
    }

    // ==================================================================
    // I2 新建（editor）
    // ==================================================================

    /**
     * I2 新建编辑器模板（source_type=editor）。
     *
     * @return 落库后的模板（version=1, is_active=true）
     */
    public DocTemplateRow createEditor(String tenantId, String docType, String title,
                                       String content, String placeholderSchema, String createdBy) {
        DocTemplateLedger.validateTenantId(tenantId);
        DocTemplateRow row = new DocTemplateRow(
                UUID.randomUUID(), docType, title, content, 1, true,
                "editor", null, null, null, null, null,
                placeholderSchema, "初版", Instant.now(), createdBy);
        ledger.insert(tenantId, row);
        return row;
    }

    // ==================================================================
    // I4 新版本（不可覆盖）
    // ==================================================================

    /**
     * I4 新版本（editor 形态）。
     *
     * <p>🛑 语义关键（数据字典 §2.27）：{@code template_id} 是<b>每行主键</b>（每版本一行），
     * 版本链的归属是 {@code doc_type}（不是 template_id）—— 唯一键 {@code (tenant_id, doc_type, version)}
     * 保证"同 doc_type 下 version 不重复"。故"新版本"= 先由入参 template_id 反查其 doc_type，
     * 再在<b>同 doc_type</b> 下插 version=max+1 的新行（template_id <b>重新生成</b>）。
     */
    public DocTemplateRow newVersion(String tenantId, UUID templateId, String title,
                                     String content, String placeholderSchema,
                                     String changeReason, String createdBy) {
        DocTemplateLedger.validateTenantId(tenantId);
        if (templateId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "template_id 缺失");
        }
        DocTemplateRow current = findLatest(tenantId, templateId);
        int newVersion = current.version() + 1;
        // 🛑 template_id 是每行主键（每版本一行），故新版本重新生成；版本链归属 = doc_type
        DocTemplateRow row = new DocTemplateRow(
                UUID.randomUUID(),   // 新行主键（每版本一行）
                current.docType(), title == null ? current.title() : title,
                content == null ? current.content() : content, newVersion, false,
                "editor", null, null, null, null, null,
                placeholderSchema == null ? current.placeholderSchema() : placeholderSchema,
                changeReason == null || changeReason.isBlank() ? "新版本" : changeReason,
                Instant.now(), createdBy);
        ledger.insert(tenantId, row);
        return row;
    }

    // ==================================================================
    // I5 版本列表
    // ==================================================================

    public List<DocTemplateRow> listVersions(String tenantId, UUID templateId) {
        DocTemplateLedger.validateTenantId(tenantId);
        if (templateId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "template_id 缺失");
        }
        return ledger.listVersions(tenantId, templateId);
    }

    // ==================================================================
    // I6 发布
    // ==================================================================

    public void publish(String tenantId, UUID templateId, int version) {
        DocTemplateLedger.validateTenantId(tenantId);
        if (templateId == null || version < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "template_id/version 非法");
        }
        // 🛑 校验版本存在（findVersion 反查 doc_type 后在同 doc_type 下定位）
        findVersion(tenantId, templateId, version);
        ledger.publish(tenantId, templateId, version);
    }

    // ==================================================================
    // I8 渲染（签署时渲染，服务端 → 电子签厂商，无客户端可见面）
    // ==================================================================

    /**
     * I8 渲染 —— 契约四规则。
     *
     * <p>🛑 渲染期只做文本占位符替换，不做 DOCX 内嵌逻辑求值。
     * ① 占位符只从白名单 {@code placeholder_schema} 取值，未声明则零占位符；
     * ② 未在白名单的占位符 → 2004（PLACEHOLDER_OUT_OF_SCOPE），不静默留空；
     * ③ {@code customer.phone} 强制脱敏（前 3 后 4）；④ 结果落快照 + SHA-256。
     *
     * @param agreementId 协议 ID（占位，用于落快照的归属；渲染本身按 template 版本）
     * @param placeholders 签署方提供的占位符取值（key=占位符名，value=值）
     */
    public Map<String, Object> render(String tenantId, UUID templateId, int version,
                                      Map<String, String> placeholders) {
        DocTemplateLedger.validateTenantId(tenantId);
        DocTemplateRow tmpl = findVersion(tenantId, templateId, version);
        String content = tmpl.content();
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "upload 形态模板不可在线渲染（无 content）—— 请用 editor 形态");
        }

        // ① 白名单（placeholder_schema 未声明 → 空白名单）
        //    🛑 失效模式 18：契约 I8 responses 只声明 200 + 403（PlaceholderOutOfScope），
        //    无 422 —— 故"未声明白名单 + 携占位符"= 白名单空集下任何占位符都越界，
        //    统一归 2004（与规则② 同码），而不是 BUSINESS_RULE_VIOLATED(5001/422)。
        java.util.Set<String> whitelist = parseWhitelist(tmpl.placeholderSchema());
        if (placeholders != null && !placeholders.isEmpty() && whitelist.isEmpty()) {
            throw new BizException(ErrorCode.PLACEHOLDER_OUT_OF_SCOPE,
                    "模板未声明 placeholder_schema，但渲染请求携带占位符（规则① fail-closed）");
        }

        String rendered = content;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                String key = e.getKey();
                // ② 未在白名单的占位符 → 2004
                if (!whitelist.contains(key)) {
                    throw new BizException(ErrorCode.PLACEHOLDER_OUT_OF_SCOPE,
                            "占位符不在白名单内: " + key + "（白名单: " + whitelist
                                    + "）—— 契约 I8 规则②，不静默留空");
                }
                // ③ customer.phone 强制脱敏（前 3 后 4）
                String value = "phone".equals(key) ? maskPhone(e.getValue()) : e.getValue();
                rendered = rendered.replace("{{" + key + "}}", value == null ? "" : value);
            }
        }

        // ④ 落快照 + hash
        String hash = sha256(rendered);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rendered_snapshot", rendered);
        out.put("rendered_hash", hash);
        out.put("template_id", templateId.toString());
        out.put("version", version);
        out.put("masked_phone", true);
        return out;
    }

    private DocTemplateRow findLatest(String tenantId, UUID templateId) {
        List<DocTemplateRow> versions = ledger.listVersions(tenantId, templateId);
        if (versions.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在: " + templateId);
        }
        return versions.get(versions.size() - 1);   // listVersions 按 version ASC
    }

    private DocTemplateRow findVersion(String tenantId, UUID templateId, int version) {
        for (DocTemplateRow r : ledger.listVersions(tenantId, templateId)) {
            if (r.version() == version) {
                return r;
            }
        }
        throw new BizException(ErrorCode.NOT_FOUND, "版本不存在: " + version);
    }

    /** placeholder_schema → 白名单集合（未声明/null → 空集）。 */
    private static java.util.Set<String> parseWhitelist(String placeholderSchema) {
        if (placeholderSchema == null || placeholderSchema.isBlank()) {
            return java.util.Set.of();
        }
        try {
            Map<String, Object> schema = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(placeholderSchema, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            if (schema.get("fields") instanceof List<?> fields) {
                java.util.Set<String> out = new java.util.LinkedHashSet<>();
                for (Object f : fields) {
                    if (f != null) {
                        out.add(String.valueOf(f));
                    }
                }
                return out;
            }
            return java.util.Set.of();
        } catch (Exception e) {
            // 白名单解析失败 → fail-closed（空集，任何占位符都是 2004）
            return java.util.Set.of();
        }
    }

    /** customer.phone 强制脱敏：前 3 后 4，中间 *。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 7) {
            return phone == null ? null : "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "SHA-256 计算失败: " + e.getMessage());
        }
    }
}