package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.rankTmdbMovieMatches
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalSearchPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TmdbMovieProviderLookupTest {
    private val movie = TmdbMovie(42, "Heart of the Beast", "Heart of the Beast", null, null, null, "2026-01-01", null)
    private val hindi = MediaItem("hindi", "Heart of the Beast (Hindi) (4K) - HINDI | ENGLISH DUBB MOVIES", null, null)
    private val unrelated = MediaItem("other", "Different movie", null, null)

    @Test fun wildcardMissFindsMovieInConcreteCategoryAndPreservesRemainingScopes() = runBlocking {
        val requests = mutableListOf<MovieMatchRequest>()
        val result = lookupProviderMovie(movie, emptyList(), { listOf("*", "english", "hindi", "tamil") }) { request ->
            requests += request
            PortalSearchPage(if (request.categoryId == "hindi") listOf(hindi) else emptyList(), request.page, false)
        }
        assertEquals(listOf(hindi), result.matches)
        assertEquals(listOf("*", "english", "hindi"), requests.map { it.categoryId })
        assertEquals(listOf(MovieMatchRequest(movie.title, categoryId = "tamil")), result.pendingRequests)
    }

    @Test fun noAcceptedMatchOnFirstPageStillSearchesLaterPages() = runBlocking {
        val requests = mutableListOf<MovieMatchRequest>()
        val result = lookupProviderMovie(movie, emptyList(), { error("Wildcard has a valid match") }) { request ->
            requests += request
            PortalSearchPage(if (request.page == 1) listOf(unrelated) else listOf(hindi), request.page, request.page == 1)
        }
        assertEquals(listOf(hindi), result.matches)
        assertEquals(listOf(1, 2), requests.map { it.page })
    }

    @Test fun filteredPageContainingOtherMediaTypesDoesNotHideNextMoviePage() = runBlocking {
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) { request ->
            if (request.page == 1) PortalSearchPage(emptyList(), 1, true, listOf("series-row"))
            else PortalSearchPage(listOf(hindi), 2, false)
        }
        assertEquals(listOf(hindi), result.matches)
    }

    @Test fun triesOriginalTitleBeforeDeclaringMiss() = runBlocking {
        val localized = movie.copy(title = "Localized movie title")
        val queries = mutableListOf<String>()
        val result = lookupProviderMovie(localized, emptyList(), { emptyList() }) { request ->
            queries += request.query
            PortalSearchPage(if (request.query == movie.originalTitle) listOf(hindi) else emptyList(), 1, false)
        }
        assertEquals(listOf(hindi), result.matches)
        assertEquals(listOf(localized.title, localized.originalTitle), queries)
    }

    @Test fun retainsSavedSearchCandidatesWhenWildcardIsEmpty() = runBlocking {
        val result = lookupProviderMovie(movie, listOf(hindi), { error("Saved match is usable") }) {
            PortalSearchPage(emptyList(), 1, false)
        }
        assertEquals(listOf(hindi), result.matches)
    }

    @Test fun trueMissExhaustsBothTitlesAndAllCategoryPages() = runBlocking {
        val localized = movie.copy(originalTitle = "Original title")
        val requests = mutableListOf<MovieMatchRequest>()
        val result = lookupProviderMovie(localized, emptyList(), { listOf("a", "b") }) { request ->
            requests += request
            PortalSearchPage(listOf(unrelated.copy(id = "${request.categoryId}-${request.page}")), request.page, request.page < 3)
        }
        assertTrue(result.matches.isEmpty())
        assertTrue(result.pendingRequests.isEmpty())
        assertEquals(18, requests.size) // Two titles, three scopes, three pages each.
    }

    @Test fun repeatedPagesCannotKeepAnUnavailableMovieLookupRunningForever() = runBlocking {
        var calls = 0
        val result = lookupProviderMovie(movie, emptyList(), { listOf("a") }) {
            calls++
            check(calls < 6)
            PortalSearchPage(listOf(unrelated), it.page, true)
        }
        assertTrue(result.matches.isEmpty())
        assertEquals(4, calls)
    }

    @Test fun providerFailureIsNotReportedAsAnEmptyMatch() = runBlocking {
        val failure = IllegalStateException("Provider unavailable")
        try {
            lookupProviderMovie(movie, emptyList(), { listOf("a") }) { throw failure }
            fail("Expected provider failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    @Test fun cancellationStopsBeforeCategoryFallback() = runBlocking {
        try {
            lookupProviderMovie(movie, emptyList(), { error("Must not continue") }) { throw CancellationException("closed") }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertEquals("closed", actual.message) }
    }

    @Test fun decoratedTitleRejectedByTmdbIsOfferedForExplicitReview() = runBlocking {
        val decorated = hindi.copy(title = "EN | Heart of the Beast (2025) (Hindi) 4K")
        assertTrue(rankTmdbMovieMatches(movie, listOf(decorated)).isEmpty())
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) {
            PortalSearchPage(listOf(decorated), 1, false)
        }
        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(decorated), result.selectionCandidates)
        assertTrue(result.requiresConfirmation)
    }

    @Test fun titleAndYearMismatchCanBeReviewedWithoutWeakeningGlobalMatching() = runBlocking {
        val wrongYear = hindi.copy(title = "Heart of the Beast (2025) Hindi")
        assertTrue(rankTmdbMovieMatches(movie, listOf(wrongYear)).isEmpty())
        val result = lookupProviderMovie(movie, listOf(wrongYear), { emptyList() }) {
            PortalSearchPage(emptyList(), 1, false)
        }
        assertEquals(listOf(wrongYear), result.reviewCandidates)
        assertTrue(result.requiresConfirmation)
    }

    @Test fun contradictoryVodFlagsCannotBecomeAnAutomaticMatch() = runBlocking {
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) {
            PortalSearchPage(emptyList(), 1, false, listOf(hindi.id), movieReviewCandidates = listOf(hindi))
        }
        assertTrue(result.matches.isEmpty())
        assertTrue(result.rawCandidates.isEmpty())
        assertEquals(listOf(hindi), result.reviewCandidates)
        assertTrue(result.requiresConfirmation)
    }

    @Test fun rejectedResultDoesNotStopSearchBeforeAConfidentMatchOnTheNextPage() = runBlocking {
        val decorated = hindi.copy(id = "review", title = "EN | Heart of the Beast Hindi")
        val pages = mutableListOf<Int>()
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) {
            pages += it.page
            PortalSearchPage(if (it.page == 1) listOf(decorated) else listOf(hindi), it.page, it.page == 1)
        }
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf(hindi), result.matches)
        assertFalse(result.requiresConfirmation)
    }

    @Test fun unrelatedRowsAndDifferentAuthoritativeTmdbIdsAreNotReviewCandidates() = runBlocking {
        val differentIdentity = hindi.copy(externalTmdbId = movie.id + 1)
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) {
            PortalSearchPage(listOf(unrelated, differentIdentity), 1, false,
                movieReviewCandidates = listOf(unrelated, differentIdentity))
        }
        assertTrue(result.selectionCandidates.isEmpty())
        assertFalse(result.requiresConfirmation)
    }

    @Test fun authoritativeIdMatchesLocalizedProviderTitle() = runBlocking {
        val localized = hindi.copy(title = "Provider localized name", externalTmdbId = movie.id)
        val result = lookupProviderMovie(movie, emptyList(), { emptyList() }) {
            PortalSearchPage(listOf(localized), 1, false)
        }
        assertEquals(listOf(localized), result.matches)
        assertFalse(result.requiresConfirmation)
    }
}
