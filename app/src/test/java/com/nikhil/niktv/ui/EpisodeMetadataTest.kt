package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeMetadataTest {
    private val episodes = listOf(
        TmdbEpisode(10, 15, "The One Where Estelle Dies", null, "2004-04-22", null),
        TmdbEpisode(10, 16, "The One with Rachel's Going Away Party", null, "2004-04-29", null)
    )

    @Test
    fun airDateWinsWhenProviderEpisodeNumberIsWrong() {
        val providerEpisode = MediaItem(
            id = "provider-16",
            title = "Friends S10 E16",
            logo = null,
            command = null,
            seasonNumber = 10,
            episodeNumber = 15,
            episodeAirDate = "2004-04-29"
        )

        assertEquals(
            "The One with Rachel's Going Away Party",
            selectTmdbEpisodeMetadata(providerEpisode, episodes)?.name
        )
    }

    @Test
    fun episodeNumberRemainsTheFallbackForGenericProviderTitles() {
        val providerEpisode = MediaItem(
            id = "provider-16",
            title = "Season 10 Episode 16",
            logo = null,
            command = null,
            seasonNumber = 10,
            episodeNumber = 16
        )

        assertEquals(
            "The One with Rachel's Going Away Party",
            selectTmdbEpisodeMetadata(providerEpisode, episodes)?.name
        )
    }
}
