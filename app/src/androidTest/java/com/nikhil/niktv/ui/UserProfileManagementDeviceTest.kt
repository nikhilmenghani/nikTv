package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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
            await("Existing profile is available") { find("Existing viewer") != null }
            click("+  Add user profile")
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
            await("Profile row is visible") { find("Existing viewer") != null }
            click("Manage Existing viewer")
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
}
