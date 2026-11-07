package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import org.junit.Assert.*
import org.junit.Test

/** Exercises the in-app gate only. Synthetic callbacks cannot download or launch an installer. */
class MandatoryUpdateNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val update = UpdateInfo("999.0", "https://updates.fixture.invalid/NikTV.apk")
    private val apk = Uri.parse("content://fixture.invalid/NikTV.apk")

    @Test fun downloadRetryInstallAndSettingsHaveCompleteRemotePathsInShortViewport() {
        fixture { state, checking, enforcement, downloads, installs ->
            await("Download is the initial action") { focused("Download & Install") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(1, downloads.size)
            NavigationAuditEvidence.capture("mandatory-update-download")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Download Down reaches Settings") { focused("Update settings") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Settings initially focuses the enforcement switch") { focusedSwitch() }
            NavigationAuditEvidence.capture("mandatory-update-settings")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Switch Down reaches Done") { focused("Done") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Done Up returns to switch") { focusedSwitch() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Done restores the originating Settings action") { focused("Update settings") && label("Done") == null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Settings Up returns to download") { focused("Download & Install") }

            instrumentation.runOnMainSync { state.value = UpdateDownloadState.Failed(null, update.version,
                update.downloadUrl, null, "Fixture network failure") }
            await("Failure preserves focus on Retry") { focused("Retry download") && label("Fixture network failure") != null }
            NavigationAuditEvidence.capture("mandatory-update-retry")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(2, downloads.size)
            for (permission in listOf(true, false)) {
                instrumentation.runOnMainSync { state.value = UpdateDownloadState.Ready(1, update.version,
                    update.downloadUrl, apk, awaitingUnknownSourcesPermission = permission) }
                val action = if (permission) "Allow installation" else "Install update"
                await("Ready focuses $action") { focused(action) }
                NavigationAuditEvidence.capture("mandatory-update-${if (permission) "permission" else "ready"}")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            instrumentation.runOnMainSync { state.value = UpdateDownloadState.InstallerLaunched(1,
                update.version, update.downloadUrl, apk) }
            await("Returned installer still exposes its install action") { focused("Install update") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(3, installs.size)
            assertEquals("All download callbacks received the synthetic update", listOf(update, update), downloads)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Reopened Settings focuses switch") { focusedSwitch() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Remote toggle disables enforcement and closes settings") { !enforcement.value && label("Done") == null }
            assertFalse(checking.value)
        }
    }

    @Test fun busyStatesKeepSettingsReachableAndBackCannotBypassTheCheckingGate() {
        fixture { state, checking, _, downloads, installs ->
            val busy = listOf(
                UpdateDownloadState.Queued(1, update.version, update.downloadUrl),
                UpdateDownloadState.Downloading(1, update.version, update.downloadUrl, 50, 100, 50),
                UpdateDownloadState.Paused(1, update.version, update.downloadUrl, 50, 100, "Waiting for fixture network"),
                UpdateDownloadState.Installing(1, update.version, update.downloadUrl, apk)
            )
            for ((index, value) in busy.withIndex()) {
                instrumentation.runOnMainSync { state.value = value }
                await("Busy state $index focuses its enabled recovery action") { focused("Update settings") }
                NavigationAuditEvidence.capture("mandatory-update-busy-$index")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Busy state $index can open Settings") { focusedSwitch() }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Busy state $index exposes Done") { focused("Done") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Busy state $index restores Settings") { focused("Update settings") && label("Done") == null }
            }
            assertTrue(downloads.isEmpty())
            assertTrue(installs.isEmpty())
            instrumentation.runOnMainSync { checking.value = true; state.value = UpdateDownloadState.Idle }
            await("Checking hides actions until a result exists") { label("Checking for updates…") != null && label("Update settings") == null }
            key(KeyEvent.KEYCODE_BACK)
            await("Back retains the mandatory in-app gate") { label("Checking for updates…") != null }
            NavigationAuditEvidence.capture("mandatory-update-checking")
            instrumentation.runOnMainSync { checking.value = false }
            await("Completed check restores its first actionable control") { focused("Download & Install") }
        }
    }

    private fun fixture(test: (androidx.compose.runtime.MutableState<UpdateDownloadState>,
        androidx.compose.runtime.MutableState<Boolean>, androidx.compose.runtime.MutableState<Boolean>,
        MutableList<UpdateInfo>, MutableList<Unit>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Use the isolated regression package" }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val state = mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle)
        val checking = mutableStateOf(false)
        val enforcement = mutableStateOf(true)
        val downloads = java.util.concurrent.CopyOnWriteArrayList<UpdateInfo>()
        val installs = java.util.concurrent.CopyOnWriteArrayList<Unit>()
        instrumentation.setInTouchMode(false)
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                Box(Modifier.fillMaxWidth().height(480.dp)) {
                    MandatoryNikTvUpdateScreen(checking.value, update, state.value, enforcement.value,
                        setEnforcementEnabled = { enforcement.value = it },
                        downloadUpdate = { downloads.add(it) }, installUpdate = { installs.add(Unit) })
                }
            } } }
            await("Fixture window is active") { activity.hasWindowFocus() && label("Update required") != null }
            test(state, checking, enforcement, downloads, installs)
        } finally {
            runCatching { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
            it.packageName?.toString() == instrumentation.targetContext.packageName
        }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun owner(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node
    private fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun focused(text: String): Boolean {
        val current = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::owner) ?: return false
        return label(text)?.let { owner(it) == current && current.isVisibleToUser } == true
    }
    private fun focusedSwitch(): Boolean {
        val current = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::owner) ?: return false
        return find { it.isCheckable && owner(it) == current && it.isVisibleToUser } != null
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(40)
        }
        NavigationAuditEvidence.capture("mandatory-update-failure")
        fail(message)
    }
}
