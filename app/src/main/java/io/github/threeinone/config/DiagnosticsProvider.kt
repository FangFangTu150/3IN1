package io.github.threeinone.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Accepts diagnostic text only, never configuration or executable commands. */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val own = uid == Process.myUid()
        val systemUi = c.packageManager.getPackagesForUid(uid)?.contains("com.android.systemui") == true
        if (!own && !systemUi) throw SecurityException("Not a diagnostic peer")
        val p = c.getSharedPreferences("diagnostics", 0)
        if (method == "report" && systemUi) {
            storeDiagnosticReport(p, extras)
        }
        return Bundle().apply {
            putString("status", p.getString("status", "尚未收到 SystemUI 回执"))
            putLong("time", p.getLong("time", 0))
            putLong("revision", p.getLong("revision", 0))
            putString(IndicatorConfig.PROBE_TOKEN, p.getString(IndicatorConfig.PROBE_TOKEN, null))
            putString(IndicatorConfig.PROBE_STATUS, p.getString(IndicatorConfig.PROBE_STATUS, null))
            putLong(IndicatorConfig.PROBE_TIME, p.getLong(IndicatorConfig.PROBE_TIME, 0))
            putLong(IndicatorConfig.PROBE_REVISION, p.getLong(IndicatorConfig.PROBE_REVISION, 0))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal fun storeDiagnosticReport(prefs: SharedPreferences, extras: Bundle?) {
    val status = extras?.getString("status")?.take(2000)
    val revision = extras?.getLong("revision", 0) ?: 0
    val time = System.currentTimeMillis()
    val editor = prefs.edit()
        .putString("status", status)
        .putLong("time", time)
        .putLong("revision", revision)
    extras?.getString(IndicatorConfig.PROBE_TOKEN)?.takeIf { it.isNotBlank() }?.let { token ->
        editor.putString(IndicatorConfig.PROBE_TOKEN, token)
            .putString(IndicatorConfig.PROBE_STATUS, status)
            .putLong(IndicatorConfig.PROBE_TIME, time)
            .putLong(IndicatorConfig.PROBE_REVISION, revision)
    }
    editor.apply()
}
