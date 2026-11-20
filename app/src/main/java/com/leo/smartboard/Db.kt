package com.leo.smartboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "smartboard.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL UNIQUE, pinned INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL, uses INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX clips_ts ON clips(pinned, ts)")
        db.execSQL("CREATE TABLE uni(w TEXT PRIMARY KEY, c INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE bi(a TEXT NOT NULL, b TEXT NOT NULL, c INTEGER NOT NULL, PRIMARY KEY(a,b))")
        db.execSQL("CREATE TABLE tri(a TEXT NOT NULL, b TEXT NOT NULL, w TEXT NOT NULL, c INTEGER NOT NULL, PRIMARY KEY(a,b,w))")
        createDictionaryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDictionaryTables(db)
    }

    private fun createDictionaryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionaries(name TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1, category TEXT NOT NULL DEFAULT 'عام')")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_words(dictionary TEXT NOT NULL, word TEXT NOT NULL, normalized TEXT NOT NULL, PRIMARY KEY(dictionary, word))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_words_normalized ON dictionary_words(normalized, dictionary)")
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}
