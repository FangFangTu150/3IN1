package io.github.threeinone

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.config.PreferenceMigration
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import io.github.threeinone.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CenterNumberTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
    private val lockedKeys = listOf("wifiScale", "textScale", "showNumber", "autoNumber", "numberThreshold")
    private val message = "已开启电量数字中间常显，请先关闭。"

    private fun render(state: IndicatorState, config: IndicatorConfig, tint: Int = Color.BLACK, size: Int = 300): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), size.toFloat(), size.toFloat(), state, config, tint)
        }

    private fun region(bitmap: Bitmap, top: Int, bottom: Int) =
        Bitmap.createBitmap(bitmap, 60, top, 180, bottom - top)

    private fun center(bitmap: Bitmap) = Bitmap.createBitmap(bitmap, 80, 90, 140, 114)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun defaultMigrationAndTopNumberDecisionRemainCompatible() {
        prefs.edit().clear().commit()
        assertFalse(IndicatorConfig.read(prefs).centerNumber)
        val old = IndicatorConfig(showNumber = true)
        assertTrue(old.showsNumber(IndicatorState.demo))
        for (phase in BatteryPhase.entries) {
            assertFalse(old.copy(centerNumber = true).showsNumber(IndicatorState.demo.copy(batteryPhase = phase)))
        }
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "center-number.xml").apply {
            writeText("""<map><boolean name="centerNumber" value="true"/><boolean name="autoNumber" value="true"/><int name="numberScale" value="170"/></map>""")
        }
        assertTrue(PreferenceMigration.importOnce(prefs, source))
        assertTrue(IndicatorConfig.read(prefs).centerNumber)
        assertTrue(IndicatorConfig.read(prefs).autoNumber)
        assertEquals(170, IndicatorConfig.read(prefs).numberScale)
    }

    @Test fun centerDigitsIgnoreEveryNetworkStateAndLegacyNumberMode() {
        for (level in listOf(-1, 0, 1, 9, 10, 99, 100)) {
            val config = IndicatorConfig(centerNumber = true)
            val base = IndicatorState.demo.copy(battery = level)
            val expected = center(render(base, config))
            for ((name, state) in IndicatorState.previews) {
                for (mode in listOf(config, config.copy(showNumber = true),
                    config.copy(autoNumber = true, numberThreshold = 100))) {
                    val actual = center(render(state.copy(battery = level), mode))
                    assertTrue("$level $name", expected.sameAs(actual))
                }
            }
            val pixels = IntArray(expected.width * expected.height)
            expected.getPixels(pixels, 0, expected.width, 0, 0, expected.width, expected.height)
            assertTrue("Missing center digits at $level", pixels.any { Color.alpha(it) > 0 })
        }
    }

    @Test fun topMarkersAndBottomSignalsRemainUnchangedAndUnknownIsNotRepeated() {
        for (phase in BatteryPhase.entries) {
            val state = IndicatorState.demo.copy(batteryPhase = phase)
            val original = render(state, IndicatorConfig())
            val center = render(state, IndicatorConfig(centerNumber = true, showNumber = true))
            assertTrue("Top marker $phase", region(original, 0, 90).sameAs(region(center, 0, 90)))
            assertTrue("Bottom signal $phase", region(original, 234, 300).sameAs(region(center, 234, 300)))
        }
        val unknown = render(IndicatorState.demo.copy(battery = -1, batteryPhase = BatteryPhase.UNKNOWN),
            IndicatorConfig(centerNumber = true))
        assertEquals(0, Color.alpha(unknown.getPixel(150, 72)))
    }

    @Test fun centerDigitsAlignWithBatteryArcCenter() {
        for (level in listOf(0, 1, 9, 10, 76, 79, 99, 100, -1)) {
            for (scale in listOf(75, 115, 250)) for (phase in BatteryPhase.entries) {
                val bitmap = render(IndicatorState.demo.copy(battery = level, batteryPhase = phase),
                    IndicatorConfig(centerNumber = true, numberScale = scale, stroke = 60))
                // This inner region excludes the arc, top markers and bottom signals.
                val rows = (90 until 195).filter { y ->
                    (60 until 240).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 }
                }
                assertTrue("Missing digits: $level / $scale / $phase", rows.isNotEmpty())
                assertEquals("Arc alignment: $level / $scale / $phase",
                    47f, (rows.first() + rows.last()) / 6f, 1f)
                bitmap.recycle()
            }
        }
    }

    @Test fun centerDigitsPreserveFrameAndBackgroundAcrossSizesAndStyles() {
        for (size in listOf(16, 22, 32)) for (scale in listOf(75, 115, 250)) {
            for (stroke in listOf(60, 160)) for (tint in listOf(Color.BLACK, Color.WHITE, 0x66345678)) {
                for (phase in BatteryPhase.entries) {
                    val state = IndicatorState.demo.copy(battery = 100, batteryPhase = phase)
                    val config = IndicatorConfig(numberScale = scale, stroke = stroke)
                    val pixels = size * 3
                    val original = render(state, config, tint, pixels)
                    val actual = render(state, config.copy(centerNumber = true), tint, pixels)
                    val background = if (tint == Color.WHITE) Color.rgb(21, 25, 27) else Color.WHITE
                    val composed = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                    Canvas(composed).apply {
                        drawColor(background)
                        drawBitmap(actual, 0f, 0f, null)
                    }
                    val label = "$size dp / $scale / $stroke / $tint / $phase"
                    for (y in 0 until pixels) for (x in 0 until pixels) {
                        if (x < pixels * .18f || x >= pixels * .82f ||
                            y < pixels * .28f || y >= pixels * .68f) {
                            assertEquals("Frame changed: $label at $x,$y",
                                original.getPixel(x, y), actual.getPixel(x, y))
                        }
                        assertEquals("Background erased: $label", 255, Color.alpha(composed.getPixel(x, y)))
                        if (x == 0 || x == pixels - 1 || y == pixels - 1) {
                            assertEquals("Clipped icon: $label at $x,$y", background, composed.getPixel(x, y))
                        }
                    }
                    original.recycle()
                    actual.recycle()
                    composed.recycle()
                }
            }
        }
    }

    @Test fun centerScaleGrowsWithinSafeBoundsAndPreservesTintAlpha() {
        val state = IndicatorState.demo.copy(battery = 100)
        fun pixelCount(bitmap: Bitmap): Int {
            val pixels = IntArray(180 * 120)
            bitmap.getPixels(pixels, 0, 180, 60, 90, 180, 120)
            return pixels.count { Color.alpha(it) > 0 }
        }
        val small = render(state, IndicatorConfig(centerNumber = true, numberScale = 75))
        val big = render(state, IndicatorConfig(centerNumber = true, numberScale = 250))
        assertTrue(pixelCount(big) > pixelCount(small) * 3)
        for (y in 90 until 210) for (x in 80 until 220) {
            if (Color.alpha(big.getPixel(x, y)) > 0) assertTrue(y in 105..195)
        }
        val translucent = render(state, IndicatorConfig(centerNumber = true, numberScale = 250), 0x66345678)
        for (y in 90 until 210) for (x in 80 until 220) {
            assertTrue(Color.alpha(translucent.getPixel(x, y)) <= 0x66)
        }
        for (size in listOf(16, 22, 32)) for (tint in listOf(Color.BLACK, Color.WHITE)) {
            val result = render(state, IndicatorConfig(centerNumber = true, numberScale = 250, stroke = 160), tint, size * 3)
            assertEquals(0, Color.alpha(result.getPixel(0, size)))
            assertEquals(0, Color.alpha(result.getPixel(size * 3 - 1, size)))
        }
    }

    @Test fun restrictedRowsBlockTouchKeyboardAccessibilityAndWrites() {
        prefs.edit().clear().putBoolean("centerNumber", true).putBoolean("showNumber", true)
            .putInt("wifiScale", 123).putInt("textScale", 180).putInt("numberThreshold", 35).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2556, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1179, 2556)
            val before = prefs.all.toMap()
            for (key in lockedKeys) {
                val row = root.findViewWithTag<ViewGroup>(key)
                assertTrue(row.isFocusable)
                assertTrue(row.isClickable)
                assertTrue(row.contentDescription.contains(message))
                assertTrue(descendants(row).drop(1).all { !it.isEnabled })
                row.performClick()
                assertEquals(message, ShadowToast.getTextOfLatestToast())
                val time = android.os.SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time + action * 10, action, 20f, 20f, 0)
                    row.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                descendants(row).filterIsInstance<Switch>().forEach {
                    val checked = it.isChecked
                    it.performClick()
                    assertEquals(checked, it.isChecked)
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
                    assertEquals(checked, it.isChecked)
                }
                descendants(row).filterIsInstance<SeekBar>().forEach {
                    it.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f) })
                    assertEquals(before, prefs.all)
                }
            }
            assertEquals(before, prefs.all)
            val size = root.findViewWithTag<ViewGroup>("numberScale")
            assertTrue(descendants(size).all { it.isEnabled })
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-xhdpi")
    fun lockedSettingsFitNarrowScreenWithLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        captureLockedSettings(640, 1480, "center-settings-narrow.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-night-xxhdpi")
    fun lockedSettingsFitLandscape() {
        captureLockedSettings(2556, 1179, "center-settings-landscape.png")
    }

    private fun captureLockedSettings(width: Int, height: Int, name: String) {
        prefs.edit().clear().putBoolean("centerNumber", true).putBoolean("autoNumber", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            descendants(root).filterIsInstance<TextView>().filter { it.layout != null }.forEach { label ->
                for (line in 0 until label.layout.lineCount) {
                    assertTrue("Clipped label ${label.text}", label.layout.getLineWidth(line) <= label.width + 2)
                }
            }
            val row = root.findViewWithTag<View>("centerNumber")
            descendants(root).filterIsInstance<ScrollView>().single().scrollTo(0, row.top)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("../DevDoc/qa", name)
            file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun switchingAndRecreatingPreservesAllThreeLegacyModes() {
        for (mode in listOf("showNumber", "autoNumber", "neither")) {
            prefs.edit().clear().putBoolean(mode, true).putInt("wifiScale", 130)
                .putInt("textScale", 200).putInt("numberThreshold", 30).putInt("numberScale", 150).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                fun root() = controller.get().findViewById<ViewGroup>(android.R.id.content)
                root().findViewWithTag<Switch>("centerNumber").performClick()
                assertTrue(IndicatorConfig.read(prefs).centerNumber)
                controller.recreate()
                assertTrue(root().findViewWithTag<Switch>("centerNumber").isChecked)
                assertFalse(descendants(root().findViewWithTag("wifiScale")).drop(1).any { it.isEnabled })
                root().findViewWithTag<Switch>("centerNumber").performClick()
                assertFalse(IndicatorConfig.read(prefs).centerNumber)
                assertEquals(mode == "showNumber", IndicatorConfig.read(prefs).showNumber)
                assertEquals(mode == "autoNumber", IndicatorConfig.read(prefs).autoNumber)
                assertEquals(130, IndicatorConfig.read(prefs).wifiScale)
                assertEquals(200, IndicatorConfig.read(prefs).textScale)
                assertEquals(30, IndicatorConfig.read(prefs).numberThreshold)
                assertEquals(150, IndicatorConfig.read(prefs).numberScale)
                assertTrue(descendants(root().findViewWithTag("wifiScale")).drop(1).all { it.isEnabled })
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun resetAndRecoveryKeepTheirExistingMeaning() {
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("centerNumber", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun views() = descendants(controller.get().findViewById(android.R.id.content))
            views().filterIsInstance<Button>().single { it.text == "恢复原图标" }.performClick()
            assertFalse(IndicatorConfig.read(prefs).enabled)
            assertTrue(IndicatorConfig.read(prefs).centerNumber)
            views().single { it.contentDescription == "恢复默认" }.performClick()
            (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog)
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(IndicatorConfig.read(prefs).centerNumber)
            assertFalse(IndicatorConfig.read(prefs).enabled)
            assertTrue(prefs.getBoolean(PreferenceMigration.MARKER, false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun producesCenterModeContactSheet() {
        val cases = listOf(0, 1, 9, 10, 99, 100, -1, 76, 79)
        val sheet = Bitmap.createBitmap(840, cases.size * 150, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
        for ((row, battery) in cases.withIndex()) for (theme in 0..1) {
            paint.color = if (theme == 0) Color.WHITE else Color.rgb(21, 25, 27)
            canvas.drawRect(theme * 420f, row * 150f, (theme + 1) * 420f, (row + 1) * 150f, paint)
            paint.color = if (theme == 0) Color.BLACK else Color.WHITE
            canvas.drawText("Battery $battery", theme * 420f + 10, row * 150f + 24, paint)
            for ((column, phase) in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING,
                BatteryPhase.FULL, BatteryPhase.PAUSED).withIndex()) {
                val save = canvas.save()
                canvas.translate(theme * 420f + column * 100f + 12, row * 150f + 40)
                IndicatorRenderer().draw(canvas, 90f, 90f,
                    IndicatorState.demo.copy(battery = battery, batteryPhase = phase),
                    IndicatorConfig(centerNumber = true, showNumber = true, numberScale = 250, stroke = 160), paint.color)
                canvas.restoreToCount(save)
            }
        }
        val file = File("../DevDoc/qa/center-number.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
