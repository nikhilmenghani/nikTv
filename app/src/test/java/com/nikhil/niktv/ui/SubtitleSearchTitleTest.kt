package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSearchTitleTest {
    @Test
    fun removesProviderLanguageSuffixFromSeriesTitle() {
        val playing = PlayingMedia(
            media = MediaItem(id = "episode", title = "The One Where Estelle Dies", logo = null, command = null),
            url = "https://example.test/video.mp4",
            catalogType = CatalogType.SERIES,
            series = MediaItem(id = "series", title = "Friends (English)", logo = null, command = null)
        )

        assertEquals("Friends", playing.suggestedSubtitleSearchTitle())
    }

    @Test
    fun retainsARegularMovieTitle() {
        val playing = PlayingMedia(
            media = MediaItem(id = "movie", title = "The Italian Job", logo = null, command = null),
            url = "https://example.test/video.mp4",
            catalogType = CatalogType.MOVIES
        )

        assertEquals("The Italian Job", playing.suggestedSubtitleSearchTitle())
    }
}
