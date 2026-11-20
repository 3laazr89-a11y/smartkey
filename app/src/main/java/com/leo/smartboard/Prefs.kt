package com.leo.smartboard

import android.content.Context
import android.content.SharedPreferences

class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("smartboard", Context.MODE_PRIVATE)

    var themeId: String
        get() = sp.getString("theme", "retro")!!
        set(v) = sp.edit().putString("theme", v).apply()
    var lang: String
        get() = sp.getString("lang", "ar")!!
        set(v) = sp.edit().putString("lang", v).apply()
    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", false)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()
    var sound: Boolean
        get() = sp.getBoolean("sound", false)
        set(v) = sp.edit().putBoolean("sound", v).apply()
    var numberRow: Boolean
        get() = sp.getBoolean("number_row", false)
        set(v) = sp.edit().putBoolean("number_row", v).apply()
    var autoCap: Boolean
        get() = sp.getBoolean("auto_cap", true)
        set(v) = sp.edit().putBoolean("auto_cap", v).apply()
    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean("double_space", true)
        set(v) = sp.edit().putBoolean("double_space", v).apply()
    var autoCorrect: Boolean
        get() = sp.getBoolean("auto_correct", false)
        set(v) = sp.edit().putBoolean("auto_correct", v).apply()
    var learnTyping: Boolean
        get() = sp.getBoolean("learn_typing", true)
        set(v) = sp.edit().putBoolean("learn_typing", v).apply()
    var learnClipboard: Boolean
        get() = sp.getBoolean("learn_clip", true)
        set(v) = sp.edit().putBoolean("learn_clip", v).apply()
    var saveClipboard: Boolean
        get() = sp.getBoolean("save_clip", true)
        set(v) = sp.edit().putBoolean("save_clip", v).apply()
    var boldKeys: Boolean
        get() = sp.getBoolean("bold_keys", false)
        set(v) = sp.edit().putBoolean("bold_keys", v).apply()
    var keyHeightDp: Int
        get() = sp.getInt("key_height", 54)
        set(v) = sp.edit().putInt("key_height", v).apply()
    var seeded: Boolean
        get() = sp.getBoolean("seeded_v1", false)
        set(v) = sp.edit().putBoolean("seeded_v1", v).apply()
    var englishDictionarySeeded: Boolean
        get() = sp.getBoolean("english_dictionary_seeded_v1", false)
        set(v) = sp.edit().putBoolean("english_dictionary_seeded_v1", v).apply()
    var recentEmoji: String
        get() = sp.getString("recent_emoji", "")!!
        set(v) = sp.edit().putString("recent_emoji", v).apply()

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(ctx.applicationContext).also { inst = it }
        }
    }
}
