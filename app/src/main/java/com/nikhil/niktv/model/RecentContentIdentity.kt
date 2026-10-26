package com.nikhil.niktv.model

/** Only explicit catalogue/TMDB identities may join sources; similar titles and remakes stay separate. */
fun RecentItem.contentIdentity(): String {
    val root = if (kind == FavoriteKind.SERIES) series ?: media else media
    val prefix = if (kind == FavoriteKind.SERIES) "tmdb-series:" else "tmdb-movie:"
    val tmdb = root.externalTmdbId?.takeIf { it > 0 }
        ?: root.id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()?.takeIf { it > 0 }
        ?: alternateTarget?.takeIf { it.series == (kind == FavoriteKind.SERIES) }?.tmdbId?.takeIf { it > 0 }
        ?: lastPlayed?.externalTmdbId?.takeIf { kind == FavoriteKind.SERIES && it > 0 }
    val identity = if (kind in setOf(FavoriteKind.SERIES, FavoriteKind.MOVIE) && tmdb != null) "tmdb:$tmdb" else "source:${root.id}"
    return "${profileKey.length}:$profileKey|${kind.name}|$identity"
}

/** Presentation only: keep the latest source's resume target and leave stored history untouched. */
fun List<RecentItem>.distinctRecentContent(): List<RecentItem> =
    sortedByDescending { it.playedAtMillis }.distinctBy { it.contentIdentity() }
