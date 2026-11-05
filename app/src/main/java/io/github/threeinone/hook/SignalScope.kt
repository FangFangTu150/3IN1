package io.github.threeinone.hook

import android.view.View
import android.view.ViewGroup

internal object SignalScope {
    val slots = setOf("wifi", "mobile", "airplane", "nosim_all", "nosim_one", "nosim_two")
    private const val ICON = "com.android.systemui.statusbar.StatusBarIconView"
    private const val MODERN = "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
    private val iconTypes = setOf(ICON, MODERN,
        "com.oplus.systemui.statusbar.phone.netspeed.widget.NetworkSpeedView",
        "com.oplus.systemui.statusbar.phone.dynamic.icon.OplusStatusBarIconView")
    private const val CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer"
    private val networks = setOf(
        "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
        "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView")

    data class Result(val signals: Set<View>, val supported: Boolean, val rejected: Set<String>)

    fun inspect(root: ViewGroup, strict: Boolean, readSlot: (View) -> String? = ::slot): Result {
        val signals = linkedSetOf<View>()
        val rejected = linkedSetOf<String>()
        var supported = true
        fun visit(group: ViewGroup) {
            val iconChildren = HostScope.isType(group, CONTAINER)
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (HostScope.isType(child, HostScope.BATTERY)) continue
                val knownNetwork = networks.any { HostScope.isType(child, it) }
                val knownIcon = iconTypes.any { HostScope.isType(child, it) }
                if (knownIcon) {
                    val name = runCatching { readSlot(child) }.getOrNull()
                    if (name == null) {
                        supported = false
                        rejected += "${child.javaClass.name}:slot"
                    }
                    else if (knownNetwork || name in slots) signals += child
                } else if (knownNetwork) {
                    signals += child
                } else if (strict && iconChildren) {
                    // Unknown branches inside the icon container cannot be partially hidden.
                    supported = false
                    rejected += child.javaClass.name
                } else if (child is ViewGroup) visit(child)
            }
        }
        visit(root)
        return Result(signals, !strict || supported, rejected)
    }

    fun slot(view: View): String? {
        if (iconTypes.none { HostScope.isType(view, it) }) return null
        return view.javaClass.getMethod("getSlot").invoke(view) as? String
    }
}
