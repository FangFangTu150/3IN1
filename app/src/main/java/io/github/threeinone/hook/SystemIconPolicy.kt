package io.github.threeinone.hook

internal object SystemIconPolicy {
    fun effectiveIntensity(intensity: Float, inTintArea: Boolean): Float =
        if (inTintArea) intensity.coerceIn(0f, 1f) else 0f

    // Limit exclusions to one SystemUI measure/layout call; never own its slot list.
    class SlotExclusion(private val slots: MutableList<String>, excluded: Collection<String>) {
        private val added = excluded.distinct().filterNot { it in slots }
        init { slots.addAll(added) }
        fun restore() { slots.removeAll(added.toSet()) }
    }
}
