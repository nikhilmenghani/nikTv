package com.nikhil.niktv.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nikhil.niktv.model.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LanMediaServerTest {
    private lateinit var file: File
    private lateinit var server: LanSelectedMediaServer
    private var failure: Exception? = null
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        file = File.createTempFile("lan-movie", ".mp4", context.cacheDir).apply { writeBytes(bytes) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("lan.fixture", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "video/mp4"
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, where: String?, args: Array<out String>?) = 0
        }.apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "lan.fixture"; exported = true }) })
        val media = PlayingMedia(MediaItem("fixture", "Fixture", null, null), "content://lan.fixture/video", CatalogType.MOVIES)
        server = LanSelectedMediaServer(context, "127.0.0.1", media, "127.0.0.1") { failure = it }
    }
    @After fun cleanup() { server.close(); file.delete() }
    private fun request(path: String = server.url, method: String = "GET", range: String? = null) =
        (URL(path).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000; requestMethod = method; range?.let { setRequestProperty("Range", it) } }
    @Test fun actualResponseSupportsFullAndPartialFileReads() {
        request().let { response -> try { val code = response.responseCode; assertEquals(failure?.stackTraceToString(), 200, code); assertArrayEquals(bytes, response.inputStream.use { it.readBytes() }) } finally { response.disconnect() } }
        request(range = "bytes=3000-3099").let { response -> try {
            assertEquals(206, response.responseCode); assertEquals("bytes 3000-3099/4096", response.getHeaderField("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(3000, 3100), response.inputStream.use { it.readBytes() })
        } finally { response.disconnect() } }
    }
    @Test fun headAndSuffixWorkWithoutCopyingTheMovie() {
        request(method = "HEAD").let { response -> try { assertEquals(200, response.responseCode); assertEquals("4096", response.getHeaderField("Content-Length")); assertEquals(-1, response.inputStream.read()) } finally { response.disconnect() } }
        request(range = "bytes=-32").let { response -> try { assertEquals(206, response.responseCode); assertArrayEquals(bytes.takeLast(32).toByteArray(), response.inputStream.use { it.readBytes() }) } finally { response.disconnect() } }
    }
    @Test fun invalidRangesAndUnselectedRoutesCannotReadFiles() {
        request(range = "bytes=9999-").let { response -> try { assertEquals(416, response.responseCode); assertEquals("bytes */4096", response.getHeaderField("Content-Range")) } finally { response.disconnect() } }
        request(server.url.substringBeforeLast('/') + "/another-file").let { response -> try { assertEquals(404, response.responseCode) } finally { response.disconnect() } }
    }
}
