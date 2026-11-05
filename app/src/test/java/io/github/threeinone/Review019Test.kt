package io.github.threeinone

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.TelephonyDisplayInfo
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.config.PreferenceMigration
import io.github.threeinone.config.SafePreferences
import io.github.threeinone.hook.*
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.ui.MainActivity
import io.github.threeinone.ui.SaveStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Review019Test {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun field(activity: MainActivity, name: String): Any =
        requireNotNull(MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity))
    private fun broadcasts() = shadowOf(app).broadcastIntents.filter { it.action == IndicatorConfig.REFRESH }

    @Test fun recoveryImmediatelyFollowedByDestroyStillNotifiesSystemUi() {
        prefs.edit().clear().putBoolean("enabled", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val before = broadcasts().size
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            .single { it.text == "恢复原图标" }.performClick()
        val writer = field(activity, "writer") as ExecutorService
        controller.pause().stop().destroy()
        assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(prefs.getBoolean("enabled", true))
        assertTrue(prefs.getLong("revision", 0) > 0)
        assertEquals(before + 1, broadcasts().size)
        assertEquals("com.android.systemui", broadcasts().last().`package`)
        assertFalse(broadcasts().last().getStringExtra(IndicatorConfig.PROBE_TOKEN).isNullOrBlank())
    }

    @Test fun oldProbeCannotOverwriteCommitFailureOrSendSuccessNotification() {
        prefs.edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val failedPrefs = object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                            editor.putLong(key, value)
                            return this
                        }
                        override fun commit() = false
                    }
                }
            }
            MainActivity::class.java.getDeclaredField("prefs").apply { isAccessible = true; set(activity, failedPrefs) }
            val before = broadcasts().size
            (field(activity, "sync") as Runnable).run()
            (field(activity, "writer") as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val label = field(activity, "saved") as TextView
            assertEquals("保存失败，请重试", label.text.toString())
            app.getSharedPreferences("diagnostics", 0).edit()
                .putString(IndicatorConfig.PROBE_TOKEN, broadcasts().first().getStringExtra(IndicatorConfig.PROBE_TOKEN))
                .putLong(IndicatorConfig.PROBE_REVISION, 0)
                .putLong(IndicatorConfig.PROBE_TIME, 1)
                .putString(IndicatorConfig.PROBE_STATUS, "historical success").commit()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
            assertEquals("保存失败，请重试", label.text.toString())
            assertEquals(before, broadcasts().size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun damagedConfigurationStillOpensAndRetainsHealthyValues() {
        prefs.edit().clear().putString("centerNumber", "true").putString("enabled", "true")
            .putString(PreferenceMigration.MARKER, "true").putString("revision", "bad")
            .putBoolean("numberScale", true).putInt("wifiScale", 130).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertFalse(IndicatorConfig.read(prefs).enabled)
            assertFalse(IndicatorConfig.read(prefs).centerNumber)
            assertEquals(115, IndicatorConfig.read(prefs).numberScale)
            assertEquals(130, IndicatorConfig.read(prefs).wifiScale)
            assertTrue((field(controller.get(), "saved") as TextView).text.contains("配置类型异常"))
            descendants(controller.get().findViewById(android.R.id.content)).filterIsInstance<Button>()
                .single { it.text == "恢复原图标" }.performClick()
            assertFalse(prefs.getBoolean("enabled", true))
            assertEquals(130, prefs.getInt("wifiScale", 0))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun eachKnownSettingTypeFallsBackIndependently() {
        val bools = listOf("enabled", "lockscreen", "colors", "showNumber", "autoNumber", "centerNumber")
        val ints = listOf("size", "wifiScale", "textScale", "stroke", "dotScale", "dotSpacing",
            "horizontalPadding", "verticalOffset", "chargingColor", "lowColor", "saverColor",
            "lowThreshold", "numberScale", "numberThreshold")
        for (key in bools + ints) {
            prefs.edit().clear().putString(key, "invalid").commit()
            assertEquals(IndicatorConfig(), IndicatorConfig.read(prefs))
            assertEquals(setOf(key), SafePreferences.malformedKeys(prefs))
        }
        prefs.edit().clear().putInt("size", 999).putInt("numberScale", -1).commit()
        assertEquals(32, IndicatorConfig.read(prefs).size)
        assertEquals(75, IndicatorConfig.read(prefs).numberScale)
    }

    @Test fun saveCompletionRejectsOldGenerationAndAllowsSuccessfulRetry() {
        val status = SaveStatus()
        val old = status.pending()
        val latest = status.pending()
        assertFalse(status.complete(old, 10, true))
        assertEquals(SaveStatus.Phase.SAVING, status.phase)
        assertTrue(status.complete(latest, 11, false))
        assertEquals(SaveStatus.Phase.FAILED, status.phase)
        assertFalse(status.complete(old, 10, true))
        assertTrue(status.complete(status.pending(), 12, true))
        assertEquals(SaveStatus.Phase.SAVED, status.phase)
        assertEquals(12L, status.revision)
    }

    @Test fun historicalReceiptCannotOverridePendingSave() {
        prefs.edit().clear().putLong("revision", 4).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.findViewById(android.R.id.content))
                .filterIsInstance<android.widget.Switch>().single { it.tag == "lockscreen" }.performClick()
            MainActivity::class.java.getDeclaredMethod("updateDiagnostics").apply {
                isAccessible = true
                invoke(activity)
            }
            assertEquals("正在保存", (field(activity, "saved") as TextView).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun successfulRetryReplacesFailureOnlyAfterCommit() {
        prefs.edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val status = field(activity, "saveStatus") as SaveStatus
            status.complete(status.pending(), 1, false)
            val before = broadcasts().size
            status.pending()
            (field(activity, "sync") as Runnable).run()
            (field(activity, "writer") as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SaveStatus.Phase.SAVED, status.phase)
            assertTrue((field(activity, "saved") as TextView).text.startsWith("设置已保存"))
            assertEquals(before + 1, broadcasts().size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun installFailureAtEveryStageReleasesPreviousResourcesInReverseOrder() {
        for (stage in 0..12) {
            val resources = OwnedResources()
            val released = mutableListOf<Int>()
            val result = runCatching {
                resources.install {
                    repeat(13) { index ->
                        if (index == stage) error("Injected stage $stage")
                        resources.add("stage-$index") { released += index }
                    }
                }
            }
            assertTrue(result.isFailure)
            assertTrue(resources.pending.isEmpty())
            assertEquals((0 until stage).reversed().toList(), released)
        }
    }

    @Test fun failedCleanupIsRetainedAndCannotBeRegisteredAgain() {
        val resources = OwnedResources()
        var fail = true
        resources.add("network") { if (fail) error("Injected unregister failure") }
        resources.add("receiver") {}
        assertEquals(1, resources.releaseAll().size)
        assertEquals(setOf("network"), resources.pending)
        assertTrue(runCatching { resources.add("network") {} }.isFailure)
        fail = false
        assertTrue(resources.releaseAll().isEmpty())
        assertTrue(resources.pending.isEmpty())
        repeat(20) {
            resources.add("network") {}
            assertTrue(resources.releaseAll().isEmpty())
        }
    }

    @Test fun rollbackKeepsOriginalErrorAndRecordsCleanupFailure() {
        val resources = OwnedResources()
        val error = runCatching {
            resources.install {
                resources.add("callback") { error("unregister") }
                error("install")
            }
        }.exceptionOrNull()!!
        assertEquals("install", error.message)
        assertTrue(error.suppressed.single().message!!.contains("callback"))
        assertEquals(setOf("callback"), resources.pending)
    }

    @Test fun sameNetworkStatePublishesOnceAndMergesLatestBattery() {
        val updates = mutableListOf<IndicatorState>()
        val publication = StatePublication { updates += it }
        publication.start()
        val token = publication.generation
        publication.battery(IndicatorState(battery = 79, batteryObserved = true))
        updates.clear()
        repeat(100) { publication.network(token, IndicatorState.demo.copy(battery = 1)) }
        assertEquals(1, updates.size)
        assertEquals(79, updates.single().battery)
    }

    @Test fun stoppedAndPreviousInstanceResultsNeverPublish() {
        val updates = mutableListOf<IndicatorState>()
        val publication = StatePublication { updates += it }
        publication.start()
        val old = publication.generation
        publication.stop()
        publication.network(old, IndicatorState.demo)
        publication.start()
        publication.network(old, IndicatorState.demo)
        assertTrue(updates.isEmpty())
        publication.network(publication.generation, IndicatorState.demo)
        assertEquals(1, updates.size)
        assertFalse(updates.single().batteryObserved)
        assertEquals(-1, updates.single().battery)
    }

    private class Wifi(handler: Handler) {
        @JvmField val mBgHandler = handler
        @JvmField val mCurrentState = WifiState()
    }
    private class WifiState {
        @JvmField var enabled = true
        @JvmField var isTransient = false
        @JvmField var connected = true
        @JvmField var level = 4
    }
    private class Tracker(handler: Handler) { @JvmField val mReceiverHandler = handler }
    private class Mobile(handler: Handler, level: Int, type: Int) {
        @JvmField val mMobileStatusTracker = Tracker(handler)
        @JvmField val mCurrentState = MobileState(level, type)
    }
    private class MobileState(@JvmField var level: Int, type: Int) {
        @JvmField var connected = true
        @JvmField var isEmergency = false
        @JvmField var serviceState: ServiceState? = null
        @JvmField var telephonyDisplayInfo = TelephonyDisplayInfo::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(type, 0, false)
        @JvmField var roaming = false
    }
    private class Controller(handler: Handler) {
        @JvmField val mReceiverHandler = handler
        @JvmField val mLock = Any()
        @JvmField val mWifiSignalController = Wifi(handler)
        @JvmField val mMobileSignalControllers = SparseArray<Any>()
    }

    @Test fun twoSubscriptionSnapshotsCannotMixMutableFieldsOrReuseAnotherSim() {
        val controller = Controller(Handler(Looper.getMainLooper()))
        controller.mMobileSignalControllers.put(1, Mobile(controller.mReceiverHandler, 1, 13))
        controller.mMobileSignalControllers.put(2, Mobile(controller.mReceiverHandler, 4, 20))
        val reader = ControllerSnapshot(controller)
        var first: ControllerSnapshot.Values? = null
        var second: ControllerSnapshot.Values? = null
        controller.mReceiverHandler.post {
            first = reader.capture(1)
            (controller.mMobileSignalControllers[1] as Mobile).mCurrentState.level = 2
            second = reader.capture(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, first!!.mobileLevel)
        assertEquals(13, first!!.networkType)
        assertEquals(4, second!!.mobileLevel)
        assertEquals(20, second!!.networkType)
        synchronized(controller.mLock) {
            controller.mMobileSignalControllers.clear()
            controller.mMobileSignalControllers.put(2, Mobile(controller.mReceiverHandler, 3, 13))
        }
        assertFalse(reader.isCurrent(first!!))
        assertFalse(reader.isCurrent(second!!))
        var absent: ControllerSnapshot.Values? = null
        controller.mReceiverHandler.post { absent = reader.capture(1) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(absent!!.identity)
        assertEquals(0, absent!!.mobileLevel)
    }

    @Test fun controllerCaptureRejectsWrongThread() {
        val controller = Controller(Handler(Looper.getMainLooper()))
        val reader = ControllerSnapshot(controller)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> { runCatching { reader.capture(1) }.isFailure }.get())
        } finally { executor.shutdown() }
    }

    @Test fun differentControllerLooperFailsClosed() {
        val thread = android.os.HandlerThread("review-test-owner").apply { start() }
        try {
            val controller = Controller(Handler(Looper.getMainLooper()))
            controller.mMobileSignalControllers.put(1, Mobile(Handler(thread.looper), 4, 20))
            val reader = ControllerSnapshot(controller)
            val result = runCatching { reader.capture(1) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Unsupported mobile state thread"))
        } finally { thread.quitSafely(); thread.join(1000) }
    }

    @Test fun recognizedIconSubclassesPreserveImsAndOtherSlots() {
        val group = StatusIconContainer(app)
        val wifi = object : ModernStatusBarView(app, "wifi") {}
        val mobile = object : ModernStatusBarView(app, "mobile") {}
        val retained = listOf("volte", "vowifi", "novolte", "alarm_clock", "bluetooth")
            .map { StatusBarIconView(app, it) }
        group.addView(wifi); group.addView(mobile)
        retained.forEach { group.addView(it) }
        val result = SignalScope.inspect(group, true)
        assertTrue(result.supported)
        assertEquals(setOf(wifi, mobile), result.signals)
        retained.forEach { assertEquals(View.VISIBLE, it.visibility) }
    }

    @Test fun unknownBranchesRejectOnlyTheirOwnGroupAndDynamicChangesAreRechecked() {
        val group = StatusIconContainer(app)
        val other = StatusIconContainer(app)
        val wifi = ModernStatusBarView(app, "wifi")
        val unknown = View(app)
        group.addView(wifi); group.addView(unknown)
        assertFalse(SignalScope.inspect(group, true).supported)
        assertTrue(SignalScope.inspect(other, true).supported)
        group.removeView(unknown)
        assertTrue(SignalScope.inspect(group, true).supported)
        group.removeView(wifi)
        assertTrue(SignalScope.inspect(group, true).signals.isEmpty())
        val replacement = ModernStatusBarView(app, "mobile")
        group.addView(replacement)
        assertEquals(setOf(replacement), SignalScope.inspect(group, true).signals)
    }

    @Test fun arbitrarySlotMethodIsNeverInvoked() {
        var calls = 0
        val impostor = object : LinearLayout(app) {
            @Suppress("unused") fun getSlot(): String { calls++; return "wifi" }
        }
        val group = StatusIconContainer(app).apply { addView(impostor) }
        assertFalse(SignalScope.inspect(group, true).supported)
        assertEquals(0, calls)
    }

    @Test fun verifiedColorOsSpeedAndDynamicIconsDoNotBlockShadeOrLoseTheirInformation() {
        val group = StatusIconContainer(app)
        val speed = com.oplus.systemui.statusbar.phone.netspeed.widget.NetworkSpeedView(app)
        val dynamic = com.oplus.systemui.statusbar.phone.dynamic.icon.OplusStatusBarIconView(app, "secure_payment")
        val wifi = ModernStatusBarView(app, "wifi")
        group.addView(speed); group.addView(dynamic); group.addView(wifi)
        val result = SignalScope.inspect(group, true)
        assertTrue(result.supported)
        assertEquals(setOf(wifi), result.signals)
        assertEquals(View.VISIBLE, speed.visibility)
        assertEquals(View.VISIBLE, dynamic.visibility)
        group.addView(View(app))
        assertFalse(SignalScope.inspect(group, true).supported)
    }

    @Test fun unchangedInitialPreviewRetainsItsAccessibilityDescription() {
        val view = io.github.threeinone.render.IndicatorView(app)
        view.state = IndicatorState.demo
        assertEquals(IndicatorState.demo.description(), view.contentDescription)
        view.state = IndicatorState.demo.copy(battery = 79)
        assertEquals(IndicatorState.demo.copy(battery = 79).description(), view.contentDescription)
    }
}
