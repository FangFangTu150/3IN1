package io.github.threeinone

import android.content.Intent
import android.os.BatteryManager
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatterySnapshotTest {
    private fun intent(level: Int, status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN, scale: Int = 100) =
        Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale).putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test fun bootUnknownStatusStillHasRealPercentageWithoutCable() {
        var state = BatterySnapshot.apply(IndicatorState.demo, intent(84))
        assertEquals(84, state.battery)
        assertEquals(.84f, state.progress)
        assertEquals(BatteryPhase.UNKNOWN, state.batteryPhase)
        assertTrue(state.batteryObserved)
        state = BatterySnapshot.apply(state, intent(83))
        assertEquals(.83f, state.progress)
        assertTrue(state.description().contains("电量 83%"))
        assertEquals(123, IndicatorConfig().batteryColor(state, 123))
    }

    @Test fun unknownChargeStatusAllowsLowColorAndAutoNumber() {
        val state = BatterySnapshot.apply(IndicatorState.demo, intent(12))
        val config = IndicatorConfig(autoNumber = true)
        assertTrue(config.showsNumber(state))
        assertEquals(config.lowColor, config.batteryColor(state, 123))
        assertEquals(BatteryPhase.UNKNOWN, BatterySnapshot.apply(state,
            intent(12).putExtra(BatteryManager.EXTRA_PLUGGED, 1)).batteryPhase)
    }

    @Test fun realChargeTransitionsAndDuplicateSnapshotsRemainCorrect() {
        var state = BatterySnapshot.apply(IndicatorState.demo, intent(83))
        val charging = intent(83, BatteryManager.BATTERY_STATUS_CHARGING)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 2)
        state = BatterySnapshot.apply(state, charging)
        assertEquals(BatteryPhase.CHARGING, state.batteryPhase)
        assertEquals(state, BatterySnapshot.apply(state, charging))
        state = BatterySnapshot.apply(state, intent(83, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(BatteryPhase.NORMAL, state.batteryPhase)
        assertEquals(83, state.battery)
        assertEquals(BatteryPhase.PAUSED, BatterySnapshot.apply(state,
            intent(83, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 1)).batteryPhase)
        assertEquals(BatteryPhase.FULL,
            BatterySnapshot.apply(state, intent(100, BatteryManager.BATTERY_STATUS_FULL)).batteryPhase)
    }

    @Test fun invalidAbsentAndMissingSnapshotsNeverFabricateProgress() {
        for (input in listOf(intent(-1), intent(50, scale = 0), intent(101),
            intent(50).putExtra(BatteryManager.EXTRA_PRESENT, false), Intent(Intent.ACTION_BATTERY_CHANGED))) {
            val state = BatterySnapshot.apply(IndicatorState.demo, input)
            assertEquals(-1, state.battery)
            assertEquals(0f, state.progress)
            assertFalse(IndicatorConfig(autoNumber = true).showsNumber(state))
        }
        assertEquals(50, BatterySnapshot.apply(IndicatorState(), intent(100, scale = 200)).battery)
    }
}
