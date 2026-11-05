package com.oplus.systemui.statusbar.phone.dynamic.icon

import android.content.Context
import android.view.View

open class OplusStatusBarIconView(context: Context, private val slot: String) : View(context) {
    open fun getSlot(): String = slot
}
