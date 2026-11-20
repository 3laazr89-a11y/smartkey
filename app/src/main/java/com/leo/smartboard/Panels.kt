package com.leo.smartboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface PanelHost {
    val kbTheme: KbTheme
    fun closePanel()
    fun pasteClip(clip: Clip)
    fun applyTheme(id: String)
    fun commitEmoji(e: String)
    fun deleteBackward()
    fun selectAllText()
    fun copyText()
    fun cutText()
    fun pasteFromClipboard()
    fun moveCursor(keyCode: Int)
    fun switchKeyboardMode(mode: Mode)
    fun openEmojiSearch()
}

private fun Context.panelHeader(host: PanelHost, title: TextView, vararg actions: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(4), 0, dp(4), 0)
        addView(iconButton(R.drawable.ic_back, host.kbTheme.barText) { host.closePanel() }.apply { rotation = 180f })
        addView(title, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        actions.forEach { addView(it) }
    }

/** Clipboard history panel: tap to paste, long-press to pin / delete. Holds up to 3000 entries. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(ctx: Context, private val host: PanelHost) : LinearLayout(ctx) {
    private val repo = ClipboardRepo.get(ctx)
    private val t = host.kbTheme
    private var items: List<Clip> = emptyList()
    private var pinnedOnly = false
    private var actionPos = -1
    private var confirmClear = 0L
    private val title = context.label("", t.barText, 15f, true)
    private val empty = context.label("الحافظة فارغة\nانسخ أي نص وسيظهر هنا تلقائياً", t.textFn, 14f).apply { gravity = Gravity.CENTER }
    private val grid = GridView(ctx)
    private val pinBtn = context.iconButton(R.drawable.ic_pin, t.barText) { pinnedOnly = !pinnedOnly; reload() }
    private val clearBtn = context.iconButton(R.drawable.ic_delete, t.barText) { clearAll() }
    private val fmt = SimpleDateFormat("d MMM، HH:mm", Locale.forLanguageTag("ar"))

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id
        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View = cell(pos)
    }

    init {
        orientation = VERTICAL
        background = gradientBg(t.bgTop, t.bgBottom)
        addView(context.panelHeader(host, title, pinBtn, clearBtn))
        val body = FrameLayout(ctx)
        grid.numColumns = 2
        grid.horizontalSpacing = context.dp(6); grid.verticalSpacing = context.dp(6)
        grid.setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(8))
        grid.clipToPadding = false
        grid.selector = rounded(0, 0f)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (actionPos >= 0) { actionPos = -1; adapter.notifyDataSetChanged() } else host.pasteClip(items[pos])
        }
        grid.setOnItemLongClickListener { _, _, pos, _ -> actionPos = pos; adapter.notifyDataSetChanged(); true }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
        reload()
    }

    fun reload() {
        items = repo.list(pinnedOnly = pinnedOnly)
        actionPos = -1
        title.text = (if (pinnedOnly) "المثبّتة" else "الحافظة") + "  ${repo.count()}/${ClipboardRepo.MAX}"
        pinBtn.setColorFilter(if (pinnedOnly) t.accent else t.barText)
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        adapter.notifyDataSetChanged()
    }

    private fun clearAll() {
        val now = System.currentTimeMillis()
        if (now - confirmClear < 3000) { repo.clearUnpinned(); confirmClear = 0; reload() }
        else { confirmClear = now; title.text = "اضغط مرة أخرى لحذف غير المثبّت" }
    }

    private fun cell(pos: Int): View {
        val c = items[pos]
        val ctx = context
        val card = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(6))
            background = rounded(if (t.borderless) t.keyPressed else t.key, ctx.dp(10).toFloat())
            minimumHeight = ctx.dp(76)
        }
        if (pos == actionPos) {
            card.gravity = Gravity.CENTER
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(ctx.iconButton(R.drawable.ic_pin, if (c.pinned) t.accent else t.text) { repo.setPinned(c.id, !c.pinned); reload() })
            row.addView(ctx.iconButton(R.drawable.ic_delete, t.text) { repo.delete(c.id); reload() })
            card.addView(row)
            card.addView(ctx.label(if (c.pinned) "إلغاء التثبيت • حذف" else "تثبيت • حذف", t.text, 11f).apply { gravity = Gravity.CENTER })
            return card
        }
        card.addView(TextView(ctx).apply {
            text = c.text.trim(); setTextColor(t.text); textSize = 13.5f
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        val meta = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (c.pinned) meta.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_pin); setColorFilter(t.accent)
            layoutParams = LayoutParams(ctx.dp(14), ctx.dp(14)).apply { marginEnd = ctx.dp(4) }
        })
        meta.addView(ctx.label(fmt.format(Date(c.ts)), (t.text and 0x00FFFFFF) or 0x88000000.toInt(), 10f))
        card.addView(meta, LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
        return card
    }
}

/** Theme picker panel shown inside the keyboard. */
@SuppressLint("ViewConstructor")
class ThemePanel(ctx: Context, private val host: PanelHost) : LinearLayout(ctx) {
    init {
        val t = host.kbTheme
        orientation = VERTICAL
        background = gradientBg(t.bgTop, t.bgBottom)
        addView(context.panelHeader(host, context.label("الثيمات", t.barText, 15f, true)))
        val grid = GridView(ctx).apply {
            numColumns = 3
            horizontalSpacing = context.dp(10); verticalSpacing = context.dp(10)
            setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(12))
            clipToPadding = false
            selector = rounded(0, 0f)
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = Themes.all.size
            override fun getItem(p: Int) = Themes.all[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val th = Themes.all[p]
                return LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(ThemePreview(context, th, th.id == t.id))
                    addView(context.label(th.name, t.barText, 12f).apply { gravity = Gravity.CENTER }, LayoutParams(-1, context.dp(22)))
                }
            }
        }
        grid.setOnItemClickListener { _, _, p, _ -> host.applyTheme(Themes.all[p].id) }
        addView(grid, LayoutParams(-1, 0, 1f))
    }
}

/** Emoji panel with categories and recents. */
@SuppressLint("ViewConstructor")
class EmojiPanel(ctx: Context, private val host: PanelHost) : LinearLayout(ctx) {
    private val prefs = Prefs.get(ctx)
    private val t = host.kbTheme
    private var current: List<String> = emptyList()
    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx)
    private val gridAdapter: BaseAdapter = object : BaseAdapter() {
        override fun getCount() = current.size
        override fun getItem(p: Int) = current[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View =
            ((cv as? TextView) ?: TextView(context).apply {
                textSize = 24f; gravity = Gravity.CENTER
                layoutParams = AbsListView.LayoutParams(-1, context.dp(46))
            }).apply { text = current[p] }
    }
    private var selectedCategory = 1

    private val cats: List<Pair<String, List<String>>> = listOf(
        "🕘" to emptyList(),
        "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕".split(" "),
        "👍" to "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐 🖖 👋 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🧠 👀 👁 👄 💋 👶 👦 👧 🧑 👨 👩 🧔 👴 👵 🙋 🙆 🙅 🤷 🤦 💁 🧕 👳 🤵 👰 🤰 🏃 🚶 🧎 💃 🕺".split(" "),
        "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☪️ ✅ ❌ ⭕ ❗ ❓ ‼️ ⁉️ 💯 🔥 ✨ ⭐ 🌟 💫 ⚡ 💥 💢 💦 💤 🎉 🎊 🎁 🏆 🥇 🔔 📌 📍 ✔️ ➕ ➖ ➗ ♾️".split(" "),
        "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🐘 🐪 🐫 🦒 🐎 🐑 🐐 🦌 🐕 🐈 🌵 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌙 🌈 ☁️ ❄️ 🌊".split(" "),
        "🍔" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥒 🌶 🌽 🥕 🧄 🧅 🥔 🥐 🍞 🥖 🧀 🥚 🍳 🥞 🥩 🍗 🍖 🍔 🍟 🍕 🥪 🌮 🌯 🥙 🧆 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍤 🍚 🍰 🎂 🧁 🍩 🍪 🍫 🍬 🍭 🍯 ☕ 🍵 🧃 🥤 🧋 🍼 🥛".split(" "),
        "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🤿 🎽 🛹 ⛸ 🎿 🏂 🏋️ 🤸 🚴 🏊 🧗 🎮 🕹 🎲 ♟ 🎯 🎳 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎬 🎨 🚗 🚕 🚌 🏎 🚓 🚑 🚒 🚚 🚲 🛵 🏍 ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏢 🕌 🕋 🏰 🗽 🗼".split(" "),
        "💡" to "⌚ 📱 💻 ⌨️ 🖥 🖨 🖱 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⌛ 🔋 🔌 💡 🔦 🕯 💸 💵 💰 💳 💎 ⚖️ 🔧 🔨 ⚙️ 🛡 🔮 💊 💉 🩺 🧬 🧹 🧺 🧻 🚿 🛁 🔑 🗝 🚪 🛋 🛏 🧸 🎈 ✉️ 📩 📦 📝 📚 📖 🔖 🔗 📎 ✂️ 📅 📈 📉 🔒 🔓 🧮 🪙 🪜 🪞 🪟 🛒 🧰 🪛 🪚 🪝 🧲 🪫 🧯 🪤 🪬 🪩 🪮 🧴 🧷 🧵 🪡 🧶 🪢 🧽 🪣 🧼 🪥 🧦 👓 🕶 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒 🩴 👞 👟 🥾 🥿 👠 👡 👢 👑 👒 🎩 🎓 🧢 ⛑ 💍 💄".split(" "),
        "🚘" to "🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🏍 🛵 🚲 🛴 🛹 🛼 🚏 🛣 🛤 ⛽ 🛞 🚨 🚥 🚦 🚧 ⚓ 🛟 ⛵ 🚤 🛳 ⛴ 🛥 🚢 ✈️ 🛩 🛫 🛬 🪂 💺 🚁 🚟 🚠 🚡 🚀 🛸 🛰 🧭 🗺 🏔 ⛰ 🌋 🗻 🏕 🏖 🏜 🏝 🏞 🏟 🏛 🏗 🧱 🏘 🏚 🏠 🏡 🏢 🏣 🏤 🏥 🏦 🏨 🏩 🏪 🏫 🏬 🏭 🏯 🏰 💒 🗼 🗽 ⛪ 🕌 🛕 🕍 ⛩ 🕋 ⛲ ⛺ 🌁 🌃 🌄 🌅 🌆 🌇 🌉 ♨️ 🎠 🎡 🎢 💈 🎪 🌍 🌎 🌏 🌐 🌑 🌒 🌓 🌔 🌕 🌖 🌗 🌘 🌙 🌚 🌛 🌜 🌝 🌞 🪐 ⭐ 🌟 🌠 ☀️ 🌤 ⛅ 🌥 🌦 🌧 ⛈ 🌩 🌨 ☃️ ⛄ 🌬 💨 💧 💦 ☔ ☂️ 🌊 🌫".split(" "),
        "🎉" to "🎉 🎊 🎈 🎁 🎀 🎗 🏆 🏅 🥇 🥈 🥉 🏵 🎖 🏋️ 🤼 🤺 🤾 🏌️ 🏇 🧘 🏄 🏂 🏊 🚣 🧗 🚵 🚴 🏆 🥅 🏟 🎯 🪀 🪁 🎱 🔮 🪄 🧿 🪬 🎮 🕹 🎰 🎲 🧩 🧸 🪅 🪩 🪆 ♟ 🃏 🎴 🎭 🎨 🧵 🪡 🎼 🎹 🥁 🪘 🎷 🎺 🪇 🎸 🪕 🎻 🪈 🎤 🎧 🎚 🎛 🎬 🎥 📽 🎞 🎟 🎫 🎪 🎭 🖼 🖌 🖍 📝 ✏️ ✒️ 🖋 🖊 📚 📖 🔍 🔎".split(" "),
        "♻️" to "♻️ ✅ ☑️ ✔️ ❌ ❎ ⭕ 🚫 ⛔ ⚠️ 🚸 🛑 💯 🔞 🔕 🔔 📣 📢 🔊 🔉 🔈 🔇 🔒 🔓 🔏 🔐 🔑 🗝 ♠️ ♥️ ♦️ ♣️ 🃏 🎴 🔀 🔁 🔂 ▶️ ⏩ ⏭ ⏯ ◀️ ⏪ ⏮ 🔼 🔽 ⏫ ⏬ ⏸ ⏹ ⏺ ⏏️ 🎦 🔅 🔆 📶 🛜 📳 📴 ✳️ ✴️ ❇️ ©️ ®️ ™️ ℹ️ ⚕️ ♀️ ♂️ ⚧️ ✖️ ➕ ➖ ➗ 🟰 ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗〰️ ➰ ➿ 🔱 ⚜️ 🔰 🌀 💠 🔷 🔶 🔸 🔹 🔺 🔻 💬 🗯 💤 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛".split(" "),
        "🏳️" to "🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇳 🇦🇪 🇸🇦 🇪🇬 🇯🇴 🇱🇧 🇸🇾 🇮🇶 🇰🇼 🇶🇦 🇧🇭 🇴🇲 🇾🇪 🇵🇸 🇲🇦 🇩🇿 🇹🇳 🇱🇾 🇸🇩 🇸🇴 🇲🇷 🇹🇷 🇮🇷 🇵🇰 🇮🇳 🇧🇩 🇮🇩 🇲🇾 🇨🇳 🇯🇵 🇰🇷 🇹🇭 🇵🇭 🇸🇬 🇦🇺 🇳🇿 🇬🇧 🇺🇸 🇨🇦 🇲🇽 🇧🇷 🇦🇷 🇨🇱 🇨🇴 🇫🇷 🇩🇪 🇮🇹 🇪🇸 🇵🇹 🇳🇱 🇧🇪 🇨🇭 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇺🇦 🇷🇺 🇿🇦 🇳🇬 🇰🇪 🇪🇹 🇬🇭 🇹🇿".split(" "),
    )

    init {
        orientation = VERTICAL
        background = gradientBg(t.bgTop, t.bgBottom)
        tabs.orientation = HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
        cats.forEachIndexed { i, (icon, _) ->
            tabs.addView(TextView(ctx).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
                setOnClickListener { select(i) }
            }, LayoutParams(0, context.dp(38), 1f))
        }
        addView(tabs)
        val search = TextView(ctx).apply {
            text = "بحث في الرموز…"; textSize = 14f
            setTextColor((t.text and 0x00FFFFFF) or 0x88000000.toInt())
            background = rounded(t.key, context.dp(10).toFloat())
            setPadding(context.dp(10), 0, context.dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "افتح شاشة البحث عن الإيموجي"
            setOnClickListener { host.openEmojiSearch() }
        }
        addView(search, LayoutParams(-1, context.dp(40)).apply { setMargins(context.dp(8), context.dp(2), context.dp(8), context.dp(2)) })
        grid.numColumns = 8
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.selector = rounded(t.keyPressed, context.dp(8).toFloat())
        grid.adapter = gridAdapter
        grid.setOnItemClickListener { _, _, p, _ ->
            val e = current[p]
            host.commitEmoji(e)
            val rec = (listOf(e) + prefs.recentEmoji.split(" ").filter { it.isNotBlank() && it != e }).take(32)
            prefs.recentEmoji = rec.joinToString(" ")
        }
        addView(grid, LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), 0, context.dp(6), context.dp(4))
        }
        bottom.addView(TextView(ctx).apply {
            text = "ABC"; setTextColor(t.textFn); textSize = 14f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(t.keyFn, context.dp(8).toFloat())
            setOnClickListener { host.closePanel() }
        }, LayoutParams(context.dp(64), context.dp(40)))
        bottom.addView(View(ctx), LayoutParams(0, 1, 1f))
        bottom.addView(context.iconButton(R.drawable.ic_backspace, t.textFn, 52, 12) { host.deleteBackward() })
        addView(bottom)
        select(if (prefs.recentEmoji.isNotBlank()) 0 else 1)
    }

    private fun select(i: Int) {
        selectedCategory = i
        current = if (i == 0) prefs.recentEmoji.split(" ").filter { it.isNotBlank() } else cats[i].second
        for (k in 0 until tabs.childCount) tabs.getChildAt(k).background = if (k == i) rounded(t.keyPressed, context.dp(10).toFloat()) else null
        gridAdapter.notifyDataSetChanged()
    }

    private fun filter(query: String) {
        val rawQuery = query.trim()
        val q = rawQuery.lowercase(Locale.ROOT)
        current = if (q.isBlank()) {
            if (selectedCategory == 0) prefs.recentEmoji.split(" ").filter { it.isNotBlank() } else cats[selectedCategory].second
        } else {
            val aliases = mapOf("وجه" to listOf("😀", "😂", "😍", "😢"), "قلب" to listOf("❤️", "💔", "💙"), "حيوان" to cats[4].second, "طعام" to cats[5].second, "رياضة" to cats[6].second)
            val allEmojis = cats.drop(1).flatMap { it.second }.distinct()
            val matches = EmojiSearch.search(rawQuery, allEmojis)
            if (matches.isNotEmpty()) matches
            else aliases[q] ?: allEmojis.filter { it.contains(rawQuery) || it.codePoints().anyMatch { cp -> q == cp.toString(16) } }
        }
        gridAdapter.notifyDataSetChanged()
    }
}

/** Browsable local library of typographic, mathematical, currency and directional symbols. */
@SuppressLint("ViewConstructor")
class SymbolPanel(ctx: Context, private val host: PanelHost) : LinearLayout(ctx) {
    private val t = host.kbTheme
    private var current: List<String> = emptyList()
    private var selectedCategory = 0
    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx)
    private val categories = listOf(
        "شائع" to "© ® ™ § ¶ † ‡ • ‣ ◦ ※ № ℃ ℉ ° ′ ″ ‰ ‱ ℠",
        "رياضيات" to "+ − × ÷ ± ∓ = ≠ ≈ ≡ < > ≤ ≥ ∞ √ ∛ ∜ ∑ ∏ ∫ ∬ ∮ ∂ ∇ π τ θ α β ∠ ⊥ ∥ ∝ ∴ ∵ ∈ ∉ ∋ ∌ ⊂ ⊃ ⊆ ⊇ ∩ ∪ ∧ ∨ ¬ ∀ ∃ ∅ ℕ ℤ ℚ ℝ ℂ",
        "أسهم" to "← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ⟵ ⟶ ⟷ ⟸ ⟹ ⟺ ↩ ↪ ↫ ↬ ↰ ↱ ↲ ↳ ↴ ↵ ↶ ↷ ↺ ↻ ➜ ➝ ➞ ➟ ➠ ➤ ➥ ➦ ➧ ➨ ⇦ ⇧ ⇨ ⇩ ⇪",
        "عملات" to "$ € £ ¥ ₹ ₽ ₩ ₺ ₪ ₫ ₴ ₦ ₱ ₡ ₲ ₭ ₮ ₸ ؋ ৳ ฿ ₿ ¢ ƒ ¤ ﷼ ر.س د.إ د.ك د.ب ر.ق ر.ع ل.ل د.م د.ج د.ت",
        "يوناني" to "Α Β Γ Δ Ε Ζ Η Θ Ι Κ Λ Μ Ν Ξ Ο Π Ρ Σ Τ Υ Φ Χ Ψ Ω α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω",
        "ترقيم" to "! ¡ ? ؟ ‽ ⁇ ⁈ ⁉ … ⋯ · • ‣ ⁃ — – ― ‐ ‑ « » ‹ › “ ” ‘ ’ ' \" ( ) [ ] { } ⟨ ⟩ / \\ | ¦ _ ~ ^ § ¶ ※ ⁂ ❧",
        "أشكال" to "★ ☆ ✦ ✧ ✩ ✪ ✫ ✬ ✭ ✮ ✯ ✰ ✱ ✲ ✳ ✴ ✵ ✶ ✷ ✸ ✹ ✺ ✻ ✼ ✽ ✾ ✿ ◆ ◇ ■ □ ▪ ▫ ▣ ▤ ▥ ▦ ▧ ▨ ▩ ▲ △ ▼ ▽ ▶ ▷ ◀ ◁ ● ○ ◎ ◉ ◌ ◍ ◐ ◑ ◒ ◓ ◔ ◕ ◖ ◗ ♠ ♣ ♥ ♦ ♡ ♢ ♤ ♧",
        "عربي" to "، ؛ ؟ ء آ أ إ ؤ ئ ة ى ٱ ـ ً ٌ ٍ َ ُ ِ ّ ْ ٰ ٔ ٕ ٖ ٗ ٘ ٙ ٚ ٛ ٜ ٝ ٞ ٟ"
    ).map { (name, symbols) -> name to symbols.split(" ").filter { it.isNotBlank() }.distinct() }

    private val adapter: BaseAdapter = object : BaseAdapter() {
        override fun getCount() = current.size
        override fun getItem(position: Int) = current[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            ((convertView as? TextView) ?: TextView(context).apply {
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(t.text)
                background = rounded(t.key, context.dp(8).toFloat())
                layoutParams = AbsListView.LayoutParams(-1, context.dp(44))
            }).apply { text = current[position] }
    }

    init {
        orientation = VERTICAL
        background = gradientBg(t.bgTop, t.bgBottom)
        val title = context.label("مكتبة الرموز · #1", t.barText, 15f, true)
        addView(context.panelHeader(host, title))

        tabs.orientation = HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setPadding(context.dp(4), 0, context.dp(4), 0)
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            background = rounded(t.bar, context.dp(10).toFloat())
            addView(tabs, ViewGroup.LayoutParams(-2, context.dp(42)))
        }
        categories.forEachIndexed { index, (name, _) ->
            tabs.addView(context.label(name, t.textFn, 12f, true).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(12), 0, context.dp(12), 0)
                background = rounded(t.key, context.dp(10).toFloat())
                setTextColor(contrastingTabText(t.key))
                setOnClickListener { select(index) }
            }, LayoutParams(-2, context.dp(38)).apply {
                marginStart = context.dp(2); marginEnd = context.dp(2)
            })
        }
        addView(scroll, LayoutParams(-1, context.dp(42)))

        grid.numColumns = 8
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = context.dp(3)
        grid.verticalSpacing = context.dp(3)
        grid.setPadding(context.dp(4), context.dp(3), context.dp(4), context.dp(3))
        grid.selector = rounded(t.keyPressed, context.dp(8).toFloat())
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> host.commitEmoji(current[position]) }
        addView(grid, LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), 0, context.dp(6), context.dp(4))
        }
        fun modeButton(label: String, mode: Mode) = TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.textFn)
            gravity = Gravity.CENTER
            background = rounded(t.keyFn, context.dp(8).toFloat())
            setOnClickListener { host.switchKeyboardMode(mode) }
        }
        bottom.addView(modeButton("ABC", Mode.LETTERS), LayoutParams(context.dp(54), context.dp(40)).apply { marginEnd = context.dp(4) })
        bottom.addView(modeButton("123", Mode.SYM1), LayoutParams(context.dp(54), context.dp(40)).apply { marginEnd = context.dp(4) })
        bottom.addView(modeButton("#2", Mode.SYM2), LayoutParams(context.dp(48), context.dp(40)))
        bottom.addView(View(ctx), LayoutParams(0, 1, 1f))
        bottom.addView(context.iconButton(R.drawable.ic_backspace, t.textFn, 52, 12) { host.deleteBackward() })
        addView(bottom)
        select(0)
    }

    private fun select(index: Int) {
        selectedCategory = index.coerceIn(categories.indices)
        current = categories[selectedCategory].second
        for (i in 0 until tabs.childCount) {
            val surface = if (i == selectedCategory) t.accent else t.key
            tabs.getChildAt(i).background = rounded(surface, context.dp(10).toFloat())
            (tabs.getChildAt(i) as? TextView)?.setTextColor(contrastingTabText(surface))
        }
        adapter.notifyDataSetChanged()
    }

    private fun contrastingTabText(surface: Int): Int {
        val base = t.bar
        fun composite(color: Int): Int {
            val a = Color.alpha(color) / 255.0
            return Color.rgb(
                (Color.red(color) * a + Color.red(base) * (1.0 - a)).toInt(),
                (Color.green(color) * a + Color.green(base) * (1.0 - a)).toInt(),
                (Color.blue(color) * a + Color.blue(base) * (1.0 - a)).toInt(),
            )
        }
        fun ratio(a: Int, b: Int): Double {
            fun luminance(color: Int): Double {
                fun channel(value: Int): Double {
                    val s = value / 255.0
                    return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
            }
            val first = luminance(a)
            val second = luminance(b)
            return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        }
        val visible = composite(surface)
        val preferred = Color.rgb(Color.red(t.textFn), Color.green(t.textFn), Color.blue(t.textFn))
        if (ratio(visible, preferred) >= 4.5) return preferred
        return if (ratio(visible, Color.BLACK) >= ratio(visible, Color.WHITE)) Color.BLACK else Color.WHITE
    }
}

/** Small native text-editing toolbar that works in editors which expose an InputConnection. */
@SuppressLint("ViewConstructor")
class TextToolsPanel(ctx: Context, private val host: PanelHost) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = gradientBg(host.kbTheme.bgTop, host.kbTheme.bgBottom)

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(context.iconButton(R.drawable.ic_back, host.kbTheme.barText) { host.closePanel() }.apply { rotation = 180f })
            addView(context.label("أدوات التحرير وتحريك المؤشر", host.kbTheme.barText, 14f, true),
                LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginStart = context.dp(6) })
        }
        addView(header, LayoutParams(-1, context.dp(46)))

        val actions = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(context.dp(4), 0, context.dp(4), 0)
        }
        listOf(
            "تحديد الكل" to { host.selectAllText() },
            "نسخ" to { host.copyText() },
            "قص" to { host.cutText() },
            "لصق" to { host.pasteFromClipboard() },
        ).forEach { (label, action) ->
            actions.addView(context.label(label, host.kbTheme.textFn, 13f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(host.kbTheme.key, context.dp(10).toFloat())
                setPadding(context.dp(4), 0, context.dp(4), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, context.dp(42), 1f).apply { marginStart = context.dp(3); marginEnd = context.dp(3) })
        }
        addView(actions, LayoutParams(-1, context.dp(50)))

        fun arrow(glyph: String, description: String, code: Int) = TextView(ctx).apply {
            text = glyph
            contentDescription = description
            textSize = 24f
            setTextColor(host.kbTheme.textFn)
            gravity = Gravity.CENTER
            background = rounded(host.kbTheme.key, context.dp(9).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { host.moveCursor(code) }
        }

        val cursorPad = LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        cursorPad.addView(arrow("↑", "تحريك المؤشر إلى أعلى", KeyEvent.KEYCODE_DPAD_UP),
            LinearLayout.LayoutParams(context.dp(48), context.dp(42)).apply { bottomMargin = context.dp(3) })
        val lowerRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        listOf(
            Triple("←", "تحريك المؤشر إلى اليسار", KeyEvent.KEYCODE_DPAD_LEFT),
            Triple("↓", "تحريك المؤشر إلى أسفل", KeyEvent.KEYCODE_DPAD_DOWN),
            Triple("→", "تحريك المؤشر إلى اليمين", KeyEvent.KEYCODE_DPAD_RIGHT),
        ).forEach { (glyph, description, code) ->
            lowerRow.addView(arrow(glyph, description, code), LinearLayout.LayoutParams(context.dp(48), context.dp(42)).apply {
                marginStart = context.dp(3); marginEnd = context.dp(3)
            })
        }
        cursorPad.addView(lowerRow)
        addView(cursorPad, LayoutParams(-1, 0, 1f))
    }
}
