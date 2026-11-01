package com.nikhil.niktv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class ExternalVideoRequestTest {
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private fun read(uri: String, mime: String? = "video/mp4") = ExternalVideoRequest.read(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime), resolver)
    @Test fun localFilesOpenDirectlyWithoutProviderIdentityAndPreserveTheirEncodedUri() {
        val request = read("file:///storage/emulated/0/Movies/My%20film.mp4")
        assertEquals("My film.mp4", request.title)
        val media = request.playing(12345, false)
        assertTrue(media.externalPlayback); assertFalse(media.offlinePlayback)
        assertEquals(request.uri.toString(), media.url)
        assertEquals(12345, media.resumePositionMillis); assertFalse(media.playWhenReady)
        assertTrue(media.profileKey.isBlank()); assertNull(media.alternateSource)
        assertTrue(media.streamRequestHeaders.isEmpty()); assertTrue(media.episodeQueue.isEmpty())
    }
    @Test fun opaqueDocumentUrisUsePublishedDisplayNameAndMimeWithoutReadingOrCopyingTheMovie() {
        val provider = MetadataProvider()
        ShadowContentResolver.registerProviderInternal("video-fixture", provider)
        val request = read("content://video-fixture/document/opaque", null)
        assertEquals("Episode 2.mkv", request.title); assertEquals("mkv", request.format)
        assertEquals(1, provider.queries)
    }
    @Test fun sharedVideoAndManifestMimeTypesAreRecognized() {
        val intent = Intent(Intent.ACTION_SEND).setType("video/webm").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fixture/video"))
        ShadowContentResolver.registerProviderInternal("fixture", MetadataProvider())
        assertEquals("content://fixture/video", ExternalVideoRequest.read(intent, resolver).uri.toString())
        assertEquals("HLS", read("https://example.test/opaque", "application/vnd.apple.mpegurl").format)
        assertEquals("DASH", read("https://example.test/opaque", "application/dash+xml").format)
        assertEquals("mkv", read("file:///storage/movie.mkv", "application/octet-stream").format)
    }
    @Test fun unrelatedIntentsSchemesAndNonVideoFilesAreRejected() {
        for ((uri, mime) in listOf("javascript:alert(1)" to "video/mp4", "intent://movie" to "video/mp4",
            "https://example.test/page" to "text/html", "file:///storage/private.txt" to "application/octet-stream")) {
            try { read(uri, mime); fail("Unsupported input accepted") } catch (_: IllegalArgumentException) { }
        }
        try { ExternalVideoRequest.read(Intent(Intent.ACTION_MAIN), resolver); fail() } catch (_: IllegalArgumentException) { }
    }
    private class MetadataProvider : ContentProvider() {
        var queries = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/x-matroska"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { queries++; addRow(arrayOf("Episode 2.mkv")) }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }
}
