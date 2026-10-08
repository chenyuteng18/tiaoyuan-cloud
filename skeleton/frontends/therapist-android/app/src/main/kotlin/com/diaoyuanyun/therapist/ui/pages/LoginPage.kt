package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.Outcome
import com.diaoyuanyun.therapist.ui.OutcomeBar
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Space
import com.diaoyuanyun.therapist.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 端 B · 页面：登录（A1）
 * ============================================================================
 *
 * 🛑 契约 `authLogin` 的请求体**逐字**是 `{account, credential, client_end}`
 *    （内联 schema，required 三项）。本页只提供这两项输入 + 恒定的
 *    `client_end`（由 `IdentityApi.CLIENT_END` 承载）：
 *    · **不提供"选择端"的下拉** —— 端由**构建产物**决定，不是用户选项；
 *      若让用户能改 client_end，就等于允许拿 web 档位登进 APP。
 *    · **不提供注册 / 自助改密** —— 员工账号由管理端开通（端 A 的 B-7 通路）。
 *
 * 🛑 本页不继承 [BasePage] 的 `role`
 * ---------------------------------------------------------------------------
 * 登录页是**唯一**在"还没有角色"时渲染的页面，而 [BasePage.role] 在无角色时抛错。
 * 故本页只复用宿主，不读 `role` —— 强行让它继承会逼出一个假的默认角色。
 */
class LoginPage(private val host: PageHost) {

    private var account = ""
    private var credential = ""
    private var submitting = false

    fun render(): View {
        val root = UiKit.page(
            ctx = host.activity,
            title = "调元云 · 员工登录",
            roleLabel = acceptedRolesLabel(),
            subtitle = "账号与凭证由门店 / 总部在管理端开通，本端不提供注册与自助改密。",
        )

        val barSlot = UiKit.column(host.activity)
        val bar = OutcomeBar(host.activity, barSlot)
        root.addView(barSlot)

        val card = UiKit.card(
            host.activity,
            hint = "登录后系统会立即读取 A2 身份与可见性档位 —— 档位以服务端解算为准，不以本地缓存为准。",
        )

        card.addView(
            UiKit.field(
                host.activity,
                "员工号（account）",
                UiKit.input(host.activity, hint = "例如 S10023") { account = it },
            ),
        )
        card.addView(
            UiKit.field(
                host.activity,
                "凭证（credential，密码 / 短信码）",
                UiKit.input(host.activity, masked = true) { credential = it },
            ),
        )

        val button = UiKit.button(host.activity, "登录") { submit(bar) }
        card.addView(button)
        root.addView(card)

        // 端恒定：写出来让一线知道"这是员工端"。
        root.addView(
            UiKit.label(
                host.activity,
                "本端 client_end 恒为 ${com.diaoyuanyun.therapist.domain.IdentityApi.CLIENT_END}" +
                    "（由构建产物决定，界面不提供选择 —— 端不是用户选项）。",
            ),
        )
        return root
    }

    private fun acceptedRolesLabel(): String =
        Access.ACCEPTED_ROLES.joinToString(" / ") { Access.roleDisplay(it) }

    private fun submit(bar: OutcomeBar) {
        if (submitting) return
        if (account.isBlank() || credential.isBlank()) {
            bar.render(Outcome.Err("请填写账号与凭证。", ""))
            return
        }
        submitting = true
        bar.render(null)
        host.scope.launch {
            try {
                Graph.session.login(account.trim(), credential)
                // 登录成功 ⇒ 由外壳接管后续（读会话、渲染工作台）。
                host.shell.onLoggedIn()
            } catch (t: Throwable) {
                bar.render(Outcome.from("登录", t))
            } finally {
                submitting = false
            }
        }
        // 说明：此处刻意**不复用** BasePage.runAction —— 那个动线假设"已经登录"，
        // 而登录页要处理的是"登录这个动作本身"（且成功后要换掉整棵视图树）。
    }
}
