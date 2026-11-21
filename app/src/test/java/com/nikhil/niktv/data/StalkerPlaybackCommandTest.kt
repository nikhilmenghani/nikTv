package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class StalkerPlaybackCommandTest {
    @Test fun episodeWithSeveralQualityFilesUsesItsOwnFiles() {
        val rows = listOf(
            """{"id":"3466197","is_file":true,"video_id":"93826","series_id":"3070139"}""",
            """{"id":"3466317","is_file":true,"video_id":"93826","series_id":"3070139"}""",
            """{"id":"other-episode","is_file":true,"video_id":"93826","series_id":"other"}""",
            """{"id":"other-show","is_file":true,"video_id":"different","series_id":"3070139"}"""
        ).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("/media/file_3466197.mpg", "/media/file_3466317.mpg"),
            StalkerPortalClient.episodePlaybackCommands("93826", "3070139", "/media/93826.mpg", rows))
    }

    @Test fun ignoredEpisodeFilterDoesNotPickAnotherEpisodesFile() {
        val rows = listOf(
            """{"id":"first","is_file":true,"video_id":"93826","series_id":"other"}""",
            """{"id":"second","is_file":true,"video_id":"93826","series_id":"another"}"""
        ).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("/media/93826.mpg"),
            StalkerPortalClient.episodePlaybackCommands("93826", "3070139", "/media/93826.mpg", rows))
    }

    @Test fun singleLegacyEpisodeDetailKeepsItsFileId() {
        val row = Json.parseToJsonElement("""{"id":"legacy-file"}""").jsonObject
        assertEquals(listOf("/media/file_legacy-file.mpg"),
            StalkerPortalClient.episodePlaybackCommands("93826", "3070139", "/media/93826.mpg", listOf(row)))
    }

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
