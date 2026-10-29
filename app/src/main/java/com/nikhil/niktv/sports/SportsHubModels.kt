package com.nikhil.niktv.sports

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SportsTab(val title: String) { EVENTS("Live Events"), CHANNELS("Sports"), CATEGORIES("Categories") }

@Serializable
internal data class SportsFormat(val title: String, val webLink: String = "", val geo: String = "ALL")

@Serializable
internal data class SportsCategory(val id: String, val title: String, val image: String = "", val link: String = "",
    val geo: String = "ALL", val custom: Boolean = false) {
    val isPlaylist: Boolean get() = link.isNotBlank()
    val path: String get() {
        val key = title.trim().lowercase(Locale.ROOT).replace(' ', '-')
        if (!sportsSlug(key)) throw SportsException("This category has an unsupported name.")
        return "categories/$key.txt"
    }
}

internal data class SportsEventFilter(val id: String, val title: String, val image: String = "")

@Serializable
internal data class SportsChannel(val id: String, val title: String, val slug: String, val category: String = "",
    val image: String = "", val formats: List<SportsFormat> = emptyList(), val geo: String = "ALL",
    val group: String = "", val playlist: SportsCategory? = null, val direct: SportsStream? = null) {
    val key: String get() = if (playlist == null) slug else "playlist:${playlist.id}:$id"
}

internal data class SportsChannelDetail(val streams: List<SportsStream> = emptyList(), val related: List<SportsChannel> = emptyList(),
    val previous: String? = null, val next: String? = null, val geo: String = "ALL", val header: String = "", val body: String = "")

internal fun SportsEvent.asChannel() = SportsChannel(id, title, slug, "Live Events", image,
    formats.ifEmpty { choices.map { SportsFormat(it) } }, geo)

internal fun sportsSlug(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,200}"))
internal fun sportsNetworkUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
    it.username.isEmpty() && it.password.isEmpty()
} == true

internal fun sportsEventMatches(event: SportsEvent, sport: String, status: String, query: String, now: Instant): Boolean =
    (sport in setOf("All sports", "All") || event.sport.equals(sport, true) || event.eventName.equals(sport, true) ||
        event.teamA.equals(sport, true) || event.teamB.equals(sport, true)) &&
    (query.isBlank() || listOf(event.title, event.teamA, event.teamB, event.competition).any { it.contains(query.trim(), true) }) &&
    when (status) {
        "All events" -> true
        "Now & next" -> event.status(now) != SportsStatus.FINISHED
        "Recent", "Finished" -> event.status(now) == SportsStatus.FINISHED
        else -> event.status(now).label == status
    }

internal fun sportsEventClock(event: SportsEvent, now: Instant): String = when (event.status(now)) {
    SportsStatus.UPCOMING -> "Starts in ${sportsDuration(Duration.between(now, event.start).seconds)}"
    SportsStatus.LIVE -> "Live · ${sportsDuration(Duration.between(event.start, now).seconds)} elapsed"
    SportsStatus.FINISHED -> "Finished"
    SportsStatus.UNKNOWN -> "Schedule unavailable"
}

private fun sportsDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s >= 86_400 -> "${s / 86_400}d ${s % 86_400 / 3600}h"
        s >= 3600 -> "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s % 3600 / 60, s % 60)
        else -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }
}
