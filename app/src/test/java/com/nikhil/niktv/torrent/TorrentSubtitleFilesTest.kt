package com.nikhil.niktv.torrent

import org.junit.Assert.*
import org.junit.Test

class TorrentSubtitleFilesTest {
    @Test fun chosenEpisodeMatchesExactAndLanguageQualifiedCaptionsOnly() {
        val names = listOf("Show.S02E03.srt", "Subs/Show.S02E03.en.forced.srt", "Show.S02E03.fr-FR.sdh.vtt",
            "Show.S02E03.hin.ass", "Show.S02E04.en.srt", "Show.S02E030.srt", "Show.S02E03.1080p.srt", "english.srt")
        val matches = matchingTorrentSubtitles("Show.S02E03.mkv", names.mapIndexed { i, name -> TorrentVideoFile(i, name, 100) })
        assertEquals(listOf(0, 1, 2, 3), matches.map { it.index })
        assertEquals(listOf(null, "en", "fr-FR", "hi"), matches.map { it.language })
        assertEquals("English · Forced", matches[1].label)
        assertEquals("French · SDH", matches[2].label)
    }

    @Test fun oversizedEmptyAndExecutablePayloadsAreNotOfferedAndCountIsBounded() {
        val files = listOf(TorrentVideoFile(100, "Movie.en.srt", MAX_TORRENT_SUBTITLE_BYTES + 1),
            TorrentVideoFile(101, "Movie.fr.srt", 0), TorrentVideoFile(102, "Movie.en.srt.exe", 12)) +
            (0..15).map { TorrentVideoFile(it, "folder$it/Movie.srt", 200) }
        val matches = matchingTorrentSubtitles("Movie.mp4", files)
        assertEquals((0..7).toList(), matches.map { it.index })
        assertTrue(matches.all { it.sizeBytes <= MAX_TORRENT_SUBTITLE_BYTES })
    }
}
