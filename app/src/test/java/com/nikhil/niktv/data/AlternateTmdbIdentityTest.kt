package com.nikhil.niktv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AlternateTmdbIdentityTest {
    private val numeric = AlternateSourceTarget("1917", false)
    private fun response(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun numericMovieTitlesRemainLiteralQueriesAndDoNotTouchProviderData() = runBlocking {
        var requests = 0
        val lookup = AlternateTmdbIdentity(fetch = { path, query ->
            requests++
            assertEquals("/search/movie", path)
            assertEquals("1917", query["query"])
            assertEquals("false", query["include_adult"])
            response("""{"results":[{"id":530915,"title":"1917","original_title":"1917","release_date":"2019-12-25"}]}""")
        })
        assertEquals(530915, lookup.resolve(numeric).tmdbId)
        assertEquals(1, requests)
        assertNull(numeric.tmdbId)
    }

    @Test fun knownIdentityAndUnconfiguredMetadataDoNotSearch() = runBlocking {
        val lookup = AlternateTmdbIdentity(fetch = { _, _ -> error("Unexpected metadata lookup") }, configured = { false })
        assertEquals(numeric, lookup.resolve(numeric))
        val known = numeric.copy(tmdbId = 530915)
        assertEquals(known, lookup.resolve(known))
    }

    @Test fun remakesRequireAnUnambiguousExactNameAndYear() = runBlocking {
        val lookup = AlternateTmdbIdentity(fetch = { _, _ -> response("""{"results":[
            {"id":1,"title":"The Thing","release_date":"1982-06-25"},
            {"id":2,"title":"The Thing","release_date":"2011-10-12"},
            {"id":3,"title":"Something Else","release_date":"1982-06-25"}]}""") })
        val target = numeric.copy(title = "The Thing")
        assertNull(lookup.resolve(target).tmdbId)
        assertEquals(1, lookup.resolve(target.copy(year = 1982)).tmdbId)
        assertNull(lookup.resolve(target.copy(year = 2000)).tmdbId)
    }

    @Test fun seriesIdentityPreservesExactEpisodeAndReusesPublicMetadataForNextEpisode() = runBlocking {
        var requests = 0
        val lookup = AlternateTmdbIdentity(fetch = { path, query ->
            requests++
            assertEquals("/search/tv", path)
            assertEquals("Friends", query["query"])
            response("""{"results":[{"id":1668,"name":"Friends","original_name":"Friends","first_air_date":"1994-09-22"}]}""")
        })
        val target = AlternateSourceTarget("Friends", true, season = 3, episode = 25)
        val found = lookup.resolve(target)
        assertEquals(1668, found.tmdbId)
        assertEquals(3, found.season)
        assertEquals(25, found.episode)
        assertEquals(24, lookup.resolve(target.copy(episode = 24)).episode)
        assertEquals(1, requests)
    }

    @Test fun expiredIdentitiesRefreshAndAdultOrSimilarNamesAreExcluded() = runBlocking {
        var clock = 1L
        var requests = 0
        val lookup = AlternateTmdbIdentity(fetch = { _, _ ->
            requests++
            response("""{"results":[{"id":1,"title":"1917","release_date":"2019-01-01"},
                {"id":2,"title":"1917","adult":true},{"id":3,"title":"1917 Again"}]}""")
        }, now = { clock })
        assertEquals(1, lookup.resolve(numeric).tmdbId)
        assertEquals(1, lookup.resolve(numeric).tmdbId)
        assertEquals(1, requests)
        clock += 6 * 60 * 60_000L
        assertEquals(1, lookup.resolve(numeric).tmdbId)
        assertEquals(2, requests)
    }

    @Test fun cancellationAndMissingResultsAreNotCached() = runBlocking {
        var requests = 0
        val lookup = AlternateTmdbIdentity(fetch = { _, _ ->
            if (++requests == 1) throw CancellationException("Fixture cancellation")
            response("""{"results":[]}""")
        })
        try { lookup.resolve(numeric); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertNull(lookup.resolve(numeric).tmdbId)
        assertNull(lookup.resolve(numeric).tmdbId)
        assertEquals(3, requests)
    }
}
