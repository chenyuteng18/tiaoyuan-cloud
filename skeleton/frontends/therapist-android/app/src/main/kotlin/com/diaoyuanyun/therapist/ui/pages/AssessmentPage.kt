package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.api.ApiErrors
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.BaselineAssessmentData
import com.diaoyuanyun.therapist.domain.ContractEnums
import com.diaoyuanyun.therapist.domain.ScaleItemBank
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
 * 端 B · 页面：量表与评估（域 C 之 C1~C4）
 * ============================================================================
 *
 * 🛑 C2 的两个契约硬约束在界面层**前置挡住**
 * ---------------------------------------------------------------------------
 * `BaselineAssessmentRequest` 逐字：`dimension_scores` **minItems 7 / maxItems 7**、
 * `total_score` 0~112。`AssessmentApi.submitBaselineAssessment` 已做本地校验
 * （服务端仍会校验并回 5001）。本页把它**做成 7 个独立输入框**而不是一个
 * JSON 文本域 —— 后者会让一线不知道"到底要填几项"，从而把一次可避免的失败
 * （少一项）记成系统故障。
 *
 * 🛑 C2 的 total_score **不由前端求和**
 * ---------------------------------------------------------------------------
 * 本页只显示"当前各项之和"作为**核对提示**，提交值以你填入的 `total_score` 为准；
 * 且**不自动覆盖**你填的值。理由：派生态由服务端算（X-1），而更直接的理由是
 * ——契约要求 `total_score` 由测量人员给出，自动覆盖会把一次抄写错误变成
 * 一次**无法察觉的篡改**。
 * 同理 `age_group_locked` / `item_group_id` / `scale_id` 三者**必须来自 C1 返回**，
 * 本页把它们做成只读带入，不让手抄（手抄 = 造第二份权威）。
 *
 * 🛑 C4 的 adherence 形状与 F1 **必须一致**（契约明文）
 * ---------------------------------------------------------------------------
 * 契约逐字：「与 F1 **同一形状** —— 两侧对同一事实不得有两套字段名」。
 * 且逐字强调「🛑 **A2 永不参与 AS**」。故本页只提供 A1 / A3 / A4 三个维度，
 * **刻意不提供 A2 这一项** —— 提供它就是把契约红线写进界面。
 */
class AssessmentPage(host: PageHost) : BasePage(host) {

    private var outcome: Outcome? = null
    private var error: Pair<String, String>? = null

    // C1
    private var ageGroup = ""
    private var dimension = ""
    private var banks: List<ScaleItemBank>? = null
    private var picked: ScaleItemBank? = null

    // C2
    private var baselineCustomerId = ""
    private val scores = MutableList(ContractEnums.DIMENSION_COUNT) { "" }
    private var totalScore = ""
    private var measureOperator = ""
    private var baseline: BaselineAssessmentData? = null

    // C3
    private var detailCustomerId = ""
    private var assessmentId = ""
    private var detail: JsonObject? = null

    // C4
    private var cycleCustomerId = ""
    private var cycleId = ""
    private var sequenceNo = "1"
    private var expectedDays = ""
    private val adherence = ADHERENCE_KEYS.associateWith { AdhState() }
    private var moduleScoresJson = "{}"
    private var bandTrendNote = ""
    private var thresholdVersion = ""

    /** 依从维度状态（applicable / value / structural_missing）。 */
    private class AdhState(var applicable: Boolean = true, var value: String = "", var structuralMissing: Boolean = false)

    override fun render(): View {
        val root = UiKit.page(ctx, "量表与评估", roleLabel)
        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        root.addView(slot)
        bar.render(outcome)
        error?.let { (t, id) -> root.addView(UiKit.errorBar(ctx, t, id)) }

        root.addView(c1Card(bar))
        root.addView(c2Card(bar))
        root.addView(c3Card(bar))
        root.addView(c4Card(bar))
        return root
    }

    // -------------------------------------------------------------------------
    // C1
    // -------------------------------------------------------------------------

    private fun c1Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "C1 题库拉取",
            hint = "按分龄组 + 维度取题组。age_group 为契约必填项；" +
                "取回的题组是 C2 的「同源题组锁定」依据。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "分龄组（age_group，契约必填 —— 8 档枚举，取自契约具名 schema AgeGroup）",
                UiKit.spinner(ctx, listOf("") + ContractEnums.AGE_GROUP, ageGroup) { ageGroup = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "维度（dimension，可选 —— 7 维枚举，取自契约具名 schema Dimension）",
                UiKit.spinner(ctx, listOf("") + ContractEnums.DIMENSION, dimension) { dimension = it },
            ),
        )
        card.addView(
            actionButton(
                label = "拉取题库",
                bar = bar,
                busyLabel = "拉取中…",
                enabled = { ageGroup.isNotBlank() },
                disabledHint = { "age_group 是契约必填项，请先选择分龄组。" },
            ) {
                Graph.assessmentApi.listScaleItemBanks(
                    role, ageGroup, dimension.ifBlank { null },
                ).also {
                    banks = it
                    picked = null
                }
            },
        )

        when (val list = banks) {
            null -> card.addView(UiKit.empty(ctx, "尚未拉取。"))
            else -> if (list.isEmpty()) {
                card.addView(UiKit.empty(ctx, "该分龄组 / 维度下暂无题组。"))
            } else {
                for (b in list) card.addView(bankRow(b))
            }
        }

        picked?.let { p ->
            card.addView(
                UiKit.text(
                    ctx,
                    "已锁定题组：scale_id=${p.scaleId ?: "—"} · item_group_id=${p.itemGroupId ?: "—"} · " +
                        "age_group_locked=${p.ageGroup ?: "—"}（C2 将引用这三项）",
                    Type.XS, Palette.ok,
                ),
            )
        }
        return card
    }

    private fun bankRow(b: ScaleItemBank): View =
        UiKit.row(ctx).apply {
            addView(
                UiKit.text(
                    ctx,
                    "${b.itemGroupId ?: b.scaleId ?: "（无 id）"} · ${b.ageGroup ?: "—"} · ${b.dimension ?: "—"}",
                    Type.SM, Palette.text,
                ).also { it.layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) },
            )
            addView(
                UiKit.ghostButton(ctx, "用作本次评估题组") {
                    // 把 C1 的结果**带入** C2 —— 不让人手抄 scale_id / item_group_id。
                    picked = b
                    b.ageGroup?.let { ageGroup = it }
                    host.shell.rerender()
                },
            )
        }

    // -------------------------------------------------------------------------
    // C2
    // -------------------------------------------------------------------------

    private fun c2Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "C2 基线评估提交",
            hint = "契约硬约束：dimension_scores 必须恰好 ${ContractEnums.DIMENSION_COUNT} 项；" +
                "total_score 0~112。三项钥匙来自上一步 C1。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx) { baselineCustomerId = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "题组钥匙（由 C1 带入，只读 —— 不做第二份权威）",
                UiKit.column(ctx).apply {
                    addView(UiKit.input(ctx, initial = picked?.scaleId ?: "", hint = "scale_id（先做 C1）", readOnly = true))
                    addView(UiKit.input(ctx, initial = picked?.itemGroupId ?: "", hint = "item_group_id（同源题组锁定）", readOnly = true))
                    addView(UiKit.input(ctx, initial = picked?.ageGroup ?: "", hint = "age_group_locked", readOnly = true))
                },
            ),
        )

        val sum = scores.sumOf { it.trim().toIntOrNull() ?: 0 }
        val filled = scores.count { it.isNotBlank() }
        card.addView(
            UiKit.field(
                ctx,
                "七个维度分（每项 0~16）—— 已填 $filled/${ContractEnums.DIMENSION_COUNT}，" +
                    "当前合计 $sum（**仅供核对**）",
                UiKit.column(ctx).apply {
                    ContractEnums.DIMENSION.forEachIndexed { i, d ->
                        addView(
                            UiKit.row(ctx).apply {
                                addView(
                                    UiKit.label(ctx, d).also {
                                        it.layoutParams = android.widget.LinearLayout.LayoutParams(150, -2)
                                    },
                                )
                                addView(
                                    UiKit.input(ctx, hint = "0~16", numeric = true) { scores[i] = it }
                                        .also {
                                            it.layoutParams =
                                                android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                                        },
                                )
                            },
                        )
                    }
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "总分（total_score，0~112）—— 以你填的值为准，本页**不自动覆盖**",
                UiKit.input(ctx, numeric = true) { totalScore = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "测量操作人（measure_operator）",
                UiKit.input(ctx) { measureOperator = it },
            ),
        )

        card.addView(
            actionButton(
                label = "基线评估",
                bar = bar,
                enabled = {
                    baselineCustomerId.isNotBlank() && picked != null &&
                        scores.count { it.isNotBlank() } == ContractEnums.DIMENSION_COUNT
                },
                disabledHint = {
                    // 计数先落成一个变量，是为了让 `when` 的条件读起来短一行。
                    // ⚠️ 这里曾有一条**错误的注释**，记的是"lambda 花括号与 when
                    //    花括号配错"。真正的原因是**行首 `+`**：Kotlin 把行首的 `+`
                    //    按**一元加**解析，于是在它之前表达式就被判定结束，
                    //    真正的错误却报在几十行之后（`Expecting '->'`），指向别处。
                    //    本文件当时写成 `"上句"` 换行 `+ "下句"`，三处全中。
                    // ⇒ 铁律：续行操作符（`+` `.` `?:` `&&` `||`）一律写在**行尾**。
                    //    其中只有 `+` 会以一元运算符的身份悄悄终止表达式，
                    //    所以它是最容易写完还能编译、却报错找错地方的一个。
                    val n = scores.count { it.isNotBlank() }
                    when {
                        picked == null -> "请先在 C1 锁定一个题组（三项钥匙必须来自 C1 返回）。"
                        n != ContractEnums.DIMENSION_COUNT ->
                            "契约要求 dimension_scores **恰好 ${ContractEnums.DIMENSION_COUNT} 项**" +
                                "（当前 $n 项）—— 少填一项服务端会回 5001，" +
                                "但让一线在点之前就看到原因，比事后再排查划算。"
                        else -> "请先填写客户 ID。"
                    }
                },
            ) {
                Graph.assessmentApi.submitBaselineAssessment(
                    role = role,
                    customerId = baselineCustomerId.trim(),
                    scaleId = picked?.scaleId ?: "",
                    itemGroupId = picked?.itemGroupId ?: "",
                    ageGroupLocked = picked?.ageGroup ?: "",
                    // 🛑 提交的是你填的值；本页**不用求和结果覆盖 total_score**。
                    dimensionScores = scores.map { it.trim().toIntOrNull() ?: 0 },
                    totalScore = totalScore.trim().toIntOrNull() ?: 0,
                    measureOperator = measureOperator.trim(),
                ).also { baseline = it }
            },
        )

        baseline?.let { b ->
            card.addView(UiKit.kv(ctx, "评估 ID", b.assessmentId))
            card.addView(UiKit.kv(ctx, "评估时间", b.assessedAt ?: "—"))
            card.addView(
                UiKit.kvView(
                    ctx,
                    "基线结论",
                    UiKit.badge(
                        ctx,
                        b.baselineConclusion?.get("haozhuan")?.takeIf { it.isJsonPrimitive }?.asString ?: "—（未下发）",
                        Tone.NEUTRAL,
                    ),
                ),
            )
            card.addView(
                UiKit.kv(
                    ctx,
                    "可迁移（migratable）",
                    when (b.migratable) {
                        null -> "—（未下发）"
                        true -> "是"
                        false -> "否"
                    },
                ),
            )
        }
        return card
    }

    // -------------------------------------------------------------------------
    // C3
    // -------------------------------------------------------------------------

    private fun c3Card(bar: OutcomeBar): View {
        val card = UiKit.card(ctx, title = "C3 评估详情", hint = "按客户 + 评估 ID 读取一次评估的全部下发字段。")
        card.addView(
            UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx) { detailCustomerId = it }),
        )
        card.addView(
            UiKit.field(
                ctx,
                "评估 ID（assessment_id）",
                UiKit.input(ctx, hint = "基线评估返回的 assessment_id") { assessmentId = it },
            ),
        )
        card.addView(
            actionButton(
                label = "评估详情",
                bar = bar,
                busyLabel = "读取中…",
                enabled = { detailCustomerId.isNotBlank() && assessmentId.isNotBlank() },
                disabledHint = { "请先填写客户 ID 与评估 ID。" },
            ) {
                Graph.assessmentApi.getAssessment(role, detailCustomerId.trim(), assessmentId.trim())
                    .also { detail = it }
            },
        )

        val d = detail
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
    // C4
    // -------------------------------------------------------------------------

    private fun c4Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "C4 周期评估提交",
            hint = "依从维度与 F1 同一形状（契约明文）；🛑 A2 永不参与 AS ⇒ 本页刻意不提供 A2 这一项。",
        )
        card.addView(UiKit.field(ctx, "客户 ID（customer_id）", UiKit.input(ctx) { cycleCustomerId = it }))
        card.addView(
            UiKit.field(
                ctx,
                "周期评估主键（cycle_id，必填 —— 使 F1 能在同一行上补判定）",
                UiKit.input(ctx) { cycleId = it },
            ),
        )
        card.addView(UiKit.field(ctx, "序号（sequence_no，第 N 次评估，每 7 次触发判定）", UiKit.input(ctx, initial = sequenceNo) { sequenceNo = it }))
        card.addView(UiKit.field(ctx, "应填报天数（expected_days，可选）", UiKit.input(ctx, numeric = true) { expectedDays = it }))
        card.addView(
            UiKit.field(
                ctx,
                "依从维度（A1 / A3 / A4 —— A2 不参与 AS，故此处无 A2）",
                UiKit.column(ctx).apply {
                    for (k in ADHERENCE_KEYS) {
                        val st = adherence.getValue(k)
                        addView(
                            UiKit.row(ctx).apply {
                                addView(UiKit.label(ctx, k).also {
                                    it.layoutParams = android.widget.LinearLayout.LayoutParams(50, -2)
                                })
                                addView(UiKit.checkbox(ctx, "适用", st.applicable) { st.applicable = it })
                                addView(UiKit.hgap(ctx))
                                addView(
                                    UiKit.input(ctx, hint = "值", numeric = true) { st.value = it }
                                        .also { it.layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) },
                                )
                                addView(UiKit.hgap(ctx))
                                addView(
                                    UiKit.checkbox(ctx, "结构性缺失", st.structuralMissing) {
                                        st.structuralMissing = it
                                    },
                                )
                            },
                        )
                    }
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "模块分（module_scores，M1–M5 各 0–16，JSON 对象）",
                UiKit.textarea(ctx, initial = moduleScoresJson) { moduleScoresJson = it },
            ),
        )
        card.addView(
            UiKit.field(ctx, "手环趋势备注（band_trend_note，可选）", UiKit.input(ctx) { bandTrendNote = it }),
        )
        card.addView(
            UiKit.field(
                ctx,
                "口径版本断言位（threshold_version，可空 = 服务端填）",
                UiKit.input(ctx) { thresholdVersion = it },
            ),
        )
        card.addView(
            actionButton(
                label = "周期评估",
                bar = bar,
                enabled = { cycleCustomerId.isNotBlank() && cycleId.isNotBlank() },
                disabledHint = { "cycle_id 必填（使 F1 能在同一行上补判定），请先补全客户 ID 与 cycle_id。" },
            ) {
                val moduleScores = parseJsonObject(moduleScoresJson)
                @Suppress("UNCHECKED_CAST")
                val msMap = Gson().fromJson(moduleScores, Map::class.java) as Map<String, Any?>

                // 🛑 依从维度**刻意不含 A2**：契约红线「A2 永不参与 AS（依从度）」。
                //    这里不从任何地方"顺手补一个 A2"。
                val dims = linkedMapOf<String, Any?>()
                for (k in ADHERENCE_KEYS) {
                    val st = adherence.getValue(k)
                    dims[k] = linkedMapOf<String, Any?>(
                        "applicable" to st.applicable,
                        "value" to (st.value.trim().toIntOrNull() ?: 0),
                        "structural_missing" to st.structuralMissing,
                    )
                }
                val body = linkedMapOf<String, Any?>()
                body["cycle_id"] = cycleId.trim()
                body["sequence_no"] = sequenceNo.trim().toIntOrNull() ?: 1
                body["adherence"] = linkedMapOf<String, Any?>(
                    "dimensions" to dims,
                ).also { m ->
                    expectedDays.trim().toIntOrNull()?.let { m["expected_days"] = it }
                }
                expectedDays.trim().toIntOrNull()?.let { body["expected_days"] = it }
                body["module_scores"] = msMap
                if (bandTrendNote.isNotBlank()) body["band_trend_note"] = bandTrendNote.trim()
                if (thresholdVersion.isNotBlank()) body["threshold_version"] = thresholdVersion.trim()
                Graph.assessmentApi.submitCycleAssessment(role, cycleCustomerId.trim(), body)
            },
        )
        card.addView(
            UiKit.label(
                ctx,
                "F1 判定结论需要 C4 的 cycle_id 作为路径参数 —— 提交后把它带到" +
                    "「专属动作」页的 F1 卡片（契约逐字：「周期评估主键（必填 —— " +
                    "使 F1 能在同一行上补判定）」）。",
            ),
        )
        return card
    }

    // -------------------------------------------------------------------------

    private fun parseJsonObject(raw: String): JsonObject {
        val trimmed = raw.trim().ifBlank { "{}" }
        val el = runCatching { com.google.gson.JsonParser.parseString(trimmed) }.getOrNull()
        if (el == null || !el.isJsonObject) {
            throw IllegalArgumentException("JSON 解析失败：请检查 module_scores 的写法（应为一个 JSON 对象）。")
        }
        return el.asJsonObject
    }

    private companion object {
        /**
         * 依从维度 —— 逐字取自契约「键为 A1/A3/A4；🛑 A2 永不参与 AS」。
         * 🛑 不在任何地方补 A2。
         */
        val ADHERENCE_KEYS: List<String> = listOf("A1", "A3", "A4")
    }
}
