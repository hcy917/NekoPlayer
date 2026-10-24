package moe.shimmerfly.nekoplayer.data.preferences

import androidx.datastore.preferences.core.floatPreferencesKey

const val MIN_LYRICS_FONT_SIZE_SP = 16f
const val MAX_LYRICS_FONT_SIZE_SP = 42f
const val DEFAULT_LYRICS_FONT_SIZE_SP = 22f

/** Base lyrics text size in sp, shared by the settings repository and the lyrics sheet. */
val LYRICS_FONT_SIZE_SP_KEY = floatPreferencesKey("lyrics_font_size_sp")

fun sanitizeLyricsFontSize(fontSizeSp: Float): Float =
    if (fontSizeSp.isNaN()) {
        DEFAULT_LYRICS_FONT_SIZE_SP
    } else {
        fontSizeSp.coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)
    }
