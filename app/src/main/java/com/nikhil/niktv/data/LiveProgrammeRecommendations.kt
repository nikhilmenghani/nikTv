package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import java.util.Locale

/** Programme identity must never inherit the channel's playback URL or TMDB ID. */
internal fun liveProgrammeRecommendationItem(channel: MediaItem, now: Long): MediaItem? {
    if (channel.isAdult) return null
    val programme = channel.currentLiveProgramme(now) ?: return null
    if (isMissingLiveProgrammeTitle(programme.title)) return null
    val episodeSuffix = Regex("""(?i)\s*[-:|,]?\s*\b(?:S\d{1,3}\s*E\d{1,4}|Season\s+\d+\s*[,.: -]*Episode\s+\d+)\b.*$""")
    val title = programme.title.replace(episodeSuffix, "").trim()
    if (title.isBlank()) return null
    return MediaItem("live-programme:${title.lowercase(Locale.ROOT)}", title, null, null,
        searchResultType = if (episodeSuffix.containsMatchIn(programme.title)) SearchContentType.SERIES else null)
}

/** Without a type from EPG, accept only a match in exactly one TMDB catalog. */
internal suspend fun resolveLiveProgrammeRecommendation(item: MediaItem,
    resolve: suspend (CatalogType, MediaItem) -> MediaIdentity?): MediaIdentity? {
    if (item.searchResultType == SearchContentType.SERIES) return resolve(CatalogType.SERIES, item)
    return listOfNotNull(resolve(CatalogType.MOVIES, item), resolve(CatalogType.SERIES, item)).singleOrNull()
}
