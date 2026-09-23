package com.nikhil.niktv.data

/** Chooses a complete segment covering the player's position, with room for playlist latency. */
internal object HlsRecordingStartSelector {
    fun segmentIndex(playlistLines: List<String>, requestedLiveOffsetMillis: Long?): Int {
        val durations = buildList {
            var nextDurationMillis: Long? = null
            for (line in playlistLines) {
                when {
                    line.startsWith("#EXTINF:") -> {
                        nextDurationMillis = line.substringAfter(':').substringBefore(',')
                            .toDoubleOrNull()?.let { (it * 1_000).toLong() }
                    }
                    line.isNotBlank() && !line.startsWith('#') -> {
                        add(nextDurationMillis?.coerceAtLeast(1L) ?: 0L)
                        nextDurationMillis = null
                    }
                }
            }
        }
        if (durations.isEmpty()) return 0

        val targetDuration = playlistLines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
            ?.substringAfter(':')?.toLongOrNull()?.coerceAtLeast(1L)?.times(1_000L)
            ?: durations.filter { it > 0L }.maxOrNull()
            ?: 3_000L
        // Both ExoPlayer and VLC usually render behind the newest HLS segment.
        // Media3 supplies its measured offset; VLC uses this conservative fallback.
        val targetOffset = requestedLiveOffsetMillis?.coerceAtLeast(0L)
            ?: targetDuration * DEFAULT_LIVE_SEGMENTS_BEHIND

        var offsetFromEdge = 0L
        for (index in durations.indices.reversed()) {
            offsetFromEdge += durations[index].takeIf { it > 0L } ?: targetDuration
            if (offsetFromEdge >= targetOffset) {
                // The recorder fetches a newer playlist than the player loaded. Keep one
                // preceding segment so the first seconds visible at Record are not lost.
                return if (targetOffset >= targetDuration) (index - 1).coerceAtLeast(0) else index
            }
        }
        return 0
    }

    private const val DEFAULT_LIVE_SEGMENTS_BEHIND = 3L
}
