package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal fun publicRecentArtwork(card: DiscoveryCard?, media: MediaItem, type: CatalogType): String? =
    card?.takeIf { it.id > 0 && !it.adult && it.series == (type == CatalogType.SERIES) &&
        ((media.externalTmdbId ?: 0) <= 0 || media.externalTmdbId == it.id) }
        ?.let { listOfNotNull(it.poster, it.backdrop).firstOrNull { url -> url.startsWith("https://image.tmdb.org/") } }

/** Cached/public presentation only. History identities, commands and progress stay untouched. */
internal suspend fun loadRecentArtwork(cache: DiscoveryCache, recents: List<RecentItem>, maxLookups: Int,
    lookup: suspend (MediaItem, CatalogType) -> DiscoveryCard?, publish: (Map<String, String>) -> Unit) {
    val resolved = linkedMapOf<String, String>()
    val missing = mutableListOf<Triple<String, MediaItem, CatalogType>>()
    for (recent in recents) {
        if (recent.kind !in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES)) continue
        coroutineContext.ensureActive()
        val type = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES
        val media = (recent.series ?: recent.media).let {
            it.copy(externalTmdbId = it.externalTmdbId ?: recent.lastPlayed?.externalTmdbId)
        }
        val key = "${recent.profileKey}:${recent.contentIdentity()}"
        val artwork = withContext(Dispatchers.IO) {
            publicRecentArtwork(libraryCachedPresentation(cache, LibraryEntry(media, recent.kind, recent = recent)), media, type)
                ?: cachedProviderArtwork(cache, media, type)
        }
        if (artwork != null) resolved[key] = artwork
        else if (!media.isAdult && !media.logo.orEmpty().startsWith("https://image.tmdb.org/")) missing += Triple(key, media, type)
    }
    publish(resolved.toMap())
    for ((key, media, type) in missing.take(maxLookups.coerceAtLeast(0))) {
        val card = try { withTimeoutOrNull(10_000) { lookup(media, type) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
        coroutineContext.ensureActive()
        publicRecentArtwork(card, media, type)?.let { resolved[key] = it; publish(resolved.toMap()) }
    }
}
