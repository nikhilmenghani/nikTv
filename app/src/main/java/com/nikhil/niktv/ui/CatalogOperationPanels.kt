package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
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
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date

private fun operationTime(value: Long): String = if (value == 0L) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))

@Composable
internal fun CatalogOperationPanel(operation: String, title: String, resumeEnabled: Boolean = true, onResume: () -> Unit) {
    val context = LocalContext.current
    val revision by remember(operation) { CatalogOperations.observe(context, operation) }.collectAsState(0L)
    val mode = remember(revision, operation) { CatalogOperations.mode(context, operation) }
    val message = remember(revision, operation) { CatalogOperations.message(context, operation) }
    val failures = remember(revision, operation) { CatalogOperations.events(context, operation, true) }
    val events = remember(revision, operation) { CatalogOperations.events(context, operation) }
    var detail by remember(operation) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(if (mode == "Ready") message else "$mode · $message", style = MaterialTheme.typography.bodyMedium)
        Text(operationTime(CatalogOperations.updated(context, operation)), style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = { CatalogOperations.control(context, operation, "Paused") }, enabled = mode == "Ready",
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(12.dp))) { Text("Pause") } }
            item { OutlinedButton(onClick = { CatalogOperations.control(context, operation, "Stopped") }, enabled = mode != "Stopped",
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(12.dp))) { Text("Stop") } }
            item { OutlinedButton(onClick = onResume, enabled = mode != "Ready" && resumeEnabled,
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(12.dp))) { Text("Resume") } }
        }
        Text("Pause/Stop retain committed work and hold scheduled runs until Resume. An in-flight page or file may finish first.", style = MaterialTheme.typography.bodySmall)
        if (operation.startsWith("scan:")) {
            Text("${failures.size} unresolved failed pages. Successful retries clear their failure. Recent activity retains the last 100 page outcomes.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedButton(onClick = { detail = "failures" }, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(12.dp))) { Text("Failed pages (${failures.size})") } }
                item { OutlinedButton(onClick = { detail = "events" }, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(12.dp))) { Text("Stored pages & activity") } }
            }
        }
    }
    detail?.let { selected ->
        val rows = if (selected == "failures") failures else events
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(if (selected == "failures") "Unresolved failed pages" else "Recent page activity") },
            text = { LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rows.isEmpty()) item { Text("No entries yet.") }
                items(rows, key = { "${it.key}:${it.time}" }) { row ->
                    Column(Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(8.dp)).focusable().padding(8.dp)) { Text("${row.outcome} · ${row.location}", style = MaterialTheme.typography.titleSmall)
                        Text(row.detail); Text(operationTime(row.time), style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                }
            } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}

@Composable
internal fun CatalogDatabasePanel(profile: PortalProfile) {
    val context = LocalContext.current
    val profileKey = profile.cacheKey()
    val dao = remember { CatalogDatabase.get(context).catalog() }
    val counts by remember(profileKey) { dao.storedCounts(profileKey) }.collectAsState(emptyList())
    var browseType by remember(profileKey) { mutableStateOf<CatalogType?>(null) }
    var offset by remember(profileKey) { mutableIntStateOf(0) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Verified Room contents · ${profile.name}", style = MaterialTheme.typography.titleMedium)
        Text("Counts come from committed database rows, deduplicated by provider ID and excluding removed items. Provider totals are unknown until scanning finishes; no estimated percentage is shown.", style = MaterialTheme.typography.bodySmall)
        listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).forEach { type ->
            val count = counts.firstOrNull { it.type == type.name }?.count ?: 0
            BackupSettingsActionRow(Icons.Default.Storage, "${type.title}: $count stored", "Browse stored titles and provider IDs", onClick = { offset = 0; browseType = type })
        }
    }
    browseType?.let { type ->
        var rows by remember(profileKey, type, offset) { mutableStateOf<List<CatalogItemRow>?>(null) }
        var failed by remember(profileKey, type, offset) { mutableStateOf(false) }
        LaunchedEffect(profileKey, type, offset) {
            try { rows = withContext(Dispatchers.IO) { dao.storedPage(profileKey, type.name, 50, offset) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        val json = remember { Json { ignoreUnknownKeys = true } }
        AlertDialog(onDismissRequest = { browseType = null }, title = { Text("${type.title} · stored records ${offset + 1}–${offset + (rows?.size ?: 0)}") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rows == null) item { Text(if (failed) "Could not read database." else "Reading Room…") }
                if (rows?.isEmpty() == true) item { Text("No more stored records.") }
                items(rows.orEmpty(), key = { it.id }) { row ->
                    val media = remember(row.payload) { runCatching { json.decodeFromString<MediaItem>(row.payload) }.getOrNull() }
                    Column(Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(8.dp)).focusable().padding(8.dp)) {
                        Text(media?.title ?: "Unreadable media record", style = MaterialTheme.typography.titleSmall)
                        Text("ID ${row.id} · category ${media?.portalCategoryId ?: row.bucket}", style = MaterialTheme.typography.bodySmall)
                        Text("Stored ${operationTime(row.observedAt)}", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            } }, confirmButton = { Row {
                TextButton(enabled = offset > 0, onClick = { offset = (offset - 50).coerceAtLeast(0) }) { Text("Previous") }
                TextButton(enabled = rows?.size == 50, onClick = { offset += 50 }) { Text("Next") }
                TextButton(onClick = { browseType = null }) { Text("Close") }
            } })
    }
}

@Composable
internal fun CatalogRestoreProgress() {
    val context = LocalContext.current
    val revision by remember { CatalogOperations.observe(context, "restore") }.collectAsState(0L)
    val message = remember(revision) { CatalogOperations.message(context, "restore") }
    Text("Restore: $message", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
}
