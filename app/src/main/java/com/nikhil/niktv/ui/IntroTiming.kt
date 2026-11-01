package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import java.security.MessageDigest

internal data class IntroRange(val startMillis: Long, val endMillis: Long) {
    fun validFor(durationMillis: Long): Boolean = startMillis >= 0L && endMillis > startMillis &&
        durationMillis > 1_000L && endMillis <= durationMillis - 1_000L
    fun contains(positionMillis: Long, durationMillis: Long): Boolean =
        validFor(durationMillis) && positionMillis >= startMillis && positionMillis < endMillis
    /** Native clocks can round a completed seek just below the target; avoid a last-half-second flash. */
    fun canOfferSkip(positionMillis: Long, durationMillis: Long): Boolean =
        contains(positionMillis, durationMillis) && endMillis - positionMillis > 500L
    /** The displayed countdown and visibility share a playback-clock deadline, never a wall-clock timer. */
    fun skipSecondsRemaining(positionMillis: Long, durationMillis: Long): Long? =
        if (canOfferSkip(positionMillis, durationMillis)) (endMillis - positionMillis + 999L) / 1_000L else null
}

internal enum class IntroScope { EPISODE, SEASON }

internal data class IntroTarget(val episodeKey: String, val seasonKey: String?, val episodeLabel: String,
    val seasonLabel: String?)

/** No URLs/credentials/titles in preference keys; length framing prevents delimiter collisions. */
private fun introKey(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    val hash = digest.digest()
    val digits = "0123456789abcdef"
    return CharArray(hash.size * 2) { index ->
        val byte = hash[index / 2].toInt() and 0xff
        digits[if (index % 2 == 0) byte ushr 4 else byte and 15]
    }.concatToString()
}

internal fun introTarget(media: PlayingMedia): IntroTarget? {
    if (media.profileKey.isBlank() || media.catalogType == CatalogType.LIVE_TV) return null
    val series = media.series
    if (series == null) {
        if (media.catalogType != CatalogType.MOVIES || media.media.id.isBlank()) return null
        val identity = media.media.externalTmdbId?.let { "tmdb:$it" } ?: "provider:${media.media.id}"
        return IntroTarget("episode:" + introKey(media.profileKey, "movie", identity), null, "This movie", null)
    }
    if (series.id.isBlank()) return null
    val identity = series.externalTmdbId?.let { "tmdb:$it" } ?: "provider:${series.id}"
    val season = media.media.seasonNumber?.takeIf { it >= 0 }
    val episode = media.media.episodeNumber?.takeIf { it > 0 }
    val episodeIdentity = if (season != null && episode != null) "s${season}e$episode" else media.media.id
    if (episodeIdentity.isBlank()) return null
    return IntroTarget("episode:" + introKey(media.profileKey, "series", identity, episodeIdentity),
        season?.let { "season:" + introKey(media.profileKey, "series", identity, it.toString()) },
        "This episode", season?.let { "Season $it" })
}

internal data class IntroTimings(val episode: IntroRange? = null, val season: IntroRange? = null) {
    // An invalid episode override must not silently fall back to a potentially wrong season timing.
    val effective: IntroRange? get() = episode ?: season
    val scope: IntroScope get() = if (episode != null) IntroScope.EPISODE else IntroScope.SEASON
}

internal fun parseIntroTime(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.last() > 59 || (parts.size == 3 && values[1] > 59)) return null
    return if (parts.size == 2) (values[0] * 60 + values[1]) * 1_000
        else (values[0] * 3600 + values[1] * 60 + values[2]) * 1_000
}

internal fun introTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
