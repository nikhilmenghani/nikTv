package com.nikhil.niktv.data

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class SetupRestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: androidx.datastore.core.DataStore<Preferences>
    private lateinit var store: ProfileStore
    private val key = intPreferencesKey("initial_catalog_items")
    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { temporary.newFolder().resolve("restore.preferences_pb") })
        store = ProfileStore(context, preferences)
        SetupRestoreJournal(context).clear()
    }
    @After fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    private suspend fun interruptRestore(): SetupRestoreSnapshot {
        preferences.edit { it[key] = 42 }
        val shared = context.getSharedPreferences("discovery_trial_v1", 0)
        shared.edit().putString("marker", "before").commit()
        val snapshot = SetupRestoreSnapshot(java.util.UUID.randomUUID().toString(), setOf(key.name),
            mapOf(key.name to encodeRestorePreference(42)), BackupPreferenceRollback(context, null).journalSnapshot())
        SetupRestoreJournal(context).write(snapshot)
        preferences.edit { it[key] = 14; it[stringPreferencesKey("unrelated-new-setting")] = "keep" }
        shared.edit().putString("marker", "partial").commit()
        return snapshot
    }
    @Test fun interruptedRestoreRollsBackAfterRestartAndRecoveryIsIdempotent() = runBlocking {
        interruptRestore()
        ProfileStore(context, preferences).recoverPendingRestore()
        assertEquals(42, preferences.data.first()[key])
        assertEquals("before", context.getSharedPreferences("discovery_trial_v1", 0).getString("marker", null))
        assertEquals("keep", preferences.data.first()[stringPreferencesKey("unrelated-new-setting")])
        assertNull(SetupRestoreJournal(context).read())
        preferences.edit { it[key] = 28 }
        store.recoverPendingRestore()
        assertEquals(28, preferences.data.first()[key])
    }
    @Test fun committedRoomMarkerRetainsCompletedSettingsEvenIfJournalCleanupWasInterrupted() = runBlocking {
        val snapshot = interruptRestore()
        val users = UserSyncStore.get(context)
        users.db.withTransaction {
            users.dao.putPreference(UserLocalPreference(SetupRestoreJournal.MARKER_ACCOUNT, "", SetupRestoreJournal.MARKER_NAME, snapshot.id))
        }
        store.recoverPendingRestore()
        assertEquals(14, preferences.data.first()[key])
        assertEquals("partial", context.getSharedPreferences("discovery_trial_v1", 0).getString("marker", null))
        assertNull(SetupRestoreJournal(context).read())
    }
    @Test fun rolledBackRoomMarkerCannotMakeAnIncompleteRestoreLookCommitted() = runBlocking {
        val snapshot = interruptRestore()
        val users = UserSyncStore.get(context)
        assertTrue(runCatching { users.db.withTransaction {
            users.dao.putPreference(UserLocalPreference(SetupRestoreJournal.MARKER_ACCOUNT, "", SetupRestoreJournal.MARKER_NAME, snapshot.id))
            error("Process interrupted before transaction commit")
        } }.isFailure)
        store.recoverPendingRestore()
        assertEquals(42, preferences.data.first()[key])
    }
    @Test fun anotherImportCannotOverwriteAnUnrecoveredJournal() = runBlocking {
        val snapshot = interruptRestore()
        assertTrue(runCatching { SetupRestoreJournal(context).write(snapshot.copy(id = java.util.UUID.randomUUID().toString())) }.isFailure)
        assertEquals(snapshot, SetupRestoreJournal(context).read())
    }
    @Test fun profileReadersAndWritersWaitUntilRecoveryCompletes() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val guarded = RestoreReadyPreferences(context, preferences) { ready.await() }
        val reader = async(start = CoroutineStart.UNDISPATCHED) { guarded.data.first() }
        val writer = async(start = CoroutineStart.UNDISPATCHED) { guarded.edit { it[key] = 28 } }
        assertFalse(reader.isCompleted)
        assertFalse(writer.isCompleted)
        assertNull(preferences.data.first()[key])
        ready.complete(Unit)
        reader.await(); writer.await()
        assertEquals(28, preferences.data.first()[key])
    }
    @Test fun failedRecoveryBlocksReadsAndWritesInsteadOfExposingPartialSetup() = runBlocking {
        val guarded = RestoreReadyPreferences(context, preferences) { error("Corrupt recovery journal") }
        assertTrue(runCatching { guarded.data.first() }.isFailure)
        assertTrue(runCatching { guarded.edit { it[key] = 28 } }.isFailure)
        assertNull(preferences.data.first()[key])
    }
    @Test fun unrestorableSnapshotsAreRejectedBeforeJournalCreation() {
        val snapshot = SetupRestoreSnapshot(java.util.UUID.randomUUID().toString(), setOf(key.name),
            mapOf(key.name to DevicePreferenceValue("int", "invalid")), emptyMap())
        assertTrue(runCatching { SetupRestoreJournal(context).write(snapshot) }.isFailure)
        assertNull(SetupRestoreJournal(context).read())
    }
}
