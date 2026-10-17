package com.nikhil.niktv.ui

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

internal fun manifestMimeType(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> "application/x-mpegURL"
    "application/dash+xml" -> "application/dash+xml"
    else -> null
}

/** Observe the response already being opened. No HEAD, second stream, or speculative decoder. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ManifestMimeDataSource(
    private val upstream: DataSource,
    private val target: Uri,
    private val detected: (String) -> Unit
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (dataSpec.uri == target) upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull()
            ?.let(::manifestMimeType)?.let(detected)
        return length
    }
}
