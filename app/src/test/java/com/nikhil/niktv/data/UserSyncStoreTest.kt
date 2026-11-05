package com.nikhil.niktv.data

import android.app.Application
import androidx.room.withTransaction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class UserSyncStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)

    @Test fun protectedChannelHistoryAndProgressStayLocalThroughMerge(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val owner = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(owner.profileId)
        val portal = PortalProfile("Privacy fixture", "https://privacy.invalid", "")
        users.associatePortal(owner, portal)
        ParentalControls.rememberCategories(context, portal.cacheKey(), CatalogType.LIVE_TV,
            listOf(Category("protected", "Protected category", CatalogType.LIVE_TV, isAdult = true)))
        val restricted = MediaItem("restricted", "Channel A", null, null, portalCategoryId = "protected")
        val regular = MediaItem("regular", "Channel B", null, null)
        val store = ProfileStore(context, memoryPreferences())
        val key = providerProgressKey(portal.cacheKey(), restricted, CatalogType.LIVE_TV, null)
        users.rememberPrivateViewing(owner, RecentItem(FavoriteKind.CHANNEL, restricted, profileKey = portal.cacheKey()))
        store.savePlaybackProgress(listOf(PlaybackProgress(key, 120_000, 600_000)))
        assertFalse(users.snapshot("101").any { it.collection == "progress" })
        store.saveRecentlyPlayed(listOf(RecentItem(FavoriteKind.CHANNEL, restricted, profileKey = portal.cacheKey()),
            RecentItem(FavoriteKind.CHANNEL, regular, profileKey = portal.cacheKey())))
        store.savePlaybackProgress(listOf(PlaybackProgress(key, 120_000, 600_000)))
        val wire = users.snapshot("101")
        assertFalse(wire.any { it.payload.contains("Channel A") || it.payload.contains("item:restricted") })
        assertTrue(wire.any { it.payload.contains("Channel B") })
        assertFalse(users.pending("101").any { it.payload.contains("Channel A") || it.payload.contains("item:restricted") })
        assertFalse(store.exportBackup().contains("Channel A"))
        users.merge("101", wire)
        assertEquals(setOf("restricted", "regular"), store.recentlyPlayed.first().map { it.media.id }.toSet())
        assertEquals(120_000L, store.playbackProgress.first().single().positionMillis)
    }

    @Test fun oldRemoteProtectedHistoryIsRedactedInsteadOfImported(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val owner = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(owner.profileId)
        val restricted = RecentItem(FavoriteKind.CHANNEL, MediaItem("old-private", "Old private channel", null, null, isAdult = true), profileKey = "portal:fixture")
        val payload = "{\"preference\":\"recently_played\",\"entry\":${Json.encodeToString(restricted)}}"
        val old = SyncRecord("101", owner.profileId, "history", "recently_played:portal:fixture|CHANNEL|old-private",
            payload, 100, "other", "old-upload")
        users.merge("101", listOf(old))
        val redacted = users.snapshot("101").single { it.recordId == old.recordId }
        assertTrue(redacted.deleted)
        assertEquals("{}", redacted.payload)
        assertTrue(redacted.generation > old.generation)
        assertTrue(ProfileStore(context, memoryPreferences()).recentlyPlayed.first().isEmpty())
        users.merge("101", listOf(old))
        assertTrue(users.snapshot("101").single { it.recordId == old.recordId }.deleted)
    }

    @Test fun pendingViewingWinsConcurrentUploadButCachedViewingAndDeletesDoNot(): Unit = runBlocking {
        val owner = UserScope("101", "viewer")
        users.db.withTransaction {
            users.mutate(owner, "history", "series", "{\"episode\":2}")
        }
        val local = users.snapshot("101").single()
        val remote = local.copy(payload = "{\"episode\":3}", revision = 100, deviceId = "other", mutationId = "other-start")
        users.merge("101", listOf(remote))
        val winner = users.snapshot("101").single()
        assertEquals(local.payload, winner.payload)
        assertTrue(winner.revision > remote.revision)
        assertTrue(users.pending("101").any { it.mutationId == winner.mutationId })
        users.acknowledge("101", users.pending("101").map { it.mutationId })
        val later = remote.copy(revision = winner.revision + 1, mutationId = "later")
        users.merge("101", listOf(later))
        assertEquals(later, users.snapshot("101").single())
        users.db.withTransaction { users.mutate(owner, "history", "series", local.payload) }
        val deleted = later.copy(deleted = true, generation = 1, revision = 500, mutationId = "deleted")
        users.merge("101", listOf(deleted))
        assertEquals(deleted, users.snapshot("101").single())
    }

    @Before fun reset(): Unit = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        context.getSharedPreferences("niktv_legacy_assignment", 0).edit().clear().commit()
        Unit
    }

    @Test fun defaultsAreStableAndDeletedDefaultsAreNeverRecreated(): Unit = runBlocking {
        users.selectAccount("101")
        coroutineScope { repeat(2) { launch { users.initializeDefaults("101") } } }
        val initial = users.profiles.first()
        assertEquals(setOf("Family", "Kids"), initial.map { it.name }.toSet())
        assertEquals(2, initial.size)
        val family = initial.single { it.type == UserProfileType.GENERAL }
        assertEquals(UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL), family.id)
        users.updateProfile(family.copy(name = "Parents"))
        assertEquals(UserProfileType.GENERAL, users.profiles.first().single { it.id == family.id }.type)
        users.deleteProfile(family.id)
        users.initializeDefaults("101")
        assertEquals(listOf("Kids"), users.profiles.first().map { it.name })
        assertTrue(users.snapshot("101").any { it.recordId == family.id && it.deleted })
    }

    @Test fun mergeIsDeterministicIdempotentAndDeletionBeatsOlderDevices(): Unit = runBlocking {
        val owner = UserScope("101", "profile")
        val old = SyncRecord(owner.accountId, owner.profileId, "history", "movie:5", "{}", 2, "device-a", "a")
        val deletion = old.copy(revision = 3, deviceId = "device-b", mutationId = "delete", deleted = true, generation = 1)
        users.merge("101", listOf(deletion, old, deletion))
        assertEquals(deletion, users.snapshot("101").single())
        users.merge("101", listOf(old))
        assertEquals(deletion, users.snapshot("101").single())
        val concurrent = deletion.copy(deviceId = "device-c", mutationId = "concurrent")
        assertTrue(concurrent.newerThan(deletion))
        users.merge("101", listOf(concurrent, deletion))
        assertEquals(concurrent, users.snapshot("101").single())
        users.merge("101", listOf(old.copy(revision = 50_000, mutationId = "stale-device-high-counter")))
        assertEquals(concurrent, users.snapshot("101").single())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { users.merge("102", listOf(old)) } }
    }

    @Test fun profileAndAccountIsolationIncludesLateCapturedWritesAndOutbox(): Unit = runBlocking {
        val base = memoryPreferences()
        val store = ProfileStore(context, base)
        users.selectAccount("101"); users.initializeDefaults("101")
        val family = UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL)
        val kids = UserSyncStore.defaultProfileId("101", UserProfileType.KIDS)
        users.selectProfile(family)
        val captured = store.captureScope()!!
        val parentFavorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("5", "Movie", null, null), profileKey = "local")
        store.saveFavorites(listOf(parentFavorite))
        users.selectProfile(kids)
        assertTrue(store.favorites.first().isEmpty())
        store.savePlaybackProgress(listOf(PlaybackProgress("movie:5", 300, 1000)), captured)
        assertTrue(store.playbackProgress.first().isEmpty())
        assertEquals(300L, store.scoped(captured).playbackProgress.first().single().positionMillis)
        users.selectAccount("202"); users.initializeDefaults("202")
        users.selectProfile(UserSyncStore.defaultProfileId("202", UserProfileType.GENERAL))
        assertTrue(store.favorites.first().isEmpty())
        assertEquals(listOf(parentFavorite), store.scoped(captured).favorites.first())
        assertTrue(users.pending("101").any { it.profileId == family && it.collection == "progress" })
        assertFalse(users.pending("202").any { it.collection == "progress" })
    }

    @Test fun migrationImportsIntoFamilyOnceAndKidsRemainsEmpty(): Unit = runBlocking {
        val favorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("legacy", "Legacy", null, null), profileKey = "local")
        val base = memoryPreferences(mutablePreferencesOf(stringPreferencesKey("favorites") to Json.encodeToString(listOf(favorite))))
        val store = ProfileStore(context, base)
        users.selectAccount("101"); users.initializeDefaults("101")
        assertTrue(store.migrateLegacy("101", true))
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val kids = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
        assertEquals(listOf(favorite), store.scoped(family).favorites.first())
        assertTrue(store.scoped(kids).favorites.first().isEmpty())
        val count = users.pending("101").size
        assertFalse(store.migrateLegacy("101", true))
        assertEquals(count, users.pending("101").size)
        users.selectAccount("202"); users.initializeDefaults("202")
        assertFalse(store.migrateLegacy("202", true))
        assertTrue(store.scoped(UserScope("202", UserSyncStore.defaultProfileId("202", UserProfileType.GENERAL))).favorites.first().isEmpty())
    }

    @Test fun existingRemoteProfileRequiresExplicitLegacyAssignment(): Unit = runBlocking {
        val base = memoryPreferences(mutablePreferencesOf(stringPreferencesKey("playback_progress") to "[{\"key\":\"movie:5\",\"positionMillis\":100,\"durationMillis\":1000}]"))
        val store = ProfileStore(context, base)
        users.selectAccount("101")
        val remote = users.createProfile("Existing")
        users.initializeDefaults("101")
        assertEquals(listOf(remote), users.profiles.first())
        assertFalse(store.migrateLegacy("101", false))
        users.selectProfile(remote.id)
        assertTrue(store.playbackProgress.first().isEmpty())
        assertTrue(store.hasLegacyViewingData())
        store.importLegacyToProfile(UserScope("101", remote.id))
        assertEquals(100L, store.playbackProgress.first().single().positionMillis)
    }

    @Test fun secretBearingPortalAndMediaFieldsNeverEnterSyncPayloads(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val owner = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(owner.profileId)
        val portal = PortalProfile("Editable", "https://fixture.invalid", "fixture-mac", password = "fixture-password")
        users.associatePortal(owner, portal)
        val favorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("5", "Movie", "https://fixture.invalid/image?token=fixture-secret", "https://fixture.invalid/play/fixture-password"), profileKey = portal.cacheKey())
        val store = ProfileStore(context, memoryPreferences())
        store.saveFavorites(listOf(favorite))
        val wire = Json.encodeToString(users.snapshot("101"))
        listOf("fixture-password", "fixture-mac", "fixture-secret", "fixture.invalid", "catalog-v5").forEach { assertFalse(it, wire.contains(it)) }
        assertEquals(favorite, store.favorites.first().single())
    }

    @Test fun uploadAcknowledgesOnlyCapturedMutationsAndKeepsChangesMadeDuringUpload(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        users.acknowledge("101", users.pending("101").map { it.mutationId })
        val family = UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL)
        users.selectProfile(family)
        val store = ProfileStore(context, memoryPreferences())
        store.savePlaybackProgress(listOf(PlaybackProgress("movie:5", 100, 1000)))
        val uploaded = users.pending("101").map { it.mutationId }
        store.savePlaybackProgress(listOf(PlaybackProgress("movie:5", 200, 1000)))
        users.acknowledge("101", uploaded)
        assertEquals(1, users.pending("101").size)
        assertEquals(200L, store.playbackProgress.first().single().positionMillis)
        assertFalse(users.pending("101").single().mutationId in uploaded)
    }

    @Test fun historyMergeKeepsUnrelatedDeviceRecordsAndTombstonesOnlyRemovedRecord(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val owner = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(owner.profileId)
        val store = ProfileStore(context, memoryPreferences())
        val local = RecentItem(FavoriteKind.MOVIE, MediaItem("5", "Local", null, null), profileKey = "portal:local")
        store.saveRecentlyPlayed(listOf(local))
        val remote = RecentItem(FavoriteKind.MOVIE, MediaItem("9", "Remote", null, null), profileKey = "portal:remote")
        val payload = "{\"preference\":\"recently_played\",\"entry\":${Json.encodeToString(remote)}}"
        val remoteRecord = SyncRecord("101", owner.profileId, "history", "recently_played:portal:remote|MOVIE|9", payload, 40, "other-device", "remote-1")
        users.merge("101", listOf(remoteRecord))
        assertEquals(setOf("5", "9"), store.recentlyPlayed.first().map { it.media.id }.toSet())
        store.saveRecentlyPlayed(store.recentlyPlayed.first().filterNot { it.media.id == "5" })
        val deleted = users.snapshot("101").single { it.collection == "history" && it.recordId.contains("|5") }
        assertTrue(deleted.deleted)
        users.merge("101", listOf(remoteRecord))
        assertEquals(listOf("9"), store.recentlyPlayed.first().map { it.media.id })
    }

    @Test fun mappingAPortalReprojectsUnmatchedRemoteResumeWithoutTitleMatching(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val owner = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(owner.profileId)
        val portalId = java.util.UUID.randomUUID().toString()
        val remoteProgress = PlaybackProgress("progress-v3|portal:$portalId|MOVIES|movie:5", 600, 1000)
        val payload = "{\"preference\":\"playback_progress\",\"entry\":${Json.encodeToString(remoteProgress)}}"
        users.merge("101", listOf(SyncRecord("101", owner.profileId, "progress", "playback_progress:${remoteProgress.key}", payload, 50, "device-b", "remote-resume")))
        val store = ProfileStore(context, memoryPreferences())
        assertEquals(remoteProgress.key, store.playbackProgress.first().single().key)
        val portal = PortalProfile("A completely different display name", "https://provider.invalid", "local-device-mac")
        users.mapPortal(owner, portalId, portal)
        assertEquals("progress-v3|${portal.cacheKey()}|MOVIES|movie:5", store.playbackProgress.first().single().key)
        assertEquals(600L, store.playbackProgress.first().single().positionMillis)
        assertFalse(Json.encodeToString(users.snapshot("101")).contains("local-device-mac"))
    }

    @Test fun oldBackupRestoreIntoSelectedUserRestoresPortalAssociationAndQueuesPortableViewing(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        users.selectProfile(UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val portal = PortalProfile("Legacy portal", "https://legacy-provider.invalid", "legacy-local-mac")
        val favorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("movie-id", "Old favorite", null, "provider-command"), profileKey = portal.cacheKey())
        val store = ProfileStore(context, memoryPreferences())
        store.importBackup(oldBackup(portal, favorite))
        assertEquals(listOf(portal), store.profiles.first())
        assertEquals(listOf(favorite), store.favorites.first())
        val portable = users.pending("101").single { it.collection == "favorites" }
        assertTrue(portable.payload.contains("portal:"))
        assertFalse(portable.payload.contains("legacy-local-mac"))
        assertFalse(portable.payload.contains("legacy-provider.invalid"))
    }

    @Test fun oldBackupWithoutSelectedUserWaitsForExplicitAssignment(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val portal = PortalProfile("Unassigned", "https://legacy-provider.invalid", "legacy-local-mac")
        val favorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("movie-id", "Old favorite", null, null), profileKey = portal.cacheKey())
        val store = ProfileStore(context, memoryPreferences())
        store.importBackup(oldBackup(portal, favorite))
        assertTrue(store.favorites.first().isEmpty())
        assertFalse(users.pending("101").any { it.collection == "favorites" })
        assertTrue(store.hasLegacyViewingData())
        val kids = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
        users.selectProfile(kids.profileId)
        store.importLegacyToProfile(kids)
        assertEquals(listOf(favorite), store.favorites.first())
        assertEquals(listOf(portal), store.profiles.first())
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        assertTrue(store.scoped(family).favorites.first().isEmpty())
    }

    @Test fun removingOneUsersPortalKeepsAnotherUsersLocalConnection(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val kids = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
        val portal = PortalProfile("Shared device connection", "https://provider.invalid", "local-mac")
        val store = ProfileStore(context, memoryPreferences())
        users.selectProfile(family.profileId)
        store.addProfile(portal)
        users.selectProfile(kids.profileId)
        store.addProfile(portal)
        store.scoped(family).removeProfile(portal)
        assertTrue(store.scoped(family).profiles.first().isEmpty())
        assertEquals(listOf(portal), store.scoped(kids).profiles.first())
        assertTrue(users.hasPortalAssociation(kids, portal))
        assertFalse(users.hasPortalAssociation(family, portal))
    }

    @Test fun oldestUnscopedProgressStaysLocalWhileProviderIdentitySyncs(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        users.selectProfile(UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val portal = PortalProfile("Provider", "https://provider.invalid", "local-mac")
        users.registerPortal("101", portal)
        val legacy = PlaybackProgress("MOVIES:5", 200, 1000)
        val provider = PlaybackProgress("progress-v3|${portal.cacheKey()}|MOVIES|movie:5", 600, 1000)
        val store = ProfileStore(context, memoryPreferences())
        store.savePlaybackProgress(listOf(legacy, provider))
        assertEquals(listOf(legacy, provider), store.playbackProgress.first())
        val synced = users.pending("101").filter { it.collection == "progress" }
        assertEquals(1, synced.size)
        assertTrue(synced.single().recordId.contains("progress-v3|portal:"))
        assertFalse(synced.single().payload.contains("MOVIES:5"))
    }

    @Test fun newlyCreatedPersonStartsEmptyAcrossEveryViewingCollectionAndLocalOfflineState(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val portal = PortalProfile("Shared provider", "https://provider.invalid", "device-local-mac")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val legacyFavorite = FavoriteItem(FavoriteKind.MOVIE, MediaItem("legacy", "Unassigned legacy", null, null))
        val base = memoryPreferences(mutablePreferencesOf(stringPreferencesKey("favorites") to Json.encodeToString(listOf(legacyFavorite))))
        val store = ProfileStore(context, base)
        users.selectProfile(family.profileId)
        populateViewingProfile(store, portal)
        assertFalse(store.recentlyPlayed.first().isEmpty())
        assertFalse(store.offlineDownloads.first().isEmpty())
        assertTrue(users.hasPortalAssociation(family, portal))

        val fresh = users.createProfile("A new person")
        users.selectProfile(fresh.id)
        assertTrue(store.favorites.first().isEmpty())
        assertTrue(store.recentlyPlayed.first().isEmpty())
        assertTrue(store.playbackProgress.first().isEmpty())
        assertTrue(store.watchedSeries.first().isEmpty())
        assertTrue(store.requestedRecommendations.first().isEmpty())
        assertTrue(store.dismissedRecommendations.first().isEmpty())
        assertTrue(store.recommendationProgress.first().isEmpty())
        assertTrue(store.recentSearches.first().isEmpty())
        assertTrue(store.categoryFilters.first().isEmpty())
        assertTrue(store.rememberedSeriesSeasons.first().isEmpty())
        assertTrue(store.browseLayouts.first().isEmpty())
        assertTrue(store.tmdbDashboardSections.first().isEmpty())
        assertEquals(SeriesStartSeason.LAST, store.seriesStartSeason.first())
        assertTrue(store.offlineDownloads.first().isEmpty())
        assertTrue(store.profiles.first().isEmpty())
        assertFalse(users.hasPortalAssociation(UserScope("101", fresh.id), portal))
        assertTrue(UserProfilePreferences.get(context, "home2_presentation").all.isEmpty())
        assertTrue(users.dao.preferences("101", fresh.id).isEmpty())
        assertEquals(listOf("profile"), users.snapshot("101").filter { it.profileId == fresh.id }.map { it.collection })

        users.selectProfile(family.profileId)
        assertFalse(store.favorites.first().isEmpty())
        assertFalse(store.recentlyPlayed.first().isEmpty())
        assertFalse(store.offlineDownloads.first().isEmpty())
        assertEquals(SeriesStartSeason.FIRST, store.seriesStartSeason.first())
        assertEquals("family-layout", UserProfilePreferences.get(context, "home2_presentation").getString("fixture", null))
    }

    @Test fun deletingPersonPreservesOthersRejectsLateCallbacksAndCannotLeakIntoSameNamedReplacement(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val portal = PortalProfile("Shared provider", "https://provider.invalid", "device-local-mac")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val store = ProfileStore(context, memoryPreferences())
        users.selectProfile(family.profileId)
        populateViewingProfile(store, portal)
        val staleFamily = users.snapshot("101").filter { it.profileId == family.profileId }
        val other = users.createProfile("Another person")
        users.selectProfile(other.id)
        store.saveFavorites(listOf(FavoriteItem(FavoriteKind.MOVIE, MediaItem("other", "Other favorite", null, null))))
        val otherRecords = users.snapshot("101").filter { it.profileId == other.id }
        val otherPreferences = users.dao.preferences("101", other.id)

        users.deleteProfile(family.profileId)
        val deleted = users.snapshot("101").filter { it.profileId == family.profileId }
        assertTrue(deleted.isNotEmpty() && deleted.all { it.deleted })
        assertEquals(UserScope("101", other.id), users.selectedScope.value)
        assertEquals(otherRecords, users.snapshot("101").filter { it.profileId == other.id })
        assertEquals(otherPreferences, users.dao.preferences("101", other.id))
        store.scoped(family).savePlaybackProgress(listOf(PlaybackProgress("late-callback", 500, 1000)))
        store.scoped(family).saveRecentlyPlayed(listOf(RecentItem(FavoriteKind.MOVIE, MediaItem("late", "Late callback", null, null))))
        assertEquals(deleted, users.snapshot("101").filter { it.profileId == family.profileId })
        users.merge("101", staleFamily.map { it.copy(revision = 1_000_000, deviceId = "stale-device", mutationId = "stale-${it.mutationId}") })
        assertEquals(deleted, users.snapshot("101").filter { it.profileId == family.profileId })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { users.selectProfile(family.profileId) } }
        val replacement = users.createProfile("Family")
        assertNotEquals(family.profileId, replacement.id)
        users.selectProfile(replacement.id)
        assertTrue(store.favorites.first().isEmpty())
        assertTrue(store.recentlyPlayed.first().isEmpty())
        assertTrue(store.playbackProgress.first().isEmpty())
        assertTrue(store.offlineDownloads.first().isEmpty())
        assertTrue(store.profiles.first().isEmpty())
        assertTrue(UserProfilePreferences.get(context, "home2_presentation").all.isEmpty())
    }

    @Test fun rememberedNavigationIsLocalAndIndependentForEveryAccountAndPerson(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val kids = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
        users.selectProfile(family.profileId)
        val records = users.snapshot("101")
        val pending = users.pending("101")
        users.rememberDestination(family, ViewerDestination(ViewerDestinationKind.PORTAL, "device-portal-one"))
        users.rememberDestination(family, ViewerDestination(ViewerDestinationKind.SPORTS))
        assertEquals(ViewerDestinationKind.SPORTS, users.rememberedDestination(family)?.kind)
        assertEquals("device-portal-one", users.rememberedPortalKey(family))
        users.selectProfile(kids.profileId)
        assertNull(users.rememberedDestination(kids))
        assertNull(users.rememberedPortalKey(kids))
        users.rememberDestination(kids, ViewerDestination(ViewerDestinationKind.OFFLINE))
        assertEquals(records, users.snapshot("101"))
        assertEquals(pending, users.pending("101"))
        val newPerson = users.createProfile("New person")
        assertNull(users.rememberedDestination(UserScope("101", newPerson.id)))
        users.selectProfile(family.profileId)

        users.selectAccount("202"); users.initializeDefaults("202")
        val other = UserScope("202", UserSyncStore.defaultProfileId("202", UserProfileType.GENERAL))
        assertNull(users.rememberedProfileId("202"))
        assertNull(users.rememberedDestination(other))
        users.selectProfile(other.profileId)
        users.rememberDestination(other, ViewerDestination(ViewerDestinationKind.PORTAL, "device-portal-two"))
        users.selectAccount("101")
        assertNull("The account gate must validate and restore explicitly", users.selectedScope.value)
        assertEquals(family.profileId, users.rememberedProfileId("101"))
        assertEquals(other.profileId, users.rememberedProfileId("202"))
        assertEquals(ViewerDestination(ViewerDestinationKind.SPORTS), users.rememberedDestination(family))
        assertEquals(ViewerDestination(ViewerDestinationKind.OFFLINE), users.rememberedDestination(kids))
        users.disconnect()
        assertNull(users.selectedScope.value)
        assertEquals(family.profileId, users.rememberedProfileId("101"))
    }

    @Test fun localAndRemoteProfileDeletionForgetOnlyThatPersonsNavigation(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        val kids = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
        users.selectProfile(family.profileId)
        users.rememberDestination(family, ViewerDestination(ViewerDestinationKind.PORTAL, "deleted-portal"))
        users.selectProfile(kids.profileId)
        users.rememberDestination(kids, ViewerDestination(ViewerDestinationKind.OFFLINE))
        users.deleteProfile(family.profileId)
        assertEquals(kids.profileId, users.rememberedProfileId("101"))
        assertEquals(kids, users.selectedScope.value)
        assertEquals(ViewerDestinationKind.OFFLINE, users.rememberedDestination(kids)?.kind)
        assertNull(users.rememberedDestination(family))
        assertNull(users.rememberedPortalKey(family))
        val live = users.snapshot("101").single { it.collection == "profile" && it.recordId == kids.profileId }
        users.merge("101", listOf(live.copy(deleted = true, generation = live.generation + 1,
            revision = live.revision + 1, deviceId = "other-device", mutationId = "remote-profile-removal")))
        assertNull(users.selectedScope.value)
        assertNull(users.rememberedProfileId("101"))
        users.rememberDestination(kids, ViewerDestination(ViewerDestinationKind.PORTAL, "late-connect-callback"))
        assertNull(users.rememberedDestination(kids))
        assertNull(users.rememberedPortalKey(kids))
        val replacement = users.createProfile("Kids")
        assertNotEquals(kids.profileId, replacement.id)
        assertNull(users.rememberedDestination(UserScope("101", replacement.id)))
    }

    @Test fun missingProfilesAndMalformedLocalNavigationCannotBeRestored(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val local = context.getSharedPreferences("niktv_user_selection", 0)
        val missing = UserScope("101", java.util.UUID.randomUUID().toString())
        local.edit().putString("profile_101", missing.profileId)
            .putString("destination_101_${missing.profileId}", "{broken-json")
            .putString("portal_101_${missing.profileId}", "missing-portal").commit()
        assertNull(users.rememberedProfileId("101"))
        assertFalse(local.contains("destination_101_${missing.profileId}"))
        assertFalse(local.contains("portal_101_${missing.profileId}"))
        local.edit().putString("profile_101", "not-a-profile-uuid").commit()
        assertNull(users.rememberedProfileId("101"))
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(family.profileId)
        local.edit().putString("destination_101_${family.profileId}", "{broken-json").commit()
        assertNull(users.rememberedDestination(family))
        assertEquals(family.profileId, users.rememberedProfileId("101"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            users.rememberDestination(family, ViewerDestination(ViewerDestinationKind.PORTAL))
        } }
    }

    @Test fun processRecreationWaitsForAccountGateBeforeRestoringAValidatedViewer(): Unit = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val family = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
        users.selectProfile(family.profileId)
        users.rememberDestination(family, ViewerDestination(ViewerDestinationKind.OFFLINE))
        val constructor = UserSyncStore::class.java.getDeclaredConstructor(android.content.Context::class.java)
        constructor.isAccessible = true
        val recreated = constructor.newInstance(context)
        try {
            assertEquals("101", recreated.selectedAccount.value)
            assertNull(recreated.selectedScope.value)
            assertEquals(family.profileId, recreated.rememberedProfileId("101"))
            assertEquals(ViewerDestinationKind.OFFLINE, recreated.rememberedDestination(family)?.kind)
            recreated.selectProfile(family.profileId)
            assertEquals(family, recreated.selectedScope.value)
        } finally { recreated.db.close() }
    }

    private suspend fun populateViewingProfile(store: ProfileStore, portal: PortalProfile) {
        val media = MediaItem("movie-one", "Fixture movie", null, null)
        val series = MediaItem("series-one", "Fixture series", null, null)
        val recent = RecentItem(FavoriteKind.MOVIE, media, profileKey = portal.cacheKey())
        val progress = PlaybackProgress("progress-v3|${portal.cacheKey()}|MOVIES|movie:movie-one", 120_000, 1_200_000)
        store.addProfile(portal)
        store.saveFavorites(listOf(FavoriteItem(FavoriteKind.MOVIE, media, profileKey = portal.cacheKey())))
        store.saveRecentlyPlayed(listOf(recent))
        store.savePlaybackProgress(listOf(progress))
        store.saveWatchedSeries(listOf(WatchedSeries(portal.cacheKey(), series)))
        store.requestRecommendations(recent, "MOVIES:1")
        store.dismissRecommendation(portal.cacheKey(), "MOVIES:2")
        store.saveRecommendationProgress(progress)
        store.addRecentSearch(RecentSearch("Fixture", SearchContentType.MOVIES, profileKey = portal.cacheKey()))
        store.saveCategoryFilter(portal.cacheKey(), CatalogType.MOVIES, listOf("category"))
        store.rememberSeriesSeason(portal.cacheKey(), series.id, 2)
        store.setBrowseLayout(portal.cacheKey(), BrowseLayout.LIST)
        store.setTmdbDashboardSections(portal.cacheKey(), DashboardSurface.HOME, listOf(TmdbHomeSection.FAMILY))
        store.setSeriesStartSeason(SeriesStartSeason.FIRST)
        store.saveOfflineDownloads(listOf(OfflineMediaDownload(profileKey = portal.cacheKey(), catalogType = CatalogType.MOVIES, media = media)))
        UserProfilePreferences.get(context, "home2_presentation").edit().putString("fixture", "family-layout").commit()
    }

    private fun oldBackup(portal: PortalProfile, favorite: FavoriteItem): String = buildJsonObject {
        put("formatVersion", 1)
        put("strings", buildJsonObject {
            put("saved_profiles", Json.encodeToString(listOf(portal)))
            put("favorites", Json.encodeToString(listOf(favorite)))
        })
        put("integers", buildJsonObject {})
    }.toString()

    private fun memoryPreferences(initial: Preferences = emptyPreferences()): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock { transform(state.value).also { state.value = it } }
    }
}
