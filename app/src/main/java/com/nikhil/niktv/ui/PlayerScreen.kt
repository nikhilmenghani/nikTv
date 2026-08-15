package com.nikhil.niktv.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(media: PlayingMedia, onBack: () -> Unit, onPlayNext: () -> Unit, onProgress: (String, Long, Long) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var videoScale by remember(media.progressKey) { mutableFloatStateOf(1f) }
    var videoOffset by remember(media.progressKey) { mutableStateOf(Offset.Zero) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    var gestureFeedback by remember(media.progressKey) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val player = remember(media.progressKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(media.url))
            if (media.resumePositionMillis > 0L) seekTo(media.resumePositionMillis)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            onProgress(media.progressKey, player.currentPosition, player.duration)
            player.release()
        }
    }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(gestureFeedback) {
        if (gestureFeedback != null) {
            delay(800)
            gestureFeedback = null
        }
    }
    LaunchedEffect(player, media.nextEpisode, autoPlayCancelled) {
        if (media.nextEpisode == null || autoPlayCancelled) {
            remainingSeconds = null
            return@LaunchedEffect
        }
        while (true) {
            val duration = player.duration
            if (duration > 0) {
                val remainingMillis = (duration - player.currentPosition).coerceAtLeast(0L)
                val seconds = ((remainingMillis + 999L) / 1000L).toInt()
                remainingSeconds = seconds.takeIf { it <= 30 }
                if (seconds == 0 && !advancing) {
                    advancing = true
                    onPlayNext()
                    return@LaunchedEffect
                }
            }
            delay(500)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            onProgress(media.progressKey, player.currentPosition, player.duration)
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    val playerView = this
                    this.player = player
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    var lastFocus = Offset.Zero
                    var lastTouch = Offset.Zero
                    var gestureStartY = 0f
                    var gestureStartValue = 0f
                    var brightnessGesture = false
                    var adjustingLevel = false
                    fun applyVideoTransform() {
                        videoSurfaceView?.apply {
                            scaleX = videoScale
                            scaleY = videoScale
                            translationX = videoOffset.x
                            translationY = videoOffset.y
                        }
                    }
                    fun moveVideoBy(delta: Offset) {
                        val maxX = playerView.width * (videoScale - 1f) / 2f
                        val maxY = playerView.height * (videoScale - 1f) / 2f
                        videoOffset = Offset(
                            (videoOffset.x + delta.x).coerceIn(-maxX, maxX),
                            (videoOffset.y + delta.y).coerceIn(-maxY, maxY)
                        )
                        applyVideoTransform()
                    }
                    val scaleDetector = ScaleGestureDetector(viewContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            lastFocus = Offset(detector.focusX, detector.focusY)
                            return true
                        }

                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val newScale = (videoScale * detector.scaleFactor).coerceIn(1f, 3f)
                            val focus = Offset(detector.focusX, detector.focusY)
                            if (newScale == 1f) videoOffset = Offset.Zero
                            else {
                                val pan = focus - lastFocus
                                val maxX = playerView.width * (newScale - 1f) / 2f
                                val maxY = playerView.height * (newScale - 1f) / 2f
                                videoOffset = Offset(
                                    (videoOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (videoOffset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            }
                            videoScale = newScale
                            lastFocus = focus
                            applyVideoTransform()
                            return true
                        }
                    })
                    setOnTouchListener { _, event ->
                        scaleDetector.onTouchEvent(event)
                        var panned = false
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastTouch = Offset(event.x, event.y)
                                gestureStartY = event.y
                                brightnessGesture = event.x < playerView.width / 2f
                                adjustingLevel = false
                                gestureStartValue = if (brightnessGesture) {
                                    val windowValue = activity?.window?.attributes?.screenBrightness ?: -1f
                                    if (windowValue >= 0f) windowValue else {
                                        Settings.System.getInt(viewContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                    }
                                } else {
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                }
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> lastTouch = Offset(event.x, event.y)
                            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                                if (videoScale > 1f) {
                                    val current = Offset(event.x, event.y)
                                    val delta = current - lastTouch
                                    if (delta.getDistance() > 1f) {
                                        moveVideoBy(delta)
                                        panned = true
                                    }
                                    lastTouch = current
                                } else {
                                    val deltaY = gestureStartY - event.y
                                    if (adjustingLevel || kotlin.math.abs(deltaY) > 24f * resources.displayMetrics.density) {
                                        adjustingLevel = true
                                        val level = (gestureStartValue + deltaY / playerView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        if (brightnessGesture) {
                                            activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                                        } else {
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                        }
                                        gestureFeedback = brightnessGesture to level
                                    }
                                }
                            }
                        }
                        scaleDetector.isInProgress || event.pointerCount > 1 || panned || adjustingLevel
                    }
                }
            },
            update = { playerView ->
                if (playerView.player !== player) playerView.player = player
                playerView.videoSurfaceView?.apply {
                    scaleX = videoScale
                    scaleY = videoScale
                    translationX = videoOffset.x
                    translationY = videoOffset.y
                }
            },
            modifier = Modifier
                .fillMaxSize()
        )
        gestureFeedback?.let { (isBrightness, level) ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
            ) {
                Column(
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp).width(180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (isBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                        contentDescription = null
                    )
                    Text(if (isBrightness) "Brightness" else "Volume", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    Text("${(level * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            media.media.title,
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
        val countdown = remainingSeconds
        if (countdown != null && media.nextEpisode != null && !autoPlayCancelled) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Up next in ${countdown}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(media.nextEpisode.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    TextButton(onClick = { autoPlayCancelled = true }) { Text("Cancel") }
                    Button(onClick = { if (!advancing) { advancing = true; onPlayNext() } }) { Text("Play now") }
                }
            }
        }
    }
}
