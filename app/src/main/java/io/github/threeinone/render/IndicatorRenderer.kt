package io.github.threeinone.render

import android.graphics.*
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import kotlin.math.min

/** A normalized 100-unit drawing shared by the real status bar and preview. */
class IndicatorRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val path = Path()
    private val lightningHalo = RadialGradient(
        0f, 0f, 18f,
        intArrayOf(0xF0000000.toInt(), 0xE8000000.toInt(), 0x60000000, Color.TRANSPARENT),
        floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP
    )

    fun draw(canvas: Canvas, width: Float, height: Float, state: IndicatorState, config: IndicatorConfig, tint: Int) {
        if (width <= 0 || height <= 0) return
        val scale = min(width / 100f, height / 100f)
        val save = canvas.save()
        try {
            canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
            canvas.scale(scale, scale)
            val showNumber = config.showsNumber(state)
            val number = if (state.battery < 0) "?"
                else state.battery.coerceIn(0, 100).toString()
            val numberSize = 13f * config.numberScale / 100
            prepareText(number, numberSize, tint, 60f)
            val numberBounds = Rect()
            paint.getTextBounds(number, 0, number.length, numberBounds)
            val haloPadding = 9f * config.numberScale / 115f
            val numberHaloWidth = (paint.measureText(number) / 2 + haloPadding).coerceAtLeast(18f)
            val numberHaloHeight = (numberBounds.height() / 2f + haloPadding).coerceAtLeast(18f)
            // Keep the largest digits within the same square without shifting the Wi-Fi.
            val numberCenterY = (numberBounds.height() / 2f + 1f).coerceAtLeast(11f)
            val battery = config.batteryColor(state, tint)
            val line = 5.5f * config.stroke / 100f
            val charging = state.batteryPhase == BatteryPhase.CHARGING
            val full = state.batteryPhase == BatteryPhase.FULL
            // Fade only the arc layer, never the wallpaper or previously drawn content.
            val arcLayer = if (charging || full || showNumber) canvas.saveLayer(0f, -4f, 100f, 100f, null) else -1
            bounds.set(12f, 9f, 88f, 85f)
            stroke(tint, line, 46)
            canvas.drawArc(bounds, 150f, 240f, false, paint)
            stroke(battery, line)
            if (state.progress > 0) canvas.drawArc(bounds, 150f, 240f * state.progress, false, paint)
            if (charging || full || showNumber) {
                topHalo(canvas, if (showNumber) 50f else 51f,
                    if (showNumber) numberHaloWidth else 18f,
                    if (showNumber) numberHaloHeight else 18f,
                    if (showNumber) numberCenterY else 10f)
                canvas.restoreToCount(arcLayer)
            }
            if (showNumber) {
                text(canvas, number, 50f, numberCenterY, numberSize, tint, 60f, centerVertically = true)
            } else if (charging) {
                path.reset()
                path.moveTo(53f, 0f); path.lineTo(44f, 12f); path.lineTo(50f, 12f)
                path.lineTo(47f, 21f); path.lineTo(58f, 8f); path.lineTo(52f, 8f); path.close()
                fill(battery); canvas.drawPath(path, paint)
            } else if (full) {
                stroke(battery, 3.2f)
                path.reset()
                path.moveTo(43f, 10f); path.lineTo(49f, 16f); path.lineTo(59f, 5f)
                canvas.drawPath(path, paint)
            } else if (state.batteryPhase == BatteryPhase.PAUSED) {
                stroke(tint, 2.4f)
                canvas.drawLine(47f, 19f, 47f, 25f, paint)
                canvas.drawLine(53f, 19f, 53f, 25f, paint)
            }

            if (state.wifiConnected || state.wifi == WifiPhase.CONNECTING) {
                val ws = min(config.wifiScale / 115f, 1.16f)
                val wSave = canvas.save()
                canvas.scale(ws, ws, 50f, 53f)
                for (i in 0..2) {
                    val radius = 30f - i * 9f
                    bounds.set(50 - radius, 65 - radius, 50 + radius, 65 + radius)
                    val active = state.wifi != WifiPhase.CONNECTING && state.wifiLevel >= 4 - i
                    stroke(tint, line * .72f, if (active) 255 else 55)
                    canvas.drawArc(bounds, 225f, 90f, false, paint)
                }
                fill(tint, if (state.wifiLevel >= 1 && state.wifi != WifiPhase.CONNECTING) 255 else 55)
                canvas.drawCircle(50f, 65f, 3.1f, paint)
                canvas.restoreToCount(wSave)
                if (state.wifi in setOf(WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE)) {
                    text(canvas, if (state.wifi == WifiPhase.CAPTIVE) "?" else "!", 70f, 65f, 14f, tint)
                } else if (state.wifi == WifiPhase.CONNECTING) {
                    text(canvas, "...", 50f, 73f, 12f, tint)
                }
            } else if (!state.airplane && state.sim == SimPhase.READY) {
                if (state.dataEnabled) {
                    text(canvas, state.networkType.ifBlank { "--" }, 50f, 46f,
                        14f * config.textScale / 100f, tint, 60f, centerVertically = true)
                } else mobileDataOff(canvas, tint)
            } else {
                text(canvas, "--", 50f, 57f, 17f, tint)
            }

            if (state.airplane) {
                airplane(canvas, tint)
            } else if (state.sim == SimPhase.READY || state.sim == SimPhase.NO_SIGNAL) {
                for (i in 0..3) {
                    val x = 50f + (i - 1.5f) * 13f * config.dotSpacing / 100f
                    val y = if (i == 0 || i == 3) 83f else 88f
                    fill(tint, if (i < state.dots) 255 else 55)
                    canvas.drawCircle(x, y, 4.2f * config.dotScale / 100f, paint)
                }
                if (state.sim == SimPhase.NO_SIGNAL) text(canvas, "×", 50f, 94f, 13f, tint)
            } else {
                simMark(canvas, state.sim, tint)
            }
            if (state.roaming && !state.airplane) text(canvas, "R", 78f, 76f, 9f, tint)
            if (state.battery < 0 && !showNumber) text(canvas, "?", 50f, 26f, 12f, tint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun topHalo(c: Canvas, centerX: Float, radiusX: Float, radiusY: Float, centerY: Float) {
        val save = c.save()
        try {
            c.translate(centerX, centerY)
            c.scale(radiusX / 18f, radiusY / 18f)
            fill(Color.BLACK)
            paint.shader = lightningHalo
            paint.blendMode = BlendMode.DST_OUT
            c.drawCircle(0f, 0f, 18f, paint)
        } finally { c.restoreToCount(save) }
    }

    private fun airplane(c: Canvas, color: Int) {
        val save = c.save()
        try {
            c.translate(38.5f, 73.5f)
            c.scale(.23f, .23f)
            path.reset()
            path.moveTo(50f, 0f)
            path.cubicTo(42f, 0f, 40f, 10f, 40f, 20f)
            path.lineTo(40f, 38f); path.lineTo(4f, 65f)
            path.quadTo(0f, 68f, 0f, 75f)
            path.quadTo(0f, 78f, 4f, 77f)
            path.lineTo(40f, 67f); path.lineTo(40f, 88f)
            path.lineTo(31f, 100f); path.quadTo(29f, 103f, 31f, 108f)
            path.lineTo(50f, 100f); path.lineTo(69f, 108f)
            path.quadTo(71f, 103f, 69f, 100f); path.lineTo(60f, 88f)
            path.lineTo(60f, 67f); path.lineTo(96f, 77f)
            path.quadTo(100f, 78f, 100f, 75f)
            path.quadTo(100f, 68f, 96f, 65f); path.lineTo(60f, 38f)
            path.lineTo(60f, 20f); path.cubicTo(60f, 10f, 58f, 0f, 50f, 0f)
            path.close()
            fill(color); c.drawPath(path, paint)
        } finally { c.restoreToCount(save) }
    }

    private fun mobileDataOff(c: Canvas, color: Int) {
        val layer = c.saveLayer(30f, 28f, 70f, 68f, null)
        try {
            fill(color)
            path.reset()
            path.moveTo(47f, 33f); path.lineTo(36f, 44f); path.lineTo(42f, 44f)
            path.lineTo(42f, 61f); path.lineTo(47f, 61f); path.close()
            path.moveTo(53f, 33f); path.lineTo(58f, 33f); path.lineTo(58f, 50f)
            path.lineTo(64f, 50f); path.lineTo(53f, 61f); path.close()
            c.drawPath(path, paint)
            stroke(Color.BLACK, 6f)
            paint.blendMode = BlendMode.DST_OUT
            c.drawLine(35f, 32f, 65f, 62f, paint)
            stroke(color, 2.6f)
            c.drawLine(35f, 32f, 65f, 62f, paint)
        } finally { c.restoreToCount(layer) }
    }

    private fun simMark(c: Canvas, phase: SimPhase, color: Int) {
        if (phase == SimPhase.EMERGENCY || phase == SimPhase.UNKNOWN) {
            text(c, if (phase == SimPhase.EMERGENCY) "SOS" else "?", 50f, 92f, 12f, color)
            return
        }
        stroke(color, 1.8f)
        path.reset(); path.moveTo(43f, 79f); path.lineTo(53f, 79f)
        path.lineTo(58f, 84f); path.lineTo(58f, 96f); path.lineTo(43f, 96f); path.close()
        c.drawPath(path, paint)
        when (phase) {
            SimPhase.LOCKED -> {
                bounds.set(48f, 85f, 54f, 91f); c.drawArc(bounds, 180f, 180f, false, paint)
                c.drawRect(47f, 88f, 55f, 93f, paint)
            }
            SimPhase.DISABLED -> c.drawLine(46f, 88f, 55f, 88f, paint)
            else -> c.drawLine(40f, 96f, 59f, 78f, paint)
        }
    }

    private fun stroke(color: Int, width: Float, alpha: Int = 255) {
        paint.reset(); paint.isAntiAlias = true; paint.color = withAlpha(color, alpha)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }
    private fun fill(color: Int, alpha: Int = 255) {
        paint.reset(); paint.isAntiAlias = true; paint.color = withAlpha(color, alpha)
        paint.style = Paint.Style.FILL
    }
    private fun text(c: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int,
        maxWidth: Float = 100f, centerVertically: Boolean = false) {
        prepareText(value, size, color, maxWidth)
        val y = if (centerVertically) {
            val glyphBounds = Rect()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            baseline - (glyphBounds.top + glyphBounds.bottom) / 2f
        } else baseline
        c.drawText(value, x, y, paint)
    }

    private fun prepareText(value: String, size: Float, color: Int, maxWidth: Float) {
        fill(color); paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = size; paint.textAlign = Paint.Align.CENTER
        val measured = paint.measureText(value)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
    }

    /** Preserve the SystemUI tint alpha while still applying each drawing layer's local alpha. */
    private fun withAlpha(color: Int, localAlpha: Int): Int {
        val alpha = (Color.alpha(color) * localAlpha.coerceIn(0, 255) + 127) / 255
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
