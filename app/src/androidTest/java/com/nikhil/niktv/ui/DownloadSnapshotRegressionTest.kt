package com.nikhil.niktv.ui

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DownloadSnapshotRegressionTest {
    private fun entry() = OfflineMediaDownload(
        requestId = UUID.randomUUID().toString(), profileKey = "synthetic",
        catalogType = CatalogType.MOVIES, media = MediaItem("fixture", "Fixture", null, null)
    )
    @Test fun slowQueriesLeaveMainResponsiveAndDeduplicateEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.targetContext.packageName.endsWith(".regression"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val fixture = entry()
        var calls = 0
        var result = emptyMap<String, OfflineDownloadInfo>()
        try {
            instrumentation.runOnMainSync {
                scope.launch {
                    try {
                        result = queryOfflineDownloadSnapshot(listOf(fixture, fixture)) {
                            calls++
                            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                            started.countDown()
                            release.await(3, TimeUnit.SECONDS)
                            OfflineDownloadInfo(OfflineDownloadStatus.COMPLETE, 100f)
                        }
                    } catch (failure: Throwable) { error.set(failure) }
                    finally { finished.countDown() }
                }
            }
            assertTrue("Worker query must start", started.await(3, TimeUnit.SECONDS))
            val before = System.nanoTime()
            instrumentation.runOnMainSync { assertSame(Looper.getMainLooper(), Looper.myLooper()) }
            assertTrue("Main must respond while worker query is blocked", (System.nanoTime() - before) / 1000000 < 1000)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            assertEquals(1, calls)
            assertEquals(OfflineDownloadStatus.COMPLETE, result[fixture.key]?.status)
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun actualWorkManagerAndDownloadIndexReadRunOffMain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        val fixture = entry()
        val result = queryOfflineDownloadSnapshot(listOf(fixture)) {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            OfflineMediaDownloads.info(context, it.requestId, it.downloadId)
        }
        assertEquals(OfflineDownloadStatus.MISSING, result[fixture.key]?.status)
    }
}
