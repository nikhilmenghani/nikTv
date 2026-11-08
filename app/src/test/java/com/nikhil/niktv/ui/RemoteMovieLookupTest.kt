package com.nikhil.niktv.ui

import com.nikhil.niktv.data.RemoteMovie
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteMovieLookupTest {
    private val title = "The Odyssey (English) - ENGLISH | LATEST MOVIES 4K"
    private val movie = RemoteMovie("a".repeat(64), "selected-4k", title, "english")
    private val wanted = MediaItem(movie.id, title, null, "receiver-command")

    @Test fun decoratedLabelUsesPlainTitleInKnownCategoryAndPreservesSelectedVersion() = runBlocking {
        val calls = mutableListOf<Triple<String, String, Int>>()
        val result = lookupRemoteMovie(movie, emptyList(), { error("Known category should suffice") }) { query, category, page ->
            calls += Triple(query, category, page)
            when {
                query != "The Odyssey" -> PortalSearchPage(emptyList(), page, false)
                page == 1 -> PortalSearchPage(listOf(wanted.copy(id = "wrong-language")), page, true)
                else -> PortalSearchPage(listOf(wanted), page, false)
            }
        }
        assertEquals(wanted, result)
        assertEquals(Triple("The Odyssey", "english", 1), calls.first())
        assertTrue(Triple("The Odyssey", "english", 2) in calls)
        assertTrue(calls.all { it.second == "english" })
    }

    @Test fun olderSenderWithoutCategoryStillChecksWildcardAndEveryConcreteCategory() = runBlocking {
        val calls = mutableListOf<String>()
        val result = lookupRemoteMovie(movie.copy(categoryId = null), emptyList(), { listOf("*", "old", "english") }) { query, category, page ->
            calls += category
            PortalSearchPage(if (query == "The Odyssey" && category == "english") listOf(wanted) else emptyList(), page, false)
        }
        assertEquals(wanted, result)
        assertTrue("*" in calls && "old" in calls && "english" in calls)
    }

    @Test fun localExactIdentityDoesNotNeedTitleOrNetworkMatch() = runBlocking {
        val renamed = wanted.copy(title = "Provider renamed this entry")
        assertEquals(renamed, lookupRemoteMovie(movie, listOf(renamed), { error("No lookup") }) { _, _, _ -> error("No lookup") })
    }

    @Test fun anotherVersionIsNeverAcceptedAsTheRequestedMovie() = runBlocking {
        assertNull(lookupRemoteMovie(movie, emptyList(), { emptyList() }) { _, _, page ->
            PortalSearchPage(listOf(wanted.copy(id = "other-4k")), page, false)
        })
    }

    @Test fun strippingLabelsRetainsCompleteTitleAsFallback() {
        assertEquals(listOf("The Odyssey", "The Odyssey (English)", title), remoteMovieQueries(title))
        assertTrue(remoteMovieQueries("Movie - Part Two").contains("Movie - Part Two"))
    }
}
