package com.diaoyuanyun.therapist.api

import com.diaoyuanyun.therapist.contract.Protocol
import com.google.gson.JsonElement

/**
 * 端 B · 错误码 → 可读文案（原生 Android）
 * ============================================================================
 *
 * 依据：契约 `x-error-codes`（12 个 code 的逐字枚举）。
 * 口径逐条转录自 `frontends/therapist-app/src/services/errors.ts` ——
 * 同一张 code 表，**内部端口径**。
 *
 * 🛑 契约明文：`ResultEnvelope.message` 是**面向开发者**的，逐字写着
 *    「不得直接渲染给客户」。本端虽不是客户端，但同一纪律成立：
 *    直接渲染 `message` 会让一线看到 `VISIBILITY_DENIED` 这类内部标识，
 *    也会在契约换措辞时把界面文案带着一起漂移。
 *
 * 🛑 与端 C 的差异（同一张表，两类用户，两套文案）
 * ---------------------------------------------------------------------------
 * 端 C（客户）必须中性措辞，且**不得出现"权限档位"这类内部概念** ——
 * 客户不该知道系统里有"档位"这个东西。
 * 端 B（调理师 / 经络师）是**内部使用者**：说"该字段组你的角色不可见，
 * 请联系管理员"是**正确且必要**的沟通，含糊化反而让一线无从上报。
 * 故两端共用同一组 code，文案**必须分开写**，不得互相复用。
 */
object ErrorCode {
    const val VALIDATION_FAILED = 1001
    const val UNAUTHENTICATED = 1002
    const val VISIBILITY_DENIED = 2001
    const val GATE_MISSING = 2002
    const val TENANT_MISMATCH = 2003
    const val PLACEHOLDER_OUT_OF_SCOPE = 2004
    const val NOT_FOUND = 3001
    const val VERSION_CONFLICT = 4001
    const val IDEMPOTENT_REPLAY = 4002
    const val BUSINESS_RULE_VIOLATED = 5001
    const val RATE_LIMITED = 6001
    const val INTERNAL_ERROR = 9001
}

/** 错误分类。界面按 kind 决定呈现方式（如 auth 触发重新登录）。 */
enum class ErrorKind { AUTH, REPLAY, DENIED, GATE, CONFLICT, SERVER, NETWORK, UNKNOWN }

/**
 * 整理后的错误信息。
 *
 * [developerMessage] **仅供日志**，界面不得直接渲染（契约明文）。
 * [reasons] 是契约要求「不得模糊报错」时必须给出的**原因名清单**（本仓第 61 条）。
 */
data class Described(
    val text: String,
    val code: Int?,
    val traceId: String,
    val kind: ErrorKind,
    val developerMessage: String?,
    val reasons: List<String>,
)

/** 出站层抛出的契约错误（携带信封四字段）。 */
class ApiException(
    val code: Int,
    val traceId: String,
    val developerMessage: String?,
    /** 整个 `data` 载荷（第 61 条：错误路径也必须保留它，否则原因名无从取用）。 */
    val data: JsonElement?,
) : Exception(developerMessage ?: "api error $code")

object ApiErrors {

    private const val UNKNOWN_COPY = "未知错误码：请记录 trace_id 并联系技术支持。"
    private const val NETWORK_COPY = "网络异常或请求超时，请检查网络后重试。"

    /**
     * code → 给一线看的文案。**内部端口径**：说清"是什么、找谁、怎么办"。
     *
     * 🛑 [ErrorCode.GATE_MISSING] 的文案点明"缺哪个前置项" —— 契约要求
     *    `data.missing_items` 给出缺失项名，故文案里把该字段名写出，
     *    让一线知道该去看响应的哪一部分。
     */
    private val COPY: Map<Int, String> = mapOf(
        ErrorCode.VALIDATION_FAILED to "提交内容不完整或有误，请检查必填项与格式。",
        ErrorCode.UNAUTHENTICATED to "登录已失效，请重新登录。",
        ErrorCode.VISIBILITY_DENIED to
            "该字段组你的角色不可见（服务端已按下发档位裁剪）。如确需，请联系管理员调整档位。",
        ErrorCode.GATE_MISSING to
            "前置门禁未通过：请查看响应 data.missing_items 列出的缺失项，补齐后再提交。",
        ErrorCode.TENANT_MISMATCH to "租户不匹配：该资源不属于当前租户，或请求头与登录身份不符。",
        ErrorCode.PLACEHOLDER_OUT_OF_SCOPE to "该能力属占位、尚未纳入本期范围。",
        ErrorCode.NOT_FOUND to "资源不存在（或不在当前租户范围内）。",
        ErrorCode.VERSION_CONFLICT to
            "该版本不可覆盖（方案 / 题库 / 判定结论为版本化对象），请基于最新版本重试。",
        ErrorCode.IDEMPOTENT_REPLAY to
            "重复提交：该请求此前已成功处理，已返回首次结果（不是失败）。",
        ErrorCode.BUSINESS_RULE_VIOLATED to "业务规则不满足，无法提交。",
        ErrorCode.RATE_LIMITED to "操作过于频繁，请稍后再试。",
        ErrorCode.INTERNAL_ERROR to "服务内部错误，请记录 trace_id 并联系技术支持。",
    )

    /**
     * 从错误对象里取「契约要求的拒绝原因名」—— 字段名取自**生成物常量**，不手写。
     *
     * 🛑 契约 `forbidden-403`：2002 GATE_MISSING 的 `data.missing_items[]` 与
     *    2001 VISIBILITY_DENIED 的 `data.denied_fields[]`。此前出站层在错误路径
     *    把 `body.data` 丢掉、错误层又只取 `message` ⇒ 一线只看到「门禁未通过」，
     *    **不知道缺什么**，只能去猜或来问 —— 而契约明文要求给出名字。
     */
    private fun reasonsOf(code: Int, data: JsonElement?): List<String> {
        val field = Protocol.ERROR_DATA_FIELDS[code] ?: return emptyList()
        val obj = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val arr = obj.get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.filter { it.isJsonPrimitive }.map { it.asString }
    }

    /** 把原因名清单拼进文案（契约明文「不得模糊报错」的落点）。 */
    private fun appendReasons(text: String, reasons: List<String>): String =
        if (reasons.isEmpty()) text else "$text（${reasons.joinToString("、")}）"

    private fun kindOf(code: Int): ErrorKind = when (code) {
        ErrorCode.UNAUTHENTICATED -> ErrorKind.AUTH
        ErrorCode.VISIBILITY_DENIED -> ErrorKind.DENIED
        ErrorCode.GATE_MISSING -> ErrorKind.GATE
        ErrorCode.VERSION_CONFLICT -> ErrorKind.CONFLICT
        ErrorCode.IDEMPOTENT_REPLAY -> ErrorKind.REPLAY
        ErrorCode.INTERNAL_ERROR -> ErrorKind.SERVER
        else -> ErrorKind.UNKNOWN
    }

    /**
     * 把任意异常整理成可渲染信息。
     *
     * 🛑 [ErrorCode.IDEMPOTENT_REPLAY] **不是失败**：契约逐字「幂等键命中已有记录
     *    （返回首次结果）」，即操作**已经成功过了**。界面必须把它当成功态呈现，
     *    否则一线会重复点第二次、第三次，制造更多重放。
     */
    fun describe(err: Throwable): Described {
        if (err is ApiException) {
            val reasons = reasonsOf(err.code, err.data)
            return Described(
                text = appendReasons(COPY[err.code] ?: UNKNOWN_COPY, reasons),
                code = err.code,
                traceId = err.traceId,
                kind = kindOf(err.code),
                developerMessage = err.developerMessage,
                reasons = reasons,
            )
        }
        return Described(
            text = NETWORK_COPY,
            code = null,
            traceId = "",
            kind = ErrorKind.NETWORK,
            developerMessage = err.message,
            reasons = emptyList(),
        )
    }

    /** 是否为幂等重放（按契约语义 = 此前已成功）。 */
    fun isReplay(d: Described): Boolean = d.code == ErrorCode.IDEMPOTENT_REPLAY

    /** 是否需要重新登录。 */
    fun needRelogin(d: Described): Boolean = d.kind == ErrorKind.AUTH
}
