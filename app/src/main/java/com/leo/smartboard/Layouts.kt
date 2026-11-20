package com.leo.smartboard

import android.graphics.RectF

enum class KType { CHAR, SHIFT, DEL, ENTER, SPACE, SYM, SYM2, ABC, LANG, EMOJI }

class Key(
    val label: String,
    val type: KType = KType.CHAR,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
    /** Colored punctuation key on the bottom row (keycap themes). */
    val punct: Boolean = false,
    /** Small text printed on the keycap; defaults to the first alternative. */
    val hint: String? = null,
) {
    val rect = RectF()
    val isFunctional get() = type != KType.CHAR && type != KType.SPACE
}

enum class Mode { LETTERS, SYM1, SYM2 }

object Layouts {
    private val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val arDigits = listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠")
    private val arabicDiacritics = listOf("ى", "ً", "ّ", "ْ", "ٔ", "ٕ", "ِ", "ـ", "َ", "ُ", "ٰ", "ٓ", "ٖ", "ٌ", "ٍ")

    // First alternative is printed on the keycap (as in the reference skin) and comes first on long-press.
    private val enAlts = mapOf(
        "a" to listOf("@", "à", "á", "â", "ä", "æ", "ã", "å"), "s" to listOf("#", "ß"), "d" to listOf("\$"),
        "f" to listOf("_"), "g" to listOf("&"), "h" to listOf("-"), "j" to listOf("+"), "k" to listOf("("), "l" to listOf(")"),
        "z" to listOf("*"), "x" to listOf("\""), "c" to listOf("'", "ç"), "v" to listOf(":"), "b" to listOf(";"),
        "n" to listOf("!", "ñ"), "m" to listOf("?"),
        "e" to listOf("è", "é", "ê", "ë", "ē"), "i" to listOf("ì", "í", "î", "ï"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "œ"), "u" to listOf("ù", "ú", "û", "ü"), "y" to listOf("ý", "ÿ"),
    )
    private val arAlts = mapOf(
        "ا" to listOf("أ", "إ", "آ", "ء", "ٱ"), "ي" to listOf("ى", "ئ", "ی"), "ه" to listOf("ة", "ھ"),
        "و" to listOf("ؤ"), "ل" to listOf("لا", "لأ", "لإ", "لآ"), "ك" to listOf("گ", "ک"), "ف" to listOf("ڤ"),
        "ج" to listOf("چ"), "ب" to listOf("پ"), "ز" to listOf("ژ"), "ى" to listOf("ئ", "ي"), "ء" to listOf("ئ", "ؤ", "أ", "إ"),
        "ت" to listOf("ة"), "د" to listOf("ذ"), "ر" to listOf("ز"),
        "ش" to listOf("@"), "س" to listOf("#"), "ن" to listOf("&"), "م" to listOf("-"), "ط" to listOf("+"),
        "ذ" to listOf("*"), "ؤ" to listOf("\""), "ة" to listOf(":"), "ظ" to listOf("!"),
    )

    fun build(mode: Mode, lang: String, numberRow: Boolean, retroReference: Boolean = false): List<List<Key>> {
        val ar = lang == "ar"
        return when (mode) {
            Mode.LETTERS -> if (ar) arabic(numberRow, retroReference) else english(numberRow, retroReference)
            Mode.SYM1 -> sym1(ar)
            Mode.SYM2 -> sym2()
        }
    }

    private fun numRow(ar: Boolean) = digits.mapIndexed { i, d -> Key(d, alts = if (ar) listOf(arDigits[i]) else emptyList()) }

    private fun letterRow(chars: String, alts: Map<String, List<String>>, top: Boolean, numberRow: Boolean, ar: Boolean): List<Key> =
        chars.split(" ").mapIndexed { i, s ->
            val a = alts[s].orEmpty()
            // Gboard-style: long-press on the top row types digits when the number row is hidden.
            val withDigit = if (top && !numberRow && i < 10) listOf(if (ar) arDigits[i] else digits[i]) + a else a
            Key(s, alts = withDigit)
        }

    private fun english(numberRow: Boolean, retroReference: Boolean): List<List<Key>> {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += numRow(false)
        rows += letterRow("q w e r t y u i o p", enAlts, true, numberRow, false)
        rows += letterRow("a s d f g h j k l", enAlts, false, numberRow, false)
        rows += listOf(Key("shift", KType.SHIFT, 1.5f)) + letterRow("z x c v b n m", enAlts, false, numberRow, false) + Key("del", KType.DEL, 1.5f)
        rows += bottom(false, 10f, retroReference)
        return rows
    }

    private fun arabic(numberRow: Boolean, retroReference: Boolean): List<List<Key>> {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += arDigits.mapIndexed { i, d -> Key(d, alts = listOf(digits[i])) }
        rows += letterRow("ض ص ث ق ف غ ع ه خ ح ج", arAlts, true, numberRow, true)
        rows += letterRow("ش س ي ب ل ا ت ن م ك ط", arAlts, false, numberRow, true)
        rows += letterRow("ذ ء ؤ ر ى ة و ز ظ د", arAlts, false, numberRow, true) + Key("del", KType.DEL, 1f)
        rows += bottom(true, 11f, retroReference)
        return rows
    }

    private fun bottom(ar: Boolean, total: Float, retroReference: Boolean = false): List<Key> {
        val comma = if (ar) Key("،", alts = listOf(",", "؛", ";"), punct = true) else Key(",", alts = listOf("،", ";"), punct = true)
        val period = if (ar) Key(".", alts = arabicDiacritics + listOf("؟", "!", "?", "…"), punct = true, hint = "َّ")
            else Key(".", alts = listOf("?", "!", "-", "\"", "'", ":", "…", "؟"), punct = true, hint = "!?")
        val languageWeight = if (ar) 1.35f else 1f
        if (retroReference) {
            val space = total - 1.5f - languageWeight - 1f - 1f - 1.5f
            return listOf(
                Key("#1؟", KType.SYM, 1.5f), Key("lang", KType.LANG, languageWeight), comma,
                Key(if (ar) "العربية" else "English", KType.SPACE, space),
                period,
                Key("enter", KType.ENTER, 1.5f)
            )
        }
        val space = total - 1.5f - languageWeight - 1f - 1f - 1.5f
        return listOf(
            Key("?123", KType.SYM, 1.5f), Key("lang", KType.LANG, languageWeight), comma,
            Key(if (ar) "العربية" else "English", KType.SPACE, space),
            period, Key("enter", KType.ENTER, 1.5f)
        )
    }

    /** Numeric keypad modeled after the reference: operators on the sides and a wide zero key. */
    private fun sym1(ar: Boolean): List<List<Key>> {
        val n = if (ar) arDigits else digits
        val comma = if (ar) "،" else ","
        return listOf(
            listOf(Key("+"), Key(n[0]), Key(n[1]), Key(n[2]), Key("-")),
            listOf(Key("*"), Key(n[3]), Key(n[4]), Key(n[5]), Key("%")),
            listOf(Key("/"), Key(n[6]), Key(n[7]), Key(n[8]), Key("del", KType.DEL)),
            listOf(
                Key("ABC", KType.ABC), Key("#1?", KType.SYM2), Key(comma, punct = true),
                Key(n[9], weight = 2f), Key("."), Key("=", alts = listOf("≈"), punct = true),
                Key("enter", KType.ENTER)
            )
        )
    }

    private fun sym2(): List<List<Key>> = listOf(
        "~ ` | • √ π ÷ × ¶ ∆".split(" ").map { Key(it) },
        "£ ¢ € ¥ ^ ° = { } \\".split(" ").map { Key(it) },
        listOf(Key("?123", KType.SYM, 1.5f)) + "% © ® ™ ✓ [ ]".split(" ").map { Key(it) } + Key("del", KType.DEL, 1.5f),
        listOf(
            Key("ABC", KType.ABC, 1.5f), Key("lang", KType.LANG, 1f), Key("<"),
            Key(" ", KType.SPACE, 4f), Key(">"), Key("enter", KType.ENTER, 1.5f)
        ),
    )

    private val symAlts = mapOf(
        "\$" to listOf("€", "£", "¥", "¢", "₹"), "-" to listOf("_", "–", "—", "·"), "+" to listOf("±"),
        "(" to listOf("<", "{", "["), ")" to listOf(">", "}", "]"), "/" to listOf("\\"), "*" to listOf("★", "†", "‡"),
        "\"" to listOf("«", "»", "“", "”"), "'" to listOf("‘", "’", "‚"), "!" to listOf("¡"), "?" to listOf("¿", "؟"),
        "&" to listOf("§"), "#" to listOf("№"),
    )
}
