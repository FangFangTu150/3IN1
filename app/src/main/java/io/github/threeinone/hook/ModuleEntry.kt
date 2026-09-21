package io.github.threeinone.hook

import android.content.*
import android.graphics.Canvas
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.LinearLayout
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorView
import java.util.IdentityHashMap
import kotlin.math.min

class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.android.systemui" || param.processName != "com.android.systemui") return
        XposedBridge.log("3IN1 0.1.6: SystemUI entry")
        runCatching { ColorOsAdapter(param.classLoader).install() }
            .onFailure { XposedBridge.log("3IN1: adapter unavailable: $it") }
    }
}

private class ColorOsAdapter(private val loader: ClassLoader) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("3IN1-config").apply { start() }
    private val io = Handler(worker.looper)
    private val prefs = XSharedPreferences(IndicatorConfig.PACKAGE, IndicatorConfig.FILE)
    private var config = IndicatorConfig()
    private var state = IndicatorState()
    private var revision = 0L
    private var context: Context? = null
    private var controller: Any? = null
    private var source: SystemStateSource? = null
    private var fault: String? = null
    private val sessions = IdentityHashMap<ViewGroup, Session>()
    private val hidden = IdentityHashMap<View, Int>()
    private var changingVisibility = false
    private var supported = false
    private var lastLog = 0L
    private var lastReport: String? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadRequests.request()
    }
    private val update = Runnable { guarded { refresh() } }
    private val reload = Runnable {
        runCatching {
            prefs.reload()
            val next = IndicatorConfig.read(prefs)
            val rev = prefs.getLong("revision", 0)
            val hasSettings = prefs.contains("revision")
            main.post {
                config = next; revision = rev
                if (!hasSettings) XposedBridge.log("3IN1: no shared configuration yet; open settings after activation")
                guarded { manageSource(); refresh(layout = true); report() }
            }
        }.onFailure { error -> main.post { fail(error) } }
    }
    private val reloadRequests = CoalescedReload(io, reload)

    private inner class Session(val host: ViewGroup, val root: ViewGroup, val lockscreen: Boolean) {
        val icon = IndicatorView(host.context)
        val dualTone = XposedHelpers.newInstance(loader.loadClass("com.android.systemui.DualToneHandler"), host.context)
        val accessibility = AccessibilityOverride(host)
        var active = false
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            main.removeCallbacks(update); main.post(update)
        }
    }

    fun install() {
        val battery = loader.loadClass(BATTERY)
        val network = loader.loadClass(NETWORK)
        // Validate the exact adapter contract before adding any hooks.
        battery.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        battery.getDeclaredMethod("onAttachedToWindow")
        battery.getDeclaredField("tmpRectList")
        battery.getDeclaredField("binding")
        battery.getDeclaredMethod("getTransXForCoord")
        val iconContainer = loader.loadClass("com.android.systemui.statusbar.phone.StatusIconContainer")
        iconContainer.getDeclaredField("mIgnoredSlots")
        iconContainer.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        iconContainer.getDeclaredMethod("updateStates")
        network.getDeclaredMethod("addCallback", Any::class.java)
        network.getDeclaredMethod("removeCallback", Any::class.java)
        loader.loadClass("com.android.systemui.statusbar.connectivity.SignalCallback")
        XposedBridge.hookAllConstructors(network, after { p ->
            controller = p.thisObject
            main.post { guarded { initialize(XposedHelpers.getObjectField(p.thisObject, "mContext") as Context); manageSource() } }
        })
        XposedBridge.hookAllMethods(battery, "onAttachedToWindow", after { p ->
            val host = p.thisObject as ViewGroup
            host.post { guarded { if (host.isAttachedToWindow) attach(host) } }
        })
        XposedBridge.hookAllMethods(battery, "onDetachedFromWindow", after { p ->
            guarded { detach(p.thisObject as ViewGroup) }
        })
        XposedBridge.hookAllMethods(battery, "onDarkChanged", after { p ->
            guarded {
                sessions[p.thisObject]?.let { session ->
                    syncTint(session)
                    session.host.invalidate()
                }
            }
        })
        XposedBridge.hookAllMethods(battery, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val session = sessions[p.thisObject] ?: return
                if (!session.active) return
                try {
                    val canvas = p.args[0] as Canvas
                    val host = session.host
                    val dp = host.resources.displayMetrics.density
                    val desired = config.size * dp
                    val available = host.height.toFloat()
                    val h = min(desired, available)
                    val w = min(desired, (host.width - config.horizontalPadding * 2 * dp).coerceAtLeast(1f))
                    val y = ((available - h) / 2 + config.verticalOffset * dp).coerceIn(0f, (available - h).coerceAtLeast(0f))
                    session.icon.measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.EXACTLY))
                    session.icon.layout(0, 0, w.toInt(), h.toInt())
                    val saved = canvas.save()
                    try {
                        val coordination = XposedHelpers.callMethod(host, "getTransXForCoord") as Float
                        canvas.translate((host.width - w) / 2 + coordination, y)
                        session.icon.draw(canvas)
                    } finally { canvas.restoreToCount(saved) }
                    p.result = null
                } catch (error: Throwable) { fail(error) }
            }
        })
        XposedHelpers.findAndHookMethod(LinearLayout::class.java, "onMeasure", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, after { p ->
                guarded {
                    val s = sessions[p.thisObject] ?: return@guarded
                    if (!s.active) return@guarded
                    val width = ((config.size + config.horizontalPadding * 2) * s.host.resources.displayMetrics.density).toInt()
                    XposedHelpers.callMethod(s.host, "setMeasuredDimension",
                        View.resolveSize(width, p.args[0] as Int), s.host.measuredHeight)
                }
            })
        XposedHelpers.findAndHookMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (changingVisibility) return
                    val view = p.thisObject as View
                    if (hidden.containsKey(view)) {
                        hidden[view] = p.args[0] as Int
                        p.args[0] = View.GONE
                    }
                }
            })
        XposedHelpers.findAndHookMethod(View::class.java, "setContentDescription", CharSequence::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val session = sessions[p.thisObject] ?: return
                    p.args[0] = session.accessibility.systemDescription(p.args[0] as? CharSequence)
                }
            })
        // ColorOS ignores View.GONE when measuring and positioning status icon slots.
        for (method in listOf("onMeasure", "updateStates")) {
            XposedBridge.hookAllMethods(iconContainer, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    guarded {
                        val group = p.thisObject as ViewGroup
                        val excluded = (0 until group.childCount).map { group.getChildAt(it) }
                            .filter { hidden.containsKey(it) }
                            .map { XposedHelpers.callMethod(it, "getSlot") as String }
                        if (excluded.isEmpty()) return@guarded
                        @Suppress("UNCHECKED_CAST")
                        val slots = XposedHelpers.getObjectField(group, "mIgnoredSlots") as MutableList<String>
                        p.setObjectExtra("3in1.slots", SystemIconPolicy.SlotExclusion(slots, excluded))
                    }
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    guarded {
                        (p.getObjectExtra("3in1.slots") as? SystemIconPolicy.SlotExclusion)?.restore()
                    }
                }
            })
        }
        // Irena supports preference notifications; explicit reload broadcasts also cover older builds.
        runCatching { prefs.registerOnSharedPreferenceChangeListener(preferenceListener) }
        reloadRequests.request()
        XposedBridge.log("3IN1: adapter hooks installed")
    }

    private fun initialize(c: Context) {
        if (context != null) return
        context = c
        // Runtime compatibility is documented as ColorOS 16-only; do not gate on
        // an exact model, firmware build, or SystemUI version.
        supported = true
        c.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == IndicatorConfig.REFRESH) reloadRequests.request()
            }
        }, IntentFilter(IndicatorConfig.REFRESH), IndicatorConfig.REFRESH_PERMISSION, main, Context.RECEIVER_EXPORTED)
        reloadRequests.request()
    }

    private fun attach(host: ViewGroup) {
        if (sessions.containsKey(host)) return
        val root = rootFor(host) ?: return
        initialize(host.context)
        val lock = root.javaClass.name != "com.android.systemui.statusbar.phone.PhoneStatusBarView"
        val s = Session(host, root, lock)
        syncTint(s)
        sessions[host] = s
        root.viewTreeObserver.addOnGlobalLayoutListener(s.layoutListener)
        manageSource()
        refresh(layout = true)
        report()
    }

    private fun detach(host: ViewGroup) {
        val s = sessions.remove(host) ?: return
        if (s.root.viewTreeObserver.isAlive) s.root.viewTreeObserver.removeOnGlobalLayoutListener(s.layoutListener)
        restoreHost(s)
        refresh(layout = true)
        manageSource()
    }

    private fun syncTint(s: Session) {
        val areas = XposedHelpers.getObjectField(s.host, "tmpRectList")
        val inArea = XposedHelpers.callStaticMethod(
            loader.loadClass("com.android.systemui.plugins.DarkIconDispatcher"), "isInAreas", areas, s.host
        ) as Boolean
        val intensity = SystemIconPolicy.effectiveIntensity(
            XposedHelpers.getFloatField(s.host, "darkIntensity"), inArea
        )
        // The third onDarkChanged argument can be zero on ColorOS. Match the stock battery.
        val binding = XposedHelpers.getObjectField(s.host, "binding")
        val tone = if (binding == null) s.dualTone else XposedHelpers.getObjectField(binding, "\$dualToneHandler")
        s.icon.tint = XposedHelpers.callMethod(tone, "getSingleColor", intensity) as Int
    }

    private fun rootFor(view: View): ViewGroup? {
        var next: View? = view
        while (next != null) {
            var type: Class<*>? = next.javaClass
            while (type != null) {
                if (type.name in ROOTS) return next as? ViewGroup
                type = type.superclass
            }
            next = next.parent as? View
        }
        return null
    }

    private fun manageSource() {
        val shouldRun = supported && config.enabled && fault == null && sessions.isNotEmpty()
        if (!shouldRun) {
            source?.stop(); source = null; state = IndicatorState()
        } else if (source == null && controller != null && context != null) {
            source = SystemStateSource(context!!, controller!!, loader, {
                val wasReady = state.ready
                state = it
                guarded { refresh(); if (wasReady != state.ready) report() }
            }, { fail(it) }).also { it.start() }
        }
    }

    private fun refresh(layout: Boolean = false) {
        val toHide = java.util.Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        sessions.values.forEach { s ->
            val active = supported && config.enabled && fault == null && state.ready &&
                (!s.lockscreen || config.lockscreen) && s.host.visibility == View.VISIBLE
            val transition = s.active != active
            s.active = active
            if (active) {
                syncTint(s)
                s.icon.state = state
                s.icon.config = config
                s.accessibility.show(state.description())
                collectSignals(s.root, toHide)
            } else restoreHost(s)
            if (layout || transition) s.host.requestLayout()
            s.host.invalidate()
        }
        changingVisibility = true
        try {
            hidden.keys.toList().filter { it !in toHide }.forEach { view ->
                val original = hidden.remove(view) ?: View.VISIBLE
                view.visibility = original
                (view.parent as? View)?.requestLayout()
            }
            toHide.forEach { view ->
                if (!hidden.containsKey(view)) {
                    hidden[view] = view.visibility
                    (view.parent as? View)?.requestLayout()
                }
                if (view.visibility != View.GONE) view.visibility = View.GONE
            }
        } finally { changingVisibility = false }
    }

    private fun collectSignals(group: ViewGroup, result: MutableSet<View>) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = child.javaClass.name
            val slot = if (name.endsWith("StatusBarIconView")) {
                runCatching { XposedHelpers.callMethod(child, "getSlot") as? String }.getOrNull()
            } else null
            if (name in SIGNALS || slot in SLOTS) result.add(child)
            else if (child is ViewGroup && name != BATTERY) collectSignals(child, result)
        }
    }

    private fun restoreHost(s: Session) {
        s.active = false
        s.accessibility.restore()
    }

    private fun report() {
        val c = context ?: return
        val text = when {
            fault != null -> "已回退原图标：$fault"
            !supported -> "固件不在已分析范围，保留原图标"
            !config.enabled -> "Hook 已加载；替换开关关闭"
            sessions.isEmpty() -> "Hook 已加载；等待状态栏容器"
            !state.ready -> "Hook 已加载；等待完整系统状态，保留原图标"
            sessions.values.any { it.active } -> "替换已运行；真机外观仍需人工验收"
            else -> "Hook 已加载；当前显示位置未启用"
        }
        if (lastReport != text) {
            lastReport = text
            XposedBridge.log("3IN1: $text; revision=$revision; hosts=${sessions.size}; controller=${controller != null}")
        }
        io.post {
            runCatching { c.contentResolver.call(Uri.parse("content://${IndicatorConfig.PACKAGE}.diagnostics"),
                "report", null, Bundle().apply { putString("status", text); putLong("revision", revision) }) }
        }
    }

    private fun fail(error: Throwable) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { fail(error) }; return }
        fault = "${error.javaClass.simpleName}: ${error.message?.take(160)}"
        runCatching { source?.stop() }; source = null
        runCatching { refresh(layout = true) }
        // Last-resort restoration does not depend on the failing state or reflection.
        changingVisibility = true
        hidden.toMap().forEach { (v, visibility) -> runCatching { v.visibility = visibility } }
        hidden.clear()
        changingVisibility = false
        sessions.values.forEach { runCatching { restoreHost(it); it.host.requestLayout(); it.host.invalidate() } }
        if (SystemClock.uptimeMillis() - lastLog > 10_000) {
            lastLog = SystemClock.uptimeMillis()
            XposedBridge.log("3IN1 restored stock icons: $error")
        }
        report()
    }

    private inline fun guarded(block: () -> Unit) { try { block() } catch (e: Throwable) { fail(e) } }
    private fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try { block(param) } catch (e: Throwable) { fail(e) }
        }
    }

    companion object {
        const val BATTERY = "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView"
        const val NETWORK = "com.android.systemui.statusbar.connectivity.NetworkControllerImpl"
        val ROOTS = setOf("com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView")
        val SIGNALS = setOf(
            "com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBarWifiView",
            "com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBarMobileView",
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView")
        val SLOTS = setOf("wifi", "mobile", "airplane", "nosim_all", "nosim_one", "nosim_two",
            "volte", "vowifi", "novolte")
    }
}
