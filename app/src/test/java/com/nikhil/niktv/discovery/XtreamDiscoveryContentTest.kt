package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.XtreamContentProjection
import org.junit.Assert.*
import org.junit.Test

class XtreamDiscoveryContentTest {
    private val portal = PortalProfile("Xtream", "http://fixture.invalid", "", portalType = PortalType.XTREAM)
    private fun app() = NikTvState(savedProfile = portal, xtreamContentPolicy = XtreamContentPolicy(enabled = true),
        rawCategoriesByType = mapOf(CatalogType.MOVIES to listOf(Category("1", "ENGLISH | MOVIES", CatalogType.MOVIES),
            Category("2", "ARABIC | MOVIES", CatalogType.MOVIES))))
    @Test fun providerMemoryAndDiskPagesRemainCompleteWhenPresentationIsFiltered() {
        val allowed = MediaItem("1", "English title", null, null, portalCategoryId = "1")
        val hidden = MediaItem("2", "Arabic title", null, null, portalCategoryId = "2")
        val raw = ProviderDiscoveryState(items = mapOf(CatalogType.MOVIES to listOf(allowed, hidden)),
            pages = mapOf(CatalogType.MOVIES to mapOf("2" to ProviderDiscoveryPage(listOf(hidden), page = 1))))
        val result = raw.withContentPolicy(app())
        assertEquals(listOf(allowed), result.items[CatalogType.MOVIES])
        assertTrue(result.pages[CatalogType.MOVIES]!!["2"]!!.items.isEmpty())
        assertEquals(2, raw.items[CatalogType.MOVIES]!!.size)
        assertEquals(listOf(hidden), raw.pages[CatalogType.MOVIES]!!["2"]!!.items)
    }
    @Test fun hiddenCategoryCannotLeakThroughUnclassifiedShowAfterStateProjection() {
        val projected = XtreamContentProjection().project(app().copy(xtreamContentPolicy = XtreamContentPolicy(enabled = true, unclassified = UnclassifiedContent.SHOW)))
        val hidden = MediaItem("2", "A title", null, null, portalCategoryId = "2")
        assertTrue(ProviderDiscoveryState(items = mapOf(CatalogType.MOVIES to listOf(hidden))).withContentPolicy(projected).items[CatalogType.MOVIES]!!.isEmpty())
    }
    @Test fun publicFeedsHeroesAndRelatedTitlesUseOriginalLanguageWithoutNetworkLookups() {
        val english = DiscoveryCard(1, false, "English title", originalLanguage = "en")
        val arabic = DiscoveryCard(2, false, "Arabic title", originalLanguage = "ar")
        val raw = DiscoveryState(feeds = mapOf(TmdbHomeSection.TRENDING_MOVIES to listOf(english, arabic)),
            heroes = listOf(arabic, english), related = listOf(arabic), collection = listOf(english, arabic))
        val visible = raw.withContentPolicy(app())
        assertEquals(listOf(english), visible.heroes)
        assertEquals(listOf(english), visible.collection)
        assertTrue(visible.related.isEmpty())
        assertEquals(listOf(english), visible.feeds[TmdbHomeSection.TRENDING_MOVIES])
    }
}
