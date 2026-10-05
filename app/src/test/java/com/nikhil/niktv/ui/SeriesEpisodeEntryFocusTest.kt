package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesEpisodeEntryFocusTest {
    private val episodes = listOf("latest", "previous", "earliest")

    @Test fun `fresh entry waits inside episode screen then chooses topmost episode`() {
        assertEquals(SeriesEpisodeEntryFocus.Loading,
            seriesEpisodeEntryFocusTarget(emptyList(), true, null, true))
        assertEquals(SeriesEpisodeEntryFocus.Episode(0, "latest"),
            seriesEpisodeEntryFocusTarget(episodes, false, null, true))
    }

    @Test fun `playback return restores selected episode rather than first row`() {
        assertEquals(SeriesEpisodeEntryFocus.Episode(1, "previous"),
            seriesEpisodeEntryFocusTarget(episodes, false, "previous", false))
    }

    @Test fun `returning episode is not replaced by first while provider is loading`() {
        assertEquals(SeriesEpisodeEntryFocus.Loading,
            seriesEpisodeEntryFocusTarget(listOf("latest"), true, "previous", false))
        assertEquals(SeriesEpisodeEntryFocus.Episode(1, "previous"),
            seriesEpisodeEntryFocusTarget(episodes, false, "previous", false))
    }

    @Test fun `removed returning episode falls back to topmost only after loading finishes`() {
        assertEquals(SeriesEpisodeEntryFocus.Episode(0, "latest"),
            seriesEpisodeEntryFocusTarget(episodes, false, "removed", false))
    }

    @Test fun `empty or failed load retains accessible back target`() {
        assertEquals(SeriesEpisodeEntryFocus.Back,
            seriesEpisodeEntryFocusTarget(emptyList(), false, null, true))
        assertEquals(SeriesEpisodeEntryFocus.Back,
            seriesEpisodeEntryFocusTarget(emptyList(), false, "missing", false))
    }

    @Test fun `background refresh and sorting do not move an established episode selection`() {
        assertNull(seriesEpisodeEntryFocusTarget(episodes.reversed(), false, null, false))
        assertNull(seriesEpisodeEntryFocusTarget(emptyList(), true, null, false))
    }
}
