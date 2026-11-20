package com.leo.smartboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Canvas-drawn keyboard with key previews, long-press accent popups, delete repeat and space-bar cursor swipe. */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKeyText(text: String)
        fun onSpecialKey(key: Key)
        fun onCursorMove(steps: Int)
        fun onSpaceLongPress()
        fun onKeyFeedback()
    }

    private val dp = resources.displayMetrics.density
    var theme: KbTheme = Themes.all[0]
        set(v) { field = v; bgShader = null; invalidate() }
    var rows: List<List<Key>> = emptyList()
        set(v) { field = v; requestLayout(); layoutKeys(); invalidate() }
    var shiftState = 0 // 0 off, 1 once, 2 caps lock
        set(v) { field = v; invalidate() }
    var lettersUpper = true
    var enterIcon: Int = R.drawable.ic_enter
        set(v) { field = v; invalidate() }
    var keyHeightDp = 52
        set(v) { field = v; requestLayout() }
    var boldKeys = false
        set(v) { field = v; invalidate() }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bgPaint = Paint()
    private var bgShader: Shader? = null
    private val icons = HashMap<Int, Drawable>()

    private val padH = 3 * dp
    private val padV = 5 * dp
    private val gap: Float get() = when (theme.id) {
        "mint_vibrant" -> 1.5f * dp
        "retro_3d_compose" -> 2.5f * dp
        else -> if (theme.keycap) 3.5f * dp else 5f * dp
    }
    private val radius = 8 * dp

    private var pressed: Key? = null
    private var downX = 0f
    private var lastSwipeX = 0f
    private var spaceSwiping = false
    private var longPressed = false
    private var popupKey: Key? = null
    private var popupItems: List<String> = emptyList()
    private var popupSel = 0
    private var popupColumns = 1
    private var popupRows = 1
    private val popupRect = RectF()
    private val h = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            pressed?.let { if (it.type == KType.DEL) { listener.onSpecialKey(it); h.postDelayed(this, 45) } }
        }
    }

    fun preferredHeight(rowCount: Int = rows.size): Int = (rowCount * keyHeightDp * dp + padV * 2).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), preferredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgShader = null
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0 || rows.isEmpty()) return
        val maxWeight = rows.maxOf { r -> r.sumOf { it.weight.toDouble() } }.toFloat()
        val unit = (width - padH * 2) / maxWeight
        val rowH = keyHeightDp * dp
        rows.forEachIndexed { ri, row ->
            val rowW = row.sumOf { it.weight.toDouble() }.toFloat() * unit
            var x = padH + (width - padH * 2 - rowW) / 2f
            val top = padV + ri * rowH
            for (k in row) {
                val w = k.weight * unit
                k.rect.set(x + gap / 2, top + gap / 2, x + w - gap / 2, top + rowH - gap / 2)
                x += w
            }
        }
    }

    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun displayLabel(k: Key): String =
        if (k.type == KType.CHAR && lettersUpper && (shiftState > 0 || theme.capsLabels)) k.label.uppercase() else k.label

    private fun shade(c: Int, f: Float): Int {
        val a = Color.alpha(c)
        fun ch(v: Int) = (if (f < 1f) v * f else v + (255 - v) * (f - 1f)).toInt().coerceIn(0, 255)
        return Color.argb(a, ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    private fun faceColor(k: Key): Int {
        val t = theme
        return when {
            k.type == KType.ENTER && t.enterKeyColor != null -> t.enterKeyColor
            k.type == KType.ENTER -> t.accent
            k.type == KType.EMOJI && t.emojiKey != null -> t.emojiKey
            k.type == KType.LANG && t.languageKeyColor != null -> t.languageKeyColor
            k.type == KType.LANG && t.keycap -> 0xFFF2994A.toInt()
            k.type == KType.SYM && t.id == "mint_vibrant" -> t.key
            k.punct && t.punctKey != null -> t.punctKey
            k.type == KType.SHIFT && t.shiftKeyColor != null -> t.shiftKeyColor
            k.type == KType.SHIFT && shiftState == 2 -> t.accent
            k.isFunctional -> t.keyFn
            else -> t.key
        }
    }

    private val faceRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = theme
        if (bgShader == null) bgShader = LinearGradient(0f, 0f, 0f, height.toFloat(), t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
        bgPaint.shader = bgShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (row in rows) for (k in row) drawKey(canvas, k)
        pressed?.let { if (!spaceSwiping && popupKey == null && it.type == KType.CHAR) drawPreview(canvas, it) }
        popupKey?.let { drawPopup(canvas) }
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        val t = theme
        val isPressed = pressed === k && !spaceSwiping
        val accentKey = k.type == KType.ENTER
        val r = k.rect
        val lr: RectF
        if (t.id == "retro_3d_compose") {
            val depth = if (isPressed) 1.5f * dp else 3f * dp
            val rad = 5f * dp
            val face = faceColor(k)
            shadowPaint.color = 0xCC000000.toInt()
            canvas.drawRoundRect(r.left, r.top + 2f * dp, r.right, r.bottom + 2f * dp, rad, rad, shadowPaint)
            keyPaint.color = 0xFF626568.toInt()
            canvas.drawRoundRect(r, rad, rad, keyPaint)
            val top = r.top + if (isPressed) 2f * dp else 0.8f * dp
            faceRect.set(r.left + 1.4f * dp, top, r.right - 1.4f * dp, r.bottom - depth)
            val baseFace = if (isPressed) shade(face, 0.90f) else face
            keyPaint.shader = LinearGradient(0f, faceRect.top, 0f, faceRect.bottom,
                shade(baseFace, 1.06f), shade(baseFace, 0.92f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(faceRect, rad * 0.78f, rad * 0.78f, keyPaint)
            keyPaint.shader = null
            keyPaint.style = Paint.Style.STROKE
            keyPaint.strokeWidth = 0.7f * dp
            keyPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(faceRect, rad * 0.78f, rad * 0.78f, keyPaint)
            keyPaint.style = Paint.Style.FILL
            lr = faceRect
        } else if (t.keycap) {
            // Mechanical keycap: dark side + raised face with a soft vertical gradient.
            val face = faceColor(k)
            val depth = if (isPressed) 1.5f * dp else 4.5f * dp
            val rad = 6 * dp
            shadowPaint.color = 0x66000000
            canvas.drawRoundRect(r.left, r.top + 1.5f * dp, r.right, r.bottom + 1.5f * dp, rad, rad, shadowPaint)
            keyPaint.color = shade(face, 0.70f)
            canvas.drawRoundRect(r, rad, rad, keyPaint)
            val top = r.top + if (isPressed) 3 * dp else 0f
            faceRect.set(r.left + 1.5f * dp, top + 0.5f * dp, r.right - 1.5f * dp, r.bottom - depth)
            keyPaint.shader = LinearGradient(0f, faceRect.top, 0f, faceRect.bottom,
                shade(if (isPressed) shade(face, 0.9f) else face, 1.06f), shade(if (isPressed) shade(face, 0.9f) else face, 0.93f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(faceRect, rad * 0.8f, rad * 0.8f, keyPaint)
            keyPaint.shader = null
            lr = faceRect
        } else {
            val color = if (isPressed) t.keyPressed else faceColor(k)
            if (!t.borderless || isPressed || accentKey) {
                if (t.shadow) {
                    val mintRaised = t.id == "mint_vibrant"
                    shadowPaint.color = if (mintRaised) 0x66004D42 else 0x33000000
                    val shadowOffset = if (mintRaised) (if (isPressed) 1f else 3f) * dp else dp
                    canvas.drawRoundRect(r.left, r.top + shadowOffset, r.right, r.bottom + shadowOffset, radius, radius, shadowPaint)
                }
                keyPaint.color = color
                canvas.drawRoundRect(r, radius, radius, keyPaint)
            }
            lr = r
        }
        val fg = when {
            t.id == "retro_3d_compose" && k.type == KType.ENTER && t.enterKeyColor != null -> Color.WHITE
            accentKey -> t.accentText
            t.id == "retro_3d_compose" && k.type == KType.SHIFT && t.shiftKeyColor != null -> Color.WHITE
            k.type == KType.SHIFT && shiftState == 2 -> t.accentText
            t.id == "retro_3d_compose" && k.punct -> 0xFFFFFFFF.toInt()
            t.keycap && (k.type == KType.EMOJI || k.punct) -> t.text
            k.isFunctional -> t.textFn
            else -> t.text
        }
        val hint = k.hint ?: k.alts.firstOrNull()?.takeIf { t.keycap || it.first().isDigit() || k.punct }
        when (k.type) {
            KType.DEL -> drawIcon(canvas, R.drawable.ic_backspace, lr, fg)
            KType.SHIFT -> drawIcon(canvas, when (shiftState) { 0 -> R.drawable.ic_shift; 1 -> R.drawable.ic_shift_on; else -> R.drawable.ic_capslock }, lr, fg)
            KType.ENTER -> drawIcon(canvas, enterIcon, lr, fg, 0.60f)
            KType.LANG -> drawIcon(canvas, R.drawable.ic_globe, lr,
                if (t.keycap) 0xFF202124.toInt() else fg,
                if (t.keycap) 0.74f else 0.56f)
            KType.EMOJI -> drawIcon(canvas, R.drawable.ic_emoji, lr, fg, if (t.keycap) 0.55f else 0.42f)
            KType.SPACE -> {
                val c = if (t.keycap) (fg and 0x00FFFFFF) or 0xAA000000.toInt() else (fg and 0x00FFFFFF) or 0x99000000.toInt()
                val spaceLabel = if (t.id == "retro_3d_compose" || t.id == "mint_vibrant") "‹  ${k.label}  ›" else if (t.keycap && k.label.isNotBlank()) "◂  ${k.label}  ▸" else k.label
                drawText(canvas, spaceLabel, lr, c, if (t.keycap) 15f else 12f, false)
            }
            KType.SYM, KType.SYM2, KType.ABC -> drawText(canvas, k.label, lr, fg, if (t.keycap) 16f else 14f, !t.keycap)
            KType.CHAR -> {
                val label = displayLabel(k)
                val boldLabel = boldKeys
                if (t.keycap && hint != null) {
                    hintPaint.color = (fg and 0x00FFFFFF) or 0x99000000.toInt()
                    hintPaint.textSize = 10.5f * dp
                    canvas.drawText(hint, lr.centerX(), lr.top + 12 * dp, hintPaint)
                    val shifted = RectF(lr.left, lr.top + 6 * dp, lr.right, lr.bottom + 2 * dp)
                    drawText(canvas, label, if (t.id == "retro_3d_compose") lr else shifted, fg, if (label.length > 1) 18f else 23f, boldLabel)
                } else {
                    drawText(canvas, label, lr, fg, if (label.length > 1) 18f else 22f, boldLabel)
                    if (hint != null) {
                        hintPaint.color = (fg and 0x00FFFFFF) or 0x88000000.toInt()
                        hintPaint.textSize = 10 * dp
                        canvas.drawText(hint, lr.right - 7 * dp, lr.top + 12 * dp, hintPaint)
                    }
                }
            }
        }
    }

    private fun drawText(canvas: Canvas, s: String, r: RectF, color: Int, sizeSp: Float, bold: Boolean) {
        textPaint.color = color
        textPaint.textSize = sizeSp * dp
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(s, r.centerX(), y, textPaint)
    }

    private fun drawIcon(canvas: Canvas, res: Int, r: RectF, color: Int, scale: Float = 0.46f) {
        val d = icon(res)
        val s = (min(r.width(), r.height()) * scale).toInt()
        val cx = r.centerX().toInt(); val cy = r.centerY().toInt()
        d.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        d.setTint(color)
        d.draw(canvas)
    }

    private fun drawPreview(canvas: Canvas, k: Key) {
        val r = k.rect
        val w = max(r.width() * 1.25f, 44 * dp)
        val hh = r.height() * 1.15f
        val left = (r.centerX() - w / 2).coerceIn(0f, max(0f, width - w))
        val bottom = r.top + r.height() * 0.15f
        val rect = RectF(left, bottom - hh, left + w, bottom)
        shadowPaint.color = 0x55000000
        canvas.drawRoundRect(rect.left, rect.top + 2 * dp, rect.right, rect.bottom + 2 * dp, radius, radius, shadowPaint)
        keyPaint.color = (if (theme.keycap) theme.key else theme.keyPressed) or 0xFF000000.toInt()
        canvas.drawRoundRect(rect, radius, radius, keyPaint)
        drawText(canvas, displayLabel(k), rect, theme.text, 28f, false)
    }

    private fun drawPopup(canvas: Canvas) {
        shadowPaint.color = 0x66000000
        canvas.drawRoundRect(popupRect.left, popupRect.top + 3 * dp, popupRect.right, popupRect.bottom + 3 * dp, radius, radius, shadowPaint)
        keyPaint.color = (if (theme.keycap) theme.key else theme.keyFn) or 0xFF000000.toInt()
        canvas.drawRoundRect(popupRect, radius, radius, keyPaint)
        val cw = popupRect.width() / popupColumns
        val ch = popupRect.height() / popupRows
        popupItems.forEachIndexed { i, s ->
            val row = i / popupColumns
            val col = i % popupColumns
            val cell = RectF(popupRect.left + col * cw, popupRect.top + row * ch,
                popupRect.left + (col + 1) * cw, popupRect.top + (row + 1) * ch)
            if (i == popupSel) {
                keyPaint.color = theme.accent
                canvas.drawRoundRect(cell.left + 3 * dp, cell.top + 3 * dp, cell.right - 3 * dp, cell.bottom - 3 * dp, radius, radius, keyPaint)
            }
            val isArabicMark = s.firstOrNull()?.let { ch ->
                Character.getType(ch) in setOf(
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(),
                )
            } == true
            val label = when {
                isArabicMark -> "◌$s"
                lettersUpper && (shiftState > 0 || theme.capsLabels) -> s.uppercase()
                else -> s
            }
            drawText(canvas, label, cell, if (i == popupSel) theme.accentText else theme.text, 20f, false)
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (row in rows) for (k in row) {
            val r = k.rect
            if (x >= r.left - gap / 2 && x <= r.right + gap / 2 && y >= r.top - gap / 2 && y <= r.bottom + gap / 2) return k
            val dx = x - r.centerX(); val dy = y - r.centerY()
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    private fun redraw() { invalidate(); (parent as? View)?.invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_POINTER_DOWN -> { up(); down(e.getX(idx), e.getY(idx)) }
            MotionEvent.ACTION_MOVE -> move(e.getX(e.pointerCount - 1), e.getY(e.pointerCount - 1))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun down(x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        pressed = k; downX = x; lastSwipeX = x; spaceSwiping = false; longPressed = false
        listener.onKeyFeedback()
        if (k.type == KType.DEL) {
            listener.onSpecialKey(k)
            h.postDelayed(repeatRunnable, 380)
        } else if (k.alts.isNotEmpty() || k.type == KType.SPACE) {
            h.postDelayed(longPressRunnable, 320)
        }
        redraw()
    }

    private fun move(x: Float, y: Float) {
        val k = pressed ?: return
        if (popupKey != null) {
            val cw = popupRect.width() / popupColumns
            val ch = popupRect.height() / popupRows
            val col = ((x - popupRect.left) / cw).toInt().coerceIn(0, popupColumns - 1)
            val row = ((y - popupRect.top) / ch).toInt().coerceIn(0, popupRows - 1)
            val sel = (row * popupColumns + col).coerceIn(0, popupItems.size - 1)
            if (sel != popupSel) { popupSel = sel; redraw() }
            return
        }
        if (k.type == KType.SPACE) {
            val step = 14 * dp
            if (!spaceSwiping && abs(x - downX) > step) { spaceSwiping = true; h.removeCallbacks(longPressRunnable) }
            if (spaceSwiping) {
                val n = ((x - lastSwipeX) / step).toInt()
                if (n != 0) { listener.onCursorMove(n); lastSwipeX += n * step }
            }
            return
        }
        if (k.type == KType.DEL || longPressed) return
        val nk = keyAt(x, y)
        if (nk != null && nk !== k && nk.type == KType.CHAR && k.type == KType.CHAR) {
            h.removeCallbacks(longPressRunnable)
            pressed = nk
            if (nk.alts.isNotEmpty()) h.postDelayed(longPressRunnable, 320)
            redraw()
        }
    }

    private fun up() {
        h.removeCallbacks(longPressRunnable); h.removeCallbacks(repeatRunnable)
        val k = pressed
        if (k != null) {
            if (popupKey != null) {
                listener.onKeyText(popupItems[popupSel])
            } else if (!longPressed && !spaceSwiping && k.type != KType.DEL) {
                if (k.type == KType.CHAR) listener.onKeyText(k.label) else listener.onSpecialKey(k)
            }
        }
        pressed = null; popupKey = null; spaceSwiping = false; longPressed = false
        redraw()
    }

    private fun cancel() {
        h.removeCallbacks(longPressRunnable); h.removeCallbacks(repeatRunnable)
        pressed = null; popupKey = null; spaceSwiping = false
        redraw()
    }

    private fun onLongPress() {
        val k = pressed ?: return
        longPressed = true
        if (k.type == KType.SPACE) { listener.onSpaceLongPress(); redraw(); return }
        listener.onKeyFeedback()
        popupItems = k.alts
        popupKey = k
        popupSel = 0
        val maxColumns = max(1, ((width - 4 * dp) / (36 * dp)).toInt())
        popupColumns = min(popupItems.size, maxColumns)
        popupRows = (popupItems.size + popupColumns - 1) / popupColumns
        val cw = min(max(k.rect.width(), 36 * dp), (width - 4 * dp) / popupColumns)
        val w = cw * popupColumns
        val hh = k.rect.height()
        val left = (k.rect.centerX() - cw / 2).coerceIn(2 * dp, max(2 * dp, width - w - 2 * dp))
        val bottom = k.rect.top - 4 * dp
        popupRect.set(left, bottom - hh * popupRows, left + w, bottom)
        redraw()
    }
}
