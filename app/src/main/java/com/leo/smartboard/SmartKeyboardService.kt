package com.leo.smartboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The keyboard itself (an Android Input Method).
 * - Arabic / English / symbols layouts, Gboard-like interactions
 * - Suggestion strip powered by [PredictionEngine] that learns from typing, copying and pasting
 * - Clipboard history (up to 3000 entries) with pinning
 * - Themes and emoji panel
 */
class SmartKeyboardService : InputMethodService(), KeyboardView.Listener, PanelHost {

    private lateinit var prefs: Prefs
    private lateinit var engine: PredictionEngine
    private lateinit var clips: ClipboardRepo
    private var cm: ClipboardManager? = null

    private var kb: KeyboardView? = null
    private lateinit var content: FrameLayout
    private lateinit var sugRow: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private lateinit var menuBtn: ImageView
    private lateinit var clipChip: TextView
    private lateinit var calcResult: TextView
    private val sugViews = ArrayList<TextView>(3)
    private var panel: View? = null
    private var applicationInputConnection: InputConnection? = null

    private var mode = Mode.LETTERS
    private var lang = "ar"
    private var lastShiftTap = 0L
    private var lastSpaceTime = 0L
    private var incognito = false
    private var noSuggest = false
    private var editorActive = false
    private var forceTools = false
    private var autoSpaced = false
    private var lastClipText: String? = null
    private var lastClipTime = 0L
    private var lastClipLearnedAsPaste: String? = null
    private val calculatorBuffer = StringBuilder()

    private data class Sug(val text: String, val verbatim: Boolean)
    private var suggestions: List<Sug?> = emptyList()

    override val kbTheme: KbTheme get() = Themes.current(this)

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        engine = PredictionEngine.get(this)
        clips = ClipboardRepo.get(this)
        lang = prefs.lang
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        cm?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ view

    override fun onCreateInputView(): View = buildViewSafely()

    private fun buildViewSafely(): View {
        return try {
            buildView()
        } catch (error: Throwable) {
            // Never let an IME construction error make the keyboard disappear completely.
            Log.e(TAG, "Failed to create SmartBoard input view", error)
            // buildView() may have assigned a partially initialized keyboard before
            // failing; discard it so the next input session can rebuild cleanly.
            kb = null
            panel = null
            emergencyView()
        }
    }

    private fun emergencyView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Themes.current(this@SmartKeyboardService).bar)
        addView(TextView(this@SmartKeyboardService).apply {
            text = "لوحة ذكية"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Themes.current(this@SmartKeyboardService).barText)
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }, LinearLayout.LayoutParams(-1, dp(64)))
    }

    private fun buildView(): View {
        val t = kbTheme
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background = gradientBg(t.bar, t.bar)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // Suggestion strip / toolbar
        val suggestionTextColor = highContrastSuggestionText(t.bar, t.barText)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(t.bar)
            setPadding(dp(2), 0, dp(2), 0)
        }
        menuBtn = iconButton(R.drawable.ic_apps, t.barText, 44, 12) { forceTools = !forceTools; refreshBar() }
        bar.addView(menuBtn)
        calcResult = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(t.accentText)
            background = rounded(t.accent, dp(8).toFloat()); visibility = View.GONE
            setPadding(dp(5), 0, dp(5), 0)
        }
        bar.addView(calcResult, LinearLayout.LayoutParams(dp(54), dp(32)).apply { marginEnd = dp(3) })

        val stripHolder = FrameLayout(this)
        sugRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sugViews.clear()
        for (i in 0 until 3) {
            if (i > 0) sugRow.addView(View(this).apply {
                setBackgroundColor(Color.argb(110, Color.red(suggestionTextColor), Color.green(suggestionTextColor), Color.blue(suggestionTextColor)))
            }, LinearLayout.LayoutParams(dp(1), dp(22)))
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 16f; setTextColor(suggestionTextColor)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                isClickable = true
            }
            val idx = i
            tv.setOnClickListener { suggestions.getOrNull(idx)?.let { pickSuggestion(it) } }
            sugViews += tv
            sugRow.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolsRow.addView(iconButton(R.drawable.ic_clip, t.barText) { showPanel(ClipboardPanel(this, this)) })
        toolsRow.addView(iconButton(R.drawable.ic_emoji, t.barText) { showPanel(EmojiPanel(this, this)) })
        toolsRow.addView(iconButton(R.drawable.ic_palette, t.barText) { showPanel(ThemePanel(this, this)) })
        toolsRow.addView(iconButton(R.drawable.ic_text_tools, t.barText) { showPanel(TextToolsPanel(this, this)) })
        toolsRow.addView(iconButton(R.drawable.ic_settings, t.barText) { openSettings() })
        clipChip = TextView(this).apply {
            textSize = 13f; setTextColor(t.accentText); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            background = rounded(t.accent, dp(16).toFloat())
            setPadding(dp(12), 0, dp(12), 0); gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            setOnClickListener { lastClipText?.let { txt -> pasteText(txt); lastClipTime = 0; refreshBar() } }
        }
        toolsRow.addView(clipChip, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        stripHolder.addView(sugRow, FrameLayout.LayoutParams(-1, dp(44)))
        stripHolder.addView(toolsRow, FrameLayout.LayoutParams(-1, dp(44)))
        bar.addView(stripHolder, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))

        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val view = KeyboardView(this, this)
        view.theme = t
        view.keyHeightDp = prefs.keyHeightDp
        view.boldKeys = prefs.boldKeys
        kb = view
        content.addView(view, FrameLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        panel = null
        loadLayout()
        refreshBar()
        return root
    }

    private fun loadLayout() {
        val v = kb ?: return
        val referenceBottom = prefs.themeId == "retro_3d_compose" || prefs.themeId == "mint_vibrant"
        v.rows = Layouts.build(mode, lang, prefs.numberRow, referenceBottom)
        v.boldKeys = prefs.boldKeys
        v.lettersUpper = mode == Mode.LETTERS && lang == "en"
        if (!v.lettersUpper && v.shiftState != 0) v.shiftState = 0
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        classifyEditor(attribute)
        editorActive = true
        super.onStartInput(attribute, restarting)
        applicationInputConnection = currentInputConnection
    }

    private fun classifyEditor(info: EditorInfo): Int {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val searchAction = (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
        val password = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val privateVariation = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val noPersonalizedLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val noSuggestionsFlag = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val privateEditor = password ||
            noPersonalizedLearning || noSuggestionsFlag || privateVariation
        incognito = privateEditor || cls != InputType.TYPE_CLASS_TEXT
        // Search widgets may use URI/filter input variations or NO_SUGGESTIONS even
        // when they want the IME to provide local search-query completions. Keep
        // learning/clipboard capture disabled, but allow our local suggestions.
        noSuggest = password || noPersonalizedLearning || cls != InputType.TYPE_CLASS_TEXT ||
            ((noSuggestionsFlag || privateVariation) && !searchAction)
        return cls
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        val cls = classifyEditor(info)
        super.onStartInputView(info, restarting)
        applicationInputConnection = currentInputConnection
        mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) Mode.SYM1 else Mode.LETTERS
        lang = prefs.lang
        forceTools = false
        calculatorBuffer.setLength(0)
        autoSpaced = false
        val v = kb
        if (v == null) {
            // The framework can request a new input view after recreating the service.
            setInputView(buildViewSafely())
        } else {
            // Do not replace the IME root while Android is in the middle of showing it.
            // Updating the existing view avoids a window/measurement race on some devices.
            closePanel()
            v.theme = Themes.current(this)
            v.keyHeightDp = prefs.keyHeightDp
            v.boldKeys = prefs.boldKeys
            loadLayout()
        }
        kb?.shiftState = 0
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        val useNewline = shouldInsertNewline(info)
        kb?.enterIcon = if (useNewline) R.drawable.ic_enter else when (action) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_next
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done
            else -> R.drawable.ic_enter
        }.let { if (!hasAction && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) R.drawable.ic_enter else it }
        checkCurrentClip()
        updateAutoShift()
        updateSuggestions()
        EmojiSearchResult.take(this)?.let { emoji ->
            currentInputConnection?.commitText(emoji, 1)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (panel !is EmojiPanel) closePanel()
        if (finishingInput) {
            // Fail closed between editors until the next onStartInputView classifies it.
            editorActive = false
            incognito = true
            noSuggest = true
        }
    }

    override fun onFinishInput() {
        editorActive = false
        incognito = true
        noSuggest = true
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        detectSystemPaste(oldSelStart, newSelStart)
        updateAutoShift()
        updateSuggestions()
    }

    private fun isMultiLine(): Boolean {
        val it = currentInputEditorInfo?.inputType ?: return false
        return (it and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    }

    /** Mirrors Gboard's field-oriented behavior instead of identifying apps by package name. */
    private fun shouldInsertNewline(info: EditorInfo): Boolean {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_SEARCH) return false
        val multiline = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        return multiline || noEnterAction || action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
    }

    // ------------------------------------------------------------------ clipboard

    private fun onClipChanged() {
        if (incognito || !editorActive) return
        val clip = try { cm?.primaryClip } catch (e: Exception) { null } ?: return
        if (clip.itemCount == 0 || isSensitiveClip(clip)) return
        val text = try { clip.getItemAt(0).coerceToText(this)?.toString() } catch (_: Exception) { null } ?: return
        if (text.isBlank()) return
        if (text == lastClipText && System.currentTimeMillis() - lastClipTime < 2000) return
        if (prefs.saveClipboard) clips.add(text)
        if (prefs.learnClipboard) engine.learnText(text, 1)
        lastClipText = text
        lastClipTime = System.currentTimeMillis()
        (panel as? ClipboardPanel)?.reload()
        // Clipboard learning mutates the in-memory n-gram tables synchronously;
        // recompute suggestions now so the active field immediately reflects them.
        if (kb != null) updateSuggestions()
    }

    private fun checkCurrentClip() {
        if (incognito || !editorActive) return
        val clip = try { cm?.primaryClip } catch (e: Exception) { null } ?: return
        if (clip.itemCount == 0 || isSensitiveClip(clip)) return
        val text = try { clip.getItemAt(0).coerceToText(this)?.toString() } catch (_: Exception) { null } ?: return
        if (text.isNotBlank() && text != lastClipText) onClipChanged()
    }

    private fun isSensitiveClip(clip: android.content.ClipData): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        } catch (_: Exception) {
            true // Fail closed if sensitive clipboard metadata cannot be read safely.
        }
    }

    /** Learns from pastes done through the app's own menu (long-press → Paste), not only our panel. */
    private fun detectSystemPaste(oldStart: Int, newStart: Int) {
        val clip = lastClipText ?: return
        if (clip.length < 2 || newStart - oldStart != clip.length || clip == lastClipLearnedAsPaste) return
        val before = currentInputConnection?.getTextBeforeCursor(clip.length, 0)?.toString() ?: return
        if (before == clip) {
            lastClipLearnedAsPaste = clip
            if (!incognito && prefs.learnClipboard) engine.learnText(clip, 2)
        }
    }

    private fun pasteText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        lastClipLearnedAsPaste = text
        if (!incognito && prefs.learnClipboard) engine.learnText(text, 2)
    }

    override fun pasteClip(clip: Clip) {
        pasteText(clip.text)
        clips.markUsed(clip.id)
        closePanel()
    }

    // ------------------------------------------------------------------ panels

    private fun showPanel(v: View) {
        val k = kb ?: return
        closePanel()
        val h = k.preferredHeight(maxOf(k.rows.size, 4)) + if (v is EmojiPanel || v is SymbolPanel) dp(46) else 0
        content.addView(v, FrameLayout.LayoutParams(-1, h))
        k.visibility = View.INVISIBLE
        panel = v
    }

    override fun closePanel() {
        panel?.let { content.removeView(it) }
        panel = null
        kb?.visibility = View.VISIBLE
    }

    override fun applyTheme(id: String) {
        prefs.themeId = id
        setInputView(buildView())
        showPanel(ThemePanel(this, this))
    }

    override fun commitEmoji(e: String) {
        (applicationInputConnection ?: currentInputConnection)?.commitText(e, 1)
    }

    override fun deleteBackward() {
        currentInputConnection?.let { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
    }

    override fun selectAllText() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll); closePanel() }
    override fun copyText() { currentInputConnection?.performContextMenuAction(android.R.id.copy); closePanel() }
    override fun cutText() { currentInputConnection?.performContextMenuAction(android.R.id.cut); closePanel() }
    override fun pasteFromClipboard() { currentInputConnection?.performContextMenuAction(android.R.id.paste); closePanel() }
    override fun switchKeyboardMode(mode: Mode) {
        this.mode = mode
        calculatorBuffer.setLength(0)
        calcResult.visibility = View.GONE
        loadLayout()
        closePanel()
        if (mode == Mode.LETTERS) updateAutoShift()
        updateSuggestions()
    }
    override fun moveCursor(keyCode: Int) {
        if (currentInputConnection == null) return
        if (keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            sendDownUpKeyEvents(keyCode)
        }
    }

    override fun openEmojiSearch() {
        startActivity(Intent(this, EmojiSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyFeedback() {
        val v = kb ?: return
        if (prefs.vibrate) {
            val vib = getSystemService(Vibrator::class.java)
            if (vib?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= 29) vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                else vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (prefs.sound) (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    override fun onKeyText(text: String) {
        val ic = currentInputConnection ?: return
        val v = kb ?: return
        if (mode == Mode.SYM1 && handleCalculatorKey(text, ic)) return
        val out = if (v.lettersUpper && v.shiftState > 0) text.uppercase() else text
        val isPunct = out.length == 1 && out[0] in ".,!?،؟:;؛"
        if (isPunct) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            val (p2, p1, word) = parseContext(before)
            val expansion = expansionFor(before)
            if (expansion != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expansion.trigger.length, 0)
                ic.commitText(expansion.replacement + out, 1)
                ic.endBatchEdit()
            } else {
                if (word.isNotEmpty()) learn(p2, p1, word)
                if (autoSpaced && before.endsWith(" ")) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText("$out ", 1)
                } else ic.commitText(out, 1)
            }
        } else {
            ic.commitText(out, 1)
        }
        autoSpaced = false
        if (v.shiftState == 1) v.shiftState = 0
        if (mode != Mode.LETTERS && out == "'") { mode = Mode.LETTERS; loadLayout() }
    }

    private fun handleCalculatorKey(text: String, ic: android.view.inputmethod.InputConnection): Boolean {
        val allowed = text.all { it.isDigit() || it in "+-×÷*/().%" }
        if (allowed) {
            calculatorBuffer.append(text); ic.commitText(text, 1); calcResult.visibility = View.GONE
            return true
        }
        if (text != "=") return false
        val result = evaluateExpression(calculatorBuffer.toString()) ?: return true
        if (calculatorBuffer.isNotEmpty()) ic.deleteSurroundingText(calculatorBuffer.length, 0)
        ic.commitText(result, 1); calculatorBuffer.setLength(0)
        calcResult.text = result; calcResult.visibility = View.VISIBLE
        return true
    }

    private fun evaluateExpression(raw: String): String? {
        return try {
        val s = raw.replace('×', '*').replace('÷', '/').replace(" ", "").map { ch ->
            when (ch) { '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'; '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'; else -> ch }
        }.joinToString("")
        if (s.isBlank() || !s.matches(Regex("[0-9+\\-*/().%]+"))) return null
        class Parser(private val input: String) {
            var p = 0
            fun parse(): Double { val n = expr(); if (p != input.length) error("tail"); return n }
            private fun expr(): Double { var x=term(); while(p<input.length && (input[p]=='+'||input[p]=='-')) { val o=input[p++]; val y=term(); x=if(o=='+') x+y else x-y }; return x }
            private fun term(): Double { var x=factor(); while(p<input.length && (input[p]=='*'||input[p]=='/')) { val o=input[p++]; val y=factor(); if(o=='/'&&y==0.0) error("zero"); x=if(o=='*') x*y else x/y }; return x }
            private fun factor(): Double { if(p<input.length&&input[p]=='-'){p++;return -factor()}; if(p<input.length&&input[p]=='('){p++;val x=expr();if(p>=input.length||input[p++]!=')')error("paren");return x}; val st=p; while(p<input.length&&(input[p].isDigit()||input[p]=='.'))p++; if(st==p)error("number"); val n=input.substring(st,p).toDouble(); return if(p<input.length&&input[p]=='%'){p++;n/100.0}else n }
        }
        val n = Parser(s).parse()
        if (!n.isFinite()) null else if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.8f", n).trimEnd('0').trimEnd('.')
        } catch (_: Exception) { null }
    }

    override fun onSpecialKey(key: Key) {
        val ic = currentInputConnection
        val v = kb ?: return
        when (key.type) {
            KType.DEL -> {
                if (ic == null) return
                autoSpaced = false
                if (mode == Mode.SYM1 && calculatorBuffer.isNotEmpty()) calculatorBuffer.setLength(calculatorBuffer.length - 1)
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            KType.SHIFT -> {
                val now = System.currentTimeMillis()
                v.shiftState = when {
                    v.shiftState == 2 -> 0
                    now - lastShiftTap < 350 -> 2
                    v.shiftState == 1 -> 0
                    else -> 1
                }
                lastShiftTap = now
                updateSuggestions()
            }
            KType.SPACE -> onSpace()
            KType.ENTER -> onEnter()
            KType.SYM -> { mode = Mode.SYM1; calculatorBuffer.setLength(0); calcResult.visibility = View.GONE; loadLayout() }
            KType.SYM2 -> showPanel(SymbolPanel(this, this))
            KType.ABC -> { mode = Mode.LETTERS; calculatorBuffer.setLength(0); calcResult.visibility = View.GONE; loadLayout(); updateAutoShift() }
            KType.LANG -> {
                lang = if (lang == "ar") "en" else "ar"
                prefs.lang = lang
                mode = Mode.LETTERS
                loadLayout(); updateAutoShift(); updateSuggestions()
            }
            KType.EMOJI -> showPanel(EmojiPanel(this, this))
            KType.CHAR -> onKeyText(key.label)
        }
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val (p2, p1, word) = parseContext(before)
        val emojiImmediatelyBeforeCursor = endsWithEmoji(before)
        val expansion = expansionFor(before)
        if (expansion != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(expansion.trigger.length, 0)
            ic.commitText(expansion.replacement, 1)
            if (expansion.replacement.lastOrNull()?.isWhitespace() != true) ic.commitText(" ", 1)
            ic.endBatchEdit()
            autoSpaced = false
            lastSpaceTime = 0
            if (mode != Mode.LETTERS) { mode = Mode.LETTERS; loadLayout() }
            updateSuggestions()
            return
        }
        if (word.isNotEmpty() && !emojiImmediatelyBeforeCursor) {
            var finalWord = word
            val correction = if (!noSuggest) engine.correction(p1, word) else null
            if (prefs.autoCorrect) {
                correction?.let { fix ->
                    ic.deleteSurroundingText(word.length, 0); ic.commitText(fix, 1); finalWord = fix
                }
            }
            // Never reinforce a misspelling. If autocorrect is off, keep the
            // user's visible text but learn only the verified correction.
            learn(p2, p1, correction ?: finalWord)
        }
        val doublePeriod = prefs.doubleSpacePeriod && now - lastSpaceTime < 700 && before.length >= 2 &&
            before.endsWith(" ") && PredictionEngine.isWordChar(before[before.length - 2])
        if (doublePeriod) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTime = 0
        } else {
            ic.commitText(" ", 1)
            lastSpaceTime = now
        }
        autoSpaced = false
        if (mode != Mode.LETTERS) { mode = Mode.LETTERS; loadLayout() }
        // Some editors do not send onUpdateSelection for commitText(" "); refresh directly.
        updateSuggestions()
    }

    /** Emoji variation selectors/skin-tone/joiner components must never be spell-corrected as text. */
    private fun endsWithEmoji(text: String): Boolean {
        if (text.isEmpty()) return false
        var offset = text.length
        var codePoint = text.codePointBefore(offset)
        while (codePoint == 0xFE0F || codePoint == 0xFE0E || codePoint == 0x200D ||
            codePoint == 0x20E3 || codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xE0020..0xE007F
        ) {
            offset -= Character.charCount(codePoint)
            if (offset <= 0) return false
            codePoint = text.codePointBefore(offset)
        }
        val type = Character.getType(codePoint)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.MODIFIER_SYMBOL.toInt()
    }

    private fun expansionFor(before: String): TextExpansionRepo.Entry? {
        if (before.isBlank() || incognito || noSuggest) return null
        return TextExpansionRepo.get(this).matchBeforeCursor(before)
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val (p2, p1, word) = parseContext(before)
        if (word.isNotEmpty()) {
            val correction = if (!noSuggest) engine.correction(p1, word) else null
            learn(p2, p1, correction ?: word)
        }
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (action == EditorInfo.IME_ACTION_SEARCH) {
            ic.performEditorAction(action)
        } else if (info != null && shouldInsertNewline(info)) {
            ic.commitText("\n", 1)
        } else if (hasAction) {
            ic.performEditorAction(action)
        } else {
            // A key event lets single-line search widgets handle Enter even if they
            // did not publish a standard IME action; inserting a newline cannot.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoSpaced = false
    }

    override fun onCursorMove(steps: Int) {
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    /** Long-press on the space bar switches Arabic / English. */
    override fun onSpaceLongPress() {
        onSpecialKey(Key("lang", KType.LANG))
    }

    private fun updateAutoShift() {
        val v = kb ?: return
        if (!v.lettersUpper || v.shiftState == 2) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = prefs.autoCap && ic.getCursorCapsMode(info.inputType) != 0
        val want = if (caps) 1 else 0
        if (v.shiftState != want) v.shiftState = want
    }

    // ------------------------------------------------------------------ predictions

    private fun learn(p2: String?, p1: String?, word: String) {
        if (!incognito && prefs.learnTyping) engine.learnTyped(p2, p1, word)
    }

    /** Splits the text before the cursor into (word-2, word-1, current partial word) within the current sentence. */
    private fun parseContext(before: String): Triple<String?, String?, String> {
        var i = before.length
        while (i > 0 && PredictionEngine.isWordChar(before[i - 1])) i--
        val prefix = before.substring(i)
        var rest = before.substring(0, i)
        val cut = rest.indexOfLast(PredictionEngine::isSentenceBoundary)
        if (cut >= 0) rest = rest.substring(cut + 1)
        val toks = PredictionEngine.tokenize(rest)
        return Triple(toks.getOrNull(toks.size - 2), toks.lastOrNull(), prefix)
    }

    private fun updateSuggestions() {
        if (kb == null) return
        val ic = currentInputConnection
        if (noSuggest || ic == null || mode != Mode.LETTERS) { suggestions = emptyList(); refreshBar(); return }
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val (p2, p1, prefix) = parseContext(before)
        if (prefix.isEmpty() && before.isNotEmpty() && !before.last().isWhitespace()) { suggestions = emptyList(); refreshBar(); return }
        val desiredGroup = if (lang == "en") 2 else 1
        var list = engine.suggest(p2, p1, prefix, 8)
            .filter { PredictionEngine.languageGroup(it) == desiredGroup }
            .take(3)
        val spellingFix = if (prefix.length >= 3 && !noSuggest) engine.correction(p1, prefix) else null
        val v = kb
        if (v != null && v.lettersUpper && v.shiftState > 0 && prefix.isEmpty()) list = list.map { it.replaceFirstChar { c -> c.uppercaseChar() } }
        suggestions = if (prefix.isNotEmpty()) {
            if (spellingFix != null) {
                val rest = list.filter { PredictionEngine.canon(it) != PredictionEngine.canon(spellingFix) &&
                    PredictionEngine.canon(it) != PredictionEngine.canon(prefix) }
                listOf(Sug(spellingFix, false), Sug(prefix, true), rest.firstOrNull()?.let { Sug(it, false) })
            } else {
            val known = list.any { PredictionEngine.canon(it) == PredictionEngine.canon(prefix) }
            if (!known) listOf(Sug(prefix, true), list.getOrNull(0)?.let { Sug(it, false) }, list.getOrNull(1)?.let { Sug(it, false) })
            else {
                val ordered = listOf(prefix) + list.filter { PredictionEngine.canon(it) != PredictionEngine.canon(prefix) }
                // best in the middle, like Gboard
                listOf(ordered.getOrNull(1)?.let { Sug(it, false) }, Sug(list[0].let { if (PredictionEngine.canon(it) == PredictionEngine.canon(prefix)) prefix else it }, false), ordered.getOrNull(2)?.let { Sug(it, false) })
            }
            }
        } else if (list.isNotEmpty()) {
            listOf(list.getOrNull(1)?.let { Sug(it, false) }, Sug(list[0], false), list.getOrNull(2)?.let { Sug(it, false) })
        } else emptyList()
        refreshBar()
    }

    private fun pickSuggestion(s: Sug) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val (p2, p1, prefix) = parseContext(before)
        ic.beginBatchEdit()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText(s.text + " ", 1)
        ic.endBatchEdit()
        // Picking a word (even the verbatim one) teaches it, with extra weight for verbatim new words.
        learn(p2, p1, s.text)
        if (s.verbatim) learn(p2, p1, s.text)
        autoSpaced = true
        kb?.let { if (it.shiftState == 1) it.shiftState = 0 }
        updateSuggestions()
    }

    private fun refreshBar() {
        if (!::sugRow.isInitialized) return
        val t = kbTheme
        val hasSug = suggestions.any { it != null }
        val recentClip = lastClipText != null && System.currentTimeMillis() - lastClipTime < 90_000 && !noSuggest
        val showSug = hasSug && !forceTools
        sugRow.visibility = if (showSug) View.VISIBLE else View.GONE
        toolsRow.visibility = if (showSug) View.GONE else View.VISIBLE
        menuBtn.setImageResource(if (forceTools) R.drawable.ic_back else R.drawable.ic_apps)
        clipChip.visibility = if (recentClip) View.VISIBLE else View.INVISIBLE
        if (recentClip) clipChip.text = "📋 " + lastClipText!!.replace('\n', ' ').trim().take(40)
        for (i in 0 until 3) {
            val s = suggestions.getOrNull(i)
            val tv = sugViews[i]
            tv.text = when {
                s == null -> ""
                s.verbatim -> "«${s.text}»"
                else -> s.text
            }
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextColor(highContrastSuggestionText(t.bar, t.barText))
        }
    }

    private fun highContrastSuggestionText(background: Int, preferred: Int): Int {
        val preferredOpaque = Color.rgb(Color.red(preferred), Color.green(preferred), Color.blue(preferred))
        if (contrastRatio(background, preferredOpaque) >= 4.5) return preferredOpaque
        val black = Color.BLACK
        val white = Color.WHITE
        return if (contrastRatio(background, black) >= contrastRatio(background, white)) black else white
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val s = value / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
        }
        val l1 = luminance(first)
        val l2 = luminance(second)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (Android 13+): password managers mark copied secrets with it. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val TAG = "SmartBoardIME"
    }
}
