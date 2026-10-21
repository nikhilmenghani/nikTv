package com.nikhil.niktv.discovery

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DiscoverySeasonCountsTest {
    @Test fun oldDetailsRemainReadableWhenCountsAreAbsent() {
        val old = """{"card":{"id":1668,"series":true,"title":"Friends"},"seasons":[1,2]}"""
        val details = Json.decodeFromString(DiscoveryDetails.serializer(), old)
        assertEquals(listOf(1, 2), details.seasons)
        assertTrue(details.seasonEpisodeCounts.isEmpty())
    }
    @Test fun countsSurviveThePublicDetailsCacheWithoutMixingSeasonKeys() {
        val details = DiscoveryDetails(DiscoveryCard(1668, true, "Friends"), seasons = listOf(0, 1, 9),
            seasonEpisodeCounts = mapOf(0 to 0, 1 to 24, 9 to 23))
        val saved = Json.encodeToString(DiscoveryDetails.serializer(), details)
        assertEquals(details, Json.decodeFromString(DiscoveryDetails.serializer(), saved))
    }
}
