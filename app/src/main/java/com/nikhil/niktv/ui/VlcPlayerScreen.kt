package com.nikhil.niktv.ui

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
internal fun VlcPlayerScreen(
    media: PlayingMedia,
    initialResumePosition: Long,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    queueHasMore: Boolean = false,
    queueLoadingMore: Boolean = false,
    onLoadMoreQueue: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    embeddedMode: Boolean = false,
    controlsTimeoutSeconds: Int = 3,
    embeddedControlsDismissRequest: Int = 0,
    startFullscreen: Boolean = false,
    fullscreenOverride: Boolean? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    onSwitchPlayer: (Long) -> Unit,
    focusPlayerSwitchOnEnter: Boolean = false,
    onPlayerSwitchFocusRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val playerConfiguration = LocalConfiguration.current
    val compactMobileControls = playerConfiguration.smallestScreenWidthDp < 600
    val scope = rememberCoroutineScope()
    var playing by remember(media.progressKey) { mutableStateOf(true) }
    var buffering by remember(media.progressKey) { mutableStateOf(true) }
    var position by remember(media.progressKey) { mutableLongStateOf(initialResumePosition) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var pendingInitialResumePosition by remember(media.progressKey) {
        mutableLongStateOf(initialResumePosition.coerceAtLeast(0L))
    }
    var resumeSeekAttempts by remember(media.progressKey) {
        mutableIntStateOf(0)
    }
    var error by remember(media.progressKey) { mutableStateOf<String?>(null) }
    var focusMode by remember { mutableStateOf(startFullscreen) }
    ApplyMobileFullscreenOrientation(focusMode)
    var controlsVisible by remember(media.progressKey) { mutableStateOf(!embeddedMode && !startFullscreen) }
    var controlsFocused by remember(media.progressKey) { mutableStateOf(false) }
    var dpadInteraction by remember(media.progressKey) { mutableIntStateOf(0) }
    var suppressNextEmbeddedPlayerFocusHandoff by remember(media.progressKey) {
        mutableStateOf(false)
    }
    val embeddedPlayerFocusHandoffArmed by rememberUpdatedState(
        embeddedMode && embeddedControlsDismissRequest > 0
    )
    var videoView by remember(media.progressKey) { mutableStateOf<View?>(null) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    var inPictureInPicture by remember { mutableStateOf(false) }
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
    val playerQueueItems = remember(media.media.id, media.episodeQueue) {
        val unique = media.episodeQueue.distinctBy { it.id }
        if (unique.any { it.id == media.media.id }) unique
        else listOf(media.media) + unique
    }
    val hasPlaybackQueue =
        playerQueueItems.size > 1 || queueHasMore || queueLoadingMore
    LaunchedEffect(modeFeedback) {
        if (modeFeedback != null) {
            delay(1_800L)
            modeFeedback = null
        }
    }
    LaunchedEffect(focusMode) {
        if (!focusMode) queueVisible = false
    }

    val backRequester = remember(media.progressKey) { FocusRequester() }
    val fullscreenRequester = remember(media.progressKey) { FocusRequester() }
    val pipRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchRequester = remember(media.progressKey) { FocusRequester() }
    val resizeRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeRequester = remember(media.progressKey) { FocusRequester() }
    val pictureSettingsRequester = remember(media.progressKey) { FocusRequester() }
    val previousRequester = remember(media.progressKey) { FocusRequester() }
    val rewindRequester = remember(media.progressKey) { FocusRequester() }
    val playRequester = remember(media.progressKey) { FocusRequester() }
    val forwardRequester = remember(media.progressKey) { FocusRequester() }
    val nextRequester = remember(media.progressKey) { FocusRequester() }
    val progressRequester = remember(media.progressKey) { FocusRequester() }
    val videoSurfaceFocusRequester = remember(media.progressKey) { FocusRequester() }

    LaunchedEffect(focusPlayerSwitchOnEnter) {
        if (focusPlayerSwitchOnEnter) {
            controlsVisible = true
            delay(120L)
            runCatching { playerSwitchRequester.requestFocus() }
            onPlayerSwitchFocusRestored()
        }
    }

    val libVlc = remember(media.progressKey) {
        LibVLC(context, arrayListOf("--network-caching=1500", "--clock-jitter=0"))
    }
    val player = remember(media.progressKey) { MediaPlayer(libVlc) }
    val seekable = duration > 0L && media.catalogType != CatalogType.LIVE_TV
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    LaunchedEffect(gestureFeedback) {
        if (gestureFeedback != null) {
            delay(800)
            gestureFeedback = null
        }
    }
    // Reapply the selected scale after the VLC surface is attached. Applying
    // BEST_FIT before attach can be lost, leaving the new fullscreen surface
    // at VLC's crop-prone default while Media3 still renders correctly.
    LaunchedEffect(player, resizeMode, videoView) {
        player.setVideoScale(
            when (resizeMode) {
                VideoResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                VideoResizeMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
                VideoResizeMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                VideoResizeMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_16_9
            }
        )
    }

    val pipActivity = activity as? MainActivity
    val pipAvailable = remember(context) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    fun showControls() {
        controlsVisible = true
        scope.launch {
            delay(80)
            runCatching { playRequester.requestFocus() }
        }
    }

    LaunchedEffect(fullscreenOverride) { fullscreenOverride?.let { focusMode = it } }
    DisposableEffect(activity, focusMode) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (focusMode) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
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
    LaunchedEffect(embeddedControlsDismissRequest) {
        if (embeddedMode && embeddedControlsDismissRequest > 0) {
            controlsVisible = false
            controlsFocused = false
        }
    }
    LaunchedEffect(controlsVisible, controlsFocused, dpadInteraction, playing, controlsTimeoutSeconds, media.progressKey, queueVisible, pictureEditorVisible) {
        if (controlsVisible && !controlsFocused && playing && error == null && !queueVisible && !pictureEditorVisible) {
            delay(controlsTimeoutSeconds.coerceIn(1, 30) * 1_000L)
            controlsVisible = false
            controlsFocused = false
            if (!embeddedMode) {
                runCatching { videoSurfaceFocusRequester.requestFocus() }
            }
        }
    }
    LaunchedEffect(controlsVisible, embeddedMode, media.progressKey) {
        if (
            controlsVisible &&
            !embeddedMode &&
            !focusPlayerSwitchOnEnter &&
            error == null
        ) {
            delay(80)
            runCatching { playRequester.requestFocus() }
        }
    }

    /*
     * FULLSCREEN_COMPOSE_VIDEO_FOCUS_V19
     *
     * Standalone/fullscreen VLC playback parks hidden-controls focus in
     * Compose. The native VLCVideoLayout remains focusable only for the
     * embedded Showcase rail-to-player bridge.
     */
    LaunchedEffect(
        controlsVisible,
        queueVisible,
        pictureEditorVisible,
        embeddedMode,
        inPictureInPicture,
        media.progressKey
    ) {
        if (
            !embeddedMode &&
            !controlsVisible &&
            !queueVisible &&
            !pictureEditorVisible &&
            !inPictureInPicture
        ) {
            delay(40L)
            runCatching { videoSurfaceFocusRequester.requestFocus() }
        }
    }

    BackHandler {
        when {
            queueVisible -> queueVisible = false
            pictureEditorVisible -> pictureEditorVisible = false
            startFullscreen -> onBack()
            controlsVisible -> {
                controlsVisible = false
                controlsFocused = false
                if (embeddedMode && videoView != null) {
                    suppressNextEmbeddedPlayerFocusHandoff = true
                    videoView?.requestFocus()
                } else {
                    runCatching { videoSurfaceFocusRequester.requestFocus() }
                }
            }
            focusMode -> {
                focusMode = false
                onFullscreenChanged?.invoke(false)
                controlsVisible = true
            }
            else -> onBack()
        }
    }

    DisposableEffect(player, media.url) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> buffering = true
                MediaPlayer.Event.Buffering -> {
                    // LibVLC emits Buffering for every percentage update, including
                    // 100%, and may emit another completed update after Playing.
                    // Treat it as a stall only while VLC is not actively playing.
                    buffering = event.buffering < 100f && !player.isPlaying
                }
                MediaPlayer.Event.Playing -> {
                    playing = true
                    buffering = false
                    advancing = false
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EncounteredError -> {
                    buffering = false
                    error = "VLC could not play this stream."
                    controlsVisible = true
                }
            }
        }
        val vlcMedia = Media(libVlc, android.net.Uri.parse(media.url)).apply {
            setHWDecoderEnabled(false, false)
            addOption(":network-caching=1500")
        }
        player.media = vlcMedia
        vlcMedia.release()
        player.play()

        // VLC_RESUME_AFTER_TIMELINE_READY_V2
        // Applying player.time during Opening/Buffering can be discarded.
        onDispose {
            val finalPosition =
                (
                    if (pendingInitialResumePosition > 0L) {
                        pendingInitialResumePosition
                    } else {
                        player.time
                    }
                    ).coerceAtLeast(0L)
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
            val knownDuration = player.length.coerceAtLeast(0L)
            duration = knownDuration

            if (player.isPlaying) {
                playing = true
                buffering = false
            }

            val pendingResume = pendingInitialResumePosition

            if (
                pendingResume > 0L &&
                player.isPlaying &&
                knownDuration > 0L &&
                media.catalogType != CatalogType.LIVE_TV
            ) {
                val target =
                    pendingResume.coerceAtMost(
                        (knownDuration - 1_000L).coerceAtLeast(0L)
                    )
                val actual = player.time.coerceAtLeast(0L)
                val reached =
                    resumeSeekAttempts > 0 &&
                        kotlin.math.abs(actual - target) <= 2_500L

                if (reached || resumeSeekAttempts >= 3) {
                    pendingInitialResumePosition = 0L
                    position = actual
                } else {
                    player.time = target
                    resumeSeekAttempts++
                    position = target
                }
            } else if (pendingResume > 0L) {
                position = pendingResume
            } else {
                position = player.time.coerceAtLeast(0L)
            }

            if (
                media.progressKey.isNotBlank() &&
                pendingInitialResumePosition == 0L
            ) {
                onProgress(media.progressKey, position, duration)
            }

            delay(500L)
        }
    }

    Box(
        modifier.fillMaxSize()
            .playerActivityObserver {
                dpadInteraction++
            }
            .background(Color.Black)
            .then(
                if (focusMode || inPictureInPicture) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            )
    ) {
        // VLC_PLAYER_SURFACE_PER_MEDIA_V2
        //
        // AndroidView normally survives recomposition. Previous/Next replaces
        // the LibVLC MediaPlayer, so retaining the old VLCVideoLayout leaves it
        // attached to the released player: the new channel's audio advances
        // while the visible video frame remains frozen. Recreate and attach the
        // surface for every playback identity, matching the Media3 lifecycle.
        key(media.progressKey, media.url) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                    var gestureStartY = 0f
                    var gestureStartValue = 0f
                    var brightnessGesture = false
                    var adjustingLevel = false
                    videoView = layout
                    // Native VLC focus is only the embedded Showcase
                    // D-pad bridge. Standalone/fullscreen focus stays in Compose.
                    layout.isFocusable = embeddedMode
                    layout.isFocusableInTouchMode = embeddedMode
                    layout.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus && embeddedMode) {
                            if (suppressNextEmbeddedPlayerFocusHandoff) {
                                suppressNextEmbeddedPlayerFocusHandoff = false
                            } else {
                                controlsVisible = true

                                /*
                                 * Bridge deliberate D-pad entry from the
                                 * Showcase rail into Compose controls. The
                                 * existing browse-dismiss counter arms this
                                 * only after the rail has owned focus, avoiding
                                 * startup focus theft.
                                 */
                                if (
                                    embeddedPlayerFocusHandoffArmed &&
                                    !layout.isInTouchMode
                                ) {
                                    showControls()
                                }
                            }
                        }
                    }
                    layout.setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                dpadInteraction++
                                gestureStartY = event.y
                                brightnessGesture = event.x < layout.width / 2f
                                adjustingLevel = false
                                gestureStartValue = if (brightnessGesture) {
                                    val configured = activity?.window?.attributes?.screenBrightness ?: -1f
                                    if (configured >= 0f) configured else {
                                        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                    }
                                } else {
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                }
                            }
                            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                                val deltaY = gestureStartY - event.y
                                if (adjustingLevel || kotlin.math.abs(deltaY) > 24f * layout.resources.displayMetrics.density) {
                                    adjustingLevel = true
                                    val level = (gestureStartValue + deltaY / layout.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    if (brightnessGesture) {
                                        activity?.window?.attributes = activity?.window?.attributes?.apply {
                                            screenBrightness = level.coerceAtLeast(0.01f)
                                        }
                                    } else {
                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                    }
                                    gestureFeedback = brightnessGesture to level
                                }
                            }
                            MotionEvent.ACTION_UP -> if (!adjustingLevel) {
                                controlsVisible = !controlsVisible
                            }
                        }
                        true
                    }
                    layout.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (keyCode != KeyEvent.KEYCODE_BACK) dpadInteraction++
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(); true }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                if (player.isPlaying) player.pause() else player.play(); showControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                if (seekable) player.time = (player.time - 10_000L).coerceAtLeast(0L); showControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (seekable) player.time = (player.time + 10_000L).coerceAtMost(duration); showControls(); true
                            }
                            KeyEvent.KEYCODE_BACK -> false
                            else -> { showControls(); true }
                        }
                    }
                    // Fullscreen transitions can recreate this AndroidView while
                    // retaining the same LibVLC MediaPlayer. LibVLC rejects a
                    // second view attachment, so release the previous surface
                    // before binding the replacement layout.
                    runCatching { player.detachViews() }
                    // TextureView avoids rotated SurfaceView buffer-size
                    // rejection on Fire TV/tablet-style landscape devices.
                    player.attachViews(layout, null, false, true)
                    }
                },
                modifier = Modifier.fillMaxSize().then(
                    if (!focusMode && !embeddedMode) Modifier.padding(
                        top = if (compactMobileControls) 58.dp else 76.dp,
                        bottom = if (compactMobileControls) 112.dp else 148.dp
                    ) else Modifier
                )
            )
        }
        if (
            !embeddedMode &&
            !controlsVisible &&
            !queueVisible &&
            !pictureEditorVisible &&
            !inPictureInPicture
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(videoSurfaceFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (event.key) {
                                ComposeKey.DirectionCenter,
                                ComposeKey.Enter,
                                ComposeKey.DirectionLeft,
                                ComposeKey.DirectionRight,
                                ComposeKey.DirectionUp,
                                ComposeKey.DirectionDown -> {
                                    dpadInteraction++
                                    showControls()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                    .focusable()
            )
        }

        VideoAppearanceOverlay(activeAppearanceProfile)

        if ((controlsVisible || (!focusMode && !embeddedMode)) && !inPictureInPicture) {
            Box(
                Modifier.fillMaxSize().then(
                    if (focusMode) Modifier.background(
                        Brush.verticalGradient(listOf(Color.Black.copy(.82f), Color.Transparent, Color.Black.copy(.88f)))
                    ) else Modifier
                )
            ) {
                Row(
                    Modifier.fillMaxWidth()
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
                        modifier = Modifier.focusRequester(backRequester)
                            .focusProperties {
                                right = if (pipAvailable) pipRequester else playerSwitchRequester
                                down = playRequester
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
                        Text("VLC · ${resizeMode.label} · ${activeAppearanceProfile.name}", color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    if (pipAvailable) IconButton(
                        onClick = {
                            controlsVisible = false
                            controlsFocused = false
                            pipActivity?.enterPlayerPictureInPicture()
                        },
                        modifier = Modifier.focusRequester(pipRequester)
                            .focusProperties { left = backRequester; right = playerSwitchRequester; down = playRequester }
                            .playerControlFocus(CircleShape) { controlsFocused = it }
                    ) { Icon(Icons.Default.PictureInPictureAlt, "Picture in Picture", tint = Color.White) }
                    IconButton(
                        onClick = {
                            modeFeedback = "Player · Media3"
                            scope.launch {
                                delay(700L)
                                onSwitchPlayer(player.time.coerceAtLeast(0L))
                            }
                        },
                        modifier = Modifier.focusRequester(playerSwitchRequester)
                            .focusProperties {
                                left = if (pipAvailable) pipRequester else backRequester
                                right = resizeRequester
                                down = playRequester
                            }
                            .playerControlFocus(CircleShape) { controlsFocused = it }
                    ) {
                        Icon(Icons.Default.SwapHoriz, "Switch player: VLC", tint = Color.White)
                    }
                    PlayerVisualButtons(
                        resizeMode = resizeMode,
                        onResize = {
                            resizeMode = resizeMode.next()
                            modeFeedback = "Video fit · ${resizeMode.label}"
                        },
                        profiles = appearanceProfiles,
                        activeProfile = activeAppearanceProfile,
                        onPictureMode = { next ->
                            appearanceOverrideId = next.id
                            modeFeedback = "Picture mode · ${next.name}"
                        },
                        onEditPictureMode = { queueVisible = false; pictureEditorVisible = true },
                        resizeRequester = resizeRequester,
                        pictureModeRequester = pictureModeRequester,
                        pictureSettingsRequester = pictureSettingsRequester,
                        leftRequester = playerSwitchRequester,
                        rightRequester = fullscreenRequester,
                        downRequester = playRequester,
                        onControlsFocused = { controlsFocused = it }
                    )
                    IconButton(
                        onClick = {
                            val entering = !focusMode
                            if (startFullscreen && !entering) onBack() else {
                                focusMode = entering
                                onFullscreenChanged?.invoke(entering)
                                controlsVisible = !entering
                                controlsFocused = false
                                if (entering) {
                                    runCatching { videoSurfaceFocusRequester.requestFocus() }
                                } else {
                                    showControls()
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(fullscreenRequester)
                            .focusProperties {
                                left = pictureSettingsRequester
                                down = playRequester
                            }
                            .playerControlFocus(CircleShape) { controlsFocused = it }
                    ) { Icon(if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White) }
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
                    Row(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == ComposeKey.DirectionUp) {
                                fullscreenRequester.requestFocus(); true
                            } else if (
                                focusMode &&
                                event.type == KeyEventType.KeyDown &&
                                event.key == ComposeKey.DirectionDown &&
                                hasPlaybackQueue &&
                                !pictureEditorVisible &&
                                !queueVisible
                            ) {
                                queueVisible = true
                                true
                            } else false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (media.previousEpisode != null) IconButton(
                            onClick = { if (!advancing) { advancing = true; onPlayPrevious() } },
                            modifier = Modifier.focusRequester(previousRequester).playerControlFocus(CircleShape) { controlsFocused = it }
                        ) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                        if (seekable) IconButton(
                            onClick = { player.time = (player.time - 10_000L).coerceAtLeast(0L) },
                            modifier = Modifier.focusRequester(rewindRequester).playerControlFocus(CircleShape) { controlsFocused = it }
                        ) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White) }
                        FilledIconButton(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier.size(if (compactMobileControls) 44.dp else 52.dp).focusRequester(playRequester)
                                .focusProperties {
                                    up = fullscreenRequester
                                    left = if (seekable) rewindRequester else previousRequester
                                    right = if (seekable) forwardRequester else nextRequester
                                }
                                .playerControlFocus(CircleShape) { controlsFocused = it },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
                        if (seekable) IconButton(
                            onClick = { player.time = (player.time + 10_000L).coerceAtMost(duration) },
                            modifier = Modifier.focusRequester(forwardRequester).playerControlFocus(CircleShape) { controlsFocused = it }
                        ) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White) }
                        if (media.nextEpisode != null) IconButton(
                            onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                            modifier = Modifier.focusRequester(nextRequester).playerControlFocus(CircleShape) { controlsFocused = it }
                        ) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                        Spacer(Modifier.width(12.dp))
                        if (seekable) PlaybackProgressBar(
                            position = position,
                            duration = duration,
                            onSeek = { player.time = it },
                            compact = compactMobileControls,
                            modifier = Modifier.weight(1f).focusRequester(progressRequester)
                                .playerControlFocus(RoundedCornerShape(28.dp)) { controlsFocused = it }
                        ) else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(Color(0xFFE50914), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (queueVisible && focusMode && !pictureEditorVisible) PlayerQueueOverlay(
            items = playerQueueItems,
            playingId = media.media.id,
            hasMore = queueHasMore,
            loadingMore = queueLoadingMore,
            onLoadMore = onLoadMoreQueue,
            onDismiss = {
                queueVisible = false
                dpadInteraction++
                showControls()
            },
            onSelect = {
                queueVisible = false
                onPlayItem(it)
            }
        )
        if (pictureEditorVisible) PlayerPictureModeEditor(
            profiles = appearanceProfiles,
            selectedId = activeAppearanceProfile.id,
            onDismiss = { pictureEditorVisible = false; appearancePreview = null; dpadInteraction++; showControls() },
            onPreview = { appearancePreview = it },
            onSelected = { appearanceOverrideId = it }
        )

        if (buffering && error == null) Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFFE50914))
            Text("Connecting to stream…", color = Color.White)
        }
        error?.let {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE181818)
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(42.dp), tint = Color(0xFFE50914))
                    Spacer(Modifier.height(10.dp))
                    Text("This title can’t be played right now", style = MaterialTheme.typography.titleLarge)
                    Text(it, color = Color.LightGray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Go back") }
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
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp).width(112.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                        contentDescription = null
                    )
                    Text(if (isBrightness) "Brightness" else "Volume")
                    Box(
                        Modifier.height(120.dp).width(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight(level.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text("${(level * 100).toInt()}%")
                }
            }
        }
        modeFeedback?.let { PlayerModeFeedback(it) }
    }
}
