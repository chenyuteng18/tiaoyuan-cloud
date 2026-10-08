package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.google.gson.JsonObject

/**
 * 域 C · 量表与评估（C1 ~ C4）
 * ============================================================================
 *
 * 🛑 C2 的两条契约硬约束必须在**提交前**本地挡住（否则一线白填一遍）
 * ---------------------------------------------------------------------------
 *   · `dimension_scores`：契约 **minItems 7 / maxItems 7** —— 恰好 7 项，
 *     不是"至少 7 项"。
 *   · `total_score`：契约 **0 ~ 112**。
 * 本层只做"提交前拦一道明显不合规的输入"，**不替代服务端校验** ——
 * 服务端仍是唯一权威（前端拦不住的形态一律由服务端 1001 拒绝）。
 *
 * 🛑 顺带一条纪律：**不自动改写用户填的 `total_score`**
 * ---------------------------------------------------------------------------
 * 页面上会把 7 项之和作为**核对提示**展示，但**不得**用求和结果覆盖用户填的
 * `total_score` —— 契约要求 `total_score` 由测量人员给出（含人工判定），
 * 自动覆盖会把一次抄写错误变成一次**无法察觉的篡改**。
 */
class AssessmentApi(private val api: ApiClient) {

    /**
     * C1 量表题库列表。
     *
     * 🛑 取 `data.items[]`（元素类型 [ScaleItemBank]），不是整个 `data` ——
     *    与 Web 端同口径。契约对该 operation 的 `data` 未声明形状（见 Models.kt 登记），
     *    但 Web 端已对齐后端 `ScaleItemBank` 的五个已知键，原生端沿用同一组键名。
     */
    suspend fun listScaleItemBanks(
        role: String,
        ageGroup: String,
        dimension: String? = null,
        version: String? = null,
    ): List<ScaleItemBank> {
        val q = LinkedHashMap<String, String>()
        // 🛑 `age_group` 是契约 **required** 的 query 参数 —— 漏传会被服务端 400，
        //    且漏传的写法在本地毫无症状（本仓第 65 条）。
        q["age_group"] = ageGroup
        dimension?.let { q["dimension"] = it }
        version?.let { q["version"] = it }
        return api.items(ENDPOINT_C1, role, query = q, element = ScaleItemBank::class.java)
    }

    /** C2 基线评估提交。 */
    suspend fun submitBaselineAssessment(
        role: String,
        customerId: String,
        scaleId: String,
        itemGroupId: String,
        ageGroupLocked: String,
        dimensionScores: List<Int>,
        totalScore: Int,
        measureOperator: String,
        idempotencyKey: String? = null,
    ): BaselineAssessmentData? {
        // —— 契约硬约束的提交前拦阻（非权威，权威在服务端）——
        if (dimensionScores.size != DIMENSION_COUNT) {
            throw IllegalArgumentException(
                "C2 契约约束：dimension_scores 必须恰好 $DIMENSION_COUNT 项" +
                    "（当前 ${dimensionScores.size} 项）。契约写的是 minItems=$DIMENSION_COUNT / " +
                    "maxItems=$DIMENSION_COUNT，不是「至少」。"
            )
        }
        if (totalScore < TOTAL_SCORE_MIN || totalScore > TOTAL_SCORE_MAX) {
            throw IllegalArgumentException(
                "C2 契约约束：total_score 须在 $TOTAL_SCORE_MIN~$TOTAL_SCORE_MAX" +
                    "（当前 $totalScore）。"
            )
        }
        val body = linkedMapOf<String, Any?>(
            "scale_id" to scaleId,
            "item_group_id" to itemGroupId,
            "age_group_locked" to ageGroupLocked,
            "dimension_scores" to dimensionScores,
            "total_score" to totalScore,
            "measure_operator" to measureOperator,
        )
        return api.one(
            ENDPOINT_C2, role, params = mapOf("id" to customerId),
            body = body, idempotencyKey = idempotencyKey,
            type = BaselineAssessmentData::class.java,
        )
    }

    /** C3 评估结果读取（`data` 形状契约未声明 ⇒ 原样透传）。 */
    suspend fun getAssessment(
        role: String,
        customerId: String,
        assessmentId: String,
    ): JsonObject? = api.raw(
        ENDPOINT_C3, role,
        params = mapOf("id" to customerId, "assessment_id" to assessmentId),
    )?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * C4 周期评估提交（`data` 形状契约未声明 ⇒ 原样透传）。
     *
     * 🛑 依从维度**刻意不含 A2**：契约红线「A2 永不参与 AS（依从度）」。
     *    调用方构造 `adherence` / `module_scores` 时必须守住这一条 ——
     *    本层不做补位、不猜缺省（契约 `CreateCycleAssessmentRequest` 的
     *    `adherence` / `module_scores` 均为 required 对象）。
     */
    suspend fun submitCycleAssessment(
        role: String,
        customerId: String,
        body: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): JsonObject? = api.raw(
        ENDPOINT_C4, role,
        params = mapOf("id" to customerId),
        body = body, idempotencyKey = idempotencyKey,
    )?.takeIf { it.isJsonObject }?.asJsonObject

    private companion object {
        const val ENDPOINT_C1 = "listScaleItemBanks"
        const val ENDPOINT_C2 = "submitBaselineAssessment"
        const val ENDPOINT_C3 = "getAssessment"
        const val ENDPOINT_C4 = "submitCycleAssessment"

        /**
         * 契约 `BaselineAssessmentRequest.dimension_scores` 的 minItems / maxItems。
         *
         * 🛑 取值来自 [ContractEnums.DIMENSION_COUNT]（= 契约具名枚举 `Dimension` 的
         *    项数），**不在这里写 7** —— 写死 7 会造出第二份"维度个数"，
         *    而它由契约 yaml 的 `Dimension.enum` 决定（由 android-check 的
         *    `named-enum-verbatim` 判据核对）。
         */
        val DIMENSION_COUNT: Int get() = ContractEnums.DIMENSION_COUNT

        /** 契约 `BaselineAssessmentRequest.total_score` 的上下界。 */
        const val TOTAL_SCORE_MIN = 0
        const val TOTAL_SCORE_MAX = 112
    }
}
