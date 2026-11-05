package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AccountSyncState
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.data.UserProfileType
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real profile management UI with local fixture callbacks; no account or viewing data is edited. */
class UserProfileManagementDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private fun walk(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) walk(root.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun roots(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return (listOfNotNull(i.uiAutomation.rootInActiveWindow) + i.uiAutomation.windows.mapNotNull { it.root })
            .filter { it.packageName == i.targetContext.packageName }
    }
    private fun find(label: String) = roots().firstNotNullOfOrNull { root -> walk(root) {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    } }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        assertTrue(message, condition())
    }
    private fun click(label: String) {
        repeat(10) {
            var node = find(label)
            if (node != null) {
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            roots().firstNotNullOfOrNull { root -> walk(root) { it.isScrollable } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        fail("$label must be reachable")
    }
    private fun manage(label: String) {
        await("Profile tile is available") { find(label) != null }
        var node = find(label)
        while (node != null && !node.isLongClickable) node = node.parent
        assertTrue("Holding a profile opens its management menu", node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
        await("Profile menu is available") { find("Manage profile") != null }
    }
    private fun fixture(content: @Composable () -> Unit, verify: () -> Unit) {
        val context = i.targetContext
        check(context.packageName.endsWith(".regression"))
        i.setInTouchMode(true)
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            i.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { Surface { content() } } } }
            verify()
        } finally { i.runOnMainSync { host.finish() } }
    }

    @Test fun creatingAnotherPersonStartsWithBlankNameGeneralTypeAndNoAutomaticImport() {
        val original = UserProfile(UUID.randomUUID().toString(), "Existing viewer", avatar = "E")
        val people = mutableStateListOf(original)
        val created = AtomicReference<UserProfile>()
        val selected = AtomicReference<String>()
        val imports = AtomicInteger()
        fixture({ UserProfilesScreen(people, AccountSyncState(initialized = true), null,
            { selected.set(it.id) }, { existing, name, type, color, avatar ->
                assertNull("Create must not edit or copy another person", existing)
                UserProfile(UUID.randomUUID().toString(), name, color, avatar, type).also { created.set(it); people.add(it) }
            }, {}, {}, {}, onImportLegacy = { imports.incrementAndGet() }) }) {
            await("Existing profile is available") { find("Watch as Existing viewer") != null }
            val add = find("Add user profile")!!
            assertFalse("Add is not a selected person", add.isSelected || add.isChecked)
            click("Add user profile")
            await("Creation form explains the empty starting state") { find("Start with an empty viewing space. Configure portals after creating it.") != null }
            val input = roots().firstNotNullOfOrNull { root -> walk(root) { it.isEditable } }
            assertNotNull(input)
            assertTrue("Another person's name is never prefilled", input?.text.isNullOrEmpty())
            assertNotNull("New profile defaults to General", find("✓  General"))
            assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "New viewer")
            }))
            if (i.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click("Create profile")
            await("A separate profile is created") { created.get() != null }
            assertNotEquals(original.id, created.get().id)
            assertEquals(UserProfileType.GENERAL, created.get().type)
            assertEquals("", created.get().avatar)
            assertEquals(0, imports.get())
            assertNull("Creation does not silently select an existing person", selected.get())
            click("Watch as New viewer")
            await("Selection uses the new permanent identity") { selected.get() == created.get().id }
            assertEquals(original, people.first())
        }
    }

    @Test fun manageExposesDeleteAndRequiresExplicitAcrossDeviceConfirmation() {
        val existing = UserProfile(UUID.randomUUID().toString(), "Existing viewer")
        val people = mutableStateListOf(existing)
        val deleted = AtomicReference<String>()
        fixture({ UserProfilesScreen(people, AccountSyncState(initialized = true), null, {}, { _, _, _, _, _ -> },
            { deleted.set(it.id); people.remove(it) }, {}, {}) }) {
            await("Profile tile is visible") { find("Watch as Existing viewer") != null }
            manage("Watch as Existing viewer")
            click("Manage profile")
            await("Identity editor is available") { find("Save changes") != null }
            click("Delete profile")
            await("Deletion scope is explicit") { find("Delete Existing viewer across devices?") != null }
            assertNull("Opening confirmation is not deletion", deleted.get())
            click("Cancel")
            assertNull(deleted.get())
            assertEquals(listOf(existing), people.toList())
            click("Delete profile")
            await("Confirmation can reopen") { find("Delete Existing viewer across devices?") != null }
            click("Delete across devices")
            await("Only the chosen profile is deleted") { deleted.get() == existing.id }
            assertTrue(people.isEmpty())
        }
    }

    @Test fun offlineDownloadsAsksForTheExactPersonWithoutPortalSelection() {
        val family = UserProfile(UUID.randomUUID().toString(), "Family")
        val kids = UserProfile(UUID.randomUUID().toString(), "Kids", type = UserProfileType.KIDS)
        val opened = AtomicReference<UserProfile>()
        val portalSelection = AtomicInteger()
        val portalManagement = AtomicInteger()
        fixture({ UserProfilesScreen(listOf(family, kids), AccountSyncState(initialized = true), null,
            { portalSelection.incrementAndGet() }, { _, _, _, _, _ -> }, {}, {}, {},
            onManagePortals = { portalManagement.incrementAndGet() }, selectedProfileId = family.id,
            onOfflineDownloads = opened::set) }) {
            await("Profile chooser offers device downloads") { find("Offline downloads") != null }
            click("Offline downloads")
            await("Multiple people require an explicit choice") { find("Choose a profile to see downloads on this device.") != null }
            assertNull("The selected profile is not silently assigned", opened.get())
            click("Cancel")
            assertNull("Cancel does not open downloads", opened.get())
            click("Offline downloads")
            click("Offline downloads for Kids")
            await("Downloads use the chosen permanent profile identity") { opened.get()?.id == kids.id }
            assertEquals(kids, opened.get())
            assertEquals("Downloads bypass portal navigation", 0, portalSelection.get())
            assertEquals(0, portalManagement.get())
            assertNull(find("Stalker"))
        }
    }

    @Test fun onlyPersonsDownloadsOpenDirectlyEvenWhenAccountNeedsReconnect() {
        val person = UserProfile(UUID.randomUUID().toString(), "Offline viewer")
        val opened = AtomicReference<UserProfile>()
        val portalSelection = AtomicInteger()
        fixture({ UserProfilesScreen(listOf(person), AccountSyncState(initialized = true,
            reconnectRequired = true, error = "Reconnect to sync. Downloads remain on this device."), null,
            { portalSelection.incrementAndGet() }, { _, _, _, _, _ -> }, {}, {}, {},
            onOfflineDownloads = opened::set) }) {
            await("Cached profile keeps device downloads accessible") { find("Offline downloads") != null }
            click("Offline downloads")
            await("One person opens their own downloads directly") { opened.get()?.id == person.id }
            assertEquals(person, opened.get())
            assertEquals(0, portalSelection.get())
            assertNull("Single-person downloads do not need a picker", find("Choose a profile to see downloads on this device."))
            assertNull("No portal authentication is entered", find("Stalker"))
        }
    }

    @Test fun remoteMenuEditsIdentityWithoutSelectingOrChangingUuidAndType() {
        val existing = UserProfile(UUID.randomUUID().toString(), "Existing viewer", avatar = "K", type = UserProfileType.KIDS)
        val selected = AtomicInteger()
        val saved = AtomicReference<UserProfile>()
        fixture({
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                UserProfilesScreen(listOf(existing), AccountSyncState(initialized = true), null, { selected.incrementAndGet() },
                    { original, name, type, color, avatar -> saved.set(requireNotNull(original).copy(name = name, type = type, color = color, avatar = avatar)) }, {}, {}, {}, selectedProfileId = existing.id)
            }
        }) {
            i.setInTouchMode(false)
            await("Selected tile receives initial remote focus") {
                var node = find("Watch as Existing viewer")
                repeat(5) { if (node?.isFocused == true) return@await true; node = node?.parent }
                false
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
            await("Remote Menu exposes management") { find("Manage profile") != null }
            assertEquals(0, selected.get())
            click("Manage profile")
            await("Identity editor opens") { find("Save changes") != null }
            assertNotNull(find("Kids profile"))
            click("Save changes")
            await("Identity changes save to the existing UUID") { saved.get() != null }
            assertEquals(existing.id, saved.get().id)
            assertEquals(UserProfileType.KIDS, saved.get().type)
            assertEquals(0, selected.get())
            await("Returning from management restores tile focus") {
                var node = find("Watch as Existing viewer")
                repeat(5) { if (node?.isFocused == true) return@await true; node = node?.parent }
                false
            }
            val down = SystemClock.uptimeMillis()
            i.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            Thread.sleep(150)
            i.uiAutomation.takeScreenshot()?.let { bitmap ->
                i.targetContext.filesDir.resolve("user-profile-pressed-validation.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }; bitmap.recycle()
            }
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong())
            i.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            await("Holding OK opens management") { find("Manage profile") != null }
            i.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            click("Cancel")
            await("Cancel closes management and restores the tile before the next remote press") {
                if (find("Manage profile") != null) return@await false
                var node = find("Watch as Existing viewer")
                repeat(5) { if (node?.isFocused == true) return@await true; node = node?.parent }
                false
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("A short OK after canceled hold selects on the first press") { selected.get() == 1 }
        }
    }
}
