package io.github.marukon.adalkeyisolator

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class StatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_REPORT || arg.isNullOrBlank() || extras == null) return null
        val ctx = context ?: return null

        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid()) {
            val pkgs = ctx.packageManager.getPackagesForUid(callingUid) ?: return null
            if (arg !in pkgs || arg !in TARGET_PACKAGE_SET) return null
        }

        val prefs = ctx.getSharedPreferences(PREF_HOOK_STATUS, Context.MODE_PRIVATE)
        val prevRewrites = prefs.getInt("${arg}_rewrite_count", 0)
        val newRewrites = extras.getInt(KEY_REWRITE_COUNT, 0)
        val bootCount = extras.getInt(KEY_BOOT_HOOK_COUNT, 0)
        val msalCount = extras.getInt(KEY_MSAL_HOOK_COUNT, 0)
        val processName = extras.getString(KEY_PROCESS_NAME).orEmpty()
        val versionName = extras.getString(KEY_VERSION_NAME).orEmpty()
        val error = extras.getString(KEY_ERROR).orEmpty()

        prefs.edit()
            .putInt("${arg}_boot_hooks", bootCount)
            .putInt("${arg}_msal_hooks", msalCount)
            .putInt("${arg}_rewrite_count", maxOf(prevRewrites, newRewrites))
            .putString("${arg}_process", processName)
            .putString("${arg}_version", versionName)
            .putString("${arg}_error", error)
            .putLong("${arg}_updated_at", System.currentTimeMillis())
            .apply()

        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "io.github.marukon.adalkeyisolator.status"
        const val METHOD_REPORT = "report"
        const val PREF_HOOK_STATUS = "hook_status"

        const val KEY_BOOT_HOOK_COUNT = "boot_hooks"
        const val KEY_MSAL_HOOK_COUNT = "msal_hooks"
        const val KEY_REWRITE_COUNT = "rewrite_count"
        const val KEY_PROCESS_NAME = "process_name"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_ERROR = "error"

        val TARGET_PACKAGE_SET = setOf(
            "com.microsoft.office.excel",
            "com.microsoft.office.word",
            "com.microsoft.office.powerpoint",
            "com.microsoft.office.onenote",
            "com.microsoft.office.officehubrow",
        )
    }
}
