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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class DownloadedSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

internal enum class SubtitleAppearancePreset(
    val label: String,
    val textSizeSp: Int,
    val textColor: Color,
    val backgroundColor: Color,
    val bottomPaddingDp: Int
) {
    COMPACT("Compact", 16, Color.White, Color.Black.copy(alpha = .72f), 20),
    STANDARD("Standard", 20, Color.White, Color.Black.copy(alpha = .78f), 28),
    LARGE("Large", 26, Color.White, Color.Black.copy(alpha = .82f), 36),
    HIGH_CONTRAST("High contrast", 22, Color.Yellow, Color.Black, 28),
    HIGH("Higher", 20, Color.White, Color.Black.copy(alpha = .78f), 92);

    fun next(): SubtitleAppearancePreset = entries[(ordinal + 1) % entries.size]
}

@Composable
internal fun DownloadedSubtitleOverlay(
    file: File?, positionMs: Long, delayMs: Long, enabled: Boolean,
    appearance: SubtitleAppearancePreset = SubtitleAppearancePreset.STANDARD,
    modifier: Modifier = Modifier
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
            color = appearance.textColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = appearance.textSizeSp.sp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = appearance.bottomPaddingDp.dp)
                .background(appearance.backgroundColor, RoundedCornerShape(6.dp))
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
