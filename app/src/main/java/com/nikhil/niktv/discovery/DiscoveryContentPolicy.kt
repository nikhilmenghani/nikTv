package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.XtreamContentGate

internal fun ProviderDiscoveryState.withContentPolicy(app: NikTvState): ProviderDiscoveryState {
    val gate = XtreamContentGate(app)
    if (!gate.active) return this
    fun allowed(item: MediaItem, type: CatalogType) = gate.allows(item, type,
        categories[type].orEmpty().firstOrNull { it.id == item.portalCategoryId }?.title)
    return copy(items = items.mapValues { (type, rows) -> rows.filter { allowed(it, type) } },
        pages = pages.mapValues { (type, pages) -> pages.mapValues { (_, page) ->
            page.copy(items = page.items.filter { allowed(it, type) })
        } })
}

/** Public suggestions obey the same profile choices; provider variants remain separately checked. */
internal fun DiscoveryState.withContentPolicy(app: NikTvState): DiscoveryState {
    val gate = XtreamContentGate(app)
    if (!gate.active) return this
    fun allowed(card: DiscoveryCard): Boolean {
        val type = if (card.series) CatalogType.SERIES else CatalogType.MOVIES
        val matches = (if (card.series) app.modernTmdbSeriesMatches else app.modernTmdbMovieMatches)[card.id].orEmpty()
        if (matches.any { gate.allows(it, type) }) return true
        return gate.allows(card.media.copy(contentLanguage = card.originalLanguage, contentGenres = card.genres), type,
            if (card.series) "Series" else "Movies")
    }
    val visibleHeroes = heroes.filter(::allowed)
    val selectedKey = hero?.key
    return copy(feeds = feeds.mapValues { it.value.filter(::allowed) }, related = related.filter(::allowed),
        collection = collection.filter(::allowed), heroes = visibleHeroes,
        heroIndex = visibleHeroes.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
        heroPresentation = heroPresentation?.takeIf(::allowed), focusedPresentation = focusedPresentation?.takeIf(::allowed))
}
