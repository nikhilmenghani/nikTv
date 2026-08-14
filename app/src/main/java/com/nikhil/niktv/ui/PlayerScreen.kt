package com.nikhil.niktv.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun PlayerScreen(media: Pair<String, String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(media.second) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(media.second)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, Modifier.fillMaxSize())
        Text(media.first, Modifier.align(Alignment.TopStart).padding(20.dp), style = MaterialTheme.typography.titleLarge)
    }
}
