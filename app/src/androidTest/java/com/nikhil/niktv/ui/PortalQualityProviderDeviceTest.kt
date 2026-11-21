package com.nikhil.niktv.ui

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only provider check. No authentication renewal, history or preference writes. */
class PortalQualityProviderDeviceTest {
    @Test fun savedEpisodeOffersBestAndManualQuality(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qualityProviderCheck") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.nikhil.niktv.debug")
        val id = requireNotNull(args.getString("episodeId"))
        val store = ProfileStore(context)
        val cache = store.episodeSeasonCaches.first().first { it.episodes.any { row -> row.id == id } }
        val episode = (cache.iptvEpisodes + cache.episodes).first { it.id == id }
        Log.i("NikTvQualityCheck", "providerTitleContainsBBHin=${episode.title.contains("BBHin")}")
        val profile = store.profiles.first().single { it.portalType == PortalType.STALKER }
        val session = requireNotNull(store.sessionFor(profile))
        val client = StalkerPortalClient(context)
        try {
            val best = client.resolvePlayback(session, episode, CatalogType.SERIES)
            val selected = best.variants.first { it.id == best.selectedId }
            val lower = best.variants.first { it.language == selected.language && it.height in 1 until selected.height }
            assertEquals(best.variants.filter { it.language == selected.language }.maxOf { it.height }, selected.height)
            val manual = client.resolvePlayback(session, episode, CatalogType.SERIES, lower.height, lower.id, best.variants)
            assertEquals(lower.id, manual.selectedId)
            assertEquals(lower.height, manual.preferredHeight)
            assertTrue(best.url.isNotBlank() && manual.url.isNotBlank())
            Log.i("NikTvQualityCheck", "best=${selected.height} manual=${lower.height} variants=${best.variants.size} bothResolved=true")
            val tmdb = TmdbClient()
            if (args.getString("checkTmdb") == "true" && tmdb.configured) {
                val results = tmdb.discoveryJson("/search/tv", mapOf("query" to "Bigg Boss"))["results"] as? JsonArray
                val hindi = results.orEmpty().mapNotNull { it as? JsonObject }.filter {
                    it["original_language"]?.jsonPrimitive?.content == "hi" && it["name"]?.jsonPrimitive?.content.equals("Bigg Boss", true)
                }
                Log.i("NikTvQualityCheck", "tmdbHindiCandidates=${hindi.size}")
                if (hindi.size == 1) {
                    val seriesId = hindi.single().getValue("id").jsonPrimitive.int
                    val seasons = tmdb.episodeSeasons(seriesId)
                    val episodes = if (episode.seasonNumber in seasons) tmdb.seasonEpisodes(seriesId, episode.seasonNumber!!) else emptyList()
                    val match = selectTmdbEpisodeMetadata(episode, episodes)
                    Log.i("NikTvQualityCheck", "tmdbSeries=$seriesId seasons=$seasons seasonEpisodes=${episodes.size} matchedName=${match?.name} matchedDate=${match?.airDate}")
                }
            }
        } finally { client.close() }
    }
}
