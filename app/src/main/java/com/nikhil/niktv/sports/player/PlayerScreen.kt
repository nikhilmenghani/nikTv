package com.nikhil.niktv.sports.player

import com.nikhil.niktv.ui.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastContext
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
import com.nikhil.niktv.data.LiveTvRecorder
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

private data class Media3AudioTrack(
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

/** Observe, without stealing taps, the middle-half swipe used to open the queue. */
internal fun Modifier.playerQueueSwipeObserver(
    enabled: Boolean,
    onOpen: () -> Unit
): Modifier = pointerInput(enabled, onOpen) {
    if (!enabled) return@pointerInput
    awaitPointerEventScope {
        var start = Offset.Zero
        var tracking = false
        var opened = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            if (change.pressed && !change.previousPressed) {
                start = change.position
                tracking = start.x in (size.width * .25f)..(size.width * .75f)
                opened = false
            } else if (change.pressed && tracking && !opened) {
                val delta = change.position - start
                if (delta.y < -40.dp.toPx() && kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)) {
                    opened = true
                    onOpen()
                }
            } else if (!change.pressed) {
                tracking = false
                opened = false
            }
        }
    }
}

internal fun PlaybackEngine.nextPlayerChoice(): PlaybackEngine = when (this) {
    PlaybackEngine.AUTO -> PlaybackEngine.MEDIA3
    PlaybackEngine.MEDIA3 -> PlaybackEngine.FLOW
    PlaybackEngine.FLOW -> PlaybackEngine.VLC
    PlaybackEngine.VLC -> if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) PlaybackEngine.MPV else PlaybackEngine.AUTO
    PlaybackEngine.MPV -> PlaybackEngine.AUTO
    PlaybackEngine.EXOPLAYER -> PlaybackEngine.MEDIA3
}

internal fun PlaybackEngine.playerChoiceLabel(): String = when (this) {
    PlaybackEngine.AUTO -> "Auto"
    PlaybackEngine.MEDIA3 -> "ExoPlayer"
    PlaybackEngine.FLOW -> "Flow"
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.MPV -> "MPV"
    PlaybackEngine.EXOPLAYER -> "ExoPlayer"
}

internal val PLAYER_ENGINE_OPTIONS = listOf(
    PlaybackEngine.AUTO,
    PlaybackEngine.MEDIA3,
    PlaybackEngine.FLOW,
    PlaybackEngine.VLC,
    PlaybackEngine.MPV
)

/*
 * PLAYER_PICTURE_TIMEOUT_FOCUS_V51
 *
 * Zero is the persisted sentinel for Infinite. Keep the existing numeric
 * values unchanged so old preferences/backups remain compatible.
 */
internal const val PLAYER_CONTROLS_TIMEOUT_INFINITE = 0
internal val PLAYER_CONTROLS_TIMEOUT_OPTIONS = listOf(
    3,
    5,
    10,
    15,
    PLAYER_CONTROLS_TIMEOUT_INFINITE
)

internal fun playerControlsTimeoutLabel(seconds: Int): String =
    if (seconds == PLAYER_CONTROLS_TIMEOUT_INFINITE) "Infinite" else "${seconds}s"

internal fun playerControlsTimeoutBadge(seconds: Int): String =
    if (seconds == PLAYER_CONTROLS_TIMEOUT_INFINITE) "∞" else "${seconds}s"

internal fun playerControlsTimeoutFeedback(seconds: Int): String =
    if (seconds == PLAYER_CONTROLS_TIMEOUT_INFINITE) {
        "Controls stay visible"
    } else {
        "Controls hide after ${seconds}s"
    }

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
    val displayValue =
        if (seconds == PLAYER_CONTROLS_TIMEOUT_INFINITE) "∞"
        else seconds.coerceIn(0, 99).toString()
    val badgeWidth = if (displayValue.length > 1) 20.dp else 17.dp

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
                playerControlsTimeoutFeedback(seconds),
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
                        text = displayValue,
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
    PlaybackEngine.FLOW -> "Flow"
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.MPV -> "MPV"
    else -> "ExoPlayer"
}

internal fun PlaybackEngine.resolvePlayerEngine(
    context: Context,
    playbackScope: String,
    domain: PlaybackDomain = PlaybackDomain.SPORTS
): PlaybackEngine = when (this) {
    PlaybackEngine.AUTO ->
        if (domain == PlaybackDomain.IPTV && PlayerEngineFallback.prefersVlc(context, playbackScope)) PlaybackEngine.VLC
        else PlaybackEngine.MEDIA3
    PlaybackEngine.EXOPLAYER -> PlaybackEngine.MEDIA3
    PlaybackEngine.MPV -> if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) PlaybackEngine.MPV else PlaybackEngine.MEDIA3
    else -> this
}

@UnstableApi
@Composable
fun PlayerScreen(
    media: PlayingMedia,
    onBack: () -> Unit,
    onRetry: (Long) -> Unit,
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
    onPlayQueueItem: ((NikMediaItem, Int) -> Unit)? = null,
    pinnedQueueIds: List<String> = emptyList(),
    queueFavoriteIds: Set<String> = emptySet(),
    onToggleQueueFavorite: ((NikMediaItem) -> Unit)? = null,
    onToggleQueuePin: ((NikMediaItem) -> Unit)? = null,
    onRefreshQueueProgramme: ((NikMediaItem) -> Unit)? = null,
    onVisibleQueueChannels: (List<NikMediaItem>) -> Unit = {},
    liveQueueCategoryTitle: String = "",
    liveQueueXtream: Boolean = false,
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
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    onPlaybackIntentChanged: (Boolean) -> Unit = {},
    onChooseAlternateSource: ((Long, () -> Long) -> Unit)? = null,
    alternateSourceChooserOpen: Boolean = false,
    onReauthenticate: ((Long) -> Unit)? = null,
    reauthenticating: Boolean = false,
    showLiveRecordingAndGuide: Boolean = true,
    onVideoFrameRendered: () -> Unit = {},
    onVideoFramesRendered: ((Int) -> Unit)? = null,
    onManualRetry: ((Long) -> Unit)? = null,
    playbackDomain: PlaybackDomain = PlaybackDomain.SPORTS,
    onTerminalPlaybackFailure: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val cricketScores = rememberCricketScoreController()
    val introSettings = rememberIntroSkipSettings(media)
    val playbackLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val isFireTv = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
        context.isTvLikeDevice(LocalConfiguration.current)
    var extraControlsOpen by remember(media.progressKey) { mutableStateOf(false) }
    val extraControlsRequester = remember(media.progressKey) { FocusRequester() }
    val transportActivity = remember(context) {
        context.findActivity() as? MainActivity
    }

    val channelTransportEnabled =
        media.catalogType == CatalogType.LIVE_TV &&
            (media.episodeQueue.distinctBy { it.id }.size > 1 || queueHasMore || queueLoadingMore)
    DisposableEffect(
        transportActivity,
        channelTransportEnabled,
        onPlayPrevious,
        onPlayNext
    ) {
        val handler: (KeyEvent) -> Boolean = { event ->
            if (!channelTransportEnabled) {
                false
            } else {
                val direction = liveChannelTransportDirection(event.keyCode, event.action, event.repeatCount)
                when (direction) {
                    1 -> onPlayNext()
                    -1 -> onPlayPrevious()
                }
                direction != null
            }
        }
        transportActivity?.playerTransportKeyHandler = handler
        onDispose {
            if (transportActivity?.playerTransportKeyHandler === handler) {
                transportActivity.playerTransportKeyHandler = null
            }
        }
    }
    val liveRecording by LiveTvRecorder.state.collectAsState()
    val recordingThisChannel = liveRecording.active && liveRecording.sourceUrl == media.url
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
    var engineSwitchResumePosition by remember(media.progressKey, media.playbackAttemptId) {
        mutableLongStateOf(playbackRestartPosition(media.catalogType, media.resumePositionMillis))
    }
    var restorePlayerSwitchFocus by remember(media.progressKey) {
        mutableStateOf(false)
    }
    var moreOptionsOpen by remember(media.progressKey) { mutableStateOf(false) }
    var selectedPlayerChoice by remember(media.progressKey) { mutableStateOf(playbackEngine) }
    LaunchedEffect(playbackEngine) {
        selectedPlayerChoice = playbackEngine
    }
    val configuredEngine = if (media.offlinePlayback && playbackEngine != PlaybackEngine.FLOW) PlaybackEngine.MEDIA3 else when (playbackEngine) {
        PlaybackEngine.FLOW -> PlaybackEngine.FLOW
        PlaybackEngine.VLC -> PlaybackEngine.VLC
        PlaybackEngine.MPV -> if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) PlaybackEngine.MPV else PlaybackEngine.MEDIA3
        PlaybackEngine.MEDIA3 -> PlaybackEngine.MEDIA3
        PlaybackEngine.EXOPLAYER -> PlaybackEngine.MEDIA3
        PlaybackEngine.AUTO -> PlaybackEngine.AUTO.resolvePlayerEngine(context, playbackScope, playbackDomain)
    }

    val requestedEngine = sessionEngineOverride ?: configuredEngine

    val effectiveEngine = if (media.nativeSabr != null || media.separateAudioUrl != null ||
        (media.streamDrm != null && !requestedEngine.isMedia3Player()) ||

        (requestedEngine == PlaybackEngine.VLC && vlcHeaderOptions(media.streamRequestHeaders) == null)) PlaybackEngine.MEDIA3 else requestedEngine
    val displayedPlayerChoice = if (media.streamDrm != null && selectedPlayerChoice.pickerChoice() !in PROTECTED_PLAYER_ENGINES)
        effectiveEngine else selectedPlayerChoice
    if (effectiveEngine == PlaybackEngine.VLC) {
        VlcPlayerScreen(
            media = media,
            cricketScores = cricketScores,
            playbackDomain = playbackDomain,
            onTerminalPlaybackFailure = onTerminalPlaybackFailure,
            introSettings = introSettings,
            initialResumePosition = engineSwitchResumePosition,
            onBack = onBack,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onPlayItem = onPlayItem,
            onPlayQueueItem = onPlayQueueItem,
            pinnedQueueIds = pinnedQueueIds,
            queueFavoriteIds = queueFavoriteIds,
            onToggleQueueFavorite = onToggleQueueFavorite,
            onToggleQueuePin = onToggleQueuePin,
            onRefreshQueueProgramme = onRefreshQueueProgramme,
            onVisibleQueueChannels = onVisibleQueueChannels,
            liveQueueCategoryTitle = liveQueueCategoryTitle,
            liveQueueXtream = liveQueueXtream,
            onProgress = onProgress,
            onPlaybackIntentChanged = onPlaybackIntentChanged,
            onChooseAlternateSource = onChooseAlternateSource,
            alternateSourceChooserOpen = alternateSourceChooserOpen,
            onVideoFrameRendered = onVideoFrameRendered,
            onDownload = onDownload,
            offlineDownloadPresent = offlineDownloadPresent,
            offlineDownloadInProgress = offlineDownloadInProgress,
            offlineDownloadProgress = offlineDownloadProgress,
            offlineDownloadProgressText = offlineDownloadProgressText,
            initialSubtitleDelayMs = subtitleDelayMs,
            initialExternalSubtitleFile = externalSubtitleFile,
            onRetry = onRetry,
            onManualRetry = onManualRetry.takeIf { playbackDomain == PlaybackDomain.SPORTS },
            onPlaybackAuthorizationFailure = onPlaybackAuthorizationFailure,
            onReauthenticate = onReauthenticate,
            reauthenticating = reauthenticating,
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
            moreOptionsOpen = moreOptionsOpen,
            onMoreOptionsOpenChanged = { moreOptionsOpen = it },
            onSelectPlayer = { selectedEngine, position ->
                restorePlayerSwitchFocus = true
                selectedPlayerChoice = selectedEngine
                engineSwitchResumePosition = playbackRestartPosition(media.catalogType, position)
                onPlaybackEngineChanged(selectedEngine)
                sessionEngineOverride = selectedEngine.resolvePlayerEngine(context, playbackScope, playbackDomain)
            },
            configuredEngine = selectedPlayerChoice,
            focusPlayerSwitchOnEnter = restorePlayerSwitchFocus,
            onPlayerSwitchFocusRestored = { restorePlayerSwitchFocus = false }
        )
        return
    }
    val coroutineScope = rememberCoroutineScope()
    val livePlaybackClock = remember(media.progressKey) { LivePlaybackClock() }
    var livePlaybackElapsedMillis by remember(media.progressKey) { mutableLongStateOf(0L) }
    val currentOnPlayPrevious by rememberUpdatedState(onPlayPrevious)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentNextEpisode by rememberUpdatedState(media.nextEpisode)
    val activity = remember(context) { context.findActivity() }
    var videoScale by remember(media.progressKey) { mutableFloatStateOf(1f) }
    var videoOffset by remember(media.progressKey) { mutableStateOf(Offset.Zero) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    // Fullscreen belongs to the player session, not to an individual queue item.
    // Preserve it while changing channels/episodes/titles.
    var focusMode by remember { mutableStateOf(startFullscreen) }
    LaunchedEffect(fullscreenOverride) {
        fullscreenOverride?.let { focusMode = it }
    }
    var gestureFeedback by remember(media.progressKey) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    val savedResizeMode = rememberVideoResizeMode()
    var resizePreview by remember(media.progressKey) { mutableStateOf<VideoResizeMode?>(null) }
    val resizeMode = resizePreview ?: savedResizeMode
    val (appearanceProfiles, persistedAppearanceProfile) =
        rememberVideoAppearanceProfiles(useSchedule = true)
    var appearancePreview by remember { mutableStateOf<VideoAppearanceProfile?>(null) }
    val activeAppearanceProfile =
        appearancePreview ?: persistedAppearanceProfile
    var modeFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestedEngine, effectiveEngine, media.playbackAttemptId) {

        if (requestedEngine == PlaybackEngine.VLC && effectiveEngine == PlaybackEngine.MEDIA3 &&

            vlcHeaderOptions(media.streamRequestHeaders) == null)

            modeFeedback = "Using ExoPlayer for this stream’s required headers"
        else if (media.streamDrm != null && requestedEngine != effectiveEngine)
            modeFeedback = "Using ExoPlayer for this protected stream"

    }
    var previousLiveRecordingActive by remember { mutableStateOf(liveRecording.active) }
    LaunchedEffect(liveRecording.active, liveRecording.error, media.url) {
        modeFeedback = when {
            liveRecording.error != null -> "Recording failed · ${liveRecording.error}"
            liveRecording.active && liveRecording.sourceUrl == media.url -> "Recording started · ${liveRecording.title}"
            previousLiveRecordingActive && !liveRecording.active -> "Recording stopped and saved"
            else -> modeFeedback
        }
        previousLiveRecordingActive = liveRecording.active
    }
    var queueVisible by remember(media.progressKey) { mutableStateOf(false) }
    var programmeGuideOpen by remember(media.progressKey) { mutableStateOf(false) }
    var queueRevealProgress by remember(media.progressKey) { mutableFloatStateOf(0f) }
    var queueRevealDragging by remember(media.progressKey) { mutableStateOf(false) }
    var pictureEditorVisible by remember { mutableStateOf(false) }
    var pictureModePickerVisible by remember { mutableStateOf(false) }
    var playerPickerVisible by remember(media.progressKey) { mutableStateOf(false) }
    var playerPickerFromError by remember(media.progressKey) { mutableStateOf(false) }
    var resizePickerVisible by remember(media.progressKey) { mutableStateOf(false) }
    var returnControlFocus by remember(media.progressKey) { mutableStateOf<FocusRequester?>(null) }
    // The parent owns this picker, while the player retains its originating
    // control. A replacement playback attempt cannot inherit a pending return.
    var sourceChooserWasOpen by remember(media.progressKey, media.url, media.playbackAttemptId) { mutableStateOf(false) }
    val playerOptionsOpen = playerPickerVisible || pictureModePickerVisible || resizePickerVisible || pictureEditorVisible || alternateSourceChooserOpen || introSettings.editorOpen || cricketScores.pickerOpen || cricketScores.moving
    val currentPlayerOptionsOpen by rememberUpdatedState(playerOptionsOpen)
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
    var controlsVisible by remember(media.progressKey) {
        mutableStateOf(
            moreOptionsOpen ||
                (!embeddedMode && !startFullscreen)
        )
    }
    LaunchedEffect(moreOptionsOpen, media.progressKey) {
        if (moreOptionsOpen) {
            controlsVisible = true
        }
    }
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
    var playbackRequested by remember(media.progressKey, media.playbackAttemptId) { mutableStateOf(media.playWhenReady) }
    var playbackState by remember(media.progressKey) { mutableIntStateOf(Player.STATE_IDLE) }
    var playbackHasBeenReady by remember(media.progressKey) { mutableStateOf(false) }
    var bufferingIndicatorVisible by remember(media.progressKey) { mutableStateOf(true) }
    var position by remember(media.progressKey) { mutableLongStateOf(0L) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var videoDetails by remember(media.progressKey) { mutableStateOf("") }
    var playbackError by remember(media.progressKey, media.playbackAttemptId, media.url) { mutableStateOf<String?>(null) }
    var subtitleDialogOpen by remember(media.progressKey) { mutableStateOf(false) }
    var subtitleTracks by remember(media.progressKey) { mutableStateOf<List<Media3SubtitleTrack>>(emptyList()) }
    var audioDialogOpen by remember(media.progressKey) { mutableStateOf(false) }
    var audioTracks by remember(media.progressKey) { mutableStateOf<List<Media3AudioTrack>>(emptyList()) }

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

    var startupTimedOut by remember(media.progressKey, media.playbackAttemptId, media.url) { mutableStateOf(false) }
    var playerViewRef by remember(media.progressKey) { mutableStateOf<PlayerView?>(null) }
    var inPictureInPicture by remember { mutableStateOf(false) }
    val playNextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val watchLiveFocusRequester = remember(media.progressKey) { FocusRequester() }
    val backFocusRequester = remember(media.progressKey) { FocusRequester() }
    val downloadFocusRequester = remember(media.progressKey) { FocusRequester() }
    val recordingPauseFocusRequester = remember(media.progressKey) { FocusRequester() }
    val subtitleFocusRequester = remember(media.progressKey) { FocusRequester() }
    val introFocusRequester = remember(media.progressKey) { FocusRequester() }
    val skipIntroFocusRequester = remember(media.progressKey) { FocusRequester() }
    val audioFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pipFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchFocusRequester = remember(media.progressKey) { FocusRequester() }
    val sourceFocusRequester = remember(media.progressKey) { FocusRequester() }
    val refreshFocusRequester = remember(media.progressKey) { FocusRequester() }
    val resizeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeFocusRequester = remember(media.progressKey) { FocusRequester() }
    val programmeGuideFocusRequester = remember(media.progressKey) { FocusRequester() }
    val directionalInput = playerUsesDirectionalInput()
    val pictureSettingsFocusRequester = remember(media.progressKey) { FocusRequester() }
    val controlsTimeoutFocusRequester = remember(media.progressKey) { FocusRequester() }
    val moreFocusRequester = remember(media.progressKey) { FocusRequester() }
    val cricketScoreFocusRequester = remember(media.progressKey) { FocusRequester() }
    val previousFocusRequester = remember(media.progressKey) { FocusRequester() }
    val rewindFocusRequester = remember(media.progressKey) { FocusRequester() }
    val playPauseFocusRequester = remember(media.progressKey) { FocusRequester() }
    val forwardFocusRequester = remember(media.progressKey) { FocusRequester() }
    val nextFocusRequester = remember(media.progressKey) { FocusRequester() }
    val progressFocusRequester = remember(media.progressKey) { FocusRequester() }
    val videoSurfaceFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorBackFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorRetryFocusRequester = remember(media.progressKey) { FocusRequester() }
    val errorPlayerFocusRequester = remember(media.progressKey) { FocusRequester() }

    LaunchedEffect(alternateSourceChooserOpen) {
        if (alternateSourceChooserOpen) {
            sourceChooserWasOpen = true
        } else if (sourceChooserWasOpen) {
            sourceChooserWasOpen = false
            returnControlFocus = if (playbackError != null || startupTimedOut) errorRetryFocusRequester else sourceFocusRequester
        }
    }

    LaunchedEffect(returnControlFocus) {
        val target = returnControlFocus ?: return@LaunchedEffect
        controlsVisible = true
        if (compactMobileControls) extraControlsOpen = true
        try {
            // Let the dialog window detach before handing focus back to its origin.
            delay(80L)
            repeat(4) {
                withFrameNanos { }
                if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        } finally { if (returnControlFocus === target) returnControlFocus = null }
    }


    LaunchedEffect(restorePlayerSwitchFocus, effectiveEngine) {
        if (restorePlayerSwitchFocus) {
            controlsVisible = true
            if (compactMobileControls) extraControlsOpen = true
            delay(120L)
            runCatching { playerSwitchFocusRequester.requestFocus() }
            restorePlayerSwitchFocus = false
        }
    }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pipActivity = activity as? PlayerPictureInPictureHost
    val pipAvailable = remember(context) {
        pipActivity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    val isTvDevice = rememberPlayerTvDevice()
    // Fire TV has no Google Cast module. Local playback must not depend on it.
    val castPlayer = remember(context, isTvDevice, media.nativeSabr, media.streamDrm) {
        if (isTvDevice || media.nativeSabr != null || media.streamDrm != null) null
        else runCatching { CastPlayer(CastContext.getSharedInstance(context)) }.getOrNull()
    }
    val castMediaItem = remember(media.url, media.media.title, media.playbackFormat) {
        MediaItem.Builder()
            .setUri(media.url)
            .setMimeType(
                if (media.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                    media.playbackFormat.contains("HLS", ignoreCase = true)
                ) MimeTypes.APPLICATION_M3U8 else null
            )
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.media.title).build())
            .build()
    }

    var castSessionActive by remember(castPlayer) {
        mutableStateOf(castPlayer?.isCastSessionAvailable == true)
    }
    var pendingCastStartPosition by remember(media.progressKey) {
        mutableStateOf<Long?>(null)
    }
    var pendingLocalResumePosition by remember(media.progressKey) {
        mutableStateOf<Long?>(null)
    }

    val detectedManifestMime = remember(media.url, media.playbackAttemptId) {
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    }
    val createLocalPlayer: (Long?) -> Player = { resumePosition ->
        if (effectiveEngine == PlaybackEngine.MPV && mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) {
            MpvPlayerAdapter(context, media, playbackRestartPosition(media.catalogType,
                resumePosition ?: engineSwitchResumePosition), playbackOptionsOverride =
                if (playbackDomain == PlaybackDomain.SPORTS) com.nikhil.niktv.sports.SportsPlaybackPolicy.mpvOptions() else null).apply {
                prepare()
                playWhenReady = media.playWhenReady && (!isTvDevice || playbackLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }
        } else {
        val renderersFactory = if (playbackDomain == PlaybackDomain.SPORTS)
            com.nikhil.niktv.sports.SportsPlaybackPolicy.renderers(context, effectiveEngine, media.decoderSessionId)
        else (if (effectiveEngine == PlaybackEngine.FLOW)
            io.github.aedev.flow.player.factory.PlayerFactory().createRenderersFactory(context)
            else DefaultRenderersFactory(context)).apply {
            if (effectiveEngine.isMedia3Player()) {
                setEnableDecoderFallback(true)
                setMediaCodecSelector(FailedDecoderRegistry.selector(context, playbackScope, media.decoderSessionId))
            }
        }
        val builder = ExoPlayer.Builder(context, renderersFactory)

            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()

                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)

            .setHandleAudioBecomingNoisy(true)
        if (playbackDomain == PlaybackDomain.SPORTS) {
            com.nikhil.niktv.sports.SportsPlaybackPolicy.configure(context, builder, effectiveEngine)
        } else if (effectiveEngine == PlaybackEngine.FLOW) {
            configureFlowInspiredPlayer(context, builder, media.catalogType == CatalogType.LIVE_TV)
        } else if (media.catalogType == CatalogType.LIVE_TV && !media.offlinePlayback) {
            // Startup and recovery thresholds are independent of the buffer we
            // accumulate while playing. Keep memory bounded on TV devices.
            builder.setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 750, 3_000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .build())
        }
        if (media.offlinePlayback) {
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(OfflineMediaDownloads.cacheDataSourceFactory(context))
            )
        }
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(media.streamRequestHeaders)
        if (playbackDomain == PlaybackDomain.SPORTS) http.setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val signedStreamSources = DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory {
            ManifestMimeDataSource(upstream.createDataSource(), android.net.Uri.parse(media.url), detectedManifestMime::set)
        })
        if (!media.offlinePlayback) builder.setMediaSourceFactory(signedStreamSources.apply {
            withStreamDrm(media.streamDrm)
            if (media.catalogType == CatalogType.LIVE_TV)
                setLoadErrorHandlingPolicy(LiveSegmentLoadErrorPolicy { playbackHasBeenReady })
        })
        builder.build().apply {
            // HLS supplies its own hold-back/segment timing. A fixed six-second
            // offset puts long-segment streams too close to unfinished media.
            val mediaItemBuilder = MediaItem.Builder().setUri(media.url)
                .withStreamDrm(media.streamDrm, media.streamRequestHeaders)

                .setMimeType(playbackMimeType(media.playbackFormat, media.url))
            if (media.nativeSabr != null) {
                val transport = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(media.streamRequestHeaders)
                    .setKeepPostFor302Redirects(true)
                    .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
                setMediaSource(com.newtube.sabr.SabrMediaSource(media.nativeSabr, transport,
                    androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context)))
            } else if (media.separateAudioUrl != null) {
                val sources = signedStreamSources
                setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(
                    sources.createMediaSource(mediaItemBuilder.build()),
                    sources.createMediaSource(MediaItem.fromUri(media.separateAudioUrl))
                ))
            } else {
                setMediaItem(mediaItemBuilder.build())
            }
            val startPosition = playbackRestartPosition(media.catalogType,
                resumePosition ?: engineSwitchResumePosition)
            if (startPosition > 0L) seekTo(startPosition)
            prepare()
            playWhenReady = media.playWhenReady && (!isTvDevice || playbackLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            repeatMode = Player.REPEAT_MODE_OFF
        }
        }
    }

    /*
     * PLAYER_ACTIVE_OWNERSHIP_V1
     *
     * The local player is a media-scoped lease. It exists only while local
     * playback owns the session, so a channel/media change creates exactly one
     * replacement ExoPlayer and Cast playback does not create an unused local
     * player behind the scenes.
     */
    val localLease = remember(
        media.progressKey,
        playbackDomain,
        effectiveEngine,
        media.url,
        media.playbackAttemptId,
        castSessionActive
    ) {
        if (castSessionActive) {
            null
        } else {
            PlayerLease(
                player = createLocalPlayer(pendingLocalResumePosition),
                releasePlayer = { it.release() }
            )
        }
    }
    val localPlayer = localLease?.player
    // Each replacement player starts with the original media item. Its one-shot
    // MIME recovery must not inherit exhaustion from the engine it replaces.
    var manifestFallbackUsed by remember(localPlayer) { mutableStateOf(false) }
    DisposableEffect(localPlayer, media.playbackAttemptId) {
        val exoPlayer = localPlayer as? ExoPlayer
        if (!com.nikhil.niktv.BuildConfig.DEBUG || exoPlayer == null || media.catalogType != CatalogType.LIVE_TV || media.offlinePlayback) {
            return@DisposableEffect onDispose { }
        }
        val diagnostics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
            ) {
                val mediaMillis = if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET &&
                    mediaLoadData.mediaEndTimeMs != C.TIME_UNSET)
                    mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs else 0L
                android.util.Log.i("NikTvStreamLoad", "type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded} elapsedMs=${loadEventInfo.loadDurationMs} mediaMs=$mediaMillis")
            }
            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {

                val httpStatus = error.causeSequence().filterIsInstance<NativePlaybackFailure>().firstOrNull()?.httpStatus ?: error.causeSequence()
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                android.util.Log.w("NikTvStreamLoad", "type=${mediaLoadData.dataType} elapsedMs=${loadEventInfo.loadDurationMs} error=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName} http=$httpStatus retryStopped=$wasCanceled")
            }
        }
        exoPlayer.addAnalyticsListener(diagnostics)
        onDispose { exoPlayer.removeAnalyticsListener(diagnostics) }
    }
    val audioFailureFallbackEnabled = remember(context) { AudioFailurePreferences.enabled(context) }
    var audioDisabledAfterFailure by remember(localPlayer) { mutableStateOf(false) }

    fun retryAudioOutput() {
        val activePlayer = localPlayer ?: return
        if (!audioDisabledAfterFailure || castSessionActive) return
        audioDisabledAfterFailure = false
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
            .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        if (activePlayer.playerError != null) activePlayer.prepare()
    }

    // Event driven; no periodic audio probing or work on the video rendering path.
    DisposableEffect(localPlayer, audioFailureFallbackEnabled) {
        val knownOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.id }.toMutableSet()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val newOutput = addedDevices.filter { it.isSink }
                    .map { knownOutputs.add(it.id) }.any { it }
                if (newOutput) retryAudioOutput()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                removedDevices.forEach { knownOutputs.remove(it.id) }
            }
        }
        if (audioFailureFallbackEnabled && localPlayer != null) {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(localLease) {
        if (localLease != null) {
            pendingLocalResumePosition = null
        }
    }

    val currentLocalPlayer by rememberUpdatedState(localPlayer)
    LaunchedEffect(localPlayer, subtitleDelayMs, subtitleAppearance) {
        if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) {
            (localPlayer as? MpvPlayerAdapter)?.let {
                it.setSubtitleDelay(subtitleDelayMs)
                it.setSubtitleAppearance(subtitleAppearance)
            }
        }
    }
    val currentCastSessionActive by rememberUpdatedState(castSessionActive)

    DisposableEffect(castPlayer, media.progressKey) {
        if (castPlayer == null) return@DisposableEffect onDispose {}
        val listener = object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                if (currentCastSessionActive) return
                pendingCastStartPosition =
                    currentLocalPlayer
                        ?.currentPosition
                        ?.takeUnless { it == C.TIME_UNSET }
                        ?.coerceAtLeast(0L)
                        ?: 0L
                castSessionActive = true
            }

            override fun onCastSessionUnavailable() {
                if (!currentCastSessionActive) return
                pendingLocalResumePosition =
                    castPlayer.currentPosition
                        .takeUnless { it == C.TIME_UNSET }
                        ?.coerceAtLeast(0L)
                        ?: 0L
                castSessionActive = false
            }
        }

        castPlayer.setSessionAvailabilityListener(listener)

        when {
            castPlayer.isCastSessionAvailable && !currentCastSessionActive ->
                listener.onCastSessionAvailable()
            !castPlayer.isCastSessionAvailable && currentCastSessionActive ->
                listener.onCastSessionUnavailable()
        }

        onDispose {
            castPlayer.setSessionAvailabilityListener(null)
        }
    }

    /*
     * Recomposition alone never restarts Cast playback. Replace the Cast item
     * only for a fresh connection/position transfer or when the current media
     * URL changes.
     */
    var preparedCastAttempt by remember(castPlayer) { mutableStateOf<String?>(null) }
    LaunchedEffect(castSessionActive, castMediaItem, media.playbackAttemptId) {
        if (!castSessionActive || castPlayer == null) return@LaunchedEffect

        val requestedPosition = pendingCastStartPosition
        val currentUri =
            castPlayer.currentMediaItem
                ?.localConfiguration
                ?.uri
        val requestedUri =
            castMediaItem.localConfiguration
                ?.uri

        if (requestedPosition != null || currentUri != requestedUri ||
            preparedCastAttempt != media.playbackAttemptId) {
            castPlayer.setMediaItem(
                castMediaItem,
                requestedPosition ?: media.resumePositionMillis.coerceAtLeast(0L)
            )
            castPlayer.prepare()
            castPlayer.play()
            preparedCastAttempt = media.playbackAttemptId
        }

        pendingCastStartPosition = null
    }

    val player: Player =
        selectActivePlayer(
            castSessionActive = castSessionActive,
            castPlayer = castPlayer,
            localPlayer = localPlayer
        )
    var liveWindowSeekingAvailable by remember(player) { mutableStateOf(false) }
    var bufferedPosition by remember(player) { mutableLongStateOf(0L) }
    var currentWindowIsLive by remember(player) { mutableStateOf(false) }
    fun sampleLiveWindow() {
        liveWindowSeekingAvailable = liveWindowCanSeek(media.allowLiveWindowSeeking && player is ExoPlayer,
            player.isCurrentMediaItemSeekable, player.duration, player.isCurrentMediaItemLive)
        currentWindowIsLive = player.isCurrentMediaItemLive
        bufferedPosition = player.bufferedPosition.coerceAtLeast(0L)
    }
    val introSecondsRemaining = introSettings.timings.effective?.skipSecondsRemaining(position, duration)
        ?.takeIf { player.isCurrentMediaItemSeekable }
    val introAvailable = introSecondsRemaining != null
    val liveRecovery = remember(media.progressKey) { LiveStreamRecovery() }
    val liveEdgeRecovery = remember(media.progressKey) { LiveStreamRecovery() }
    var liveRefreshPending by remember(player) { mutableStateOf(false) }
    val currentStreamRetry by rememberUpdatedState(onRetry)
    LaunchedEffect(player, liveRefreshPending) {
        if (liveRefreshPending) {
            delay(10_000L)
            liveRefreshPending = false
            if (player.playbackState == Player.STATE_IDLE) {
                playbackError = "The live stream could not be refreshed. Retry or choose another player."
                controlsVisible = true
            }
        }
    }
    DisposableEffect(player, media.progressKey) {

        val retryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

        val completion = EpisodeCompletionGate()
        /*
         * A replacement player can already be READY before this listener is
         * installed. Adopt its observable state first so Compose never waits
         * for an event that has already happened.
         */
        playbackState = player.playbackState
        isPlaying = player.isPlaying
        playbackRequested = player.playWhenReady
        position =
            player.currentPosition
                .takeUnless { it == C.TIME_UNSET }
                ?.coerceAtLeast(0L)
                ?: 0L
        duration =
            player.duration
                .takeIf { it != C.TIME_UNSET && it > 0L }
                ?: 0L
        sampleLiveWindow()
        if (playbackState == Player.STATE_READY) {
            playbackError = null
            startupTimedOut = false
            playbackHasBeenReady = true
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { onVideoFrameRendered() }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) {
                    playbackError = null
                    startupTimedOut = false
                    playbackHasBeenReady = true
                }
                val bufferedMillis = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                android.util.Log.i(
                    "NikTvPlayback",
                    "engine=$effectiveEngine state=$state bufferedMs=$bufferedMillis positionMs=${player.currentPosition} live=${media.catalogType == CatalogType.LIVE_TV}"
                )
                if (
                    media.catalogType == CatalogType.SERIES && !advancing &&
                    completion.claim(state == Player.STATE_ENDED, playbackRequested, currentNextEpisode != null, autoPlayCancelled)
                ) {
                    advancing = true
                    subtitleDialogOpen = false
                    currentOnPlayNext()
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
                audioTracks = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { group ->
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            val language = format.language
                                ?.let { Locale.forLanguageTag(it).displayName }
                                ?.takeIf(String::isNotBlank)
                            Media3AudioTrack(
                                id = "${group.mediaTrackGroup.id}:$index",
                                label = format.label?.takeIf(String::isNotBlank)
                                    ?: language ?: "Audio ${index + 1}",
                                group = group,
                                trackIndex = index
                            )
                        }
                    }
            }
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playbackRequested = value
                onPlaybackIntentChanged(value)
            }
            override fun onPlayerError(error: PlaybackException) {
                val inferredMime = detectedManifestMime.get()
                if (player === localPlayer && effectiveEngine.isMedia3Player() &&
                    media.nativeSabr == null && media.separateAudioUrl == null && !manifestFallbackUsed &&
                    inferredMime != null && playbackMimeType(media.playbackFormat, media.url) == null &&
                    error.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                    // Opaque endpoints may reveal HLS/DASH only in their response headers.
                    // Retry preparation once with that observed MIME, preserving headers and pause intent.
                    manifestFallbackUsed = true
                    playbackError = null
                    val position = playbackRestartPosition(media.catalogType, player.currentPosition)
                    val requested = playbackRequested
                    player.setMediaItem(MediaItem.Builder().setUri(media.url).setMimeType(inferredMime)
                        .withStreamDrm(media.streamDrm, media.streamRequestHeaders).build())
                    if (position > 0L) player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = requested
                    return
                }

                val httpStatus = error.causeSequence().filterIsInstance<NativePlaybackFailure>()
                    .firstOrNull()?.httpStatus ?: error.causeSequence()
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                if (player === localPlayer && !media.offlinePlayback && playbackRequested &&
                    !liveRefreshPending && playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED) &&
                    recoverableLiveSourceFailure(media.catalogType == CatalogType.LIVE_TV,
                        playbackHasBeenReady, error.errorCode, httpStatus,
                        playlistStalled = error.causeSequence().any {
                            it is androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.PlaylistStuckException ||
                                it is androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.PlaylistResetException
                        })) {
                    val missingLiveSegment = player.currentManifest is androidx.media3.exoplayer.hls.HlsManifest &&
                        httpStatus in setOf(404, 410)
                    if ((missingLiveSegment || error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) &&
                        liveEdgeRecovery.claimRecovery()) {
                        playbackError = null
                        startupTimedOut = false
                        android.util.Log.w("NikTvPlayback", "Recovering live playlist at default position; code=${error.errorCode} http=$httpStatus")
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.play()
                        return
                    }
                    if (liveRecovery.claimRecovery()) {
                        liveRefreshPending = true
                        playbackError = null
                        startupTimedOut = false
                        android.util.Log.w("NikTvPlayback", "Refreshing interrupted live stream; code=${error.errorCode} http=$httpStatus")
                        retryScope.launch {
                            if (currentLocalPlayer === player && playbackRequested) currentStreamRetry(0L)
                        }
                        return
                    }
                }
                if (player === localPlayer && shouldRecoverWithoutAudio(
                        audioFailureFallbackEnabled,
                        audioDisabledAfterFailure,
                        error.errorCode
                    )
                ) {
                    audioDisabledAfterFailure = true
                    playbackError = null
                    startupTimedOut = false
                    decoderRecoveryInProgress = false
                    // Reuse the same player, position, video decoder selection and play intent.
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    android.util.Log.w("NikTvAudio", "Audio output failed; continuing video only")
                    player.prepare()
                    return
                }
                val failedDecoder =
                    if (playbackDomain == PlaybackDomain.SPORTS)
                        com.nikhil.niktv.sports.SportsDecoderPolicy.record(error, media.decoderSessionId)
                    else
                    FailedDecoderRegistry.record(
                        context,
                        error,
                        playbackScope,
                        media.decoderSessionId
                    )

                val authorizationFailure = httpStatus in setOf(401, 403)

                when {
                    authorizationFailure &&
                        media.authorizationRetryCount == 0 -> {

                        playbackError =
                            "Stream authorization expired. Requesting a fresh playback link…"
                        controlsVisible = true

                        retryScope.launch {
                            delay(350L)

                            if (!playbackRequested) return@launch
                            onPlaybackAuthorizationFailure(
                                player.currentPosition
                                    .coerceAtLeast(0L)
                            )
                        }
                    }

                    failedDecoder != null && (if (playbackDomain == PlaybackDomain.SPORTS)
                        com.nikhil.niktv.sports.SportsDecoderPolicy.canRecover(media.decoderRecoveryCount)
                        else allowDecoderRecovery(media.decoderRecoveryCount)) -> {
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

                        retryScope.launch {
                            delay(250L)

                            if (!playbackRequested) {
                                decoderRecoveryInProgress = false
                                playbackError = "Decoder recovery paused. Retry or switch player."
                                return@launch
                            }

                            onRetryAlternateDecoder(
                                recoveryPosition
                            )
                        }
                    }

                    effectiveEngine.isMedia3Player() &&
                        media.nativeSabr == null && media.separateAudioUrl == null && media.streamDrm == null &&
                        automaticLivePlayerFallback(selectedPlayerChoice, media.catalogType == CatalogType.LIVE_TV,
                            playbackRequested, media.offlinePlayback, vlcHeaderOptions(media.streamRequestHeaders) != null,
                            error.errorCode) -> {
                        // One compatible-engine handoff for Auto. Retain the chosen preference,
                        // resolved URL, headers and queue; never retry an authorization failure here.
                        playbackError = null
                        decoderRecoveryInProgress = false
                        engineSwitchResumePosition = 0L
                        sessionEngineOverride = PlaybackEngine.VLC
                        android.util.Log.w("NikTvPlayback", "Auto live decoder fallback to VLC; code=${error.errorCode}")
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

        onDispose { retryScope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); player.removeListener(listener) }
    }
    LaunchedEffect(playbackState, playbackHasBeenReady, decoderRecoveryInProgress) {
        bufferingIndicatorVisible = when {
            decoderRecoveryInProgress -> true
            playbackState == Player.STATE_IDLE -> true
            playbackState != Player.STATE_BUFFERING -> false
            !playbackHasBeenReady -> true
            else -> {
                // Do not flash a full-screen reconnect notice for sub-second
                // jitter. Longer interruptions are still shown to the viewer.
                delay(750L)
                playbackState == Player.STATE_BUFFERING
            }
        }
    }
    DisposableEffect(player, media.progressKey, localLease) {
        onDispose {
            if (media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES)) {
                onProgress(
                    media.progressKey,
                    player.currentPosition,
                    player.duration
                )
            }
            if (player === localLease?.player) {
                localLease.releaseOnce()
            }
        }
    }
    PauseTvPlaybackInBackground {
        playbackRequested = false
        player.pause()
        controlsVisible = true
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
            }
            delay(500)
        }
    }
    val showAutoPlayPrompt =
        remainingSeconds != null && media.nextEpisode != null && !autoPlayCancelled && !introAvailable
    LaunchedEffect(showAutoPlayPrompt) {
        if (showAutoPlayPrompt) {
            delay(120L)
            runCatching { playNextFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(player, media.progressKey) {
        var lastProgressSave = android.os.SystemClock.elapsedRealtime()
        var lastHealthSample = lastProgressSave
        val recoveryWindow = androidx.media3.common.Timeline.Window()
        while (true) {
            delay(1_000)
            if (media.catalogType == CatalogType.LIVE_TV) {
                livePlaybackElapsedMillis = livePlaybackClock.sample(
                    android.os.SystemClock.elapsedRealtime(), player.isPlaying
                )
                LiveTvRecorder.reportPlaybackElapsed(media.url, livePlaybackElapsedMillis)
                if (player === localPlayer && !media.offlinePlayback) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val active = playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED)
                    val eligible = active && playbackRequested && playbackHasBeenReady &&
                        playbackError == null && !liveRefreshPending &&
                        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    // The UI elapsed clock advances when an engine claims it is playing,
                    // even if the native media clock is frozen. Recovery needs real progress.
                    val timeline = player.currentTimeline
                    val windowOffset = if (!timeline.isEmpty && player.currentMediaItemIndex >= 0)
                        timeline.getWindow(player.currentMediaItemIndex, recoveryWindow).positionInFirstPeriodMs else 0L
                    val streamPosition = liveRecoveryPosition(player.currentPosition, windowOffset)
                    liveEdgeRecovery.observePlayback(now, streamPosition, eligible)
                    if (liveRecovery.shouldRecover(now, streamPosition, eligible,
                            player.playbackState == Player.STATE_ENDED)) {
                        liveRefreshPending = true
                        android.util.Log.w("NikTvPlayback", "Refreshing stopped/stalled ${effectiveEngine.playerChoiceLabel()} live stream")
                        currentStreamRetry(0L)
                    } else if (liveRecovery.unrecoverable(now, eligible, player.playbackState == Player.STATE_ENDED)) {
                        playbackError = "The live stream stopped. Retry to refresh the stream, or choose another player."
                        controlsVisible = true
                    }
                }
            }
            if (com.nikhil.niktv.BuildConfig.DEBUG && media.catalogType == CatalogType.LIVE_TV && playbackRequested &&
                android.os.SystemClock.elapsedRealtime() - lastHealthSample >= 15_000L) {
                val exo = player as? ExoPlayer
                val counters = exo?.videoDecoderCounters
                counters?.ensureUpdated()
                android.util.Log.i("NikTvPlaybackHealth", "engine=$effectiveEngine positionMs=${player.currentPosition} " +
                    "bufferedMs=${player.totalBufferedDuration} playing=${player.isPlaying} " +
                    "suppression=${player.playbackSuppressionReason} frames=${counters?.renderedOutputBufferCount} " +
                    "dropped=${counters?.droppedBufferCount}")
                lastHealthSample = android.os.SystemClock.elapsedRealtime()
            }
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            sampleLiveWindow()
            if (onVideoFramesRendered != null) {
                (player as? androidx.media3.exoplayer.ExoPlayer)?.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    onVideoFramesRendered(counters.renderedOutputBufferCount)
                }
            }
            videoDetails = (player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat?.let { format ->
                buildList {
                    if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
                    format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)?.let(::add)
                    if (format.bitrate > 0) add("%.1f Mbps".format(Locale.ROOT, format.bitrate / 1_000_000f))
                }.joinToString(" · ")
            }.orEmpty()
            if (media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES) &&
                android.os.SystemClock.elapsedRealtime() - lastProgressSave >= 5_000L) {
                onProgress(media.progressKey, player.currentPosition, player.duration)
                lastProgressSave = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    LaunchedEffect(player, media.url) {
        val deadline = PlaybackStartupDeadline()
        while (!playbackHasBeenReady && playbackError == null) {
            if (deadline.expired(android.os.SystemClock.elapsedRealtime(),
                    playbackRequested && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                    playbackState == Player.STATE_READY)) {
                startupTimedOut = true
                controlsVisible = true
                if (onTerminalPlaybackFailure != null &&
                    playbackLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    // Let the Sports coordinator resolve a replacement while
                    // retaining this session. A real user pause still cancels
                    // the pending fallback through the existing intent callback.
                    onTerminalPlaybackFailure()
                } else player.pause()
                break
            }
            delay(250L)
        }
    }
    // Sports source fallback consumes the existing player's final error. It
    // adds no timer/retry loop, and ordinary IPTV has no callback or effect.
    if (onTerminalPlaybackFailure != null) {
        val lifecycleState by playbackLifecycleOwner.lifecycle.currentStateFlow.collectAsState()
        var reported by remember(player, media.playbackAttemptId) { mutableStateOf(false) }
        LaunchedEffect(player, media.playbackAttemptId, playbackError, playbackRequested,
            decoderRecoveryInProgress, lifecycleState) {
            val authorizationPending = media.authorizationRetryCount == 0 &&
                playbackError?.startsWith("Stream authorization expired.") == true
            if (!reported && playbackError != null && playbackRequested &&
                !authorizationPending && !decoderRecoveryInProgress &&
                lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                reported = true
                onTerminalPlaybackFailure()
            }
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
        playerOptionsOpen,
        programmeGuideOpen,
        moreOptionsOpen
    ) {
        val canAutoHide =
            controlsVisible &&
                isPlaying &&
                playbackError == null &&
                !startupTimedOut
                && !queueVisible
                && !pictureEditorVisible
                && !playerOptionsOpen
                && !programmeGuideOpen
                && !moreOptionsOpen

        if (
            canAutoHide &&
            controlsTimeoutSeconds != PLAYER_CONTROLS_TIMEOUT_INFINITE
        ) {
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
            if (embeddedMode) {
                suppressNextEmbeddedPlayerFocusHandoff = true
                playerViewRef?.requestFocus()
            } else {
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
            !playerOptionsOpen && returnControlFocus == null &&
            !restorePlayerSwitchFocus &&
            playbackError == null &&
            !startupTimedOut
        ) {
            delay(80L)

            if (!currentPlayerOptionsOpen && returnControlFocus == null) {
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        }
    }
    LaunchedEffect(playbackError, startupTimedOut, media.progressKey, controlsVisible, queueVisible) {
        if ((playbackError != null || startupTimedOut) && controlsVisible && !queueVisible && !currentPlayerOptionsOpen) {
            delay(120L)
            if (!currentPlayerOptionsOpen) runCatching { errorRetryFocusRequester.requestFocus() }
        }
    }
    fun showControlsAndFocusPlayPause() {
        controlsVisible = true
        coroutineScope.launch {
            delay(80L)
            runCatching { (if (playbackError != null || startupTimedOut) errorRetryFocusRequester else playPauseFocusRequester).requestFocus() }
        }
    }

    fun showEpisodeQueue() {
        if (!focusMode || !hasPlaybackQueue) return
        controlsVisible = false
        controlsFocused = false
        queueRevealDragging = false
        queueRevealProgress = 1f
        queueVisible = true
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
        playerOptionsOpen,
        embeddedMode,
        inPictureInPicture,
        introAvailable,
        media.progressKey
    ) {
        if (
            !embeddedMode &&
            !controlsVisible &&
            !queueVisible &&
            !pictureEditorVisible &&
            !playerOptionsOpen &&
            !inPictureInPicture
        ) {
            delay(40L)
            runCatching { (if (introAvailable && directionalInput) skipIntroFocusRequester else videoSurfaceFocusRequester).requestFocus() }
        }
    }

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) extraControlsOpen = false
    }
    fun skipIntro() {
        val actualDuration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: return
        val range = introSettings.timings.effective ?: return
        if (!player.isCurrentMediaItemSeekable || !range.contains(player.currentPosition, actualDuration)) return
        player.seekTo(range.endMillis)
        position = range.endMillis
        // A seek preserves playWhenReady, including a user's explicit pause.
        runCatching { if (controlsVisible) playPauseFocusRequester.requestFocus() else videoSurfaceFocusRequester.requestFocus() }
    }
    BackHandler {
        when {
            programmeGuideOpen -> programmeGuideOpen = false
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
            // Hide visible controls first; the next Back leaves playback.
            startFullscreen -> onBack()
            focusMode -> {
                focusMode = false
                onFullscreenChanged?.invoke(false)
                controlsVisible = true
            }
            else -> onBack()
        }
    }
    PlayerSurfaceHost(
        modifier
            .fillMaxSize()
            .playerQueueSwipeObserver(
                enabled = focusMode && hasPlaybackQueue && !pictureEditorVisible,
                onOpen = ::showEpisodeQueue
            )
            .playerActivityObserver {
                dpadInteraction++
            }
            .cricketScoreMovement(cricketScores)
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
        PlayerVideoLayer {
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
                    var lastTapAtMillis = 0L
                    var lastTapOnRight = false
                    var pendingSingleTap: Runnable? = null
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
                        if (
                            keyCode in setOf(
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_REWIND
                            ) &&
                            keyEvent.repeatCount > 0
                        ) return@setOnKeyListener true

                        /*
                         * AndroidView's factory listener survives ordinary
                         * recomposition. Always control the PlayerView's
                         * currently adopted player rather than the player that
                         * happened to be captured when the View was created.
                         */
                        val keyPlayer =
                            playerView.player
                                ?: return@setOnKeyListener false

                        if (keyCode != KeyEvent.KEYCODE_BACK) dpadInteraction++
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                showControlsAndFocusPlayPause()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                playbackRequested = !playbackRequested
                                if (playbackRequested) keyPlayer.play()
                                else keyPlayer.pause()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                playbackRequested = true
                                keyPlayer.play()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                playbackRequested = false
                                keyPlayer.pause()
                                controlsVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (
                                    media.catalogType == CatalogType.LIVE_TV &&
                                    hasPlaybackQueue
                                ) {
                                    onPlayNext()
                                } else if (media.catalogType != CatalogType.LIVE_TV || liveWindowSeekingAvailable) {
                                    val maxPosition =
                                        keyPlayer.duration
                                            .takeIf { it > 0L }
                                            ?: Long.MAX_VALUE
                                    keyPlayer.seekTo(
                                        (keyPlayer.currentPosition + 10_000L)
                                            .coerceAtMost(maxPosition)
                                    )
                                    controlsVisible = true
                                }
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                if (
                                    media.catalogType == CatalogType.LIVE_TV &&
                                    hasPlaybackQueue
                                ) {
                                    onPlayPrevious()
                                } else if (media.catalogType != CatalogType.LIVE_TV || liveWindowSeekingAvailable) {
                                    keyPlayer.seekTo(
                                        (keyPlayer.currentPosition - 10_000L)
                                            .coerceAtLeast(0L)
                                    )
                                    controlsVisible = true
                                }
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
                                // Reserve the outer quarters for level gestures. The
                                // entire middle half belongs to the playback queue,
                                // even while the controls are visible.
                                val sideBand = playerView.width * 0.25f
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
                                        !playerOptionsOpen &&
                                        ((event.x >= playerView.width * 0.25f &&
                                            event.x <= playerView.width * 0.75f) ||
                                            (!compactMobileControls && event.y >= playerView.height * 0.72f))
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
                                    val tappedOnRight = event.x >= playerView.width / 2f
                                    val doubleTap =
                                        hasPlaybackQueue &&
                                            tappedOnRight == lastTapOnRight &&
                                            event.eventTime - lastTapAtMillis in
                                                1..ViewConfiguration.getDoubleTapTimeout().toLong()
                                    if (doubleTap) {
                                        pendingSingleTap?.let(playerView::removeCallbacks)
                                        pendingSingleTap = null
                                        lastTapAtMillis = 0L
                                        if (media.catalogType == CatalogType.LIVE_TV || !advancing) {
                                            advancing = true
                                            if (tappedOnRight) currentOnPlayNext()
                                            else currentOnPlayPrevious()
                                        }
                                    } else {
                                        lastTapAtMillis = event.eventTime
                                        lastTapOnRight = tappedOnRight
                                        val targetVisibility = !controlsVisible
                                        if (hasPlaybackQueue) {
                                            pendingSingleTap?.let(playerView::removeCallbacks)
                                            val singleTapAction = Runnable {
                                                controlsVisible = targetVisibility
                                                pendingSingleTap = null
                                            }
                                            pendingSingleTap = singleTapAction
                                            playerView.postDelayed(
                                                singleTapAction,
                                                ViewConfiguration.getDoubleTapTimeout().toLong(),
                                            )
                                        } else {
                                            controlsVisible = targetVisibility
                                        }
                                    }
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
                val mpvPlayer = player as? MpvPlayerAdapter
                if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) mpvPlayer?.setVideoSizing(resizeMode)
                val requestedResizeMode = if (mpvPlayer != null) AspectRatioFrameLayout.RESIZE_MODE_FILL else when (resizeMode) {
                    VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                if (playerView.resizeMode != requestedResizeMode) {
                    playerView.resizeMode = requestedResizeMode
                }
                playerView.videoSurfaceView?.apply {
                    val modeScale = if (mpvPlayer == null && resizeMode == VideoResizeMode.ZOOM) 1.25f else 1f
                    val gestureScale = if (resizePreview != null) 1f else videoScale
                    val gestureOffset = if (resizePreview != null) Offset.Zero else videoOffset
                    scaleX = gestureScale * modeScale
                    scaleY = gestureScale * modeScale
                    translationX = gestureOffset.x
                    translationY = gestureOffset.y
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
            !playerOptionsOpen &&
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
                                ComposeKey.DirectionDown -> {
                                    val entered = introAvailable && runCatching { skipIntroFocusRequester.requestFocus() }.getOrDefault(false)
                                    if (!entered) { dpadInteraction++; showControlsAndFocusPlayPause() }
                                    true
                                }
                                ComposeKey.DirectionCenter,
                                ComposeKey.Enter,
                                ComposeKey.DirectionLeft,
                                ComposeKey.DirectionRight,
                                ComposeKey.DirectionUp -> {
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
        MobileQueueSwipeHandle(
            visible =
                compactMobileControls &&
                    focusMode &&
                    hasPlaybackQueue &&
                    !queueVisible &&
                    !pictureEditorVisible &&
                    !playerOptionsOpen &&
                    !inPictureInPicture,
            controlsVisible = controlsVisible,
            onOpen = {
                controlsVisible = false
                controlsFocused = false
                queueRevealDragging = false
                queueRevealProgress = 1f
                queueVisible = true
            }
        )
        }
        PlayerControlsLayer(visible = (controlsVisible || (!focusMode && !embeddedMode)) && !inPictureInPicture) {
            val seekable = duration > 0L && (media.catalogType != CatalogType.LIVE_TV || liveWindowSeekingAvailable)
            val primaryActionRequester = if (playbackError != null || startupTimedOut) errorRetryFocusRequester else playPauseFocusRequester
            val topDownRequester = if (seekable) progressFocusRequester else primaryActionRequester
            val firstMediaActionRequester = if (media.catalogType == CatalogType.LIVE_TV && recordingThisChannel) {
                recordingPauseFocusRequester
            } else {
                downloadFocusRequester
            }

            val secondaryActionRequesters = buildList {
                if (media.catalogType != CatalogType.LIVE_TV) {
                    if (introAvailable) add(skipIntroFocusRequester)
                    if (introSettings.target != null) add(introFocusRequester)
                    add(subtitleFocusRequester)
                    if (audioTracks.size > 1) add(audioFocusRequester)
                    if (media.nativeSabr == null && onChooseAlternateSource == null) add(downloadFocusRequester)
                }
                if (media.catalogType == CatalogType.LIVE_TV && !castSessionActive) add(refreshFocusRequester)
                add(resizeFocusRequester)
                add(pictureModeFocusRequester)
                if (media.nativeSabr == null) add(playerSwitchFocusRequester)
                if (onChooseAlternateSource != null) add(sourceFocusRequester)
                add(controlsTimeoutFocusRequester)
                add(cricketScoreFocusRequester)
                add(moreFocusRequester)
                if (pipAvailable) add(pipFocusRequester)
            }
            val firstQuickActionRequester = when {
                media.catalogType == CatalogType.LIVE_TV && showLiveRecordingAndGuide -> programmeGuideFocusRequester
                extraControlsOpen -> secondaryActionRequesters.first()
                else -> extraControlsRequester
            }
            val lastPlaybackActionRequester = when {
                media.nextEpisode != null -> nextFocusRequester
                seekable -> forwardFocusRequester
                else -> playPauseFocusRequester
            }
            val controlRowScrollState = rememberScrollState()
            val playbackDetailLines = buildList {
                videoDetails.takeIf { it.isNotBlank() }?.let { add(it) }
                add("${if (media.offlinePlayback) "Offline" else if (onChooseAlternateSource != null) "Alternate source" else "IPTV stream"} · ${media.playbackFormat.ifBlank { mediaFormatLabel(media.url) }}")
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
                        .zIndex(2f)
                        .then(if (focusMode) Modifier.statusBarsPadding() else Modifier)
                        .padding(
                            horizontal = if (compactMobileControls) 10.dp else 20.dp,
                            vertical = if (compactMobileControls) 4.dp else 14.dp
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
                                right = firstQuickActionRequester
                                down = topDownRequester
                            }
                            .playerDpadFocusRoutes(
                                right = firstQuickActionRequester,
                                down = topDownRequester
                            ),
                        onFocused = { controlsFocused = it }
                    )
                    Spacer(Modifier.width(if (compactMobileControls) 8.dp else 12.dp))
                    Column(
                        Modifier.weight(1f).padding(
                            top = 2.dp,
                            end = if (compactMobileControls) 44.dp else 56.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            media.media.title,
                            color = Color.White,
                            style = if (compactMobileControls) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        media.series?.let {
                            Text(
                                it.title,
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                        PlayerDateTime(
                            compact = compactMobileControls,
                            modifier = Modifier.align(Alignment.End)
                        )
                        PlayerDownloadStatusPill(
                            if (recordingThisChannel) LiveTvRecorder.statusText(liveRecording)
                            else offlineDownloadProgressText.orEmpty()
                        )
                    }
                }

                val quickActions: @Composable RowScope.() -> Unit = {
                        if (introAvailable) PlayerChromeIconButton(
                            icon = Icons.Default.SkipNext, contentDescription = "Skip intro", onClick = ::skipIntro,
                            modifier = Modifier.playerSecondaryFocus(skipIntroFocusRequester, secondaryActionRequesters,
                                extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it })
                        if (introSettings.target != null) PlayerChromeIconButton(
                            icon = Icons.Default.Flag, contentDescription = "Intro timing",
                            onClick = {
                                if (seekable && player.isCurrentMediaItemSeekable) introSettings.open(player.currentPosition)
                                else modeFeedback = "Intro timing needs a seekable video"
                            },
                            modifier = Modifier.playerSecondaryFocus(introFocusRequester, secondaryActionRequesters,
                                extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it })
                        if (media.catalogType == CatalogType.LIVE_TV && !castSessionActive) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh stream",
                                onClick = { onRetry(0L) },
                                modifier = Modifier.playerSecondaryFocus(refreshFocusRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester, backFocusRequester),
                                onFocused = { controlsFocused = it }
                            )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV) {
PlayerChromeIconButton(
                            icon = Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            onClick = { subtitleDialogOpen = true },
                            modifier = Modifier
                                .playerSecondaryFocus(subtitleFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it }
                        )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV && audioTracks.size > 1) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.AudioFile,
                                contentDescription = "Audio language",
                                onClick = { audioDialogOpen = true },
                                modifier = Modifier.playerSecondaryFocus(
                                    audioFocusRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester,
                                    if (seekable) progressFocusRequester else backFocusRequester
                                ),
                                onFocused = { controlsFocused = it }
                            )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV && media.nativeSabr == null && onChooseAlternateSource == null) {
                            PlayerChromeIconButton(
                                icon = if (offlineDownloadPresent && !displayedDownloadInProgress) Icons.Default.DownloadDone else Icons.Default.DownloadForOffline,
                                contentDescription = if (offlineDownloadPresent) "Cancel or remove offline download" else "Download for offline playback",
                                onClick = {
                                    if (!offlineDownloadPresent) downloadRequested = true
                                    onDownload()
                                },
                                modifier = Modifier
                                    .playerSecondaryFocus(downloadFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                                selected = offlineDownloadPresent,
                                progress = offlineDownloadProgress.takeIf { displayedDownloadInProgress },
                                indeterminateProgress = displayedDownloadInProgress && offlineDownloadProgress == null,
                                onFocused = { controlsFocused = it }
                            )
                        }
                        PlayerChromeIconButton(
                            icon = when (resizeMode) {
                                VideoResizeMode.FIT -> Icons.Default.FitScreen
                                VideoResizeMode.FILL -> Icons.Default.CropFree
                                VideoResizeMode.ZOOM -> Icons.Default.ZoomIn
                                VideoResizeMode.STRETCH -> Icons.Default.AspectRatio
                            },
                            badgeText = resizeMode.badge,
                            contentDescription = "Video fit: ${resizeMode.label}",
                            onClick = {
                                resizePreview = savedResizeMode
                                resizePickerVisible = true
                            },
                            modifier = Modifier
                                .playerSecondaryFocus(resizeFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            selected = false,
                            onFocused = {
                                controlsFocused = it
                            }
                        )
                        PlayerChromeIconButton(
                            icon = videoAppearanceIcon(activeAppearanceProfile.id),
                            contentDescription = "Choose picture mode: ${activeAppearanceProfile.name}",
                            onClick = { pictureModePickerVisible = true },
                            modifier = Modifier
                                .playerSecondaryFocus(pictureModeFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it }
                        )
                        if (media.nativeSabr == null) PlayerChromeIconButton(
                                    icon = Icons.Default.SmartDisplay,
                                    badgeText = displayedPlayerChoice.playerChoiceBadge(),
                                    contentDescription = "Player: ${displayedPlayerChoice.playerChoiceLabel()}",
                                    onClick = { playerPickerVisible = true },
                                    modifier = Modifier
                                        .playerSecondaryFocus(playerSwitchFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                                    size = if (compactMobileControls) 44.dp else 48.dp,
                                    selected = false,
                                    onFocused = { controlsFocused = it }
                                )
                        if (onChooseAlternateSource != null) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.VideoSettings,
                                contentDescription = "Source and quality",
                                onClick = {
                                    onChooseAlternateSource(player.currentPosition.coerceAtLeast(0L)) {
                                        player.currentPosition.coerceAtLeast(0L)
                                    }
                                },
                                modifier = Modifier.playerSecondaryFocus(
                                    sourceFocusRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester,
                                    if (seekable) progressFocusRequester else backFocusRequester
                                ),
                                size = if (compactMobileControls) 44.dp else 48.dp,
                                onFocused = { controlsFocused = it }
                            )
                        }

                        PlayerChromeIconButton(
                            icon = Icons.Default.Timer,
                            badgeText = playerControlsTimeoutBadge(controlsTimeoutSeconds),
                            contentDescription = "Controls timeout: ${playerControlsTimeoutLabel(controlsTimeoutSeconds)}",
                            onClick = {
                                val seconds = nextPlayerControlsTimeoutSeconds(controlsTimeoutSeconds)
                                onControlsTimeoutChanged(seconds)
                                modeFeedback = playerControlsTimeoutFeedback(seconds)
                            },
                            modifier = Modifier.playerSecondaryFocus(controlsTimeoutFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                            icon = Icons.Default.SportsCricket,
                            contentDescription = "Live cricket scores",
                            onClick = { cricketScores.pickerOpen = true },
                            modifier = Modifier.playerSecondaryFocus(cricketScoreFocusRequester, secondaryActionRequesters,
                                extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                            icon = Icons.Default.Info,
                            contentDescription = "Playback information",
                            onClick = { moreOptionsOpen = true },
                            modifier = Modifier
                                .playerSecondaryFocus(moreFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                            onFocused = { controlsFocused = it }
                        )
                }

                val liveActions: @Composable RowScope.() -> Unit = {
                    PlayerChromeIconButton(
                        icon = Icons.Default.EventNote,
                        badgeText = media.media.liveSchedule.size.takeIf { it > 0 }?.toString(),
                        contentDescription = "Programme guide",
                        onClick = { programmeGuideOpen = true },
                        modifier = Modifier
                            .focusRequester(programmeGuideFocusRequester)
                            .playerDpadFocusRoutes(
                                left = lastPlaybackActionRequester,
                                right = if (recordingThisChannel) recordingPauseFocusRequester else downloadFocusRequester,
                                up = if (seekable) progressFocusRequester else backFocusRequester
                            ),
                        onFocused = { controlsFocused = it }
                    )
                    if (recordingThisChannel) {
                        PlayerChromeIconButton(
                            icon = if (liveRecording.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (liveRecording.paused) "Resume recording" else "Pause recording",
                            onClick = {
                                if (liveRecording.paused) LiveTvRecorder.resume(
                                    context,
                                    player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L },
                                    livePlaybackClock.sample(android.os.SystemClock.elapsedRealtime(), player.isPlaying)
                                )
                                else LiveTvRecorder.pause(context,
                                    livePlaybackClock.sample(android.os.SystemClock.elapsedRealtime(), player.isPlaying))
                            },
                            modifier = Modifier
                                .focusRequester(recordingPauseFocusRequester)
                                .playerDpadFocusRoutes(
                                    left = programmeGuideFocusRequester,
                                    right = downloadFocusRequester,
                                    up = backFocusRequester
                                ),
                            onFocused = { controlsFocused = it }
                        )
                    }
                    PlayerChromeIconButton(
                        icon = if (recordingThisChannel) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                        contentDescription = if (recordingThisChannel) "Stop recording" else "Record live TV",
                        onClick = {
                            if (liveRecording.active) LiveTvRecorder.stop(context,
                                livePlaybackClock.sample(android.os.SystemClock.elapsedRealtime(), player.isPlaying))
                            else LiveTvRecorder.start(
                                context,
                                media.media.title,
                                media.url,
                                player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L },
                                livePlaybackClock.sample(android.os.SystemClock.elapsedRealtime(), player.isPlaying)
                            )
                        },
                        modifier = Modifier
                            .focusRequester(downloadFocusRequester)
                            .playerDpadFocusRoutes(
                                left = if (recordingThisChannel) recordingPauseFocusRequester else programmeGuideFocusRequester,
                                right = if (extraControlsOpen) secondaryActionRequesters.first() else extraControlsRequester,
                                up = backFocusRequester
                            ),
                        onFocused = { controlsFocused = it }
                    )
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
                            vertical = if (compactMobileControls) 4.dp else 11.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(if (compactMobileControls) 3.dp else 8.dp)
                    ) {
                        if (seekable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            PlaybackProgressBar(
                                mediaKey = media.progressKey,
                                active = controlsVisible || (!focusMode && !embeddedMode),
                                position = position,
                                duration = duration,
                                bufferedPosition = bufferedPosition.takeIf { liveWindowSeekingAvailable },
                                onNavigateRight = if (liveWindowSeekingAvailable && currentWindowIsLive) ({
                                    runCatching { watchLiveFocusRequester.requestFocus() }.getOrDefault(false)
                                }) else null,
                                onSeek = {
                                    player.seekTo(it)
                                },
                                compact = compactMobileControls,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(progressFocusRequester)
                                    .focusProperties {
                                        up = backFocusRequester
                                        down = primaryActionRequester
                                        if (liveWindowSeekingAvailable && currentWindowIsLive) right = watchLiveFocusRequester
                                    }
                                    .playerControlFocus(
                                        shape = RoundedCornerShape(14.dp),
                                        scaleOnFocus = false
                                    ) { controlsFocused = it }
                            )
                            if (liveWindowSeekingAvailable && currentWindowIsLive) PlayerChromeIconButton(
                                icon = Icons.Default.LiveTv, contentDescription = "Watch live", badgeText = "LIVE",
                                onClick = { player.seekToDefaultPosition(); playbackRequested = true; player.play() },
                                modifier = Modifier.focusRequester(watchLiveFocusRequester)
                                    .playerDpadFocusRoutes(left = progressFocusRequester, down = primaryActionRequester, up = backFocusRequester),
                                onFocused = { controlsFocused = it })
                            }
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
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    formatLivePlaybackElapsed(livePlaybackElapsedMillis),
                                    color = Color.White.copy(alpha = 0.86f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        if (playbackError == null && !startupTimedOut) {
                            Row(
                                Modifier
                                    .fillMaxWidth()

                                    .onPreviewKeyEvent { event ->
                                        if (
                                            event.type == ComposeKeyEventType.KeyDown &&
                                            event.key == ComposeKey.DirectionDown &&
                                            focusMode &&
                                            hasPlaybackQueue
                                        ) {
                                            dpadInteraction++
                                            showEpisodeQueue()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(
                                    if (compactMobileControls) 4.dp else 8.dp,
                                    Alignment.CenterHorizontally
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val utilityButtonSize = if (compactMobileControls) 44.dp else 48.dp
                                val isTv = LocalContext.current.isTvLikeDevice(LocalConfiguration.current)

                                Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 0.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                    if (media.previousEpisode != null) {
                                        PlayerChromeIconButton(
                                            icon = Icons.Default.SkipPrevious,
                                            contentDescription = "Previous",
                                            onClick = { if (media.catalogType == CatalogType.LIVE_TV || !advancing) { advancing = true; onPlayPrevious() } },
                                            modifier = Modifier.focusRequester(previousFocusRequester)
                                                .focusProperties {
                                                    up = if (seekable) progressFocusRequester else backFocusRequester
                                                    right = if (seekable) rewindFocusRequester else playPauseFocusRequester
                                                }
                                                .playerDpadFocusRoutes(
                                                    right = if (seekable) rewindFocusRequester else playPauseFocusRequester,
                                                    up = if (seekable) progressFocusRequester else backFocusRequester
                                                ),
                                            size = if (compactMobileControls) 44.dp else 48.dp,
                                            onFocused = { controlsFocused = it }
                                        )
                                    }
                                    if (seekable) {
                                        PlayerChromeIconButton(
                                            icon = Icons.Default.Replay10,
                                            contentDescription = "Back 10 seconds",
                                            onClick = {
                                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                            },
                                            modifier = Modifier.focusRequester(rewindFocusRequester)
                                                .focusProperties {
                                                    up = progressFocusRequester
                                                    left = if (media.previousEpisode != null) previousFocusRequester else FocusRequester.Default
                                                    right = playPauseFocusRequester
                                                }
                                                .playerDpadFocusRoutes(
                                                    left = if (media.previousEpisode != null) previousFocusRequester else null,
                                                    right = playPauseFocusRequester,
                                                    up = progressFocusRequester
                                                ),
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
                                                up = if (seekable) progressFocusRequester else backFocusRequester
                                                left = when {
                                                    seekable -> rewindFocusRequester
                                                    media.previousEpisode != null -> previousFocusRequester
                                                    else -> FocusRequester.Default
                                                }
                                                right = when {
                                                    seekable -> forwardFocusRequester
                                                    media.nextEpisode != null -> nextFocusRequester
                                                    else -> firstQuickActionRequester
                                                }
                                            }
                                            .playerDpadFocusRoutes(
                                                left = when {
                                                    seekable -> rewindFocusRequester
                                                    media.previousEpisode != null -> previousFocusRequester
                                                    else -> null
                                                },
                                                right = when {
                                                    seekable -> forwardFocusRequester
                                                    media.nextEpisode != null -> nextFocusRequester
                                                    else -> firstQuickActionRequester
                                                },
                                                up = if (seekable) progressFocusRequester else backFocusRequester
                                            ),
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
                                                .focusProperties {
                                                    up = progressFocusRequester
                                                    left = playPauseFocusRequester
                                                    right = if (media.nextEpisode != null) nextFocusRequester else firstQuickActionRequester
                                                }
                                                .playerDpadFocusRoutes(
                                                    left = playPauseFocusRequester,
                                                    right = if (media.nextEpisode != null) nextFocusRequester else firstQuickActionRequester,
                                                    up = progressFocusRequester
                                                ),
                                            size = if (compactMobileControls) 44.dp else 48.dp,
                                            onFocused = { controlsFocused = it }
                                        )
                                    }
                                    if (media.nextEpisode != null) {
                                        PlayerChromeIconButton(
                                            icon = Icons.Default.SkipNext,
                                            contentDescription = "Next",
                                            onClick = { if (media.catalogType == CatalogType.LIVE_TV || !advancing) { advancing = true; onPlayNext() } },
                                            modifier = Modifier.focusRequester(nextFocusRequester)
                                                .focusProperties {
                                                    up = if (seekable) progressFocusRequester else backFocusRequester
                                                    left = if (seekable) forwardFocusRequester else playPauseFocusRequester
                                                    right = firstQuickActionRequester
                                                }
                                                .playerDpadFocusRoutes(
                                                    left = if (seekable) forwardFocusRequester else playPauseFocusRequester,
                                                    right = firstQuickActionRequester,
                                                    up = if (seekable) progressFocusRequester else backFocusRequester
                                                ),
                                            size = if (compactMobileControls) 44.dp else 48.dp,
                                            onFocused = { controlsFocused = it }
                                        )
                                    }
                                }

                                if (media.catalogType == CatalogType.LIVE_TV && showLiveRecordingAndGuide) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        content = liveActions
                                    )
                                }

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                PlayerExtraControls(visible = extraControlsOpen) {
                                quickActions()
                                if (pipAvailable) {
                                    val pipLeftRequester = moreFocusRequester
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        onClick = {
                                            controlsVisible = false
                                            controlsFocused = false
                                            pipActivity?.enterPlayerPictureInPicture()
                                        },
                                        modifier = Modifier
                                            .playerSecondaryFocus(pipFocusRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressFocusRequester else backFocusRequester),
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }


                                }
                                }
                                PlayerChromeIconButton(
                                    icon = Icons.Default.Tune,
                                    contentDescription = if (extraControlsOpen) "Hide extra controls" else "More controls",
                                    onClick = { extraControlsOpen = !extraControlsOpen },
                                    selected = extraControlsOpen,
                                    modifier = Modifier.focusRequester(extraControlsRequester)
                                        .focusProperties {
                                            left = if (extraControlsOpen) secondaryActionRequesters.last() else lastPlaybackActionRequester
                                            right = FocusRequester.Cancel
                                            up = if (seekable) progressFocusRequester else backFocusRequester
                                        }
                                        .playerDpadFocusRoutes(
                                            left = if (extraControlsOpen) secondaryActionRequesters.last() else lastPlaybackActionRequester,
                                            right = FocusRequester.Cancel,
                                            up = if (seekable) progressFocusRequester else backFocusRequester
                                        ),
                                    onFocused = { controlsFocused = it }
                                )
                            }
                        }
                    }
                }
            }
            if (moreOptionsOpen) {
                PlayerMoreOptionsDialog(
                    detailLines = playbackDetailLines,
                    onDismiss = {
                        moreOptionsOpen = false
                        coroutineScope.launch {
                            delay(80L)
                            runCatching { moreFocusRequester.requestFocus() }
                        }
                    }
                )
            }
            if (programmeGuideOpen) {
                PlayerLiveScheduleOverlay(media.media) {
                    programmeGuideOpen = false
                    if (directionalInput) coroutineScope.launch { delay(80L); runCatching { programmeGuideFocusRequester.requestFocus() } }
                }
            }
        }
        PlayerOverlayLayer {
        CricketScoreOverlay(cricketScores, visible = !inPictureInPicture)
        if (introAvailable && !controlsVisible && (focusMode || embeddedMode) && !queueVisible &&
            !playerOptionsOpen && !programmeGuideOpen && !moreOptionsOpen && !inPictureInPicture &&
            playbackError == null && !startupTimedOut) IntroSkipButton(skipIntroFocusRequester,
            onSkip = ::skipIntro, onControls = ::showControlsAndFocusPlayPause,
            onReturnToVideo = { runCatching { videoSurfaceFocusRequester.requestFocus() }; Unit },
            remainingSeconds = introSecondsRemaining ?: 0L,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp))
        if (introSettings.editorOpen) IntroTimingDialog(introSettings, duration,
            currentPosition = { player.currentPosition.coerceAtLeast(0L) }, onDismiss = {
                introSettings.close()
                returnControlFocus = introFocusRequester
            })
        if (queueVisible && focusMode && !pictureEditorVisible) PlayerQueueOverlay(
            items = playerQueueItems,
            alternateSeries = media.series,
            pinnedIds = if (media.catalogType == CatalogType.LIVE_TV) pinnedQueueIds else emptyList(),
            playingId = media.media.id,
            favoriteIds = queueFavoriteIds,
            onToggleFavorite = if (media.catalogType == CatalogType.LIVE_TV) {
                onToggleQueueFavorite
            } else {
                null
            },
            onTogglePin = if (media.catalogType == CatalogType.LIVE_TV) onToggleQueuePin else null,
            onRefreshProgramme = if (media.catalogType == CatalogType.LIVE_TV) onRefreshQueueProgramme else null,
            onVisibleChannels = onVisibleQueueChannels,
            liveCategoryTitle = liveQueueCategoryTitle,
            liveXtream = liveQueueXtream,
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
                controlsVisible = false
                controlsFocused = false
                runCatching { videoSurfaceFocusRequester.requestFocus() }
            },
            onSelect = { item, index ->
                queueVisible = false
                if (onPlayQueueItem != null) onPlayQueueItem(item, index) else onPlayItem(item)
            }
        )
        if (pictureEditorVisible) PlayerPictureModeEditor(
            profiles = appearanceProfiles,
            selectedId = activeAppearanceProfile.id,
            onDismiss = {
                pictureEditorVisible = false
                pictureModePickerVisible = true
            },
            onPreview = { appearancePreview = it },
            onSelected = {
                VideoAppearancePreferences.setActive(context, it)
            }
        )
        if (playerPickerVisible) PlayerEngineQuickOverlay(
            selectedEngine = displayedPlayerChoice,
            allowedEngines = if (media.streamDrm != null) PROTECTED_PLAYER_ENGINES else null,
            onDismiss = {
                playerPickerVisible = false
                controlsVisible = true
                val origin = if (playerPickerFromError) errorPlayerFocusRequester else playerSwitchFocusRequester
                playerPickerFromError = false
                coroutineScope.launch { delay(80L); runCatching { origin.requestFocus() } }
            },
            onSelect = { selectedEngine ->
                val fromError = playerPickerFromError
                playerPickerFromError = false
                playerPickerVisible = false
                controlsVisible = true
                if (fromError && selectedEngine.resolvePlayerEngine(context, playbackScope, playbackDomain) == effectiveEngine) {
                    selectedPlayerChoice = selectedEngine
                    onPlaybackEngineChanged(selectedEngine)
                    (onManualRetry ?: onRetry)(playbackRestartPosition(media.catalogType, player.currentPosition))
                } else if (selectedEngine != selectedPlayerChoice.pickerChoice()) {
                    restorePlayerSwitchFocus = true
                    selectedPlayerChoice = selectedEngine
                    modeFeedback = "Player · ${selectedEngine.playerChoiceLabel()}"
                    engineSwitchResumePosition = playbackRestartPosition(media.catalogType, player.currentPosition)
                    onPlaybackEngineChanged(selectedEngine)
                    sessionEngineOverride = selectedEngine.resolvePlayerEngine(context, playbackScope, playbackDomain)
                    if (fromError) onManualRetry?.invoke(playbackRestartPosition(media.catalogType, player.currentPosition))
                } else if (fromError) {
                    (onManualRetry ?: onRetry)(playbackRestartPosition(media.catalogType, player.currentPosition))
                } else {
                    coroutineScope.launch { delay(80L); runCatching { playerSwitchFocusRequester.requestFocus() } }
                }
            }
        )
        if (resizePickerVisible) VideoResizeQuickOverlay(
            preview = resizeMode,
            onPreview = { resizePreview = it },
            onSave = {
                VideoResizePreferences.save(context, resizeMode)
                videoScale = 1f
                videoOffset = Offset.Zero
                resizePreview = null
                resizePickerVisible = false
                returnControlFocus = resizeFocusRequester
            },
            onSkip = {
                resizePreview = null
                resizePickerVisible = false
                returnControlFocus = resizeFocusRequester
            }
        )
        if (pictureModePickerVisible) PictureModeQuickOverlay(
            profiles = appearanceProfiles,
            preview = activeAppearanceProfile,
            onPreview = { appearancePreview = it },
            onSettings = {
                pictureModePickerVisible = false
                pictureEditorVisible = true
            },
            onApply = {
                VideoAppearancePreferences.setActive(context, activeAppearanceProfile.id)
                pictureModePickerVisible = false
                appearancePreview = null
                returnControlFocus = pictureModeFocusRequester
            },
            onSkip = {
                appearancePreview = null
                pictureModePickerVisible = false
                returnControlFocus = pictureModeFocusRequester
            }
        )
        if (
            (bufferingIndicatorVisible || decoderRecoveryInProgress) &&
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
                    } else if (playbackHasBeenReady) {
                        "Buffering stream…"
                    } else {
                        "Connecting to stream…"
                    },
                    color = Color.White
                )
            }
        }
        if (audioDisabledAfterFailure && !castSessionActive && !inPictureInPicture) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xDD181818)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.VolumeOff, contentDescription = "Audio disabled after output failure",
                        tint = Color(0xFFFFCC80), modifier = Modifier.size(20.dp))
                    if (controlsVisible) {
                        Text("Audio unavailable · video only", color = Color.White,
                            style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { retryAudioOutput() }) { Text("Retry audio") }
                    }
                }

            }
        }
        val failure = playbackError ?: if (startupTimedOut) "The stream did not start within 25 seconds." else null
        if (failure != null && (controlsVisible || (!focusMode && !embeddedMode))) {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE181818)
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(42.dp), tint = Color(0xFFE50914))
                    Text("This title can’t be played right now", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(failure, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                    PlayerErrorActions(
                        compact = compactMobileControls,
                        canNavigate = hasPlaybackQueue,
                        onPrevious = onPlayPrevious,
                        onBack = onBack,
                        onRetry = {
                            liveRecovery.reset(); liveEdgeRecovery.reset()
                            (onManualRetry ?: onRetry)(playbackRestartPosition(media.catalogType, player.currentPosition))
                        },
                        onNext = onPlayNext,
                        showAlternatePlayer = media.nativeSabr == null,
                        onSwitchPlayer = {
                            playerPickerFromError = true
                            playerPickerVisible = true
                        },
                        switchPlayerModifier = Modifier.focusRequester(errorPlayerFocusRequester),
                        onReauthenticate = onReauthenticate?.let { authenticate ->
                            { authenticate(playbackRestartPosition(media.catalogType, player.currentPosition)) }
                        },
                        reauthenticating = reauthenticating,
                        onChooseSource = onChooseAlternateSource?.let { choose -> {
                            choose(player.currentPosition.coerceAtLeast(0L)) { player.currentPosition.coerceAtLeast(0L) }
                        } },
                        retryModifier = Modifier.focusRequester(errorRetryFocusRequester)
                            .playerControlFocus(RoundedCornerShape(12.dp)) { controlsFocused = it }
                    )
                }
            }
        }
        gestureFeedback?.let { (isBrightness, level) ->
            PlayerLevelFeedback(isBrightness, level, compactMobileControls,
                Modifier.align(if (isBrightness) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = if (compactMobileControls) 12.dp else 20.dp))
        }
        val countdown = remainingSeconds
        modeFeedback?.let { PlayerModeFeedback(it) }
        if (countdown != null && showAutoPlayPrompt) {
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
                    NikTvTextActionButton(onClick = {
                        autoPlayCancelled = true
                        playerViewRef?.requestFocus()
                    }) { Text("Cancel") }
                    NikTvPrimaryActionButton(
                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                        modifier = Modifier.focusRequester(playNextFocusRequester)
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
        if (audioDialogOpen) {
            PlayerAudioTrackDialog(
                tracks = audioTracks.map { track ->
                    PlayerAudioTrackOption(
                        track.id,
                        track.label,
                        track.group.isTrackSelected(track.trackIndex)
                    )
                },
                onSelect = { id ->
                    audioTracks.firstOrNull { it.id == id }?.let { track ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(
                                TrackSelectionOverride(track.group.mediaTrackGroup, listOf(track.trackIndex))
                            )
                            .build()
                    }
                    audioDialogOpen = false
                },
                onDismiss = { audioDialogOpen = false }
            )
        }
        }
    }
}

internal fun PlaybackEngine.playerChoiceBadge(): String = when (this) {
    PlaybackEngine.AUTO -> "AUTO"
    PlaybackEngine.MEDIA3, PlaybackEngine.EXOPLAYER -> "EXO"
    PlaybackEngine.FLOW -> "FLOW"
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.MPV -> "MPV"
}

private object FailedDecoderRegistry {
    private val sessions = DecoderSessionFailures()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun selector(
        context: Context,
        playbackScope: String,
        sessionId: String
    ): MediaCodecSelector {
        val rememberedMtk = context
                .getSharedPreferences(
                    "player_decoder_fallbacks",
                    Context.MODE_PRIVATE
                )
                .getStringSet(
                    "failed_decoders",
                    emptySet()
                )
                .orEmpty().filter { it == "omx.mtk.video.decoder.avc" }.toSet()

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

            val failedNames = rememberedMtk + sessions.failed(sessionId)
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
                    ("omx.mtk.video.decoder.avc" in (failedNames + rememberedMtk) ||
                        PlayerEngineFallback.prefersSoftwareAvc(context, playbackScope))

            val candidatesByName = filtered.associateBy { it.name }
            // Preserve IPTV's committed MTK fallback policy. Sports owns an
            // independent native-order selector and cannot update this registry.
            val ordered = iptvDecoderCandidateOrder(candidates.map { it.name }, rememberedMtk, sessions.failed(sessionId),
                preferSoftware = mtkAvcPreviouslyFailed)
                .mapNotNull(candidatesByName::get)

            // Exhaustion is final for this selection: do not restore failed candidates.
            ordered
        }
    }

    fun record(
        context: Context,
        error: Throwable,
        playbackScope: String,
        sessionId: String
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
            decoderFailureName(messages)
                ?: return null

        val normalized =
            decoder.lowercase()

        sessions.record(sessionId, normalized)

        if (normalized == "omx.mtk.video.decoder.avc") {
            context
                .getSharedPreferences(
                    "player_decoder_fallbacks",
                    Context.MODE_PRIVATE
                )
                .edit()
                .putStringSet(
                    "failed_decoders",
                    setOf(normalized).filter { it == "omx.mtk.video.decoder.avc" }.toSet()
                )
                .apply()
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
/*
 * PLAYER_FOCUS_AND_ENGINE_SELECTOR_V49
 *
 * The visible player control surface and the TV focus surface intentionally
 * share one exact size and RoundedCornerShape. This avoids a 48dp button
 * receiving a 56dp focus ring. Surface owns both the click target and shape.
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
    badgeText: String? = null,
    size: Dp = if (primaryAction) 56.dp else 48.dp,
    iconSize: Dp = if (primaryAction) 28.dp else 23.dp,
    onFocused: (Boolean) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val minimumFocusSize = when {
        rememberPlayerTvDevice() -> 56.dp
        configuration.smallestScreenWidthDp < 600 -> 44.dp
        else -> 48.dp
    }
    val controlSize = if (size < minimumFocusSize) minimumFocusSize else size
    val shape = RoundedCornerShape(if (primaryAction) 16.dp else 12.dp)
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(controlSize)
            .playerControlFocus(
                shape = shape,
                scaleOnFocus = true,
                onFocused = {
                    isFocused = it
                    onFocused(it)
                }
            ),
        shape = shape,
        color = when {
            isFocused -> Color(0xFF3B4D68)
            selected -> Color(0xFF303A49)
            primaryAction -> Color.Black.copy(alpha = 0.64f)
            else -> Color.Black.copy(alpha = 0.46f)
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9AA9BD))
        } else null,
        contentColor = Color.White
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
                // A mode badge must never shift the glyph away from the common
                // center line shared by the surrounding player controls.
                modifier = Modifier.size(iconSize),
                tint = Color.White
            )
            badgeText?.let { label ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = 3.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    contentColor = Color.White
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
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

/*
 * PLAYER_ENGINE_ROW_AND_POPUP_RESTORE_V50
 *
 * Keep every player choice in one equal-width row. Selection is persistent
 * styling; D-pad focus remains a separate TV-only treatment. The compact
 * label keeps Auto / Media3 / VLC / ExoPlayer visually balanced in one row.
 */
@Composable
private fun PlayerEngineChoiceButton(
    engine: PlaybackEngine,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isTvLikeDevice(configuration)
    val controlHeight = if (isTv) 44.dp else 40.dp
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(controlHeight)
            .playerControlFocus(shape) {},
        shape = shape,
        color = if (selected) {
            Color(0xFF303A49)
        } else {
            Color.White.copy(alpha = 0.055f)
        },
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) {
                Color(0xFF9AA9BD)
            } else {
                Color.White.copy(alpha = 0.10f)
            }
        ),
        contentColor = Color.White
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                engine.playerChoiceLabel(),
                style = if (isTv) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                fontWeight = if (selected) {
                    androidx.compose.ui.text.font.FontWeight.SemiBold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Medium
                },
                color = Color.White.copy(alpha = if (selected) 1f else 0.90f),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}


/*
 * Picture mode uses the same selected-vs-focused visual language as Player.
 * Eight presets are shown as two uniform rows of four so every choice remains
 * visible without shrinking long labels into unreadable chips.
 */
@Composable
internal fun PlayerVisualChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isTvLikeDevice(configuration)
    val controlHeight = if (isTv) 56.dp else 44.dp
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(controlHeight)
            .playerControlFocus(shape) {},
        shape = shape,
        color = if (selected) {
            Color(0xFF303A49)
        } else {
            Color.White.copy(alpha = 0.055f)
        },
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) {
                Color(0xFF9AA9BD)
            } else {
                Color.White.copy(alpha = 0.10f)
            }
        ),
        contentColor = Color.White
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (isTv) 11.sp else 10.sp,
                    lineHeight = if (isTv) 13.sp else 11.sp
                ),
                fontWeight = if (selected) {
                    androidx.compose.ui.text.font.FontWeight.SemiBold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Medium
                },
                color = Color.White.copy(alpha = if (selected) 1f else 0.90f),
                maxLines = 2,
                softWrap = true
            )
        }
    }
}

@Composable
private fun PlayerPictureModeActionButton(
    label: String,
    modifier: Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    NikTvActionButton(
        label = label,
        modifier = modifier,
        primary = primary,
        icon = icon,
        onClick = onClick
    )
}

@Composable
internal fun PictureModeQuickOverlay(
    profiles: List<VideoAppearanceProfile>,
    preview: VideoAppearanceProfile,
    onPreview: (VideoAppearanceProfile) -> Unit,
    onSettings: () -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isTvLikeDevice(configuration)
    val compactPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val columns = if (compactPhone) 2 else 4
    val secondaryActionWidth = if (compactPhone) 76.dp else 96.dp
    val settingsActionWidth = if (compactPhone) 108.dp else 112.dp
    val actionHeight = 44.dp
    val modeRequesters = remember(profiles.map { it.id }) { profiles.map { FocusRequester() } }
    val skipRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onSkip,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.apply {
                    setGravity(Gravity.END)
                    setDimAmount(0f)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            Column {
                Text("Picture mode", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("Preview: ${preview.name}  •  Apply to save", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(
                Modifier.playerChoiceFocusEntry(modeRequesters.getOrNull(profiles.indexOfFirst { it.id == preview.id }.coerceAtLeast(0)) ?: settingsRequester).then(
                    if (compactPhone) {
                        Modifier.widthIn(min = 260.dp, max = 330.dp)
                    } else {
                        // Match the mode grid to the dialog/action width on
                        // tablets and TVs. A fixed 420dp grid left an empty
                        // column directly above the right-aligned Apply button.
                        Modifier.fillMaxWidth()
                    }
                ),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                profiles.chunked(columns).forEachIndexed { row, rowModes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowModes.forEachIndexed { column, profile ->
                            val index = row * columns + column
                            PlayerVisualChoiceButton(
                                label = profile.name,
                                selected = profile.id == preview.id,
                                modifier = Modifier.weight(1f).focusRequester(modeRequesters[index]).focusProperties {
                                    if (column > 0) left = modeRequesters[index - 1]
                                    if (column < rowModes.lastIndex) right = modeRequesters[index + 1]
                                    if (row > 0) up = modeRequesters[index - columns]
                                    down = modeRequesters.getOrNull(index + columns) ?: applyRequester
                                },
                                onClick = { onPreview(profile) }
                            )
                        }
                        repeat(columns - rowModes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerPictureModeActionButton(
                    label = "Settings",
                    icon = Icons.Default.Tune,
                    modifier = Modifier.width(settingsActionWidth).height(actionHeight).focusRequester(settingsRequester)
                        .focusProperties { right = skipRequester },
                    onClick = onSettings
                )
                PlayerPictureModeActionButton(
                    label = "Skip",
                    modifier = Modifier.width(secondaryActionWidth).height(actionHeight).focusRequester(skipRequester)
                        .focusProperties { left = settingsRequester; right = applyRequester },
                    onClick = onSkip
                )
                PlayerPictureModeActionButton(
                    label = "Apply",
                    primary = true,
                    modifier = Modifier.width(secondaryActionWidth).height(actionHeight).focusRequester(applyRequester)
                        .focusProperties { left = skipRequester },
                    onClick = onApply
                )
            }
        }
    )
}


@Composable
internal fun PlayerMoreOptionsDialog(
    detailLines: List<String>,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val compactLandscape =
        configuration.smallestScreenWidthDp < 600 &&
            configuration.screenWidthDp > configuration.screenHeightDp
    val optionsBodyMaxHeight =
        (configuration.screenHeightDp * if (compactLandscape) .55f else .68f).dp
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100L)
        runCatching { closeRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Playback information", color = Color.White)
                Text(
                    "Details for the current stream",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                Modifier
                    .widthIn(min = 300.dp, max = 520.dp)
                    .heightIn(max = optionsBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
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

            }
        },
        confirmButton = {
            NikTvTextActionButton(
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(closeRequester)
                    .playerControlFocus(RoundedCornerShape(12.dp)) {},
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Close")
            }
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
    active: Boolean = true,
    modifier: Modifier = Modifier,
    bufferedPosition: Long? = null,
    onNavigateRight: (() -> Boolean)? = null
) {
    val safeDuration = duration.coerceAtLeast(0L)
    var preview by remember(mediaKey) { mutableStateOf<Long?>(null) }
    var revision by remember(mediaKey) { mutableIntStateOf(0) }
    var directionHeld by remember(mediaKey) { mutableStateOf(false) }
    val holdAcceleration = remember(mediaKey) { SeekHoldAcceleration() }
    var dragging by remember(mediaKey) { mutableStateOf(false) }
    val seek by rememberUpdatedState(onSeek)
    LaunchedEffect(mediaKey, revision, directionHeld, dragging, active) {
        if (!active) { preview = null; directionHeld = false; dragging = false; holdAcceleration.reset(); return@LaunchedEffect }
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
            .height(if (compact) 40.dp else 58.dp)
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 3.dp)
            .onPreviewKeyEvent { event ->
                if (!active) return@onPreviewKeyEvent true
                if (event.key == ComposeKey.DirectionRight && onNavigateRight != null) {
                    if (event.type == ComposeKeyEventType.KeyDown) onNavigateRight()
                    return@onPreviewKeyEvent true
                }
                val direction = when (event.key) {
                    ComposeKey.DirectionLeft, ComposeKey.MediaRewind -> -1
                    ComposeKey.DirectionRight, ComposeKey.MediaFastForward -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == ComposeKeyEventType.KeyDown) {
                    val step = holdAcceleration.step(direction, event.nativeKeyEvent.eventTime,
                        event.nativeKeyEvent.repeatCount > 0, safeDuration)
                    directionHeld = true
                    preview = seekPreviewPosition(preview ?: position, direction, safeDuration, step)
                    revision++
                } else if (event.type == ComposeKeyEventType.KeyUp) {
                    holdAcceleration.release(direction)
                    directionHeld = holdAcceleration.isHeld
                    revision++
                }
                true
            }
            .onFocusChanged { if (!it.hasFocus) { directionHeld = false; holdAcceleration.reset() } }
            .focusProperties { canFocus = active }
            .focusable(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "−$remaining  /  $total",
                color = Color.White.copy(alpha = 0.70f),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            )
        }
        Slider(
            // Use one normalized unit for the thumb, painted track and accessibility range.
            // Millisecond values were being clamped at the range endpoint on device.
            value = fraction,
            onValueChange = {
                holdAcceleration.reset()
                directionHeld = false
                dragging = true
                preview = (it.toDouble() * safeDuration).toLong().coerceIn(0L, safeDuration)
                revision++
            },
            onValueChangeFinished = { dragging = false; revision++ },
            enabled = active && safeDuration > 0L,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().height(if (compact) 20.dp else 28.dp)
                .focusProperties { canFocus = false },
            thumb = {
                Text(
                    elapsed,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "Current playback time $elapsed" }
                        .padding(horizontal = 6.dp, vertical = if (compact) 1.dp else 3.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false
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
                    bufferedPosition?.let { buffered ->
                        Box(Modifier.fillMaxHeight().fillMaxWidth(
                            if (safeDuration > 0L) (buffered.toFloat() / safeDuration).coerceIn(0f, 1f) else 0f)
                            .background(Color.White.copy(alpha = .35f)))
                    }
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
    scaleOnFocus: Boolean = true,
    onFocused: (Boolean) -> Unit = {}
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = rememberPlayerTvDevice()
    val directionalInput = playerUsesDirectionalInput()
    val showFocus = focused && directionalInput
    LaunchedEffect(showFocus) { onFocused(showFocus) }
    val minimumSize = when {
        isTv -> 56.dp
        configuration.smallestScreenWidthDp < 600 -> 44.dp
        else -> 48.dp
    }
    return this
        .requiredSizeIn(minWidth = minimumSize, minHeight = minimumSize)
        .onFocusChanged {
            focused = it.isFocused

        }
        .then(
            if (showFocus) {
                Modifier
                    .zIndex(2f)
                    .then(
                        if (scaleOnFocus) Modifier.graphicsLayer {
                            scaleX = 1.10f
                            scaleY = 1.10f
                        } else Modifier
                    )
                    .border(2.5.dp, Color.White, shape)
                    .background(Color.White.copy(alpha = 0.15f), shape)
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
