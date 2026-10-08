package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient

/**
 * 域 E · 手环数据（E3 遥测 / E4 派生）
 * ============================================================================
 *
 * 🛑 ③ 组（缺口原因）与 ④ 组（派生结果）**客户恒不可见**，本端可见
 * ---------------------------------------------------------------------------
 * 契约 `x-visibility-matrix` 实测：`gap_reason` / `derived_result` 两组对
 * `client` 为 false，对 `therapist` / `meridian` / `admin` 为 true。
 * 这条**不在本层实现**（服务端裁剪即事实）——
 * 一旦看到本端某字段缺失，应先怀疑契约矩阵与 A2 档位，而不是先怀疑"后端漏发了"。
 *
 * 🛑 本层只读，**不做任何减法**
 * ---------------------------------------------------------------------------
 * ④ 组（A3 / AS / effect_verdict / refund_eligibility）由服务端算（X-1）。
 * 在客户端"顺手算一下"就是造第二份权威。
 */
class BandApi(private val api: ApiClient) {

    /**
     * E3 手环遥测（③ 组）。
     *
     * 🛑 `gap_reason` 是 7 值枚举，界面必须经
     *    [com.diaoyuanyun.therapist.ui.theme.Labels.gapReason] 取标签 ——
     *    直接渲染原始码会让一线看到 `compliant_removal` 这类英文码，
     *    而"按说明书摘除"与"未佩戴"在业务上是**完全不同**的两回事。
     */
    suspend fun getBandTelemetry(role: String, customerId: String): BandTelemetryData? = api.one(
        ENDPOINT_E3, role, params = mapOf("id" to customerId),
        type = BandTelemetryData::class.java,
    )

    /** E4 手环派生（④ 组；客户恒不下发）。 */
    suspend fun getBandDerived(role: String, customerId: String): BandDerivedData? = api.one(
        ENDPOINT_E4, role, params = mapOf("id" to customerId),
        type = BandDerivedData::class.java,
    )

    private companion object {
        const val ENDPOINT_E3 = "getBandTelemetry"
        const val ENDPOINT_E4 = "getBandDerived"
    }
}
