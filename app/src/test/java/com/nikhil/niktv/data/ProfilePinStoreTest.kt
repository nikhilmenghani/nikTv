package com.nikhil.niktv.data

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ProfilePinStoreTest {
    @Test fun stalePasswordlessMetadataCannotEraseEitherSideKnownPassword() = runBlocking {
        val store = UserSyncStore.get(RuntimeEnvironment.getApplication())
        withContext(Dispatchers.IO) { store.db.clearAllTables() }
        store.disconnect(); store.selectAccount("101")
        val profile = store.createProfile("Original")
        val original = store.snapshot("101").single { it.collection == "profile" }
        val verifier = ProfilePin.create("1357")
        val withPin = original.copy(payload = Json.encodeToString(profile.copy(pinVerifier = verifier)),
            revision = original.revision + 10, mutationId = "remote-password")
        store.updateProfile(profile.copy(name = "Local rename"))
        // Even a locally newer ordinary edit without the new field retains the cloud PIN.
        val passwordless = original.copy(payload = Json.encodeToString(profile.copy(name = "Newer rename")),
            revision = withPin.revision + 10, mutationId = "newer-old-client")
        store.merge("101", listOf(passwordless))
        store.merge("101", listOf(withPin))
        assertEquals(verifier, store.profiles.first().single().pinVerifier)
        assertEquals("Newer rename", store.profiles.first().single().name)
        // A newer remote old-client edit cannot remove the now-known local PIN either.
        val revision = store.snapshot("101").maxOf { it.revision }
        store.merge("101", listOf(passwordless.copy(revision = revision + 1, mutationId = "another-old-client")))
        assertEquals(verifier, store.profiles.first().single().pinVerifier)
        assertTrue(store.pending("101").any { it.collection == "profile" &&
            Json.decodeFromString<UserProfile>(it.payload).pinVerifier == verifier })
    }
    @Test fun pinSurvivesStaleProfileEditsAndIsIncludedInSync() = runBlocking {
        val store = UserSyncStore.get(RuntimeEnvironment.getApplication())
        withContext(Dispatchers.IO) { store.db.clearAllTables() }
        store.disconnect()
        store.selectAccount("101")
        val original = store.createProfile("PIN fixture")
        val verifier = ProfilePin.create("1357")
        store.setProfilePin(original.id, verifier, onlyIfUnset = true)
        store.updateProfile(original.copy(name = "Updated name"))
        val current = store.profiles.first().single()
        assertEquals("Updated name", current.name)
        assertEquals(verifier, current.pinVerifier)
        val record = store.snapshot("101").single { it.collection == "profile" && it.recordId == original.id }
        assertEquals(verifier, Json.decodeFromString<UserProfile>(record.payload).pinVerifier)
        assertTrue(store.pending("101").any { it.collection == "profile" &&
            Json.decodeFromString<UserProfile>(it.payload).pinVerifier == verifier })
        try {
            store.setProfilePin(original.id, ProfilePin.create("2468"), onlyIfUnset = true)
            fail("A stale first-time setup must not replace an existing password")
        } catch (_: IllegalStateException) { }
        assertEquals(verifier, store.profiles.first().single().pinVerifier)
        val changed = store.setProfilePin(original.id, ProfilePin.create("9753"))
        assertFalse(ProfilePin.verifies(changed.pinVerifier, "1357"))
        assertTrue(ProfilePin.verifies(changed.pinVerifier, "9753"))
        val synced = store.snapshot("101").single { it.collection == "profile" && it.recordId == original.id }
        withContext(Dispatchers.IO) { store.db.clearAllTables() }
        store.merge("101", listOf(synced))
        assertTrue(ProfilePin.verifies(store.profiles.first().single().pinVerifier, "9753"))
    }
}
