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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nikhil.niktv.R
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    media: PlayingMedia,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var videoScale by remember(media.progressKey) { mutableFloatStateOf(1f) }
    var videoOffset by remember(media.progressKey) { mutableStateOf(Offset.Zero) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    var focusMode by remember(media.progressKey) { mutableStateOf(false) }
    var gestureFeedback by remember(media.progressKey) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var controlsVisible by remember(media.progressKey) { mutableStateOf(true) }
    var isPlaying by remember(media.progressKey) { mutableStateOf(false) }
    var playbackState by remember(media.progressKey) { mutableIntStateOf(Player.STATE_IDLE) }
    var position by remember(media.progressKey) { mutableLongStateOf(0L) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var playbackError by remember(media.progressKey) { mutableStateOf<String?>(null) }
    var startupTimedOut by remember(media.progressKey) { mutableStateOf(false) }
    var playerViewRef by remember(media.progressKey) { mutableStateOf<PlayerView?>(null) }
    val playNextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val player = remember(media.progressKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(media.url))
            if (media.resumePositionMillis > 0L) seekTo(media.resumePositionMillis)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) startupTimedOut = false
            }
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = buildString {
                    append(error.errorCodeName.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                    error.cause?.message?.takeIf { it.isNotBlank() }?.let { append("\n"); append(it) }
                }
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
    LaunchedEffect(remainingSeconds, autoPlayCancelled) {
        if (remainingSeconds != null && !autoPlayCancelled) {
            delay(120L)
            runCatching { playNextFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            if (position % 5_000L < 1_000L) onProgress(media.progressKey, player.currentPosition, player.duration)
        }
    }
    LaunchedEffect(player, media.url) {
        delay(25_000L)
        if (playbackState != Player.STATE_READY && playbackError == null) {
            startupTimedOut = true
            controlsVisible = true
        }
    }
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && playbackError == null && !startupTimedOut) {
            delay(4_000L)
            controlsVisible = false
        }
    }
    BackHandler {
        if (focusMode) focusMode = false else onBack()
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        // AndroidView instances survive recomposition by default. When autoplay advances to
        // another episode, recreate PlayerView so its touch/key listeners capture the new
        // episode's player and Compose control state instead of the disposed episode's state.
        key(media.progressKey) {
            AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    val playerView = this
                    playerViewRef = this
                    this.player = player
                    useController = false
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
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                player.play()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                player.pause()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val maxPosition = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(maxPosition))
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (player.duration > 0L) {
                                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration))
                                }
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (player.duration > 0L) {
                                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                }
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                controlsVisible = true
                                if (media.previousEpisode != null && !advancing) {
                                    advancing = true
                                    onPlayPrevious()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                controlsVisible = true
                                if (media.nextEpisode != null && !advancing) {
                                    advancing = true
                                    onPlayNext()
                                }
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
                                    controlsVisible = !controlsVisible
                                    gestureConsumed = true
                                }
                            }
                        }
                        // Claim the gesture at ACTION_DOWN. The old Media3 controller
                        // used to do this for us; without it Android would stop sending
                        // pointer-down/move/up events, breaking tap, pinch and pan.
                        val ownsGesture = event.actionMasked == MotionEvent.ACTION_DOWN
                        val consumed = ownsGesture ||
                            gestureConsumed || scaleDetector.isInProgress || event.pointerCount > 1 || panned || adjustingLevel
                        if (consumed && !ownsGesture) gestureConsumed = true
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
        }
        if (controlsVisible) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Transparent, Color.Black.copy(alpha = .88f)))
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(media.media.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        media.series?.let { Text(it.title, color = Color.LightGray, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                    IconButton(onClick = {
                        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) { Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White) }
                    IconButton(onClick = { focusMode = !focusMode }) {
                        Icon(if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }

                if (playbackError == null && !startupTimedOut) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (media.previousEpisode != null) IconButton(onClick = {
                            if (!advancing) {
                                advancing = true
                                onPlayPrevious()
                            }
                        }) {
                            Icon(Icons.Default.SkipPrevious, "Previous episode", Modifier.size(34.dp), tint = Color.White)
                        }
                        if (duration > 0L) IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                            Icon(Icons.Default.Replay10, "Back 10 seconds", Modifier.size(38.dp), tint = Color.White)
                        }
                        FilledIconButton(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier.size(68.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                        ) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(42.dp)) }
                        if (duration > 0L) IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration)) }) {
                            Icon(Icons.Default.Forward10, "Forward 10 seconds", Modifier.size(38.dp), tint = Color.White)
                        }
                        if (media.nextEpisode != null) IconButton(onClick = {
                            if (!advancing) {
                                advancing = true
                                onPlayNext()
                            }
                        }) {
                            Icon(Icons.Default.SkipNext, "Next episode", Modifier.size(34.dp), tint = Color.White)
                        }
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    if (duration > 0L) {
                        Slider(
                            value = position.coerceAtMost(duration).toFloat(),
                            onValueChange = { player.seekTo(it.toLong()) },
                            valueRange = 0f..duration.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914), inactiveTrackColor = Color.White.copy(alpha = .35f))
                        )
                    } else if (media.catalogType == com.nikhil.niktv.model.CatalogType.LIVE_TV) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914)))
                            Spacer(Modifier.width(8.dp))
                            Text("LIVE", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if ((playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) && playbackError == null && !startupTimedOut) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color(0xFFE50914))
                Text("Connecting to stream…", color = Color.White)
            }
        }
        val failure = playbackError ?: if (startupTimedOut) "The stream did not start within 25 seconds." else null
        if (failure != null) {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE181818)
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(42.dp), tint = Color(0xFFE50914))
                    Text("This title can’t be played right now", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(failure, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onBack) { Text("Go back") }
                        Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry with fresh link") }
                    }
                }
            }
        }
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
                    TextButton(onClick = {
                        autoPlayCancelled = true
                        playerViewRef?.requestFocus()
                    }) { Text("Cancel") }
                    Button(
                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                        modifier = Modifier.focusRequester(playNextFocusRequester)
                    ) { Text("Play now") }
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
