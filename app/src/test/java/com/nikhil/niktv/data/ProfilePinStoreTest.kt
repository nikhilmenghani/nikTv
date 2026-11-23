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
