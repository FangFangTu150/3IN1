package io.github.threeinone.hook

import android.annotation.SuppressLint
import android.content.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.SparseArray
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
    private var current = IndicatorState()
    private var proxy: Any? = null
    private var running = false
    private var lastBatteryLog = ""
    private var receiverRegistered = false
    private var connectivityRegistered = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val collect = Runnable { if (running) runCatching { snapshot() }.onFailure(failed) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (running) schedule()
        }
        override fun onLost(network: Network) { if (running) schedule() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                acceptBattery(intent, "broadcast")
            }
            schedule()
        }
    }

    fun start() {
        if (running) return
        running = true
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
            receiverRegistered = true
            if (initialBattery?.action == Intent.ACTION_BATTERY_CHANGED) {
                acceptBattery(initialBattery, "initial-sticky")
            } else {
                XposedBridge.log("3IN1 battery: no initial sticky snapshot; waiting for battery broadcast")
            }
            val type = loader.loadClass("com.android.systemui.statusbar.connectivity.SignalCallback")
            proxy = Proxy.newProxyInstance(loader, arrayOf(type)) { obj, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(obj)
                    "equals" -> obj === args?.getOrNull(0)
                    "toString" -> "3IN1.SignalCallback"
                    else -> { schedule(); null }
                }
            }
            XposedHelpers.callMethod(controller, "addCallback", proxy)
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main)
            connectivityRegistered = true
            schedule()
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(collect)
        proxy?.let { runCatching { XposedHelpers.callMethod(controller, "removeCallback", it) } }
        proxy = null
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        if (connectivityRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        receiverRegistered = false
        connectivityRegistered = false
    }

    private fun schedule() {
        main.removeCallbacks(collect)
        main.post(collect)
    }

    private fun acceptBattery(intent: Intent, origin: String) {
        current = BatterySnapshot.apply(current, intent)
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

    private fun snapshot() {
        val wifiController = field(controller, "mWifiSignalController")!!
        val wifi = field(wifiController, "mCurrentState")!!
        // Same current-network API used by this firmware's WifiStatusTracker.
        val network = XposedHelpers.callMethod(context.getSystemService(WifiManager::class.java),
            "getCurrentNetwork") as? Network
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifiPhase = WifiStatus.phase(bool(wifi, "enabled"), bool(wifi, "isTransient"),
            bool(wifi, "connected"), caps)
        val phone = context.getSystemService(TelephonyManager::class.java)
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.subscriptionId == subId }
        @Suppress("UNCHECKED_CAST")
        val mobiles = field(controller, "mMobileSignalControllers") as SparseArray<Any>
        val mobile = mobiles[subId]?.let { field(it, "mCurrentState") }
        val subPhone = if (SubscriptionManager.isValidSubscriptionId(subId)) phone.createForSubscriptionId(subId) else phone
        val simStates = (0 until phone.activeModemCount.coerceAtLeast(1)).map { phone.getSimState(it) }
        val simState = selected?.let { phone.getSimState(it.simSlotIndex) }
        val locked = setOf(TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED, TelephonyManager.SIM_STATE_PERM_DISABLED)
        val service = mobile?.let { field(it, "serviceState") as? ServiceState }
        val phase = when {
            simState in locked || (selected == null && simStates.any { it in locked }) -> SimPhase.LOCKED
            simStates.all { it == TelephonyManager.SIM_STATE_ABSENT } -> SimPhase.ABSENT
            selected == null && simStates.any { it == TelephonyManager.SIM_STATE_READY } -> SimPhase.DISABLED
            selected == null -> SimPhase.UNKNOWN
            mobile == null -> SimPhase.UNKNOWN
            bool(mobile, "isEmergency") -> SimPhase.EMERGENCY
            service == null -> SimPhase.UNKNOWN
            service.state != ServiceState.STATE_IN_SERVICE && !bool(mobile, "connected") -> SimPhase.NO_SIGNAL
            else -> SimPhase.READY
        }
        val display = mobile?.let { field(it, "telephonyDisplayInfo") as? TelephonyDisplayInfo }
        current = current.copy(
            powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            wifi = wifiPhase, wifiLevel = number(wifi, "level").coerceIn(0, 4),
            airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
            sim = phase, mobileLevel = mobile?.let { number(it, "level").coerceIn(0, 4) } ?: 0,
            dataEnabled = selected != null && subPhone.isDataEnabled,
            networkType = IndicatorState.networkLabel(display?.networkType ?: 0, display?.overrideNetworkType ?: 0),
            roaming = mobile?.let { bool(it, "roaming") } ?: false,
            networkReady = phase != SimPhase.UNKNOWN || Settings.Global.getInt(
                context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        )
        changed(current)
    }

    private fun field(obj: Any, name: String): Any? = XposedHelpers.getObjectField(obj, name)
    private fun bool(obj: Any, name: String) = XposedHelpers.getBooleanField(obj, name)
    private fun number(obj: Any, name: String) = XposedHelpers.getIntField(obj, name)
}
