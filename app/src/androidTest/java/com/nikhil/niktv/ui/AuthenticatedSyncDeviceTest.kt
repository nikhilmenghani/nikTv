package com.nikhil.niktv.ui

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Root orchestrates phases on two explicitly provisioned devices. Never reads or accepts a token.
 * All writes belong to one disposable UUID; real profiles, portals and playback are untouched. */
class AuthenticatedSyncDeviceTest {
    @Test fun runPhase(): Unit = runBlocking { withTimeout(180_000) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Authenticated validation requires explicit root phase orchestration", args.containsKey("phase"))
        check(context.packageName == "com.nikhil.niktv.debug") { "Use the explicitly provisioned development app." }
        val phase = requireNotNull(args.getString("phase"))
        val users = UserSyncStore.get(context)
        val manager = AccountSyncManager.get(context)
        val previous = users.selectedScope.value
        val accountId = requireNotNull(users.selectedAccount.value) { "Connect this device before validation." }
        val local = context.getSharedPreferences("authenticated_sync_validation", 0)
        var profileId = args.getString("validationProfileId")
        val portal = PortalProfile("Sync validation fixture", "https://${if (phase.startsWith("poco")) "poco" else "tablet"}-sync-validation.invalid", "")
        val media = MediaItem("sync-validation-movie-a", "Sync validation A", null, null)
        val progressKey = providerProgressKey(portal.cacheKey(), media, CatalogType.MOVIES, null)
        val series = MediaItem("sync-validation-series", "Sync validation series", null, null)
        val episode = MediaItem("sync-validation-episode-1", "Sync validation episode", null, null,
            seasonNumber = 1, episodeNumber = 1, portalEpisodeId = "1")
        val episodeKey = providerProgressKey(portal.cacheKey(), episode, CatalogType.SERIES, series)
        fun recent(suffix: String) = RecentItem(FavoriteKind.MOVIE,
            MediaItem("sync-validation-movie-$suffix", "Sync validation ${suffix.uppercase()}", null, null), profileKey = portal.cacheKey())
        suspend fun sync() {
            manager.syncNow()
            assertTrue("Account sync succeeded", manager.state.value.initialized)
            assertNull("Account sync has no error", manager.state.value.error)
        }
        try {
            // The union phase deliberately writes on the stale tablet before fetching Poco's edit.
            if (phase != "tablet_union") sync()
            if (phase == "tablet_create") {
                check(profileId == null) { "A new run must not reuse another profile ID." }
                val profile = users.createProfile("Sync validation ${UUID.randomUUID().toString().take(8)}")
                profileId = profile.id
                check(local.edit().putString("createdProfileId", profile.id).commit())
                instrumentation.sendStatus(0, Bundle().apply { putString("validationProfileId", profile.id) })
            }
            val id = requireNotNull(profileId ?: local.getString("createdProfileId", null))
            UUID.fromString(id)
            val records = users.snapshot(accountId)
            val profileRecord = records.single { it.collection == "profile" && it.recordId == id }
            val profile = kotlinx.serialization.json.Json.decodeFromString<UserProfile>(profileRecord.payload)
            check(profile.name.startsWith("Sync validation ")) { "Refuse to modify an ordinary user profile." }
            val owner = UserScope(accountId, id)
            val store = ProfileStore(context).scoped(owner)
            val otherRows = records.filter { it.profileId != id && it.profileId.isNotBlank() }
                .associate { Triple(it.profileId, it.collection, it.recordId) to it.mutationId }
            suspend fun assertOthersUnchanged() = assertEquals("Other people remain unchanged", otherRows,
                users.snapshot(accountId).filter { it.profileId != id && it.profileId.isNotBlank() }
                    .associate { Triple(it.profileId, it.collection, it.recordId) to it.mutationId })
            suspend fun mapRemotePortal() {
                val logicalId = users.snapshot(accountId).single { it.profileId == id && it.collection == "portals" && !it.deleted }.recordId
                users.mapPortal(owner, logicalId, portal)
            }
            suspend fun progress() = store.playbackProgress.first().single { it.key == progressKey }
            suspend fun assertEpisodeResume() = assertEquals("The same provider episode resumes across devices", 90_000L,
                store.playbackProgress.first().single { it.key == episodeKey }.positionMillis)
            suspend fun updateMovie(position: Long, action: ViewingAction) = store.savePlaybackProgress(
                store.playbackProgress.first().map { if (it.key == progressKey) it.copy(positionMillis = position, action = action) else it })
            suspend fun assertUnion() = assertEquals(setOf("a", "b", "c").map { "sync-validation-movie-$it" }.toSet(),
                store.recentlyPlayed.first().map { it.media.id }.toSet())
            when (phase) {
                "tablet_create" -> {
                    users.associatePortal(owner, portal)
                    store.savePlaybackProgress(listOf(PlaybackProgress(progressKey, 60_000, 600_000), PlaybackProgress(episodeKey, 90_000, 600_000)))
                    store.saveRecentlyPlayed(listOf(recent("a")))
                    assertOthersUnchanged(); sync()
                    assertEpisodeResume()
                    // Hold this device's local snapshot while Poco changes the remote. The next
                    // explicit sync restores the normal periodic account catch-up work.
                    WorkManager.getInstance(context).cancelAllWorkByTag("niktv-account:$accountId").result.get(5, TimeUnit.SECONDS)
                }
                "poco_resume" -> {
                    mapRemotePortal()
                    assertEquals(60_000L, progress().positionMillis)
                    assertEpisodeResume()
                    updateMovie(30_000, ViewingAction.REWIND)
                    store.saveRecentlyPlayed(store.recentlyPlayed.first() + recent("b"))
                    assertOthersUnchanged(); sync()
                }
                "tablet_union" -> {
                    assertEquals(setOf("sync-validation-movie-a"), store.recentlyPlayed.first().map { it.media.id }.toSet())
                    store.saveRecentlyPlayed(store.recentlyPlayed.first() + recent("c"))
                    assertOthersUnchanged(); sync(); assertUnion()
                    assertEquals(30_000L, progress().positionMillis)
                    assertEquals(ViewingAction.REWIND, progress().action)
                    assertEpisodeResume()
                }
                "poco_unwatched" -> {
                    mapRemotePortal(); assertUnion(); assertEpisodeResume()
                    updateMovie(0, ViewingAction.MARK_UNWATCHED)
                    assertOthersUnchanged(); sync()
                }
                "tablet_cleanup", "cleanup" -> {
                    if (!profileRecord.deleted) {
                        if (phase == "tablet_cleanup") {
                            assertEquals(0L, progress().positionMillis)
                            assertEquals(ViewingAction.MARK_UNWATCHED, progress().action)
                            assertUnion()
                            assertEpisodeResume()
                        }
                        users.deleteProfile(id)
                        assertOthersUnchanged(); sync()
                    }
                    assertTrue(users.snapshot(accountId).single { it.collection == "profile" && it.recordId == id }.deleted)
                    local.edit().remove("createdProfileId").commit()
                }
                "poco_verify_cleanup" -> {
                    assertTrue("Deleted fixture remains tombstoned", profileRecord.deleted)
                    assertFalse(users.profiles.first().any { it.id == id })
                    assertTrue(store.recentlyPlayed.first().isEmpty())
                    assertTrue(store.playbackProgress.first().isEmpty())
                }
                else -> error("Unknown validation phase.")
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("validationPhase", phase); putString("validationProfileId", id) })
        } finally {
            if (previous != null && previous.accountId == users.selectedAccount.value && users.profiles.first().any { it.id == previous.profileId })
                users.selectProfile(previous.profileId)
            else users.clearSelection()
        }
    } }
}
