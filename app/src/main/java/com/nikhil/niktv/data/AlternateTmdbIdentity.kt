package com.nikhil.niktv.data

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public metadata only; never reads or changes IPTV catalogs or provider mappings. */
internal class AlternateTmdbIdentity(
    private val fetch: suspend (String, Map<String, String>) -> JsonObject,
    private val configured: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Cached(val id: Int, val year: Int?, val expires: Long)
    private val cache = object : LinkedHashMap<AlternateSourceTarget, Cached>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AlternateSourceTarget, Cached>?) = size > 64
    }

    suspend fun resolve(target: AlternateSourceTarget): AlternateSourceTarget {
        if (target.tmdbId?.let { it > 0 } == true || target.title.isBlank() || !configured()) return target
        val key = target.copy(season = null, episode = null)
        synchronized(cache) { cache[key]?.takeIf { now() < it.expires } }?.let {
            return target.copy(tmdbId = it.id, year = target.year ?: it.year)
        }
        val query = buildMap {
            // Numeric movie names such as 1917 and 2012 are literal names, not year filters.
            put("query", target.title)
            put("include_adult", "false")
            put("page", "1")
            if (!target.series) target.year?.let { put("year", it.toString()) }
        }
        val root = fetch(if (target.series) "/search/tv" else "/search/movie", query)
        val fields = if (target.series) listOf("name", "original_name") else listOf("title", "original_title")
        val wanted = identityTitle(target.title)
        val matches = root["results"]?.jsonArray.orEmpty().take(20).mapNotNull { value ->
            val candidate = value.jsonObject
            val id = candidate["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            if (candidate["adult"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val names = fields.mapNotNull { candidate[it]?.jsonPrimitive?.contentOrNull }
            if (names.none { identityTitle(it) == wanted }) return@mapNotNull null
            val year = candidate[if (target.series) "first_air_date" else "release_date"]
                ?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
            if (target.year != null && year != target.year) return@mapNotNull null
            id to year
        }.distinctBy { it.first }
        // Do not silently choose a popular remake or a similarly named series.
        val match = matches.singleOrNull() ?: return target
        synchronized(cache) { cache[key] = Cached(match.first, match.second, now() + 6 * 60 * 60_000L) }
        return target.copy(tmdbId = match.first, year = target.year ?: match.second)
    }
}

private fun identityTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
    .replace(Regex("^(?:the|an|a)\\s+"), "").filter(Char::isLetterOrDigit)
