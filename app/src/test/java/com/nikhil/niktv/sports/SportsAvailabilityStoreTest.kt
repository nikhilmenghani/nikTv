package com.nikhil.niktv.sports

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SportsAvailabilityStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("sports_availability_v1", 0).edit().clear().commit() }

    @Test fun manualFlagSurvivesFailureAndSuccessfulPlaybackUntilExplicitlyCleared() {
        val store = SportsAvailabilityStore(context) { 1234L }
        store.flag("channel", SportsFlagReason.MANUAL)
        store.flag("channel", SportsFlagReason.PLAYBACK_FAILED)
        store.clear("channel", automaticOnly = true)
        assertEquals(SportsChannelFlag(SportsFlagReason.MANUAL, 1234L), SportsAvailabilityStore(context).flags()["channel"])
        assertTrue(store.clear("channel").isEmpty())
    }

    @Test fun automaticFailureKeepsTimeAndCanClearAfterActualVideo() {
        val store = SportsAvailabilityStore(context) { 5678L }
        store.flag("disney", SportsFlagReason.PLAYBACK_FAILED)
        assertEquals(SportsChannelFlag(SportsFlagReason.PLAYBACK_FAILED, 5678L), SportsAvailabilityStore(context).flags()["disney"])
        assertTrue(store.clear("disney", automaticOnly = true).isEmpty())
        assertTrue(SportsAvailabilityStore(context).flags().isEmpty())
    }

    @Test fun filtersPersistWithoutAssumingUnflaggedChannelsAreWorking() {
        val store = SportsAvailabilityStore(context)
        assertEquals(SportsAvailabilityFilter.ALL, store.filter())
        val flag = SportsChannelFlag(SportsFlagReason.MANUAL, 1)
        assertTrue(SportsAvailabilityFilter.ALL.includes(flag)); assertTrue(SportsAvailabilityFilter.ALL.includes(null))
        assertFalse(SportsAvailabilityFilter.HIDE_FLAGGED.includes(flag)); assertTrue(SportsAvailabilityFilter.HIDE_FLAGGED.includes(null))
        assertTrue(SportsAvailabilityFilter.FLAGGED_ONLY.includes(flag)); assertFalse(SportsAvailabilityFilter.FLAGGED_ONLY.includes(null))
        store.setFilter(SportsAvailabilityFilter.HIDE_FLAGGED)
        assertEquals(SportsAvailabilityFilter.HIDE_FLAGGED, SportsAvailabilityStore(context).filter())
    }

    @Test fun flagsShareNativeIdentityAcrossTabsAndKeepPlaylistProvidersSeparateFromIptv() {
        val iptv = context.getSharedPreferences("profile_store", 0)
        iptv.edit().putString("fixture", "IPTV unchanged").commit()
        val before = iptv.all.toMap()
        val first = SportsCategory("one", "One", link = "https://one.example.test/list")
        val second = first.copy(id = "two")
        val a = SportsChannel("1", "Same", "", playlist = first)
        val b = a.copy(playlist = second)
        val store = SportsAvailabilityStore(context)
        store.flag(a.key, SportsFlagReason.MANUAL)
        assertNull(store.flags()[b.key])
        assertNotNull(store.flags()[a.copy(direct = SportsStream("https://rotated.example.test/live", "new")).key])
        store.setFilter(SportsAvailabilityFilter.FLAGGED_ONLY)
        assertEquals(before, iptv.all)
    }
}
