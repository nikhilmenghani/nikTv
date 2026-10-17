package com.nikhil.niktv.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Capture all focus events, including transient focus that a settled screenshot would miss. */
class PlayerChoiceInitialFocusTest {
    @Test fun initialFocusNeverVisitsAutoBeforeFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val events = CopyOnWriteArrayList<String>()
        val dialogReady = AtomicBoolean(false)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        val choices = listOf("Auto", "ExoPlayer", "Flow", "VLC")
                        val requesters = remember { choices.map { FocusRequester() } }
                        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
                            val dialogView = LocalView.current
                            LaunchedEffect(dialogView) {
                                while (!dialogView.hasWindowFocus()) withFrameNanos { }
                                withFrameNanos { }
                                dialogReady.set(true)
                            }
                            Row(Modifier.playerChoiceFocusEntry(requesters[2])) {
                                choices.forEachIndexed { index, label ->
                                    Surface(onClick = {}, modifier = Modifier.weight(1f).height(56.dp)
                                        .focusRequester(requesters[index]).onFocusChanged {
                                            if (it.isFocused) events.add(label)
                                        }) { Text(label) }
                                }
                            }
                        })
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 8000
            while ((!dialogReady.get() || events.isEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Dialog window must be ready for remote input", dialogReady.get())
            instrumentation.waitForIdleSync()
            assertEquals("First focus must already be Flow, with no intermediate Auto frame", listOf("Flow"), events.toList())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            val nextDeadline = System.currentTimeMillis() + 4000
            while (events.lastOrNull() != "VLC" && System.currentTimeMillis() < nextDeadline) Thread.sleep(50)
            assertEquals("Moving inside the group must not redirect back to Flow", listOf("Flow", "VLC"), events.toList())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
