package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * {@code requested_at} 的来源标注（PRD P0-19 C1-2）。
 *
 * <h2>为什么"来源"必须是一个独立可判定的值，而不是一个备注</h2>
 * P0-19 的 C1-2 把 24h 计时钉在两件事上：
 * <pre>
 *   requested_at = 最早且【可核实】
 *   可核实集合 = { 客户可自证的时间（须附凭据引用字段）, 调理师转交提交时间 }
 *   取较早者；客户无凭据时用调理师转交时间。24h 一律从 requested_at 起算。
 *   requested_at_claimed = 客户主张的时间，仅留存、【永不进 24h 计时】。
 * </pre>
 * 于是"这一条 {@code requested_at} 凭什么算数"必须被系统记下来 ——
 * 否则任何一次争议里，我方只能回答"系统里就是这时间"，而回答不了"它为什么是这时间"。
 *
 * <p>三值里 {@link #MERIDIAN_ACCEPTED} 与前两者的性质<b>不同</b>：
 * 前两者是"客户诉求在系统外已经发生"的两个可核实锚点；第三者是"经络师在系统内受理"，
 * 它是<b>系统内</b>事件、时间天然可核实，但<b>不是</b>最早的 ——
 * 它只在客户既无凭据、又无调理师转交时作为兜底锚点（此时 24h 从受理起算，
 * 对门店是<b>更宽松</b>的，故必须由门店主动声明"我确实没更早的锚点"才允许使用）。
 *
 * <p>🛑 {@link #isVerifiableInEarlySet()} 只对前两者为 {@code true} ——
 * 这条区分是"可核实集合取较早"的机械落点：{@link #MERIDIAN_ACCEPTED} 参与
 * "取较早"时会把一个更晚的时间伪装成更早（因为它可能在客户已自证之后才创建），
 * 故它<b>不进</b>取较早的比较集合，只在无更早锚点时单独使用。
 */
public enum RequestedAtSource {

    /** 客户自证（截图 / 通话记录）—— 必须附凭据引用（{@code requested_at_source_ref}）。 */
    CUSTOMER_PROVEN("客户自证", true),

    /** 调理师转交提交时间（复用 05 表「异常 / 备注」列，系统自动识别路由生成转交代办）。 */
    THERAPIST_HANDOFF("调理师转交", true),

    /** 经络师受理（系统内事件，仅在前两者皆无时作为兜底锚点）。 */
    MERIDIAN_ACCEPTED("经络师受理", false);

    private final String code;
    private final boolean inEarlyComparisonSet;

    RequestedAtSource(String code, boolean inEarlyComparisonSet) {
        this.code = code;
        this.inEarlyComparisonSet = inEarlyComparisonSet;
    }

    /** 字面（与 V6 迁移的 {@code refund_requested_at_source_check} 逐字一致）。 */
    public String code() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RequestedAtSource::code).toList();
    }

    public static RequestedAtSource parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "requested_at 来源标注（requested_at_source）必填 —— "
                            + "无来源标注的 requested_at 视为不可核实，不得进 24h 计时（P0-19 C1-2）");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(s -> s.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "requested_at 来源标注不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }

    /** 是否属于"可核实集合"（C1-2 取较早的比较域）。 */
    public boolean isVerifiableInEarlySet() {
        return inEarlyComparisonSet;
    }

    /** 本来源是否必须附凭据引用（仅客户自证：截图 / 通话记录须可提交）。 */
    public boolean requiresEvidenceRef() {
        return this == CUSTOMER_PROVEN;
    }
}