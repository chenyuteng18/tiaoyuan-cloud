package com.diaoyuanyun.dy.app.doctpl.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code DocTemplateRow} 的构造期校验 —— 复述 V5 §2.27 的 CHECK（doc_type 六值 /
 * source_type 两形态 / mime_type 三值 / file_size ≤10MiB / content·file_ref 不同时为空）。
 */
@DisplayName("文书模板行对象构造校验")
class DocTemplateRowTest {

    private static DocTemplateRow editor() {
        return new DocTemplateRow(UUID.randomUUID(), "知情同意书", "标题", "正文", 1,
                true, "editor", null, null, null, null, null, null, "初版",
                Instant.now(), "op");
    }

    @Test
    @DisplayName("doc_type 六值校验")
    void doc_type_enum() {
        for (String t : new String[]{"知情同意书", "调理协议书", "手环数据说明", "隐私与授权须知", "到店须知", "其他"}) {
            new DocTemplateRow(UUID.randomUUID(), t, "标题", "正文", 1, true, "editor",
                    null, null, null, null, null, null, "初版", Instant.now(), "op");
        }
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "病历", "标题", "正文", 1, true, "editor",
                null, null, null, null, null, null, "初版", Instant.now(), "op"));
    }

    @Test
    @DisplayName("editor 形态 content 必填、file_ref 须空")
    void editor_requires_content() {
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "知情同意书", "标题", null, 1, true, "editor",
                null, null, null, null, null, null, "初版", Instant.now(), "op"));
    }

    @Test
    @DisplayName("upload 形态 file_ref/file_hash/mime_type/file_size 必填")
    void upload_requires_file_fields() {
        // 缺失 file_hash → 拒
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "知情同意书", "标题", null, 1, true, "upload",
                "oss://x", "f.pdf", "application/pdf", 100L, null, null, "初版",
                Instant.now(), "op"));
    }

    @Test
    @DisplayName("mime_type 仅三值")
    void mime_type_enum() {
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "知情同意书", "标题", null, 1, true, "upload",
                "oss://x", "f.exe", "application/octet-stream", 100L, "hash".repeat(16),
                null, "初版", Instant.now(), "op"));
    }

    @Test
    @DisplayName("file_size > 10 MiB → 拒")
    void file_size_cap() {
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "知情同意书", "标题", null, 1, true, "upload",
                "oss://x", "f.pdf", "application/pdf", 11L * 1024 * 1024,
                "hash".repeat(16), null, "初版", Instant.now(), "op"));
    }

    @Test
    @DisplayName("version < 1 → 拒（版本递增不可覆盖）")
    void version_lt_1_fails() {
        assertThrows(BizException.class, () -> new DocTemplateRow(
                UUID.randomUUID(), "知情同意书", "标题", "正文", 0, true, "editor",
                null, null, null, null, null, null, "初版", Instant.now(), "op"));
    }
}