package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IptvAvailabilityDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
    private val category = Category("fixture", "Fixture Live TV", CatalogType.LIVE_TV)
    private val rows = listOf(MediaItem("one", "Fixture channel one", null, null, portalCategoryId = category.id),
        MediaItem("two", "Fixture channel two", null, null, portalCategoryId = category.id))
    private fun await(label: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(70) }
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val name = listOfNotNull(node.text, node.contentDescription).joinToString(" / ")
            if (name.isNotBlank() || node.isFocused) visible += "${if (node.isFocused) "FOCUSED " else ""}$name"
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(ins.uiAutomation.rootInActiveWindow)
        assertTrue("$label; visible=${visible.joinToString(" | ")}", condition())
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.contentDescription?.toString() == label || n.text?.toString()?.lineSequence()?.any { it == label } == true) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun focused(label: String): Boolean { var n = find(label); repeat(10) { if (n?.isFocused == true) return true; n = n?.parent }; return false }
    private fun click(label: String) {
        await("Visible $label") { find(label) != null }
        var n = find(label)
        repeat(10) { if (n?.isClickable != true) n = n?.parent }
        assertTrue("Clickable $label", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); Thread.sleep(160) }
    private fun longClick(label: String) {
        var n = find(label)
        repeat(10) { if (n?.isLongClickable != true) n = n?.parent }
        assertTrue("Held tile $label", n?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
    }
    private fun hold() {
        ins.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 120L)
        ins.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
    }
    private fun menuAction(label: String) {
        await("Menu contains $label") { find(label) != null }
        repeat(16) { if (focused(label)) return; key(KeyEvent.KEYCODE_DPAD_DOWN) }
        fail("D-pad cannot reach $label")
    }
    private fun host(): PlaybackFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.targetContext.getSharedPreferences("iptv_channel_availability_v1", 0).edit().clear().commit()
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
    }

    @Test fun heldTileFlagsFiltersAndUnflagsWithoutPlaybackAndRecoversFromEmptyResults() {
        val activity = host()
        val store = IptvAvailabilityStore(ins.targetContext)
        var plays = 0
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                ModernIptvCollection(NikTvState(savedProfile = profile, selectedCategory = category, items = rows, loading = false, restoring = false),
                    category, {}, { plays++ }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, isTv = true)
            } } }
            await("First live tile receives D-pad focus") { focused(rows.first().title) }
            hold()
            await("Hold reveals flag action without executing it") { find("Flag as unavailable") != null }
            assertTrue(store.flags(profile.cacheKey()).isEmpty())
            menuAction("Flag as unavailable"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Manual marker appears and tile focus returns") { find("Flagged unavailable") != null && focused(rows.first().title) }
            assertEquals(IptvFlagReason.MANUAL, IptvAvailabilityStore(ins.targetContext).flags(profile.cacheKey())["one"]?.reason)
            click("Availability: All channels")
            await("Chooser initially focuses current filter") { focused("All channels") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Hide flagged preserves the other tile") { find(rows.first().title) == null && find(rows.last().title) != null && focused("Availability: Hide flagged") }
            assertEquals(IptvAvailabilityFilter.HIDE_FLAGGED, IptvAvailabilityStore(ins.targetContext).filter(profile.cacheKey()))
            click("Availability: Hide flagged")
            await("Filter chooser starts on saved selection") { focused("Hide flagged") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Flagged only shows the flagged tile") { find(rows.first().title) != null && find(rows.last().title) == null && focused("Availability: Flagged only") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down through search enters channel results") { focused(rows.first().title) }
            hold(); menuAction("Remove flag"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Removing last flagged tile restores filter focus") { store.flags(profile.cacheKey()).isEmpty() && find(rows.first().title) == null && focused("Availability: Flagged only") }
            click("Availability: Flagged only")
            await("All channels remains accessible from empty state") { find("All channels") != null }
            click("All channels")
            await("Both channels return") { find(rows.first().title) != null && find(rows.last().title) != null }
            assertEquals("No stream may open during flag/filter operations", 0, plays)
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun profileSwitchDoesNotInheritFlagsOrFiltersAndVodHasNoAvailabilityControl() {
        val activity = host()
        val store = IptvAvailabilityStore(ins.targetContext)
        store.flag(profile.cacheKey(), "one", IptvFlagReason.MANUAL)
        store.setFilter(profile.cacheKey(), IptvAvailabilityFilter.FLAGGED_ONLY)
        val selected = mutableStateOf(profile)
        val selectedCategory = mutableStateOf(category)
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                ModernIptvCollection(NikTvState(savedProfile = selected.value, items = rows, loading = false, restoring = false),
                    selectedCategory.value, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, isTv = true)
            } } }
            await("First profile reads its marker/filter") { find("Flagged unavailable") != null && find(rows.last().title) == null }
            ins.runOnMainSync { selected.value = profile.copy(portalUrl = "https://other.invalid") }
            await("Other profile sees both unflagged channels") { find("Availability: All channels") != null && find(rows.last().title) != null && find("Flagged unavailable") == null }
            ins.runOnMainSync { selectedCategory.value = category.copy(type = CatalogType.MOVIES) }
            await("Movie collection has no channel availability control") { find("Availability: All channels") == null }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun media3FinalFailureAndRealVideoUpdateAutomaticFlags() = exercisePlayer(PlaybackEngine.MEDIA3)
    @Test fun vlcFinalFailureAndRealVideoUpdateAutomaticFlags() = exercisePlayer(PlaybackEngine.VLC)

    @Test fun favoritesAndRecentTilesShareFlagsAndRecoverFocusWithoutDeletingEntries() {
        val activity = host()
        val store = IptvAvailabilityStore(ins.targetContext)
        val favorites = rows.map { FavoriteItem(FavoriteKind.CHANNEL, it, profileKey = profile.cacheKey()) }
        val recents = rows.map { RecentItem(FavoriteKind.CHANNEL, it, profileKey = profile.cacheKey(), categoryTitle = category.title) }
        try {
            store.flag(profile.cacheKey(), "one", IptvFlagReason.MANUAL)
            store.setFilter(profile.cacheKey(), IptvAvailabilityFilter.FLAGGED_ONLY)
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                DiscoveryFavoritesContent(NikTvState(savedProfile = profile, favorites = favorites), {}, {})
            } } }
            await("My List uses profile flag/filter") { find(rows.first().title) != null && find(rows.last().title) == null && find("Flagged unavailable") != null }
            longClick(rows.first().title); click("Remove flag")
            await("Removing final My List match recovers focus") { find(rows.first().title) == null && focused("Availability: Flagged only") }
            assertEquals(2, favorites.size)
            ins.runOnMainSync {
                store.flag(profile.cacheKey(), "one", IptvFlagReason.MANUAL)
                activity.setContent { MaterialTheme(colorScheme = NikColors) {
                    ModernRecentChannelsCollection(recents, {}, false, favorites, listOf(category), {}, {}, {}, {}, {}, true,
                        availabilityProfile = profile.cacheKey())
                } }
            }
            await("Recently Played shares profile flags") { find(rows.first().title) != null && find(rows.last().title) == null && find("Flagged unavailable") != null }
            longClick(rows.first().title); click("Remove flag")
            await("Removing final history match recovers focus") { find(rows.first().title) == null && focused("Availability: Flagged only") }
            assertEquals(2, recents.size)
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    private fun exercisePlayer(engine: PlaybackEngine) {
        val activity = host()
        val server = PlaybackEngineFixtureTest().FixtureServer()
        val store = IptvAvailabilityStore(ins.targetContext)
        val selected = mutableStateOf(PlayingMedia(rows.first(), "${server.base}/missing.mp4", CatalogType.LIVE_TV,
            profileKey = profile.cacheKey(), progressKey = "availability:fixture", directFullscreen = true))
        val audio = ins.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val frames = java.util.concurrent.atomic.AtomicInteger()
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val playback = selected.value
                PlayerScreen(playback, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, playbackEngine = engine,
                    startFullscreen = true,
                    onTerminalPlaybackFailure = { store.observe(profile.cacheKey(), selected.value, playback, failed = true) },
                    onVideoFrameRendered = { frames.incrementAndGet(); store.observe(profile.cacheKey(), selected.value, playback, failed = false) })
            } } }
            await("$engine terminal error records automatic marker", 30_000) { store.flags(profile.cacheKey())["one"]?.reason == IptvFlagReason.PLAYBACK_FAILED }
            ins.runOnMainSync { selected.value = selected.value.copy(url = "${server.base}/movie.mp4", playbackAttemptId = "working-${engine.name}") }
            await("$engine actual video clears automatic marker", 30_000) { frames.get() > 0 && "one" !in store.flags(profile.cacheKey()) }
            val before = frames.get()
            ins.runOnMainSync { store.flag(profile.cacheKey(), "one", IptvFlagReason.MANUAL)
                selected.value = selected.value.copy(playbackAttemptId = "manual-${engine.name}") }
            await("$engine manual flag survives real video", 30_000) { frames.get() > before }
            assertEquals(IptvFlagReason.MANUAL, store.flags(profile.cacheKey())["one"]?.reason)
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.close()
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
