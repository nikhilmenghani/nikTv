package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ActorControlsDeviceTest {
    private val test get() = InstrumentationRegistry.getInstrumentation()
    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString()?.lineSequence()?.any { it == label } == true || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return find(test.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (ready()) return; Thread.sleep(40) }; fail(message)
    }
    private fun action(label: String, action: Int) {
        var n = node(label)
        repeat(8) { if ((action == AccessibilityNodeInfo.ACTION_FOCUS && n?.isFocusable != true) || (action == AccessibilityNodeInfo.ACTION_CLICK && n?.isClickable != true)) n = n?.parent }
        assertTrue("Reachable $label", n?.performAction(action) == true)
    }
    private fun focused(label: String): Boolean {
        var n = node(label); repeat(8) { if (n?.isFocused == true) return true; n = n?.parent }; return false
    }
    private fun host(): PlaybackFixtureHostActivity {
        val context = test.targetContext; check(context.packageName.endsWith(".regression"))
        return test.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }
    @Test fun quickSortsStaySynchronizedAndActorInfoLoadsOnlyOnDemand() {
        val activity=host(); test.setInTouchMode(false)
        var infoCalls=0
        val cards=listOf(DiscoveryCard(1,false,"Older",date="2000-01-01",rating=9.0), DiscoveryCard(2,false,"Newer",date="2020-01-01",rating=5.0))
        try {
            test.runOnMainSync { activity.setContent { MaterialTheme(colorScheme=NikColors) {
                DiscoveryActorDialog(DiscoveryPerson("Fixture actor",role="Lead",id=123),
                    load={_,_->cards}, close={}, reduceMotion=true,
                    loadInfo={ infoCalls++; DiscoveryActorInfo("Fixture actor",biography="Public biography\n" + (1..100).joinToString("\n") { "Biography line $it" },birthday="1980-01-01",birthplace="Fixture city",department="Acting") },open={})
            } } }
            await("Credits start newest first") { focused("Newer") && node("Sort: Year: newest")!=null }
            assertEquals(0,infoCalls)
            action("Year",AccessibilityNodeInfo.ACTION_CLICK)
            await("Ascending year and menu agree") { node("Sort: Year: oldest")!=null && focused("Older") }
            action("Year",AccessibilityNodeInfo.ACTION_CLICK)
            await("Descending year and menu agree") { node("Sort: Year: newest")!=null && focused("Newer") }
            action("Rating",AccessibilityNodeInfo.ACTION_CLICK)
            await("Descending rating") { node("Sort: Rating: highest")!=null && focused("Older") }
            action("Rating",AccessibilityNodeInfo.ACTION_CLICK)
            await("Ascending rating") { node("Sort: Rating: lowest")!=null && focused("Newer") }
            action("Sort: Rating: lowest",AccessibilityNodeInfo.ACTION_CLICK)
            await("Sort menu is open") { node("Year: newest")!=null }
            action("Year: newest",AccessibilityNodeInfo.ACTION_CLICK)
            await("Menu changes shared sorting") { node("Sort: Year: newest")!=null && focused("Newer") }
            action("About Fixture actor",AccessibilityNodeInfo.ACTION_CLICK)
            await("Information loaded") { node("Public biography")!=null && focused("Close") }
            assertEquals(1,infoCalls)
            assertNotNull(node("Birthplace: Fixture city"))
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Long biography is reachable") { focused("Actor information") }
            repeat(30) { test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(15) }
            await("End of biography returns to Close") { focused("Close") }
            action("Close",AccessibilityNodeInfo.ACTION_CLICK)
            await("Focus returns to info action") { focused("About Fixture actor") }
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
