package com.nikhil.niktv.discovery

import kotlinx.serialization.json.*

/** Cast credits only: names are never used as an ambiguous person lookup. */
internal fun discoveryActorCredits(root: JsonObject, today: String = currentDiscoveryDate()): List<DiscoveryCard> =
    root["cast"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        .sortedByDescending { it["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
        .mapNotNull { credit ->
            fun field(key: String) = credit[key]?.jsonPrimitive?.contentOrNull
            val series = when (field("media_type")) { "tv" -> true; "movie" -> false; else -> return@mapNotNull null }
            val id = credit["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = field(if (series) "name" else "title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DiscoveryCard(id, series, title, field("overview"),
                field("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                field("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
                field(if (series) "first_air_date" else "release_date"),
                credit["vote_average"]?.jsonPrimitive?.doubleOrNull,
                adult = credit["adult"]?.jsonPrimitive?.booleanOrNull == true)
        }.filter { it.released(today) }.distinctBy { it.key }.take(60)

internal fun adjacentDiscoveryRow(current: Int, delta: Int, rows: List<Int>): Int? =
    if (delta > 0) rows.firstOrNull { it > current } else rows.lastOrNull { it < current }
