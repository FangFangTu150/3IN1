package io.github.threeinone.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.config.PreferenceMigration
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorView
import io.github.threeinone.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var small: IndicatorView
    private lateinit var large: IndicatorView
    private lateinit var diagnostic: TextView
    private lateinit var saved: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var previewIndex = 0
    private var lastProbe = 0L
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) Color.rgb(241, 244, 245) else Color.rgb(25, 31, 33)
    private val muted get() = if (dark) Color.rgb(160, 173, 178) else Color.rgb(94, 109, 115)
    private val accent get() = if (dark) Color.rgb(99, 215, 196) else Color.rgb(0, 127, 112)
    private val background get() = if (dark) Color.rgb(21, 23, 25) else Color.WHITE
    private val sync = Runnable {
        val revision = System.currentTimeMillis()
        writer.execute {
            val success = prefs.edit().putLong("revision", revision).commit()
            handler.post {
                if (isDestroyed) return@post
                saved.text = if (success) "设置已保存 · 等待系统回执" else "保存失败，请重试"
                if (success) probe()
            }
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        @Suppress("DEPRECATION")
        prefs = try { getSharedPreferences(IndicatorConfig.FILE, MODE_WORLD_READABLE) }
        catch (_: SecurityException) { getSharedPreferences(IndicatorConfig.FILE, MODE_PRIVATE) }
        PreferenceMigration.importOnce(prefs,
            java.io.File(applicationInfo.dataDir, "shared_prefs/${IndicatorConfig.FILE}.xml"))
        previewIndex = bundle?.getInt("preview", 0)?.coerceIn(IndicatorState.previews.indices) ?: 0
        buildPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("preview", previewIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        probe()
    }

    override fun onDestroy() {
        if (handler.hasCallbacks(sync)) {
            handler.removeCallbacks(sync)
            sync.run()
        }
        handler.removeCallbacksAndMessages(null)
        writer.shutdown()
        super.onDestroy()
    }

    private fun buildPage() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(20), 0, dp(20), 0)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        header.addView(label("3IN1", 26f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        },
            LinearLayout.LayoutParams(0, dp(58), 1f))
        header.addView(iconButton(android.R.drawable.ic_menu_revert, "恢复默认") {
            AlertDialog.Builder(this).setTitle("恢复默认设置？")
                .setMessage("恢复全部外观参数并关闭系统替换。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    prefs.edit().clear().putBoolean(PreferenceMigration.MARKER, true).apply()
                    buildPage(); scheduleSave()
                }.show()
        })
        root.addView(header)

        val previews = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val natural = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val actualFrame = FrameLayout(this)
        small = IndicatorView(this).apply { tint = ink }
        actualFrame.addView(small, FrameLayout.LayoutParams(dp(22), dp(28), Gravity.CENTER))
        natural.addView(actualFrame, LinearLayout.LayoutParams(-1, dp(76)))
        natural.addView(label("实际尺寸", 12f, muted).apply { gravity = Gravity.CENTER })
        previews.addView(natural, LinearLayout.LayoutParams(0, dp(130), 1f))
        val enlarged = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = shape(Color.rgb(21, 25, 27), 6f)
        }
        large = IndicatorView(this).apply { tint = Color.WHITE }
        enlarged.addView(large, LinearLayout.LayoutParams(dp(90), dp(104)))
        enlarged.addView(label("放大预览", 12f, Color.rgb(181, 195, 200)).apply { gravity = Gravity.CENTER })
        previews.addView(enlarged, LinearLayout.LayoutParams(0, dp(130), 1f))
        root.addView(previews)

        val spinner = Spinner(this).apply {
            contentDescription = "预览状态"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                IndicatorState.previews.map { it.first })
            setSelection(previewIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    previewIndex = position; updatePreview()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(spinner, LinearLayout.LayoutParams(-1, dp(44)))
        saved = label("本机设置", 12f, muted)
        root.addView(saved, LinearLayout.LayoutParams(-1, dp(24)))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        section("系统显示")
        toggle("启用三合一", "enabled", false, confirm = true)
        toggle("锁屏显示三合一", "lockscreen", true)
        section("尺寸与位置")
        slider("整体图标尺寸", "size", 16, 32, 22, "dp")
        slider("中间 Wi-Fi 大小", "wifiScale", 70, 150, 115, "%")
        slider("网络类型文字大小", "textScale", 80, 250, 150, "%")
        slider("图标粗细", "stroke", 60, 160, 120, "%")
        slider("信号点大小", "dotScale", 60, 140, 100, "%")
        slider("信号点间距", "dotSpacing", 70, 125, 100, "%")
        slider("水平留白", "horizontalPadding", 0, 8, 2, "dp")
        slider("垂直偏移", "verticalOffset", -4, 4, 0, "dp")
        section("状态颜色")
        toggle("启用状态颜色", "colors", true)
        colorRow("充电与充满", "chargingColor", 0xFF1CB753.toInt())
        colorRow("低电量", "lowColor", 0xFFE7433A.toInt())
        colorRow("省电模式", "saverColor", 0xFFFFB300.toInt())
        slider("低电量阈值", "lowThreshold", 5, 50, 20, "%")
        section("电量数字")
        toggle("始终显示电量数字", "showNumber", false)
        toggle("低于阈值自动显示（含等于）", "autoNumber", false)
        slider("电量数字显示阈值", "numberThreshold", 1, 100, 20, "%")
        slider("电量数字大小", "numberScale", 75, 250, 115, "%")
        section("适配诊断")
        val diagnosticsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        diagnostic = label("尚未收到 SystemUI 回执", 13f, muted).apply { setTextIsSelectable(true) }
        diagnosticsRow.addView(diagnostic, LinearLayout.LayoutParams(0, -2, 1f))
        diagnosticsRow.addView(iconButton(android.R.drawable.ic_popup_sync, "刷新诊断") { probe() })
        controls.addView(diagnosticsRow)
        val recovery = Button(this).apply {
            text = "恢复原图标"
            setOnClickListener { prefs.edit().putBoolean("enabled", false).apply(); buildPage(); scheduleSave() }
        }
        controls.addView(recovery, LinearLayout.LayoutParams(-1, dp(52)))
        controls.addView(Button(this).apply {
            text = "关于"
            setOnClickListener {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("关于 3IN1")
                    .setIcon(R.mipmap.ic_launcher)
                    .setMessage("版本 $version")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("作者主页") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/17348848")))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        updatePreview()
    }

    private fun updatePreview() {
        if (!::small.isInitialized) return
        val c = IndicatorConfig.read(prefs)
        small.config = c; large.config = c
        val state = IndicatorState.previews[previewIndex].second
        small.state = state; large.state = state
        small.layoutParams = FrameLayout.LayoutParams(dp(c.size), dp(c.size), Gravity.CENTER)
        small.translationY = dp(c.verticalOffset).toFloat()
    }

    private fun scheduleSave() {
        updatePreview()
        saved.text = "正在保存"
        handler.removeCallbacks(sync); handler.postDelayed(sync, 180)
    }

    private fun probe() {
        lastProbe = System.currentTimeMillis()
        sendBroadcast(Intent(IndicatorConfig.REFRESH).setPackage("com.android.systemui"))
        handler.postDelayed({ if (!isDestroyed) updateDiagnostics() }, 650)
        handler.postDelayed({ if (!isDestroyed) updateDiagnostics() }, 2000)
    }

    private fun updateDiagnostics() {
        val p = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val timestamp = p.getLong("time", 0)
        val recent = timestamp >= lastProbe - 300
        val stamp = if (timestamp > 0) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp)) else ""
        diagnostic.text = if (recent) "${p.getString("status", "")}\n$stamp"
            else "未收到本次回执，请检查 LSPosed 作用域及 SystemUI 是否已重启" +
                if (timestamp > 0) "\n上次：${p.getString("status", "")}\n$stamp" else ""
        val received = p.getLong("revision", -1) == prefs.getLong("revision", 0)
        saved.text = if (recent && received) "设置已保存 · SystemUI 已接收" else "设置已保存 · 系统尚未确认"
    }

    private fun section(name: String) {
        val divider = View(this).apply { setBackgroundColor(if (dark) 0xFF303638.toInt() else 0xFFE7EBEC.toInt()) }
        controls.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        controls.addView(label(name, 18f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun toggle(title: String, key: String, default: Boolean, confirm: Boolean = false) {
        val widget = Switch(this).apply {
            text = title; textSize = 16f; setTextColor(ink); isChecked = prefs.getBoolean(key, default)
            setPadding(0, dp(12), 0, dp(12)); minHeight = dp(52)
        }
        var internal = false
        widget.setOnCheckedChangeListener { _, checked ->
            if (!internal) {
                if (confirm && checked) {
                    internal = true; widget.isChecked = false; internal = false
                    AlertDialog.Builder(this).setTitle("启用系统图标替换？")
                        .setMessage("基于 ColorOS 16 机型开发，其他 Android/ColorOS 版本不保证。异常时在 LSPosed 停用本模块并重启即可恢复。不会自动重启系统。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("启用") { _, _ ->
                            internal = true; widget.isChecked = true; internal = false
                            prefs.edit().putBoolean(key, true).apply(); scheduleSave()
                        }.show()
                } else {
                    prefs.edit().putBoolean(key, checked).apply(); scheduleSave()
                }
            }
        }
        controls.addView(widget, LinearLayout.LayoutParams(-1, -2))
    }

    private fun slider(title: String, key: String, min: Int, max: Int, default: Int, unit: String) {
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        top.addView(label(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("${prefs.getInt(key, default)}$unit", 14f, accent).apply { typeface = Typeface.MONOSPACE }
        top.addView(value)
        controls.addView(top)
        val bar = SeekBar(this).apply {
            this.max = max - min; progress = prefs.getInt(key, default).coerceIn(min, max) - min
            contentDescription = title
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            setPadding(dp(4), 0, dp(4), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = progress + min
                    value.text = "$next$unit"
                    prefs.edit().putInt(key, next).apply(); scheduleSave()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controls.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun colorRow(title: String, key: String, default: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(if (dark) 0xFF262B2E.toInt() else 0xFFF1F4F5.toInt(), 4f)
            isClickable = true; isFocusable = true; contentDescription = "$title 颜色"
        }
        val color = prefs.getInt(key, default)
        val swatch = View(this).apply { background = shape(color, 4f) }
        row.addView(swatch, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        row.addView(label(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        val hex = label(hex(color), 13f, muted).apply { typeface = Typeface.MONOSPACE }
        row.addView(hex)
        row.setOnClickListener {
            val input = EditText(this).apply {
                setSingleLine(); setText(hex(prefs.getInt(key, default))); selectAll()
                contentDescription = "十六进制颜色"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(12))
                addView(input)
            }
            val palette = LinearLayout(this)
            listOf(0xFF1CB753, 0xFFE7433A, 0xFFFFB300, 0xFF2F80ED, 0xFF007F70).forEach { entry ->
                palette.addView(View(this).apply {
                    background = shape(entry.toInt(), 4f); contentDescription = hex(entry.toInt())
                    isFocusable = true; setOnClickListener { input.setText(hex(entry.toInt())) }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) })
            }
            holder.addView(palette)
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(holder)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val raw = input.text.toString().trim()
                    if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) input.error = "请输入 #RRGGBB"
                    else {
                        val next = Color.parseColor(raw)
                        prefs.edit().putInt(key, next).apply()
                        swatch.background = shape(next, 4f); hex.text = hex(next)
                        scheduleSave(); dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
        controls.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun iconButton(icon: Int, title: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(accent)
        background = null; contentDescription = title; tooltipText = title
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { action() }
    }
    private fun label(text: String, size: Float, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun shape(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = kotlin.math.round(value * resources.displayMetrics.density).toInt()
}
