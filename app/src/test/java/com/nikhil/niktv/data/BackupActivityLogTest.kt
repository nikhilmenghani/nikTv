package com.nikhil.niktv.data

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupActivityLogTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("backup_activity", 0).edit().clear().commit() }

    @Test fun recordsOutcomeAndTimestampAndReplaysHistoryToViewer() = runBlocking {
        val start = System.currentTimeMillis()
        assertEquals(3, BackupActivityLog.track(context, "Catalog restore", success = { "Merged $it snapshots" }) { 3 })
        val events = BackupActivityLog.observe(context).first()
        assertEquals(listOf("Completed", "Started"), events.map { it.status })
        assertEquals("Merged 3 snapshots", events.first().detail)
        assertTrue(events.all { it.timestamp >= start })
        assertEquals(events, BackupActivityLog.read(context))
    }

    @Test fun failedAndCancelledOperationsAreNeverReportedAsCompleted() = runBlocking {
        try { BackupActivityLog.track(context, "Export") { throw java.io.IOException("https://host/user/password") } }
        catch (_: java.io.IOException) { }
        assertEquals("Failed", BackupActivityLog.read(context).first().status)
        assertFalse(BackupActivityLog.read(context).first().detail.contains("password"))
        try { BackupActivityLog.track(context, "Restore") { throw CancellationException() } }
        catch (_: CancellationException) { }
        assertEquals("Cancelled", BackupActivityLog.read(context).first().status)
        assertFalse(BackupActivityLog.read(context).any { it.status == "Completed" })
    }

    @Test fun boundedLogKeepsNewestEventsAndDoesNotRetainSensitiveExceptionMessages() {
        repeat(125) { BackupActivityLog.record(context, "Backup $it", "Queued") }
        val events = BackupActivityLog.read(context)
        assertEquals(100, events.size)
        assertEquals("Backup 124", events.first().operation)
        assertEquals("Backup 25", events.last().operation)
        val message = BackupActivityLog.failureSummary(IllegalStateException("GitHub 403 https://private/token-secret"))
        assertTrue(message.contains("403"))
        assertFalse(message.contains("token-secret"))
    }
}
