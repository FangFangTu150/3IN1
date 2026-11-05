package com.oplus.systemui.statusbar.phone.netspeed.widget

import android.content.Context
import android.widget.FrameLayout

open class NetworkSpeedView(context: Context) : FrameLayout(context) {
    open fun getSlot(): String = "network_speed"
}
