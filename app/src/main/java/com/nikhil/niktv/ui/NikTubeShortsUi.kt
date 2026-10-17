package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun NikTubeShortsContent(vm: NikTubeMobileViewModel, options: (NikTubeVideo) -> Unit) {
    LaunchedEffect(Unit) { vm.loadShorts() }
    val pager = rememberPagerState { vm.shorts.size }
    LaunchedEffect(pager.settledPage, vm.shorts.size) {
        val video = vm.shorts.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (NikTubeSession.video?.id != video.id) { NikTubeSession.player?.pause(); vm.play(video, 0, showWatch = false) }
        if (pager.settledPage >= vm.shorts.size - 4) vm.loadShorts(true)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(pager, Modifier.fillMaxSize(), key = { vm.shorts[it].id }) { page ->
            val video = vm.shorts[page]
            val active = NikTubeSession.video?.takeIf { it.id == video.id }
            val player = NikTubeSession.player
            Box(Modifier.fillMaxSize().clickable { NikTubeSession.toggle() }) {
                AsyncImage(video.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (page == pager.settledPage && active != null && player != null) {
                    AndroidView(factory = { PlayerView(it).apply { useController = false } }, update = { if (it.player !== player) it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .6f)).padding(16.dp)) {
                    Text(active?.title ?: video.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(active?.channel ?: video.channel, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Row {
                        IconButton(onClick = NikTubeSession::toggle) { Icon(if (NikTubeSession.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { options(active ?: video) }) { Icon(Icons.Default.MoreVert, "Short actions", tint = Color.White) }
                    }
                }
                if (vm.opening?.id == video.id) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (active != null) NikTubeSession.failure?.let { message -> Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    Text(message); FilledTonalButton(onClick = { vm.play(video, 0, showWatch = false) }) { Text("Retry") }
                } }
            }
        }
        if (vm.shortsLoading && vm.shorts.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
        vm.shortsError?.let { message -> Column(Modifier.align(Alignment.Center).padding(24.dp)) { Text(message, color = Color.White); FilledTonalButton(onClick = { vm.loadShorts(vm.shorts.isNotEmpty()) }) { Text("Retry") } } }
    }
}
