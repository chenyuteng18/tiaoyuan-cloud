package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.DailyReportData
import com.diaoyuanyun.therapist.domain.FulfillmentApi
import com.diaoyuanyun.therapist.ui.BasePage
import com.diaoyuanyun.therapist.ui.Outcome
import com.diaoyuanyun.therapist.ui.OutcomeBar
import com.diaoyuanyun.therapist.ui.PageHost
import com.diaoyuanyun.therapist.ui.UiKit
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 端 B · 页面：服务与方案（域 D 之 D1 / D3 / D4 / D5-a / D5-b / D6）
 * ============================================================================
 *
 * 🛑 D3 代录：界面**不提供 source 选择器**（这是刻意的，不是漏做）
 * ---------------------------------------------------------------------------
 * 契约 `DailyReportRequest.source` 逐字：「代录须标 **代核**」。
 * `FulfillmentApi.submitDailyReportAsStaff` 已把 `source` 写死为 `SOURCE_PROXY`。
 * 本页**刻意不提供"以客户身份代填"这条路径** —— 提供一个 source 下拉就等于
 * 允许把代录伪装成客户自填，而那会让下游把一条门店行为记成客户行为。
 * 契约的枚举里有两个值，但端 B 只可能产生其中一个。
 *
 * 🛑 D1 核销的字段名来自【控制器真实形状】（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * 真相源是 `FulfillmentController.VisitRequest`：
 *   serving_store_id / plan_id / plan_version / part_method / duration_min /
 *   pre_feedback / post_feedback / abnormal_note
 * 自创字段名不会报错（服务端只会看到一个陌生键）⇒ 表现为"核销成功但字段没记上"。
 *
 * 🛑 D5-a / D6 的字段名同样来自控制器真实形状
 * ---------------------------------------------------------------------------
 * `PlanController.PlanRequest`：customer_id / treatment_json / lifestyle_json / intent_params
 * `PlanController.DispatchRequest`：plan_id / plan_version / store_id / device_id /
 *   param_snapshot / result / failed_reason / event
 * ⚠️ 契约对这两个端点**没有声明 requestBody**（与 D5-c 同款）⇒ 控制器即真相源。
 *
 * 🛑 D6 的前置条件写进界面：需方案已审核
 * ---------------------------------------------------------------------------
 * 契约 summary 逐字：「需方案已审核」。故本页把 D5-c（审核，仅经络师）的入口
 * 指向「专属动作」页，并在 D6 卡片上**明写这条前置**，而不是让一线提交后
 * 收到一个 5001 再去猜。
 */
class ServicePage(host: PageHost) : BasePage(host) {

    private var outcome: Outcome? = null

    // D1
    private var visitCustomerId = host.shell.sharedCustomerId
    private var servingStoreId = ""
    private var visitPlanId = ""
    private var planVersion = ""
    private var partMethod = ""
    private var durationMin = ""
    private var preFeedback = ""
    private var postFeedback = ""
    private var abnormalNote = ""

    // D3 / D4
    private var reportCustomerId = host.shell.sharedCustomerId
    private var reportDate = ""
    private var answersJson = "{}"
    private var reports: List<DailyReportData>? = null

    // D5-a / D5-b
    private var planCustomerId = host.shell.sharedCustomerId
    private var treatmentJson = "{}"
    private var lifestyleJson = "{}"
    private var intentParams = ""
    private var lookupPlanId = ""
    private var planDetail: JsonObject? = null

    // D6
    private var dispatchPlanId = ""
    private var dispatchPlanVersion = ""
    private var dispatchStoreId = ""
    private var dispatchDeviceId = ""
    private var paramSnapshot = ""
    private var dispatchResult = ""
    private var failedReason = ""
    private var dispatchEvent = ""

    override fun render(): View {
        val root = UiKit.page(ctx, "服务与方案", roleLabel)
        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        root.addView(slot)
        bar.render(outcome)

        root.addView(d1Card(bar))
        root.addView(d3Card(bar))
        root.addView(d4Card(bar))
        root.addView(d5aCard(bar))
        root.addView(d5bCard(bar))
        root.addView(d6Card(bar))
        return root
    }

    // -------------------------------------------------------------------------
    // D1
    // -------------------------------------------------------------------------

    private fun d1Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "D1 服务核销",
            hint = "契约未声明 requestBody ⇒ 字段名逐字对齐后端控制器 VisitRequest。" +
                "核销是服务的账本锚点。",
        )
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx, initial = visitCustomerId) { visitCustomerId = it }))
        card.addView(UiKit.field(ctx, "服务门店（serving_store_id）", UiKit.input(ctx) { servingStoreId = it }))
        card.addView(UiKit.field(ctx, "关联方案 ID（plan_id，可选）", UiKit.input(ctx) { visitPlanId = it }))
        card.addView(UiKit.field(ctx, "方案版本（plan_version，可选）", UiKit.input(ctx, numeric = true) { planVersion = it }))
        card.addView(UiKit.field(ctx, "部位 / 手法（part_method，可选）", UiKit.input(ctx) { partMethod = it }))
        card.addView(UiKit.field(ctx, "时长（duration_min，分钟，可选）", UiKit.input(ctx, numeric = true) { durationMin = it }))
        card.addView(UiKit.field(ctx, "服务前反馈（pre_feedback，可选）", UiKit.textarea(ctx) { preFeedback = it }))
        card.addView(UiKit.field(ctx, "服务后反馈（post_feedback，可选）", UiKit.textarea(ctx) { postFeedback = it }))
        card.addView(
            UiKit.field(
                ctx,
                "异常备注（abnormal_note，可选 —— 有异常必须写在这里，**不要写进反馈正文**）",
                UiKit.textarea(ctx) { abnormalNote = it },
            ),
        )
        card.addView(
            actionButton(
                label = "服务核销",
                bar = bar,
                enabled = { visitCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>()
                if (servingStoreId.isNotBlank()) body["serving_store_id"] = servingStoreId.trim()
                if (visitPlanId.isNotBlank()) body["plan_id"] = visitPlanId.trim()
                planVersion.trim().toIntOrNull()?.let { body["plan_version"] = it }
                if (partMethod.isNotBlank()) body["part_method"] = partMethod.trim()
                durationMin.trim().toIntOrNull()?.let { body["duration_min"] = it }
                if (preFeedback.isNotBlank()) body["pre_feedback"] = preFeedback.trim()
                if (postFeedback.isNotBlank()) body["post_feedback"] = postFeedback.trim()
                if (abnormalNote.isNotBlank()) body["abnormal_note"] = abnormalNote.trim()
                Graph.fulfillmentApi.createVisit(role, visitCustomerId.trim(), body)
            },
        )
        card.addView(
            UiKit.label(
                ctx,
                "服务记录（D2）在「客户详情」页读取 —— 两者同域，但分开是因为 D2 是" +
                    "**客户维度的全局账本**，而 D1 是本次核销单。",
            ),
        )
        return card
    }

    // -------------------------------------------------------------------------
    // D3 / D4
    // -------------------------------------------------------------------------

    private fun d3Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "D3 代录每日填报",
            hint = "source 由本层写死为「${FulfillmentApi.SOURCE_PROXY}」—— 界面刻意不提供" +
                " source 选择器，端 B 不存在「以客户身份代填」这条路径。",
        )
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx, initial = reportCustomerId) { reportCustomerId = it }))
        card.addView(UiKit.field(ctx, "填报日期（date）", UiKit.input(ctx, hint = "YYYY-MM-DD") { reportDate = it }))
        card.addView(UiKit.field(ctx, "作答内容（answers_json，JSON 对象）", UiKit.textarea(ctx, initial = answersJson) { answersJson = it }))
        card.addView(
            actionButton(
                label = "代录每日填报",
                bar = bar,
                enabled = { reportCustomerId.isNotBlank() && reportDate.isNotBlank() },
                disabledHint = { "请先填写客户 ID 与填报日期。" },
            ) {
                val answers = parseJsonObject(answersJson, "answers_json")
                @Suppress("UNCHECKED_CAST")
                val map = Gson().fromJson(answers, Map::class.java) as Map<String, Any?>
                // 🛑 source 由域层写死为「代核」，本页**不传**、也不提供选择器。
                Graph.fulfillmentApi.submitDailyReportAsStaff(
                    role, reportCustomerId.trim(), reportDate.trim(), map,
                )
            },
        )
        return card
    }

    private fun d4Card(bar: OutcomeBar): View {
        val card = UiKit.card(ctx, title = "D4 填报记录", hint = "按客户查每日填报历史。")
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx, initial = reportCustomerId) { reportCustomerId = it }))
        card.addView(
            actionButton(
                label = "查询填报记录",
                bar = bar,
                busyLabel = "查询中…",
                enabled = { reportCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                Graph.fulfillmentApi.listDailyReports(role, reportCustomerId.trim()).also { reports = it }
            },
        )
        when (val list = reports) {
            null -> card.addView(UiKit.empty(ctx, "尚未查询。"))
            else -> if (list.isEmpty()) {
                card.addView(UiKit.empty(ctx, "暂无填报记录。"))
            } else {
                for ((i, r) in list.withIndex()) {
                    card.addView(
                        UiKit.kvView(
                            ctx,
                            r.date ?: "第 ${i + 1} 条",
                            UiKit.row(ctx).apply {
                                r.source?.let { addView(UiKit.badge(ctx, it, Tone.NEUTRAL)); addView(UiKit.hgap(ctx)) }
                                addView(UiKit.text(ctx, r.weeklyCount?.let { "本周 $it 次" } ?: "", Type.MD, Palette.text))
                                r.suggestion?.let {
                                    addView(UiKit.hgap(ctx))
                                    addView(UiKit.text(ctx, "建议：$it", Type.MD, Palette.text))
                                }
                            },
                        ),
                    )
                }
            }
        }
        return card
    }

    // -------------------------------------------------------------------------
    // D5-a / D5-b
    // -------------------------------------------------------------------------

    private fun d5aCard(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "D5-a 方案出具",
            hint = "契约未声明 requestBody ⇒ 字段名对齐控制器 PlanRequest" +
                "（treatment_json / lifestyle_json / intent_params）。出具后需经络师审核（D5-c）。",
        )
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx, initial = planCustomerId) { planCustomerId = it }))
        card.addView(UiKit.field(ctx, "调理方案（treatment_json，JSON）", UiKit.textarea(ctx, initial = treatmentJson) { treatmentJson = it }))
        card.addView(UiKit.field(ctx, "起居建议（lifestyle_json，JSON）", UiKit.textarea(ctx, initial = lifestyleJson) { lifestyleJson = it }))
        card.addView(UiKit.field(ctx, "意图参数（intent_params，可选）", UiKit.input(ctx) { intentParams = it }))
        card.addView(
            actionButton(
                label = "方案出具",
                bar = bar,
                enabled = { planCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                // 两个 JSON 字段在控制器侧是对象（非字符串）—— 先解析再传，
                // 传字符串会让服务端收到一个 JSON 字符串而不是对象（静默降级）。
                val body = linkedMapOf<String, Any?>()
                body["customer_id"] = planCustomerId.trim()
                body["treatment_json"] = jsonValueOf(treatmentJson, "treatment_json")
                body["lifestyle_json"] = jsonValueOf(lifestyleJson, "lifestyle_json")
                if (intentParams.isNotBlank()) body["intent_params"] = intentParams.trim()
                Graph.fulfillmentApi.createPlan(role, body)
            },
        )
        card.addView(
            UiKit.label(
                ctx,
                "🛑 出具后不能直接下发设备 —— 契约 D6 逐字「需方案已审核」，审核动作" +
                    "（D5-c）**仅经络师**可用，入口在「专属动作」页。",
            ),
        )
        return card
    }

    private fun d5bCard(bar: OutcomeBar): View {
        val card = UiKit.card(ctx, title = "D5-b 方案查阅", hint = "按方案 ID 读取方案内容与状态。")
        card.addView(UiKit.field(ctx, "方案 ID（plan_id）", UiKit.input(ctx) { lookupPlanId = it }))
        card.addView(
            actionButton(
                label = "方案查阅",
                bar = bar,
                busyLabel = "读取中…",
                enabled = { lookupPlanId.isNotBlank() },
                disabledHint = { "请先填写方案 ID。" },
            ) {
                Graph.fulfillmentApi.getPlan(role, lookupPlanId.trim()).also { planDetail = it }
            },
        )
        val d = planDetail
        if (d == null) {
            card.addView(UiKit.empty(ctx, "尚未读取。"))
        } else if (d.entrySet().isEmpty()) {
            card.addView(UiKit.empty(ctx, "服务端未返回字段。"))
        } else {
            for ((k, v) in d.entrySet()) {
                card.addView(UiKit.kv(ctx, k, if (v.isJsonObject || v.isJsonArray) v.toString() else v.asString))
            }
        }
        return card
    }

    // -------------------------------------------------------------------------
    // D6
    // -------------------------------------------------------------------------

    private fun d6Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "D6 设备参数下发",
            hint = "前置：方案已审核（D5-c，仅经络师）。契约未声明 requestBody ⇒ " +
                "字段名对齐控制器 DispatchRequest。",
        )
        card.addView(UiKit.field(ctx, "方案 ID（plan_id）", UiKit.input(ctx) { dispatchPlanId = it }))
        card.addView(UiKit.field(ctx, "方案版本（plan_version）", UiKit.input(ctx, numeric = true) { dispatchPlanVersion = it }))
        card.addView(UiKit.field(ctx, "门店 ID（store_id）", UiKit.input(ctx) { dispatchStoreId = it }))
        card.addView(UiKit.field(ctx, "设备 ID（device_id）", UiKit.input(ctx) { dispatchDeviceId = it }))
        card.addView(UiKit.field(ctx, "参数快照（param_snapshot，可选）", UiKit.textarea(ctx) { paramSnapshot = it }))
        card.addView(UiKit.field(ctx, "下发结果（result，可选）", UiKit.input(ctx) { dispatchResult = it }))
        card.addView(UiKit.field(ctx, "失败原因（failed_reason，失败时填）", UiKit.input(ctx) { failedReason = it }))
        card.addView(UiKit.field(ctx, "事件（event，可选）", UiKit.input(ctx) { dispatchEvent = it }))
        card.addView(
            actionButton(
                label = "设备下发",
                bar = bar,
                enabled = { dispatchPlanId.isNotBlank() },
                disabledHint = { "请先填写方案 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>()
                body["plan_id"] = dispatchPlanId.trim()
                dispatchPlanVersion.trim().toIntOrNull()?.let { body["plan_version"] = it }
                if (dispatchStoreId.isNotBlank()) body["store_id"] = dispatchStoreId.trim()
                if (dispatchDeviceId.isNotBlank()) body["device_id"] = dispatchDeviceId.trim()
                if (paramSnapshot.isNotBlank()) body["param_snapshot"] = paramSnapshot.trim()
                if (dispatchResult.isNotBlank()) body["result"] = dispatchResult.trim()
                if (failedReason.isNotBlank()) body["failed_reason"] = failedReason.trim()
                if (dispatchEvent.isNotBlank()) body["event"] = dispatchEvent.trim()
                Graph.fulfillmentApi.createDeviceDispatch(role, body)
            },
        )
        return card
    }

    // -------------------------------------------------------------------------

    private fun parseJsonObject(raw: String, field: String): JsonObject {
        val trimmed = raw.trim().ifBlank { "{}" }
        val el = runCatching { com.google.gson.JsonParser.parseString(trimmed) }.getOrNull()
        if (el == null || !el.isJsonObject) {
            throw IllegalArgumentException("JSON 解析失败：请检查 $field 的写法（应为一个 JSON 对象）。")
        }
        return el.asJsonObject
    }

    /** 解析成任意 JsonElement（用于既可能是对象、也可能被写成标量的字段）。 */
    private fun jsonValueOf(raw: String, field: String): Any {
        val trimmed = raw.trim().ifBlank { "{}" }
        val el = runCatching { com.google.gson.JsonParser.parseString(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("JSON 解析失败：请检查 $field 的写法。")
        @Suppress("UNCHECKED_CAST")
        return Gson().fromJson(el, Map::class.java) as Map<String, Any?>
    }
}
