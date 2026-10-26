package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.DiscoveryDetails
import com.nikhil.niktv.discovery.DiscoveryEpisode
import com.nikhil.niktv.discovery.DiscoverySeriesEpisodes
import com.nikhil.niktv.discovery.DiscoveryState
import com.nikhil.niktv.discovery.DiscoveryCache
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.SeriesStartSeason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Isolated season/episode remote navigation: no providers, playback or normal app data. */
class SeriesEpisodeNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var currentHost: PlaybackFixtureHostActivity? = null

    @Test fun loadingPopupFocusesCancelAfterTouchAndKeepsItAcrossProgressUpdates() {
        for (touch in listOf(false, true)) {
            val activity = startHost()
            val progress = mutableStateOf(EpisodeDestinationState("fixture", "series", 1, 1, null, 20, 1))
            val visible = mutableStateOf(true)
            var canceled = false
            try {
                instrumentation.setInTouchMode(touch)
                instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Text("Underlying episode screen")
                        if (visible.value) EpisodeDestinationDialog(progress.value,
                            cancel = { canceled = true; visible.value = false }, retry = { fail("Cancel must be the default action") })
                    }
                } } }
                await("Cancel takes initial focus, touch=$touch") { focused("Cancel") }
                instrumentation.runOnMainSync { progress.value = progress.value.copy(loaded = 500, page = 25) }
                await("Progress updates retain Cancel focus, touch=$touch") {
                    label("500 episodes loaded · page 25") != null && focused("Cancel")
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("OK immediately cancels the operation, touch=$touch") { canceled && label("Cancel") == null }
            } finally { finishHost(activity) }
        }
    }

    @Test fun earliestSortLoadsMissingProviderPagesBeforeFocusingTheTrueFirstEpisode() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val series = MediaItem("fixture-paged-sort", "Fixture paged sort", null, null)
        val profile = com.nikhil.niktv.model.PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
        val session = com.nikhil.niktv.model.PortalSession(profile, "fixture", "https://fixture.invalid/portal.php", "", "", "")
        fun episode(n: Int) = MediaItem("paged-$n", "Paged episode $n", null, null, seasonNumber = 1, episodeNumber = n)
        runBlocking { DiscoveryCache(java.io.File(context.cacheDir, "discovery_preview")).write(
            "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]") }
        val model = mutableStateOf(NikTvState(selectedSeries = series, savedProfile = profile, session = session,
            selectedType = CatalogType.SERIES, selectedSeriesSeason = 1, availableSeriesSeasons = listOf(1),
            items = (481..520).map(::episode), episodeHasMore = true, useTmdbEpisodeMetadata = false,
            restoring = false, homeOpen = false))
        val requests = CopyOnWriteArrayList<Int?>()
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                ModernSeriesDetailScreen(model.value, play = { fail("Sort must not play") }, closeSeries = {},
                    toggleFavorite = {}, toggleSeriesWatch = {}, loadSeriesSeason = {}, setUseTmdbEpisodeMetadata = {},
                    refreshTmdbEpisodeCache = {}, downloadForOffline = { _, _, _ -> }, removeOfflineDownload = { _, _ -> },
                    openSearch = {}, openSettings = {}, refreshCatalog = {}, loadMoreEpisodes = {}, openTmdbMovie = {}, openTmdbSeries = {},
                    goToEpisode = { number -> requests.add(number); model.value = model.value.copy(
                        episodeDestination = EpisodeDestinationState(profile.cacheKey(), series.id, 1, 1, number, 40, 1)) },
                    cancelEpisodeDestination = { model.value = model.value.copy(episodeDestination = null) })
            } } }
            await("Fixture starts on Play latest") { activity.hasWindowFocus() && focused("Play latest") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header action is focused") { focused("Related") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Sort is focused") { focused("Sort episodes") }
            chooseSort("Latest first", "Earliest first")
            await("Earliest sort shows progress and a focused cancel action") { requests.size == 1 &&
                label("Finding the earliest episode") != null && label("40 episodes loaded · page 1") != null && focused("Cancel") }
            assertNull(requests.single())
            assertFalse("A loaded-only minimum must not take focus during the fetch", focused("Paged episode 481"))
            instrumentation.runOnMainSync { model.value = model.value.copy(items = (1..520).map(::episode),
                episodeHasMore = false, episodeDestination = model.value.episodeDestination!!.copy(
                    running = false, loaded = 520, page = 26, focusId = "paged-1")) }
            await("Completed load and sort focuses real episode 1") { focused("Paged episode 1") }
            assertTrue("Complete first episode stays visible", label("Paged episode 1")?.isVisibleToUser == true)
        } finally { finishHost(activity) }
    }

    @Test fun goToEpisodeMenuOpensNumberEntryWithoutAutomaticallyOpeningTheKeyboard() {
        val activity = startHost()
        val sort = FocusRequester()
        val choices = CopyOnWriteArrayList<Int>()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    val search = remember { FocusRequester() }
                    val layout = remember { FocusRequester() }
                    SeriesEpisodeToolbar(season = { Text("Episodes") }, count = "520 episodes", compact = false,
                        newestFirst = true, onNewestFirst = {}, listView = true, onListView = {}, searchOpen = false,
                        onSearch = {}, searchField = {}, searchRequester = search, sortRequester = sort,
                        layoutRequester = layout, onGoToEpisode = { choices.add(it) })
                }
            } } }
            instrumentation.waitForIdleSync()
            await("Standalone toolbar is attached") { activity.hasWindowFocus() && label("520 episodes") != null }
            instrumentation.runOnMainSync { sort.requestFocus() }
            await("Sort has remote focus") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Latest first initially has popup focus") { focused("Latest first") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Earliest first is accessible") { focused("Earliest first") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Go to episode is accessible") { focused("Go to episode…") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Number entry opens with Cancel visible") { label("Episode number") != null && label("Cancel")?.isVisibleToUser == true }
            assertFalse("Entry focus alone must not open a keyboard", instrumentation.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_3)
            key(KeyEvent.KEYCODE_7)
            await("Remote number input reaches the field") { find { it.isEditable && it.text?.toString() == "37" } != null }
            key(KeyEvent.KEYCODE_BACK)
            var go = label("Go")
            await("Go is ready after hiding the keyboard") { go = label("Go"); go != null }
            while (go != null && !go!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) go = go!!.parent
            await("Go dispatches the requested exact episode") { choices.toList() == listOf(37) }
        } finally { finishHost(activity) }
    }

    @Test fun selectedLastSeasonIsVisibleWhenTheOuterPageClipsTheSidebar() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                val entry = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height((context.resources.configuration.screenHeightDp - 150).dp))
                    Row(Modifier.height(620.dp)) {
                        SeriesSeasonSidebar((1..49).toList(), 49, mapOf(49 to "42 episodes"), entry,
                            {}, {}, {}, Modifier.width(156.dp).fillMaxHeight())
                        Text("Loaded season 49 episodes", Modifier.padding(start = 16.dp, top = 10.dp))
                    }
                }
            } } }
            await("Selected season 49 is visible above the clipped sidebar bottom") { label("Season 49")?.isVisibleToUser == true }
            assertTrue("Previous season remains visible alongside the selected season", label("Season 48")?.isVisibleToUser == true)
        } finally { finishHost(activity) }
    }

    @Test fun lastSeasonShowsNeighboursInsteadOfAnEmptySidebar() {
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                val entry = remember { FocusRequester() }
                SeriesSeasonSidebar((1..10).toList(), 10, (1..10).associateWith { "24 episodes" }, entry,
                    {}, {}, {}, Modifier.width(156.dp).height(340.dp))
            } } }
            await("Last season and its neighbours are visible") {
                label("Season 10")?.isVisibleToUser == true && label("Season 9")?.isVisibleToUser == true &&
                    label("Season 8")?.isVisibleToUser == true
            }
        } finally { finishHost(activity) }
    }

    @Test fun shortSeasonListStartsAtTheFirstSeasonWhenAllFit() {
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                val entry = remember { FocusRequester() }
                SeriesSeasonSidebar((1..3).toList(), 3, emptyMap(), entry,
                    {}, {}, {}, Modifier.width(156.dp).height(340.dp))
            } } }
            await("Every season remains visible") { (1..3).all { label("Season $it")?.isVisibleToUser == true } }
        } finally { finishHost(activity) }
    }

    @Test fun seasonRightEntersEpisodesAndRestoresAnOffscreenSelection() = fixture { seasons, plays ->
        // Down from the toolbar enters episodes. Left returns to the selected season.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down enters the first episode") { focused("Fixture episode 30") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left reaches selected Season 1") { focused("Season 1") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Season 2 may focus without selecting it") { focused("Season 2") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right from unselected Season 2 enters Season 1 episodes") { focused("Fixture episode 30") }
        assertEquals("Focus must not fetch or select another season", listOf(1), seasons.toList())

        for (number in 29 downTo 18) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Episode $number receives remote focus") { focused("Fixture episode $number") }
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Episode Left returns to selected season") { focused("Season 1") }

        // Move the nested episode list while the sidebar keeps input focus. The
        // remembered episode now needs composition/reveal before it can focus.
        repeat(3) {
            val sidebar = bounds(checkNotNull(label("Season 1")))
            val episodeList = find { node -> node.isScrollable && bounds(node).left > sidebar.right }
            assertNotNull("Episode list has its own scroll action", episodeList)
            assertTrue("Episode list can scroll independently", episodeList!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(350)
        }
        await("Remembered episode has left the visible list") { label("Fixture episode 18")?.isVisibleToUser != true }
        assertTrue("Scrolling episodes must retain sidebar focus", focused("Season 1"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right reveals and restores the remembered offscreen episode") { focused("Fixture episode 18") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left returns to Season 1 again") { focused("Season 1") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Second and final season is reachable without selecting it") { focused("Season 2") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right from another season still restores the current episode") { focused("Fixture episode 18") }
        assertEquals(listOf(1), seasons.toList())
        assertTrue("Navigation never starts playback", plays.isEmpty())
    }

    @Test fun gridSeasonDownEntersEpisodesAndUpReturnsToSelectedSeason() = fixture { seasons, plays ->
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Sort follows Search") { focused("Sort episodes") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("List layout follows Sort") { focused("Episode list view") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Grid layout is reachable") { focused("Episode grid view") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Layout toggle retains focus on the opposite layout button") { focused("Episode list view") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Grid toolbar Down enters the selected season") { focused("Season 1") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Grid seasons navigate horizontally without changing selection") { focused("Season 2") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Season Down enters current grid episodes") { focused("Fixture episode 30") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Next grid episode is reachable") { focused("Fixture episode 29") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Episode Up returns to selected season") { focused("Season 1") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Season Down restores remembered grid episode") { focused("Fixture episode 29") }
        assertEquals(listOf(1), seasons.toList())
        assertTrue("Changing layouts never starts playback", plays.isEmpty())
    }

    @Test fun sortingFiveHundredTmdbListEpisodesFocusesTheNewFirstEpisode() = verifyLargeTmdbSort(grid = false)

    @Test fun sortingFiveHundredTmdbGridEpisodesFocusesTheNewFirstEpisode() = verifyLargeTmdbSort(grid = true)

    private fun verifyLargeTmdbSort(grid: Boolean) = fixture(episodeCount = 500) { seasons, plays ->
        if (grid) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Sort follows Search") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("List layout follows Sort") { focused("Episode list view") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Grid layout is reachable") { focused("Episode grid view") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Grid retains layout toggle focus") { focused("Episode list view") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Grid toolbar Down reaches Season 1") { focused("Season 1") }
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Initial latest episode receives focus") { focused("Fixture episode 500") }

        fun reachSort() {
            key(KeyEvent.KEYCODE_DPAD_UP)
            if (grid) {
                await("Grid episode Up reaches Season 1") { focused("Season 1") }
                key(KeyEvent.KEYCODE_DPAD_UP)
            }
            await("First episode can return to Search toolbar") { focused("Search episodes") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Sort action receives focus") { focused("Sort episodes") }
        }

        reachSort()
        chooseSort("Latest first", "Earliest first")
        await("Earliest sort focuses the first episode, not old episode 500") { focused("Fixture episode 1") }
        assertTrue("First sorted episode is visible", label("Fixture episode 1")?.isVisibleToUser == true)
        await("Reordering a large season scrolls to its beginning") { label("Fixture episode 500")?.isVisibleToUser != true }
        if (grid) {
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Earliest grid episode Up reaches season") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        } else {
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Earliest list episode Left reaches season") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        await("Season re-entry retains new first episode after sorting") { focused("Fixture episode 1") }

        reachSort()
        chooseSort("Earliest first", "Latest first")
        await("Latest sort focuses new first episode 500") { focused("Fixture episode 500") }
        assertTrue("Latest sorted episode is visible", label("Fixture episode 500")?.isVisibleToUser == true)
        await("Latest sorting also resets the viewport") { label("Fixture episode 1")?.isVisibleToUser != true }
        reachSort()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Sort popup starts on selected order") { focused("Latest first") }
        key(KeyEvent.KEYCODE_BACK)
        await("Dismissing without selecting restores the Sort button") { focused("Sort episodes") && label("Earliest first") == null }
        assertEquals("Sorting remains local to the loaded season", listOf(1), seasons.toList())
        assertTrue("Sorting never starts playback", plays.isEmpty())
    }

    @Test fun sortingFiveHundredProviderEpisodesResetsFocusAndScroll() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Run only against the isolated regression package" }
        val configuration = context.resources.configuration
        check(configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(configuration)) { "Use a tablet or TV" }
        val series = MediaItem("fixture-provider-sort-series", "Fixture provider sort series", null, null)
        val episodes = (1..500).map { number -> MediaItem("provider-sort-$number", "Provider sort episode $number", null, null,
            seasonNumber = 2, episodeNumber = number) }
        runBlocking {
            DiscoveryCache(java.io.File(context.cacheDir, "discovery_preview")).write(
                "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]")
        }
        val state = NikTvState(selectedSeries = series, selectedType = CatalogType.SERIES,
            items = episodes, availableSeriesSeasons = listOf(1, 2), selectedSeriesSeason = 2,
            useTmdbEpisodeMetadata = false, restoring = false, homeOpen = false)
        val actions = CopyOnWriteArrayList<String>()
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                ModernSeriesDetailScreen(state, play = { actions.add("play") }, closeSeries = {},
                    toggleFavorite = {}, toggleSeriesWatch = {}, loadSeriesSeason = { actions.add("season") },
                    setUseTmdbEpisodeMetadata = {}, refreshTmdbEpisodeCache = {},
                    downloadForOffline = { _, _, _ -> actions.add("download") }, removeOfflineDownload = { _, _ -> },
                    openSearch = {}, openSettings = {}, refreshCatalog = {}, loadMoreEpisodes = {},
                    openTmdbMovie = {}, openTmdbSeries = {})
            } } }
            await("Provider sort fixture begins on Play latest") { activity.hasWindowFocus() && focused("Play latest") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Provider hero Down reaches header Related action") { focused("Related") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header Related Down reaches Sort") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Initial provider episode 500 receives focus") { focused("Provider sort episode 500") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Provider Left reaches Season 2") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Season 2 Up reaches Season 1") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Season Up reaches Sort") { focused("Sort episodes") }
            chooseSort("Latest first", "Earliest first")
            await("Provider earliest sort focuses episode 1") { focused("Provider sort episode 1") }
            await("Provider sort resets its nested viewport") { label("Provider sort episode 500")?.isVisibleToUser != true }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Sorted provider episode Left reaches Season 2") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Provider season re-entry retains new first episode") { focused("Provider sort episode 1") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Return to season before reversing sort") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Return through Season 1 before reversing sort") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Sort is reachable again") { focused("Sort episodes") }
            chooseSort("Earliest first", "Latest first")
            await("Provider latest sort focuses episode 500") { focused("Provider sort episode 500") }
            await("Reversing provider order resets the viewport") { label("Provider sort episode 1")?.isVisibleToUser != true }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Latest provider episode Left reaches Season 2") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Return through Season 1 before dismissing sort") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Sort is reachable for dismissal") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider sort popup focuses current order") { focused("Latest first") }
            key(KeyEvent.KEYCODE_BACK)
            await("Provider sort dismissal returns to Sort") { focused("Sort episodes") && label("Earliest first") == null }
            assertTrue("Sorting performs no provider loads, downloads or playback", actions.isEmpty())
        } finally { finishHost(activity) }
    }

    private fun chooseSort(current: String, target: String) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Sort popup focuses current order: $current") { focused(current) }
        if (current != target) {
            key(if (target == "Earliest first") KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            await("Requested sort option is focused: $target") { focused(target) }
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun providerSecondSeasonRightEntersNestedEpisodesWithoutMovingTheOuterRow() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Run only against the isolated regression package" }
        val config = context.resources.configuration
        check(config.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(config)) { "Use a tablet or TV for wide season navigation" }
        val series = MediaItem("fixture-provider-series", "Fixture provider series", null, null,
            description = "A fixture synopsis with full series information.")
        val episodes = (1..12).map { number -> MediaItem("fixture-provider-$number", "Provider episode $number", null, null,
            description = "Fixture synopsis for episode $number", seasonNumber = 2, episodeNumber = number) }
        val progress = episodes.map { episode ->
            val titleKey = episode.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
            PlaybackProgress("progress-v2||SERIES|series:${series.id}|s:2|e:${episode.episodeNumber}|$titleKey|fixture", 60_000, 180_000,
                updatedAtMillis = episode.episodeNumber!!.toLong())
        }
        // Header presentation normally resolves TMDB. A fresh negative public
        // metadata cache makes this fixture deterministic without any network.
        runBlocking {
            DiscoveryCache(java.io.File(context.cacheDir, "discovery_preview")).write(
                "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]")
        }
        val state = NikTvState(selectedSeries = series, selectedType = CatalogType.SERIES,
            items = episodes, availableSeriesSeasons = listOf(1, 2), selectedSeriesSeason = 2,
            playbackProgress = progress, useTmdbEpisodeMetadata = false, restoring = false, homeOpen = false)
        val seasons = CopyOnWriteArrayList<Int>()
        val plays = CopyOnWriteArrayList<String>()
        val activity = startHost()
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                CompositionLocalProvider(com.nikhil.niktv.discovery.LocalDiscoveryNavigationTrace provides ::diagnostic) {
                ModernSeriesDetailScreen(state, play = { plays.add(it.id) }, closeSeries = {},
                    toggleFavorite = {}, toggleSeriesWatch = {}, loadSeriesSeason = { seasons.add(it) },
                    setUseTmdbEpisodeMetadata = {}, refreshTmdbEpisodeCache = {},
                    downloadForOffline = { _, _, _ -> fail("Navigation must not download") },
                    removeOfflineDownload = { _, _ -> }, openSearch = {}, openSettings = {},
                    refreshCatalog = {}, loadMoreEpisodes = {}, openTmdbMovie = {}, openTmdbSeries = {})
                }
            } } }
            await("Provider fixture owns input and starts on Resume") {
                activity.hasWindowFocus() && focused("Resume") && label("More info") != null
            }
            // Await every destination before sending another key. A timed burst
            // can queue an extra Down behind a stale accessibility snapshot.
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Provider hero Down reaches description actions") { focused("More info") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Related beside More info") { focused("Related") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to More info") { focused("More info") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Description actions Down reaches Sort") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Provider Down reaches first episode") { focused("Provider episode 12") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Provider Left returns selected second season") { focused("Season 2") }
            val seasonTop = bounds(checkNotNull(label("Season 2"))).top
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right from second and final season reaches an episode") { focused("Provider episode 12") }
            assertEquals("Sideways focus transfer must not move the containing episode row", seasonTop, bounds(checkNotNull(label("Season 2"))).top)
            for (number in 11 downTo 3) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Provider episode $number is reachable") { focused("Provider episode $number") }
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Provider episode Left restores Season 2") { focused("Season 2") }
            val sidebar = bounds(checkNotNull(label("Season 2")))
            val episodeList = find { it.isScrollable && bounds(it).left > sidebar.right }
            assertNotNull("Provider nested list has a separate scroll action", episodeList)
            // E3 lies outside even the tablet's taller initial viewport.
            repeat(6) {
                if (label("Provider episode 3")?.isVisibleToUser == true) {
                    find { it.isScrollable && bounds(it).left > sidebar.right }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    Thread.sleep(350)
                }
            }
            await("Remembered provider episode leaves the visible list") { label("Provider episode 3")?.isVisibleToUser != true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right restores the remembered offscreen provider episode") { focused("Provider episode 3") }
            for (number in 4..12) {
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("Up reaches provider episode $number") { focused("Provider episode $number") }
            }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("First episode Up reaches Sort") { focused("Sort episodes") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Sort Up reaches the description row") { focused("More info") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Related remains reachable in the description row") { focused("Related") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Related Up reaches Resume without a same-row loop") { focused("Resume") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Repeated Up reaches the top Back button") { focused("Back") }
            assertTrue("Changing focus never selects a different provider season", seasons.isEmpty())
            assertTrue("Changing focus never starts playback", plays.isEmpty())
        } finally { finishHost(activity) }
    }

    private fun fixture(episodeCount: Int = 30, test: (CopyOnWriteArrayList<Int>, CopyOnWriteArrayList<Int>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Run only against the isolated regression package" }
        val config = context.resources.configuration
        check(config.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(config)) { "Use a tablet or TV for wide season navigation" }
        val activity = startHost()
        val seasons = CopyOnWriteArrayList<Int>()
        val plays = CopyOnWriteArrayList<Int>()
        val card = DiscoveryCard(123456, true, "Fixture series", date = "2020-01-01")
        val state = DiscoveryState(profile = "fixture-only", detail = card,
            details = DiscoveryDetails(card, seasons = listOf(1, 2), seasonEpisodeCounts = mapOf(1 to episodeCount, 2 to 20)),
            season = 1, episodes = (1..episodeCount).map { DiscoveryEpisode(1, it, "Fixture episode $it", "Fixture synopsis $it") })
        val entry = FocusRequester()
        try {
            instrumentation.runOnMainSync { activity.setContent {
                MaterialTheme {
                    val stableState = remember { state }
                    Box(Modifier.fillMaxSize()) {
                        DiscoverySeriesEpisodes(stableState, SeriesStartSeason.FIRST, entry,
                            loadSeason = { seasons.add(it) }, play = { _, _, episode -> plays.add(episode) },
                            download = { _, _, _ -> fail("Navigation must not download") },
                            onFocus = {}, up = { false }, down = { false })
                    }
                }
            } }
            await("Fixture owns the input window") { activity.hasWindowFocus() && label("Fixture episode $episodeCount") != null }
            await("Search toolbar receives initial remote focus") {
                if (!focused("Search episodes")) instrumentation.runOnMainSync { entry.requestFocus() }
                focused("Search episodes")
            }
            await("Initial season callback completes") { seasons.isNotEmpty() }
            test(seasons, plays)
        } finally { finishHost(activity) }
    }

    private fun startHost(): PlaybackFixtureHostActivity {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = activity
        instrumentation.waitForIdleSync()
        instrumentation.setInTouchMode(false)
        // An empty ComponentActivity need not have a focused content window yet.
        // Each fixture waits for its own rendered content after setContent.
        diagnostic("Host created; windowFocused=${activity.hasWindowFocus()}")
        return activity
    }

    private fun finishHost(activity: PlaybackFixtureHostActivity) {
        // Cleanup must not replace an earlier navigation assertion with a
        // lifecycle-timing failure. The next host launch clears this test task.
        runCatching {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }.onFailure { diagnostic("Fixture cleanup: ${it.javaClass.simpleName}") }
        currentHost = null
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun label(value: String) = find { it.text?.toString() == value || it.contentDescription?.toString() == value }
    private fun focused(value: String): Boolean {
        var node = label(value)
        repeat(6) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != instrumentation.targetContext.packageName) return null
        return walk(root)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(40)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val targetPackage = instrumentation.targetContext.packageName
        diagnostic("Failed: $message; window=${root?.packageName}; hostFocused=${currentHost?.hasWindowFocus()}")
        if (root?.packageName?.toString() == targetPackage) {
            val focused = find { it.isFocused }
            fun labels(node: AccessibilityNodeInfo?, result: MutableList<String>) {
                if (node == null || result.size >= 35) return
                node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                for (index in 0 until node.childCount) labels(node.getChild(index), result)
            }
            val focusedLabels = mutableListOf<String>()
            labels(focused, focusedLabels)
            val visibleLabels = mutableListOf<String>()
            labels(root, visibleLabels)
            diagnostic("Focused fixture labels: $focusedLabels; bounds=${focused?.let(::bounds)}")
            diagnostic("Visible fixture labels: $visibleLabels")
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try { java.io.File(instrumentation.targetContext.externalCacheDir, "episode-navigation-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        fail(message)
    }

    private fun diagnostic(message: String) {
        println("Series navigation: $message")
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nSeries navigation: $message\n") })
    }
}
