package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Full Series shell plus the real phone bottom bar; all catalogue data is local/fake. */
class ProviderSeriesReturnDeviceTest {
    @Test fun touchDetailReturnRestoresTheSeriesCardBeforeBottomNavigationCanTakeAnArrow() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(usesDiscoveryPhoneLayout(context.resources.configuration.smallestScreenWidthDp,
            context.isTvLikeDevice(context.resources.configuration)))
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val profile = PortalProfile("Series return fixture", "https://series-return.invalid", "")
        val category = Category("local-series-return", "Local series", CatalogType.SERIES)
        val items = (1..3).map { MediaItem("return-series-$it", "Local series $it", null, "fixture://$it") }
        val shown = mutableStateOf(true)
        var navigationCount = 0
        lateinit var vm: DiscoveryViewModel
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = emptyList<DiscoveryCard>()
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card)
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        fun await(message: String, test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < end) { if (test()) return; Thread.sleep(25) }
            fail(message)
        }
        fun card(): View? {
            fun find(view: View): View? {
                if ((view.tag as? String)?.contains(items[0].id) == true) return view
                if (view is ViewGroup) for (n in 0 until view.childCount) find(view.getChildAt(n))?.let { return it }
                return null
            }
            var result: View? = null
            i.runOnMainSync { result = find(host.window.decorView) }
            return result
        }
        fun focusTag(): String? {
            var result: String? = null
            i.runOnMainSync { result = host.currentFocus?.tag as? String }
            return result
        }
        i.runOnMainSync {
            vm = DiscoveryViewModel(context.applicationContext as Application, source)
            host.setContent { MaterialTheme {
                val saved = rememberSaveableStateHolder()
                CompositionLocalProvider(LocalDiscoveryExperiment provides true,
                    LocalProviderDiscoveryPageLoader provides { _, _, page, _ -> PortalCatalogPage(items, page, false) },
                    LocalOpenProviderDiscovery provides { _, _, _ -> shown.value = false }) {
                    Box(Modifier.fillMaxSize()) {
                        if (shown.value) {
                            saved.SaveableStateProvider("series") {
                                DiscoveryPreviewScreen(NikTvState(savedProfile = profile,
                                    session = PortalSession(profile, "", "https://series-return.invalid", "", "", ""),
                                    discoverySurface = DashboardSurface.SERIES, discoveryNavigationId = 7,
                                    rawCategoriesByType = mapOf(CatalogType.SERIES to listOf(category)),
                                    categoryFilters = mapOf("${profile.cacheKey()}|SERIES" to listOf(category.id))),
                                    DashboardSurface.SERIES, {}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> },
                                    {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, vm = vm, experiment = true)
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                DiscoveryPhoneBottomNavigation(DiscoveryPhoneDestination.SERIES, { navigationCount++ })
                            }
                        } else Text("Local episode details")
                    }
                }
            } }
        }
        try {
            await("Cached fake Series cards attach") { card() != null }
            val bounds = Rect()
            val target = card()!!
            i.runOnMainSync { assertTrue(target.getGlobalVisibleRect(bounds)) }
            i.setInTouchMode(true)
            val down = SystemClock.uptimeMillis()
            i.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
            i.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
            await("Touch opens local details") { !shown.value }
            i.runOnMainSync { shown.value = true }
            await("Return restores the actual Series card, not only its host") { focusTag()?.contains(items[0].id) == true }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Hardware remains in the media row") { focusTag()?.contains("return-series-") == true }
            assertEquals("Bottom navigation must not activate", 0, navigationCount)
        } finally {
            i.runOnMainSync { vm.viewModelScope.cancel(); host.finish() }
        }
    }
}
