// ============================================================================
// GENERATED FILE — DO NOT EDIT.
//
// 真源: contract/sdk-generator/_cut/therapist-app.openapi.yaml
// 生成: frontends/tools/gen-endpoints.py
// 重跑: python frontends/tools/gen-endpoints.py
// 校验: python frontends/tools/gen-endpoints.py --check
//
// 这一份是【端 B · 调理师 / 经络师 APP（原生 Android · Kotlin）】的可用端点清单，逐条机械转录自契约 ——
// 一条 operation 属于本端，当且仅当它的 x-callable-roles 与
// 本端 token-roles (therapist, meridian) 有交集。
//
// 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。
// 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」
//    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。
// ============================================================================

package com.diaoyuanyun.therapist.contract

/** 契约版本（契约 info.version 的机械转录）。 */
const val CONTRACT_VERSION: String = "api-contract-v1.0.0"

/**
 * 契约 servers[0].url（§2.0 Base Path）—— **本端拼 URL 的唯一前缀**。
 *
 * 🛑 出站 URL 必须写成 API_BASE_PATH + endpoint.path：
 *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；
 *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且编译/构建/门禁全绿（第 56 条）。
 */
object Contract {
    const val VERSION: String = CONTRACT_VERSION
    const val API_BASE_PATH: String = "/api/v1"
    /** 本端 token-roles（取自 generator-matrix.yaml 的声明）。 */
    val END_TOKEN_ROLES: List<String> = listOf("therapist", "meridian")
}

/**
 * 跨端协议片段（契约 x-api-protocol 的机械转录）—— **出站层一律引用本组常量**。
 *
 * 🛑 为什么不能在本端出站层手写字面量（本仓第 57 条）
 *    头名 / 令牌前缀 / 信封成功码此前在三个端各写一遍。`X-Trace-Id` 更彻底：
 *    契约里一个字都没有，只活在后端 TraceIdFilter 与各端字面量里。
 *    改一处 ⇒ 各处静默分叉 ⇒ 全量 401 / 幂等去重失效 / 留痕断链，而构建全绿。
 */
object Protocol {
    /** 鉴权头名。 */
    const val AUTH_HEADER: String = "Authorization"
    /** 令牌前缀 —— 拼 `${AUTH_SCHEME} ${token}`。 */
    const val AUTH_SCHEME: String = "Bearer"
    /** 租户一致性校验头（服务端仅校验、不采纳其值）。 */
    const val TENANT_HEADER: String = "X-Tenant-Id"
    /** 留痕头 —— 与响应体 trace_id 并存，用于日志双向检索。 */
    const val TRACE_HEADER: String = "X-Trace-Id"
    /** 幂等键请求头名（写请求必带）。 */
    const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"
    // ---------- 响应信封字段名（契约 x-api-protocol.envelope-fields） ----------
    // 🛑 出站层解信封【必须】引用下面的具名常量，不得写 "code" / "data" 这类字面量 ——
    //    契约改名后字面量不会跟着改，而「取不到字段」多数情况下表现为静默拿到 null
    //    （data 变 null ⇒ 列表全空 / 详情全白），且 debug 包正常、构建与门禁全绿。
    /** 信封字段 "code"。 */
    const val ENVELOPE_FIELD_CODE: String = "code"
    /** 信封字段 "message"。 */
    const val ENVELOPE_FIELD_MESSAGE: String = "message"
    /** 信封字段 "data"。 */
    const val ENVELOPE_FIELD_DATA: String = "data"
    /** 信封字段 "trace_id"。 */
    const val ENVELOPE_FIELD_TRACE_ID: String = "trace_id"
    /** 响应信封字段全集（由上面具名常量组成 —— 两处各写一份就会漂移）。 */
    val ENVELOPE_FIELDS: List<String> = listOf(ENVELOPE_FIELD_CODE, ENVELOPE_FIELD_MESSAGE, ENVELOPE_FIELD_DATA, ENVELOPE_FIELD_TRACE_ID)
    /** 信封成功码 —— 契约 §2.0 逐字「code != 0 时 data 为空」，故成功码为 0。 */
    const val ENVELOPE_OK_CODE: Int = 0

    // ---------------- 分页协议（权威定义见 x-api-protocol.pagination） -------------
    /** 页码参数名（出站拼接用）。 */
    const val PAGE_FIELD: String = "page"
    /** 每页条数参数名。 */
    const val PAGE_SIZE_FIELD: String = "page_size"
    /**
     * 分页**响应**里的列表容器键（契约 pagination.response-fields 首位）。
     *
     * 🛑 出站层取列表【必须】用本常量：`ApiClient.items()` 此前手写 `get("items")`，
     *    契约改名后所有列表端点会**静默返回空列表**（取不到键 ⇒ 当成无数据），
     *    而 debug 包正常、构建与门禁全绿。
     */
    const val ITEMS_FIELD: String = "items"
    /** page 下界（< 该值一律 400，不静默纠正）。 */
    const val PAGE_MIN: Int = 1
    /** page_size 下界。 */
    const val PAGE_SIZE_MIN: Int = 1
    /** page_size 上界（> 该值一律 400，不夹逼）。 */
    const val PAGE_SIZE_MAX: Int = 100
    /** 未传 page_size 时的缺省值。 */
    const val PAGE_SIZE_DEFAULT: Int = 20
    /** 越界处置。本仓唯一合法取值 'reject-400'（越界直接拒，不得静默夹逼）。 */
    const val OVER_RANGE_POLICY: String = "reject-400"
    /** 越界对应的错误码名。 */
    const val OVER_RANGE_ERROR: String = "VALIDATION_FAILED"

    /**
     * 拒绝响应的 data 载荷字段名（第 61 条）—— 「不得模糊报错」的机器可读那一半。
     *
     * message 是给人读的；本表是给机器读的。错误层必须用
     * ERROR_DATA_FIELDS[code] 取字段名，不得手写 "missing_items"。
     */
    val ERROR_DATA_FIELDS: Map<Int, String> = mapOf(
        2002 to "missing_items",
        2001 to "denied_fields",
    )
}

/** 契约 paths 允许的 HTTP 方法。 */
enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/** 一个契约 operation 的机械转录。 */
data class Endpoint(
    val id: String,
    val row: String,
    val method: HttpMethod,
    val path: String,
    val grantedRoles: List<String>,
    /** 契约 required 的 query 参数名（第 65 条）—— 调用点必须带上它们。 */
    val requiredQuery: List<String>,
    /** 契约 path 参数名（第 71 条）—— URL 里的 {id} 占位符，漏传会拼出字面量。 */
    val requiredPath: List<String>,
    /** 契约 requestBody schema 的 required 字段名。 */
    val requiredBody: List<String>,
    /**
     * 契约 200 响应 `data` 载荷的**具名 schema 名**（`components.schemas` 的键）。
     *
     * 未声明形状的端点（`data: {type: object, nullable: true}`）为 null ——
     * 与 `domain/Models.kt` 头部的登记**一一对应**，由门禁
     * `contract-schema-fields` 核对（声明数 / 名字 / 字段集合全等）。
     */
    val dataSchema: String? = null,
    /** 契约 x-row-scope：行级范围随 admin 子档位变化。 */
    val rowScope: String? = null,
    /** 契约 x-super-admin-only：仅超管。 */
    val superAdminOnly: Boolean? = null,
    /** 契约 x-ruling-pending：取值系推断、**待裁定** —— 不得当定论实现。 */
    val rulingPending: String? = null,
    /** 契约 x-frontier：占位待冻结 —— 不得当已冻结契约用。 */
    val frontier: String? = null,
    /** 契约 x-idempotency-key：幂等键构成说明。 */
    val idempotencyKeySpec: String? = null,
)

/** 契约角色由哪些 token-role 构成（逐条取自契约 x-roles）。 */
data class RoleExpansion(
    val tokens: List<String>,
    val end: String,
    val display: String,
)

/** 本端全部可用端点 + 角色展开表的唯一索引点。 */
object Endpoints {

    val ALL: List<Endpoint> = listOf(
        Endpoint(
            id = "authLogin",
            row = "A1",
            method = HttpMethod.POST,
            path = "/auth/login",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf("account", "client_end", "credential"),
            dataSchema = "LoginData",
        ),
        Endpoint(
            id = "authMe",
            row = "A2",
            method = HttpMethod.GET,
            path = "/auth/me",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf(),
            dataSchema = "AuthMeData",
        ),
        Endpoint(
            id = "createCustomer",
            row = "B2",
            method = HttpMethod.POST,
            path = "/customers",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf("age", "gender", "name", "phone", "screening_id"),
            dataSchema = "CustomerCreateData",
        ),
        Endpoint(
            id = "getCustomer",
            row = "B4",
            method = HttpMethod.GET,
            path = "/customers/{id}",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
            dataSchema = "CustomerDetailData",
        ),
        Endpoint(
            id = "submitBaselineAssessment",
            row = "C2",
            method = HttpMethod.POST,
            path = "/customers/{id}/assessments/baseline",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf("age_group_locked", "dimension_scores", "item_group_id", "measure_operator", "scale_id", "total_score"),
            dataSchema = "BaselineAssessmentData",
        ),
        Endpoint(
            id = "getAssessment",
            row = "C3",
            method = HttpMethod.GET,
            path = "/customers/{id}/assessments/{assessment_id}",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("assessment_id", "id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "getBandDerived",
            row = "E4",
            method = HttpMethod.GET,
            path = "/customers/{id}/band/derived",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
            dataSchema = "BandDerivedData",
        ),
        Endpoint(
            id = "getBandTelemetry",
            row = "E3",
            method = HttpMethod.GET,
            path = "/customers/{id}/band/telemetry",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
            dataSchema = "BandTelemetryData",
        ),
        Endpoint(
            id = "signConsent",
            row = "B3",
            method = HttpMethod.POST,
            path = "/customers/{id}/consents",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "submitCycleAssessment",
            row = "C4",
            method = HttpMethod.POST,
            path = "/customers/{id}/cycle-assessments",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf("adherence", "cycle_id", "module_scores", "sequence_no"),
        ),
        Endpoint(
            id = "listDailyReports",
            row = "D4",
            method = HttpMethod.GET,
            path = "/customers/{id}/daily-reports",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "submitDailyReport",
            row = "D3",
            method = HttpMethod.POST,
            path = "/customers/{id}/daily-reports",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf("answers_json", "date", "source"),
            dataSchema = "DailyReportData",
        ),
        Endpoint(
            id = "getIntakeProfile",
            row = "B5",
            method = HttpMethod.GET,
            path = "/customers/{id}/intake-profile",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "patchIntakeProfile",
            row = "B6",
            method = HttpMethod.PATCH,
            path = "/customers/{id}/intake-profile",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "listVerdicts",
            row = "F2",
            method = HttpMethod.GET,
            path = "/customers/{id}/verdicts",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "listVisits",
            row = "D2",
            method = HttpMethod.GET,
            path = "/customers/{id}/visits",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "createVisit",
            row = "D1",
            method = HttpMethod.POST,
            path = "/customers/{id}/visits",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
            dataSchema = "VisitData",
        ),
        Endpoint(
            id = "createVerdict",
            row = "F1",
            method = HttpMethod.POST,
            path = "/cycle-assessments/{id}/verdicts",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf("adherence", "confidence", "core_metric_improved", "customer_id", "module_scores", "risk_flag", "same_origin", "sequence_no"),
            dataSchema = "VerdictData",
        ),
        Endpoint(
            id = "createDeviceDispatch",
            row = "D6",
            method = HttpMethod.POST,
            path = "/device-dispatches",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "createPlan",
            row = "D5-a",
            method = HttpMethod.POST,
            path = "/plans",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "getPlan",
            row = "D5-b",
            method = HttpMethod.GET,
            path = "/plans/{id}",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "reviewPlan",
            row = "D5-c",
            method = HttpMethod.POST,
            path = "/plans/{id}/reviews",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "createRefund",
            row = "G1",
            method = HttpMethod.POST,
            path = "/refunds",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf("customer_id", "entry", "reason_code", "refund_route", "requested_at"),
            dataSchema = "RefundData",
        ),
        Endpoint(
            id = "getRefund",
            row = "G2",
            method = HttpMethod.GET,
            path = "/refunds/{id}",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "createRefundReceipt",
            row = "G5",
            method = HttpMethod.POST,
            path = "/refunds/{id}/receipts",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
            dataSchema = "RefundReceiptData",
        ),
        Endpoint(
            id = "createRetention",
            row = "G3",
            method = HttpMethod.POST,
            path = "/refunds/{id}/retentions",
            grantedRoles = listOf("meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf("id"),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "listScaleItemBanks",
            row = "C1",
            method = HttpMethod.GET,
            path = "/scale-item-banks",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf("age_group"),
            requiredPath = listOf(),
            requiredBody = listOf(),
        ),
        Endpoint(
            id = "createScreeningRecord",
            row = "B1",
            method = HttpMethod.POST,
            path = "/screening-records",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf("customer_id", "items_json", "operator_id"),
            dataSchema = "ScreeningData",
        ),
        Endpoint(
            id = "listStores",
            row = "A3",
            method = HttpMethod.GET,
            path = "/stores",
            grantedRoles = listOf("therapist", "meridian"),
            requiredQuery = listOf(),
            requiredPath = listOf(),
            requiredBody = listOf(),
            dataSchema = "StoreListData",
        ),
    )

    val IDS: List<String> = ALL.map { it.id }

    private val BY_ID: Map<String, Endpoint> = ALL.associateBy { it.id }

    /** 按 operationId 取端点；未收录返回 null（调用方必须 fail-closed）。 */
    fun byId(id: String): Endpoint? = BY_ID[id]

    /** 契约 x-roles 的 token-role 展开表（本端相关项）。 */
    val ROLE_EXPANSION: Map<String, RoleExpansion> = mapOf(
        "meridian" to RoleExpansion(
            tokens = listOf("meridian"),
            end = "app",
            display = "经络师（APP）",
        ),
        "therapist" to RoleExpansion(
            tokens = listOf("therapist"),
            end = "app",
            display = "调理师（APP）",
        ),
    )
}
