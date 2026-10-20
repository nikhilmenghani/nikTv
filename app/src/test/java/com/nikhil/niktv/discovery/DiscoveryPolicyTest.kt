package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPolicyTest {
    @Test fun availabilityCountsStayScopedByProfileAndMediaType() {
        val movie = DiscoveryCard(7, false, "Movie", date = "2020-01-01")
        val series = movie.copy(series = true)
        val counts = mapOf(discoveryAvailabilityKey("profile-a", movie) to 3)
        assertEquals(3, counts[discoveryAvailabilityKey("profile-a", movie)])
        assertNull(counts[discoveryAvailabilityKey("profile-b", movie)])
        assertNull(counts[discoveryAvailabilityKey("profile-a", series)])
    }

    @Test fun continueWatchingProgressNeverUsesAnotherProfileOrEpisode() {
        val movie = RecentItem(FavoriteKind.MOVIE, MediaItem("7", "A Movie", null, null), profileKey = "p")
        val other = PlaybackProgress("progress-v2|other|MOVIES|movie:a-movie|path", 10, 100)
        val own = other.copy(key = "progress-v2|p|MOVIES|movie:a-movie|path")
        assertNull(discoveryRecentProgress(movie, listOf(other)))
        assertEquals(own, discoveryRecentProgress(movie, listOf(other, own)))
        val show = movie.copy(kind = FavoriteKind.SERIES, lastPlayed = MediaItem("8", "Episode", null, null, seasonNumber = 2, episodeNumber = 3))
        val episode = own.copy(key = "progress-v2|p|SERIES|series:7|s:2|e:3|episode|path")
        assertNull(discoveryRecentProgress(show, listOf(episode.copy(key = episode.key.replace("e:3", "e:2")))))
        assertEquals(episode, discoveryRecentProgress(show, listOf(episode)))
    }
    private fun card(id: Int, show: Boolean = false, date: String? = "2020-01-01") =
        DiscoveryCard(id, show, "Title $id", date = date)
    @Test fun releasePolicyExcludesUnknownInvalidFutureAndSensitiveTitles() {
        val today = "2026-10-19"
        assertTrue(card(1, date = "2026-10-19").released(today))
        assertTrue(card(1, true).released(today))
        listOf<String?>(null, "", "unknown", "2026-10-20", "2026-13-02").forEach {
            assertFalse("Invalid/unreleased date $it", card(1, date = it).released(today))
        }
        assertFalse(card(1).copy(adult = true).released(today))
    }
    @Test fun homeInterleavesMoviesAndShowsWithoutDroppingSingletons() {
        val movies = (1..8).map { card(it) }; val shows = (1..8).map { card(it, true) }
        val heroes = discoveryHeroes(DashboardSurface.HOME, movies, shows)
        assertEquals(10, heroes.size)
        assertEquals(listOf("movie:1", "tv:1", "movie:2", "tv:2"), heroes.take(4).map { it.key })
        assertEquals(listOf(card(1)), discoveryHeroes(DashboardSurface.HOME, listOf(card(1)), emptyList()))
        assertTrue(discoveryHeroes(DashboardSurface.MOVIES, movies, shows).none { it.series })
        assertTrue(discoveryHeroes(DashboardSurface.SERIES, movies, shows).all { it.series })
    }
    @Test fun eachInteractionOrLifecycleBlockStopsHeroRotation() {
        val idle = HeroActivity(true, true, false, false, false, false)
        assertTrue(idle.mayRotate)
        listOf(idle.copy(visible = false), idle.copy(foreground = false), idle.copy(focused = true),
            idle.copy(scrolling = true), idle.copy(touching = true), idle.copy(popup = true)).forEach { assertFalse(it.mayRotate) }
    }
    @Test fun generationRejectsOutOfOrderResponsesAcrossProfilesScreensAndDetails() {
        val generation = DiscoveryGeneration()
        val first = generation.next(); assertTrue(generation.accepts(first))
        assertEquals(first, generation.nextToken())
        val second = generation.next(); assertFalse(generation.accepts(first)); assertTrue(generation.accepts(second))
        generation.next(); assertFalse(generation.accepts(second))
    }
    @Test fun episodeResolutionRequiresAnUnambiguousSeasonAndEpisodeIdentity() {
        val s1e2 = MediaItem("a", "Other title", null, null, seasonNumber = 1, episodeNumber = 2)
        val s2e2 = s1e2.copy(id = "b", seasonNumber = 2)
        val unnumbered = s1e2.copy(id = "c", episodeNumber = null)
        assertEquals(s2e2, discoveryEpisodeMatch(listOf(s1e2, s2e2, unnumbered), 2, 2))
        assertNull(discoveryEpisodeMatch(listOf(s1e2, unnumbered), 1, 1))
        assertNull(discoveryEpisodeMatch(listOf(s1e2, s1e2.copy(id = "duplicate")), 1, 2))
    }
    @Test fun tmdbFavoriteIdentityDoesNotMasqueradeAsProviderAvailability() {
        val movie = card(42)
        assertNull(movie.media.command)
        assertEquals("tmdb-movie:42", movie.media.id)
        assertEquals(42, movie.media.externalTmdbId)
        assertEquals(42, movie.movie().id)
        assertEquals("tmdb-series:42", card(42, true).media.id)
    }
    @Test fun releaseDatesUseStrictGregorianLeapYearRulesWithoutJavaTime() {
        assertTrue(releasedDiscoveryDate("2024-02-29", "2026-10-19"))
        assertTrue(releasedDiscoveryDate("2000-02-29", "2026-10-19"))
        listOf("2026-02-29", "1900-02-29", "2026-04-31", "2026-00-10", "0000-01-01").forEach {
            assertFalse(it, releasedDiscoveryDate(it, "2026-10-19"))
        }
    }
    @Test fun discoveryQueriesKeepMediaTypesLanguagesAndReleaseConstraintsSeparate() {
        val movie = discoveryFeedRequest(TmdbHomeSection.TRENDING_MOVIES)
        val tv = discoveryFeedRequest(TmdbHomeSection.TRENDING_SERIES)
        assertEquals("/trending/movie/day", movie.first); assertEquals("/trending/tv/day", tv.first)
        val hindi = discoveryFeedRequest(TmdbHomeSection.NEW_BOLLYWOOD_MOVIES)
        assertEquals("hi", hindi.second["with_original_language"])
        assertEquals(currentDiscoveryDate(), hindi.second["primary_release_date.lte"])
        val action = discoveryFeedRequest(TmdbHomeSection.ACTION_SERIES)
        assertEquals("10759", action.second["with_genres"])
        assertEquals(currentDiscoveryDate(), action.second["first_air_date.lte"])
        TmdbHomeSection.entries.forEach { assertEquals("false", discoveryFeedRequest(it).second["include_adult"]) }
    }
}
