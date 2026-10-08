package com.diaoyuanyun.therapist.api

import com.diaoyuanyun.therapist.contract.Endpoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 端 B · 出站 URL 组装
 * ============================================================================
 *
 * 🛑 这里只有一件事，但它是本仓第 56 条的核心
 * ---------------------------------------------------------------------------
 * 出站 URL **必须**是 `requestBaseUrl + endpoint.path`：
 *   · `requestBaseUrl` = 网关根 + 契约 `servers[0].url`（`/api/v1`）；
 *   · `endpoint.path` 是契约 `paths` 键（如 `/auth/me`），**不含**前缀。
 * 此前前缀只写在各端 env 的**注释**里 —— 那是句不可执行的话：运维按注释把基址
 * 配成网关根 ⇒ 全量 404，而编译 / 构建 / 全部门禁**全绿**（它们从不发真实请求）。
 *
 * 🛑 path 占位符必须全部被替换，且**替换后要复查残留**
 * ---------------------------------------------------------------------------
 * 契约 P2 逐字：path 参数恒 required，`{id}` 占位符必须在 URL 里被替换。
 * 漏传的后果是**静默**的：拼出字面量 `{id}` 发出去，后端路由不匹配返回 404，
 * 而调用点会以为"资源不存在"（3001 与路由 404 是两回事）。
 * 故本类**替换 + 复查残留**两步都做，缺一不可 —— 只替换不复查，
 * 漏传时不会有任何提示。
 */
object UrlBuilder {

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    /** 契约 path 里的占位符集合（供判据与调用点核对）。 */
    fun placeholdersOf(path: String): List<String> =
        PLACEHOLDER.findAll(path).map { it.groupValues[1] }.sorted().toList()

    /**
     * 用实参替换 path 里的全部占位符。
     *
     * @throws IllegalStateException 有占位符没有对应实参（**不得**留下字面量）
     */
    fun fillPath(path: String, pathArgs: Map<String, String>): String {
        val missing = placeholdersOf(path).filter { pathArgs[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            // 🛑 文案里引用概念一律用「」，**不得**用直引号 ——
            //    Kotlin 的 "..." 里出现直引号会把字符串截断，
            //    而编译器的报错会指向别处（本轮实测：报的是 IllegalStateException 歧义），
            //    排查成本极高。这是一条写代码时的硬纪律。
            val names = missing.joinToString("、") { ph -> "{" + ph + "}" }
            throw IllegalStateException(
                "出站：URL 占位符 $names 未提供实参。" +
                    "契约 P2：path 参数恒 required —— 漏传会拼出字面量占位符发出去，" +
                    "后端路由不匹配返回 404，而调用点会误判成「资源不存在」。"
            )
        }
        return PLACEHOLDER.replace(path) { m ->
            encodePathSegment(pathArgs.getValue(m.groupValues[1]))
        }
    }

    /**
     * 组装最终 URL。
     *
     * @param requestBaseUrl 出站前缀（网关根 + Base Path），取自 [com.diaoyuanyun.therapist.env.AppEnv]
     * @param query          已由调用点经分页助手 / 契约 required 参数构造
     */
    fun build(
        requestBaseUrl: String,
        endpoint: Endpoint,
        pathArgs: Map<String, String>,
        query: Map<String, String>,
    ): String {
        val filled = fillPath(endpoint.path, pathArgs)

        // 🛑 复查残留：只替换不复查，漏传时不会有任何提示（见类注释）
        val residual = placeholdersOf(filled)
        if (residual.isNotEmpty()) {
            throw IllegalStateException(
                "出站：URL 替换后仍残留占位符 ${residual.joinToString("、")} —— " +
                    "这是组装缺陷，不得发出这个请求。"
            )
        }

        val base = requestBaseUrl.trimEnd('/')
        val full = base + filled
        val parsed = full.toHttpUrlOrNull()
            ?: throw IllegalStateException("出站：URL 不合法（基址配置错误？）：$full")

        if (query.isEmpty()) return parsed.toString()
        val b = parsed.newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    /** path 段编码：保留 `-_.~` 与字母数字，其余百分号编码（不用 `+` 表示空格）。 */
    private fun encodePathSegment(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }
}
