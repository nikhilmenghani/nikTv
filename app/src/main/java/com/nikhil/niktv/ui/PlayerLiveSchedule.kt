package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact, non-focusable guide rendered from metadata already on PlayingMedia. */
@Composable
internal fun PlayerLiveSchedule(item: MediaItem, compact: Boolean) {
    if (item.liveSchedule.isEmpty() && item.liveProgramme == null) return
    val now by produceState(System.currentTimeMillis(), item.id, item.liveSchedule) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val current = item.liveSchedule.firstOrNull { programme ->
        val start = programme.startTimeMillis
        val end = programme.endTimeMillis
        start != null && end != null && now in start until end
    } ?: item.liveProgramme
    val upcoming = item.liveSchedule
        .filter { (it.startTimeMillis ?: Long.MIN_VALUE) > now }
        .take(if (compact) 1 else 2)
    if (current == null && upcoming.isEmpty()) return
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    Column {
        current?.let {
            Text(
                "Now · ${it.title}",
                color = Color.White.copy(alpha = .88f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (upcoming.isNotEmpty()) {
            Row {
                Text("Next", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(5.dp))
                Text(
                    upcoming.joinToString("  •  ") { programme ->
                        val time = programme.startTimeMillis?.let { formatter.format(Date(it)) }
                        listOfNotNull(time, programme.title).joinToString(" · ")
                    },
                    color = Color.White.copy(alpha = .72f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
