package io.github.threeinone.hook

import android.annotation.SuppressLint
import android.content.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import io.github.threeinone.model.*
import java.lang.reflect.Proxy

/** Reads the verified ColorOS controller; callback payloads only trigger a fresh snapshot. */
@SuppressLint("MissingPermission") // Executes inside SystemUI, not the settings APK; checks its grants below.
class SystemStateSource(
    private val context: Context,
    private val controller: Any,
    private val loader: ClassLoader,
    private val changed: (IndicatorState) -> Unit,
    private val failed: (Throwable) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val publication = StatePublication(changed)
    private val resources = OwnedResources()
    private lateinit var reader: ControllerSnapshot
    private var proxy: Any? = null
    private val running get() = publication.running
    private var lastBatteryLog = ""
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val collect = Runnable {
        val token = publication.generation
        if (publication.accepts(token)) runCatching { snapshot(token) }
            .onFailure { error -> main.post { if (publication.accepts(token)) failed(error) } }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (running) schedule()
        }
        override fun onLost(network: Network) { if (running) schedule() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (!running) return
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                acceptBattery(intent, "broadcast")
            }
            schedule()
        }
    }

    fun start() {
        if (running) return
        check(resources.pending.isEmpty()) { "State source cleanup pending: ${resources.pending}" }
        reader = ControllerSnapshot(controller)
        check(reader.handler.looper !== main.looper) { "Unsupported main-thread network controller" }
        publication.start()
        try {
            require(context.packageName == "com.android.systemui") { "State source requires SystemUI context" }
            for (permission in listOf("android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_WIFI_STATE")) {
                check(context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "SystemUI permission unavailable: $permission"
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            }
            val initialBattery = context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            resources.add("battery-receiver") { context.unregisterReceiver(receiver) }
            if (initialBattery?.action == Intent.ACTION_BATTERY_CHANGED) {
                acceptBattery(initialBattery, "initial-sticky")
            } else {
                XposedBridge.log("3IN1 battery: no initial sticky snapshot; waiting for battery broadcast")
            }
            if (!running) return
            val type = loader.loadClass("com.android.systemui.statusbar.connectivity.SignalCallback")
            proxy = Proxy.newProxyInstance(loader, arrayOf(type)) { obj, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(obj)
                    "equals" -> obj === args?.getOrNull(0)
                    "toString" -> "3IN1.SignalCallback"
                    else -> { schedule(); null }
                }
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main)
            resources.add("wifi-callback") { connectivity.unregisterNetworkCallback(callback) }
            val token = publication.generation
            reader.handler.post {
                synchronized(resources) {
                    if (!publication.accepts(token)) return@synchronized
                    runCatching {
                        // addCallback itself reads the mutable controllers to send initial values.
                        val signal = requireNotNull(proxy)
                        synchronized(requireNotNull(XposedHelpers.getObjectField(controller, "mLock"))) {
                            resources.add("signal-callback") {
                                XposedHelpers.callMethod(controller, "removeCallback", signal)
                            }
                            XposedHelpers.callMethod(controller, "addCallback", signal)
                        }
                        schedule()
                    }.onFailure { error -> main.post { if (publication.accepts(token)) failed(error) } }
                }
            }
        } catch (error: Throwable) {
            runCatching { stop() }.exceptionOrNull()?.let { error.addSuppressed(it) }
            throw error
        }
    }

    fun stop() = synchronized(resources) {
        publication.stop()
        if (::reader.isInitialized) reader.handler.removeCallbacks(collect)
        val errors = resources.releaseAll()
        if (errors.isEmpty()) proxy = null
        check(errors.isEmpty()) { "State cleanup failed: ${errors.joinToString()}" }
    }

    private fun schedule() {
        if (!running || !::reader.isInitialized) return
        synchronized(collect) {
            if (!reader.handler.hasCallbacks(collect)) reader.handler.post(collect)
        }
    }

    private fun acceptBattery(intent: Intent, origin: String) {
        publication.battery(BatterySnapshot.apply(publication.current, intent))
        val current = publication.current
        val details = "level=${intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}" +
            " scale=${intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)}" +
            " status=${intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)}" +
            " plugged=${intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)}" +
            " present=${intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)}" +
            " percent=${current.battery} phase=${current.batteryPhase}"
        // Only log changed battery snapshots, not repeated network/layout callbacks.
        if (lastBatteryLog != details) {
            lastBatteryLog = details
            XposedBridge.log("3IN1 battery [$origin]: $details")
        }
    }

    private fun snapshot(token: Long) {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val values = reader.capture(subId)
        // Same current-network API used by this firmware's WifiStatusTracker.
        val network = XposedHelpers.callMethod(context.getSystemService(WifiManager::class.java),
            "getCurrentNetwork") as? Network
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifiPhase = WifiStatus.phase(values.wifiEnabled, values.wifiTransient,
            values.wifiConnected, caps)
        val phone = context.getSystemService(TelephonyManager::class.java)
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.subscriptionId == subId }
        val subPhone = if (SubscriptionManager.isValidSubscriptionId(subId)) phone.createForSubscriptionId(subId) else phone
        val simStates = (0 until phone.activeModemCount.coerceAtLeast(1)).map { phone.getSimState(it) }
        val simState = selected?.let { phone.getSimState(it.simSlotIndex) }
        val locked = setOf(TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED, TelephonyManager.SIM_STATE_PERM_DISABLED)
        val phase = when {
            simState in locked || (selected == null && simStates.any { it in locked }) -> SimPhase.LOCKED
            simStates.all { it == TelephonyManager.SIM_STATE_ABSENT } -> SimPhase.ABSENT
            selected == null && simStates.any { it == TelephonyManager.SIM_STATE_READY } -> SimPhase.DISABLED
            selected == null -> SimPhase.UNKNOWN
            values.identity == null -> SimPhase.UNKNOWN
            values.emergency -> SimPhase.EMERGENCY
            values.service == null -> SimPhase.UNKNOWN
            values.service != ServiceState.STATE_IN_SERVICE && !values.connected -> SimPhase.NO_SIGNAL
            else -> SimPhase.READY
        }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val next = IndicatorState(
            powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            wifi = wifiPhase, wifiLevel = values.wifiLevel.coerceIn(0, 4),
            airplane = airplane,
            sim = phase, mobileLevel = values.mobileLevel.coerceIn(0, 4),
            dataEnabled = selected != null && subPhone.isDataEnabled,
            networkType = IndicatorState.networkLabel(values.networkType, values.overrideType),
            roaming = values.roaming,
            networkReady = phase != SimPhase.UNKNOWN || airplane
        )
        if (!publication.accepts(token)) return
        if (subId != SubscriptionManager.getDefaultDataSubscriptionId() || !reader.isCurrent(values)) {
            schedule()
            return
        }
        main.post { publication.network(token, next) }
    }
}
