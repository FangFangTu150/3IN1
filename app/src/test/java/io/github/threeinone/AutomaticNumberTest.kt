package io.github.threeinone

import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.IndicatorState
import org.junit.Assert.*
import org.junit.Test

class AutomaticNumberTest {
    @Test fun thresholdAppliesDuringDischargeAndRecharge() {
        val config = IndicatorConfig(autoNumber = true, numberThreshold = 30)
        for (phase in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING, BatteryPhase.PAUSED)) {
            for (level in listOf(31, 30, 29, 0, 29, 30, 31)) {
                assertEquals("$phase $level", level <= 30 && phase != BatteryPhase.CHARGING,
                    config.showsNumber(IndicatorState.demo.copy(battery = level, batteryPhase = phase)))
            }
        }
    }

    @Test fun alwaysShowOverridesAutomaticAndUnknownIsNotLowBattery() {
        val unknown = IndicatorState.demo.copy(battery = -1, batteryPhase = BatteryPhase.UNKNOWN)
        assertFalse(IndicatorConfig(autoNumber = true).showsNumber(unknown))
        assertTrue(IndicatorConfig(autoNumber = true).showsNumber(unknown.copy(battery = 10)))
        assertFalse(IndicatorConfig().showsNumber(IndicatorState.demo.copy(battery = 0)))
        assertTrue(IndicatorConfig(showNumber = true, autoNumber = true).showsNumber(unknown))
        assertTrue(IndicatorConfig(showNumber = true, autoNumber = true).showsNumber(IndicatorState.demo))
    }

    @Test fun thresholdEndpointsAndFullCharge() {
        assertTrue(IndicatorConfig(autoNumber = true, numberThreshold = 1)
            .showsNumber(IndicatorState.demo.copy(battery = 1)))
        val full = IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL)
        assertFalse(IndicatorConfig(autoNumber = true, numberThreshold = 99).showsNumber(full))
        assertFalse(IndicatorConfig(autoNumber = true, numberThreshold = 100).showsNumber(full))
    }

    @Test fun chargingAndFullOverrideEveryNumberMode() {
        for (config in listOf(IndicatorConfig(), IndicatorConfig(showNumber = true),
            IndicatorConfig(autoNumber = true, numberThreshold = 100))) {
            for (phase in listOf(BatteryPhase.CHARGING, BatteryPhase.FULL)) {
                for (level in listOf(-1, 0, 29, 30, 31, 100)) {
                    assertFalse(config.showsNumber(IndicatorState.demo.copy(battery = level, batteryPhase = phase)))
                }
            }
        }
    }
}
