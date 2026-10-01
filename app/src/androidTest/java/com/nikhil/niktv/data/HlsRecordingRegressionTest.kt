package com.nikhil.niktv.data

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.PlaybackRegressionActivity
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class HlsRecordingRegressionTest {
    @Test fun extinfAccountingAndUnsupportedSegmentsOnDevice() {
        val parsed = parseHlsRecordingPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:1.25,\na.ts\n#EXTINF:2.5,\nb.ts")
        assertEquals(3750L, parsed.segments.sumOf { it.durationMillis })
        try { parseHlsRecordingPlaylist("#EXTM3U\n#EXT-X-BYTERANGE:12@0\n#EXTINF:1,\na.ts"); fail() }
        catch (_: UnsupportedHlsRecordingException) { }
    }
    private fun rejectAndClean(tag: String, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        val title = "NikTvRegression-" + UUID.randomUUID().toString()
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n$tag\n#EXTINF:1.25,\na.ts\n#EXT-X-ENDLIST"
        val bytes = playlist.toByteArray()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrBlank()) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            } catch (_: java.io.IOException) { }
        }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun ownOutputs(): List<android.net.Uri> {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            return context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$title-%"), null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
            }.orEmpty()
        }
        try {
            instrumentation.runOnMainSync { LiveTvRecorder.start(activity, title, "http://127.0.0.1:${server.localPort}/fixture.m3u8") }
            val deadline = System.currentTimeMillis() + 15000
            while (LiveTvRecorder.state.value.error == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val state = LiveTvRecorder.state.value
            assertFalse("Rejected recording must stop", state.active)
            assertTrue("Clear rejection error expected: ${state.error}", state.error?.contains(expected) == true)
            assertEquals("Rejected playlist must append no segment bytes", 0L, state.bytesWritten)
            assertTrue("Rejected recorder output must be deleted", ownOutputs().isEmpty())
        } finally {
            context.stopService(Intent(context, LiveTvRecordingService::class.java))
            // Only this test's uniquely named synthetic artifacts can be removed.
            ownOutputs().forEach { context.contentResolver.delete(it, null, null) }
            server.close()
            worker.join(1000)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun byteRangePlaylistIsRejectedAndOwnPendingOutputRemoved() = rejectAndClean("#EXT-X-BYTERANGE:16@0", "Byte-range")
    @Test fun encryptedPlaylistIsRejectedAndOwnPendingOutputRemoved() = rejectAndClean("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "Encrypted")
}
