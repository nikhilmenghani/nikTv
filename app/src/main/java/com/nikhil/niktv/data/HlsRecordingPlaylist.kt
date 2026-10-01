package com.nikhil.niktv.data

internal class UnsupportedHlsRecordingException(message: String) : IllegalArgumentException(message)
internal data class HlsRecordingSegment(val uri: String, val durationMillis: Long)
internal data class HlsRecordingPlaylist(
    val segments: List<HlsRecordingSegment>, val pollingIntervalMillis: Long, val finished: Boolean
)

internal fun validateHlsRecordingFeatures(lines: List<String>) {
    if (lines.firstOrNull() != "#EXTM3U") throw UnsupportedHlsRecordingException("Invalid HLS recording playlist.")
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-BYTERANGE:") -> throw UnsupportedHlsRecordingException("Byte-range HLS recording is not supported.")
            line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                if (!Regex("(?:^|,)METHOD=NONE(?:,|$)").containsMatchIn(line.substringAfter(':')))
                    throw UnsupportedHlsRecordingException("Encrypted HLS recording is not supported.")
            }
            line.startsWith("#EXT-X-MAP:") -> throw UnsupportedHlsRecordingException("Fragmented HLS recording is not supported.")
            line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:") ||
                line == "#EXT-X-I-FRAMES-ONLY" || line == "#EXT-X-GAP" ->
                throw UnsupportedHlsRecordingException("This HLS playlist format is not supported for recording.")
        }
    }
}

internal fun parseHlsRecordingPlaylist(text: String): HlsRecordingPlaylist {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    validateHlsRecordingFeatures(lines)
    val segments = mutableListOf<HlsRecordingSegment>()
    var duration: Long? = null
    for (line in lines) {
        when {
            line.startsWith("#EXTINF:") -> {
                if (duration != null) throw UnsupportedHlsRecordingException("HLS segment URI is missing.")
                val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                if (seconds == null || !seconds.isFinite() || seconds <= 0 || seconds > 86400)
                    throw UnsupportedHlsRecordingException("HLS segment has an invalid duration.")
                duration = (seconds * 1000).toLong().coerceAtLeast(1)
            }
            !line.startsWith("#") -> {
                val segmentDuration = duration ?: throw UnsupportedHlsRecordingException("HLS segment duration is missing.")
                segments += HlsRecordingSegment(line, segmentDuration)
                duration = null
            }
        }
    }
    if (duration != null || segments.isEmpty()) throw UnsupportedHlsRecordingException("HLS playlist has no complete media segments.")
    if (segments.map { it.uri }.distinct().size != segments.size)
        throw UnsupportedHlsRecordingException("HLS recording requires distinct segment URIs.")
    val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
        ?.substringAfter(':')?.toLongOrNull()?.coerceIn(1, 10) ?: 3L
    return HlsRecordingPlaylist(segments, target * 500, lines.any { it == "#EXT-X-ENDLIST" })
}
