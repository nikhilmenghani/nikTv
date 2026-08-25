package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StalkerPlaybackCommandTest {
    @Test
    fun unrelatedFirstDetailCannotReplaceSelectedMovieCommand() {
        val command = StalkerPortalClient.resolveMoviePlaybackCommand(
            movieId = "200",
            originalCommand = "/media/file_200.mpg",
            details = listOf("100" to "100")
        )

        assertEquals("/media/file_200.mpg", command)
    }

    @Test
    fun matchingDetailCanProvideThePortalFileId() {
        val command = StalkerPortalClient.resolveMoviePlaybackCommand(
            movieId = "200",
            originalCommand = "/media/file_legacy.mpg",
            details = listOf(
                "100" to "100",
                "9876" to "200"
            )
        )

        assertEquals("/media/file_9876.mpg", command)
    }
}
