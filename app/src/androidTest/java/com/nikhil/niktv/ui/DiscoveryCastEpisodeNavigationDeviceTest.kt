package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Full cast-dialog -> series route, using only fixed public metadata and fake actions. */
class DiscoveryCastEpisodeNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var host: PlaybackFixtureHostActivity? = null

    @Test fun castSelectedSeriesRetainsSeasonToEpisodeRemoteNavigation() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Use only the isolated regression package" }
        val configuration = context.resources.configuration
        check(configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(configuration)) { "Use a tablet or TV" }
        val profile = PortalProfile("Cast navigation fixture", "https://cast-fixture.invalid", "")
        val parent = DiscoveryCard(810001, true, "Fixture parent series", date = "2020-01-01")
        val target = DiscoveryCard(810002, true, "Fixture cast series", date = "2020-01-01")
        val actor = DiscoveryPerson("Fixture Actor", "Fixture role", id = 810003)
        val loads = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<String>()
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = emptyList<DiscoveryCard>()
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                if (card.key == parent.key) DiscoveryDetails(card, cast = listOf(actor), seasons = listOf(1), seasonEpisodeCounts = mapOf(1 to 1))
                else DiscoveryDetails(card, seasons = listOf(1, 2), seasonEpisodeCounts = mapOf(1 to 7, 2 to 7))
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun actorCredits(person: DiscoveryPerson, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> {
                assertEquals(actor.id, person.id)
                return listOf(target)
            }
            override suspend fun episodes(card: DiscoveryCard, season: Int): List<DiscoveryEpisode> {
                loads.add("${card.key}:$season")
                return (1..if (card.key == parent.key) 1 else 7).map { number ->
                    DiscoveryEpisode(season, number, if (card.key == parent.key) "Parent episode" else "Cast series episode $number",
                        overview = "Fixture episode synopsis $number", date = "2020-01-01")
                }
            }
        }
        lateinit var vm: DiscoveryViewModel
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        host = activity
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                vm = DiscoveryViewModel(context.applicationContext as Application, source)
                vm.bind(profile.cacheKey(), DashboardSurface.SERIES, emptyList())
                vm.openDetails(parent)
                activity.setContent { MaterialTheme {
                    // Provider cast/related routes host public details in a Dialog.
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    CompositionLocalProvider(LocalDiscoveryNavigationTrace provides { message ->
                        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
                    }) {
                    DiscoveryPreviewScreen(
                        app = NikTvState(savedProfile = profile, seriesStartSeason = SeriesStartSeason.FIRST,
                            restoring = false, homeOpen = false),
                        surface = DashboardSurface.SERIES,
                        selectSurface = { actions.add("surface") }, exit = { actions.add("exit") },
                        navigate = { actions.add("navigate:$it") }, playCard = { actions.add("play") },
                        playEpisode = { _, _, _ -> actions.add("episode") }, openRecent = { actions.add("recent") },
                        openWatched = { _, _ -> actions.add("watched") }, toggleFavorite = { actions.add("favorite") },
                        openCategory = { actions.add("category") }, openSection = { actions.add("section") },
                        removeRecent = {}, markWatched = { _, _ -> }, dismissEpisode = { _, _ -> }, vm = vm)
                    }
                    }
                } }
            }
            await("Parent detail owns the window and focuses Play") {
                focused("Play") && vm.state.value.episodes.isNotEmpty()
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Parent Down enters episode toolbar") { focused("Search episodes") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Parent toolbar Down enters episode") { focused("Parent episode") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Last parent episode Down enters cast") { focused(actor.name) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Actor credits dialog opens and focuses target series") {
                label("Featuring ${actor.name}") != null && focused(target.title)
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Cast selection opens the complete target detail route") {
                vm.state.value.detail?.key == target.key && vm.state.value.season == 1 &&
                    vm.state.value.episodes.size == 7 && focused("Play") && label("Featuring ${actor.name}") == null
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Cast detail Down reaches Search toolbar") { focused("Search episodes") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Cast detail toolbar Down enters first episode") { focused("Cast series episode 7") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Episode Left reaches selected Season 1") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Second and final season can focus without selecting it") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right from Season 2 enters the selected Season 1 episode list") { focused("Cast series episode 7") }
            assertEquals(1, vm.state.value.season)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Episode Down reaches another episode after cast navigation") { focused("Cast series episode 6") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left still returns to selected Season 1") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Season 2 remains reachable") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right restores the remembered episode after cast navigation") { focused("Cast series episode 6") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Remembered episode Left returns to Season 1") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("First season Up reaches toolbar") { focused("Search episodes") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Toolbar Down restores the episode") { focused("Cast series episode 6") }
            assertFalse("Focus alone must never load Season 2", loads.any { it.endsWith(":2") })
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Return to first season before selecting another") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Second season is selectable") { focused("Season 2") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Second season finishes loading without losing sidebar focus") {
                vm.state.value.season == 2 && vm.state.value.episodes.size == 7 && focused("Season 2")
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters newly loaded second-season episodes") { focused("Cast series episode 7") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to the now-selected Season 2") { focused("Season 2") }
            assertTrue("Opening details and navigating never plays or searches providers", actions.isEmpty())
        } finally {
            runCatching { instrumentation.runOnMainSync {
                runCatching { vm.pause() }
                activity.finish()
            }; instrumentation.waitForIdleSync() }
            host = null
        }
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
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
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(40)
        }
        val focused = find { it.isFocused }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || labels.size >= 35) return
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(focused)
        val bounds = focused?.let { node -> Rect().also(node::getBoundsInScreen) }
        println("Cast navigation failure: $message; hostFocused=${host?.hasWindowFocus()}; focused=$labels; bounds=$bounds")
        fail(message)
    }
}
