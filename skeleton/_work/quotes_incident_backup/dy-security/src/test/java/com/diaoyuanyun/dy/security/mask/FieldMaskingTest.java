package com.diaoyuanyun.dy.security.mask;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FieldMaskingTest: 字段裁剪核心断言 (ADR-07)。
 *
 * <p>无权限字段在序列化后的 JSON 里该 key <b>完全不存在</b> (而非 null/空串), 有权限时存在。
 * 注意: 合规要求小程序包内不得含「退款」字样 (ADR-12), 这里以 {@code refund} 字段名演示裁剪语义。
 */
class FieldMaskingTest {

    /** 示范业务 DTO: 含敏感字段 refund */
    public static class RefundView {
        private String id = "c-1";
        private long refund = 8800L;
        private long amount = 12000L;
        private String note = "ok";

        public String getId() {
            return id;
        }

        public long getRefund() {
            return refund;
        }

        public long getAmount() {
            return amount;
        }

        public String getNote() {
            return note;
        }
    }

    @Test
    void forbidden_field_is_removed_entirely_from_json() {
        RefundView view = new RefundView();
        // 无 refund 权限 -> refund 字段整体移除
        String json = FieldMasker.mask(view, Set.of("refund"));

        assertFalse(json.contains("\"refund\""), "无权限时 refund key 必须不存在, 实际: " + json);
        assertTrue(json.contains("\"id\""), "有权限字段 id 应存在");
        assertTrue(json.contains("\"amount\""), "有权限字段 amount 应存在");
        assertTrue(json.contains("\"note\""), "有权限字段 note 应存在");
    }

    @Test
    void permitted_field_remains_in_json() {
        RefundView view = new RefundView();
        // 无禁止项 -> refund 应出现
        String json = FieldMasker.mask(view, Set.of());

        assertTrue(json.contains("\"refund\""), "有权限时 refund key 应存在, 实际: " + json);
        assertTrue(json.contains("\"amount\""));
    }

    @Test
    void multiple_forbidden_fields_all_removed() {
        RefundView view = new RefundView();
        String json = FieldMasker.mask(view, Set.of("refund", "note"));

        assertFalse(json.contains("\"refund\""));
        assertFalse(json.contains("\"note\""));
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"amount\""));
    }
}
