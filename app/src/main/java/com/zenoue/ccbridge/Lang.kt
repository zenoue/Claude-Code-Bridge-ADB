package com.zenoue.ccbridge

import java.util.Locale

/** UI language of the APK shell: Vietnamese on a Vietnamese system, English otherwise (same rule as the feature). */
object Lang {
    fun t(vi: String, en: String): String = if (Locale.getDefault().language == "vi") vi else en
}
