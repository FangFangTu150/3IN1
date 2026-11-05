package com.android.systemui.statusbar

import android.content.Context
import android.view.View

open class StatusBarIconView(context: Context, private val slot: String) : View(context) {
    open fun getSlot(): String = slot
}
