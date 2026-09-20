package io.github.threeinone

import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import org.junit.Assert.*
import org.junit.Test

class IndicatorStateTest {
    private val demo = IndicatorState.demo
    private val config = IndicatorConfig()

    @Test fun progressClampsWithoutInventingUnknownCharge() {
        assertEquals(0f, demo.copy(battery = 0).progress)
        assertEquals(.01f, demo.copy(battery = 1).progress)
        assertEquals(1f, demo.copy(battery = 100).progress)
        assertEquals(1f, demo.copy(battery = 150).progress)
        assertEquals(0f, demo.copy(battery = -1).progress)
        assertEquals(.76f, demo.copy(batteryPhase = BatteryPhase.UNKNOWN).progress)
    }

    @Test fun chargingWinsThenLowThenSaver() {
        val lowSaver = demo.copy(battery = 12, powerSave = true)
        assertEquals(config.chargingColor, config.batteryColor(lowSaver.copy(batteryPhase = BatteryPhase.CHARGING), 123))
        assertEquals(config.lowColor, config.batteryColor(lowSaver, 123))
        assertEquals(config.saverColor, config.batteryColor(lowSaver.copy(battery = 21), 123))
        assertEquals(config.lowColor, config.batteryColor(demo.copy(battery = 20), 123))
        assertEquals(123, config.batteryColor(demo.copy(battery = 21), 123))
        assertEquals(123, config.copy(colors = false).batteryColor(lowSaver, 123))
    }

    @Test fun pausedDoesNotMasqueradeAsCharging() {
        assertEquals(123, config.batteryColor(demo.copy(batteryPhase = BatteryPhase.PAUSED), 123))
        assertEquals(config.chargingColor, config.batteryColor(demo.copy(batteryPhase = BatteryPhase.FULL), 123))
    }

    @Test fun dotsUseOnlyValidSignalAndRespectAirplane() {
        assertEquals(4, demo.copy(mobileLevel = 8).dots)
        assertEquals(0, demo.copy(mobileLevel = -1).dots)
        assertEquals(2, demo.copy(mobileLevel = 2).dots)
        assertEquals(0, demo.copy(sim = SimPhase.NO_SIGNAL).dots)
        assertEquals(0, demo.copy(airplane = true).dots)
        assertTrue(demo.copy(airplane = true).wifiConnected)
    }

    @Test fun knownUnknownBatteryIsRenderableButUninitializedSourceIsNot() {
        assertFalse(IndicatorState().ready)
        assertTrue(demo.ready)
        assertTrue(demo.copy(battery = -1, batteryPhase = BatteryPhase.UNKNOWN).ready)
        assertFalse(demo.copy(networkReady = false).ready)
    }

    @Test fun networkTypesReflectNsaAndLegacyData() {
        assertEquals("5G", IndicatorState.networkLabel(13, 3))
        assertEquals("5G", IndicatorState.networkLabel(20, 0))
        assertEquals("4G", IndicatorState.networkLabel(13, 0))
        assertEquals("3G", IndicatorState.networkLabel(3, 0))
        assertEquals("2G", IndicatorState.networkLabel(2, 0))
        assertEquals("", IndicatorState.networkLabel(0, 0))
    }

    @Test fun allPreviewStatesHaveDistinctAccessibleDescriptions() {
        assertEquals(IndicatorState.previews.size, IndicatorState.previews.map { it.second.description() }.toSet().size)
        assertTrue(demo.copy(sim = SimPhase.EMERGENCY).description().contains("仅紧急呼叫"))
        assertTrue(demo.copy(airplane = true).description().contains("飞行模式"))
    }
}
