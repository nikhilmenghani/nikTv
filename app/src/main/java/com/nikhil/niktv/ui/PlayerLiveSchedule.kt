package com.nikhil.niktv.ui

import android.view.Gravity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PlayerLiveScheduleSummary(
    item: MediaItem,
    compact: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
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
    val next = item.liveSchedule.filter { (it.startTimeMillis ?: Long.MIN_VALUE) > now }.take(2)
    val time = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val entries = listOfNotNull(current?.let { "NOW" to it }) + next.map { "NEXT" to it }
    val expandRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100L)
        runCatching { expandRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = if (compact) 250.dp else 310.dp, max = if (compact) 310.dp else 400.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = Color(0xF21A1A1A),
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
            val dialogView = LocalView.current
            SideEffect { (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END) }
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (entries.isEmpty()) {
                    Text("Programme information is unavailable.", color = Color.White.copy(.65f))
                }
                entries.forEach { (label, programme) ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Text(
                            label,
                            modifier = Modifier.width(if (compact) 34.dp else 40.dp),
                            color = if (label == "NOW") Color(0xFFFF6B76) else Color.White.copy(.48f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Column(Modifier.weight(1f)) {
                            programme.startTimeMillis?.let {
                                Text(time.format(Date(it)), color = Color.White.copy(.55f), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                programme.title,
                                color = Color.White.copy(if (label == "NOW") .92f else .74f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onExpand,
                modifier = Modifier.focusRequester(expandRequester)
                    .focusProperties { right = closeRequester }
                    .playerControlFocus(RoundedCornerShape(12.dp)) {},
                shape = RoundedCornerShape(12.dp)
            ) { Text("Full guide") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeRequester)
                    .focusProperties { left = expandRequester }
                    .playerControlFocus(RoundedCornerShape(12.dp)) {},
                shape = RoundedCornerShape(12.dp)
            ) { Text("Close") }
        }
    )
}

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
    val dateTimeFormatter = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dayKeyFormatter = SimpleDateFormat("yyyyMMdd", Locale.US)
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
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    softWrap = true
                )
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
                            val startDate = programme.startTimeMillis?.let(::Date)
                            val endDate = programme.endTimeMillis?.let(::Date)
                            val range = when {
                                startDate != null && endDate != null -> {
                                    val endText = if (dayKeyFormatter.format(startDate) == dayKeyFormatter.format(endDate)) {
                                        timeFormatter.format(endDate)
                                    } else {
                                        dateTimeFormatter.format(endDate)
                                    }
                                    "${dateTimeFormatter.format(startDate)} – $endText"
                                }
                                startDate != null -> dateTimeFormatter.format(startDate)
                                endDate != null -> "Until ${dateTimeFormatter.format(endDate)}"
                                else -> "Time unavailable"
                            }
                            Text(
                                if (isCurrent) "NOW  ·  $range" else range,
                                color = if (isCurrent) Color(0xFFFF8A94) else Color.White.copy(.58f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                programme.title,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                softWrap = true
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
            ) { Text("Back") }
        }
    )
}
