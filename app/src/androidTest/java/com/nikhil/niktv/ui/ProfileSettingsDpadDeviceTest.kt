package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** No real profiles, sessions, credentials or providers are changed by these tests. */
class ProfileSettingsDpadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        assertTrue(message, condition())
    }
    private fun key(value: Int) = instrumentation.sendKeyDownUpSync(value)

    private fun fixture(available: Boolean, verify: (AtomicReference<String>, AtomicBoolean, AtomicBoolean, AtomicInteger) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val ready = AtomicBoolean(false)
        val focused = AtomicReference("")
        val first = AtomicBoolean(false)
        val second = AtomicBoolean(false)
        val setupRequests = AtomicInteger(0)
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        val rail = remember { SettingsDestination.entries.associateWith { FocusRequester() } }
                        val entry = remember { FocusRequester() }
                        var firstChecked by remember { mutableStateOf(false) }
                        var secondChecked by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxSize()) {
                            SettingsDestinationRail(SettingsDestination.PROFILES, {}, rail, entry,
                                Modifier.width(200.dp).fillMaxHeight())
                            Column(Modifier.weight(1f).padding(24.dp)) {
                                ProfileAvailabilitySetting("Wio fixture", true, firstChecked, available,
                                    { firstChecked = it; first.set(it) }, { setupRequests.incrementAndGet() },
                                    Modifier.focusRequester(entry).onFocusChanged { if (it.isFocused) focused.set("first") })
                                ProfileAvailabilitySetting("Nikhil fixture", false, secondChecked, true,
                                    { secondChecked = it; second.set(it) }, {},
                                    Modifier.onFocusChanged { if (it.isFocused) focused.set("second") })
                                TextButton({}) { Text("Add profile") }
                            }
                        }
                        LaunchedEffect(Unit) {
                            repeat(120) {
                                withFrameNanos { }
                                if (host.hasWindowFocus() && rail.getValue(SettingsDestination.PROFILES).requestFocus()) {
                                    ready.set(true)
                                    return@LaunchedEffect
                                }
                            }
                        }
                    }
                }
            }
            await("Profiles rail must be focused", ready::get)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right must enter the first profile, rather than Add profile", { focused.get() == "first" })
            verify(focused, first, second, setupRequests)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun rightEntersFirstProfileAndRemoteTogglesEachRowOnce() = fixture(true) { focus, first, second, _ ->
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK enables the first profile", first::get)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down goes directly to the next profile, skipping decorative switches", { focus.get() == "second" })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK enables the second profile", second::get)
        assertTrue("Moving to the second profile preserves the first", first.get())
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up returns to the first profile", { focus.get() == "first" })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK can disable the first profile again", { !first.get() })
        assertTrue("First-row toggle must not modify another profile", second.get())
    }

    @Test fun missingConfigurationIsReachableButCannotEnableProfile() = fixture(false) { focus, first, _, setup ->
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Unavailable profile explains setup instead of silently ignoring OK", { setup.get() == 1 })
        assertFalse("D-pad must never bypass credential availability", first.get())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("User can move past an unavailable profile", { focus.get() == "second" })
    }
}
