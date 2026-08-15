package com.nikhil.niktv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nikhil.niktv.R
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(media: PlayingMedia, onBack: () -> Unit, onPlayNext: () -> Unit, onProgress: (String, Long, Long) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var videoScale by remember(media.progressKey) { mutableFloatStateOf(1f) }
    var videoOffset by remember(media.progressKey) { mutableStateOf(Offset.Zero) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    var focusMode by remember(media.progressKey) { mutableStateOf(false) }
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
    DisposableEffect(activity, focusMode) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (focusMode) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
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
    BackHandler {
        if (focusMode) focusMode = false else onBack()
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    val playerView = this
                    this.player = player
                    useController = true
                    setFullscreenButtonClickListener { fullScreen ->
                        focusMode = fullScreen
                    }
                    val controlSize = (48f * resources.displayMetrics.density).toInt()
                    val controlPadding = (12f * resources.displayMetrics.density).toInt()
                    val settingsButton = findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                    val settingsContainer = settingsButton?.parent as? ViewGroup
                    val settingsIndex = settingsContainer?.indexOfChild(settingsButton) ?: -1
                    fun playerControlButton(icon: Int, description: String, action: () -> Unit) =
                        ImageButton(viewContext).apply {
                            setImageResource(icon)
                            contentDescription = description
                            background = null
                            isClickable = true
                            isFocusable = true
                            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                            setPadding(controlPadding, controlPadding, controlPadding, controlPadding)
                            layoutParams = ViewGroup.LayoutParams(controlSize, controlSize)
                            setOnClickListener { action() }
                        }
                    if (settingsContainer != null && settingsIndex >= 0) {
                        settingsContainer.addView(
                            playerControlButton(R.drawable.ic_player_rotate, "Rotate screen") {
                                val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            },
                            settingsIndex + 1
                        )
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    var lastFocus = Offset.Zero
                    var lastTouch = Offset.Zero
                    var gestureStartY = 0f
                    var gestureStartValue = 0f
                    var brightnessGesture = false
                    var adjustingLevel = false
                    var gestureConsumed = false
                    var tapCandidate = false
                    val tapSlop = 14f * resources.displayMetrics.density
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
                    setOnKeyListener { _, keyCode, keyEvent ->
                        if (keyEvent.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (player.isPlaying) player.pause() else player.play()
                                showController()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                player.play()
                                showController()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                player.pause()
                                showController()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val maxPosition = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(maxPosition))
                                showController()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                showController()
                                true
                            }
                            else -> false
                        }
                    }
                    setOnTouchListener { touchedView, event ->
                        scaleDetector.onTouchEvent(event)
                        var panned = false
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastTouch = Offset(event.x, event.y)
                                gestureStartY = event.y
                                brightnessGesture = event.x < playerView.width / 2f
                                adjustingLevel = false
                                gestureConsumed = false
                                tapCandidate = true
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
                                val movement = Offset(event.x, event.y) - lastTouch
                                if (movement.getDistance() > tapSlop) tapCandidate = false
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
                            MotionEvent.ACTION_UP -> {
                                if (tapCandidate && !gestureConsumed && !adjustingLevel && !scaleDetector.isInProgress) {
                                    if (playerView.isControllerFullyVisible) playerView.hideController()
                                    else playerView.showController()
                                    gestureConsumed = true
                                }
                            }
                        }
                        val consumed = gestureConsumed || scaleDetector.isInProgress || event.pointerCount > 1 || panned || adjustingLevel
                        if (consumed) gestureConsumed = true
                        consumed
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
                modifier = Modifier
                    .align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
            ) {
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp).width(136.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (isBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                        contentDescription = null
                    )
                    Text(if (isBrightness) "Brightness" else "Volume", style = MaterialTheme.typography.labelLarge)
                    Box(
                        modifier = Modifier
                            .height(120.dp)
                            .width(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            // Track container for the vertical level meter.
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Fill from the bottom so the meter behaves like a vertical slider.
                                .fillMaxHeight(level)
                                .align(Alignment.BottomCenter)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text("${(level * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (!focusMode) {
            Text(
                media.media.title,
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 16.dp, end = 72.dp),
                style = MaterialTheme.typography.titleLarge
            )
        }
        val countdown = remainingSeconds
        if (!focusMode && countdown != null && media.nextEpisode != null && !autoPlayCancelled) {
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
