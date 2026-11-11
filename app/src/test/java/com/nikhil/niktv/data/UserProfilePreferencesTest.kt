package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import com.nikhil.niktv.model.*
import com.nikhil.niktv.sports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import androidx.room.withTransaction
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class UserProfilePreferencesTest {
    @Test fun portalAccessIsProfileSpecificPortableAndDoesNotExposeConnectionDetails() = runBlocking {
        val portal = PortalProfile("Private", "http://private-provider.test", "00:11:22:33:44:55")
        assertTrue(ProfilePortalAccess.allowed(context, portal, family()))
        assertTrue(ProfilePortalAccess.allowed(context, portal, kids()))
        withTimeout(5_000) { ProfilePortalAccess.setAllowed(context, family(), ProfilePortalAccess.key(portal), false) }
        assertFalse(ProfilePortalAccess.allowed(context, portal, family()))
        assertTrue(ProfilePortalAccess.allowed(context, portal, kids()))
        users.selectProfile(kids().profileId)
        ProfilePortalAccess.setAllowed(context, family(), ProfilePortalAccess.SPORTS, false)
        assertTrue(ProfilePortalAccess.hidden(context, kids()).isEmpty())
        assertEquals(setOf(ProfilePortalAccess.key(portal), ProfilePortalAccess.SPORTS), ProfilePortalAccess.hidden(context, family()))
        val records = users.snapshot("101").filter { it.payload.contains("profile_portal_access_v1") }
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.profileId == family().profileId })
        assertFalse(records.any { it.payload.contains("private-provider") || it.payload.contains("00:11:22:33:44:55") })
        ProfilePortalAccess.setAllowed(context, family(), ProfilePortalAccess.key(portal), true)
        assertTrue(ProfilePortalAccess.allowed(context, portal, family()))
        assertTrue(ProfilePortalAccess.allowed(context, portal.copy(name = "Renamed"), family()))
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private fun family(account: String = "101") = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
    private fun kids() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))

    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        listOf("iptv_pin_preferences", "sports_library_v1", "browse_collections", "player_media_browser",
            "sports_browse", "sports_availability_v1", "iptv_channel_availability_v1").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        users.selectAccount("101"); users.initializeDefaults("101"); users.selectProfile(family().profileId)
        UserProfilePreferences.warm(context, family())
        Unit
    }

    @Test fun capturedPreferenceEditorNeverWritesIntoNewPersonOrAccount() = runBlocking {
        val captured = UserProfilePreferences.get(context, "iptv_pin_preferences")
        captured.edit().putString("pins", "family").commit()
        users.selectProfile(kids().profileId)
        val kidsPrefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        assertFalse(kidsPrefs.contains("pins"))
        captured.edit().putString("pins", "late-family-write").commit()
        assertEquals("late-family-write", captured.getString("pins", null))
        assertFalse(kidsPrefs.contains("pins"))
        users.selectAccount("202"); users.initializeDefaults("202"); users.selectProfile(family("202").profileId)
        assertFalse(UserProfilePreferences.get(context, "iptv_pin_preferences").contains("pins"))
    }

    @Test fun preferenceCommitCannotDeadlockUiContinuationOfAnotherStorageEdit() = runBlocking {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "fixture-ui").apply { isDaemon = true }
        }
        val ui = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = java.util.concurrent.CountDownLatch(1)
        val prefs = UserProfilePreferences.get(context, "discovery_trial_v1")
        try {
            val holder = scope.launch {
                users.withEditLock { entered.complete(Unit); release.await() }
            }
            withTimeout(3_000) { entered.await() }
            val writer = executor.submit<Boolean> {
                started.countDown()
                prefs.edit().putString("sections", "MOVIES").commit()
            }
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            // The UI is synchronously waiting for preferences while the earlier coroutine
            // needs to finish/release its lock. Unlock must not require a UI continuation.
            delay(50)
            release.complete(Unit)
            assertTrue(writer.get(3, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(3_000) { holder.join() }
            assertEquals("MOVIES", prefs.getString("sections", null))
            assertTrue(users.pending("101").any { it.collection == "preferences" })
        } finally {
            release.complete(Unit)
            scope.cancel()
            executor.shutdownNow()
            ui.close()
        }
    }

    @Test fun browseMutationsArePerKeyDurableAndPortalSecretsAreRemoved() = runBlocking {
        val portal = PortalProfile("Editable", "https://credential-fixture.invalid", "fixture-mac", password = "fixture-password")
        users.associatePortal(family(), portal)
        val prefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        prefs.edit().putStringSet(portal.cacheKey() + "|category|MOVIES", setOf("one", "two")).commit()
        prefs.edit().putString("other", "LIST").commit()
        val records = users.pending("101").filter { it.collection == "preferences" }
        assertEquals(2, records.map { it.recordId }.distinct().size)
        val wire = Json.encodeToString(records)
        listOf("credential-fixture", "fixture-mac", "fixture-password", "catalog-v5").forEach { assertFalse(it, wire.contains(it)) }
        val beforeDelete = users.snapshot("101")
        prefs.edit().remove("other").commit()
        users.merge("101", beforeDelete)
        assertFalse(prefs.contains("other"))
        assertEquals(setOf("one", "two"), prefs.getStringSet(portal.cacheKey() + "|category|MOVIES", null))
    }

    @Test fun explicitLegacyImportIsIdempotentAndKidsStartsEmpty() = runBlocking {
        context.getSharedPreferences("iptv_pin_preferences", Context.MODE_PRIVATE).edit().putString("pins", "legacy").commit()
        assertTrue(UserProfilePreferences.hasLegacy(context))
        assertFalse(UserProfilePreferences.get(context, "iptv_pin_preferences").contains("pins"))
        UserProfilePreferences.importLegacy(context, family())
        val familyPrefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        assertEquals("legacy", familyPrefs.getString("pins", null))
        familyPrefs.edit().remove("pins").commit()
        UserProfilePreferences.importLegacy(context, family())
        assertFalse(familyPrefs.contains("pins"))
        assertFalse(UserProfilePreferences.get(context, "iptv_pin_preferences", owner = kids()).contains("pins"))
    }

    @Test fun sportsFavoritesSyncPerProviderChannelWithoutResolvedStreams() = runBlocking {
        val library = SportsLibraryStore(context)
        val one = SportsChannel("one", "One", "one", image = "https://fixture.invalid/image?token=secret", formats = listOf(SportsFormat("HD", "https://fixture.invalid/secret")))
        val two = SportsChannel("two", "Two", "two")
        library.toggle(one); library.toggle(two)
        assertEquals(setOf("one", "two"), library.favourites().map { it.id }.toSet())
        val records = users.snapshot("101").filter { it.recordId.startsWith("portable_preferences:sports_favorites_v2|") }
        assertEquals(2, records.size)
        val wire = Json.encodeToString(records)
        assertFalse(wire.contains("fixture.invalid")); assertFalse(wire.contains("secret"))
        val old = users.snapshot("101")
        library.toggle(one)
        users.merge("101", old)
        assertEquals(listOf("two"), library.favourites().map { it.id })
        users.selectProfile(kids().profileId)
        assertTrue(SportsLibraryStore(context).favourites().isEmpty())
    }

    @Test fun cachedGettersStayImmediateWhileStorageTransactionIsBusy() = runBlocking {
        val prefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        prefs.edit().putString("pins", "committed").commit()
        assertEquals("committed", prefs.getString("pins", null))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = CoroutineScope(Dispatchers.IO).async {
            users.withEditLock { users.db.withTransaction { entered.complete(Unit); release.await() } }
        }
        val executor = Executors.newSingleThreadExecutor()
        entered.await()
        try {
            val reads = executor.submit(Callable {
                repeat(10_000) {
                    assertEquals("committed", prefs.getString("pins", null))
                    assertTrue(prefs.contains("pins"))
                    assertEquals("committed", prefs.all["pins"])
                }
                true
            })
            assertTrue(reads.get(2, TimeUnit.SECONDS))
        } finally {
            release.complete(Unit)
            transaction.await()
            executor.shutdownNow()
        }
    }

    @Test fun externalMergeRefreshesCacheBeforeReturningAndQueuedInvalidationsCannotRegressIt() = runBlocking {
        val prefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        prefs.edit().putString("pins", "original").commit()
        val original = users.snapshot("101").single { it.recordId == "portable_preferences:iptv_pin_preferences|pins" }
        val payload = Json.parseToJsonElement(original.payload).jsonObject
        val remoteEntry = JsonObject(payload.getValue("entry").jsonObject + ("value" to JsonPrimitive("remote")))
        val remote = original.copy(payload = JsonObject(payload + ("entry" to remoteEntry)).toString(),
            revision = original.revision + 100, deviceId = "remote", mutationId = "remote-mutation")
        users.merge("101", listOf(remote))
        assertEquals("remote", prefs.getString("pins", null))
        prefs.edit().putString("pins", "new-local").commit()
        assertEquals("new-local", prefs.getString("pins", null))
        delay(100L)
        assertEquals("new-local", prefs.getString("pins", null))
    }

    @Test fun deletedProfileInvalidatesCapturedPreferenceCacheAndRejectsLateWrites() = runBlocking {
        val prefs = UserProfilePreferences.get(context, "iptv_pin_preferences")
        prefs.edit().putString("pins", "family").commit()
        users.deleteProfile(family().profileId)
        assertFalse(prefs.contains("pins"))
        prefs.edit().putString("pins", "late-callback").commit()
        assertFalse(prefs.contains("pins"))
    }
}
