package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.FavoriteKind

/** Popup identity includes kind; the hero retains its existing broader profile/media check. */
internal data class DiscoveryFavoriteLookup(val keys: Set<String>, val profileMediaIds: Set<String>)

internal fun discoveryFavoriteLookup(favorites: List<FavoriteItem>, profile: String): DiscoveryFavoriteLookup {
    val keys = HashSet<String>(favorites.size)
    val mediaIds = HashSet<String>()
    favorites.forEach { favorite ->
        keys.add(favorite.key)
        if (favorite.profileKey == profile) mediaIds.add(favorite.media.id)
    }
    return DiscoveryFavoriteLookup(keys, mediaIds)
}

internal fun discoveryFavoriteKey(profile: String, kind: FavoriteKind, mediaId: String): String =
    "$profile:${kind.name}:$mediaId"

/** Provider identifiers can overlap across catalog kinds; keep the new header exact. */
internal fun providerHeroFavoriteIds(favorites: List<FavoriteItem>, profile: String, kind: FavoriteKind): Set<String> =
    favorites.asSequence().filter { it.profileKey == profile && it.kind == kind }.map { it.media.id }.toSet()
