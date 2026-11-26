package com.nikhil.niktv.torrent

import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.GenreMarket
import com.nikhil.niktv.discovery.WatchProvider
import com.nikhil.niktv.model.TmdbHomeSection
import org.junit.Assert.*
import org.junit.Test

class TorrentHomeRepositoryTest {
    private fun card(id: Int, series: Boolean) = DiscoveryCard(id, series, "Title $id", date = "2020-01-01")

    @Test fun homeHasStableRegionalShelvesWithIndependentErrors() {
        val initial = TorrentHomeState()
        assertEquals(listOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES,
            TmdbHomeSection.BOLLYWOOD, TmdbHomeSection.HOLLYWOOD,
            TmdbHomeSection.BOLLYWOOD_SERIES, TmdbHomeSection.HOLLYWOOD_SERIES), initial.shelves.map { it.section })
        val changed = initial.copy(shelves = initial.shelves.map {
            if (it.section == TmdbHomeSection.BOLLYWOOD) it.copy(error = "Offline") else it
        })
        assertEquals(1, changed.shelves.count { it.error != null })
        assertEquals(initial.shelves.map { it.key }, changed.shelves.map { it.key })
    }

    @Test fun heroesIncludeMoviesAndSeriesWithoutDuplicateTitlesOrExceedingTen() {
        val movies = (1..10).map { card(it, false) }
        val shows = (1..10).map { card(it, true) }
        val state = TorrentHomeState(shelves = listOf(
            TorrentHomeShelf(TmdbHomeSection.TRENDING_MOVIES, listOf(movies.first()) + movies),
            TorrentHomeShelf(TmdbHomeSection.TRENDING_SERIES, shows)))
        assertEquals(10, state.heroes.size)
        assertEquals(10, state.heroes.map { it.key }.distinct().size)
        assertEquals(movies.first(), state.heroes.first())
        assertEquals(shows.first(), state.heroes[1])
        assertEquals(state.heroes.first(), state.copy(heroIndex = 900).hero)
        assertNull(TorrentHomeState().hero)
    }

    @Test fun collectionDestinationsCannotMixUnrelatedFiltersOrMediaTypes() {
        TorrentHomeCollection("genre:35", "Comedy", false, genre = WatchProvider(35, "Comedy"), market = GenreMarket.BOLLYWOOD).validate()
        TorrentHomeCollection("provider:8", "Provider", true, provider = WatchProvider(8, "Provider")).validate()
        assertThrows(IllegalArgumentException::class.java) { TorrentHomeCollection("none", "None", false).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentHomeCollection("mixed", "Mixed", false, section = TmdbHomeSection.HOLLYWOOD, genre = WatchProvider(35, "Comedy")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentHomeCollection("wrong-type", "Wrong", true, section = TmdbHomeSection.HOLLYWOOD).validate()
        }
    }
}
