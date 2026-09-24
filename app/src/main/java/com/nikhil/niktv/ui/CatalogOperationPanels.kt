package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date
import androidx.work.WorkInfo

private fun operationTime(value: Long): String = if (value == 0L) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))

internal enum class CatalogScanDisplay(val label: String) {
    CHECKING("Checking status"), IDLE("Ready"), QUEUED("Queued"), SCANNING("Scanning"),
    PAUSED("Paused"), STOPPED("Stopped"), COMPLETE("Complete"), INTERRUPTED("Interrupted")
}

@Composable
private fun CatalogProgressDetails(progress: CatalogOperationProgress, isScan: Boolean) {
    fun value(text: String) = text.ifBlank { "N/A" }
    val fields = if (isScan) listOf(
        "Media" to value(progress.mediaType),
        "Stage" to value(progress.phase),
        "Category" to value(progress.category),
        "Categories" to if (progress.categoryCount > 0) "${progress.categoryPosition} of ${progress.categoryCount}" else "N/A",
        "Page" to if (progress.page > 0) if (progress.totalPages > 0) "${progress.page} of ${progress.totalPages}" else progress.page.toString() else "N/A",
        "Progress" to (progress.percentText ?: "N/A"),
        "Time remaining" to (formatRemainingTime(progress.estimatedRemainingMillis) ?: "Calculating…"),
        "Current page" to if (progress.recordsInPage > 0) "${progress.recordsInPage} records" else "N/A",
        "In category" to if (progress.recordsInCategory > 0) "${progress.recordsInCategory} records" else "N/A",
        "Stored total" to if (progress.totalRecords > 0) progress.totalRecords.toString() else "N/A"
    ) else listOf(
        "Profile" to value(progress.category),
        "Media" to value(progress.mediaType),
        "Stage" to value(progress.phase),
        "Transfer" to if (progress.totalParts > 0) "${progress.part} of ${progress.totalParts}" else "N/A",
        "Records" to if (progress.totalRecords > 0) progress.totalRecords.toString() else "N/A"
    )
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        fields.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal fun catalogScanDisplay(mode: String, work: List<WorkInfo.State>?, cursor: Int, completed: Long): CatalogScanDisplay = when {
    mode == "Paused" -> CatalogScanDisplay.PAUSED
    mode == "Stopped" -> CatalogScanDisplay.STOPPED
    work == null -> CatalogScanDisplay.CHECKING
    WorkInfo.State.RUNNING in work -> CatalogScanDisplay.SCANNING
    work.any { !it.isFinished } -> CatalogScanDisplay.QUEUED
    cursor >= 0 -> CatalogScanDisplay.INTERRUPTED
    completed > 0 -> CatalogScanDisplay.COMPLETE
    else -> CatalogScanDisplay.IDLE
}

@Composable
internal fun CatalogOperationPanel(
    operation: String,
    title: String,
    resumeEnabled: Boolean = true,
    onStart: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onFullScan: (() -> Unit)? = null,
    onRestoredResume: (() -> Unit)? = null,
    restoredResumeLabel: String = "Resume restored",
    onResume: () -> Unit
) {
    val context = LocalContext.current
    val revision by remember(operation) { CatalogOperations.observe(context, operation) }.collectAsState(0L)
    val mode = remember(revision, operation) { CatalogOperations.mode(context, operation) }
    val message = remember(revision, operation) { CatalogOperations.message(context, operation) }
    val progress = remember(revision, operation) { CatalogOperations.progress(context, operation) }
    val failures = remember(revision, operation) { CatalogOperations.events(context, operation, true) }
    val backupEvents by remember(context) { BackupActivityLog.observe(context) }.collectAsState(emptyList())
    val events = remember(revision, operation, backupEvents) {
        if (operation.startsWith("scan:")) CatalogOperations.events(context, operation)
        else backupEvents.filter { it.operation.startsWith("IPTV catalog backup") || it.operation.startsWith("Catalog checkpoint ·") }
            .map { CatalogPageEvent(it.id, it.timestamp, it.operation, it.status, it.detail) }
    }
    var detail by remember(operation) { mutableStateOf<String?>(null) }
    val isScan = operation.startsWith("scan:")
    val scanWork by remember(operation) {
        if (isScan) SearchMetadataSyncScheduler.observeScanWork(context, operation.removePrefix("scan:"))
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = null)
    val scanId = operation.removePrefix("scan:")
    val scanState = catalogScanDisplay(mode, scanWork?.map { it.state },
        CatalogScanPreferences.cursor(context, scanId), CatalogScanPreferences.completed(context, scanId))
    val interrupted = isScan && scanState == CatalogScanDisplay.INTERRUPTED
    val held = mode != "Ready" || interrupted
    val busy = isScan && scanState in listOf(CatalogScanDisplay.CHECKING, CatalogScanDisplay.QUEUED, CatalogScanDisplay.SCANNING)
    val updated = CatalogOperations.updated(context, operation)
    val uploadActive = !isScan && mode == "Ready" && (progress?.phase != null && progress.phase != "Complete" || message.let {
        it.startsWith("Backup queued") || it.contains("Uploading part") ||
            it.contains("Reading Room snapshot") || it.contains("Creating dated restore checkpoint") ||
            it.startsWith("Waiting for playback")
    })
    val queuedWork = scanWork?.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
    val queuedExplanation = when {
        queuedWork != null && queuedWork.runAttemptCount > 0 ->
            "Waiting to retry after an interrupted or unsuccessful attempt. Saved progress is retained." +
                if (queuedWork.nextScheduleTimeMillis > System.currentTimeMillis() && queuedWork.nextScheduleTimeMillis < Long.MAX_VALUE)
                    " Next eligible attempt: ${operationTime(queuedWork.nextScheduleTimeMillis)}." else ""
        queuedWork != null && queuedWork.nextScheduleTimeMillis > System.currentTimeMillis() && queuedWork.nextScheduleTimeMillis < Long.MAX_VALUE ->
            "Continuing from saved progress after ${operationTime(queuedWork.nextScheduleTimeMillis)}."
        scanWork?.any { it.state == WorkInfo.State.BLOCKED } == true ->
            "Waiting for an earlier scan task. Try now can replace the waiting queue without discarding saved pages."
        else -> "Waiting for Android to start the scan. An internet connection and available storage are required. Try now requests a fresh start from saved progress."
    }
    val summary = when (mode) {
        "Paused" -> "Progress saved. Resume when you’re ready."
        "Stopped" -> "Stopped. Saved progress is available to resume."
        else -> if (isScan && scanState == CatalogScanDisplay.QUEUED) queuedExplanation else if (interrupted) "Scan interrupted. Resume from the last saved page." else if (updated == 0L) {
            when {
                operation.startsWith("scan:") -> "Ready to scan channels, movies and series."
                operation == CatalogOperations.RESTORE -> "No catalog restore is running."
                else -> "No catalog upload yet."
            }
        } else message
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1B1E24),
        border = BorderStroke(1.dp, SettingsOutline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (operation.startsWith("scan:")) Icons.Default.Storage else Icons.Default.CloudUpload,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (isScan || held) Text(if (isScan) scanState.label else mode, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if ((isScan && scanState == CatalogScanDisplay.SCANNING) || uploadActive) {
                val fraction = progress?.fraction
                if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            val parts = summary.split(" · ")
            val showStage = !held && parts.size > 1 && !summary.startsWith("Complete") && !summary.startsWith("Scan already complete")
            if (progress != null && progress.phase != "Complete") CatalogProgressDetails(progress, isScan)
            else {
                Text(if (showStage) parts.last() else summary, style = MaterialTheme.typography.bodyMedium)
                if (showStage) Text(parts.dropLast(1).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (updated > 0) Text("Updated ${operationTime(updated)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val compactActions = LocalConfiguration.current.screenWidthDp < 600
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val actionWidth = if (compactActions) (maxWidth - 16.dp) / 3 else 148.dp
                val actionTextStyle = if (compactActions) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelLarge
                val actionPadding = PaddingValues(horizontal = if (compactActions) 8.dp else 16.dp, vertical = 8.dp)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3
                ) {
                val actionModifier = Modifier.width(actionWidth)
                if (held) {
                    NikTvPrimaryActionButton(onClick = onResume, enabled = resumeEnabled, shape = RoundedCornerShape(8.dp),
                        modifier = actionModifier, contentPadding = actionPadding) { Text("Resume local", style = actionTextStyle, maxLines = 1) }
                    onRestoredResume?.let { restored ->
                        NikTvSecondaryActionButton(onClick = restored, shape = RoundedCornerShape(8.dp),
                            modifier = actionModifier, contentPadding = actionPadding) { Text(restoredResumeLabel, style = actionTextStyle, maxLines = 1) }
                    }
                    onFullScan?.let { fullScan ->
                        NikTvSecondaryActionButton(onClick = fullScan, shape = RoundedCornerShape(8.dp),
                            modifier = actionModifier, contentPadding = actionPadding) { Text("Full scan", style = actionTextStyle, maxLines = 1) }
                    }
                } else {
                    if (onStart != null) NikTvPrimaryActionButton(
                        onClick = if (scanState == CatalogScanDisplay.QUEUED) onRetry ?: onStart else onStart,
                        enabled = !busy || (scanState == CatalogScanDisplay.QUEUED && onRetry != null),
                        shape = RoundedCornerShape(8.dp), modifier = actionModifier, contentPadding = actionPadding) {
                        Text(text = when (scanState) {
                            CatalogScanDisplay.SCANNING -> "Scanning…"
                            CatalogScanDisplay.QUEUED -> "Try now"
                            CatalogScanDisplay.CHECKING -> "Checking…"
                            CatalogScanDisplay.COMPLETE -> "Resume scan"
                            else -> "Resume scan"
                        }, style = actionTextStyle, maxLines = 1)
                    }
                    onRestoredResume?.let { restored ->
                        NikTvSecondaryActionButton(onClick = restored, enabled = !busy, shape = RoundedCornerShape(8.dp),
                            modifier = actionModifier, contentPadding = actionPadding) { Text(restoredResumeLabel, style = actionTextStyle, maxLines = 1) }
                    }
                    onFullScan?.let { fullScan ->
                        NikTvSecondaryActionButton(onClick = fullScan, enabled = !busy, shape = RoundedCornerShape(8.dp),
                            modifier = actionModifier, contentPadding = actionPadding) { Text("Full scan", style = actionTextStyle, maxLines = 1) }
                    }
                    if (!isScan || busy) NikTvSecondaryActionButton(onClick = { CatalogOperations.control(context, operation, "Paused") },
                        shape = RoundedCornerShape(8.dp), modifier = actionModifier, contentPadding = actionPadding) { Text("Pause", style = actionTextStyle, maxLines = 1) }
                }
                if (mode != "Stopped" && (!isScan || busy || held)) NikTvSecondaryActionButton(onClick = { CatalogOperations.control(context, operation, "Stopped") },
                    shape = RoundedCornerShape(8.dp), modifier = actionModifier, contentPadding = actionPadding) { Text("Stop", style = actionTextStyle, maxLines = 1) }
                NikTvSecondaryActionButton(onClick = { detail = "events" },
                    shape = RoundedCornerShape(8.dp), modifier = actionModifier, contentPadding = actionPadding) { Text("Details", style = actionTextStyle, maxLines = 1) }
                if (failures.isNotEmpty()) NikTvSecondaryActionButton(onClick = { detail = "failures" },
                    shape = RoundedCornerShape(8.dp), modifier = actionModifier, contentPadding = actionPadding) {
                        Text("${failures.size} failed", color = MaterialTheme.colorScheme.error,
                            style = actionTextStyle, maxLines = 1)
                    }
                }
            }
        }
    }
    detail?.let { selected ->
        val rows = if (selected == "failures") failures else events
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(if (selected == "failures") "Unresolved failed pages" else "$title details") },
            text = { LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected != "failures") item {
                    Text("Pause and Stop keep completed pages/files and hold scheduled runs until Resume. An in-flight request may finish first.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (operation.startsWith("scan:")) Text("Recent page activity · last 100 outcomes", style = MaterialTheme.typography.titleSmall)
                }
                if (rows.isEmpty()) item { Text(if (selected == "failures") "No unresolved failures." else "No activity recorded yet.") }
                items(rows, key = { "${it.key}:${it.time}" }) { row ->
                    Column(Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(8.dp)).focusable().padding(8.dp)) { Text("${row.outcome} · ${row.location}", style = MaterialTheme.typography.titleSmall)
                        Text(row.detail); Text(operationTime(row.time), style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                }
            } }, confirmButton = { NikTvSecondaryActionButton(onClick = { detail = null }) { Text("Close") } })
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
        Text("Saved on this device", style = MaterialTheme.typography.titleMedium)
        Text("Browse the channels, movies and series already available locally.", style = MaterialTheme.typography.bodySmall)
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
        AlertDialog(onDismissRequest = { browseType = null }, title = { Text("${type.title} · newest records ${offset + 1}–${offset + (rows?.size ?: 0)}") },
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
                NikTvSecondaryActionButton(enabled = offset > 0, onClick = { offset = (offset - 50).coerceAtLeast(0) }) { Text("Previous") }
                NikTvSecondaryActionButton(enabled = rows?.size == 50, onClick = { offset += 50 }) { Text("Next") }
                NikTvSecondaryActionButton(onClick = { browseType = null }) { Text("Close") }
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
