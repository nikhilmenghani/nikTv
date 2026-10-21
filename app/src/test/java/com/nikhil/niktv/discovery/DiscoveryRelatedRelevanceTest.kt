package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRelatedRelevanceTest {
    private val source = DiscoveryCard(1, true, "Series", date = "2020-01-01", genres = listOf("Comedy"), originalLanguage = "hi")
    private fun card(id: Int, language: String?, genre: String = "Comedy") =
        source.copy(id = id, title = "Other $id", originalLanguage = language, genres = listOf(genre))

    @Test fun sameLanguageAndGenreTakePriorityWithoutAdditionalLookup() {
        val candidates = listOf(card(2, "en"), card(3, "hi", "Drama"), card(4, "hi"))
        assertEquals(listOf(4, 2), relevantDiscoveryRecommendations(source, candidates).map { it.id })
    }
    @Test fun otherLanguagesAreIncludedWhenGenresMatch() {
        assertEquals(listOf(2), relevantDiscoveryRecommendations(source, listOf(card(2, "en"))).map { it.id })
        assertTrue(relevantDiscoveryRecommendations(source, listOf(card(2, "en", "Horror"))).isEmpty())
        assertEquals(listOf(3), relevantDiscoveryRecommendations(source, listOf(card(3, null))).map { it.id })
    }
    @Test fun noGenreMatchesProducesAnEmptyPanelWithoutInventingRecommendations() {
        assertTrue(relevantDiscoveryRecommendations(source, emptyList()).isEmpty())
        assertTrue(relevantDiscoveryRecommendations(source, listOf(card(2, "hi", "Drama"))).isEmpty())
    }
    @Test fun unknownLanguageKeepsGenreMatchesAndExcludesUnreleasedAndSource() {
        val candidates = listOf(source, card(2, null), card(3, null, "Horror"), card(4, null).copy(date = "2999-01-01"), card(2, null))
        assertEquals(listOf(2), relevantDiscoveryRecommendations(source.copy(originalLanguage = null), candidates).map { it.id })
    }
}
