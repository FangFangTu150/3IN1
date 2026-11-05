package io.github.threeinone.hook

import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.TelephonyDisplayInfo
import android.util.SparseArray
import java.lang.reflect.Field

/** Only scalar values leave the verified controller thread. */
internal class ControllerSnapshot(private val controller: Any) {
    private val fields = mutableMapOf<Pair<Class<*>, String>, Field>()
    val handler = field(controller, "mReceiverHandler") as Handler
    private val lock = requireNotNull(field(controller, "mLock"))

    data class Values(
        val subId: Int, val identity: Any?, val wifiEnabled: Boolean, val wifiTransient: Boolean,
        val wifiConnected: Boolean, val wifiLevel: Int, val mobileLevel: Int,
        val emergency: Boolean, val connected: Boolean, val service: Int?,
        val networkType: Int, val overrideType: Int, val roaming: Boolean
    )

    fun capture(subId: Int): Values {
        check(Looper.myLooper() === handler.looper) { "Network snapshot outside controller thread" }
        val wifiController = requireNotNull(field(controller, "mWifiSignalController"))
        check((field(wifiController, "mBgHandler") as Handler).looper === handler.looper) {
            "Unsupported Wi-Fi state thread"
        }
        val wifi = requireNotNull(field(wifiController, "mCurrentState"))
        return synchronized(lock) {
            val mobileController = mobiles()[subId]
            mobileController?.let {
                val tracker = requireNotNull(field(it, "mMobileStatusTracker"))
                check((field(tracker, "mReceiverHandler") as Handler).looper === handler.looper) {
                    "Unsupported mobile state thread"
                }
            }
            val mobile = mobileController?.let { field(it, "mCurrentState") }
            val display = mobile?.let { field(it, "telephonyDisplayInfo") as? TelephonyDisplayInfo }
            Values(subId, mobileController, bool(wifi, "enabled"), bool(wifi, "isTransient"),
                bool(wifi, "connected"), number(wifi, "level"), mobile?.let { number(it, "level") } ?: 0,
                mobile?.let { bool(it, "isEmergency") } ?: false,
                mobile?.let { bool(it, "connected") } ?: false,
                mobile?.let { (field(it, "serviceState") as? ServiceState)?.state },
                display?.networkType ?: 0, display?.overrideNetworkType ?: 0,
                mobile?.let { bool(it, "roaming") } ?: false)
        }
    }

    fun isCurrent(values: Values): Boolean = synchronized(lock) {
        mobiles()[values.subId] === values.identity
    }

    @Suppress("UNCHECKED_CAST")
    private fun mobiles() = field(controller, "mMobileSignalControllers") as SparseArray<Any>
    private fun bool(obj: Any, name: String) = field(obj, name) as Boolean
    private fun number(obj: Any, name: String) = field(obj, name) as Int
    private fun field(obj: Any, name: String): Any? {
        val key = obj.javaClass to name
        val member = fields.getOrPut(key) {
            var type: Class<*>? = obj.javaClass
            var found: Field? = null
            while (type != null && found == null) {
                found = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
                type = type.superclass
            }
            requireNotNull(found) { "Missing controller field: $name" }.apply { isAccessible = true }
        }
        return member.get(obj)
    }
}
