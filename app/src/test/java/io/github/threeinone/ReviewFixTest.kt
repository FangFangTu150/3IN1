package io.github.threeinone

import android.content.pm.PermissionInfo
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.config.storeDiagnosticReport
import io.github.threeinone.hook.AccessibilityOverride
import io.github.threeinone.hook.CoalescedReload
import io.github.threeinone.model.WifiPhase
import io.github.threeinone.model.WifiStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFixTest {
    @Test fun onlyCurrentWifiCapabilitiesDetermineInternetState() {
        val current = NetworkCapabilities()
        shadowOf(current).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        val cellular = NetworkCapabilities()
        shadowOf(cellular).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(cellular).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertEquals(WifiPhase.NO_INTERNET, WifiStatus.phase(true, false, true, current))
        assertEquals(WifiPhase.CONNECTING, WifiStatus.phase(true, false, true, cellular))
        shadowOf(current).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        assertEquals(WifiPhase.CAPTIVE, WifiStatus.phase(true, false, true, current))
        shadowOf(current).removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        shadowOf(current).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertEquals(WifiPhase.CONNECTED, WifiStatus.phase(true, false, true, current))
    }

    @Test fun missingWifiCapabilityDoesNotAssertInternetOrReuseStaleData() {
        assertEquals(WifiPhase.CONNECTING, WifiStatus.phase(true, false, true, null))
        assertEquals(WifiPhase.OFF, WifiStatus.phase(false, false, false, null))
        assertEquals(WifiPhase.DISCONNECTED, WifiStatus.phase(true, false, false, null))
        assertEquals(WifiPhase.CONNECTING, WifiStatus.phase(true, true, true, null))
    }

    @Test fun inactiveHostIsNeverRestoredFromOldSnapshot() {
        val host = LinearLayout(RuntimeEnvironment.getApplication())
        host.contentDescription = "84%"
        val override = AccessibilityOverride(host)
        host.contentDescription = "83%"
        override.restore()
        assertEquals("83%", host.contentDescription)
        host.contentDescription = override.systemDescription("82%")
        override.restore()
        assertEquals("82%", host.contentDescription)
    }

    @Test fun restoreUsesLatestSystemDescriptionAndOnlyOnce() {
        val host = LinearLayout(RuntimeEnvironment.getApplication())
        val child = View(host.context)
        host.addView(child)
        host.contentDescription = "84%"
        val originalImportance = host.importantForAccessibility
        val originalChildImportance = child.importantForAccessibility
        val override = AccessibilityOverride(host)
        override.show("combined")
        host.contentDescription = override.systemDescription("83% charging")
        assertEquals("combined", host.contentDescription)
        override.show("combined updated")
        override.restore()
        assertEquals("83% charging", host.contentDescription)
        assertEquals(originalImportance, host.importantForAccessibility)
        assertEquals(originalChildImportance, child.importantForAccessibility)
        host.contentDescription = "82%"
        override.restore()
        assertEquals("82%", host.contentDescription)
        override.show("new session")
        host.contentDescription = override.systemDescription(null)
        override.restore()
        assertNull(host.contentDescription)
    }

    @Test fun repeatedRefreshCannotMoveFirstDeadline() {
        var calls = 0
        val queue = CoalescedReload(Handler(Looper.getMainLooper()), Runnable { calls++ })
        queue.request()
        repeat(9) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            queue.request()
        }
        assertEquals(0, calls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertEquals(1, calls)
        repeat(100) { queue.request() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(2, calls)
    }

    @Test fun requestDuringReloadSchedulesOneFollowUp() {
        var calls = 0
        lateinit var queue: CoalescedReload
        queue = CoalescedReload(Handler(Looper.getMainLooper()), Runnable {
            calls++
            if (calls == 1) repeat(10) { queue.request() }
        })
        queue.request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(2, calls)
    }

    @Test fun refreshPermissionIsSignatureProtectedAndRequestedByApp() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val permission = pm.getPermissionInfo(IndicatorConfig.REFRESH_PERMISSION, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
        val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(IndicatorConfig.REFRESH_PERMISSION))
    }

    @Test fun diagnosticProbeSnapshotSurvivesLaterUnrelatedStatus() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("diagnostics-token", 0)
        storeDiagnosticReport(prefs, Bundle().apply {
            putString("status", "first")
            putLong("revision", 1)
            putString(IndicatorConfig.PROBE_TOKEN, "token-1")
        })
        storeDiagnosticReport(prefs, Bundle().apply {
            putString("status", "later")
            putLong("revision", 2)
        })
        assertEquals("token-1", prefs.getString(IndicatorConfig.PROBE_TOKEN, null))
        assertEquals("first", prefs.getString(IndicatorConfig.PROBE_STATUS, null))
        assertTrue(prefs.getLong(IndicatorConfig.PROBE_TIME, 0) > 0)
        assertEquals(1, prefs.getLong(IndicatorConfig.PROBE_REVISION, -1))
        assertEquals("later", prefs.getString("status", null))
        assertEquals(2, prefs.getLong("revision", -1))
    }
}
