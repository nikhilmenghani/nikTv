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
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AppStorageSnapshot
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
        val phone = usesDiscoveryPhoneLayout(ins.targetContext.resources.configuration.smallestScreenWidthDp,
            ins.targetContext.isTvLikeDevice(ins.targetContext.resources.configuration))
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                if (closed.value) Text("Library closed") else {
                    BackHandler { closed.value = true }
                    // The production library reserves dock space; only discovery
                    // preview feeds draw their phone dock above the content.
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            DiscoveryLibraryScreen(state, DiscoveryLibraryDestination.MY_LIST,
                                back = { closed.value = true }, select = { closed.value = true }, navigate = { closed.value = true }) {
                                if (useEstablishedLibraryBrowser(DiscoveryLibraryDestination.MY_LIST, phone))
                                    DiscoveryFavoritesContent(state, opened::set, { removals.incrementAndGet() })
                                else DiscoveryLibraryFeed(state, DiscoveryLibraryDestination.MY_LIST, 0,
                                    openFavorite = opened::set, toggleFavorite = { removals.incrementAndGet() }, openRecent = {},
                                    openChannel = { _, _, _ -> }, openCategory = {}, browseCategories = {}, configureCategories = {},
                                    browseSaved = {}, search = {}, refreshProgramme = {}, enrichGuides = {}, background = {})
                            }
                        }
                        if (phone) DiscoveryPhoneBottomNavigation(DiscoveryPhoneDestination.LIBRARY, { closed.value = true },
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            } } } }
            await("My List hero is ready") { control(root(), "Open") != null }
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

    @Test fun storageAllCleanupActionsReachCancelAndBackWithoutDeletingData() {
        val activity = host()
        val deletes = AtomicInteger()
        val closed = mutableStateOf(false)
        val snapshot = AppStorageSnapshot(1_000_000, 500_000, 100, 100, 100, 100, 100, 100, 100)
        val rows = listOf(
            "Live TV recordings" to "Clear Live TV recordings?", "Media cache" to "Clear media cache?",
            "Offline downloads" to "Clear offline downloads?", "Artwork cache" to "Clear artwork cache?",
            "Downloaded subtitles" to "Clear downloaded subtitles?", "Temporary cache" to "Clear temporary cache?")
        fun clearFor(screen: AccessibilityNodeInfo?, row: String): AccessibilityNodeInfo? {
            var parent = find(screen, row)
            repeat(7) {
                control(parent, "Clear")?.let { return it }
                parent = parent?.parent
            }
            return null
        }
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                if (closed.value) Text("Storage closed") else {
                    BackHandler { closed.value = true }
                    OfflineStorageScreen(NikTvState(restoring = false), { deletes.incrementAndGet() },
                        { closed.value = true }, storageSnapshot = snapshot)
                }
            } } } }
            await("Storage controls loaded from synthetic snapshot") { clearFor(root(), rows.last().first)?.isEnabled == true }
            NavigationAuditEvidence.capture("storage-six-cleanup-controls")
            rows.forEach { (row, title) ->
                focus("Clear $row") { clearFor(it, row) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("$row confirmation opens") { find(root(), title) != null }
                focus("Cancel")
                NavigationAuditEvidence.capture("storage-cancel-${row.lowercase().replace(' ', '-')}")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("$row cancellation returns to Storage") { find(root(), title) == null && find(root(), row) != null }
                assertEquals("Cancellation never removes data", 0, deletes.get())
            }
            focus("Back"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Visible Back leaves Storage") { closed.value }
            assertEquals(0, deletes.get())
        } finally { main { activity.finish() } }
    }
}
