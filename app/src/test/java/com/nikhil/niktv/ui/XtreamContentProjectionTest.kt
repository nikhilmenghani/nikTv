package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class XtreamContentProjectionTest {
    private val portal = PortalProfile("Connection", "http://test.invalid", "", portalType = PortalType.XTREAM)
    private val english = Category("1", "ENGLISH | MOVIES", CatalogType.MOVIES)
    private val arabic = Category("2", "ARABIC | MOVIES", CatalogType.MOVIES)
    private val allowed = MediaItem("a", "Allowed", null, null, portalCategoryId = "1")
    private val hidden = MediaItem("b", "Hidden", null, null, portalCategoryId = "2")
    private fun snapshot() = NikTvState(savedProfile = portal, selectedType = CatalogType.MOVIES,
        xtreamContentPolicy = XtreamContentPolicy(enabled = true), categories = listOf(english, arabic),
        rawCategoriesByType = mapOf(CatalogType.MOVIES to listOf(english, arabic)),
        items = listOf(allowed, hidden), searchResults = listOf(allowed, hidden), searchType = SearchContentType.MOVIES,
        favorites = listOf(allowed, hidden).map { FavoriteItem(FavoriteKind.MOVIE, it, profileKey = portal.cacheKey()) },
        recentlyPlayed = listOf(allowed, hidden).map { RecentItem(FavoriteKind.MOVIE, it, profileKey = portal.cacheKey()) },
        nowPlaying = PlayingMedia(allowed, "http://test.invalid/movie", CatalogType.MOVIES, episodeQueue = listOf(allowed, hidden)))
    @Test fun allExposedProviderSurfacesUseSamePolicyAndRawStateIsUntouched() {
        val raw = snapshot()
        val result = XtreamContentProjection().project(raw)
        assertEquals(listOf(allowed), result.items)
        assertEquals(listOf(allowed), result.searchResults)
        assertEquals(listOf("a"), result.favorites.map { it.media.id })
        assertEquals(listOf("a"), result.recentlyPlayed.map { it.media.id })
        assertEquals(listOf(allowed), result.nowPlaying!!.episodeQueue)
        assertEquals(listOf(english), result.categories)
        assertEquals(2, raw.items.size)
    }
    @Test fun stalkerAndDisabledProfilesStayUnchanged() {
        val raw = snapshot()
        val stalker = raw.copy(savedProfile = portal.copy(portalType = PortalType.STALKER))
        assertSame(stalker, XtreamContentProjection().project(stalker))
        val disabled = raw.copy(xtreamContentPolicy = XtreamContentPolicy())
        assertSame(disabled, XtreamContentProjection().project(disabled))
    }
    @Test fun policyChangeRestoresItemsWithoutDestroyingSharedCache() {
        val source = MutableStateFlow(snapshot())
        val flow = ContentFilteredStateFlow(source)
        assertEquals(1, flow.value.items.size)
        source.value = source.value.copy(xtreamContentPolicy = XtreamContentPolicy(enabled = true, languages = setOf("ar")))
        assertEquals(listOf(hidden), flow.value.items)
        assertEquals(2, source.value.items.size)
    }
    @Test fun progressOnlyUpdatesReuseFilteredListInstances() {
        val projection = XtreamContentProjection()
        val raw = snapshot()
        val first = projection.project(raw)
        val next = projection.project(raw.copy(playbackProgress = listOf(PlaybackProgress("k", 30, 100))))
        assertSame(first.items, next.items)
        assertSame(first.favorites, next.favorites)
    }
    @Test fun episodesInheritTheirSeriesPolicyInsteadOfMissingEpisodeCategory() {
        val series = allowed.copy(title = "Hindi series", portalCategoryId = "3")
        val episode = MediaItem("episode", "Episode 1", null, null)
        val raw = snapshot().copy(selectedType = CatalogType.SERIES, selectedSeries = series, items = listOf(episode),
            rawCategoriesByType = mapOf(CatalogType.SERIES to listOf(Category("3", "HINDI TV SERIES", CatalogType.SERIES))))
        assertEquals(listOf(episode), XtreamContentProjection().project(raw).items)
    }
}
