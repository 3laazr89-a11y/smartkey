package com.leo.smartboard

import android.content.Context
import java.util.TreeMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln

/**
 * On-device n-gram language model (unigram / bigram / trigram).
 * Learns from typed text, copied text and pasted text, and predicts
 * word completions plus the next word from context, Gboard-style.
 */
class PredictionEngine private constructor(private val ctx: Context) {
    private val db = Db.get(ctx)
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private val uni = HashMap<String, Int>(8192)
    private val index = TreeMap<String, MutableSet<String>>() // normalized form -> words
    private val bi = HashMap<String, HashMap<String, Int>>()
    private val tri = HashMap<String, HashMap<String, Int>>()
    private val pending = ArrayList<Op>()
    private var globalTopWords: List<Pair<String, Int>> = emptyList()
    private var globalTopWordsDirty = true

    @Volatile var ready = false
        private set

    init {
        io.execute {
            load()
            migrateLegacyDictionaries()
            seedBuiltInEnglishDictionary()
            if (!Prefs.get(ctx).seeded) {
                Seeds.phrases.forEach { learnText(it, 2, persistNow = false) }
                flushPending()
                Prefs.get(ctx).seeded = true
            }
            ready = true
        }
    }

    data class Stats(val words: Int, val pairs: Int, val triples: Int)

    fun stats(): Stats = synchronized(lock) {
        Stats(uni.size, bi.values.sumOf { it.size }, tri.values.sumOf { it.size })
    }

    fun count(word: String): Int = synchronized(lock) { uni[canon(word)] ?: 0 }

    fun containsWord(word: String): Boolean = synchronized(lock) {
        val canonical = canon(word)
        (uni[canonical] ?: 0) > 0 || index[norm(canonical)]?.contains(canonical) == true || dictionaryHasWord(norm(canonical))
    }

    /**
     * Imports a dictionary as an independent source. Imported words are deliberately
     * NOT written into the learned n-gram tables: deleting a dictionary must never
     * erase words or frequencies learned from the user's own typing.
     */
    fun importDictionary(name: String, text: String, enabled: Boolean = true, category: String = "عام"): Int {
        return importDictionaryWords(name, tokenize(text), enabled, category)
    }

    fun importDictionaryWords(name: String, words: Iterable<String>, enabled: Boolean = true, category: String = "عام"): Int {
        var safeName = name.trim().ifBlank { "قاموس" }.take(80)
        if (safeName.startsWith(META_PREFIX)) safeName = "قاموس_${safeName.removePrefix(META_PREFIX)}".take(80)
        val writable = db.writableDatabase
        var count = 0
        writable.beginTransaction()
        try {
            writable.execSQL("INSERT OR REPLACE INTO dictionaries(name,enabled,category) VALUES(?,?,?)", arrayOf<Any>(safeName, if (enabled) 1 else 0, category.take(40).ifBlank { "عام" }))
            writable.delete("dictionary_words", "dictionary=?", arrayOf(safeName))
            val insert = writable.compileStatement("INSERT OR IGNORE INTO dictionary_words(dictionary,word,normalized) VALUES(?,?,?)")
            for (raw in words) {
                val word = canon(raw)
                if (word.length !in 1..32 || !word.any(Char::isLetter)) continue
                insert.clearBindings()
                insert.bindString(1, safeName)
                insert.bindString(2, word)
                insert.bindString(3, norm(word))
                if (insert.executeInsert() != -1L) count++
                if (count >= MAX_DICTIONARY_WORDS) break
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        synchronized(lock) { rebuildWordIndexLocked() }
        return count
    }

    fun importedDictionaries(): Set<String> {
        val names = LinkedHashSet<String>()
        db.readableDatabase.rawQuery("SELECT name FROM dictionaries ORDER BY name", null).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    fun isDictionaryEnabled(name: String): Boolean {
        db.readableDatabase.rawQuery("SELECT enabled FROM dictionaries WHERE name=?", arrayOf(name)).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    fun setDictionaryEnabled(name: String, enabled: Boolean) {
        db.writableDatabase.execSQL("UPDATE dictionaries SET enabled=? WHERE name=?", arrayOf<Any>(if (enabled) 1 else 0, name))
    }

    fun dictionaryWordCount(name: String): Int {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM dictionary_words WHERE dictionary=?", arrayOf(name)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun dictionaryWords(name: String): Set<String> {
        val words = LinkedHashSet<String>()
        db.readableDatabase.rawQuery("SELECT word FROM dictionary_words WHERE dictionary=? ORDER BY word", arrayOf(name)).use { cursor ->
            while (cursor.moveToNext()) words += cursor.getString(0)
        }
        return words
    }

    fun forEachDictionaryWord(name: String, action: (String) -> Unit) {
        db.readableDatabase.rawQuery("SELECT word FROM dictionary_words WHERE dictionary=? ORDER BY word", arrayOf(name)).use { cursor ->
            while (cursor.moveToNext()) action(cursor.getString(0))
        }
    }

    fun dictionaryCategory(name: String): String {
        db.readableDatabase.rawQuery("SELECT category FROM dictionaries WHERE name=?", arrayOf(name)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) ?: "عام" else "عام"
        }
    }

    fun setDictionaryCategory(name: String, category: String) {
        db.writableDatabase.execSQL("UPDATE dictionaries SET category=? WHERE name=?", arrayOf(category.take(40).ifBlank { "عام" }, name))
    }

    fun deleteDictionary(name: String) {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete("dictionary_words", "dictionary=?", arrayOf(name))
            writable.delete("dictionaries", "name=?", arrayOf(name))
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private fun dictionaryPrefs() = ctx.getSharedPreferences(DICTIONARY_PREFS, Context.MODE_PRIVATE)

    private fun dictionaryHasWord(normalized: String): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM dictionary_words dw JOIN dictionaries d ON d.name=dw.dictionary " +
                "WHERE d.enabled=1 AND dw.normalized=? LIMIT 1",
            arrayOf(normalized)
        ).use { return it.moveToFirst() }
    }

    private fun dictionaryWordsForPrefix(normalizedPrefix: String, limit: Int): List<String> {
        val words = ArrayList<String>()
        val upperBound = normalizedPrefix + '\uFFFF'
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT dw.word FROM dictionary_words dw JOIN dictionaries d ON d.name=dw.dictionary " +
                "WHERE d.enabled=1 AND dw.normalized>=? AND dw.normalized<? ORDER BY dw.normalized,dw.word LIMIT $limit",
            arrayOf(normalizedPrefix, upperBound)
        ).use { cursor ->
            while (cursor.moveToNext()) words += cursor.getString(0)
        }
        return words
    }

    private fun migrateLegacyDictionaries() {
        val prefs = dictionaryPrefs()
        val names = prefs.all.keys.filterNot { it.startsWith(META_PREFIX) }
        for (name in names) {
            val words = prefs.getStringSet(name, emptySet()).orEmpty()
            importDictionaryWords(name, words, prefs.getBoolean(ENABLED_PREFIX + name, true), prefs.getString(CATEGORY_PREFIX + name, "عام") ?: "عام")
            prefs.edit().remove(name).remove(ENABLED_PREFIX + name).remove(CATEGORY_PREFIX + name).apply()
        }
    }

    /** Ships an offline English starter dictionary so English suggestions work on first run. */
    private fun seedBuiltInEnglishDictionary() {
        val prefs = Prefs.get(ctx)
        if (prefs.englishDictionarySeeded) return
        try {
            val words = ctx.assets.open("english_20k.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
            importDictionaryWords("English 20K", words, enabled = true, category = "English")
            prefs.englishDictionarySeeded = true
        } catch (_: Exception) {
            // A damaged/missing asset must not prevent the keyboard from starting.
        }
    }

    /** Rebuild from learned words plus all enabled dictionary sources. */
    private fun rebuildWordIndexLocked() {
        index.clear()
        uni.keys.forEach { word -> index.getOrPut(norm(word)) { HashSet(2) }.add(word) }
    }

    /** Learns from a block of text (copy / paste). Each sentence is learned separately. */
    fun learnText(text: String, weight: Int = 1, persistNow: Boolean = true) {
        val clipped = if (text.length > 6000) text.substring(0, 6000) else text
        for (sentence in clipped.split(SENTENCE_SPLIT)) {
            val words = tokenize(sentence)
            if (words.isNotEmpty()) learnSequence(words, weight)
        }
        if (persistNow) io.execute { flushPending() }
    }

    /** Learns the last word typed together with its preceding context. */
    fun learnTyped(prev2: String?, prev1: String?, word: String) {
        val w = canon(word)
        if (!isWord(w)) return
        synchronized(lock) {
            bump(w, 1)
            if (prev1 != null && isWord(canon(prev1))) {
                bumpBi(canon(prev1), w, 1)
                if (prev2 != null && isWord(canon(prev2))) bumpTri(canon(prev2), canon(prev1), w, 1)
            }
        }
        io.execute { flushPending() }
    }

    fun suggest(prev2: String?, prev1: String?, prefix: String, limit: Int = 3): List<String> {
        if (!ready) return emptyList()
        val p1 = prev1?.let { canon(it) }
        val p2 = prev2?.let { canon(it) }
        val targetGroup = languageGroup(prefix.ifEmpty { p1.orEmpty() })
        val mixedContextWeight = if (prefix.isNotEmpty() && p1 != null && languageGroup(prefix) != 0 &&
            languageGroup(p1) != 0 && languageGroup(prefix) != languageGroup(p1)) 0.55 else 1.0
        val scores = HashMap<String, Double>()
        synchronized(lock) {
            if (prefix.isEmpty()) {
                if (p1 == null) return emptyList()
                if (p2 != null) tri[triKey(p2, p1)]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0.0) + c * 6.0 }
                bi[p1]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0.0) + c * 3.0 + ln(1.0 + (uni[w] ?: 0)) }
                // Back off to frequent local words so the suggestion strip remains useful
                // even when this exact preceding word has not yet acquired a bigram.
                if (scores.size < limit) {
                    if (globalTopWordsDirty) {
                        globalTopWords = uni.entries.sortedByDescending { it.value }.take(32).map { it.key to it.value }
                        globalTopWordsDirty = false
                    }
                    for ((word, count) in globalTopWords) {
                        if (word != p1) scores.putIfAbsent(word, ln(1.0 + count) * 0.35)
                        if (scores.size >= limit) break
                    }
                }
            } else {
                val np = norm(prefix)
                var scanned = 0
                for ((_, words) in index.subMap(np, true, np + '\uFFFF', true)) {
                    for (w in words) {
                        if (targetGroup != 0 && languageGroup(w) != targetGroup) continue
                        var s = ln(1.0 + (uni[w] ?: 1)) * 2.0
                        if (p1 != null) {
                            s += (bi[p1]?.get(w) ?: 0) * 4.0 * mixedContextWeight
                            if (p2 != null) s += (tri[triKey(p2, p1)]?.get(w) ?: 0) * 8.0 * mixedContextWeight
                        }
                        if (w.length == prefix.length) s += 0.5
                        scores[w] = s
                    }
                    if (++scanned > 3000) break
                }
                for (w in dictionaryWordsForPrefix(np, 3000)) {
                    if (targetGroup != 0 && languageGroup(w) != targetGroup) continue
                    var s = ln(1.0 + (uni[w] ?: 1)) * 2.0
                    if (p1 != null) {
                        s += (bi[p1]?.get(w) ?: 0) * 4.0 * mixedContextWeight
                        if (p2 != null) s += (tri[triKey(p2, p1)]?.get(w) ?: 0) * 8.0 * mixedContextWeight
                    }
                    if (w.length == prefix.length) s += 0.5
                    scores[w] = maxOf(scores[w] ?: Double.NEGATIVE_INFINITY, s)
                }
                // Typo tolerance: words one edit away from what was typed.
                if (scores.size < limit && np.length >= 3) {
                    val first = np.substring(0, 1)
                    var n = 0
                    for ((nw, words) in index.subMap(first, true, first + '\uFFFF', true)) {
                        if (++n > 8000) break
                        if (nw.length < np.length - 1) continue
                        val target = if (nw.length > np.length + 1) nw.substring(0, np.length) else nw
                        if (target != np && editDistanceAtMostOne(target, np)) {
                            for (w in words) {
                                val s = ln(1.0 + (uni[w] ?: 1)) + (if (p1 != null) (bi[p1]?.get(w) ?: 0) * 2.0 else 0.0)
                                scores[w] = maxOf(scores[w] ?: 0.0, s * 0.6)
                            }
                        }
                    }
                }
            }
            Unit
        }
        val upper = prefix.isNotEmpty() && prefix[0].isUpperCase()
        return scores.entries
            .filter { targetGroup == 0 || languageGroup(it.key) == targetGroup }
            .sortedByDescending { it.value }
            .take(limit)
            .map { display(it.key, upper) }
    }

    /** Conservative local spelling correction using learned frequency and word context. */
    fun correction(prev1: String?, typed: String): String? {
        val w = canon(typed)
        if (w.length < 3 || containsWord(w)) return null
        val target = norm(w)
        val group = languageGroup(w)
        if (group == 0) return null
        val context = prev1?.let(::canon)
        data class Candidate(val word: String, val distance: Double, val score: Double, val freq: Int, val pair: Int)
        val candidates = ArrayList<Candidate>()
        val maxDistance = if (target.length >= 8) 1.75 else 1.0
        synchronized(lock) {
            val initials = keyboardNeighbors(target.first(), group) + target.first()
            var scanned = 0
            for (initial in initials) {
                val key = initial.toString()
                for ((indexed, words) in index.subMap(key, true, key + '\uFFFF', true)) {
                    if (++scanned > 5000) break
                    if (kotlin.math.abs(indexed.length - target.length) > if (maxDistance > 1.0) 2 else 1) continue
                    for (candidate in words) {
                        if (languageGroup(candidate) != group) continue
                        val normalized = norm(candidate)
                        if (normalized == target) continue
                        val distance = spellingDistance(target, normalized, group)
                        if (distance > maxDistance) continue
                        val freq = uni[candidate] ?: 0
                        val pair = if (context == null) 0 else (bi[context]?.get(candidate) ?: 0)
                        // A dictionary word alone is not enough to change typed text.
                        if (freq < 3 && pair < 2) continue
                        if (distance > 1.0 && (freq < 12 || pair < 4)) continue
                        val score = ln(1.0 + freq) * 1.4 + ln(1.0 + pair) * 2.0 - distance * 2.3
                        candidates += Candidate(candidate, distance, score, freq, pair)
                    }
                }
                if (scanned > 5000) break
            }
        }
        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        if (ranked.getOrNull(1)?.let { best.score - it.score < 0.65 && it.word != best.word } == true) return null
        return display(best.word, typed.firstOrNull()?.isUpperCase() == true)
    }

    fun clear() {
        synchronized(lock) { uni.clear(); bi.clear(); tri.clear(); pending.clear(); globalTopWordsDirty = true; rebuildWordIndexLocked() }
        io.execute {
            val w = db.writableDatabase
            w.execSQL("DELETE FROM uni"); w.execSQL("DELETE FROM bi"); w.execSQL("DELETE FROM tri")
        }
    }

    // ---------------------------------------------------------------- internals

    private sealed class Op {
        class U(val w: String, val n: Int) : Op()
        class B(val a: String, val b: String, val n: Int) : Op()
        class T(val a: String, val b: String, val w: String, val n: Int) : Op()
    }

    private fun learnSequence(words: List<String>, weight: Int) {
        synchronized(lock) {
            for (i in words.indices) {
                val w = canon(words[i])
                if (!isWord(w)) continue
                bump(w, weight)
                if (i >= 1) {
                    val a = canon(words[i - 1])
                    if (isWord(a)) {
                        bumpBi(a, w, weight)
                        if (i >= 2) {
                            val z = canon(words[i - 2])
                            if (isWord(z)) bumpTri(z, a, w, weight)
                        }
                    }
                }
            }
        }
    }

    private fun bump(w: String, n: Int) {
        val c = uni[w] ?: 0
        if (c == 0) index.getOrPut(norm(w)) { HashSet(2) }.add(w)
        uni[w] = c + n
        globalTopWordsDirty = true
        pending += Op.U(w, n)
    }

    private fun bumpBi(a: String, b: String, n: Int) {
        val m = bi.getOrPut(a) { HashMap(4) }
        m[b] = (m[b] ?: 0) + n
        pending += Op.B(a, b, n)
    }

    private fun bumpTri(a: String, b: String, w: String, n: Int) {
        val m = tri.getOrPut(triKey(a, b)) { HashMap(2) }
        m[w] = (m[w] ?: 0) + n
        pending += Op.T(a, b, w, n)
    }

    private fun flushPending() {
        val ops: List<Op> = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
        if (ops.isEmpty()) return
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val uUp = d.compileStatement("UPDATE uni SET c=c+? WHERE w=?")
            val uIns = d.compileStatement("INSERT INTO uni(w,c) VALUES(?,?)")
            val bUp = d.compileStatement("UPDATE bi SET c=c+? WHERE a=? AND b=?")
            val bIns = d.compileStatement("INSERT INTO bi(a,b,c) VALUES(?,?,?)")
            val tUp = d.compileStatement("UPDATE tri SET c=c+? WHERE a=? AND b=? AND w=?")
            val tIns = d.compileStatement("INSERT INTO tri(a,b,w,c) VALUES(?,?,?,?)")
            for (op in ops) when (op) {
                is Op.U -> {
                    uUp.clearBindings(); uUp.bindLong(1, op.n.toLong()); uUp.bindString(2, op.w)
                    if (uUp.executeUpdateDelete() == 0) { uIns.clearBindings(); uIns.bindString(1, op.w); uIns.bindLong(2, op.n.toLong()); uIns.executeInsert() }
                }
                is Op.B -> {
                    bUp.clearBindings(); bUp.bindLong(1, op.n.toLong()); bUp.bindString(2, op.a); bUp.bindString(3, op.b)
                    if (bUp.executeUpdateDelete() == 0) { bIns.clearBindings(); bIns.bindString(1, op.a); bIns.bindString(2, op.b); bIns.bindLong(3, op.n.toLong()); bIns.executeInsert() }
                }
                is Op.T -> {
                    tUp.clearBindings(); tUp.bindLong(1, op.n.toLong()); tUp.bindString(2, op.a); tUp.bindString(3, op.b); tUp.bindString(4, op.w)
                    if (tUp.executeUpdateDelete() == 0) { tIns.clearBindings(); tIns.bindString(1, op.a); tIns.bindString(2, op.b); tIns.bindString(3, op.w); tIns.bindLong(4, op.n.toLong()); tIns.executeInsert() }
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    private fun load() {
        val r = db.readableDatabase
        synchronized(lock) {
            r.rawQuery("SELECT w,c FROM uni", null).use { c ->
                while (c.moveToNext()) {
                    val w = c.getString(0); uni[w] = c.getInt(1)
                }
            }
            globalTopWordsDirty = true
            r.rawQuery("SELECT a,b,c FROM bi", null).use { c ->
                while (c.moveToNext()) bi.getOrPut(c.getString(0)) { HashMap(4) }[c.getString(1)] = c.getInt(2)
            }
            r.rawQuery("SELECT a,b,w,c FROM tri", null).use { c ->
                while (c.moveToNext()) tri.getOrPut(triKey(c.getString(0), c.getString(1))) { HashMap(2) }[c.getString(2)] = c.getInt(3)
            }
            rebuildWordIndexLocked()
        }
    }

    companion object {
        private const val DICTIONARY_PREFS = "imported_dictionaries"
        private const val ENABLED_PREFIX = "__enabled__:"
        private const val CATEGORY_PREFIX = "__category__:"
        private const val META_PREFIX = "__"
        private const val MAX_DICTIONARY_WORDS = 300000
        private val SENTENCE_SPLIT = Regex("[.!?؟،,;؛:\\n\\r\\t()\\[\\]{}\"«»]+")

        @Volatile private var inst: PredictionEngine? = null
        fun get(ctx: Context): PredictionEngine = inst ?: synchronized(this) {
            inst ?: PredictionEngine(ctx.applicationContext).also { inst = it }
        }

        fun isWordChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '\'' || ch == '’' || Character.getType(ch) == Character.NON_SPACING_MARK.toInt()

        fun isSentenceBoundary(ch: Char): Boolean = ch in ".!?؟،,;؛:\n\r"

        /** 1=Arabic, 2=Latin, 0=other/unknown. */
        fun languageGroup(text: String): Int {
            for (ch in text) {
                if (ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFEFF') return 1
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '\u00C0'..'\u024F') return 2
            }
            return 0
        }

        private fun keyboardNeighbors(ch: Char, group: Int): Set<Char> {
            val rows = if (group == 1) listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ذءؤرىةوزظد")
                else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val positions = ArrayList<Triple<Char, Float, Float>>()
            rows.forEachIndexed { row, chars ->
                val offset = when (row) { 0 -> 0f; 1 -> 0.5f; else -> 1.25f }
                chars.forEachIndexed { col, c -> positions += Triple(c, col + offset, row.toFloat()) }
            }
            val source = positions.firstOrNull { it.first == ch } ?: return emptySet()
            return positions.asSequence()
                .filter { it.first != ch && kotlin.math.abs(it.second - source.second) <= 1.1f && kotlin.math.abs(it.third - source.third) <= 1f }
                .map { it.first }.toSet()
        }

        private fun spellingDistance(a: String, b: String, group: Int): Double {
            if (kotlin.math.abs(a.length - b.length) > 2) return Double.POSITIVE_INFINITY
            val d = Array(a.length + 1) { DoubleArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i.toDouble()
            for (j in 0..b.length) d[0][j] = j.toDouble()
            for (i in 1..a.length) for (j in 1..b.length) {
                val x = a[i - 1]; val y = b[j - 1]
                val substitute = when {
                    x == y -> 0.0
                    y in keyboardNeighbors(x, group) -> 0.65
                    else -> 1.0
                }
                d[i][j] = minOf(
                    d[i - 1][j] + 1.0,
                    d[i][j - 1] + 1.0,
                    d[i - 1][j - 1] + substitute,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 0.7)
                }
            }
            return d[a.length][b.length]
        }

        fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in text) {
                if (isWordChar(ch)) sb.append(ch) else if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        /** Stored form: lowercase, Arabic diacritics (tashkeel) and tatweel removed. */
        fun canon(w: String): String {
            val sb = StringBuilder(w.length)
            for (ch in w) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || ch == 'ـ') continue
                sb.append(ch.lowercaseChar())
            }
            return sb.toString().trim('\'', '’')
        }

        /** Matching form: also folds Arabic alef/yaa/taa-marbuta variants so "ا" finds "أن". */
        fun norm(w: String): String {
            val s = canon(w)
            val sb = StringBuilder(s.length)
            for (ch in s) sb.append(
                when (ch) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; 'ؤ', 'ئ' -> 'ء'; else -> ch }
            )
            return sb.toString()
        }

        private fun isWord(w: String) = w.isNotEmpty() && w.length <= 32 && w.any { it.isLetter() }
        private fun triKey(a: String, b: String) = a + '\u0001' + b

        private fun display(w: String, upper: Boolean): String = when {
            w == "i" -> "I"
            w.startsWith("i'") -> "I" + w.substring(1)
            upper -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }

        fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> {
                        if (i + 1 < a.length && j + 1 < b.length && a[i] == b[j + 1] && a[i + 1] == b[j]) { i += 2; j += 2 } else { i++; j++ }
                    }
                }
            }
            edits += (a.length - i) + (b.length - j)
            return edits <= 1
        }
    }
}
