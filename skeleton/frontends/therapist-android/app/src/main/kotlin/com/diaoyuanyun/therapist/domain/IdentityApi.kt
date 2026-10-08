package com.diaoyuanyun.therapist.domain

import com.diaoyuanyun.therapist.api.ApiClient
import com.diaoyuanyun.therapist.api.Paging
import com.diaoyuanyun.therapist.auth.Access

/**
 * 域 A · 身份与合作门店（A1 / A2 / A3）
 * ============================================================================
 *
 * 🛑 本端【不】本地解 token 载荷推断角色与档位
 * ---------------------------------------------------------------------------
 * 契约逐字：A2 `/auth/me` 是「当前身份 + 角色 + 可见性档位解算结果
 * （**可见性档位唯一权威下发点**）」。本地解 token 得到的只是**签发时的声明**，
 * 而档位会随配置变化 —— 一旦与服务端解算不一致，就会出现"界面显示能看、
 * 请求却 403"这类无法自证的矛盾。故本层：**角色与档位一律读 A2；本地只缓存，不推断。**
 */
class IdentityApi(private val api: ApiClient) {

    /**
     * A1 登录。
     *
     * 🛑 请求体字段名**逐字**取自契约内联 schema：
     *      required: ["account", "credential", "client_end"]
     *    曾在别处见过写成 `{username, password, tenant_id}` 的版本 —— 那是
     *    **自创字段名**，契约里没有这三个词。契约为准：不存在 `tenant_id` 入参
     *    （租户来自 token）。这类错误的危险之处是**不会在本地报错**
     *    （服务端只返回 1001），排查者会去查账号密码，而真实原因是字段名写错了。
     *
     * 🛑 `client_end` 恒为 [CLIENT_END]，**不由调用点决定**：
     *    它是**本端身份**的一部分。写错会让 A1 按别的端的口径下发
     *    `expires_in` 与档位。
     *
     * 🛑 登录前用 `anyGrantedRoleFor("authLogin")` 取**占位角色**（从生成物现取）——
     *    登录前我们确实不知道登录进来的是调理师还是经络师，这是事实，
     *    不该用写死的角色字面量掩盖。
     *
     * 🛑 返回类型**不可空**：登录拿不到 token / data 是**硬失败**，
     *    不该把它降级成"返回 null 让调用方自己判断" —— 那会让调用点
     *    写出 `?.let { }` 而**静默不落盘**，表现为"登录没反应"。
     *    故这里当场抛错，调用点无从忽略。
     */
    suspend fun authLogin(account: String, credential: String): LoginData {
        val placeholder = Access.anyGrantedRoleFor(ENDPOINT)
            ?: throw IllegalStateException(
                "contract: $ENDPOINT 不在本端生成物里（契约可能已变更）—— " +
                    "请重跑 frontends/tools/gen-endpoints.py 并核对本端角色。"
            )
        val body = linkedMapOf<String, Any?>(
            "account" to account,
            "credential" to credential,
            "client_end" to CLIENT_END,
        )
        val data = api.oneAnonymous(ENDPOINT, placeholder, body, LoginData::class.java)
            ?: throw IllegalStateException("登录响应缺少 data（契约 LoginData）")
        if (data.token.isBlank()) {
            throw IllegalStateException("登录响应未包含 token（契约 LoginData.token）")
        }
        return data
    }

    /**
     * A2 拉取档位（可见性**唯一权威下发点**）。
     *
     * @param role 显式传入（登录后立即调用时用 A1 返回的 role）。
     *             **未登录就抛**，不回落默认角色 —— 未登录就该报"未登录"。
     */
    suspend fun authMe(role: String): AuthMeData? = api.one(
        ENDPOINT_ME, role, type = AuthMeData::class.java,
    )

    /**
     * A3 门店列表。
     *
     * 🛑 行级 scope 过滤**由服务端做**（契约 `x-row-scope`：门店负责人仅本店 /
     *    区域督导仅辖区 / 总部全量）。本层**不筛** —— 前端自行裁剪就是造第二个裁剪点，
     *    与服务端不一致时无从判断谁对。
     */
    suspend fun listStores(
        role: String,
        page: Int? = null,
        pageSize: Int? = Paging.DEFAULT_PAGE_SIZE,
    ): StoreListData = api.one(
        ENDPOINT_STORES, role,
        query = Paging.pageQuery(page, pageSize),
        type = StoreListData::class.java,
    ) ?: StoreListData()

    companion object {
        /** 本端身份标识 —— 契约 `client_end` 枚举 mp / app / web 中的**本端**取值。 */
        const val CLIENT_END = "app"

        private const val ENDPOINT = "authLogin"
        private const val ENDPOINT_ME = "authMe"
        private const val ENDPOINT_STORES = "listStores"
    }
}
