package com.nikhil.niktv.ui

import android.app.Application
import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Real header composition with a local provider cache. Action callbacks cannot open streams. */
class ProviderDiscoveryHeroDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private var activeVm: ProviderDiscoveryViewModel? = null
    private var activeHost: PlaybackFixtureHostActivity? = null
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.contentDescription?.toString() == label || n.text?.toString() == label) return n
            for (index in 0 until n.childCount) n.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        val roots = listOfNotNull(i.uiAutomation.rootInActiveWindow) + i.uiAutomation.windows.mapNotNull { it.root }
        return roots.filter { it.packageName == i.targetContext.packageName }.firstNotNullOfOrNull(::find)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        val state = activeVm?.state?.value
        i.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nHEADER_FIXTURE_FAILURE ready=${state?.ready} categories=${state?.categories?.values?.sumOf { it.size }} pages=${state?.pages?.values?.sumOf { it.size }} items=${state?.items?.values?.sumOf { it.size }} errors=${state?.errors?.keys} windows=${i.uiAutomation.windows.map { it.root?.packageName }}\n") })
        if (activeHost?.hasWindowFocus() == true) i.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(i.targetContext.getExternalFilesDir(null), "header-fixture-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
        fail(message)
    }
    private fun click(label: String) {
        await("Action $label is available") { node(label) != null }
        var target = node(label)!!
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Action $label accepts touch/accessibility click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun favoritesAndOriginalProviderActionsUpdateWithoutAFeedArrival() = fixture { host, vm, presentation, items, plays, infos ->
        await("First cached title") { node(items[0].title) != null }
        val pages = vm.state.value.pages
        click("My List")
        await("Favorite icon updates immediately") { node("In My List") != null }
        assertEquals(setOf(items[0].id), presentation.value.favoriteIds)
        assertSame("Favorite does not need a provider feed emission", pages, vm.state.value.pages)
        click("In My List"); await("Favorite removal updates immediately") { node("My List") != null }
        click("Play"); click("Info")
        assertEquals(listOf(items[0]), plays); assertEquals(listOf(items[0]), infos)
        click("Next featured title"); await("Manual paging selects next provider identity") { node(items[1].title) != null }
        click("Play"); assertEquals(items[1], plays.last())
        assertFalse(plays.any { it.id.startsWith("tmdb-") })
    }
    @Test fun seriesHeaderKeepsProviderActionsAndOnlyLabelsValidIptvProgressAsResume() = fixture(CatalogType.SERIES) { _, vm, presentation, items, plays, infos ->
        await("First cached series title") { node(items[0].title) != null }
        val profile = vm.state.value.profile
        val episode = MediaItem("provider-episode", "Saved episode", null, "fixture://episode", seasonNumber = 2, episodeNumber = 9)
        val recent = RecentItem(FavoriteKind.SERIES, items[0], lastPlayed = episode, profileKey = profile)
        val progress = PlaybackProgress(requireNotNull(discoveryRecentProgressPrefix(recent)) + "fixture", 120_000, 1_200_000)
        val lookup = DiscoveryProgressLookup.build(profile, listOf(recent), listOf(progress))
        i.runOnMainSync { presentation.value = presentation.value.copy(progress = lookup, seriesRecents = listOf(recent)) }
        await("Provider series offers valid Resume") { node("Resume") != null }
        click("Resume"); click("Info")
        assertSame(items[0], plays.single()); assertSame(items[0], infos.single())
        i.runOnMainSync { presentation.value = presentation.value.copy(progress = null) }
        await("Unknown progress does not offer Resume") { node("Play") != null }
        click("Next featured title"); await("Second series keeps provider identity") { node(items[1].title) != null }
        click("Play"); assertSame(items[1], plays.last())
        assertFalse(plays.any { it.id.startsWith("tmdb-") })
    }

    @Test fun backgroundAndFocusedStatesPauseRotationWithoutNewFeedData() = fixture { _, _, presentation, items, _, _ ->
        await("First cached title") { node(items[0].title) != null }
        i.runOnMainSync { presentation.value = presentation.value.copy(active = false, mayRotate = true) }
        Thread.sleep(8_300)
        assertNotNull("Inactive header does not rotate", node(items[0].title))
        i.runOnMainSync { presentation.value = presentation.value.copy(active = true, mayRotate = true) }
        await("Idle active header rotates after eight seconds") { node(items[1].title) != null }
        i.runOnMainSync { presentation.value = presentation.value.copy(mayRotate = false) }
        Thread.sleep(8_300)
        assertNotNull("Focused/popup state stops the next rotation", node(items[1].title))
    }
    @Test fun tabletPortraitAndLandscapeKeepActionsVisibleAndProviderIdentity() {
        val configuration = i.targetContext.resources.configuration
        assumeTrue("Physical tablet orientation fixture", configuration.smallestScreenWidthDp >= 600 &&
            !i.targetContext.isTvLikeDevice(configuration))
        assumeTrue("Requires host-scoped display rotation with guaranteed restoration",
            InstrumentationRegistry.getArguments().getString("scopedDisplayRotation") == "true")
        fixture { _, _, _, items, plays, infos ->
            var selected = 0
            for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT,
                Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT)) {
                // The host runner scopes wm user-rotation and restores its original mode in
                // a host try/finally. API 36 tablet policy may ignore requestedOrientation.
                i.sendStatus(2, Bundle().apply { putString("stream", "\nHEADER_ORIENTATION_REQUEST=" +
                    (if (orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape") + "\n") })
                await("Rotated tablet retains the selected provider title") {
                    activeHost?.let { !it.isDestroyed && it.resources.configuration.orientation == orientation &&
                        it.hasWindowFocus() && node(items[selected].title) != null } == true
                }
                for (label in listOf(items[selected].title, "Play", "Info", "My List", "Next featured title")) {
                    await("Rotated $label has visible bounds") {
                        val target = node(label) ?: return@await false
                        val bounds = Rect(); target.getBoundsInScreen(bounds)
                        val window = Rect(); activeHost!!.window.decorView.getWindowVisibleDisplayFrame(window)
                        target.isVisibleToUser && !bounds.isEmpty && window.contains(bounds)
                    }
                }
                click("Play"); click("Info")
                assertEquals(items[selected], plays.last()); assertEquals(items[selected], infos.last())
                if (selected == 0) {
                    click("Next featured title"); selected = 1
                    await("Carousel selection before recreation") { node(items[selected].title) != null }
                }
            }
            assertEquals(listOf(items[0], items[1], items[1]), plays)
            assertEquals(plays, infos)
        }
    }
    private fun fixture(type: CatalogType = CatalogType.MOVIES, test: (PlaybackFixtureHostActivity, ProviderDiscoveryViewModel,
        androidx.compose.runtime.MutableState<ProviderHeroPresentation>, List<MediaItem>, MutableList<MediaItem>, MutableList<MediaItem>) -> Unit) {
        check(i.targetContext.packageName.endsWith(".regression"))
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val host = i.startActivitySync(Intent(i.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        activeHost = host
        val profile = PortalProfile("Local fixture", "https://example.invalid", "fixture")
        val label = if (type == CatalogType.SERIES) "series" else "movie"
        val category = Category("local", "Local cached $label", type)
        val items = listOf(MediaItem("provider-one", "First cached fixture $label", null, "fixture://one"),
            MediaItem("provider-two", "Second cached fixture $label", null, "fixture://two"))
        var reads = 0; var requests = 0
        val source = object : ProviderDiscoverySource {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                reads++; return mapOf(category.id to ProviderDiscoveryPage(items, 1, false, System.currentTimeMillis()))
            }
            override suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage {
                requests++; error("Fixture must never open a provider request")
            }
            override suspend fun save(cache: ProviderDiscoveryCache) { error("Fixture must not write provider data") }
        }
        val vm = ProviderDiscoveryViewModel(i.targetContext.applicationContext as Application, source)
        activeVm = vm
        val presentation = mutableStateOf(ProviderHeroPresentation(emptySet(), null, true, false, false, false, true, false))
        val plays = mutableListOf<MediaItem>(); val infos = mutableListOf<MediaItem>()
        val originalOrientation = host.requestedOrientation
        fun mount(current: PlaybackFixtureHostActivity) {
            activeHost = current
            current.window.decorView.isSoundEffectsEnabled = false
            current.setContent { MaterialTheme {
                val configuration = LocalConfiguration.current
                val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp,
                    LocalContext.current.isTvLikeDevice(configuration))
                ProviderDiscoveryHero(profile.cacheKey(), type, listOf(category), vm,
                    landscape = !phone, phone = phone,
                    portraitPhone = phone && configuration.screenHeightDp >= configuration.screenWidthDp,
                    homeFrame = !phone, reduceMotion = false,
                    presentation = presentation, playRequester = remember { FocusRequester() }, infoRequester = remember { FocusRequester() },
                    play = { item, queue -> assertTrue(item in queue); plays.add(item) },
                    add = { item -> presentation.value = presentation.value.copy(favoriteIds =
                        if (item.id in presentation.value.favoriteIds) presentation.value.favoriteIds - item.id else presentation.value.favoriteIds + item.id) },
                    info = { item, queue -> assertTrue(item in queue); infos.add(item) }, focused = {}, down = {}, up = {}, categoryActions = {})
            } }
        }
        // The debug host deliberately has no configChanges override. Recreate only the local
        // composition after rotation, retaining the fake source/VM and saved-state registry.
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is PlaybackFixtureHostActivity && activity !== activeHost) mount(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        host.application.registerActivityLifecycleCallbacks(callbacks)
        try {
            i.runOnMainSync {
                vm.bind(PortalSession(profile, "", "https://example.invalid", "", "", ""), mapOf(type to listOf(category)),
                    60_000, preview = true, initialPreviewWindow = mapOf(type to setOf(category.id)))
                mount(host)
            }
            test(host, vm, presentation, items, plays, infos)
            assertEquals(1, reads); assertEquals(0, requests)
        } finally { i.runOnMainSync {
            vm.pause(release = true)
            activeHost?.let { it.requestedOrientation = originalOrientation; it.finish() }
            host.application.unregisterActivityLifecycleCallbacks(callbacks)
            activeHost = null; activeVm = null
        } }
    }
}
