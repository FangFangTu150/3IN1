package io.github.threeinone.hook

import io.github.threeinone.model.IndicatorState

internal class StatePublication(private val changed: (IndicatorState) -> Unit) {
    @Volatile var generation = 0L
        private set
    @Volatile var running = false
        private set
    var current = IndicatorState()
        private set
    private var published: IndicatorState? = null

    fun start() { generation++; running = true; current = IndicatorState(); published = null }
    fun stop() { running = false; generation++ }
    fun accepts(token: Long) = running && token == generation

    fun battery(value: IndicatorState) {
        current = value
        publish()
    }

    fun network(token: Long, value: IndicatorState) {
        if (!accepts(token)) return
        current = value.copy(battery = current.battery, batteryPhase = current.batteryPhase,
            batteryObserved = current.batteryObserved)
        publish()
    }

    private fun publish() {
        if (running && current != published) {
            published = current
            changed(current)
        }
    }
}
