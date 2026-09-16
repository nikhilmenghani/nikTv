package com.nikhil.niktv.data

import android.content.Context
import android.os.Build

object SearchSyncCommitPreferences {
    private fun prefs(context: Context) =
        context.getSharedPreferences("search_sync_commit", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("include_device", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("include_device", enabled).apply()
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.joinToString(" ")
        .replace(Regex("[\\r\\n]+"), " ").take(100).ifBlank { "Android device" }

    fun message(context: Context, mediaType: String, profileName: String? = null): String {
        val profile = profileName?.replace(Regex("[\\r\\n]+"), " ")?.trim()?.take(100)
        val base = "Update NikTV $mediaType search metadata" +
            (profile?.takeIf { it.isNotBlank() }?.let { " · Profile: $it" } ?: "")
        return if (enabled(context)) "$base · ${deviceName()}" else base
    }
}
