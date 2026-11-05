package com.nikhil.niktv.ui

import android.app.Application
import android.os.Looper
import com.nikhil.niktv.data.SyncRecord
import com.nikhil.niktv.data.UserProfilePreferences
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class IntroPortableSyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private val family get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
    private val kids get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
    private lateinit var ui: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private val controllers = mutableListOf<IntroSkipSettings>()
    private val portalId = "11111111-2222-3333-4444-555555555555"

    @Before fun setup() = runBlocking {
        ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + ui)
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        users.selectAccount("101"); users.initializeDefaults("101")
        UserProfilePreferences.warm(context, family)
        users.selectProfile(family.profileId)
        Unit
    }

    @After fun stop() = runBlocking {
        withContext(ui) { controllers.forEach { it.dispose() } }
        scope.coroutineContext.job.cancelAndJoin()
        ui.close()
    }

    private fun playback(localKey: String, owner: UserScope = family): PlayingMedia = PlayingMedia(
        MediaItem("episode-3", "Episode three", null, null, seasonNumber = 2, episodeNumber = 3),
        "https://fixture.invalid/stream?token=fixture-secret", CatalogType.SERIES,
        series = MediaItem("series-42", "Series", null, null), profileKey = localKey,
        logicalPortalId = portalId, userScope = owner)

    private fun store(owner: UserScope): IntroTimingStore {
        val captured = UserProfilePreferences.get(context, "player_intro_timings", owner = owner)
        return IntroTimingStore { captured }
    }

    private suspend fun controller(target: IntroTarget, store: IntroTimingStore): IntroSkipSettings = withContext(ui) {
        IntroSkipSettings(target, store, scope).also { controllers += it; it.observe(); it.load() }
    }

    private fun record(owner: UserScope, key: String, records: List<SyncRecord>): SyncRecord = records.single {
        it.profileId == owner.profileId && it.recordId == "portable_preferences:player_intro_timings|$key"
    }

    private suspend fun mergeRange(owner: UserScope, key: String, range: IntroRange?) {
        val original = record(owner, key, users.snapshot(owner.accountId))
        val payload = Json.parseToJsonElement(original.payload).jsonObject
        val entry = payload.getValue("entry").jsonObject
        val updated = range?.let { "${it.startMillis},${it.endMillis},${it.autoSkip},123456" }
        val remote = original.copy(
            payload = if (updated == null) original.payload else JsonObject(payload + ("entry" to
                JsonObject(entry + ("value" to JsonPrimitive(updated))))).toString(),
            deleted = range == null,
            generation = original.generation + if (range == null) 1 else 0,
            revision = original.revision + 100, deviceId = "other-device", mutationId = UUID.randomUUID().toString())
        users.merge(owner.accountId, listOf(remote))
    }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(4_000L) {
        while (!withContext(ui) { condition() }) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10L)
        }
    }

    @Test fun timingKeysAndAutoSkipArePortableAcrossLocalPortalCredentials() = runBlocking {
        val first = requireNotNull(introTarget(playback("catalog-v5|STALKER|https://first.invalid|fixture-mac-a")))
        val second = requireNotNull(introTarget(playback("catalog-v5|STALKER|https://second.invalid|fixture-mac-b")))
        assertEquals(first.episodeKey, second.episodeKey)
        assertEquals(first.seasonKey, second.seasonKey)
        assertNotEquals(first.legacyEpisodeKey, second.legacyEpisodeKey)
        val episode = IntroRange(2_000, 24_000, autoSkip = true)
        val season = IntroRange(1_000, 20_000, autoSkip = false)
        withContext(Dispatchers.IO) {
            store(family).save(first, IntroScope.SEASON, season)
            store(family).save(first, IntroScope.EPISODE, episode)
        }
        assertEquals(IntroTimings(episode, season), store(family).read(second))
        val wire = users.snapshot("101").filter { it.collection == "preferences" }.joinToString { it.payload }
        assertFalse(wire.contains("first.invalid")); assertFalse(wire.contains("second.invalid"))
        assertFalse(wire.contains("fixture-mac")); assertFalse(wire.contains("fixture-secret"))
        UserProfilePreferences.warm(context, kids)
        assertEquals(IntroTimings(), store(kids).read(second))
    }

    @Test fun remoteRefreshUsesCapturedProfileAndPreservesUnsavedDraftThenUnregisters() = runBlocking {
        val target = requireNotNull(introTarget(playback("local-portal")))
        val familyStore = store(family)
        withContext(Dispatchers.IO) { familyStore.save(target, IntroScope.EPISODE, IntroRange(1_000, 10_000)) }
        val settings = controller(target, familyStore)
        withContext(ui) {
            settings.open(40_000)
            settings.startText = "0:07"; settings.endText = "0:39"; settings.autoSkip = false
        }
        UserProfilePreferences.warm(context, kids)
        users.selectProfile(kids.profileId)
        val kidsRange = IntroRange(9_000, 18_000)
        withContext(Dispatchers.IO) { store(kids).save(target, IntroScope.EPISODE, kidsRange) }
        val remote = IntroRange(5_000, 25_000, autoSkip = true)
        mergeRange(family, target.episodeKey, remote)
        awaitState { settings.timings.episode == remote }
        withContext(ui) {
            assertTrue(settings.editorOpen)
            assertEquals("0:07", settings.startText); assertEquals("0:39", settings.endText)
            assertFalse(settings.autoSkip)
        }
        assertEquals(kidsRange, store(kids).read(target).episode)
        withContext(ui) { settings.dispose() }
        val afterDisposal = IntroRange(7_000, 27_000)
        mergeRange(family, target.episodeKey, afterDisposal)
        repeat(15) { shadowOf(Looper.getMainLooper()).idle(); delay(10L) }
        assertEquals(afterDisposal, familyStore.read(target).episode)
        withContext(ui) { assertEquals(remote, settings.timings.episode) }
    }

    @Test fun remoteEpisodeRemovalRefreshesLiveSeasonFallbackIncludingAutoSkip() = runBlocking {
        val target = requireNotNull(introTarget(playback("local-portal")))
        val timings = store(family)
        val season = IntroRange(1_000, 22_000, autoSkip = true)
        withContext(Dispatchers.IO) {
            timings.save(target, IntroScope.SEASON, season)
            timings.save(target, IntroScope.EPISODE, IntroRange(2_000, 10_000))
        }
        val settings = controller(target, timings)
        mergeRange(family, target.episodeKey, null)
        awaitState { settings.timings.episode == null && settings.timings.season == season }
        withContext(ui) { assertEquals(season, settings.timings.effective); assertTrue(settings.timings.effective!!.autoSkip) }
    }
}
