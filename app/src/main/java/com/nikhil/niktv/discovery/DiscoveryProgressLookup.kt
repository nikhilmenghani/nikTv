package com.nikhil.niktv.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable derived progress for one profile/library snapshot; no provider lookup or disk cache added. */
internal class DiscoveryProgressLookup private constructor(
    val profile: String?,
    private val recents: Map<RecentItem, PlaybackProgress?>,
    private val movies: Map<String, Float?>,
    private val series: Map<String, Float?>,
) {
    fun recent(item: RecentItem): PlaybackProgress? = if (item.profileKey == profile) recents[item] else null
    fun provider(type: CatalogType, id: String): Float? =
        (if (type == CatalogType.SERIES) series else movies)[id]

    companion object {
        fun build(profile: String?, recent: List<RecentItem>, progress: List<PlaybackProgress>): DiscoveryProgressLookup {
            if (profile == null) return DiscoveryProgressLookup(null, emptyMap(), emptyMap(), emptyMap())
            // Prefix ranges preserve arbitrary profile/provider IDs without parsing their delimiters.
            val sorted = progress.withIndex().sortedBy { it.value.key }
            val byRecent = HashMap<RecentItem, PlaybackProgress?>()
            val byPrefix = HashMap<String, PlaybackProgress?>()
            val movies = HashMap<String, Float?>()
            val series = HashMap<String, Float?>()
            for (entry in recent) {
                if (entry.profileKey != profile) continue
                val prefix = discoveryRecentProgressPrefix(entry)
                val match = if (entry.alternateTarget != null) progress.firstOrNull { it.key == prefix } else prefix?.let {
                    if (!byPrefix.containsKey(it)) byPrefix[it] = latestPrefixProgress(sorted, it)
                    byPrefix[it]
                }
                byRecent[entry] = match
                val target = when (entry.kind) {
                    FavoriteKind.MOVIE -> movies
                    FavoriteKind.SERIES -> series
                    else -> continue
                }
                val fraction = discoveryProgressFraction(match)
                // The first matching recent wins even when it has no valid progress.
                // putIfAbsent would incorrectly replace a stored null with a later recent.
                if (!target.containsKey(entry.media.id)) target[entry.media.id] = fraction
                if (entry.kind == FavoriteKind.SERIES) entry.series?.id?.let { id ->
                    if (!target.containsKey(id)) target[id] = fraction
                }
            }
            return DiscoveryProgressLookup(profile, byRecent, movies, series)
        }
    }
}

private fun latestPrefixProgress(sorted: List<IndexedValue<PlaybackProgress>>, prefix: String): PlaybackProgress? {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (sorted[middle].value.key < prefix) low = middle + 1 else high = middle
    }
    var latest: IndexedValue<PlaybackProgress>? = null
    while (low < sorted.size) {
        val entry = sorted[low++]
        if (!entry.value.key.startsWith(prefix)) break
        val previous = latest
        if (previous == null || entry.value.updatedAtMillis > previous.value.updatedAtMillis ||
            entry.value.updatedAtMillis == previous.value.updatedAtMillis && entry.index < previous.index) latest = entry
    }
    return latest?.value
}

internal fun discoveryProgressFraction(progress: PlaybackProgress?): Float? = progress
    ?.takeIf { it.durationMillis > 0 }
    ?.let { (it.positionMillis.toFloat() / it.durationMillis).coerceIn(0f, 1f) }

@Composable
internal fun rememberDiscoveryProgressLookup(profile: String?, recents: List<RecentItem>, progress: List<PlaybackProgress>): DiscoveryProgressLookup? {
    val loaded by produceState<DiscoveryProgressLookup?>(null, profile, recents, progress) {
        value = withContext(Dispatchers.Default) { DiscoveryProgressLookup.build(profile, recents, progress) }
    }
    // The previous same-profile snapshot may remain visible until the latest computation is ready.
    // produceState cancellation rejects an obsolete result after a profile/data replacement.
    return loaded?.takeIf { it.profile == profile }
}
