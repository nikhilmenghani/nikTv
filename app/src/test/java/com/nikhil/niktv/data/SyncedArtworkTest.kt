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
}
