package com.nikhil.niktv.model

/** Provider identity only: labels, credentials and resolved URLs never identify shared content. */
fun providerProgressKey(portalKey: String, item: MediaItem, type: CatalogType, series: MediaItem?): String =
    "progress-v3|$portalKey|${type.name}|" + when (type) {
        CatalogType.SERIES -> "series:${series?.id ?: item.id}|s:${item.seasonNumber ?: -1}|e:${item.episodeNumber ?: -1}|provider:${item.portalEpisodeId ?: item.id}"
        CatalogType.MOVIES -> "movie:${item.id}"
        else -> "item:${item.id}"
    }

/** Retaining explicit completion/restart records lets another device distinguish them from absence. */
fun playbackSample(previous: PlaybackProgress?, key: String, positionMillis: Long, durationMillis: Long): PlaybackProgress {
    val position = positionMillis.coerceAtLeast(0L)
    val action = when {
        durationMillis > 0L && position >= durationMillis - 5_000L -> ViewingAction.COMPLETED
        position == 0L && (previous?.positionMillis ?: 0L) > 1_000L -> ViewingAction.RESTART
        previous != null && position + 1_000L < previous.positionMillis -> ViewingAction.REWIND
        else -> ViewingAction.PROGRESS
    }
    return PlaybackProgress(key, position, durationMillis, action = action)
}
