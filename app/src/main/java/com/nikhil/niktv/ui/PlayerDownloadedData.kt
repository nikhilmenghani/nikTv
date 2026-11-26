package com.nikhil.niktv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Verified file bytes, independent of the video's time/bit-rate mapping. */
internal data class PlaybackDownloadedData(
    val totalBytes: Long,
    val downloadedBytes: Long,
    val ranges: List<LongRange> = emptyList(),
    val storageWarning: String? = null
) {
    val percent: Int? get() {
        if (totalBytes <= 0L) return null
        val downloaded = downloadedBytes.coerceIn(0L, totalBytes)
        // Double rounding on very large files must not announce completion early.
        return (downloaded.toDouble() / totalBytes.toDouble() * 100.0).toInt()
            .coerceIn(0, if (downloaded == totalBytes) 100 else 99)
    }
}

internal val LocalPlaybackDownloadProgress = staticCompositionLocalOf<StateFlow<PlaybackDownloadedData?>?> { null }

internal fun normalizedDownloadedByteRanges(totalBytes: Long, ranges: List<LongRange>): List<LongRange> {
    if (totalBytes <= 0L) return emptyList()
    val normalized = ranges.mapNotNull { range ->
        if (range.isEmpty() || range.last < 0L || range.first >= totalBytes) null
        else range.first.coerceAtLeast(0L)..range.last.coerceAtMost(totalBytes - 1L)
    }.sortedBy { it.first }
    val merged = mutableListOf<LongRange>()
    for (range in normalized) {
        val prior = merged.lastOrNull()
        if (prior != null && range.first <= prior.last + 1L)
            merged[merged.lastIndex] = prior.first..maxOf(prior.last, range.last)
        else merged += range
    }
    return merged
}

@Composable
internal fun rememberPlayerDownloadedData(): State<PlaybackDownloadedData?>? =
    LocalPlaybackDownloadProgress.current?.collectAsStateWithLifecycle()

private val downloadedRangeColor = Color(0xFF75C9AB)

@Composable
internal fun PlayerDownloadedDataLabel(data: State<PlaybackDownloadedData?>, modifier: Modifier = Modifier) {
    val download = data.value
    val percent = download?.percent
    val warning = download?.storageWarning?.takeIf(String::isNotBlank)
    if (percent == null && warning == null) return
    Text(
        warning ?: "Downloaded $percent%",
        modifier.semantics {
            contentDescription = listOfNotNull(
                percent?.let { "Downloaded $it percent; positions are approximate" }, warning
            ).joinToString(". ")
        },
        style = MaterialTheme.typography.labelSmall, color = if (warning != null) Color(0xFFFFCC80) else downloadedRangeColor,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

/** Verified byte offsets approximate time on files with uneven bit rates. */
@Composable
internal fun PlayerDownloadedTrackMarks(data: State<PlaybackDownloadedData?>, modifier: Modifier) {
    val download = data.value ?: return
    val total = download.totalBytes.takeIf { it > 0L }?.toDouble() ?: return
    val ranges = remember(download.totalBytes, download.ranges) {
        normalizedDownloadedByteRanges(download.totalBytes, download.ranges)
    }
    Canvas(modifier) {
        for (range in ranges) {
            val start = (range.first / total * size.width).toFloat()
            val end = ((range.last + 1L) / total * size.width).toFloat()
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - end else start
            drawRect(downloadedRangeColor, Offset(left, 0f), Size(end - start, size.height))
        }
    }
}
