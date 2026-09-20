package io.github.threeinone.model

import android.net.NetworkCapabilities

internal object WifiStatus {
    fun phase(enabled: Boolean, connecting: Boolean, connected: Boolean, current: NetworkCapabilities?): WifiPhase =
        when {
            !enabled -> WifiPhase.OFF
            connecting -> WifiPhase.CONNECTING
            !connected -> WifiPhase.DISCONNECTED
            current == null || !current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WifiPhase.CONNECTING
            current.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> WifiPhase.CAPTIVE
            current.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> WifiPhase.CONNECTED
            else -> WifiPhase.NO_INTERNET
        }
}
