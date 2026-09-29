package com.diaoyuanyun.dy.app.doctpl.controller;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.app.doctpl.service.DocFileService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文书模板的<b>文件通道控制器</b>（B-4）—— 契约域 I 的 I3 上传 / I7 下载。
 *
 * <p>与 {@link DocTemplateController} 分列的理由见该类的类注释与 {@link DocFileService}：
 * 载荷形态（multipart / 字节流）与错误码语义都与 JSON 端点不同，混在一起会互相污染。
 *
 * <h2>契约逐字</h2>
 * <pre>
 *  I3 POST /doc-templates/uploads              x-callable-roles: [admin]  multipart/form-data
 *  I7 GET  /doc-templates/{id}/download        x-callable-roles: [admin]  x-super-admin-only: true
 * </pre>
 * 🛑 <b>I3/I7 都要"两道"权限</b>：
 * <ol>
 *   <li>第一道（本层）：{@code @RequirePermission("doc:write")} —— 契约 {@code x-callable-roles: [admin]}
 *       的落实。🛑 不复用别的码：{@code doc:write} 已被登记为"文书模板是总部维护的内容资产"，
 *       一线角色不持（{@code PermissionRegistry} 的注释里已就此立过裁定）。</li>
 *   <li>第二道（服务层）：I7 额外的 {@code x-super-admin-only: true} —— 由
 *       {@code DocFileService.requireSuperAdmin} 在业务层再判一次。
 *       两道不是冗余：第一道依赖"权限码 → 角色"的映射表（可配置、会漂移），
 *       第二道直接判角色归属（不可配置）。**超管独占这种事不该只押在一张可变的映射表上。**</li>
 * </ol>
 *
 * <h2>🛑 I7 用 {@code ResponseEntity<byte[]>} 而不是 {@code Result<…>}</h2>
 * 契约 I7 的 200 响应体是<b>原件字节流</b>（不是 JSON 信封）。若套 {@code Result}
 * 会被 base64 或字符串化，那就不再是"原件"了（哈希必然不符）。
 * 这也是 I7 与其余端点唯一的形态差异，写在这里以免后人"顺手统一"。
 */
@RestController
@RequestMapping("/api/v1")
public class DocFileController {

    private final DocFileService service;

    public DocFileController(DocFileService service) {
        this.service = service;
    }

    // ==================================================================
    // I3 · POST /doc-templates/uploads
    // ==================================================================

    /**
     * I3 上传（multipart/form-data → source_type=upload）。
     *
     * <p>表单字段：{@code file}（必填）、{@code doc_type}（必填）、{@code title}（可选）。
     *
     * <p>🛑 幂等：同 {@code (tenant_id, doc_type, file_hash)} 重复上传 ⇒ 4002（幂等重放，返 409），
     * 由 {@link DocFileService#upload} 判定并抛出。之所以不在控制器预判：文件哈希要读完字节才知道，
     * 控制器不该为此先把整个文件读进内存两次。
     */
    @RequirePermission("doc:write")
    @PostMapping(value = "/doc-templates/uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("doc_type") String docType,
                                              @RequestParam(value = "title", required = false)
                                              String title) {
        String tenantId = requireTenant();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "file 缺失或为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取上传字节失败: " + e.getMessage());
        }
        DocTemplateRow row = service.upload(tenantId, docType, title,
                file.getOriginalFilename(), file.getContentType(), bytes,
                TenantContext.staffId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template_id", row.templateId().toString());
        data.put("doc_type", row.docType());
        data.put("version", row.version());
        data.put("source_type", row.sourceType());
        data.put("file_name", row.fileName());
        data.put("file_size", row.fileSize());
        data.put("file_hash", row.fileHash());
        data.put("file_ref", row.fileRef());
        data.put("is_active", row.isActive());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // I7 · GET /doc-templates/{id}/download
    // ==================================================================

    /**
     * I7 下载原件（上传形态用）—— 仅超管。返回原件字节流（非 JSON 信封）。
     *
     * <p>{@code Content-Disposition} 用 RFC 5987 的 {@code filename*} 形式传原始文件名：
     * 六类文书的文件名常含中文，用 {@code filename=} 直传会因 header 的 ISO-8859-1 编码而乱码。
     */
    @RequirePermission("doc:write")
    @GetMapping("/doc-templates/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id,
                                           @RequestParam("version") int version) {
        String tenantId = requireTenant();
        DocFileService.DownloadResult r =
                service.download(tenantId, uuid(id, "id"), version);

        HttpHeaders headers = new HttpHeaders();
        MediaType type = r.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(r.mimeType());
        headers.setContentType(type);
        headers.setContentLength(r.bytes().length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(r.fileName() == null ? "download" : r.fileName(),
                                StandardCharsets.UTF_8)
                        .build().toString());
        // 完整性凭据回填给调用方：下载方可据此独立复核（与登记的 file_hash 同源）
        headers.set("X-File-Sha256", r.fileHash() == null ? "" : r.fileHash());
        return new ResponseEntity<>(r.bytes(), headers, org.springframework.http.HttpStatus.OK);
    }

    // ==================================================================
    // 契约自描述
    // ==================================================================

    @RequirePermission("doc:write")
    @GetMapping("/doc-templates/uploads/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "I-文书模板与渲染（文件通道：I3 上传 / I7 下载）");
        m.put("endpoints", java.util.List.of(
                "I3 POST /doc-templates/uploads（multipart/form-data）",
                "I7 GET /doc-templates/{id}/download（字节流 · 仅超管）"));
        m.put("upload_rules", java.util.List.of(
                "① mime_type 仅三值（pdf/docx/markdown）",
                "② file_size ≤ 10 MiB（与 spring.servlet.multipart.max-file-size 同值，由门禁交叉断言）",
                "③ 先算 SHA-256 再落盘；BlobStore 侧重算自校验",
                "④ 幂等键 (tenant_id, doc_type, file_hash) → 命中返 4002",
                "⑤ 不解析可执行内容（不解压 DOCX / 不进宏 / 拒收 PE/ELF）",
                "⑥ 新版本 is_active=false（活跃由 I6 发布独占）"));
        m.put("download_rules", java.util.List.of(
                "① @RequirePermission(doc:write) 与 服务层超管判定 双道",
                "② 仅 source_type=upload 有原件（editor 形态明确报错，不返回空流）",
                "③ 读后重算 SHA-256 与登记 file_hash 比对（完整性 + 防替换）",
                "④ 响应为原始字节流（不套 Result 信封）"));
        m.put("pending", "对象存储厂商接入（本域只依赖 BlobStore 接口；默认 InMemoryBlobStore）");
        return Result.ok(m, MDC.get("traceId"));
    }

    // ==================================================================
    // 内部
    // ==================================================================

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
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 不是合法 UUID: " + raw);
        }
    }
}