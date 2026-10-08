package com.diaoyuanyun.therapist.ui.theme

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.diaoyuanyun.therapist.R

/**
 * 端 B · 设计令牌（原生 Android）
 * ============================================================================
 *
 * 色值**逐字**取自 `frontends/therapist-app/src/ui/tokens.ts` 的 COLOR ——
 * 本仓"五源对齐"纪律在原生端的落点：同一套品牌令牌，四个工程同值。
 *
 * 🛑 代码里不得出现 `#RRGGBB` 字面量（本仓 P0 规则第 3 条）。
 *    一律经 [Palette] 的 `@ColorRes` 引用；`Ui.color()` 负责解成 ARGB。
 *
 * 🛑 端 B 与端 C 的**用色纪律不同**（不是审美差异，是合规差异）
 * ---------------------------------------------------------------------------
 *   · 端 C（客户）：契约 x-wording-discipline R8 禁用"不利"色与措辞 ⇒
 *     端 C 的色板**刻意没有**语义状态槽 —— 没有槽位就没有误用可能。
 *   · 端 B（内部）：③ 组（缺口原因）与 ④ 组（派生结果）对 staff 可见，
 *     一线必须一眼分辨严重度 ⇒ 端 B **需要**语义状态色。
 * ⇒ 色值同源、语义槽不同，两端不得互换。
 */
object Palette {
    // ---- 品牌主色 ----
    @ColorRes val brand = R.color.dy_brand
    @ColorRes val brandDark = R.color.dy_brand_dark
    @ColorRes val brandLight = R.color.dy_brand_light

    // ---- 底色 / 承载面 ----
    @ColorRes val bg = R.color.dy_bg
    @ColorRes val surface = R.color.dy_surface
    @ColorRes val surfaceAlt = R.color.dy_surface_alt
    @ColorRes val border = R.color.dy_border

    // ---- 文本 ----
    @ColorRes val text = R.color.dy_text
    @ColorRes val textMuted = R.color.dy_text_muted
    @ColorRes val textInverse = R.color.dy_text_inverse

    // ---- 语义状态（内部端专用，端 C 不得复用）----
    @ColorRes val ok = R.color.dy_ok
    @ColorRes val warn = R.color.dy_warn
    @ColorRes val danger = R.color.dy_danger
    @ColorRes val info = R.color.dy_info
}

/** 间距（dp）。取值与 Web 端 SPACE 一致，保证两端节奏相同。 */
object Space {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
    const val XXL = 32
}

/** 圆角（dp）。 */
object Radius {
    const val SM = 4f
    const val MD = 8f
    const val LG = 12f
}

/** 字号（sp）。与 Web 端 FONT 同名同值。 */
object Type {
    const val XS = 12f
    const val SM = 13f
    const val MD = 14f
    const val LG = 16f
    const val XL = 20f
    const val XXL = 24f
}

/** 令牌解算工具（唯一允许把 `@ColorRes` 变成 ARGB 的地方）。 */
object Ui {
    fun color(ctx: Context, @ColorRes id: Int): Int = ContextCompat.getColor(ctx, id)

    /** dp → px。 */
    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt() + 0

    /** 语义状态槽 → 具体色（Labels 里的 tone 用它解算）。 */
    fun toneColor(ctx: Context, tone: Tone): Int = color(ctx, tone.colorRes)
}

/** 语义状态槽。**这是端 B 专有的**：端 C 不得出现这组概念。 */
enum class Tone(@ColorRes val colorRes: Int) {
    OK(Palette.ok),
    WARN(Palette.warn),
    DANGER(Palette.danger),
    INFO(Palette.info),
    NEUTRAL(Palette.textMuted),
}
