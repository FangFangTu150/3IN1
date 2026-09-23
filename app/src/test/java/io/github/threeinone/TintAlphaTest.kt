package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.model.WifiPhase
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TintAlphaTest {
    @Test fun systemTintAlphaIsPreservedAcrossArcWifiTextAndSignalDots() {
        // Use an empty battery arc so the sampled top pixel is not the
        // composition of the background and progress arcs.
        val arcState = IndicatorState.demo.copy(battery = 0)
        val arcConfig = IndicatorConfig(colors = false)
        val opaqueArc = render(Color.WHITE, arcState, arcConfig)
        val translucentArc = render(Color.argb(128, 255, 255, 255), arcState, arcConfig)
        assertAlphaNear(opaqueArc, translucentArc, 150, 30)

        val wifiOpaque = render(Color.WHITE)
        val wifiTranslucent = render(Color.argb(128, 255, 255, 255))
        assertAlphaNear(wifiOpaque, wifiTranslucent, 150, 195)

        val signalOpaque = render(Color.WHITE)
        val signalTranslucent = render(Color.argb(128, 255, 255, 255))
        assertAlphaNear(signalOpaque, signalTranslucent, 130, 264)

        val textState = IndicatorState.demo.copy(wifi = WifiPhase.OFF)
        val textOpaque = render(Color.WHITE, textState)
        val textTranslucent = render(Color.argb(128, 255, 255, 255), textState)
        assertRegionAlphaNear(textOpaque, textTranslucent, 120, 120, 180, 165)
    }

    @Test fun opaqueBlackTintDoesNotChangeWhenAlphaIsApplied() {
        val opaque = render(Color.BLACK)
        val explicit = render(Color.argb(255, 0, 0, 0))
        for (y in 0 until opaque.height) {
            for (x in 0 until opaque.width) {
                assertEquals("Pixel changed at ($x,$y)", opaque.getPixel(x, y), explicit.getPixel(x, y))
            }
        }
    }

    @Test fun localInactiveAlphaMultipliesTintAlpha() {
        val config = IndicatorConfig()
        val state = IndicatorState.demo.copy(wifi = WifiPhase.CONNECTED, wifiLevel = 0)
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config,
            Color.argb(128, 255, 255, 255))

        val inactiveBand = Color.alpha(bitmap.getPixel(150, 195))
        assertTrue("Inactive Wi-Fi band must include tint alpha", inactiveBand in 20..35)
    }

    @Test fun customChargingColorStaysOpaqueWhenSystemTintIsTranslucent() {
        val state = IndicatorState.demo.copy(batteryPhase = io.github.threeinone.model.BatteryPhase.CHARGING)
        val config = IndicatorConfig()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config,
            Color.argb(128, 255, 255, 255))

        assertEquals(255, Color.alpha(bitmap.getPixel(153, 18)))
    }

    private fun render(tint: Int, state: IndicatorState = IndicatorState.demo,
        config: IndicatorConfig = IndicatorConfig()): Bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
        IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, tint)
    }

    private fun assertAlphaNear(first: Bitmap, second: Bitmap, x: Int, y: Int) {
        val expected = Color.alpha(first.getPixel(x, y))
        val actual = Color.alpha(second.getPixel(x, y))
        assertTrue("Expected a visible reference pixel at ($x,$y)", expected > 0)
        assertTrue("Tint alpha was not preserved at ($x,$y): $expected -> $actual",
            actual in (expected / 2 - 3).coerceAtLeast(0)..(expected / 2 + 3))
    }

    private fun assertRegionAlphaNear(first: Bitmap, second: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int) {
        var bestX = -1
        var bestY = -1
        var expected = 0
        for (y in top..bottom) for (x in left..right) {
            val alpha = Color.alpha(first.getPixel(x, y))
            if (alpha > expected) {
                expected = alpha
                bestX = x
                bestY = y
            }
        }
        assertTrue("Expected a visible text pixel in the selected region", expected > 0)
        val actual = Color.alpha(second.getPixel(bestX, bestY))
        assertTrue("Tint alpha was not preserved at ($bestX,$bestY): $expected -> $actual",
            actual in (expected / 2 - 3).coerceAtLeast(0)..(expected / 2 + 3))
    }
}
