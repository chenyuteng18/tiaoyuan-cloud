package com.diaoyuanyun.dy.app.doctpl.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code doc_template} 一行（V5 §2.27）—— 文书模板的落库载体。
 *
 * <h2>契约 / PRD 依据</h2>
 * 契约域 I + PRD §2.27「文书模板」：
 * <pre>
 *  doc_type   六值（知情同意书/调理协议书/手环数据说明/隐私与授权须知/到店须知/其他）
 *  version    版本递增·不可覆盖（默认 1）
 *  is_active  同 doc_type 下最多 1 个 true（部分唯一索引）
 *  source_type editor(在线编辑) / upload(上传) 两形态
 *  mime_type  仅三值（pdf / docx / markdown）
 *  file_size  ≤ 10 MiB
 *  file_hash  SHA-256 hex 64 字符
 * </pre>
 *
 * <h2>🛑 content 与 file_ref 不得同时为空（库层 ck_doc_template_content_or_file）</h2>
 * 形态① editor：content 有值、file_ref NULL；形态② upload：file_ref/file_hash/mime_type/file_size
 * 皆有值。本 record 在构造期做同一条校验（应用层镜像），报错指向构造点而非库层 23514。
 *
 * <h2>构造期校验</h2>
 * 复述 V5 NOT NULL / CHECK。doc_type 六值、source_type 两值、mime_type 三值、file_size 上限。
 */
public record DocTemplateRow(
        UUID templateId,
        String docType,
        String title,
        String content,
        int version,
        boolean isActive,
        String sourceType,
        String fileRef,
        String fileName,
        String mimeType,
        Long fileSize,
        String fileHash,
        String placeholderSchema,
        String changeReason,
        Instant createdAt,
        String createdBy) {

    public static final java.util.Set<String> DOC_TYPES = java.util.Set.of(
            "知情同意书", "调理协议书", "手环数据说明", "隐私与授权须知", "到店须知", "其他");
    public static final java.util.Set<String> SOURCE_TYPES = java.util.Set.of("editor", "upload");
    public static final java.util.Set<String> MIME_TYPES = java.util.Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown");
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;   // 10 MiB

    public DocTemplateRow {
        requireNonNull(templateId, "模板主键（template_id）");
        if (docType == null || !DOC_TYPES.contains(docType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "doc_type 非法: '" + docType + "'（六值之一）");
        }
        requireNonBlank(title, "标题（title）");
        if (version < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "version 须 ≥ 1");
        }
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "source_type 非法: '" + sourceType + "'（editor/upload）");
        }
        if (mimeType != null && !MIME_TYPES.contains(mimeType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "mime_type 仅三值（pdf/docx/markdown）: '" + mimeType + "'");
        }
        if (fileSize != null && (fileSize <= 0 || fileSize > MAX_FILE_SIZE)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "file_size 须 >0 且 ≤ 10 MiB: " + fileSize);
        }
        // 🛑 content 与 file_ref 不得同时为空（库层约束的应用层镜像）
        if ("editor".equals(sourceType) && (content == null || content.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "source_type=editor 时 content 必填");
        }
        if ("upload".equals(sourceType)
                && (fileRef == null || fileHash == null || mimeType == null || fileSize == null)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "source_type=upload 时 file_ref/file_hash/mime_type/file_size 四者必填");
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
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 不得为空白");
        }
    }
}