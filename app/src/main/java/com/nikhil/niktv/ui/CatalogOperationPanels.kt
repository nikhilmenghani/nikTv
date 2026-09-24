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
import androidx.compose.ui.text.TextStyle
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
        "Media types" to if (progress.mediaCount > 0) "${progress.mediaPosition} of ${progress.mediaCount}" else "N/A",
        "Stage" to value(progress.phase),
        "Category" to value(progress.category),
        "Categories" to if (progress.categoryCount > 0) "${progress.categoryPosition} of ${progress.categoryCount}" else "N/A",
        "Page" to if (progress.page > 0) if (progress.totalPages > 0) "${progress.page} of ${progress.totalPages}" else progress.page.toString() else "N/A",
        "Scan position (estimate)" to (progress.percentText ?: "N/A"),
        "Category ETA" to when {
            progress.phase.startsWith("Finalizing", ignoreCase = true) -> "Finishing…"
            progress.totalPages > 0 && progress.page >= progress.totalPages -> "Finishing…"
            else -> formatRemainingTime(progress.estimatedRemainingMillis) ?: "Calculating…"
        },
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

private data class CatalogPanelAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val error: Boolean = false
)

@Composable
private fun CatalogPanelActionButton(
    action: CatalogPanelAction,
    modifier: Modifier,
    textStyle: TextStyle,
    contentPadding: PaddingValues
) {
    val content: @Composable RowScope.() -> Unit = {
        Text(
            action.label,
            style = textStyle,
            color = if (action.error) MaterialTheme.colorScheme.error else Color.Unspecified,
            maxLines = 1
        )
    }
    if (action.primary) {
        NikTvPrimaryActionButton(
            onClick = action.onClick,
            enabled = action.enabled,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier,
            contentPadding = contentPadding,
            content = content
        )
    } else {
        NikTvSecondaryActionButton(
            onClick = action.onClick,
            enabled = action.enabled,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier,
            contentPadding = contentPadding,
            content = content
        )
    }
}

@Composable
internal fun CatalogOperationPanel(
    operation: String,
    title: String,
    resumeEnabled: Boolean = true,
    onStart: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onCheckUpdates: (() -> Unit)? = null,
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
    val checkingUpdates = isScan && scanWork?.any {
        !it.state.isFinished && SearchMetadataSyncScheduler.UPDATE_CHECK_TAG in it.tags
    } == true
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
        else -> if (checkingUpdates) message else if (isScan && scanState == CatalogScanDisplay.QUEUED) queuedExplanation else if (interrupted) "Scan interrupted. Resume from the last saved page." else if (updated == 0L) {
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
                if (isScan || held) Text(if (checkingUpdates) "Checking updates" else if (isScan) scanState.label else mode, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if ((isScan && scanState == CatalogScanDisplay.SCANNING) || uploadActive) {
                val fraction = progress?.fraction
                if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            val parts = summary.split(" · ")
            val showStage = !held && parts.size > 1 && !summary.startsWith("Complete") && !summary.startsWith("Scan already complete")
            if (progress != null && progress.phase != "Complete" && (busy || uploadActive)) {
                CatalogProgressDetails(progress, isScan)
            }
            else {
                Text(if (showStage) parts.last() else summary, style = MaterialTheme.typography.bodyMedium)
                if (showStage) Text(parts.dropLast(1).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (updated > 0) Text("Updated ${operationTime(updated)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val compactActions = LocalConfiguration.current.screenWidthDp < 600
            val actionTextStyle = if (compactActions) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge
            val actionPadding = PaddingValues(horizontal = if (compactActions) 8.dp else 16.dp, vertical = 8.dp)
            val actions = buildList {
                if (held) {
                    add(CatalogPanelAction("Resume local", onResume, resumeEnabled, primary = true))
                    onRestoredResume?.let { add(CatalogPanelAction(restoredResumeLabel, it)) }
                    onFullScan?.let { add(CatalogPanelAction("Refresh all", it)) }
                } else {
                    onStart?.let { start ->
                        val click = when {
                            checkingUpdates -> ({})
                            scanState == CatalogScanDisplay.QUEUED -> onRetry ?: start
                            scanState == CatalogScanDisplay.COMPLETE -> onCheckUpdates ?: start
                            else -> start
                        }
                        val label = when {
                            checkingUpdates -> "Checking…"
                            scanState == CatalogScanDisplay.SCANNING -> "Scanning…"
                            scanState == CatalogScanDisplay.QUEUED -> "Try now"
                            scanState == CatalogScanDisplay.CHECKING -> "Checking…"
                            scanState == CatalogScanDisplay.COMPLETE -> "Check updates"
                            scanState == CatalogScanDisplay.IDLE -> "Start scan"
                            else -> "Resume scan"
                        }
                        add(CatalogPanelAction(
                            label = label,
                            onClick = click,
                            enabled = !checkingUpdates && (!busy ||
                                (scanState == CatalogScanDisplay.QUEUED && onRetry != null)),
                            primary = true
                        ))
                    }
                    onRestoredResume?.let { add(CatalogPanelAction(restoredResumeLabel, it, enabled = !busy)) }
                    onFullScan?.let { add(CatalogPanelAction("Refresh all", it, enabled = !busy)) }
                    if (!isScan || busy) add(CatalogPanelAction("Pause", {
                        CatalogOperations.control(context, operation, "Paused")
                    }))
                }
                if (mode != "Stopped" && (!isScan || busy || held)) add(CatalogPanelAction("Stop", {
                    CatalogOperations.control(context, operation, "Stopped")
                }))
                add(CatalogPanelAction("Details", { detail = "events" }))
                if (failures.isNotEmpty()) add(CatalogPanelAction(
                    label = "${failures.size} failed",
                    onClick = { detail = "failures" },
                    error = true
                ))
            }
            if (compactActions) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.chunked(3).forEach { actionRow ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            actionRow.forEach { action ->
                                CatalogPanelActionButton(
                                    action = action,
                                    modifier = Modifier.weight(1f),
                                    textStyle = actionTextStyle,
                                    contentPadding = actionPadding
                                )
                            }
                        }
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3
                ) {
                    actions.forEach { action ->
                        CatalogPanelActionButton(
                            action = action,
                            modifier = Modifier.width(148.dp),
                            textStyle = actionTextStyle,
                            contentPadding = actionPadding
                        )
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
internal fun CatalogTypeUpdatePanels(
    profile: PortalProfile,
    onCheck: (CatalogType) -> Unit,
    onResume: (CatalogType) -> Unit,
    onRefresh: (CatalogType) -> Unit
) {
    val context = LocalContext.current
    val profileId = CatalogScanPreferences.id(profile)
    val repository = remember(context) { CatalogRepository(context) }
    val types = remember { listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES) }
    val scanOperation = remember(profileId) { CatalogOperations.scan(profileId) }
    val operationRevision by remember(scanOperation) {
        CatalogOperations.observe(context, scanOperation)
    }.collectAsState(0L)
    val statuses by remember(profileId) { repository.observeUpdateStatuses(profile) }
        .collectAsState(initial = emptyList())
    var checkpoints by remember(profileId) {
        mutableStateOf<Map<CatalogType, CatalogScanCheckpoint>?>(null)
    }
    val scanWork by remember(profileId) {
        SearchMetadataSyncScheduler.observeScanWork(context, profileId)
    }.collectAsState(initial = null)
    val activeWork = scanWork.orEmpty().filterNot { it.state.isFinished }
    val scanBusy = activeWork.isNotEmpty()
    val checkingUpdates = activeWork.any { SearchMetadataSyncScheduler.UPDATE_CHECK_TAG in it.tags }
    val scanProgress = remember(operationRevision, scanOperation) {
        CatalogOperations.progress(context, scanOperation)
    }
    val selectedScanType = remember(operationRevision, profileId) {
        CatalogScanPreferences.activeType(context, profileId)
    }

    LaunchedEffect(profileId, statuses, operationRevision, scanBusy) {
        try {
            checkpoints = withContext(Dispatchers.IO) {
                types.associateWith { repository.scanCheckpoint(profile, it) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkpoints = emptyMap()
        }
    }

    val interactionsEnabled = checkpoints != null && !scanBusy

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Updates by media type", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Check or refresh only the part of the provider catalog you need.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        types.forEach { type ->
            val status = statuses.firstOrNull { it.type == type.name }
            val checkpoint = checkpoints?.get(type)
            val savedState = status?.state?.uppercase().orEmpty()
            val activelyScanning = scanBusy && !checkingUpdates &&
                (selectedScanType == type.name || scanProgress?.mediaType == type.title)
            val state = when {
                checkpoints == null -> "LOADING"
                savedState == "CHECKING" && checkingUpdates -> "CHECKING"
                activelyScanning -> "SCANNING"
                savedState == "FAILED" -> "FAILED"
                checkpoint?.hasData == false -> "NOT_SCANNED"
                checkpoint?.complete == false -> "INCOMPLETE"
                savedState == "CHECKING" -> "NOT_CHECKED"
                savedState.isNotBlank() -> savedState
                else -> "NOT_CHECKED"
            }
            val badge = when (state) {
                "LOADING" -> "Loading"
                "CHECKING" -> "Checking"
                "SCANNING" -> "Scanning"
                "NO_CHANGES" -> "No changes detected"
                "UPDATE_AVAILABLE" -> "Update available"
                "CHANGED" -> "Changed"
                "INCOMPLETE" -> "Incomplete"
                "NOT_SCANNED" -> "Not scanned"
                "FAILED" -> "Check failed"
                else -> "Not checked"
            }
            val badgeColor = when (state) {
                "NO_CHANGES" -> Color(0xFF62C58F)
                "UPDATE_AVAILABLE", "CHANGED" -> MaterialTheme.colorScheme.tertiary
                "FAILED" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            val detectedChanges = buildList {
                status?.let { update ->
                    update.newPages.takeIf { it > 0 }?.let { pages ->
                        add(if (update.newPagesExact) "$pages new ${if (pages == 1) "page" else "pages"} available"
                        else "More provider pages detected")
                    }
                    update.newCategories.takeIf { it > 0 }?.let { count ->
                        add("$count new ${if (count == 1) "category" else "categories"}")
                    }
                    update.removedCategories.takeIf { it > 0 }?.let { count ->
                        add("$count removed ${if (count == 1) "category" else "categories"}")
                    }
                }
            }.joinToString(" · ")
            val detail = when (state) {
                "LOADING" -> "Reading saved scan progress…"
                "CHECKING" -> status?.detail?.ifBlank { "Comparing provider pages with the last scan…" }
                    ?: "Comparing provider pages with the last scan…"
                "SCANNING" -> buildString {
                    append(scanProgress?.phase?.ifBlank { "Refreshing from the provider" }
                        ?: "Refreshing from the provider")
                    scanProgress?.category?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    scanProgress?.page?.takeIf { it > 0 }?.let { append(" · page $it") }
                    append('…')
                }
                "NO_CHANGES" -> status?.detail?.ifBlank { "No new provider pages or category changes found." }
                    ?: "No new provider pages or category changes found."
                "UPDATE_AVAILABLE" -> status?.detail?.ifBlank { detectedChanges }
                    ?.ifBlank { "New provider pages are ready to sync." }
                    ?: "New provider pages are ready to sync."
                "CHANGED" -> status?.detail?.ifBlank { detectedChanges }
                    ?.ifBlank { "The provider catalog changed; refresh recommended." }
                    ?: "The provider catalog changed; refresh recommended."
                "INCOMPLETE" -> buildString {
                    append("Resume from saved progress")
                    checkpoint?.currentCategory?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    checkpoint?.currentPage?.takeIf { it > 0 }?.let { append(" · page $it") }
                    append('.')
                }
                "NOT_SCANNED" -> "No local ${type.title} scan has been completed yet."
                "FAILED" -> status?.detail?.ifBlank { "Could not check the provider. Your local catalog is unchanged." }
                    ?: "Could not check the provider. Your local catalog is unchanged."
                else -> "Check the provider for new pages before deciding whether to sync."
            }
            val timing = buildList {
                status?.checkedAt?.takeIf { it > 0 }?.let {
                    val label = if (status.detail.startsWith("Synced", ignoreCase = true)) "Synced" else "Checked"
                    add("$label ${operationTime(it)}")
                }
                checkpoint?.updatedAt?.takeIf { it > 0 }?.let {
                    add("${if (checkpoint.complete) "Local data" else "Progress saved"} ${operationTime(it)}")
                }
            }

            val primaryLabel: String
            val primaryAction: () -> Unit
            val secondaryLabel: String?
            val secondaryAction: (() -> Unit)?
            when (state) {
                "INCOMPLETE" -> {
                    primaryLabel = "Resume"
                    primaryAction = { onResume(type) }
                    secondaryLabel = "Restart"
                    secondaryAction = { onRefresh(type) }
                }
                "NOT_SCANNED" -> {
                    primaryLabel = "Scan"
                    primaryAction = { onRefresh(type) }
                    secondaryLabel = null
                    secondaryAction = null
                }
                "UPDATE_AVAILABLE", "CHANGED" -> {
                    primaryLabel = "Sync"
                    primaryAction = { onRefresh(type) }
                    secondaryLabel = "Check again"
                    secondaryAction = { onCheck(type) }
                }
                "NO_CHANGES" -> {
                    primaryLabel = "Check again"
                    primaryAction = { onCheck(type) }
                    secondaryLabel = "Refresh"
                    secondaryAction = { onRefresh(type) }
                }
                "FAILED" -> {
                    primaryLabel = "Retry check"
                    primaryAction = { onCheck(type) }
                    secondaryLabel = null
                    secondaryAction = null
                }
                "CHECKING" -> {
                    primaryLabel = "Checking…"
                    primaryAction = {}
                    secondaryLabel = "Refresh"
                    secondaryAction = { onRefresh(type) }
                }
                "SCANNING" -> {
                    primaryLabel = "Scanning…"
                    primaryAction = {}
                    secondaryLabel = "Refresh"
                    secondaryAction = { onRefresh(type) }
                }
                else -> {
                    primaryLabel = "Check now"
                    primaryAction = { onCheck(type) }
                    secondaryLabel = "Refresh"
                    secondaryAction = { onRefresh(type) }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1B1E24),
                border = BorderStroke(1.dp, SettingsOutline)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(type.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = badgeColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f))
                        ) {
                            Text(
                                badge,
                                Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeColor,
                                maxLines = 1
                            )
                        }
                    }
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                    timing.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NikTvPrimaryActionButton(
                            onClick = primaryAction,
                            enabled = interactionsEnabled && state != "CHECKING" && state != "LOADING",
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(primaryLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        secondaryLabel?.let { label ->
                            NikTvSecondaryActionButton(
                                onClick = secondaryAction ?: {},
                                enabled = interactionsEnabled && state != "CHECKING" && state != "LOADING",
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
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
