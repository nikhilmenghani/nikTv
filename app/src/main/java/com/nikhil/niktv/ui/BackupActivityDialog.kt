package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.data.BackupActivityLog
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
internal fun BackupActivityDialog(close: () -> Unit) {
    val context = LocalContext.current
    val events by remember(context) { BackupActivityLog.observe(context) }.collectAsState(initial = BackupActivityLog.read(context))
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backup and restore activity", style = MaterialTheme.typography.titleLarge)
                Text("Latest 100 events on this device · ${TimeZone.getDefault().displayName}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (events.isEmpty()) Text("No backup or restore activity recorded yet.")
                else LazyColumn(
                    Modifier.heightIn(max = 380.dp).weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(12.dp)).focusable(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(event.operation, style = MaterialTheme.typography.titleSmall)
                                Text("${event.status} · ${formatter.format(Date(event.timestamp))}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (event.status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NikTvTextActionButton(onClick = close) { Text("Close") }
                }
            }
        }
    }
}
