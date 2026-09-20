package io.github.threeinone.config

import android.content.ContentProvider
import android.content.ContentValues
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
            p.edit().putString("status", extras?.getString("status")?.take(2000))
                .putLong("time", System.currentTimeMillis())
                .putLong("revision", extras?.getLong("revision", 0) ?: 0).apply()
        }
        return Bundle().apply {
            putString("status", p.getString("status", "尚未收到 SystemUI 回执"))
            putLong("time", p.getLong("time", 0))
            putLong("revision", p.getLong("revision", 0))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
