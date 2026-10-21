package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFavoriteLookupTest {
    private val media = MediaItem("same-id", "Fixture", null, null)

    @Test fun exactKeyPreservesKindAndProfileIdentityWithoutConsideringSourceCategoryOrTime() {
        val first = FavoriteItem(FavoriteKind.MOVIE, media, addedAtMillis = 1, profileKey = "one", categoryTitle = "First")
        val otherPresentation = first.copy(addedAtMillis = 2, categoryTitle = "Second", source = FavoriteSource.TMDB)
        val lookup = discoveryFavoriteLookup(listOf(first, otherPresentation), "one")
        assertEquals(first.key, discoveryFavoriteKey("one", FavoriteKind.MOVIE, media.id))
        assertEquals(setOf(first.key), lookup.keys)
        assertTrue(otherPresentation.key in lookup.keys)
        assertFalse(discoveryFavoriteKey("one", FavoriteKind.SERIES, media.id) in lookup.keys)
        assertFalse(discoveryFavoriteKey("two", FavoriteKind.MOVIE, media.id) in lookup.keys)
    }

    @Test fun heroRetainsItsBroaderProfileMediaMembershipAcrossKinds() {
        val channel = FavoriteItem(FavoriteKind.CHANNEL, media, profileKey = "one")
        val otherProfile = FavoriteItem(FavoriteKind.MOVIE, media.copy(id = "other-id"), profileKey = "two")
        val lookup = discoveryFavoriteLookup(listOf(channel, otherProfile), "one")
        assertTrue(media.id in lookup.profileMediaIds)
        assertFalse(discoveryFavoriteKey("one", FavoriteKind.MOVIE, media.id) in lookup.keys)
        assertFalse("other-id" in lookup.profileMediaIds)
        assertTrue(otherProfile.key in lookup.keys)
    }

    @Test fun duplicateFavoritesAndProviderOccurrencesDoNotChangeMembership() {
        val favorite = FavoriteItem(FavoriteKind.MOVIE, media, profileKey = "one")
        val lookup = discoveryFavoriteLookup(listOf(favorite, favorite.copy(categoryTitle = "Pinned"), favorite), "one")
        val providerOccurrences = listOf(media, media.copy(title = "Provider occurrence"))
        assertEquals(listOf(true, true), providerOccurrences.map {
            discoveryFavoriteKey("one", FavoriteKind.MOVIE, it.id) in lookup.keys
        })
        assertEquals(setOf(media.id), lookup.profileMediaIds)
    }

    @Test fun refreshedIndexReflectsAddRemoveAndUsesTheSameKeysAsEveryFavoriteKind() {
        val favorites = FavoriteKind.entries.map { FavoriteItem(it, media, profileKey = "one") }
        val lookup = discoveryFavoriteLookup(favorites, "one")
        favorites.forEach { assertTrue(discoveryFavoriteKey(it.profileKey, it.kind, it.media.id) in lookup.keys) }
        assertTrue(discoveryFavoriteLookup(emptyList(), "one").keys.isEmpty())
        assertTrue(discoveryFavoriteLookup(favorites, "two").profileMediaIds.isEmpty())
    }
}
