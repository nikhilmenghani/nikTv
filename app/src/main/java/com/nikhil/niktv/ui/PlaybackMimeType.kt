package com.nikhil.niktv.ui

internal fun playbackMimeType(format: String, url: String): String? = when {
    format.contains("HLS", true) || format.equals("m3u8", true) || url.substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
    format.contains("DASH", true) || format.equals("mpd", true) || url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
    else -> null
}
