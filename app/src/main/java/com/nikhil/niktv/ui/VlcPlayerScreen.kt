package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
internal fun VlcPlayerScreen(
    media: PlayingMedia,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    embeddedMode: Boolean = false
) {
    val context = LocalContext.current
    var playing by remember(media.progressKey) { mutableStateOf(true) }
    var position by remember(media.progressKey) { mutableLongStateOf(media.resumePositionMillis) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var error by remember(media.progressKey) { mutableStateOf<String?>(null) }
    val libVlc = remember(media.progressKey) { LibVLC(context, arrayListOf("--network-caching=1500", "--clock-jitter=0")) }
    val player = remember(media.progressKey) { MediaPlayer(libVlc) }

    BackHandler(onBack = onBack)
    DisposableEffect(player, media.url) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> playing = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EncounteredError -> error = "VLC could not play this stream."
            }
        }
        val vlcMedia = Media(libVlc, android.net.Uri.parse(media.url)).apply {
            // VLC's software path avoids the unstable OMX.MTK AVC implementation.
            setHWDecoderEnabled(false, false)
            addOption(":network-caching=1500")
        }
        player.media = vlcMedia
        vlcMedia.release()
        player.play()
        if (media.resumePositionMillis > 0) player.time = media.resumePositionMillis
        onDispose {
            val finalPosition = player.time.coerceAtLeast(0L)
            val finalDuration = player.length.coerceAtLeast(0L)
            if (media.progressKey.isNotBlank()) onProgress(media.progressKey, finalPosition, finalDuration)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
        }
    }

    LaunchedEffect(player, media.progressKey) {
        while (true) {
            position = player.time.coerceAtLeast(0L)
            duration = player.length.coerceAtLeast(0L)
            if (media.progressKey.isNotBlank()) onProgress(media.progressKey, position, duration)
            delay(1_000)
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { player.attachViews(it, null, false, false) }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (!embeddedMode) {
            Row(
                Modifier.align(Alignment.TopStart).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                Text(media.media.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text("VLC", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (duration > 0) {
                    Slider(
                        value = position.coerceAtMost(duration).toFloat(),
                        onValueChange = { position = it.toLong() },
                        onValueChangeFinished = { player.time = position },
                        valueRange = 0f..duration.toFloat()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPrevious, enabled = media.previousEpisode != null) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                    }
                    FilledIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                    }
                    IconButton(onClick = onPlayNext, enabled = media.nextEpisode != null) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
