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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.nikhil.niktv.R
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem as NikMediaItem
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.SubtitleSearchRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.io.File

private data class Media3SubtitleTrack(
    val id: String,
    val label: String,
    val group: Tracks.Group,
    val trackIndex: Int
)

private const val DOWNLOADED_SUBTITLE_TRACK_ID = "niktv:downloaded"

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

internal fun PlaybackEngine.nextPlayerChoice(): PlaybackEngine = when (this) {
    PlaybackEngine.AUTO -> PlaybackEngine.MEDIA3
    PlaybackEngine.MEDIA3 -> PlaybackEngine.VLC
    PlaybackEngine.VLC -> PlaybackEngine.EXOPLAYER
    PlaybackEngine.EXOPLAYER -> PlaybackEngine.AUTO
}

internal fun PlaybackEngine.playerChoiceLabel(): String = when (this) {
    PlaybackEngine.AUTO -> "Auto"
    PlaybackEngine.MEDIA3 -> "Media3"
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.EXOPLAYER -> "ExoPlayer"
}

internal val PLAYER_CONTROLS_TIMEOUT_OPTIONS = listOf(3, 5, 10, 15)

internal fun nextPlayerControlsTimeoutSeconds(current: Int): Int {
    val exactIndex = PLAYER_CONTROLS_TIMEOUT_OPTIONS.indexOf(current)
    if (exactIndex >= 0) {
        return PLAYER_CONTROLS_TIMEOUT_OPTIONS[
            (exactIndex + 1) % PLAYER_CONTROLS_TIMEOUT_OPTIONS.size
        ]
    }
    return PLAYER_CONTROLS_TIMEOUT_OPTIONS.firstOrNull { it > current }
        ?: PLAYER_CONTROLS_TIMEOUT_OPTIONS.first()
}

@Composable
internal fun PlayerControlsTimeoutButton(
    seconds: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val displaySeconds = seconds.coerceIn(0, 99).toString()
    val badgeWidth = if (displaySeconds.length > 1) 20.dp else 17.dp

    IconButton(
        onClick = { onChange(nextPlayerControlsTimeoutSeconds(seconds)) },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Timer,
                "Controls hide after ${seconds}s",
                Modifier.fillMaxSize(),
                tint = Color.White
            )

            Surface(
                modifier = Modifier
                    .offset(y = 2.dp)
                    .width(badgeWidth)
                    .height(17.dp),
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFF090909),
                contentColor = Color.White
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displaySeconds,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun PlaybackEngine.playerEngineLabel(): String = when (this) {
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.EXOPLAYER -> "ExoPlayer"
    else -> "Media3"
}

private fun PlaybackEngine.resolvePlayerEngine(
    context: Context,
    playbackScope: String
): PlaybackEngine = when (this) {
    PlaybackEngine.AUTO ->
        if (PlayerEngineFallback.prefersVlc(context, playbackScope)) PlaybackEngine.VLC
        else PlaybackEngine.MEDIA3
    else -> this
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
    onDownload: () -> Unit = {},
    offlineDownloadPresent: Boolean = false,
    offlineDownloadInProgress: Boolean = false,
    offlineDownloadProgress: Float? = null,
    offlineDownloadProgressText: String? = null,
    onPlayItem: (NikMediaItem) -> Unit = {},
    queueHasMore: Boolean = false,
    queueLoadingMore: Boolean = false,
    onLoadMoreQueue: () -> Boolean = { false },
    controlsTimeoutSeconds: Int = 3,
    onControlsTimeoutChanged: (Int) -> Unit = {},
    playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    onPlaybackEngineChanged: (PlaybackEngine) -> Unit = {},
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
    var downloadRequested by remember(media.progressKey) { mutableStateOf(false) }
    LaunchedEffect(downloadRequested, offlineDownloadInProgress) {
        if (offlineDownloadInProgress) {
            downloadRequested = false
        } else if (downloadRequested) {
            delay(5_000L)
            downloadRequested = false
        }
    }
    val displayedDownloadInProgress = offlineDownloadInProgress || downloadRequested
    val playbackScope = media.series?.id ?: media.progressKey.ifBlank { media.media.id }
    var sessionEngineOverride by remember(media.media.id) { mutableStateOf<PlaybackEngine?>(null) }
    var subtitleDelayMs by remember(media.media.id) { mutableLongStateOf(0L) }
    var externalSubtitleFile by remember(media.media.id) { mutableStateOf<File?>(null) }
    var externalSubtitleEnabled by remember(media.media.id) { mutableStateOf(false) }
    var subtitleAppearance by remember { mutableStateOf(SubtitleAppearancePreset.STANDARD) }
    DisposableEffect(media.media.id, externalSubtitleFile?.absolutePath) {
        val episodeSubtitleFile = externalSubtitleFile
        onDispose {
            episodeSubtitleFile?.delete()
        }
    }
    var engineSwitchResumePosition by remember(media.progressKey) {
        mutableLongStateOf(media.resumePositionMillis)
    }
    var restorePlayerSwitchFocus by remember(media.progressKey) {
        mutableStateOf(false)
    }
    val configuredEngine = if (media.offlinePlayback) PlaybackEngine.MEDIA3 else when (playbackEngine) {
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
            onDownload = onDownload,
            offlineDownloadPresent = offlineDownloadPresent,
            offlineDownloadInProgress = offlineDownloadInProgress,
            offlineDownloadProgress = offlineDownloadProgress,
            offlineDownloadProgressText = offlineDownloadProgressText,
            initialSubtitleDelayMs = subtitleDelayMs,
            initialExternalSubtitleFile = externalSubtitleFile,
            onPlaybackAuthorizationFailure = onPlaybackAuthorizationFailure,
            queueHasMore = queueHasMore,
            queueLoadingMore = queueLoadingMore,
            onLoadMoreQueue = onLoadMoreQueue,
            modifier = modifier,
            embeddedMode = embeddedMode,
            controlsTimeoutSeconds = controlsTimeoutSeconds,
            onControlsTimeoutChanged = onControlsTimeoutChanged,
            embeddedControlsDismissRequest = embeddedControlsDismissRequest,
            startFullscreen = startFullscreen,
            fullscreenOverride = fullscreenOverride,
            onFullscreenChanged = onFullscreenChanged,
            onSwitchPlayer = { position ->
                val nextEngine = playbackEngine.nextPlayerChoice()
                engineSwitchResumePosition = position
                restorePlayerSwitchFocus = true
                onPlaybackEngineChanged(nextEngine)
                sessionEngineOverride = nextEngine.resolvePlayerEngine(context, playbackScope)
            },
            configuredEngine = playbackEngine,
            focusPlayerSwitchOnEnter = restorePlayerSwitchFocus,
            onPlayerSwitchFocusRestored = { restorePlayerSwitchFocus = false }
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
    val (appearanceProfiles, persistedAppearanceProfile) =
        rememberVideoAppearanceProfiles(useSchedule = true)
    var appearancePreview by remember { mutableStateOf<VideoAppearanceProfile?>(null) }
    val activeAppearanceProfile =
        appearancePreview ?: persistedAppearanceProfile
    var modeFeedback by remember { mutableStateOf<String?>(null) }
    var queueVisible by remember(media.progressKey) { mutableStateOf(false) }
    var queueRevealProgress by remember(media.progressKey) { mutableFloatStateOf(0f) }
    var queueRevealDragging by remember(media.progressKey) { mutableStateOf(false) }
    var pictureEditorVisible by remember { mutableStateOf(false) }
    var moreOptionsOpen by remember(media.progressKey) { mutableStateOf(false) }
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
        if (!focusMode) {
            queueVisible = false
            queueRevealProgress = 0f
            queueRevealDragging = false
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
    var playbackRequested by remember(media.progressKey) { mutableStateOf(true) }
    var playbackState by remember(media.progressKey) { mutableIntStateOf(Player.STATE_IDLE) }
    var position by remember(media.progressKey) { mutableLongStateOf(0L) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var videoDetails by remember(media.progressKey) { mutableStateOf("") }
    var playbackError by remember(media.progressKey) { mutableStateOf<String?>(null) }
    var subtitleDialogOpen by remember(media.progressKey) { mutableStateOf(false) }
    var subtitleTracks by remember(media.progressKey) { mutableStateOf<List<Media3SubtitleTrack>>(emptyList()) }

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
    val downloadFocusRequester = remember(media.progressKey) { FocusRequester() }
    val subtitleFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pipFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchFocusRequester = remember(media.progressKey) { FocusRequester() }
    val resizeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pictureSettingsFocusRequester = remember(media.progressKey) { FocusRequester() }
    val controlsTimeoutFocusRequester = remember(media.progressKey) { FocusRequester() }
    val moreFocusRequester = remember(media.progressKey) { FocusRequester() }
    val fullscreenFocusRequester = remember(media.progressKey) { FocusRequester() }
    val previousFocusRequester = remember(media.progressKey) { FocusRequester() }
    val rewindFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playPauseFocusRequester = remember(media.progressKey) { FocusRequester() }
    val forwardFocusRequester = remember(media.progressKey) { FocusRequester() }
    val nextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val progressFocusRequester = remember(media.progressKey) { FocusRequester() }
    val videoSurfaceFocusRequester = remember(media.progressKey) { FocusRequester() }

    LaunchedEffect(restorePlayerSwitchFocus, effectiveEngine) {
        if (restorePlayerSwitchFocus) {
            controlsVisible = true
            delay(120L)
            runCatching { moreFocusRequester.requestFocus() }
            restorePlayerSwitchFocus = false
        }
    }
    val errorBackFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorRetryFocusRequester = remember(media.progressKey) { FocusRequester() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pipActivity = activity as? MainActivity
    val pipAvailable = remember(context) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    val player = remember(media.progressKey, effectiveEngine, media.url) {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            if (effectiveEngine == PlaybackEngine.MEDIA3) {
                // Media3 mode applies NikTV's learned decoder policy.
                setEnableDecoderFallback(true)
                setMediaCodecSelector(FailedDecoderRegistry.selector(context, playbackScope))
            }
            // ExoPlayer compatibility mode intentionally retains the device's
            // native decoder order and default fallback behavior.
        }
        val builder = ExoPlayer.Builder(context, renderersFactory)
        if (media.offlinePlayback) {
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(OfflineMediaDownloads.cacheDataSourceFactory(context))
            )
        }
        builder.build().apply {
            val mediaItemBuilder = MediaItem.Builder().setUri(media.url)
            setMediaItem(mediaItemBuilder.build())
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
                if (
                    state == Player.STATE_ENDED &&
                    media.catalogType == CatalogType.SERIES &&
                    media.nextEpisode != null &&
                    !autoPlayCancelled &&
                    !advancing
                ) {
                    advancing = true
                    subtitleDialogOpen = false
                    onPlayNext()
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                subtitleTracks = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group ->
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            Media3SubtitleTrack(
                                id = "${group.mediaTrackGroup.id}:$index",
                                label = format.label
                                    ?: format.language?.let { language -> Locale.forLanguageTag(language).displayName }
                                    ?: "Subtitle ${index + 1}",
                                group = group,
                                trackIndex = index
                            )
                        }
                    }
            }
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playbackRequested = value
            }
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
                if ((remainingMillis <= 750L || player.playbackState == Player.STATE_ENDED) && !advancing) {
                    advancing = true
                    subtitleDialogOpen = false
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
        pictureEditorVisible,
        moreOptionsOpen
    ) {
        val canAutoHide =
            controlsVisible &&
                isPlaying &&
                playbackError == null &&
                !startupTimedOut
                && !queueVisible
                && !pictureEditorVisible
                && !moreOptionsOpen

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
                runCatching { videoSurfaceFocusRequester.requestFocus() }
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
            !restorePlayerSwitchFocus &&
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

    /*
     * FULLSCREEN_COMPOSE_VIDEO_FOCUS_V19
     *
     * Standalone/fullscreen playback must not leave Android PlayerView as the
     * resting D-pad focus target. Some TV devices draw a focused-view treatment
     * over PlayerView, which makes the video look selected/dim.
     *
     * Hidden controls therefore park focus on a transparent Compose target.
     * Embedded Showcase playback keeps its native PlayerView focus bridge.
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
            moreOptionsOpen -> moreOptionsOpen = false
            queueVisible -> queueVisible = false
            pictureEditorVisible -> {
                pictureEditorVisible = false
                appearancePreview = null
            }
            controlsVisible -> {
                controlsVisible = false
                controlsFocused = false
                if (embeddedMode && playerViewRef != null) {
                    suppressNextEmbeddedPlayerFocusHandoff = true
                    playerViewRef?.requestFocus()
                } else {
                    runCatching { videoSurfaceFocusRequester.requestFocus() }
                }
            }
            // A direct-fullscreen player gets a two-step Back interaction:
            // first hide controls, then leave playback on the next Back.
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
            .playerMediaKeys(playbackRequested) { requested ->
                playbackRequested = requested
                if (requested) player.play() else player.pause()
                controlsVisible = true
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
                    // Embedded Showcase needs native View focus as its
                    // rail-to-player bridge. Standalone/fullscreen keeps focus
                    // in Compose so the video surface never looks selected.
                    isFocusable = embeddedMode
                    isFocusableInTouchMode = embeddedMode
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
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
                    var levelGestureEligible = false
                    var adjustingLevel = false
                    var gestureConsumed = false
                    var tapCandidate = false
                    var queueGestureOwned = false
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
                        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && keyEvent.repeatCount > 0) return@setOnKeyListener true
                        if (keyCode != KeyEvent.KEYCODE_BACK) dpadInteraction++
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                showControlsAndFocusPlayPause()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                playbackRequested = !playbackRequested; if (playbackRequested) player.play() else player.pause()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                playbackRequested = true
                                player.play()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                playbackRequested = false
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
                                val topSystemGestureInset =
                                    48f * resources.displayMetrics.density
                                if (
                                    focusMode &&
                                    event.y < topSystemGestureInset
                                ) {
                                    // Reserve the top edge for Android's transient
                                    // status/navigation-bar reveal gesture.
                                    return@setOnTouchListener false
                                }
                                dpadInteraction++
                                lastTouch = Offset(event.x, event.y)
                                gestureStartY = event.y
                                val sideBand = playerView.width * 0.34f
                                val brightnessBand = event.x <= sideBand
                                val volumeBand = event.x >= playerView.width - sideBand
                                levelGestureEligible = brightnessBand || volumeBand
                                brightnessGesture = brightnessBand
                                adjustingLevel = false
                                gestureConsumed = false
                                tapCandidate = true
                                queueGestureOwned =
                                    focusMode &&
                                        hasPlaybackQueue &&
                                        !pictureEditorVisible &&
                                        event.y >= playerView.height * 0.72f
                                gestureStartValue = when {
                                    !levelGestureEligible -> 0f
                                    brightnessGesture -> {
                                        val windowValue = activity?.window?.attributes?.screenBrightness ?: -1f
                                        if (windowValue >= 0f) windowValue else {
                                            Settings.System.getInt(viewContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                        }
                                    }
                                    else -> {
                                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    }
                                }
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> lastTouch = Offset(event.x, event.y)
                            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                                val movement = Offset(event.x, event.y) - lastTouch
                                if (movement.getDistance() > tapSlop) tapCandidate = false
                                val queueSwipeDistance = gestureStartY - event.y
                                if (queueGestureOwned) {
                                    val progress =
                                        (queueSwipeDistance / (playerView.height * 0.32f))
                                            .coerceIn(0f, 1f)
                                    if (progress > 0f) {
                                        queueVisible = true
                                        queueRevealDragging = true
                                        queueRevealProgress = progress
                                        controlsVisible = false
                                        controlsFocused = false
                                        gestureConsumed = true
                                    }
                                    // The bottom-edge swipe belongs to the queue sheet,
                                    // not brightness/volume adjustment.
                                    Unit
                                } else if (videoScale > 1f) {
                                    val current = Offset(event.x, event.y)
                                    val delta = current - lastTouch
                                    if (delta.getDistance() > 1f) {
                                        moveVideoBy(delta)
                                        panned = true
                                    }
                                    lastTouch = current
                                } else {
                                    val deltaY = gestureStartY - event.y
                                    if (
                                        levelGestureEligible &&
                                        (adjustingLevel || kotlin.math.abs(deltaY) > 24f * resources.displayMetrics.density)
                                    ) {
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
                                if (queueGestureOwned) {
                                    queueRevealDragging = false
                                    if (queueRevealProgress >= 0.18f) {
                                        queueRevealProgress = 1f
                                        queueVisible = true
                                    } else {
                                        queueRevealProgress = 0f
                                        queueVisible = false
                                    }
                                    queueGestureOwned = false
                                }
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
        DownloadedSubtitleOverlay(
            file = externalSubtitleFile,
            positionMs = position,
            delayMs = subtitleDelayMs,
            enabled = externalSubtitleEnabled,
            appearance = subtitleAppearance,
            modifier = Modifier.fillMaxSize().padding(bottom = if (controlsVisible) 118.dp else 24.dp)
        )
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
                        if (event.type != ComposeKeyEventType.KeyDown) {
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
                                    showControlsAndFocusPlayPause()
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
            val seekable = duration > 0L && media.catalogType != CatalogType.LIVE_TV
            val topDownRequester = if (seekable) progressFocusRequester else playPauseFocusRequester
            val playbackDetailLines = buildList {
                videoDetails.takeIf { it.isNotBlank() }?.let { add(it) }
                add("${if (media.offlinePlayback) "Offline" else "IPTV stream"} · ${media.playbackFormat.ifBlank { mediaFormatLabel(media.url) }}")
                add("Player · ${effectiveEngine.playerEngineLabel()} · Video fit · ${resizeMode.label}")
                add("Picture · ${activeAppearanceProfile.name} · Controls · ${controlsTimeoutSeconds}s")
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.76f),
                            0.22f to Color.Transparent,
                            0.68f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (focusMode) Modifier.statusBarsPadding() else Modifier)
                        .padding(
                            horizontal = if (compactMobileControls) 10.dp else 20.dp,
                            vertical = if (compactMobileControls) 8.dp else 14.dp
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    PlayerChromeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(backFocusRequester)
                            .focusProperties {
                                right = if (media.catalogType != CatalogType.LIVE_TV) downloadFocusRequester else subtitleFocusRequester
                                down = topDownRequester
                            }
                            .playerDpadFocusRoutes(
                                right = if (media.catalogType != CatalogType.LIVE_TV) downloadFocusRequester else subtitleFocusRequester,
                                down = topDownRequester
                            ),
                        onFocused = { controlsFocused = it }
                    )
                    Spacer(Modifier.width(if (compactMobileControls) 8.dp else 12.dp))
                    Column(
                        Modifier.weight(1f).padding(top = 2.dp, end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            media.media.title,
                            color = Color.White,
                            style = if (compactMobileControls) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1
                        )
                        media.series?.let {
                            Text(
                                it.title,
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                        PlayerDateTime(compact = compactMobileControls)
                        PlayerDownloadStatusPill(offlineDownloadProgressText.orEmpty())
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(if (compactMobileControls) 4.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (media.catalogType != CatalogType.LIVE_TV) {
                            PlayerChromeIconButton(
                                icon = if (offlineDownloadPresent && !displayedDownloadInProgress) Icons.Default.DownloadDone else Icons.Default.DownloadForOffline,
                                contentDescription = if (offlineDownloadPresent) "Cancel or remove offline download" else "Download for offline playback",
                                onClick = {
                                    if (!offlineDownloadPresent) downloadRequested = true
                                    onDownload()
                                },
                                modifier = Modifier
                                    .focusRequester(downloadFocusRequester)
                                    .focusProperties {
                                        left = backFocusRequester
                                        right = subtitleFocusRequester
                                        down = topDownRequester
                                    }
                                    .playerDpadFocusRoutes(backFocusRequester, subtitleFocusRequester, topDownRequester),
                                selected = offlineDownloadPresent,
                                progress = offlineDownloadProgress.takeIf { displayedDownloadInProgress },
                                indeterminateProgress = displayedDownloadInProgress && offlineDownloadProgress == null,
                                onFocused = { controlsFocused = it }
                            )
                        }
                        PlayerChromeIconButton(
                            icon = Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            onClick = { subtitleDialogOpen = true },
                            modifier = Modifier
                                .focusRequester(subtitleFocusRequester)
                                .focusProperties {
                                    left = if (media.catalogType != CatalogType.LIVE_TV) downloadFocusRequester else backFocusRequester
                                    right = resizeFocusRequester
                                    down = topDownRequester
                                }
                                .playerDpadFocusRoutes(
                                    left = if (media.catalogType != CatalogType.LIVE_TV) downloadFocusRequester else backFocusRequester,
                                    right = resizeFocusRequester,
                                    down = topDownRequester
                                ),
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                            icon = when (resizeMode) {
                                VideoResizeMode.FIT -> Icons.Default.FitScreen
                                VideoResizeMode.FILL -> Icons.Default.CropFree
                                VideoResizeMode.ZOOM -> Icons.Default.ZoomIn
                                VideoResizeMode.STRETCH -> Icons.Default.AspectRatio
                            },
                            contentDescription = "Video fit: ${resizeMode.label}",
                            onClick = {
                                resizeMode = resizeMode.next()
                                videoScale = 1f
                                videoOffset = Offset.Zero
                                modeFeedback = "Video fit · ${resizeMode.label}"
                            },
                            modifier = Modifier
                                .focusRequester(resizeFocusRequester)
                                .focusProperties {
                                    left = subtitleFocusRequester
                                    right = moreFocusRequester
                                    down = topDownRequester
                                }
                                .playerDpadFocusRoutes(
                                    left = subtitleFocusRequester,
                                    right = moreFocusRequester,
                                    down = topDownRequester
                                ),
                            selected = resizeMode != VideoResizeMode.FIT,
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                            icon = Icons.Default.MoreHoriz,
                            contentDescription = "More playback options",
                            onClick = { moreOptionsOpen = true },
                            modifier = Modifier
                                .focusRequester(moreFocusRequester)
                                .focusProperties {
                                    left = resizeFocusRequester
                                    down = topDownRequester
                                }
                                .playerDpadFocusRoutes(
                                    left = resizeFocusRequester,
                                    down = topDownRequester
                                ),
                            onFocused = { controlsFocused = it }
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(if (focusMode) Modifier.navigationBarsPadding() else Modifier)
                        .padding(
                            horizontal = if (compactMobileControls) 10.dp else 24.dp,
                            vertical = if (compactMobileControls) 8.dp else 14.dp
                        ),
                    shape = RoundedCornerShape(if (compactMobileControls) 18.dp else 22.dp),
                    color = Color.Black.copy(alpha = 0.58f),
                    contentColor = Color.White,
                    shadowElevation = if (focusMode) 8.dp else 2.dp
                ) {
                    Column(
                        Modifier.padding(
                            horizontal = if (compactMobileControls) 10.dp else 16.dp,
                            vertical = if (compactMobileControls) 8.dp else 11.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(if (compactMobileControls) 5.dp else 8.dp)
                    ) {
                        if (seekable) {
                            PlaybackProgressBar(
                                mediaKey = media.progressKey,
                                position = position,
                                duration = duration,
                                onSeek = { player.seekTo(it) },
                                compact = compactMobileControls,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(progressFocusRequester)
                                    .focusProperties {
                                        up = moreFocusRequester
                                        down = playPauseFocusRequester
                                    }
                                    .playerControlFocus(RoundedCornerShape(14.dp)) { controlsFocused = it }
                            )
                        } else if (media.catalogType == CatalogType.LIVE_TV) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    "LIVE",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                        if (playbackError == null && !startupTimedOut) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (media.previousEpisode != null) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.SkipPrevious,
                                        contentDescription = "Previous",
                                        onClick = { if (!advancing) { advancing = true; onPlayPrevious() } },
                                        modifier = Modifier.focusRequester(previousFocusRequester)
                                            .focusProperties { up = if (seekable) progressFocusRequester else moreFocusRequester },
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                if (seekable) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.Replay10,
                                        contentDescription = "Back 10 seconds",
                                        onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) },
                                        modifier = Modifier.focusRequester(rewindFocusRequester)
                                            .focusProperties { up = progressFocusRequester },
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                PlayerChromeIconButton(
                                    icon = if (playbackRequested) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playbackRequested) "Pause" else "Play",
                                    onClick = {
                                        playbackRequested = !playbackRequested
                                        if (playbackRequested) player.play() else player.pause()
                                    },
                                    modifier = Modifier
                                        .focusRequester(playPauseFocusRequester)
                                        .focusProperties {
                                            up = if (seekable) progressFocusRequester else moreFocusRequester
                                            left = when {
                                                seekable -> rewindFocusRequester
                                                media.previousEpisode != null -> previousFocusRequester
                                                else -> FocusRequester.Default
                                            }
                                            right = when {
                                                seekable -> forwardFocusRequester
                                                media.nextEpisode != null -> nextFocusRequester
                                                else -> FocusRequester.Default
                                            }
                                        },
                                    primaryAction = true,
                                    size = if (compactMobileControls) 50.dp else 58.dp,
                                    iconSize = if (compactMobileControls) 27.dp else 30.dp,
                                    onFocused = { controlsFocused = it }
                                )
                                if (seekable) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.Forward10,
                                        contentDescription = "Forward 10 seconds",
                                        onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration)) },
                                        modifier = Modifier.focusRequester(forwardFocusRequester)
                                            .focusProperties { up = progressFocusRequester },
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                if (media.nextEpisode != null) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.SkipNext,
                                        contentDescription = "Next",
                                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                                        modifier = Modifier.focusRequester(nextFocusRequester)
                                            .focusProperties { up = if (seekable) progressFocusRequester else moreFocusRequester },
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (moreOptionsOpen) {
                PlayerMoreOptionsDialog(
                    detailLines = playbackDetailLines,
                    currentPlayer = effectiveEngine.playerEngineLabel(),
                    onSwitchPlayer = {
                        moreOptionsOpen = false
                        val nextEngine = playbackEngine.nextPlayerChoice()
                        modeFeedback = "Player · ${nextEngine.playerChoiceLabel()}"
                        coroutineScope.launch {
                            delay(700L)
                            engineSwitchResumePosition = player.currentPosition.coerceAtLeast(0L)
                            restorePlayerSwitchFocus = true
                            onPlaybackEngineChanged(nextEngine)
                            sessionEngineOverride = nextEngine.resolvePlayerEngine(context, playbackScope)
                        }
                    },
                    pictureMode = activeAppearanceProfile,
                    onNextPictureMode = {
                        val current = appearanceProfiles.indexOfFirst { it.id == activeAppearanceProfile.id }.coerceAtLeast(0)
                        val next = appearanceProfiles[(current + 1) % appearanceProfiles.size]
                        VideoAppearancePreferences.setActive(context, next.id)
                        modeFeedback = "Picture mode · ${next.name}"
                    },
                    onEditPictureMode = {
                        moreOptionsOpen = false
                        queueVisible = false
                        pictureEditorVisible = true
                        appearancePreview = null
                    },
                    controlsTimeoutSeconds = controlsTimeoutSeconds,
                    onControlsTimeoutChanged = { seconds ->
                        onControlsTimeoutChanged(seconds)
                        modeFeedback = "Controls hide after ${seconds}s"
                    },
                    pipAvailable = pipAvailable,
                    onPictureInPicture = {
                        moreOptionsOpen = false
                        controlsVisible = false
                        controlsFocused = false
                        pipActivity?.enterPlayerPictureInPicture()
                    },
                    fullscreen = focusMode,
                    onToggleFullscreen = {
                        moreOptionsOpen = false
                        val enteringFullscreen = !focusMode
                        if (startFullscreen && !enteringFullscreen) {
                            onBack()
                        } else {
                            focusMode = enteringFullscreen
                            onFullscreenChanged?.invoke(enteringFullscreen)
                            controlsVisible = !enteringFullscreen
                            controlsFocused = false
                            if (enteringFullscreen) {
                                runCatching { videoSurfaceFocusRequester.requestFocus() }
                            } else {
                                showControlsAndFocusPlayPause()
                            }
                        }
                    },
                    onDismiss = {
                        moreOptionsOpen = false
                        coroutineScope.launch {
                            delay(80L)
                            runCatching { moreFocusRequester.requestFocus() }
                        }
                    }
                )
            }
        }
        if (queueVisible && focusMode && !pictureEditorVisible) PlayerQueueOverlay(
            items = playerQueueItems,
            playingId = media.media.id,
            hasMore = queueHasMore,
            loadingMore = queueLoadingMore,
            onLoadMore = onLoadMoreQueue,
            revealProgress = queueRevealProgress,
            revealDragging = queueRevealDragging,
            onDismiss = {
                queueVisible = false
                queueRevealProgress = 0f
                queueRevealDragging = false
                dpadInteraction++
                showControlsAndFocusPlayPause()
            },
            onSelect = {
                queueVisible = false
                onPlayItem(it)
            }
        )
        if (pictureEditorVisible) PlayerPictureModeEditor(
            profiles = appearanceProfiles,
            selectedId = activeAppearanceProfile.id,
            onDismiss = { pictureEditorVisible = false; appearancePreview = null; dpadInteraction++; showControlsAndFocusPlayPause() },
            onPreview = { appearancePreview = it },
            onSelected = {
                VideoAppearancePreferences.setActive(context, it)
            }
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
                    .align(if (isBrightness) Alignment.CenterEnd else Alignment.CenterStart)
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
                    .zIndex(20f)
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
        if (subtitleDialogOpen) {
            SubtitleSelectionDialog(
                tracks = buildList {
                    addAll(subtitleTracks.map { track ->
                        SubtitleTrackOption(track.id, track.label, !externalSubtitleEnabled && track.group.isTrackSelected(track.trackIndex))
                    })
                    externalSubtitleFile?.let {
                        add(SubtitleTrackOption(DOWNLOADED_SUBTITLE_TRACK_ID, "Downloaded - ${it.name}", externalSubtitleEnabled))
                    }
                },
                delayMs = subtitleDelayMs,
                onSelect = { id ->
                    val builder = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    if (id == DOWNLOADED_SUBTITLE_TRACK_ID) {
                        externalSubtitleEnabled = true
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    } else if (id == null) {
                        externalSubtitleEnabled = false
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    } else {
                        externalSubtitleEnabled = false
                        subtitleTracks.firstOrNull { it.id == id }?.let { track ->
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    TrackSelectionOverride(track.group.mediaTrackGroup, listOf(track.trackIndex))
                                )
                        }
                    }
                    player.trackSelectionParameters = builder.build()
                },
                onDelayChange = { delay ->
                    subtitleDelayMs = delay
                },
                onDismiss = { subtitleDialogOpen = false },
                timingRequiresVlc = false,
                appearance = subtitleAppearance,
                onAppearanceChange = { subtitleAppearance = it },
                internetSearch = SubtitleSearchRequest(
                    query = media.suggestedSubtitleSearchTitle(),
                    seasonNumber = media.media.seasonNumber,
                    episodeNumber = media.media.episodeNumber,
                    episodeTitle = media.media.title
                ),
                onExternalSubtitle = { file ->
                    externalSubtitleFile?.takeIf { it != file }?.delete()
                    externalSubtitleFile = file
                    externalSubtitleEnabled = true
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                },
                downloadedSubtitleName = externalSubtitleFile?.name,
                onDeleteDownloadedSubtitle = externalSubtitleFile?.let { file ->
                    {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        file.delete()
                        externalSubtitleFile = null
                        externalSubtitleEnabled = false
                    }
                }
            )
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

/*
 * MODERN_PLAYER_CHROME_V48
 *
 * One visual system for Media3/ExoPlayer and VLC:
 * - cinematic top/bottom scrims instead of opaque toolbars;
 * - compact identity metadata with download progress as a pill;
 * - a small primary action strip, with secondary/technical controls in More;
 * - an inset transport dock with a slim scrubber and separate time readout;
 * - TV-only focus chrome so touch devices do not retain a TV-style focus ring.
 */
@Composable
internal fun PlayerChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primaryAction: Boolean = false,
    progress: Float? = null,
    indeterminateProgress: Boolean = false,
    size: Dp = if (primaryAction) 56.dp else 48.dp,
    iconSize: Dp = if (primaryAction) 28.dp else 23.dp,
    onFocused: (Boolean) -> Unit = {}
) {
    val shape = RoundedCornerShape(if (primaryAction) 18.dp else 14.dp)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .background(
                if (primaryAction) Color.Black.copy(alpha = 0.64f)
                else Color.Black.copy(alpha = 0.46f),
                shape
            )
            .playerControlFocus(shape, onFocused)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (indeterminateProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.18f)
                )
            } else if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.18f)
                )
            }
            Icon(
                icon,
                contentDescription,
                modifier = Modifier.size(iconSize),
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
            )
        }
    }
}

@Composable
internal fun PlayerDownloadStatusPill(text: String) {
    if (text.isBlank()) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.52f),
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                Icons.Default.Download,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PlayerMoreOptionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .playerControlFocus(shape) {},
        shape = shape,
        color = Color.White.copy(alpha = 0.055f),
        contentColor = Color.White
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.92f))
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun PlayerMoreOptionsDialog(
    detailLines: List<String>,
    currentPlayer: String,
    onSwitchPlayer: () -> Unit,
    pictureMode: VideoAppearanceProfile,
    onNextPictureMode: () -> Unit,
    onEditPictureMode: () -> Unit,
    controlsTimeoutSeconds: Int,
    onControlsTimeoutChanged: (Int) -> Unit,
    pipAvailable: Boolean,
    onPictureInPicture: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstOptionRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100L)
        runCatching { firstOptionRequester.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Playback options", color = Color.White)
                Text(
                    "Technical details and less-frequent controls",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                Modifier.widthIn(min = 300.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (detailLines.any { it.isNotBlank() }) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Black.copy(alpha = 0.34f)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            detailLines.filter(String::isNotBlank).forEach { line ->
                                Text(
                                    line,
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                PlayerMoreOptionRow(
                    icon = Icons.Default.SmartDisplay,
                    label = "Player",
                    value = currentPlayer,
                    modifier = Modifier.focusRequester(firstOptionRequester),
                    onClick = onSwitchPlayer
                )
                if (pipAvailable) {
                    PlayerMoreOptionRow(
                        icon = Icons.Default.PictureInPictureAlt,
                        label = "Picture in Picture",
                        onClick = onPictureInPicture
                    )
                }
                PlayerMoreOptionRow(
                    icon = videoAppearanceIcon(pictureMode.id),
                    label = "Picture mode",
                    value = pictureMode.name,
                    onClick = onNextPictureMode
                )
                PlayerMoreOptionRow(
                    icon = Icons.Default.Tune,
                    label = "Picture settings",
                    onClick = onEditPictureMode
                )
                PlayerMoreOptionRow(
                    icon = Icons.Default.Timer,
                    label = "Controls hide",
                    value = "${controlsTimeoutSeconds}s",
                    onClick = {
                        onControlsTimeoutChanged(
                            nextPlayerControlsTimeoutSeconds(controlsTimeoutSeconds)
                        )
                    }
                )
                PlayerMoreOptionRow(
                    icon = if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    label = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                    onClick = onToggleFullscreen
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * A seek bar with an Android 16-style vertical indicator. The left-aligned
 * readout is clipped into active/inactive layers as the fill passes beneath it.
 */
/**
 * MODERN_PLAYER_CHROME_V48
 * Slim full-width scrubber with time readouts above the rail. D-pad Left/Right
 * keeps the existing delayed seek-preview behavior; touch drag still seeks.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlaybackProgressBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    mediaKey: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val safeDuration = duration.coerceAtLeast(0L)
    var preview by remember(mediaKey) { mutableStateOf<Long?>(null) }
    var revision by remember(mediaKey) { mutableIntStateOf(0) }
    var directionHeld by remember(mediaKey) { mutableStateOf(false) }
    var dragging by remember(mediaKey) { mutableStateOf(false) }
    val seek by rememberUpdatedState(onSeek)
    LaunchedEffect(mediaKey, revision, directionHeld, dragging) {
        val target = preview ?: return@LaunchedEffect
        if (directionHeld || dragging) return@LaunchedEffect
        delay(450L)
        seek(target.coerceIn(0L, safeDuration))
        delay(1_000L)
        preview = null
    }
    val safePosition = (preview ?: position).coerceIn(0L, safeDuration)
    val fraction = if (safeDuration > 0L) safePosition.toFloat() / safeDuration else 0f
    val elapsed = formatPlayerTime(safePosition)
    val remaining = formatPlayerTime(safeDuration - safePosition)
    val total = formatPlayerTime(safeDuration)
    Column(
        modifier
            .height(if (compact) 48.dp else 58.dp)
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 3.dp)
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    ComposeKey.DirectionLeft, ComposeKey.MediaRewind -> -1
                    ComposeKey.DirectionRight, ComposeKey.MediaFastForward -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == ComposeKeyEventType.KeyDown) {
                    directionHeld = true
                    preview = seekPreviewPosition(preview ?: position, direction, safeDuration)
                    revision++
                } else if (event.type == ComposeKeyEventType.KeyUp) {
                    directionHeld = false
                    revision++
                }
                true
            }
            .onFocusChanged { if (!it.hasFocus) directionHeld = false }
            .focusable(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                elapsed,
                color = Color.White.copy(alpha = 0.92f),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                "−$remaining  /  $total",
                color = Color.White.copy(alpha = 0.70f),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            )
        }
        Slider(
            value = safePosition.toFloat(),
            onValueChange = {
                dragging = true
                preview = it.toLong().coerceIn(0L, safeDuration)
                revision++
            },
            onValueChangeFinished = { dragging = false; revision++ },
            enabled = safeDuration > 0L,
            valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth().height(if (compact) 24.dp else 28.dp),
            thumb = {
                Box(
                    Modifier
                        .size(if (compact) 12.dp else 14.dp)
                        .shadow(4.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (compact) 4.dp else 5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.24f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
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
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isTvLikeDevice(configuration)
    val minimumSize = when {
        isTv -> 56.dp
        configuration.smallestScreenWidthDp < 600 -> 44.dp
        else -> 48.dp
    }
    return this
        .requiredSizeIn(minWidth = minimumSize, minHeight = minimumSize)
        .onFocusChanged {
            focused = it.isFocused
            onFocused(it.isFocused)
        }
        .then(
            if (focused && isTv) {
                Modifier
                    .shadow(
                        12.dp,
                        shape,
                        ambientColor = Color(0x99E50914),
                        spotColor = Color(0x99E50914)
                    )
                    .background(Color(0xFF351014), shape)
                    .border(2.dp, Color(0xFFFF4A54), shape)
            } else {
                Modifier
            }
        )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
