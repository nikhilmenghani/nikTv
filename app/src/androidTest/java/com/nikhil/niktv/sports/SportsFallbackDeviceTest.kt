package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SportsFallbackDeviceTest {
    @Test fun failedNativeSourceAutomaticallyAdvancesToWorkingVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val audit = Json.parseToJsonElement(File(context.filesDir, "sports-stream-audit.json").readText()).jsonObject
        assertTrue("Exhaustive tablet audit must finish first", audit["complete"]!!.jsonPrimitive.boolean)
        val passed = audit["results"]!!.jsonArray.map { it.jsonObject }.first { it.text("result") == "PASS" }
        val repository = SportsRepository(country = { sportsDeviceCountry(context) })
        val working = repository.streams(passed.text("slug"))[passed["sourceIndex"]!!.jsonPrimitive.int]
        val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(context)::resolve)
        val activity = instrumentation.startActivitySync(Intent(context, SportsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SportsPlaybackController(scope, { emptyList() }, resolver::resolve)
        val frame = CountDownLatch(1)
        try {
            LocalSportsPages(mapOf("/broken.m3u8" to "<html>This is not a video manifest</html>")).use { pages ->
                instrumentation.runOnMainSync {
                    controller.start("fixture", listOf(SportsStream(pages.base + "/broken.m3u8", "Broken fixture"), working))
                    activity.setContent {
                        val state by controller.state.collectAsState()
                        MaterialTheme { SportsPlayer(state, "Automatic fallback test", {}, controller::failed, controller::retry, onFirstFrame = { frame.countDown() }) }
                    }
                }
                assertTrue("Video must render after automatic source fallback", frame.await(40, TimeUnit.SECONDS))
                assertEquals(1, controller.state.value.selectedIndex)
                assertEquals(2, controller.state.value.attempts)
                assertNull(controller.state.value.error)
                val end = SystemClock.elapsedRealtime() + 3000
                while (SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
                assertEquals("Working source remains selected", 1, controller.state.value.selectedIndex)
            }
        } finally {
            instrumentation.runOnMainSync { controller.stop(); scope.cancel(); activity.finish() }
        }
    }
}
