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
import com.nikhil.niktv.model.CatalogType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CatalogProfileSettings(
    profiles: List<PortalProfile>,
    active: PortalProfile?,
    onProfileSelected: (PortalProfile) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedId by remember(profiles) {
        mutableStateOf(
            CatalogScanPreferences.selectedProfileId(context)
                ?.takeIf { saved -> profiles.any { CatalogScanPreferences.id(it) == saved } }
                ?: active?.let(CatalogScanPreferences::id)
        )
    }
    val profile = profiles.firstOrNull { CatalogScanPreferences.id(it) == selectedId } ?: active ?: profiles.firstOrNull()
    if (profile == null) return
    val id = CatalogScanPreferences.id(profile)
    val restoreRevision by remember { CatalogOperations.observe(context, CatalogOperations.RESTORE) }.collectAsState(0L)
    val restoredAt = remember(id, restoreRevision) { CatalogScanPreferences.restoredAt(context, id) }
    var restoredCursor by remember(id, restoreRevision) {
        mutableIntStateOf(CatalogScanPreferences.restoredCursor(context, id))
    }
    val restoredType = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).getOrNull(restoredCursor)
    LaunchedEffect(id, restoreRevision, restoredAt) {
        if (restoredAt > 0L) {
            val corrected = withContext(Dispatchers.IO) {
                CatalogRepository(context).resumeScanIndex(profile)
            }
            if (corrected != restoredCursor) {
                CatalogScanPreferences.restoredCursor(context, id, corrected)
                restoredCursor = corrected
            }
        }
    }
    LaunchedEffect(id) {
        if (CatalogScanPreferences.selectedProfileId(context) == null) {
            CatalogScanPreferences.selectedProfileId(context, id)
        }
    }
    var chooseProfile by remember { mutableStateOf(false) }
    var hours by remember(id) { mutableIntStateOf(CatalogScanPreferences.hours(context, id)) }
    var checkpoints by remember(id) { mutableStateOf<List<CatalogCheckpointFile>?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf<String?>(null) }
    var selectedCheckpoint by remember(id) { mutableStateOf<CatalogCheckpointFile?>(null) }
    var confirmFullScan by remember(id) { mutableStateOf(false) }
    BackupSettingsActionRow(Icons.Default.AccountCircle, "Profile · ${profile.name}",
        "All catalog actions below apply to this profile until you change it.", onClick = { chooseProfile = true })
    if (chooseProfile) AlertDialog(
        onDismissRequest = { chooseProfile = false }, title = { Text("Choose catalog profile") },
        text = { LazyColumn { items(profiles) { entry ->
            NikTvTextActionButton(onClick = {
                selectedId = CatalogScanPreferences.id(entry)
                CatalogScanPreferences.selectedProfileId(context, selectedId!!)
                onProfileSelected(entry)
                chooseProfile = false
            }, modifier = Modifier.fillMaxWidth()) { Text(entry.name) }
        } } }, confirmButton = { NikTvTextActionButton(onClick = { chooseProfile = false }) { Text("Close") } })
    if (confirmFullScan) ProjectCardConfirmationDialog(
        title = "Start a full ${profile.name} scan?",
        message = "A full scan starts again with Live TV and refreshes every provider page for Live TV, Movies and Series. Existing records remain available while it runs, but it does not continue the restored Movies cursor. Use Resume scan when you want to continue from the last backed-up page.",
        confirmLabel = "Start full scan",
        close = { confirmFullScan = false },
        confirm = {
            confirmFullScan = false
            SearchMetadataSyncScheduler.fullScan(context, profile)
        }
    )
    CatalogOperationPanel(CatalogOperations.scan(id), "Catalog scan", onStart = {
        SearchMetadataSyncScheduler.refresh(context, profile, resume = true)
    }, onRetry = { SearchMetadataSyncScheduler.retryQueued(context, profile) },
        onFullScan = { confirmFullScan = true },
        onRestoredResume = if (restoredAt > 0L && restoredType != null) ({
            SearchMetadataSyncScheduler.resumeRestored(context, profile)
        }) else null,
        restoredResumeLabel = restoredType?.let { "Resume restored ${it.title}" } ?: "Resume restored") {
        SearchMetadataSyncScheduler.refresh(context, profile, resume = true)
    }
    CatalogDatabasePanel(profile)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local database update schedule", style = MaterialTheme.typography.titleSmall)
        Text("For ${profile.name} on this device. Off allows restoring a checkpoint before scanning.", style = MaterialTheme.typography.bodySmall)
        SettingsChoiceGrid(
            options = listOf(0 to "Off", 6 to "6 hours", 12 to "12 hours", 24 to "Daily", 168 to "Weekly"),
            selected = hours,
            onSelect = { value ->
                hours = value
                CatalogScanPreferences.hours(context, id, value)
                SearchMetadataSyncScheduler.configureProfile(context, profile)
            }
        )
        Text("Scans continue in the background with a notification. Requests are paced and scans yield during playback. Android may delay work under battery restrictions. Episode details are cached when opened.", style = MaterialTheme.typography.bodySmall)
        Text("Completed scans create a GitHub checkpoint when catalog backup is enabled.", style = MaterialTheme.typography.bodySmall)
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
                NikTvTextActionButton(onClick = { selectedCheckpoint = entry; checkpoints = null },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))} · device ${entry.device}")
                }
            } } }, confirmButton = { NikTvTextActionButton(onClick = { checkpoints = null }) { Text("Close") } })
    }
    selectedCheckpoint?.let { entry ->
        AlertDialog(onDismissRequest = { selectedCheckpoint = null }, title = { Text("Merge catalog checkpoint?") },
            text = { Text("Restore ${profile.name} from ${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))}. Newer local records are retained. Reopen the profile afterward to reload the dashboard.") },
            confirmButton = { NikTvTextActionButton(onClick = {
                selectedCheckpoint = null; busy = true; message = "Restoring catalog…"
                scope.launch {
                    try {
                        CatalogBackupManager(context).restoreCheckpoint(profile, entry)
                        message = "Catalog restored. Search can use it now; reopen the profile to reload the dashboard."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Restore failed. Check GitHub settings and the original password if this checkpoint was encrypted. See activity for the outcome." }
                    finally { busy = false }
                }
            }) { Text("Restore") } }, dismissButton = { NikTvTextActionButton(onClick = { selectedCheckpoint = null }) { Text("Cancel") } })
    }
}
