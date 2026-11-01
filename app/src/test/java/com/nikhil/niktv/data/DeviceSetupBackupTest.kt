package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class DeviceSetupBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: ProfileStore
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { temporary.newFolder().resolve("device.preferences_pb") })
        store = ProfileStore(context, preferences)
    }
    @After fun stop() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    @Test fun appearanceFavoritesAndSettingsRoundTripExactlyWithoutCacheOrSessions() = runBlocking {
        val profile = PortalProfile("Tablet", "https://setup.invalid", "00:11:22:33:44:55")
        val favorites = listOf(FavoriteItem(FavoriteKind.MOVIE, MediaItem("saved", "Saved fixture", null, null),
            profileKey = profile.cacheKey(), addedAtMillis = 123L))
        preferences.edit {
            it[stringPreferencesKey("saved_profiles")] = Json.encodeToString(listOf(profile))
            it[stringPreferencesKey("favorites")] = Json.encodeToString(favorites)
            it[stringPreferencesKey("playback_engine")] = "VLC"
            it[stringPreferencesKey("dismissed_recommendations_v1")] = "{\"profile\":[\"hidden-row\"]}"
            it[intPreferencesKey("keep_awake_only_during_playback")] = 1
            it[intPreferencesKey("initial_catalog_items")] = 42
            it[stringPreferencesKey("active_session")] = "temporary-session"
            it[stringPreferencesKey("search_catalog_movies")] = "cache-to-rebuild"
        }
        context.getSharedPreferences("discovery_trial_v1", 0).edit().putString("profile:HOME", "ROW_B,ROW_A").commit()
        context.getSharedPreferences("iptv_pin_preferences", 0).edit().putStringSet("pins", setOf("movie-2", "movie-1")).commit()
        context.getSharedPreferences("browse_collections", 0).edit().putString("collections", "fixture grouping").commit()
        context.getSharedPreferences("player_intro_timings", 0).edit().putLong("intro", 85000L).commit()
        context.getSharedPreferences("app_update_download", 0).edit().putBoolean("startup_check_enabled", false)
            .putLong("download_id", 999L).commit()
        val expectedPreferences = DevicePreferencesBackup.capture(context)
        val fingerprint = store.backupFingerprint()
        val content = store.exportBackup()
        assertEquals("Backup identity and date must not change the fingerprint", fingerprint, store.backupFingerprint())
        val root = Json.parseToJsonElement(content).jsonObject
        assertFalse(root.getValue("strings").jsonObject.containsKey("active_session"))
        assertFalse(root.getValue("strings").jsonObject.containsKey("search_catalog_movies"))
        assertFalse(root.containsKey("database"))
        preferences.edit { it.clear() }
        DevicePreferencesBackup.stores.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        context.getSharedPreferences("discovery_trial_v1", 0).edit().putString("extra-destination-row", "remove me").commit()
        store.importBackup(content)
        assertEquals(favorites, store.favorites.first())
        assertEquals(PlaybackEngine.VLC, store.playbackEngine.first())
        assertEquals(42, store.initialCatalogItems.first())
        assertTrue(store.keepAwakeOnlyDuringPlayback.first())
        assertEquals(expectedPreferences, DevicePreferencesBackup.capture(context))
        assertFalse(preferences.data.first().contains(stringPreferencesKey("active_session")))
    }

    @Test fun repeatedBackupsHaveStableDeviceIdentityAndDistinctNumbersAndNames() = runBlocking {
        DeviceBackupIdentity.rename(context, "Tablet")
        val firstContent = store.exportBackup()
        val first = Json.parseToJsonElement(firstContent).jsonObject.getValue("backupMetadata").jsonObject
        val second = Json.parseToJsonElement(store.exportBackup()).jsonObject.getValue("backupMetadata").jsonObject
        val sourceId = first.getValue("device").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(sourceId, second.getValue("device").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(first.getValue("backupNumber").jsonPrimitive.long + 1, second.getValue("backupNumber").jsonPrimitive.long)
        val manager = GitHubBackupManager(context)
        val name1 = manager.suggestedBackupFileName(false, 2, Date(0), 1)
        val name2 = manager.suggestedBackupFileName(false, 2, Date(0), 2)
        assertNotEquals(name1, name2)
        assertTrue(GitHubBackupFile(name1, name1, 1).displayLabel.contains("Tablet"))
        assertTrue(GitHubBackupFile(name2, name2, 1).displayLabel.contains("Backup 2"))
        context.getSharedPreferences("device_backup_identity", 0).edit().clear().commit()
        DeviceBackupIdentity.rename(context, "Living room")
        val otherId = DeviceBackupIdentity.id(context)
        assertNotEquals(sourceId, otherId)
        val destinationBackup = store.exportBackup()
        assertTrue(destinationBackup.contains("Living room"))
        store.importBackup(firstContent)
        assertEquals("A restore must retain the destination device identity", otherId, DeviceBackupIdentity.id(context))
        assertEquals("Living room", DeviceBackupIdentity.name(context))
    }

    @Test fun legacyBackupsRemainReadableAndDoNotEraseUnrecordedUiPreferences() = runBlocking {
        context.getSharedPreferences("discovery_trial_v1", 0).edit().putString("order", "keep").commit()
        store.importBackup("""{"formatVersion":1,"strings":{},"integers":{"initial_catalog_items":28}}""")
        assertEquals(28, store.initialCatalogItems.first())
        assertEquals("keep", context.getSharedPreferences("discovery_trial_v1", 0).getString("order", null))
    }

    @Test fun retiredYouTubeBackupFieldsAreIgnoredWithoutLosingIptvSettings() = runBlocking {
        store.importBackup("""{"formatVersion":2,"strings":{},"integers":{"initial_catalog_items":42},"nikTubeLibrary":{"history":"[]"},"devicePreferences":{"niktube_engine":{"engine":{"type":"string","value":"FLOW"}}}}""")
        assertEquals(42, store.initialCatalogItems.first())
        val content = store.exportBackup()
        assertFalse(content.contains("nikTubeLibrary"))
        assertFalse(content.contains("niktube_engine"))
    }

    @Test fun invalidPortablePreferenceFailsBeforeChangingStoredSetup() = runBlocking {
        store.setInitialCatalogItems(42)
        val malformed = """{"formatVersion":2,"strings":{},"integers":{"initial_catalog_items":14},"devicePreferences":{"player_media_browser":{"layout":{"type":"int","value":"invalid"}}}}"""
        assertTrue(runCatching { store.importBackup(malformed) }.isFailure)
        assertEquals(42, store.initialCatalogItems.first())
    }

    @Test fun catalogCloudTriggersStayDisabledEvenWhenAnOldBackupEnabledThem() {
        context.getSharedPreferences("catalog_device", Context.MODE_PRIVATE).edit().putBoolean("upload", true).commit()
        assertFalse(CatalogPreferences.backupEnabled(context))
        assertNull(SearchMetadataSyncScheduler.requestNow(context))
        assertNull(SearchMetadataSyncScheduler.requestRestore(context, PortalProfile("Fixture", "https://setup.invalid", "00")))
        assertFalse(DevicePreferencesBackup.capture(context).getValue("catalog_device").containsKey("upload"))
    }
}
