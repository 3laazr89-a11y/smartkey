package com.leo.smartboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusPx
    if (strokeColor != null) setStroke(strokePx, strokeColor)
}

fun gradientBg(top: Int, bottom: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))

fun Context.iconButton(res: Int, tint: Int, sizeDp: Int = 44, padDp: Int = 11, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.label(text: String, color: Int, sizeSp: Float, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text; setTextColor(color); textSize = sizeSp
    if (bold) setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER_VERTICAL
}

/** A miniature rendering of a keyboard theme, used in the theme pickers. */
class ThemePreview(ctx: Context, var theme: KbTheme, private val chosen: Boolean = false) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.72f).toInt())
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = 12 * d
        p.shader = LinearGradient(0f, 0f, 0f, h, theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.shader = null
        val pad = 7 * d; val gap = 3 * d
        val rows = 4; val cols = 6
        val kh = (h - pad * 2 - gap * (rows - 1)) / rows
        val kw = (w - pad * 2 - gap * (cols - 1)) / cols
        for (ri in 0 until rows) for (ci in 0 until cols) {
            if (ri == 3 && (ci == 2 || ci == 3)) continue
            val x = pad + ci * (kw + gap); val y = pad + ri * (kh + gap)
            val right = if (ri == 3 && ci == 1) x + kw * 3 + gap * 2 else x + kw
            val color = when {
                ri == 3 && ci == 5 -> theme.accent
                ri == 3 && ci == 0 && theme.emojiKey != null -> theme.emojiKey!!
                ri == 3 && ci == 4 && theme.punctKey != null -> theme.punctKey!!
                ri == 3 && ci == 1 && theme.keycap -> theme.key
                ri == 3 || (ci == 5 && ri == 2) -> theme.keyFn
                else -> theme.key
            }
            p.color = color
            if (!theme.borderless || color == theme.accent) c.drawRoundRect(RectF(x, y, right, y + kh), 3 * d, 3 * d, p)
            if (ri < 3 && !(ri == 2 && ci == 5)) {
                p.color = theme.text
                c.drawCircle(x + kw / 2, y + kh / 2, 1.6f * d, p)
            }
        }
        if (chosen) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = theme.accent
            c.drawRoundRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, r, r, p)
            p.style = Paint.Style.FILL
        }
    }
}
