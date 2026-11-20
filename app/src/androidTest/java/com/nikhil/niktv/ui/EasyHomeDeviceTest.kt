package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses the layout setting and authenticated production Home with a synthetic IPTV session. */
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

    private fun tap(label: String) {
        val target = Rect().also { requireNotNull(control(label)).getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            try { ins.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    @Test fun easyLayoutIsSelectableAndHomeShortcutsWorkByRemoteAndTouch() {
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
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync {
                val appVm = NikTvViewModel(context.applicationContext as Application)
                vm = appVm
                appVm.viewModelScope.coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                val app = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(appVm) as MutableStateFlow<NikTvState>
                val portal = PortalProfile("Fixture IPTV", "https://fixture.invalid", "00:00:00:00:00:01")
                val session = PortalSession(portal, "fixture", portal.portalUrl, "", "", "")
                val category = Category("local-news", "Fixture news", CatalogType.LIVE_TV)
                val cache = BrowseCatalogCache(portal.cacheKey(), CatalogType.LIVE_TV, System.currentTimeMillis(),
                    listOf(category), mapOf(category.id to emptyList()))
                app.value = NikTvState(profiles = listOf(portal), savedProfile = portal, session = session,
                    homeOpen = true, discoverySurface = DashboardSurface.HOME, restoring = false,
                    rawCategoriesByType = mapOf(CatalogType.LIVE_TV to listOf(category)),
                    browseCachesByType = mapOf(CatalogType.LIVE_TV to cache))
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
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns directly to the Live TV shortcut on Easy Home") {
                appVm.state.value.discoverySurface == DashboardSurface.HOME && control("Live TV")?.isFocused == true &&
                    control("Profiles") != null
            }
            ins.setInTouchMode(true)
            tap("Search")
            await("Touch opens IPTV Search") { appVm.state.value.searchOpen }
        } finally {
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
