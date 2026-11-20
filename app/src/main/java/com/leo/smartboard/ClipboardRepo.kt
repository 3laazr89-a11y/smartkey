package com.leo.smartboard

import android.content.ContentValues
import android.content.Context

data class Clip(val id: Long, val text: String, val pinned: Boolean, val ts: Long, val uses: Int)

/** Persistent clipboard history holding up to [MAX] entries (pinned entries are never evicted). */
class ClipboardRepo private constructor(ctx: Context) {
    private val db = Db.get(ctx)

    @Synchronized
    fun add(raw: String): Boolean {
        val text = raw.take(MAX_LEN)
        if (text.isBlank()) return false
        val w = db.writableDatabase
        val now = System.currentTimeMillis()
        val updated = w.update("clips", ContentValues().apply { put("ts", now) }, "text=?", arrayOf(text))
        if (updated == 0) {
            w.insert("clips", null, ContentValues().apply {
                put("text", text); put("ts", now); put("pinned", 0); put("uses", 0)
            })
        }
        trim()
        return updated == 0
    }

    private fun trim() {
        val total = count()
        if (total > MAX) {
            val extra = total - MAX
            db.writableDatabase.execSQL("DELETE FROM clips WHERE id IN (SELECT id FROM clips WHERE pinned=0 ORDER BY ts ASC LIMIT $extra)")
        }
    }

    fun count(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM clips", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun list(query: String? = null, pinnedOnly: Boolean = false, limit: Int = MAX): List<Clip> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!query.isNullOrBlank()) { where += "text LIKE ?"; args += "%${query.trim()}%" }
        if (pinnedOnly) where += "pinned=1"
        val sql = "SELECT id,text,pinned,ts,uses FROM clips" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY pinned DESC, ts DESC LIMIT $limit"
        val out = ArrayList<Clip>()
        db.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += Clip(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getLong(3), c.getInt(4))
        }
        return out
    }

    fun setPinned(id: Long, pinned: Boolean) {
        db.writableDatabase.update("clips", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun markUsed(id: Long) {
        db.writableDatabase.execSQL("UPDATE clips SET uses=uses+1, ts=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("clips", "id=?", arrayOf(id.toString()))
    }

    fun clearUnpinned() {
        db.writableDatabase.delete("clips", "pinned=0", null)
    }

    companion object {
        const val MAX = 3000
        const val MAX_LEN = 20000
        @Volatile private var inst: ClipboardRepo? = null
        fun get(ctx: Context): ClipboardRepo = inst ?: synchronized(this) {
            inst ?: ClipboardRepo(ctx.applicationContext).also { inst = it }
        }
    }
}
