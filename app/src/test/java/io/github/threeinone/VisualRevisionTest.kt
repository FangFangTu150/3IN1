package io.github.threeinone

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRevisionTest {
    @Test fun number250AndAutomaticPreferencesPersistAndClamp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("numbers250", 0)
        prefs.edit().putInt("numberScale", 250).putBoolean("autoNumber", true)
            .putInt("numberThreshold", 35).commit()
        val config = IndicatorConfig.read(prefs)
        assertEquals(250, config.numberScale)
        assertTrue(config.autoNumber)
        assertEquals(35, config.numberThreshold)
        prefs.edit().putInt("numberScale", 999).putInt("numberThreshold", -10).commit()
        assertEquals(250, IndicatorConfig.read(prefs).numberScale)
        assertEquals(1, IndicatorConfig.read(prefs).numberThreshold)
    }

    @Test fun automaticRenderingMatchesExplicitModeIncludingCharging() {
        for (phase in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING, BatteryPhase.PAUSED)) {
            for (level in listOf(29, 30, 31)) {
                val state = IndicatorState.demo.copy(battery = level, batteryPhase = phase)
                assertTrue(render(state, IndicatorConfig(autoNumber = true, numberThreshold = 30))
                    .sameAs(render(state, IndicatorConfig(showNumber = level <= 30))))
            }
        }
    }

    @Test fun largeNumbersAndHaloGrowWithoutClipping() {
        val state = IndicatorState.demo.copy(battery = 100)
        val small = render(state, IndicatorConfig(showNumber = true, numberScale = 150))
        val large = render(state, IndicatorConfig(showNumber = true, numberScale = 250))
        fun glyphInk(b: Bitmap) = (1..78).sumOf { y ->
            (60..240).count { x -> Color.alpha(b.getPixel(x, y)) == 255 }
        }
        assertTrue(glyphInk(large) > glyphInk(small) * 1.5)
        assertTrue("Larger halo must fade a wider arc",
            Color.alpha(large.getPixel(72, 57)) < Color.alpha(small.getPixel(72, 57)))
        for (i in 0 until 300) assertEquals(0, Color.alpha(large.getPixel(i, 0)))
        for (y in 135 until 300) for (x in 0 until 300)
            assertEquals(small.getPixel(x, y), large.getPixel(x, y))
    }

    @Test fun automaticNumberContactSheet() {
        val sheet = Bitmap.createBitmap(1000, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
        val cases = listOf(
            Triple("100 · 250%", IndicatorState.demo.copy(battery = 100), IndicatorConfig(showNumber = true, numberScale = 250)),
            Triple("8 · 250%", IndicatorState.demo.copy(battery = 8), IndicatorConfig(showNumber = true, numberScale = 250)),
            Triple("31% · 自动隐藏", IndicatorState.demo.copy(battery = 31), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("30% · 自动显示", IndicatorState.demo.copy(battery = 30), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("充电 30% · 闪电优先", IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("充电 31% · 恢复闪电", IndicatorState.demo.copy(battery = 31, batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250))
        )
        cases.forEachIndexed { row, (name, state, config) ->
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                c.drawRect(mode * 500f, row * 160f, (mode + 1) * 500f, (row + 1) * 160f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                c.drawText(name, mode * 500f + 12, row * 160f + 24, p)
                for ((index, size) in listOf(66f, 110f).withIndex()) {
                    val save = c.save()
                    c.translate(mode * 500f + 175 + index * 160, row * 160f + 40)
                    IndicatorRenderer().draw(c, size, size, state, config, p.color)
                    c.restoreToCount(save)
                }
            }
        }
        save(sheet, "visual-0.1.4.png")
    }

    private fun render(state: IndicatorState, config: IndicatorConfig, tint: Int = Color.BLACK): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, tint)
        }

    @Test fun numbersDoNotShrinkOrShiftWifiAndSignalDots() {
        for (phase in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING)) {
            val state = IndicatorState.demo.copy(batteryPhase = phase)
            val off = render(state, IndicatorConfig())
            val on = render(state, IndicatorConfig(showNumber = true))
            for (y in 90 until 300) for (x in 0 until 300)
                assertEquals("Geometry shifted at $x,$y", off.getPixel(x, y), on.getPixel(x, y))
        }
    }

    @Test fun chargingOverridesNumberAndKeepsGreen() {
        val state = IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.CHARGING)
        for (tint in listOf(Color.BLACK, Color.WHITE)) {
            val b = render(state, IndicatorConfig(showNumber = true), tint)
            val numberPixels = (18..48).flatMap { y -> (115..185).map { x -> b.getPixel(x, y) } }
            assertEquals(0, numberPixels.count { it == tint })
            assertTrue(b.sameAs(render(state, IndicatorConfig(), tint)))
            assertTrue("Arc must retain charging green", (0 until 300).any { y ->
                (0 until 300).any { x -> b.getPixel(x, y) == 0xff1cb753.toInt() }
            })
        }
    }

    @Test fun numberHaloFadesOnlyArcAndKeepsTopContinuous() {
        val state = IndicatorState.demo.copy(battery = 100)
        val off = render(state, IndicatorConfig())
        val on = render(state, IndicatorConfig(showNumber = true))
        // Outside the glyphs but inside their shared halo.
        assertTrue(Color.alpha(on.getPixel(108, 34)) in 1 until Color.alpha(off.getPixel(108, 34)))
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(41, 52, 62))
        IndicatorRenderer().draw(c, 300f, 300f, state, IndicatorConfig(showNumber = true), Color.WHITE)
        for (x in 105..195) assertEquals(255, Color.alpha(b.getPixel(x, 30)))
        assertEquals(Color.rgb(41, 52, 62), b.getPixel(90, 3))
    }

    @Test fun unknownNumberDoesNotInventBatteryProgress() {
        val unknown = IndicatorState.demo.copy(battery = 80, batteryPhase = BatteryPhase.UNKNOWN)
        val negative = unknown.copy(battery = -1)
        assertFalse(render(unknown, IndicatorConfig(showNumber = true))
            .sameAs(render(negative, IndicatorConfig(showNumber = true))))
        assertTrue(render(unknown, IndicatorConfig(showNumber = true))
            .sameAs(render(unknown.copy(batteryPhase = BatteryPhase.NORMAL), IndicatorConfig(showNumber = true))))
    }

    @Test fun networkScale250ActuallyEnlargesGlyphsAndPersists() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("scale250", 0)
        prefs.edit().putInt("textScale", 250).commit()
        assertEquals(250, IndicatorConfig.read(prefs).textScale)
        prefs.edit().putInt("textScale", 900).commit()
        assertEquals(250, IndicatorConfig.read(prefs).textScale)
        for (label in listOf("2G", "3G", "4G", "5G")) {
            val state = IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label)
            fun ink(b: Bitmap) = (90..195).sumOf { y ->
                (65..235).count { x -> Color.alpha(b.getPixel(x, y)) > 128 }
            }
            val normal = ink(render(state, IndicatorConfig(textScale = 150)))
            val large = ink(render(state, IndicatorConfig(textScale = 250)))
            assertTrue("$label must grow, not be silently width-clamped", large > normal * 2)
        }
    }

    @Test fun airplaneHasRoundedNoseSweptWingsAndSeparateTailInBothModes() {
        for (wifi in listOf(WifiPhase.OFF, WifiPhase.CONNECTED)) {
            val b = render(IndicatorState.demo.copy(airplane = true, wifi = wifi), IndicatorConfig())
            assertTrue(Color.alpha(b.getPixel(150, 225)) > 128)
            assertEquals(0, Color.alpha(b.getPixel(133, 225)))
            assertTrue(Color.alpha(b.getPixel(120, 270)) > 128)
            assertTrue(Color.alpha(b.getPixel(180, 270)) > 128)
            assertTrue(Color.alpha(b.getPixel(150, 282)) > 128)
            for (x in 0 until 300) assertEquals(0, Color.alpha(b.getPixel(x, 299)))
        }
    }

    @Test fun updatedStatesAndExtremeParametersContactSheet() {
        val numbered = IndicatorConfig(showNumber = true)
        val cases = listOf(
            Triple("数字 76", IndicatorState.demo, numbered),
            Triple("数字 100 · 最大字号", IndicatorState.demo.copy(battery = 100), numbered.copy(numberScale = 150)),
            Triple("数字 0 · 低电量", IndicatorState.demo.copy(battery = 0), numbered),
            Triple("数字 + 充电", IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), numbered),
            Triple("原充电闪电", IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), IndicatorConfig()),
            Triple("飞行模式", IndicatorState.demo.copy(wifi = WifiPhase.OFF, airplane = true), numbered),
            Triple("飞行模式 + Wi-Fi", IndicatorState.demo.copy(airplane = true), numbered),
            Triple("5G · 250%", IndicatorState.demo.copy(wifi = WifiPhase.OFF), numbered.copy(textScale = 250)),
            Triple("4G · 极端参数", IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "4G"),
                numbered.copy(textScale = 250, numberScale = 150, stroke = 160, dotScale = 140, dotSpacing = 125))
        )
        val sheet = Bitmap.createBitmap(1000, cases.size * 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }
        cases.forEachIndexed { row, (name, state, config) ->
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                canvas.drawRect(mode * 500f, row * 160f, (mode + 1) * 500f, (row + 1) * 160f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                canvas.drawText(name, mode * 500f + 12, row * 160f + 24, p)
                for ((index, size) in listOf(66f, 110f).withIndex()) {
                    val save = canvas.save()
                    canvas.translate(mode * 500f + 175 + index * 160, row * 160f + 40)
                    IndicatorRenderer().draw(canvas, size, size, state, config, p.color)
                    canvas.restoreToCount(save)
                }
            }
            val b = render(state, config)
            for (i in 0 until 300) {
                assertEquals(0, Color.alpha(b.getPixel(0, i)))
                assertEquals(0, Color.alpha(b.getPixel(299, i)))
                assertEquals(0, Color.alpha(b.getPixel(i, 299)))
            }
        }
        save(sheet, "visual-0.1.3.png")
    }

    @Test fun launcherUsesNewAdaptiveArtworkAndMonochromeLayer() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        val b = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(225, 228, 233))
        icon.setBounds(20, 20, 300, 300)
        icon.draw(c)
        icon.monochrome!!.apply {
            setBounds(340, 20, 620, 300)
            setTint(Color.rgb(55, 76, 90))
            draw(c)
        }
        save(b, "launcher-0.1.3.png")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("../DevDoc/qa", name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
