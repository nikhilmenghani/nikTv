package com.nikhil.niktv.ui

internal fun playbackMimeType(format: String, url: String): String? = when {
    // Download exports retain their conversion label for display, but the saved
    // bytes are MP4. Match the output before inspecting the original HLS format.
    format.trim().equals("HLS → MP4", true) -> "video/mp4"
    format.contains("HLS", true) || format.equals("m3u8", true) || url.substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
    format.contains("DASH", true) || format.equals("mpd", true) || url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
    format.equals("SS", true) || format.equals("ism", true) || format.equals("ismv", true) -> "application/vnd.ms-sstr+xml"
    else -> null
}
