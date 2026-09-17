package com.nikhil.niktv.data

import android.app.Application
import androidx.room.Room
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CatalogRepositoryTest {
    private lateinit var db: CatalogDatabase
    private lateinit var repository: CatalogRepository
    private val profile = PortalProfile("Test", "https://example.test", "00:11:22:33:44:55")
    private val type = CatalogType.MOVIES
    private fun movie(id: String) = MediaItem(id, "Movie $id", null, "/media/$id", portalCategoryId = "1")
    private fun cache(vararg ids: String, at: Long = 100) = BrowseCatalogCache(profile.cacheKey(), type, at,
        listOf(Category("1", "Movies", type)), mapOf("1" to ids.map(::movie)), mapOf("1" to 1), mapOf("1" to false))

    @Before fun before() {
        val context = RuntimeEnvironment.getApplication()
        CatalogDiskCache.clear(context)
        db = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
        repository = CatalogRepository(context, db)
    }
    @After fun after() { db.close() }

    @Test fun legacyCatalogIsImportedOnceAndRemainsAfterLegacyFileRemoval() = runBlocking {
        CatalogDiskCache.write(RuntimeEnvironment.getApplication(), "browse:${profile.cacheKey()}:${type.name}", cache("10"))
        assertEquals("10", repository.browse(profile.cacheKey(), type)!!.itemsByCategory["1"]!!.single().id)
        CatalogDiskCache.clear(RuntimeEnvironment.getApplication())
        assertEquals("10", repository.media(profile.cacheKey(), type, "10")!!.id)
        assertNull(repository.media("different-profile", type, "10"))
        assertNull(repository.media(profile.cacheKey(), CatalogType.LIVE_TV, "10"))
    }

    @Test fun twoDeviceSnapshotsMergeAndTombstonesPreventOldBackupsRestoringRemovedItems() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        val old = repository.snapshot(profile, type)
        repository.saveBrowse(cache("30", at = 200))
        repository.mergeSnapshot(profile, old)
        assertEquals(setOf("10", "20", "30"), repository.search(profile.cacheKey(), type)!!.items.map { it.id }.toSet())
        repository.reconcileCategory(profile.cacheKey(), type, "1", setOf("30"), 300)
        repository.mergeSnapshot(profile, old)
        assertEquals(listOf("30"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
        repository.saveBrowse(cache("10", "20", at = 100))
        assertEquals(listOf("30"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
    }

    @Test fun snapshotImportRebindsEpisodeProfileAndPreservesProviderFields() = runBlocking {
        val episode = movie("610:2:3").copy(portalSeasonId = "620", portalEpisodeId = "630", episodeNumber = 3)
        repository.saveEpisodes(EpisodeSeasonCache(profile.cacheKey(), "610", 2, listOf(2), listOf(episode)))
        val snapshot = repository.snapshot(profile, CatalogType.SERIES)
        assertEquals("", snapshot.episodes.single().profile)
        repository.clear()
        repository.mergeSnapshot(profile, snapshot)
        val restored = repository.snapshot(profile, CatalogType.SERIES)
        assertEquals(snapshot.episodes, restored.episodes)
    }

    @Test fun mergeIsCommutativeAndIdempotent() = runBlocking {
        repository.saveBrowse(cache("10", at = 100))
        val a = repository.snapshot(profile, type)
        repository.saveBrowse(cache("20", at = 200))
        val b = repository.snapshot(profile, type)
        assertEquals(mergeCatalogSnapshots(a, b), mergeCatalogSnapshots(b, a))
        val merged = mergeCatalogSnapshots(a, b)
        assertEquals(merged, mergeCatalogSnapshots(merged, merged))
    }

    @Test fun backupIsOptInAndXtreamPlaybackUsesCurrentCredentials() {
        assertFalse(CatalogPreferences.backupEnabled(RuntimeEnvironment.getApplication()))
        val current = profile.copy(portalType = PortalType.XTREAM, username = "new-user", password = "new pass")
        val stale = movie("4502").copy(command = "https://old.test/movie/old-user/old-pass/4502.mkv")
        assertEquals("https://example.test/movie/new-user/new%20pass/4502.mkv", xtreamPlaybackUrl(current, stale, type))
        assertEquals("https://example.test/live/new-user/new%20pass/4502.ts", xtreamPlaybackUrl(current, stale, CatalogType.LIVE_TV))
    }

    @Test fun completeAllCategoryServesRealCategoriesLocally() = runBlocking {
        repository.saveBrowse(BrowseCatalogCache(profile.cacheKey(), type, 100,
            listOf(Category("*", "All", type), Category("1", "Movies", type), Category("2", "Empty", type)),
            mapOf("*" to listOf(movie("10"))), mapOf("*" to 12), mapOf("*" to false)))
        val result = repository.browse(profile.cacheKey(), type)!!
        assertEquals("10", result.itemsByCategory["1"]!!.single().id)
        assertEquals(emptyList<MediaItem>(), result.itemsByCategory["2"])
        assertEquals(false, result.hasMoreByCategory["1"])
    }

    @Test fun newerPartialSeasonRetainsOtherDevicesEpisodes() = runBlocking {
        repository.saveEpisodes(EpisodeSeasonCache(profile.cacheKey(), "610", 2, listOf(2), listOf(movie("1")), cachedAtMillis = 100))
        val a = repository.snapshot(profile, CatalogType.SERIES)
        repository.saveEpisodes(EpisodeSeasonCache(profile.cacheKey(), "610", 2, listOf(2), listOf(movie("2")), cachedAtMillis = 200))
        val b = repository.snapshot(profile, CatalogType.SERIES)
        val merged = mergeCatalogSnapshots(a, b)
        assertEquals(merged, mergeCatalogSnapshots(b, a))
        assertEquals(merged, mergeCatalogSnapshots(merged, merged))
        val episodeCache = kotlinx.serialization.json.Json.decodeFromString<EpisodeSeasonCache>(merged.episodes.single().payload)
        assertEquals(setOf("1", "2"), episodeCache.episodes.map { it.id }.toSet())
    }

    @Test fun anotherProfilesSnapshotCannotBeImported() = runBlocking {
        repository.saveBrowse(cache("10"))
        val snapshot = repository.snapshot(profile, type).copy(profileId = "another-account")
        try {
            repository.mergeSnapshot(profile, snapshot)
            fail("Cross-profile import must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf("10"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
    }

    @Test fun catalogRestoreLeavesPersonalDataLocalWhileExportsIncludeIt() = runBlocking {
        val store = ProfileStore(RuntimeEnvironment.getApplication())
        val favorite = FavoriteItem(FavoriteKind.MOVIE, movie("personal-favorite"), profileKey = profile.cacheKey())
        val recent = RecentItem(FavoriteKind.MOVIE, movie("personal-history"), profileKey = profile.cacheKey())
        store.saveFavorites(listOf(favorite))
        store.saveRecentlyPlayed(listOf(recent))
        repository.saveBrowse(cache("10"))
        val snapshot = repository.snapshot(profile, type)
        val serialized = Json.encodeToString(snapshot)
        assertFalse(serialized.contains("personal-favorite"))
        assertFalse(serialized.contains("personal-history"))

        repository.clear()
        repository.mergeSnapshot(profile, snapshot)
        assertEquals(listOf(favorite), store.favorites.first())
        assertEquals(listOf(recent), store.recentlyPlayed.first())
        assertEquals("10", repository.browse(profile.cacheKey(), type)!!.itemsByCategory["1"]!!.single().id)

        val exported = Json.parseToJsonElement(store.exportBackup()).jsonObject["strings"]!!.jsonObject
        assertEquals(listOf(favorite), Json.decodeFromString<List<FavoriteItem>>(exported["favorites"]!!.jsonPrimitive.content))
        assertEquals(listOf(recent), Json.decodeFromString<List<RecentItem>>(exported["recently_played"]!!.jsonPrimitive.content))
    }
}
