package com.android.systemui.statusbar.pipeline.shared.ui.view

import android.content.Context
import android.widget.FrameLayout

open class ModernStatusBarView(context: Context, private val slot: String) : FrameLayout(context) {
    open fun getSlot(): String = slot
}
