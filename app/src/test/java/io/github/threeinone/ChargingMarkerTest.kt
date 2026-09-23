package io.github.threeinone

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import io.github.threeinone.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargingMarkerTest {
    @Test fun legacyConflictIsNormalizedAndControlsRemainExclusive() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(IndicatorConfig.FILE, 0)
        prefs.edit().clear().putBoolean("showNumber", true).putBoolean("autoNumber", true)
            .putInt("numberThreshold", 30).putInt("numberScale", 250).commit()
        assertFalse(IndicatorConfig.read(prefs).autoNumber)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun descendants(v: View): List<View> = listOf(v) +
                if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
                else emptyList()
            val switches = descendants(controller.get().findViewById(android.R.id.content))
                .filterIsInstance<Switch>()
            val always = switches.single { it.text == "始终显示电量数字" }
            val auto = switches.single { it.text == "低于阈值自动显示（含等于）" }
            assertTrue(always.isChecked)
            assertFalse(auto.isChecked)
            assertFalse(prefs.getBoolean("autoNumber", true))
            auto.isChecked = true
            assertFalse(always.isChecked)
            assertTrue(IndicatorConfig.read(prefs).autoNumber)
            always.isChecked = true
            assertFalse(auto.isChecked)
            assertFalse(IndicatorConfig.read(prefs).autoNumber)
            always.isChecked = false
            assertFalse(auto.isChecked)
            assertFalse(IndicatorConfig.read(prefs).showNumber)
            assertEquals(30, prefs.getInt("numberThreshold", 0))
            assertEquals(250, prefs.getInt("numberScale", 0))
            assertFalse(IndicatorConfig.normalizeNumberMode(prefs))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun modeWritePublishesNoConflictingIntermediateState() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("atomic-number", 0)
        prefs.edit().putBoolean("showNumber", true).commit()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            assertFalse(p.getBoolean("showNumber", false) && p.getBoolean("autoNumber", false))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            IndicatorConfig.setNumberMode(prefs, "autoNumber", true)
            IndicatorConfig.setNumberMode(prefs, "showNumber", true)
            IndicatorConfig.setNumberMode(prefs, "showNumber", false)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun render(state: IndicatorState, config: IndicatorConfig): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, Color.BLACK)
        }

    @Test fun fullCheckIsAtTopWithArcOnlyHaloAndUnchangedNetwork() {
        val state = IndicatorState.demo.copy(battery = 100)
        val normal = render(state, IndicatorConfig())
        val full = render(state.copy(batteryPhase = BatteryPhase.FULL), IndicatorConfig(colors = false))
        assertTrue("Check must occupy the top marker", Color.alpha(full.getPixel(147, 45)) > 200)
        assertTrue("Halo must soften but not erase the arc",
            Color.alpha(full.getPixel(114, 33)) in 1 until Color.alpha(normal.getPixel(114, 33)))
        for (y in 90 until 300) for (x in 60..240)
            assertEquals("Network geometry changed", normal.getPixel(x, y), full.getPixel(x, y))
        for (x in 0 until 300) assertEquals(0, Color.alpha(full.getPixel(x, 0)))
        val background = Color.rgb(37, 42, 47)
        val opaque = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(background)
        IndicatorRenderer().draw(canvas, 300f, 300f, state.copy(batteryPhase = BatteryPhase.FULL),
            IndicatorConfig(), Color.WHITE)
        for (y in 0 until 90) for (x in 0 until 300)
            assertEquals(255, Color.alpha(opaque.getPixel(x, y)))
        assertEquals(background, opaque.getPixel(90, 3))
    }

    @Test fun chargingAndFullExtremeContactSheet() {
        val cases = listOf(
            "Auto 30 / unplugged" to IndicatorState.demo.copy(battery = 30),
            "Auto 30 / charging" to IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.CHARGING),
            "Auto 30 / paused" to IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.PAUSED),
            "Full / Wi-Fi" to IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL),
            "Full / 5G 250%" to IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL, wifi = WifiPhase.OFF),
            "Always / charging" to IndicatorState.demo.copy(battery = 10, batteryPhase = BatteryPhase.CHARGING)
        )
        val bitmap = Bitmap.createBitmap(1000, cases.size * 170, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }
        cases.forEachIndexed { row, (label, state) ->
            for (mode in 0..1) {
                paint.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                canvas.drawRect(mode * 500f, row * 170f, (mode + 1) * 500f, (row + 1) * 170f, paint)
                paint.color = if (mode == 0) Color.BLACK else Color.WHITE
                canvas.drawText(label, mode * 500f + 12, row * 170f + 25, paint)
                for ((index, size) in listOf(16f, 32f, 48f, 110f).withIndex()) {
                    val save = canvas.save()
                    canvas.translate(mode * 500f + 25 + index * 110, row * 170f + 48)
                    IndicatorRenderer().draw(canvas, size, size, state,
                        IndicatorConfig(autoNumber = row != 5, showNumber = row == 5,
                            numberThreshold = 100, numberScale = 250, textScale = 250, stroke = 160), paint.color)
                    canvas.restoreToCount(save)
                }
            }
        }
        val file = File("../DevDoc/qa/visual-0.1.8.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
