package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.api.ApiErrors
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.CustomerDetailData
import com.diaoyuanyun.therapist.domain.VisitData
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.Outcome
import com.diaoyuanyun.therapist.ui.OutcomeBar
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 端 B · 页面：客户详情（③④ 组的呈现面）
 * ============================================================================
 *
 * 这是端 B 与端 C **差异最大**的一页，差异全部来自契约矩阵（不是设计偏好）：
 *
 *   | 字段组 | 端 C（客户） | 端 B（staff） |
 *   |---|---|---|
 *   | ① 手环原始数据     | 可见 | 可见 |
 *   | ② 采集状态         | 可见 | 可见 |
 *   | ③ 缺口原因分类     | **不可见**（硬约束） | 可见 |
 *   | ④ 派生结果         | **不可见**（硬约束） | 可见 |
 *
 * 🛑 关于"借调"这一栏的写法（刻意的）
 * ---------------------------------------------------------------------------
 * 契约把 service 端的角色称为 `therapist`(调理师)/`meridian`(经络师)，
 * 但**本页不推断两者的业务分工**（例如"谁负责判定"）—— 那属于 PRD 的解释层，
 * 不是契约层。本页只呈现**契约事实**：该动作需要哪个角色。
 * 把推断写进界面会让一次业务口径调整变成一次前端改造。
 *
 * 🛑 本端【没有】"列出我负责的客户"这个端点（29 个里没有 listCustomers）
 * ---------------------------------------------------------------------------
 * 故本页要求**手工输入客户 ID** —— 这是如实登记的缺口，
 * 不用示例数据顶替（用示例数据会让一线以为系统里有这些客户）。
 */
class CustomerPage(host: PageHost) : BasePage(host) {

    private var customerId = host.shell.sharedCustomerId
    private var customer: CustomerDetailData? = null
    private var error: Pair<String, String>? = null

    private var visits: List<VisitData>? = null
    private var visitsError: Pair<String, String>? = null

    override fun render(): View {
        val root = UiKit.page(ctx, "客户详情", roleLabel)

        error?.let { (t, id) -> root.addView(UiKit.errorBar(ctx, t, id)) }

        root.addView(locateCard())
        customer?.let { root.addView(basicCard(it)); root.addView(derivedCard(it)) }
        root.addView(visitsCard())
        root.addView(contractCard())
        return root
    }

    // -------------------------------------------------------------------------

    private fun locateCard(): View {
        val card = UiKit.card(
            ctx,
            title = "定位客户",
            hint = "契约端 B 无「列出我负责的客户」端点，故此处只能手工输入客户 ID ——" +
                "这是如实登记的缺口，不用示例数据顶替。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx, initial = customerId, hint = "customer uuid") { customerId = it },
            ),
        )
        card.addView(
            UiKit.ghostButton(ctx, "读取客户") {
                host.scope.launch {
                    error = null
                    try {
                        customer = Graph.intakeApi.getCustomer(role, customerId.trim())
                        // 读到客户后写入跨页共享上下文（「服务与方案」页据此预填）。
                        host.shell.sharedCustomerId = customerId.trim()
                        if (customer == null) error = "未读取到客户（服务端未返回 data）。" to ""
                    } catch (t: Throwable) {
                        val d = ApiErrors.describe(t)
                        error = d.text to d.traceId
                        customer = null
                    }
                    host.shell.rerender()
                }
            },
        )
        return card
    }

    private fun basicCard(c: CustomerDetailData): View {
        val card = UiKit.card(ctx, title = "基本信息")
        card.addView(UiKit.kv(ctx, "客户 ID", c.customerId))
        card.addView(UiKit.kv(ctx, "姓名", c.name ?: "—"))
        card.addView(UiKit.kv(ctx, "性别 / 年龄", "${c.gender ?: "—"} · ${c.age ?: "—"} 岁"))
        card.addView(UiKit.kv(ctx, "筛查结果", c.screeningResult ?: "—"))
        card.addView(UiKit.kv(ctx, "佩戴意愿", c.bandWillingness ?: "—"))
        /*
         * 🛑 owner_store_id / serving_store_id 对客户不下发、对 staff 下发。
         *    本端显示它们**是契约允许的**；端 C 同名页不得引用这两个字段。
         */
        card.addView(UiKit.kv(ctx, "归属门店", c.ownerStoreId ?: "—"))
        card.addView(UiKit.kv(ctx, "服务门店", c.servingStoreId ?: "—"))
        /*
         * 🛑 刻意**不显示** `status`（5 值聚合态）：契约只在 `CustomerCreateData`
         *    （建档响应）里定义它，`CustomerDetailData`（B4 详情）**没有这个字段**。
         *    Web 端初版曾引用 `customer.status`，被 tsc 挡下 —— 这正是
         *    "字段名必须逐条对齐契约"的实例：若用 `as any` 绕过，界面会永远显示 "—"，
         *    而没人会知道是字段名错了（静默假绿）。原生端由**编译期**挡住：
         *    `CustomerDetailData` 上根本没有这个属性。
         */
        return card
    }

    private fun derivedCard(c: CustomerDetailData): View {
        val card = UiKit.card(
            ctx,
            title = "④ 派生结果（客户不可见）",
            hint = "该组对客户恒不下发；本端可见是因为契约 x-visibility-matrix 对本端为 true。",
        )
        card.addView(UiKit.kv(ctx, "效果结论", c.effectVerdict ?: "—"))
        card.addView(
            UiKit.kv(
                ctx,
                "AS 值",
                if (c.asValue == null) "—（未下发）" else c.asValue.toString(),
            ),
        )
        card.addView(
            UiKit.label(ctx, "派生字段一律以服务端计算结果为准；本端**不做任何前端推算**（X-1）。"),
        )
        return card
    }

    private fun visitsCard(): View {
        val card = UiKit.card(ctx, title = "服务记录（D2）")
        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        card.addView(slot)

        card.addView(
            UiKit.ghostButton(ctx, "读取服务记录") {
                host.scope.launch {
                    bar.render(null)
                    try {
                        visits = Graph.fulfillmentApi.listVisits(role, customerId.trim())
                        visitsError = null
                    } catch (t: Throwable) {
                        val d = ApiErrors.describe(t)
                        visitsError = d.text to d.traceId
                    }
                    host.shell.rerender()
                }
            },
        )
        visitsError?.let { (t, id) -> card.addView(UiKit.errorBar(ctx, t, id)) }

        when (val list = visits) {
            null -> card.addView(UiKit.empty(ctx, "尚未读取。"))
            else -> if (list.isEmpty()) {
                card.addView(UiKit.empty(ctx, "暂无服务记录。"))
            } else {
                for (v in list) {
                    val confirmTone = if (v.customerConfirmed == true) Tone.OK else Tone.WARN
                    val confirmText = if (v.customerConfirmed == true) "客户已确认" else "客户未确认"
                    card.addView(
                        UiKit.kvView(
                            ctx,
                            "第 ${v.visitNo ?: "—"} 次",
                            UiKit.row(ctx).apply {
                                addView(UiKit.text(ctx, v.executedAt ?: "—", Type.MD, Palette.text))
                                addView(UiKit.hgap(ctx))
                                addView(UiKit.badge(ctx, confirmText, confirmTone))
                                if (!v.abnormalNote.isNullOrBlank()) {
                                    addView(UiKit.hgap(ctx))
                                    addView(
                                        UiKit.text(
                                            ctx, "异常备注：${v.abnormalNote}", Type.MD, Palette.warn,
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                }
            }
        }
        return card
    }

    private fun contractCard(): View {
        val card = UiKit.card(ctx, title = "契约侧提示")
        card.addView(
            UiKit.label(
                ctx,
                "本页可显示 ③ 组（缺口原因）与 ④ 组（派生结果）是因为当前角色为 $roleLabel。" +
                    "R1 组档位由 A2 下发：经络师与管理员有该档位，调理师无该档位" +
                    "（服务端不下发该字段，而不是下发为否）。",
            ),
        )
        return card
    }
}
