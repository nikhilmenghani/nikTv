package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PlaybackSidecarSubtitle
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val MAX_SIDECAR_BYTES = 2L * 1024 * 1024
private val SIDECAR_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

private fun sidecarCachePrefix(subtitle: PlaybackSidecarSubtitle): String {
    val key = MessageDigest.getInstance("SHA-256")
        .digest("${subtitle.id}\n${subtitle.url}".toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    return "sidecar-$key-"
}

internal fun playbackSidecarMatches(path: String?, subtitle: PlaybackSidecarSubtitle): Boolean =
    path != null && File(path).name.startsWith(sidecarCachePrefix(subtitle))

internal fun playbackSubtitleDisplayName(file: File, sidecars: List<PlaybackSidecarSubtitle>): String =
    sidecars.firstOrNull { playbackSidecarMatches(file.absolutePath, it) }?.label
        ?: file.name.replace(Regex("^sidecar-[a-f0-9]{24}--?\\d+-"), "")

/** Fetch only when selected, with bounded disk/network work and cancellation ownership. */
internal class PlaybackSidecarSubtitleLoader(
    private val directory: File,
    private val http: OkHttpClient = sharedHttp
) {
    suspend fun load(subtitle: PlaybackSidecarSubtitle): File {
        val url = subtitle.url.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty()) { "This subtitle link is invalid." }
        require(subtitle.id.isNotBlank()) { "This subtitle has no identity." }
        val basename = subtitle.fileName.substringAfterLast('/').substringAfterLast('\\')
        val extension = basename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SIDECAR_EXTENSIONS) { "This subtitle format is not supported." }
        val safeName = basename.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").takeLast(160)
            .takeIf { it.isNotBlank() } ?: "subtitle.$extension"
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    var pending: File? = null
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Included subtitle returned HTTP ${it.code}.")
                            val body = it.body
                            if (body.contentLength() > MAX_SIDECAR_BYTES) throw IOException("This subtitle exceeds the 2 MiB limit.")
                            check(directory.isDirectory || directory.mkdirs()) { "Subtitle storage is unavailable." }
                            val file = File.createTempFile(sidecarCachePrefix(subtitle), "-$safeName", directory)
                            pending = file
                            var received = 0L
                            body.byteStream().use { input -> file.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Subtitle loading was cancelled.")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received > MAX_SIDECAR_BYTES) throw IOException("This subtitle exceeds the 2 MiB limit.")
                                    output.write(buffer, 0, count)
                                }
                            } }
                            if (received == 0L) throw IOException("This subtitle file is empty.")
                            if (extension in setOf("srt", "vtt") && readDownloadedSubtitle(file).isEmpty())
                                throw IOException("This included subtitle has no readable SRT or VTT cues.")
                            // A cancellation after I/O but before delivery must not leak the file.
                            continuation.resume(file) { _, discarded, _ -> discarded.delete() }
                            pending = null
                        }
                    } catch (error: Exception) {
                        pending?.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    companion object {
        private val sharedHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()
    }
}
