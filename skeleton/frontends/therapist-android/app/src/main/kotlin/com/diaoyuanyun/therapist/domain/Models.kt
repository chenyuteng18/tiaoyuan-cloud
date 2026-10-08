package com.diaoyuanyun.therapist.domain

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * 端 B · 契约响应 / 请求模型
 * ============================================================================
 *
 * 每一段的字段**逐条抄自冻结契约** `contract/openapi-v1.0.0.yaml` 的
 * `components.schemas`（命名模型）与各 operation 的内联 requestBody。
 *
 * 🛑 本层【刻意不做】的四件事（做了才是缺陷）
 * ---------------------------------------------------------------------------
 *   1. **不裁剪字段** —— 契约 `x-global-conventions.visibility` 逐字：
 *      「无权限字段**不下发**（不是 null、不是空串）」，裁剪在服务端（X-1）。
 *      本层若"顺手把不该看的字段跳过"，就造出了第二个裁剪点，
 *      与服务端不一致时无从判断谁对。
 *   2. **不推算任何派生值** —— ④ 组（A3 / AS / effect_verdict / 退款资格）
 *      由服务端算（X-1）。本层只读 E4 的返回，不在客户端做减法。
 *   3. **不代填日期 / 不猜缺省值** —— 契约 `CreateVerdictRequest.risk_flag` 逐字：
 *      「🛑 缺失 ⇒ 未定（**不得默认「无」** —— 那会让 D4 该触发而不触发）」。
 *      同类：`same_origin` 三项任一项缺省 = 不成立 = 不可比 ⇒ 挂起。
 *   4. **不把幂等重放当失败** —— 4002 的语义是"此前已成功"（见 ApiErrors）。
 *
 * 🛑 一处**如实登记**的契约缺口（不得当成"我漏了"）
 * ---------------------------------------------------------------------------
 * 契约对下列 operation 的 **200 响应 `data` 载荷未声明形状**（实测：`data` 只写
 * `{type: object, nullable: true}`，无任何 properties）：
 *   · `signConsent` / `getIntakeProfile` / `patchIntakeProfile` / `listScaleItemBanks`
 *   · `getAssessment` / `submitCycleAssessment` / `listVisits` / `listDailyReports`
 *   · `createPlan` / `getPlan` / `reviewPlan` / `createDeviceDispatch`
 *   · `listVerdicts` / `getRefund` / `createRetention`
 * 同理，`createVisit` / `createDeviceDispatch` / `createPlan` / `reviewPlan` 四个
 * 写端点的 **requestBody 亦未声明**（本仓 README 已登记：唯一真相源是后端控制器
 * record）。⇒ 本层对这些一律用 [JsonObject] / `Map<String, Any?>` **原样透传**，
 * **不臆造字段名** —— 臆造的字段名会拼出服务端不认识的键，请求静默降级为
 * "只传了部分字段"，而编译 / 构建全绿。该缺口已登记在 README。
 */

// ===========================================================================
// 域 A · 身份与合作门店
// ===========================================================================

/** 契约 `LoginData`（A1）。 */
data class LoginData(
    val token: String,
    @SerializedName("expires_in") val expiresIn: Int? = null,
    /** 逐字抄契约枚举：client / therapist / meridian / manager / area / hq。 */
    val role: String,
    @SerializedName("client_end") val clientEnd: String? = null,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("staff_id") val staffId: String? = null,
)

/**
 * 契约 `AuthMeData.band_visibility` —— 四个字段组的档位**布尔声明**。
 *
 * 🛑 这是**声明，不是数据**（契约 description 逐字）。本层只转存，不解释、不加规则。
 * 🛑 用类型化四布尔而不是 `JsonObject`：后者要在每个消费点写
 *    `obj.get("field_group_3_gap_reason")` 这种字符串取值 —— 键名一旦写错，
 *    取到的是 `null`，而 `null` 与 `false` 在界面上会渲染成同一句话
 *    （"不可见"），于是"我拼错了键名"与"服务端确实关了"变得无法区分。
 *    类型化后拼错键名 = **编译错误**。
 */
data class BandVisibility(
    @SerializedName("field_group_1_raw") val fieldGroup1Raw: Boolean = false,
    @SerializedName("field_group_2_status") val fieldGroup2Status: Boolean = false,
    @SerializedName("field_group_3_gap_reason") val fieldGroup3GapReason: Boolean = false,
    @SerializedName("field_group_4_derived") val fieldGroup4Derived: Boolean = false,
)

/** 契约 `AuthMeData.store_scope` —— 行级范围（本层**不裁**，裁剪是服务端的事）。 */
data class StoreScope(
    /** 逐字抄契约枚举：own_store / region / all。 */
    @SerializedName("row_level") val rowLevel: String = "",
    @SerializedName("store_ids") val storeIds: List<String> = emptyList(),
)

/**
 * 契约 `AuthMeData`（A2）——**档位的唯一权威下发点**。
 *
 * 🛑 本类字段**逐条对齐契约**，一个不多、一个不少。
 *    曾经写过一版带 `display` / `tier` / `staff_id` / `store_id` 的"Profile"——
 *    那四个字段契约 `AuthMeData` 里**根本没有**（`staff_id` 只在 `LoginData`，
 *    `tenant_id` 只在 `LoginData`）。自创字段的危害与请求侧同族：
 *    Gson 反序列化时缺失字段只落 `null`，界面把它渲染成 "—"，
 *    而没人会知道是**字段名根本不存在**（本仓第 50 条）。
 */
data class AuthMeData(
    /** 逐字抄契约枚举：client / therapist / meridian / manager / area / hq。 */
    val role: String,
    /** 字段组档位（声明）。 */
    @SerializedName("band_visibility") val bandVisibility: BandVisibility? = null,
    /**
     * 退款文案可见性。契约 `x-visible-to: [meridian, admin]` ——
     * **调理师这一档不下发**（服务端不发这个字段，而不是发 false）。
     *
     * 🛑 `null` 与 `false` 语义不同，界面必须分开呈现：
     *      · `null`  = 服务端**未下发**该字段（本角色无此档位）；
     *      · `false` = 下发了、且明确为否。
     *    合并成"不可见"会让运维以为"服务端把它关了"，而实际是"该角色没有这一档"。
     */
    @SerializedName("refund_visibility") val refundVisibility: Boolean? = null,
    /** 行级范围（契约 `x-visible-to: [therapist, meridian, admin]`）。 */
    @SerializedName("store_scope") val storeScope: StoreScope? = null,
)

/** 契约 `Store`。 */
data class Store(
    @SerializedName("store_id") val storeId: String,
    val name: String,
    /** 逐字抄契约枚举：直营 / 加盟。 */
    @SerializedName("franchise_type") val franchiseType: String,
)

/** 契约 `StoreListData`（分页形状见 `x-api-protocol.pagination`）。 */
data class StoreListData(
    val items: List<Store> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    @SerializedName("page_size") val pageSize: Int = 0,
)

// ===========================================================================
// 域 B · 客户建档（B1~B6）
// ===========================================================================

/** 契约 `ScreeningData`（B1）。 */
data class ScreeningData(
    @SerializedName("screening_id") val screeningId: String,
    /** 逐字抄契约枚举：通过 / 不通过。 */
    val result: String,
    @SerializedName("submitted_at") val submittedAt: String? = null,
)

/** 契约 `CustomerCreateData`（B2）。 */
data class CustomerCreateData(
    @SerializedName("customer_id") val customerId: String,
    /**
     * 契约逐字：「5 值粗粒度派生聚合态，**不是**服务主状态机
     * （14 态见 `customer_state_transition`）」。⚠️ 界面不得把它当状态机用。
     */
    val status: String? = null,
    @SerializedName("owner_store_id") val ownerStoreId: String? = null,
    @SerializedName("serving_store_id") val servingStoreId: String? = null,
)

/** 契约 `CustomerDetailData`（B4）。 */
data class CustomerDetailData(
    @SerializedName("customer_id") val customerId: String,
    val name: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    /** 形状未在契约声明 ⇒ 原样透传（见文件头登记）。 */
    @SerializedName("intake_profile") val intakeProfile: JsonObject? = null,
    @SerializedName("screening_result") val screeningResult: String? = null,
    /** 逐字抄契约枚举：自愿佩戴 / 暂不佩戴。 */
    @SerializedName("band_willingness") val bandWillingness: String? = null,
    @SerializedName("owner_store_id") val ownerStoreId: String? = null,
    @SerializedName("serving_store_id") val servingStoreId: String? = null,
    /** ④ 组派生值：**仅端 B / 端 A 可见**，服务端裁剪。 */
    @SerializedName("effect_verdict") val effectVerdict: String? = null,
    /** ④ 组派生值。 */
    @SerializedName("as_value") val asValue: Double? = null,
)

// ===========================================================================
// 域 C · 量表与评估
// ===========================================================================

/**
 * `listScaleItemBanks`（C1）的 `items[]` 元素。
 *
 * 🛑 契约对该 operation 的 `data` **未声明形状**（只写 `{type: object}`），
 *    这里的五个字段取自 Web 端 `services/domain.ts` 的 `ScaleItemBank`
 *    （它带索引签名，即"已知五键 + 任意附加键"）。原生端保留这五个
 *    **已知键**用于列表展示；附加键会被 Gson 丢弃 —— 这一取舍必须写明：
 *    它是"契约缺口下的对齐"，不是"契约声明了这五个字段"。
 */
data class ScaleItemBank(
    @SerializedName("bank_id") val bankId: String? = null,
    @SerializedName("scale_id") val scaleId: String? = null,
    @SerializedName("item_group_id") val itemGroupId: String? = null,
    @SerializedName("age_group") val ageGroup: String? = null,
    val dimension: String? = null,
)

/** 契约 `BaselineAssessmentData`（C2）。 */
data class BaselineAssessmentData(
    @SerializedName("assessment_id") val assessmentId: String,
    @SerializedName("assessed_at") val assessedAt: String? = null,
    /** 基线结论（对象，形状未声明 ⇒ 原样透传）。 */
    @SerializedName("baseline_conclusion") val baselineConclusion: JsonObject? = null,
    /** 契约逐字声明存在；语义随口径，本层不解释。 */
    val migratable: Boolean? = null,
    @SerializedName("dimension_scores") val dimensionScores: List<Int>? = null,
)

// ===========================================================================
// 域 D · 履约与方案
// ===========================================================================

/** 契约 `VisitData`（D1）。 */
data class VisitData(
    @SerializedName("visit_id") val visitId: String,
    @SerializedName("visit_no") val visitNo: Int? = null,
    @SerializedName("executed_at") val executedAt: String? = null,
    @SerializedName("serving_store_id") val servingStoreId: String? = null,
    @SerializedName("gate_check_json") val gateCheckJson: JsonObject? = null,
    @SerializedName("customer_confirmed") val customerConfirmed: Boolean? = null,
    @SerializedName("abnormal_note") val abnormalNote: String? = null,
)

/** 契约 `DailyReportData`（D3）。 */
data class DailyReportData(
    @SerializedName("report_id") val reportId: String,
    val date: String? = null,
    @SerializedName("weekly_count") val weeklyCount: Int? = null,
    @SerializedName("last_week_compare") val lastWeekCompare: JsonObject? = null,
    val suggestion: String? = null,
    /** 逐字抄契约枚举：客户 / 代核。 */
    val source: String? = null,
)

// ===========================================================================
// 域 E · 手环数据（③ 组 / ④ 组，客户恒不可见）
// ===========================================================================

/** 契约 `BandTelemetryData`（E3）—— ③ 组。 */
data class BandTelemetryData(
    /** 逐字抄契约枚举：手环 / 未接入。 */
    @SerializedName("data_source") val dataSource: String? = null,
    @SerializedName("collected_days") val collectedDays: Int? = null,
    @SerializedName("synced_date") val syncedDate: String? = null,
    /** `metrics[]` 逐项形状未在契约声明 ⇒ 原样透传。 */
    val metrics: List<JsonObject> = emptyList(),
    /**
     * 缺口原因 —— **7 值枚举，逐字抄契约**。
     * 🛑 这是**分类**，不是归责：`not_worn` 与 `compliant_removal` 必须分开显示，
     *    且都不能被读作客户过错。
     */
    @SerializedName("gap_reason") val gapReason: String? = null,
)

/** 契约 `BandDerivedData`（E4）—— ④ 组。 */
data class BandDerivedData(
    @SerializedName("a3_applicable") val a3Applicable: Boolean? = null,
    @SerializedName("a3_value") val a3Value: Double? = null,
    @SerializedName("as_value") val asValue: Double? = null,
    /** 逐字抄契约枚举：E1显著改善 / E2部分改善 / E3稳定 / E4无明显改善 / E5加重。 */
    @SerializedName("effect_verdict") val effectVerdict: String? = null,
    @SerializedName("refund_eligibility") val refundEligibility: Boolean? = null,
)

// ===========================================================================
// 域 F · 判定结论
// ===========================================================================

/** 契约 `VerdictData`（F1）。 */
data class VerdictData(
    @SerializedName("verdict_id") val verdictId: String,
    /** 逐字抄契约枚举：稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核。 */
    val branch: String? = null,
    val confidence: Double? = null,
    @SerializedName("evidence_snapshot") val evidenceSnapshot: JsonObject? = null,
    @SerializedName("threshold_version") val thresholdVersion: String? = null,
    @SerializedName("decided_at") val decidedAt: String? = null,
)

// ===========================================================================
// 域 G · 退款与挽留
// ===========================================================================

/** 契约 `RefundData`（G1）。 */
data class RefundData(
    @SerializedName("refund_id") val refundId: String,
    @SerializedName("liable_store_id") val liableStoreId: String? = null,
    @SerializedName("sla_due_at") val slaDueAt: String? = null,
    @SerializedName("recording_delay_h") val recordingDelayH: Double? = null,
    /** 逐字抄契约枚举：继续 / 终止 / 归档。 */
    val outcome: String? = null,
)

/** 契约 `RefundReceiptData`（G5）。 */
data class RefundReceiptData(
    /** 逐字抄契约枚举：已推送 / 未授权（转线下）/ 推送失败。 */
    @SerializedName("receipt_state") val receiptState: String,
)
