package com.nikhil.niktv.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class RecordingOwnershipTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val family = UserScope("101", "11111111-2222-3333-4444-555555555555")
    private val kids = UserScope("101", "66666666-2222-3333-4444-555555555555")
    private val otherAccount = family.copy(accountId = "202")
    private fun media(id: Int) = RecordedLiveTvMedia(Uri.parse("content://media/external/downloads/$id"),
        "fixture-secret https://fixture.invalid/stream?token=fixture", 100, 0, 1000)

    @Before fun reset() { context.getSharedPreferences("niktv_recording_ownership", 0).edit().clear().commit() }

    @Test fun recordingsAreIsolatedByBothAccountAndPersonAndRemainUnassignedWithoutOwner() {
        val store = RecordingOwnership(context)
        val one = media(1); val two = media(2); val legacy = media(3)
        store.created(one.uri, family); store.created(two.uri, kids); store.created(legacy.uri, null)
        val all = listOf(one, two, legacy)
        assertEquals(listOf(one), store.visible(all, family))
        assertEquals(listOf(two), store.visible(all, kids))
        assertTrue(store.visible(all, otherAccount).isEmpty())
        assertTrue(store.visible(all, null).isEmpty())
        assertEquals(listOf(legacy), store.unassigned(all))
        assertEquals(listOf(one), RecordingOwnership(context).visible(all, family))
    }

    @Test fun explicitImportAssignsOnlyMissingRecordingsAndCannotTakeAnotherPersonsMedia() {
        val store = RecordingOwnership(context)
        val one = media(1); val two = media(2); val three = media(3)
        store.created(one.uri, family)
        assertEquals(1, store.assignUnassigned(kids, listOf(one, two, two)))
        assertEquals(1, store.assignUnassigned(otherAccount, listOf(one, two, three)))
        assertEquals(0, store.assignUnassigned(otherAccount, listOf(one, two, three)))
        assertEquals(listOf(one), store.visible(listOf(one, two, three), family))
        assertEquals(listOf(two), store.visible(listOf(one, two, three), kids))
        assertEquals(listOf(three), store.visible(listOf(one, two, three), otherAccount))
        store.forget(one.uri)
        assertEquals(listOf(one), store.unassigned(listOf(one, two, three)))
    }

    @Test fun ownershipMetadataContainsOnlyMediaStoreUriAndScopeAndRejectsStreamUris() {
        val store = RecordingOwnership(context)
        val one = media(1)
        store.assignUnassigned(family, listOf(one))
        val stream = one.copy(uri = Uri.parse("https://fixture.invalid/stream?token=fixture-secret"))
        assertEquals(0, store.assignUnassigned(family, listOf(stream)))
        val values = context.getSharedPreferences("niktv_recording_ownership", 0).all
        assertEquals(setOf(one.uri.toString()), values.keys)
        val payload = JSONObject(values.values.single() as String)
        assertEquals(setOf("accountId", "profileId"), payload.keys().asSequence().toSet())
        assertFalse(values.toString().contains("fixture"))
    }

    @Test fun serviceIntentCapturesOriginalOwnerAndLegacyStartsDoNotAdoptCurrentSelection() {
        val captured = Intent().putRecordingOwner(family)
        assertEquals(family, captured.recordingOwner())
        val newerSelection = otherAccount
        assertNotEquals(newerSelection, captured.recordingOwner())
        assertNull(Intent().putRecordingOwner(null).recordingOwner())
        assertNull(Intent().putExtra(LiveTvRecordingService.EXTRA_OWNER_ACCOUNT, "101")
            .putExtra(LiveTvRecordingService.EXTRA_OWNER_PROFILE, "invalid").recordingOwner())
    }
}
