package com.nikhil.niktv.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.data.LiveTvRecording
import com.nikhil.niktv.data.LiveTvRecordingManager
import com.nikhil.niktv.data.LiveTvRecordingStatus
import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun rememberCurrentRecordingProfile(): PortalProfile? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profileFlow = remember(context) {
        com.nikhil.niktv.data.ProfileStore(context).activeProfile
    }
    val profile by profileFlow.collectAsState(initial = null)
    return profile
}

@Composable
internal fun LiveTvRecordingDialog(
    channel: MediaItem,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val profileFlow = remember(context) {
        com.nikhil.niktv.data.ProfileStore(context).activeProfile
    }
    val profile by profileFlow.collectAsState(initial = null)
    val records by LiveTvRecordingManager.records(context).collectAsState()
    val profileKey = profile?.cacheKey()
    val channelRecords = records.filter {
        profileKey != null &&
            it.profileKey == profileKey &&
            it.channel.channelId == channel.id
    }
    val active = channelRecords.firstOrNull { it.isActive }
    val scheduled = channelRecords
        .filter { it.isScheduled }
        .sortedBy { it.scheduledStartMillis }
    val exactAvailable =
        remember(context) {
            LiveTvRecordingManager.exactSchedulingAvailable(context)
        }
    val firstFocus = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    val currentProgramme =
        channel.liveProgramme
            ?.takeIf {
                val end = it.endTimeMillis
                end != null && end > now + 30_000L
            }
    val upcoming =
        channel.liveSchedule
            .filter {
                val start = it.startTimeMillis
                val end = it.endTimeMillis
                start != null &&
                    end != null &&
                    start > now &&
                    end > start
            }
            .distinctBy {
                "${it.startTimeMillis}:${it.endTimeMillis}:${it.title}"
            }
            .sortedBy { it.startTimeMillis }
            .take(3)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(if (configuration.screenWidthDp < 600) 0.88f else 0.94f)
                .heightIn(max = 540.dp)
                .fillMaxHeight(if (configuration.screenHeightDp < 500) 0.82f else 0.72f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF51A1A1A)
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    "Live TV recording",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    channel.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (profile == null) {
                    Text(
                        "Loading the active IPTV profile…",
                        color = Color(0xFFFFC66D),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (active != null) {
                    Text(
                        LiveTvRecordingManager.statusText(active),
                        color = Color(0xFFFF6B72),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (active.isPaused) {
                                    LiveTvRecordingManager.resume(
                                        context,
                                        active.id
                                    )
                                } else {
                                    LiveTvRecordingManager.pause(
                                        context,
                                        active.id
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(firstFocus)
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                if (active.isPaused) {
                                    Icons.Default.PlayArrow
                                } else {
                                    Icons.Default.Pause
                                },
                                null
                            )
                            Text(
                                if (active.isPaused) {
                                    " Resume"
                                } else {
                                    " Pause"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                LiveTvRecordingManager.extend(
                                    context,
                                    active.id,
                                    15L * 60L * 1_000L
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(Icons.Default.Add, null)
                            Text(" +15 min")
                        }
                        OutlinedButton(
                            onClick = {
                                LiveTvRecordingManager.stop(
                                    context,
                                    active.id
                                )
                                onDismiss()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(Icons.Default.StopCircle, null)
                            Text(" Stop")
                        }
                    }
                } else {
                    Text(
                        "Record now",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Button(
                        enabled = profile != null,
                        onClick = {
                            val activeProfile =
                                profile ?: return@Button
                            LiveTvRecordingManager.startNow(
                                context = context,
                                profile = activeProfile,
                                channel = channel,
                                title = recordingTitle(
                                    channel,
                                    currentProgramme
                                )
                            )
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstFocus)
                            .remoteFocusFrame(
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(Icons.Default.FiberManualRecord, null)
                        Text(" Record until stopped")
                    }

                    currentProgramme?.endTimeMillis?.let { end ->
                        OutlinedButton(
                            onClick = {
                                val activeProfile =
                                    profile ?: return@OutlinedButton
                                LiveTvRecordingManager.startNow(
                                    context = context,
                                    profile = activeProfile,
                                    channel = channel,
                                    title = recordingTitle(
                                        channel,
                                        currentProgramme
                                    ),
                                    scheduledEndMillis = end
                                )
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                "Record current programme until " +
                                    recordingClock(end)
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "30 min" to 30L,
                            "1 hr" to 60L,
                            "2 hr" to 120L
                        ).forEach { (label, minutes) ->
                            OutlinedButton(
                                onClick = {
                                    val activeProfile =
                                        profile ?: return@OutlinedButton
                                    LiveTvRecordingManager.startNow(
                                        context = context,
                                        profile = activeProfile,
                                        channel = channel,
                                        title = recordingTitle(
                                            channel,
                                            currentProgramme
                                        ),
                                        maximumDurationMillis =
                                            minutes * 60L * 1_000L
                                    )
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .remoteFocusFrame(
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                if (scheduled.isNotEmpty()) {
                    Text(
                        "Scheduled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    scheduled.forEach { recording ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF252830),
                            modifier = Modifier
                                .fillMaxWidth()
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                                .focusable()
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        recording.title,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow =
                                            TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Starts " +
                                            recordingDateTime(
                                                recording
                                                    .scheduledStartMillis
                                            ),
                                        color = Color.LightGray,
                                        style =
                                            MaterialTheme.typography
                                                .labelSmall
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        LiveTvRecordingManager
                                            .startScheduledNow(
                                                context,
                                                recording.id
                                            )
                                        onDismiss()
                                    },
                                    modifier =
                                        Modifier.remoteFocusFrame(
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        "Start now"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        LiveTvRecordingManager.stop(
                                            context,
                                            recording.id
                                        )
                                    },
                                    modifier =
                                        Modifier.remoteFocusFrame(
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        "Cancel scheduled recording"
                                    )
                                }
                            }
                        }
                    }
                }

                if (upcoming.isNotEmpty()) {
                    Text(
                        "Upcoming on this channel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!exactAvailable) {
                        Text(
                            "Exact alarm access is not enabled. Android may " +
                                "start scheduled recordings slightly late.",
                            color = Color(0xFFFFC66D),
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (
                            android.os.Build.VERSION.SDK_INT >=
                            android.os.Build.VERSION_CODES.S
                        ) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                android.provider.Settings
                                                    .ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                Uri.parse(
                                                    "package:${context.packageName}"
                                                )
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.remoteFocusFrame(
                                    RoundedCornerShape(10.dp)
                                )
                            ) {
                                Text("Allow exact timing")
                            }
                        }
                    }
                    upcoming.forEach { programme ->
                        val start =
                            requireNotNull(
                                programme.startTimeMillis
                            )
                        val end =
                            requireNotNull(
                                programme.endTimeMillis
                            )
                        OutlinedButton(
                            onClick = {
                                val activeProfile =
                                    profile ?: return@OutlinedButton
                                LiveTvRecordingManager.schedule(
                                    context = context,
                                    profile = activeProfile,
                                    channel = channel,
                                    title = recordingTitle(
                                        channel,
                                        programme
                                    ),
                                    startAtMillis = start,
                                    endAtMillis = end
                                )
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment =
                                    Alignment.Start
                            ) {
                                Text(
                                    programme.title,
                                    maxLines = 2,
                                    overflow =
                                        TextOverflow.Ellipsis
                                )
                                Text(
                                    "${recordingClock(start)}–" +
                                        recordingClock(end),
                                    style =
                                        MaterialTheme.typography
                                            .labelSmall
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .remoteFocusFrame(
                            RoundedCornerShape(10.dp)
                        )
                ) {
                    Text("Close")
                }
            }
        }

        LaunchedEffect(active?.id, channel.id) {
            delay(100L)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

internal fun LazyListScope.liveTvRecordingManagerSections(
    context: Context,
    recordings: List<LiveTvRecording>
) {
    val active =
        recordings.filter { it.isActive }
            .sortedByDescending { it.startedAtMillis }
    val scheduled =
        recordings.filter { it.isScheduled }
            .sortedBy { it.scheduledStartMillis }
    val completed =
        recordings.filter { it.isTerminal }
            .sortedByDescending {
                it.endedAtMillis.takeIf { value -> value > 0L }
                    ?: it.updatedAtMillis
            }

    if (active.isNotEmpty()) {
        item("managed-recordings-active-header") {
            RecordingSectionHeader("Active")
        }
        items(
            items = active,
            key = { "recording-active-${it.id}" }
        ) { recording ->
            ManagedRecordingRow(context, recording)
        }
    }

    if (scheduled.isNotEmpty()) {
        item("managed-recordings-scheduled-header") {
            RecordingSectionHeader("Scheduled")
        }
        items(
            items = scheduled,
            key = { "recording-scheduled-${it.id}" }
        ) { recording ->
            ManagedRecordingRow(context, recording)
        }
    }

    if (completed.isNotEmpty()) {
        item("managed-recordings-completed-header") {
            RecordingSectionHeader("Completed")
        }
        items(
            items = completed,
            key = { "recording-completed-${it.id}" }
        ) { recording ->
            ManagedRecordingRow(context, recording)
        }
    }
}

@Composable
private fun RecordingSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun ManagedRecordingRow(
    context: Context,
    recording: LiveTvRecording
) {
    var confirmDelete by remember(recording.id) {
        mutableStateOf(false)
    }
    val playable =
        recording.state == LiveTvRecordingStatus.COMPLETED &&
            !recording.outputUri.isNullOrBlank()

    Surface(
        onClick = {
            openRecording(context, recording)
        },
        enabled = playable,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF151820),
        modifier = Modifier
            .fillMaxWidth()
            .remoteFocusFrame(RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(64.dp, 46.dp),
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFF272A31)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            recording.isScheduled ->
                                Icons.Default.PlayArrow
                            recording.state ==
                                LiveTvRecordingStatus.FAILED ->
                                Icons.Default.StopCircle
                            else ->
                                Icons.Default.FiberManualRecord
                        },
                        null,
                        tint =
                            if (
                                recording.state ==
                                LiveTvRecordingStatus.FAILED
                            ) {
                                Color(0xFFFFA0A5)
                            } else {
                                Color(0xFFE50914)
                            },
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    recording.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    recording.channel.channelTitle,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    LiveTvRecordingManager.statusText(recording),
                    color =
                        if (recording.isActive) {
                            Color(0xFFFF6B72)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                recording.channel.categoryId?.let {
                    Text(
                        "Channel ${recording.channel.channelId} · Category $it",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                recording.errorMessage?.let {
                    Text(
                        it,
                        color = Color(0xFFFFA0A5),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    recording.isActive -> {
                        IconButton(
                            onClick = {
                                if (recording.isPaused) {
                                    LiveTvRecordingManager.resume(
                                        context,
                                        recording.id
                                    )
                                } else {
                                    LiveTvRecordingManager.pause(
                                        context,
                                        recording.id
                                    )
                                }
                            },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(
                                if (recording.isPaused) {
                                    Icons.Default.PlayArrow
                                } else {
                                    Icons.Default.Pause
                                },
                                if (recording.isPaused) {
                                    "Resume recording"
                                } else {
                                    "Pause recording"
                                }
                            )
                        }
                        IconButton(
                            onClick = {
                                LiveTvRecordingManager.extend(
                                    context,
                                    recording.id,
                                    15L * 60L * 1_000L
                                )
                            },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.Add, "Extend 15 minutes")
                        }
                        IconButton(
                            onClick = {
                                LiveTvRecordingManager.stop(
                                    context,
                                    recording.id
                                )
                            },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.StopCircle, "Stop recording")
                        }
                    }

                    recording.isScheduled -> {
                        IconButton(
                            onClick = {
                                LiveTvRecordingManager.startScheduledNow(
                                    context,
                                    recording.id
                                )
                            },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.PlayArrow, "Start now")
                        }
                        IconButton(
                            onClick = {
                                LiveTvRecordingManager.stop(
                                    context,
                                    recording.id
                                )
                            },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                "Cancel scheduled recording"
                            )
                        }
                    }

                    else -> {
                        if (playable) {
                            IconButton(
                                onClick = {
                                    shareRecording(
                                        context,
                                        recording
                                    )
                                },
                                modifier =
                                    Modifier.remoteFocusFrame(
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    "Share recording"
                                )
                            }
                        }
                        IconButton(
                            onClick = { confirmDelete = true },
                            modifier =
                                Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                "Delete recording"
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    "Remove “${recording.title}” and its saved file from this device?"
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false }
                ) {
                    Text("Keep")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        LiveTvRecordingManager.delete(
                            context,
                            recording.id
                        )
                        confirmDelete = false
                    }
                ) {
                    Text("Delete")
                }
            }
        )
    }
}

private fun openRecording(
    context: Context,
    recording: LiveTvRecording
) {
    val uri =
        recording.outputUri?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?: return
    val view =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching { context.startActivity(view) }
        .onFailure {
            Toast.makeText(
                context,
                "No video player is available",
                Toast.LENGTH_SHORT
            ).show()
        }
}

private fun shareRecording(
    context: Context,
    recording: LiveTvRecording
) {
    val uri =
        recording.outputUri?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?: return
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, recording.title)
            clipData =
                ClipData.newUri(
                    context.contentResolver,
                    recording.title,
                    uri
                )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                send,
                "Share ${recording.title}"
            )
        )
    }.onFailure {
        Toast.makeText(
            context,
            "No app is available to share this recording",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun recordingTitle(
    channel: MediaItem,
    programme: LiveProgramme?
): String =
    programme?.title
        ?.takeIf(String::isNotBlank)
        ?.let { "${channel.title} · $it" }
        ?: channel.title

private fun recordingClock(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT)
        .format(Date(millis))

private fun recordingDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT
    ).format(Date(millis))
