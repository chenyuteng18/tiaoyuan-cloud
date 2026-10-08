package com.diaoyuanyun.therapist

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.ui.AppShell
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.SystemBars
import com.diaoyuanyun.therapist.ui.Tab
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.pages.AssessmentPage
import com.diaoyuanyun.therapist.ui.pages.BandPage
import com.diaoyuanyun.therapist.ui.pages.CustomerPage
import com.diaoyuanyun.therapist.ui.pages.IntakePage
import com.diaoyuanyun.therapist.ui.pages.LoginPage
import com.diaoyuanyun.therapist.ui.pages.MeridianActionsPage
import com.diaoyuanyun.therapist.ui.pages.ServicePage
import com.diaoyuanyun.therapist.ui.pages.WorkbenchPage
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Space
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import com.diaoyuanyun.therapist.ui.theme.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * 端 B · 应用外壳（唯一 Activity）
 * ============================================================================
 *
 * 结构（自上而下）：
 *   ┌─────────────────────────────────────────────┐
 *   │ 标题 · 角色徽标 · 退出                       │ ← 固定行
 *   ├─────────────────────────────────────────────┤
 *   │ 页签（横向可滚动，按角色过滤）                │ ← 固定行
 *   ├─────────────────────────────────────────────┤
 *   │ 当前页（[contentHost]，占满剩余高度）         │ ← 可换
 *   └─────────────────────────────────────────────┘
 *
 * 🛑 为什么是"单 Activity + 手工换页"而不是 Navigation/Fragment
 * ---------------------------------------------------------------------------
 * 本端只有 7 个页签、无深链接、无返回栈需求（返回键直接退出是合理预期）。
 * 引 Navigation + Fragment 会引入：`nav_graph.xml`（又一份页清单 —— 与
 * `ui/Nav.kt` 分叉）、Fragment 生命周期、以及"状态存在哪"的新问题。
 * 而本端页面的状态本来就该跟着页面实例走（见 [pageFor] 的缓存）。
 * ⇒ 页清单**只有一处**（`ui/Nav.kt` 的 [Tab]），外壳只做分发。
 *
 * 🛑 页面实例**缓存**，不每次重建
 * ---------------------------------------------------------------------------
 * 两个理由，都不是性能：
 *   1. **表单内容**：一线的动线是"填一半 → 提交 → 看结果 → 接着填"。
 *      每次 `rerender()` 都 new 一个页面 = 每次提交后输入框被清空。
 *   2. **在途请求的结果**：`rerender()` 会重建视图树，但页面实例持有的
 *      状态（如已读取的门店列表）不该跟着丢 —— 否则"读完了、界面却回到未读"。
 */
class MainActivity : AppCompatActivity(), AppShell {

    private val pageScope: CoroutineScope = MainScope()

    private lateinit var root: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var contentHost: LinearLayout

    private var currentTab: Tab = Tab.WORKBENCH

    /**
     * 「客户详情」页定位到的客户 ID —— 供「服务与方案」页**预填**。
     *
     * 🛑 与 Web 端 `App.tsx` 的 `customer` 状态同职责（跨页共享的客户上下文）。
     *    不放进各个页面：那会出现"服务页填了客户 A、详情页还在看客户 B"，
     *    而两边都不知道对方的存在。
     */
    override var sharedCustomerId: String = ""

    private val pageCache = LinkedHashMap<Tab, BasePage>()
    private var loginPage: LoginPage? = null

    private val host: PageHost by lazy {
        PageHost(
            activity = this,
            shell = this,
            scope = pageScope,
            // 会话失效 ⇒ 统一清会话 + 回登录页（不在各页面各写一遍）。
            onNeedRelogin = { onLoggedOut() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@MainActivity, Palette.bg))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // 🛑 顺序不可交换：必须在 setContentView **之前**启用边到边并装好 inset 监听。
        //    详见 ui/SystemBars.kt 顶部（targetSdk ≥ 35 强制边到边，靠主题给状态栏
        //    上色在 API 35+ 已失效；不处理 inset 会让顶栏被状态栏、底部被导航栏压住，
        //    而构建与调试全程没有任何告警）。
        SystemBars.setUp(this, root)
        setContentView(root)
        rerender()
    }

    override fun onDestroy() {
        // 🛑 取消页面协程：否则 Activity 销毁后回调仍会碰视图
        //    （典型症状是"退到桌面后偶发崩溃"，且栈里指向一个已销毁的 Activity）。
        pageScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // 对外（供页面调用）
    // -------------------------------------------------------------------------

    /** 登录成功后由登录页调用：切换到已登录外壳。 */
    override fun onLoggedIn() {
        currentTab = Tab.WORKBENCH
        sharedCustomerId = ""
        pageCache.clear()
        rerender()
    }

    /** 退出 / 会话失效：清会话、清缓存、回登录页。 */
    fun onLoggedOut() {
        Graph.session.logout()
        currentTab = Tab.WORKBENCH
        sharedCustomerId = ""
        pageCache.clear()
        loginPage = null
        rerender()
    }

    /** 切换页签（带准入过滤 —— 未授予则忽略，不报错）。 */
    override fun showTab(tab: Tab) {
        val role = Graph.session.role()
        // 🛑 二次准入校验：页签本身已按 canCall 过滤过，这里再查一次是为了挡住
        //    "程序化切到一个不该进的页"（例如未来加了深链）。**非**安全边界，
        //    真正的边界是出站层 + 服务端。
        if (tab.requires != null && !Access.canCall(role, tab.requires)) return
        currentTab = tab
        rerender()
    }

    /** 重绘整棵树（页面内部状态保留 —— 页面实例是缓存的）。 */
    override fun rerender() {
        root.removeAllViews()
        val role = Graph.session.role()

        if (role == null || !Graph.session.isLoggedIn()) {
            // 未登录：只渲染登录页。**不**渲染导航栏 —— 一个不能用的页签
            // 会让一线以为"登录了但没权限"，而真实原因是"根本没登录"。
            val page = loginPage ?: LoginPage(host).also { loginPage = it }
            root.addView(page.render())
            return
        }

        root.addView(buildTopBar(role))
        root.addView(buildNavRow(role))

        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(contentHost)

        // 当前页签若已不可见（角色变了 / 契约变了），回落到工作台 ——
        // 而不是停在一个"没有入口的页"上（那是死链）。
        val visible = visibleTabs(role)
        if (visible.none { it == currentTab }) currentTab = Tab.WORKBENCH
        pageFor(currentTab).let { contentHost.addView(it.render()) }

        // 工作台每次进入都重读 A2（档位是服务端解算结果，本地缓存不作权威）。
        if (currentTab == Tab.WORKBENCH) (pageFor(Tab.WORKBENCH) as WorkbenchPage).refresh()
    }

    // -------------------------------------------------------------------------
    // 视图构建
    // -------------------------------------------------------------------------

    private fun buildTopBar(role: String): View {
        val bar = UiKit.row(this).apply {
            setPadding(
                Ui.dp(this@MainActivity, Space.LG), Ui.dp(this@MainActivity, Space.MD),
                Ui.dp(this@MainActivity, Space.LG), Ui.dp(this@MainActivity, Space.SM),
            )
        }
        bar.addView(UiKit.text(this, "调元云 · 员工端", Type.MD, Palette.brand, bold = true))
        bar.addView(UiKit.text(this, " ", Type.MD).also {
            it.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        bar.addView(UiKit.badge(this, Access.roleDisplay(role), Tone.NEUTRAL))
        bar.addView(UiKit.hgap(this, Space.SM))
        bar.addView(
            UiKit.ghostButton(this, "退出") { onLoggedOut() },
        )
        return bar
    }

    private fun buildNavRow(role: String): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        navRow = UiKit.row(this).apply {
            setPadding(
                Ui.dp(this@MainActivity, Space.MD), 0,
                Ui.dp(this@MainActivity, Space.MD), Ui.dp(this@MainActivity, Space.SM),
            )
        }
        for (tab in visibleTabs(role)) {
            val selected = tab == currentTab
            navRow.addView(
                UiKit.text(
                    this, tab.label, Type.MD,
                    if (selected) Palette.brand else Palette.textMuted,
                    bold = selected,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(
                        Ui.dp(this@MainActivity, Space.SM), Ui.dp(this@MainActivity, Space.XS),
                        Ui.dp(this@MainActivity, Space.SM), Ui.dp(this@MainActivity, Space.XS),
                    )
                    setOnClickListener { showTab(tab) }
                },
            )
            navRow.addView(UiKit.hgap(this, Space.XS))
        }
        scroll.addView(navRow)
        return scroll
    }

    // -------------------------------------------------------------------------
    // 页清单 —— **唯一的分发点**，与 ui/Nav.kt 的 Tab 一一对应
    // -------------------------------------------------------------------------

    /**
     * 当前角色可见的页签。
     *
     * 🛑 过滤判定直接用**端点 id**（`tab.requires`）+ `Access.canCall`，
     *    不在这里手写"调理师看不到手环页"这类条件 —— 手写条件会与契约漂移。
     */
    private fun visibleTabs(role: String?): List<Tab> =
        Tab.entries.filter { it.requires == null || Access.canCall(role, it.requires) }

    /**
     * 取页实例（缓存）。
     *
     * 🛑 `when` 必须**穷尽** [Tab] 的每一项，且每一项都要有分支 ——
     *    这是本仓第 63/64 条在原生端的落点：**"页面写了但导航进不去"**
     *    与 **"导航有入口但没有页面"** 都必须被挡住。
     *    Kotlin 的 `when` 对 enum 做穷尽检查（缺分支即编译错误）覆盖了后者；
     *    前者由 `ui/Nav.kt` 的 Tab 表与 `tools/android-check.mjs` 的
     *    `nav-tab-render-triangulation` 判据覆盖（三方一致：Tab 表 / 本 when / 页文件）。
     */
    private fun pageFor(tab: Tab): BasePage = pageCache.getOrPut(tab) {
        when (tab) {
            Tab.WORKBENCH -> WorkbenchPage(host)
            Tab.CUSTOMER -> CustomerPage(host)
            Tab.INTAKE -> IntakePage(host)
            Tab.ASSESSMENT -> AssessmentPage(host)
            Tab.SERVICE -> ServicePage(host)
            Tab.BAND -> BandPage(host)
            Tab.SOLE -> MeridianActionsPage(host)
        }
    }
}
