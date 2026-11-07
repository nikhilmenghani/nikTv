package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real gate's shared dispatcher without account restoration or network state. */
class AccountGateBackNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(40) }
        NavigationAuditEvidence.capture("account-back-failure-$label")
        fail(label)
    }
    private fun keyBack() { ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ins.waitForIdleSync() }
    private fun host(): PlaybackFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }

    @Test fun portalPeopleRootBackPathNeverBouncesToThePortalChooser() {
        val activity = host()
        val people = mutableStateOf(false)
        val rootExits = AtomicInteger()
        val resumes = AtomicInteger()
        val ready = AtomicInteger()
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                BackHandler { rootExits.incrementAndGet() }
                AccountGateBackHandler(peopleVisible = people.value, choosingProfile = people.value,
                    selectedViewerAvailable = true, state = NikTvState(restoring = false),
                    openPeople = { people.value = true }, resumeContent = { resumes.incrementAndGet(); people.value = false })
                SideEffect { ready.incrementAndGet() }
                Text(if (people.value) "People root" else "Portal chooser")
            } } } }
            await("Portal gate is composed") { ready.get() > 0 }
            keyBack()
            await("First Back opens people") { people.value }
            NavigationAuditEvidence.capture("account-back-portal-to-people")
            keyBack()
            await("Second Back reaches the root dispatcher") { rootExits.get() == 1 }
            assertTrue("People must stay the root until Android exits", people.value)
            assertEquals("Back cannot resume the portal chooser", 0, resumes.get())
            NavigationAuditEvidence.capture("account-back-people-root")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun avatarPeopleBackResumesTheRetainedSession() {
        val profile = PortalProfile("Fixture", "https://account-back.invalid", "")
        verifyResume(NikTvState(session = PortalSession(profile, "fixture", profile.portalUrl, "", "", "")), "session")
    }

    @Test fun peopleBackResumesAnActiveOfflineDestination() =
        verifyResume(NikTvState(offlineDownloadsOpen = true), "offline")

    private fun verifyResume(state: NikTvState, label: String) {
        val activity = host()
        val people = mutableStateOf(true)
        val rootExits = AtomicInteger()
        val resumes = AtomicInteger()
        val ready = AtomicInteger()
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                BackHandler { rootExits.incrementAndGet() }
                AccountGateBackHandler(peopleVisible = people.value, choosingProfile = people.value,
                    selectedViewerAvailable = true, state = state,
                    openPeople = { people.value = true }, resumeContent = { resumes.incrementAndGet(); people.value = false })
                SideEffect { ready.incrementAndGet() }
                Text(if (people.value) "People chooser" else "Retained $label content")
            } } } }
            await("People chooser is composed") { ready.get() > 0 }
            keyBack()
            await("Back resumes retained $label content") { resumes.get() == 1 && !people.value }
            assertEquals("Cancel does not exit active content", 0, rootExits.get())
            NavigationAuditEvidence.capture("account-back-retained-$label")
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
