package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code daily_report} 一行（V5 §2.16）—— 每日填报的落库载体。
 *
 * <h2>契约 / PRD 依据</h2>
 * PRD §2.16 + 契约 {@code DailyReportRequest / DailyReportData}：
 * <pre>
 *   date         业务日（补填窗口 ≤2 天，config #11）
 *   answers_json 全点选（无开放输入）
 *   source       CHECK 客户 / 代核（代录须标"代核"）
 * </pre>
 *
 * <h2>🛑 {@code source} 只有两个合法字面</h2>
 * {@code 客户}（客户本人填报）与 {@code 代核}（门店/调理师代录）。
 * 代录必须标"代核"，否则把"代录"伪装成"客户自查"会让数据出处失真 ——
 * 而每日填报率是北极星指标之一（G3），出处错 = 指标错。
 * 故该值 fail-closed：不在两值内即拒。
 *
 * <h2>构造期校验（fail-closed，报错指向成因而非库层 CHECK）</h2>
 * 复述 V5 的 NOT NULL 与 source CHECK。不校验"补填窗口"（那是服务层的时间窗口逻辑，
 * 依赖 config #11，属运行期口径而非结构事实）。
 */
public record DailyReportRow(
        UUID reportId,
        UUID customerId,
        LocalDate date,
        String answersJson,
        String source,
        Instant submittedAt,
        String createdBy) {

    public DailyReportRow {
        requireNonNull(reportId, "填报主键（report_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        requireNonNull(date, "业务日（date）");
        requireNonBlank(answersJson, "填报答案（answers_json）");
        requireNonNull(submittedAt, "提交时点（submitted_at）");
        if (!"客户".equals(source) && !"代核".equals(source)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "source 必须是『客户』或『代核』: 实际=" + source
                            + "。PRD §2.16：代录须标『代核』，否则把代录伪装成客户自查、"
                            + "每日填报率（北极星 G3）的出处即失真");
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
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 不得为空白字符串 —— 空白会落成一个「看起来有值」的空串");
        }
    }
}