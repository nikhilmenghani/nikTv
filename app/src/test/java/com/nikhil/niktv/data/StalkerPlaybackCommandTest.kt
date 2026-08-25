package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StalkerPlaybackCommandTest {
    @Test
    fun searchResultProducesWioVodCommandFallbacksWithoutDetailRows() {
        val commands = StalkerPortalClient.moviePlaybackCommands(
            itemId = "16037",
            originalCommand = "/media/16037.mpg",
            detailCommands = emptyList()
        )

        assertEquals(
            listOf(
                "/media/file_16037.mpg",
                "ffmpeg /media/file_16037.mpg",
                "/media/16037.mpg",
                "ffmpeg /media/16037.mpg"
            ),
            commands
        )
    }

    @Test
    fun hindiDetailRanksAheadOfSameTitleEnglishDetail() {
        val commands = StalkerPortalClient.rankMovieDetailCommands(
            selectedId = "468667",
            selectedTitle = "Welcome to the Jungle (2026) (Hindi) - HINDI | LATEST MOVIES 4K",
            details = listOf(
                VodDetailCandidate("100", null, "Welcome to the Jungle (2003) (English)", "/media/english.mpg"),
                VodDetailCandidate("900", "468667", "Welcome to the Jungle (2026) (Hindi)", "/media/hindi-file.mpg")
            )
        )

        assertEquals(listOf("/media/hindi-file.mpg", "/media/file_900.mpg"), commands)
    }
}
