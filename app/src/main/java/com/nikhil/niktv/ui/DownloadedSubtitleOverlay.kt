package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException

internal data class DownloadedSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Immutable lookup also works after a backward seek or a subtitle delay change. */
internal class DownloadedSubtitleTimeline(cues: List<DownloadedSubtitleCue>) {
    private val cues = cues.sortedBy { it.startMs }
    private val latestEnd = LongArray(cues.size).also { ends ->
        this.cues.forEachIndexed { index, cue ->
            ends[index] = maxOf(cue.endMs, if (index == 0) Long.MIN_VALUE else ends[index - 1])
        }
    }

    fun textAt(positionMs: Long, delayMs: Long = 0L): String? {
        val time = positionMs - delayMs
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= time) low = middle + 1 else high = middle
        }
        val active = ArrayList<String>()
        var index = low - 1
        while (index >= 0 && latestEnd[index] > time) {
            if (cues[index].endMs > time) active.add(cues[index].text)
            index--
        }
        return active.takeIf { it.isNotEmpty() }?.asReversed()?.joinToString("\n")
    }
}

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
    var timeline by remember(file?.absolutePath) {
        mutableStateOf(DownloadedSubtitleTimeline(emptyList()))
    }
    LaunchedEffect(file?.absolutePath) {
        timeline = withContext(Dispatchers.IO) {
            DownloadedSubtitleTimeline(file?.let(::readDownloadedSubtitle).orEmpty())
        }
    }
    if (!enabled) return
    val text = timeline.textAt(positionMs, delayMs) ?: return
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = appearance.textColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = appearance.textSizeSp.sp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = appearance.bottomPaddingDp.dp)
                .background(appearance.backgroundColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

internal fun readDownloadedSubtitle(file: File): List<DownloadedSubtitleCue> = try {
    val bytes = file.readBytes()
    val utf16 = bytes.size >= 2 && (
        (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
            (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte())
        )
    parseDownloadedSubtitleText(bytes.toString(if (utf16) Charsets.UTF_16 else Charsets.UTF_8))
} catch (_: IOException) {
    emptyList()
} catch (_: SecurityException) {
    emptyList()
}

private val subtitleTimestamp = Regex("(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)[,.](\\d{1,3})")
private val subtitleTiming = Regex("^\\s*(${subtitleTimestamp.pattern})[ \\t]*-->[ \\t]*(${subtitleTimestamp.pattern})(?:[ \\t]+.*)?$")
private val subtitleBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val subtitleMarkup = Regex("<[^>]+>")
private val subtitleAssStyle = Regex("\\{\\\\[^}]*\\}")

internal fun parseDownloadedSubtitleText(raw: String): List<DownloadedSubtitleCue> {
    return raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\\n\\s*\\n"))
        .mapNotNull { block ->
            val lines = block.lines().filter(String::isNotBlank)
            val header = lines.firstOrNull()?.trim() ?: return@mapNotNull null
            if (header == "NOTE" || header.startsWith("NOTE ") || header.startsWith("NOTE\t") ||
                header == "STYLE" || header == "REGION") return@mapNotNull null
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) return@mapNotNull null
            val timing = subtitleTiming.matchEntire(lines[timingIndex]) ?: return@mapNotNull null
            val start = parseSubtitleTime(timing.groupValues[1]) ?: return@mapNotNull null
            val end = parseSubtitleTime(timing.groupValues[6]) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val text = Parser.unescapeEntities(
                lines.drop(timingIndex + 1).joinToString("\n")
                    .replace(subtitleBreak, "\n")
                    .replace(subtitleMarkup, "")
                    .replace(subtitleAssStyle, ""), false
            ).trim()
            text.takeIf(String::isNotBlank)?.let { DownloadedSubtitleCue(start, end, it) }
        }
}

private fun parseSubtitleTime(value: String): Long? {
    val match = subtitleTimestamp.matchEntire(value) ?: return null
    val (hours, minutes, seconds, millis) = match.destructured
    val wholeHours = if (hours.isEmpty()) 0L else hours.toLongOrNull() ?: return null
    val remainder = minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + millis.padEnd(3, '0').toLong()
    if (wholeHours > (Long.MAX_VALUE - remainder) / 3_600_000L) return null
    return wholeHours * 3_600_000L + remainder
}
