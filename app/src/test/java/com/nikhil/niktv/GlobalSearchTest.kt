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
}
