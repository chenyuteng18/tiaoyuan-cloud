package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import android.widget.LinearLayout
import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.contract.Contract
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.AuthMeData
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.Outcome
import com.diaoyuanyun.therapist.ui.OutcomeBar
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Space
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 端 B · 页面：工作台（X-3 角色级装载的呈现面）
 * ============================================================================
 *
 * 本页回答三个问题，且答案**全部由生成物现算**（无一处手写端点清单）：
 *   ① 我是哪个角色？（来自 A2，不是本地猜的）
 *   ② 我这个角色可以做哪些事？（[Access.groupByDomain] 机械筛选）
 *   ③ 哪些事**我明知道有、但不归我做**？（[Access.endpointsDeniedForRole]）
 *
 * 🛑 为什么必须把 ③ 显式列出来，而不是"藏起来当不存在"
 * ---------------------------------------------------------------------------
 * 一线最常见的困惑不是"我不能做什么"，而是**"为什么别人能做我不能"** ——
 * 如果不显示"该动作存在、需要经络师权限"，一线会认为系统缺功能，
 * 反复提需求或私下借账号。把边界写出来（并写明**该找谁**）是唯一能终止
 * 这类往返的做法。同时这**也是 X-3 的可验收形态**：7 个仅经络师端点
 * 在调理师视图下必须出现在"不归我"列表里。
 *
 * 🛑 本页不提供"以其他角色预览"的开关
 * ---------------------------------------------------------------------------
 * 那会造出一个**绕过 X-3 的入口**（预览即等于临时提权），
 * 且会让"界面藏了入口但代码能调通"的缝重新长出来。
 * 想看经络师视图，就用经络师账号登录 —— 由服务端签发对应档位。
 */
class WorkbenchPage(host: PageHost) : BasePage(host) {

    private var live: AuthMeData? = Graph.session.cachedMe()
    private var error: Pair<String, String>? = null

    // A3 合作门店（本页负责，理由见下）
    private var stores: com.diaoyuanyun.therapist.domain.StoreListData? = null
    private var storesError: Pair<String, String>? = null

    /**
     * 🛑 每次进入工作台都**重新读一次 A2** —— 档位是服务端解算结果，
     *    本地缓存不作权威（契约逐字：A2 是「可见性档位唯一权威下发点」）。
     */
    fun refresh() {
        host.scope.launch {
            try {
                live = Graph.session.me(role)
                error = null
            } catch (t: Throwable) {
                val d = com.diaoyuanyun.therapist.api.ApiErrors.describe(t)
                error = d.text to d.traceId
            }
            host.shell.rerender()
        }
    }

    override fun render(): View {
        val root = UiKit.page(ctx, "工作台", roleLabel)

        error?.let { (text, traceId) ->
            root.addView(UiKit.errorBar(ctx, text, traceId))
        }

        root.addView(identityCard())

        val mine = Access.endpointsForRole(role)
        val notMine = Access.endpointsDeniedForRole(role)

        root.addView(capabilityCard(mine))
        root.addView(notMineCard(notMine))
        root.addView(storesCard())
        root.addView(contractCard())
        return root
    }

    // -------------------------------------------------------------------------
    // 我的身份与档位
    // -------------------------------------------------------------------------

    private fun identityCard(): View {
        val card = UiKit.card(
            ctx,
            title = "我的身份与档位",
            hint = "以下全部来自 A2（可见性档位唯一权威下发点），不是本地推断。",
        )
        card.addView(UiKit.kvView(ctx, "角色", UiKit.badge(ctx, roleLabel, Tone.NEUTRAL)))
        card.addView(UiKit.kv(ctx, "角色码", role))
        card.addView(UiKit.kv(ctx, "契约版本", Contract.VERSION))

        val bv = live?.bandVisibility
        card.addView(UiKit.kv(ctx, "① 手环原始数据", tierText(bv?.fieldGroup1Raw)))
        card.addView(UiKit.kv(ctx, "② 采集状态", tierText(bv?.fieldGroup2Status)))
        card.addView(UiKit.kv(ctx, "③ 缺口原因分类", tierText(bv?.fieldGroup3GapReason)))
        card.addView(UiKit.kv(ctx, "④ 派生结果", tierText(bv?.fieldGroup4Derived)))

        /*
         * 🛑 档位三态必须分开显示，不得把"未下发"与"下发为否"合并成"不可见"：
         *    契约 AuthMeData.refund_visibility 的 x-visible-to = [meridian, admin]
         *    ⇒ 调理师这一档**根本不下发**（不是下发 false）。
         *    合并显示会让运维以为"服务端把它关了"，而实际是"该角色无此档位"。
         */
        card.addView(
            UiKit.kv(
                ctx,
                "R1 组档位（退款可见性）",
                when (val rv = live?.refundVisibility) {
                    null -> "未下发（本角色无此档位）"
                    true -> "已下发：可见"
                    false -> "已下发：否"
                },
            ),
        )

        live?.storeScope?.let { scope ->
            card.addView(
                UiKit.kv(
                    ctx,
                    "数据行级范围",
                    "${scope.rowLevel}（${scope.storeIds.size} 家门店）",
                ),
            )
        }
        return card
    }

    /** 档位三态（null 与 false 分开）。 */
    private fun tierText(v: Boolean?): String = when (v) {
        null -> "（A2 未返回）"
        true -> "可见"
        false -> "不可见"
    }

    // -------------------------------------------------------------------------
    // 我可用能力
    // -------------------------------------------------------------------------

    private fun capabilityCard(mine: List<com.diaoyuanyun.therapist.contract.Endpoint>): View {
        val card = UiKit.card(
            ctx,
            title = "我这个角色可用的能力（${mine.size} 项）",
            hint = "由生成物的 grantedRoles 机械筛选，本页不含任何手写端点清单。",
        )
        if (mine.isEmpty()) {
            card.addView(UiKit.empty(ctx, "无可用能力。"))
            return card
        }
        for (g in Access.groupByDomain(role)) {
            card.addView(
                UiKit.text(ctx, "${g.domain} · ${Access.domainLabel(g.domain)}", Type.MD, Palette.text)
                    .also { it.layoutParams = marginTop() },
            )
            // 端点逐项一行（原生端不做 flex-wrap 标签流 —— 一行一项可读性更稳，
            // 且长 id 不会被挤成两行）。
            for (e in g.endpoints) {
                card.addView(
                    UiKit.label(ctx, "  ${e.row}  ${e.id}   (${e.method.name} ${e.path})"),
                )
            }
        }
        return card
    }

    // -------------------------------------------------------------------------
    // 不归我
    // -------------------------------------------------------------------------

    private fun notMineCard(notMine: List<com.diaoyuanyun.therapist.contract.Endpoint>): View {
        val card = UiKit.card(
            ctx,
            title = "存在、但不归我这个角色（${notMine.size} 项）",
            hint = "这些能力在系统里是有的，只是契约未授予当前角色。要做请找对应角色，不要借账号。",
        )
        if (notMine.isEmpty()) {
            card.addView(UiKit.empty(ctx, "当前角色被授予了本端全部可用能力。"))
            return card
        }
        for (e in notMine) {
            val owners = e.grantedRoles.joinToString(" / ") { Access.roleDisplay(it) }
            card.addView(
                UiKit.kv(
                    ctx,
                    "${e.row} ${e.id}",
                    "${e.method.name} ${e.path}（仅 $owners）",
                ),
            )
        }
        return card
    }

    // -------------------------------------------------------------------------
    // A3 合作门店
    // -------------------------------------------------------------------------

    private fun storesCard(): View {
        val card = UiKit.card(
            ctx,
            title = "我的合作门店（A3）",
            hint = "按行级 scope 过滤 —— 过滤由服务端做（store_scope），本页不筛" +
                "（X-1：前端不造第二份范围权威）。",
        )
        val barSlot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, barSlot)
        card.addView(barSlot)

        val button = UiKit.ghostButton(ctx, "读取合作门店") {
            host.scope.launch {
                bar.render(null)
                try {
                    // 🛑 行级 scope 过滤**由服务端做**（契约 x-row-scope）。
                    //    本层不筛 —— 前端自行裁剪就是造第二个裁剪点。
                    stores = Graph.identityApi.listStores(role)
                    storesError = null
                } catch (t: Throwable) {
                    val d = com.diaoyuanyun.therapist.api.ApiErrors.describe(t)
                    storesError = d.text to d.traceId
                }
                host.shell.rerender()
            }
        }
        card.addView(button)

        storesError?.let { (text, traceId) -> card.addView(UiKit.errorBar(ctx, text, traceId)) }
        when (val s = stores) {
            null -> card.addView(UiKit.empty(ctx, "尚未读取。"))
            else -> if (s.items.isEmpty()) {
                card.addView(
                    UiKit.empty(ctx, "当前范围下没有门店（可能 scope 为 own_store 且未绑定门店）。"),
                )
            } else {
                for (st in s.items) {
                    card.addView(
                        UiKit.kv(ctx, st.name, "${st.storeId} · ${st.franchiseType}"),
                    )
                }
                card.addView(
                    UiKit.label(ctx, "共 ${s.total} 家（本页第 ${s.page} 页，每页 ${s.pageSize} 条）。")
                        .also { it.layoutParams = marginTop() },
                )
            }
        }
        return card
    }

    // -------------------------------------------------------------------------
    // 契约自证
    // -------------------------------------------------------------------------

    private fun contractCard(): View {
        val card = UiKit.card(
            ctx,
            title = "契约侧核对（供排查用）",
            hint = "数字由生成物现算；契约一变它自动跟着变。",
        )
        val s = Access.summarizeAccess()
        card.addView(UiKit.kv(ctx, "本端端点数", s.totalInThisEnd.toString()))
        card.addView(
            UiKit.kv(
                ctx,
                "按角色计数",
                s.roles.joinToString("  ·  ") { "${Access.roleDisplay(it)} ${s.perRole[it] ?: 0}" },
            ),
        )
        card.addView(
            UiKit.kv(
                ctx,
                "仅经络师可用",
                if (s.meridianOnly.isEmpty()) "（无）" else s.meridianOnly.joinToString(" · "),
            ),
        )
        card.addView(
            UiKit.kv(
                ctx,
                "仅调理师可用",
                if (s.therapistOnly.isEmpty()) "（无）" else s.therapistOnly.joinToString(" · "),
            ),
        )
        return card
    }

    private fun marginTop(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = com.diaoyuanyun.therapist.ui.theme.Ui.dp(ctx, Space.MD) }
}
