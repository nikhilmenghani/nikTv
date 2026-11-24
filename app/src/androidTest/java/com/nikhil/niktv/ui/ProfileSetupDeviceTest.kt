package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Isolated account/UI fixtures: no provider calls, real profiles or credentials. */
class ProfileSetupDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val portal = PortalProfile("Fixture", "https://profile-setup.invalid", "00:11:22:33:44:55")

    private fun account(test: suspend (UserSyncStore, UserScope) -> Unit) = runBlocking {
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val oldAccount = users.selectedAccount.value
        val oldScope = users.selectedScope.value
        val account = "9911214321"
        users.selectAccount(account)
        val watching = users.createProfile("Watching fixture")
        users.setProfileEnabled(watching.id, true)
        users.selectProfile(watching.id)
        val owner = UserScope(account, watching.id)
        try { test(users, owner) }
        finally {
            users.profiles.first().forEach { users.deleteProfile(it.id) }
            oldAccount?.let { users.selectAccount(it) }
            oldScope?.let { users.selectProfile(it.profileId) }
        }
    }

    @Test fun createsReadyProfileAndOptionsWithoutChangingTheWatchingProfile() = account { users, watching ->
        ProfileStore(context).scoped(watching).addProfile(portal)
        val before = ProfileStore(context).connections.first().size
        val initial = ProfileSetupStore.load(context, null)
        val choice = initial.portals.single { it.portal.cacheKey() == portal.cacheKey() }
        val next = initial.copy(profile = initial.profile.copy(name = "New viewer"), layout = ViewerLayout.EASY,
            selected = setOf(choice.key), startingPortal = choice.key, content = XtreamContentPolicy.parents(),
            pinchToZoom = true, quality = 720, swipeControls = com.nikhil.niktv.data.PlayerSwipeControls(true, false, true))
        ProfileSetupStore.save(context, next)
        val created = UserScope(watching.accountId, next.profile.id)
        assertEquals(watching, users.selectedScope.value)
        assertTrue(next.profile.id in users.enabledProfileIds(watching.accountId))
        assertTrue(users.hasPortalAssociation(created, portal))
        assertTrue(users.hasPortalAssociation(watching, portal))
        assertTrue(ProfilePortalAccess.allowed(context, portal, created))
        assertEquals(before, ProfileStore(context).connections.first().size)
        assertEquals(ViewerLayout.EASY, ProfileBrowsePreferences.layout(context, created))
        assertEquals(XtreamContentPolicy.parents(), XtreamContentPreferences.read(context, created))
        assertTrue(PlayerGesturePreferences.pinchToZoom(context, created))
        assertEquals(com.nikhil.niktv.data.PlayerSwipeControls(true, false, true), PlayerGesturePreferences.swipes(context, created))
        assertEquals(720, PortalQualityPreferences.height(context, created))
        assertEquals(ProfileParentalPolicy(), ParentalControls.policy(context, created))
        assertEquals(portal.cacheKey(), users.rememberedDestination(created)?.portalKey)
        assertEquals(setOf(choice.key), ProfileSetupStore.load(context, next.profile).selected)
    }

    @Test fun oneSwitchAssignsAndAllowsAnUnloadedProfileWithoutRemovingSharedCredentials() = account { users, watching ->
        ProfileStore(context).scoped(watching).addProfile(portal)
        val other = users.createProfile("Other viewer")
        val owner = UserScope(watching.accountId, other.id)
        ProfileSetupStore.setPortalAllowed(context, owner, portal, true)
        assertTrue(users.hasPortalAssociation(owner, portal))
        assertTrue(ProfilePortalAccess.allowed(context, portal, owner))
        assertEquals(watching, users.selectedScope.value)
        ProfileSetupStore.setPortalAllowed(context, owner, portal, false)
        assertFalse(users.hasPortalAssociation(owner, portal))
        assertFalse(ProfilePortalAccess.allowed(context, portal, owner))
        assertTrue(users.hasPortalAssociation(watching, portal))
        assertTrue(ProfileStore(context).connections.first().any { it.cacheKey() == portal.cacheKey() })
    }

    @Test fun batchFailureRollsBackProfileAndPreferencesTogether() = account { users, watching ->
        val person = UserProfile(UUID.randomUUID().toString(), "Rolled back fixture")
        val owner = UserScope(watching.accountId, person.id)
        try {
            UserProfilePreferences.saveBatch(context, owner, listOf(
                UserProfilePreferences.Change(ProfileBrowsePreferences.DOMAIN, "layout", "EASY"))) {
                users.updateProfile(person)
                error("Fixture failure")
            }
            fail("The fixture must fail")
        } catch (_: IllegalStateException) { }
        assertFalse(users.profiles.first().any { it.id == person.id })
        assertTrue(users.dao.preferences(owner.accountId, owner.profileId).isEmpty())
        assertTrue(users.dao.records(owner.accountId).none { it.profileId == owner.profileId })
        assertEquals(watching, users.selectedScope.value)
    }

    @Test fun managingAnotherProfileKeepsItsHistoryAndDoesNotChangeTheWatchingProfile() = account { users, watching ->
        ProfileStore(context).scoped(watching).addProfile(portal)
        val other = users.createProfile("Managed viewer")
        val owner = UserScope(watching.accountId, other.id)
        val store = ProfileStore(context).scoped(owner)
        val favorite = FavoriteItem(FavoriteKind.MOVIE,
            MediaItem("saved-movie", "Saved movie", "https://art.invalid/poster.jpg", null), profileKey = portal.cacheKey())
        store.saveFavorites(listOf(favorite))
        val draft = ProfileSetupStore.load(context, other)
        val choice = draft.portals.single { it.portal.cacheKey() == portal.cacheKey() }
        assertFalse(draft.enabled)
        ProfileSetupStore.save(context, draft.copy(enabled = true, selected = setOf(choice.key),
            layout = ViewerLayout.EASY, pinchToZoom = true, content = XtreamContentPolicy.parents()))
        assertEquals(watching, users.selectedScope.value)
        assertEquals(listOf(favorite), store.favorites.first())
        assertEquals(ViewerLayout.STANDARD, ProfileBrowsePreferences.layout(context, watching))
        assertFalse(PlayerGesturePreferences.pinchToZoom(context, watching))
        assertEquals(ViewerLayout.EASY, ProfileBrowsePreferences.layout(context, owner))
        assertTrue(other.id in users.enabledProfileIds(owner.accountId))
        assertTrue(users.hasPortalAssociation(owner, portal))
    }

    @Test fun twoStepsPreserveChoicesAndFocusOnPhoneTabletTvAndLandscape() {
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val saved = AtomicReference<ProfileSetupDraft>()
        val focusEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val words = mutableListOf<String>()
                walk(event.source) { node -> node.text?.toString()?.let(words::add); false }
                focusEvents += words.joinToString("|")
            }
        }
        try {
            for (mode in 0..3) {
                ins.setInTouchMode(false)
                ins.runOnMainSync { host.setContent {
                    val config = Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = if (mode == 0) 390 else 900
                        screenHeightDp = if (mode == 3) 360 else 540
                        smallestScreenWidthDp = if (mode == 0 || mode == 3) 390 else 600
                        uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                            if (mode == 2) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                    }
                    CompositionLocalProvider(LocalConfiguration provides config) {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            Box(Modifier.width(config.screenWidthDp.dp).height(config.screenHeightDp.dp)) {
                                ProfileSetupForm(ProfileSetupDraft(UserProfile(UUID.nameUUIDFromBytes("ui-$mode".toByteArray()).toString(), "Setup fixture"),
                                    "9911214321", portals = listOf(ProfilePortalChoice(portal, true))), {}, { saved.set(it) })
                            }
                        }
                    }
                } }
                await("First step appears") { find("1 of 2 · Profile details") != null }
                await("Name receives entry focus") { walk(root()) { it.className == "android.widget.EditText" && it.isFocused && it.text?.toString() == "Setup fixture" } != null }
                assertFalse("Directional entry never opens the keyboard", ins.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("D-pad leaves the read-only name field") { walk(root()) { it.className == "android.widget.EditText" && it.isFocused && it.text.isNullOrEmpty() } != null }
                focusEvents.clear()
                click("Next")
                await("Second step focuses the first portal") { focused("Fixture") }
                assertTrue("Step transition must not focus header/footer: $focusEvents",
                    focusEvents.none { it in listOf("Previous", "Cancel", "Create & open") })
                val before = settledBounds("Fixture")
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("One OK enables this portal") { control("Fixture")?.isChecked == true }
                assertTrue("Toggling must retain focus", focused("Fixture"))
                assertEquals("Toggling must keep the same screen bounds", before, settledBounds("Fixture"))
                screenshot(host, "profile-setup-$mode-portals")
                click("Playback")
                click("Pinch to zoom")
                click("Previous")
                await("Previous restores the name field without an IME") { find("1 of 2 · Profile details") != null }
                click("Next")
                await("The selected portal remains enabled") { control("Fixture")?.isChecked == true }
                click("Create & open")
                await("New profile prompts for a password before saving") { find("Set password for Setup fixture") != null }
                await("Password owns entry focus") { focusedField("New password (4 digits)") }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                enterText("0000")
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Confirmation owns focus") { focusedField("Confirm password") }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                enterText("0000")
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Password confirmation leaves the editor") { focused("Cancel") }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Set password is selected and enabled") { focused("Set password") && control("Set password")?.isEnabled == true }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Setup saves all choices together") { saved.get()?.profile?.name == "Setup fixture" && saved.get()?.pinchToZoom == true }
                assertEquals(setOf(ProfilePortalAccess.key(portal)), saved.get().selected)
                assertTrue(ProfilePin.verifies(saved.get().profile.pinVerifier, "0000"))
                saved.set(null)
            }
        } finally { ins.uiAutomation.setOnAccessibilityEventListener(null); ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }

    @Test fun noConnectionsOpensAddPortalAndKeepsSetupFocusAndDraft() {
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val focusEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val words = mutableListOf<String>()
                walk(event.source) { node -> node.text?.toString()?.let(words::add); false }
                focusEvents += words.joinToString("|")
            }
        }
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent {
                MaterialTheme(colorScheme = NikColors) {
                    ProfileSetupForm(ProfileSetupDraft(UserProfile("first-portal-fixture", "No portal fixture"), "9911214321"), {}, {})
                }
            } }
            await("Setup starts at profile details") { find("1 of 2 · Profile details") != null }
            click("Next")
            await("Add portal is the first target") { focused("Add portal") }
            assertFalse("At least one portal is required", control("Create & open")?.isEnabled == true)
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The connection editor opens") { find("Configure portal") != null }
            screenshot(host, "profile-setup-first-portal-entry")
            await("The provider type owns entry focus") { focused("Stalker / MAG") }
            assertFalse("Provider focus does not open the keyboard", ins.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            focusEvents.clear()
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back returns directly to Add portal") { focused("Add portal") }
            assertTrue("Closing the editor must not focus setup navigation: $focusEvents",
                focusEvents.none { it in listOf("Previous", "Cancel", "Create & open") })
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Add portal opens again") { focused("Stalker / MAG") }
            click("Portal URL")
            await("Portal URL editor is focused") { walk(root()) { it.isEditable && it.isFocused } != null }
            enterText(portal.portalUrl)
            click("Done")
            await("URL editor closes") { find(portal.portalUrl) != null }
            click("Generate compatible device identity")
            click("Use portal")
            await("New portal is selected in the same draft") { control("Stalker")?.isChecked == true }
            assertTrue("Create becomes available", control("Create & open")?.isEnabled == true)
            assertNotNull("Profile name remains in the draft", find("Portals for No portal fixture"))
            screenshot(host, "profile-setup-first-portal-return")
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.runOnMainSync { host.finish() }
            ins.setInTouchMode(true)
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun walk(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (match(node)) return node
        for (index in 0 until node.childCount) walk(node.getChild(index), match)?.let { return it }
        return null
    }
    private fun find(text: String) = walk(root()) { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun control(text: String): AccessibilityNodeInfo? = find(text)?.let { node ->
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
    }
    private fun click(text: String) {
        repeat(8) {
            control(text)?.takeIf { it.isVisibleToUser }?.let { node ->
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            walk(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        fail("$text must be reachable by scrolling")
    }
    private fun focused(text: String) = walk(root()) { node ->
        node.isFocused && walk(node) { it.text?.toString() == text || it.contentDescription?.toString() == text } != null
    } != null
    private fun focusedField(label: String) = walk(root()) { it.isFocused && walk(it) { node -> node.text?.toString() == label } != null } != null
    private fun enterText(value: String) {
        await("Focused editor accepts $value") { walk(root()) { it.isFocused && it.isEditable } != null }
        assertTrue(requireNotNull(walk(root()) { it.isFocused && it.isEditable }).performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun settledBounds(label: String): Rect {
        var previous: Rect? = null
        var unchangedSince = 0L
        await("Bounds settle for the focused $label control") {
            val current = control(label)?.takeIf { it.isVisibleToUser }?.let(::bounds)
            val now = SystemClock.uptimeMillis()
            if (current != previous) {
                previous = current
                unchangedSince = now
            }
            // Entry focus scrolls the row into view. Wait for the accessibility
            // viewport bounds to settle before comparing the toggle itself.
            current != null && now - unchangedSince >= 250L
        }
        return requireNotNull(previous)
    }
    private fun await(message: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(40) }
        if (!check()) {
            val focused = walk(root()) { it.isFocused }
            val labels = mutableListOf<String>()
            walk(focused) { node -> node.text?.toString()?.let(labels::add); false }
            fail("$message. Focused node: ${focused?.className}, labels: $labels")
        }
    }
    private fun screenshot(host: PlaybackFixtureHostActivity, name: String) {
        val bitmap = ins.uiAutomation.takeScreenshot()
        try { File(host.externalCacheDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
