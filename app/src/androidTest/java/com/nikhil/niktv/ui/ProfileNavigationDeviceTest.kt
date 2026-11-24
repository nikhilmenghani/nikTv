package com.nikhil.niktv.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            await("Home is selected on startup") { tab("Home")?.isSelected == true }
            val measure = InstrumentationRegistry.getArguments().getString("tabFrameMetrics") == "true"
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
                    if (!alreadyFocused) {
                        var control: AccessibilityNodeInfo? = null
                        await("$target is reachable") { tab(target).also { control = it } != null }
                        if (control?.isFocused != true) {
                            assertTrue("$target accepts D-pad focus", control?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
                        }
                    }
                    await("$target owns focus before OK") { tab(target)?.isFocused == true }
                    focusLabels.clear()
                    frames?.begin(target)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    fun checkLiveStatus(snapshot: List<AccessibilityNodeInfo>) {
                        if (useful != null && tab(target, snapshot)?.isSelected == true) {
                            assertFalse("Live TV must keep cached or loading content during setup", snapshot.any {
                                it.isVisibleToUser && it.text?.startsWith("No matching categories") == true
                            })
                        }
                    }
                    await("$target becomes selected and stays focused") {
                        val snapshot = nodes()
                        checkLiveStatus(snapshot)
                        tab(target, snapshot)?.let { it.isSelected && it.isFocused } == true
                    }
                    frames?.selected()
                    useful?.let { label -> await("$target shows category $label") {
                        val snapshot = nodes()
                        checkLiveStatus(snapshot)
                        snapshot.any { it.isVisibleToUser && it.matches(label) }
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
                await("Back from Series returns to Home") { tab("Home")?.isSelected == true }
                activate("Live TV", liveCollection)
                assertTrue("The isolated provider supplied the fresh catalogue", provider.liveCategoryRequests.get() > 0)
                provider.unavailable.set(true)
                activate("My List")
                activate("Live TV", liveCollection)
                if (!measure) NavigationAuditEvidence.capture("tab-navigation-live-offline")
                if (measure) {
                    repeat(3) {
                        activate("Home")
                        activate("Movies")
                        activate("Series")
                        activate("Live TV", liveCollection)
                        activate("My List")
                    }
                    requireNotNull(frames).write(File(context.externalCacheDir, "navigation-audit/tab-navigation-metrics.json"))
                }
            } finally {
                automation.setOnAccessibilityEventListener(null)
                automation.serviceInfo = automation.serviceInfo.apply { eventTypes = oldEventTypes }
                instrumentation.runOnMainSync { frames?.close() }
            }
        }
    }

    private val tabTitles = setOf("Home", "Movies", "Series", "Live TV", "My List")

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
            if (session.uri != "/player_api.php" || session.parameters["username"]?.singleOrNull() != "fixture" ||
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
        private data class Frame(val total: Long, val overrun: Long?)
        private val thread = HandlerThread("tab-navigation-frame-metrics").apply { start() }
        private val lock = Any()
        private var start = Long.MAX_VALUE
        private val frames = mutableListOf<Frame>()
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
                    if (total > 0) frames.add(Frame(total, (total - deadline).takeIf { deadline > 0 }))
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
            transitions.put(JSONObject().put("tab", target)
                .put("observedSelectionMs", selectionNs / 1_000_000.0)
                .put("observedUsefulContentMs", if (hasUsefulContent) contentNs / 1_000_000.0 else JSONObject.NULL)
                .put("frames", snapshot.size).put("droppedCallbacks", droppedCallbacks)
                .put("frameTotalP95Ms", percentile(snapshot.map { it.total }))
                .put("frameOverrunP95Ms", percentile(overruns))
                .put("missedDeadlines", if (overruns.isEmpty()) JSONObject.NULL else overruns.count { it > 0 }))
        }

        private fun percentile(values: List<Long>): Any {
            if (values.isEmpty()) return JSONObject.NULL
            val index = (kotlin.math.ceil(values.size * .95).toInt() - 1).coerceIn(values.indices)
            return values.sorted()[index] / 1_000_000.0
        }

        fun write(path: File) {
            path.parentFile?.mkdirs()
            path.writeText(JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("debuggable", debuggable).put("refreshRateHz", refreshRate ?: JSONObject.NULL)
                .put("transitions", transitions).toString(2))
        }

        override fun close() {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
    }
}
