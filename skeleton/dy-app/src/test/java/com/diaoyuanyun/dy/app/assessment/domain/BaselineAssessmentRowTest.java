package com.diaoyuanyun.dy.app.assessment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BaselineAssessmentRow} 的构造期校验 —— 逐条复述 V5 的 CHECK / NOT NULL，
 * 使"错了什么"在进入 SQL 前被点名，而不是留给数据库报一个只有约束名的错误。
 *
 * <h2>它守什么</h2>
 * <ol>
 *   <li>必填项非空（assessment_id / customer_id / scale_id / metrics / diagnosis /
 *       measure_operator / assessed_at）；</li>
 *   <li>分龄锁定必须在 8 组枚举内（与 V5 CHECK 同字面）；</li>
 *   <li>JSON 串不得为空白（空白会落成一个"看起来有值"的空串）。</li>
 * </ol>
 */
@DisplayName("BaselineAssessmentRow 构造期校验")
class BaselineAssessmentRowTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID S = UUID.randomUUID();

    private static BaselineAssessmentRow valid() {
        return new BaselineAssessmentRow(A, C, S, null, "男16-32",
                "{\"total_score\":56}", "{\"_missing\":true}",
                false, UUID.randomUUID(), null, Instant.now(), false, "op");
    }

    @Test
    @DisplayName("合法行可构造，8 组分龄锁定被接受")
    void valid_row_constructs() {
        BaselineAssessmentRow row = valid();
        assertEquals("男16-32", row.ageGroupLocked());
        assertTrue(row.locked(), "locked 恒 true（基线锁定只读）");
        assertFalse(row.legacy());
    }

    @Test
    @DisplayName("8 组分龄锁定均被接受（与 V5 CHECK 同字面）")
    void all_eight_age_groups_accepted() {
        for (String g : List.of("男16-32", "男33-40", "男41-48", "男49以上",
                "女14-28", "女29-35", "女36-42", "女43-49以上")) {
            BaselineAssessmentRow row = new BaselineAssessmentRow(A, C, S, null, g,
                    "{}", "{\"_missing\":true}", false,
                    UUID.randomUUID(), null, Instant.now(), false, "op");
            assertEquals(g, row.ageGroupLocked());
        }
    }

    @Test
    @DisplayName("非法分龄锁定 → 阻断（不回落默认组）")
    void invalid_age_group_fails_closed() {
        assertThrows(BizException.class, () -> new BaselineAssessmentRow(
                A, C, S, null, "未知组", "{}", "{\"_missing\":true}", false,
                UUID.randomUUID(), null, Instant.now(), false, "op"));
    }

    @Test
    @DisplayName("空分龄锁定 → 阻断")
    void blank_age_group_fails() {
        assertThrows(BizException.class, () -> new BaselineAssessmentRow(
                A, C, S, null, "", "{}", "{\"_missing\":true}", false,
                UUID.randomUUID(), null, Instant.now(), false, "op"));
    }

    @Test
    @DisplayName("metrics 空白 → 阻断（空白与缺失语义不同但同样缺依据）")
    void blank_metrics_fails() {
        assertThrows(BizException.class, () -> new BaselineAssessmentRow(
                A, C, S, null, "男16-32", "  ", "{\"_missing\":true}", false,
                UUID.randomUUID(), null, Instant.now(), false, "op"));
    }

    @Test
    @DisplayName("diagnosis 空白 → 阻断")
    void blank_diagnosis_fails() {
        assertThrows(BizException.class, () -> new BaselineAssessmentRow(
                A, C, S, null, "男16-32", "{}", " ", false,
                UUID.randomUUID(), null, Instant.now(), false, "op"));
    }

    @Test
    @DisplayName("测量人必填 → null 阻断")
    void null_measure_operator_fails() {
        assertThrows(BizException.class, () -> new BaselineAssessmentRow(
                A, C, S, null, "男16-32", "{}", "{\"_missing\":true}", false,
                null, null, Instant.now(), false, "op"));
    }
}