package com.nikhil.niktv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType as ComposeKeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.nikhil.niktv.R
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.MediaItem as NikMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal fun Modifier.playerActivityObserver(onActivity: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == ComposeKeyEventType.KeyDown && event.key != ComposeKey.Back) onActivity()
        false
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) onActivity()
            }
        }
    }

@UnstableApi
@Composable
fun PlayerScreen(
    media: PlayingMedia,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryAlternateDecoder: (Long) -> Unit,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    onPlayItem: (NikMediaItem) -> Unit = {},
    controlsTimeoutSeconds: Int = 3,
    playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    modifier: Modifier = Modifier,
    embeddedMode: Boolean = false,
    embeddedControlsDismissRequest: Int = 0,
    startFullscreen: Boolean = false,
    fullscreenOverride: Boolean? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val playerConfiguration = LocalConfiguration.current
    val compactMobileControls = playerConfiguration.smallestScreenWidthDp < 600
    val playbackScope = media.series?.id ?: media.progressKey.ifBlank { media.media.id }
    var sessionEngineOverride by remember { mutableStateOf<PlaybackEngine?>(null) }
    var engineSwitchResumePosition by remember(media.progressKey) {
        mutableLongStateOf(media.resumePositionMillis)
    }
    val configuredEngine = when (playbackEngine) {
        PlaybackEngine.VLC -> PlaybackEngine.VLC
        PlaybackEngine.MEDIA3 -> PlaybackEngine.MEDIA3
        PlaybackEngine.EXOPLAYER -> PlaybackEngine.EXOPLAYER
        PlaybackEngine.AUTO -> if (PlayerEngineFallback.prefersVlc(context, playbackScope)) PlaybackEngine.VLC else PlaybackEngine.MEDIA3
    }
    val effectiveEngine = sessionEngineOverride ?: configuredEngine
    if (effectiveEngine == PlaybackEngine.VLC) {
        VlcPlayerScreen(
            media = media,
            initialResumePosition = engineSwitchResumePosition,
            onBack = onBack,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onPlayItem = onPlayItem,
            onProgress = onProgress,
            modifier = modifier,
            embeddedMode = embeddedMode,
            controlsTimeoutSeconds = controlsTimeoutSeconds,
            embeddedControlsDismissRequest = embeddedControlsDismissRequest,
            startFullscreen = startFullscreen,
            fullscreenOverride = fullscreenOverride,
            onFullscreenChanged = onFullscreenChanged,
            onSwitchPlayer = { position ->
                engineSwitchResumePosition = position
                sessionEngineOverride = PlaybackEngine.MEDIA3
            }
        )
        return
    }
    val coroutineScope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    var videoScale by remember(media.progressKey) { mutableFloatStateOf(1f) }
    var videoOffset by remember(media.progressKey) { mutableStateOf(Offset.Zero) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    // Fullscreen belongs to the player session, not to an individual queue item.
    // Preserve it while changing channels/episodes/titles.
    var focusMode by remember { mutableStateOf(startFullscreen) }
    ApplyMobileFullscreenOrientation(focusMode)
    LaunchedEffect(fullscreenOverride) {
        fullscreenOverride?.let { focusMode = it }
    }
    var gestureFeedback by remember(media.progressKey) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var resizeMode by remember(media.progressKey) { mutableStateOf(VideoResizeMode.FIT) }
    val (appearanceProfiles, scheduledAppearanceProfile) = rememberVideoAppearanceProfiles(useSchedule = true)
    var appearanceOverrideId by remember { mutableStateOf<String?>(null) }
    var appearancePreview by remember { mutableStateOf<VideoAppearanceProfile?>(null) }
    val activeAppearanceProfile = appearancePreview ?: appearanceProfiles.firstOrNull { it.id == appearanceOverrideId }
        ?: scheduledAppearanceProfile
    var modeFeedback by remember { mutableStateOf<String?>(null) }
    var queueVisible by remember(media.progressKey) { mutableStateOf(false) }
    var pictureEditorVisible by remember { mutableStateOf(false) }
    val hasPlaybackQueue = media.episodeQueue.distinctBy { it.id }.size > 1 && !media.directFullscreen
    LaunchedEffect(modeFeedback) {
        if (modeFeedback != null) {
            delay(1_800L)
            modeFeedback = null
        }
    }
    var controlsVisible by remember(media.progressKey) { mutableStateOf(!embeddedMode && !startFullscreen) }
    var controlsFocused by remember(media.progressKey) { mutableStateOf(false) }
    var dpadInteraction by remember(media.progressKey) { mutableIntStateOf(0) }
    var suppressNextEmbeddedPlayerFocusHandoff by remember(media.progressKey) {
        mutableStateOf(false)
    }
    val embeddedPlayerFocusHandoffArmed by rememberUpdatedState(
        embeddedMode && embeddedControlsDismissRequest > 0
    )

    /*
     * EMBEDDED_BROWSE_DISMISSES_CONTROLS_V10
     *
     * The Showcase rail owns browsing focus. Every time focus/touch returns
     * to that rail, Showcase increments embeddedControlsDismissRequest.
     *
     * This is intentionally one-way:
     * - rail -> hides controls;
     * - PlayerView focus -> existing focus listener shows controls again.
     *
     * Using a monotonically increasing request instead of a Boolean matters:
     * the user can enter the player, then return to the rail repeatedly and
     * each transition still produces a new dismissal event.
     */
    LaunchedEffect(embeddedControlsDismissRequest) {
        if (
            embeddedMode &&
            embeddedControlsDismissRequest > 0
        ) {
            controlsVisible = false
            controlsFocused = false
        }
    }

    var isPlaying by remember(media.progressKey) { mutableStateOf(false) }
    var playbackState by remember(media.progressKey) { mutableIntStateOf(Player.STATE_IDLE) }
    var position by remember(media.progressKey) { mutableLongStateOf(0L) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var videoDetails by remember(media.progressKey) { mutableStateOf("") }
    var playbackError by remember(media.progressKey) { mutableStateOf<String?>(null) }

    /*
     * MTK_AVC_SEAMLESS_RECOVERY_V14
     *
     * MediaTek AVC hardware decoders on some TV/Fire TV devices can fail
     * during an otherwise valid stream. Keep this separate from playbackError
     * so automatic decoder recovery does not flash the fatal error dialog.
     */
    var decoderRecoveryInProgress by remember(media.progressKey) {
        mutableStateOf(false)
    }

    var startupTimedOut by remember(media.progressKey) { mutableStateOf(false) }
    var playerViewRef by remember(media.progressKey) { mutableStateOf<PlayerView?>(null) }
    var inPictureInPicture by remember { mutableStateOf(false) }
    val playNextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val backFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pipFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchFocusRequester = remember(media.progressKey) { FocusRequester() }
    val resizeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val fullscreenFocusRequester = remember(media.progressKey) { FocusRequester() }
    val previousFocusRequester = remember(media.progressKey) { FocusRequester() }
    val rewindFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playPauseFocusRequester = remember(media.progressKey) { FocusRequester() }
    val forwardFocusRequester = remember(media.progressKey) { FocusRequester() }
    val nextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val progressFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorBackFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorRetryFocusRequester = remember(media.progressKey) { FocusRequester() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pipActivity = activity as? MainActivity
    val pipAvailable = remember(context) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    val player = remember(media.progressKey, effectiveEngine) {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            if (effectiveEngine == PlaybackEngine.MEDIA3) {
                // Media3 mode applies NikTV's learned decoder policy.
                setEnableDecoderFallback(true)
                setMediaCodecSelector(FailedDecoderRegistry.selector(context, playbackScope))
            }
            // ExoPlayer compatibility mode intentionally retains the device's
            // native decoder order and default fallback behavior.
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(MediaItem.fromUri(media.url))
            if (engineSwitchResumePosition > 0L) seekTo(engineSwitchResumePosition)
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
                val failedDecoder =
                    FailedDecoderRegistry.record(
                        context,
                        error,
                        playbackScope
                    )

                val authorizationFailure =
                    error.causeSequence()
                        .filterIsInstance<
                            HttpDataSource.InvalidResponseCodeException
                        >()
                        .firstOrNull()
                        ?.responseCode in setOf(401, 403)

                when {
                    authorizationFailure &&
                        media.authorizationRetryCount == 0 -> {

                        playbackError =
                            "Stream authorization expired. Requesting a fresh playback link…"
                        controlsVisible = true

                        coroutineScope.launch {
                            delay(350L)
                            onPlaybackAuthorizationFailure(
                                player.currentPosition
                                    .coerceAtLeast(0L)
                            )
                        }
                    }

                    failedDecoder != null -> {
                        /*
                         * MTK_AVC_RUNTIME_RECOVERY_V14
                         *
                         * Decoder failure is device-side, not a portal/VOD
                         * failure. Hide the fatal overlay, blacklist the failed
                         * codec, and recreate the player at a nearby safe
                         * position so Media3 selects the next decoder.
                         */
                        playbackError = null
                        startupTimedOut = false
                        controlsVisible = false
                        controlsFocused = false
                        decoderRecoveryInProgress = true

                        val recoveryPosition =
                            (
                                player.currentPosition -
                                    1_500L
                                )
                                .coerceAtLeast(0L)

                        coroutineScope.launch {
                            delay(250L)

                            onRetryAlternateDecoder(
                                recoveryPosition
                            )
                        }
                    }

                    else -> {
                        decoderRecoveryInProgress = false

                        playbackError = buildString {
                            append(
                                error.errorCodeName
                                    .replace('_', ' ')
                                    .lowercase()
                                    .replaceFirstChar(
                                        Char::uppercase
                                    )
                            )

                            error.cause
                                ?.message
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?.let {
                                    append("\n")
                                    append(it)
                                }
                        }

                        controlsVisible = true
                    }
                }
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
    DisposableEffect(pipActivity) {
        pipActivity?.setPlayerActiveForPip(true)
        pipActivity?.pipModeListener = { entered ->
            inPictureInPicture = entered
            if (entered) {
                controlsVisible = false
                controlsFocused = false
            }
        }
        onDispose {
            pipActivity?.pipModeListener = null
            pipActivity?.setPlayerActiveForPip(false)
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
        if (media.catalogType != com.nikhil.niktv.model.CatalogType.SERIES || media.nextEpisode == null || autoPlayCancelled) {
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
            videoDetails = player.videoFormat?.let { format ->
                buildList {
                    if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
                    format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)?.let(::add)
                    if (format.bitrate > 0) add("%.1f Mbps".format(Locale.ROOT, format.bitrate / 1_000_000f))
                }.joinToString(" · ")
            }.orEmpty()
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
    /*
     * SHOWCASE_EMBEDDED_CONTROLS_TIMEOUT_V11
     *
     * Player controls already used controlsTimeoutSeconds for standalone /
     * fullscreen playback (3 seconds by default). Apply the same timeout to
     * the Showcase embedded player.
     *
     * Do not dismiss controls while the user is actively focused on one of
     * the embedded control buttons. When focus is on the video surface, the
     * overlay disappears after the configured timeout.
     */
    LaunchedEffect(
        controlsVisible,
        controlsFocused,
        dpadInteraction,
        isPlaying,
        controlsTimeoutSeconds,
        media.progressKey,
        embeddedMode,
        queueVisible,
        pictureEditorVisible
    ) {
        val canAutoHide =
            controlsVisible &&
                !controlsFocused &&
                isPlaying &&
                playbackError == null &&
                !startupTimedOut
                && !queueVisible
                && !pictureEditorVisible

        if (canAutoHide) {
            delay(
                controlsTimeoutSeconds
                    .coerceIn(1, 30) * 1_000L
            )

            controlsVisible = false
            controlsFocused = false

            /*
             * Standalone/fullscreen keeps the previous behavior of returning
             * focus to PlayerView. In embedded Showcase mode the video surface
             * already owns focus when this timeout normally fires; requesting
             * focus again would trigger its focus listener and immediately
             * show the controls again.
             */
            if (!embeddedMode) {
                playerViewRef?.requestFocus()
            }
        }
    }
/*
 * Automatically focus Play/Pause only for the standalone/fullscreen player.
 *
 * In embedded Live TV mode, the channel list owns initial focus.
 * Otherwise the embedded PlayerView can briefly receive focus, make
 * controlsVisible=true, and this delayed requestFocus() steals focus
 * from the currently playing channel row.
 */
    LaunchedEffect(
        controlsVisible,
        media.progressKey,
        embeddedMode
    ) {
        if (
            !embeddedMode &&
            controlsVisible &&
            playbackError == null &&
            !startupTimedOut
        ) {
            delay(80L)

            runCatching {
                playPauseFocusRequester.requestFocus()
            }
        }
    }
    LaunchedEffect(playbackError, startupTimedOut, media.progressKey) {
        if (playbackError != null || startupTimedOut) {
            delay(120L)
            runCatching { errorRetryFocusRequester.requestFocus() }
        }
    }
    fun showControlsAndFocusPlayPause() {
        controlsVisible = true
        coroutineScope.launch {
            delay(80L)
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }
    BackHandler {
        when {
            queueVisible -> queueVisible = false
            pictureEditorVisible -> pictureEditorVisible = false
            controlsVisible -> {
                controlsVisible = false
                controlsFocused = false
                if (embeddedMode && playerViewRef != null) {
                    suppressNextEmbeddedPlayerFocusHandoff = true
                }
                playerViewRef?.requestFocus()
            }
            startFullscreen -> onBack()
            focusMode -> {
                focusMode = false
                onFullscreenChanged?.invoke(false)
                controlsVisible = true
            }
            else -> onBack()
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .playerActivityObserver {
                dpadInteraction++
            }
            .background(Color.Black)
            .then(if (focusMode || inPictureInPicture) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            .clipToBounds()
    ) {
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
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
                    if (!embeddedMode) requestFocus()
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus && embeddedMode) {
                            if (suppressNextEmbeddedPlayerFocusHandoff) {
                                suppressNextEmbeddedPlayerFocusHandoff = false
                            } else {
                                controlsVisible = true

                                /*
                                 * The Showcase rail intentionally dismisses the
                                 * embedded overlay whenever browsing owns focus.
                                 * Once that has happened, a non-touch focus entry
                                 * into PlayerView is a deliberate D-pad move into
                                 * the player, so bridge native View focus back to
                                 * Compose by focusing Play/Pause.
                                 *
                                 * Keeping the handoff armed by the existing
                                 * dismiss counter avoids recreating the old
                                 * startup focus-steal that embedded mode was
                                 * designed to prevent.
                                 */
                                if (
                                    embeddedPlayerFocusHandoffArmed &&
                                    !playerView.isInTouchMode
                                ) {
                                    showControlsAndFocusPlayPause()
                                }
                            }
                        }
                    }
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
                        if (keyCode != KeyEvent.KEYCODE_BACK) dpadInteraction++
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                showControlsAndFocusPlayPause()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
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
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                showControlsAndFocusPlayPause()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                showControlsAndFocusPlayPause()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                showControlsAndFocusPlayPause()
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
                                dpadInteraction++
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
                playerView.resizeMode = when (resizeMode) {
                    VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                playerView.videoSurfaceView?.apply {
                    val modeScale = if (resizeMode == VideoResizeMode.ZOOM) 1.25f else 1f
                    scaleX = videoScale * modeScale
                    scaleY = videoScale * modeScale
                    translationX = videoOffset.x
                    translationY = videoOffset.y
                }
            },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!focusMode && !inPictureInPicture && !embeddedMode) Modifier.padding(
                        top = if (compactMobileControls) 58.dp else 76.dp,
                        bottom = if (compactMobileControls) 112.dp else 148.dp
                    ) else Modifier)
            )
        }
        VideoAppearanceOverlay(activeAppearanceProfile)
        if ((controlsVisible || (!focusMode && !embeddedMode)) && !inPictureInPicture) {
            Box(
                Modifier.fillMaxSize().then(
                    if (focusMode) Modifier.background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Transparent, Color.Black.copy(alpha = .88f)))
                    ) else Modifier
                )
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == ComposeKeyEventType.KeyDown && event.key == ComposeKey.DirectionUp && hasPlaybackQueue) {
                                queueVisible = true; true
                            } else false
                        }
                        .then(if (focusMode) Modifier.statusBarsPadding() else Modifier)
                        .then(if (!focusMode) Modifier.background(Color(0xFF090909)) else Modifier)
                        .padding(
                            horizontal = if (compactMobileControls) 8.dp else 16.dp,
                            vertical = if (compactMobileControls) 4.dp else 10.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(backFocusRequester)
                            .focusProperties {
                                right = if (pipAvailable) {
                                    pipFocusRequester
                                } else {
                                    playerSwitchFocusRequester
                                }
                                down = playPauseFocusRequester
                            }
                            .playerControlFocus(CircleShape) { controlsFocused = it }
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = if (compactMobileControls) 4.dp else 10.dp)) {
                        Text(
                            media.media.title,
                            color = Color.White,
                            style = if (compactMobileControls) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                        media.series?.let { Text(it.title, color = Color.LightGray, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        videoDetails.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.LightGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Text("${if (effectiveEngine == PlaybackEngine.EXOPLAYER) "ExoPlayer" else "Media3"} · ${resizeMode.label} · ${activeAppearanceProfile.name}", color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    // PLAYER_GLOBAL_ORIENTATION_NO_ROTATE_V12
                    if (pipAvailable) {
                        IconButton(
                            onClick = {
                                controlsVisible = false
                                controlsFocused = false
                                pipActivity?.enterPlayerPictureInPicture()
                            },
                            modifier = Modifier.focusRequester(pipFocusRequester)
                                .focusProperties {
                                    left = backFocusRequester
                                    right = playerSwitchFocusRequester
                                    down = playPauseFocusRequester
                                }
                                .playerControlFocus(CircleShape) { controlsFocused = it }
                        ) { Icon(Icons.Default.PictureInPictureAlt, "Picture in Picture", tint = Color.White) }
                    }
                    IconButton(
                        onClick = {
                            modeFeedback = "Player · VLC"
                            coroutineScope.launch {
                                delay(700L)
                                engineSwitchResumePosition = player.currentPosition.coerceAtLeast(0L)
                                sessionEngineOverride = PlaybackEngine.VLC
                            }
                        },
                        modifier = Modifier.focusRequester(playerSwitchFocusRequester)
                            .focusProperties {
                                left = if (pipAvailable) pipFocusRequester else backFocusRequester
                                right = resizeFocusRequester
                                down = playPauseFocusRequester
                            }
                            .playerControlFocus(CircleShape) { controlsFocused = it }
                    ) {
                        Icon(Icons.Default.SwapHoriz, "Switch player: Media3", tint = Color.White)
                    }
                    PlayerVisualButtons(
                        resizeMode = resizeMode,
                        onResize = {
                            resizeMode = resizeMode.next()
                            videoScale = 1f
                            videoOffset = Offset.Zero
                            modeFeedback = "Video fit · ${resizeMode.label}"
                        },
                        profiles = appearanceProfiles,
                        activeProfile = activeAppearanceProfile,
                        onPictureMode = { next ->
                            appearanceOverrideId = next.id
                            modeFeedback = "Picture mode · ${next.name}"
                        },
                        onEditPictureMode = { pictureEditorVisible = true },
                        resizeRequester = resizeFocusRequester,
                        pictureModeRequester = pictureModeFocusRequester,
                        leftRequester = playerSwitchFocusRequester,
                        rightRequester = fullscreenFocusRequester,
                        downRequester = playPauseFocusRequester,
                        onControlsFocused = { controlsFocused = it }
                    )
                    IconButton(onClick = {
                        val enteringFullscreen = !focusMode
                        if (startFullscreen && !enteringFullscreen) {
                            onBack()
                        } else {
                            focusMode = enteringFullscreen
                            onFullscreenChanged?.invoke(enteringFullscreen)
                            controlsVisible = !enteringFullscreen
                            controlsFocused = false
                            playerViewRef?.requestFocus()
                        }
                    }, modifier = Modifier.focusRequester(fullscreenFocusRequester)
                        .focusProperties {
                            left = pictureModeFocusRequester
                            down = playPauseFocusRequester
                        }
                        .playerControlFocus(CircleShape) { controlsFocused = it }) {
                        Icon(if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .then(if (focusMode) Modifier.navigationBarsPadding() else Modifier)
                        .then(if (!focusMode) Modifier.background(Color(0xFF090909)) else Modifier)
                        .padding(
                            horizontal = if (compactMobileControls) 10.dp else 24.dp,
                            vertical = if (compactMobileControls) 8.dp else 16.dp
                        )
                ) {
                    val seekable = duration > 0L && media.catalogType != com.nikhil.niktv.model.CatalogType.LIVE_TV
                    Row(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == ComposeKeyEventType.KeyDown && event.key == ComposeKey.DirectionUp) {
                                runCatching { fullscreenFocusRequester.requestFocus() }
                                true
                            } else if (event.type == ComposeKeyEventType.KeyDown && event.key == ComposeKey.DirectionDown && hasPlaybackQueue) {
                                queueVisible = true
                                true
                            } else false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                            if (playbackError == null && !startupTimedOut) {
                                if (media.previousEpisode != null) IconButton(onClick = { if (!advancing) { advancing = true; onPlayPrevious() } }, modifier = Modifier.focusRequester(previousFocusRequester).playerControlFocus(CircleShape) { controlsFocused = it }) {
                                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                                }
                                if (seekable) IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.focusRequester(rewindFocusRequester).playerControlFocus(CircleShape) { controlsFocused = it }) {
                                    Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White)
                                }
                                FilledIconButton(
                                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                                    modifier = Modifier.size(if (compactMobileControls) 44.dp else 52.dp).focusRequester(playPauseFocusRequester)
                                        .focusProperties {
                                            up = fullscreenFocusRequester
                                            left = if (seekable) rewindFocusRequester else previousFocusRequester
                                            right = if (seekable) forwardFocusRequester else nextFocusRequester
                                        }
                                        .playerControlFocus(CircleShape) { controlsFocused = it },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play") }
                                if (seekable) IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration)) }, modifier = Modifier.focusRequester(forwardFocusRequester).playerControlFocus(CircleShape) { controlsFocused = it }) {
                                    Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White)
                                }
                                if (media.nextEpisode != null) IconButton(onClick = { if (!advancing) { advancing = true; onPlayNext() } }, modifier = Modifier.focusRequester(nextFocusRequester).playerControlFocus(CircleShape) { controlsFocused = it }) {
                                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            if (seekable) PlaybackProgressBar(
                                position = position,
                                duration = duration,
                                onSeek = { player.seekTo(it) },
                                compact = compactMobileControls,
                                modifier = Modifier.weight(1f).focusRequester(progressFocusRequester)
                                    .playerControlFocus(RoundedCornerShape(28.dp)) { controlsFocused = it }
                            ) else if (media.catalogType == com.nikhil.niktv.model.CatalogType.LIVE_TV) Row(
                                Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically
                            ) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914)))
                            Spacer(Modifier.width(8.dp))
                            Text("LIVE", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                    if (!embeddedMode) Box(
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Text(videoDetails, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (queueVisible) PlayerQueueOverlay(
            items = media.episodeQueue,
            playingId = media.media.id,
            onDismiss = { queueVisible = false; dpadInteraction++; showControlsAndFocusPlayPause() },
            onSelect = { queueVisible = false; onPlayItem(it) }
        )
        if (pictureEditorVisible) PlayerPictureModeEditor(
            profiles = appearanceProfiles,
            selectedId = activeAppearanceProfile.id,
            onDismiss = { pictureEditorVisible = false; appearancePreview = null; dpadInteraction++; showControlsAndFocusPlayPause() },
            onPreview = { appearancePreview = it },
            onSelected = { appearanceOverrideId = it }
        )
        if (
            (
                playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE ||
                    decoderRecoveryInProgress
                ) &&
            playbackError == null &&
            !startupTimedOut
        ) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFE50914)
                )

                Text(
                    if (decoderRecoveryInProgress) {
                        "Switching to a compatible decoder…"
                    } else {
                        "Connecting to stream…"
                    },
                    color = Color.White
                )
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
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.focusRequester(errorBackFocusRequester)
                                .focusProperties { right = errorRetryFocusRequester }
                                .playerControlFocus(RoundedCornerShape(24.dp)) { controlsFocused = it }
                        ) { Text("Go back") }
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.focusRequester(errorRetryFocusRequester)
                                .focusProperties { left = errorBackFocusRequester }
                                .playerControlFocus(RoundedCornerShape(24.dp)) { controlsFocused = it }
                        ) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry with fresh link") }
                    }
                }
            }
        }
        gestureFeedback?.let { (isBrightness, level) ->
            Surface(
                modifier = Modifier
                    .align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = if (compactMobileControls) 12.dp else 20.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
            ) {
                Column(
                    Modifier
                        .padding(
                            horizontal = if (compactMobileControls) 10.dp else 18.dp,
                            vertical = if (compactMobileControls) 12.dp else 16.dp
                        )
                        .width(if (compactMobileControls) 88.dp else 136.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (isBrightness) Icons.Default.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp,
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
        modeFeedback?.let { PlayerModeFeedback(it) }
        if (countdown != null && media.nextEpisode != null && !autoPlayCancelled) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .then(if (focusMode) Modifier.navigationBarsPadding() else Modifier)
                    .padding(16.dp).widthIn(max = 560.dp),
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
                    }, modifier = Modifier.playerControlFocus(RoundedCornerShape(24.dp)) { controlsFocused = it }) { Text("Cancel") }
                    Button(
                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                        modifier = Modifier.focusRequester(playNextFocusRequester).playerControlFocus(RoundedCornerShape(24.dp)) { controlsFocused = it }
                    ) { Text("Play now") }
                }
            }
        }
    }
}

private object FailedDecoderRegistry {
    private val failedNames =
        java.util.Collections.synchronizedSet(
            mutableSetOf<String>()
        )

    /*
     * Accept both runtime and initialization failure wording emitted by
     * different Media3/Android codec layers.
     */
    private val decoderPattern =
        Regex(
            "(?:decoder failed|decoder init failed):\\s*([^,\\s]+)",
            RegexOption.IGNORE_CASE
        )

    private fun isAndroidSoftwareDecoder(
        name: String
    ): Boolean {
        val normalized =
            name.lowercase()

        return normalized.startsWith(
            "omx.google."
        ) ||
            normalized.startsWith(
                "c2.android."
            )
    }

    fun selector(
        context: Context,
        playbackScope: String
    ): MediaCodecSelector {
        failedNames +=
            context
                .getSharedPreferences(
                    "player_decoder_fallbacks",
                    Context.MODE_PRIVATE
                )
                .getStringSet(
                    "failed_decoders",
                    emptySet()
                )
                .orEmpty()

        return MediaCodecSelector {
                mimeType,
                secure,
                tunneling ->

            val candidates =
                MediaCodecSelector.DEFAULT
                    .getDecoderInfos(
                        mimeType,
                        secure,
                        tunneling
                    )

            val filtered =
                candidates.filterNot {
                    it.name.lowercase() in
                        failedNames
                }

            /*
             * MTK_AVC_SOFTWARE_PREFERENCE_V14
             *
             * Keep hardware decoding as the default. Only after this device
             * has actually crashed OMX.MTK.VIDEO.DECODER.AVC do we prefer an
             * Android software AVC decoder for subsequent player instances.
             */
            val mtkAvcPreviouslyFailed =
                mimeType.equals(
                    "video/avc",
                    ignoreCase = true
                ) &&
                    ("omx.mtk.video.decoder.avc" in failedNames ||
                        PlayerEngineFallback.prefersSoftwareAvc(context, playbackScope))

            val ordered =
                if (mtkAvcPreviouslyFailed) {
                    filtered.sortedBy { codec ->
                        if (
                            isAndroidSoftwareDecoder(
                                codec.name
                            )
                        ) {
                            0
                        } else {
                            1
                        }
                    }
                } else {
                    filtered
                }

            /*
             * If the device genuinely exposes no alternative decoder, retain
             * Media3's original candidates so the app reports the real device
             * capability rather than manufacturing a no-decoder condition.
             */
            if (ordered.isEmpty()) {
                candidates
            } else {
                ordered
            }
        }
    }

    fun record(
        context: Context,
        error: Throwable,
        playbackScope: String
    ): String? {
        val messages =
            generateSequence(
                error as Throwable?
            ) {
                it.cause
            }
                .mapNotNull {
                    it.message
                }
                .joinToString("\n")

        val decoder =
            decoderPattern
                .find(messages)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

        val normalized =
            decoder.lowercase()

        failedNames.add(normalized)

        context
            .getSharedPreferences(
                "player_decoder_fallbacks",
                Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                "failed_decoders",
                failedNames.toSet()
            )
            .apply()

        if (normalized == "omx.mtk.video.decoder.avc") {
            PlayerEngineFallback.recordMtkAvcFailure(context, playbackScope)
        }

        return decoder
    }
}

private object PlayerEngineFallback {
    private const val PREFS = "player_engine_fallbacks"

    fun prefersSoftwareAvc(context: Context, scope: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("mtk:$scope", 0) >= 1

    fun prefersVlc(context: Context, scope: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("mtk:$scope", 0) >= 2

    fun recordMtkAvcFailure(context: Context, scope: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "mtk:$scope"
        prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1).coerceAtMost(3)).apply()
    }
}

/**
 * A seek bar with an Android 16-style vertical indicator. The left-aligned
 * readout is clipped into active/inactive layers as the fill passes beneath it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlaybackProgressBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val safePosition = position.coerceIn(0L, duration)
    val fraction = (safePosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val elapsed = formatPlayerTime(safePosition)
    val remaining = formatPlayerTime(duration - safePosition)
    val total = formatPlayerTime(duration)
    Box(
        modifier.height(if (compact) 44.dp else 56.dp)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != ComposeKeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    ComposeKey.DirectionLeft -> {
                        onSeek((safePosition - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    ComposeKey.DirectionRight -> {
                        onSeek((safePosition + 10_000L).coerceAtMost(duration))
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = safePosition.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().height(if (compact) 44.dp else 56.dp),
            thumb = {
                Box(
                    Modifier.width(if (compact) 3.dp else 4.dp).height(if (compact) 34.dp else 44.dp)
                        .shadow(5.dp, RoundedCornerShape(99.dp))
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            },
            track = {
                Box(
                    Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp)
                        .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.46f),
            contentColor = Color.White
        ) {
            Text(
                "$elapsed  •  −$remaining  •  $total",
                modifier = Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 2.dp else 4.dp),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

@Composable
internal fun Modifier.playerControlFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    onFocused: (Boolean) -> Unit
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .requiredSizeIn(
            minWidth = if (LocalConfiguration.current.smallestScreenWidthDp < 600) 44.dp else 56.dp,
            minHeight = if (LocalConfiguration.current.smallestScreenWidthDp < 600) 44.dp else 56.dp
        )
        .onFocusChanged {
            focused = it.isFocused
            onFocused(it.isFocused)
        }
        .then(
            if (focused) Modifier
                .shadow(18.dp, shape, ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914))
                .background(Color(0xFF451014), shape)
                .border(4.dp, Color(0xFFFF3340), shape)
            else Modifier
        )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
