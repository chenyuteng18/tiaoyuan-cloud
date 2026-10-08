package com.diaoyuanyun.therapist.ui

import com.diaoyuanyun.therapist.auth.Access

/**
 * 端 B · 导航表（应用外壳的**唯一**页清单）
 * ============================================================================
 *
 * 与 Web 端 `therapist-app/src/App.tsx` 的 `NAV` **逐项同构**，一一对应：
 *
 *   | Tab            | Web 端      | requires（端点 id）           |
 *   |----------------|-------------|-------------------------------|
 *   | WORKBENCH      | workbench   | null（不依赖具体端点）        |
 *   | CUSTOMER       | customer    | `getCustomer`                 |
 *   | INTAKE         | intake      | `createCustomer`              |
 *   | ASSESSMENT     | assessment  | `submitBaselineAssessment`    |
 *   | SERVICE        | service     | `createVisit`                  |
 *   | BAND           | band        | `getBandTelemetry`            |
 *   | SOLE           | sole        | `Access.firstSoleEndpointId()`|
 *
 * 🛑 `requires` 是**端点 id**，界面过滤时用 `Access.canCall(role, requires)` 判定，
 *    不在界面里手写"调理师看不到手环页"这类条件 —— 手写条件会与契约漂移。
 *    `null` 表示不依赖具体端点。
 *
 * 🛑 「专属动作」页的 `requires` 由 [Access.firstSoleEndpointId] **现算**
 * ---------------------------------------------------------------------------
 * 不写 `requires = "createRefund"` 这类固定端点：那等于把"本端最窄的那一档"
 * 钉在某一个具体端点上，契约增删行号后它会悄悄指错，**而且不会让门禁变红**。
 *
 * 🛑 本表与"渲染分支"必须**三方一致**（本仓第 63/64 条的落点）
 * ---------------------------------------------------------------------------
 * 三方 = ① 本表的 Tab 枚举；② `MainActivity` 的 when 分支；③ 各页文件的存在。
 * 只做其中两方会漏掉最典型的形态：**页面写了但导航进不去**
 * （编译/构建全绿，功能缺失）。`frontends/tools/android-check.mjs` 的
 * `nav-tab-render-triangulation` 判据逐项核对这三方。
 */
enum class Tab(val label: String, val requires: String?) {
    /**
     * 工作台 —— 不依赖具体端点。
     *
     * 🛑 它是"我这个身份能做什么"的呈现面，本身调 A2（档位唯一权威）
     *    与 A3（合作门店），而 A2 的准入角色从生成物现取，故不写死 requires。
     */
    WORKBENCH("工作台", null),

    CUSTOMER("客户详情", "getCustomer"),

    /**
     * 客户建档（域 B 的 B1~B6 入口）。
     *
     * 🛑 这三项（建档 / 评估 / 服务）在 Web 端是 2026-09-30 才补上的
     *    —— 此前域 B/C/D 的封装**全部写好了但从没接上界面**，
     *    一线打开 APP 看不到建档 / 评估 / 服务，只能去用端 A。
     *    原生端从第一版起就带上它们，避免复现同一个缺口。
     */
    INTAKE("客户建档", "createCustomer"),
    ASSESSMENT("量表评估", "submitBaselineAssessment"),
    SERVICE("服务与方案", "createVisit"),
    BAND("手环数据", "getBandTelemetry"),

    /**
     * 专属动作 —— 依赖"本端最窄的一档"里当前存在的第一个端点（在此现算）。
     *
     * 🛑 `requires` 为 `null` 时意味着**契约里已不存在任何单一角色专属端点**，
     *    那时该页签自动不出现（不留死链）。这不是"漏了"，是契约事实。
     */
    SOLE("专属动作", Access.firstSoleEndpointId()),
}
