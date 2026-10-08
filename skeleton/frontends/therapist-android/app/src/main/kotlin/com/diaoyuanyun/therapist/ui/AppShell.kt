package com.diaoyuanyun.therapist.ui

/**
 * 端 B · 外壳能力面（页面的**唯一**换页 / 重绘入口）
 * ============================================================================
 *
 * 🛑 为什么需要这个接口，而不是让页面直接持有 `MainActivity`
 * ---------------------------------------------------------------------------
 * 最初版本把 `PageHost.activity` 声明成 `AppCompatActivity`，页面里写
 * `host.activity.rerender()` —— 那是**编译不过**的（`AppCompatActivity` 上没有
 * `rerender`），于是想让页面直接用 `MainActivity` 类型。但那样会：
 *   · 让"谁能换页"在每个页面里各拿一个 Activity 引用（外壳不再独占导航状态）；
 *   · 把 `ui` 包反向依赖到根包（活动类），层级立刻变成环形。
 * ⇒ 用接口把能力**收窄成四项**：换页 / 重绘 / 登录完成 / 共享客户上下文。
 *    页面因此**拿不到** `finish()`、`setContentView()`、以及任何 Graph 单例 ——
 *    它只能在被允许的四件事里行动。
 */
interface AppShell {

    /**
     * 跨页共享的「当前定位到的客户 ID」。
     *
     * 🛑 与 Web 端 `App.tsx` 的 `customer` 状态同职责。
     *    不放进各页面：那会出现"服务页填了客户 A、详情页还在看客户 B"，
     *    而两边都不知道对方的存在。
     */
    var sharedCustomerId: String

    /** 切换页签（内部会做准入过滤 —— 未授予则忽略，不报错）。 */
    fun showTab(tab: Tab)

    /** 重绘整棵树（页面实例是缓存的，表单内容与已加载数据不丢）。 */
    fun rerender()

    /** 登录成功：外壳切换到已登录形态（工作台 + 导航栏）。 */
    fun onLoggedIn()
}
