package com.nikhil.niktv.ui

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class IptvPinPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private val family get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.GENERAL))
    private val kids get() = UserScope("101", UserSyncStore.defaultProfileId("101", UserProfileType.KIDS))
    private val portal = PortalProfile("Pins", "https://pin-portal.invalid", "pin-mac", password = "pin-password")
    private val profile get() = portal.cacheKey()
    private val category = "guide"
    private val legacyKey get() = "$profile|channel|$category"
    private val preferences get() = IptvPinPreferences.preferences(context)
    private fun pins() = IptvPinPreferences.pinnedChannelOrder(context, profile, category)
    private fun toggle(id: String) = IptvPinPreferences.toggleChannel(context, profile, category, id)
    private suspend fun pinRecord(id: String) = users.snapshot("101").single {
        !it.deleted && it.profileId == family.profileId && it.recordId.endsWith("|entry-v2|$id")
    }

    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        context.getSharedPreferences("iptv_pin_preferences", 0).edit().clear().commit()
        users.selectAccount("101")
        users.initializeDefaults("101")
        users.selectProfile(family.profileId)
        users.associatePortal(family, portal)
        UserProfilePreferences.warm(context, family)
        Unit
    }

    @Test fun legacyOrderMigratesAtomicallyAndLaterLegacyUpdatesCannotResurrectUnpinnedChannels() = runBlocking {
        preferences.edit().putStringSet(legacyKey, setOf("first", "third"))
            .putString("$legacyKey|order", "[\"third\",\"first\",\"third\"]").commit()
        val oldRecords = users.snapshot("101").filter {
            it.recordId.contains("|channel|$category") && !it.deleted
        }
        assertEquals(listOf("third", "first"), pins())
        assertEquals(listOf("third", "first", "second"), toggle("second"))
        assertFalse(preferences.contains(legacyKey))
        assertFalse(preferences.contains("$legacyKey|order"))
        assertEquals(2, preferences.getInt("$legacyKey|format-v2", 0))
        assertEquals(setOf("third", "first", "second"), preferences.all.keys
            .filter { it.startsWith("$legacyKey|entry-v2|") }.map { it.substringAfter("|entry-v2|") }.toSet())
        assertEquals(listOf("third", "second"), toggle("first"))

        // An older app can still publish its aggregate list. Once migrated, it is ignored.
        users.merge("101", oldRecords.map { it.copy(revision = it.revision + 1000,
            deviceId = "old-device", mutationId = "old-${it.recordId}") })
        assertEquals(listOf("third", "second"), pins())
        assertEquals(-1L, Json.parseToJsonElement(pinRecord("first").payload).jsonObject
            .getValue("entry").jsonObject.getValue("value").jsonPrimitive.long)
    }

    @Test fun concurrentDifferentChannelPinsMergeAndRemoteUnpinIsATombstone() = runBlocking {
        toggle("alpha")
        toggle("bravo")
        val bravo = pinRecord("bravo")
        val remoteCharlie = concurrentPin(bravo, "charlie")
        users.merge("101", listOf(remoteCharlie))
        assertEquals(listOf("alpha", "bravo", "charlie"), pins())

        val staleSnapshot = users.snapshot("101")
        val remoteUnpin = bravo.copy(deleted = true, revision = remoteCharlie.revision + 1,
            deviceId = "other-device", mutationId = "remote-unpin-bravo")
        users.merge("101", listOf(remoteUnpin))
        users.merge("101", staleSnapshot)
        assertEquals(listOf("alpha", "charlie"), pins())
        assertEquals(listOf("alpha", "charlie", "bravo"), toggle("bravo"))

        val wire = Json.encodeToString(users.snapshot("101").filter { it.collection == "preferences" })
        for (secret in listOf("pin-portal.invalid", "pin-mac", "pin-password", "catalog-v5")) {
            assertFalse("Portal identity must stay portable: $secret", wire.contains(secret))
        }
    }

    @Test fun firstLegacyUnpinPublishesAuthoritativeStateAgainstConcurrentMigration() = runBlocking {
        preferences.edit().putStringSet(legacyKey, setOf("65", "80"))
            .putString("$legacyKey|order", "[\"65\",\"80\"]").commit()
        assertEquals(listOf("80"), toggle("65"))
        val unpin = pinRecord("65")
        val payload = Json.parseToJsonElement(unpin.payload).jsonObject
        assertEquals(-1L, payload.getValue("entry").jsonObject.getValue("value").jsonPrimitive.long)
        val migrationEntry = JsonObject(payload.getValue("entry").jsonObject + ("value" to JsonPrimitive(0L)))
        val concurrentMigration = unpin.copy(payload = JsonObject(payload + ("entry" to migrationEntry)).toString(),
            revision = unpin.revision - 1, deviceId = "migrating-device", mutationId = "migrated-65")
        val concurrentAddition = concurrentPin(pinRecord("80"), "90")
        users.merge("101", listOf(concurrentMigration, concurrentAddition))
        assertEquals(listOf("80", "90"), pins())
        assertEquals(unpin, pinRecord("65"))
    }

    @Test fun externalMergeNotifiesOpenPinSnapshotAndViewerSwitchStaysIsolated() = runBlocking {
        toggle("alpha")
        val capturedFamily = preferences
        val alpha = pinRecord("alpha")
        val changed = ConcurrentLinkedQueue<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> key?.let(changed::add) }
        capturedFamily.registerOnSharedPreferenceChangeListener(listener)
        try {
            val remoteBravo = concurrentPin(alpha, "bravo")
            users.merge("101", listOf(remoteBravo))
            withTimeout(5000) {
                while (changed.none { it.endsWith("|entry-v2|bravo") }) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals(listOf("alpha", "bravo"), IptvPinPreferences.snapshot(capturedFamily, profile).pinnedChannelOrder(category))

            users.selectProfile(kids.profileId)
            UserProfilePreferences.warm(context, kids)
            assertTrue(pins().isEmpty())
            toggle("kids-only")
            assertEquals(listOf("kids-only"), pins())
            assertEquals(listOf("alpha", "bravo"), IptvPinPreferences.snapshot(capturedFamily, profile).pinnedChannelOrder(category))
            assertTrue(IptvPinPreferences.snapshot(preferences, profile).pinnedCategories(CatalogType.LIVE_TV).isEmpty())
        } finally {
            capturedFamily.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test fun emptyV2CategoryIgnoresLegacySetAndConcurrentOrderTiesAreDeterministic() {
        assertEquals(listOf("bravo", "charlie"), IptvPinPreferences.pinnedChannelOrder(mapOf(
            "$legacyKey|format-v2" to 2, "$legacyKey|entry-v2|charlie" to 20L,
            "$legacyKey|entry-v2|bravo" to 20L, legacyKey to setOf("obsolete")), profile, category))
        assertTrue(IptvPinPreferences.pinnedChannelOrder(mapOf(
            "$legacyKey|format-v2" to 2, legacyKey to setOf("obsolete")), profile, category).isEmpty())
        assertTrue(IptvPinPreferences.pinnedChannelOrder(mapOf(
            "$legacyKey|format-v2" to 2, "$legacyKey|entry-v2|unpinned" to -1L), profile, category).isEmpty())
    }

    @Test fun disabledViewerPinRecordsWaitUntilThatViewerIsEnabled() = runBlocking {
        toggle("family-channel")
        users.selectProfile(kids.profileId)
        UserProfilePreferences.warm(context, kids)
        toggle("kids-channel")
        val kidsRecord = users.snapshot("101").single {
            it.profileId == kids.profileId && it.recordId.endsWith("|entry-v2|kids-channel")
        }
        val remoteUnpin = kidsRecord.copy(deleted = true, revision = kidsRecord.revision + 100,
            deviceId = "other-device", mutationId = "remote-kids-unpin")
        val local = object : SyncLocalReplica {
            override suspend fun merge(records: List<SyncRecord>) = users.merge("101", records)
            override suspend fun prepare(emptyRemoteAccount: Boolean) = Unit
            override suspend fun snapshot() = users.snapshot("101")
            override suspend fun pending() = users.pending("101")
            override suspend fun acknowledge(ids: Collection<String>) = users.acknowledge("101", ids)
        }
        val familyOnly = EnabledProfileReplica(local, setOf(family.profileId))
        familyOnly.merge(listOf(remoteUnpin))
        assertEquals(listOf("kids-channel"), pins())
        // Profile definitions always sync so a device can offer disabled viewers in settings.
        // Their pin/viewing records remain excluded until the viewer is enabled.
        assertTrue(familyOnly.pending().none { it.profileId == kids.profileId && it.collection != "profile" })
        assertEquals(remoteUnpin, familyOnly.snapshot().single { it.recordId == kidsRecord.recordId && it.profileId == kids.profileId })

        EnabledProfileReplica(local, setOf(family.profileId, kids.profileId)).merge(listOf(remoteUnpin))
        assertTrue(pins().isEmpty())
        assertEquals(listOf("family-channel"), IptvPinPreferences.snapshot(
            IptvPinPreferences.preferences(context, family), profile).pinnedChannelOrder(category))
    }

    private fun concurrentPin(template: SyncRecord, id: String): SyncRecord {
        val payload = Json.parseToJsonElement(template.payload).jsonObject
        val key = payload.getValue("mapKey").jsonPrimitive.content.substringBeforeLast("|entry-v2|") + "|entry-v2|$id"
        return template.copy(recordId = "portable_preferences:$key",
            payload = JsonObject(payload + ("mapKey" to JsonPrimitive(key))).toString(),
            revision = template.revision + 100, deviceId = "other-device", mutationId = "remote-pin-$id")
    }
}
