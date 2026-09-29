package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 禁忌筛查结论 —— 契约域 B1 的 {@code result}，逐字对齐 V5 {@code screening_record.result} 的 CHECK。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5__remaining_entities_org_journey_verdict_refund.sql L170:
 *      result VARCHAR(16) NOT NULL CHECK (result IN ('通过', '不通过'))
 *  data-dict §2.7: result ∈ {通过, 不通过}（CHECK；IDX）
 *  契约 B1 描述: 命中禁忌（result=不通过）→ 客户状态置 REJECTED
 * </pre>
 *
 * <h2>🛑 为什么是中文两值而不是 PASSED / REJECTED</h2>
 * 库层 CHECK 逐字就是中文两值，契约 {@code ScreeningData.result} 的 enum 也是
 * {@code [通过, 不通过]}。<b>落库值 = 出参值 = 本枚举字面</b>，三者同为一份口径。
 * 若在此处"顺手英文化"（{@code PASSED} / {@code FAILED}），就必须在写库与出参
 * 各做一次映射 —— 两处映射必然分叉，且分叉的那一侧<b>不会报错</b>：
 * 它只会表现为"库里躺着一个 CHECK 不认的值"，而那要等到某次写入才 500。
 *
 * <h2>fail-closed 解析</h2>
 * {@link #of(String)} 对未登记字面一律抛（{@code 5001}），<b>绝不回落</b>。
 * 回落成一个默认值会让"库里出现了第三个值"这件事静默变成一次正常判定 ——
 * 而筛查结论是<b>硬门禁</b>的输入（不通过 ⇒ 客户 REJECTED ⇒ 后续 B2/B3 全 403），
 * 一个静默猜出来的结论会直接决定一个人能不能被建档。
 */
public enum ScreeningResult {

    /** 通过 —— B2 建档的前置（契约 B2：「需 screening_result=通过」）。 */
    PASSED("通过", "通过", true),

    /**
     * 不通过（命中禁忌）—— 硬门禁① 的触发值。
     *
     * <p>契约 B1 描述逐字：命中禁忌 → 客户状态置 {@code REJECTED}，
     * 后续 B2/B3 入口 403 {@code GATE_MISSING}（{@code missing_items=["screening_result"]}）。
     * 且该记录<b>不可删除</b>（P0-19 同类纪律：证据链不可断）。
     */
    REJECTED("不通过", "不通过", false);

    private final String code;
    private final String label;
    private final boolean passing;

    ScreeningResult(String code, String label, boolean passing) {
        this.code = code;
        this.label = label;
        this.passing = passing;
    }

    /** 契约 / 库层字面（中文两值）。落库、出参、断言一律用它。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 是否"通过"。B2 建档门禁的判据基点。 */
    public boolean isPassing() {
        return passing;
    }

    /** 严格解析：未登记一律抛（{@code 5001} 口径断裂），绝不回落。 */
    public static ScreeningResult of(String code) {
        for (ScreeningResult r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的筛查结论字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记: 通过 / 不通过；权威来源 = V5 screening_record.result 的 CHECK）—— "
                        + "🛑 不得回落为任一分支：本值直接决定客户能否被建档"
                        + "（不通过 ⇒ REJECTED ⇒ B2/B3 全 403），静默猜一个会让硬门禁失效");
    }

    /** 宽容解析：未登记返回 {@code null}（供"这一行是不是历史脏数据"的诊断场景）。 */
    public static ScreeningResult tryOf(String code) {
        for (ScreeningResult r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        return null;
    }
}