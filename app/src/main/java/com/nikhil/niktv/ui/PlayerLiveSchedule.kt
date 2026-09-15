package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact guide rendered from the playing channel's already-fetched metadata. */
@Composable
internal fun PlayerLiveScheduleOverlay(item: MediaItem, onDismiss: () -> Unit) {
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
    val programmes = item.liveSchedule.filter { (it.endTimeMillis ?: Long.MAX_VALUE) > now }
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    val requesters = remember(programmes) { programmes.map { FocusRequester() } }
    val closeRequester = remember { FocusRequester() }
    LaunchedEffect(programmes) {
        kotlinx.coroutines.delay(100L)
        runCatching { (requesters.firstOrNull() ?: closeRequester).requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 300.dp, max = 430.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color(0xF21A1A1A),
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Programme guide", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(.62f))
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (programmes.isEmpty()) {
                    item {
                        Text(
                            "No programme information was returned for this channel.",
                            color = Color.White.copy(alpha = .68f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
                itemsIndexed(programmes) { index, programme ->
                    val isCurrent = programme == current
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requesters[index])
                            .focusProperties {
                                if (index > 0) up = requesters[index - 1]
                                down = requesters.getOrNull(index + 1) ?: closeRequester
                            }
                            .focusable()
                            .remoteFocusFrame(RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) Color(0xFF452126) else Color.White.copy(.055f),
                        border = BorderStroke(1.dp, if (isCurrent) Color(0xFFE50914) else Color.White.copy(.10f))
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                            val range = listOfNotNull(
                                programme.startTimeMillis?.let { formatter.format(Date(it)) },
                                programme.endTimeMillis?.let { formatter.format(Date(it)) }
                            ).joinToString(" – ")
                            Text(
                                if (isCurrent) "NOW  ·  $range" else range,
                                color = if (isCurrent) Color(0xFFFF8A94) else Color.White.copy(.58f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                programme.title,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeRequester).playerControlFocus(RoundedCornerShape(12.dp)) {},
                shape = RoundedCornerShape(12.dp)
            ) { Text("Close") }
        }
    )
}
