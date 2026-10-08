package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.auth.Access
import com.diaoyuanyun.therapist.auth.SoleGrant
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.VerdictApi
import com.diaoyuanyun.therapist.domain.VerdictData
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
import kotlinx.coroutines.launch

/**
 * 端 B · 页面：专属动作（**本端最窄的一档**）
 * ============================================================================
 *
 * 本页是 X-3「角色级装载」在界面层**最尖锐的验收面**：
 * 它只渲染**恰好只授予单一角色**的端点（本端目前 = 7 个，全部仅经络师）。
 *
 * 🛑 为什么单开一页，而不是并进工作台的"我可用能力"清单
 * ---------------------------------------------------------------------------
 * 工作台那页列的是"我这个角色能用什么"（22 vs 29 两个数量级混在一起）。
 * 但真正会出事的是**最窄的那一档**：它是"漏了一个未授权入口"的最可能落点
 * —— 调理师视图一旦渲染了这 7 个里的任何一个，点下去必然 403，
 * 而报出来的现象与"权限没配好"完全一样，排查方向会整个跑偏。
 * 故本页把它单拎出来：**调理师登录时这一页必须一个入口都不渲染**，
 * 而这个事实**肉眼可见**（不是靠读代码判断）。
 *
 * 🛑 本页的入口清单**不是手写的**，也不是"写死的 7 项"
 * ---------------------------------------------------------------------------
 * 由 `Access.solelyGrantedEndpoints()` 从生成物的 `grantedRoles` **现算**。
 * 若本页自己写 `['D5-c','F1',…]`，就造出了第二份清单：契约一改就漂移，
 * 而且**漂移不会让任何门禁变红**（本仓第 46 条同型）。
 *
 * 🛑 本页对【不归我】的项不隐藏，而是显式标注
 * ---------------------------------------------------------------------------
 * 调理师视图下，这 7 项会**以"仅经络师"的标记出现但不给按钮**。
 * 理由与工作台一致：一线最困惑的是"为什么别人能做我不能"，
 * 把边界写出来并写明**该找谁**，是唯一能终止这类往返的做法。
 * （注意：这是**呈现**选择，不是准入判定 —— 判定只在 `Access`。）
 */
class MeridianActionsPage(host: PageHost) : BasePage(host) {

    private var outcome: Outcome? = null

    // D5-c
    private var planId = ""
    private var reviewResult = ""
    private var reviewReason = ""
    private var reviewSecond = true

    // F1
    private var cycleId = ""
    private var verdictCustomerId = ""
    private var verdictSeq = "1"
    private var baseTotal = ""
    private var currentTotal = ""
    private var sameItemGroup = true
    private var rangeMatches = true
    private var sameMeasurer = true
    /** 空串 = **未定**（合法语义 = 不传该字段）；**绝不预选「无」**。 */
    private var riskFlag = ""
    /** 空串 = 缺失 ⇒ 挂起（契约逐字）。 */
    private var coreImproved = ""
    private var verdictJson = """{"confidence":{},"module_scores":{},"adherence":{"dimensions":{}}}"""
    private var verdict: VerdictData? = null

    // F2
    private var listVerdictCustomerId = ""
    private var verdicts: List<VerdictData>? = null

    // G1
    private var refundCustomerId = ""
    private var refundEntry = ENTRY_A
    private var refundRoute = ROUTE_FULFILLMENT
    private var refundReason = ""
    private var refundRequestedAt = ""
    private var refundStatement = ""

    // G2
    private var refundId = ""
    private var refundDetail: JsonObject? = null

    // G3
    private var retentionAttempts = "1"
    private var retentionResult = ""

    // G5
    private var receiptTemplateId = ""
    private var receiptQuota = ""
    private var receiptPushOk = true
    private var receiptFailReason = ""

    override fun render(): View {
        val role0 = role
        val sole = Access.solelyGrantedEndpoints()
        val mine = sole.filter { Access.canCall(role0, it.endpoint.id) }
        val notMine = sole.filterNot { Access.canCall(role0, it.endpoint.id) }

        val root = UiKit.page(ctx, "专属动作", roleLabel)

        // 调理师视图：本页**不给任何操作按钮**（结构性地不给，不靠"藏起来"）。
        if (mine.isEmpty()) {
            val card = UiKit.card(
                ctx,
                title = "本角色在本端没有专属动作",
                hint = "这不是报错，而是契约事实：本端目前没有任何「仅调理师」端点。",
            )
            card.addView(
                UiKit.label(
                    ctx,
                    "本端「只授予单一角色」的端点共 ${sole.size} 项，$roleLabel 可用的为 0 项。" +
                        "下表只作**边界说明**，不提供操作入口 —— 需要执行请找对应角色，" +
                        "不要借用账号（借用账号会让审计留痕记到错误的人身上）。",
                ),
            )
            root.addView(card)
            root.addView(notMineCard(notMine, sole.size))
            return root
        }

        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        root.addView(slot)
        bar.render(outcome)

        root.addView(mineCard(mine.map { "${it.endpoint.row} ${it.endpoint.id}" }, notMine.size))

        if (Access.canCall(role0, "reviewPlan")) root.addView(d5cCard(bar))
        if (Access.canCall(role0, "createVerdict")) root.addView(f1Card(bar))
        if (Access.canCall(role0, "listVerdicts")) root.addView(f2Card(bar))
        if (Access.canCall(role0, "createRefund")) root.addView(g1Card(bar))
        if (Access.canCall(role0, "getRefund")) root.addView(g2Card(bar))
        if (Access.canCall(role0, "createRetention")) root.addView(g3Card(bar))
        if (Access.canCall(role0, "createRefundReceipt")) root.addView(g5Card(bar))

        root.addView(
            UiKit.label(
                ctx,
                "本页仅渲染当前角色被授予的入口；未授予的项只作边界说明。",
            ),
        )
        return root
    }

    // -------------------------------------------------------------------------

    private fun mineCard(items: List<String>, notMineCount: Int): View {
        val card = UiKit.card(
            ctx,
            title = "${roleLabel}专属动作（${items.size} 项）",
            hint = "清单由生成物的 grantedRoles 现算（只保留「恰好只授予一个角色」的端点），" +
                "本页不含手写端点清单。",
        )
        card.addView(
            UiKit.row(ctx).apply {
                for (s in items) {
                    addView(UiKit.badge(ctx, s, Tone.NEUTRAL))
                    addView(UiKit.hgap(ctx, com.diaoyuanyun.therapist.ui.theme.Space.XS))
                }
            },
        )
        if (notMineCount > 0) {
            card.addView(
                UiKit.label(ctx, "另有 $notMineCount 项为其他角色的专属动作（本页不渲染其入口）。"),
            )
        }
        return card
    }

    private fun notMineCard(notMine: List<SoleGrant>, totalSole: Int): View {
        val card = UiKit.card(
            ctx,
            title = "存在、但不归 $roleLabel（${notMine.size} 项 / 共 $totalSole 项专属动作）",
        )
        for (s in notMine) {
            card.addView(
                UiKit.kv(
                    ctx,
                    "${s.endpoint.row} ${s.endpoint.id}",
                    "${s.endpoint.method.name} ${s.endpoint.path}" +
                        "（仅 ${Access.roleDisplay(s.role)}）",
                ),
            )
        }
        return card
    }

    // -------------------------------------------------------------------------
    // D5-c
    // -------------------------------------------------------------------------

    private fun d5cCard(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "D5-c 方案审核",
            hint = "退回必填 reason。请求体字段逐字对齐后端控制器" +
                "（contract 未声明 requestBody）：result / reason / second_confirm。",
        )
        card.addView(UiKit.field(ctx, "方案 ID（{id}，来自 D5-a 出具方案）", UiKit.input(ctx, hint = "plan uuid") { planId = it }))
        card.addView(UiKit.field(ctx, "审核结论（result）", UiKit.input(ctx, hint = "例如 通过 / 退回") { reviewResult = it }))
        card.addView(UiKit.field(ctx, "退回原因（reason，退回时必填）", UiKit.input(ctx) { reviewReason = it }))
        card.addView(UiKit.field(ctx, "二次确认（second_confirm）", UiKit.checkbox(ctx, "已二次确认", reviewSecond) { reviewSecond = it }))
        card.addView(
            actionButton(
                label = "方案审核",
                bar = bar,
                enabled = { planId.isNotBlank() },
                disabledHint = { "请先填写方案 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "result" to reviewResult.trim().ifBlank { "通过" },
                    "second_confirm" to reviewSecond,
                )
                if (reviewReason.isNotBlank()) body["reason"] = reviewReason.trim()
                Graph.fulfillmentApi.reviewPlan(role, planId.trim(), body)
            },
        )
        return card
    }

    // -------------------------------------------------------------------------
    // F1
    // -------------------------------------------------------------------------

    private fun f1Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "F1 判定结论落库",
            hint = "同源断言三项任一项缺省 = 不可比 ⇒ 挂起；risk_flag 缺省 ⇒ 未定" +
                "（不得默认「无」）—— 本页不代填。",
        )
        card.addView(UiKit.field(ctx, "周期评估 ID（{id}，来自 C4）", UiKit.input(ctx, hint = "cycle assessment uuid") { cycleId = it }))
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx) { verdictCustomerId = it }))
        card.addView(UiKit.field(ctx, "序号（sequence_no，每 7 次触发）", UiKit.input(ctx, initial = verdictSeq, numeric = true) { verdictSeq = it }))
        card.addView(UiKit.field(ctx, "基线总分（base_total，缺失 ⇒ 挂起）", UiKit.input(ctx, numeric = true) { baseTotal = it }))
        card.addView(UiKit.field(ctx, "复评总分（current_total，缺失 ⇒ 挂起）", UiKit.input(ctx, numeric = true) { currentTotal = it }))
        card.addView(
            UiKit.field(
                ctx,
                "同源断言三项（任一项缺省 = 不成立 = 不可比）",
                UiKit.column(ctx).apply {
                    addView(UiKit.checkbox(ctx, "同题组（same_item_group）", sameItemGroup) { sameItemGroup = it })
                    addView(UiKit.checkbox(ctx, "量程相符（range_matches）", rangeMatches) { rangeMatches = it })
                    addView(UiKit.checkbox(ctx, "同一测量人（same_measurer）", sameMeasurer) { sameMeasurer = it })
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "风险标记（risk_flag）—— 🛑 首项就是「未定」，**不代填「无」**",
                UiKit.spinner(
                    ctx,
                    // 🛑 第一项是"未定"（= 不传该字段），**不是**「无」。
                    //    把「无」放第一项会让 spinner 默认选中它 ⇒ 正是契约禁的代填。
                    listOf(RISK_UNDECIDED_LABEL) + VerdictApi.RISK_FLAG_VALUES,
                    RISK_UNDECIDED_LABEL,
                ) { picked ->
                    riskFlag = if (picked == RISK_UNDECIDED_LABEL) "" else picked
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "核心指标是否改善（core_metric_improved）—— 留空表示缺失 ⇒ 挂起",
                UiKit.spinner(ctx, listOf(CORE_MISSING_LABEL, "是", "否"), CORE_MISSING_LABEL) { picked ->
                    coreImproved = when (picked) {
                        "是" -> "true"
                        "否" -> "false"
                        else -> ""
                    }
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "其余结构化部分（confidence / module_scores / adherence，JSON）",
                UiKit.textarea(ctx, initial = verdictJson) { verdictJson = it },
            ),
        )
        card.addView(
            actionButton(
                label = "判定结论落库",
                bar = bar,
                enabled = { cycleId.isNotBlank() && verdictCustomerId.isNotBlank() },
                disabledHint = { "请先填写周期评估 ID 与客户 ID。" },
            ) {
                val rest = parseJsonObject(verdictJson, "confidence / module_scores / adherence")
                val body = linkedMapOf<String, Any?>()
                body["customer_id"] = verdictCustomerId.trim()
                body["sequence_no"] = verdictSeq.trim().toIntOrNull() ?: 0
                baseTotal.trim().toIntOrNull()?.let { body["base_total"] = it }
                currentTotal.trim().toIntOrNull()?.let { body["current_total"] = it }
                body["same_origin"] = linkedMapOf<String, Any?>(
                    "same_item_group" to sameItemGroup,
                    "range_matches" to rangeMatches,
                    "same_measurer" to sameMeasurer,
                )
                body["adherence"] = jsonOrEmptyObject(rest.get("adherence"))
                // 🛑 只有操作者**明确选了**枚举值时才带上；"未定"= 不带该字段。
                if (riskFlag.isNotBlank()) body["risk_flag"] = riskFlag
                if (coreImproved.isNotBlank()) body["core_metric_improved"] = coreImproved == "true"
                body["confidence"] = jsonOrEmptyObject(rest.get("confidence"))
                body["module_scores"] = jsonOrEmptyObject(rest.get("module_scores"))
                Graph.verdictApi.createVerdict(role, cycleId.trim(), body).also { verdict = it }
            },
        )

        verdict?.let { v ->
            card.addView(UiKit.kv(ctx, "判定 ID", v.verdictId))
            card.addView(UiKit.kv(ctx, "分支（服务端算）", v.branch ?: "—"))
            card.addView(UiKit.kv(ctx, "置信度", v.confidence?.toString() ?: "—"))
            card.addView(UiKit.kv(ctx, "口径版本", v.thresholdVersion ?: "—"))
            card.addView(UiKit.kv(ctx, "判定时间", v.decidedAt ?: "—"))
        }
        return card
    }

    // -------------------------------------------------------------------------
    // F2
    // -------------------------------------------------------------------------

    private fun f2Card(bar: OutcomeBar): View {
        val card = UiKit.card(ctx, title = "F2 判定历史", hint = "按客户查历史判定结论。")
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx) { listVerdictCustomerId = it }))
        card.addView(
            actionButton(
                label = "查询历史",
                bar = bar,
                busyLabel = "查询中…",
                enabled = { listVerdictCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                Graph.verdictApi.listVerdicts(role, listVerdictCustomerId.trim()).also { verdicts = it }
            },
        )
        when (val list = verdicts) {
            null -> card.addView(UiKit.empty(ctx, "尚未查询。"))
            else -> if (list.isEmpty()) {
                card.addView(UiKit.empty(ctx, "暂无判定记录。"))
            } else {
                for (v in list) {
                    card.addView(
                        UiKit.kv(
                            ctx,
                            v.decidedAt ?: v.verdictId,
                            "分支 ${v.branch ?: "—"} · 置信度 ${v.confidence?.toString() ?: "—"}",
                        ),
                    )
                }
            }
        }
        return card
    }

    // -------------------------------------------------------------------------
    // G1
    // -------------------------------------------------------------------------

    private fun g1Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "G1 代录客户诉求",
            hint = "reason_code 必填（未记录原因不可结案）；requested_at 是「最早且可核实」" +
                "的时间，即计时基准 —— 本页不代填。",
        )
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx) { refundCustomerId = it }))
        card.addView(
            UiKit.field(
                ctx,
                "入口（entry，逐字枚举）",
                UiKit.spinner(ctx, listOf(ENTRY_A, ENTRY_B), refundEntry) { refundEntry = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "路由（refund_route，逐字枚举）",
                UiKit.spinner(ctx, listOf(ROUTE_FULFILLMENT, ROUTE_EFFECT), refundRoute) { refundRoute = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "原因（reason_code）—— 必填，**无默认值、不代填**",
                UiKit.spinner(ctx, listOf(REASON_UNSET) + REASON_CODES, REASON_UNSET) { picked ->
                    refundReason = if (picked == REASON_UNSET) "" else picked
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "诉求时间（requested_at，「最早且可核实」，**计时基准**）",
                UiKit.input(ctx, hint = "YYYY-MM-DD") { refundRequestedAt = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户原话（customer_statement，append-only，可空）",
                UiKit.textarea(ctx) { refundStatement = it },
            ),
        )
        card.addView(
            actionButton(
                label = "代录诉求",
                bar = bar,
                enabled = { refundCustomerId.isNotBlank() && refundReason.isNotBlank() && refundRequestedAt.isNotBlank() },
                disabledHint = { "客户 ID / 原因 / 诉求时间三项必填 —— 未记录原因不可结案。" },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "customer_id" to refundCustomerId.trim(),
                    "entry" to refundEntry,
                    "refund_route" to refundRoute,
                    "reason_code" to refundReason,
                    "requested_at" to refundRequestedAt.trim(),
                )
                if (refundStatement.isNotBlank()) body["customer_statement"] = refundStatement.trim()
                Graph.refundApi.createRefund(role, body)
            },
        )
        return card
    }

    // -------------------------------------------------------------------------
    // G2
    // -------------------------------------------------------------------------

    private fun g2Card(bar: OutcomeBar): View {
        val card = UiKit.card(ctx, title = "G2 工单详情", hint = "读取工单（含 SLA 到期、录入延迟）。")
        card.addView(UiKit.field(ctx, "工单 ID（refund_id）", UiKit.input(ctx) { refundId = it }))
        card.addView(
            actionButton(
                label = "读取工单",
                bar = bar,
                busyLabel = "读取中…",
                enabled = { refundId.isNotBlank() },
                disabledHint = { "请先填写工单 ID。" },
            ) {
                Graph.refundApi.getRefund(role, refundId.trim()).also { refundDetail = it }
            },
        )
        val d = refundDetail
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
    // G3
    // -------------------------------------------------------------------------

    private fun g3Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "G3 挽留记录",
            hint = "挽留记录必填（入口 B 不经挽留）；analysis / communication 在库层是 JSONB，传对象即可。",
        )
        card.addView(UiKit.field(ctx, "工单 ID（{id}）", UiKit.input(ctx, initial = refundId) { refundId = it }))
        card.addView(UiKit.field(ctx, "尝试次数（attempts）", UiKit.input(ctx, initial = retentionAttempts, numeric = true) { retentionAttempts = it }))
        card.addView(UiKit.field(ctx, "挽留结论（result）", UiKit.input(ctx) { retentionResult = it }))
        card.addView(
            actionButton(
                label = "挽留记录",
                bar = bar,
                enabled = { refundId.isNotBlank() && retentionResult.isNotBlank() },
                disabledHint = { "工单 ID 与挽留结论均为必填。" },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "attempts" to (retentionAttempts.trim().toIntOrNull() ?: 0),
                    "result" to retentionResult.trim(),
                )
                Graph.refundApi.createRetention(role, refundId.trim(), body)
            },
        )
        return card
    }

    // -------------------------------------------------------------------------
    // G5
    // -------------------------------------------------------------------------

    private fun g5Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "G5 回执（三态留痕）",
            hint = "subscription_quota 是「推送之前」判定的额度（≤0 ⇒ 未授权（转线下））" +
                "—— 顺序由服务端保证，本页如实传值。",
        )
        card.addView(UiKit.field(ctx, "工单 ID（{id}）", UiKit.input(ctx, initial = refundId) { refundId = it }))
        card.addView(UiKit.field(ctx, "订阅消息模板 ID（template_id）", UiKit.input(ctx) { receiptTemplateId = it }))
        card.addView(
            UiKit.field(
                ctx,
                "剩余额度（subscription_quota，推送前判定）",
                UiKit.input(ctx, numeric = true) { receiptQuota = it },
            ),
        )
        card.addView(UiKit.field(ctx, "推送是否成功（push_succeeded）", UiKit.checkbox(ctx, "推送成功", receiptPushOk) { receiptPushOk = it }))
        if (!receiptPushOk) {
            card.addView(
                UiKit.field(
                    ctx,
                    "失败原因（failure_reason，push_succeeded=false 时必填）",
                    UiKit.input(ctx) { receiptFailReason = it },
                ),
            )
        }
        card.addView(
            actionButton(
                label = "回执",
                bar = bar,
                enabled = { refundId.isNotBlank() },
                disabledHint = { "请先填写工单 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "push_succeeded" to receiptPushOk,
                )
                receiptQuota.trim().toIntOrNull()?.let { body["subscription_quota"] = it }
                if (receiptTemplateId.isNotBlank()) body["template_id"] = receiptTemplateId.trim()
                if (!receiptPushOk && receiptFailReason.isNotBlank()) {
                    body["failure_reason"] = receiptFailReason.trim()
                }
                Graph.refundApi.createRefundReceipt(role, refundId.trim(), body)
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

    /** 取子对象；缺失或不是对象时给**空对象**（契约对应字段是 required 对象）。 */
    @Suppress("UNCHECKED_CAST")
    private fun jsonOrEmptyObject(el: com.google.gson.JsonElement?): Map<String, Any?> {
        if (el == null || !el.isJsonObject) return emptyMap()
        return Gson().fromJson(el, Map::class.java) as Map<String, Any?>
    }

    private companion object {
        const val ENTRY_A = "A门店代录"
        const val ENTRY_B = "B首周期"
        const val ROUTE_FULFILLMENT = "履约类"
        const val ROUTE_EFFECT = "效果类"

        /** 未选择占位（**不能**用某个真实原因码充当"未选"）。 */
        const val REASON_UNSET = "（请选择 —— 不代填）"

        /** 契约 `reason_code` 六值枚举（逐字）。 */
        val REASON_CODES: List<String> = listOf(
            "效果未达预期",
            "症状加重或出现新不适",
            "服务体验或沟通问题",
            "时间·经济·家庭原因",
            "配合度不足导致无明显变化",
            "信任或价格异议",
        )

        const val RISK_UNDECIDED_LABEL = "（未定 —— 不代填）"
        const val CORE_MISSING_LABEL = "（缺失 ⇒ 挂起）"
    }
}
