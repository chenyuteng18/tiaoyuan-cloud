package com.diaoyuanyun.therapist.api

import com.diaoyuanyun.therapist.contract.Protocol

/**
 * 端 B · 分页查询构造器（原生 Android）
 * ============================================================================
 *
 * 参数名与上下界**只来自生成物 `Protocol`**（契约 `x-api-protocol.pagination`
 * 的机械转录）。
 *
 * 🛑 为什么需要这个文件（本仓第 58 条）
 * ---------------------------------------------------------------------------
 * 契约只声明了分页**约束**（`?page=<int>&page_size=<int≤100>`），没说越界怎么办；
 * 后端两个列表端点因而各走一路（A3 超界拒 / D2 静默夹逼）。
 * 前端这一侧的同一个病是另一种形态：**参数名在各调用点手抄**。
 * 契约若把 `page_size` 改成 `pageSize`，编译 / 构建 / 门禁**都不会报** ——
 * 它们从不发真实请求，只是请求参数会静默变成服务端不认识的键，分页悄然失效。
 *
 * ⇒ 调用点一律写 `Paging.pageQuery(page, size)`，不再写 `mapOf("page" to ...)`。
 *
 * 🛑 本类**不做夹逼**（与本仓后端 `PageQuery` 同口径）
 * ---------------------------------------------------------------------------
 * 越界（< 下界 或 > 上界）一律让**服务端**按契约回 400 `VALIDATION_FAILED`。
 * 在客户端夹逼会把"我请求了 101 条却只拿到 100 条"这件事变成**无从察觉** ——
 * 那正是契约 `over-range-policy: reject-400` 要禁的形态。
 */
object Paging {

    /** 页码参数名（契约 pagination.request-fields[0]）。 */
    val PAGE_FIELD: String = Protocol.PAGE_FIELD

    /** 每页条数参数名（契约 pagination.request-fields[1]）。 */
    val PAGE_SIZE_FIELD: String = Protocol.PAGE_SIZE_FIELD

    /** 契约缺省每页条数 —— 供调用点的默认形参取值。 */
    val DEFAULT_PAGE_SIZE: Int = Protocol.PAGE_SIZE_DEFAULT

    /** 契约上界 —— 仅供界面展示可选项，**不用于夹逼**。 */
    val MAX_PAGE_SIZE: Int = Protocol.PAGE_SIZE_MAX

    /** 契约下界。 */
    val MIN_PAGE_SIZE: Int = Protocol.PAGE_SIZE_MIN

    /** 页码下界。 */
    val MIN_PAGE: Int = Protocol.PAGE_MIN

    /**
     * 构造分页 query（键名取自生成物常量）。
     *
     * 传 `null` 即**不发送该参数**（由服务端按契约缺省处置）—— 这与"发送 0"
     * 是两回事：契约 `page=0` 属越界、会被 400 拒绝，而"不发"是合法的。
     */
    fun pageQuery(page: Int? = null, pageSize: Int? = null): Map<String, String> {
        val q = LinkedHashMap<String, String>()
        if (page != null) q[PAGE_FIELD] = page.toString()
        if (pageSize != null) q[PAGE_SIZE_FIELD] = pageSize.toString()
        return q
    }

    /**
     * 越界预检 —— 仅供**界面**在提交前给出提示，**不是**替代服务端校验。
     *
     * 🛑 返回 `null` 表示"看起来合法"，不表示"服务端一定接受"：
     *    真正权威的判定在服务端（契约唯一合法处置 = 越界拒 400）。
     *    本函数存在的唯一理由是让一线在点提交之前就看到问题，
     *    而不是发出去等一个 400 回来 —— 它不是第二套校验规则，
     *    取值全部来自生成物常量，与服务端同源。
     */
    fun hintOutOfRange(page: Int?, pageSize: Int?): String? {
        if (page != null && page < MIN_PAGE) return "页码不得小于 $MIN_PAGE。"
        if (pageSize != null && pageSize < MIN_PAGE_SIZE) return "每页条数不得小于 $MIN_PAGE_SIZE。"
        if (pageSize != null && pageSize > MAX_PAGE_SIZE) return "每页条数不得大于 $MAX_PAGE_SIZE。"
        return null
    }
}
