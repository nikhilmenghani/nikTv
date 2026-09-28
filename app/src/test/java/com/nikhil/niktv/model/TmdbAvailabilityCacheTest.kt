package com.nikhil.niktv.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TmdbAvailabilityCacheTest {
    @Test fun `empty results remain fresh until the refresh interval expires`() {
        val entry = TmdbAvailabilityEntry(10_000L, emptyList(), matchingVersion = 4)
        assertTrue(entry.isFresh(69_999L, 60_000L))
        assertFalse(entry.isFresh(70_000L, 60_000L))
        assertFalse(entry.isFresh(9_000L, 60_000L))
    }

    @Test fun `persisted positive and negative entries retain their timestamps and candidates`() {
        val cache = TmdbAvailabilityCache(mapOf(
            1 to TmdbAvailabilityEntry(10L, listOf(MediaItem("provider-id", "Movie", null, null))),
            2 to TmdbAvailabilityEntry(20L, emptyList())))
        val restored = Json.decodeFromString<TmdbAvailabilityCache>(Json.encodeToString(cache))
        assertEquals(cache, restored)
    }
}
