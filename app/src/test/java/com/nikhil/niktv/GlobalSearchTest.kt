package com.nikhil.niktv

import com.nikhil.niktv.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GlobalSearchTest {
    private fun media(id: String, title: String, type: SearchContentType) =
        MediaItem(id, title, null, null, searchResultType = type)

    @Test fun identicalProviderIdsAcrossTypesAreNotDuplicates() {
        val movie = media("42", "Friends", SearchContentType.MOVIES)
        val series = media("42", "Friends", SearchContentType.SERIES)
        val channel = media("42", "Friends 24x7", SearchContentType.LIVE_TV)
        val results = listOf(movie, series, channel, movie).rankSearchResults("Friends", SearchContentType.ALL)
        assertEquals(3, results.size)
        assertEquals(3, results.map { it.searchIdentity(SearchContentType.ALL) }.toSet().size)
    }

    @Test fun exactTitleComesBeforeLongerAndPartialMatches() {
        val results = listOf(
            media("1", "Friends reunion", SearchContentType.SERIES),
            media("2", "Best friends", SearchContentType.MOVIES),
            media("3", "FRIENDS", SearchContentType.SERIES)
        ).rankSearchResults("friends", SearchContentType.ALL)
        assertEquals("3", results.first().id)
    }

    @Test fun resultOrderingIsStableAcrossArrivalOrder() {
        val items = globalSearchTypes.map { media("1", "Friends", it) }
        assertEquals(items.rankSearchResults("friends", SearchContentType.ALL),
            items.reversed().rankSearchResults("friends", SearchContentType.ALL))
    }

    @Test fun searchRoutingIsNotPersistedInMedia() {
        val item = media("42", "Friends", SearchContentType.SERIES)
        val restored = Json.decodeFromString<MediaItem>(Json.encodeToString(item))
        assertNull(restored.searchResultType)
        assertEquals(item.id, restored.id)
    }

    @Test fun globalRecentsRetainTheirScope() {
        val recent = RecentSearch("Friends", SearchContentType.ALL)
        assertEquals(SearchContentType.ALL, Json.decodeFromString<RecentSearch>(Json.encodeToString(recent)).type)
    }

    @Test fun cachedRowsCanMatchADifferentQueryWithoutCrossingProfilesOrTypes() {
        val cache = SearchResultCache("wio", SearchContentType.MOVIES, "dil toh", "*", 3, true,
            listOf(media("1", "Dil Toh Baccha Hai Ji", SearchContentType.MOVIES)))
        val caches = listOf(cache, cache.copy(profileKey = "other", items = listOf(media("2", "Dil", SearchContentType.MOVIES))),
            cache.copy(type = SearchContentType.SERIES, items = listOf(media("3", "Dil", SearchContentType.SERIES))))
        val matches = caches.cachedSearchItems("wio", SearchContentType.MOVIES, "*")
            .filter { it.title.matchesTitleKeywords("baccha") }
        assertEquals(listOf("1"), matches.map { it.id })
    }

    @Test fun cachedRowsRespectCategoryEvenWhenOriginalQueryWasUnscoped() {
        val unknown = media("1", "Film", SearchContentType.MOVIES)
        val cache = SearchResultCache("wio", SearchContentType.MOVIES, "film", "*", 1, false,
            listOf(unknown, unknown.copy(id = "2", portalCategoryId = "hindi"),
                unknown.copy(id = "3", portalCategoryId = "english")))
        assertEquals(listOf("2"), listOf(cache).cachedSearchItems("wio", SearchContentType.MOVIES, "hindi").map { it.id })
        assertEquals(listOf("1", "2"), listOf(cache.copy(categoryId = "hindi"))
            .cachedSearchItems("wio", SearchContentType.MOVIES, "hindi").map { it.id })
    }

    @Test fun newestCachedCopyWinsAcrossPreviousQueries() {
        val old = SearchResultCache("wio", SearchContentType.MOVIES, "film", "*", 1, false,
            listOf(media("1", "Film CAM", SearchContentType.MOVIES)), cachedAtMillis = 1)
        val fresh = old.copy(query = "hd", cachedAtMillis = 2,
            items = listOf(media("1", "Film HD", SearchContentType.MOVIES)))
        assertEquals("Film HD", listOf(old, fresh).cachedSearchItems("wio", SearchContentType.MOVIES, "*").single().title)
    }
}
