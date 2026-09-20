package io.github.threeinone.config

import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * LSPosed redirects the preferences directory after activation. Import settings
 * saved before activation once, without relying on hidden Context APIs.
 */
object PreferenceMigration {
    const val MARKER = "_private_imported_v1"
    private val booleanKeys = setOf("enabled", "lockscreen", "colors", "showNumber", "autoNumber")
    private val integerKeys = setOf("size", "wifiScale", "textScale", "stroke", "dotScale",
        "dotSpacing", "horizontalPadding", "verticalOffset", "chargingColor", "lowColor",
        "saverColor", "lowThreshold", "numberScale", "numberThreshold")

    fun importOnce(destination: SharedPreferences, legacyFile: File): Boolean {
        if (destination.getBoolean(MARKER, false)) return false
        // An existing destination is authoritative, including an explicit disabled state.
        if (destination.all.isNotEmpty()) {
            destination.edit().putBoolean(MARKER, true).apply()
            return false
        }
        if (!legacyFile.isFile || legacyFile.length() > 65_536) return false
        val values = linkedMapOf<String, Any>()
        runCatching {
            legacyFile.inputStream().use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    val key = parser.getAttributeValue(null, "name") ?: continue
                    val value = parser.getAttributeValue(null, "value") ?: continue
                    when {
                        key in booleanKeys && parser.name == "boolean" ->
                            values[key] = requireNotNull(value.toBooleanStrictOrNull())
                        key in integerKeys && parser.name == "int" -> values[key] = value.toInt()
                        key == "revision" && parser.name == "long" -> values[key] = value.toLong()
                    }
                }
            }
        }.getOrElse { return false }
        if (values.isEmpty()) return false
        val editor = destination.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.putBoolean(MARKER, true).apply()
        return true
    }
}
