package com.nikhil.niktv.ui

import android.app.Application
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class IptvAvailabilityStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val first = "catalog-v5|STALKER|https://one.invalid|fixture-device"
    private val second = "catalog-v5|XTREAM|https://two.invalid|fixture-user"
    private val channel = MediaItem("same-id", "Fixture channel", null, null)
    private fun playing(profile: String = first) = PlayingMedia(channel, "https://stream.invalid/live", CatalogType.LIVE_TV, profileKey = profile)
    @Before fun reset() { context.getSharedPreferences("iptv_channel_availability_v1", 0).edit().clear().commit() }

    @Test fun flagsAndFiltersPersistPerProfileWithoutTouchingOtherStores() {
        val names = listOf("sports_availability_v1", "iptv_pin_preferences", "profile_store")
        val old = names.associateWith { name -> context.getSharedPreferences(name, 0).also { it.edit().putString("fixture", "unchanged").commit() }.all.toMap() }
        val store = IptvAvailabilityStore(context) { 1234 }
        store.flag(first, channel.id, IptvFlagReason.MANUAL)
        store.setFilter(first, IptvAvailabilityFilter.HIDE_FLAGGED)
        val reopened = IptvAvailabilityStore(context)
        assertEquals(IptvChannelFlag(IptvFlagReason.MANUAL, 1234), reopened.flags(first)[channel.id])
        assertTrue(reopened.flags(second).isEmpty())
        assertEquals(IptvAvailabilityFilter.ALL, reopened.filter(second))
        assertEquals(IptvAvailabilityFilter.HIDE_FLAGGED, reopened.filter(first))
        assertFalse(store.prefs.all.toString().contains("fixture-device"))
        assertFalse(store.prefs.all.toString().contains("https://"))
        names.forEach { assertEquals(old[it], context.getSharedPreferences(it, 0).all) }
    }

    @Test fun actualVideoClearsAutomaticFlagsButPreservesManualDecisions() {
        val store = IptvAvailabilityStore(context)
        val playback = playing()
        store.observe(first, playback, playback, failed = true)
        assertEquals(IptvFlagReason.PLAYBACK_FAILED, store.flags(first)[channel.id]?.reason)
        store.observe(first, playback, playback, failed = false)
        assertTrue(store.flags(first).isEmpty())
        store.flag(first, channel.id, IptvFlagReason.MANUAL)
        store.observe(first, playback, playback, failed = true)
        store.observe(first, playback, playback, failed = false)
        assertEquals(IptvFlagReason.MANUAL, store.flags(first)[channel.id]?.reason)
        store.clear(first, channel.id)
        assertTrue(store.flags(first).isEmpty())
    }

    @Test fun staleAttemptsClosedPlayersOtherProfilesOfflineAndVodCannotChangeLiveFlags() {
        val store = IptvAvailabilityStore(context)
        val active = playing()
        val stale = active.copy(playbackAttemptId = "old")
        store.observe(first, active, stale, true)
        store.observe(first, null, active, true)
        store.observe(second, active, active, true)
        store.observe(first, active.copy(offlinePlayback = true), active.copy(offlinePlayback = true), true)
        store.observe(first, active.copy(catalogType = CatalogType.MOVIES), active.copy(catalogType = CatalogType.MOVIES), true)
        assertTrue(store.flags(first).isEmpty()); assertTrue(store.flags(second).isEmpty())
        store.flag(first, channel.id, IptvFlagReason.PLAYBACK_FAILED)
        store.observe(first, active, stale, false)
        assertNotNull(store.flags(first)[channel.id])
    }

    @Test fun filtersComposeWithQueueSearchAndSourcesWithoutLeakingToVod() {
        val flag = IptvChannelFlag(IptvFlagReason.MANUAL, 1)
        assertTrue(IptvAvailabilityFilter.ALL.includes(flag)); assertTrue(IptvAvailabilityFilter.ALL.includes(null))
        assertFalse(IptvAvailabilityFilter.HIDE_FLAGGED.includes(flag)); assertTrue(IptvAvailabilityFilter.HIDE_FLAGGED.includes(null))
        assertTrue(IptvAvailabilityFilter.FLAGGED_ONLY.includes(flag)); assertFalse(IptvAvailabilityFilter.FLAGGED_ONLY.includes(null))
        val filter = PlaybackQueueFilter(query = "Fixture", sourceIds = setOf("sports"), excludedIds = setOf(channel.id))
        val item = channel.copy(portalCategoryId = "sports")
        assertFalse(filter.includes(item, CatalogType.LIVE_TV))
        assertTrue(filter.includes(item.copy(id = "working"), CatalogType.LIVE_TV))
        assertFalse(filter.includes(item.copy(id = "working", portalCategoryId = "news"), CatalogType.LIVE_TV))
        assertTrue(filter.includes(item, CatalogType.MOVIES))
    }
}
