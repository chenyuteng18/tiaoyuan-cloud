package com.diaoyuanyun.therapist.ui.theme

/**
 * 端 B · 契约枚举 → 中文标签 + 语义色
 * ============================================================================
 *
 * 逐条转录自 `frontends/therapist-app/src/ui/tokens.ts` 的三张表 ——
 * **键名逐字**抄契约枚举，不改写、不加值、不合并。
 *
 * 🛑 为什么键名必须逐字（本仓第 54 条同族）
 * ---------------------------------------------------------------------------
 * 这三张表是"契约枚举值 → 人能读的标签"的唯一映射点。键名若被"顺手规范化"
 * （例如把 `not_worn` 改成 `notWorn`），映射会在服务端下发真实枚举值时**静默落空**
 * —— 而落空的表现是"显示原始英文码"，不是报错。故键名与契约逐字对齐，
 * 并由 android-check 的 `enum-labels-verbatim` 判据核对。
 *
 * 🛑 这里**只有解释，没有归责**
 * ---------------------------------------------------------------------------
 * ③ 组是「缺口原因**分类**」，是事实记录，不是评价：`not_worn` 与
 * `compliant_removal` 必须分开显示，且**都不能**被读作客户过错。
 * 契约 x-field-groups 的定性是"分类"，本表不得越出这个定性。
 */
object Labels {

    /**
     * ③ 组（缺口原因）7 值 → 标签 + 状态色。
     * 键名逐字取自契约 `BandTelemetryData.gap_reason`。
     */
    val GAP_REASON: Map<String, Label> = mapOf(
        "no_open" to Label("未开启（授权 / 开关）", Tone.WARN),
        "sync_failed" to Label("同步失败", Tone.WARN),
        "not_worn" to Label("未佩戴", Tone.INFO),
        "compliant_removal" to Label("按说明书摘除（洗浴 / 桑拿 / 游泳等）", Tone.OK),
        "involuntary_technical" to Label("非自愿技术性缺失", Tone.DANGER),
        "beyond_retention_window" to Label("超出厂商保留窗口", Tone.INFO),
        "unknown" to Label("未分类（待核）", Tone.NEUTRAL),
    )

    /**
     * ④ 组 `effect_verdict` 5 值 → 标签 + 状态色。
     * 键名逐字取自契约（含中文枚举值本身，**不得**改成拼音或英文）。
     *
     * 🛑 **仅端 B / 端 A 可用**：契约 `BandDerivedData.effect_verdict` 的
     *    `x-visible-to` 是 `[therapist, meridian, admin]`，客户恒不下发。
     */
    val EFFECT_VERDICT: Map<String, Label> = mapOf(
        "E1显著改善" to Label("E1 显著改善", Tone.OK),
        "E2部分改善" to Label("E2 部分改善", Tone.OK),
        "E3稳定" to Label("E3 稳定", Tone.INFO),
        "E4无明显改善" to Label("E4 无明显改善", Tone.WARN),
        "E5加重" to Label("E5 加重", Tone.DANGER),
    )

    /** `customer.status` 5 值 → 标签（逐字抄契约枚举）。 */
    val CUSTOMER_STATUS: Map<String, String> = mapOf(
        "CREATED" to "已建档",
        "PROFILED" to "已完善档案",
        "CONSENTED" to "已签同意书",
        "REJECTED" to "已拒绝",
        "ARCHIVED" to "已归档",
    )

    /** 未知枚举值的安全兜底：**显示原码**，不猜、不吞。 */
    fun gapReason(raw: String?): Label =
        GAP_REASON[raw] ?: Label(raw ?: "—", Tone.NEUTRAL)

    fun effectVerdict(raw: String?): Label =
        EFFECT_VERDICT[raw] ?: Label(raw ?: "—", Tone.NEUTRAL)

    fun customerStatus(raw: String?): String =
        CUSTOMER_STATUS[raw] ?: (raw ?: "—")
}

/** 标签 + 语义色。 */
data class Label(val text: String, val tone: Tone)
