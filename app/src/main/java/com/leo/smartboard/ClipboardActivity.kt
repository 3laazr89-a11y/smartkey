package com.leo.smartboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full clipboard manager: search all 3000 entries, copy, pin, delete. */
class ClipboardActivity : Activity() {
    private val repo by lazy { ClipboardRepo.get(this) }
    private var items: List<Clip> = emptyList()
    private lateinit var count: TextView
    private lateinit var search: EditText
    private val fmt = SimpleDateFormat("d MMM yyyy، HH:mm", Locale.forLanguageTag("ar"))
    private val ink = 0xFF14211F.toInt()
    private val muted = 0xFF5C6B68.toInt()
    private val brand = 0xFF0F766E.toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val c = items[p]
            val box = (cv as? LinearLayout) ?: LinearLayout(this@ClipboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(TextView(context).apply { textSize = 15f; setTextColor(ink); maxLines = 4; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
                addView(TextView(context).apply { textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
            }
            box.background = rounded(0xFFFFFFFF.toInt(), dp(14).toFloat())
            (box.getChildAt(0) as TextView).text = c.text
            (box.getChildAt(1) as TextView).text = (if (c.pinned) "📌 مثبّت • " else "") + fmt.format(Date(c.ts)) + (if (c.uses > 0) " • استُخدم ${c.uses}×" else "")
            return box
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("الحافظة", ink, 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "مسح غير المثبّت"; setTextColor(brand); textSize = 14f; setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                AlertDialog.Builder(this@ClipboardActivity).setMessage("حذف كل النصوص غير المثبّتة؟")
                    .setPositiveButton("حذف") { _, _ -> repo.clearUnpinned(); reload() }.setNegativeButton("إلغاء", null).show()
            }
        })
        root.addView(header)
        count = label("", muted, 13f)
        root.addView(count)
        search = EditText(this).apply {
            hint = "ابحث في الحافظة…"; textSize = 15f; isSingleLine = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), dp(14).toFloat(), 0x22000000, dp(1))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = reload()
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val list = ListView(this).apply {
            divider = null; dividerHeight = dp(8)
            adapter = this@ClipboardActivity.adapter
            setOnItemClickListener { _, _, p, _ -> copy(items[p]) }
            setOnItemLongClickListener { _, _, p, _ -> actions(items[p]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("اضغط للنسخ • اضغط مطولاً للتثبيت أو الحذف", muted, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12)) })
        setContentView(root)
        reload()
    }

    private fun reload() {
        items = repo.list(search.text?.toString())
        count.text = "${repo.count()} من ${ClipboardRepo.MAX} نص محفوظ" + if (!search.text.isNullOrBlank()) " • النتائج: ${items.size}" else ""
        adapter.notifyDataSetChanged()
    }

    private fun copy(c: Clip) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", c.text))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun actions(c: Clip) {
        val opts = arrayOf("نسخ", if (c.pinned) "إلغاء التثبيت" else "تثبيت", "حذف")
        AlertDialog.Builder(this).setItems(opts) { _, i ->
            when (i) {
                0 -> copy(c)
                1 -> { repo.setPinned(c.id, !c.pinned); reload() }
                2 -> { repo.delete(c.id); reload() }
            }
        }.show()
    }
}
