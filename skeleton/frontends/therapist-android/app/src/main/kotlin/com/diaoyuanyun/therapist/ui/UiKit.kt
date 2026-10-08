package com.diaoyuanyun.therapist.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.diaoyuanyun.therapist.api.ApiErrors
import com.diaoyuanyun.therapist.api.Described
import com.diaoyuanyun.therapist.ui.theme.Palette
import com.diaoyuanyun.therapist.ui.theme.Radius
import com.diaoyuanyun.therapist.ui.theme.Space
import com.diaoyuanyun.therapist.ui.theme.Tone
import com.diaoyuanyun.therapist.ui.theme.Type
import com.diaoyuanyun.therapist.ui.theme.Ui

/**
 * 端 B · 通用 UI 元件（原生 Android，**程序化构建**）
 * ============================================================================
 *
 * 与 Web 端 `therapist-app/src/ui/components.tsx` **逐元件对应**：
 *   Card / KV / Badge / Empty / ErrorBar / Page ⇄ [card] / [kv] / [badge] /
 *   [empty] / [errorBar] / [page]。
 *
 * 🛑 为什么程序化构建而不是写 XML 布局
 * ---------------------------------------------------------------------------
 *   · 本端每个页面都是**数据驱动**的（工作台列的是生成物现算的端点清单，
 *     专属动作列的是生成物现算的"单一角色端点"）—— 这些清单无法预先写进 XML，
 *     必然要在代码里 addView。若一半 XML、一半代码，样式规则就分了两处，
 *     而两处早晚分叉（本仓第 46 条）。
 *   · 全部走 [Palette] / [Space] / [Type] 令牌 ⇒ 代码里**零色值字面量**，
 *     满足本仓 P0 规则第 3 条（android-check 的 `no-color-literal` 会扫）。
 *
 * 🛑 为什么不用 Material 的现成组件（TextInputLayout / MaterialCardView）
 * ---------------------------------------------------------------------------
 * 它们的默认样式（阴影高度 / 圆角 / 内边距 / 描边色）由主题继承链决定，
 * 而主题继承链在本端是**变量**（OEM 主题会覆盖）。用它们就等于把"长什么样"
 * 交给运行环境 —— 同一份代码在不同机型上不同样，与"五源对齐"直接冲突。
 * ⇒ 自绘（GradientDrawable + 令牌）换来的是**像素级可控**。
 *    代价是要自己写圆角描边，但本端的视觉规格只有三档圆角、一种描边。
 */

/** 页面/元件的统一尺寸与背景工具。 */
/**
 * 圆角底/描边。
 *
 * 🛑 `fill` 的缺省值是 `Color.TRANSPARENT`，**不是**一个 `0x00000000` 字面量
 * ---------------------------------------------------------------------------
 * "无底色"不是设计令牌，它是"这个形状不填色"的语义 —— 用框架常量表达它，
 * 顺带让"代码里不得出现色值字面量"这条 P0 规则（第 3 条）没有例外口子。
 * 一旦在调用点写 `0x00000000`，下一个人会以为"这里可以填色值"，
 * 于是品牌色就会从这里开始**长到 Kotlin 里**，而 colors.xml 不再是唯一真实源。
 */
private fun rounded(
    ctx: Context,
    fill: Int = Color.TRANSPARENT,
    stroke: Int? = null,
    radius: Float = Radius.MD,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fill)
    cornerRadius = Ui.dp(ctx, radius.toInt()).toFloat()
    if (stroke != null) setStroke(Ui.dp(ctx, 1), stroke)
}

private fun lpMatchWrap() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
)

/**
 * 叶子视图的默认布局参数 —— **WRAP_CONTENT / WRAP_CONTENT**，刻意不是 MATCH_PARENT。
 *
 * 🛑 为什么不能默认 MATCH_PARENT（一处会静默失效的坑）
 * ---------------------------------------------------------------------------
 * 横向 LinearLayout 里，一个宽度 MATCH_PARENT 的子视图会**吃掉整行宽度**，
 * 排在它后面的兄弟节点被挤出屏幕外 —— 而没有任何报错。
 * 最典型的症状："页头只显示标题、角色徽标不见了"，且在不同屏宽下表现不同，
 * 极难归因。故叶子一律 wrap，**需要撑满的由调用方显式声明**。
 * 撑满的常见场景只有两个：纵向容器里的分隔条 / 通栏文本，
 * 那两处由 [column] / [padding] / [errorBar] 等显式给出 MATCH_PARENT。
 */
private fun lpWrap() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
)

/** 撑满父宽度的纵向子视图参数（带可选外边距）。 */
private fun lpTight(ctx: Context, top: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).also { it.setMargins(0, Ui.dp(ctx, top), 0, Ui.dp(ctx, bottom)) }

private fun padding(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).also { it.setMargins(Ui.dp(ctx, l), Ui.dp(ctx, t), Ui.dp(ctx, r), Ui.dp(ctx, b)) }

/** 权重撑满（横向行里让某个子视图占满剩余宽度）。 */
private fun lpWeight(weight: Float) = LinearLayout.LayoutParams(
    0, ViewGroup.LayoutParams.WRAP_CONTENT, weight,
)

/** 端 B 通用元件工厂。**所有页面只能通过本对象造视图**（令牌唯一的落点）。 */
object UiKit {

    // -------------------------------------------------------------------------
    // 文本
    // -------------------------------------------------------------------------

    fun text(
        ctx: Context,
        value: String,
        size: Float = Type.MD,
        colorRes: Int = Palette.text,
        bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(Ui.color(ctx, colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = lpWrap()
    }

    /** 小号弱化说明文字（等价 Web 端 `labelStyle`）。 */
    fun label(ctx: Context, value: String): TextView =
        text(ctx, value, Type.XS, Palette.textMuted)

    // -------------------------------------------------------------------------
    // 布局
    // -------------------------------------------------------------------------

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lpMatchWrap()
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lpMatchWrap()
    }

    /**
     * 页面外壳：标题 + 角色徽标 + 可滚动内容区。
     * @return 内容容器（页面往它里面 addView）。
     */
    fun page(
        ctx: Context,
        title: String,
        roleLabel: String,
        subtitle: String? = null,
    ): LinearLayout {
        val outer = column(ctx)
        outer.setPadding(
            Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.LG),
            Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.XL),
        )

        // 🛑 标题用 `weight=1` 占满剩余宽度（**不是** MATCH_PARENT）：
        //    横向行里用 MATCH_PARENT 会把角色徽标挤出屏幕，且没有任何报错。
        val header = row(ctx)
        header.addView(text(ctx, title, Type.XL, Palette.text, bold = true).also {
            it.layoutParams = lpWeight(1f)
        })
        header.addView(badge(ctx, roleLabel, Tone.NEUTRAL))
        outer.addView(header)

        if (subtitle != null) {
            outer.addView(
                label(ctx, subtitle).also { it.layoutParams = lpTight(ctx, top = Space.XS) },
            )
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            isFillViewport = true
        }
        val content = column(ctx)
        content.setPadding(0, Ui.dp(ctx, Space.LG), 0, 0)
        scroll.addView(content)
        outer.addView(scroll)
        return content
    }

    /**
     * 卡片。
     * @return 卡片的内容容器（调用方往它里面 addView）。
     */
    fun card(ctx: Context, title: String? = null, hint: String? = null): LinearLayout {
        val box = column(ctx)
        box.background = rounded(ctx, Ui.color(ctx, Palette.surface), Ui.color(ctx, Palette.border))
        box.setPadding(
            Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.LG),
            Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.LG),
        )
        box.layoutParams = padding(ctx, 0, 0, 0, Space.LG)

        if (title != null) {
            box.addView(
                text(ctx, title, Type.LG, Palette.text, bold = true).also {
                    it.layoutParams = padding(ctx, 0, 0, 0, Space.SM)
                },
            )
        }
        if (hint != null) {
            box.addView(label(ctx, hint).also {
                it.layoutParams = padding(ctx, 0, 0, 0, Space.MD)
            })
        }
        return box
    }

    /** 键值行（等价 Web 端 `KV`）。 */
    fun kv(ctx: Context, k: String, v: String, valueTone: Tone? = null): LinearLayout =
        row(ctx).apply {
            layoutParams = padding(ctx, 0, Space.XS, 0, Space.XS)
            val key = label(ctx, k).also {
                it.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 150), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(key)
            val value = text(
                ctx, v, Type.MD,
                valueTone?.colorRes ?: Palette.text,
            ).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(value)
        }

    /** 键值行（值是任意 View，如徽标 / 多段文本）。 */
    fun kvView(ctx: Context, k: String, v: View): LinearLayout =
        row(ctx).apply {
            layoutParams = padding(ctx, 0, Space.XS, 0, Space.XS)
            addView(label(ctx, k).also {
                it.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 150), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(v.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        }

    /** 状态徽标（等价 Web 端 `Badge`）。 */
    fun badge(ctx: Context, value: String, tone: Tone = Tone.NEUTRAL): TextView =
        text(ctx, value, Type.XS, tone.colorRes).apply {
            background = rounded(ctx, stroke = Ui.color(ctx, tone.colorRes), radius = Radius.SM)
            setPadding(Ui.dp(ctx, Space.SM), Ui.dp(ctx, 2), Ui.dp(ctx, Space.SM), Ui.dp(ctx, 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /**
     * 空态。**始终带说明文字**（不做"空白 + 无解释"的页面）——
     * 一线看到空白会以为"系统坏了"或"我没权限"，必须明说为什么空。
     */
    fun empty(ctx: Context, value: String): TextView =
        label(ctx, value).apply {
            gravity = Gravity.CENTER
            // 通栏（居中才有意义 —— wrap 宽度下 gravity=CENTER 是空操作）
            layoutParams = lpTight(ctx)
            setPadding(0, Ui.dp(ctx, Space.LG), 0, Ui.dp(ctx, Space.LG))
        }

    /** 错误条（等价 Web 端 `ErrorBar`）。文案来自 ApiErrors，**不直接渲染服务端 message**。 */
    fun errorBar(ctx: Context, value: String, traceId: String? = null, tone: Tone = Tone.DANGER): LinearLayout =
        column(ctx).apply {
            background = rounded(ctx, Ui.color(ctx, Palette.surfaceAlt), Ui.color(ctx, tone.colorRes))
            setPadding(
                Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
                Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
            )
            layoutParams = padding(ctx, 0, 0, 0, Space.MD)
            addView(text(ctx, value, Type.SM, Palette.text))
            if (!traceId.isNullOrBlank()) {
                addView(label(ctx, "trace_id: $traceId").also {
                    it.layoutParams = padding(ctx, 0, Space.XS, 0, 0)
                })
            }
        }

    // -------------------------------------------------------------------------
    // 表单
    // -------------------------------------------------------------------------

    /**
     * 带标签的字段。
     * @param body 字段控件体（[input] / [spinner] / [checkboxGroup] 等）
     */
    fun field(ctx: Context, labelText: String, body: View): LinearLayout =
        column(ctx).apply {
            layoutParams = padding(ctx, 0, 0, 0, Space.MD)
            addView(label(ctx, labelText))
            addView(body.also { it.layoutParams = lpMatchWrap().also { p -> p.topMargin = Ui.dp(ctx, Space.XS) } })
        }

    /** 单行输入。 */
    fun input(
        ctx: Context,
        initial: String = "",
        hint: String = "",
        readOnly: Boolean = false,
        masked: Boolean = false,
        numeric: Boolean = false,
        onChange: (String) -> Unit = {},
    ): EditText = EditText(ctx).apply {
        setText(initial)
        this.hint = hint
        textSize = Type.MD
        setTextColor(Ui.color(ctx, Palette.text))
        setHintTextColor(Ui.color(ctx, Palette.textMuted))
        background = rounded(ctx, Ui.color(ctx, Palette.surface), Ui.color(ctx, Palette.border), Radius.SM)
        setPadding(Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM), Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM))
        isEnabled = !readOnly
        if (masked) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        layoutParams = lpMatchWrap()
        // 🛑 只监听 `afterTextChanged`：`onTextChanged` 在程序化 setText 时也会触发，
        //    会把"回填一个 id"当成"用户输入"，导致回填值被自己的回调覆盖。
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isEnabled) onChange(s?.toString().orEmpty())
            }
        })
    }

    /** 多行输入（JSON 文本域等）。 */
    fun textarea(
        ctx: Context,
        initial: String = "",
        hint: String = "",
        onChange: (String) -> Unit = {},
    ): EditText = input(ctx, initial, hint, onChange = onChange).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        setText(initial)
    }

    /**
     * 下拉选择。
     *
     * 🛑 `options` 里**不允许**出现"默认替我选一个合理值"的项 ——
     *    契约多处明文禁止代填（如 F1 的 `risk_flag` 缺失即"未定"，不得默认「无」）。
     *    需要"未选择"语义时，由调用方把空串作为第一项显式传入。
     */
    fun spinner(
        ctx: Context,
        options: List<String>,
        selected: String? = null,
        onPick: (String) -> Unit = {},
    ): Spinner = Spinner(ctx).apply {
        background = rounded(ctx, Ui.color(ctx, Palette.surface), Ui.color(ctx, Palette.border), Radius.SM)
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
        val idx = options.indexOf(selected ?: "")
        if (idx >= 0) setSelection(idx)
        layoutParams = lpMatchWrap()
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // 🛑 只在**用户**选择时回调：`setSelection` 也会触发一次，
                //    若不区分，"程序化回填"会被当成"用户改选"，上游状态被覆盖。
                if (p?.selectedItemPosition == pos) onPick(options[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
    }

    /** 复选框（勾选项）。 */
    fun checkbox(
        ctx: Context,
        labelText: String,
        checked: Boolean = false,
        onChange: (Boolean) -> Unit = {},
    ): CheckBox = CheckBox(ctx).apply {
        text = labelText
        textSize = Type.MD
        setTextColor(Ui.color(ctx, Palette.text))
        isChecked = checked
        layoutParams = lpMatchWrap()
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // -------------------------------------------------------------------------
    // 按钮
    // -------------------------------------------------------------------------

    /** 主按钮（品牌实底）。 */
    fun button(
        ctx: Context,
        labelText: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): TextView = text(ctx, labelText, Type.MD, Palette.textInverse, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(ctx, Ui.color(ctx, Palette.brand), null, Radius.SM)
        setPadding(Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.SM), Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.SM))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { if (isEnabled) onClick() }
    }

    /** 次按钮（描边幽灵按钮）。 */
    fun ghostButton(
        ctx: Context,
        labelText: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): TextView = text(ctx, labelText, Type.MD, Palette.brand, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(ctx, stroke = Ui.color(ctx, Palette.border), radius = Radius.SM)
        setPadding(Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.SM), Ui.dp(ctx, Space.LG), Ui.dp(ctx, Space.SM))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { if (isEnabled) onClick() }
    }

    /** 分隔留白。 */
    fun gap(ctx: Context, dp: Int = Space.MD): View =
        View(ctx).also { it.layoutParams = LinearLayout.LayoutParams(1, Ui.dp(ctx, dp)) }

    /** 行内横向留白。 */
    fun hgap(ctx: Context, dp: Int = Space.SM): View =
        View(ctx).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, dp), 1) }
}

/**
 * 提交结果三态（**4002 幂等重放不是失败**）。
 *
 * 🛑 为什么单开一个类型而不是用 `Boolean + String`
 * ---------------------------------------------------------------------------
 * "成功 / 幂等命中 / 失败" 是三态，用 `Boolean` 压缩成两态就会把
 * **幂等命中**归到"失败"里 —— 那一线的反应是**再点一次**，
 * 于是产生更多重放（契约逐字：重放 = 此前已成功处理）。
 */
sealed interface Outcome {
    data class Ok(val label: String, val json: String) : Outcome
    data class Replay(val label: String, val json: String, val traceId: String) : Outcome
    data class Err(val text: String, val traceId: String) : Outcome

    /** 从异常整理出来（幂等重放走 [Replay]，其余走 [Err]）。 */
    companion object {
        fun from(label: String, err: Throwable): Outcome {
            val d: Described = ApiErrors.describe(err)
            return if (ApiErrors.isReplay(d)) {
                Replay(label, "该请求此前已成功处理（幂等命中，返回首次结果）。", d.traceId)
            } else {
                Err(d.text, d.traceId)
            }
        }

        /** 成功态；`data` 为空时给出**契约解释**而不是空白。 */
        fun ok(label: String, json: String?): Outcome = Ok(
            label,
            json ?: "（服务端未返回 data —— 契约 envelope-rule：code != 0 时 data 为空；此处 code = 0）",
        )
    }
}

/**
 * 可原地更新的结果条。
 *
 * 🛑 为什么原地更新而不是重建整页
 * ---------------------------------------------------------------------------
 * 重建整页会把用户正在输入的内容清空（输入框里的文本属于 View，不属于页面状态）。
 * 一线的典型动线是"填一半 → 提交 → 看结果 → 接着填"，清空输入会让这个动线
 * 变成"每次提交都要重填一遍"，那是可用性事故而非小瑕疵。
 */
class OutcomeBar(private val ctx: Context, private val parent: LinearLayout) {

    fun render(outcome: Outcome?) {
        parent.removeAllViews()
        when (outcome) {
            null -> Unit
            is Outcome.Ok -> parent.addView(
                UiKit.column(ctx).apply {
                    background = rounded(ctx, Ui.color(ctx, Palette.surfaceAlt), Ui.color(ctx, Palette.ok))
                    setPadding(
                        Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
                        Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
                    )
                    layoutParams = padding(ctx, 0, 0, 0, Space.MD)
                    addView(UiKit.text(ctx, "✓ ${outcome.label} 已完成", Type.SM, Palette.ok, bold = true))
                    addView(UiKit.text(ctx, outcome.json, Type.XS, Palette.textMuted))
                },
            )

            is Outcome.Replay -> parent.addView(
                UiKit.column(ctx).apply {
                    background = rounded(ctx, Ui.color(ctx, Palette.surfaceAlt), Ui.color(ctx, Palette.info))
                    setPadding(
                        Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
                        Ui.dp(ctx, Space.MD), Ui.dp(ctx, Space.SM),
                    )
                    layoutParams = padding(ctx, 0, 0, 0, Space.MD)
                    addView(UiKit.text(ctx, "↻ ${outcome.label} 幂等命中（不是失败）", Type.SM, Palette.info, bold = true))
                    addView(UiKit.text(ctx, outcome.json, Type.XS, Palette.textMuted))
                    if (outcome.traceId.isNotBlank()) {
                        addView(UiKit.label(ctx, "trace_id: ${outcome.traceId}"))
                    }
                },
            )

            is Outcome.Err -> parent.addView(
                UiKit.errorBar(ctx, outcome.text, outcome.traceId),
            )
        }
    }
}
