package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.PortalNavigationRequests
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** The real account gate is mounted with fixture-only account and cached portal sessions. */
class PortalGateNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private var diagnostics: () -> String = { "" }
    private fun await(label: String, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) { if (ready()) return; Thread.sleep(50) }
        fun labels(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
            listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
        fail(label + " | " + diagnostics() + " | screen=" + labels(ins.uiAutomation.rootInActiveWindow).take(30))
    }
    @Test fun loadedViewerSwitchesBehindChooserAndColdRequestOverridesSports() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val manager = AccountSyncManager.get(context)
        val account = "9" + SystemClock.uptimeMillis()
        val family = runBlocking {
            users.selectAccount(account)
            users.createProfile("Portal gate fixture").also {
                users.setProfileEnabled(it.id, true)
                users.selectProfile(it.id)
            }
        }
        val owner = UserScope(account, family.id)
        val portals = listOf(
            PortalProfile("Stalker gate", "http://127.0.0.1:1", "00:00:00:00:00:31"),
            PortalProfile("Second gate", "http://127.0.0.1:1", "00:00:00:00:00:32"))
        runBlocking {
            val store = ProfileStore(context).scoped(owner)
            portals.forEach { store.save(PortalSession(it, "fixture-session", "http://127.0.0.1:1/portal.php", "", "", "")) }
            users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.PORTAL, portals[0].cacheKey()))
        }
        @Suppress("UNCHECKED_CAST")
        val accountState = AccountSyncManager::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<AccountSyncState>
        val previousAccount = accountState.value
        accountState.value = AccountSyncState(account, "fixture", initialized = true)
        CatalogPreferences.setPreferLocal(context, false)
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        lateinit var vm: NikTvViewModel
        val epoch = mutableIntStateOf(0)
        try {
            ins.runOnMainSync {
                vm = NikTvViewModel(context.applicationContext as Application)
                vm.viewModelScope.coroutineContext.cancelChildren()
                diagnostics = { "scope=${users.selectedScope.value} account=${accountState.value} " +
                    "portal=${vm.state.value.savedProfile?.name} session=${vm.state.value.session?.profile?.name} " +
                    "loading=${vm.state.value.loading} error=${vm.state.value.error} " +
                    "remembered=${runBlocking { users.rememberedProfileId(account) }} enabled=${users.enabledProfileIds(account)} pending=${PortalNavigationRequests.pending.value}" }
                host.setContent { MaterialTheme(colorScheme = NikColors) {
                    key(epoch.intValue) { AccountProfileGate(vm) {
                        val state by vm.state.collectAsState()
                        Text("Fixture portal: " + state.session?.profile?.name)
                    } }
                } }
            }
            await("Initial viewer opens cached portal") { find("Fixture portal: Stalker gate") != null }
            PortalNavigationRequests.pending.value = PortalNavigationRequests.PROFILE_CHOOSER
            await("Explicit profile action opens chooser") { find("Who's watching?") != null }
            PortalNavigationRequests.pending.value = portals[1].cacheKey()
            await("Hidden content cannot block direct portal switch") { find("Fixture portal: Second gate") != null && PortalNavigationRequests.pending.value == null }
            assertEquals(owner, users.selectedScope.value)
            await("Portal activation settles") { !vm.state.value.loading }
            val reused = vm.state.value.session
            PortalNavigationRequests.pending.value = portals[1].cacheKey()
            await("Already-loaded portal request is consumed") { PortalNavigationRequests.pending.value == null }
            assertSame("An already loaded portal is reused", reused, vm.state.value.session)
            runBlocking { users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.SPORTS)) }
            ins.runOnMainSync {
                PortalNavigationRequests.pending.value = portals[0].cacheKey()
                epoch.intValue++
            }
            await("Cold explicit portal wins over remembered Sports") { find("Fixture portal: Stalker gate") != null && PortalNavigationRequests.pending.value == null }
            assertEquals(owner, users.selectedScope.value)
            runBlocking { ProfilePortalAccess.setAllowed(context, owner, ProfilePortalAccess.key(portals[1]), false) }
            PortalNavigationRequests.pending.value = portals[1].cacheKey()
            await("Disabled portal request is discarded") { PortalNavigationRequests.pending.value == null && find("Fixture portal: Stalker gate") != null }
            assertNotNull("Disabled requests keep the current content", find("Fixture portal: Stalker gate"))
            assertEquals(portals[0].cacheKey(), vm.state.value.session?.profile?.cacheKey())
            PortalNavigationRequests.pending.value = "missing-portal"
            await("Stale unknown request is discarded") { PortalNavigationRequests.pending.value == null && find("Fixture portal: Stalker gate") != null }
            assertNotNull(find("Fixture portal: Stalker gate"))
            runBlocking { ProfilePortalAccess.setAllowed(context, owner, ProfilePortalAccess.key(portals[1]), true) }
            ins.runOnMainSync {
                PortalNavigationRequests.pending.value = "stale-request"
                PortalNavigationRequests.pending.value = portals[1].cacheKey()
            }
            await("Latest portal request wins without reopening profiles") {
                find("Fixture portal: Second gate") != null && PortalNavigationRequests.pending.value == null
            }
            assertEquals(owner, users.selectedScope.value)
        } finally {
            PortalNavigationRequests.pending.value = null
            ins.runOnMainSync { runCatching { vm.viewModelScope.cancel() }; host.finish() }
            CatalogPreferences.setPreferLocal(context, true)
            users.disconnect()
            accountState.value = previousAccount
        }
    }
}
