package com.diaoyuanyun.therapist.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.diaoyuanyun.therapist.api.ApiErrors
import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.di.Graph
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 页面宿主 —— 页面与外壳之间**唯一**的通信面。
 * ============================================================================
 *
 * 🛑 为什么给页面一个宿主对象，而不是让页面直接持有 Activity
 * ---------------------------------------------------------------------------
 * 页面若直接拿 Activity，就能拿到 `setContentView` / `finish` / 任意 Graph 单例 ——
 * 于是"谁能换页"这件事会在每个页面里各写一遍，而外壳不再知道当前在哪一页。
 * 宿主把能力收窄成三件**声明式**的事：访问外壳（换页/重绘，见 [AppShell]）、
 * 要求重新登录、跑协程。页面因此无法自行改变导航状态（那是外壳的职责）。
 *
 * 🛑 `activity` 与 `shell` 刻意分开两个字段，不是一个
 * ---------------------------------------------------------------------------
 * `activity` 的用途是 `Context`（造视图 / 取资源）；`shell` 的用途是外壳动作。
 * 合成一个的话就必须给 Activity 加接口约束，页面侧仍然能一路 `as Any` 绕回
 * Activity 的全部能力 —— 分开之后，"想 `finish()` 自己"这件事至少要先显式
 * 写出 `(host.activity as Activity)`，在 code review 里一眼可见。
 */
class PageHost(
    /** 仅用于 `Context`（视图构建 / 资源 / 尺寸）。 */
    val activity: AppCompatActivity,
    /** 外壳动作面（换页 / 重绘 / 共享上下文）。 */
    val shell: AppShell,
    val scope: CoroutineScope,
    /** 会话失效（401 / 1002）时由外壳统一处理：清会话 + 回登录页。 */
    val onNeedRelogin: () -> Unit,
)

/**
 * 页面基类：统一"置忙 → 提交 → 结果三态 → 401 重新登录"这条动线。
 * ============================================================================
 *
 * 🛑 为什么把它收在基类而不是每个页面各写一遍
 * ---------------------------------------------------------------------------
 * 这条动线里有两处**一旦写错就静默失效**的地方：
 *   1. 忘记置忙 ⇒ 一线连点两次 ⇒ 两条幂等键 ⇒ **两次真实写入**；
 *   2. 忘记处理 auth 类失败 ⇒ 令牌过期后界面一直报错，而用户不知道要重新登录。
 * 八个页面各写一遍 = 八次机会写漏。收在基类后，漏写会变成**编译期缺参**。
 */
abstract class BasePage(protected val host: PageHost) {

    protected val ctx: Context get() = host.activity

    /** 当前角色。**未登录即抛**（不回落默认角色，理由见 `Access.requireAppRole`）。 */
    protected val role: String
        get() = Graph.session.role()
            ?: throw IllegalStateException(
                "页面：当前未登录（无角色）—— 业务页面不得在未登录时被渲染。"
            )

    /** 当前角色的展示名（取自契约 `x-roles.display`，不手写）。 */
    protected val roleLabel: String get() = Access.roleDisplay(role)

    protected val busy: Boolean get() = busyFlag
    private var busyFlag = false

    /** 渲染整页。外壳在切换页签 / 需要重绘时调用。 */
    abstract fun render(): View

    /**
     * 执行一次提交动作。
     *
     * @param button 被点的按钮（忙时禁用 + 文案替换，防止连点）
     * @param busyLabel 忙时文案（如「提交中…」）
     * @param block 实际的挂起调用；返回值用于成功态的结果展示
     */
    protected fun runAction(
        label: String,
        bar: OutcomeBar,
        button: TextView,
        busyLabel: String,
        block: suspend () -> Any?,
    ) {
        // 🛑 双保险防连点：`busyFlag` 挡住"按钮还没禁用前的第二次点击"窗口
        //    （点击事件与禁用之间存在一帧的间隙）。两次提交 = 两条幂等键 =
        //    两次真实写入，这是本仓反复防的那类事故。
        if (busyFlag) return
        busyFlag = true

        val originalText = button.text
        button.isEnabled = false
        button.alpha = 0.45f
        button.text = busyLabel
        bar.render(null)

        host.scope.launch {
            try {
                val data = block()
                bar.render(Outcome.ok(label, jsonOf(data)))
            } catch (t: Throwable) {
                bar.render(Outcome.from(label, t))
                // 会话失效 ⇒ 交外壳统一处理（清会话 + 回登录页）。
                if (ApiErrors.needRelogin(ApiErrors.describe(t))) host.onNeedRelogin()
            } finally {
                busyFlag = false
                button.isEnabled = true
                button.alpha = 1f
                button.text = originalText
            }
        }
    }

    /**
     * 造一个"动作按钮"：自己就是提交入口，忙时自我禁用 + 换文案。
     *
     * 🛑 为什么把它做成工厂方法，而不是让页面 `ghostButton { runAction(..., it) }`
     * ---------------------------------------------------------------------------
     * `UiKit.ghostButton` 的点击回调签名是 `() -> Unit`（没有 View 参数）——
     * 因为它要能用于任何场景。于是页面在回调里**拿不到按钮自己**，
     * 就无法把按钮交给 [runAction] 去禁用。曾经为此写过一版
     * "回调里 `it as TextView`" + `wireEnable` 重新挂监听器的补丁，
     * 那是个**错的**设计：`wireEnable` 每次调用都会覆盖上一个监听器，
     * 而 `originalClick` 从未被赋值 ⇒ 按钮点下去**什么都不发生**，
     * 且编译通过。本工厂把"按钮"与"动作"在同一个作用域里绑好，
     * 事后无法拆开（不需要任何 tag / 弱引用 / 全局变量）。
     *
     * @param enabled 可点性判据（**每次点击时判定**，不是渲染时算一次）——
     *                这样"先填字段、按钮随后才该亮"能即时生效。
     * @param disabledHint 判据不满足时在结果条里说明**为什么**（同样是**每次点击时**求值：
     *                     写成字符串快照的话，用户改了字段后提示会停在旧内容上，
     *                     而"提示与实际不符"比"没有提示"更误导）。
     *                     给一句解释比"点了没反应"好得多：后者会被报成"按钮坏了"。
     */
    protected fun actionButton(
        label: String,
        bar: OutcomeBar,
        busyLabel: String = "提交中…",
        enabled: () -> Boolean = { true },
        disabledHint: () -> String = { "请先补全必填项。" },
        block: suspend () -> Any?,
    ): TextView {
        // 先造一个空壳按钮（`onClick` 随后覆盖）—— 这样按钮实例在赋值前
        // 就已存在，闭包里可以安全捕获它。
        val button = UiKit.ghostButton(ctx, label) { }
        button.alpha = if (enabled()) 1f else 0.45f
        button.setOnClickListener {
            if (busyFlag) return@setOnClickListener
            if (!enabled()) {
                // 🛑 不给"点了没反应"这种体验：说明缺什么。
                bar.render(Outcome.Err(disabledHint(), ""))
                return@setOnClickListener
            }
            runAction(label, bar, button, busyLabel, block)
        }
        return button
    }

    /**
     * 把任意返回值转成可展示的文本。
     *
     * 🛑 不在这里做**字段级**的美化：本端 15 个 operation 的 `data` 契约未声明形状
     *    （见 `domain/Models.kt` 的登记），任何"按字段名排布"的展示都是臆造。
     *    原样 JSON 是**唯一不撒谎**的呈现 —— 它难看，但不会让人误以为
     *    "系统里就是这些字段"。
     */
    private fun jsonOf(data: Any?): String? = when (data) {
        null -> null
        is JsonElement -> if (data.isJsonNull) null else data.toString()
        is Unit -> null
        is String -> data
        else -> runCatching { Gson().toJson(data) }.getOrElse { data.toString() }
    }
}
