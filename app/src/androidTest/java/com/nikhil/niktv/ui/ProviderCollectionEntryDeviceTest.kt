package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Local collection fixture: no provider, account, player or personal app state. */
class ProviderCollectionEntryDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(20) }
        fail(message)
    }
    private fun key(host: PlaybackFixtureHostActivity, code: Int) = i.runOnMainSync {
        host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    @Test fun delayedCacheAndPagingEnterFirstTileAndDetailsReturnRestoresSelection() {
        check(i.targetContext.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(i.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val profile = PortalProfile("Fixture", "https://example.invalid", "fixture")
        val category = Category("a", "Fixture Movies", CatalogType.MOVIES)
        val titles = (0 until 80).map { MediaItem("movie-$it", "Fixture movie $it", null, "fixture://$it") }
        val empty = ProviderDiscoveryState(identity = "loading", profile = profile.cacheKey(), categories = mapOf(CatalogType.MOVIES to listOf(category)), loading = setOf(CatalogType.MOVIES))
        fun loaded(count: Int, identity: String = "ready") = empty.copy(identity = identity, ready = true,
            loading = emptySet(), pages = mapOf(CatalogType.MOVIES to mapOf(category.id to ProviderDiscoveryPage(titles.take(count), 1, false))),
            items = mapOf(CatalogType.MOVIES to titles.take(count)))
        val data = mutableStateOf(empty)
        val opened = mutableStateOf(false)
        val details = mutableStateOf(false)
        val focus = Collections.synchronizedList(mutableListOf<String>())
        val selected = Collections.synchronizedList(mutableListOf<String>())
        var footer: Button? = null
        try {
            await("Fixture window owns input") { host.hasWindowFocus() }
            i.setInTouchMode(false); i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            i.runOnMainSync { host.window.decorView.isSoundEffectsEnabled = false; host.setContent { MaterialTheme {
                val saved = rememberSaveableStateHolder()
                if (!opened.value) AndroidView(factory = { context -> FrameLayout(context).apply {
                    addView(Button(context).apply { text = "View all"; footer = this }, FrameLayout.LayoutParams(180, 80, Gravity.BOTTOM or Gravity.END))
                } }, modifier = Modifier.fillMaxSize())
                else if (details.value) Text("Local details fixture")
                else saved.SaveableStateProvider("same-collection-visit") {
                    CompositionLocalProvider(LocalOpenProviderDiscovery provides { _, item, _ -> selected.add(item.id); details.value = true }) {
                        ProviderDiscoveryCollection(CatalogType.MOVIES, data.value, NikTvState(savedProfile = profile), {}, {}, {},
                            focusObserved = { focus.add(it) })
                    }
                }
            } } }
            await("Old footer attached") { footer != null }
            i.runOnMainSync { footer!!.requestFocus(); opened.value = true }
            i.waitForIdleSync(); Thread.sleep(150)
            assertTrue("No fallback header/item focus while the source is empty: $focus", focus.isEmpty())
            i.runOnMainSync { data.value = loaded(14) }
            await("Fresh collection focuses its first tile: $focus") { focus.lastOrNull() == "movie-0" }
            i.runOnMainSync { data.value = loaded(80) }
            i.waitForIdleSync(); Thread.sleep(150)
            assertEquals("Paging must not choose an arbitrary new visible tile", listOf("movie-0"), focus.distinct())
            val configuration = host.resources.configuration
            val columns = discoveryCollectionColumns(configuration.screenWidthDp.toFloat(), discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat()))
            var target = 0
            repeat(4) { target += columns; key(host, KeyEvent.KEYCODE_DPAD_DOWN); val id = "movie-$target"; await("Next row focuses $id") { focus.lastOrNull() == id } }
            repeat(2) { target++; key(host, KeyEvent.KEYCODE_DPAD_RIGHT); val id = "movie-$target"; await("Right focuses $id") { focus.lastOrNull() == id } }
            key(host, KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selected item opens local details") { details.value }
            assertEquals("movie-$target", selected.last())
            i.waitForIdleSync()
            i.runOnMainSync { focus.clear(); data.value = empty.copy(identity = "loading-after-return"); details.value = false }
            i.waitForIdleSync(); Thread.sleep(150)
            assertTrue("Cache hydration must not reset return focus to a header: $focus", focus.isEmpty())
            i.runOnMainSync { data.value = loaded(80, "ready-after-return") }
            await("Details return restores the selected tile: $focus") { focus.lastOrNull() == "movie-$target" }
            assertEquals("No first-tile flash on details return", listOf("movie-$target"), focus.distinct())
        } finally { i.runOnMainSync { host.finish() } }
    }
}
