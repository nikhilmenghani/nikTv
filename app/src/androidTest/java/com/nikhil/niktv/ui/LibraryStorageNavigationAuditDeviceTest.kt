package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.discovery.components.DiscoveryFavoritesContent
import com.nikhil.niktv.model.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Production My List bodies and Storage; all data and actions stay inside the fixture. */
class LibraryStorageNavigationAuditDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    @Test fun wideHeaderKeepsFullMenuAndRestoresProfileAfterPortalSelection() = checkWideHeader(0)
    @Test fun tvSizedHeaderKeepsFullMenuAndRestoresProfileAfterPortalSelection() = checkWideHeader(1)
    @Test fun mobileLandscapeHeaderKeepsFullMenuAndScrollsPortalPopup() = checkWideHeader(2)
    private fun checkWideHeader(mode: Int) {
        val activity = host()
        val switches = AtomicInteger()
        val profileOpens = AtomicInteger()
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                var selected by remember { mutableStateOf(1) }
                val names = listOf("Xtream", "Stalker", "Sports", "Playfy TV", "Torrents")
                val icons = listOf(Icons.Default.Key, Icons.Default.Tv, Icons.Default.SportsCricket, Icons.Default.LiveTv, Icons.Default.Download)
                val actions = names.mapIndexed { index, name -> DirectPortalAction(name, icons[index], selected == index, id = name) {
                    selected = index; switches.incrementAndGet()
                } }
                val cfg = android.content.res.Configuration(LocalConfiguration.current).apply {
                    if (mode == 1) { screenWidthDp = 960; screenHeightDp = 540; uiMode = android.content.res.Configuration.UI_MODE_TYPE_TELEVISION }
                    if (mode == 2) { screenWidthDp = 800; screenHeightDp = 360; smallestScreenWidthDp = 360; orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                }
                CompositionLocalProvider(LocalConfiguration provides cfg, LocalDirectPortalActions provides actions,
                    LocalOpenUserProfiles provides { profileOpens.incrementAndGet() }) {
                    Column(Modifier.widthIn(max = if (mode == 1) androidx.compose.ui.unit.Dp(960f) else if (mode == 2) androidx.compose.ui.unit.Dp(800f) else androidx.compose.ui.unit.Dp.Infinity).fillMaxSize()) {
                        PortalWideHeader("Test", {}) {
                            DiscoveryAction("Back", Icons.Default.ArrowBack, iconOnly = true, onClick = {})
                            DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, onClick = {})
                            listOf("Home" to Icons.Default.Home, "Movies" to Icons.Default.Movie, "Series" to Icons.Default.Tv,
                                "Live TV" to Icons.Default.LiveTv, "My List" to Icons.Default.VideoLibrary).forEach { (name, icon) ->
                                DiscoveryAction(name, icon, onClick = {})
                            }
                            PortalOptionsButton("Options", open = {})
                        }
                        Text("Content below menu")
                    }
                }
            } } }
            val profile = "Profile and portals: Test"
            await("Wide menu is mounted") { control(root(), profile) != null && control(root(), "My List") != null }
            assertNull("Portal buttons use no space before the menu opens", find(root(), "Stalker"))
            for (label in listOf("Home", "Movies", "Series", "Live TV", "My List")) {
                val before = android.graphics.Rect().also { control(root(), label)!!.getBoundsInScreen(it) }
                focus(label)
                val after = android.graphics.Rect().also { control(root(), label)!!.getBoundsInScreen(it) }
                assertEquals("Focus does not resize $label", before, after)
                assertTrue("$label has room for its caption", after.width() > 70)
            }
            NavigationAuditEvidence.capture("wide-menu-full-labels")
            focus(profile); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Active portal is focused when opening") { isFocused("Stalker") }
            NavigationAuditEvidence.capture("profile-expanded-portal-menu")
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns directly to profile") { isFocused(profile) && find(root(), "Stalker") == null }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Portal menu reopens") { isFocused("Stalker") }
            focus("Xtream"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selecting portal retains the profile target") { switches.get() == 1 && isFocused(profile) && find(root(), "Xtream") == null }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Newly selected portal is the popup entry") { isFocused("Xtream") }
            if (mode == 2) repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) } else focus("Switch profile")
            assertTrue("Profile action is reachable below the portal choices", isFocused("Switch profile"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Profile chooser remains independently accessible") { profileOpens.get() == 1 }
        } finally { main { activity.finish() } }
    }
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun host(): PlaybackFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun focused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) focused(node.getChild(i))?.let { return it }
        return null
    }
    private fun owner(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var result = node
        while (result != null && !result.isClickable) result = result.parent
        return result
    }
    private fun control(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label) {
            owner(node)?.takeIf { it.isVisibleToUser && it.isEnabled }?.let { return it }
        }
        for (i in 0 until node.childCount) control(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun sameControl(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        fun ancestor(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Boolean {
            var next: AccessibilityNodeInfo? = child
            repeat(12) { if (next == parent) return true; next = next?.parent }
            return false
        }
        return ancestor(a, b) || ancestor(b, a)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(60) }
        NavigationAuditEvidence.capture("library-storage-failure-$label")
        fail(label)
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); Thread.sleep(110) }
    private fun isFocused(label: String): Boolean {
        val screen = root()
        val action = control(screen, label) ?: return false
        return focused(screen)?.let { sameControl(action, it) } == true
    }
    private fun focus(label: String, target: (AccessibilityNodeInfo?) -> AccessibilityNodeInfo? = { control(it, label) }) {
        repeat(70) {
            val screen = root()
            val current = focused(screen)
            val destination = target(screen)
            if (current != null && destination != null && sameControl(current, destination)) return
            if (current == null) { if (it == 0) key(KeyEvent.KEYCODE_DPAD_DOWN) else Thread.sleep(70); return@repeat }
            val direction = if (destination == null) KeyEvent.KEYCODE_DPAD_UP else {
                val from = Rect().also(current::getBoundsInScreen)
                val to = Rect().also(destination::getBoundsInScreen)
                val dy = to.centerY() - from.centerY()
                if (kotlin.math.abs(dy) > minOf(from.height(), to.height()) / 2)
                    if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                else if (to.centerX() > from.centerX()) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            }
            key(direction)
        }
        NavigationAuditEvidence.capture("library-storage-focus-$label")
        fail("D-pad reaches $label")
    }

    @Test fun myListHeroSavedRowsRemovalCancelAndBackSupportDpad() {
        val activity = host()
        val profile = PortalProfile("Library audit", "https://library-audit.invalid", "")
        val saved = listOf(
            FavoriteItem(FavoriteKind.MOVIE, MediaItem("movie-1", "Saved movie 1", null, null), addedAtMillis = 104, profileKey = profile.cacheKey()),
            FavoriteItem(FavoriteKind.MOVIE, MediaItem("movie-2", "Saved movie 2", null, null), addedAtMillis = 103, profileKey = profile.cacheKey()),
            FavoriteItem(FavoriteKind.SERIES, MediaItem("series-1", "Saved series 1", null, null), addedAtMillis = 102, profileKey = profile.cacheKey()),
            FavoriteItem(FavoriteKind.SERIES, MediaItem("series-2", "Saved series 2", null, null), addedAtMillis = 101, profileKey = profile.cacheKey()))
        val state = NikTvState(savedProfile = profile, restoring = false, homeOpen = false, favoritesOpen = true, favorites = saved)
        val opened = AtomicReference<FavoriteItem?>()
        val removals = AtomicInteger()
        val closed = mutableStateOf(false)
        val browsing = mutableStateOf<FavoriteKind?>(null)
        val phone = usesDiscoveryPhoneLayout(ins.targetContext.resources.configuration.smallestScreenWidthDp,
            ins.targetContext.isTvLikeDevice(ins.targetContext.resources.configuration))
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                if (closed.value) Text("Library closed") else {
                    BackHandler { if (browsing.value != null) browsing.value = null else closed.value = true }
                    // The production library reserves dock space; only discovery
                    // preview feeds draw their phone dock above the content.
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            DiscoveryLibraryScreen(state, DiscoveryLibraryDestination.MY_LIST,
                                back = { closed.value = true }, select = { closed.value = true }, navigate = { closed.value = true }) {
                                if (browsing.value != null || useEstablishedLibraryBrowser(DiscoveryLibraryDestination.MY_LIST, phone))
                                    DiscoveryFavoritesContent(state.copy(favorites = saved.filter { browsing.value == null || it.kind == browsing.value }),
                                        opened::set, { removals.incrementAndGet() })
                                else DiscoveryLibraryFeed(state, DiscoveryLibraryDestination.MY_LIST, 0,
                                    openFavorite = opened::set, toggleFavorite = { removals.incrementAndGet() }, openRecent = {},
                                    openChannel = { _, _, _ -> }, openCategory = {}, browseCategories = {}, configureCategories = {},
                                    browseSaved = { browsing.value = it }, search = {}, refreshProgramme = {}, enrichGuides = {}, background = {})
                            }
                        }
                        if (phone) {
                            com.nikhil.niktv.discovery.components.MobileLibrarySwitcher(false,
                                { browsing.value = null }, { closed.value = true })
                            DiscoveryPhoneBottomNavigation(DiscoveryPhoneDestination.LIBRARY, { closed.value = true },
                                Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                        }
                    }
                }
            } } } }
            await("My List hero is ready") { control(root(), "Open") != null }
            assertNull("Home-style Open replaces duplicate Info", control(root(), "Info"))
            assertNull("Trailer belongs on the detail page", control(root(), "Watch trailer"))
            focus("Open")
            NavigationAuditEvidence.capture("my-list-hero-focus")
            focus(if (phone) "Remove from My List" else "In My List")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Hero removal starts on Keep") { find(root(), "Remove from My List?") != null && isFocused("Keep") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Removal confirmation is reachable without selecting it") { isFocused("Remove") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("D-pad returns to Keep") { isFocused("Keep") }
            NavigationAuditEvidence.capture("my-list-remove-keep-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Keeping the favorite closes its dialog") { find(root(), "Remove from My List?") == null }
            assertEquals(0, removals.get())
            focus("Open"); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Hero Down enters first saved movie") { isFocused("Saved movie 1") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters second saved movie") { isFocused("Saved movie 2") }
            if (phone) {
                assertNull("A fully visible collection needs no empty View all page", control(root(), "View all Saved movies"))
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters the saved series row") { isFocused("Saved series 1") || isFocused("Saved series 2") }
            focus("Saved series 1")
            if (phone) {
                val screen = root()
                val card = Rect().also(requireNotNull(focused(screen))::getBoundsInScreen)
                val dock = Rect().also(requireNotNull(control(screen, "My List"))::getBoundsInScreen)
                assertTrue("Focused saved card is fully above the phone dock", card.bottom <= dock.top)
            }
            NavigationAuditEvidence.capture("my-list-saved-series-focus")
            assertNull("Browsing cannot open content", opened.get())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Center opens the focused saved series") { opened.get()?.media?.id == "series-1" }
            focus("Saved movie 1"); key(KeyEvent.KEYCODE_DPAD_UP)
            await("First saved row Up restores the hero") { isFocused("Open") }
            NavigationAuditEvidence.capture("my-list-returned-hero-focus")
            key(KeyEvent.KEYCODE_BACK)
            await("Back leaves My List") { closed.value }
            assertEquals(0, removals.get())
        } finally { main { activity.finish() } }
    }

    @Test fun settingsStorageFiltersPortalAndCancellationKeepsTheFile() {
        val activity = host()
        val closed = mutableStateOf(false)
        val person = UserProfile("11111111-2222-3333-4444-555555555555", "Storage test")
        val snapshot = ProfileStorageSnapshot(10_000_000, 5_000_000, listOf(
            ProfileStorageFile("fixture-stream", "Cached episode.mkv", "torrents", StorageFileKind.STREAM, 4096),
            ProfileStorageFile("fixture-iptv", "IPTV movie", "stalker:fixture", StorageFileKind.DOWNLOAD, 8192)),
            100, 100, 100, 100)
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                BackHandler { closed.value = true }
                if (closed.value) Text("Storage closed") else ProfileStorageSettings(NikTvState(restoring = false),
                    snapshotOverride = snapshot, profilesOverride = listOf(person))
            } } } }
            await("Profile storage files loaded") { find(root(), "Cached episode.mkv") != null && find(root(), "IPTV movie") != null }
            focus("Portal · All portals"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Portal dropdown opens on selected item") { isFocused("All portals") }
            focus("Torrents"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Only the chosen portal files remain") { find(root(), "Cached episode.mkv") != null && find(root(), "IPTV movie") == null }
            focus("Clear Cached episode.mkv"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Clear confirmation focuses Cancel") { isFocused("Cancel") }
            NavigationAuditEvidence.capture("settings-storage-confirmation-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Cancel preserves the file and returns focus") {
                find(root(), "Clear Cached episode.mkv?") == null && isFocused("Clear Cached episode.mkv")
            }
            focus("Portal · Torrents"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            focus("Shared app caches"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Shared caches are clearly separate") { find(root(), "Shared across all profiles on this device") != null }
            NavigationAuditEvidence.capture("settings-storage-shared-caches")
            key(KeyEvent.KEYCODE_BACK)
            await("Back leaves settings content") { closed.value }
        } finally { main { activity.finish() } }
    }
}
