package com.nikhil.niktv.data

import kotlinx.serialization.json.*

/** Keep the Torrent merge repair out of unrelated portable preference domains. */
internal fun torrentLibraryArtworkPreference(payload: String): Boolean = runCatching {
    val root = Json.parseToJsonElement(payload).jsonObject
    if ((root["preference"] as? JsonPrimitive)?.contentOrNull != "portable_preferences") return false
    val key = (root["mapKey"] as? JsonPrimitive)?.contentOrNull ?: return false
    if (!key.startsWith("torrent_library_v1|progress:") && !key.startsWith("torrent_library_v1|saved:")) return false
    val value = (root["entry"] as? JsonObject)?.get("value") as? JsonObject ?: return false
    (value["kind"] as? JsonPrimitive)?.contentOrNull == "torrent-library-v1"
}.getOrDefault(false)

internal fun portablePublicArtwork(value: JsonElement): Boolean = runCatching {
    val uri = java.net.URI((value as? JsonPrimitive)?.contentOrNull ?: return false)
    uri.scheme == "https" && uri.host == "image.tmdb.org" && uri.port == -1 &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        Regex("^/t/p/(?:original|w[0-9]+|h[0-9]+)/[A-Za-z0-9_.-]+\\.(?:jpg|jpeg|png|webp)$").matches(uri.path.orEmpty())
}.getOrDefault(false)

/** Fill public presentation only; the winning viewing action and playback identity stay intact. */
internal fun retainSyncedArtwork(winner: JsonElement, other: JsonElement?): JsonElement {
    if (winner !is JsonObject || other !is JsonObject) return winner
    if ((winner["kind"] as? JsonPrimitive)?.contentOrNull == "torrent-library-v1" &&
        (other["kind"] as? JsonPrimitive)?.contentOrNull == "torrent-library-v1")
        return retainTorrentLibraryArtwork(winner, other)
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

/** Only marked Torrent library metadata participates. Viewing progress and
 * source/file identity always come from the winning record. */
private fun retainTorrentLibraryArtwork(winner: JsonObject, other: JsonObject): JsonObject {
    val id = (winner["tmdbId"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return winner
    val series = (winner["series"] as? JsonPrimitive)?.booleanOrNull ?: return winner
    if (id != (other["tmdbId"] as? JsonPrimitive)?.intOrNull ||
        series != (other["series"] as? JsonPrimitive)?.booleanOrNull) return winner
    val values = winner.toMutableMap()
    for (field in listOf("poster", "backdrop")) {
        if (!torrentArtworkPath(winner[field]) && torrentArtworkPath(other[field])) values[field] = other.getValue(field)
    }
    if (!torrentMetadataDate(winner["date"]) && torrentMetadataDate(other["date"])) values["date"] = other.getValue("date")
    fun sameNumber(field: String): Boolean = (winner[field] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        ?.let { it == (other[field] as? JsonPrimitive)?.intOrNull } == true
    if (series && sameNumber("season") && sameNumber("episode") &&
        !torrentArtworkPath(winner["episodeStill"]) && torrentArtworkPath(other["episodeStill"]))
        values["episodeStill"] = other.getValue("episodeStill")
    return JsonObject(values)
}

private fun torrentArtworkPath(value: JsonElement?): Boolean {
    val path = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("/t/p/") } ?: return false
    return portablePublicArtwork(JsonPrimitive("https://image.tmdb.org$path"))
}

private fun torrentMetadataDate(value: JsonElement?): Boolean {
    val date = (value as? JsonPrimitive)?.contentOrNull ?: return false
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return false
    return runCatching { java.time.LocalDate.parse(date).year > 0 }.getOrDefault(false)
}
