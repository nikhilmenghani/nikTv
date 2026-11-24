package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.DashboardSurface
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DiscoveryGlassScrollDeviceTest {
    @Test fun wideNavigationKeepsGlassStableAndOnlyRevealsTheCurrentArtwork() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val greenStarted = CountDownLatch(1)
        val releaseGreen = CountDownLatch(1)
        val revisitStarted = CountDownLatch(1)
        val releaseRevisit = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val color = when (session.uri) {
                    "/red.png" -> Color.RED
                    "/blue.png" -> Color.BLUE
                    "/green.png" -> {
                        greenStarted.countDown()
                        releaseGreen.await(10, TimeUnit.SECONDS)
                        Color.GREEN
                    }
                    "/revisit.png" -> {
                        revisitStarted.countDown()
                        releaseRevisit.await(10, TimeUnit.SECONDS)
                        Color.RED
                    }
                    else -> Color.GREEN
                }
                val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val data = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                bitmap.recycle()
                return newFixedLengthResponse(Response.Status.OK, "image/png", data.inputStream(), data.size.toLong())
            }
        }
        server.start()
        val activity = try { ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity }
        catch (failure: Throwable) { server.stop(); throw failure }
        val origin = "http://127.0.0.1:${server.listeningPort}"
        val surface = mutableStateOf(DashboardSurface.HOME)
        val artwork = mutableStateOf("$origin/red.png")
        val headerPixel = IntArray(2)
        val artworkPixel = IntArray(2)
        fun sample(at: IntArray): Int {
            val screenshot = requireNotNull(ins.uiAutomation.takeScreenshot())
            return try { screenshot.getPixel(at[0], at[1]) } finally { screenshot.recycle() }
        }
        fun awaitColor(label: String, matches: (Int) -> Boolean) {
            val end = SystemClock.uptimeMillis() + 6_000
            var color = 0
            while (SystemClock.uptimeMillis() < end) {
                color = sample(artworkPixel)
                if (matches(color)) return
                SystemClock.sleep(50)
            }
            fail("$label. Artwork pixel was ${Integer.toHexString(color)}")
        }
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val tab = remember { FocusRequester() }
                val body = remember { FocusRequester() }
                DiscoveryArtworkShell(overlay = true, phone = false, backdropBehindRows = true,
                    initialSceneArtwork = artwork.value, artworkIdentity = "scene fixture" to surface.value, header = {
                        DiscoveryTopNavigation(surface.value, "Fixture", tab, body, {}, {}, {}, {}, false, {}, null)
                    }, content = {
                        val publish = LocalPublishMobileGlassSource.current
                        val current = artwork.value
                        LaunchedEffect(publish, current) { publish(MobileGlassSource(current, Rect.Zero)) }
                    })
            } } }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val decor = activity.window.decorView
                val density = decor.resources.displayMetrics.density
                decor.getLocationOnScreen(headerPixel)
                val topInset = decor.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())?.top ?: 0
                artworkPixel[0] = headerPixel[0] + decor.width * 2 / 3
                artworkPixel[1] = headerPixel[1] + decor.height / 3
                headerPixel[0] += (31 * density).toInt()
                headerPixel[1] += topInset + (18 * density).toInt()
            }
            awaitColor("Home artwork appears") { Color.red(it) > 130 && Color.blue(it) < 35 }
            val glass = sample(headerPixel)
            NavigationAuditEvidence.capture("wide-glass-red")
            ins.runOnMainSync { artwork.value = "$origin/green.png" }
            assertTrue("The delayed request started", greenStarted.await(6, TimeUnit.SECONDS))
            assertTrue("Home retains its prepared image while the next one loads", Color.red(sample(artworkPixel)) > 130)
            ins.runOnMainSync { artwork.value = "$origin/blue.png" }
            awaitColor("Latest artwork wins") { Color.blue(it) > 130 && Color.red(it) < 35 }
            releaseGreen.countDown()
            repeat(6) {
                assertTrue("A superseded response cannot paint over the latest hero", Color.blue(sample(artworkPixel)) > 130)
                SystemClock.sleep(50)
            }
            assertEquals("The glass color is independent of the backdrop", glass, sample(headerPixel))
            NavigationAuditEvidence.capture("wide-glass-blue")
            ins.runOnMainSync {
                surface.value = DashboardSurface.MOVIES
                artwork.value = "$origin/green-next.png"
            }
            awaitColor("Movies shows its own artwork") { Color.green(it) > 130 && Color.blue(it) < 35 }
            assertEquals(glass, sample(headerPixel))
            ins.runOnMainSync {
                surface.value = DashboardSurface.HOME
                artwork.value = "$origin/revisit.png"
            }
            assertTrue("The new Home artwork is requested", revisitStarted.await(6, TimeUnit.SECONDS))
            ins.waitForIdleSync()
            awaitColor("The new route has painted its neutral backdrop while artwork is pending") {
                Color.red(it) < 50 && Color.green(it) < 50 && Color.blue(it) < 50
            }
            val pending = sample(artworkPixel)
            assertTrue("A revisit never exposes old Home or Movies artwork: ${Integer.toHexString(pending)}",
                Color.red(pending) < 50 && Color.green(pending) < 50 && Color.blue(pending) < 50)
            assertEquals(glass, sample(headerPixel))
            releaseRevisit.countDown()
            awaitColor("Home revisit uses its current selection") { Color.red(it) > 130 && Color.blue(it) < 35 }
            NavigationAuditEvidence.capture("wide-glass-revisited")
        } finally {
            releaseGreen.countDown()
            releaseRevisit.countDown()
            server.stop()
            ins.runOnMainSync { activity.finish() }
        }
    }

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
