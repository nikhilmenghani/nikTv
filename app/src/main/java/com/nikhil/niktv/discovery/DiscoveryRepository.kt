package com.nikhil.niktv.discovery

import android.content.Context
import com.nikhil.niktv.data.TmdbClient
import com.nikhil.niktv.model.TmdbHomeSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** No portal client is available here: speculative discovery cannot search/open provider streams. */
internal class DiscoveryRepository(context: Context, private val tmdb: TmdbClient = TmdbClient()) : DiscoveryDataSource {
    private val cache = DiscoveryCache(java.io.File(context.applicationContext.cacheDir, "discovery_preview"))
    companion object { private val slots = Semaphore(2) }
    private val json = Json { ignoreUnknownKeys = true }
    val configured get() = tmdb.configured
    private suspend fun <T> cached(key: String, ttl: Long, serializer: kotlinx.serialization.KSerializer<T>,
        stale: (T) -> Unit = {}, load: suspend () -> T): T {
        return cache.load(key, ttl, serializer, stale) { slots.withPermit { load() } }
    }
    override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> =
        cached("feed-v3:${section.name}", DiscoveryCache.FEED_TTL, ListSerializer(DiscoveryCard.serializer()), stale) {
            val (path, query) = discoveryFeedRequest(section)
            val first = cards(tmdb.discoveryJson(path, query + ("page" to "1")), section.series)
            if (first.size >= 10) first
            else (first + cards(tmdb.discoveryJson(path, query + ("page" to "2")), section.series)).distinctBy { it.key }.take(30)
        }.filter { it.released() }

    override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails =
        cached("detail-v4:${card.key}", DiscoveryCache.DETAIL_TTL, DiscoveryDetails.serializer(), stale) {
            val root = tmdb.discoveryJson("/${if (card.series) "tv" else "movie"}/${card.id}",
                mapOf("append_to_response" to if (card.series) "credits,images,content_ratings" else "credits,images,release_dates", "include_image_language" to "en,null"))
            val genres = root["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.string("name") }
            val logo = root["images"]?.jsonObject?.get("logos")?.jsonArray.orEmpty().firstOrNull()?.jsonObject?.string("file_path")
            val certification = if (card.series) root["content_ratings"]?.jsonObject?.get("results")?.jsonArray.orEmpty()
                .firstOrNull { it.jsonObject.string("iso_3166_1") == "US" }?.jsonObject?.string("rating")
            else root["release_dates"]?.jsonObject?.get("results")?.jsonArray.orEmpty()
                .firstOrNull { it.jsonObject.string("iso_3166_1") == "US" }?.jsonObject?.get("release_dates")?.jsonArray.orEmpty()
                .firstNotNullOfOrNull { it.jsonObject.string("certification")?.takeIf(String::isNotBlank) }
            DiscoveryDetails(card.copy(title = root.string(if (card.series) "name" else "title") ?: card.title,
                poster = root.string("poster_path")?.let { image(it, "w342") } ?: card.poster,
                backdrop = root.string("backdrop_path")?.let { image(it, "w1280") } ?: card.backdrop,
                date = root.string(if (card.series) "first_air_date" else "release_date") ?: card.date,
                rating = root["vote_average"]?.jsonPrimitive?.doubleOrNull ?: card.rating,
                overview = root.string("overview") ?: card.overview, genres = genres,
                runtime = root["runtime"]?.jsonPrimitive?.intOrNull ?: root["episode_run_time"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.intOrNull,
                certification = certification?.takeIf(String::isNotBlank), titleLogo = logo?.let { image(it, "w500") },
                originalLanguage = root.string("original_language") ?: card.originalLanguage),
                cast = root["credits"]?.jsonObject?.get("cast")?.jsonArray.orEmpty().take(20).mapNotNull { c ->
                    c.jsonObject.string("name")?.let { DiscoveryPerson(it, c.jsonObject.string("character").orEmpty(), c.jsonObject.string("profile_path")?.let { p -> image(p, "w185") }, c.jsonObject["id"]?.jsonPrimitive?.intOrNull) }
                }, seasons = root["seasons"]?.jsonArray.orEmpty().mapNotNull { s ->
                    s.jsonObject["season_number"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } }.sorted(),
                seasonEpisodeCounts = root["seasons"]?.jsonArray.orEmpty().mapNotNull { s ->
                    val season = s.jsonObject["season_number"]?.jsonPrimitive?.intOrNull
                    val count = s.jsonObject["episode_count"]?.jsonPrimitive?.intOrNull
                    if (season != null && season >= 0 && count != null && count >= 0) season to count else null
                }.toMap(),
                collection = root["belongs_to_collection"]?.takeIf { it is JsonObject }?.jsonObject?.let { c ->
                    c["id"]?.jsonPrimitive?.intOrNull?.let { DiscoveryCollection(it, c.string("name").orEmpty(), c.string("backdrop_path")?.let { p -> image(p, "w780") }) }
                })
        }
    override suspend fun related(card: DiscoveryCard): List<DiscoveryCard> = cached("related-v2:${card.key}", DiscoveryCache.DETAIL_TTL,
        ListSerializer(DiscoveryCard.serializer())) {
        cards(tmdb.discoveryJson("/${if (card.series) "tv" else "movie"}/${card.id}/recommendations"), card.series)
    }.filter { it.released() }
    override suspend fun collection(id: Int): List<DiscoveryCard> = cached("collection-v1:$id", DiscoveryCache.DETAIL_TTL,
        ListSerializer(DiscoveryCard.serializer())) {
        val root = tmdb.discoveryJson("/collection/$id")
        cards(root, false, "parts")
    }.filter { it.released() }
    override suspend fun reviews(card: DiscoveryCard): List<DiscoveryReview> = cached("reviews-v1:${card.key}", DiscoveryCache.DETAIL_TTL,
        ListSerializer(DiscoveryReview.serializer())) {
        tmdb.discoveryJson("/movie/${card.id}/reviews")["results"]?.jsonArray.orEmpty().take(10).mapNotNull {
            val obj = it.jsonObject
            obj.string("content")?.let { content -> DiscoveryReview(obj.string("author").orEmpty(), content.take(12000)) }
        }
    }
    override suspend fun episodes(card: DiscoveryCard, season: Int): List<DiscoveryEpisode> = cached("episodes-v1:${card.key}:$season", DiscoveryCache.DETAIL_TTL,
        ListSerializer(DiscoveryEpisode.serializer())) {
        tmdb.discoveryJson("/tv/${card.id}/season/$season")["episodes"]?.jsonArray.orEmpty().mapNotNull { item ->
            val e = item.jsonObject
            e["episode_number"]?.jsonPrimitive?.intOrNull?.let { number -> DiscoveryEpisode(season, number,
                e.string("name") ?: "Episode $number", e.string("overview"), e.string("air_date"), e.string("still_path")?.let { image(it, "w780") }) }
        }
    }.filter { it.released() }
    private fun cards(root: JsonObject, series: Boolean, field: String = "results") = root[field]?.jsonArray.orEmpty().mapNotNull {
        val o = it.jsonObject
        o["id"]?.jsonPrimitive?.intOrNull?.let { id -> DiscoveryCard(id, series,
            o.string(if (series) "name" else "title").orEmpty(), o.string("overview"),
            o.string("poster_path")?.let { p -> image(p, "w342") }, o.string("backdrop_path")?.let { p -> image(p, "w1280") },
            o.string(if (series) "first_air_date" else "release_date"), o["vote_average"]?.jsonPrimitive?.doubleOrNull,
            genres = with(tmdb) { o.discoveryGenreNames() }, adult = o["adult"]?.jsonPrimitive?.booleanOrNull == true,
            originalLanguage = o.string("original_language")) }
    }.filter { it.title.isNotBlank() && it.released() }.take(30)
    override suspend fun actorCredits(person: DiscoveryPerson, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> {
        val id = person.id?.takeIf { it > 0 } ?: return emptyList()
        return cached("actor-credits-v1:$id", DiscoveryCache.DETAIL_TTL, ListSerializer(DiscoveryCard.serializer()), stale) {
            discoveryActorCredits(tmdb.discoveryJson("/person/$id/combined_credits"))
        }.filter { it.released() }
    }
    override suspend fun providerDetails(item: com.nikhil.niktv.model.MediaItem, type: com.nikhil.niktv.model.CatalogType,
        stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
        val fallback = providerDetailFallback(item, type)
        val card = cachedProviderMetadata(cache, item, type) {
            if ((item.externalTmdbId ?: 0) > 0) providerTmdbSeed(item, type)
            else slots.withPermit {
                val lookup = item.copy(externalTmdbId = null)
                if (type == com.nikhil.niktv.model.CatalogType.SERIES) tmdb.confidentlyMatchSeries(lookup)?.discovery()
                else tmdb.confidentlyMatchMovie(lookup)?.discovery()
            }
        } ?: return DiscoveryDetails(fallback)
        return try { details(card, stale) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { DiscoveryDetails(if (card.title.isBlank()) fallback.copy(id = card.id) else card) }
    }
    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun image(path: String, size: String) = "https://image.tmdb.org/t/p/$size$path"
}
