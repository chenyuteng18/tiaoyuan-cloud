package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.google.gson.JsonObject

/**
 * 域 G · 退款与挽留（G1 代录 / G2 查询 / G3 挽留 / G5 回执）
 * ============================================================================
 *
 * 🛑 端 B 是退款通路的**唯一入口**，且入口只有两条（契约逐字）
 * ---------------------------------------------------------------------------
 * 业务方 2026-09-16 补充裁定：「**客户端不出现任何退款字样与入口**」——
 * 两条通路（A 门店代录 / B 首周期）的入口**都在门店侧**，由经络师 / 门店负责人
 * **在本端**代录发起，系统按规则自动算出金额。
 * ⇒ 故本端**允许**出现退款相关措辞（词表 SCOPE 只覆盖客户端包；
 *    见 `compliance/wordlists/scan1_refund.words` 的 SCOPE 段），
 *    但**不得**在本端任何位置提供"客户自助发起退款"的入口 —— 那与裁定直接冲突。
 *
 * 🛑 契约 `RefundCreateRequest` 的必填项（不得省略、不得臆造）
 * ---------------------------------------------------------------------------
 *   required: `customer_id` / `entry` / `refund_route` / `reason_code` / `requested_at`
 *   · `entry` 逐字枚举：`A门店代录` / `B首周期`
 *   · `refund_route` 逐字枚举：`履约类` / `效果类`
 *   · `reason_code` 六值枚举（效果未达预期 / 症状加重或出现新不适 / 服务体验或沟通问题 /
 *     时间·经济·家庭原因 / 配合度不足导致无明显变化 / 信任或价格异议）
 *   本层**不提供默认值** —— 退款诉求的口径一旦被默认值污染，后续所有统计都会偏。
 */
class RefundApi(private val api: ApiClient) {

    /**
     * G1 退款诉求代录。
     *
     * @param body 字段名逐字对齐契约 `RefundCreateRequest`。
     *             🛑 `entry` / `refund_route` / `reason_code` 三项**必须**落在契约枚举内，
     *             本层不做合法性判断（枚举是服务端的事），但**不做任何默认填充**。
     */
    suspend fun createRefund(
        role: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): RefundData? = api.one(
        ENDPOINT_G1, role, body = body, idempotencyKey = idempotencyKey,
        type = RefundData::class.java,
    )

    /** G2 退款单查询（`data` 形状契约未声明 ⇒ 原样透传）。 */
    suspend fun getRefund(role: String, refundId: String): JsonObject? = api.raw(
        ENDPOINT_G2, role, params = mapOf("id" to refundId),
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** G3 挽留记录（`data` 形状契约未声明 ⇒ 原样透传）。 */
    suspend fun createRetention(
        role: String,
        refundId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_G3, role, params = mapOf("id" to refundId),
        body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** G5 24h 回执（回执状态三值枚举逐字抄契约）。 */
    suspend fun createRefundReceipt(
        role: String,
        refundId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): RefundReceiptData? = api.one(
        ENDPOINT_G5, role, params = mapOf("id" to refundId),
        body = body, idempotencyKey = idempotencyKey,
        type = RefundReceiptData::class.java,
    )

    private companion object {
        const val ENDPOINT_G1 = "createRefund"
        const val ENDPOINT_G2 = "getRefund"
        const val ENDPOINT_G3 = "createRetention"
        const val ENDPOINT_G5 = "createRefundReceipt"
    }
}
