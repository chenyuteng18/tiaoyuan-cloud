package com.diaoyuanyun.therapist.ui

import android.graphics.Color
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Ui

/**
 * 端 B · 系统栏与窗口 inset（**唯一落点**）
 * ============================================================================
 *
 * 🛑 为什么必须有这个文件：targetSdk ≥ 35 起，边到边是**强制的**，不是可选项
 * ---------------------------------------------------------------------------
 * 平台行为变更原文（Android 15 behavior changes，apps targeting API 35+）：
 *   · 「Apps are edge-to-edge by default on devices running Android 15 if the app
 *      is targeting Android 15.」
 *   · 「If your app is not already edge-to-edge, portions of your app may be
 *      obscured and **you must handle insets**.」
 *   · 状态栏：「The top offset is disabled so content draws behind the status bar
 *      **unless insets are applied**.」
 *   · 导航栏：「Bottom offset is disabled so content draws behind the system
 *      navigation bar **unless insets are applied**.」
 *
 * 而 `R.attr#statusBarColor` / `Window#setStatusBarColor` /
 * `Window#setDecorFitsSystemWindows` 都在该页的
 * 「**deprecated and disabled**（废弃且已禁用）」清单里 —— 也就是说，
 * 「靠主题给状态栏上色」这条路在 API 35+ 上**已经失效**，而本工程原先正是
 * 只靠 `android:statusBarColor` 上色、且全仓零 insets 处理。
 *
 * 🛑 失效形态与本仓前几轮抓到的缺陷**同族**：构建全绿、调试全好、上线才炸
 * ---------------------------------------------------------------------------
 *   · 边到边是**运行时**窗口行为，构建期没有任何告警：`assembleDebug`、
 *     `assembleRelease`、结构门禁当时全部是绿的；
 *   · 联调通常在习惯的机型 / 旧系统镜像上做，看不到；
 *   · 只有 API 35/36 真机才暴露：顶栏被状态栏压住、底部内容被导航栏压住。
 * 与 R8 抹掉 Gson 裸字段（debug 正常、release 失效）是同一类问题 ——
 * **编译与测试都指望不上，只能靠判据按形态守**。
 *
 * 🛑 为什么显式调用 enableEdgeToEdge()，而不是「反正 35+ 已经强制」
 * ---------------------------------------------------------------------------
 * 不调用它时：API 35+ 强制边到边，而 **API < 35 不是** —— 同一个 APK 在不同设备上
 * 走两套窗口策略，inset 是否被 Framework 自动适配也不同。显式调一次的代价是零，
 * 换来的是「所有 API 档位行为一致、这段 inset 代码被全量设备走到」。
 * 官方 codelab 的说法：`Call enableEdgeToEdge to make this backward compatible.`
 *
 * 🛑 图标明暗必须显式指定：本端**只有浅色主题**
 * ---------------------------------------------------------------------------
 * `SystemBarStyle.auto(...)` 按系统深色模式决定图标明暗 —— 而本端主题恒为
 * `Theme.Material3.Light`、背景恒为浅色 `dy_bg`。系统开深色模式时 `auto` 会给
 * 浅色图标 ⇒ 浅底配浅字，状态栏的时间/电量看不见。
 * ⇒ 必须用 `light(...)`：其实现为 `nightMode = MODE_NIGHT_NO`、
 *    `detectDarkMode = { _ -> false }`，恒为深色图标。
 *
 * 🛑 两个 scrim 参数各在哪档生效 —— **反编译 activity 1.8.0 得到的结论，非推测**
 * ---------------------------------------------------------------------------
 * `SystemBarStyle.light(scrim, darkScrim)` 的语义（androidx 源码）：
 *   · `scrim`     = 浅色底，配深色图标；
 *   · `darkScrim` = 「系统图标恒为浅色的设备」上的深色底。
 * 各 API 档位 impl 实际把哪个刷到哪根栏（`javap -c` 逐条核对 `enableEdgeToEdge`
 * 的分派实现）：
 *   · API 23–25：statusBarColor = `getScrim(isDark)` = **scrim**；
 *                navigationBarColor = **darkScrim（恒用，不看 isDark）**；
 *                只设 `isAppearanceLightStatusBars`（`LightNavigationBars` 是 26+）。
 *   · API 26–27：两根栏都 = `getScrim(isDark)` = **scrim**；两个图标 flag 都设。
 *   · API 28  ：不覆盖 `setUp`，继承 26。
 *   · API 29+ ：两根栏都 = `getScrimWithEnforcedContrast(isDark)` = **scrim**。
 *   · API 35+ ：两根栏的 setColor 均**已是 no-op**，只有图标 flag 仍生效。
 * ⇒ `darkScrim` **只被 API 23–25 的导航栏用到**。那几档导航键恒为浅色，
 *   若给它一个透明底，浅色按钮会落在本端浅色背景上 ⇒ 看不见。
 *   故本端把 `darkScrim` 设为品牌深绿 `dy_brand_dark`：视觉与品牌一致，
 *   又保证浅色导航键可读。
 *   ⚠️ 这正是「传参要传对」的地方：传错**不会报任何错**，只在特定 API 档位上
 *      表现为「导航栏按钮看不见」。
 *
 * 🛑 为什么 inset 落在 root 的 padding 上，而不是各页面各加
 * ---------------------------------------------------------------------------
 * 本端唯一 Activity（`MainActivity`）的视图树是
 * 「root（带底色）→ 顶栏 / 页签 / 内容」。
 * 把 inset 做成 **root 的 padding**：
 *   · 背景色仍铺满整屏（padding 不影响 background 的绘制区域）⇒ 状态栏区域
 *     显示的是 `dy_bg` 而不是系统默认底色，视觉连续；
 *   · 内容整体内缩，顶栏不再被状态栏压住、底部不再被导航栏压住。
 * 若把 inset 加在顶栏上，就只解决上半边，漏掉「内容区底部被导航栏压住」；
 * 若每个页面各自加，则又多出 N 处会漂移的实现（本仓头号大忌）。
 *
 * 输入法：`android:windowSoftInputMode="adjustResize"` 仍保留，但**它不再负责
 * 适配键盘**。平台对 `SOFT_INPUT_ADJUST_RESIZE` 的废弃说明原文：
 *   「Call `Window#setDecorFitsSystemWindows(boolean)` with `false` and install an
 *     `OnApplyWindowInsetsListener` on your root content view that fits insets of
 *     type `Type#ime()`.」
 * 即：`setDecorFitsSystemWindows(false)` 之后 Framework **不再**把内容视图适配到
 * inset，改由本文件负责。故此处同时吃 `ime()`，底部取 `max(系统栏, 输入法)`：
 * 键盘弹出时输入法 inset 更大，取它；收起时退回导航栏 inset —— 不会叠加两次。
 * （本端 5 个页面共 20+ 个输入框，表单是主交互，这不是边缘场景。）
 *
 * ⚠️ 一处如实登记：本文件**不可**被纯 JVM 单测覆盖（需要真窗口 / 真 ViewRootImpl），
 *    本机也没有 API 35+ 的模拟器可跑。故它由
 *    `frontends/tools/android-check.mjs` 的 `edge-to-edge-insets` 判据按**形态**
 *    钉住（启用点存在、调用顺序正确、三类 inset 齐全、主题里不得再留已禁用的
 *    系统栏属性），并由 `android-reverse-check.mjs` 证明该判据有牙齿。
 *    **真机核验**仍是未收口项，已登记在 README 第七节。
 */
object SystemBars {

    /**
     * 启用边到边、并把 inset 装到 [root] 上。
     *
     * 🛑 必须在 `setContentView(root)` **之前**调用。原因不是「会崩」，而是：
     *    `setDecorFitsSystemWindows(false)` 会改变窗口对 inset 的处置方式；
     *    若在内容视图已挂载之后再翻转，会先按「非边到边」排一次版、再重排一次
     *    （表现为首帧抖动）。官方文档给出的顺序同样是在 `setContentView` 之前。
     */
    fun setUp(activity: AppCompatActivity, root: View) {
        activity.enableEdgeToEdge(
            statusBarStyle = style(activity),
            navigationBarStyle = style(activity),
        )
        applyInsets(root)
    }

    /**
     * 两根系统栏共用同一套明暗策略：**浅色底 + 深色图标**。
     *
     * 浅色底取**透明**而不是某个具体色值，是为了让 root 的 `dy_bg` 直接透上来 ——
     * 状态栏/导航栏区域与页面同底，而不是多出一条色带。
     */
    private fun style(activity: AppCompatActivity): SystemBarStyle =
        SystemBarStyle.light(Color.TRANSPARENT, Ui.color(activity, Palette.brandDark))

    /**
     * 把「系统栏 + 刘海 + 输入法」三类 inset 变成 [root] 的 padding。
     *
     * 返回原 [insets]（**不** CONSUMED）：本端子视图都不消费 inset，保持向下传递，
     * 以便将来某个局部（如底部固定按钮条）需要自行处理时拿得到。
     */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        // 立刻派发一次，避免首帧按 0 inset 排版后再跳一次。
        ViewCompat.requestApplyInsets(root)
    }
}
