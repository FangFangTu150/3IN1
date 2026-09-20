package io.github.threeinone.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState

class IndicatorView(context: Context) : View(context) {
    private val renderer = IndicatorRenderer()
    var config = IndicatorConfig()
        set(value) { field = value; invalidate() }
    var state = IndicatorState.demo
        set(value) { field = value; contentDescription = value.description(); invalidate() }
    var tint = Color.BLACK
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), state, config, tint)
    }
}
