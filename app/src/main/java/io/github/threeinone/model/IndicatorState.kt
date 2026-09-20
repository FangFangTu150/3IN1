package io.github.threeinone.model

enum class BatteryPhase { NORMAL, CHARGING, FULL, PAUSED, UNKNOWN }
enum class WifiPhase { OFF, DISCONNECTED, CONNECTING, CONNECTED, NO_INTERNET, CAPTIVE }
enum class SimPhase { READY, NO_SIGNAL, EMERGENCY, ABSENT, DISABLED, LOCKED, UNKNOWN }

data class IndicatorState(
    val battery: Int = -1,
    val batteryPhase: BatteryPhase = BatteryPhase.UNKNOWN,
    val powerSave: Boolean = false,
    val wifi: WifiPhase = WifiPhase.OFF,
    val wifiLevel: Int = 0,
    val sim: SimPhase = SimPhase.UNKNOWN,
    val mobileLevel: Int = 0,
    val networkType: String = "",
    val dataEnabled: Boolean = false,
    val roaming: Boolean = false,
    val airplane: Boolean = false,
    val networkReady: Boolean = false,
    val batteryObserved: Boolean = false
) {
    val wifiConnected get() = wifi in setOf(WifiPhase.CONNECTED, WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE)
    val ready get() = networkReady && batteryObserved
    val progress get() = if (battery < 0) 0f else battery.coerceIn(0, 100) / 100f
    val dots get() = if (sim == SimPhase.READY && !airplane) mobileLevel.coerceIn(0, 4) else 0

    fun description(): String = buildString {
        append(if (battery < 0) "电量未知" else "电量 $battery%")
        append("，" + when (batteryPhase) {
            BatteryPhase.CHARGING -> "充电中"
            BatteryPhase.FULL -> "已充满"
            BatteryPhase.PAUSED -> "已插电，未充电"
            BatteryPhase.UNKNOWN -> "充放电状态未知"
            else -> if (powerSave) "省电模式" else "正常"
        })
        append("，Wi-Fi " + when (wifi) {
            WifiPhase.OFF -> "关闭"
            WifiPhase.DISCONNECTED -> "未连接"
            WifiPhase.CONNECTING -> "连接中"
            WifiPhase.CONNECTED -> "已连接，${wifiLevel.coerceIn(0, 4)}格"
            WifiPhase.NO_INTERNET -> "无互联网"
            WifiPhase.CAPTIVE -> "需要登录"
        })
        append("，" + if (airplane) "飞行模式" else when (sim) {
            SimPhase.READY -> "$networkType 信号 $dots 格" + if (dataEnabled) "" else "，移动数据关闭"
            SimPhase.NO_SIGNAL -> "无信号"
            SimPhase.EMERGENCY -> "仅紧急呼叫"
            SimPhase.ABSENT -> "无 SIM 卡"
            SimPhase.DISABLED -> "SIM 已停用"
            SimPhase.LOCKED -> "SIM 已锁定"
            SimPhase.UNKNOWN -> "蜂窝状态未知"
        })
        if (roaming) append("，漫游")
    }

    companion object {
        val demo = IndicatorState(76, BatteryPhase.NORMAL, wifi = WifiPhase.CONNECTED,
            wifiLevel = 4, sim = SimPhase.READY, mobileLevel = 4, networkType = "5G",
            dataEnabled = true, networkReady = true, batteryObserved = true)
        val previews: List<Pair<String, IndicatorState>> = listOf(
            "正常 · Wi-Fi" to demo,
            "充电中" to demo.copy(battery = 56, batteryPhase = BatteryPhase.CHARGING),
            "已充满" to demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL),
            "低电量" to demo.copy(battery = 12),
            "省电模式" to demo.copy(battery = 45, powerSave = true),
            "插电未充电 / 限充" to demo.copy(battery = 80, batteryPhase = BatteryPhase.PAUSED),
            "电量未知" to demo.copy(battery = -1, batteryPhase = BatteryPhase.UNKNOWN),
            "Wi-Fi 弱信号" to demo.copy(wifiLevel = 1, mobileLevel = 1),
            "Wi-Fi 无互联网" to demo.copy(wifi = WifiPhase.NO_INTERNET),
            "Wi-Fi 需要登录" to demo.copy(wifi = WifiPhase.CAPTIVE),
            "Wi-Fi 连接中" to demo.copy(wifi = WifiPhase.CONNECTING),
            "Wi-Fi 关闭 · 5G" to demo.copy(wifi = WifiPhase.OFF),
            "Wi-Fi 未连接 · 4G" to demo.copy(wifi = WifiPhase.DISCONNECTED, networkType = "4G"),
            "移动数据关闭" to demo.copy(wifi = WifiPhase.OFF, dataEnabled = false),
            "无信号" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.NO_SIGNAL, mobileLevel = 0),
            "仅紧急呼叫" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.EMERGENCY),
            "无 SIM 卡" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.ABSENT),
            "SIM 停用" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.DISABLED),
            "SIM 锁定" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.LOCKED),
            "飞行模式" to demo.copy(wifi = WifiPhase.OFF, airplane = true),
            "飞行模式 · Wi-Fi" to demo.copy(airplane = true),
            "漫游" to demo.copy(wifi = WifiPhase.OFF, roaming = true)
        )
        fun networkLabel(type: Int, overrideType: Int): String = when {
            type == 20 || overrideType in setOf(3, 4, 5) -> "5G"
            type in setOf(13, 19) || overrideType in setOf(1, 2) -> "4G"
            type in setOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17) -> "3G"
            type in setOf(1, 2, 4, 7, 11, 16) -> "2G"
            else -> ""
        }
    }
}
