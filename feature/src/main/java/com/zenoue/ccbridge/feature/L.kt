package com.zenoue.ccbridge.feature

import java.util.Locale

/**
 * UI language. The app ships Vietnamese and English texts side by side (no resource files in the
 * feature module): Vietnamese when the system language is Vietnamese, English for everything else.
 * The choice is read on every call, so it follows a language change without restarting the app.
 */
object L {
    /** Tests pin the language here; null = follow the system. */
    @Volatile var forceEnglish: Boolean? = null

    val english: Boolean get() = forceEnglish ?: (Locale.getDefault().language != "vi")

    fun t(vi: String, en: String): String = if (english) en else vi
}
