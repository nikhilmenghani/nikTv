package com.nikhil.niktv.ui

import com.nikhil.niktv.data.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ProfilePinRequestTest {
    private val unset = UserProfile("fixture", "Fixture")
    @Test fun offlineFallbackNeverSetsOrRemembersAnUnknownPassword() = runBlocking {
        val request = ProfilePinRequest(unset, false) { error("offline") }
        assertFalse(request.useOffline())
        request.checkRemotePassword()
        assertTrue(request.useOffline())
        assertTrue(request.usingOffline)
        assertFalse(request.checking)
        assertFalse(request.setupVerified)
        assertNull(request.newPinVerifier)
        assertFalse(request.completeSetup("replacement"))
        assertTrue(com.nikhil.niktv.data.ProfilePin.verifies(request.profile.pinVerifier, "2564"))
        assertFalse(com.nikhil.niktv.data.ProfilePin.verifies(request.profile.pinVerifier, "1234"))
        assertTrue(request.completeUnlock(null))
        val destructive = ProfilePinRequest(unset, true) { error("offline") }
        destructive.checkRemotePassword()
        assertFalse(destructive.useOffline())
    }
    @Test fun missingLocalPasswordRequiresSuccessfulRemoteVerification() = runBlocking {
        var fail = true
        val request = ProfilePinRequest(unset, false) { if (fail) error("offline") else unset }
        assertTrue(request.checking)
        assertFalse(request.completeSetup("new-verifier"))
        request.checkRemotePassword()
        assertNotNull(request.checkError)
        assertFalse(request.setupVerified)
        assertFalse(request.completeSetup("new-verifier"))
        fail = false
        request.checkRemotePassword()
        assertNull(request.checkError)
        assertTrue(request.setupVerified)
        assertTrue(request.completeSetup("new-verifier"))
    }

    @Test fun remotePasswordSelectsUnlockAndRejectsStaleVerification() = runBlocking {
        val known = unset.copy(pinVerifier = "remote-verifier")
        val request = ProfilePinRequest(unset, false) { known }
        request.checkRemotePassword()
        assertEquals(known, request.profile)
        assertFalse(request.setupVerified)
        assertFalse(request.completeSetup("replacement"))
        assertFalse(request.completeUnlock(null))
        assertTrue(request.completeUnlock(known.pinVerifier))
        assertEquals(known.pinVerifier, request.authorizedVerifier)
    }

    @Test fun backgroundSyncWinsOverAnOlderInFlightReadAndFailure() = runBlocking {
        for (fails in listOf(false, true)) {
            val response = CompletableDeferred<UserProfile>()
            val request = ProfilePinRequest(unset, false) { response.await() }
            val checking = async { request.checkRemotePassword() }
            yield()
            request.refresh(unset.copy(pinVerifier = "synced-verifier"))
            if (fails) response.completeExceptionally(IllegalStateException("offline")) else response.complete(unset)
            checking.await()
            assertEquals("synced-verifier", request.profile.pinVerifier)
            assertFalse(request.checking)
            assertNull(request.checkError)
            assertFalse(request.setupVerified)
        }
    }

    @Test fun passwordArrivingAfterSetupOpenedDisablesSetup() = runBlocking {
        val request = ProfilePinRequest(unset, false) { unset }
        request.checkRemotePassword()
        assertTrue(request.setupVerified)
        request.refresh(unset.copy(pinVerifier = "arrived-later"))
        request.refresh(unset) // A stale local flow emission must not restore setup.
        assertEquals("arrived-later", request.profile.pinVerifier)
        assertFalse(request.setupVerified)
        assertFalse(request.completeSetup("replacement"))
        assertFalse(request.result.isCompleted)
    }
}
