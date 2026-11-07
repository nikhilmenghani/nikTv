package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

/** Provider actor/related destinations use the same retained VM and route host as production. */
class ProviderRelatedNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun actorCreditsOpenMovieAndSeriesOnFirstTouchSelectionAndOneBackClosesEveryVisit() = exercise(false)
    @Test fun actorCreditsOpenOnFirstDpadSelectionAndBackRestoresTheSelectedPoster() = exercise(true)

    private fun exercise(remote: Boolean) {
        check(context.packageName.endsWith(".regression"))
        val actor = DiscoveryPerson("Navigation Actor", "Fixture role", id = 810090)
        val movie = DiscoveryCard(810091, false, "Credit movie destination", date = "2020-01-01")
        val series = DiscoveryCard(810092, true, "Credit series destination", date = "2020-01-01")
        val profile = PortalProfile("Related navigation fixture", "https://navigation.invalid", "")
        val selected = mutableStateOf<DiscoveryCard?>(null)
        val models = mutableMapOf<String, DiscoveryViewModel>()
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = emptyList<DiscoveryCard>()
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                DiscoveryDetails(card, seasons = if (card.series) listOf(1, 2) else emptyList(),
                    seasonEpisodeCounts = if (card.series) mapOf(1 to 4, 2 to 4) else emptyMap())
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = (1..4).map {
                DiscoveryEpisode(season, it, "Credit episode $it", date = "2020-01-01")
            }
        }
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.setInTouchMode(!remote)
            instrumentation.runOnMainSync {
                listOf(movie, series).forEach { card -> models[card.key] = DiscoveryViewModel(context.applicationContext as Application, source) }
                host.setContent { MaterialTheme {
                val actorPages = rememberSaveableStateHolder()
                val card = selected.value
                if (card == null) actorPages.SaveableStateProvider("credits") {
                    DiscoveryActorDialog(actor, load = { _, _ -> listOf(movie, series) }, close = {},
                        loadInfo = { DiscoveryActorInfo(actor.name, biography = "Navigation fixture biography") },
                        open = { selected.value = it })
                } else {
                    val vm = models.getValue(card.key)
                    Dialog(onDismissRequest = { selected.value = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        ProviderRelatedDetailRoute(profile.cacheKey(), card, vm, close = { selected.value = null }) {
                            DiscoveryPreviewScreen(NikTvState(savedProfile = profile, restoring = false, homeOpen = false),
                                if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES,
                                selectSurface = { selected.value = null }, exit = { selected.value = null },
                                navigate = { selected.value = null }, playCard = {}, playEpisode = { _, _, _ -> },
                                openRecent = {}, openWatched = { _, _ -> }, toggleFavorite = {}, openCategory = {},
                                openSection = {}, removeRecent = {}, markWatched = { _, _ -> }, dismissEpisode = { _, _ -> }, vm = vm, honorLandingNavigation = false)
                        }
                    }
                }
            } } }
            await("Actor credits appear") { label("Featuring ${actor.name}") != null }
            NavigationAuditEvidence.capture("related-credits-${if (remote) "dpad" else "touch"}")
            if (!remote) {
                click("About ${actor.name}")
                await("Actor information opens") { label("Navigation fixture biography") != null }
                NavigationAuditEvidence.capture("related-actor-information")
                click("Close")
                await("Closing information restores credits") { label("Featuring ${actor.name}") != null }
            } else await("Credits initially focus the first poster") { focused(movie.title) }
            for (card in listOf(series, movie, series, movie)) {
                if (remote) {
                    if (!focused(card.title)) {
                        instrumentation.sendKeyDownUpSync(if (card.series) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                        await("D-pad reaches ${card.title}") { focused(card.title) }
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                } else click(card.title)
                await("First selection must show ${card.title}, not reopen actor credits") {
                    models[card.key]?.state?.value?.detail?.key == card.key &&
                        label("Featuring ${actor.name}") == null && label("Back") != null
                }
                // Allow a second composition/Flow publication; this was the point
                // where the old initialized + stale-null effect dismissed the route.
                Thread.sleep(200)
                assertEquals(card.key, selected.value?.key)
                NavigationAuditEvidence.capture("related-first-${if (card.series) "series" else "movie"}-${if (remote) "dpad" else "touch"}")
                if (remote) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) else click("Back")
                await("One Back restores actor credits for ${card.title}") {
                    selected.value == null && (remote || models[card.key]?.state?.value?.detail == null) &&
                        label("Featuring ${actor.name}") != null
                }
                if (remote) await("Back restores the selected ${card.title} poster") { focused(card.title) }
                NavigationAuditEvidence.capture("related-back-${if (card.series) "series" else "movie"}-${if (remote) "dpad" else "touch"}")
            }
        } finally {
            instrumentation.runOnMainSync { models.values.forEach { it.pause() }; host.finish() }
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
        for (index in 0 until node.childCount) find(node.getChild(index), value)?.let { return it }
        return null
    }
    private fun label(value: String) = find(root(), value)
    private fun focused(value: String): Boolean {
        var node = label(value)
        while (node != null && !node.isClickable) node = node.parent
        return node != null && node == root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun click(value: String) {
        await("Control is present: $value") { label(value) != null }
        var node = label(value)
        while (node != null) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        fail("Cannot activate $value")
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            Thread.sleep(60)
        }
        fail(message)
    }
}
