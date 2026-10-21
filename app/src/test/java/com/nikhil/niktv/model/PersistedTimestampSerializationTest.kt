@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nikhil.niktv.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Assert.*
import org.junit.Test

class PersistedTimestampSerializationTest {
    private val media = MediaItem("fixture", "Fixture title", null, null)
    private val profile = PortalProfile("Fixture", "https://provider.invalid", "fixture-id")
    private val timestamps = setOf("searchedAtMillis", "cachedAtMillis", "addedAtMillis", "playedAtMillis",
        "updatedAtMillis", "resolvedAtMillis", "queuedAtMillis", "authenticatedAtMillis")

    /** A clock-dependent default must bypass omission decisions, regardless of clock speed. */
    private inner class TimestampEncoder : AbstractEncoder() {
        override val serializersModule = EmptySerializersModule()
        val written = mutableSetOf<String>()
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            written += descriptor.getElementName(index)
            return true
        }
        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean {
            val field = descriptor.getElementName(index)
            assertFalse("$field must always serialize; comparing it with a newly read clock can lose its original value", field in timestamps)
            return false
        }
        override fun encodeValue(value: Any) = Unit
        override fun encodeNull() = Unit
    }

    private fun <T> assertTimestamp(serializer: KSerializer<T>, value: T, field: String, expected: Long) {
        val encoder = TimestampEncoder()
        serializer.serialize(encoder, value)
        assertTrue("$field must be written even when default omission is enabled", field in encoder.written)
        val encoded = Json.encodeToString(serializer, value)
        assertEquals(expected, Json.parseToJsonElement(encoded).jsonObject.getValue(field).jsonPrimitive.long)
        assertEquals(value, Json.decodeFromString(serializer, encoded))
    }

    @Test fun allClockDependentModelDefaultsArePersistedWithoutClockRaces() {
        RecentSearch("fixture", SearchContentType.MOVIES).let {
            assertTimestamp(RecentSearch.serializer(), it, "searchedAtMillis", it.searchedAtMillis)
        }
        SearchResultCache("profile", SearchContentType.MOVIES, "fixture", lastPage = 1, hasMore = false, items = listOf(media)).let {
            assertTimestamp(SearchResultCache.serializer(), it, "cachedAtMillis", it.cachedAtMillis)
        }
        TmdbIptvMapping("profile", CatalogType.MOVIES, 1, media).let {
            assertTimestamp(TmdbIptvMapping.serializer(), it, "cachedAtMillis", it.cachedAtMillis)
        }
        FavoriteItem(FavoriteKind.MOVIE, media).let {
            assertTimestamp(FavoriteItem.serializer(), it, "addedAtMillis", it.addedAtMillis)
        }
        EpisodeSeasonCache("profile", "series", 1, listOf(1), listOf(media)).let {
            assertTimestamp(EpisodeSeasonCache.serializer(), it, "cachedAtMillis", it.cachedAtMillis)
        }
        RecentItem(FavoriteKind.MOVIE, media).let {
            assertTimestamp(RecentItem.serializer(), it, "playedAtMillis", it.playedAtMillis)
        }
        PlaybackProgress("profile|fixture", 10, 100).let {
            assertTimestamp(PlaybackProgress.serializer(), it, "updatedAtMillis", it.updatedAtMillis)
        }
        PlaybackUrl("profile|fixture", "https://media.invalid").let {
            assertTimestamp(PlaybackUrl.serializer(), it, "resolvedAtMillis", it.resolvedAtMillis)
        }
        OfflineMediaDownload(profileKey = "profile", catalogType = CatalogType.MOVIES, media = media).let {
            assertTimestamp(OfflineMediaDownload.serializer(), it, "queuedAtMillis", it.queuedAtMillis)
        }
        PortalSession(profile, "fixture-token", "https://provider.invalid/api", "", "", "").let {
            assertTimestamp(PortalSession.serializer(), it, "authenticatedAtMillis", it.authenticatedAtMillis)
        }
    }

    @Test fun timestampPersistenceDoesNotEnableUnrelatedDefaultFields() {
        val favorite = Json.parseToJsonElement(Json.encodeToString(FavoriteItem.serializer(), FavoriteItem(FavoriteKind.MOVIE, media))).jsonObject
        assertTrue("addedAtMillis" in favorite)
        assertFalse("series" in favorite)
        assertFalse("source" in favorite)
        assertFalse("profileKey" in favorite)
        val item = favorite.getValue("media").jsonObject
        assertFalse("description" in item)
        assertFalse("seasonNumber" in item)
        assertFalse("episodeNumber" in item)
        assertFalse("liveProgramme" in item)
        assertFalse("liveSchedule" in item)
        assertFalse("cast" in item)
    }

    @Test fun legacyRecordsWithOmittedTimestampsStillDecodeUsingTheirExistingDefaults() {
        val favorite = Json.parseToJsonElement(Json.encodeToString(FavoriteItem.serializer(), FavoriteItem(FavoriteKind.MOVIE, media))).jsonObject
        val legacy = kotlinx.serialization.json.JsonObject(favorite - "addedAtMillis").toString()
        val before = System.currentTimeMillis()
        val restored = Json.decodeFromString(FavoriteItem.serializer(), legacy)
        assertTrue(restored.addedAtMillis in before..System.currentTimeMillis())
        assertEquals(media, restored.media)
        assertEquals(FavoriteKind.MOVIE, restored.kind)
    }
}
