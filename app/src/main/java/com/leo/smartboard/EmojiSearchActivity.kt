package com.leo.smartboard

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View

/** Search runs in a normal Activity so the active IME can type into a real editor. */
class EmojiSearchActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var adapter: BaseAdapter
    private val allEmoji by lazy { EmojiSearch.allSearchableEmojis() }
    private var results: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = Themes.current(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bgTop)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 26f
            setTextColor(theme.textFn)
            gravity = Gravity.CENTER
            background = rounded(theme.keyFn, dp(20).toFloat())
            contentDescription = "إغلاق البحث عن الإيموجي"
            setOnClickListener { finish() }
        }
        toolbar.addView(close, LinearLayout.LayoutParams(dp(38), dp(40)))

        val categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val categories = listOf(
            "🕘" to "سعيد",
            "😊" to "سعيد",
            "🐶" to "حيوان",
            "🍎" to "طعام",
            "⚽" to "رياضة",
            "✈️" to "طائرة",
            "💡" to "هاتف",
            "♻️" to "رمز",
            "🏳️" to "علم"
        )
        categories.forEach { (icon, query) ->
            categoryBar.addView(TextView(this).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                contentDescription = "فئة $query"
                setOnClickListener {
                    search.setText(query)
                    search.setSelection(search.text.length)
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        toolbar.addView(categoryBar, LinearLayout.LayoutParams(dp(184), dp(40)))

        search = EditText(this).apply {
            hint = "بحث…"
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(theme.text)
            setHintTextColor((theme.text and 0x00FFFFFF) or 0x88000000.toInt())
            background = rounded(theme.key, dp(20).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateResults(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(search, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(6)
        })
        panel.addView(toolbar, LinearLayout.LayoutParams(-1, dp(44)))

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 7
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = rounded(theme.keyPressed, dp(8).toFloat())
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToPadding = false
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = results.size
            override fun getItem(position: Int) = results[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View =
                ((convertView as? TextView) ?: TextView(this@EmojiSearchActivity).apply {
                    textSize = 28f
                    gravity = Gravity.CENTER
                    background = rounded(theme.key, dp(8).toFloat())
                    layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(48))
                }).apply { text = results[position] }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            EmojiSearchResult.store(this, results[position])
            finish()
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            text = "لا توجد نتائج"
            textSize = 15f
            setTextColor(theme.textFn)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        panel.addView(body, LinearLayout.LayoutParams(-1, dp(108)))
        val overlay = FrameLayout(this).apply { setBackgroundColor(0x00000000) }
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(overlay)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        search.requestFocus()
        search.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
        updateResults("")
    }

    private lateinit var search: EditText

    private fun updateResults(query: String) {
        results = if (query.isBlank()) allEmoji.take(14) else EmojiSearch.search(query, allEmoji)
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (::empty.isInitialized) empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
    }
}

/** One-shot handoff from the search Activity back to the original text editor. */
object EmojiSearchResult {
    private const val PREFS = "emoji_search_handoff"
    private const val KEY = "pending_emoji"

    fun store(context: Context, emoji: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, emoji).commit()
    }

    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY, null) ?: return null
        prefs.edit().remove(KEY).commit()
        return value
    }
}
