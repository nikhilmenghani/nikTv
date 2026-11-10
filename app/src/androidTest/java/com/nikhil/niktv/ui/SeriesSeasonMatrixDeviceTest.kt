package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.discovery.components.DiscoverySeriesContent
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic catalogue only: exercises real season/sidebar/episode controls without provider requests. */
class SeriesSeasonMatrixDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun label(value: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(root())
    }
    private fun focused(value: String): Boolean {
        var node = label(value)
        while (node != null && !node.isClickable) node = node.parent
        return node != null && node == root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; Thread.sleep(40) }
        NavigationAuditEvidence.capture("season-matrix-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)

    @Test fun seasonCountsOneTwoThreeElevenSixteenAndTwentyFourKeepEpisodeCursorAndResume() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(context.resources.configuration))
        for (count in listOf(1, 2, 3, 11, 16, 24)) exercise(count)
    }

    @Test fun providerSeasonCountsKeepMiddleEpisodeReturnAndSeasonSidebarReachable() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(context.resources.configuration))
        for (count in listOf(1, 2, 3, 11, 16, 24)) exerciseProvider(count)
    }

    private fun exerciseProvider(seasonCount: Int) {
        val context = ins.targetContext
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val profile = PortalProfile("Provider matrix", "https://provider-matrix.invalid", "")
        val series = MediaItem("provider-matrix-$seasonCount", "Provider matrix $seasonCount", null, null)
        val season = if (seasonCount == 3) 2 else seasonCount
        fun episode(number: Int) = MediaItem("provider-matrix-$seasonCount-$season-$number", "Provider matrix episode $number", null, null,
            seasonNumber = season, episodeNumber = number)
        runBlocking { DiscoveryCache(java.io.File(context.cacheDir, "discovery_preview")).write(
            "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]") }
        val state = mutableStateOf(NikTvState(savedProfile = profile,
            session = PortalSession(profile, "", profile.portalUrl, "", "", ""), selectedSeries = series,
            selectedType = CatalogType.SERIES, selectedSeriesSeason = season, availableSeriesSeasons = (1..seasonCount).toList(),
            items = (1..30).map(::episode), playbackReturnFocusId = episode(15).id,
            recentlyPlayed = listOf(RecentItem(FavoriteKind.SERIES, series, lastPlayed = episode(15), profileKey = profile.cacheKey())),
            useTmdbEpisodeMetadata = false, restoring = false, homeOpen = false))
        val visible = mutableStateOf(true)
        val remoteReady = mutableStateOf(false)
        val remoteEntry = FocusRequester()
        val keyboardInputReady = AtomicBoolean(false)
        var played: String? = null
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val saved = rememberSaveableStateHolder()
                val inputMode = LocalInputModeManager.current.inputMode
                SideEffect { keyboardInputReady.set(inputMode == InputMode.Keyboard) }
                if (!remoteReady.value) {
                    Text("Prepare provider remote navigation", Modifier.focusRequester(remoteEntry).focusable())
                    LaunchedEffect(Unit) { repeat(20) {
                        withFrameNanos { }; if (runCatching { remoteEntry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    } }
                } else if (visible.value) saved.SaveableStateProvider("provider") {
                    Box(Modifier.fillMaxWidth().height(480.dp)) {
                        DiscoverySeriesContent(state.value, play = { played = it.id; visible.value = false }, closeSeries = {},
                            toggleFavorite = {}, toggleSeriesWatch = {}, loadSeriesSeason = {}, setUseTmdbEpisodeMetadata = {},
                            refreshTmdbEpisodeCache = {}, downloadForOffline = { _, _, _ -> }, removeOfflineDownload = { _, _ -> },
                            openSearch = {}, openSettings = {}, refreshCatalog = {}, loadMoreEpisodes = {}, openTmdbMovie = {}, openTmdbSeries = {})
                    }
                } else Text("Playing provider middle episode fixture")
            } } }
            // setInTouchMode alone does not update Compose's input mode on every tablet.
            // Deliver a real Dpad event before mounting the return target, as a remote user would.
            await("Provider remote fixture owns input") {
                host.hasWindowFocus() && label("Prepare provider remote navigation")?.isVisibleToUser == true
            }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Provider fixture enters keyboard input mode before return restoration") { keyboardInputReady.get() }
            ins.runOnMainSync { remoteReady.value = true }
            await("Provider $seasonCount seasons: Continue Watching restores episode 15") { focused("Provider matrix episode 15") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Provider $seasonCount seasons: selected season sidebar reachable") { focused("Season $season") }
            assertTrue(label("Season $season")?.isVisibleToUser == true)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Provider $seasonCount seasons: Right restores middle episode") { focused("Provider matrix episode 15") }
            for (number in 14 downTo 10) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Provider $seasonCount seasons: episode $number is navigable and visible") {
                    focused("Provider matrix episode $number") && label("Provider matrix episode $number")?.isVisibleToUser == true
                }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider $seasonCount seasons: middle episode activates") {
                !visible.value && played == episode(10).id && label("Playing provider middle episode fixture")?.isVisibleToUser == true
            }
            ins.runOnMainSync {
                state.value = state.value.copy(playbackReturnFocusId = episode(11).id,
                    recentlyPlayed = listOf(RecentItem(FavoriteKind.SERIES, series, lastPlayed = episode(11), profileKey = profile.cacheKey())))
                visible.value = true
            }
            await("Provider $seasonCount seasons: newer playback return restores episode 11") {
                focused("Provider matrix episode 11") && label("Provider matrix episode 11")?.isVisibleToUser == true
            }
            NavigationAuditEvidence.capture("provider-season-matrix-$seasonCount-resume-middle")
        } finally {
            ins.runOnMainSync { host.finish() }
            ins.waitForIdleSync()
            ins.setInTouchMode(true)
        }
    }

    private fun exercise(seasonCount: Int) {
        val context = ins.targetContext
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val card = DiscoveryCard(900100 + seasonCount, true, "Series with $seasonCount seasons")
        val resumeSeason = if (seasonCount == 3) 2 else seasonCount
        fun episodes(season: Int) = (1..30).map { DiscoveryEpisode(season, it, "S$season episode $it", overview = "Episode synopsis fixture") }
        val state = mutableStateOf(DiscoveryState(profile = "matrix", detail = card,
            details = DiscoveryDetails(card, seasons = (1..seasonCount).toList(), seasonEpisodeCounts = (1..seasonCount).associateWith { 30 }),
            season = resumeSeason, episodes = episodes(resumeSeason)))
        val watched = mutableStateOf(resumeSeason to 15)
        val visible = mutableStateOf(true)
        val entry = FocusRequester()
        var played: Pair<Int, Int>? = null
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val saved = rememberSaveableStateHolder()
                if (visible.value) saved.SaveableStateProvider("episodes") {
                    Box(Modifier.fillMaxWidth().height(480.dp)) {
                        DiscoverySeriesEpisodes(state.value, SeriesStartSeason.FIRST, entry,
                            loadSeason = { season -> state.value = state.value.copy(season = season, episodes = episodes(season)) },
                            play = { _, season, episode -> played = season to episode; visible.value = false },
                            download = { _, _, _ -> fail("Navigation must not download") }, onFocus = {}, up = { false }, down = { false },
                            lastWatched = watched.value, loadInitialSeason = false)
                    }
                } else Text("Playing middle episode fixture")
                LaunchedEffect(visible.value) { if (visible.value) repeat(20) {
                    withFrameNanos { }; if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                } }
            } } }
            await("$seasonCount seasons: toolbar attached with the watched episode positioned") {
                focused("Search episodes") && label("S$resumeSeason episode 15")?.isVisibleToUser == true
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("$seasonCount seasons: entry restores episode 15") { focused("S$resumeSeason episode 15") }
            assertTrue(label("S$resumeSeason episode 15")?.isVisibleToUser == true)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("$seasonCount seasons: selected sidebar season is reachable") { focused("Season $resumeSeason") }
            val selected = Rect().also { requireNotNull(label("Season $resumeSeason")).getBoundsInScreen(it) }
            assertTrue("Selected late season is within viewport", selected.top >= 0 && selected.bottom <= context.resources.displayMetrics.heightPixels)
            if (seasonCount > 1) {
                val adjacent = resumeSeason - 1
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("$seasonCount seasons: adjacent season is reachable") { focused("Season $adjacent") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("$seasonCount seasons: explicit season changes") { state.value.season == adjacent && label("S$adjacent episode 30") != null }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("$seasonCount seasons: another season gets its own initial cursor") { focused("S$adjacent episode 30") }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await("$seasonCount seasons: selected adjacent season is reachable") { focused("Season $adjacent") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("$seasonCount seasons: return season is reachable") { focused("Season $resumeSeason") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("$seasonCount seasons: original season restores") { state.value.season == resumeSeason && label("S$resumeSeason episode 15") != null }
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("$seasonCount seasons: returning season preserves middle cursor") { focused("S$resumeSeason episode 15") }
            for (episode in 14 downTo 10) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("$seasonCount seasons: episode $episode scrolls into view") {
                    focused("S$resumeSeason episode $episode") && label("S$resumeSeason episode $episode")?.isVisibleToUser == true
                }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("$seasonCount seasons: middle episode activates exactly once") {
                !visible.value && played == (resumeSeason to 10) && label("Playing middle episode fixture")?.isVisibleToUser == true
            }
            // The player may advance while this pane is absent; Continue Watching must use the new episode.
            ins.runOnMainSync { watched.value = resumeSeason to 11; visible.value = true }
            await("$seasonCount seasons: pane remounts with newer watched episode positioned") {
                focused("Search episodes") && label("S$resumeSeason episode 11")?.isVisibleToUser == true
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("$seasonCount seasons: newer same-season Continue Watching restores episode 11") { focused("S$resumeSeason episode 11") }
            NavigationAuditEvidence.capture("season-matrix-$seasonCount-resume-middle")
        } finally {
            ins.runOnMainSync { host.finish() }
            ins.waitForIdleSync()
            ins.setInTouchMode(true)
        }
    }
}
