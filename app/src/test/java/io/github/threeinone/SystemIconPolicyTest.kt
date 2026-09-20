package io.github.threeinone

import io.github.threeinone.hook.SystemIconPolicy
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.IndicatorState
import org.junit.Assert.*
import org.junit.Test

class SystemIconPolicyTest {
    @Test fun outsideTintAreaUsesLightIconsRegardlessOfGlobalIntensity() {
        assertEquals(0f, SystemIconPolicy.effectiveIntensity(1f, false))
        assertEquals(0f, SystemIconPolicy.effectiveIntensity(0f, true))
        assertEquals(.35f, SystemIconPolicy.effectiveIntensity(.35f, true))
        assertEquals(1f, SystemIconPolicy.effectiveIntensity(1f, true))
    }

    @Test fun exclusionsReleaseWidthWithoutChangingExistingIgnoredSlots() {
        val slots = mutableListOf("alarm_clock", "wifi")
        val exclusion = SystemIconPolicy.SlotExclusion(slots, listOf("wifi", "mobile", "mobile"))
        assertEquals(listOf("alarm_clock", "wifi", "mobile"), slots)
        slots.add("bluetooth")
        exclusion.restore()
        assertEquals(listOf("alarm_clock", "wifi", "bluetooth"), slots)
    }

    @Test fun nestedLayoutAndExceptionRestoreOnlyTheirOwnExclusions() {
        val slots = mutableListOf("alarm_clock")
        val outer = SystemIconPolicy.SlotExclusion(slots, listOf("wifi"))
        try {
            val inner = SystemIconPolicy.SlotExclusion(slots, listOf("wifi", "mobile"))
            try { throw IllegalStateException("layout") }
            finally { inner.restore() }
        } catch (_: IllegalStateException) {
            assertEquals(listOf("alarm_clock", "wifi"), slots)
        } finally { outer.restore() }
        assertEquals(listOf("alarm_clock"), slots)
    }

    @Test fun normalBatteryFollowsTintButChargingRetainsItsStateColor() {
        val config = IndicatorConfig()
        for (tint in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
            assertEquals(tint, config.batteryColor(IndicatorState.demo.copy(batteryPhase = BatteryPhase.NORMAL), tint))
            assertEquals(0xff1cb753.toInt(),
                config.batteryColor(IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), tint))
        }
    }
}
