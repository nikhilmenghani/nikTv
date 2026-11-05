package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.sports.SportsPlaybackPreferences
import com.nikhil.niktv.ui.WatchingConfiguration
import com.nikhil.niktv.ui.WatchingPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class PlaybackTimingPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private val family get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
    private val kids get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
    private lateinit var base: DataStore<Preferences>
    private lateinit var store: ProfileStore

    @Before fun reset(): Unit = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        listOf("niktv_playback_ui", "sports_playback_settings", "niktv_legacy_assignment").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        base = memoryPreferences()
        store = ProfileStore(context, base)
        users.selectAccount("101"); users.initializeDefaults("101"); users.selectProfile(family.profileId)
        UserProfilePreferences.warm(context, family)
    }

    @Test fun timingReplicatesPerKeyWithoutEngineOrHistoryChanges(): Unit = runBlocking {
        val media = MediaItem("movie", "Existing history", null, null)
        store.saveRecentlyPlayed(listOf(RecentItem(FavoriteKind.MOVIE, media)))
        val history = users.snapshot("101").filter { it.collection == "history" }
        store.setPlayerControlsTimeoutSeconds(8)
        WatchingPreferences.save(WatchingPreferences.preferences(context, family), WatchingConfiguration(false, 75, 3))
        SportsPlaybackPreferences(context, family).also {
            it.setPlayerControlsTimeoutSeconds(12)
            it.setPlaybackEngine(PlaybackEngine.MPV)
        }
        assertEquals(history, users.snapshot("101").filter { it.collection == "history" })
        val records = users.snapshot("101")
        val preferences = records.filter { it.collection == "preferences" }
        assertEquals(5, preferences.size)
        assertTrue(preferences.all { it.recordId.startsWith("portable_preferences:niktv_playback_ui|") })
        val wire = Json.encodeToString(records)
        assertFalse(wire.contains("MPV")); assertFalse(wire.contains("engine"))

        // Emulate another device's empty transactional storage and independent hardware choice.
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        context.getSharedPreferences("sports_playback_settings", 0).edit().putString("engine", "MEDIA3").commit()
        users.merge("101", records.reversed()); users.merge("101", records)
        assertEquals(8, store.scoped(family).playerControlsTimeoutSeconds.first())
        assertEquals(WatchingConfiguration(false, 75, 3), WatchingPreferences.read(WatchingPreferences.preferences(context, family)))
        val sports = SportsPlaybackPreferences(context, family).settings.first()
        assertEquals(12, sports.timeout)
        assertEquals(PlaybackEngine.MEDIA3, sports.engine)
        assertEquals(history, users.snapshot("101").filter { it.collection == "history" })
        assertEquals(listOf(media.id), store.scoped(family).recentlyPlayed.first().map { it.media.id })
    }

    @Test fun capturedWritersCannotChangeFreshPeopleOrAnotherAccount(): Unit = runBlocking {
        base.edit { it[intPreferencesKey("player_controls_timeout_seconds")] = 21 }
        context.getSharedPreferences("niktv_playback_ui", 0).edit().putInt("watching_check_minutes", 300).commit()
        context.getSharedPreferences("sports_playback_settings", 0).edit().putInt("controls_timeout", 22).commit()
        val capturedStore = store.scoped(family)
        val capturedWatching = WatchingPreferences.preferences(context, family)
        val capturedSports = SportsPlaybackPreferences(context, family)
        users.selectProfile(kids.profileId)
        assertDefaults(kids)
        capturedStore.setPlayerControlsTimeoutSeconds(0)
        WatchingPreferences.save(capturedWatching, WatchingConfiguration(false, 45, 2))
        capturedSports.setPlayerControlsTimeoutSeconds(16)
        assertDefaults(kids)
        assertEquals(0, capturedStore.playerControlsTimeoutSeconds.first())
        assertEquals(16, capturedSports.playerControlsTimeoutSeconds.first())
        val newcomer = users.createProfile("Another person")
        assertDefaults(UserScope("101", newcomer.id))
        users.selectAccount("202"); users.initializeDefaults("202")
        val other = UserScope("202", UserSyncStore.defaultProfileId("202", UserProfileType.GENERAL))
        users.selectProfile(other.profileId)
        assertDefaults(other)
        assertEquals(21, base.data.first()[intPreferencesKey("player_controls_timeout_seconds")])
        assertTrue(users.snapshot("202").none { it.collection in setOf("preferences", "history", "progress") })
    }

    @Test fun savedAndMergedTimingAlwaysUsesSupportedRanges(): Unit = runBlocking {
        val sports = SportsPlaybackPreferences(context, family)
        for ((input, expected) in listOf(-5 to 0, 0 to 0, 1 to 1, 30 to 30, 99 to 30)) {
            store.setPlayerControlsTimeoutSeconds(input)
            sports.setPlayerControlsTimeoutSeconds(input)
            assertEquals(expected, store.playerControlsTimeoutSeconds.first())
            assertEquals(expected, sports.playerControlsTimeoutSeconds.first())
        }
        val prefs = WatchingPreferences.preferences(context, family)
        WatchingPreferences.save(prefs, WatchingConfiguration(false, -10, 99))
        assertEquals(WatchingConfiguration(false, 15, 12), WatchingPreferences.read(prefs))
        // Decoders also tolerate unsupported values from an older portable preference writer.
        UserProfilePreferences.get(context, "niktv_playback_ui", owner = family).edit()
            .putInt("controls_timeout", 1000).putInt("sports_controls_timeout", -100).commit()
        assertEquals(30, store.playerControlsTimeoutSeconds.first())
        assertEquals(0, sports.playerControlsTimeoutSeconds.first())
    }

    @Test fun legacyTimingMigratesOnceOnlyToAssignedFamilyAndKeepsRemoteChoices(): Unit = runBlocking {
        base.edit { it[intPreferencesKey("player_controls_timeout_seconds")] = 9 }
        context.getSharedPreferences("niktv_playback_ui", 0).edit().putBoolean("watching_check_enabled", false)
            .putInt("watching_check_minutes", 200).putInt("watching_check_episodes", 7).commit()
        context.getSharedPreferences("sports_playback_settings", 0).edit().putInt("controls_timeout", 11)
            .putString("engine", "MPV").commit()
        context.getSharedPreferences("niktv_legacy_assignment", 0).edit().putString("portal_account", "101").commit()
        store.setPlayerControlsTimeoutSeconds(5)
        val prefs = WatchingPreferences.preferences(context, family)
        prefs.edit().putInt("watching_check_minutes", 75).putBoolean("watching_check_enabled", true).commit()
        prefs.edit().remove("watching_check_enabled").commit() // Retained deletion is a choice, too.
        assertFalse(store.migrateLegacyPlaybackTiming("202"))
        assertTrue(store.migrateLegacyPlaybackTiming("101"))
        assertEquals(5, store.playerControlsTimeoutSeconds.first())
        assertEquals(WatchingConfiguration(true, 75, 7), WatchingPreferences.read(prefs))
        assertEquals(11, SportsPlaybackPreferences(context, family).playerControlsTimeoutSeconds.first())
        assertDefaults(kids)
        val snapshot = users.snapshot("101")
        assertFalse(store.migrateLegacyPlaybackTiming("101"))
        assertEquals(snapshot, users.snapshot("101"))
        users.selectAccount("202"); users.initializeDefaults("202")
        val otherFamily = UserScope("202", UserSyncStore.defaultProfileId("202", UserProfileType.GENERAL))
        context.getSharedPreferences("niktv_legacy_assignment", 0).edit().putString("portal_account", "202").commit()
        users.deleteProfile(otherFamily.profileId)
        assertFalse(store.migrateLegacyPlaybackTiming("202"))
        assertTrue(users.snapshot("202").none { it.recordId == "legacy-playback-timing-import-v1" })
    }

    @Test fun explicitLegacyAssignmentImportsGlobalTimerIntoOnlyTheChosenUuid(): Unit = runBlocking {
        base.edit { it[intPreferencesKey("player_controls_timeout_seconds")] = 9 }
        context.getSharedPreferences("niktv_legacy_assignment", 0).edit().putString("portal_account", "999").commit()
        val remote = users.createProfile("Existing remote person")
        val owner = UserScope("101", remote.id)
        assertFalse(store.migrateLegacyPlaybackTiming("101"))
        store.importLegacyToProfile(owner)
        assertEquals(9, store.scoped(owner).playerControlsTimeoutSeconds.first())
        assertDefaults(family); assertDefaults(kids)
        store.scoped(owner).setPlayerControlsTimeoutSeconds(7)
        store.importLegacyToProfile(owner)
        assertEquals(7, store.scoped(owner).playerControlsTimeoutSeconds.first())
    }

    private suspend fun assertDefaults(owner: UserScope) {
        UserProfilePreferences.warm(context, owner)
        assertEquals(3, store.scoped(owner).playerControlsTimeoutSeconds.first())
        assertEquals(3, SportsPlaybackPreferences(context, owner).playerControlsTimeoutSeconds.first())
        assertEquals(WatchingConfiguration(), WatchingPreferences.read(WatchingPreferences.preferences(context, owner)))
    }

    private fun memoryPreferences(): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
}
