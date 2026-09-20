package io.github.threeinone.model

import android.content.Intent
import android.os.BatteryManager

internal object BatterySnapshot {
    fun apply(state: IndicatorState, intent: Intent): IndicatorState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val valid = present && level >= 0 && scale > 0 && level <= scale
        return state.copy(
            batteryObserved = true,
            battery = if (valid) (level.toLong() * 100 / scale).toInt() else -1,
            batteryPhase = when {
                !valid -> BatteryPhase.UNKNOWN
                status == BatteryManager.BATTERY_STATUS_FULL -> BatteryPhase.FULL
                status == BatteryManager.BATTERY_STATUS_CHARGING -> BatteryPhase.CHARGING
                status == BatteryManager.BATTERY_STATUS_UNKNOWN -> BatteryPhase.UNKNOWN
                plugged -> BatteryPhase.PAUSED
                status in setOf(BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING) -> BatteryPhase.NORMAL
                else -> BatteryPhase.UNKNOWN
            }
        )
    }
}
