package com.leo.smartboard

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Local-only storage for user-defined trigger -> replacement pairs. */
class TextExpansionRepo private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("text_expansions", Context.MODE_PRIVATE)
    private val lock = Any()
    private var cached: LinkedHashMap<String, String>? = null

    data class Entry(val trigger: String, val replacement: String)

    fun all(): List<Entry> = synchronized(lock) {
        readLocked().map { Entry(it.key, it.value) }.sortedBy { it.trigger.lowercase(Locale.ROOT) }
    }

    fun put(trigger: String, replacement: String) {
        val key = normalizeTrigger(trigger)
        val value = replacement.trimEnd()
        require(key.length in 2..40) { "يجب أن يكون الاختصار بين حرفين و40 حرفاً" }
        require(!key.any(Char::isWhitespace)) { "لا يمكن أن يحتوي الاختصار على مسافات" }
        require(value.isNotBlank() && value.length <= 4000) { "أدخل نصاً بحد أقصى 4000 حرف" }
        synchronized(lock) {
            val map = readLocked()
            map[key] = value
            writeLocked(map)
        }
    }

    fun remove(trigger: String) = synchronized(lock) {
        val map = readLocked()
        map.remove(normalizeTrigger(trigger))
        writeLocked(map)
    }

    fun expand(trigger: String): String? = synchronized(lock) {
        readLocked()[normalizeTrigger(trigger)]
    }

    fun matchBeforeCursor(before: String): Entry? = synchronized(lock) {
        readLocked().entries
            .asSequence()
            .filter { before.endsWith(it.key, ignoreCase = true) }
            .filter { entry ->
                val start = before.length - entry.key.length
                start == 0 || !entry.key.first().isLetterOrDigit() || !PredictionEngine.isWordChar(before[start - 1])
            }
            .maxByOrNull { it.key.length }
            ?.let { Entry(it.key, it.value) }
    }

    private fun readLocked(): LinkedHashMap<String, String> {
        cached?.let { return LinkedHashMap(it) }
        val out = LinkedHashMap<String, String>()
        val raw = prefs.getString(DATA_KEY, null)
        if (raw == null) {
            cached = out
            return LinkedHashMap(out)
        }
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (key.length in 2..40 && value.isNotBlank()) out[normalizeTrigger(key)] = value.take(4000)
            }
        } catch (_: Exception) {
            // A damaged preference should not crash the keyboard; it can be rebuilt by the user.
        }
        cached = out
        return LinkedHashMap(out)
    }

    private fun writeLocked(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        cached = LinkedHashMap(map)
        prefs.edit().putString(DATA_KEY, json.toString()).apply()
    }

    private fun normalizeTrigger(value: String): String = value.trim().lowercase(Locale.ROOT)

    companion object {
        private const val DATA_KEY = "entries_v1"
        @Volatile private var instance: TextExpansionRepo? = null
        fun get(ctx: Context): TextExpansionRepo = instance ?: synchronized(this) {
            instance ?: TextExpansionRepo(ctx.applicationContext).also { instance = it }
        }
    }
}
