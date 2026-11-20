package com.leo.smartboard

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Reads words from common SQLite dictionary schemas without modifying the source DB. */
object DictionaryDbImporter {
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_WORDS = 300_000
    private const val MAX_ROWS_PER_COLUMN = 400_000
    private const val MAX_CELL_CHARS = 2_048

    private val preferredColumns = setOf(
        "word", "words", "term", "token", "entry", "lemma", "vocab", "vocabulary",
        "value", "text", "الكلمة", "كلمة"
    )
    private val metadataColumns = setOf(
        "id", "_id", "freq", "frequency", "count", "rank", "score", "weight", "pos", "tag", "language", "lang"
    )

    /** Copies a SAF document into cache, opens it read-only, and returns unique word tokens. */
    fun readWords(context: Context, uri: Uri): Set<String> {
        val temp = File.createTempFile("smartboard-dict-", ".db", context.cacheDir)
        try {
            copyBounded(context, uri, temp)
            val db = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val words = LinkedHashSet<String>()
                var rowsRead = 0
                val tableNames = ArrayList<String>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' " +
                        "ORDER BY CASE WHEN lower(name) LIKE '%word%' OR lower(name) LIKE '%dict%' OR " +
                        "lower(name) LIKE '%vocab%' OR lower(name) LIKE '%lex%' OR lower(name) LIKE '%term%' THEN 0 ELSE 1 END, name",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) tableNames += cursor.getString(0)
                }

                for (table in tableNames) {
                    if (words.size >= MAX_WORDS || rowsRead >= MAX_ROWS_PER_COLUMN) break
                    val quotedTable = quoteIdentifier(table)
                    val columns = ArrayList<Pair<String, String>>()
                    db.rawQuery("PRAGMA table_info($quotedTable)", null).use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        val typeIndex = cursor.getColumnIndex("type")
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex) ?: continue
                            val type = if (typeIndex >= 0) cursor.getString(typeIndex).orEmpty() else ""
                            columns += name to type
                        }
                    }
                    if (columns.isEmpty()) continue
                    val preferred = columns.filter { normalizeName(it.first) in preferredColumns }
                    val textLike = columns.filter { (name, type) ->
                        val n = normalizeName(name)
                        val affinity = type.uppercase(Locale.ROOT)
                        n !in metadataColumns && (type.isBlank() || listOf("TEXT", "CHAR", "CLOB", "STRING", "BLOB", "BYTE").any { affinity.contains(it) })
                    }
                    val fallback = columns.filter { normalizeName(it.first) !in metadataColumns }
                    val selected = (if (preferred.isNotEmpty()) preferred else textLike.ifEmpty { fallback }).distinctBy { it.first }
                    for ((column, _) in selected) {
                        if (words.size >= MAX_WORDS || rowsRead >= MAX_ROWS_PER_COLUMN) break
                        val quotedColumn = quoteIdentifier(column)
                        db.rawQuery(
                            "SELECT $quotedColumn FROM $quotedTable WHERE $quotedColumn IS NOT NULL LIMIT $MAX_ROWS_PER_COLUMN",
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                rowsRead++
                                val cell = cursor.getString(0)?.takeIf { it.length <= MAX_CELL_CHARS } ?: continue
                                for (token in PredictionEngine.tokenize(cell)) {
                                    val word = PredictionEngine.canon(token)
                                    if (word.length in 1..32 && word.any(Char::isLetter)) words.add(word)
                                    if (words.size >= MAX_WORDS) break
                                }
                                if (words.size >= MAX_WORDS || rowsRead >= MAX_ROWS_PER_COLUMN) break
                            }
                        }
                    }
                }
                require(words.isNotEmpty()) {
                    "لم أجد كلمات في جداول SQLite. يجب أن يحتوي الملف على أعمدة نصية مثل word أو term أو text."
                }
                return words
            } finally {
                db.close()
            }
        } finally {
            temp.delete()
        }
    }

    private fun copyBounded(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح ملف DB")
        input.use { source ->
            FileOutputStream(target).use { destination ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FILE_BYTES) { "حجم ملف DB يتجاوز 64MB" }
                    destination.write(buffer, 0, count)
                }
            }
        }
    }

    private fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun normalizeName(name: String): String = name.trim().lowercase(Locale.ROOT)
}
