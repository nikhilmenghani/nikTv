package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.RecentItem

/** Numbering belongs to the provider identity, independently of an enriched episode title. */
internal fun MediaItem.episodeIdentityLabel(): String? {
    val episode = episodeNumber?.takeIf { it > 0 } ?: return null
    val season = seasonNumber?.takeIf { it >= 0 }
    return if (season != null) "S$season:E$episode" else "Episode $episode"
}

internal fun PlayingMedia.playerSeriesSubtitle(): String? = listOfNotNull(
    media.episodeIdentityLabel().takeIf { catalogType == CatalogType.SERIES },
    series?.title?.takeIf(String::isNotBlank)
).joinToString(" · ").takeIf(String::isNotBlank)

private val episodeTitlePrefix = Regex(
    "^(?:S(\\d+)\\s*[:._ -]?\\s*E(?:P(?:ISODE)?)?\\s*(\\d+)|(?:EPISODE|EP|E)[ ._:#-]*(\\d+))\\b[ .:|–—-]*",
    RegexOption.IGNORE_CASE
)

internal fun RecentItem.continueWatchingSubtitle(): String {
    val episode = lastPlayed ?: media.takeIf { kind == FavoriteKind.EPISODE }
        ?: return categoryTitle.orEmpty()
    if (kind !in setOf(FavoriteKind.SERIES, FavoriteKind.EPISODE)) return episode.title
    val label = episode.episodeIdentityLabel() ?: return episode.title
    val title = episode.title.trim()
    val prefix = episodeTitlePrefix.find(title)
    val existingNumber = prefix?.groupValues?.let { (it[2].ifEmpty { it[3] }).toIntOrNull() }
    val existingSeason = prefix?.groupValues?.get(1)?.toIntOrNull()
    val detail = if (prefix != null && existingNumber == episode.episodeNumber &&
        (existingSeason == null || existingSeason == episode.seasonNumber)) title.drop(prefix.value.length) else title
    return if (detail.isBlank()) label else "$label · $detail"
}
