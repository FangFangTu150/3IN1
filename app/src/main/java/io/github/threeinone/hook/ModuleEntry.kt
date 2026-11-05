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
import io.github.threeinone.config.SafePreferences
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorView
import java.util.IdentityHashMap
import kotlin.math.min
import java.lang.reflect.Member

class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.android.systemui" || param.processName != "com.android.systemui") return
        XposedBridge.log("3IN1: SystemUI entry")
        runCatching { ColorOsAdapter(param.classLoader).install() }
            .onFailure { XposedBridge.log("3IN1: adapter unavailable: $it") }
    }
}

private class ColorOsAdapter(private val loader: ClassLoader) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var io: Handler
    private val resources = OwnedResources()
    @Volatile private var installed = false
    private val prefs = XSharedPreferences(IndicatorConfig.PACKAGE, IndicatorConfig.FILE)
    private var config = IndicatorConfig()
    private var state = IndicatorState()
    private var revision = 0L
    private var pendingProbeToken: String? = null
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
    private val dirtySessions = java.util.Collections.newSetFromMap(IdentityHashMap<Session, Boolean>())
    private val update = Runnable {
        val dirty = dirtySessions.toSet()
        dirtySessions.clear()
        guarded { refresh(structures = dirty); report() }
    }
    private val reload = Runnable {
        runCatching {
            prefs.reload()
            val next = IndicatorConfig.read(prefs)
            val rev = SafePreferences.long(prefs, "revision", 0)
            val hasSettings = prefs.contains("revision")
            main.post {
                if (!installed) return@post
                config = next; revision = rev
                if (!hasSettings) XposedBridge.log("3IN1: no shared configuration yet; open settings after activation")
                val probeToken = pendingProbeToken.also { pendingProbeToken = null }
                guarded { manageSource(); refresh(layout = true); report(probeToken) }
            }
        }.onFailure { error -> main.post { pendingProbeToken = null; fail(error) } }
    }
    private lateinit var reloadRequests: CoalescedReload

    private inner class Session(val host: ViewGroup, val scope: HostScope) {
        val root get() = scope.root
        val icon = IndicatorView(host.context)
        val dualTone = XposedHelpers.newInstance(loader.loadClass("com.android.systemui.DualToneHandler"), host.context)
        val accessibility = AccessibilityOverride(host)
        var active = false
        var signals = emptySet<View>()
        var capable = true
        var rejected = emptySet<String>()
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            dirtySessions.add(this)
            if (!main.hasCallbacks(update)) main.post(update)
        }
    }

    fun install() {
        try { resources.install { installHooks() } } catch (error: Throwable) {
            installed = false
            main.removeCallbacksAndMessages(null)
            if (resources.pending.isNotEmpty()) XposedBridge.log("3IN1 install cleanup pending: ${resources.pending}")
            throw error
        }
    }

    private fun hook(member: Member, callback: XC_MethodHook) {
        val handle = XposedBridge.hookMethod(member, callback)
        resources.add("hook:${resources.pending.size}:$member") { handle.unhook() }
    }

    private fun hookMethods(type: Class<*>, name: String, callback: XC_MethodHook) {
        val methods = type.declaredMethods.filter { it.name == name }
        check(methods.isNotEmpty()) { "Missing hook: ${type.name}.$name" }
        methods.forEach { hook(it, callback) }
    }

    @Suppress("DEPRECATION") // Optional legacy XSharedPreferences notifications; explicit broadcast remains supported.
    private fun installHooks() {
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
        worker = HandlerThread("3IN1-config").apply { start() }
        resources.add("config-worker") { worker.quitSafely() }
        io = Handler(worker.looper)
        reloadRequests = CoalescedReload(io, reload)
        network.declaredConstructors.forEach { constructor -> hook(constructor, after { p ->
            main.post {
                if (installed) guarded {
                    controller = p.thisObject
                    initialize(XposedHelpers.getObjectField(p.thisObject, "mContext") as Context)
                    manageSource()
                }
            }
        }) }
        hookMethods(battery, "onAttachedToWindow", after { p ->
            val host = p.thisObject as ViewGroup
            host.post { if (installed) guarded { if (host.isAttachedToWindow) attach(host) } }
        })
        hookMethods(battery, "onDetachedFromWindow", after { p ->
            guarded { detach(p.thisObject as ViewGroup) }
        })
        hookMethods(battery, "onDarkChanged", after { p ->
            guarded {
                sessions[p.thisObject]?.let { session ->
                    syncTint(session)
                    session.host.invalidate()
                }
            }
        })
        hookMethods(battery, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (!installed || sessions.isEmpty()) return
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
                    if (session.icon.width != w.toInt() || session.icon.height != h.toInt()) {
                        session.icon.measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.EXACTLY))
                        session.icon.layout(0, 0, w.toInt(), h.toInt())
                    }
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
        hook(LinearLayout::class.java.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType), after { p ->
                guarded {
                    val s = sessions[p.thisObject] ?: return@guarded
                    if (!s.active) return@guarded
                    val width = ((config.size + config.horizontalPadding * 2) * s.host.resources.displayMetrics.density).toInt()
                    XposedHelpers.callMethod(s.host, "setMeasuredDimension",
                        View.resolveSize(width, p.args[0] as Int), s.host.measuredHeight)
                }
            })
        hook(View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!installed || changingVisibility || hidden.isEmpty()) return
                    val view = p.thisObject as View
                    if (hidden.containsKey(view)) {
                        hidden[view] = p.args[0] as Int
                        p.args[0] = View.GONE
                    }
                }
            })
        hook(View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!installed || sessions.isEmpty()) return
                    val session = sessions[p.thisObject] ?: return
                    p.args[0] = session.accessibility.systemDescription(p.args[0] as? CharSequence)
                }
            })
        // ColorOS ignores View.GONE when measuring and positioning status icon slots.
        for (method in listOf("onMeasure", "updateStates")) {
            hookMethods(iconContainer, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!installed || hidden.isEmpty()) return
                    guarded {
                        val group = p.thisObject as ViewGroup
                        val excluded = (0 until group.childCount).map { group.getChildAt(it) }
                            .filter { hidden.containsKey(it) }
                            .mapNotNull { SignalScope.slot(it) }
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
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            resources.add("preference-listener") { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        }
        installed = true
        reloadRequests.request()
        XposedBridge.log("3IN1: adapter hooks installed")
    }

    private fun initialize(c: Context) {
        if (context != null) return
        val version = runCatching {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(IndicatorConfig.PACKAGE, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
        XposedBridge.log("3IN1 $version: module initialized")
        // Runtime compatibility is documented as ColorOS 16-only; do not gate on
        // an exact model, firmware build, or SystemUI version.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == IndicatorConfig.REFRESH) {
                    intent.getStringExtra(IndicatorConfig.PROBE_TOKEN)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { pendingProbeToken = it }
                    reloadRequests.request()
                }
            }
        }
        c.registerReceiver(receiver, IntentFilter(IndicatorConfig.REFRESH),
            IndicatorConfig.REFRESH_PERMISSION, main, Context.RECEIVER_EXPORTED)
        resources.add("refresh-receiver") { c.unregisterReceiver(receiver) }
        context = c
        supported = true
        reloadRequests.request()
    }

    private fun attach(host: ViewGroup) {
        if (sessions.containsKey(host)) return
        val scope = HostScope.resolve(host) ?: return
        if (scope.kind == HostKind.SHADE && sessions.values.any { it.root === scope.root }) return
        initialize(host.context)
        val s = Session(host, scope)
        syncTint(s)
        sessions[host] = s
        s.root.viewTreeObserver.addOnGlobalLayoutListener(s.layoutListener)
        manageSource()
        refresh(layout = true)
        report()
    }

    private fun detach(host: ViewGroup) {
        val s = sessions.remove(host) ?: return
        dirtySessions.remove(s)
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

    private fun manageSource() {
        val shouldRun = supported && config.enabled && fault == null && sessions.isNotEmpty()
        if (!shouldRun) {
            source?.stop()
            state = IndicatorState()
        } else if (controller != null && context != null) {
            if (source == null) source = SystemStateSource(context!!, controller!!, loader, {
                val wasReady = state.ready
                state = it
                guarded { refresh(); if (wasReady != state.ready) report() }
            }, { fail(it) })
            source?.start()
        }
    }

    private fun refresh(layout: Boolean = false, structures: Set<Session> = emptySet()) {
        val toHide = java.util.Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        sessions.values.forEach { s ->
            if (layout || s in structures) {
                val inspection = SignalScope.inspect(s.root, s.scope.kind == HostKind.SHADE)
                s.signals = inspection.signals
                s.capable = inspection.supported
                s.rejected = inspection.rejected
            }
            val active = supported && config.enabled && fault == null && state.ready &&
                s.capable && s.scope.kind.enabled(config.lockscreen) && s.host.visibility == View.VISIBLE
            val transition = s.active != active
            val contentChanged = active && (s.icon.state != state || s.icon.config != config)
            val oldTint = s.icon.tint
            s.active = active
            if (active) {
                if (layout || transition || s in structures) syncTint(s)
                s.icon.state = state
                s.icon.config = config
                val description = state.description()
                if (transition || s in structures || s.host.contentDescription != description) {
                    s.accessibility.show(description)
                }
                toHide.addAll(s.signals)
            } else restoreHost(s)
            if (layout || transition) s.host.requestLayout()
            if (layout || transition || contentChanged || oldTint != s.icon.tint) s.host.invalidate()
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

    private fun restoreHost(s: Session) {
        s.active = false
        s.accessibility.restore()
    }

    private fun report(probeToken: String? = null) {
        val c = context ?: return
        val status = when {
            fault != null -> "已回退原图标：$fault"
            !supported -> "固件不在已分析范围，保留原图标"
            !config.enabled -> "Hook 已加载；替换开关关闭"
            sessions.isEmpty() -> "Hook 已加载；等待状态栏容器"
            !state.ready -> "Hook 已加载；等待完整系统状态，保留原图标"
            sessions.values.any { it.active } -> "替换已运行；真机外观仍需人工验收"
            else -> "Hook 已加载；当前显示位置未启用"
        }
        val counts = HostKind.entries.joinToString("，") { kind ->
            val scoped = sessions.values.filter { it.scope.kind == kind }
            "${kind.name}=${scoped.count { it.active }}/${scoped.size}"
        }
        val rejected = sessions.values.count { !it.capable }
        val rejectedTypes = sessions.values.filter { !it.capable }.flatMap { it.rejected }.distinct().take(4)
        val text = "$status\n宿主接管/识别：$counts\n结构能力不匹配=$rejected" +
            if (rejectedTypes.isEmpty()) "" else "\n未接管类型：${rejectedTypes.joinToString()}"
        val changed = lastReport != text
        if (lastReport != text) {
            lastReport = text
            XposedBridge.log("3IN1: $text; revision=$revision; hosts=${sessions.size}; controller=${controller != null}")
        }
        if (!changed && probeToken == null) return
        io.post {
            runCatching { c.contentResolver.call(Uri.parse("content://${IndicatorConfig.PACKAGE}.diagnostics"),
                "report", null, Bundle().apply {
                    putString("status", text)
                    putLong("revision", revision)
                    if (!probeToken.isNullOrBlank()) putString(IndicatorConfig.PROBE_TOKEN, probeToken)
                }) }
        }
    }

    private fun fail(error: Throwable) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { fail(error) }; return }
        fault = "${error.javaClass.simpleName}: ${error.message?.take(160)}"
        runCatching { source?.stop() }.onFailure {
            fault += "；${it.message?.take(160)}"
            XposedBridge.log("3IN1 cleanup pending: $it")
        }
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
            if (!installed) return
            try { block(param) } catch (e: Throwable) { fail(e) }
        }
    }

    companion object {
        const val BATTERY = HostScope.BATTERY
        const val NETWORK = "com.android.systemui.statusbar.connectivity.NetworkControllerImpl"
    }
}
