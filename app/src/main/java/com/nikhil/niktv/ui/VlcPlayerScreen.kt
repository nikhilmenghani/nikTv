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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.data.SubtitleSearchRequest
import com.nikhil.niktv.data.LiveTvRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia
import java.io.File

// A replacement stream must wait for the previous native decoder to stop.
private val vlcPlaybackLifecycle = kotlinx.coroutines.sync.Mutex()

@Composable
internal fun VlcPlayerScreen(
    media: PlayingMedia,
    initialResumePosition: Long,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    queueFavoriteIds: Set<String> = emptySet(),
    onToggleQueueFavorite: ((MediaItem) -> Unit)? = null,
    onProgress: (String, Long, Long) -> Unit,
    onDownload: () -> Unit = {},
    offlineDownloadPresent: Boolean = false,
    offlineDownloadInProgress: Boolean = false,
    offlineDownloadProgress: Float? = null,
    offlineDownloadProgressText: String? = null,
    initialSubtitleDelayMs: Long = 0L,
    initialExternalSubtitleFile: File? = null,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    queueHasMore: Boolean = false,
    queueLoadingMore: Boolean = false,
    onLoadMoreQueue: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    embeddedMode: Boolean = false,
    controlsTimeoutSeconds: Int = 3,
    onControlsTimeoutChanged: (Int) -> Unit = {},
    embeddedControlsDismissRequest: Int = 0,
    startFullscreen: Boolean = false,
    fullscreenOverride: Boolean? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    moreOptionsOpen: Boolean = false,
    onMoreOptionsOpenChanged: (Boolean) -> Unit = {},
    onSelectPlayer: (PlaybackEngine, Long) -> Unit,
    configuredEngine: PlaybackEngine,
    focusPlayerSwitchOnEnter: Boolean = false,
    onPlayerSwitchFocusRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val isFireTv = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
        context.isTvLikeDevice(LocalConfiguration.current)
    var extraControlsOpen by remember(media.progressKey) { mutableStateOf(false) }
    val extraControlsRequester = remember(media.progressKey) { FocusRequester() }
    val liveRecording by LiveTvRecorder.state.collectAsState()
    val recordingThisChannel = liveRecording.active && liveRecording.sourceUrl == media.url
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
    var error by remember(media.progressKey) { mutableStateOf<String?>(null) }
    var focusMode by remember { mutableStateOf(startFullscreen) }
    ApplyMobileFullscreenOrientation(focusMode)
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
    var videoView by remember(media.progressKey) { mutableStateOf<View?>(null) }
    var advancing by remember(media.progressKey) { mutableStateOf(false) }
    var remainingSeconds by remember(media.progressKey) { mutableStateOf<Int?>(null) }
    var autoPlayCancelled by remember(media.progressKey) { mutableStateOf(false) }
    var authorizationRecoveryRequested by remember(media.progressKey) { mutableStateOf(false) }
    var inPictureInPicture by remember { mutableStateOf(false) }
    var gestureFeedback by remember(media.progressKey) { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var resizeMode by remember(media.progressKey) { mutableStateOf(VideoResizeMode.FIT) }
    val (appearanceProfiles, persistedAppearanceProfile) =
        rememberVideoAppearanceProfiles(useSchedule = true)
    var appearancePreview by remember { mutableStateOf<VideoAppearanceProfile?>(null) }
    val activeAppearanceProfile =
        appearancePreview ?: persistedAppearanceProfile
    var modeFeedback by remember { mutableStateOf<String?>(null) }
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
    var subtitleDialogOpen by remember(media.media.id) { mutableStateOf(false) }
    var subtitleTracks by remember(media.media.id) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedSubtitleTrackId by remember(media.media.id) { mutableStateOf<Int?>(null) }
    var subtitleDelayMs by remember(media.media.id) { mutableLongStateOf(initialSubtitleDelayMs) }
    var externalSubtitleFile by remember(media.media.id) { mutableStateOf(initialExternalSubtitleFile) }
    var externalSubtitleAttached by remember(media.media.id) { mutableStateOf(false) }
    var externalSubtitleEnabled by remember(media.media.id) { mutableStateOf(initialExternalSubtitleFile != null) }
    var subtitleAppearance by remember { mutableStateOf(SubtitleAppearancePreset.STANDARD) }
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

    val backRequester = remember(media.progressKey) { FocusRequester() }
    val castRequester = remember(media.progressKey) { FocusRequester() }
    val downloadRequester = remember(media.progressKey) { FocusRequester() }
    val recordingPauseRequester = remember(media.progressKey) { FocusRequester() }
    val subtitleRequester = remember(media.progressKey) { FocusRequester() }
    val fullscreenRequester = remember(media.progressKey) { FocusRequester() }
    val pipRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchRequester = remember(media.progressKey) { FocusRequester() }
    val resizeRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeRequester = remember(media.progressKey) { FocusRequester() }
    val programmeGuideRequester = remember(media.progressKey) { FocusRequester() }
    val pictureSettingsRequester = remember(media.progressKey) { FocusRequester() }
    val controlsTimeoutRequester = remember(media.progressKey) { FocusRequester() }
    val moreRequester = remember(media.progressKey) { FocusRequester() }
    val previousRequester = remember(media.progressKey) { FocusRequester() }
    val rewindRequester = remember(media.progressKey) { FocusRequester() }
    val playRequester = remember(media.progressKey) { FocusRequester() }
    val forwardRequester = remember(media.progressKey) { FocusRequester() }
    val nextRequester = remember(media.progressKey) { FocusRequester() }
    val playNextNowRequester = remember(media.progressKey) { FocusRequester() }
    val progressRequester = remember(media.progressKey) { FocusRequester() }
    val videoSurfaceFocusRequester = remember(media.progressKey) { FocusRequester() }

    LaunchedEffect(focusPlayerSwitchOnEnter) {
        if (focusPlayerSwitchOnEnter) {
            controlsVisible = true
            delay(120L)
            runCatching { moreRequester.requestFocus() }
            onPlayerSwitchFocusRestored()
        }
    }

    val libVlc = remember(media.progressKey, media.url) {
        LibVLC(context, arrayListOf("--network-caching=1500", "--clock-jitter=0"))
    }
    var playbackRequested by remember(media.progressKey) { mutableStateOf(true) }
    val player = remember(libVlc) { MediaPlayer(libVlc) }
    fun refreshSubtitleTracks() {
        subtitleTracks = player.spuTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { it.id to (it.name?.takeIf(String::isNotBlank) ?: "Subtitle ${it.id}") }
        selectedSubtitleTrackId = player.spuTrack.takeIf { it >= 0 }
    }
    fun selectDownloadedSubtitleWhenReady() {
        scope.launch {
            repeat(12) { attempt ->
                if (attempt > 0) delay(250L)
                refreshSubtitleTracks()
                val downloadedTrackId = subtitleTracks.lastOrNull()?.first
                    ?: return@repeat
                if (player.setSpuTrack(downloadedTrackId)) {
                    selectedSubtitleTrackId = downloadedTrackId
                    player.setSpuDelay(subtitleDelayMs * 1_000L)
                    return@launch
                }
            }
        }
    }
    LaunchedEffect(player, subtitleDelayMs) {
        player.setSpuDelay(subtitleDelayMs * 1_000L)
    }
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
    LaunchedEffect(controlsVisible, controlsFocused, dpadInteraction, playing, controlsTimeoutSeconds, media.progressKey, queueVisible, pictureEditorVisible, programmeGuideOpen, moreOptionsOpen) {
        if (
            controlsVisible &&
            playing &&
            error == null &&
            !queueVisible &&
            !pictureEditorVisible &&
            !programmeGuideOpen &&
            !moreOptionsOpen &&
            controlsTimeoutSeconds != PLAYER_CONTROLS_TIMEOUT_INFINITE
        ) {
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

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) extraControlsOpen = false
    }
    BackHandler {
        when {
            programmeGuideOpen -> programmeGuideOpen = false
            moreOptionsOpen -> onMoreOptionsOpenChanged(false)
            queueVisible -> queueVisible = false
            pictureEditorVisible -> {
                pictureEditorVisible = false
                appearancePreview = null
            }
            extraControlsOpen -> {
                extraControlsOpen = false
                extraControlsRequester.requestFocus()
            }
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
            // Match Media3/ExoPlayer: Back dismisses controls before a second
            // Back exits direct-fullscreen playback.
            startFullscreen -> onBack()
            focusMode -> {
                focusMode = false
                onFullscreenChanged?.invoke(false)
                controlsVisible = true
            }
            else -> onBack()
        }
    }

    LaunchedEffect(player, media.url) {
        var ownsPlaybackSlot = false
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
                    playbackRequested = true
                    playing = true
                    buffering = false
                    advancing = false
                    refreshSubtitleTracks()
                    externalSubtitleFile?.takeIf { !externalSubtitleAttached && it.exists() }?.let { file ->
                        externalSubtitleAttached = player.addSlave(
                            IMedia.Slave.Type.Subtitle,
                            android.net.Uri.fromFile(file),
                            true
                        )
                        refreshSubtitleTracks()
                        player.setSpuDelay(subtitleDelayMs * 1_000L)
                        selectDownloadedSubtitleWhenReady()
                    }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    playbackRequested = false
                    playing = false
                }
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    if (
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
                MediaPlayer.Event.EncounteredError -> {
                    buffering = false
                    if (media.authorizationRetryCount == 0 && !authorizationRecoveryRequested) {
                        authorizationRecoveryRequested = true
                        error = null
                        scope.launch {
                            delay(250L)
                            onPlaybackAuthorizationFailure(player.time.coerceAtLeast(0L))
                        }
                    } else {
                        error = "VLC could not play this stream after requesting a fresh playback link."
                        controlsVisible = true
                    }
                }
            }
        }
        try {
            vlcPlaybackLifecycle.lock()
            ownsPlaybackSlot = true
            // Media construction can wait on LibVLC's native mutex. Keeping all
            // preparation on a worker prevents that mutex from freezing Compose
            // input dispatch and triggering an ANR.
            var preparedExternalSubtitle = false
            withContext(Dispatchers.IO) {
                val vlcMedia = Media(libVlc, android.net.Uri.parse(media.url)).apply {
                    // Prefer device-supported hardware decoders. With force=false,
                    // VLC retains its software decoder fallback and device exclusions.
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=1500")
                    externalSubtitleFile?.takeIf(File::exists)?.let { file ->
                        addSlave(
                            IMedia.Slave(
                                IMedia.Slave.Type.Subtitle,
                                4,
                                android.net.Uri.fromFile(file).toString()
                            )
                        )
                        preparedExternalSubtitle = true
                    }
                }
                try {
                    player.media = vlcMedia
                } finally {
                    vlcMedia.release()
                }
                player.play()
            }
            if (preparedExternalSubtitle) externalSubtitleAttached = true
            awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            buffering = false
            error = "VLC could not open this stream. Try another channel or retry playback."
            controlsVisible = true
            awaitCancellation()
        } finally {
            try {
            // VideoHelper posts surface updates to the main looper. Detaching
            // on a worker races those callbacks and can crash inside LibVLC.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                player.setEventListener(null)
                player.detachViews()
            }
            // Teardown uses the same native lock and must not run in Compose's
            // synchronous disposal path either.
            val finalTiming = withContext(NonCancellable + Dispatchers.IO) {
                val position = (
                    if (pendingInitialResumePosition > 0L) pendingInitialResumePosition
                    else player.time
                ).coerceAtLeast(0L)
                val length = player.length.coerceAtLeast(0L)
                runCatching { player.stop() }
                runCatching { player.release() }
                runCatching { libVlc.release() }
                position to length
            }
            if (media.progressKey.isNotBlank() &&
                media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES)) {
                onProgress(media.progressKey, finalTiming.first, finalTiming.second)
            }
            } finally {
                if (ownsPlaybackSlot) vlcPlaybackLifecycle.unlock()
            }
        }
    }

    LaunchedEffect(player, media.progressKey) {
        var lastProgressSave = android.os.SystemClock.elapsedRealtime()
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
                // Seek exactly once after VLC reaches Playing. Retrying this seek
                // every polling tick makes keyframe-based VOD streams replay the
                // same opening segment several times before playback can advance.
                player.time = target
                pendingInitialResumePosition = 0L
                position = target
            } else if (pendingResume > 0L) {
                position = pendingResume
            } else {
                position = player.time.coerceAtLeast(0L)
            }

            if (
                media.progressKey.isNotBlank() &&
                media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES) &&
                pendingInitialResumePosition == 0L &&
                android.os.SystemClock.elapsedRealtime() - lastProgressSave >= 5_000L
            ) {
                onProgress(media.progressKey, position, duration)
                lastProgressSave = android.os.SystemClock.elapsedRealtime()
            }

            delay(500L)
        }
    }

    LaunchedEffect(player, media.nextEpisode, autoPlayCancelled) {
        if (media.catalogType != CatalogType.SERIES || media.nextEpisode == null || autoPlayCancelled) {
            remainingSeconds = null
            return@LaunchedEffect
        }
        while (true) {
            val knownDuration = player.length.coerceAtLeast(0L)
            if (knownDuration > 0L) {
                val seconds = (((knownDuration - player.time).coerceAtLeast(0L) + 999L) / 1_000L).toInt()
                remainingSeconds = seconds.takeIf { it <= 30 }
                val remainingMillis = (knownDuration - player.time).coerceAtLeast(0L)
                if (remainingMillis <= 750L && !advancing) {
                    advancing = true
                    subtitleDialogOpen = false
                    onPlayNext()
                    return@LaunchedEffect
                }
            }
            delay(500L)
        }
    }
    LaunchedEffect(remainingSeconds, autoPlayCancelled) {
        if (remainingSeconds != null && !autoPlayCancelled) {
            delay(120L)
            runCatching { playNextNowRequester.requestFocus() }
        }
    }

    PlayerSurfaceHost(
        modifier.fillMaxSize()
            .playerQueueSwipeObserver(
                enabled = focusMode && hasPlaybackQueue && !pictureEditorVisible,
                onOpen = {
                    controlsVisible = false
                    controlsFocused = false
                    queueRevealDragging = false
                    queueRevealProgress = 1f
                    queueVisible = true
                }
            )
            .playerActivityObserver {
                dpadInteraction++
            }
            .playerMediaKeys(playbackRequested) { requested ->
                playbackRequested = requested
                if (requested) player.play() else player.pause()
                showControls()
                dpadInteraction++
            }
            .background(Color.Black)
            .then(
                if (focusMode || inPictureInPicture) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            )
    ) {
        PlayerVideoLayer {
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
                    var levelGestureEligible = false
                    var adjustingLevel = false
                    var queueGestureOwned = false
                    var queueSwipeTriggered = false
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
                                val topSystemGestureInset =
                                    48f * layout.resources.displayMetrics.density
                                if (
                                    focusMode &&
                                    event.y < topSystemGestureInset
                                ) {
                                    // Leave the fullscreen top edge to Android's
                                    // transient status/navigation-bar reveal gesture.
                                    return@setOnTouchListener false
                                }
                                dpadInteraction++
                                gestureStartY = event.y
                                // Outer quarters adjust brightness/volume; the
                                // middle half consistently reveals the queue.
                                val sideBand = layout.width * 0.25f
                                val brightnessBand = event.x <= sideBand
                                val volumeBand = event.x >= layout.width - sideBand
                                levelGestureEligible = brightnessBand || volumeBand
                                brightnessGesture = brightnessBand
                                adjustingLevel = false
                                queueSwipeTriggered = false
                                queueGestureOwned =
                                    focusMode &&
                                        hasPlaybackQueue &&
                                        !pictureEditorVisible &&
                                        if (compactMobileControls) {
                                            event.x >= layout.width * 0.25f &&
                                                event.x <= layout.width * 0.75f
                                        } else {
                                            event.y >= layout.height * 0.72f
                                        }
                                gestureStartValue = when {
                                    !levelGestureEligible -> 0f
                                    brightnessGesture -> {
                                        val configured = activity?.window?.attributes?.screenBrightness ?: -1f
                                        if (configured >= 0f) configured else {
                                            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                        }
                                    }
                                    else -> {
                                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    }
                                }
                            }
                            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                                val deltaY = gestureStartY - event.y
                                if (queueGestureOwned) {
                                    val progress =
                                        (deltaY / (layout.height * 0.32f))
                                            .coerceIn(0f, 1f)
                                    if (progress > 0f) {
                                        queueVisible = true
                                        queueRevealDragging = true
                                        queueRevealProgress = progress
                                        controlsVisible = false
                                        controlsFocused = false
                                        queueSwipeTriggered = true
                                    }
                                } else if (
                                    levelGestureEligible &&
                                    (adjustingLevel || kotlin.math.abs(deltaY) > 24f * layout.resources.displayMetrics.density)
                                ) {
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
                                if (!adjustingLevel && !queueSwipeTriggered) {
                                    controlsVisible = !controlsVisible
                                }
                                queueSwipeTriggered = false
                            }
                        }
                        true
                    }
                    layout.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.repeatCount > 0) return@setOnKeyListener true
                        if (
                            keyCode in setOf(
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_REWIND
                            ) &&
                            event.repeatCount > 0
                        ) return@setOnKeyListener true
                        if (keyCode != KeyEvent.KEYCODE_BACK) dpadInteraction++
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(); true }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                playbackRequested = !playbackRequested; if (playbackRequested) player.play() else player.pause(); showControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                playbackRequested = true
                                player.play(); showControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                playbackRequested = false
                                player.pause(); showControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                if (
                                    media.catalogType == CatalogType.LIVE_TV &&
                                    hasPlaybackQueue
                                ) {
                                    onPlayPrevious()
                                } else {
                                    if (seekable) player.time = (player.time - 10_000L).coerceAtLeast(0L)
                                    showControls()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (
                                    media.catalogType == CatalogType.LIVE_TV &&
                                    hasPlaybackQueue
                                ) {
                                    onPlayNext()
                                } else {
                                    if (seekable) player.time = (player.time + 10_000L).coerceAtMost(duration)
                                    showControls()
                                }
                                true
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
                    // LibVLC only creates its separate subtitle surface when
                    // using SurfaceView. TextureView silently drops SPU text.
                    player.attachViews(layout, null, true, false)
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
        MobileQueueSwipeHandle(
            visible =
                compactMobileControls &&
                    focusMode &&
                    hasPlaybackQueue &&
                    !queueVisible &&
                    !pictureEditorVisible &&
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
            val topDownRequester = if (seekable) progressRequester else playRequester
            val firstMediaActionRequester = if (media.catalogType == CatalogType.LIVE_TV && recordingThisChannel) {
                recordingPauseRequester
            } else {
                downloadRequester
            }

            val secondaryActionRequesters = buildList {
                if (media.catalogType == CatalogType.LIVE_TV) add(programmeGuideRequester)
                else add(subtitleRequester)
                if (media.catalogType == CatalogType.LIVE_TV && recordingThisChannel) add(recordingPauseRequester)
                add(downloadRequester)
                add(resizeRequester)
                add(pictureModeRequester)
                add(playerSwitchRequester)
                if (!isFireTv) add(castRequester)
                add(controlsTimeoutRequester)
                add(moreRequester)
                if (pipAvailable) add(pipRequester)
                if (!isFireTv) add(fullscreenRequester)
            }
            val firstQuickActionRequester = if (extraControlsOpen) secondaryActionRequesters.first() else extraControlsRequester
            val lastPlaybackActionRequester = when {
                media.nextEpisode != null -> nextRequester
                seekable -> forwardRequester
                else -> playRequester
            }
            val controlRowScrollState = rememberScrollState()
            val playbackDetailLines = buildList {
                add("${if (media.offlinePlayback) "Offline" else "IPTV stream"} · ${media.playbackFormat.ifBlank { mediaFormatLabel(media.url) }}")
                add("Player · VLC · Video fit · ${resizeMode.label}")
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
                            .focusRequester(backRequester)
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
                        PlayerDownloadStatusPill(
                            if (recordingThisChannel) LiveTvRecorder.statusText(liveRecording)
                            else offlineDownloadProgressText.orEmpty()
                        )
                    }
                }

                val quickActions: @Composable RowScope.() -> Unit = {
                        if (media.catalogType == CatalogType.LIVE_TV) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.EventNote,
                                badgeText = media.media.liveSchedule.size.takeIf { it > 0 }?.toString(),
                                contentDescription = "Programme guide",
                                onClick = { programmeGuideOpen = true },
                                modifier = Modifier.playerSecondaryFocus(programmeGuideRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                onFocused = { controlsFocused = it }
                            )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV) {
PlayerChromeIconButton(
                            icon = Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            onClick = { refreshSubtitleTracks(); subtitleDialogOpen = true },
                            modifier = Modifier
                                .playerSecondaryFocus(subtitleRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onFocused = { controlsFocused = it }
                        )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV) {
                            PlayerChromeIconButton(
                                icon = if (offlineDownloadPresent && !displayedDownloadInProgress) Icons.Default.DownloadDone else Icons.Default.DownloadForOffline,
                                contentDescription = if (offlineDownloadPresent) "Cancel or remove offline download" else "Download for offline playback",
                                onClick = {
                                    if (!offlineDownloadPresent) downloadRequested = true
                                    onDownload()
                                },
                                modifier = Modifier
                                    .playerSecondaryFocus(downloadRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                selected = offlineDownloadPresent,
                                progress = offlineDownloadProgress.takeIf { displayedDownloadInProgress },
                                indeterminateProgress = displayedDownloadInProgress && offlineDownloadProgress == null,
                                onFocused = { controlsFocused = it }
                            )
                        } else {
                            if (recordingThisChannel) {
                                PlayerChromeIconButton(
                                    icon = if (liveRecording.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (liveRecording.paused) "Resume recording" else "Pause recording",
                                    onClick = {
                                        if (liveRecording.paused) LiveTvRecorder.resume(context)
                                        else LiveTvRecorder.pause(context)
                                    },
                                    modifier = Modifier
                                        .playerSecondaryFocus(recordingPauseRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                    selected = false,
                                    onFocused = { controlsFocused = it }
                                )
                            }
                            PlayerChromeIconButton(
                                icon = if (recordingThisChannel) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                                contentDescription = if (recordingThisChannel) "Stop recording" else "Record live TV",
                                onClick = {
                                    if (liveRecording.active) LiveTvRecorder.stop(context)
                                    else LiveTvRecorder.start(context, media.media.title, media.url)
                                },
                                modifier = Modifier
                                    .playerSecondaryFocus(downloadRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                selected = false,
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
                                val nextMode = resizeMode.next()
                                resizeMode = nextMode
                                modeFeedback = "Video fit · ${nextMode.label}"
                            },
                            modifier = Modifier
                                .playerSecondaryFocus(resizeRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
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
                                .playerSecondaryFocus(pictureModeRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                                icon = Icons.Default.SmartDisplay,
                                badgeText = configuredEngine.playerChoiceBadge(),
                                contentDescription = "Player: ${configuredEngine.playerChoiceLabel()}",
                                onClick = {
                                    val selectedEngine = configuredEngine.nextPlayerChoice()
                                    modeFeedback = "Player · ${selectedEngine.playerChoiceLabel()}"
                                    onSelectPlayer(selectedEngine, player.time.coerceAtLeast(0L))
                                },
                                modifier = Modifier
                                    .playerSecondaryFocus(playerSwitchRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                size = if (compactMobileControls) 44.dp else 48.dp,
                                selected = false,
                                onFocused = { controlsFocused = it }
                            )
                        if (!isFireTv) {
                        com.nikhil.niktv.ui.components.CastButton(
                            modifier = Modifier
                                .playerSecondaryFocus(castRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onCastConnected = {
                                onSelectPlayer(PlaybackEngine.MEDIA3, player.time.coerceAtLeast(0L))
                            }
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
                            modifier = Modifier.playerSecondaryFocus(controlsTimeoutRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onFocused = { controlsFocused = it }
                        )
                        PlayerChromeIconButton(
                            icon = Icons.Default.Info,
                            contentDescription = "Playback information",
                            onClick = { onMoreOptionsOpenChanged(true) },
                            modifier = Modifier
                                .playerSecondaryFocus(moreRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
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
                            PlaybackProgressBar(
                                mediaKey = media.progressKey,
                                position = position,
                                duration = duration,
                                onSeek = { player.time = it },
                                compact = compactMobileControls,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(progressRequester)
                                    .focusProperties {
                                        up = backRequester
                                        down = playRequester
                                    }
                                    .playerControlFocus(
                                        shape = RoundedCornerShape(14.dp),
                                        scaleOnFocus = false
                                    ) { controlsFocused = it }
                            )
                        } else {
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    if (
                                        event.type == KeyEventType.KeyDown &&
                                        event.key == ComposeKey.DirectionDown &&
                                        focusMode &&
                                        hasPlaybackQueue
                                    ) {
                                        dpadInteraction++
                                        queueRevealProgress = 1f
                                        queueRevealDragging = false
                                        queueVisible = true
                                        controlsVisible = false
                                        controlsFocused = false
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
                                        onClick = { if (!advancing) { advancing = true; onPlayPrevious() } },
                                        modifier = Modifier.focusRequester(previousRequester)
                                            .focusProperties {
                                                up = if (seekable) progressRequester else backRequester
                                                right = if (seekable) rewindRequester else playRequester
                                            }
                                            .playerDpadFocusRoutes(
                                                right = if (seekable) rewindRequester else playRequester,
                                                up = if (seekable) progressRequester else backRequester
                                            ),
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                if (seekable) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.Replay10,
                                        contentDescription = "Back 10 seconds",
                                        onClick = { player.time = (player.time - 10_000L).coerceAtLeast(0L) },
                                        modifier = Modifier.focusRequester(rewindRequester)
                                            .focusProperties {
                                                up = progressRequester
                                                left = if (media.previousEpisode != null) previousRequester else FocusRequester.Default
                                                right = playRequester
                                            }
                                            .playerDpadFocusRoutes(
                                                left = if (media.previousEpisode != null) previousRequester else null,
                                                right = playRequester,
                                                up = progressRequester
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
                                        .focusRequester(playRequester)
                                        .focusProperties {
                                            up = if (seekable) progressRequester else backRequester
                                            left = when {
                                                seekable -> rewindRequester
                                                media.previousEpisode != null -> previousRequester
                                                else -> FocusRequester.Default
                                            }
                                            right = when {
                                                seekable -> forwardRequester
                                                media.nextEpisode != null -> nextRequester
                                                else -> firstQuickActionRequester
                                            }
                                        }
                                        .playerDpadFocusRoutes(
                                            left = when {
                                                seekable -> rewindRequester
                                                media.previousEpisode != null -> previousRequester
                                                else -> null
                                            },
                                            right = when {
                                                seekable -> forwardRequester
                                                media.nextEpisode != null -> nextRequester
                                                else -> firstQuickActionRequester
                                            },
                                            up = if (seekable) progressRequester else backRequester
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
                                        onClick = { player.time = (player.time + 10_000L).coerceAtMost(duration) },
                                        modifier = Modifier.focusRequester(forwardRequester)
                                            .focusProperties {
                                                up = progressRequester
                                                left = playRequester
                                                right = if (media.nextEpisode != null) nextRequester else firstQuickActionRequester
                                            }
                                            .playerDpadFocusRoutes(
                                                left = playRequester,
                                                right = if (media.nextEpisode != null) nextRequester else firstQuickActionRequester,
                                                up = progressRequester
                                            ),
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                if (media.nextEpisode != null) {
                                    PlayerChromeIconButton(
                                        icon = Icons.Default.SkipNext,
                                        contentDescription = "Next",
                                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                                        modifier = Modifier.focusRequester(nextRequester)
                                            .focusProperties {
                                                up = if (seekable) progressRequester else backRequester
                                                left = if (seekable) forwardRequester else playRequester
                                                right = firstQuickActionRequester
                                            }
                                            .playerDpadFocusRoutes(
                                                left = if (seekable) forwardRequester else playRequester,
                                                right = firstQuickActionRequester,
                                                up = if (seekable) progressRequester else backRequester
                                            ),
                                        size = if (compactMobileControls) 44.dp else 48.dp,
                                        onFocused = { controlsFocused = it }
                                    )
                                }
                                }

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                PlayerExtraControls(visible = extraControlsOpen) {
                                quickActions()
                                if (pipAvailable) {
                                val pipLeftRequester = moreRequester
                                PlayerChromeIconButton(
                                    icon = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in Picture",
                                    onClick = {
                                        controlsVisible = false
                                        controlsFocused = false
                                        pipActivity?.enterPlayerPictureInPicture()
                                    },
                                    modifier = Modifier
                                        .playerSecondaryFocus(pipRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                    size = if (compactMobileControls) 44.dp else 48.dp,
                                    onFocused = { controlsFocused = it }
                                )
                            }

                            if (!isFireTv) {
PlayerChromeIconButton(
                                icon = if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (focusMode) "Exit fullscreen" else "Fullscreen",
                                onClick = {
                                    val entering = !focusMode
                                    if (startFullscreen && !entering) {
                                        onBack()
                                    } else {
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
                                modifier = Modifier
                                    .playerSecondaryFocus(fullscreenRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                size = utilityButtonSize,
                                selected = false,
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
                                            up = if (seekable) progressRequester else backRequester
                                        }
                                        .playerDpadFocusRoutes(
                                            left = if (extraControlsOpen) secondaryActionRequesters.last() else lastPlaybackActionRequester,
                                            right = FocusRequester.Cancel,
                                            up = if (seekable) progressRequester else backRequester
                                        ),
                                    onFocused = { controlsFocused = it }
                                )
                        }
                    }
                }
            }
            if (moreOptionsOpen) {
                PlayerMoreOptionsDialog(
                    detailLines = playbackDetailLines,
                    onDismiss = {
                        onMoreOptionsOpenChanged(false)
                        scope.launch {
                            delay(80L)
                            runCatching { moreRequester.requestFocus() }
                        }
                    }
                )
            }
            if (programmeGuideOpen) {
                PlayerLiveScheduleOverlay(media.media) {
                    programmeGuideOpen = false
                    scope.launch { delay(80L); runCatching { programmeGuideRequester.requestFocus() } }
                }
            }
        }
        PlayerOverlayLayer {
        if (queueVisible && focusMode && !pictureEditorVisible) PlayerQueueOverlay(
            items = playerQueueItems,
            playingId = media.media.id,
            favoriteIds = queueFavoriteIds,
            onToggleFavorite = if (media.catalogType == CatalogType.LIVE_TV) {
                onToggleQueueFavorite
            } else {
                null
            },
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
            onDismiss = {
                pictureEditorVisible = false
                pictureModePickerVisible = true
            },
            onPreview = { appearancePreview = it },
            onSelected = {
                VideoAppearancePreferences.setActive(context, it)
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
                showControls()
            },
            onSkip = {
                appearancePreview = null
                pictureModePickerVisible = false
                showControls()
            }
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
                    NikTvPrimaryActionButton(onClick = onBack) { Text("Go back") }
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
        val countdown = remainingSeconds
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
                    NikTvTextActionButton(onClick = { autoPlayCancelled = true; videoView?.requestFocus() }) { Text("Cancel") }
                    NikTvPrimaryActionButton(
                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                        modifier = Modifier.focusRequester(playNextNowRequester).playerControlFocus(RoundedCornerShape(24.dp)) { controlsFocused = it }
                    ) { Text("Play now") }
                }
            }
        }
        if (subtitleDialogOpen) {
            SubtitleSelectionDialog(
                tracks = subtitleTracks.map { (id, label) ->
                    SubtitleTrackOption(id.toString(), label, selectedSubtitleTrackId == id)
                },
                delayMs = subtitleDelayMs,
                onSelect = { id ->
                    val trackId = id?.toIntOrNull() ?: -1
                    player.setSpuTrack(trackId)
                    selectedSubtitleTrackId = trackId.takeIf { it >= 0 }
                    externalSubtitleEnabled = id != null
                },
                onDelayChange = { delay ->
                    subtitleDelayMs = delay
                    player.setSpuDelay(delay * 1_000L)
                },
                onDismiss = { subtitleDialogOpen = false },
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
                    player.addSlave(IMedia.Slave.Type.Subtitle, android.net.Uri.fromFile(file), true)
                    externalSubtitleAttached = true
                    refreshSubtitleTracks()
                    player.setSpuDelay(subtitleDelayMs * 1_000L)
                    selectDownloadedSubtitleWhenReady()
                },
                downloadedSubtitleName = externalSubtitleFile?.name,
                onDeleteDownloadedSubtitle = externalSubtitleFile?.let { file ->
                    {
                        player.setSpuTrack(-1)
                        selectedSubtitleTrackId = null
                        file.delete()
                        externalSubtitleFile = null
                        externalSubtitleAttached = false
                        externalSubtitleEnabled = false
                        refreshSubtitleTracks()
                    }
                }
            )
        }
        }
    }
}
