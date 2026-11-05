package io.github.threeinone.config

import android.content.SharedPreferences
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.IndicatorState

data class IndicatorConfig(
    val enabled: Boolean = false,
    val lockscreen: Boolean = true,
    val size: Int = 22,
    val wifiScale: Int = 115,
    val textScale: Int = 150,
    val stroke: Int = 120,
    val dotScale: Int = 100,
    val dotSpacing: Int = 100,
    val horizontalPadding: Int = 2,
    val verticalOffset: Int = 0,
    val colors: Boolean = true,
    val chargingColor: Int = 0xFF1CB753.toInt(),
    val lowColor: Int = 0xFFE7433A.toInt(),
    val saverColor: Int = 0xFFFFB300.toInt(),
    val lowThreshold: Int = 20,
    val showNumber: Boolean = false,
    val numberScale: Int = 115,
    val autoNumber: Boolean = false,
    val numberThreshold: Int = 20,
    val centerNumber: Boolean = false
) {
    fun showsNumber(state: IndicatorState): Boolean =
        !centerNumber && state.batteryPhase != BatteryPhase.CHARGING && state.batteryPhase != BatteryPhase.FULL && (showNumber ||
        (autoNumber &&
            state.battery in 0..numberThreshold.coerceIn(1, 100)))

    fun batteryColor(state: IndicatorState, tint: Int): Int = when {
        !colors || state.battery < 0 -> tint
        state.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL) -> chargingColor
        state.battery in 0..lowThreshold -> lowColor
        state.powerSave -> saverColor
        else -> tint
    }
    companion object {
        const val PACKAGE = "io.github.threeinone"
        const val FILE = "indicator"
        const val REFRESH = "$PACKAGE.REFRESH"
        const val REFRESH_PERMISSION = "$PACKAGE.permission.REFRESH"
        const val PROBE_TOKEN = "probeToken"
        const val PROBE_STATUS = "probeStatus"
        const val PROBE_TIME = "probeTime"
        const val PROBE_REVISION = "probeRevision"
        fun normalizeNumberMode(p: SharedPreferences): Boolean {
            if (!SafePreferences.boolean(p, "showNumber", false) || !SafePreferences.boolean(p, "autoNumber", false)) return false
            p.edit().putBoolean("autoNumber", false).apply()
            return true
        }

        fun setNumberMode(p: SharedPreferences, key: String, enabled: Boolean) {
            require(key == "showNumber" || key == "autoNumber")
            val other = if (key == "showNumber") "autoNumber" else "showNumber"
            val editor = p.edit().putBoolean(key, enabled)
            if (enabled) editor.putBoolean(other, false)
            editor.apply()
        }
        fun read(p: SharedPreferences) = IndicatorConfig(
            enabled = SafePreferences.boolean(p, "enabled", false), lockscreen = SafePreferences.boolean(p, "lockscreen", true),
            size = SafePreferences.integer(p, "size", 22).coerceIn(16, 32),
            wifiScale = SafePreferences.integer(p, "wifiScale", 115).coerceIn(70, 150),
            textScale = SafePreferences.integer(p, "textScale", 150).coerceIn(80, 250),
            stroke = SafePreferences.integer(p, "stroke", 120).coerceIn(60, 160),
            dotScale = SafePreferences.integer(p, "dotScale", 100).coerceIn(60, 140),
            dotSpacing = SafePreferences.integer(p, "dotSpacing", 100).coerceIn(70, 125),
            horizontalPadding = SafePreferences.integer(p, "horizontalPadding", 2).coerceIn(0, 8),
            verticalOffset = SafePreferences.integer(p, "verticalOffset", 0).coerceIn(-4, 4),
            colors = SafePreferences.boolean(p, "colors", true),
            chargingColor = SafePreferences.integer(p, "chargingColor", 0xFF1CB753.toInt()) or 0xFF000000.toInt(),
            lowColor = SafePreferences.integer(p, "lowColor", 0xFFE7433A.toInt()) or 0xFF000000.toInt(),
            saverColor = SafePreferences.integer(p, "saverColor", 0xFFFFB300.toInt()) or 0xFF000000.toInt(),
            lowThreshold = SafePreferences.integer(p, "lowThreshold", 20).coerceIn(5, 50),
            showNumber = SafePreferences.boolean(p, "showNumber", false),
            numberScale = SafePreferences.integer(p, "numberScale", 115).coerceIn(75, 250),
            autoNumber = !SafePreferences.boolean(p, "showNumber", false) && SafePreferences.boolean(p, "autoNumber", false),
            numberThreshold = SafePreferences.integer(p, "numberThreshold", 20).coerceIn(1, 100),
            centerNumber = SafePreferences.boolean(p, "centerNumber", false)
        )
    }
}
