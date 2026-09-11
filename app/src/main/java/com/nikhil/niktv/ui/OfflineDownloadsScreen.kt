package com.nikhil.niktv.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.nikhil.niktv.data.AppStorageSnapshot
import com.nikhil.niktv.data.appStorageSnapshot
import com.nikhil.niktv.data.clearAppCaches
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun OfflineDownloadsScreen(
    state: NikTvState,
    play: (OfflineMediaDownload) -> Unit,
    remove: (MediaItem, CatalogType) -> Unit,
    removeAll: () -> Unit,
    close: () -> Unit
) {
    val context = LocalContext.current
    val profileKey = state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()
    val entries = state.offlineDownloads.filter { it.profileKey == profileKey }
    var pendingRemoval by remember { mutableStateOf<OfflineMediaDownload?>(null) }
    var pendingClear by remember { mutableStateOf<String?>(null) }
    var storageRevision by remember { mutableLongStateOf(0L) }
    var storage by remember { mutableStateOf<AppStorageSnapshot?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.offlineDownloadRevision, storageRevision) {
        storage = runCatching { appStorageSnapshot(context) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090909))) {
        ModernScreenTopBar("Offline downloads", close)
        OfflineStorageCard(
            storage = storage,
            clearing = clearing,
            onClearDownloads = { pendingClear = "downloads" },
            onClearCache = { pendingClear = "cache" }
        )
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.DownloadDone, null, Modifier.size(54.dp), tint = Color.Gray)
                    Text("No offline downloads", style = MaterialTheme.typography.titleLarge)
                    Text("Downloaded movies and episodes remain available without internet.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).forEach { type ->
                    val group = entries.filter { it.catalogType == type }
                    item("offline-header-${type.name}") {
                        Text(type.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    }
                    if (group.isEmpty()) {
                        item("offline-empty-${type.name}") { Text("No downloads", color = Color.DarkGray) }
                    } else items(group, key = { it.key }) { entry ->
                        val info = remember(entry.requestId, state.offlineDownloadRevision) {
                            OfflineMediaDownloads.info(context, entry.requestId, entry.downloadId)
                        }
                        Surface(
                            onClick = { play(entry) },
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF151820),
                            modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(14.dp))
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SubcomposeAsyncImage(
                                    model = artworkRequest(context, entry.media),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(112.dp, 64.dp).clip(RoundedCornerShape(9.dp)).background(Color.DarkGray)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(entry.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    entry.series?.let { Text(it.title, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                                    val statusText = when (info.status) {
                                        OfflineDownloadStatus.COMPLETE -> if (info.bytesDownloaded > 0L) {
                                            "Available offline · ${formatOfflineBytes(info.bytesDownloaded)}"
                                        } else "Available offline"
                                        OfflineDownloadStatus.DOWNLOADING -> info.progressLabel()
                                        OfflineDownloadStatus.QUEUED -> "Queued"
                                        OfflineDownloadStatus.PAUSED -> "Paused"
                                        OfflineDownloadStatus.FAILED -> "Download failed"
                                        OfflineDownloadStatus.MISSING -> "Not downloaded"
                                    }
                                    Text(statusText, color = if (info.status == OfflineDownloadStatus.COMPLETE) MaterialTheme.colorScheme.primary else Color.LightGray, style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        if (entry.requestId.startsWith("hls-export:")) "${entry.fileType.ifBlank { "HLS → MP4" }} · Download/NikTV"
                                        else if (entry.downloadId >= 0L) "${entry.fileType.ifBlank { "Video" }} · Download/NikTV · external players"
                                        else "Legacy HLS cache · NikTV only",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    info.percent?.takeIf { info.status == OfflineDownloadStatus.DOWNLOADING }?.let {
                                        LinearProgressIndicator(progress = { it / 100f }, Modifier.fillMaxWidth().padding(top = 5.dp))
                                    }
                                }
                                IconButton(onClick = { pendingRemoval = entry }) {
                                    Icon(Icons.Default.Delete, "Delete offline download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(if (OfflineMediaDownloads.status(context, entry.requestId, entry.downloadId) == OfflineDownloadStatus.COMPLETE) "Delete download?" else "Cancel download?") },
            text = { Text("Remove “${entry.media.title}” from offline downloads?") },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Keep") } },
            confirmButton = { Button(onClick = { remove(entry.media, entry.catalogType); pendingRemoval = null }) { Text("Remove") } }
        )
    }
    pendingClear?.let { target ->
        val downloads = target == "downloads"
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(if (downloads) "Clear offline downloads?" else "Clear app cache?") },
            text = {
                Text(
                    if (downloads) {
                        "This removes every downloaded movie and episode. Profiles, settings, and watch history are kept."
                    } else {
                        "This clears temporary artwork and subtitle files. They will be downloaded again when needed."
                    }
                )
            },
            dismissButton = { TextButton(onClick = { pendingClear = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    pendingClear = null
                    clearing = true
                    scope.launch {
                        if (downloads) {
                            removeAll()
                            delay(1_000L)
                        } else {
                            clearAppCaches(context)
                        }
                        storageRevision++
                        clearing = false
                    }
                }) { Text("Clear") }
            }
        )
    }
}

@Composable
private fun OfflineStorageCard(
    storage: AppStorageSnapshot?,
    clearing: Boolean,
    onClearDownloads: () -> Unit,
    onClearCache: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF151820),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (clearing || storage == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            storage?.let { snapshot ->
                val usedBytes = (snapshot.totalBytes - snapshot.availableBytes).coerceAtLeast(0L)
                LinearProgressIndicator(
                    progress = { if (snapshot.totalBytes > 0L) usedBytes.toFloat() / snapshot.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp))
                )
                Text(
                    "${formatDownloadBytes(snapshot.availableBytes)} available of ${formatDownloadBytes(snapshot.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                StorageUsageRow(
                    label = "Offline downloads",
                    bytes = snapshot.offlineDownloadBytes,
                    enabled = !clearing && snapshot.offlineDownloadBytes > 0L,
                    onClear = onClearDownloads
                )
                StorageUsageRow(
                    label = "Artwork and subtitle cache",
                    bytes = snapshot.cacheBytes,
                    enabled = !clearing && snapshot.cacheBytes > 0L,
                    onClear = onClearCache
                )
            }
        }
    }
}

@Composable
private fun StorageUsageRow(
    label: String,
    bytes: Long,
    enabled: Boolean,
    onClear: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatDownloadBytes(bytes), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
        TextButton(
            onClick = onClear,
            enabled = enabled,
            modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp))
        ) { Text("Clear") }
    }
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
    SearchContentType.LIVE_TV -> FavoriteKind.CHANNEL
    SearchContentType.MOVIES -> FavoriteKind.MOVIE
    SearchContentType.SERIES -> FavoriteKind.SERIES
    SearchContentType.EPISODES -> FavoriteKind.EPISODE
}
