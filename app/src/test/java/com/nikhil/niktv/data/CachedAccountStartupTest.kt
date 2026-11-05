package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class CachedAccountStartupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private val auth get() = context.getSharedPreferences("niktv_account_auth", Context.MODE_PRIVATE)
    private fun manager(): AccountSyncManager = AccountSyncManager::class.java
        .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)

    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        auth.edit().clear().commit()
        Unit
    }

    @Test fun unreadableTokenKeepsSuccessfulAccountAndItsProfilesInRecoverableState() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val original = users.snapshot("101")
        users.selectAccount("202")
        auth.edit().putString("accountId", "101").putString("login", "fixture-owner")
            .putString("ciphertext", "unreadable-fixture").putString("iv", "unreadable-fixture")
            .putBoolean("initialized:101", true).commit()
        val restored = manager().restoreCachedAccount()
        assertEquals("101", restored.accountId)
        assertTrue(restored.initialized)
        assertTrue(restored.reconnectRequired)
        assertFalse(restored.syncing)
        assertEquals("101", users.selectedAccount.value)
        assertEquals(original, users.snapshot("101"))
        assertEquals("101", context.getSharedPreferences("github_configuration_accounts", Context.MODE_PRIVATE).getString("active", null))
    }

    @Test fun missingAuthenticationDeactivatesSelectionWithoutDeletingAccountData() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val original = users.snapshot("101")
        val restored = manager().restoreCachedAccount()
        assertNull(restored.accountId)
        assertFalse(restored.initialized)
        assertNull(users.selectedAccount.value)
        assertEquals(original, users.snapshot("101"))
    }

    @Test fun cachedStartupCannotPromoteIncompleteSetupOrBypassKnownRevocation() {
        val incomplete = cachedAccountState("101", "fixture", initialized = false, reconnect = false, tokenAvailable = true)
        assertFalse(incomplete.initialized)
        assertFalse(incomplete.syncing)
        val ready = cachedAccountState("101", "fixture", initialized = true, reconnect = false, tokenAvailable = true)
        assertTrue(ready.initialized)
        assertFalse(ready.reconnectRequired)
        assertFalse(ready.syncing)
        val revoked = cachedAccountState("101", "fixture", initialized = true, reconnect = true, tokenAvailable = true)
        assertTrue(revoked.initialized)
        assertTrue(revoked.reconnectRequired)
        assertNotNull(revoked.error)
    }

    @Test fun ordinaryViewingAndBackgroundActionsDoNotStartNetworkWork() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        auth.edit().putString("accountId", "101").putString("ciphertext", "fixture")
            .putBoolean("retryNeeded:101", true).commit()
        val pending = users.pending("101")
        val manager = manager()
        listOf("playback", "playback-start", "playback-progress", "playback-pause", "playback-stop",
            "background", "mark-watched", "mark-unwatched", "viewing-change", "profile", "preferences")
            .forEach { manager.requestSync(it, immediate = true) }
        assertEquals(pending, users.pending("101"))
        assertEquals(AccountSyncState(), manager.state.value)
    }

    @Test fun recreatedScreenUsesLiveCachedStateWhileNetworkMutexIsOccupied() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        val manager = manager()
        @Suppress("UNCHECKED_CAST")
        val state = AccountSyncManager::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<AccountSyncState>
        val live = AccountSyncState("101", "fixture", initialized = true, syncing = true)
        state.value = live
        val mutex = AccountSyncManager::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(manager) as Mutex
        mutex.lock()
        try {
            assertSame(live, withTimeout(500L) { manager.restoreCachedAccount() })
            assertTrue(mutex.isLocked)
            assertEquals("101", users.selectedAccount.value)
        } finally { mutex.unlock() }
    }

    @Test fun requestedSyncOutlivesUiScopeAndQueuedOldAccountRequestsCannotRun() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        auth.edit().putString("accountId", "101").commit()
        val manager = manager()
        val mutex = AccountSyncManager::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(manager) as Mutex
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requested = CompletableDeferred<List<Job>>()
        mutex.lock()
        var heldByTest = true
        try {
            caller.launch {
                requested.complete(listOf(requireNotNull(manager.requestManualSync()), requireNotNull(manager.requestLaunchSync())))
                awaitCancellation()
            }
            val jobs = withTimeout(1_000L) { requested.await() }
            caller.cancel()
            assertTrue(jobs.all { it.isActive })
            // Both operations captured 101. Simulate switching while they wait for
            // an earlier upload: neither may restore or sync the newly active 202.
            auth.edit().putString("accountId", "202").commit()
            users.selectAccount("202")
            mutex.unlock()
            heldByTest = false
            withTimeout(2_000L) { jobs.joinAll() }
            assertTrue(jobs.none { it.isCancelled })
            assertEquals(AccountSyncState(), manager.state.value)
            assertEquals("202", users.selectedAccount.value)
        } finally {
            caller.cancel()
            if (heldByTest) mutex.unlock()
        }
    }

    @Test fun explicitRequestDoesNotRequireAWorkerRetryFlagButRetryWorkerStillDoes() = runBlocking {
        users.selectAccount("101"); users.initializeDefaults("101")
        auth.edit().putString("accountId", "101").putBoolean("initialized:101", true).commit()
        val workerManager = manager()
        workerManager.syncForAccount("101")
        assertNull(workerManager.state.value.accountId)
        val manualManager = manager()
        withTimeout(2_000L) { requireNotNull(manualManager.requestManualSync()).join() }
        assertEquals("101", manualManager.state.value.accountId)
        assertTrue(manualManager.state.value.initialized)
        assertTrue(manualManager.state.value.reconnectRequired)
    }
}
