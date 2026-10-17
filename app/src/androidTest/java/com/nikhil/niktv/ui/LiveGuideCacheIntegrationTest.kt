package com.nikhil.niktv.ui

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Metadata fixtures only; no accounts, networking, playback or normal app data. */
class LiveGuideCacheIntegrationTest {
    @Test fun providerSizedGuideStreamsPersistsAndAdvancesWithoutNetworkOrPlayer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providerFixture = InstrumentationRegistry.getArguments().getString("providerGuideFixture").equals("true", ignoreCase = true)
        val fixture = if (providerFixture) File(context.externalCacheDir, "provider-guide-fixture.json")
            else File(context.cacheDir, "synthetic-guide-fixture.json")
        val now = (System.currentTimeMillis() / 1000) * 1000
        try {
            if (!providerFixture) fixture.bufferedWriter().use { writer ->
                writer.write("{\"js\":{\"data\":{")
                for (channel in 1..8582) {
                    if (channel > 1) writer.write(",")
                    writer.write("\"$channel\":[")
                    val count = if (channel > 4001) 0 else if (channel <= 2885) 4 else 3
                    repeat(count) { index ->
                        if (index > 0) writer.write(",")
                        val start = now / 1000 - 60 + index * 120
                        writer.write("""{"id":"${channel}_$index","name":"Programme $channel/$index","start_timestamp":"$start","stop_timestamp":"${start+120}","time":"ignored formatted time","descr":"${"Skipped description ".repeat(8)}","duration":120,"mark_archive":0,"display_duration":120,"t_time":"ignored"}""")
                    }
                    writer.write("]")
                }
                writer.write("}},\"text\":\"synthetic provider debug\"}")
            }
            val runtime = Runtime.getRuntime()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val started = SystemClock.elapsedRealtime()
            val schedules = fixture.bufferedReader().use { readStreamingPortalGuide(it, now) }
            val elapsed = SystemClock.elapsedRealtime() - started
            val growth = runtime.totalMemory() - runtime.freeMemory() - before
            if (!providerFixture) {
                assertEquals(4001, schedules.size)
                assertEquals(14888, schedules.values.sumOf { it.size })
            }
            assertTrue(schedules.isNotEmpty() && schedules.size <= 5000)
            assertTrue(schedules.values.sumOf { it.size } <= 20_000)
            val key = if (providerFixture) "provider-fixture" else "synthetic-profile"
            val snapshot = boundedPortalGuide(key, now, schedules, now)
            val disk = PortalGuideDiskCache(context)
            disk.write(snapshot)
            val normalizedBytes = context.cacheDir.resolve("live-guide-v1").listFiles().orEmpty()
                .filter { it.name.endsWith(".json") }.maxByOrNull { it.lastModified() }?.length()
            val restored = disk.read(key)!!
            if (!providerFixture) {
                val channel = MediaItem("1", "Synthetic channel", null, "unchanged-command")
                    .withFreshLiveSchedule(restored.schedules.getValue("1"), now)
                assertEquals("Programme 1/0", channel.currentLiveProgramme(now)?.title)
                assertEquals("Programme 1/1", channel.currentLiveProgramme(now + 60_000)?.title)
                assertNull(channel.currentLiveProgramme(now + 10 * 60_000))
            } else {
                assertFalse(restored.schedules.containsKey("71505")) // Raw snapshot has no English movie guide for this channel.
                assertTrue(restored.schedules.any { (id, rows) ->
                    MediaItem(id, "Fixture channel", null, null).withFreshLiveSchedule(rows, now).currentLiveProgramme(now) != null
                })
            }
            assertNull(disk.read("different-profile"))
            Log.i("NikTvGuideFixture", "providerFixture=$providerFixture bytes=${fixture.length()} normalizedBytes=$normalizedBytes channels=${schedules.size} rows=${schedules.values.sumOf { it.size }} parseMs=$elapsed javaHeapGrowthBytes=$growth pssKb=${Debug.getPss()}")
            Unit
        } finally { fixture.delete() }
    }
}
