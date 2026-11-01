package com.nikhil.niktv

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import java.util.Locale

/** Only Android's supplied read grant is used; no paths are guessed or files copied. */
internal data class ExternalVideoRequest(val uri: Uri, val title: String, val format: String) {
    fun playing(position: Long = 0, requested: Boolean = true) = PlayingMedia(
        MediaItem("external:$uri", title, null, null), uri.toString(), CatalogType.MOVIES,
        resumePositionMillis = position.coerceAtLeast(0), progressKey = "external:$uri",
        directFullscreen = true, playbackFormat = format, playWhenReady = requested, externalPlayback = true)

    companion object {
        private val extensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "mpg", "mpeg", "3gp", "3g2", "ts", "m2ts", "mts", "vob", "wmv", "flv", "ogv", "m3u8", "mpd")
        fun read(intent: Intent, resolver: ContentResolver): ExternalVideoRequest {
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                else -> null
            } ?: throw IllegalArgumentException("No video was selected.")
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("content", "file", "http", "https")) { "This video link is not supported." }
            require(uri.scheme != "content" || !uri.authority.isNullOrBlank()) { "This file link is not valid." }
            require(uri.scheme != "file" || uri.path?.startsWith('/') == true) { "This file link is not valid." }
            require(uri.scheme !in setOf("http", "https") || !uri.host.isNullOrBlank()) { "This video link is not valid." }
            var title = uri.lastPathSegment.orEmpty()
            var mime = intent.type.orEmpty().substringBefore(';').lowercase(Locale.ROOT)
            if (uri.scheme == "content") {
                if (mime.isBlank() || mime == "application/octet-stream") mime = resolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) title = cursor.getString(index).orEmpty()
                }
            }
            val extension = title.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val format = when {
                mime in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl") || extension == "m3u8" -> "HLS"
                mime == "application/dash+xml" || extension == "mpd" -> "DASH"
                mime.startsWith("video/") || mime in setOf("application/mp4", "application/x-matroska") -> extension.ifBlank { mime.substringAfter('/') }
                mime in setOf("", "application/octet-stream") && extension in extensions -> extension
                else -> throw IllegalArgumentException("Select a video file to open in NikTV.")
            }
            val display = title.filterNot { it.isISOControl() }.trim().take(200).ifBlank { "Video" }
            return ExternalVideoRequest(uri, display, format)
        }
    }
}
