package com.leo.smartboard

import android.content.Context

/** A keyboard color theme. All colors are ARGB ints. */
data class KbTheme(
    val id: String,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val key: Int,
    val keyFn: Int,
    val keyPressed: Int,
    val text: Int,
    val textFn: Int,
    val accent: Int,
    val accentText: Int,
    val bar: Int,
    val barText: Int,
    val shadow: Boolean = true,
    val borderless: Boolean = false,
    /** 3D mechanical keycaps (raised face + darker side), like the reference skin. */
    val keycap: Boolean = false,
    /** Always show English letters in capitals on the keycaps. */
    val capsLabels: Boolean = false,
    val emojiKey: Int? = null,
    val punctKey: Int? = null,
    val enterKeyColor: Int? = null,
    val shiftKeyColor: Int? = null,
    val languageKeyColor: Int? = null,
)

private fun c(hex: Long): Int = hex.toInt()

object Themes {
    val all: List<KbTheme> = listOf(
        KbTheme("retro", "ريترو", c(0xFF1C1E21), c(0xFF16181A), c(0xFFF3F3F1), c(0xFF5E7684), c(0xFFDADAD6),
            c(0xFF2F3337), c(0xFF22303A), c(0xFF2F7BF5), c(0xFFFFE14D), c(0xFF1C1E21), c(0xFFE9EAEC),
            keycap = true, capsLabels = false, emojiKey = c(0xFFF5A524), punctKey = c(0xFFEF5A5F)),
        KbTheme("retro_mint", "ريترو نعناعي", c(0xFF20302C), c(0xFF17231F), c(0xFFF1F4EE), c(0xFF4E8C7A), c(0xFFD7DDD2),
            c(0xFF26332F), c(0xFF0F2A22), c(0xFFF2994A), c(0xFF1B1B1B), c(0xFF20302C), c(0xFFE8F0EC),
            keycap = true, capsLabels = false, emojiKey = c(0xFFF2C94C), punctKey = c(0xFF6FCF97)),
        KbTheme("retro_dark", "ريترو داكن", c(0xFF0F1012), c(0xFF0A0B0C), c(0xFF34373C), c(0xFF4B5563), c(0xFF4A4E55),
            c(0xFFEDEDED), c(0xFFEDEDED), c(0xFFE74C3C), c(0xFFFFFFFF), c(0xFF0F1012), c(0xFFEDEDED),
            keycap = true, capsLabels = false, emojiKey = c(0xFFF39C12), punctKey = c(0xFF16A085)),
        KbTheme("retro_3d_compose", "ريترو ثلاثي الأبعاد", c(0xFF17191B), c(0xFF111315), c(0xFFF5F5F3), c(0xFF78949D), c(0xFFD8DADC),
            c(0xFF202124), c(0xFF263238), c(0xFF2D87D1), c(0xFF163B56), c(0xFF17191B), c(0xFFF0F1F2),
            keycap = true, capsLabels = false, emojiKey = c(0xFFF5A524), punctKey = c(0xFF6C5CE7),
            enterKeyColor = c(0xFF006D77), shiftKeyColor = c(0xFF7651A8), languageKeyColor = c(0xFFF2C14E)),
        KbTheme("mint_vibrant", "نعناعي حيوي", c(0xFF7BE1CC), c(0xFF73DCC5), c(0xFFFCFCFC), c(0xFF00836F), c(0xFFD8F4ED),
            c(0xFF082D29), c(0xFF063A34), c(0xFF00836F), c(0xFF063A34), c(0xFF7BE1CC), c(0xFF073B35),
            shadow = true),
        KbTheme("material_dark", "داكن", c(0xFF202124), c(0xFF202124), c(0xFF3C4043), c(0xFF2D2F31), c(0xFF5F6368),
            c(0xFFE8EAED), c(0xFFE8EAED), c(0xFF8AB4F8), c(0xFF202124), c(0xFF202124), c(0xFFE8EAED)),
        KbTheme("material_light", "فاتح", c(0xFFE8EAED), c(0xFFE8EAED), c(0xFFFFFFFF), c(0xFFD2D5DA), c(0xFFBDC1C6),
            c(0xFF202124), c(0xFF202124), c(0xFF1A73E8), c(0xFFFFFFFF), c(0xFFE8EAED), c(0xFF3C4043)),
        KbTheme("ios", "iOS", c(0xFFD9DDE2), c(0xFFCDD2D8), c(0xFFFFFFFF), c(0xFFB5BBC2), c(0xFFA5ADB6),
            c(0xFF1C1C1E), c(0xFF24272B), c(0xFF0A84FF), c(0xFFFFFFFF), c(0xFFD4D8DD), c(0xFF202124),
            shadow = true),
        KbTheme("ios_dark", "iOS داكن", c(0xFF1C1C1E), c(0xFF141416), c(0xFF3A3A3C), c(0xFF48484A), c(0xFF555559),
            c(0xFFF5F5F7), c(0xFFD1D1D6), c(0xFF0A84FF), c(0xFFFFFFFF), c(0xFF1C1C1E), c(0xFFF5F5F7),
            shadow = true),
        KbTheme("amoled", "أسود نقي", c(0xFF000000), c(0xFF000000), c(0xFF141414), c(0xFF0A0A0A), c(0xFF2A2A2A),
            c(0xFFFFFFFF), c(0xFFBDBDBD), c(0xFF00E5A0), c(0xFF000000), c(0xFF000000), c(0xFFFFFFFF), shadow = false),
        KbTheme("ocean", "محيط", c(0xFF0B3D5C), c(0xFF06223A), c(0xFF145377), c(0xFF0E3F5E), c(0xFF1E6A96),
            c(0xFFE6F6FF), c(0xFFB3E1FF), c(0xFF4FD1FF), c(0xFF06223A), c(0xFF0B3D5C), c(0xFFE6F6FF)),
        KbTheme("sunset", "غروب", c(0xFFFF7E5F), c(0xFF8E3E86), c(0x33FFFFFF), c(0x22000000), c(0x66FFFFFF),
            c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFFFFE08A), c(0xFF5A1E4F), c(0xFFFF7E5F), c(0xFFFFFFFF), shadow = false),
        KbTheme("forest", "غابة", c(0xFF1B3A2B), c(0xFF0F2419), c(0xFF2C5641), c(0xFF214533), c(0xFF3F7459),
            c(0xFFEAF7EE), c(0xFFC4E5CF), c(0xFF9BE15D), c(0xFF0F2419), c(0xFF1B3A2B), c(0xFFEAF7EE)),
        KbTheme("sand", "رملي", c(0xFFF4ECDD), c(0xFFE9DCC4), c(0xFFFFFBF3), c(0xFFDCCBA9), c(0xFFCDB78E),
            c(0xFF3B2F22), c(0xFF3B2F22), c(0xFFB5651D), c(0xFFFFFBF3), c(0xFFF4ECDD), c(0xFF3B2F22)),
        KbTheme("rose", "وردي", c(0xFFFCE4EC), c(0xFFF8BBD0), c(0xFFFFFFFF), c(0xFFF48FB1), c(0xFFF06292),
            c(0xFF4A1A2C), c(0xFF4A1A2C), c(0xFFD81B60), c(0xFFFFFFFF), c(0xFFFCE4EC), c(0xFF4A1A2C)),
        KbTheme("neon", "نيون", c(0xFF0D0221), c(0xFF190B36), c(0xFF1F1147), c(0xFF140A33), c(0xFF3A1F7A),
            c(0xFF2DE2E6), c(0xFFFF3864), c(0xFFFF3864), c(0xFF0D0221), c(0xFF0D0221), c(0xFF2DE2E6)),
        KbTheme("gold", "ذهبي", c(0xFF1A1A1A), c(0xFF0D0D0D), c(0xFF2A2620), c(0xFF1F1C17), c(0xFF4A4232),
            c(0xFFF5E6C4), c(0xFFD4AF37), c(0xFFD4AF37), c(0xFF1A1A1A), c(0xFF1A1A1A), c(0xFFD4AF37)),
        KbTheme("glass", "زجاجي", c(0xFF667EEA), c(0xFF38B2AC), c(0x40FFFFFF), c(0x26FFFFFF), c(0x80FFFFFF),
            c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFF2D3A8C), c(0xFF667EEA), c(0xFFFFFFFF), shadow = false),
        KbTheme("minimal", "بلا حدود", c(0xFF2B2B2B), c(0xFF2B2B2B), c(0xFF2B2B2B), c(0xFF2B2B2B), c(0xFF444444),
            c(0xFFF2F2F2), c(0xFFAAAAAA), c(0xFFFFB74D), c(0xFF2B2B2B), c(0xFF2B2B2B), c(0xFFF2F2F2), shadow = false, borderless = true),
    )

    fun byId(id: String?): KbTheme = all.firstOrNull { it.id == id } ?: all[0]
    fun current(ctx: Context): KbTheme = byId(Prefs.get(ctx).themeId)
}
