package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class DownloadedSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

@Composable
internal fun DownloadedSubtitleOverlay(
    file: File?, positionMs: Long, delayMs: Long, enabled: Boolean, modifier: Modifier = Modifier
) {
    val cues by produceState(emptyList<DownloadedSubtitleCue>(), file?.absolutePath) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf(File::exists)?.let(::parseDownloadedSubtitle).orEmpty()
        }
    }
    if (!enabled) return
    val cueTime = positionMs - delayMs
    val cue = cues.firstOrNull { cueTime in it.startMs until it.endMs } ?: return
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Text(
            cue.text,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

private fun parseDownloadedSubtitle(file: File): List<DownloadedSubtitleCue> {
    val timestamp = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.:](\\d{3})")
    return file.readText().replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\\n\\s*\\n"))
        .mapNotNull { block ->
            val lines = block.lines().filter(String::isNotBlank)
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) return@mapNotNull null
            val times = lines[timingIndex].split("-->")
            val start = times.getOrNull(0)?.let { parseSubtitleTime(it, timestamp) } ?: return@mapNotNull null
            val end = times.getOrNull(1)?.let { parseSubtitleTime(it, timestamp) } ?: return@mapNotNull null
            val text = lines.drop(timingIndex + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "").trim()
            text.takeIf(String::isNotBlank)?.let { DownloadedSubtitleCue(start, end, it) }
        }
}

private fun parseSubtitleTime(value: String, pattern: Regex): Long? {
    val match = pattern.find(value) ?: return null
    val (hours, minutes, seconds, millis) = match.destructured
    return hours.toLong() * 3_600_000L + minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + millis.toLong()
}
