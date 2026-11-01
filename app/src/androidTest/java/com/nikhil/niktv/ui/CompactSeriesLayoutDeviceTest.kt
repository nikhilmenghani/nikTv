package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.DiscoveryCache
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Generated episode data only; no provider, playback, settings or history writes. */
class CompactSeriesLayoutDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Animated inline rows move existing nodes without replacing their semantics.
        // Read fresh geometry rather than UiAutomation's cached pre-animation bounds.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(40)
        }
        println("Compact screen failure: $message; search=${label("Search episodes")?.let(::bounds)}; title=${find { it.text?.toString()?.startsWith("A long series title") == true }?.let(::bounds)}")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(instrumentation.targetContext.externalCacheDir, "compact-series-failure.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
        fail(message)
    }
    private fun click(text: String) {
        var node = label(text)
        repeat(8) { if (node?.isClickable != true) node = node?.parent }
        assertTrue("Clickable: $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    @Test fun phoneKeepsHeaderAndToolsVisibleWhileEpisodeListScrollsAndCanJumpBack() {
        val context = instrumentation.targetContext
        check(context.resources.configuration.smallestScreenWidthDp < 600)
        val series = MediaItem("compact-series-fixture", "A long series title for the compact header", null, null,
            description = "A full series description, available using the information action.")
        val profile = PortalProfile("Fixture", "https://fixture.invalid", "")
        val episodes = (500 downTo 1).map { n -> MediaItem("compact-$n", "Compact episode $n", null, null,
            seasonNumber = 1, episodeNumber = n) }
        val model = mutableStateOf(NikTvState(savedProfile = profile,
            session = PortalSession(profile, "", profile.portalUrl, "", "", ""), selectedSeries = series,
            selectedType = CatalogType.SERIES, selectedSeriesSeason = 1, availableSeriesSeasons = listOf(1),
            items = episodes, useTmdbEpisodeMetadata = false, discoveryExperienceEnabled = true,
            recentlyPlayed = listOf(RecentItem(FavoriteKind.SERIES, series, lastPlayed = episodes.first { it.episodeNumber == 250 },
                profileKey = profile.cacheKey())), loading = false, restoring = false, homeOpen = false))
        runBlocking { DiscoveryCache(java.io.File(context.cacheDir, "discovery_preview")).write(
            "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]") }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        var refreshedEpisode: String? = null
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                CompositionLocalProvider(com.nikhil.niktv.discovery.LocalMobileGlassEnabled provides true) {
                    ModernSeriesDetailScreen(model.value, play = { fail("Navigation must not play") }, closeSeries = {},
                        toggleFavorite = {}, toggleSeriesWatch = {}, loadSeriesSeason = {}, setUseTmdbEpisodeMetadata = {},
                        refreshTmdbEpisodeCache = {}, downloadForOffline = { _, _, _ -> }, removeOfflineDownload = { _, _ -> },
                        openSearch = {}, openSettings = {}, refreshCatalog = {}, loadMoreEpisodes = {}, openTmdbMovie = {}, openTmdbSeries = {},
                        refreshEpisodeTmdb = { selected ->
                            refreshedEpisode = selected.id
                            model.value = model.value.copy(episodeTmdbRefresh = EpisodeTmdbRefresh(profile.cacheKey(), series.id,
                                selected.id, running = false, message = "Updated TMDB details for this fixture episode."))
                        }, dismissEpisodeTmdbRefresh = { model.value = model.value.copy(episodeTmdbRefresh = null) })
                }
            } } }
            await("Middle watched episode and neighbour are visible") {
                label("Compact episode 250")?.isVisibleToUser == true && label("Compact episode 251")?.isVisibleToUser == true
            }
            assertTrue(bounds(requireNotNull(label("Compact episode 251"))).top < bounds(requireNotNull(label("Compact episode 250"))).top)
            val watchedBadge = bounds(requireNotNull(label("Last watched")))
            val watchedNumber = bounds(requireNotNull(label("S1:E250")))
            assertTrue("Watched badge is above episode metadata", watchedBadge.bottom <= watchedNumber.top)
            assertTrue("Watched badge is on the right", watchedBadge.right > watchedNumber.right)
            assertEquals("Episode number columns stay aligned", watchedNumber.left, bounds(requireNotNull(label("S1:E251"))).left)
            val header = bounds(requireNotNull(label(series.title)))
            val search = bounds(requireNotNull(label("Search episodes")))
            var scroller = label("Compact episode 250")
            repeat(12) { if (scroller?.isScrollable != true) scroller = scroller?.parent }
            assertTrue("Episode pane scrolls independently", scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            await("Scroll moves episode rows") { label("Compact episode 250")?.isVisibleToUser != true }
            assertEquals(header, bounds(requireNotNull(label(series.title))))
            assertEquals(search, bounds(requireNotNull(label("Search episodes"))))
            click("Episode options")
            await("Jump action is available") { label("Last watched episode") != null }
            SystemClock.sleep(250) // Capture the settled popup, after its opening fade.
            val sortSnapshot = instrumentation.uiAutomation.takeScreenshot()
            try { java.io.File(context.externalCacheDir, "episode-sort-popup.png").outputStream().use {
                sortSnapshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { sortSnapshot.recycle() }
            click("Last watched episode")
            await("Jump restores watched episode and neighbour") {
                label("Compact episode 250")?.isVisibleToUser == true && label("Compact episode 251")?.isVisibleToUser == true
            }
            click("Related")
            await("Related expands inline and moves the episode toolbar down") {
                label("Search episodes")?.isVisibleToUser == true && bounds(requireNotNull(label("Search episodes"))).top > search.top
            }
            assertNull("Related is inline, not a popup", label("Close related"))
            assertEquals(header, bounds(requireNotNull(label(series.title))))
            await("Expanded Related exposes its collapse action") { label("Hide related") != null }
            var activeRelated = label("Hide related")
            // Compose exposes selected buttons as checked; tabs expose isSelected.
            repeat(8) { if (activeRelated?.isSelected != true && activeRelated?.isChecked != true) activeRelated = activeRelated?.parent }
            assertTrue("Expanded Related is marked as active", activeRelated?.isSelected == true || activeRelated?.isChecked == true)
            click("Hide related")
            await("Toggling Related restores the toolbar") { label("Search episodes")?.let { bounds(it) == search } == true }
            assertNotNull("Collapsed button restores its original label", label("Related"))
            assertEquals(header, bounds(requireNotNull(label(series.title))))
            assertEquals(search, bounds(requireNotNull(label("Search episodes"))))
            assertTrue(label("Compact episode 250")?.isVisibleToUser == true)
            click("Related")
            await("Related reopens inline") { label("Search episodes")?.let { bounds(it).top > search.top } == true }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back collapses Related") { label("Search episodes")?.let { bounds(it) == search } == true }
            click("Series information")
            await("Info shows full description") { label(series.description!!) != null }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Closing Info preserves list position") { label(series.description!!) == null && label("Compact episode 250")?.isVisibleToUser == true }
            var episodeAction = label("Compact episode 250")
            repeat(10) { if (episodeAction?.isLongClickable != true) episodeAction = episodeAction?.parent }
            assertTrue("Episode long press opens its own menu", episodeAction?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
            await("Episode-specific metadata action is available") { label("Refresh TMDB details") != null }
            click("Refresh TMDB details")
            await("Only the selected episode is refreshed") { refreshedEpisode == "compact-250" && label("Updated TMDB details for this fixture episode.") != null }
            click("Close")
            await("Metadata result closes without changing the list position") {
                model.value.episodeTmdbRefresh == null && label("Compact episode 250")?.isVisibleToUser == true
            }
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { java.io.File(context.externalCacheDir, "compact-series-layout.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
