package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.api.ApiErrors
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.BandDerivedData
import com.diaoyuanyun.therapist.domain.BandTelemetryData
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.OutcomeBar
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.theme.Labels
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 端 B · 页面：手环数据（E3 + E4）
 * ============================================================================
 *
 * 本页是 `a3_applicable` 与 `data_source` 两条契约条文的**落点**：
 *
 *  1. `a3_applicable == false` ⇒ **不渲染 a3_value**。
 *     契约给的理由逐字：「避免 0 值被误读为"戴了 0 天"」。
 *     换算到界面：不适用时若显示 "A3: 0"，一线会去追问客户"为什么一天都没戴"，
 *     而真实原因是这个指标对本次评估不适用。**显示错误的值比不显示更坏。**
 *
 *  2. `data_source == '未接入'` ⇒ 只显示"未接入"，**不显示 0 / 空图表 / 示例数据**。
 *     未接入时给一个 0 值的表，会让一线以为"设备在跑但数据是 0"，进而去查设备。
 *
 *  3. ③ 组 `gap_reason` 7 值**逐条给中文解释**，且 `not_worn` 与
 *     `compliant_removal` 分开显示：后者是**按说明书主动摘除**（洗浴/桑拿/游泳），
 *     不是缺失。把两者合并会制造"客户不配合"的错误印象。
 *
 * 🛑 本页**只读**，不做任何减法 —— ④ 组由服务端算（X-1）。
 */
class BandPage(host: PageHost) : BasePage(host) {

    private var tel: BandTelemetryData? = null
    private var derived: BandDerivedData? = null
    private var loaded = false
    private var error: Pair<String, String>? = null

    override fun render(): View {
        val root = UiKit.page(ctx, "手环数据", roleLabel)
        val customerId = host.shell.sharedCustomerId

        if (customerId.isBlank()) {
            // 🛑 手环数据以客户为主键，而本端**没有**客户列表端点（见 CustomerPage 登记）。
            //    故这里明说"先去客户详情定位"，而不是给一个空表 ——
            //    空表会被读成"这个客户没数据"。
            val card = UiKit.card(
                ctx,
                title = "请先定位客户",
                hint = "手环数据以客户为主键，而本端契约里没有「列出我负责的客户」端点，" +
                    "故无法提供客户选择器。请先到「客户详情」页读取一个客户。",
            )
            card.addView(
                UiKit.ghostButton(ctx, "去客户详情") { host.shell.showTab(com.diaoyuanyun.therapist.ui.Tab.CUSTOMER) },
            )
            root.addView(card)
            return root
        }

        error?.let { (t, id) -> root.addView(UiKit.errorBar(ctx, t, id)) }

        root.addView(readCard(customerId))

        if (loaded) {
            root.addView(telemetryCard())
            root.addView(gapCard())
            root.addView(derivedCard())
        }

        val hint = UiKit.card(ctx, title = "契约侧提示")
        hint.addView(
            UiKit.label(
                ctx,
                "客户 ID：$customerId · 当前角色 $roleLabel 对 ③④ 两组均有档位" +
                    "（契约 x-visibility-matrix）。客户对这两组恒不可见。",
            ),
        )
        root.addView(hint)
        return root
    }

    private fun readCard(customerId: String): View {
        val card = UiKit.card(ctx)
        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        card.addView(slot)
        card.addView(
            UiKit.ghostButton(ctx, "读取手环数据（E3 + E4）") {
                host.scope.launch {
                    bar.render(null)
                    try {
                        tel = Graph.bandApi.getBandTelemetry(role, customerId)
                        // E4 仅 staff：本端两角色都可见 ④ 组。客户走这条会被服务端 403。
                        derived = Graph.bandApi.getBandDerived(role, customerId)
                        error = null
                    } catch (t: Throwable) {
                        val d = ApiErrors.describe(t)
                        error = d.text to d.traceId
                    } finally {
                        loaded = true
                    }
                    host.shell.rerender()
                }
            },
        )
        return card
    }

    private fun telemetryCard(): View {
        val card = UiKit.card(ctx, title = "① 原始数据 / ② 采集状态（E3）")
        val t = tel
        if (t == null) {
            card.addView(UiKit.empty(ctx, "未读取到遥测数据（服务端未返回 data）。"))
            return card
        }
        // data_source == '未接入' ⇒ 只显示未接入，不给任何数值占位。
        if (t.dataSource == "未接入") {
            card.addView(UiKit.empty(ctx, "手环未接入：设备尚未开始回传数据，此处不显示任何数值。"))
            return card
        }
        card.addView(UiKit.kvView(ctx, "数据来源", UiKit.badge(ctx, t.dataSource ?: "—", Tone.NEUTRAL)))
        card.addView(UiKit.kv(ctx, "已采集天数", if (t.collectedDays == null) "—" else "${t.collectedDays} 天"))
        card.addView(UiKit.kv(ctx, "同步日期", t.syncedDate ?: "—"))
        card.addView(UiKit.kv(ctx, "指标条数", t.metrics.size.toString()))
        card.addView(UiKit.label(ctx, "同步时间精确到日（契约 R4：客户可见不得到分秒）。"))
        return card
    }

    private fun gapCard(): View {
        val card = UiKit.card(
            ctx,
            title = "③ 缺口原因分类（客户不可见）",
            hint = "本组是缺口原因【分类】—— 是事实记录，不是评价，也不指责任何一方。",
        )
        val raw = tel?.gapReason
        if (raw.isNullOrBlank()) {
            card.addView(UiKit.empty(ctx, "无缺口分类（要么数据完整，要么服务端未下发该字段）。"))
            return card
        }
        val label = Labels.gapReason(raw)
        card.addView(UiKit.kvView(ctx, "分类", UiKit.badge(ctx, label.text, label.tone)))
        card.addView(UiKit.kv(ctx, "机读键", raw))
        card.addView(
            UiKit.text(
                ctx,
                "`not_worn` 与 `compliant_removal` 是**两件不同的事**：" +
                    "后者是按说明书在洗浴 / 桑拿 / 游泳时主动摘除，不是缺失。" +
                    "本组对客户恒不下发（硬约束，配置不得放开）。",
                Type.XS, Palette.textMuted,
            ),
        )
        return card
    }

    private fun derivedCard(): View {
        val card = UiKit.card(
            ctx,
            title = "④ 派生结果（客户不可见）",
            hint = "全部由服务端计算；本端不做任何前端推算（X-1）。",
        )
        val d = derived
        if (d == null) {
            card.addView(UiKit.empty(ctx, "未读取到派生结果。"))
            return card
        }

        card.addView(
            UiKit.kvView(
                ctx,
                "A3 是否适用",
                when (d.a3Applicable) {
                    null -> UiKit.text(ctx, "—（未下发）", Type.MD, Palette.textMuted)
                    true -> UiKit.badge(ctx, "适用", Tone.OK)
                    false -> UiKit.badge(ctx, "不适用（不显示数值）", Tone.INFO)
                },
            ),
        )
        /*
         * 🛑 契约：a3_applicable 为 false 时不返回 a3_value。
         *    故此处**以 a3_applicable 为准**决定是否显示 —— 不以
         *    "a3_value 是否有值"为准。后者会把一次契约违反静默吞掉。
         */
        card.addView(
            UiKit.kv(
                ctx,
                "A3 值",
                when {
                    d.a3Applicable != true -> "不显示（不适用）"
                    d.a3Value == null -> "—（未下发）"
                    else -> d.a3Value.toString()
                },
            ),
        )
        card.addView(UiKit.kv(ctx, "AS 值", d.asValue?.toString() ?: "—（未下发）"))
        card.addView(
            UiKit.kvView(
                ctx,
                "效果结论",
                UiKit.badge(ctx, Labels.effectVerdict(d.effectVerdict).text, Labels.effectVerdict(d.effectVerdict).tone),
            ),
        )
        card.addView(
            UiKit.kv(
                ctx,
                "R1 组资格",
                when (d.refundEligibility) {
                    null -> "—（未下发）"
                    true -> "是"
                    false -> "否"
                },
            ),
        )
        card.addView(
            UiKit.text(
                ctx,
                "A3 不适用时**必须不显示数值** —— 契约给的理由是避免 0 值被误读为" +
                    "「戴了 0 天」。显示错误的数值比不显示更坏。",
                Type.XS, Palette.warn,
            ),
        )
        return card
    }
}
