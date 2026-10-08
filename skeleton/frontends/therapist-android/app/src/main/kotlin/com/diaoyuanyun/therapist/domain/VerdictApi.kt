package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.diaoyuanyun.therapist.api.Paging

/**
 * 域 F · 判定结论（F1 生成 / F2 列表）
 * ============================================================================
 *
 * 🛑 契约 `CreateVerdictRequest` 对缺省值的红线（逐字，不得违反）
 * ---------------------------------------------------------------------------
 *   · `risk_flag`：契约逐字「🛑 缺失 ⇒ 未定（**不得默认「无」** ——
 *     那会让 D4 该触发而不触发）」。⚠️ **方向别读反**：不传是**合法**的
 *     （就是"未定"这个语义）；被禁的是代填「无」。详见 [createVerdict]。
 *   · `same_origin`：三项任一项缺省 = 不成立 = **不可比** ⇒ 判定挂起。
 *     本层不补位。
 *   · `confidence`：契约 required 对象；不得由前端臆造一个"看起来合理"的置信度 ——
 *     它是判定链的产出，不是输入（本端只在 F1 的**请求**里透传服务端此前下发的口径）。
 *
 * 🛑 本层不做分支计算
 * ---------------------------------------------------------------------------
 * D1~D5 五分支（稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核）由服务端
 * `EffectVerdictEngine` 算（X-1）。本层只读 `VerdictData.branch`。
 */
class VerdictApi(private val api: ApiClient) {

    /**
     * F1 生成判定结论。
     *
     * @param body 字段名**逐字**对齐契约 `CreateVerdictRequest`
     *             （`customer_id` / `sequence_no` / `same_origin` / `adherence` /
     *              `risk_flag` / `core_metric_improved` / `confidence` / `module_scores` …）。
     */
    suspend fun createVerdict(
        role: String,
        cycleAssessmentId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): VerdictData? {
        // 🛑 契约红线（逐字）：「risk_flag 缺失 ⇒ 未定（**不得默认「无」** ——
        //    那会让 D4 该触发而不触发）」。
        //
        // 🛑 这条红线的**方向**极易读反，必须写明：
        //    · 契约里 `risk_flag` 是**可选**的，且「缺失」有明确定义＝**未定**。
        //      ⇒ **不传是合法的**，且是默认语义。
        //    · 被禁的是"客户端在不知道时替服务端填一个「无」"
        //      —— 那会把"未评估"伪装成"已评估且为无"，从而使 D4 该触发而不触发。
        //
        // ⇒ 本层只做一件事：**若给了值，必须在契约枚举内**；绝不代填、也绝不
        //    因为"缺失"而拒绝。曾写过一版"缺失即抛"——那是把一条**合法输入**
        //    当成错误，会逼操作者随便选一个值，等于用另一种方式制造了同样的污染。
        body["risk_flag"]?.let { v ->
            if (v !is String || v !in RISK_FLAG_VALUES) {
                throw IllegalArgumentException(
                    "F1 契约枚举：risk_flag 只能取 ${RISK_FLAG_VALUES.joinToString(" / ")}" +
                        "（当前 '$v'）。不传表示【未定】——这是合法取值；" +
                        "但一旦传了就必须在枚举内。"
                )
            }
        }
        return api.one(
            ENDPOINT_F1, role, params = mapOf("id" to cycleAssessmentId),
            body = body, idempotencyKey = idempotencyKey,
            type = VerdictData::class.java,
        )
    }

    /**
     * F2 判定结论列表（取 `data.items[]`，元素类型 [VerdictData]，与 Web 端同口径）。
     *
     * 🛑 这是本端 7 个「仅经络师」端点之一 —— 出站层的 `assertCanCall` 会挡住
     *    调理师的调用（抛错），故界面侧必须**不渲染**该入口。
     */
    suspend fun listVerdicts(
        role: String,
        customerId: String,
        page: Int? = null,
        pageSize: Int? = Paging.DEFAULT_PAGE_SIZE,
    ): List<VerdictData> = api.items(
        ENDPOINT_F2, role,
        params = mapOf("id" to customerId),
        query = Paging.pageQuery(page, pageSize),
        element = VerdictData::class.java,
    )

    companion object {
        /**
         * 契约 `CreateVerdictRequest.risk_flag` 的枚举值（逐字）。
         * 🛑 **不含"未定"** —— "未定"的表示法就是**不传该字段**，不是某个枚举值。
         */
        val RISK_FLAG_VALUES: List<String> = listOf("无", "高危", "新发", "同病")

        private const val ENDPOINT_F1 = "createVerdict"
        private const val ENDPOINT_F2 = "listVerdicts"
    }
}
