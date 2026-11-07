package com.nikhil.niktv.ui

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.nikhil.niktv.discovery.DiscoveryLibraryDestination
import com.nikhil.niktv.discovery.DiscoveryLibraryFeed
import com.nikhil.niktv.discovery.components.ModernIptvCollection
import com.nikhil.niktv.model.*
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Production live catalogue and guide, with no portal connection or stream preparation. */
class LiveCatalogNavigationAuditDeviceTest {
    private val test = InstrumentationRegistry.getInstrumentation()

    private fun tree(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun labelled(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString()?.lineSequence()?.any { it == label } == true ||
            node.contentDescription?.toString() == label

    private fun node(label: String) = find(tree()) { labelled(it, label) }

    private fun focused(label: String): Boolean {
        val root = tree()
        val actual = find(root) { it.isFocused } ?: return false
        var target = find(root) { labelled(it, label) }
        while (target != null) {
            if (target == actual) return true
            if (target.isClickable) return false
            target = target.parent
        }
        return false
    }

    private fun seedFocus(label: String) {
        var target = node(label)
        while (target != null && !target.isFocusable) target = target.parent
        assertTrue("Can focus $label", target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
        await("Focused $label") { focused(label) }
    }

    private fun key(code: Int) = test.sendKeyDownUpSync(code)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        NavigationAuditEvidence.capture("live-catalog-failure-$message")
        fail(message)
    }

    private fun host(): PlaybackFixtureHostActivity {
        val context = test.targetContext
        check(context.packageName.endsWith(".regression"))
        return test.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }

    @Test fun categoryChannelsQueueAndCachedGuideSupportRemoteNavigationAndBack() {
        val activity = host()
        test.setInTouchMode(false)
        val profile = PortalProfile("Live navigation", "https://live-audit.invalid", "00:00:00:00:00:31")
        val category = Category("live-audit-news", "Audit news", CatalogType.LIVE_TV)
        val now = System.currentTimeMillis()
        val channels = (1..9).map { index ->
            MediaItem("audit-channel-$index", "Audit channel $index", null, null,
                portalCategoryId = category.id, liveSchedule = listOf(
                    LiveProgramme("Audit programme $index", now - 300_000, now + 1_800_000),
                    LiveProgramme("Next programme $index", now + 1_800_000, now + 3_600_000)))
        }
        val state = NikTvState(savedProfile = profile, selectedType = CatalogType.LIVE_TV,
            categories = listOf(category), items = channels, restoring = false, homeOpen = false,
            browseCachesByType = mapOf(CatalogType.LIVE_TV to BrowseCatalogCache(profile.cacheKey(),
                CatalogType.LIVE_TV, now, listOf(category), mapOf(category.id to channels))))
        val selections = CopyOnWriteArrayList<Triple<String, List<String>, String?>>()
        val refreshes = CopyOnWriteArrayList<String>()
        val categoryVisits = AtomicInteger()
        val exits = AtomicInteger()
        try {
            test.runOnMainSync {
                activity.setContent { MaterialTheme(colorScheme = NikColors) {
                    var collection by remember { mutableStateOf(false) }
                    val saved = rememberSaveableStateHolder()
                    BackHandler { if (collection) collection = false else exits.incrementAndGet() }
                    Surface(Modifier.fillMaxSize()) {
                        if (collection) ModernIptvCollection(state, category,
                            close = { collection = false },
                            openItem = { selections += Triple(it.id, emptyList(), null) },
                            toggleFavorite = {}, loadMore = {}, refresh = { refreshes += "category" },
                            enrichFocusedMetadata = { _, _ -> }, enrichVisibleLiveGuides = {},
                            refreshLiveProgramme = { refreshes += it.id },
                            findChannelsInCategory = {}, cancelCategoryFind = {}, isTv = false,
                            playInContext = { item, queue, filter ->
                                selections += Triple(item.id, queue.map { it.id }, filter.contextId)
                            })
                        else saved.SaveableStateProvider("live-feed") {
                            DiscoveryLibraryFeed(state, DiscoveryLibraryDestination.LIVE_TV, 0,
                                openFavorite = {}, toggleFavorite = {}, openRecent = {},
                                openChannel = { item, queue, filter ->
                                    selections += Triple(item.id, queue.map { it.id }, filter.contextId)
                                }, openCategory = { categoryVisits.incrementAndGet(); collection = true },
                                browseCategories = {}, configureCategories = {}, browseSaved = {}, search = {},
                                refreshProgramme = { refreshes += it.id }, enrichGuides = {}, background = {})
                        }
                    }
                } }
            }
            await("Live feed has cached channels") { node("Watch") != null && node("View all") != null }
            seedFocus("Watch")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters first channel") { focused("Audit programme 1") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters second channel") { focused("Audit programme 2") }
            NavigationAuditEvidence.capture("live-feed-selected-channel")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Channel opens once with full category queue") { selections.size == 1 }
            assertEquals(channels[1].id, selections.single().first)
            assertEquals(channels.map { it.id }, selections.single().second)
            assertEquals(category.id, selections.single().third)

            seedFocus("View all")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Category opens from feed") { node("Refresh category") != null }
            assertEquals(1, categoryVisits.get())
            seedFocus("Audit programme 1")
            val phone = activity.resources.configuration.smallestScreenWidthDp < 600
            key(if (phone) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Category grid enters adjacent channel") { focused("Audit programme 2") }
            NavigationAuditEvidence.capture("live-category-channel-grid")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Category selection opens once") { selections.size == 2 }
            assertEquals(channels[1].id, selections.last().first)
            assertEquals(channels.map { it.id }, selections.last().second)
            assertEquals(category.id, selections.last().third)
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns to live feed") { node("Watch") != null && node("Refresh category") == null }

            seedFocus("Watch")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches My List action") { focused("My List") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Guide action") { focused("Guide") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Guide opens on Close") { focused("Close") && node("Fetch programme") != null }
            assertNotNull("Guide includes future cached programmes", node("Next programme 2")
                ?: find(tree()) { it.text?.toString()?.contains("Next programme 2") == true })
            NavigationAuditEvidence.capture("live-guide-cached-schedule")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left reaches guide refresh") { focused("Fetch programme") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Guide refresh uses selected channel") { refreshes.contains(channels[1].id) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right returns to guide Close") { focused("Close") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Guide Close restores Guide action") { focused("Guide") && node("Fetch programme") == null }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Guide reopens") { focused("Close") }
            key(KeyEvent.KEYCODE_BACK)
            await("Guide Back restores Guide action") { focused("Guide") && node("Fetch programme") == null }
            NavigationAuditEvidence.capture("live-guide-returned-focus")
            assertEquals("Modal Back never exits landing", 0, exits.get())
            key(KeyEvent.KEYCODE_BACK)
            await("Landing Back reaches app navigation") { exits.get() == 1 }
        } finally {
            test.runOnMainSync { activity.finish() }
            test.setInTouchMode(true)
        }
    }
}
