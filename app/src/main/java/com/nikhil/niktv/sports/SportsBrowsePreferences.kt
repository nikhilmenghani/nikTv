package com.nikhil.niktv.sports

import android.content.Context

internal val SPORTS_STATUS_FILTERS = listOf("Now & next", "Live", "Recent", "Upcoming", "All events")
internal data class SportsBrowseDefaults(val sport: String = "All sports", val status: String = "Now & next")

/** Device-local browsing defaults, independent of IPTV profiles and playback preferences. */
internal class SportsBrowsePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_browse", Context.MODE_PRIVATE)
    fun read() = SportsBrowseDefaults(
        prefs.getString("sport", null)?.trim()?.takeIf { it.length in 1..100 } ?: "All sports",
        prefs.getString("status", null)?.let { if (it == "Finished") "Recent" else it }?.takeIf { it in SPORTS_STATUS_FILTERS } ?: "Now & next")
    fun setSport(value: String) { require(value.trim().length in 1..100); prefs.edit().putString("sport", value.trim()).apply() }
    fun setStatus(value: String) { require(value in SPORTS_STATUS_FILTERS); prefs.edit().putString("status", value).apply() }
}

internal data class CricketBrowseDefaults(val version: String = defaultCricketAppVersion(), val region: String = "CA")

internal class CricketBrowsePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cricket_video_region", Context.MODE_PRIVATE)
    fun read() = CricketBrowseDefaults(
        prefs.getString("app_version", null)?.takeIf { it in setOf("16.11.01", "6.16.04") } ?: defaultCricketAppVersion(),
        prefs.getString("region", null)?.takeIf { it in setOf("CA", "IN") } ?: "CA")
    fun setVersion(value: String) { require(value in setOf("16.11.01", "6.16.04")); prefs.edit().putString("app_version", value).apply() }
    fun setRegion(value: String) { require(value in setOf("CA", "IN")); prefs.edit().putString("region", value).apply() }
}
