package com.nikhil.niktv.ui

import android.Manifest
import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.AccountTokenVault
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ViewerDestination
import com.nikhil.niktv.data.ViewerDestinationKind
import com.nikhil.niktv.data.automaticAccountSyncEnabled
import com.nikhil.niktv.discovery.DiscoveryCache
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.GenreMarket
import com.nikhil.niktv.discovery.WatchProvider
import com.nikhil.niktv.discovery.WatchProviderPage
import com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProfileNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun createsOpensEditsAndDeletesALocalProfile() {
        val account = "90000008113"
        val profileName = "Navigation QA"
        val incompleteName = "Incomplete QA"
        withLocalViewer(account, "Navigation portal", fixtureProfiles = setOf(profileName, incompleteName), setup = { _, _ ->
            createRememberedViewer(account, incompleteName)
        }) { provider, portal, _ ->
                val portalName = portal.name
                val users = UserSyncStore.get(context)
            await("People chooser on a fresh account") { find("Who's watching?") != null }
            click("Watch as $incompleteName")
            await("An existing viewer without portals enters the profile editor directly") {
                find("1 of 2 · Profile details") != null && focused("Display name") &&
                    nodes().any { it.isFocused && it.text?.toString() == incompleteName }
            }
            assertNull(find("Profiles & portals"))
            click("Cancel")
            await("Cancel returns the incomplete viewer to the people chooser") { find("Who's watching?") != null }

            click("Add user profile")
            await("The display name is the D-pad entry target") { focused("Display name") }
            enterFocusedText(profileName)
            key(KeyEvent.KEYCODE_BACK)
            await("Back leaves the name editor on the profile details page") {
                find("1 of 2 · Profile details") != null && focused("Display name") &&
                    keyboardHidden()
            }
            click("Next")
            await("Empty portal setup initially focuses Add portal") { focused("Add portal") }
            assertFalse("Creation needs a selected portal", control("Create & open")?.isEnabled == true)
            NavigationAuditEvidence.capture("profile-navigation-new-no-portal")

            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Portal editor enters at the portal type selector") { focused("Stalker / MAG") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad can reach Xtream") { focused("Xtream") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            editPortalField("Portal name", portalName)
            editPortalField("Portal URL", portal.portalUrl)
            editPortalField("Username", portal.username)
            editPortalField("Password", portal.password)
            click("Use portal")
            await("The new portal is selected in the profile editor") {
                control(portalName)?.isChecked == true
            }
            await("Returning from portal editor restores Add portal focus") { focused("Add portal") }
            NavigationAuditEvidence.capture("profile-navigation-portal-selected")

            click("Create & open")
            await("New profile opens one password prompt") { find("Set password for $profileName") != null }
            await("New password has initial D-pad focus") { focused("New password (4 digits)") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad moves to password confirmation") { focused("Confirm password") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad dismisses the keyboard and reaches Cancel") { focused("Cancel") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches the enabled Set password action with the keyboard dismissed") {
                focused("Set password") && control("Set password")?.isEnabled == true && keyboardHidden()
            }
            instrumentation.waitForIdleSync()
            NavigationAuditEvidence.capture("profile-navigation-password-focused")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitDashboard(profileName)
            NavigationAuditEvidence.capture("profile-navigation-dashboard")

            val created = runBlocking { users.profiles.first().single { it.name == profileName } }
            val owner = UserScope(account, created.id)
            assertTrue(ProfilePin.verifies(created.pinVerifier, "0000"))
            assertEquals(owner, users.selectedScope.value)
            assertTrue(runBlocking { users.hasPortalAssociation(owner, portal) })
            assertTrue("The new profile stays in the local pending queue",
                runBlocking { users.pending(account).any { it.profileId == created.id } })
            assertTrue("The app authenticated with the loopback portal", provider.authentications.get() > 0)

            click("Switch profile: $profileName")
            await("The account can still choose an existing viewer") { find("Who's watching?") != null }
            click("Watch as $profileName")
            await("Choosing the existing viewer resumes the dashboard") {
                find("Switch profile: $profileName") != null && find("Live TV") != null
            }
            click("Options")
            click("Settings")
            click("Profiles & portals")
            click(profileName)
            await("Profile settings opens with Manage profile visible") { find("Manage profile") != null }
            click("Manage profile")
            await("Settings opens the same profile editor") { focused("Display name") }
            click("Blue")
            click("Next")
            await("The existing profile remembers its portal selection") { control(portalName)?.isChecked == true }
            click("Save & open")
            awaitDashboard(profileName)
            assertEquals(owner, users.selectedScope.value)
            assertEquals(0xFF1565C0L, runBlocking { users.profiles.first().single { it.id == created.id } }.color)
            NavigationAuditEvidence.capture("profile-navigation-dashboard-after-edit")

            click("Switch profile: $profileName")
            await("The people chooser opens on request") { find("Who's watching?") != null }
            click("Manage profiles")
            click(profileName)
            click("Delete profile")
            click("Delete on this device", "Delete across devices")
            await("Deletion requests the profile password") { find("Unlock $profileName") != null }
            await("Deletion password has initial D-pad focus") { focused("PIN") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad leaves the deletion password field") { focused("Cancel") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches the enabled Unlock action") { focused("Unlock") && control("Unlock")?.isEnabled == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The deleted profile disappears from the chooser") {
                find("Who's watching?") != null && find("Watch as $profileName") == null &&
                    runBlocking { users.profiles.first().none { it.id == created.id } }
            }
            assertNull(users.selectedScope.value)
            NavigationAuditEvidence.capture("profile-navigation-profile-deleted")
        }
    }

    @Test
    fun productionTabsKeepFocusAcrossRapidSwitchesBackAndProviderOutage() {
        val account = "90000008114"
        val viewerName = "Tab navigation QA"
        val liveCategory = "Fixture Live Sports"
        val liveCollection = "All channels"
        withLocalViewer(account, "Tab navigation portal", setOf(viewerName), liveCategory, setup = { users, portal ->
            val viewer = createRememberedViewer(account, viewerName)
            users.selectProfile(viewer.profileId)
            ProfileStore(context).scoped(viewer).addProfile(portal)
            users.rememberDestination(viewer, ViewerDestination(ViewerDestinationKind.PORTAL, portal.cacheKey()))
            users.clearSelection()
        }) { provider, _, activity ->
            awaitDashboard(viewerName)
            // Fire OS can retain the previous virtual accessibility tree even after drawing the new tab.
            val readCompose = Build.VERSION.SDK_INT < 33
            fun ownsTab(title: String, selected: Boolean = false, focused: Boolean = false): Boolean =
                if (readCompose) readDashboardSemantics(activity) { nodes -> nodes.any { node ->
                    node.isDashboardTab(title) && (!selected || node.config.getOrNull(SemanticsProperties.Selected) == true) &&
                        (!focused || node.config.getOrNull(SemanticsProperties.Focused) == true)
                } } else tab(title)?.let { (!selected || it.isSelected) && (!focused || it.isFocused) } == true
            await("Home is selected on startup") { ownsTab("Home", selected = true) }
            val measure = InstrumentationRegistry.getArguments().getString("tabFrameMetrics") == "true"
            val saveProfile = InstrumentationRegistry.getArguments().getString("tabSaveProfile") == "true"
            val traceFocus = InstrumentationRegistry.getArguments().getString("tabFocusDiagnostics") == "true"
            var frames: TabFrameCapture? = null
            if (measure) instrumentation.runOnMainSync { frames = TabFrameCapture(activity.window) }
            val focusLabels = ConcurrentLinkedQueue<String>()
            val oldEventTypes = automation.serviceInfo.eventTypes
            try {
                automation.serviceInfo = automation.serviceInfo.apply { eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_FOCUSED }
                if (!measure) automation.setOnAccessibilityEventListener { event ->
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.packageName?.toString() == context.packageName) {
                        val label = event.contentDescription ?: event.source?.contentDescription ?: event.text.firstOrNull()
                        label?.toString()?.let(focusLabels::add)
                    }
                }
                fun activate(target: String, useful: String? = null, alreadyFocused: Boolean = false) {
                    if (!alreadyFocused && !ownsTab(target, focused = true)) {
                        await("$target accepts D-pad focus") {
                            if (readCompose) readDashboardSemantics(activity) { nodes ->
                                nodes.firstOrNull { it.isDashboardTab(target) }?.config
                                    ?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true
                            } else tab(target)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
                        }
                    }
                    await("$target owns focus before OK") { ownsTab(target, focused = true) }
                    focusLabels.clear()
                    frames?.begin(target)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    var focusState = ""
                    fun checkLiveStatus(snapshot: List<AccessibilityNodeInfo>) {
                        if (useful != null && tab(target, snapshot)?.isSelected == true) {
                            assertFalse("Live TV must keep cached or loading content during setup", snapshot.any {
                                it.isVisibleToUser && it.text?.startsWith("No matching categories") == true
                            })
                        }
                    }
                    await("$target becomes selected and stays focused") {
                        if (!readCompose && useful != null) checkLiveStatus(nodes())
                        val ready = ownsTab(target, selected = true, focused = true)
                        if (!ready && traceFocus && readCompose) {
                            val now = readDashboardSemantics(activity) { nodes ->
                                val native = activity.window.decorView.findFocus()
                                val labels = nodes.filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
                                    .flatMap { it.dashboardLabels() }.toList()
                                "native=${native?.javaClass?.name}/${native?.tag} focused=$labels"
                            }
                            if (now != focusState) {
                                android.util.Log.i("NikTvFireNavigation", "$target after OK: $now")
                                focusState = now
                            }
                        }
                        ready
                    }
                    frames?.selected()
                    useful?.let { label -> await("$target shows category $label") {
                        if (readCompose) {
                            val labels = readDashboardSemantics(activity) { nodes -> nodes.flatMap { it.dashboardLabels() }.toList() }
                            assertFalse("Live TV must keep cached or loading content during setup", labels.any { it.startsWith("No matching categories") })
                            label in labels
                        } else {
                            val snapshot = nodes()
                            checkLiveStatus(snapshot)
                            snapshot.any { it.isVisibleToUser && it.matches(label) }
                        }
                    } }
                    frames?.finish(hasUsefulContent = useful != null)
                    assertTrue("OK on $target must not focus another tab: $focusLabels", focusLabels.none {
                        it.removeSuffix(" 2") in tabTitles && it.removeSuffix(" 2") != target
                    })
                }

                activate("Home")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                activate("Movies", alreadyFocused = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                activate("Series", alreadyFocused = true)
                key(KeyEvent.KEYCODE_BACK)
                await("Back from Series returns to Home") { ownsTab("Home", selected = true) }
                activate("Live TV", liveCollection)
                assertTrue("The isolated provider supplied the fresh catalogue", provider.liveCategoryRequests.get() > 0)
                provider.unavailable.set(true)
                if (!measure) key(KeyEvent.KEYCODE_DPAD_RIGHT)
                activate("My List", alreadyFocused = !measure)
                if (!measure) key(KeyEvent.KEYCODE_DPAD_LEFT)
                activate("Live TV", liveCollection, alreadyFocused = !measure)
                if (!measure) {
                    NavigationAuditEvidence.capture("tab-navigation-live-offline")
                    repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                    activate("Home", alreadyFocused = true)
                }
                if (measure || saveProfile) {
                    repeat(3) {
                        activate("Home")
                        activate("Movies")
                        activate("Series")
                        activate("Live TV", liveCollection)
                        activate("My List")
                    }
                }
                if (saveProfile) {
                    // Save while this regression process still owns the exercised code.
                    SystemClock.sleep(5_000)
                    android.os.Process.sendSignal(android.os.Process.myPid(), android.system.OsConstants.SIGUSR1)
                    SystemClock.sleep(1_000)
                }
                if (measure) {
                    val report = requireNotNull(frames).report()
                    File(context.externalCacheDir, "navigation-audit/tab-navigation-metrics.json").apply {
                        parentFile?.mkdirs()
                        writeText(report.toString(2))
                    }
                    instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nTAB_NAVIGATION_RESULT=$report\n") })
                }
            } finally {
                automation.setOnAccessibilityEventListener(null)
                automation.serviceInfo = automation.serviceInfo.apply { eventTypes = oldEventTypes }
                instrumentation.runOnMainSync { frames?.close() }
            }
        }
    }

    private val tabTitles = setOf("Home", "Movies", "Series", "Live TV", "My List")

    @Test
    fun homeGenresKeepTheirMarketWhenOpenedWithTheRemote() {
        val account = "90000008116"
        val viewerName = "Home genres QA"
        val comedy = WatchProvider(35, "Comedy")
        fun title(market: GenreMarket) = "${market.label} comedy fixture"
        val cache = DiscoveryCache(File(context.cacheDir, "discovery_preview"))
        runBlocking {
            cache.write("genre-catalog-v1:false", Json.encodeToString(listOf(comedy)))
            for (market in GenreMarket.entries) {
                val page = WatchProviderPage(listOf(DiscoveryCard(8100 + market.ordinal, false, title(market),
                    date = "2020-01-01", originalLanguage = market.language)), 1, 1)
                cache.write("genre-titles-v1:false:35:1:${market.name}", Json.encodeToString(page))
                cache.write("genre-poster-v1:false:35:${market.name}", "null")
            }
        }
        withLocalViewer(account, "Home genres portal", setOf(viewerName), setup = { users, portal ->
            val owner = createRememberedViewer(account, viewerName)
            users.selectProfile(owner.profileId)
            ProfileStore(context).scoped(owner).addProfile(portal)
            users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.PORTAL, portal.cacheKey()))
            users.clearSelection()
        }) { _, _, main ->
            awaitDashboard(viewerName)
            fun nativeFocus(): String? {
                var tag: String? = null
                instrumentation.runOnMainSync { tag = main.currentFocus?.tag as? String }
                return tag
            }
            fun isComedy(market: GenreMarket) = nativeFocus()?.let {
                it.startsWith("popular-genres:${market.name}:") && it.endsWith(":35")
            } == true
            fun verifyComedy(market: GenreMarket) {
                await("Comedy opens with the ${market.label} filter") {
                    find("Movies · ${market.label}") != null && find(title(market)) != null
                }
                assertFalse(GenreMarket.entries.filter { it != market }.any { find(title(it)) != null })
                NavigationAuditEvidence.capture("home-genres-${market.name.lowercase()}")
                key(KeyEvent.KEYCODE_BACK)
            }
            fun verifyHollywoodBrowser() {
                await("View All keeps the Hollywood filter") {
                    find("Hollywood Genres") != null && find(title(GenreMarket.HOLLYWOOD)) != null
                }
                assertTrue(find(title(GenreMarket.BOLLYWOOD)) == null)
                key(KeyEvent.KEYCODE_BACK)
            }
            val config = main.resources.configuration
            if (usesDiscoveryPhoneLayout(config, main.isTvLikeDevice(config))) {
                val screen = main.resources.displayMetrics
                fun input(command: String) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input $command"))
                        .use { it.readBytes() }
                }
                fun comedyBelowHeading(market: GenreMarket): Rect? {
                    val heading = find(market.heading)?.let { Rect().also(it::getBoundsInScreen) } ?: return null
                    return nodes().filter { it.isVisibleToUser && it.isClickable && it.matches(comedy.name) }
                        .map { Rect().also(it::getBoundsInScreen) }
                        .filter { it.top >= heading.bottom && it.centerY() < screen.heightPixels * .88f }
                        .minByOrNull { it.top }
                }
                for (market in GenreMarket.entries) {
                    for (attempt in 1..8) {
                        if (comedyBelowHeading(market) != null) break
                        val x = screen.widthPixels / 2
                        input("swipe $x ${screen.heightPixels * 7 / 10} $x ${screen.heightPixels * 4 / 10} 450")
                        instrumentation.waitForIdleSync()
                    }
                    await("Touch can reach the ${market.label} Comedy tile") { comedyBelowHeading(market) != null }
                    val tile = requireNotNull(comedyBelowHeading(market))
                    input("tap ${tile.centerX()} ${tile.centerY()}")
                    verifyComedy(market)
                    await("Home returns after closing ${market.label} Comedy") { main.hasWindowFocus() }
                }
                click("View all Hollywood Genres")
                verifyHollywoodBrowser()
                return@withLocalViewer
            }

            for (attempt in 1..20) {
                if (isComedy(GenreMarket.BOLLYWOOD)) break
                val previous = nativeFocus()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                // Home's hero is composed; the first move may stay within that header/hero.
                if (previous != null) await("Remote moves down from $previous") { nativeFocus() != previous }
            }
            await("Bollywood Comedy is focused on Home") { isComedy(GenreMarket.BOLLYWOOD) }
            assertTrue(find("Bollywood Genres") != null)

            for (market in GenreMarket.entries) {
                if (market == GenreMarket.HOLLYWOOD) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Hollywood Comedy is focused below Bollywood") { isComedy(market) }
                    assertTrue(find("Hollywood Genres") != null)
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                verifyComedy(market)
                await("Close returns focus to the ${market.label} Comedy tile") { main.hasWindowFocus() && isComedy(market) }
            }

            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Hollywood View All receives focus") { nativeFocus() == "popular-genres:HOLLYWOOD:all" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            verifyHollywoodBrowser()
            await("Close returns focus to Hollywood View All") {
                main.hasWindowFocus() && nativeFocus() == "popular-genres:HOLLYWOOD:all"
            }
        }
    }

    @Test
    fun sportsAndPlayfyReturnFocusToTheChosenIptvPortal() {
        val account = "90000008115"
        val viewerName = "Portal handoff QA"
        val firstPortal = "First IPTV fixture"
        val secondPortal = "Second IPTV fixture"
        withLocalViewer(account, firstPortal, setOf(viewerName), setup = { users, portal ->
            val viewer = createRememberedViewer(account, viewerName)
            users.selectProfile(viewer.profileId)
            ProfileStore(context).scoped(viewer).apply {
                addProfile(portal)
                addProfile(portal.copy(name = secondPortal, username = "second-fixture"))
            }
            users.rememberDestination(viewer, ViewerDestination(ViewerDestinationKind.PORTAL, portal.cacheKey()))
            users.clearSelection()
        }) { _, portal, main ->
            val configuration = main.resources.configuration
            org.junit.Assume.assumeFalse(com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout(configuration, main.isTvLikeDevice(configuration)))
            awaitDashboard(viewerName)
            fun headerBounds(activity: Activity, label: String, index: Int = 0): androidx.compose.ui.geometry.Rect {
                var bounds = androidx.compose.ui.geometry.Rect.Zero
                await("$label is laid out in the header") {
                    bounds = readDashboardSemantics(activity) { nodes ->
                        nodes.filter { label in it.dashboardLabels() }
                            .distinctBy { it.boundsInWindow }.sortedBy { it.boundsInWindow.left }
                            .elementAtOrNull(index)?.boundsInWindow ?: androidx.compose.ui.geometry.Rect.Zero
                    }
                    !bounds.isEmpty && activity.hasWindowFocus()
                }
                return bounds
            }
            val iptvOptions = headerBounds(main, "Options")
            val fixedControls = listOf("NikTV", "Back", "Xtream", "Switch profile: $viewerName")
            val iptvBounds = fixedControls.associateWith { headerBounds(main, it) }
            fun portalNode(nodes: Sequence<SemanticsNode>, label: String, index: Int) =
                nodes.filter { label in it.dashboardLabels() && it.config.getOrNull(SemanticsProperties.Selected) != null }
                    .sortedBy { it.boundsInWindow.left }.elementAtOrNull(index)?.config
            fun isPortalFocused(activity: Activity, label: String, index: Int = 0, selected: Boolean = false) =
                readDashboardSemantics(activity) { nodes ->
                    val portal = portalNode(nodes, label, index)
                    activity.hasWindowFocus() && portal?.getOrNull(SemanticsProperties.Focused) == true &&
                        (!selected || portal.getOrNull(SemanticsProperties.Selected) == true)
                }
            fun switchFrom(launcherLabel: String, activityClass: Class<out Activity>, movesLeft: Int, destination: Int) {
                val monitor = instrumentation.addMonitor(activityClass.name, null, false)
                val sports = try {
                    await("$launcherLabel accepts remote focus") { readDashboardSemantics(main) { nodes ->
                        main.hasWindowFocus() && portalNode(nodes, launcherLabel, 0)
                            ?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true
                    } }
                    await("$launcherLabel is focused on IPTV") { isPortalFocused(main, launcherLabel) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) { "$launcherLabel did not open" }
                } finally { instrumentation.removeMonitor(monitor) }
                val seenFocus = ConcurrentLinkedQueue<String>()
                val oldEventTypes = automation.serviceInfo.eventTypes
                try {
                    await("$launcherLabel retains entry focus") { isPortalFocused(sports, launcherLabel, selected = true) }
                    val options = headerBounds(sports, if (launcherLabel == "Sports") "Sports options" else "Playfy options")
                    assertEquals("$launcherLabel options tile dimensions", iptvOptions.size, options.size)
                    assertEquals("$launcherLabel options alignment", iptvOptions.top, options.top, 1f)
                    for ((label, iptv) in iptvBounds) {
                        val other = headerBounds(sports, label)
                        assertEquals("$launcherLabel keeps $label the same size", iptv.size, other.size)
                        assertEquals("$launcherLabel keeps $label on the same horizontal anchor", iptv.left, other.left, 1f)
                        assertEquals("$launcherLabel keeps $label on the same vertical anchor", iptv.top, other.top, 1f)
                    }
                    NavigationAuditEvidence.capture("shared-portal-header-$launcherLabel")
                    repeat(movesLeft) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                    await("$launcherLabel focuses IPTV button ${destination + 1}") { isPortalFocused(sports, "Xtream", destination) }
                    automation.serviceInfo = automation.serviceInfo.apply { eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_FOCUSED }
                    automation.setOnAccessibilityEventListener { event ->
                        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.packageName?.toString() == context.packageName) {
                            (event.contentDescription ?: event.source?.contentDescription ?: event.text.firstOrNull())
                                ?.toString()?.let(seenFocus::add)
                        }
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("$launcherLabel returns directly to IPTV button ${destination + 1}") {
                        isPortalFocused(main, "Xtream", destination, selected = true)
                    }
                    assertTrue("No dashboard tab receives focus during handoff: $seenFocus", seenFocus.none { it.removeSuffix(" 2") in tabTitles })
                    NavigationAuditEvidence.capture("portal-return-from-$launcherLabel")
                } finally {
                    automation.setOnAccessibilityEventListener(null)
                    automation.serviceInfo = automation.serviceInfo.apply { eventTypes = oldEventTypes }
                    instrumentation.runOnMainSync { if (!sports.isFinishing) sports.finish() }
                }
            }
            try {
                switchFrom("Sports", com.nikhil.niktv.sports.SportsActivity::class.java, movesLeft = 1, destination = 1)
                switchFrom("Playfy TV", com.nikhil.niktv.sports.PlayfyActivity::class.java, movesLeft = 3, destination = 0)
            } finally {
                runBlocking { ProfileStore(context).removeProfile(portal.copy(name = secondPortal, username = "second-fixture")) }
            }
        }
    }

    private fun SemanticsNode.dashboardLabels() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.isDashboardTab(title: String) = config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        dashboardLabels().any { it == title || it == "$title 2" }

    private fun <T : Any> readDashboardSemantics(activity: Activity, read: (Sequence<SemanticsNode>) -> T): T {
        fun roots(view: View): Sequence<ViewRootForTest> = sequence {
            if (view is ViewRootForTest) yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(roots(view.getChildAt(index)))
        }
        lateinit var result: T
        instrumentation.runOnMainSync {
            result = read(roots(activity.window.decorView)
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
                .filter { !it.boundsInWindow.isEmpty })
        }
        return result
    }

    private fun tab(title: String, snapshot: List<AccessibilityNodeInfo> = nodes()): AccessibilityNodeInfo? {
        val candidates = snapshot.filter { it.isVisibleToUser && (it.matches(title) || it.matches("$title 2")) }
            .mapNotNull { node -> generateSequence(node) { it.parent }.firstOrNull {
                it.isVisibleToUser && (it.isFocusable || it.isSelected || it.isClickable)
            } }.filter { Build.VERSION.SDK_INT >= 33 || it.refresh() }
        // A selected Compose Tab remains a focus target without ACTION_CLICK.
        // Prefer it over the compact library switcher's duplicate My List label.
        return candidates.firstOrNull { it.isSelected }
            ?: candidates.firstOrNull { it.isFocused }
            ?: candidates.lastOrNull()
    }

    private fun withLocalViewer(
        account: String,
        portalName: String,
        fixtureProfiles: Set<String>,
        liveCategory: String? = null,
        setup: suspend (UserSyncStore, PortalProfile) -> Unit,
        exercise: (LocalXtreamProvider, PortalProfile, MainActivity) -> Unit,
    ) {
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val manager = AccountSyncManager.get(context)
        val vault = AccountTokenVault(context)
        val authKeys = listOf("accountId", "login", "ciphertext", "iv")
        val fixtureToken = "local-navigation-fixture"
        val interruptedFixture = vault.preferences.getString("accountId", null) == account && vault.token() == fixtureToken
        val oldAuth = authKeys.associateWith { key -> vault.preferences.getString(key, null).takeUnless { interruptedFixture } }
        val initializedKey = "initialized:$account"
        val previousInitialization = vault.preferences.getBoolean(initializedKey, false)
            .takeIf { vault.preferences.contains(initializedKey) && !interruptedFixture }
        val oldAccount = users.selectedAccount.value.takeUnless { interruptedFixture }
        val oldScope = users.selectedScope.value.takeUnless { interruptedFixture }
        val oldPullOnly = manager.manualPullOnly.value
        val oldAccessibilityFlags = automation.serviceInfo.flags
        val provider = LocalXtreamProvider(liveCategory)
        provider.start()
        val portal = PortalProfile(portalName, "http://127.0.0.1:${provider.listeningPort}", "",
            portalType = PortalType.XTREAM, username = "fixture", password = "fixture")
        var activity: MainActivity? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking {
                assertFalse("Regression builds never sync automatically", automaticAccountSyncEnabled())
                if (BuildConfig.DEBUG) {
                    manager.requestManualPullOnly(true).join()
                    assertTrue(manager.manualPullOnly.value)
                }
                users.selectAccount(account)
                users.clearSelection()
                users.profiles.first().filter { it.name in fixtureProfiles }.forEach { users.deleteProfile(it.id) }
                setup(users, portal)
            }
            // A synthetic cached login opens the normal gate without a GitHub credential.
            vault.save(fixtureToken, account, "local-navigation")
            check(vault.preferences.edit().putBoolean(initializedKey, true).commit())
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            exercise(provider, portal, activity)
        } finally {
            provider.stop()
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            instrumentation.setInTouchMode(true)
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
            runBlocking {
                users.profiles.first().filter { it.name in fixtureProfiles }.forEach { users.deleteProfile(it.id) }
                ProfileStore(context).removeProfile(portal)
                if (oldAccount == null) users.disconnect() else users.selectAccount(oldAccount)
                oldScope?.let { users.selectProfile(it.profileId) }
                vault.preferences.edit().apply {
                    oldAuth.forEach { (key, value) -> putString(key, value) }
                    if (previousInitialization == null) remove(initializedKey)
                    else putBoolean(initializedKey, previousInitialization)
                }.commit()
                manager.restoreCachedAccount()
                if (BuildConfig.DEBUG) manager.requestManualPullOnly(oldPullOnly).join()
            }
        }
    }

    private suspend fun createRememberedViewer(account: String, name: String): UserScope {
        val users = UserSyncStore.get(context)
        val viewer = users.createProfile(name)
        val secured = users.setProfilePin(viewer.id, ProfilePin.create("0000"))
        ProfilePin.remember(context, account, secured)
        users.setProfileEnabled(viewer.id, true)
        return UserScope(account, viewer.id)
    }

    private fun awaitDashboard(profileName: String) {
        var choseProfileAgain = false
        await("Dashboard opens directly for $profileName") {
            choseProfileAgain = choseProfileAgain || find("Who's watching?") != null
            find("Switch profile: $profileName") != null && find("Live TV") != null
        }
        assertFalse("Saving must not return to the profile chooser", choseProfileAgain)
    }

    private fun editPortalField(label: String, value: String) {
        click(label)
        setFocusedText(value)
        click("Done")
    }

    private fun enterFocusedText(value: String) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        setFocusedText(value)
    }

    private fun setFocusedText(value: String) {
        var field: AccessibilityNodeInfo? = null
        await("The current text field is editable") {
            field = nodes().firstOrNull { it.isFocused && it.isEditable }
            field != null
        }
        assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun keyboardHidden() = automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun click(vararg labels: String) {
        var target: AccessibilityNodeInfo? = null
        var scrollAt = SystemClock.uptimeMillis() + 400L
        var scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        await("Clickable ${labels.joinToString(" or ")}") {
            val snapshot = nodes()
            target = labels.firstNotNullOfOrNull { label ->
                control(label, snapshot)?.takeIf { node -> node.isEnabled && node.isVisibleToUser }
            }
            if (target == null && SystemClock.uptimeMillis() >= scrollAt) {
                val scroll = snapshot.firstOrNull { it.isScrollable && it.isVisibleToUser }
                if (scroll != null && !scroll.performAction(scrollAction)) {
                    scrollAction = if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                scrollAt = SystemClock.uptimeMillis() + 400L
            }
            target != null
        }
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun control(label: String, snapshot: List<AccessibilityNodeInfo> = nodes()) = snapshot.firstNotNullOfOrNull { node ->
        if (node.matches(label)) generateSequence(node) { it.parent }.firstOrNull { it.isClickable } else null
    }

    private fun focused(label: String) = nodes().filter { it.matches(label) }.any { node ->
        generateSequence(node) { it.parent }.any { it.isFocused }
    }

    private fun AccessibilityNodeInfo.matches(label: String) =
        text?.toString() == label || contentDescription?.toString() == label

    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser && it.matches(label) }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }
            ?: automation.windows.firstNotNullOfOrNull {
                it.root?.takeIf { node -> node.packageName?.toString() == context.packageName }
            }
        return buildList {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(root)
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        NavigationAuditEvidence.capture("profile-navigation-failure")
        val visible = nodes().filter { it.isVisibleToUser }
        val focused = visible.filter { it.refresh() && it.isFocused }.map { it.contentDescription ?: it.text }
        error("$message. Focused controls: $focused. Visible controls: ${visible.mapNotNull { it.text ?: it.contentDescription }}")
    }

    private class LocalXtreamProvider(private val liveCategory: String? = null) : NanoHTTPD("127.0.0.1", 0) {
        val authentications = AtomicInteger()
        val liveCategoryRequests = AtomicInteger()
        val unavailable = AtomicBoolean(false)

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/player_api.php" || session.parameters["username"]?.singleOrNull() !in setOf("fixture", "second-fixture") ||
                session.parameters["password"]?.singleOrNull() != "fixture") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
            if (unavailable.get()) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json", "{}")
            val body = when (session.parameters["action"]?.singleOrNull()) {
                null -> {
                    authentications.incrementAndGet()
                    """{"user_info":{"auth":1,"status":"Active"}}"""
                }
                "get_live_categories" -> liveCategory?.let {
                    liveCategoryRequests.incrementAndGet()
                    JSONArray().put(JSONObject().put("category_id", "2701").put("category_name", it)).toString()
                } ?: "[]"
                else -> "[]"
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }
    }

    /** Window timings and observed UI milestones are separate measurements. */
    private class TabFrameCapture(private val window: Window) : AutoCloseable {
        private data class Frame(val total: Long, val overrun: Long?, val stages: LongArray)
        private val measuredStages = linkedMapOf(
            "beforeInput" to FrameMetrics.UNKNOWN_DELAY_DURATION,
            "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "animation" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
            "draw" to FrameMetrics.DRAW_DURATION,
            "sync" to FrameMetrics.SYNC_DURATION,
            "commands" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
        )
        private val thread = HandlerThread("tab-navigation-frame-metrics").apply { start() }
        private val lock = Any()
        private var start = Long.MAX_VALUE
        private val frames = mutableListOf<Frame>()
        private val frameDurations = mutableListOf<Long>()
        private var dropped = 0
        private val transitions = JSONArray()
        private var target = ""
        private var selectionNs = 0L
        private val refreshRate = window.decorView.display?.refreshRate
        private val debuggable = window.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedCallbacks ->
            synchronized(lock) {
                val frameStart = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                if (frameStart >= start) {
                    val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
                    if (total > 0) frames.add(Frame(total, (total - deadline).takeIf { deadline > 0 },
                        measuredStages.values.map { metrics.getMetric(it) }.toLongArray()))
                    dropped += droppedCallbacks
                }
            }
        }

        init { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }

        fun begin(label: String) {
            target = label
            synchronized(lock) {
                frames.clear()
                dropped = 0
                start = System.nanoTime()
            }
        }

        fun selected() { selectionNs = System.nanoTime() - synchronized(lock) { start } }

        fun finish(hasUsefulContent: Boolean) {
            val contentNs = System.nanoTime() - synchronized(lock) { start }
            // The result must include the rest of the indicator animation even
            // when accessibility sees the selected tab in the opening frame.
            val remaining = ((250_000_000L - contentNs) / 1_000_000L).coerceAtLeast(0)
            SystemClock.sleep(remaining)
            val (snapshot, droppedCallbacks) = synchronized(lock) {
                start = Long.MAX_VALUE
                frames.toList() to dropped
            }
            val overruns = snapshot.mapNotNull { it.overrun }
            frameDurations.addAll(snapshot.map { it.total })
            val longest = snapshot.maxByOrNull { it.total }
            val longestStages = longest?.let { frame ->
                JSONObject().apply { measuredStages.keys.forEachIndexed { index, label -> put(label, frame.stages[index] / 1_000_000.0) } }
            }
            transitions.put(JSONObject().put("tab", target)
                .put("observedSelectionMs", selectionNs / 1_000_000.0)
                .put("observedUsefulContentMs", if (hasUsefulContent) contentNs / 1_000_000.0 else JSONObject.NULL)
                .put("frames", snapshot.size).put("droppedCallbacks", droppedCallbacks)
                .put("frameTotalP95Ms", percentile(snapshot.map { it.total }))
                .put("longestFrameStagesMs", longestStages ?: JSONObject.NULL)
                .put("frameOverrunP95Ms", percentile(overruns))
                .put("missedDeadlines", if (overruns.isEmpty()) JSONObject.NULL else overruns.count { it > 0 }))
        }

        private fun percentile(values: List<Long>): Any {
            if (values.isEmpty()) return JSONObject.NULL
            val index = (kotlin.math.ceil(values.size * .95).toInt() - 1).coerceIn(values.indices)
            return values.sorted()[index] / 1_000_000.0
        }

        fun report(): JSONObject {
            val interval = refreshRate?.takeIf { it > 0f }?.let { 1_000_000_000.0 / it }
            return JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("debuggable", debuggable).put("refreshRateHz", refreshRate ?: JSONObject.NULL)
                .put("frameCount", frameDurations.size).put("frameTotalP95Ms", percentile(frameDurations))
                .put("framesOverRefreshInterval", interval?.let { ns -> frameDurations.count { it > ns } } ?: JSONObject.NULL)
                .put("transitions", transitions)
        }

        override fun close() {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
    }
}
