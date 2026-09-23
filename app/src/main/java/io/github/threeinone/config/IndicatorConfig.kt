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
    val numberThreshold: Int = 20
) {
    fun showsNumber(state: IndicatorState): Boolean =
        state.batteryPhase != BatteryPhase.CHARGING && state.batteryPhase != BatteryPhase.FULL && (showNumber ||
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
            if (!p.getBoolean("showNumber", false) || !p.getBoolean("autoNumber", false)) return false
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
            enabled = p.getBoolean("enabled", false), lockscreen = p.getBoolean("lockscreen", true),
            size = p.getInt("size", 22).coerceIn(16, 32),
            wifiScale = p.getInt("wifiScale", 115).coerceIn(70, 150),
            textScale = p.getInt("textScale", 150).coerceIn(80, 250),
            stroke = p.getInt("stroke", 120).coerceIn(60, 160),
            dotScale = p.getInt("dotScale", 100).coerceIn(60, 140),
            dotSpacing = p.getInt("dotSpacing", 100).coerceIn(70, 125),
            horizontalPadding = p.getInt("horizontalPadding", 2).coerceIn(0, 8),
            verticalOffset = p.getInt("verticalOffset", 0).coerceIn(-4, 4),
            colors = p.getBoolean("colors", true),
            chargingColor = p.getInt("chargingColor", 0xFF1CB753.toInt()) or 0xFF000000.toInt(),
            lowColor = p.getInt("lowColor", 0xFFE7433A.toInt()) or 0xFF000000.toInt(),
            saverColor = p.getInt("saverColor", 0xFFFFB300.toInt()) or 0xFF000000.toInt(),
            lowThreshold = p.getInt("lowThreshold", 20).coerceIn(5, 50),
            showNumber = p.getBoolean("showNumber", false),
            numberScale = p.getInt("numberScale", 115).coerceIn(75, 250),
            autoNumber = !p.getBoolean("showNumber", false) && p.getBoolean("autoNumber", false),
            numberThreshold = p.getInt("numberThreshold", 20).coerceIn(1, 100)
        )
    }
}
