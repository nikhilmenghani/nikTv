package com.nikhil.niktv.data

import kotlinx.serialization.json.*

internal fun portablePublicArtwork(value: JsonElement): Boolean = runCatching {
    val uri = java.net.URI((value as? JsonPrimitive)?.contentOrNull ?: return false)
    uri.scheme == "https" && uri.host == "image.tmdb.org" && uri.port == -1 &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        Regex("^/t/p/(?:original|w[0-9]+|h[0-9]+)/[A-Za-z0-9_.-]+\\.(?:jpg|jpeg|png|webp)$").matches(uri.path.orEmpty())
}.getOrDefault(false)

/** Fill public presentation only; the winning viewing action and playback identity stay intact. */
internal fun retainSyncedArtwork(winner: JsonElement, other: JsonElement?): JsonElement {
    if (winner !is JsonObject || other !is JsonObject) return winner
    val media = winner["id"] is JsonPrimitive && other["id"] is JsonPrimitive
    if (media && listOf("id", "title", "seasonNumber", "episodeNumber", "portalSeasonId", "portalEpisodeId", "externalTmdbId").any { field ->
        val a = (winner[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val b = (other[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        a != null && b != null && a != b
    }) return winner
    val values = winner.mapValues { (field, value) -> retainSyncedArtwork(value, other[field]) }.toMutableMap()
    if (media) {
        val known = (other["externalTmdbId"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        if ((winner["externalTmdbId"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } == null && known != null)
            values["externalTmdbId"] = JsonPrimitive(known)
        if (!portablePublicArtwork(winner["logo"] ?: JsonNull) && portablePublicArtwork(other["logo"] ?: JsonNull))
            values["logo"] = other.getValue("logo")
    }
    return JsonObject(values)
}
