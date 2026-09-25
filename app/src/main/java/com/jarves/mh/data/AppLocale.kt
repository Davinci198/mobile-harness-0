package com.jarves.mh.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * App language selection backed by the platform per-app locale service (Android 13+).
 * Two locales only: English and Romanian. The system default follows the device language.
 */
object AppLocale {
    const val TAG_SYSTEM = "system"
    const val TAG_ENGLISH = "en"
    const val TAG_ROMANIAN = "ro"

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun currentTag(context: Context): String {
        if (!isSupported) return TAG_SYSTEM
        val manager = context.getSystemService(LocaleManager::class.java) ?: return TAG_SYSTEM
        val locales = manager.applicationLocales
        if (locales.isEmpty) return TAG_SYSTEM
        return locales[0].language
    }

    fun set(context: Context, tag: String) {
        if (!isSupported) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = if (tag == TAG_SYSTEM) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tag)
        }
    }
}
