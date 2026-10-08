package com.diaoyuanyun.therapist.auth

import com.diaoyuanyun.therapist.contract.Contract
import com.diaoyuanyun.therapist.contract.Endpoint
import com.diaoyuanyun.therapist.contract.Endpoints

/**
 * 端 B · X-3 角色准入（**客户端侧**）
 * ============================================================================
 *
 * 🛑 界面过滤【不是】安全边界（这一条必须写在最前面）
 * ---------------------------------------------------------------------------
 * 真正拦住越权的是三件事，缺一不可：
 *   1. 服务端按 A2 档位裁剪 + 403（**权威**）；
 *   2. 出站层 [com.diaoyuanyun.therapist.api.ApiClient] 的 `assertCanCall`
 *      —— 挡住"界面藏了但代码还调"；
 *   3. 界面导航过滤（[solelyGrantedEndpoints] / [canCall]，由 `ui/Nav.kt` 的 Tab 表
 *      与 `MainActivity.visibleTabs` 消费）—— 不让一线看到点不动的东西。
 * 只做 3 是最常见的错（"我藏了按钮就等于没权限了"）：任何一处漏藏都会真的发出请求。
 * 只做 1 也不够：一线仍会看到点不动的东西而困惑。
 *
 * 🛑 本类的数据**全部来自生成物**，不手写任何端点 id
 * ---------------------------------------------------------------------------
 * `Endpoints.ALL` 是契约的机械转录（`gen-endpoints.py` 产出）。本类只做
 * **集合运算**（角色 ∩ grantedRoles）—— 一旦在此手写端点 id 列表，
 * 就又造出第二份清单（本仓第 46 条：契约一改就漂移，且漂移不会让门禁变红）。
 */
object Access {

    /** 本端接受的全部角色（取自**生成物**，不手写）。 */
    val ACCEPTED_ROLES: List<String> get() = Contract.END_TOKEN_ROLES

    /** 是否为本端合法角色。 */
    fun isAppRole(role: Any?): Boolean = role is String && ACCEPTED_ROLES.contains(role)

    /**
     * 收窄角色 —— 不是本端角色即**明确抛出**，不回落默认值。
     *
     * 🛑 为什么必须抛而不是 `?: "therapist"`
     * ---------------------------------------------------------------------------
     * 最常见的来路是"拿错了端的 token"（例如客户端 token 打到了端 B）。
     * 若回落一个默认角色，界面会**正常渲染**、请求由服务端 403 兜底 ——
     * 排查者会去查权限配置，而真实原因是 token 根本不是这一端的。
     */
    fun requireAppRole(role: Any?): String {
        if (!isAppRole(role)) {
            throw IllegalStateException(
                "contract: 角色 '$role' 不属于本端（合法取值：${ACCEPTED_ROLES.joinToString(" / ")}）。" +
                    "通常是拿错了端的令牌；本端不回落默认角色。"
            )
        }
        return role as String
    }

    /** 某端点的**授予角色**（未收录返回 null）。 */
    fun grantedRolesOf(endpointId: String): List<String>? =
        Endpoints.byId(endpointId)?.grantedRoles

    /**
     * 取该端点在本端的**任一**授予角色 —— 供登录等"尚未知道角色"的场景做准入占位。
     *
     * 🛑 为什么从生成物现取，而不是在调用点写 `"therapist"` 字面量
     * ---------------------------------------------------------------------------
     * 在调用点写死角色字面量会让"角色判断只存在于一个权威面"这条纪律瓦解：
     * 一旦契约变更（某端点不再授予 therapist），字面量不会跟着变，
     * 而那处调用会继续"看起来合法"。故占位角色也必须现取。
     */
    fun anyGrantedRoleFor(endpointId: String): String? =
        Endpoints.byId(endpointId)?.grantedRoles?.firstOrNull()

    /** 该角色是否有权调用该端点（客户端侧预检，**非**安全边界）。 */
    fun canCall(role: String?, endpointId: String): Boolean {
        if (role.isNullOrBlank()) return false
        val e = Endpoints.byId(endpointId) ?: return false
        return e.grantedRoles.contains(role)
    }

    /** 该角色可调用的全部端点 id。 */
    fun allowedIds(role: String?): List<String> {
        if (role.isNullOrBlank()) return emptyList()
        return Endpoints.ALL.filter { it.grantedRoles.contains(role) }.map { it.id }
    }

    /** 该角色可调用的全部端点（机械筛选生成物）。 */
    fun endpointsForRole(role: String?): List<Endpoint> {
        if (role.isNullOrBlank()) return emptyList()
        return Endpoints.ALL.filter { it.grantedRoles.contains(role) }
    }

    /**
     * 该角色在本端【不可用】的端点（= 属于本端但未授予该角色）。
     *
     * 🛑 与 [allowedIds] 互补，但**必须显式列出**而不是"取反了就完事"：
     *    工作台要把"存在、但不归我"印出来（一线最困惑的是"为什么别人能做我不能"）。
     */
    fun endpointsDeniedForRole(role: String?): List<Endpoint> {
        if (role.isNullOrBlank()) return Endpoints.ALL
        return Endpoints.ALL.filterNot { it.grantedRoles.contains(role) }
    }

    /**
     * 「只授予单一角色」的端点 —— 机械枚举，**不在调用点写角色字面量**。
     *
     * 🛑 这是本端 X-3 最尖锐的验收面：它是"漏了一个未授权入口"的最可能落点。
     *    调理师视图一旦渲染了这些中的任何一个，点下去必然 403，
     *    而报出来的现象与"权限没配好"完全一样，排查方向会整个跑偏。
     */
    fun solelyGrantedEndpoints(): List<SoleGrant> =
        Endpoints.ALL
            .filter { it.grantedRoles.size == 1 }
            .map { SoleGrant(it.grantedRoles[0], it) }

    /** 某端点是否为「只授予单一角色」；是则返回那个角色，否则 `null`。 */
    fun soleGrantedRoleOf(endpointId: String): String? {
        val g = grantedRolesOf(endpointId) ?: return null
        return if (g.size == 1) g[0] else null
    }

    /**
     * 「专属动作」导航项该依赖哪个端点 —— **机械推导，不在界面里写死行号**。
     *
     * 🛑 为什么不让界面直接写 `requires = "createRefund"`
     * ---------------------------------------------------------------------------
     * 那等于把"本端最窄的那一档"钉死在某一个具体端点上：契约增删行号后，
     * 该导航项会**悄悄指向一个已不存在（或已不再专属）的端点** ——
     * 表现是"这个页签莫名其妙消失了"，且不会让任何门禁变红（本仓第 46 条同型）。
     * ⇒ 取「当前存在的第一个单一角色专属端点」；一个都不剩时返回 `null`
     *    （导航项自动失效，而不是留一个死链）。
     */
    fun firstSoleEndpointId(): String? = solelyGrantedEndpoints().firstOrNull()?.endpoint?.id

    /**
     * 按域归组（**展示归类**，不做准入判定）。
     *
     * 🛑 域名字是**展示标签**；某端点归哪个域取自生成物的 `row`，不手写。
     */
    fun groupByDomain(role: String?): List<NavGroup> {
        val buckets = LinkedHashMap<String, MutableList<Endpoint>>()
        for (e in endpointsForRole(role)) {
            val d = domainOf(e.row)
            buckets.getOrPut(d) { mutableListOf() }.add(e)
        }
        return buckets.entries
            .sortedBy { it.key }
            .map { (domain, items) -> NavGroup(domain, items.sortedBy { it.row }) }
    }

    /**
     * 准入概览（**现算**，供工作台自证）。
     *
     * 🛑 `meridianOnly` / `therapistOnly` 都是由生成物**现算**的，
     *    不写死"7 个"这种会过期的数字 —— 契约新增端点后它会自动变。
     */
    fun summarizeAccess(): AccessSummary {
        val perRole = LinkedHashMap<String, Int>()
        for (r in ACCEPTED_ROLES) perRole[r] = 0
        val meridianOnly = mutableListOf<String>()
        val therapistOnly = mutableListOf<String>()
        for (e in Endpoints.ALL) {
            for (r in e.grantedRoles) {
                if (perRole.containsKey(r)) perRole[r] = (perRole[r] ?: 0) + 1
            }
            if (e.grantedRoles.size == 1) {
                when (e.grantedRoles[0]) {
                    "meridian" -> meridianOnly.add(e.id)
                    "therapist" -> therapistOnly.add(e.id)
                }
            }
        }
        return AccessSummary(
            contractVersion = Contract.VERSION,
            roles = ACCEPTED_ROLES,
            totalInThisEnd = Endpoints.ALL.size,
            perRole = perRole,
            meridianOnly = meridianOnly,
            therapistOnly = therapistOnly,
        )
    }

    /**
     * 该角色的导航项 = 可调用端点按契约行分组后的结果。
     *
     * 🛑 与 Web 端（端 B `App.tsx` 的 NAV 表）的差别，必须写明：
     *    Web 端的 NAV 是**手写的 7 项**，每项声明它渲染时需要哪些端点 id
     *    （由 `x3-check` ⑦ 核验 id 确实是契约里存在的）。
     *    原生端这里**不另立函数**：导航的"页"由 `ui/Nav.kt` 的 TAB 表声明
     *    （与 Web 端同构，便于逐页对齐），而"能力清单"的归组一律走
     *    [groupByDomain] —— 同一件事只留一处实现（第 46 条）。
     *    曾经这里有一个 `navFor()` 做同样的分组，已删除：
     *    两个函数算同一份结果，早晚会分叉，而分叉不会让门禁变红。
     */
    /** 契约行标识 → 域字母（`D5-a` → `D`；空行标识归入 `?`）。 */
    fun domainOf(row: String): String {
        if (row.isEmpty()) return "?"
        val c = row[0]
        return if (c in 'A'..'Z') c.toString() else "?"
    }

    /** 域 → 中文名（与契约各域的定性对齐，端 B 无 H 域）。 */
    val DOMAIN_LABEL: Map<String, String> = mapOf(
        "A" to "身份与门店",
        "B" to "建档与知情",
        "C" to "量表与评估",
        "D" to "履约与方案",
        "E" to "手环数据",
        "F" to "判定结论",
        "G" to "退款与挽留",
    )

    fun domainLabel(domain: String): String = DOMAIN_LABEL[domain] ?: domain

    /**
     * 角色展示名 —— 取自契约 `x-roles.display`（`Endpoints.ROLE_EXPANSION`）。
     *
     * 🛑 不手写中文名：契约里已有 `display`，手写就是第二份真相源。
     *    取不到时**返回原码**而不是猜一个中文 —— 猜出来的名字会掩盖"契约没声明"。
     *
     * ✅ 曾登记的一处跨端不一致，已于 2026-10-08 收口（第 78 条）
     * ---------------------------------------------------------------------------
     * 起因：Web 端（`therapist-app/src/contract/access.ts`）曾手写一张短形表
     * `ROLE_LABEL = { therapist: '调理师', meridian: '经络师' }`，而契约
     * `x-roles.display` 给的是 `调理师（APP）` / `经络师（APP）`（"角色 × 端"的展开名）。
     * 两者**都不算错**，但同一角色在两端显示不同文本，属"五源对齐"的漂移 ——
     * 且这类漂移**不报错、不违约、构建与全部判据全绿**，只有并排看两端界面才发现。
     * 收口：Web 端改为与本端**同源的取法**（遍历生成物 `ROLE_EXPANSION` → 匹配 token
     * → 取不到回退原码），中文角色名在整个仓库里只剩契约这一份；
     * 并由端 B 门禁新判据 `x3-role-label-from-contract` + 三组反向注入（I15~I17）钉住。
     * ⇒ 本端保持原写法即可，**不要**为了"对齐"再手写一份。
     */
    fun roleDisplay(role: String?): String {
        if (role.isNullOrBlank()) return "—"
        for ((_, spec) in Endpoints.ROLE_EXPANSION) {
            if (spec.tokens.contains(role)) return spec.display.ifEmpty { role }
        }
        return role
    }
}

/** 一个域下的端点集合（导航 / 工作台分组用）。 */
data class NavGroup(val domain: String, val endpoints: List<Endpoint>)

/** 「只授予单一角色」的端点 + 那个角色（成对返回，调用点不写角色字面量）。 */
data class SoleGrant(val role: String, val endpoint: Endpoint)

/** 准入概览（工作台自证用；全部现算）。 */
data class AccessSummary(
    val contractVersion: String,
    val roles: List<String>,
    val totalInThisEnd: Int,
    val perRole: Map<String, Int>,
    val meridianOnly: List<String>,
    val therapistOnly: List<String>,
)
