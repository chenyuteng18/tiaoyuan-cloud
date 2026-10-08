package com.diaoyuanyun.therapist.api

import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.contract.Endpoint
import com.diaoyuanyun.therapist.contract.Endpoints
import com.diaoyuanyun.therapist.contract.Protocol
import com.diaoyuanyun.therapist.data.SessionStore
import com.diaoyuanyun.therapist.env.AppEnv
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.UUID

/**
 * 端 B · 出站层（契约语义的唯一入口）
 * ============================================================================
 *
 * 与 Web 端 `api/client.ts` **同一条纪律**，逐条移植：
 *   1. 端点必须属于本端（`Endpoints.byId` 命中），否则是**代码缺陷**；
 *   2. 角色必须被授予该端点（[Access.canCall]）—— 挡住"界面藏了但代码还调"；
 *   3. URL = 出站前缀 + 契约 path（[UrlBuilder]，前缀取自生成物）；
 *   4. 头名 / 令牌前缀 / 幂等头 / 留痕头 / 成功码**一律引用生成物常量**；
 *   5. 错误路径**必须**保留 `data`（契约「不得模糊报错」的机器可读那一半）。
 *
 * 🛑 `role` 刻意设为**必填参数**（本端无默认值）
 * ---------------------------------------------------------------------------
 * 与 Web 端完全一致的理由：若给默认值或从会话隐式读取，"忘了传"就等于
 * "跳过角色准入"，而这个缝**不会让任何测试变红** —— 它只会让一次越权调用
 * 真的发出请求，由服务端 403 兜底；那时排查者会以为是自己权限配错了，
 * 而真实原因是**界面少藏了一个入口**。
 * 必填使"少传一个参数"变成**编译错误** —— 把静默失效变成构建期可见的失败。
 */
class ApiClient(
    private val sessions: SessionStore,
    private val transport: HttpTransport = HttpTransport(),
) {

    private val gson = Gson()

    /**
     * 调用一个本端契约允许的 operation，返回 `data` 载荷。
     *
     * @param role 当前登录角色（therapist / meridian）—— **必填**
     * @throws IllegalStateException 端点不属本端 / 角色未被授予 / 未登录
     * @throws ApiException 服务端按信封返回的错误（含 code / trace_id / data）
     */
    suspend fun raw(
        endpointId: String,
        role: String,
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
        idempotencyKey: String? = null,
    ): JsonElement? {
        val endpoint = endpointOf(endpointId)
        assertCanCall(endpointId, role)
        val token = requireToken()

        val url = UrlBuilder.build(AppEnv.requestBaseUrl, endpoint, params, query)

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        // 🛑 头名与令牌前缀取自生成物常量（契约 x-api-protocol 的机械转录）
        headers[Protocol.AUTH_HEADER] = Protocol.AUTH_SCHEME + " " + token
        // 🛑 刻意【不】发送 Protocol.TENANT_HEADER（X-Tenant-Id）
        // -------------------------------------------------------------------
        // 契约 `x-global-conventions.auth` 逐字：「租户来自 token」，
        // 且该头在契约里的定性是「服务端仅校验、**不采纳其值**」。
        // 本仓 Web 端（`therapist-app/src/api/client.ts`）同样不发这个头 ——
        // 本端若"顺手加一个更严格的头"，就造出了一处**跨端协议分叉**：
        // 一旦本地缓存的租户与 token 里的不一致，本端会拿到 2003
        // TENANT_MISMATCH 而 Web 端不会，而两端代码看起来都"符合契约"。
        // ⇒ 三端同口径：不发。要发就三端一起发，并同步改契约。
        // 写请求一律带幂等键（与 Web 端同口径：GET 不带）
        if (endpoint.method != com.diaoyuanyun.therapist.contract.HttpMethod.GET) {
            headers[Protocol.IDEMPOTENCY_HEADER] = idempotencyKey ?: newIdempotencyKey()
        }

        val json = body?.let { gson.toJson(it) }
        val res = transport.send(endpoint.method, url, headers, json)
        return unwrap(res)
    }

    /**
     * **匿名**调用 —— 只允许 A1 `authLogin` 使用（登录前必然没有令牌）。
     *
     * 🛑 为什么不给 [raw] 加一个 `requiresToken = false` 开关
     * ---------------------------------------------------------------------------
     * 开关会被误用，而且误用的形态极其自然："这个端点报 401 了，先把开关关掉试试"
     * —— 关掉之后请求**真的会出去**（服务端仍会 403/401 兜底），但客户端这一侧的
     * 准入链条被悄悄摘掉了一环，且**没有任何测试会红**。
     *
     * 故这里**单独开一个方法与一个调用点**：`api/ApiClient.kt` **之外**，
     * 全仓只允许 `domain/IdentityApi.kt` 出现一次 `oneAnonymous(`，且必须落在
     * `authLogin` 里 —— 这条可被门禁机械核验（android-check 的
     * `anonymous-call-single-site` 判据）。
     * ⇒ "在第二个地方开匿名调用"这件事会**当场变红**，而不是等到被人发现。
     */
    suspend fun rawAnonymous(
        endpointId: String,
        role: String,
        body: Any? = null,
    ): JsonElement? {
        val endpoint = endpointOf(endpointId)
        assertCanCall(endpointId, role)

        val url = UrlBuilder.build(AppEnv.requestBaseUrl, endpoint, emptyMap(), emptyMap())

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        // 登录也是写请求 ⇒ 与出站层同口径带幂等键（不得因为"它是登录"就少一个头，
        // 两端协议片段必须一致，否则又是一个"同一协议两处实现"）
        if (endpoint.method != com.diaoyuanyun.therapist.contract.HttpMethod.GET) {
            headers[Protocol.IDEMPOTENCY_HEADER] = newIdempotencyKey()
        }

        val json = body?.let { gson.toJson(it) }
        val res = transport.send(endpoint.method, url, headers, json)
        return unwrap(res)
    }

    /** 匿名调用的**类型化**版本（与 [rawAnonymous] 同一条纪律：唯一调用点是 A1）。 */
    suspend fun <T> oneAnonymous(
        endpointId: String,
        role: String,
        body: Any?,
        type: Class<T>,
    ): T? {
        val data = rawAnonymous(endpointId, role, body) ?: return null
        if (!data.isJsonObject) return null
        return gson.fromJson(data, type)
    }

    /** 取单个对象；`data` 为空或缺字段时返回 `null`（**不猜**默认值）。 */
    suspend fun <T> one(
        endpointId: String,
        role: String,
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
        idempotencyKey: String? = null,
        type: Class<T>,
    ): T? {
        val data = raw(endpointId, role, params, query, body, idempotencyKey) ?: return null
        if (!data.isJsonObject) return null
        return gson.fromJson(data, type)
    }

    /**
     * 取分页列表的 `items`。
     *
     * 🛑 为什么取 `items` 而不是整个 data：契约 `x-global-conventions.pagination`
     *    逐字写「响应 data.{items[], total, page, page_size}」。取整个 data 会让
     *    调用点各自去解包一次（又是一份重复解读点）。
     */
    suspend fun <T> items(
        endpointId: String,
        role: String,
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
        idempotencyKey: String? = null,
        element: Class<T>,
    ): List<T> {
        val data = raw(endpointId, role, params, query, body, idempotencyKey) ?: return emptyList()
        val arr = when {
            data.isJsonArray -> data.asJsonArray
            // 🛑 容器键取自**生成物常量**，不得手写 "items"（契约可能改名）：
            //    手写时契约改名不会让这里跟着改，而"取不到键"的表现是
            //    **静默返回空列表**（下面的 takeIf 判不过 ⇒ emptyList），
            //    界面上就是"这个列表没有数据"，构建与门禁全绿。
            //    —— 与「端点层不得手抄」（判据 ⑦）同一条纪律，只是对象换成了协议字段名。
            data.isJsonObject -> data.asJsonObject.get(Protocol.ITEMS_FIELD)
                ?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return emptyList()
        return arr.map { gson.fromJson(it, element) }
    }

    // -------------------------------------------------------------------------
    // 内部：三处 fail-closed 断言
    // -------------------------------------------------------------------------

    private fun endpointOf(endpointId: String): Endpoint =
        Endpoints.byId(endpointId)
            ?: throw IllegalStateException(
                "contract: operation not granted to this app: $endpointId\n" +
                    "若契约确实新增了该端点，请重跑 frontends/tools/gen-endpoints.py。"
            )

    /**
     * X-3 角色级准入。
     *
     * 🛑 顺序刻意是"先查归属、再查角色"（与 Web 端逐字一致）：
     *    ① 端点不属本端 —— 这是**代码缺陷**，必须最先暴露；
     *    ② 端点属本端但当前角色未被授予 —— 这是**角色边界**。
     *    只做①会让"界面藏了入口、代码仍能调"成为一条缝。
     */
    private fun assertCanCall(endpointId: String, role: String) {
        if (!Access.canCall(role, endpointId)) {
            val e = Endpoints.byId(endpointId)
            throw IllegalStateException(
                "contract: ROLE_NOT_GRANTED —— 角色 '$role' 未被授予 '$endpointId'。\n" +
                    "该端点本端授予的角色为：${e?.grantedRoles?.joinToString(" / ") ?: "（未知端点）"}"
            )
        }
    }

    private fun requireToken(): String =
        sessions.token() ?: throw ApiException(
            com.diaoyuanyun.therapist.api.ErrorCode.UNAUTHENTICATED,
            "",
            "本地无令牌：调用出站前必须先完成 A1 登录。",
            null,
        )

    /**
     * 解信封。
     *
     * 🛑 三类"看起来像成功"的失败必须区分开（否则一线会被误导）：
     *    · 响应体为空 200 → 视为无 data（契约允许 `code=0` 且 `data` 缺省）；
     *    · 响应体为空非 2xx → 内部错误（**不得**当成"资源不存在"）；
     *    · 响应体不是信封 JSON → 内部错误（**不得**当成成功）。
     */
    private fun unwrap(res: TransportResult): JsonElement? {
        val text = res.body.trim()
        if (text.isEmpty()) {
            if (res.status in 200..299) return null
            throw ApiException(
                ErrorCode.INTERNAL_ERROR,
                res.traceHeader.orEmpty(),
                "HTTP ${res.status} 且响应体为空 —— 这不是业务错误，请记录状态码。",
                null,
            )
        }

        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            throw ApiException(
                ErrorCode.INTERNAL_ERROR,
                res.traceHeader.orEmpty(),
                "响应不是契约信封 JSON（HTTP ${res.status}）—— 可能是网关/代理返回的页面。",
                null,
            )
        }

        val obj = root.asJsonObject
        // 🛑 信封四字段名一律取自**生成物常量**（契约 x-api-protocol.envelope-fields 的
        //    机械转录），不得手写 "code" / "message" / "data" / "trace_id"。
        //
        //    实测（2026-10-08 收口）：生成物里 `Protocol.ENVELOPE_FIELDS` 存在但**零引用**，
        //    而此处四个字段名全是手写字面量 —— 典型的「死权威」：权威声明在生成物里躺着，
        //    取用点却是手抄的第二份。契约把 `data` 改名后，重跑生成器只会改那个常量，
        //    这里一行不动 ⇒ `data` 恒为 null ⇒ **列表全空、详情全白**，
        //    而 debug 包完全正常、构建与门禁全绿。
        //    ⇒ 用手写字的字面量会被门禁 `protocol-fields-from-generated` 判红。
        val codeEl = obj.get(Protocol.ENVELOPE_FIELD_CODE)?.takeIf { it.isJsonPrimitive }
        val traceId = obj.get(Protocol.ENVELOPE_FIELD_TRACE_ID)?.takeIf { it.isJsonPrimitive }?.asString
            ?: res.traceHeader.orEmpty()
        val message = obj.get(Protocol.ENVELOPE_FIELD_MESSAGE)?.takeIf { it.isJsonPrimitive }?.asString
        val data = obj.get(Protocol.ENVELOPE_FIELD_DATA)

        if (codeEl == null) {
            // 🛑 诊断必须给出「实际收到了什么」：此前只说"缺 code"，排查者拿到一份
            //    网关/代理返回的 JSON 时无从判断是**契约改名**还是**根本不是信封**。
            //    这里把顶层键名照原样列出，并给出契约声明的信封字段全集
            //    （`Protocol.ENVELOPE_FIELDS` —— 生成物常量，**不是**第二份手写清单）。
            throw ApiException(
                ErrorCode.INTERNAL_ERROR, traceId,
                "信封缺 ${Protocol.ENVELOPE_FIELD_CODE} 字段 —— 契约 §2.0 要求信封恒含它。\n" +
                    "  实际收到的顶层字段：" + obj.keySet().joinToString(", ").ifEmpty { "（无）"} + "\n" +
                    "  契约信封字段：" + Protocol.ENVELOPE_FIELDS.joinToString(", "),
                obj,
            )
        }

        val code = codeEl.asInt
        // 🛑 成功码取自生成物常量（契约 §2.0「code != 0 时 data 为空」）
        if (code != Protocol.ENVELOPE_OK_CODE) {
            // 🛑 错误路径**必须**把 data 带出来（本仓第 61 条）—— 契约
            //    「不得模糊报错」的机器可读那一半就装在 data 里：
            //    2002 → data.missing_items[]、2001 → data.denied_fields[]。
            throw ApiException(code, traceId, message, data)
        }
        return data?.takeIf { !it.isJsonNull }
    }

    companion object {
        /**
         * 生成幂等键。
         *
         * 🛑 与端 A 的差别必须写明：端 A 的 I3（上传文档模板）在契约里**逐字指定**
         *    键构成 `(tenant_id, doc_type, file_hash)` —— 那里**随机键是错的**
         *    （重复上传每次都成功 ⇒ 静默绕过幂等语义）。
         *    端 B 的 29 个端点里**没有**逐操作声明 `x-idempotency-key` 的，
         *    故这里按契约缺省（UUID）生成；一旦端 B 出现逐操作声明，
         *    必须改为按要素显式构造，**不得**继续用随机键。
         *    该落差已登记在 README。
         */
        fun newIdempotencyKey(): String = "th-" + UUID.randomUUID().toString()
    }
}
