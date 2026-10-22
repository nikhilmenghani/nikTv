package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState

internal data class LibraryEntry(val media: MediaItem, val kind: FavoriteKind,
    val category: Category? = null, val favorite: FavoriteItem? = null, val recent: RecentItem? = null) {
    val key get() = "${kind.name}:${media.id}"
    val live get() = kind == FavoriteKind.CHANNEL
    val type get() = when (kind) { FavoriteKind.CHANNEL -> CatalogType.LIVE_TV; FavoriteKind.MOVIE -> CatalogType.MOVIES; else -> CatalogType.SERIES }
}
internal data class LibraryRow(val key: String, val title: String, val entries: List<LibraryEntry>, val category: Category? = null)
internal enum class LibrarySort(val title: String) { RECENT("Recently added"), TITLE("Title A–Z") }
internal fun librarySavedRows(state: NikTvState, profile: String, kind: FavoriteKind?, query: String,
    sort: LibrarySort): List<LibraryRow> {
    val saved = state.favorites.filter { it.profileKey == profile && (kind == null || it.kind == kind) &&
        (query.isBlank() || it.media.title.contains(query.trim(), true) || it.categoryTitle.orEmpty().contains(query.trim(), true)) }
    val ordered = when (sort) { LibrarySort.RECENT -> saved.sortedByDescending { it.addedAtMillis }; LibrarySort.TITLE -> saved.sortedBy { it.media.title.lowercase() } }
    return FavoriteKind.entries.mapNotNull { type ->
        val entries = ordered.filter { it.kind == type }.map { LibraryEntry(it.media, type, favorite = it) }
        entries.takeIf { it.isNotEmpty() }?.let { LibraryRow("saved:${type.name}", when(type) {
            FavoriteKind.CHANNEL -> "Saved channels"; FavoriteKind.MOVIE -> "Saved movies";
            FavoriteKind.SERIES -> "Saved series"; FavoriteKind.EPISODE -> "Saved episodes"
        }, it) }
    }
}
/** My List's landing page is a collection, not a second activity feed. */
internal fun libraryCollectionRows(state: NikTvState, profile: String): List<LibraryRow> =
    librarySavedRows(state, profile, null, "", LibrarySort.RECENT).filter { row ->
        row.entries.firstOrNull()?.kind in setOf(FavoriteKind.CHANNEL, FavoriteKind.MOVIE, FavoriteKind.SERIES)
    }

internal fun libraryLiveRows(state: NikTvState, profile: String, pins: Map<String, List<String>>): List<LibraryRow> {
    val cache = state.browseCachesByType[CatalogType.LIVE_TV]?.takeIf { it.profileKey == profile }
    val categories = state.categories.filter { it.type == CatalogType.LIVE_TV }
    val rows = categories.mapNotNull { category ->
        val sources = category.sourceCategoryIds.ifEmpty { listOf(category.id) }
        val entries = sources.asSequence().flatMap { cache?.itemsByCategory?.get(it).orEmpty().asSequence() }
            .take(12).map { LibraryEntry(it, FavoriteKind.CHANNEL, category) }.toList()
        entries.takeIf { it.isNotEmpty() }?.let { LibraryRow("live:${category.id}", category.title, it, category) }
    }
    val recent = state.recentlyPlayed.filter { it.profileKey == profile && it.kind == FavoriteKind.CHANNEL }
        .distinctBy { it.media.id }.map { recent -> LibraryEntry(recent.media, FavoriteKind.CHANNEL,
            categories.firstOrNull { it.id == recent.media.portalCategoryId || recent.media.portalCategoryId in it.sourceCategoryIds }, recent = recent) }
    val wanted = pins.values.flatten().toSet()
    val byId = recent.associateByTo(mutableMapOf()) { it.media.id }
    if (wanted.isNotEmpty()) categories.forEach { category -> category.sourceCategoryIds.ifEmpty { listOf(category.id) }.forEach { source ->
        cache?.itemsByCategory?.get(source).orEmpty().forEach { media ->
            if (media.id in wanted) byId[media.id] = LibraryEntry(media, FavoriteKind.CHANNEL, category)
        }
    } }
    val pinned = pins.flatMap { (category, ids) -> ids.mapNotNull { id -> byId[id]?.copy(category = categories.firstOrNull { it.id == category }) } }
        .distinctBy { it.media.id }
    return buildList {
        if (pinned.isNotEmpty()) add(LibraryRow("pinned", "Pinned channels", pinned))
        if (recent.isNotEmpty()) add(LibraryRow("recent", "Recently watched", recent))
        addAll(rows)
    }
}

/** Previews are bounded; an explicit Play retains the full loaded navigation context. */
internal fun libraryLiveQueue(state: NikTvState, profile: String, entry: LibraryEntry,
    fallback: List<MediaItem>): Pair<List<MediaItem>, PlaybackQueueFilter> {
    val cache = state.browseCachesByType[CatalogType.LIVE_TV]?.takeIf { it.profileKey == profile }
    val queue = entry.category?.let { category -> category.sourceCategoryIds.ifEmpty { listOf(category.id) }
        .flatMap { cache?.itemsByCategory?.get(it).orEmpty() } }.orEmpty()
        .ifEmpty { fallback.ifEmpty { listOf(entry.media) } }
    val index = queue.indexOfFirst { it === entry.media }.takeIf { it >= 0 }
        ?: queue.indexOfFirst { it.id == entry.media.id }.coerceAtLeast(0)
    return queue to PlaybackQueueFilter(contextId = entry.category?.id,
        selectedOrdinal = queue.take(index).count { it.id == entry.media.id },
        sourceIds = entry.category?.sourceCategoryIds?.takeIf { it.isNotEmpty() }?.toSet())
}

/** Only current-profile movie/series favorites may appear as ambient hero artwork. */
internal fun libraryFavoriteHeroes(favorites: List<FavoriteItem>, profile: String): List<FavoriteItem> =
    favorites.filter {
        it.profileKey == profile && it.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES) &&
            !it.media.isAdult && !com.nikhil.niktv.data.adultContentLabel(it.media.title) &&
            !com.nikhil.niktv.data.adultContentLabel(it.categoryTitle.orEmpty())
    }.distinctBy { it.key }
