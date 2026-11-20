package com.leo.smartboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Launcher screen: setup steps, try-it field, theme gallery, preferences, clipboard and learning stats. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private val ink = 0xFF14211F.toInt()
    private val muted = 0xFF5C6B68.toInt()
    private val brand = 0xFF0F766E.toInt()
    private val card = 0xFFFFFFFF.toInt()
    private val importDict = 21
    private val importDictDb = 26
    private val importBackup = 22
    private val exportBackup = 23
    private val importDictionaries = 24
    private val exportDictionaries = 25
    private var refreshAfterImeSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        PredictionEngine.get(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Rebuild only after returning from Android's IME settings/picker. Rebuilding
        // on every focus change detaches the active EditText when the keyboard opens.
        if (hasFocus && refreshAfterImeSettings) {
            refreshAfterImeSettings = false
            build()
        }
    }

    private fun build() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)

        // Hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0F766E.toInt(), 0xFF0B1F1E.toInt())
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        hero.addView(label("لوحة ذكية", 0xFFF4F1EA.toInt(), 28f, true))
        hero.addView(label("لوحة مفاتيح تتعلم من كتابتك ونسخك ولصقك، مع حافظة تتسع لـ 3000 نص وثيمات متعددة.", 0xCCF4F1EA.toInt(), 14f).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(hero)

        // Setup
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        section("الإعداد")
        step("1", "تفعيل اللوحة", "فعّل «لوحة ذكية» من إعدادات لوحات المفاتيح", enabled) {
            refreshAfterImeSettings = true
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        step("2", "اختيارها كلوحة افتراضية", "اخترها من قائمة لوحات المفاتيح", current) {
            if (!enabled) Toast.makeText(this, "فعّل اللوحة أولاً", Toast.LENGTH_SHORT).show() else {
                refreshAfterImeSettings = true
                imm.showInputMethodPicker()
            }
        }
        root.addView(EditText(this).apply {
            hint = "جرّب الكتابة هنا…"
            textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(card, dp(14).toFloat(), 0x22000000, dp(1))
            minLines = 2
        }, lp(top = 12))

        // Themes
        section("الثيمات")
        val selected = prefs.themeId
        val cols = 3
        Themes.all.chunked(cols).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { th ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { prefs.themeId = th.id; build() }
                }
                cell.addView(ThemePreview(this, th, th.id == selected))
                cell.addView(label(th.name, ink, 13f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(24)))
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            repeat(cols - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            root.addView(line)
        }

        // Preferences
        section("الكتابة")
        val box = cardBox()
        box.addView(button("تبديل تخطيط اللغة: ${if (prefs.lang == "ar") "العربية" else "English"}") { prefs.lang = if (prefs.lang == "ar") "en" else "ar"; build() })
        toggle(box, "تغليظ حروف المفاتيح", prefs.boldKeys) { prefs.boldKeys = it }
        toggle(box, "الاهتزاز عند الضغط", prefs.vibrate) { prefs.vibrate = it }
        toggle(box, "صوت الضغط", prefs.sound) { prefs.sound = it }
        toggle(box, "صف الأرقام دائماً", prefs.numberRow) { prefs.numberRow = it }
        toggle(box, "حرف كبير تلقائياً (English)", prefs.autoCap) { prefs.autoCap = it }
        toggle(box, "مسافتان = نقطة", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        toggle(box, "تصحيح تلقائي للأخطاء", prefs.autoCorrect) { prefs.autoCorrect = it }
        val hLabel = label("ارتفاع المفاتيح: ${prefs.keyHeightDp}", ink, 15f)
        box.addView(hLabel, LinearLayout.LayoutParams(-1, dp(40)))
        box.addView(SeekBar(this).apply {
            max = 24; progress = prefs.keyHeightDp - 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { prefs.keyHeightDp = 40 + p; hLabel.text = "ارتفاع المفاتيح: ${40 + p}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        section("الاختصارات النصية")
        val expansions = cardBox()
        expansions.addView(button("إضافة اختصار نصي") { expansionDialog(null) })
        TextExpansionRepo.get(this).all().forEach { entry ->
            expansions.addView(label("${entry.trigger}  ←  ${entry.replacement.replace('\n', ' ').take(52)}", ink, 13f).apply {
                setPadding(0, dp(10), 0, dp(4))
            })
            expansions.addView(button("تعديل ${entry.trigger}", outline = true) { expansionDialog(entry) })
            expansions.addView(button("حذف ${entry.trigger}", outline = true) {
                confirm("حذف الاختصار «${entry.trigger}»؟") { TextExpansionRepo.get(this).remove(entry.trigger); build() }
            })
        }

        // Learning
        section("التعلّم والتنبؤ")
        val learn = cardBox()
        toggle(learn, "التعلّم من الكتابة", prefs.learnTyping) { prefs.learnTyping = it }
        toggle(learn, "التعلّم من النسخ واللصق", prefs.learnClipboard) { prefs.learnClipboard = it }
        val st = PredictionEngine.get(this).stats()
        learn.addView(label("كلمات متعلَّمة: ${st.words}   •   أزواج سياق: ${st.pairs}   •   ثلاثيات: ${st.triples}", muted, 13f).apply { setPadding(0, dp(8), 0, dp(8)) })
        learn.addView(button("مسح ما تعلّمته اللوحة", outline = true) {
            confirm("مسح كل الكلمات والسياقات المتعلَّمة؟") { PredictionEngine.get(this).clear(); prefs.seeded = false; build() }
        })

        section("القواميس")
        val dict = cardBox()
        dict.addView(button("استيراد قاموس TXT") { openPicker(importDict, "text/plain") })
        dict.addView(button("استيراد قاموس SQLite (.db)", outline = true) { openPicker(importDictDb, "*/*") })
        dict.addView(button("إضافة كلمات يدوياً", outline = true) { addDictionaryDialog() })
        dict.addView(button("تصدير كل القواميس JSON", outline = true) { createDictionaryBackup() })
        dict.addView(button("استعادة القواميس من نسخة JSON", outline = true) { openPicker(importDictionaries, "application/json") })
        PredictionEngine.get(this).importedDictionaries().sorted().forEach { name ->
            val engine = PredictionEngine.get(this)
            toggle(dict, "$name (${engine.dictionaryWordCount(name)} كلمة)", engine.isDictionaryEnabled(name)) {
                engine.setDictionaryEnabled(name, it)
            }
            dict.addView(button("التصنيف: ${engine.dictionaryCategory(name)}") {
                val categories = arrayOf("عام", "عربي", "English", "عمل", "شخصي")
                AlertDialog.Builder(this).setTitle("تصنيف القاموس: $name")
                    .setItems(categories) { _, which -> engine.setDictionaryCategory(name, categories[which]); build() }
                    .setNegativeButton("إلغاء", null).show()
            })
            dict.addView(button("حذف القاموس: $name", outline = true) {
                confirm("حذف القاموس المستورد «$name»؟ لن تُحذف الكلمات التي تعلمتها اللوحة من كتابتك.") { engine.deleteDictionary(name); build() }
            })
        }

        // Clipboard
        section("الحافظة")
        val clip = cardBox()
        toggle(clip, "حفظ كل ما يُنسخ في الحافظة", prefs.saveClipboard) { prefs.saveClipboard = it }
        clip.addView(label("محفوظ: ${ClipboardRepo.get(this).count()} من ${ClipboardRepo.MAX} نص", muted, 13f).apply { setPadding(0, dp(8), 0, dp(8)) })
        clip.addView(button("إدارة الحافظة والبحث فيها") { startActivity(Intent(this, ClipboardActivity::class.java)) })
        clip.addView(button("تصدير نسخة احتياطية للحافظة") { createBackup() })
        clip.addView(button("استيراد نسخة احتياطية") { openPicker(importBackup, "application/json") })

        root.addView(label("تعمل الحافظة عندما تكون «لوحة ذكية» هي لوحة المفاتيح الحالية. البيانات تبقى على جهازك فقط.", muted, 12f).apply {
            setPadding(dp(4), dp(18), dp(4), 0)
        })
    }

    private fun openPicker(request: Int, mime: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime }
        startActivityForResult(i, request)
    }

    private fun createBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "smartboard-backup.json")
        }
        startActivityForResult(i, exportBackup)
    }

    private fun addDictionaryDialog() {
        val nameInput = EditText(this).apply { hint = "اسم القاموس" }
        val wordsInput = EditText(this).apply { hint = "الكلمات مفصولة بمسافات أو أسطر"; minLines = 3 }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); addView(nameInput); addView(wordsInput) }
        AlertDialog.Builder(this).setTitle("إضافة قاموس").setView(form)
            .setPositiveButton("إضافة") { _, _ ->
                val raw = wordsInput.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val words = PredictionEngine.tokenize(raw)
                    val name = nameInput.text.toString().trim().ifBlank { "قاموس مخصص" }
                    val n = PredictionEngine.get(this).importDictionary(name, words.joinToString(" "))
                    Toast.makeText(this, "أضيفت $n كلمة", Toast.LENGTH_SHORT).show(); build()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun expansionDialog(existing: TextExpansionRepo.Entry?) {
        val repo = TextExpansionRepo.get(this)
        val trigger = EditText(this).apply {
            hint = "الاختصار، مثل: ;addr"
            setSingleLine(true)
            setText(existing?.trigger.orEmpty())
        }
        val replacement = EditText(this).apply {
            hint = "النص الذي سيُكتب عند الضغط على المسافة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setText(existing?.replacement.orEmpty())
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(trigger)
            addView(replacement)
        }
        AlertDialog.Builder(this).setTitle(if (existing == null) "إضافة اختصار نصي" else "تعديل الاختصار")
            .setView(form)
            .setPositiveButton("حفظ") { _, _ ->
                try {
                    val newTrigger = trigger.text.toString()
                    repo.put(newTrigger, replacement.text.toString())
                    if (existing != null && existing.trigger.lowercase() != newTrigger.trim().lowercase()) repo.remove(existing.trigger)
                    Toast.makeText(this, "تم حفظ الاختصار", Toast.LENGTH_SHORT).show()
                    build()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, e.message ?: "بيانات غير صالحة", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private data class DictionaryBackup(val name: String, val category: String, val enabled: Boolean, val words: List<String>)

    private fun createDictionaryBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "smartboard-dictionaries.json")
        }
        startActivityForResult(intent, exportDictionaries)
    }

    private fun writeDictionaryBackup(uri: Uri) {
        val engine = PredictionEngine.get(applicationContext)
        val names = engine.importedDictionaries().sorted()
        Thread({
            try {
                val output = contentResolver.openOutputStream(uri) ?: error("تعذر فتح ملف التصدير")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("{\"format\":\"smartboard-dictionaries-v1\",\"dictionaries\":[")
                    names.forEachIndexed { index, name ->
                        if (index > 0) writer.write(",")
                        writer.write("{\"name\":${JSONObject.quote(name)},\"category\":${JSONObject.quote(engine.dictionaryCategory(name))},\"enabled\":${engine.isDictionaryEnabled(name)},\"words\":[")
                        var first = true
                        engine.forEachDictionaryWord(name) { word ->
                            if (!first) writer.write(",")
                            writer.write(JSONObject.quote(word))
                            first = false
                        }
                        writer.write("]}")
                    }
                    writer.write("]}")
                }
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, "تم تصدير ${names.size} قاموس", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, "تعذر تصدير القواميس: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }, "smartboard-dictionary-export").start()
    }

    private fun readDocument(uri: Uri, maxBytes: Int = 12 * 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        val input = contentResolver.openInputStream(uri) ?: error("تعذر فتح الملف")
        input.use { stream ->
            val buffer = ByteArray(8192)
            var count: Int
            var total = 0
            while (stream.read(buffer).also { count = it } >= 0) {
                total += count
                require(total <= maxBytes) { "الملف أكبر من الحد المسموح (12MB)" }
                out.write(buffer, 0, count)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun importDictionaryText(name: String, text: String) {
        val engine = PredictionEngine.get(applicationContext)
        val performImport: () -> Unit = {
            Toast.makeText(this, "جارٍ استيراد القاموس…", Toast.LENGTH_SHORT).show()
            Thread({
                try {
                    val count = engine.importDictionary(name, text)
                    runOnUiThread {
                        if (!isFinishing) {
                            Toast.makeText(this, "تم استيراد $count كلمة من $name", Toast.LENGTH_LONG).show()
                            build()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!isFinishing) Toast.makeText(this, "تعذر استيراد القاموس: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }, "smartboard-txt-import").start()
        }
        if (name in engine.importedDictionaries()) confirm("يوجد قاموس باسم «$name». هل تريد استبداله؟") { performImport() }
        else performImport()
    }

    private fun importDictionaryDb(uri: Uri) {
        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "قاموس.db"
        val name = displayName.substringBeforeLast('.', displayName).ifBlank { "قاموس" }.take(80)
        Toast.makeText(this, "جارٍ قراءة ملف قاعدة البيانات…", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                val words = DictionaryDbImporter.readWords(applicationContext, uri)
                val engine = PredictionEngine.get(applicationContext)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val performImport: () -> Unit = {
                        Thread({
                            try {
                                val count = engine.importDictionaryWords(name, words)
                                runOnUiThread {
                                    if (!isFinishing) {
                                        Toast.makeText(this, "تم استيراد $count كلمة من قاعدة $name", Toast.LENGTH_LONG).show()
                                        build()
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    if (!isFinishing) Toast.makeText(this, "تعذر استيراد القاموس: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }, "smartboard-db-store").start()
                    }
                    if (name in engine.importedDictionaries()) confirm("يوجد قاموس باسم «$name». هل تريد استبداله؟") { performImport() }
                    else performImport()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, "تعذر قراءة ملف DB: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }, "smartboard-db-read").start()
    }

    private fun parseDictionaryBackup(jsonText: String): List<DictionaryBackup> {
        val root = JSONObject(jsonText)
        require(root.optString("format") == "smartboard-dictionaries-v1") { "صيغة نسخة القواميس غير معروفة" }
        val list = root.optJSONArray("dictionaries") ?: error("النسخة لا تحتوي على قواميس")
        require(list.length() <= 100) { "عدد القواميس يتجاوز الحد المسموح" }
        var totalWords = 0
        val result = ArrayList<DictionaryBackup>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            val wordArray = item.optJSONArray("words") ?: JSONArray()
            require(wordArray.length() <= 300_000) { "قاموس «$name» أكبر من الحد المسموح" }
            totalWords += wordArray.length()
            require(totalWords <= 500_000) { "النسخة تحتوي على كلمات كثيرة جداً" }
            val words = ArrayList<String>(wordArray.length())
            for (j in 0 until wordArray.length()) {
                val word = wordArray.optString(j).trim()
                if (word.isNotEmpty()) words += word
            }
            result += DictionaryBackup(name.take(80), item.optString("category", "عام").take(40), item.optBoolean("enabled", true), words)
        }
        return result.distinctBy { it.name }
    }

    @Deprecated("Activity result API kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                importDict -> {
                    val text = readDocument(uri, 10 * 1024 * 1024)
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "قاموس"
                    importDictionaryText(name, text)
                }
                importDictDb -> importDictionaryDb(uri)
                importDictionaries -> {
                    val backup = parseDictionaryBackup(readDocument(uri, 64 * 1024 * 1024))
                    require(backup.isNotEmpty()) { "لم يتم العثور على قواميس صالحة في الملف" }
                    val engine = PredictionEngine.get(this)
                    val collisions = backup.map { it.name }.filter { it in engine.importedDictionaries() }
                    val restore = {
                        var count = 0
                        backup.forEach { dict ->
                            count += engine.importDictionaryWords(dict.name, dict.words, dict.enabled, dict.category)
                        }
                        Toast.makeText(this, "تمت استعادة ${backup.size} قاموساً و$count كلمة", Toast.LENGTH_LONG).show()
                        build()
                    }
                    if (collisions.isNotEmpty()) confirm("ستُستبدل القواميس الموجودة: ${collisions.joinToString()}؟") { restore() }
                    else restore()
                }
                importBackup -> {
                    val json = JSONObject(readDocument(uri))
                    val arr = json.optJSONArray("clips") ?: JSONArray()
                    for (i in 0 until arr.length()) ClipboardRepo.get(this).add(arr.optString(i))
                    Toast.makeText(this, "تم استيراد ${arr.length()} نص", Toast.LENGTH_SHORT).show(); build()
                }
                exportBackup -> {
                    val clips = JSONArray().apply { ClipboardRepo.get(this@SettingsActivity).list(limit = ClipboardRepo.MAX).forEach { put(it.text) } }
                    contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(JSONObject().put("format", "smartboard-backup-v1").put("clips", clips).toString(2)) }
                    Toast.makeText(this, "تم إنشاء النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
                }
                exportDictionaries -> writeDictionaryBackup(uri)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت معالجة الملف: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun section(title: String) {
        root.addView(label(title, brand, 15f, true).apply { setPadding(dp(4), dp(24), dp(4), dp(8)) })
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        background = rounded(card, dp(16).toFloat())
        root.addView(this)
    }

    private fun step(num: String, title: String, sub: String, done: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(card, dp(16).toFloat())
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = if (done) "✓" else num; gravity = Gravity.CENTER; textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (done) 0xFFFFFFFF.toInt() else brand)
            background = rounded(if (done) brand else 0x1A0F766E, dp(18).toFloat())
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        texts.addView(label(title, ink, 16f, true))
        texts.addView(label(if (done) "تم ✓" else sub, muted, 13f))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, lp(top = 8))
    }

    private fun toggle(parent: LinearLayout, title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            text = title; textSize = 15f; setTextColor(ink); isChecked = value
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun button(text: String, outline: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f
        setTextColor(if (outline) brand else 0xFFFFFFFF.toInt())
        background = if (outline) rounded(0, dp(12).toFloat(), brand, dp(1)) else rounded(brand, dp(12).toFloat())
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("نعم") { _, _ -> ok() }.setNegativeButton("إلغاء", null).show()
    }
}
