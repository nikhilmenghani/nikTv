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

    @Test fun inspectorCountsDeduplicateBucketsAndExcludeLatestTombstones() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        repository.saveSearch(SearchCatalogCache(profile.cacheKey(), type, 100, listOf(movie("10"), movie("20"))))
        assertEquals(2, db.catalog().storedCounts(profile.cacheKey()).first().single().count)
        repository.reconcileCategory(profile.cacheKey(), type, "1", setOf("20"), 300)
        assertEquals(1, db.catalog().storedCounts(profile.cacheKey()).first().single().count)
        assertEquals(listOf("20"), db.catalog().storedPage(profile.cacheKey(), type.name, 50, 0).map { it.id })
        assertTrue(db.catalog().storedPage("other", type.name, 50, 0).isEmpty())
        assertTrue(db.catalog().storedPage(profile.cacheKey(), type.name, 50, 50).isEmpty())
    }

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
        val favorite = FavoriteItem(FavoriteKind.MOVIE, movie("personal-favorite"), addedAtMillis = 100L, profileKey = profile.cacheKey())
        val recent = RecentItem(FavoriteKind.MOVIE, movie("personal-history"), playedAtMillis = 100L, profileKey = profile.cacheKey())
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

    @Test fun checkpointRestoreIsAtomicAndSearchableWithoutScanning() = runBlocking {
        repository.saveBrowse(cache("10"))
        val id = SearchMetadataDocuments.anonymousProfileId(profile)
        val snapshots = listOf(CatalogType.MOVIES, CatalogType.LIVE_TV, CatalogType.SERIES).map { repository.snapshot(profile, it) }
        assertEquals(3, snapshots.size)
        assertEquals(2, snapshots.count { it.items.isEmpty() })
        val checkpoint = CatalogCheckpoint(profileId = id, createdAt = 100, scanCompletedAt = 90, snapshots = snapshots)
        repository.clear()
        repository.mergeCheckpoint(profile, checkpoint)
        assertEquals(listOf("10"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
        repository.clear()
        val corruptSeries = snapshots.last().copy(items = listOf(snapshots.first().items.first().copy(type = CatalogType.SERIES.name, payload = "invalid")))
        try {
            repository.mergeCheckpoint(profile, checkpoint.copy(snapshots = snapshots.dropLast(1) + corruptSeries))
            fail("Malformed checkpoint must fail")
        } catch (_: IllegalArgumentException) { }
        assertNull(repository.media(profile.cacheKey(), type, "10"))
    }

    @Test fun scanRestartRetainsSearchableItemsButResetsPaginationOnlyForSelectedProfile() = runBlocking {
        repository.saveBrowse(cache("10"))
        repository.restartScan(profile, type)
        val browse = repository.browse(profile.cacheKey(), type)!!
        assertEquals(0, browse.pagesByCategory["1"])
        assertEquals(true, browse.hasMoreByCategory["1"])
        assertEquals("10", repository.search(profile.cacheKey(), type)!!.items.single().id)
    }

    @Test fun compactSearchIndexDoesNotDuplicateFullPayloadRows() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        repository.saveSearch(SearchCatalogCache(profile.cacheKey(), type, 200,
            listOf(movie("10"), movie("20"))))

        val snapshot = repository.snapshot(profile, type)
        assertEquals(2, snapshot.items.size)
        assertTrue(snapshot.items.none { it.bucket == "@search" })
        assertEquals(listOf("20"), repository.searchIndex(profile.cacheKey(), type, "movie 20").map { it.id })
        assertEquals(2, db.catalog().searchRowCount(profile.cacheKey(), type.name))
    }

    @Test fun backupSnapshotPartsPageCanonicalRowsWithoutChangingRestoreFormat() = runBlocking {
        repository.saveBrowse(cache("10", "20", "30", "40", "50"))
        val plan = repository.snapshotPlan(profile, type)
        val first = repository.snapshotPart(profile, plan, 0, itemsPerPart = 2, episodesPerPart = 2)
        val second = repository.snapshotPart(profile, plan, 1, itemsPerPart = 2, episodesPerPart = 2)
        val third = repository.snapshotPart(profile, plan, 2, itemsPerPart = 2, episodesPerPart = 2)

        assertEquals(5, plan.itemCount)
        assertEquals(listOf("10", "20"), first.items.map { it.id })
        assertEquals(listOf("30", "40"), second.items.map { it.id })
        assertEquals(listOf("50"), third.items.map { it.id })
        assertTrue(first.buckets.isNotEmpty())
        assertTrue(second.buckets.isEmpty())
        assertTrue((first.items + second.items + third.items).all { it.profile.isEmpty() && it.bucket != "@search" })
    }

    @Test fun incrementalPageUpdatesCompactSearchMetadata() = runBlocking {
        val category = Category("1", "Movies", type)
        repository.saveBrowsePage(profile.cacheKey(), type, listOf(category), category, 1,
            listOf(movie("77").copy(title = "A Very Specific Film")), hasMore = true, observedAt = 300)

        val result = repository.searchIndex(profile.cacheKey(), type, "specific")
        assertEquals(listOf("77"), result.map { it.id })
        assertEquals("A Very Specific Film", result.single().title)
    }

    @Test fun missingCompactIndexRebuildsFromCanonicalRowsWithoutProviderAccess() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        db.catalog().clearSearchRows(profile.cacheKey(), type.name)
        repository.saveSearch(SearchCatalogCache(profile.cacheKey(), type, 100, listOf(movie("10"))))
        assertEquals(1, db.catalog().searchRowCount(profile.cacheKey(), type.name))

        repository.rebuildMissingSearchIndexes()

        assertEquals(2, db.catalog().searchRowCount(profile.cacheKey(), type.name))
        assertEquals(listOf("10"), repository.searchIndex(profile.cacheKey(), type, "movie 10").map { it.id })
    }

    @Test fun compactIndexRepairRetiresEqualCountOrphanBeforeRebuilding() = runBlocking {
        repository.saveBrowse(cache("10"))
        db.catalog().clearSearchRows(profile.cacheKey(), type.name)
        db.catalog().putSearchRows(listOf(CatalogSearchRow(
            profile = profile.cacheKey(),
            type = type.name,
            id = "stale",
            title = "Stale movie",
            normalizedTitle = "stale movie",
            categoryId = "1",
            externalTmdbId = null,
            channelNumber = null,
            observedAt = 200
        )))

        assertTrue(repository.searchIndex(profile.cacheKey(), type, "stale").isEmpty())
        assertEquals(listOf("10"), repository.searchIndex(profile.cacheKey(), type, "movie 10").map { it.id })
    }

    @Test fun incrementalPageCommitPreservesEarlierRowsAndAdvancesOnlyItsBucket() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        val categories = listOf(Category("1", "Movies", type), Category("2", "Other", type))
        repository.saveBrowsePage(profile.cacheKey(), type, categories, categories.first(), 2,
            listOf(movie("30")), hasMore = true, observedAt = 200)

        val browse = repository.browse(profile.cacheKey(), type)!!
        assertEquals(setOf("10", "20", "30"), browse.itemsByCategory["1"]!!.map { it.id }.toSet())
        assertEquals(2, browse.pagesByCategory["1"])
        assertEquals(true, browse.hasMoreByCategory["1"])
        assertTrue(repository.snapshot(profile, type).buckets.any { it.bucket == "2" && it.page == 0 })
    }

    @Test fun restoredPageCursorBeatsNewerLocalResetAndResumesMovies() = runBlocking {
        repository.saveBrowse(BrowseCatalogCache(profile.cacheKey(), CatalogType.LIVE_TV, 100,
            listOf(Category("*", "All", CatalogType.LIVE_TV), Category("news", "News", CatalogType.LIVE_TV)),
            mapOf("*" to listOf(movie("1"))), mapOf("*" to 20, "news" to 0),
            mapOf("*" to false, "news" to true)))
        repository.saveBrowse(BrowseCatalogCache(profile.cacheKey(), CatalogType.MOVIES, 100,
            listOf(Category("movies", "Movies", CatalogType.MOVIES)), mapOf("movies" to listOf(movie("2"))),
            mapOf("movies" to 750), mapOf("movies" to true)))
        val restoredMovies = repository.snapshot(profile, CatalogType.MOVIES)

        repository.restartScan(profile, CatalogType.MOVIES)
        assertEquals(0, repository.browse(profile.cacheKey(), CatalogType.MOVIES)!!.pagesByCategory["movies"])
        repository.mergeSnapshot(profile, restoredMovies)

        assertEquals(750, repository.browse(profile.cacheKey(), CatalogType.MOVIES)!!.pagesByCategory["movies"])
        assertEquals(1, repository.resumeScanIndex(profile))
    }

    @Test fun snapshotMergeInvalidatesProviderBaselineAndUpdateVerdict() = runBlocking {
        repository.saveBrowse(cache("10", "20"))
        assertTrue(repository.promoteProviderBaseline(profile, type, completedAt = 150L))
        repository.saveUpdateStatus(CatalogTypeUpdateRow(
            profile = profile.cacheKey(),
            type = type.name,
            state = CatalogUpdateState.UPDATE_AVAILABLE.name,
            detail = "2 new provider pages available.",
            newPages = 2,
            newPagesExact = true,
            checkedAt = 200L
        ))
        val restored = repository.snapshot(profile, type)

        repository.mergeSnapshot(profile, restored)

        assertTrue(repository.providerBaselines(profile, type).isEmpty())
        assertNull(repository.updateStatus(profile, type))
    }

    @Test fun newerSnapshotTombstoneRetiresExistingCompactSearchRow() = runBlocking {
        repository.saveBrowse(cache("10", at = 100))
        val snapshot = repository.snapshot(profile, type)
        val remote = snapshot.copy(items = snapshot.items.map {
            it.copy(deleted = true, observedAt = 300)
        })

        repository.mergeSnapshot(profile, remote)

        assertTrue(repository.searchIndex(profile.cacheKey(), type, "movie 10").isEmpty())
        assertNull(repository.media(profile.cacheKey(), type, "10"))
    }

    @Test fun removedBucketDoesNotDeleteSharedIdFromCompactSearch() = runBlocking {
        val action = Category("action", "Action", type)
        val drama = Category("drama", "Drama", type)
        val sharedInAction = movie("shared").copy(portalCategoryId = action.id)
        val sharedInDrama = movie("shared").copy(portalCategoryId = drama.id)
        repository.saveBrowse(BrowseCatalogCache(
            profileKey = profile.cacheKey(),
            type = type,
            cachedAtMillis = 100L,
            categories = listOf(action, drama),
            itemsByCategory = mapOf(action.id to listOf(sharedInAction), drama.id to listOf(sharedInDrama)),
            pagesByCategory = mapOf(action.id to 1, drama.id to 1),
            hasMoreByCategory = mapOf(action.id to false, drama.id to false)
        ))
        repository.saveBrowsePage(
            profile = profile.cacheKey(),
            type = type,
            categories = listOf(action),
            category = action,
            page = 1,
            items = listOf(sharedInAction),
            hasMore = false,
            observedAt = 300L
        )

        repository.reconcileSuccessfulRefresh(
            profile = profile,
            type = type,
            providerCategories = listOf(action),
            startedAt = 200L,
            at = 400L
        )

        assertEquals(listOf("shared"), repository.searchIndex(profile.cacheKey(), type, "shared").map { it.id })
        assertEquals(listOf(action.id), repository.snapshot(profile, type).buckets.map { it.bucket })
    }

    @Test fun tmdbSeriesMatchingFindsPersistedTitleWithoutVisibleBrowseItems() = runBlocking {
        val item = movie("463114").copy(title = "India's Got Latent (Hindi)")
        repository.saveBrowse(BrowseCatalogCache(profile.cacheKey(), CatalogType.SERIES, 100,
            listOf(Category("1", "Series", CatalogType.SERIES)), mapOf("1" to listOf(item)),
            mapOf("1" to 1), mapOf("1" to false)))
        val tmdb = TmdbSeries(123, "India's Got Latent", "India's Got Latent", null, null, null, "2024-01-01", null)
        val persisted = repository.search(profile.cacheKey(), CatalogType.SERIES)!!.items
        assertEquals("463114", rankTmdbSeriesMatches(tmdb, persisted).single().id)
    }

    @Test fun completedRefreshRetiresUnseenRowsAndRemovedCategories() = runBlocking {
        val retained = movie("10")
        val removedFromActiveCategory = movie("20")
        val removedCategoryItem = movie("30").copy(portalCategoryId = "2")
        val categories = listOf(
            Category("1", "Movies", type),
            Category("2", "Removed", type)
        )
        repository.saveBrowse(BrowseCatalogCache(
            profile.cacheKey(), type, 10_000, categories,
            mapOf("1" to listOf(retained, removedFromActiveCategory), "2" to listOf(removedCategoryItem)),
            mapOf("1" to 1, "2" to 1), mapOf("1" to false, "2" to false)
        ))
        val generation = repository.restartScan(profile, type)

        repository.saveBrowsePage(
            profile.cacheKey(), type, listOf(categories.first()), categories.first(), page = 1,
            items = listOf(retained), hasMore = false, observedAt = 300
        )
        repository.reconcileSuccessfulRefresh(
            profile, type, listOf(categories.first()), startedAt = generation, at = 400
        )

        assertEquals(listOf("10"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
        assertEquals(listOf("1"), repository.snapshot(profile, type).buckets.map { it.bucket })
        assertNull(repository.media(profile.cacheKey(), type, "20"))
        assertNull(repository.media(profile.cacheKey(), type, "30"))
    }

    @Test fun confirmedEmptyRefreshCreatesCompleteCheckpointAndRetiresOldRows() = runBlocking {
        repository.saveBrowse(cache("10", "20", at = 100))
        val generation = repository.restartScan(profile, type)

        repository.reconcileSuccessfulRefresh(
            profile = profile,
            type = type,
            providerCategories = emptyList(),
            startedAt = generation,
            confirmedEmpty = true,
            at = 300
        )

        val checkpoint = repository.scanCheckpoint(profile, type)
        assertTrue(checkpoint.hasData)
        assertTrue(checkpoint.complete)
        assertNull(repository.browse(profile.cacheKey(), type))
        assertTrue(repository.searchIndex(profile.cacheKey(), type, "movie").isEmpty())
        assertEquals(listOf(CatalogRepository.EMPTY_BUCKET),
            repository.snapshot(profile, type).buckets.map { it.bucket })
        assertTrue(repository.promoteProviderBaseline(profile, type, completedAt = 400))
    }

    @Test fun markerlessConfirmedEmptyCreatesCheckpointOnlyWhenNoLiveRowsExist() = runBlocking {
        assertTrue(repository.reconcileSuccessfulRefresh(
            profile = profile,
            type = type,
            providerCategories = emptyList(),
            startedAt = 0,
            confirmedEmpty = true,
            at = 300
        ))
        assertTrue(repository.scanCheckpoint(profile, type).complete)

        repository.saveBrowse(cache("10", at = 400))
        assertFalse(repository.reconcileSuccessfulRefresh(
            profile = profile,
            type = type,
            providerCategories = emptyList(),
            startedAt = 0,
            confirmedEmpty = true,
            at = 500
        ))
        assertEquals(listOf("10"), repository.search(profile.cacheKey(), type)!!.items.map { it.id })
    }

    @Test fun markerlessResumeReconcilesRemovedCategoriesWithoutRetiringActiveRows() = runBlocking {
        val active = Category("1", "Movies", type)
        val removed = Category("2", "Removed", type)
        repository.saveBrowse(BrowseCatalogCache(
            profile.cacheKey(), type, 100,
            listOf(active, removed),
            mapOf(
                active.id to listOf(movie("10"), movie("20")),
                removed.id to listOf(movie("30").copy(portalCategoryId = removed.id))
            ),
            mapOf(active.id to 1, removed.id to 1),
            mapOf(active.id to false, removed.id to true)
        ))

        assertTrue(repository.reconcileSuccessfulRefresh(
            profile = profile,
            type = type,
            providerCategories = listOf(active),
            startedAt = 0,
            at = 300
        ))

        assertEquals(setOf("10", "20"), repository.search(profile.cacheKey(), type)!!.items.map { it.id }.toSet())
        assertEquals(listOf(active.id), repository.snapshot(profile, type).buckets.map { it.bucket })
        assertTrue(repository.scanCheckpoint(profile, type).complete)
    }

    @Test fun baselinePromotionIsPerTypeAndDoesNotChangeCheckpoint() = runBlocking {
        repository.saveBrowse(cache("10", "20", at = 100))
        val before = repository.scanCheckpoint(profile, type)

        assertTrue(repository.promoteProviderBaseline(profile, type, completedAt = 200))
        repository.markTypeSynced(profile, type, at = 250)

        assertEquals(before, repository.scanCheckpoint(profile, type))
        val baseline = repository.providerBaselines(profile, type).single()
        assertEquals("1", baseline.category)
        assertEquals(1, baseline.lastPage)
        assertEquals(0, baseline.totalItems)
        assertEquals(250L, baseline.completedAt)
        val status = repository.updateStatus(profile, type)!!
        assertEquals(CatalogUpdateState.NO_CHANGES, status.updateState)
        assertEquals(250L, status.checkedAt)
        assertTrue(repository.providerBaselines(profile, CatalogType.SERIES).isEmpty())
    }
}
