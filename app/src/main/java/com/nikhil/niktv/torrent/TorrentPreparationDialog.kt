package com.nikhil.niktv.torrent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.ui.remoteCombinedClickable
import kotlinx.coroutines.launch

/** Soft breaks at separators preserve filename groups and never change the actual path. */
internal fun torrentPreparationFileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    .replace(".", ".\u200B").replace("_", "_\u200B").replace("-", "-\u200B")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TorrentPreparationDialog(state: TorrentPortalState, cancel: () -> Unit,
    retry: () -> Unit, select: (TorrentVideoFile) -> Unit) {
    val configuration = LocalConfiguration.current
    val short = configuration.screenHeightDp < 480
    val cancelFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val filesScroll = rememberLazyListState()
    val targets = remember { mutableMapOf<Int, FocusRequester>() }
    var lastFocusedFile by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val transfer = state.transfer
    val failure = state.error ?: transfer?.error
    val checking = transfer?.stage?.startsWith("Checking", ignoreCase = true) == true
    val choosing = state.files.size > 1 && state.loading == null
    val download = state.transferPurpose == TorrentTransferPurpose.DOWNLOAD
    fun returnToFiles() {
        val index = state.files.indexOfFirst { it.index == lastFocusedFile }.takeIf { it >= 0 } ?: 0
        val file = state.files.getOrNull(index) ?: return
        scope.launch {
            filesScroll.scrollToItem(index + 1)
            withFrameNanos { }
            targets[file.index]?.requestFocus()
        }
    }
    fun Modifier.footerNavigation() = onPreviewKeyEvent {
        if (it.key == Key.DirectionUp && state.files.isNotEmpty()) {
            if (it.type == KeyEventType.KeyDown) returnToFiles()
            true
        } else false
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = if (short) 12.dp else 20.dp, vertical = 12.dp)
            .widthIn(max = minOf(520.dp, (configuration.screenWidthDp - if (short) 24 else 40).coerceAtLeast(160).dp)).fillMaxWidth()
            .heightIn(max = (configuration.screenHeightDp * .84f).dp)
            .initialFocusEntry(cancelFocus), shape = RoundedCornerShape(16.dp),
            color = Color(0xF5171B24), contentColor = Color.White, border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
            Column(Modifier.padding(if (short) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp)) {
                Text(if (choosing) { if (download) "Choose file to download" else "Choose video" }
                    else if (download) "Preparing download" else "Preparing stream",
                    fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Short lists wrap their contents; only this body scrolls, leaving actions visible.
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = filesScroll,
                    contentPadding = PaddingValues(2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "transfer-summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loading != null) Text(if (checking) "Checking saved parts…" else transfer?.stage ?: state.loading,
                                fontSize = 13.sp, color = Color.White.copy(alpha = .8f))
                            if (state.loading != null && failure == null) {
                                if (checking || transfer == null || transfer.totalBytes <= 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                                else LinearProgressIndicator(progress = { (transfer.downloadedBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(3.dp))
                            }
                            transfer?.let {
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Saved data ${torrentSize(it.downloadedBytes)} / ${torrentSize(it.totalBytes)}", fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
                                    Text("${torrentSize(it.downloadBytesPerSecond)}/s · ${it.peers} peers", fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
                                }
                                it.storageWarning?.let { warning -> Text(warning, fontSize = 12.sp, color = Color(0xFFFFD79A)) }
                            }
                            failure?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                            if (choosing) Text(if (download) "Select a file to download." else "Select a video to prepare playback.",
                                fontSize = 12.sp, color = Color.White.copy(alpha = .65f))
                        }
                    }
                    itemsIndexed(state.files, key = { _, file -> "file:${file.index}" }) { index, file ->
                        val target = remember(file.index) { targets.getOrPut(file.index) { FocusRequester() } }
                        TorrentPreparationFileCard(file, download, Modifier.focusRequester(target).onFocusChanged {
                            if (it.isFocused) lastFocusedFile = file.index
                        }.focusProperties {
                            if (index == 0) up = cancelFocus
                            if (index == state.files.lastIndex) down = cancelFocus
                            left = cancelFocus; right = cancelFocus
                        }) { select(file) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (checking) "Reusing saved parts" else if (state.files.isNotEmpty())
                        "${state.files.size} ${if (state.files.size == 1) "file" else "files"}" else "",
                        Modifier.weight(1f), fontSize = 11.sp, color = Color.White.copy(alpha = .55f), maxLines = 2)
                    if (failure != null) NikTvSecondaryActionButton(retry,
                        Modifier.heightIn(min = 40.dp).focusRequester(retryFocus).footerNavigation()
                            .focusProperties { right = cancelFocus; down = FocusRequester.Cancel }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry", fontSize = 13.sp)
                    }
                    NikTvSecondaryActionButton(cancel, Modifier.heightIn(min = 40.dp).focusRequester(cancelFocus).footerNavigation()
                        .focusProperties { down = FocusRequester.Cancel; if (failure != null) left = retryFocus }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TorrentPreparationFileCard(file: TorrentVideoFile, download: Boolean, modifier: Modifier, select: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = "Select file: ${file.name}" }
        .remoteCombinedClickable(onClick = select, onLongClick = null,
            interactionSource = remember { MutableInteractionSource() }, showIndication = false),
        shape = shape, color = if (focused) Color(0xFF303A49) else Color.White.copy(alpha = .055f), contentColor = Color.White,
        border = BorderStroke(2.dp, Color.White.copy(alpha = if (focused) .95f else .15f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (file.media) Icons.Default.VideoFile else Icons.Default.InsertDriveFile, null, Modifier.size(24.dp), tint = Color.White.copy(alpha = .6f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(torrentPreparationFileName(file.name), fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val folder = file.name.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/')
                Text(listOf(folder.takeIf { it.isNotBlank() }, file.name.substringAfterLast('.', "File").uppercase(), torrentSize(file.sizeBytes)).filterNotNull().joinToString(" · "),
                    fontSize = 12.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (download || !file.media) Icons.Default.Download else Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = .7f))
        }
    }
}
