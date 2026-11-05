package io.github.threeinone

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.systemui.statusbar.phone.*
import com.oplus.systemui.qs.OplusQuickStatusBarHeader
import com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView
import io.github.threeinone.hook.HostKind
import io.github.threeinone.hook.HostScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostScopeTest {
    private val names = mapOf(101 to "quick_qs_status_icons", 102 to "batteryRemainingIcon",
        103 to "statusIcons", 104 to "split_shade_status_bar")
    private var resourcePackage = "com.android.systemui"
    private val context: Context by lazy {
        val base = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val resources = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getResourcePackageName(id: Int): String =
                if (id in names) resourcePackage else super.getResourcePackageName(id)
            override fun getResourceEntryName(id: Int): String = names[id] ?: super.getResourceEntryName(id)
        }
        object : ContextWrapper(base) {
            override fun getResources() = resources
        }
    }

    private fun group(parent: ViewGroup): Pair<ViewGroup, StatBatteryMeterView> {
        val group = LinearLayout(context).apply { id = 101 }
        val icons = LinearLayout(context).apply {
            addView(StatusIconContainer(context).apply { id = 103 })
        }
        val battery = StatBatteryMeterView(context).apply { id = 102 }
        group.addView(icons)
        group.addView(battery)
        parent.addView(group)
        return group to battery
    }

    @Test fun homeAndKeyguardSubclassesAreClassifiedIndependently() {
        val home = object : PhoneStatusBarView(context) {}
        val lock = object : KeyguardStatusBarView(context) {}
        for ((root, kind) in listOf(home to HostKind.HOME, lock to HostKind.KEYGUARD)) {
            val battery = StatBatteryMeterView(context)
            root.addView(battery)
            assertEquals(HostScope(root, kind), HostScope.resolve(battery))
        }
        assertTrue(HostKind.HOME.enabled(false))
        assertTrue(HostKind.SHADE.enabled(false))
        assertFalse(HostKind.KEYGUARD.enabled(false))
        assertTrue(HostKind.KEYGUARD.enabled(true))
    }

    @Test fun ordinaryAndSplitHeadersUseOnlyTheirOwnIconGroup() {
        for (root in listOf(OplusQuickStatusBarHeader(context),
            LinearLayout(context).apply { id = 104 })) {
            val (group, battery) = group(root)
            assertEquals(HostScope(group, HostKind.SHADE), HostScope.resolve(battery))
            assertNotSame(root, HostScope.resolve(battery)?.root)
        }
    }

    @Test fun simultaneousHeadersNeverShareAnExclusionRoot() {
        val window = LinearLayout(context)
        val first = OplusQuickStatusBarHeader(context)
        val second = LinearLayout(context).apply { id = 104 }
        window.addView(first)
        window.addView(second)
        val a = group(first)
        val b = group(second)
        assertSame(a.first, HostScope.resolve(a.second)?.root)
        assertSame(b.first, HostScope.resolve(b.second)?.root)
        assertNotSame(HostScope.resolve(a.second)?.root, HostScope.resolve(b.second)?.root)
        repeat(20) {
            a.first.removeView(a.second)
            assertNull(HostScope.resolve(a.second))
            a.first.addView(a.second)
            assertSame(a.first, HostScope.resolve(a.second)?.root)
        }
    }

    @Test fun rejectsMissingIconsDuplicateBatteriesAndWrongBatteryIdentity() {
        val (group, battery) = group(OplusQuickStatusBarHeader(context))
        val icons = group.getChildAt(0) as ViewGroup
        val status = icons.getChildAt(0)
        icons.removeAllViews()
        assertNull(HostScope.resolve(battery))
        icons.addView(status)
        val duplicate = StatBatteryMeterView(context).apply { id = 102 }
        group.addView(duplicate)
        assertNull(HostScope.resolve(battery))
        assertNull(HostScope.resolve(duplicate))
        group.removeView(duplicate)
        battery.id = View.NO_ID
        assertNull(HostScope.resolve(battery))
        battery.id = 102
        assertNotNull(HostScope.resolve(battery))
    }

    @Test fun rejectsUnknownParentsForeignResourcesAndNonBatteryViews() {
        val root = LinearLayout(context)
        val (_, battery) = group(root)
        assertNull(HostScope.resolve(battery))
        root.id = 104
        assertNotNull(HostScope.resolve(battery))
        resourcePackage = "other.app"
        assertNull(HostScope.resolve(battery))
        resourcePackage = "com.android.systemui"
        val impostor = LinearLayout(context).apply { id = 102 }
        (battery.parent as ViewGroup).addView(impostor)
        assertNull(HostScope.resolve(impostor))
    }
}
