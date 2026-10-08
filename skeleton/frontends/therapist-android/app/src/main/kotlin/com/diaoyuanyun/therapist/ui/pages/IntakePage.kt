package com.diaoyuanyun.therapist.ui.pages

import android.view.View
import com.diaoyuanyun.therapist.di.Graph
import com.diaoyuanyun.therapist.domain.ScreeningData
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
 * 端 B · 页面：客户建档与档案（域 B 之 B1~B6）
 * ============================================================================
 *
 * 本页存在的理由（**实测缺口**，不是设计偏好）
 * ---------------------------------------------------------------------------
 * Web 端曾经把 B 域六个函数（B1/B2/B3/B5/B6 + D2 同层）**全部封装完毕但
 * `src/` 全域零调用** —— 即"函数写好了，从没接上界面"。一线打开 APP 看不到
 * "建档"这件事，只能去用端 A 或找管理员。
 * 这不是"少一个按钮"：B 域是**客户进入服务链路的入口**（建档 ⇒ 签同意书 ⇒
 * 评估 ⇒ 服务），它缺失时整条链路在端 B 侧不可用，而 tsc / vite / 既有门禁
 * **全部绿**（既有门禁只判"函数是否存在"，不判"是否被界面触达"）。
 *
 * 🛑 硬门禁的界面表达：B1 筛查必须先于 B2 建档
 * ---------------------------------------------------------------------------
 * 契约 `CustomerCreateRequest.screening_id` 逐字：「服务端校验其 result=通过」。
 * 故本页把流程做成**顺序的两步**（先筛查拿 `screening_id`，再建档引用它），
 * 并把上一步的 `screening_id` **自动带进**下一步 —— 而不是给一个自由输入框让
 * 一线手抄一个 UUID（手抄必然出错，且出错后表现是"建档被拒但不知道为什么"）。
 *
 * 🛑 B3 的请求体字段名来自【控制器真实形状】（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * 唯一真相源是 `CustomerController.signConsent` 真实读取的键：
 *   · `auth_scope`       —— 数组，元素取 `ConsentAuthScope` 的码
 *   · `band_willingness` —— `BandWillingness` 的码（自愿佩戴 / 暂不佩戴）
 *   · `evidence_hash`    —— 证据哈希
 *   · `data_source`      —— `ConsentDataSource` 的码（self-report / device）
 * ⚠️ 自创字段名（如 `scope` / `willing`）**不会报任何错**：服务端只会收到一个
 *    陌生的键 ⇒ 表现为一次业务失败，而排查方向会跑偏到"客户数据有问题"。
 */
class IntakePage(host: PageHost) : BasePage(host) {

    private var outcome: Outcome? = null

    // B1
    private var screenCustomerId = ""
    private var flagPregnancy = false
    private var flagAcute = false
    private var flagRiskHistory = false
    private var flagNonmedical = false
    private var screening: ScreeningData? = null

    // B2
    private var name = ""
    private var gender = GENDER_FEMALE
    private var age = ""
    private var phone = ""
    private var createdStatus: String? = null
    private var createdCustomerId: String? = null

    // B3
    private var consentCustomerId = ""
    private val scopes = linkedSetOf<String>()
    private var willingness = ""
    private var dataSource = ""
    private var evidenceHash = ""

    // B5 / B6
    private var profileCustomerId = ""
    private var profile: JsonObject? = null
    private var patchJson = "{}"

    override fun render(): View {
        val root = UiKit.page(ctx, "客户建档与档案", roleLabel)
        val slot = UiKit.column(ctx)
        val bar = OutcomeBar(ctx, slot)
        root.addView(slot)
        bar.render(outcome)

        root.addView(b1Card(bar))
        root.addView(b2Card(bar))
        root.addView(b3Card(bar))
        root.addView(b5Card(bar))
        root.addView(b6Card(bar))
        root.addView(contractCard())
        return root
    }

    // -------------------------------------------------------------------------
    // B1
    // -------------------------------------------------------------------------

    private fun b1Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "B1 禁忌筛查（建档前置门禁）",
            hint = "契约逐字：建档请求的 screening_id 由服务端校验其 result=通过。" +
                "故必须先做这一步。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx, hint = "customer uuid") { screenCustomerId = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "筛查项（items_json）—— 🛑 契约只给了这四个键名，未定义取值形态，本页按 bool 提交",
                UiKit.column(ctx).apply {
                    addView(UiKit.checkbox(ctx, "妊娠（pregnancy）") { flagPregnancy = it })
                    addView(UiKit.checkbox(ctx, "急性期（acute）") { flagAcute = it })
                    addView(UiKit.checkbox(ctx, "风险史（risk_history）") { flagRiskHistory = it })
                    addView(
                        UiKit.checkbox(ctx, "非医疗自述（nonmedical_disclosed）") { flagNonmedical = it },
                    )
                },
            ),
        )
        card.addView(
            UiKit.label(
                ctx,
                "operator_id 契约要求必传，但逐字「服务端从 token 覆写」⇒ 本页传占位值，" +
                    "真实操作人由服务端依登录身份写入（不在这里假装由前端决定）。",
            ),
        )

        card.addView(
            actionButton(
                label = "禁忌筛查",
                bar = bar,
                enabled = { screenCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "customer_id" to screenCustomerId.trim(),
                    "items_json" to linkedMapOf<String, Any?>(
                        "pregnancy" to flagPregnancy,
                        "acute" to flagAcute,
                        "risk_history" to flagRiskHistory,
                        "nonmedical_disclosed" to flagNonmedical,
                    ),
                    "operator_id" to OPERATOR_PLACEHOLDER,
                )
                Graph.intakeApi.createScreeningRecord(role, body).also { screening = it }
            },
        )

        screening?.let { s ->
            card.addView(
                UiKit.kvView(
                    ctx,
                    "筛查结果",
                    UiKit.badge(
                        ctx, s.result,
                        if (s.result == SCREENING_PASS) Tone.OK else Tone.DANGER,
                    ),
                ),
            )
            card.addView(UiKit.kv(ctx, "screening_id", s.screeningId))
            card.addView(UiKit.kv(ctx, "提交时间", s.submittedAt ?: "—"))
        }
        return card
    }

    // -------------------------------------------------------------------------
    // B2
    // -------------------------------------------------------------------------

    private fun b2Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "B2 客户建档",
            hint = "screening_id 必须引用一次「通过」的筛查；本页自动带入上一步的结果，" +
                "不让你手抄 UUID。",
        )
        card.addView(UiKit.field(ctx, "姓名（name）", UiKit.input(ctx) { name = it }))
        card.addView(
            UiKit.field(
                ctx,
                "性别（gender）",
                UiKit.spinner(ctx, listOf(GENDER_FEMALE, GENDER_MALE), gender) { gender = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "年龄（age，契约约束 1~119）",
                UiKit.input(ctx, hint = "1~119", numeric = true) { age = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "手机号（phone，租户内唯一 —— 跨店识别键）",
                UiKit.input(ctx, numeric = true) { phone = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "筛查记录（screening_id，只读 —— 来自上一步，不手抄）",
                UiKit.input(
                    ctx,
                    initial = screening?.screeningId ?: "",
                    hint = "请先在上一步完成 B1 筛查（通过后自动带入）",
                    readOnly = true,
                ),
            ),
        )

        card.addView(
            actionButton(
                label = "客户建档",
                bar = bar,
                enabled = {
                    name.isNotBlank() && age.isNotBlank() && phone.isNotBlank() &&
                        screening?.result == SCREENING_PASS
                },
                disabledHint = {
                    when {
                        screening == null -> "尚未完成 B1 筛查 —— 契约要求建档引用一次「通过」的筛查。"
                        screening?.result != SCREENING_PASS -> "筛查结果为「不通过」，此路不通。"
                        else -> "请先补全姓名 / 年龄 / 手机号。"
                    }
                },
            ) {
                val body = linkedMapOf<String, Any?>(
                    "name" to name.trim(),
                    "gender" to gender,
                    "age" to (age.trim().toIntOrNull() ?: 0),
                    "phone" to phone.trim(),
                    "screening_id" to (screening?.screeningId ?: ""),
                )
                Graph.intakeApi.createCustomer(role, body).also { r ->
                    createdStatus = r?.status
                    createdCustomerId = r?.customerId
                    // 建档成功后把客户 ID 自动带入后续两步 —— 不让一线手抄。
                    r?.customerId?.let {
                        consentCustomerId = it
                        profileCustomerId = it
                        host.shell.sharedCustomerId = it
                    }
                }
            },
        )

        // 条件不满足的原因**始终可见**（不只在下一次点击时）：一线扫一眼就知道卡在哪。
        when {
            screening == null -> card.addView(
                UiKit.label(
                    ctx,
                    "尚未完成 B1 筛查 —— 服务端也会拒，故此处先说明原因，" +
                        "而不是让一线提交后收到一个 5001 再去猜。",
                ),
            )
            screening?.result != SCREENING_PASS -> card.addView(
                UiKit.text(
                    ctx,
                    "筛查结果为「不通过」⇒ 契约要求服务端校验 result=通过，此路不通。",
                    Type.XS, Palette.danger,
                ),
            )
        }
        return card
    }

    // -------------------------------------------------------------------------
    // B3
    // -------------------------------------------------------------------------

    private fun b3Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "B3 签署知情同意书",
            hint = "契约未声明 requestBody ⇒ 字段名逐字对齐后端控制器" +
                "（auth_scope / band_willingness / evidence_hash / data_source）。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx, initial = consentCustomerId) { consentCustomerId = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "授权项（auth_scope，可单独拒绝 —— 空集合亦合法，即全部拒绝）",
                UiKit.column(ctx).apply {
                    for (s in AUTH_SCOPES) {
                        addView(
                            UiKit.checkbox(ctx, "${s.second}（${s.first}）", scopes.contains(s.first)) { on ->
                                // 边勾边存，**不重绘**：重绘会丢掉其它字段的输入焦点。
                                if (on) scopes.add(s.first) else scopes.remove(s.first)
                            },
                        )
                    }
                },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "佩戴意愿（band_willingness，逐字枚举）",
                UiKit.spinner(ctx, listOf("", WILLING_VOLUNTARY, WILLING_DECLINE), willingness) {
                    willingness = it
                },
            ),
        )
        card.addView(
            UiKit.label(
                ctx,
                "「暂不佩戴」不是拒绝服务、也不是数据缺失 —— 它是「是否适用」这类问题的答案，" +
                    "不得用作降级服务或扣分的判据。",
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "同意来源（data_source，逐字枚举）",
                UiKit.spinner(ctx, listOf("", SOURCE_SELF_REPORT, SOURCE_DEVICE), dataSource) {
                    dataSource = it
                },
            ),
        )
        card.addView(
            UiKit.field(ctx, "证据哈希（evidence_hash，可空）", UiKit.input(ctx) { evidenceHash = it }),
        )
        card.addView(
            actionButton(
                label = "签署同意书",
                bar = bar,
                enabled = { consentCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                val body = linkedMapOf<String, Any?>()
                body["auth_scope"] = scopes.toList()
                if (willingness.isNotBlank()) body["band_willingness"] = willingness
                if (dataSource.isNotBlank()) body["data_source"] = dataSource
                if (evidenceHash.isNotBlank()) body["evidence_hash"] = evidenceHash.trim()
                Graph.intakeApi.signConsent(role, consentCustomerId.trim(), body)
            },
        )
        return card
    }

    // -------------------------------------------------------------------------
    // B5 / B6
    // -------------------------------------------------------------------------

    private fun b5Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "B5 读取扩展档案",
            hint = "读取客户的建档扩展档案（intake profile）。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx, initial = profileCustomerId) { profileCustomerId = it },
            ),
        )
        card.addView(
            actionButton(
                label = "读取扩展档案",
                bar = bar,
                busyLabel = "读取中…",
                enabled = { profileCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                Graph.intakeApi.getIntakeProfile(role, profileCustomerId.trim()).also { profile = it }
            },
        )

        val p = profile ?: return card.also { it.addView(UiKit.empty(ctx, "尚未读取。")) }
        if (p.entrySet().isEmpty()) {
            card.addView(UiKit.empty(ctx, "档案为空（服务端未返回字段）。"))
        } else {
            for ((k, v) in p.entrySet()) {
                card.addView(
                    UiKit.kv(ctx, k, if (v.isJsonObject || v.isJsonArray) v.toString() else v.asString),
                )
            }
        }
        return card
    }

    private fun b6Card(bar: OutcomeBar): View {
        val card = UiKit.card(
            ctx,
            title = "B6 补充 / 修订档案",
            hint = "契约逐字「不可覆盖」⇒ 本层只发 PATCH 增量" +
                "（服务端侧有 IntakeProfileRevision 留痕），不做整体覆盖式提交。",
        )
        card.addView(
            UiKit.field(
                ctx,
                "客户 ID（customer_id）",
                UiKit.input(ctx, initial = profileCustomerId) { profileCustomerId = it },
            ),
        )
        card.addView(
            UiKit.field(
                ctx,
                "增量内容（JSON 对象）—— 只写本次要补充/修订的键",
                UiKit.textarea(ctx, initial = patchJson) { patchJson = it },
            ),
        )
        card.addView(
            actionButton(
                label = "修订档案",
                bar = bar,
                enabled = { profileCustomerId.isNotBlank() },
                disabledHint = { "请先填写客户 ID。" },
            ) {
                val patch = parseJsonObject(patchJson)
                if (patch.entrySet().isEmpty()) {
                    // 🛑 append-only 语义下提交空对象没有意义 —— 明确报错而不是发一个空 PATCH。
                    throw IllegalArgumentException(
                        "增量内容为空 —— append-only 语义下提交空对象没有意义，请填写要修订的键。"
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val map = Gson().fromJson(patch, Map::class.java) as Map<String, Any?>
                Graph.intakeApi.patchIntakeProfile(role, profileCustomerId.trim(), map)
            },
        )
        return card
    }

    private fun contractCard(): View {
        val card = UiKit.card(ctx, title = "契约侧提示")
        card.addView(
            UiKit.label(
                ctx,
                "建档成功后返回的 status 是**5 值粗粒度派生聚合态**" +
                    "（CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED），" +
                    "**不是服务主状态机**（14 态在 customer_state_transition）——" +
                    "界面不得把它当状态机用。" +
                    (if (createdStatus != null) " 本次返回：$createdStatus。" else "") +
                    (if (createdCustomerId != null) " 客户 ID：$createdCustomerId。" else ""),
            ),
        )
        return card
    }

    // -------------------------------------------------------------------------

    private fun parseJsonObject(raw: String): JsonObject {
        val trimmed = raw.trim().ifBlank { "{}" }
        val el = runCatching { com.google.gson.JsonParser.parseString(trimmed) }.getOrNull()
        if (el == null || !el.isJsonObject) {
            throw IllegalArgumentException("JSON 解析失败：请检查增量内容的写法（应为一个 JSON 对象）。")
        }
        return el.asJsonObject
    }

    private companion object {
        const val OPERATOR_PLACEHOLDER = "(服务端从 token 覆写)"
        const val SCREENING_PASS = "通过"
        const val GENDER_FEMALE = "女"
        const val GENDER_MALE = "男"
        const val WILLING_VOLUNTARY = "自愿佩戴"
        const val WILLING_DECLINE = "暂不佩戴"
        const val SOURCE_SELF_REPORT = "self-report"
        const val SOURCE_DEVICE = "device"

        /** 四项授权 —— 码逐字取自 `ConsentAuthScope`。 */
        val AUTH_SCOPES: List<Pair<String, String>> = listOf(
            "collect_basic" to "基础信息采集",
            "generate_advice" to "生成调理建议",
            "service_record" to "服务记录",
            "rights_ack" to "权利告知确认",
        )
    }
}
