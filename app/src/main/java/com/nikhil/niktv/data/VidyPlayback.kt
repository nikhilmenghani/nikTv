package com.nikhil.niktv.data

/** An embed is a separate playback route, never a media URL for a native decoder. */
internal fun vidyFallbackStream(target: AlternateSourceTarget): AlternateStream? {
    val id = target.tmdbId?.takeIf { it > 0 } ?: return null
    val path = if (target.series) {
        val season = target.season?.takeIf { it >= 0 } ?: return null
        val episode = target.episode?.takeIf { it > 0 } ?: return null
        "tv/$id/$season/$episode"
    } else "movie/$id"
    return AlternateStream("vidy-embedded", "Vidy · Embedded player (fallback)", "https://vidy.st/$path",
        format = "embedded", route = AlternatePlaybackRoute.VIDY_EMBEDDED)
}

internal fun AlternateStream.isVidyEmbedded() = route == AlternatePlaybackRoute.VIDY_EMBEDDED
