package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.R
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.data.LiveTvRecorder
import com.nikhil.niktv.data.RecordedLiveTvMedia
import com.nikhil.niktv.data.AppStorageSnapshot
import com.nikhil.niktv.data.appStorageSnapshot
import com.nikhil.niktv.data.clearArtworkCache
import com.nikhil.niktv.data.clearSubtitleCache
import com.nikhil.niktv.data.clearHlsWorkingFiles
import com.nikhil.niktv.data.clearTemporaryCaches
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun OfflineDownloadsScreen(
    state: NikTvState,
    play: (OfflineMediaDownload) -> Unit,
    playRecording: (RecordedLiveTvMedia) -> Unit,
    remove: (MediaItem, CatalogType) -> Unit,
    close: () -> Unit
) {
    val context = LocalContext.current
    val profileKey = state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()
    val owner = LocalViewingOwner.current
    // The ViewModel already scopes downloads to the person. A portal is not required
    // to browse their local library, including files downloaded from another portal.
    val entries = if (owner != null) state.offlineDownloads else state.offlineDownloads.filter { it.profileKey == profileKey }
    val libraryKey = owner?.let { "${it.accountId}|${it.profileId}" } ?: profileKey
    val recordingOwnership = remember(context) { com.nikhil.niktv.data.RecordingOwnership(context) }
    val scope = rememberCoroutineScope()
    var unassignedRecordings by remember(libraryKey) { mutableStateOf<List<RecordedLiveTvMedia>>(emptyList()) }
    var importingRecordings by remember(libraryKey) { mutableStateOf(false) }
    val remoteNavigationActive = context.usesRemoteNavigation(LocalConfiguration.current) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val entryFocusRequester = remember(libraryKey) { FocusRequester() }
    var entryFocusComplete by rememberSaveable(libraryKey) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var pendingRemoval by remember { mutableStateOf<OfflineMediaDownload?>(null) }
    var pendingRecordingRemoval by remember { mutableStateOf<RecordedLiveTvMedia?>(null) }
    var recordings by remember { mutableStateOf<List<RecordedLiveTvMedia>>(emptyList()) }
    var recordingsLoaded by remember { mutableStateOf(false) }
    val liveRecording by LiveTvRecorder.state.collectAsState()
    var storageRevision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(libraryKey, storageRevision, liveRecording.active) {
        val local = withContext(Dispatchers.IO) {
            val all = LiveTvRecorder.recordings(context)
            recordingOwnership.visible(all, owner) to
                (if (owner != null) recordingOwnership.unassigned(all) else emptyList())
        }
        recordings = local.first
        unassignedRecordings = local.second
        recordingsLoaded = true
    }
    val firstRecordingUri = recordings.firstOrNull()?.uri
    val firstEntryKey = if (recordings.isEmpty()) {
        listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
            .firstNotNullOfOrNull { type -> entries.firstOrNull { it.catalogType == type }?.key }
    } else null
    val hasEntryMedia = firstRecordingUri != null || firstEntryKey != null
    LaunchedEffect(remoteNavigationActive, recordingsLoaded, firstRecordingUri, firstEntryKey) {
        if (!remoteNavigationActive || !recordingsLoaded || entryFocusComplete) return@LaunchedEffect
        if (hasEntryMedia) listState.scrollToItem(1) // The media group heading comes first.
        repeat(6) { attempt ->
            withFrameNanos { }
            if (runCatching { entryFocusRequester.requestFocus() }.getOrDefault(false)) {
                entryFocusComplete = true
                return@LaunchedEffect
            }
            delay(40L * (attempt + 1))
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090909))) {
        ModernScreenTopBar("Offline downloads", close,
            backModifier = if (!hasEntryMedia) Modifier.focusRequester(entryFocusRequester) else Modifier)
        if (owner != null && unassignedRecordings.isNotEmpty()) NikTvTextActionButton(
            onClick = { importingRecordings = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Import device recordings (${unassignedRecordings.size})", style = MaterialTheme.typography.labelMedium)
        }
        if (entries.isEmpty() && recordings.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 18.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.DownloadDone, null, Modifier.size(54.dp), tint = Color.Gray)
                    Text("No offline media", style = MaterialTheme.typography.titleLarge)
                    Text("Downloaded movies, episodes, and Live TV recordings will appear here.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (recordings.isNotEmpty()) {
                    item("recordings-header") {
                        Text("Recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    }
                    items(recordings, key = { it.uri.toString() }) { recording ->
                        val returningTile = rememberReturningTile { playRecording(recording) }
                        OfflineMediaCard(
                            title = recording.title,
                            subtitle = "Live TV recording · MPEG-TS",
                            detail = "${formatRecordingDuration(recording.durationMillis)} · ${formatOfflineBytes(recording.sizeBytes)}",
                            icon = Icons.Default.FiberManualRecord,
                            playable = true,
                            initialFocus = entryFocusRequester.takeIf { recording.uri == firstRecordingUri },
                            playModifier = returningTile.modifier,
                            play = returningTile.open,
                            share = { shareOfflineVideo(context, recording.uri, recording.title) },
                            remove = { pendingRecordingRemoval = recording }
                        )
                    }
                }
                listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).forEach { type ->
                    val group = entries.filter { it.catalogType == type }
                    if (group.isNotEmpty()) {
                        item("offline-header-${type.name}") {
                            Text(type.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                    }
                    items(group, key = { it.key }) { entry ->
                        val returningTile = rememberReturningTile { play(entry) }
                        val info = observedOfflineDownloadInfo(entry)
                            ?: com.nikhil.niktv.data.OfflineDownloadInfo(OfflineDownloadStatus.QUEUED)
                        val complete = info.status == OfflineDownloadStatus.COMPLETE
                        val statusText = when (info.status) {
                            OfflineDownloadStatus.COMPLETE -> "Available offline"
                            OfflineDownloadStatus.DOWNLOADING -> info.percent?.let { "Downloading · ${it.toInt()}%" } ?: "Downloading"
                            OfflineDownloadStatus.QUEUED -> "Queued"
                            OfflineDownloadStatus.PAUSED -> "Paused"
                            OfflineDownloadStatus.FAILED -> "Download failed"
                            OfflineDownloadStatus.MISSING -> "Not downloaded"
                        }
                        val sizeText = when {
                            complete -> (info.totalBytes ?: info.bytesDownloaded).takeIf { it > 0L }?.let(::formatOfflineBytes)
                            info.totalBytes != null && info.totalBytes > 0L -> "${formatOfflineBytes(info.bytesDownloaded)} / ${formatOfflineBytes(info.totalBytes)}"
                            info.bytesDownloaded > 0L -> formatOfflineBytes(info.bytesDownloaded)
                            else -> null
                        }
                        OfflineMediaCard(
                            title = entry.media.title,
                            subtitle = listOfNotNull(entry.series?.title, statusText).joinToString(" · "),
                            detail = listOfNotNull(sizeText, entry.fileType.takeIf { it.isNotBlank() }).joinToString(" · "),
                            icon = Icons.Default.DownloadDone,
                            playable = complete,
                            initialFocus = entryFocusRequester.takeIf { entry.key == firstEntryKey },
                            playModifier = returningTile.modifier,
                            play = returningTile.open,
                            share = {
                                val uri = OfflineMediaDownloads.shareableUri(context, entry.requestId, entry.downloadId)
                                if (uri != null) shareOfflineVideo(context, uri, entry.media.title)
                                else Toast.makeText(context, "The offline file is not available to share", Toast.LENGTH_SHORT).show()
                            },
                            remove = { pendingRemoval = entry },
                            progress = info.percent?.takeIf { info.status == OfflineDownloadStatus.DOWNLOADING }
                        )
                    }
                }
            }
        }
    }
    if (importingRecordings && owner != null) AlertDialog(
        onDismissRequest = { importingRecordings = false }, title = { Text("Import device recordings?") },
        text = { Text("Assign older recordings without a profile to this person on this device. Other people's recordings stay separate. Files remain local.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = {
            importingRecordings = false
            val captured = unassignedRecordings
            scope.launch {
                withContext(Dispatchers.IO) { recordingOwnership.assignUnassigned(owner, captured) }
                storageRevision++
            }
        }) { Text("Import") } },
        dismissButton = { NikTvTextActionButton(onClick = { importingRecordings = false }) { Text("Cancel") } })

    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(if (observedOfflineDownloadInfo(entry)?.status == OfflineDownloadStatus.COMPLETE) "Delete download?" else "Cancel download?") },
            text = { Text("Remove “${entry.media.title}” from offline downloads?") },
            dismissButton = { NikTvTextActionButton(onClick = { pendingRemoval = null }) { Text("Keep") } },
            confirmButton = { NikTvPrimaryActionButton(onClick = { remove(entry.media, entry.catalogType); pendingRemoval = null }) { Text("Remove") } }
        )
    }
    pendingRecordingRemoval?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingRecordingRemoval = null },
            title = { Text("Delete recording?") },
            text = { Text("Remove “${recording.title}” from this device?") },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NikTvSecondaryActionButton(
                        onClick = { pendingRecordingRemoval = null },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Keep", style = MaterialTheme.typography.labelLarge)
                    }
                    NikTvPrimaryActionButton(
                        onClick = {
                            LiveTvRecorder.delete(context, recording)
                            recordings = recordings.filterNot { it.uri == recording.uri }
                            storageRevision++
                            pendingRecordingRemoval = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF181818),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

private fun shareOfflineVideo(context: Context, uri: android.net.Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share $title")) }
        .onFailure { Toast.makeText(context, "No app is available to share this video", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun OfflineMediaCard(
    title: String, subtitle: String, detail: String, icon: ImageVector,
    playable: Boolean, initialFocus: FocusRequester?, playModifier: Modifier,
    play: () -> Unit, share: () -> Unit, remove: () -> Unit, progress: Float? = null
) {
    val playFocus = remember { FocusRequester() }
    val shareFocus = remember { FocusRequester() }
    val deleteFocus = remember { FocusRequester() }
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF151820),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .06f)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = Color(0xFFBDC3CF), style = MaterialTheme.typography.bodySmall)
                    if (detail.isNotBlank()) Text(detail, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                    progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .09f))
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvPrimaryActionButton(onClick = play, enabled = playable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).then(playModifier)
                        .then(if (playable && initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier)
                        .focusRequester(playFocus).focusProperties { right = shareFocus }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Play", style = MaterialTheme.typography.labelMedium)
                }
                NikTvSecondaryActionButton(onClick = share, enabled = playable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).focusRequester(shareFocus).focusProperties { left = playFocus; right = deleteFocus }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Share", style = MaterialTheme.typography.labelMedium)
                }
                NikTvSecondaryActionButton(onClick = remove,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                        .then(if (!playable && initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier)
                        .focusRequester(deleteFocus).focusProperties { if (playable) left = shareFocus }) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Remove", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun formatRecordingDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "Duration unavailable"
    val totalSeconds = durationMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

internal fun MediaItem.displayTitle(series: MediaItem): String {
    val original = title.trim()
    var cleaned = original

    val seriesNames = listOf(
        series.title.trim(),
        series.title.substringAfter(':').substringBefore(" - ").trim()
    ).filter { it.length >= 5 }.distinct()
    seriesNames.forEach { seriesName ->
        cleaned = cleaned.replaceFirst(
            Regex("^${Regex.escape(seriesName)}\\s*[-:|.]*\\s*", RegexOption.IGNORE_CASE),
            ""
        )
    }

    cleaned = cleaned
        .replaceFirst(
            Regex("^\\s*(?:S\\d+\\s*[:._-]?\\s*E(?:P(?:ISODE)?)?\\s*\\d+|(?:EPISODE|EP|E)\\s*#?\\s*\\d+)\\s*[. :|\\-–—]*\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        .replaceFirst(Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\s*[. :|\\-–—]*\\s*"), "")
        .trim(' ', '.', ':', '-', '–', '—', '|')

    return cleaned.takeIf { it.isNotBlank() } ?: original
}

internal fun MediaItem.displayAirDate(): String? = episodeAirDate?.takeIf(String::isNotBlank)
    ?: Regex("\\b(?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b")
        .find(title)
        ?.value

internal fun String.seasonNumberFromTitle(): Int? {
    val patterns = listOf(
        Regex("(?i)S(?:EASON)?[ ._-]*(\\d+)"),
        Regex("(?i)S(\\d+)[ ._-]*E"),
        Regex("(?i)Season[ ._-]*(\\d+)")
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.findAll(this).firstOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

internal fun naturalTitleCompare(first: String, second: String): Int {
    val tokenPattern = Regex("\\d+|\\D+")
    val firstTokens = tokenPattern.findAll(first.lowercase()).map { it.value }.toList()
    val secondTokens = tokenPattern.findAll(second.lowercase()).map { it.value }.toList()
    for (index in 0 until minOf(firstTokens.size, secondTokens.size)) {
        val firstToken = firstTokens[index]
        val secondToken = secondTokens[index]
        val comparison = if (firstToken.all(Char::isDigit) && secondToken.all(Char::isDigit))
            (firstToken.toLongOrNull() ?: Long.MAX_VALUE).compareTo(secondToken.toLongOrNull() ?: Long.MAX_VALUE)
        else firstToken.compareTo(secondToken)
        if (comparison != 0) return comparison
    }
    return firstTokens.size.compareTo(secondTokens.size)
}

internal fun episodeComparator(descending: Boolean): Comparator<MediaItem> = Comparator { first, second ->
    val firstSeason = first.seasonNumber ?: first.title.seasonNumberFromTitle()
    val secondSeason = second.seasonNumber ?: second.title.seasonNumberFromTitle()
    val seasonComp = when {
        firstSeason != null && secondSeason != null && firstSeason != secondSeason -> firstSeason.compareTo(secondSeason)
        firstSeason != null && secondSeason == null -> 1
        firstSeason == null && secondSeason != null -> -1
        else -> 0
    }
    if (seasonComp != 0) {
        return@Comparator if (descending) -seasonComp else seasonComp
    }
    val firstEp = first.episodeNumber ?: first.title.episodeNumberFromTitle()
    val secondEp = second.episodeNumber ?: second.title.episodeNumberFromTitle()
    val epComp = when {
        firstEp != null && secondEp != null && firstEp != secondEp -> firstEp.compareTo(secondEp)
        firstEp != null && secondEp == null -> 1
        firstEp == null && secondEp != null -> -1
        else -> naturalTitleCompare(first.title, second.title)
    }
    if (descending) -epComp else epComp
}

internal fun cast4kStyleDeviceMacAddress(context: android.content.Context): String {
    return cast4kLegacyDeviceIdentity(context).macAddress
}
internal fun SearchContentType.favoriteKind() = when (this) {
    SearchContentType.ALL -> error("Global results require a concrete media type")
    SearchContentType.LIVE_TV -> FavoriteKind.CHANNEL
    SearchContentType.MOVIES -> FavoriteKind.MOVIE
    SearchContentType.SERIES -> FavoriteKind.SERIES
    SearchContentType.EPISODES -> FavoriteKind.EPISODE
}
