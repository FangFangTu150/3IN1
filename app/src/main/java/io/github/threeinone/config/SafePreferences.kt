package io.github.threeinone.config

import android.content.SharedPreferences

/** A malformed key must not prevent recovery or invalidate unrelated settings. */
internal object SafePreferences {
    fun boolean(p: SharedPreferences, key: String, default: Boolean): Boolean =
        try { p.getBoolean(key, default) } catch (_: ClassCastException) { default }

    fun integer(p: SharedPreferences, key: String, default: Int): Int =
        try { p.getInt(key, default) } catch (_: ClassCastException) { default }

    fun long(p: SharedPreferences, key: String, default: Long): Long =
        try { p.getLong(key, default) } catch (_: ClassCastException) { default }

    fun string(p: SharedPreferences, key: String, default: String?): String? =
        try { p.getString(key, default) } catch (_: ClassCastException) { default }

    fun malformedKeys(p: SharedPreferences): Set<String> = p.all.filter { (key, value) ->
        when (key) {
            in booleans -> value !is Boolean
            in integers -> value !is Int
            "revision" -> value !is Long
            else -> false
        }
    }.keys

    private val booleans = setOf("enabled", "lockscreen", "colors", "showNumber", "autoNumber",
        "centerNumber", PreferenceMigration.MARKER)
    private val integers = setOf("size", "wifiScale", "textScale", "stroke", "dotScale",
        "dotSpacing", "horizontalPadding", "verticalOffset", "chargingColor", "lowColor",
        "saverColor", "lowThreshold", "numberScale", "numberThreshold")
}
