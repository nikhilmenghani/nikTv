package com.nikhil.niktv.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, read-only network validation on the connected debug account. Never uploads. */
class ManualPullOnlyDeviceTest {
    @Test fun selectedProfileCanDiscardTestEditsWithoutUploading(): Unit = runBlocking { withTimeout(180_000) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("validateManualPullOnly") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.nikhil.niktv.debug")
        val manager = AccountSyncManager.get(context)
        manager.restoreCachedAccount()
        val store = UserSyncStore.get(context)
        if (store.selectedScope.value == null) {
            val accountId = requireNotNull(store.selectedAccount.value)
            store.selectProfile(requireNotNull(store.rememberedProfileId(accountId)) { "Select the profile to pull first." })
        }
        val owner = requireNotNull(store.selectedScope.value) { "Select the profile to pull first." }
        manager.requestManualPullOnly(true).join()
        assertTrue(manager.manualPullOnly.value)
        manager.syncNow()
        assertNull(manager.state.value.error)
        assertTrue(manager.state.value.message.contains("No changes uploaded"))
        val serverCopy = store.snapshot(owner.accountId)
        val otherQueue = store.pending(owner.accountId).filter { it.profileId != owner.profileId }
        val profile = store.profiles.first().single { it.id == owner.profileId }
        try {
            store.updateProfile(profile.copy(name = "Manual pull validation"))
            assertTrue(store.pending(owner.accountId).any { it.profileId == owner.profileId })
            // The ordinary upload entry point must be blocked even if explicitly invoked.
            manager.syncForAccount(owner.accountId, requireRetry = false)
            assertEquals("Manual pull validation", store.profiles.first().single { it.id == profile.id }.name)
            manager.syncNow()
            assertNull(manager.state.value.error)
            assertEquals(profile, store.profiles.first().single { it.id == profile.id })
            assertTrue(store.pending(owner.accountId).none { it.profileId == owner.profileId })
            assertEquals(otherQueue, store.pending(owner.accountId))
            assertEquals(serverCopy.filter { it.profileId != owner.profileId },
                store.snapshot(owner.accountId).filter { it.profileId != owner.profileId })
        } finally {
            // Restore the downloaded copy if a network failure interrupted validation.
            if (store.profiles.first().any { it.id == profile.id && it.name == "Manual pull validation" })
                store.replaceProfileFromRemote(owner, serverCopy)
        }
    } }
}
