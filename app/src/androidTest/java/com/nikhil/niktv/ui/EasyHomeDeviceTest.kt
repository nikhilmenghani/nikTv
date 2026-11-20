package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ProfileBrowsePreferences
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ViewerLayout
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Drives authenticated production navigation with a cached IPTV fixture. */
class EasyHomeDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()

    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return visit(ins.uiAutomation.rootInActiveWindow)
    }

    private fun control(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        repeat(8) {
            if (node?.isClickable == true) return node
            node = node?.parent
        }
        return null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000L
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(40L)
        assertTrue(message, condition())
    }

    private fun key(code: Int) = ins.sendKeyDownUpSync(code)

    private fun focusedNode(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun queryHasFocus(): Boolean {
        val label = find("Search title or channel") ?: return false
        val target = focusedNode() ?: return false
        // Compose exposes the editor's label and its inner focus target as sibling semantics nodes.
        return Rect().also(target::getBoundsInScreen).contains(Rect().also(label::getBoundsInScreen))
    }

    private fun imeVisible(host: PlaybackFixtureHostActivity): Boolean {
        var visible = false
        ins.runOnMainSync { visible = host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return visible
    }

    private fun tap(label: String) {
        val target = Rect().also { requireNotNull(control(label)).getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            try { ins.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    @Test fun easyLayoutIsSelectableAndAllPagesWorkByRemoteAndTouch() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val oldAccount = users.selectedAccount.value
        val oldOwner = users.selectedScope.value
        val account = "991121"
        val owner = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
        val other = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.KIDS))
        runBlocking {
            users.selectAccount(account)
            users.initializeDefaults(account)
            users.selectProfile(owner.profileId)
            ProfileBrowsePreferences.setLayout(context, owner, ViewerLayout.STANDARD)
        }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val setup = mutableStateOf(true)
        var vm: NikTvViewModel? = null
        lateinit var appState: MutableStateFlow<NikTvState>
        val movie = MediaItem("fixture-movie", "Fixture Movie", null, "ffmpeg https://fixture.invalid/movie")
        val series = MediaItem("fixture-series", "Fixture Series", null, "ffmpeg https://fixture.invalid/series")
        val channel = MediaItem("fixture-channel", "Fixture Channel", null, "ffmpeg https://fixture.invalid/channel")
        val focused = java.util.concurrent.CopyOnWriteArrayList<String>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.packageName == context.packageName) {
                val node = event.source
                focused += (node?.contentDescription ?: node?.text?.takeIf { it.isNotBlank() }
                    ?: node?.getChild(0)?.text ?: event.text.joinToString()).toString()
            }
        }
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync {
                val appVm = NikTvViewModel(context.applicationContext as Application)
                vm = appVm
                appVm.viewModelScope.coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                appState = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(appVm) as MutableStateFlow<NikTvState>
                val portal = PortalProfile("Fixture IPTV", "https://fixture.invalid", "00:00:00:00:00:01")
                val session = PortalSession(portal, "fixture", portal.portalUrl, "", "", "")
                val catalog = mapOf(
                    Category("local-news", "Fixture news", CatalogType.LIVE_TV) to channel,
                    Category("local-movies", "Fixture comedies", CatalogType.MOVIES) to movie,
                    Category("local-series", "Fixture dramas", CatalogType.SERIES) to series
                )
                val now = System.currentTimeMillis()
                val caches = catalog.map { (category, media) ->
                    category.type to BrowseCatalogCache(portal.cacheKey(), category.type, now, listOf(category),
                        mapOf(category.id to listOf(media)), mapOf(category.id to 1), mapOf(category.id to false),
                        mapOf(category.id to now))
                }.toMap()
                val episodeCacheVersion = NikTvViewModel::class.java.getDeclaredField("EPISODE_METADATA_VERSION")
                    .apply { isAccessible = true }.getInt(null)
                val seasons = listOf(1, 2)
                val episodes = seasons.map { season ->
                    val episode = MediaItem("episode-$season", "Fixture Episode $season", null,
                        "ffmpeg https://fixture.invalid/episode", seasonNumber = season, episodeNumber = 1)
                    EpisodeSeasonCache(portal.cacheKey(), series.id, season, seasons, listOf(episode),
                        metadataVersion = episodeCacheVersion, cachedAtMillis = now, iptvEpisodes = listOf(episode),
                        tmdbCachedAtMillis = now, seasonIndexValidated = true)
                }
                NikTvViewModel::class.java.getDeclaredField("episodeSeasonCaches").apply { isAccessible = true }
                    .set(appVm, episodes)
                appState.value = NikTvState(profiles = listOf(portal), savedProfile = portal, session = session,
                    homeOpen = true, discoverySurface = DashboardSurface.HOME, restoring = false,
                    rawCategoriesByType = catalog.keys.groupBy(Category::type), browseCachesByType = caches,
                    favorites = listOf(FavoriteItem(FavoriteKind.MOVIE, movie, profileKey = portal.cacheKey()),
                        FavoriteItem(FavoriteKind.MOVIE, movie.copy(id = "tmdb-42", title = "TMDB only"))))
                host.setContent {
                    CompositionLocalProvider(LocalViewingOwner provides owner) {
                        if (setup.value) MaterialTheme(colorScheme = NikColors) {
                            Surface {
                                CompositionLocalProvider(LocalSettingsPage provides SettingsPage.VIEWER_PORTALS) {
                                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                                        ViewerProfileSettings(owner.profileId, listOf(portal))
                                    }
                                }
                            }
                        } else NikTvAuthenticatedApp(appVm)
                    }
                }
            }
            await("Profile Settings shows the layout control") { control("App layout") != null }
            tap("App layout")
            await("The Easy choice is visible") { control("Easy") != null }
            tap("Easy")
            await("The active profile saves Easy") { ProfileBrowsePreferences.layout(context, owner) == ViewerLayout.EASY }
            assertEquals(ViewerLayout.STANDARD, ProfileBrowsePreferences.layout(context, other))
            val appVm = requireNotNull(vm)
            ins.setInTouchMode(false)
            ins.runOnMainSync { setup.value = false }
            await("Production Home shows the IPTV shortcuts") {
                listOf("Live TV", "Movies", "Series", "Search", "My List").all { control(it) != null }
            }
            await("D-pad starts on Live TV") { control("Live TV")?.isFocused == true }
            NavigationAuditEvidence.capture("easy-home-focused")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right moves from Live TV to Movies") { control("Movies")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to Live TV") { control("Live TV")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK opens the IPTV Live TV catalog") {
                appVm.state.value.discoverySurface == null && appVm.state.value.selectedType == CatalogType.LIVE_TV &&
                    find("Fixture news") != null
            }
            await("The first channel category is focused") { control("Fixture news")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The category opens its IPTV channels") { control("Fixture Channel") != null }
            NavigationAuditEvidence.capture("easy-live-channels")
            key(KeyEvent.KEYCODE_BACK)
            await("Back focuses the original channel category") { control("Fixture news")?.isFocused == true }
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns directly to the Live TV shortcut on Easy Home") {
                appVm.state.value.discoverySurface == DashboardSurface.HOME && control("Live TV")?.isFocused == true &&
                    control("Profiles") != null
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Movies shortcut is focused") { control("Movies")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Movies shows the IPTV categories") { control("Fixture comedies")?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Movies opens the category's saved catalog") { control("Fixture Movie") != null }
            NavigationAuditEvidence.capture("easy-movies")
            key(KeyEvent.KEYCODE_BACK)
            await("Movies restores the category") { control("Fixture comedies")?.isFocused == true }
            key(KeyEvent.KEYCODE_BACK)
            await("Movies returns to Easy Home") { control("Movies")?.isFocused == true && control("Profiles") != null }

            ins.setInTouchMode(true)
            tap("Series")
            await("Series opens the IPTV categories") { control("Fixture dramas") != null }
            tap("Fixture dramas")
            await("Series opens titles in the category") { control("Fixture Series") != null }
            tap("Fixture Series")
            await("Series opens provider episodes") { control("Fixture Episode 1") != null && control("Season 1") != null }
            tap("Season 1")
            await("Shows the other provider season") { control("Season 2") != null }
            tap("Season 2")
            await("Season 2 displays its episodes") { control("Fixture Episode 2") != null && control("Season 2") != null }
            NavigationAuditEvidence.capture("easy-episodes")
            tap("Home")
            await("Home returns directly from episodes") { control("Profiles") != null }
            tap("My List")
            await("My List shows saved IPTV titles and downloads") { control("Fixture Movie") != null && control("Downloads") != null }
            assertNull(find("TMDB only"))
            NavigationAuditEvidence.capture("easy-my-list")
            tap("Downloads")
            await("Downloads opens from My List") { appVm.state.value.offlineDownloadsOpen }
            key(KeyEvent.KEYCODE_BACK)
            await("Back from Downloads returns to My List") { control("Fixture Movie") != null && appVm.state.value.favoritesOpen }
            tap("Home")
            await("Home returns directly from My List") { control("Profiles") != null }
            tap("Search")
            await("Touch opens IPTV Search") { appVm.state.value.searchOpen && find("Enter a title or channel") != null }
            ins.setInTouchMode(false)
            await("The search editor is focused") { queryHasFocus() }
            assertFalse("Directional focus doesn't open the keyboard", imeVisible(host))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK opens the keyboard") { imeVisible(host) }
            focused.clear()
            key(KeyEvent.KEYCODE_BACK)
            await("First Back dismisses the keyboard and retains editor focus") {
                !imeVisible(host) && appVm.state.value.searchOpen && queryHasFocus()
            }
            assertFalse("Keyboard dismissal avoids intermediate Back or Home focus", focused.any { it in listOf("Back", "Home") })
            ins.runOnMainSync {
                appState.update { it.copy(searchQuery = "Fixture", searchResults = listOf(
                    series.copy(searchResultType = SearchContentType.SERIES)), searchUsedServer = true) }
            }
            await("The single IPTV result is visible") { control("Fixture Series") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches the IPTV result") { control("Fixture Series")?.isFocused == true }
            NavigationAuditEvidence.capture("easy-search-results")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selecting a series result opens its episodes") { control("Fixture Episode 1") != null }
            focused.clear()
            key(KeyEvent.KEYCODE_BACK)
            await("Back focuses the original series result") { appVm.state.value.searchOpen && control("Fixture Series")?.isFocused == true }
            assertFalse("Returning to results avoids intermediate Back or Home focus", focused.any { it in listOf("Back", "Home") })
            key(KeyEvent.KEYCODE_BACK)
            await("Second Back returns to Easy Home") { !appVm.state.value.searchOpen && control("Profiles") != null }
        } catch (failure: AssertionError) {
            NavigationAuditEvidence.capture("easy-navigation-failure")
            val node = focusedNode()
            throw AssertionError("${failure.message}; focus=${node?.contentDescription ?: node?.text}; focus events=$focused", failure)
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.runOnMainSync {
                vm?.viewModelScope?.coroutineContext?.cancelChildren()
                host.finish()
            }
            runBlocking {
                ProfileBrowsePreferences.setLayout(context, owner, ViewerLayout.STANDARD)
                if (oldAccount == null) users.disconnect() else {
                    users.selectAccount(oldAccount)
                    oldOwner?.let { users.selectProfile(it.profileId) }
                }
            }
        }
    }
}
