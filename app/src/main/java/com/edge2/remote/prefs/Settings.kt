package com.edge2.remote.prefs

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Local preferences (language + theme), stored in SharedPreferences.
 * Values: language `system|fr|en|es`, theme `system|dark|light`.
 */
object Settings {
    private const val PREFS = "edge2_settings"
    private const val K_LANG = "lang"
    private const val K_THEME = "theme"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lang(c: Context): String = prefs(c).getString(K_LANG, "system") ?: "system"
    fun setLang(c: Context, v: String) = prefs(c).edit().putString(K_LANG, v).apply()

    fun theme(c: Context): String = prefs(c).getString(K_THEME, "system") ?: "system"
    fun setTheme(c: Context, v: String) = prefs(c).edit().putString(K_THEME, v).apply()

    /** Wraps a context with the chosen language (for attachBaseContext). */
    fun wrapLocale(base: Context): Context {
        val lang = lang(base)
        if (lang == "system") return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
