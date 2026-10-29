package com.nikhil.niktv.sports

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal enum class SportsFlagReason { MANUAL, PLAYBACK_FAILED }

@Serializable
internal data class SportsChannelFlag(val reason: SportsFlagReason, val updatedAt: Long) {
    val label: String get() = if (reason == SportsFlagReason.MANUAL) "Flagged unavailable" else "Previously failed"
}

internal enum class SportsAvailabilityFilter(val title: String) {
    ALL("All channels"), HIDE_FLAGGED("Hide flagged"), FLAGGED_ONLY("Flagged only");

    fun includes(flag: SportsChannelFlag?): Boolean = when (this) {
        ALL -> true
        HIDE_FLAGGED -> flag == null
        FLAGGED_ONLY -> flag != null
    }
}

/** Local observations, not a provider guarantee. No stream addresses or IPTV profile data are saved. */
internal class SportsAvailabilityStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_availability_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun flags(): Map<String, SportsChannelFlag> = runCatching {
        json.decodeFromString<Map<String, SportsChannelFlag>>(prefs.getString("flags", "{}")!!)
    }.getOrDefault(emptyMap())
    fun filter(): SportsAvailabilityFilter = runCatching {
        SportsAvailabilityFilter.valueOf(prefs.getString("filter", null).orEmpty())
    }.getOrDefault(SportsAvailabilityFilter.ALL)
    fun setFilter(filter: SportsAvailabilityFilter) { prefs.edit().putString("filter", filter.name).apply() }
    fun flag(key: String, reason: SportsFlagReason): Map<String, SportsChannelFlag> {
        val old = flags()
        // A transient playback observation must never replace the user's explicit decision.
        if (reason == SportsFlagReason.PLAYBACK_FAILED && old[key]?.reason == SportsFlagReason.MANUAL) return old
        return save(old + (key to SportsChannelFlag(reason, now())))
    }
    fun clear(key: String, automaticOnly: Boolean = false): Map<String, SportsChannelFlag> {
        val old = flags()
        if (key !in old || (automaticOnly && old[key]?.reason == SportsFlagReason.MANUAL)) return old
        return save(old - key)
    }
    private fun save(flags: Map<String, SportsChannelFlag>): Map<String, SportsChannelFlag> {
        prefs.edit().putString("flags", json.encodeToString(flags)).apply()
        return flags
    }
}
