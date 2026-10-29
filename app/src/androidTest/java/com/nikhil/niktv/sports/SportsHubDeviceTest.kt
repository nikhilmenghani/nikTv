package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Touch, real remote keys and real catalogue smoke coverage in the isolated package. */
class SportsHubDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(70) }
        assertTrue(label, condition())
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
    private fun focused(label: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.contentDescription?.toString() == label || node.text?.toString()?.lineSequence()?.any { it == label } == true) {
                var ancestor: AccessibilityNodeInfo? = node
                repeat(8) { if (ancestor?.isFocused == true) return true; ancestor = ancestor?.parent }
            }
            for (i in 0 until node.childCount) if (walk(node.getChild(i))) return true
            return false
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun click(label: String) {
        var n = find(label)
        repeat(8) { if (n?.isClickable != true) n = n?.parent }
        assertTrue("Clickable $label", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); Thread.sleep(160) }
    private fun availability(current: String, next: String) {
        click("Availability: $current")
        await("Availability chooser opens") { find("Channel availability") != null && find(next) != null }
        click(next)
    }
    private fun host(): SportsFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, SportsFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
    }

    @Test fun tabsTilesSourceChoicesAndCategoryReturnSupportDpadAndTouch() {
        val activity = host()
        val now = Instant.parse("2026-10-28T10:00:00Z")
        val event = SportsEvent("event", "Fixture A vs B", "fixture-event", "Cricket", "Test", "", "A", "B", now.minusSeconds(30), now.plusSeconds(1000), listOf("One"))
        val channels = (1..40).map { SportsChannel("$it", "Fixture channel $it", "fixture-$it", "Sports") }
        val categories = listOf(SportsCategory("news", "Fixture News"), SportsCategory("external", "Fixture Playlist", link = "https://fixture.invalid/list"))
        val state = mutableStateOf(SportsState(events = listOf(event), channels = channels, categories = categories, loading = false))
        var picked = -1; var favourites = 0
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val pages = rememberSaveableStateHolder()
                fun back() { state.value = state.value.let { old -> when {
                    old.selected != null -> old.copy(channel = null, event = null, streams = emptyList(), restoreBrowseFocus = true)
                    old.collection != null -> old.copy(collection = null, collectionChannels = emptyList(), restoreBrowseFocus = true)
                    else -> old
                } } }
                pages.SaveableStateProvider(state.value.route) {
                    var sport by rememberSaveable { mutableStateOf("All sports") }; var status by rememberSaveable { mutableStateOf("Now & next") }
                    var query by rememberSaveable { mutableStateOf("") }
                    SportsBrowseContent(state.value, now, sport, { sport = it }, status, { status = it }, query, { query = it },
                        ::back, {}, { state.value = state.value.copy(event = it) }, { picked = it },
                        selectTab = { state.value = state.value.copy(tab = it, channel = null, event = null, collection = null, restoreBrowseFocus = false) },
                        openChannel = { state.value = state.value.copy(channel = it, streams = listOf(SportsStream("https://fixture.invalid/one", "Fixture source 1"), SportsStream("https://fixture.invalid/two", "Fixture source 2"))) },
                        openCategory = { state.value = state.value.copy(collection = it, collectionChannels = channels, restoreBrowseFocus = false) },
                        toggleFavourite = { favourites++; state.value = state.value.copy(favourites = listOf(it)) })
                }
            } } }
            await("Selected events tab starts focused") { focused("Live Events") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Sports") { focused("Sports") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Sports shows its directory") { find("40 channels") != null && focused("Sports") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters first channel") { focused("Fixture channel 1") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right moves one tile") { focused("Fixture channel 2") }
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 120L)
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            await("Held tile opens action popup") { find("Add to Favourites") != null }
            assertEquals("Release cannot select a popup action", 0, favourites)
            await("Popup starts on its action") { focused("Add to Favourites") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Popup closes and restores its tile") { favourites == 1 && focused("Fixture channel 2") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("First source focuses without autoplay") { focused("Fixture source 1") }
            assertEquals(-1, picked)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right moves to source two") { focused("Fixture source 2") }
            click("Back")
            await("Back restores the opened channel") { focused("Fixture channel 2") }
            click("Categories")
            await("Categories tab renders") { find("2 categories") != null && focused("Categories") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters a category") { focused("Fixture News") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Category channels loaded") { find("40 channels") != null }
            click("Back")
            await("Back restores category origin") { focused("Fixture News") }
            assertEquals("Catalogue navigation never plays", -1, picked)
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun playlistGroupPopupFiltersAndReturnsFocusToItsButton() {
        val activity = host()
        val provider = SportsCategory("fixture", "Fixture Provider", link = "https://fixture.invalid/list")
        val rows = listOf(SportsChannel("1", "Fixture News TV", "", group = "News", playlist = provider), SportsChannel("2", "Fixture Sport TV", "", group = "Sport", playlist = provider))
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsBrowseContent(SportsState(tab = SportsTab.CATEGORIES, collection = provider, collectionChannels = rows, loading = false), Instant.now(),
                    "All sports", {}, "Now & next", {}, "", {}, {}, {}, {}, {})
            } } }
            await("Group action visible") { find("Group: All groups") != null }
            click("Group: All groups")
            await("Group chooser opens") { find("Playlist groups") != null && focused("All groups") }
            click("Sport")
            await("Group filter leaves one channel") { find("1 channel") != null && find("Fixture Sport TV") != null && find("Fixture News TV") == null }
            await("Focus returns to group action") { focused("Group: Sport") }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun emptyDirectoryDoesNotSendDpadFocusToAnUnattachedTile() {
        val activity = host()
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsBrowseContent(SportsState(tab = SportsTab.CHANNELS, loading = false), Instant.now(),
                    "All sports", {}, "Now & next", {}, "", {}, {}, {}, {}, {})
            } } }
            await("Empty Sports directory renders") { focused("Sports") && find("Nothing to show") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Focus remains on the tab without a crash") { focused("Sports") }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun unavailableChannelDialogKeepsItsReasonAndRecoveryActionsVisible() {
        val activity = host()
        val message = "The available sources could not play. Retry to fetch fresh links, or choose another source."
        var chosen = false
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                var shown by remember { mutableStateOf(true) }
                if (shown) SportsPlaybackErrorDialog(message, true, {}, { chosen = true; shown = false }, {})
            } } }
            await("Failure reason remains visible") { find(message) != null && focused("Refresh and retry") }
            val screen = ins.targetContext.resources.displayMetrics.heightPixels
            for (label in listOf(message, "Refresh and retry", "Choose another source")) {
                val rect = android.graphics.Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
                assertTrue("Visible bounded error content: $label", rect.height() > 0 && rect.top >= 0 && rect.bottom <= screen)
            }
            val retry = android.graphics.Rect().also { requireNotNull(find("Refresh and retry")).getBoundsInScreen(it) }
            assertTrue("Action must not expand to full dialog height", retry.height() < screen / 3)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches source recovery") { focused("Choose another source") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Recovery action closes the failed player") { chosen && find("Channel unavailable") == null }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun holdToFlagFiltersAndUnflaggingLastResultRestoreDpadFocus() {
        val activity = host()
        val store = SportsAvailabilityStore(ins.targetContext)
        ins.targetContext.getSharedPreferences("sports_availability_v1", 0).edit().clear().commit()
        val channels = listOf(SportsChannel("a", "Fixture A", "fixture-a"), SportsChannel("b", "Fixture B", "fixture-b"))
        val state = mutableStateOf(SportsState(tab = SportsTab.CHANNELS, channels = channels, loading = false))
        var played = false
        fun hold() {
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 120L)
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsBrowseContent(state.value, Instant.now(), "All sports", {}, "Now & next", {}, "", {}, {}, {}, {}, { played = true },
                    openChannel = { played = true }, toggleChannelFlag = { item ->
                        val flags = if (item.key in state.value.channelFlags) store.clear(item.key) else store.flag(item.key, SportsFlagReason.MANUAL)
                        state.value = state.value.copy(channelFlags = flags)
                    }, setAvailabilityFilter = { filter -> store.setFilter(filter); state.value = state.value.copy(availabilityFilter = filter) })
            } } }
            await("Sports tab focused") { focused("Sports") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First tile focused") { focused("Fixture A") }
            hold()
            await("Flag action is available on hold") { focused("Add to Favourites") && find("Flag as unavailable") != null }
            assertTrue(store.flags().isEmpty())
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad reaches flag action") { focused("Flag as unavailable") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Flagged tile regains focus") { focused("Fixture A") && find("Flagged unavailable") != null }
            assertEquals(SportsFlagReason.MANUAL, SportsAvailabilityStore(ins.targetContext).flags()[channels.first().key]?.reason)
            click("Availability: All channels")
            await("Filter opens on current choice") { focused("All channels") }
            click("Hide flagged")
            await("Flagged channel hidden, filter focused") { find("Fixture A") == null && find("Fixture B") != null && focused("Availability: Hide flagged") }
            assertEquals(SportsAvailabilityFilter.HIDE_FLAGGED, SportsAvailabilityStore(ins.targetContext).filter())
            availability("Hide flagged", "Flagged only")
            await("Only flagged channels remain") { find("Fixture A") != null && find("Fixture B") == null && focused("Availability: Flagged only") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters flagged results") { focused("Fixture A") }
            hold()
            await("Flag can be removed") { find("Remove flag") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Remove focused") { focused("Remove flag") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Last result removed and focus recovers") { find("0 channels") != null && find("Fixture A") == null && focused("Availability: Flagged only") }
            assertTrue(SportsAvailabilityStore(ins.targetContext).flags().isEmpty())
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches the active tab") { focused("Sports") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("An empty filtered directory still exposes recovery") { focused("Availability: Flagged only") }
            availability("Flagged only", "All channels")
            await("Both channels return without flags") { find("Fixture A") != null && find("Fixture B") != null && find("Flagged unavailable") == null }
            assertFalse("Flagging/filtering cannot play a stream", played)
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun eventAndCategoryChannelFlagsUseTheSameIdentityWithoutHidingCategories() {
        val activity = host()
        val event = SportsEvent("event", "Fixture A vs B", "fixture-event", "Cricket", "Test", "", "A", "B", null, null, emptyList())
        val category = SportsCategory("fixture", "Fixture category")
        val flag = SportsChannelFlag(SportsFlagReason.PLAYBACK_FAILED, System.currentTimeMillis())
        val state = mutableStateOf(SportsState(events = listOf(event), categories = listOf(category), loading = false,
            channelFlags = mapOf(event.slug to flag), availabilityFilter = SportsAvailabilityFilter.FLAGGED_ONLY))
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsBrowseContent(state.value, Instant.now(), "All sports", {}, "All events", {}, "", {}, {}, {}, {}, {},
                    selectTab = { state.value = state.value.copy(tab = it) },
                    openCategory = { state.value = state.value.copy(collection = it, collectionChannels = listOf(event.asChannel())) },
                    setAvailabilityFilter = { state.value = state.value.copy(availabilityFilter = it) })
            } } }
            await("Event shows automatic failure marker") { find(event.title) != null && find("Previously failed") != null }
            click("Categories")
            await("Category folders are not availability-filtered") { find(category.title) != null && find("Availability: Flagged only") == null }
            click(category.title)
            await("Same channel marker in category results") { find(event.title) != null && find("Previously failed") != null && find("Availability: Flagged only") != null }
            availability("Flagged only", "Hide flagged")
            await("Category results obey availability filter") { find(event.title) == null && find("0 channels") != null }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun liveHubMetadataAndSelectedSportsSourceUseTheExistingPlayer() {
        val activity = host()
        val audio = ins.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        lateinit var vm: SportsViewModel
        try {
            ins.targetContext.getSharedPreferences("sports_availability_v1", 0).edit().clear().commit()
            SportsAvailabilityStore(ins.targetContext).flag("willow-", SportsFlagReason.PLAYBACK_FAILED)
            ins.runOnMainSync { vm = ViewModelProvider(activity)[SportsViewModel::class.java] }
            await("Live Events catalogue", 45_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error); assertTrue(vm.state.value.events.isNotEmpty()); assertTrue(vm.state.value.filters.isNotEmpty())
            ins.runOnMainSync { vm.selectTab(SportsTab.CHANNELS) }
            await("Sports directory", 45_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error); assertTrue(vm.state.value.channels.isNotEmpty()); assertNull(vm.state.value.playing)
            val willow = vm.state.value.channels.firstOrNull { it.slug == "willow-" } ?: vm.state.value.channels.first()
            ins.runOnMainSync { vm.openChannel(willow) }
            await("Channel detail", 35_000) { !vm.state.value.loadingStreams }
            assertNull(vm.state.value.streamError); assertTrue(vm.state.value.streams.isNotEmpty()); assertTrue(vm.state.value.detail.related.isNotEmpty())
            ins.runOnMainSync { vm.play(0) }
            await("Selected source resolves", 35_000) { vm.state.value.playing != null || vm.state.value.streamError != null }
            assertNull(vm.state.value.streamError)
            val stream = requireNotNull(vm.state.value.playing)
            val attemptId = vm.state.value.playback.attemptId
            ins.runOnMainSync { vm.playbackRendered(attemptId - 1) }
            assertEquals("Stale frame must not clear a flag", SportsFlagReason.PLAYBACK_FAILED, vm.state.value.channelFlags[willow.key]?.reason)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            val rendered = CountDownLatch(1)
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsPlayer(stream, willow.title, {}, onVideoFrameRendered = { vm.playbackRendered(attemptId); rendered.countDown() })
            } } }
            assertTrue("Existing sports player renders the channel", rendered.await(30, TimeUnit.SECONDS))
            await("Actual video clears automatic failure marker") { vm.state.value.channelFlags[willow.key] == null }
            ins.runOnMainSync { vm.toggleChannelFlag(willow); vm.playbackRendered(attemptId) }
            assertEquals("Successful playback preserves manual flags", SportsFlagReason.MANUAL, vm.state.value.channelFlags[willow.key]?.reason)
            ins.runOnMainSync { vm.toggleChannelFlag(willow) }
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) { androidx.compose.material3.Text("Playback check complete") } }; vm.back(); vm.back(); vm.selectTab(SportsTab.CATEGORIES) }
            await("Category index", 45_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error); assertTrue(vm.state.value.categories.any { it.isPlaylist }); assertNull(vm.state.value.playing)
            val external = vm.state.value.categories.first { it.title.equals("SAMSUNG TV", true) }
            ins.runOnMainSync { vm.openCategory(external) }
            await("External playlist channels", 40_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error); assertTrue(vm.state.value.collectionChannels.isNotEmpty()); assertNull(vm.state.value.playing)
            android.util.Log.i("NikTvSportsHubTest", "Events=${vm.state.value.events.size}; Sports=${vm.state.value.channels.size}; categories=${vm.state.value.categories.size}; external channels=${vm.state.value.collectionChannels.size}; first video frame rendered")
        } finally {
            ins.runOnMainSync { runCatching { vm.back() } }
            ins.runOnMainSync { activity.finish() }
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
