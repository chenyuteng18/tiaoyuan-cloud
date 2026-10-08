package com.diaoyuanyun.therapist.auth

import com.diaoyuanyun.therapist.data.SessionStore
import com.diaoyuanyun.therapist.domain.AuthMeData
import com.diaoyuanyun.therapist.domain.IdentityApi
import com.diaoyuanyun.therapist.domain.LoginData

/**
 * 端 B · 会话与角色上下文（原生 Android）
 * ============================================================================
 *
 * 职责：**把"当前是谁、是哪个角色"这件事收在一个地方** —— 与 Web 端
 * `services/session.ts` 同口径，逐条移植：
 *
 * 🛑 角色与档位必须来自服务端 A2，**不本地解 token 猜**
 * ---------------------------------------------------------------------------
 * 契约逐字：A2 `/auth/me` 是「当前身份 + 角色 + 可见性档位解算结果
 * （**可见性档位唯一权威下发点**）」。本地解 token 载荷得到的只是**签发时的声明**，
 * 而档位会随配置变化 —— 一旦不一致，就会出现"界面显示能看、请求却 403"
 * 这类无法自证的矛盾。故本层：**角色与档位一律读 A2；本地只缓存，不推断。**
 *
 * 🛑 登录顺序**不可颠倒**：A1（拿 token + role）→ 收窄 role → A2（拿权威档位）
 * ---------------------------------------------------------------------------
 *   · 登录前我们**不知道**进来的是调理师还是经络师 —— 这是事实，不用默认值掩盖。
 *   · **不拿 A1 的 role 直接构造 ClientSession**：A1 **不带档位**
 *     （`band_visibility` / `refund_visibility` 只在 `AuthMeData` 里）。
 *     构造出来的会话会把档位缺省成 false ⇒ 表现为"登录成功了但界面全空"，
 *     而排查者会去查数据，不会去查"档位从没下发过"。
 *   · 先收窄角色再落盘：否则一个 manager token 会先被存下，然后 A2 报错，
 *     留下一个"有 token 但角色非法"的中间态。
 */
class Session(
    private val store: SessionStore,
    private val identity: IdentityApi,
) {

    /** 当前角色；未登录返回 `null`（**不猜**）。 */
    fun role(): String? = store.role()

    /** 当前是否已具备出站条件。 */
    fun isLoggedIn(): Boolean = store.isLoggedIn()

    /** 缓存的档位档案（可能为 null —— 界面必须显示"未下发"而不是"全不可见"）。 */
    fun cachedMe(): AuthMeData? = store.me()

    /**
     * 登录（A1 → A2）。
     *
     * 🛑 请求体字段名与 `client_end` 都收在 [IdentityApi] 里，不在本层拼 ——
     *    本层只负责**顺序**与**落盘**，字段口径只有一处。
     *
     * @return A2 的权威档位（已落盘）。
     */
    suspend fun login(account: String, credential: String): AuthMeData {
        val login: LoginData = identity.authLogin(account, credential)
        // 🛑 顺序：先收窄角色（可能抛），再落盘 token。
        //    反过来的话，非法角色的 token 会先落盘，留下中间态。
        val role = Access.requireAppRole(login.role)
        store.saveToken(login.token)
        return me(role)
    }

    /**
     * 拉取 A2（可见性档位**唯一权威下发点**）并落盘。
     *
     * @param role 显式传入（登录后立即调用时用 A1 的 role）；省略则读本地缓存的角色。
     *             **两者都没有就抛** —— 未登录就该报"未登录"，不回落默认角色。
     */
    suspend fun me(role: String? = null): AuthMeData {
        val effective = role ?: store.role()
            ?: throw IllegalStateException(
                "会话：尚未登录（无法确定角色），请先登录。不回落默认角色 ——" +
                    "回落会让「未登录」表现为「界面全空」，把排查方向引向数据而非会话。"
            )
        val data = identity.authMe(effective)
            ?: throw IllegalStateException("A2 响应缺少 data（契约 AuthMeData）。")
        // 服务端下发的角色才是权威：不是本端两个角色之一 ⇒ 明确抛出
        // （通常是拿错了端的 token，例如客户端 token 打到了端 B）。
        Access.requireAppRole(data.role)
        store.saveMe(data)
        return data
    }

    /** 退出登录。 */
    fun logout() = store.clear()
}
