package io.github.threeinone.hook

import android.view.View
import android.view.ViewGroup

internal enum class HostKind {
    HOME, KEYGUARD, SHADE;

    fun enabled(lockscreen: Boolean): Boolean = this != KEYGUARD || lockscreen
}

internal data class HostScope(val root: ViewGroup, val kind: HostKind) {
    companion object {
        const val BATTERY = "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView"
        private const val HOME = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
        private const val KEYGUARD = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
        private const val HEADER = "com.oplus.systemui.qs.OplusQuickStatusBarHeader"
        private const val ICONS = "com.android.systemui.statusbar.phone.StatusIconContainer"

        fun resolve(host: ViewGroup): HostScope? {
            if (!isType(host, BATTERY)) return null
            var ancestor = host.parent as? View
            while (ancestor != null) {
                if (ancestor is ViewGroup) {
                    if (isType(ancestor, HOME)) return HostScope(ancestor, HostKind.HOME)
                    if (isType(ancestor, KEYGUARD)) return HostScope(ancestor, HostKind.KEYGUARD)
                    if (isSystemId(ancestor, "quick_qs_status_icons")) return shade(host, ancestor)
                }
                ancestor = ancestor.parent as? View
            }
            return null
        }

        private fun shade(host: ViewGroup, group: ViewGroup): HostScope? {
            if (!isSystemId(host, "batteryRemainingIcon")) return null
            val children = descendants(group).toList()
            if (children.filter { isType(it, BATTERY) }.singleOrNull() !== host) return null
            if (children.count { isType(it, ICONS) && isSystemId(it, "statusIcons") } != 1) return null
            var ancestor = group.parent as? View
            while (ancestor != null) {
                if (isType(ancestor, HEADER) ||
                    (ancestor is ViewGroup && isSystemId(ancestor, "split_shade_status_bar"))) {
                    return HostScope(group, HostKind.SHADE)
                }
                ancestor = ancestor.parent as? View
            }
            return null
        }

        private fun descendants(group: ViewGroup): Sequence<View> = sequence {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                yield(child)
                if (child is ViewGroup) yieldAll(descendants(child))
            }
        }

        internal fun isSystemId(view: View, name: String): Boolean =
            view.id != View.NO_ID && runCatching {
                view.resources.getResourcePackageName(view.id) == "com.android.systemui" &&
                    view.resources.getResourceEntryName(view.id) == name
            }.getOrDefault(false)

        internal fun isType(view: View, name: String): Boolean {
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if (type.name == name) return true
                type = type.superclass
            }
            return false
        }
    }
}
