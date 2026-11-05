package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.OfflineMediaDownload
import com.nikhil.niktv.model.PortalProfile
import org.junit.Assert.*
import org.junit.Test

/** Local metadata only: no download, portal authentication, or account writes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineProfileDownloadsDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private fun walk(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (index in 0 until node.childCount) walk(node.getChild(index), test)?.let { return it }
        return null
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return i.uiAutomation.rootInActiveWindow?.takeIf { it.packageName == i.targetContext.packageName }
    }
    private fun find(text: String) = walk(root()) { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun reveal(text: String) {
        val end = SystemClock.uptimeMillis() + 6_000
        while (find(text) == null && SystemClock.uptimeMillis() < end) {
            walk(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        assertNotNull("Current person's local download remains visible: $text", find(text))
    }

    @Test fun personDownloadsIncludeAllPortalSourcesWithoutAnAuthenticatedSession() {
        val context = i.targetContext
        check(context.packageName.endsWith(".regression"))
        val stalePortal = PortalProfile("Unused local portal", "https://unused.invalid", "")
        val first = OfflineMediaDownload(profileKey = stalePortal.cacheKey(), catalogType = CatalogType.MOVIES,
            media = MediaItem("offline-fixture-a", "Downloaded from first portal", null, null))
        val second = OfflineMediaDownload(profileKey = "unselected-local-portal", catalogType = CatalogType.MOVIES,
            media = MediaItem("offline-fixture-b", "Downloaded from second portal", null, null))
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            i.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalViewingOwner provides UserScope("offline-fixture-account", "offline-fixture-person")) {
                    OfflineDownloadsScreen(NikTvState(savedProfile = stalePortal, session = null,
                        offlineDownloads = listOf(first, second), restoring = false),
                        play = { fail("Rendering metadata must not start playback") },
                        playRecording = { fail("Rendering metadata must not play recordings") },
                        remove = { _, _ -> fail("Rendering must not remove local media") }, close = {})
                }
            } } }
            reveal("Offline downloads")
            reveal(first.media.title)
            reveal(second.media.title)
            assertNull("No portal authentication screen is opened", find("Portal URL"))
            assertNull(find("Stalker"))
            assertNull(find("Xtream"))
            assertNull(find("No offline media"))
        } finally { i.runOnMainSync { host.finish() } }
    }
}
