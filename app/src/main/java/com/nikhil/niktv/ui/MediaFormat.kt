package com.nikhil.niktv.ui

internal fun mediaFormatLabel(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('.', "").substringAfterLast('/').lowercase()
    return when (extension) {
        "m3u8" -> "HLS"
        "mpd" -> "DASH"
        "mp4" -> "MP4"
        "mkv" -> "MKV"
        "webm" -> "WEBM"
        "avi" -> "AVI"
        "mov" -> "MOV"
        "m4v" -> "M4V"
        "ts" -> "MPEG-TS"
        else -> if (url.startsWith("content://", ignoreCase = true)) "Downloaded video" else "Video stream"
    }
}
