package com.nikhil.niktv.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DiscoveryGlassScrollDeviceTest {
    @Test fun movingHeroBoundsDoNotRecomposeNavigation() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val activity = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val headerCompositions = AtomicInteger()
        val publish = AtomicReference<((MobileGlassSource?) -> Unit)?>(null)
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                DiscoveryArtworkShell(overlay = true, phone = false, header = {
                    SideEffect { headerCompositions.incrementAndGet() }
                    DiscoveryGlassPanel(Modifier.width(300.dp).height(48.dp), null, Rect.Zero) { Text("My List") }
                }, content = {
                    val callback = LocalPublishMobileGlassSource.current
                    SideEffect { publish.set(callback) }
                })
            } } }
            ins.waitForIdleSync()
            assertNotNull(publish.get())
            ins.runOnMainSync { publish.get()!!.invoke(MobileGlassSource(null, Rect(0f, 0f, 1000f, 600f))) }
            ins.waitForIdleSync()
            val before = headerCompositions.get()
            repeat(30) { frame ->
                ins.runOnMainSync { publish.get()!!.invoke(MobileGlassSource(null,
                    Rect(0f, -frame * 12f, 1000f, 600f - frame * 12f))) }
                ins.waitForIdleSync()
            }
            assertEquals("Scroll coordinates must redraw glass without recomposing navigation", before, headerCompositions.get())
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
