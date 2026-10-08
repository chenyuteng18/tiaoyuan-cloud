package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.diaoyuanyun.therapist.api.Paging
import com.google.gson.JsonObject

/**
 * 域 D · 履约与方案（D1 ~ D6）
 * ============================================================================
 *
 * 🛑 两处与本端角色强相关的硬口径（不得由调用点随便决定）
 * ---------------------------------------------------------------------------
 *   1. **D3 日报的来源恒为「代核」**：契约 `DailyReportRequest.source` 的取值是
 *      `客户 / 代核`，而端 B 是**内部作业端** —— 一线在此提交的日报都是
 *      代客户核录的。故本端把 `source` 写死为 [SOURCE_PROXY]，**不提供选择器**：
 *      让一线能选"客户"，会让一条本该标记为"代核"的记录变成"客户自报"，
 *      而这个差别在数据侧是**不可逆的语义污染**。
 *   2. **D5 方案三支（D5-a 提交 / D5-b 查阅 / D5-c 复核）共用同一路径前缀**，
 *      但行标识不同 ⇒ 界面必须让三者都可到达（本仓第 63 条）。
 *
 * 🛑 契约未声明形状的三处（原样透传，**不臆造字段**）
 * ---------------------------------------------------------------------------
 *   · `createVisit` / `createDeviceDispatch` / `createPlan` / `reviewPlan` 的
 *     **requestBody 未声明** —— 唯一真相源是后端控制器 record（本仓 README 已登记）。
 *     故本层收 `Map<String, Any?>` 并要求调用点**逐字对齐控制器字段名**。
 *   · `listVisits` / `listDailyReports` / `createPlan` / `getPlan` / `reviewPlan` /
 *     `createDeviceDispatch` 的 **200 data 未声明** ⇒ 原样返回 [JsonObject]。
 */
class FulfillmentApi(private val api: ApiClient) {

    /** D1 履约记录创建（requestBody 契约未声明 ⇒ 字段名对齐后端 record）。 */
    suspend fun createVisit(
        role: String,
        customerId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): VisitData? = api.one(
        ENDPOINT_D1, role, params = mapOf("id" to customerId),
        body = body, idempotencyKey = idempotencyKey,
        type = VisitData::class.java,
    )

    /**
     * D2 履约记录列表。
     *
     * 🛑 取 `data.items[]`（元素类型 [VisitData]），不是整个 `data` —— 与 Web 端同口径。
     * 🛑 分页**不夹逼**：越界由服务端按契约回 400 `VALIDATION_FAILED`。
     *    在客户端夹逼会把"我请求了 101 条却只拿到 100 条"变成无从察觉（第 58 条）。
     */
    suspend fun listVisits(
        role: String,
        customerId: String,
        page: Int? = null,
        pageSize: Int? = Paging.DEFAULT_PAGE_SIZE,
    ): List<VisitData> = api.items(
        ENDPOINT_D2, role,
        params = mapOf("id" to customerId),
        query = Paging.pageQuery(page, pageSize),
        element = VisitData::class.java,
    )

    /**
     * D3 日报提交（**代核**）。
     *
     * @param date 契约 required；**不代填** —— 由调用点给出真实日期。
     */
    suspend fun submitDailyReportAsStaff(
        role: String,
        customerId: String,
        date: String,
        answersJson: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): DailyReportData? {
        val body = linkedMapOf<String, Any?>(
            "date" to date,
            "answers_json" to answersJson,
            // 🛑 写死「代核」，不提供选择（见类注释第 1 条）
            "source" to SOURCE_PROXY,
        )
        return api.one(
            ENDPOINT_D3, role, params = mapOf("id" to customerId),
            body = body, idempotencyKey = idempotencyKey,
            type = DailyReportData::class.java,
        )
    }

    /** D4 日报列表（取 `data.items[]`，元素类型 [DailyReportData]，与 Web 端同口径）。 */
    suspend fun listDailyReports(
        role: String,
        customerId: String,
        page: Int? = null,
        pageSize: Int? = Paging.DEFAULT_PAGE_SIZE,
    ): List<DailyReportData> = api.items(
        ENDPOINT_D4, role,
        params = mapOf("id" to customerId),
        query = Paging.pageQuery(page, pageSize),
        element = DailyReportData::class.java,
    )

    /** D5-a 方案提交（requestBody 契约未声明 ⇒ 字段名对齐后端 record）。 */
    suspend fun createPlan(
        role: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_D5A, role, body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** D5-b 方案查阅（`data` 形状契约未声明 ⇒ 原样透传）。 */
    suspend fun getPlan(role: String, planId: String): JsonObject? = api.raw(
        ENDPOINT_D5B, role, params = mapOf("id" to planId),
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** D5-c 方案复核（requestBody 契约未声明 ⇒ 字段名对齐后端 record）。 */
    suspend fun reviewPlan(
        role: String,
        planId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_D5C, role, params = mapOf("id" to planId),
        body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /** D6 设备派发（requestBody 契约未声明 ⇒ 字段名对齐后端 record）。 */
    suspend fun createDeviceDispatch(
        role: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_D6, role, body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    companion object {
        /**
         * 契约 `DailyReportRequest.source` 中的「代核」取值 —— **逐字**抄契约枚举。
         * 🛑 不得改成"代录"「代办」等近义词：服务端按枚举值匹配，写错即 1001。
         */
        const val SOURCE_PROXY = "代核"

        private const val ENDPOINT_D1 = "createVisit"
        private const val ENDPOINT_D2 = "listVisits"
        private const val ENDPOINT_D3 = "submitDailyReport"
        private const val ENDPOINT_D4 = "listDailyReports"
        private const val ENDPOINT_D5A = "createPlan"
        private const val ENDPOINT_D5B = "getPlan"
        private const val ENDPOINT_D5C = "reviewPlan"
        private const val ENDPOINT_D6 = "createDeviceDispatch"
    }
}
