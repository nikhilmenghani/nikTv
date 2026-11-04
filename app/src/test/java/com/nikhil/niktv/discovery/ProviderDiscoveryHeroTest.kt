package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDiscoveryHeroTest {
    private val a = Category("a", "A", CatalogType.MOVIES)
    private val b = Category("b", "B", CatalogType.MOVIES)
    private fun media(id: String) = MediaItem(id, "Movie $id", null, "original-$id")
    private fun page(vararg ids: String) = ProviderDiscoveryPage(ids.map(::media))
    @Test fun emptyAndFailedFeedsStopTheHeaderLoadingAnimation() {
        val type = CatalogType.MOVIES
        val empty = ProviderDiscoveryState(profile = "profile", categories = mapOf(type to listOf(a)), ready = true,
            pages = mapOf(type to mapOf("a" to ProviderDiscoveryPage(emptyList(), 1, false))))
        assertFalse(providerHeroWaiting("profile", type, listOf(a), empty, true))
        assertFalse(providerHeroWaiting("profile", type, emptyList(), empty, false))
        assertTrue(providerHeroWaiting("profile", type, listOf(a), empty, false))
        assertTrue(providerHeroWaiting("another", type, listOf(a), empty, true))
        assertFalse(providerHeroWaiting("profile", type, listOf(a), empty.copy(errors = mapOf(type to "offline")), false))
    }
    @Test fun delayedEarlierCategoriesAppendWithoutMovingTheSelectedIdentity() {
        val first = appendProviderHeroes(emptyList(), listOf(a, b), mapOf("b" to page("b1", "b2")))
        val next = appendProviderHeroes(first, listOf(a, b), mapOf("a" to page("a1"), "b" to page("b1", "b2")))
        assertEquals(listOf("b1", "b2", "a1"), next.map { it.item.id })
        assertSame(first[1], next[1])
        assertEquals("original-b2", next[1].item.command)
        assertEquals(listOf("b1", "b2"), next[1].queue.map { it.id })
    }
    @Test fun poolDeduplicatesProviderIdentityAndRemainsBounded() {
        val pages = mapOf("a" to page(*(0..20).map(Int::toString).toTypedArray()), "b" to page("0", "1"))
        val first = appendProviderHeroes(emptyList(), listOf(a, b), pages)
        assertEquals(10, first.size)
        assertEquals(10, first.map { it.item.id }.distinct().size)
        assertSame(first, appendProviderHeroes(first, listOf(b), mapOf("b" to page("new"))))
    }
    @Test fun mergedSelectionsAndMissingPagesUseOnlyLoadedUnrestrictedMedia() {
        val group = Category("group", "Merged", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b"))
        val secret = media("private").copy(isAdult = true)
        val result = appendProviderHeroes(emptyList(), listOf(group), mapOf("b" to ProviderDiscoveryPage(listOf(secret, media("ok")))))
        assertEquals(listOf("ok"), result.map { it.item.id })
        assertEquals(listOf("ok"), result.single().queue.map { it.id })
    }
}
