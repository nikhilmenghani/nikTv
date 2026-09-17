package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CatalogProfileSettings(profiles: List<PortalProfile>, active: PortalProfile?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedId by remember(active) { mutableStateOf(active?.let(CatalogScanPreferences::id)) }
    val profile = profiles.firstOrNull { CatalogScanPreferences.id(it) == selectedId } ?: active ?: profiles.firstOrNull()
    if (profile == null) return
    val id = CatalogScanPreferences.id(profile)
    var chooseProfile by remember { mutableStateOf(false) }
    var hours by remember(id) { mutableIntStateOf(CatalogScanPreferences.hours(context, id)) }
    val status by remember(id) { CatalogScanPreferences.observe(context, id) }
        .collectAsState(initial = CatalogScanPreferences.status(context, id))
    var checkpoints by remember(id) { mutableStateOf<List<CatalogCheckpointFile>?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf<String?>(null) }
    var selectedCheckpoint by remember(id) { mutableStateOf<CatalogCheckpointFile?>(null) }
    BackupSettingsActionRow(Icons.Default.AccountCircle, "Catalog profile: ${profile.name}",
        "Choose which profile to scan, schedule or restore.", onClick = { chooseProfile = true })
    if (chooseProfile) AlertDialog(
        onDismissRequest = { chooseProfile = false }, title = { Text("Choose catalog profile") },
        text = { LazyColumn { items(profiles) { entry ->
            TextButton(onClick = { selectedId = CatalogScanPreferences.id(entry); chooseProfile = false },
                modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(12.dp))) { Text(entry.name) }
        } } }, confirmButton = { TextButton(onClick = { chooseProfile = false }) { Text("Close") } })
    BackupSettingsActionRow(Icons.Default.Refresh, "Scan and update this profile",
        "Update channel, movie and series listings in Room in the background. If paused or stopped, use Resume below. Yields during playback.",
        onClick = { SearchMetadataSyncScheduler.refresh(context, profile) })
    CatalogOperationPanel(CatalogOperations.scan(id), "Scan progress · ${profile.name}") {
        SearchMetadataSyncScheduler.refresh(context, profile, resume = true)
    }
    CatalogDatabasePanel(profile)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local database update schedule", style = MaterialTheme.typography.titleSmall)
        Text("For ${profile.name} on this device. Off allows restoring a checkpoint before scanning.", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(0 to "Off", 6 to "6 hours", 12 to "12 hours", 24 to "Daily", 168 to "Weekly")) { (value, label) ->
                FilterChip(selected = hours == value, onClick = {
                    hours = value
                    CatalogScanPreferences.hours(context, id, value)
                    SearchMetadataSyncScheduler.configureProfile(context, profile)
                }, label = { Text(label) }, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(8.dp)))
            }
        }
        Text("Provider requests run one at a time with a minimum two-second gap. Failures use exponential backoff; scans yield during playback. Episode details are cached when opened.", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("When catalog backup is enabled below, completed scans also queue a GitHub backup and dated checkpoint. Episodes are cached when opened.", style = MaterialTheme.typography.bodySmall)
    }
    BackupSettingsActionRow(Icons.Default.CloudDownload, "Restore a catalog checkpoint",
        "Choose a dated GitHub backup for ${profile.name}. Merges Room listings without replacing favorites or history.",
        enabled = !busy, onClick = {
            busy = true; message = "Loading checkpoints…"
            scope.launch {
                try {
                    checkpoints = CatalogBackupManager(context).checkpoints(profile)
                    message = if (checkpoints!!.isEmpty()) "No dated checkpoints yet. Create one using Back up IPTV catalog now. Older backups remain available through Restore IPTV catalog." else null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not list checkpoints. Check the saved GitHub settings and connection." }
                finally { busy = false }
            }
        })
    message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
    checkpoints?.takeIf { it.isNotEmpty() }?.let { entries ->
        AlertDialog(onDismissRequest = { checkpoints = null }, title = { Text("Restore ${profile.name}") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(entries) { entry ->
                TextButton(onClick = { selectedCheckpoint = entry; checkpoints = null },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(12.dp))) {
                    Text("${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))} · device ${entry.device}")
                }
            } } }, confirmButton = { TextButton(onClick = { checkpoints = null }) { Text("Close") } })
    }
    selectedCheckpoint?.let { entry ->
        AlertDialog(onDismissRequest = { selectedCheckpoint = null }, title = { Text("Merge catalog checkpoint?") },
            text = { Text("Restore ${profile.name} from ${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))}. Newer local records are retained. Reopen the profile afterward to reload the dashboard.") },
            confirmButton = { TextButton(onClick = {
                selectedCheckpoint = null; busy = true; message = "Restoring catalog…"
                scope.launch {
                    try {
                        CatalogBackupManager(context).restoreCheckpoint(profile, entry)
                        message = "Catalog restored. Search can use it now; reopen the profile to reload the dashboard."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Restore failed. Check GitHub settings and the original password if this checkpoint was encrypted. See activity for the outcome." }
                    finally { busy = false }
                }
            }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { selectedCheckpoint = null }) { Text("Cancel") } })
    }
}
