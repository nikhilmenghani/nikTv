package com.nikhil.niktv.data

import kotlinx.serialization.json.*

/** Container flags are evidence; the catalogue tab and ambiguous is_movie/is_series flags are not. */
internal fun portalVodHasEpisodes(payload: JsonElement): Boolean {
    val rows = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["data"] as? JsonArray ?: return false
        else -> return false
    }
    return rows.any { node ->
        val row = node as? JsonObject ?: return@any false
        fun flag(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.lowercase() in setOf("1", "true", "yes")
        flag("is_season") || flag("is_episode") ||
            (row["series"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.intOrNull != null }
    }
}

/** A season ID must never be passed to create_link as a movie file. */
class PortalVodSeriesContainerException : Exception("This title contains seasons and episodes.")
