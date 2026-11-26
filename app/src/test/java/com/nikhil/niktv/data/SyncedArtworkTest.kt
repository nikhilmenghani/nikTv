package com.nikhil.niktv.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncedArtworkTest {
    private fun media(id: String = "movie", tmdb: Int? = null, logo: String? = null) = buildJsonObject {
        put("id", id); put("title", "Hera Pheri"); put("externalTmdbId", tmdb?.let(::JsonPrimitive) ?: JsonNull)
        put("logo", logo?.let(::JsonPrimitive) ?: JsonNull)
    }
    private val poster = "https://image.tmdb.org/t/p/w342/hera.jpg"
    @Test fun fillsMissingPresentationWithoutReplacingWinningViewingData() {
        val recent = buildJsonObject { put("playedAtMillis", 9000); put("media", media()) }
        val old = buildJsonObject { put("playedAtMillis", 1000); put("media", media(tmdb = 21614, logo = poster)) }
        val merged = retainSyncedArtwork(recent, old).jsonObject
        assertEquals(9000, merged.getValue("playedAtMillis").jsonPrimitive.int)
        assertEquals(21614, merged.getValue("media").jsonObject.getValue("externalTmdbId").jsonPrimitive.int)
        assertEquals(poster, merged.getValue("media").jsonObject.getValue("logo").jsonPrimitive.content)
    }
    @Test fun differentMovieOrExplicitRematchCannotInheritOldArtwork() {
        for (winner in listOf(media(id = "another"), media(tmdb = 170645)))
            assertEquals(winner, retainSyncedArtwork(winner, media(tmdb = 21614, logo = poster)))
        val episode = JsonObject(media() + ("episodeNumber" to JsonPrimitive(2)))
        val previous = JsonObject(media(tmdb = 21614, logo = poster) + ("episodeNumber" to JsonPrimitive(1)))
        assertEquals(episode, retainSyncedArtwork(episode, previous))
    }
    @Test fun providerLinksAndSignedOrSpoofedPublicUrlsRemainLocal() {
        for (url in listOf("https://provider.invalid/private.jpg?token=secret", poster + "?token=secret",
            "https://image.tmdb.org.evil.invalid/t/p/w342/hera.jpg", "https://user:secret@image.tmdb.org/t/p/w342/hera.jpg")) {
            assertFalse(portablePublicArtwork(JsonPrimitive(url)))
            assertEquals(JsonNull, retainSyncedArtwork(media(), media(logo = url)).jsonObject["logo"])
        }
    }

    private fun torrent(id: Int = 1, series: Boolean = false, season: Int? = null, episode: Int? = null,
        known: Boolean = false) = buildJsonObject {
        put("kind", "torrent-library-v1"); put("tmdbId", id); put("series", series)
        put("date", if (known) JsonPrimitive("2000-01-01") else JsonNull)
        put("poster", if (known) JsonPrimitive("/t/p/w500/poster.jpg") else JsonNull)
        put("backdrop", if (known) JsonPrimitive("/t/p/w1280/backdrop.jpg") else JsonNull)
        put("season", season?.let(::JsonPrimitive) ?: JsonNull)
        put("episode", episode?.let(::JsonPrimitive) ?: JsonNull)
        put("episodeStill", if (known) JsonPrimitive("/t/p/w500/episode.jpg") else JsonNull)
        put("positionMillis", if (known) 60_000 else 180_000)
    }

    @Test fun markedTorrentMergeRetainsKnownArtworkWhileKeepingNewerProgressAndIdentity() {
        val winner = torrent(series = true, season = 3, episode = 8)
        val known = torrent(series = true, season = 3, episode = 8, known = true)
        val merged = retainSyncedArtwork(winner, known).jsonObject
        assertEquals("/t/p/w500/poster.jpg", merged["poster"]?.jsonPrimitive?.content)
        assertEquals("/t/p/w1280/backdrop.jpg", merged["backdrop"]?.jsonPrimitive?.content)
        assertEquals("2000-01-01", merged["date"]?.jsonPrimitive?.content)
        assertEquals("/t/p/w500/episode.jpg", merged["episodeStill"]?.jsonPrimitive?.content)
        assertEquals(180_000, merged["positionMillis"]?.jsonPrimitive?.int)
        assertEquals(8, merged["episode"]?.jsonPrimitive?.int)
    }

    @Test fun torrentEpisodeArtworkNeverLeaksFromAnotherEpisodeOrSeason() {
        val known = torrent(series = true, season = 3, episode = 8, known = true)
        for (winner in listOf(torrent(series = true, season = 3, episode = 9), torrent(series = true, season = 4, episode = 8))) {
            val merged = retainSyncedArtwork(winner, known).jsonObject
            assertEquals("/t/p/w500/poster.jpg", merged["poster"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, merged["episodeStill"])
            assertEquals(winner["episode"], merged["episode"])
            assertEquals(winner["season"], merged["season"])
        }
    }

    @Test fun torrentRetentionRequiresExplicitKindAndMatchingTitleTypeAndTmdbId() {
        val winner = torrent()
        for (other in listOf(torrent(id = 2, known = true), torrent(series = true, known = true),
                JsonObject(torrent(known = true) - "kind")))
            assertEquals(winner, retainSyncedArtwork(winner, other))
        val unmarked = JsonObject(winner - "kind")
        assertEquals(unmarked, retainSyncedArtwork(unmarked, torrent(known = true)))
    }

    @Test fun torrentRetentionRejectsSignedSpoofedAndTraversingArtworkPaths() {
        val winner = torrent()
        for (path in listOf("/t/p/w500/image.jpg?token=secret", "/t/p/w500/../../private.jpg",
                "//evil.invalid/t/p/w500/image.jpg", "https://image.tmdb.org/t/p/w500/image.jpg", "/t/p/w500/image.jpg#fragment")) {
            val other = JsonObject(torrent(known = true) + ("poster" to JsonPrimitive(path)))
            assertEquals(JsonNull, retainSyncedArtwork(winner, other).jsonObject["poster"])
        }
    }

    @Test fun torrentArtworkRepairOnlyRunsForMarkedLibraryPreferenceRecords() {
        fun preference(name: String = "portable_preferences", key: String = "torrent_library_v1|progress:tmdb-movie:1",
            marked: Boolean = true) = buildJsonObject {
            put("preference", name); put("mapKey", key)
            put("entry", buildJsonObject {
                put("type", "json"); put("value", if (marked) torrent(known = true) else JsonObject(torrent(known = true) - "kind"))
            })
        }.toString()
        assertTrue(torrentLibraryArtworkPreference(preference()))
        assertTrue(torrentLibraryArtworkPreference(preference(key = "torrent_library_v1|saved:tmdb-tv:1")))
        assertFalse(torrentLibraryArtworkPreference(preference(name = "local_user_preferences")))
        assertFalse(torrentLibraryArtworkPreference(preference(key = "home2_presentation|poster")))
        assertFalse(torrentLibraryArtworkPreference(preference(marked = false)))
        assertFalse(torrentLibraryArtworkPreference("malformed"))
    }
}
