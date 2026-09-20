package io.github.threeinone

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Spinner
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
@Config(sdk = [34], qualifiers = "zh-rCN-w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingTest {
    @Test fun allStatesRenderOnLightAndDarkWithoutOverflow() {
        val states = IndicatorState.previews
        val sheet = Bitmap.createBitmap(1000, states.size * 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val renderer = IndicatorRenderer()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK }
        states.forEachIndexed { index, (name, state) ->
            val y = index * 120f
            p.color = Color.WHITE; c.drawRect(0f, y, 500f, y + 120, p)
            p.color = Color.rgb(21, 25, 27); c.drawRect(500f, y, 1000f, y + 120, p)
            p.color = Color.BLACK; c.drawText(name, 12f, y + 28, p)
            for (mode in 0..1) {
                val tint = if (mode == 0) Color.BLACK else Color.WHITE
                for ((position, size) in listOf(22, 66, 100).withIndex()) {
                    val save = c.save()
                    c.translate(mode * 500 + 220f + position * 80, y + (120 - size) / 2)
                    renderer.draw(c, size.toFloat(), size.toFloat(), state, IndicatorConfig(), tint)
                    c.restoreToCount(save)
                }
            }
        }
        save(sheet, "states.png")
        for ((_, state) in states) {
            val b = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(b), 80f, 80f, state, IndicatorConfig(stroke = 160, wifiScale = 150,
                dotScale = 140, dotSpacing = 125, textScale = 170), Color.BLACK)
            val pixels = IntArray(80 * 80); b.getPixels(pixels, 0, 80, 0, 0, 80, 80)
            assertTrue("Blank state: $state", pixels.count { Color.alpha(it) > 0 } > 100)
            for (x in 0 until 80) {
                assertEquals("Left overflow", 0, Color.alpha(b.getPixel(0, x)))
                assertEquals("Right overflow", 0, Color.alpha(b.getPixel(79, x)))
            }
        }
    }

    @Test fun chargingArcRemainsContinuousAtTop() {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f,
            IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(), Color.BLACK)
        // Top-center path is never erased for the lightning symbol.
        for (x in 135..165) assertTrue(Color.alpha(bitmap.getPixel(x, 28)) > 0)
    }

    @Test fun chargingHaloFadesArcWithoutErasingBackgroundOrAddingProgress() {
        val normal = render(IndicatorState.demo.copy(battery = 100))
        val charging = render(IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.CHARGING))
        val original = Color.alpha(normal.getPixel(126, 30))
        val faded = Color.alpha(charging.getPixel(126, 30))
        assertTrue("Arc next to bolt must remain faint but visible", faded in 1 until original / 2)
        val emptyNormal = render(IndicatorState.demo.copy(battery = 0))
        val emptyCharging = render(IndicatorState.demo.copy(battery = 0, batteryPhase = BatteryPhase.CHARGING))
        assertTrue(Color.alpha(emptyCharging.getPixel(126, 30)) < Color.alpha(emptyNormal.getPixel(126, 30)))
        for (background in listOf(Color.WHITE, Color.rgb(21, 25, 27))) {
            val composed = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val c = Canvas(composed)
            c.drawColor(background)
            IndicatorRenderer().draw(c, 300f, 300f,
                IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(), Color.BLACK)
            assertEquals("Halo must not erase the host background", 255, Color.alpha(composed.getPixel(126, 30)))
            assertEquals(background, composed.getPixel(90, 3))
        }
    }

    @Test fun wifiHasFourSeparatelyLitLevels() {
        val yPositions = listOf(65, 53, 44, 35)
        for (level in 0..4) {
            val b = render(IndicatorState.demo.copy(wifiLevel = level), IndicatorConfig(wifiScale = 115))
            yPositions.forEachIndexed { i, y ->
                val alpha = Color.alpha(b.getPixel(150, y * 3))
                if (i < level) assertEquals("Active Wi-Fi band $i at level $level", 255, alpha)
                else assertEquals("Inactive Wi-Fi band $i at level $level", 55, alpha)
            }
        }
    }

    @Test fun dataOffUsesArrowsIndependentOfNetworkGeneration() {
        val state = IndicatorState.demo.copy(wifi = WifiPhase.OFF, dataEnabled = false)
        val four = render(state.copy(networkType = "4G"))
        val five = render(state.copy(networkType = "5G"))
        assertTrue("Data-off symbol must not contain 4G/5G text", four.sameAs(five))
        assertTrue(Color.alpha(four.getPixel(44 * 3, 55 * 3)) > 200)
        assertTrue(Color.alpha(four.getPixel(56 * 3, 39 * 3)) > 200)
        assertTrue(Color.alpha(four.getPixel(50 * 3, 47 * 3)) > 200)
    }

    @Test fun networkLabelsAreOpticallyCenteredAboveSignalDots() {
        for (label in listOf("2G", "3G", "4G", "5G")) {
            val b = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label))
            val rows = (90..195).filter { y -> (90..210).any { x -> Color.alpha(b.getPixel(x, y)) > 128 } }
            assertTrue(rows.isNotEmpty())
            assertEquals(46f, (rows.first() + rows.last()) / 6f, 1f)
        }
    }

    @Test fun revisedStatesContactSheet() {
        val states = listOf(
            "充电 · 四格 Wi-Fi" to IndicatorState.demo.copy(battery = 56, batteryPhase = BatteryPhase.CHARGING),
            "Wi-Fi 未连接 · 5G" to IndicatorState.demo.copy(wifi = WifiPhase.OFF),
            "Wi-Fi 未连接 · 4G" to IndicatorState.demo.copy(wifi = WifiPhase.DISCONNECTED, networkType = "4G"),
            "移动数据关闭" to IndicatorState.demo.copy(wifi = WifiPhase.OFF, dataEnabled = false)
        )
        val bitmap = Bitmap.createBitmap(1000, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
        for ((row, pair) in states.withIndex()) {
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                c.drawRect(mode * 500f, row * 180f, (mode + 1) * 500f, (row + 1) * 180f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                c.drawText(pair.first, mode * 500f + 20, row * 180f + 30, p)
                for ((index, size) in listOf(66f, 120f).withIndex()) {
                    val saved = c.save()
                    c.translate(mode * 500f + 160 + index * 180, row * 180f + 45)
                    IndicatorRenderer().draw(c, size, size, pair.second, IndicatorConfig(), p.color)
                    c.restoreToCount(saved)
                }
            }
        }
        save(bitmap, "revised-states.png")
    }

    private fun render(state: IndicatorState, config: IndicatorConfig = IndicatorConfig()): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config, Color.BLACK)
        return bitmap
    }

    @Test fun configRangesClampCorruptOrOldPreferences() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", 0)
        prefs.edit().putInt("size", 1000).putInt("dotSpacing", -100).putInt("verticalOffset", -90).commit()
        val c = IndicatorConfig.read(prefs)
        assertEquals(32, c.size); assertEquals(70, c.dotSpacing); assertEquals(-4, c.verticalOffset)
        assertFalse(c.enabled)
    }

    @Test fun settingsHasWorkingControlsAndCapturesPortrait() {
        captureActivity("settings-light.png", 1179, 2556)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night-xxhdpi")
    fun settingsDarkTheme() {
        captureActivity("settings-dark.png", 1179, 2556)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-xxhdpi")
    fun settingsLandscape() {
        captureActivity("settings-landscape.png", 2556, 1179)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-xhdpi")
    fun settingsLargeTextOnNarrowScreen() {
        RuntimeEnvironment.setFontScale(1.3f)
        captureActivity("settings-large-text.png", 640, 1480)
    }

    private fun captureActivity(name: String, width: Int, height: Int) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val views = descendants(root)
            assertEquals(11, views.count { it is SeekBar })
            assertEquals(5, views.count { it is Switch })
            val spinner = views.filterIsInstance<Spinner>().single()
            spinner.setSelection(1)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            save(bitmap, name)
            val labelViews = views.filterIsInstance<android.widget.TextView>()
            labelViews.filter { it.layout != null }.forEach {
                val layout = it.layout
                for (line in 0 until layout.lineCount) {
                    assertTrue("Clipped label: ${it.text}", layout.getLineWidth(line) <= it.width + 2)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(group: ViewGroup): List<View> = buildList {
        for (i in 0 until group.childCount) {
            val view = group.getChildAt(i)
            add(view); if (view is ViewGroup) addAll(descendants(view))
        }
    }
    private fun save(bitmap: Bitmap, name: String) {
        val file = File("../DevDoc/qa", name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
