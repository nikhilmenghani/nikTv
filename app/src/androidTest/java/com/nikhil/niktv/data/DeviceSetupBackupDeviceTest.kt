package com.nikhil.niktv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic credentials only, in the isolated regression app; no network requests. */
class DeviceSetupBackupDeviceTest {
    @Test fun savedConnectionsReencryptAndRestoreAlongsideAppearance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".regression"))
        val manager = GitHubBackupManager(context)
        val previousRemote = RemoteCredentials.exportDeviceConfiguration(context)
        val previousBackup = manager.loadConfig()
        val previousPreferences = DevicePreferencesBackup.capture(context)
        val previousLibrary = NikTubeRepository(context).use { it.exportDeviceLibrary() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "setup-roundtrip-${System.nanoTime()}.preferences_pb")
        val store = ProfileStore(context, PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        try {
            val remote = RemoteConfigurationBackup("fixture-read-token", "fixture-owner", "fixture-config", ".env",
                mapOf("G_TOKEN" to "fixture-backup-token"), true)
            RemoteCredentials.restoreDeviceConfiguration(context, remote)
            val connection = GitHubBackupConfig(username = "fixture-owner", repository = "fixture-backups",
                token = "fixture-upload-token", passphrase = "fixture-password-123", rememberPassphrase = true,
                autoBackupIntervalHours = 12)
            manager.saveConfig(connection, reschedule = false)
            context.getSharedPreferences("discovery_trial_v1", 0).edit().putString("fixture:HOME", "B,A").commit()
            val video = NikTubeVideo("fixture-video", "Fixture video", "Fixture channel")
            NikTubeRepository(context).use {
                it.saveHistory(listOf(video to 15_000L))
                it.saveSubscriptions(listOf(NikTubeChannel("fixture-channel", "Fixture channel")))
                it.savePlaylists(mapOf("Saved" to listOf(video)))
            }
            val backup = store.exportBackup()
            RemoteCredentials.restoreDeviceConfiguration(context, null)
            manager.saveConfig(GitHubBackupConfig(), reschedule = false)
            context.getSharedPreferences("discovery_trial_v1", 0).edit().clear().commit()
            NikTubeRepository(context).use { it.restoreDeviceLibrary(emptyMap()) }
            store.importBackup(backup)
            assertTrue("Private configuration is restored", remote == RemoteCredentials.exportDeviceConfiguration(context))
            assertTrue("Encrypted backup destination is restored", connection == manager.loadConfig())
            assertEquals("B,A", context.getSharedPreferences("discovery_trial_v1", 0).getString("fixture:HOME", null))
            NikTubeRepository(context).use {
                assertEquals(listOf(video to 15_000L), it.history())
                assertEquals("fixture-channel", it.subscriptions().single().id)
                assertEquals(mapOf("Saved" to listOf(video)), it.playlists())
            }
        } finally {
            RemoteCredentials.restoreDeviceConfiguration(context, previousRemote)
            manager.saveConfig(previousBackup, reschedule = false)
            DevicePreferencesBackup.restore(context, previousPreferences)
            NikTubeRepository(context).use { it.restoreDeviceLibrary(previousLibrary) }
            scope.coroutineContext.job.cancelAndJoin()
            file.delete()
        }
    }
}
