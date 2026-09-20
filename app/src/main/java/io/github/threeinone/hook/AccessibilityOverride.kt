package io.github.threeinone.hook

import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap

internal class AccessibilityOverride(private val host: ViewGroup) {
    private var owned = false
    private var writing = false
    private var originalDescription: CharSequence? = null
    private var originalImportance = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var combinedDescription: CharSequence? = null
    private val children = IdentityHashMap<View, Int>()

    fun systemDescription(value: CharSequence?): CharSequence? {
        if (writing || !owned) return value
        originalDescription = value
        return combinedDescription
    }

    fun show(description: CharSequence) {
        if (!owned) {
            originalDescription = host.contentDescription
            originalImportance = host.importantForAccessibility
            owned = true
        }
        combinedDescription = description
        write {
            host.contentDescription = description
            host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            for (i in 0 until host.childCount) {
                val child = host.getChildAt(i)
                children.putIfAbsent(child, child.importantForAccessibility)
                child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    }

    fun restore() {
        if (!owned) return
        write {
            host.contentDescription = originalDescription
            host.importantForAccessibility = originalImportance
            children.forEach { (child, importance) -> child.importantForAccessibility = importance }
        }
        children.clear()
        owned = false
    }

    private inline fun write(block: () -> Unit) {
        writing = true
        try { block() } finally { writing = false }
    }
}
