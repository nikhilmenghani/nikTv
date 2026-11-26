package com.nikhil.niktv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Collect here so byte updates never rebuild the player or its media/seek state. */
@Composable
internal fun PlayerDownloadedDataIndicator(modifier: Modifier = Modifier) {
    val progress = LocalPlaybackDownloadProgress.current ?: return
    val data by progress.collectAsStateWithLifecycle()
    val ranges = remember(data?.totalBytes, data?.ranges) {
        normalizedDownloadedByteRanges(data?.totalBytes ?: 0L, data?.ranges.orEmpty())
    }
    val label = "Downloaded · ${data?.percent?.let { "$it%" } ?: "—"}"
    val warning = data?.storageWarning?.takeIf { it.isNotBlank() }
    val accent = Color(0xFF75C9AB)
    Column(modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 8.dp)
        .semantics { contentDescription = "Downloaded data: ${data?.percent?.let { "$it percent" } ?: "waiting"}" +
            (warning?.let { ". $it" } ?: "") }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
            if (warning != null) Text(warning, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFCC80), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))) {
            drawRect(Color.White.copy(alpha = .12f))
            val total = data?.totalBytes?.takeIf { it > 0L }?.toDouble() ?: return@Canvas
            for (range in ranges) {
                val left = (range.first.toDouble() / total * size.width).toFloat()
                val right = ((range.last + 1L).toDouble() / total * size.width).toFloat()
                drawRect(accent, Offset(left, 0f), Size((right - left).coerceAtLeast(0f), size.height))
            }
        }
    }
}
