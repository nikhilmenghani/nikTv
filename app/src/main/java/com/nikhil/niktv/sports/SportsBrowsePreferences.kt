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
