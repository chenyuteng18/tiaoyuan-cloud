package com.diaoyuanyun.therapist.api

import com.diaoyuanyun.therapist.contract.HttpMethod
import com.diaoyuanyun.therapist.contract.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 端 B · HTTP 传输层（纯机械，不含任何契约语义）
 * ============================================================================
 *
 * 🛑 分层纪律：本类**不认识** operationId、不认识信封、不认识错误码。
 *    它只回答"发出去、拿回来"。契约语义一律在 [ApiClient] ——
 *    若把信封解包或错误映射写进来，等于在传输层埋了第二份契约解读点
 *    （本仓第 54/57 条反复惩罚的那种重复源）。
 *
 * 🛑 为什么用**同步** execute + withContext(Dispatchers.IO) 而不是 OkHttp 的 enqueue
 * ---------------------------------------------------------------------------
 *   · `enqueue` 的回调不在调用方栈上，异常必须靠额外的错误通道回传；
 *     一旦有人忘了接那个通道，失败会**静默消失**（协程不会知道）。
 *   · 同步 `execute()` 在 IO 线程池里跑，异常沿栈直上，与 Kotlin 协程的
 *     取消语义天然对齐（取消 → 协程取消 → 调用结束）。
 *   · 代价是每个并发请求占一个线程 —— 本端是一线作业工具（单用户、低频），
 *     这个代价可以接受，且换来的是"异常不可能丢"。
 *     若将来上量，再引入异步通道并**同时**补一条"异常不得静默"的判据。
 */
class HttpTransport(
    connectTimeoutMs: Long = 10_000L,
    readTimeoutMs: Long = 15_000L,
    writeTimeoutMs: Long = 15_000L,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 发送一次请求。
     *
     * @param jsonBody 已序列化的请求体；`null` 表示无体（GET/DELETE 等）
     * @return 状态码 + 原始响应体 + 留痕头
     * @throws java.io.IOException 网络层失败（连接不上 / 超时）
     */
    suspend fun send(
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        jsonBody: String?,
    ): TransportResult = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = jsonBody?.toRequestBody(mediaType)

        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)

        // 🛑 OkHttp 对 GET/HEAD 带体是**被禁止**的（会抛 IllegalArgumentException）。
        //    契约里 GET 端点的 requestBody 均未声明 ⇒ 本端只在非 GET 时挂体，
        //    并对"GET 却想传体"这种调用错误**当场报错**，而不是静默丢掉。
        if (body != null && method == HttpMethod.GET) {
            throw IllegalStateException(
                "出站：GET $url 携带了请求体 —— 契约中 GET 端点均未声明 requestBody。" +
                    "参数应走 query 或 path，请检查调用点。"
            )
        }
        builder.method(method.name, body)

        client.newCall(builder.build()).execute().use { resp: Response ->
            TransportResult(
                status = resp.code,
                body = resp.body?.string().orEmpty(),
                traceHeader = resp.header(TraceHeaderName),
            )
        }
    }

    companion object {
        /**
         * 留痕头名 —— 🛑 **不得在此手写字面量**。
         *
         * 取值唯一来源是**生成物** `Protocol.TRACE_HEADER`（契约 `x-api-protocol`
         * 的机械转录）。本仓第 57 条：`X-Trace-Id` 在契约里曾经一个字都没有，
         * 只活在后端 `TraceIdFilter.HEADER` 常量与各端字面量里（实测 6 处）；
         * 改一处 ⇒ 留痕链断掉而全端仍绿。故此处只做**转发**，不做声明。
         * android-check 的 `no-protocol-literal` 判据会扫描本文件：
         * 剥注释后不得出现头名字面量。
         */
        val TraceHeaderName: String get() = Protocol.TRACE_HEADER
    }
}

/** 传输层原始结果（不做任何契约解读）。 */
data class TransportResult(
    val status: Int,
    val body: String,
    val traceHeader: String?,
)
