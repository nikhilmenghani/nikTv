package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nikhil.niktv.data.UserSyncStore
import org.junit.rules.TemporaryFolder
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PortalSessionOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var storeScope: CoroutineScope
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private lateinit var store: ProfileStore
    private val a = PortalProfile("A", "https://a.invalid", "00:00:00:00:00:01")
    private val b = PortalProfile("B", "https://b.invalid", "00:00:00:00:00:02")
    private fun session(p: PortalProfile, token: String) = PortalSession(p, token, "https://fixture.invalid/portal.php", "", "", "")
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val context = RuntimeEnvironment.getApplication()
        UserSyncStore.get(context).disconnect()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { temporary.newFolder().resolve("session-ownership.preferences_pb") })
        store = ProfileStore(context, preferences)
        vm = NikTvViewModel(context, store)
        vm.viewModelScope.coroutineContext.cancelChildren()
        @Suppress("UNCHECKED_CAST")
        val field = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        state = field
        state.value = NikTvState(savedProfile = a, session = session(a, "old"), restoring = false)
    }
    @After fun stop() {
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }
    @Test fun delayedARefreshCannotOverwriteBOrPersistItAsActive() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        vm.authenticatePortal = { started.complete(Unit); finish.await(); session(a, "late") }
        val work = async(Dispatchers.Default) { runCatching { vm.refreshSession(a) } }
        withTimeout(5_000) { started.await() }
        withTimeout(5_000) { store.save(session(b, "b")) }
        state.value = state.value.copy(savedProfile = b, session = session(b, "b"))
        finish.complete(Unit)
        assertTrue(withTimeout(5_000) { work.await() }.exceptionOrNull() is CancellationException)
        assertEquals("b", state.value.session?.token)
        assertEquals("b", withTimeout(5_000) { store.activeSession.first() }?.token)
        assertNull(store.sessionFor(a)?.takeIf { it.token == "late" })
    }
    @Test fun tokenRotationRetriesOnlyTheStillCurrentOperationOnce() = runBlocking {
        var attempts = 0
        val result = vm.withAutomaticSessionRetry(state.value.session!!) { active ->
            attempts++
            if (attempts == 1) {
                state.value = state.value.copy(session = session(a, "new"))
                throw NikTvViewModel.SessionRotatedDuringPlayback()
            }
            active.token
        }
        assertEquals("new", result)
        assertEquals(2, attempts)
    }
    @Test fun repeatedTokenRotationIsBounded() = runBlocking {
        var attempts = 0
        assertTrue(runCatching {
            vm.withAutomaticSessionRetry(state.value.session!!) {
                attempts++; throw NikTvViewModel.SessionRotatedDuringPlayback()
            }
        }.isFailure)
        assertEquals(2, attempts)
    }
    @Test fun delayedRefreshCannotAdoptAReactivatedPortalWithTheSameIdentity() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        vm.authenticatePortal = { started.complete(Unit); finish.await(); session(a, "obsolete") }
        val work = async(Dispatchers.Default) { runCatching { vm.refreshSession(a) } }
        withTimeout(5_000) { started.await() }
        vm.switchProfile(b)
        vm.switchProfile(a)
        vm.viewModelScope.coroutineContext.cancelChildren()
        state.value = state.value.copy(savedProfile = a, session = session(a, "reactivated"))
        finish.complete(Unit)
        assertTrue(withTimeout(5_000) { work.await() }.exceptionOrNull() is CancellationException)
        assertEquals("reactivated", state.value.session?.token)
    }
    @Test fun manualAuthenticationTimesOutAndRestoresDismissal() = runTest {
        state.value = state.value.copy(error = "Authorization failed")
        vm.authenticatePortal = { awaitCancellation() }
        vm.reauthenticate()
        runCurrent()
        assertTrue(state.value.reauthenticating)
        advanceTimeBy(20_001)
        runCurrent()
        assertFalse(state.value.reauthenticating)
        assertTrue(state.value.error.orEmpty().contains("20 seconds"))
        vm.dismissError()
        assertNull(state.value.error)
    }
    @Test fun dismissingDuringAuthenticationCancelsItAndCannotReopenTheDialog() = runTest {
        state.value = state.value.copy(error = "Authorization failed")
        var canceled = false
        vm.authenticatePortal = { try { awaitCancellation() } finally { canceled = true } }
        vm.reauthenticate()
        runCurrent()
        vm.dismissError()
        runCurrent()
        assertTrue(canceled)
        assertFalse(state.value.reauthenticating)
        assertNull(state.value.error)
        assertEquals("old", state.value.session?.token)
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(state.value.error)
    }
    @Test fun repeatedAuthenticationClicksCannotQueueDuplicateAttempts() = runTest {
        var requests = 0
        vm.authenticatePortal = { requests++; awaitCancellation() }
        vm.reauthenticate()
        vm.reauthenticate()
        runCurrent()
        assertEquals(1, requests)
        vm.dismissError()
    }
    @Test fun changingPortalCancelsManualAuthenticationAndClearsBusyState() = runTest {
        vm.authenticatePortal = { awaitCancellation() }
        vm.reauthenticate()
        runCurrent()
        assertTrue(state.value.reauthenticating)
        vm.switchProfile(b)
        assertFalse(state.value.reauthenticating)
        vm.viewModelScope.coroutineContext.cancelChildren()
        runCurrent()
    }
}
