package io.github.threeinone.hook

import android.os.Handler

internal class CoalescedReload(private val handler: Handler, private val action: Runnable) {
    private var pending = false
    private val run = Runnable {
        synchronized(this) { pending = false }
        action.run()
    }

    @Synchronized fun request() {
        if (pending) return
        pending = handler.postDelayed(run, 100)
    }
}
