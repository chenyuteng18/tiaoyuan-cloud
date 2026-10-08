package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.google.gson.JsonObject

/**
 * 域 B · 客户建档与知情（B1 ~ B6）
 * ============================================================================
 *
 * 🛑 本层的**顺序语义**是契约的一部分，不是流程偏好
 * ---------------------------------------------------------------------------
 *   · B1（禁忌筛查）→ B2（建档）：契约 `CustomerCreateRequest.screening_id` 逐字
 *     「服务端校验其 `result=通过`」。即"没通过筛查就建不了档"是**服务端硬门禁**，
 *     本层只需把 `screening_id` 从 B1 的返回**带入** B2 —— 但**不得**由人手抄 UUID
 *     （手抄会让"筛查未通过"的客户被一个旧 id 蒙混过去）。
 *   · B2 成功后 `customer_id` 带入 B3（签同意书）/ B5 / B6。
 *     契约顺序是**筛查 → 建档 → 同意书**。
 */
class IntakeApi(private val api: ApiClient) {

    /** B1 禁忌筛查提交（硬门禁①）。 */
    suspend fun createScreeningRecord(
        role: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): ScreeningData? = api.one(
        ENDPOINT_SCREENING, role, body = body, idempotencyKey = idempotencyKey,
        type = ScreeningData::class.java,
    )

    /**
     * B2 建档。
     *
     * 🛑 `screening_id` **必须**来自 B1 的返回（见类注释）。
     * 🛑 本方法**不做**"筛查是否通过"的判断 —— 那是服务端门禁（X-1）。
     *    前端若自作聪明拦一道，两处判定不一致时无从判断谁对。
     */
    suspend fun createCustomer(
        role: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): CustomerCreateData? = api.one(
        ENDPOINT_CUSTOMER, role, body = body, idempotencyKey = idempotencyKey,
        type = CustomerCreateData::class.java,
    )

    /**
     * B3 签署同意书。
     *
     * 🛑 契约对该 operation 的 200 `data` **未声明形状** ⇒ 原样返回 [JsonObject]，
     *    **不臆造字段**（臆造的字段名会拼出服务端不认识的键，请求静默降级，
     *    而编译 / 构建全绿）。
     */
    suspend fun signConsent(
        role: String,
        customerId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_CONSENT, role,
        params = mapOf("id" to customerId),
        body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** B4 客户详情。 */
    suspend fun getCustomer(role: String, customerId: String): CustomerDetailData? = api.one(
        ENDPOINT_CUSTOMER_GET, role,
        params = mapOf("id" to customerId),
        type = CustomerDetailData::class.java,
    )

    /** B5 入档档案读取（`data` 形状契约未声明 ⇒ 原样透传）。 */
    suspend fun getIntakeProfile(role: String, customerId: String): JsonObject? = api.raw(
        ENDPOINT_INTAKE, role, params = mapOf("id" to customerId),
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * B6 入档档案修改（`data` 形状契约未声明 ⇒ 原样透传）。
     *
     * 🛑 这里曾经**指向了 B5 的端点 id**（`ENDPOINT_INTAKE = "getIntakeProfile"`）——
     *    一个 GET 的端点被当成 PATCH 用，body 与幂等键发出去而服务端根本不读。
     *    它能编译、能构建、`gen-endpoints.py --check` 也绿，**只有真发一次请求才会暴露**。
     *    这与本仓第 50 条同型：**"写下的端点"≠"契约里那个端点"**。
     *    故 B5 / B6 各有自己的常量，并由 android-check 的 `wrapper-own-endpoint`
     *    判据钉住（同一个端点 id 常量**不得**被两个不同的封装函数共用）。
     */
    suspend fun patchIntakeProfile(
        role: String,
        customerId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_INTAKE_PATCH, role,
        params = mapOf("id" to customerId),
        body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    private companion object {
        const val ENDPOINT_SCREENING = "createScreeningRecord"
        const val ENDPOINT_CUSTOMER = "createCustomer"
        const val ENDPOINT_CONSENT = "signConsent"
        const val ENDPOINT_CUSTOMER_GET = "getCustomer"
        const val ENDPOINT_INTAKE = "getIntakeProfile"
        const val ENDPOINT_INTAKE_PATCH = "patchIntakeProfile"
    }
}
