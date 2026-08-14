package com.nikhil.niktv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun PlayerScreen(media: Pair<String, String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val player = remember(media.second) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(media.second)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, Modifier.fillMaxSize())
        Text(
            media.first,
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 16.dp, end = 72.dp),
            style = MaterialTheme.typography.titleLarge
        )
        FilledTonalIconButton(
            onClick = {
                activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)
        ) {
            Icon(
                if (isLandscape) Icons.Default.StayCurrentPortrait else Icons.Default.ScreenRotation,
                contentDescription = if (isLandscape) "Switch to portrait" else "Switch to landscape"
            )
        }
    }
}
