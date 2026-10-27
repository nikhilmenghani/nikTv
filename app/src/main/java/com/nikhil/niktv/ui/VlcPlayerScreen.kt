package com.nikhil.niktv.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia
import java.io.File

// A replacement stream must wait for the previous native decoder to stop.
private val vlcPlaybackLifecycle = kotlinx.coroutines.sync.Mutex()

/** One bounded, session-local video still; never stored on disk or shared across titles. */
private class VlcSourceStill {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var closed = false
    var copying = false
    var menuGeneration = 0L
    // Published bitmaps can still belong to Compose's painter/render node.
    // Drop ownership instead of recycling pixels that a frame may still use.
    fun close() { closed = true; menuGeneration++; bitmap = null }
}

private fun captureVlcSourceStill(view: View?, still: VlcSourceStill, nativeFrameAvailable: Boolean,
    openChooser: () -> Unit) {
    if (still.closed) return
    val menuGeneration = ++still.menuGeneration
    if (still.copying) { openChooser(); return }
    // The bundled VLC layout names video and subtitle surfaces separately.
    val surface = (view as? VLCVideoLayout)?.findViewById<SurfaceView>(org.videolan.R.id.surface_video)
    if (!nativeFrameAvailable || surface == null || surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) {
        openChooser(); return
    }
    val scale = minOf(1.0, 1280.0 / maxOf(surface.width, surface.height),
        kotlin.math.sqrt(921_600.0 / (surface.width.toDouble() * surface.height)))
    val bitmap = try { Bitmap.createBitmap((surface.width * scale).toInt().coerceAtLeast(1),
        (surface.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { openChooser(); return }
    still.copying = true
    val handler = Handler(Looper.getMainLooper())
    var opened = false
    val open = Runnable { if (!opened && !still.closed && menuGeneration == still.menuGeneration) { opened = true; openChooser() } }
    // PixelCopy is asynchronous; a stalled surface cannot hold the source menu.
    handler.postDelayed(open, 250L)
    try {
        PixelCopy.request(surface, bitmap, { result ->
            still.copying = false
            handler.removeCallbacks(open)
            if (result == PixelCopy.SUCCESS && !still.closed && menuGeneration == still.menuGeneration) {
                still.bitmap = bitmap
            } else bitmap.recycle()
            open.run()
        }, handler)
    } catch (_: IllegalArgumentException) {
        still.copying = false; handler.removeCallbacks(open); bitmap.recycle(); open.run()
    }
}

@Composable
private fun VlcRetainedStill(bitmap: Bitmap, resizeMode: VideoResizeMode) {
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
        contentScale = when (resizeMode) {
            VideoResizeMode.FIT -> ContentScale.Fit
            VideoResizeMode.FILL, VideoResizeMode.ZOOM -> ContentScale.Crop
            VideoResizeMode.STRETCH -> ContentScale.FillBounds
        })
}

@Composable
internal fun VlcPlayerScreen(
    media: PlayingMedia,
    introSettings: IntroSkipSettings,
    initialResumePosition: Long,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onPlayQueueItem: ((MediaItem, Int) -> Unit)? = null,
    pinnedQueueIds: List<String> = emptyList(),
    queueFavoriteIds: Set<String> = emptySet(),
    onToggleQueueFavorite: ((MediaItem) -> Unit)? = null,
    onToggleQueuePin: ((MediaItem) -> Unit)? = null,
    onRefreshQueueProgramme: ((MediaItem) -> Unit)? = null,
    onVisibleQueueChannels: (List<MediaItem>) -> Unit = {},
    liveQueueCategoryTitle: String = "",
    liveQueueXtream: Boolean = false,
    onProgress: (String, Long, Long) -> Unit,
    onPlaybackIntentChanged: (Boolean) -> Unit = {},
    onChooseAlternateSource: ((Long, () -> Long) -> Unit)? = null,
    alternateSourceChooserOpen: Boolean = false,
    onVideoFrameRendered: () -> Unit = {},
    onDownload: () -> Unit = {},
    offlineDownloadPresent: Boolean = false,
    offlineDownloadInProgress: Boolean = false,
    offlineDownloadProgress: Float? = null,
    offlineDownloadProgressText: String? = null,
    initialSubtitleDelayMs: Long = 0L,
    initialExternalSubtitleFile: File? = null,
    onRetry: (Long) -> Unit,
    onManualRetry: ((Long) -> Unit)? = null,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    onReauthenticate: ((Long) -> Unit)? = null,
    reauthenticating: Boolean = false,
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
    onPlayerSwitchFocusRestored: () -> Unit = {},
    playbackDomain: PlaybackDomain = PlaybackDomain.IPTV,
    onTerminalPlaybackFailure: (() -> Unit)? = null
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
    val livePlaybackClock = remember(media.progressKey) { LivePlaybackClock() }
    var livePlaybackElapsedMillis by remember(media.progressKey) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var playing by remember(media.progressKey) { mutableStateOf(true) }
    var buffering by remember(media.progressKey) { mutableStateOf(true) }
    var position by remember(media.progressKey) { mutableLongStateOf(initialResumePosition) }
    var duration by remember(media.progressKey) { mutableLongStateOf(0L) }
    var pendingInitialResumePosition by remember(media.progressKey, media.playbackAttemptId, media.url) {
        mutableLongStateOf(playbackRestartPosition(media.catalogType, initialResumePosition))
    }
    val sourceStill = remember(media.progressKey) { VlcSourceStill() }
    val currentPlaybackAttempt by rememberUpdatedState(media.playbackAttemptId)
    DisposableEffect(sourceStill) { onDispose { sourceStill.close() } }
    var error by remember(media.progressKey, media.playbackAttemptId, media.url) { mutableStateOf<String?>(null) }
    var focusMode by remember { mutableStateOf(startFullscreen) }
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
    var authorizationRecoveryRequested by remember(media.progressKey, media.playbackAttemptId, media.url) { mutableStateOf(false) }
    var inPictureInPicture by remember { mutableStateOf(false) }
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
    var sourceChooserWasOpen by remember(media.progressKey, media.url, media.playbackAttemptId) { mutableStateOf(false) }
    val playerOptionsOpen = playerPickerVisible || pictureModePickerVisible || resizePickerVisible || pictureEditorVisible || alternateSourceChooserOpen || introSettings.editorOpen
    val currentPlayerOptionsOpen by rememberUpdatedState(playerOptionsOpen)
    var subtitleDialogOpen by remember(media.media.id) { mutableStateOf(false) }
    var subtitleTracks by remember(media.media.id) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var audioDialogOpen by remember(media.media.id) { mutableStateOf(false) }
    var audioTracks by remember(media.media.id) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedAudioTrackId by remember(media.media.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitleTrackId by remember(media.media.id) { mutableStateOf<Int?>(null) }
    var subtitleDelayMs by remember(media.media.id) { mutableLongStateOf(initialSubtitleDelayMs) }
    var externalSubtitleFile by remember(media.media.id) { mutableStateOf(initialExternalSubtitleFile) }
    var externalSubtitleEnabled by remember(media.media.id) { mutableStateOf(initialExternalSubtitleFile != null) }
    var subtitleAppearance by remember { mutableStateOf(SubtitleAppearancePreset.STANDARD) }
    val playerQueueItems = remember(media.media.id, media.episodeQueue) {
        val unique = media.episodeQueue.distinctBy { it.id }
        if (unique.any { it.id == media.media.id }) unique
        else listOf(media.media) + unique
    }
    val hasPlaybackQueue =
        playerQueueItems.size > 1 || queueHasMore || queueLoadingMore
    val currentHasPlaybackQueue by rememberUpdatedState(hasPlaybackQueue)
    val currentPlayPrevious by rememberUpdatedState(onPlayPrevious)
    val currentPlayNext by rememberUpdatedState(onPlayNext)
    val currentNextEpisode by rememberUpdatedState(media.nextEpisode)
    val currentAdvancePrevious by rememberUpdatedState<() -> Unit> {
        if (media.catalogType == CatalogType.LIVE_TV || !advancing) {
            advancing = true
            onPlayPrevious()
        }
    }
    val currentAdvanceNext by rememberUpdatedState<() -> Unit> {
        if (media.catalogType == CatalogType.LIVE_TV || !advancing) {
            advancing = true
            onPlayNext()
        }
    }
    var queueExpansionStarted by remember(media.progressKey) { mutableStateOf(false) }
    LaunchedEffect(queueLoadingMore) {
        if (queueLoadingMore) {
            queueExpansionStarted = true
        } else if (queueExpansionStarted) {
            // A provider can advertise another page and then return no items.
            // Release the input guard when that attempt finishes so Previous and
            // a subsequent retry remain available even though media did not change.
            delay(250L)
            advancing = false
            queueExpansionStarted = false
        }
    }
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
    val downloadRequester = remember(media.progressKey) { FocusRequester() }
    val recordingPauseRequester = remember(media.progressKey) { FocusRequester() }
    val subtitleRequester = remember(media.progressKey) { FocusRequester() }
    val introRequester = remember(media.progressKey) { FocusRequester() }
    val skipIntroRequester = remember(media.progressKey) { FocusRequester() }
    val audioRequester = remember(media.progressKey) { FocusRequester() }
    val pipRequester = remember(media.progressKey) { FocusRequester() }
    val playerSwitchRequester = remember(media.progressKey) { FocusRequester() }
    val sourceRequester = remember(media.progressKey) { FocusRequester() }
    val refreshRequester = remember(media.progressKey) { FocusRequester() }
    val resizeRequester = remember(media.progressKey) { FocusRequester() }
    val pictureModeRequester = remember(media.progressKey) { FocusRequester() }
    val programmeGuideRequester = remember(media.progressKey) { FocusRequester() }
    val directionalInput = playerUsesDirectionalInput()
    val pictureSettingsRequester = remember(media.progressKey) { FocusRequester() }
    val controlsTimeoutRequester = remember(media.progressKey) { FocusRequester() }
    val moreRequester = remember(media.progressKey) { FocusRequester() }
    val previousRequester = remember(media.progressKey) { FocusRequester() }
    val rewindRequester = remember(media.progressKey) { FocusRequester() }
    val playRequester = remember(media.progressKey) { FocusRequester() }
    val forwardRequester = remember(media.progressKey) { FocusRequester() }
    val nextRequester = remember(media.progressKey) { FocusRequester() }
    val playNextNowRequester = remember(media.progressKey) { FocusRequester() }
    val errorRetryRequester = remember(media.progressKey) { FocusRequester() }
    val errorPlayerRequester = remember(media.progressKey) { FocusRequester() }
    LaunchedEffect(error, media.progressKey, controlsVisible, queueVisible) {
        if (error != null && controlsVisible && !queueVisible && !currentPlayerOptionsOpen) {
            delay(120L)
            if (!currentPlayerOptionsOpen) runCatching { errorRetryRequester.requestFocus() }
        }
    }
    val progressRequester = remember(media.progressKey) { FocusRequester() }
    val videoSurfaceFocusRequester = remember(media.progressKey) { FocusRequester() }

    LaunchedEffect(alternateSourceChooserOpen) {
        if (alternateSourceChooserOpen) {
            sourceChooserWasOpen = true
        } else if (sourceChooserWasOpen) {
            sourceChooserWasOpen = false
            returnControlFocus = if (error != null) errorRetryRequester else sourceRequester
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


    LaunchedEffect(focusPlayerSwitchOnEnter) {
        if (focusPlayerSwitchOnEnter) {
            controlsVisible = true
            if (compactMobileControls) extraControlsOpen = true
            delay(120L)
            runCatching { playerSwitchRequester.requestFocus() }
            onPlayerSwitchFocusRestored()
        }
    }

    // Keep startup caching independent of HLS's sustained download buffer.
    val networkCacheMillis = if (playbackDomain == PlaybackDomain.SPORTS) com.nikhil.niktv.sports.SportsPlaybackPolicy.vlcNetworkCacheMillis else 1500
    val playbackOptions = remember(playbackDomain, media.catalogType, media.offlinePlayback, media.playbackFormat, media.url) {
        if (playbackDomain == PlaybackDomain.SPORTS) com.nikhil.niktv.sports.SportsPlaybackPolicy.vlcOptions(isHlsPlayback(media.playbackFormat, media.url))
        else vlcPlaybackOptions(media.catalogType == CatalogType.LIVE_TV, media.offlinePlayback,
            isHlsPlayback(media.playbackFormat, media.url))
    }
    // Construct JNI state asynchronously; the session still owns decoder cleanup
    // before replacement media may acquire the native playback slot.
    var playbackRequested by remember(media.progressKey, media.playbackAttemptId) { mutableStateOf(media.playWhenReady) }
    val liveRecovery = remember(media.progressKey) { LiveStreamRecovery() }
    var session by remember(playbackDomain, media.progressKey, media.url, media.playbackAttemptId, playbackOptions) {
        mutableStateOf<VlcPlaybackSession?>(null)
    }
    LaunchedEffect(playbackDomain, media.progressKey, media.url, media.playbackAttemptId, playbackOptions) {
        var created: VlcPlaybackSession? = null
        try {
            // JNI construction and module discovery must not block Compose or input dispatch.
            withContext(kotlinx.coroutines.NonCancellable + NativePlaybackWorker.dispatcher) {
                created = VlcPlaybackSession.create(context, playbackOptions)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session = created
            awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "VLC initialization failed. Retry or switch player." }
        finally { created?.close() }
    }
    val preparedSession = session
    if (preparedSession == null) {
        if (onTerminalPlaybackFailure != null) {
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
            var reported by remember(media.playbackAttemptId) { mutableStateOf(false) }
            LaunchedEffect(error, playbackRequested, lifecycleState) {
                if (!reported && error != null && playbackRequested &&
                    lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    reported = true
                    onTerminalPlaybackFailure()
                }
            }
        }
        BackHandler(onBack = onBack)
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!playbackRequested) sourceStill.bitmap?.let { VlcRetainedStill(it, resizeMode) }
            if (error == null) {
                if (playbackRequested || sourceStill.bitmap == null) CircularProgressIndicator()
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty())
                NikTvPrimaryActionButton(onClick = { (onManualRetry ?: onRetry)(position) }) { Text("Retry") }
                NikTvTextActionButton(onClick = { playerPickerVisible = true }) { Text("Choose player") }
                onReauthenticate?.let { authenticate ->
                    NikTvTextActionButton(enabled = !reauthenticating, onClick = { authenticate(position) }) {
                        Text(if (reauthenticating) "Signing in…" else "Reauthenticate")
                    }
                }
            }
        }
        if (playerPickerVisible) PlayerEngineQuickOverlay(
            selectedEngine = configuredEngine,
            onDismiss = { playerPickerVisible = false },
            onSelect = { selected ->
                playerPickerVisible = false
                onSelectPlayer(selected, position)
                if (selected.resolvePlayerEngine(context, media.series?.id ?: media.progressKey.ifBlank { media.media.id }, playbackDomain) == PlaybackEngine.VLC)
                    onRetry(position)
            }
        )
        return
    }
    val libVlc = preparedSession.lib
    val player = preparedSession.player
    val restoringInitialPosition = media.catalogType != CatalogType.LIVE_TV && pendingInitialResumePosition > 0L
    var nativeClockSequence by remember(player) { mutableLongStateOf(0L) }
    var nativeClockPosition by remember(player) { mutableLongStateOf(0L) }
    var nativeFrameAvailable by remember(player) { mutableStateOf(false) }
    var resumeSeekJob by remember(player) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var downloadedNativeTrackIds by remember(player) { mutableStateOf(emptySet<Int>()) }
    var attachedDownloadedTrackId by remember(player, externalSubtitleFile) { mutableStateOf<Int?>(null) }
    var nativeSubtitleAttached by remember(player, externalSubtitleFile) { mutableStateOf(false) }
    var nativeSubtitleBeforeIds by remember(player, externalSubtitleFile) { mutableStateOf(emptySet<Int>()) }
    val downloadedUsesOverlay = externalSubtitleFile?.extension?.let(::downloadedSubtitleUsesOverlay) != false

    var streamTerminated by remember(player) { mutableStateOf(false) }
    var streamWasPlaying by remember(player) { mutableStateOf(false) }
    var refreshPending by remember(player) { mutableStateOf(false) }
    val currentRetry by rememberUpdatedState(onRetry)
    val focus = remember(player) {
        VlcAudioFocus(context,
            play = { play -> preparedSession.command { if (play) it.play() else it.pause() } },
            volume = { gain -> preparedSession.command { it.volume = (gain * 100).toInt() } },
            intentChanged = { playbackRequested = it; onPlaybackIntentChanged(it) })
    }
    DisposableEffect(focus) { onDispose { focus.close() } }
    val completion = remember(player) { EpisodeCompletionGate() }

    fun refreshLiveStream(manual: Boolean = false) {
        if (refreshPending) return
        if (manual) liveRecovery.reset()
        refreshPending = true
        modeFeedback = "Refreshing stream..."
        if (manual) (onManualRetry ?: currentRetry)(0L) else currentRetry(0L)
    }
    fun requestPlayback(requested: Boolean) {
        focus.request(requested, apply = false)
        if (requested && media.catalogType == CatalogType.LIVE_TV &&
            (streamTerminated || error != null)) {
            refreshLiveStream(manual = true)
        } else preparedSession.command { if (focus.shouldPlay) it.play() else it.pause() }
    }
    LaunchedEffect(player, refreshPending) {
        if (refreshPending) {
            delay(10_000L)
            refreshPending = false
        }
    }
    val seekTarget = remember(player) { PlaybackSeekTarget() }
    fun seekMovieTo(target: Long) {
        val bounded = seekTarget.absolute(target, duration, android.os.SystemClock.elapsedRealtime())
        android.util.Log.i("NikTvSeek", "VLC requestedMs=$bounded actualMs=${player.time}")
        // Explicit user intent replaces startup positioning. Cancel a queued
        // resume first; this ordered native command wins over an executing one.
        resumeSeekJob?.cancel()
        resumeSeekJob = null
        pendingInitialResumePosition = 0L
        preparedSession.command { it.setTime(bounded, false) }
        position = bounded
    }
    fun skipMovie(delta: Long) {
        val base = pendingInitialResumePosition.takeIf { it > 0L } ?: player.time.coerceAtLeast(0L)
        val target = seekTarget.target(base, duration, delta,
            android.os.SystemClock.elapsedRealtime())
        seekMovieTo(target)
    }
    fun chooseAlternateSource() {
        val choose = onChooseAlternateSource ?: return
        val attempt = media.playbackAttemptId
        captureVlcSourceStill(videoView, sourceStill, nativeFrameAvailable) {
            if (currentPlaybackAttempt != attempt) return@captureVlcSourceStill
            choose((if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L)) {
                (if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L)
            }
        }
    }
    val surfacePlayer = remember { arrayOfNulls<MediaPlayer>(1) }
    fun refreshSubtitleTracks() {
        subtitleTracks = player.spuTracks.orEmpty()
            .filter { it.id >= 0 && it.id !in downloadedNativeTrackIds }
            .map { it.id to (it.name?.takeIf(String::isNotBlank) ?: "Subtitle ${it.id}") }
        selectedSubtitleTrackId = player.spuTrack.takeIf { it >= 0 }
    }
    LaunchedEffect(player, externalSubtitleFile, externalSubtitleEnabled, streamWasPlaying) {
        val file = externalSubtitleFile ?: return@LaunchedEffect
        if (!externalSubtitleEnabled || !streamWasPlaying || downloadedSubtitleUsesOverlay(file.extension)) return@LaunchedEffect
        attachedDownloadedTrackId?.let { player.setSpuTrack(it); return@LaunchedEffect }
        if (!nativeSubtitleAttached) {
            nativeSubtitleBeforeIds = player.spuTracks.orEmpty().map { it.id }.toSet()
            nativeSubtitleAttached = player.addSlave(IMedia.Slave.Type.Subtitle, android.net.Uri.fromFile(file), false)
            if (!nativeSubtitleAttached) { modeFeedback = "Could not load downloaded subtitle"; return@LaunchedEffect }
        }
        repeat(12) {
            val tracks = player.spuTracks.orEmpty().map { it.id }
            val id = attachedSubtitleTrackId(nativeSubtitleBeforeIds, tracks, player.spuTrack)
            if (id != null) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                attachedDownloadedTrackId = id
                downloadedNativeTrackIds = downloadedNativeTrackIds + id
                player.setSpuTrack(id)
                player.setSpuDelay(subtitleDelayMs * 1_000L)
                refreshSubtitleTracks()
                return@LaunchedEffect
            }
            delay(250L)
        }
    }

    LaunchedEffect(player, subtitleDelayMs) {
        player.setSpuDelay(subtitleDelayMs * 1_000L)
    }
    // VLC length/isSeekable alone can describe one short live segment, not a DVR window.
    // Live TV uses the elapsed clock and direct playback without seeking.
    val seekable = duration > 0L && media.catalogType != CatalogType.LIVE_TV
    val introSecondsRemaining = introSettings.timings.effective?.skipSecondsRemaining(position, duration)
        ?.takeIf { player.isSeekable }
    val introAvailable = introSecondsRemaining != null
    fun skipIntro() {
        val range = introSettings.timings.effective ?: return
        if (!player.isSeekable || !range.contains(player.time, player.length)) return
        seekMovieTo(range.endMillis)
        runCatching { if (controlsVisible) playRequester.requestFocus() else videoSurfaceFocusRequester.requestFocus() }
    }
    val tvBackgroundPolicy = rememberPlayerTvDevice()
    val playbackLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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

    val pipActivity = activity as? PlayerPictureInPictureHost
    val pipAvailable = remember(context) {
        pipActivity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    fun showControls() {
        controlsVisible = true
        scope.launch {
            delay(80)
            runCatching { (if (error != null) errorRetryRequester else playRequester).requestFocus() }
        }
    }
    fun refreshAudioTracks() {
        audioTracks = player.audioTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { it.id to (it.name?.takeIf(String::isNotBlank) ?: "Audio ${it.id}") }
        selectedAudioTrackId = player.audioTrack.takeIf { it >= 0 }
    }

    // The VLC AndroidView is deliberately retained while moving through a queue.
    // Its native listeners are installed only once, so route every state access
    // through updated holders instead of capturing the first episode's state.
    val currentControlsVisible by rememberUpdatedState(controlsVisible)
    val currentShowControls by rememberUpdatedState<() -> Unit> { showControls() }
    val currentSetControlsVisible by rememberUpdatedState<(Boolean) -> Unit> { controlsVisible = it }
    val currentSetControlsFocused by rememberUpdatedState<(Boolean) -> Unit> { controlsFocused = it }
    val currentRegisterInteraction by rememberUpdatedState<() -> Unit> { dpadInteraction++ }
    val currentQueueRevealProgress by rememberUpdatedState(queueRevealProgress)
    val currentSetQueueVisible by rememberUpdatedState<(Boolean) -> Unit> { queueVisible = it }
    val currentSetQueueRevealProgress by rememberUpdatedState<(Float) -> Unit> { queueRevealProgress = it }
    val currentSetQueueRevealDragging by rememberUpdatedState<(Boolean) -> Unit> { queueRevealDragging = it }
    val currentSetGestureFeedback by rememberUpdatedState<(Pair<Boolean, Float>?) -> Unit> { gestureFeedback = it }
    val currentPlaybackRequested by rememberUpdatedState(playbackRequested)
    val currentSetPlaybackRequested by rememberUpdatedState<(Boolean) -> Unit> { requestPlayback(it) }
    val currentSkipMovie by rememberUpdatedState<(Long) -> Unit> { skipMovie(it) }
    val currentSeekable by rememberUpdatedState(seekable)
    val currentCatalogType by rememberUpdatedState(media.catalogType)

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
    PauseTvPlaybackInBackground {
        requestPlayback(false)
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
    LaunchedEffect(embeddedControlsDismissRequest) {
        if (embeddedMode && embeddedControlsDismissRequest > 0) {
            controlsVisible = false
            controlsFocused = false
        }
    }
    LaunchedEffect(controlsVisible, controlsFocused, dpadInteraction, playing, controlsTimeoutSeconds, media.progressKey, queueVisible, pictureEditorVisible, playerOptionsOpen, programmeGuideOpen, moreOptionsOpen) {
        if (
            controlsVisible &&
            playing &&
            error == null &&
            !queueVisible &&
            !pictureEditorVisible &&
            !playerOptionsOpen &&
            !programmeGuideOpen &&
            !moreOptionsOpen &&
            controlsTimeoutSeconds != PLAYER_CONTROLS_TIMEOUT_INFINITE
        ) {
            delay(controlsTimeoutSeconds.coerceIn(1, 30) * 1_000L)
            controlsVisible = false
            controlsFocused = false
            if (embeddedMode) {
                suppressNextEmbeddedPlayerFocusHandoff = true
                videoView?.requestFocus()
            } else {
                runCatching { videoSurfaceFocusRequester.requestFocus() }
            }
        }
    }
    LaunchedEffect(controlsVisible, embeddedMode, media.progressKey) {
        if (
            controlsVisible &&
            !embeddedMode &&
            !playerOptionsOpen && returnControlFocus == null &&
            !focusPlayerSwitchOnEnter &&
            error == null
        ) {
            delay(80)
            if (!currentPlayerOptionsOpen && returnControlFocus == null) {
                runCatching { playRequester.requestFocus() }
            }
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
            runCatching { (if (introAvailable && directionalInput) skipIntroRequester else videoSurfaceFocusRequester).requestFocus() }
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
            // Match Media3/ExoPlayer: hide controls before leaving playback.
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
                MediaPlayer.Event.TimeChanged -> {
                    nativeClockPosition = event.timeChanged.coerceAtLeast(0L)
                    nativeClockSequence++
                    if (pendingInitialResumePosition > 0L && com.nikhil.niktv.BuildConfig.DEBUG && nativeClockSequence <= 12L)
                        android.util.Log.d("NikTvVlcResume", "clockSequence=$nativeClockSequence eventNativeMs=$nativeClockPosition")
                }
                MediaPlayer.Event.Opening -> buffering = true
                MediaPlayer.Event.Buffering -> {
                    // LibVLC emits Buffering for every percentage update, including
                    // 100%, and may emit another completed update after Playing.
                    // Treat it as a stall only while VLC is not actively playing.
                    buffering = event.buffering < 100f && !player.isPlaying
                }
                MediaPlayer.Event.Playing -> {
                    error = null
                    if (!focus.shouldPlay) preparedSession.command { it.pause() }
                    streamWasPlaying = true
                    streamTerminated = false
                    playing = focus.shouldPlay
                    buffering = false
                    refreshSubtitleTracks()
                    refreshAudioTracks()
                    if (externalSubtitleEnabled && externalSubtitleFile?.extension?.let(::downloadedSubtitleUsesOverlay) != false) {
                        player.setSpuTrack(-1)
                        selectedSubtitleTrackId = null
                    }
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> {
                    streamTerminated = true
                    playing = false
                    buffering = false
                }
                MediaPlayer.Event.EndReached -> {
                    streamTerminated = true
                    playing = false
                    val endedEarly = !streamWasPlaying || (player.length > 0L && player.time.coerceAtLeast(0L) < player.length - 2_000L)
                    if (media.catalogType != CatalogType.LIVE_TV && endedEarly) {
                        buffering = false
                        error = "VLC could not continue this source. Retry, switch player or choose another source."
                        controlsVisible = true
                        return@setEventListener
                    }
                    if (
                        media.catalogType == CatalogType.SERIES &&
                        completion.claim(true, playbackRequested, currentNextEpisode != null, autoPlayCancelled) &&
                        !advancing
                    ) {
                        advancing = true
                        subtitleDialogOpen = false
                        currentPlayNext()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    preparedSession.retireAfterFailure()
                    streamTerminated = true
                    buffering = false
                    if (media.catalogType == CatalogType.LIVE_TV && streamWasPlaying) {
                        // The watchdog shares one budget for errors, EOF and stalls.
                        // Do not treat every post-start transport error as expired authorization.
                        error = null
                    } else {
                        // LibVLC's event has no HTTP status. Do not invent an authorization failure.
                        error = "VLC could not open this media. Retry or switch player."
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
            withContext(NativePlaybackWorker.dispatcher) {
                val vlcMedia = Media(libVlc, android.net.Uri.parse(media.url)).apply {
                    // Prefer device-supported hardware decoders. With force=false,
                    // VLC retains its software decoder fallback and device exclusions.
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=$networkCacheMillis")
                    requireNotNull(vlcHeaderOptions(media.streamRequestHeaders))
                        .forEach(::addOption)
                }
                try {
                    player.media = vlcMedia
                } finally {
                    vlcMedia.release()
                }
            }
            // Check on the main thread after asynchronous media preparation.
            // Home may have been pressed while the stream was opening.
            if (!tvBackgroundPolicy || playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED) == true) focus.request(playbackRequested)
            awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            preparedSession.retireAfterFailure()
            buffering = false
            error = "VLC could not open this stream. Try another channel or retry playback."
            controlsVisible = true
            awaitCancellation()
        } finally {
            try {
            val finalTiming = preparedSession.close()
            if (media.progressKey.isNotBlank() &&
                media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES)) {
                onProgress(media.progressKey,
                    if (!streamWasPlaying) media.resumePositionMillis else if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else finalTiming.first,
                    finalTiming.second)
            }
            } finally {
                if (ownsPlaybackSlot) vlcPlaybackLifecycle.unlock()
            }
        }
    }

    LaunchedEffect(player, media.playbackAttemptId, playbackRequested) {
        if (playbackRequested) {
            delay(20_000L)
            if (!streamWasPlaying && playbackRequested && error == null) {
                buffering = false
                error = "VLC did not start this source. Retry, switch player or choose another source."
                controlsVisible = true
            }
        }
    }
    if (onTerminalPlaybackFailure != null) {
        val lifecycleState by playbackLifecycleOwner.lifecycle.currentStateFlow.collectAsState()
        var reported by remember(player, media.playbackAttemptId) { mutableStateOf(false) }
        LaunchedEffect(player, media.playbackAttemptId, error, playbackRequested, lifecycleState) {
            if (!reported && error != null && playbackRequested &&
                lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                reported = true
                onTerminalPlaybackFailure()
            }
        }
    }

    LaunchedEffect(player, media.progressKey) {
        var lastProgressSave = android.os.SystemClock.elapsedRealtime()
        val resume = VlcResumeSeekPolicy(pendingInitialResumePosition)
        var resumeTraceSamples = 0
        try {
        while (true) {
            if (media.catalogType == CatalogType.LIVE_TV) {
                livePlaybackElapsedMillis = livePlaybackClock.sample(
                    android.os.SystemClock.elapsedRealtime(), player.isPlaying
                )
                LiveTvRecorder.reportPlaybackElapsed(media.url, livePlaybackElapsedMillis)
            }
            val knownDuration = player.length.coerceAtLeast(0L)
            duration = knownDuration

            if (player.isPlaying) {
                playing = true
                buffering = false
            }

            val pendingResume = pendingInitialResumePosition

            if (pendingResume > 0L && media.catalogType != CatalogType.LIVE_TV) {
                val actual = player.time.coerceAtLeast(0L)
                val seekableNow = player.isSeekable
                val decision = resume.sample(android.os.SystemClock.elapsedRealtime(), streamWasPlaying && error == null,
                    playbackRequested && focus.shouldPlay, seekableNow, knownDuration, nativeClockPosition, nativeClockSequence)
                when (decision.kind) {
                    VlcResumeSeekPolicy.Kind.ISSUE_SEEK -> {
                        position = decision.target
                        if (com.nikhil.niktv.BuildConfig.DEBUG) android.util.Log.d("NikTvVlcResume",
                            "requestMs=${decision.target} nativeBeforeMs=$actual durationMs=$knownDuration seekable=$seekableNow")
                        resumeSeekJob = launch {
                            try {
                                val result = preparedSession.seekForResume(decision.target)
                                resume.commandCompleted(android.os.SystemClock.elapsedRealtime(), result?.result, nativeClockSequence)
                                if (com.nikhil.niktv.BuildConfig.DEBUG) android.util.Log.d("NikTvVlcResume",
                                    "commandTargetMs=${decision.target} result=${result?.result} nativeAfterMs=${result?.nativePosition}")
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { resume.commandCompleted(android.os.SystemClock.elapsedRealtime(), null, nativeClockSequence) }
                        }
                    }
                    VlcResumeSeekPolicy.Kind.PENDING -> {
                        position = decision.target
                        if (com.nikhil.niktv.BuildConfig.DEBUG && resumeTraceSamples++ < 12)
                            android.util.Log.d("NikTvVlcResume",
                                "pendingTargetMs=${decision.target} nativeMs=$actual eventNativeMs=$nativeClockPosition clockSequence=$nativeClockSequence seekable=$seekableNow requested=$playbackRequested")
                    }
                    VlcResumeSeekPolicy.Kind.ACKNOWLEDGED -> {
                        pendingInitialResumePosition = 0L
                        position = actual
                        if (com.nikhil.niktv.BuildConfig.DEBUG) android.util.Log.d("NikTvVlcResume",
                            "acknowledgedTargetMs=${decision.target} nativeMs=$actual")
                    }
                    VlcResumeSeekPolicy.Kind.UNAVAILABLE -> {
                        resumeSeekJob?.cancel()
                        pendingInitialResumePosition = 0L
                        position = actual
                        android.util.Log.w("NikTvVlcResume", "Resume unavailable; native playback continues")
                    }
                }
            } else {
                position = player.time.coerceAtLeast(0L)
                seekTarget.acknowledge(position)
            }

            if (
                media.progressKey.isNotBlank() &&
                media.catalogType in setOf(CatalogType.MOVIES, CatalogType.SERIES) &&
                pendingInitialResumePosition == 0L && error == null && player.isPlaying &&
                android.os.SystemClock.elapsedRealtime() - lastProgressSave >= 5_000L
            ) {
                onProgress(media.progressKey, position, duration)
                lastProgressSave = android.os.SystemClock.elapsedRealtime()
            }

            delay(500L)
        }
        } finally { resumeSeekJob?.cancel() }
    }

    // Recover only after successful live playback; startup/auth failures use the
    // existing error path. The budget survives player recreation for this channel.
    LaunchedEffect(player) {
        if (media.catalogType != CatalogType.LIVE_TV || media.offlinePlayback) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            val active = playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED)
            if (liveRecovery.shouldRecover(
                    android.os.SystemClock.elapsedRealtime(), player.time,
                    active && playbackRequested && focus.shouldPlay && streamWasPlaying && error == null &&
                        !authorizationRecoveryRequested && !refreshPending,
                    streamTerminated
                )) {
                android.util.Log.w("NikTvPlayback", "VLC live stream stopped/stalled; requesting fresh stream")
                streamTerminated = true
                refreshLiveStream()
            } else if (error == null && liveRecovery.unrecoverable(android.os.SystemClock.elapsedRealtime(),
                    active && playbackRequested && focus.shouldPlay && streamWasPlaying && !authorizationRecoveryRequested && !refreshPending,
                    streamTerminated)) {
                error = "The live stream stopped. Retry to refresh the stream, or choose another player."
                controlsVisible = true
            }
        }
    }

    // Observe actual video output, independently of VLC's media clock. Read native
    // statistics on a worker, retain/release the media reference, and never log URLs.
    LaunchedEffect(player) {
        if (!com.nikhil.niktv.BuildConfig.DEBUG || media.catalogType != CatalogType.LIVE_TV || media.offlinePlayback) return@LaunchedEffect
        var previous: IMedia.Stats? = null
        var sampledAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(15_000L)
            if (!playbackRequested || !streamWasPlaying || refreshPending ||
                !playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED)) {
                previous = null
                sampledAt = android.os.SystemClock.elapsedRealtime()
                continue
            }
            val stats = withContext(Dispatchers.IO) {
                runCatching {
                    val retainedMedia = player.media ?: return@runCatching null
                    try { retainedMedia.stats } finally { retainedMedia.release() }
                }.getOrNull()
            } ?: continue
            val now = android.os.SystemClock.elapsedRealtime()
            val before = previous
            if (before != null) {
                android.util.Log.i("NikTvVlcHealth",
                    "sampleMs=${now - sampledAt} frames=${stats.displayedPictures - before.displayedPictures} " +
                        "lost=${stats.lostPictures - before.lostPictures} bytes=${stats.readBytes - before.readBytes} " +
                        "positionMs=$position buffering=$buffering")
            }
            previous = stats
            sampledAt = now
        }
    }

    LaunchedEffect(player) {
        val deadline = PlaybackStartupDeadline()
        while (!streamWasPlaying && error == null) {
            val foreground = !tvBackgroundPolicy || playbackLifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED)
            if (deadline.expired(android.os.SystemClock.elapsedRealtime(),
                    foreground && playbackRequested && focus.shouldPlay, streamWasPlaying)) {
                preparedSession.retireAfterFailure()
                preparedSession.command { it.stop() }
                streamTerminated = true
                buffering = false
                error = "The stream did not start within 25 seconds. Retry or switch player."
                controlsVisible = true
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            val displayed = withContext(NativePlaybackWorker.dispatcher) {
                player.media?.let { current ->
                    try { current.stats?.displayedPictures ?: 0 } finally { current.release() }
                } ?: 0
            }
            nativeFrameAvailable = displayed > 0
            // Playing frames await the resumed clock. While paused, an actual
            // native still/keyframe may be shown without pretending the pending
            // resume was acknowledged; Play still waits for the real clock.
            if (displayed > 0 && (pendingInitialResumePosition == 0L || !playbackRequested)) { onVideoFrameRendered(); break }
            delay(250L)
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

            }
            delay(500L)
        }
    }
    val showAutoPlayPrompt =
        remainingSeconds != null && media.nextEpisode != null && !autoPlayCancelled && !introAvailable
    LaunchedEffect(showAutoPlayPrompt) {
        if (showAutoPlayPrompt) {
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
            .playerMediaKeys(playbackRequested && !streamTerminated) { requested ->
                requestPlayback(requested)
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
        // Keep one SurfaceView alive across queue changes. Destroying it before
        // VLC's decoder has fully stopped causes a large abandoned-BufferQueue
        // burst during rapid channel switching.
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                    var gestureStartY = 0f
                    var gestureStartX = 0f
                    var gestureStartValue = 0f
                    var brightnessGesture = false
                    var levelGestureEligible = false
                    var adjustingLevel = false
                    var queueGestureOwned = false
                    var queueSwipeTriggered = false
                    var tapCandidate = false
                    var controlsWereVisibleOnDown = false
                    var lastTapAtMillis = 0L
                    var lastTapOnRight = false
                    var pendingSingleTap: Runnable? = null
                    val tapSlop = 14f * layout.resources.displayMetrics.density
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
                                currentSetControlsVisible(true)

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
                                    currentShowControls()
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
                                currentRegisterInteraction()
                                controlsWereVisibleOnDown = currentControlsVisible
                                if (!controlsWereVisibleOnDown && !currentHasPlaybackQueue) {
                                    // Some high-density Samsung touch stacks report
                                    // enough pointer drift to reject ACTION_UP as a tap.
                                    // Keep immediate reveal for streams without a
                                    // queue. Queued playback waits for the double-tap
                                    // window so the overlay cannot steal tap two.
                                    currentSetControlsVisible(true)
                                }
                                gestureStartX = event.x
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
                                tapCandidate = true
                                queueGestureOwned =
                                    focusMode &&
                                        currentHasPlaybackQueue &&
                                        !pictureEditorVisible &&
                                        !playerOptionsOpen &&
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
                                if (
                                    kotlin.math.abs(event.x - gestureStartX) > tapSlop ||
                                    kotlin.math.abs(event.y - gestureStartY) > tapSlop
                                ) {
                                    tapCandidate = false
                                }
                                if (queueGestureOwned) {
                                    val progress =
                                        (deltaY / (layout.height * 0.32f))
                                            .coerceIn(0f, 1f)
                                    if (progress > 0f) {
                                        currentSetQueueVisible(true)
                                        currentSetQueueRevealDragging(true)
                                        currentSetQueueRevealProgress(progress)
                                        currentSetControlsVisible(false)
                                        currentSetControlsFocused(false)
                                        queueSwipeTriggered = true
                                    }
                                } else if (
                                    levelGestureEligible &&
                                    (adjustingLevel || kotlin.math.abs(deltaY) > 24f * layout.resources.displayMetrics.density)
                                ) {
                                    adjustingLevel = true
                                    currentSetControlsVisible(false)
                                    currentSetControlsFocused(false)
                                    val level = (gestureStartValue + deltaY / layout.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    if (brightnessGesture) {
                                        activity?.window?.attributes = activity?.window?.attributes?.apply {
                                            screenBrightness = level.coerceAtLeast(0.01f)
                                        }
                                    } else {
                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                    }
                                    currentSetGestureFeedback(brightnessGesture to level)
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (queueGestureOwned) {
                                    currentSetQueueRevealDragging(false)
                                    if (currentQueueRevealProgress >= 0.18f) {
                                        currentSetQueueRevealProgress(1f)
                                        currentSetQueueVisible(true)
                                    } else {
                                        currentSetQueueRevealProgress(0f)
                                        currentSetQueueVisible(false)
                                    }
                                    queueGestureOwned = false
                                }
                                if (!adjustingLevel && !queueSwipeTriggered && tapCandidate) {
                                    val tappedOnRight = event.x >= layout.width / 2f
                                    val doubleTap =
                                        currentHasPlaybackQueue &&
                                            tappedOnRight == lastTapOnRight &&
                                            event.eventTime - lastTapAtMillis in
                                                1..ViewConfiguration.getDoubleTapTimeout().toLong()
                                    if (doubleTap) {
                                        lastTapAtMillis = 0L
                                        pendingSingleTap?.let(layout::removeCallbacks)
                                        pendingSingleTap = null
                                        if (tappedOnRight) currentAdvanceNext()
                                        else currentAdvancePrevious()
                                    } else {
                                        lastTapAtMillis = event.eventTime
                                        lastTapOnRight = tappedOnRight
                                        if (currentHasPlaybackQueue) {
                                            pendingSingleTap?.let(layout::removeCallbacks)
                                            val targetVisible = !controlsWereVisibleOnDown
                                            val action = Runnable {
                                                currentSetControlsVisible(targetVisible)
                                                pendingSingleTap = null
                                            }
                                            pendingSingleTap = action
                                            layout.postDelayed(
                                                action,
                                                ViewConfiguration.getDoubleTapTimeout().toLong()
                                            )
                                        } else {
                                            currentSetControlsVisible(!controlsWereVisibleOnDown)
                                        }
                                    }
                                }
                                queueSwipeTriggered = false
                                tapCandidate = false
                            }
                            MotionEvent.ACTION_CANCEL -> tapCandidate = false
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
                        if (keyCode != KeyEvent.KEYCODE_BACK) currentRegisterInteraction()
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { currentShowControls(); true }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                val requested = !currentPlaybackRequested
                                currentSetPlaybackRequested(requested)
                                currentShowControls()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                currentSetPlaybackRequested(true)
                                currentShowControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                currentSetPlaybackRequested(false)
                                currentShowControls(); true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                if (
                                    currentCatalogType == CatalogType.LIVE_TV &&
                                    currentHasPlaybackQueue
                                ) {
                                    currentPlayPrevious()
                                } else {
                                    if (currentSeekable) currentSkipMovie(-10_000L)
                                    currentShowControls()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (
                                    currentCatalogType == CatalogType.LIVE_TV &&
                                    currentHasPlaybackQueue
                                ) {
                                    currentPlayNext()
                                } else {
                                    if (currentSeekable) currentSkipMovie(10_000L)
                                    currentShowControls()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> false
                            else -> { currentShowControls(); true }
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
                    surfacePlayer[0] = player
                    }
                },
                update = { layout ->
                    val previouslyAttached = surfacePlayer[0]
                    if (previouslyAttached !== player) {
                        // The previous player's coroutine stops/releases it on IO,
                        // and the playback mutex holds back the replacement stream.
                        // Detach its callbacks before attaching this retained view.
                        runCatching { previouslyAttached?.setEventListener(null) }
                        runCatching { previouslyAttached?.detachViews() }
                        runCatching { player.detachViews() }
                        player.attachViews(layout, null, true, false)
                        surfacePlayer[0] = player
                    }
                    // Apply after attachment and on every size-mode change. Fill preserves
                    // aspect ratio; Zoom adds visible magnification even for 16:9 streams.
                    player.setVideoScale(
                        when (resizeMode) {
                            VideoResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                            VideoResizeMode.FILL, VideoResizeMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                            VideoResizeMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
                        }
                    )
                    val zoom = if (resizeMode == VideoResizeMode.ZOOM) 1.25f else 1f
                    layout.scaleX = zoom
                    layout.scaleY = zoom
                    player.updateVideoSurfaces()
                },
                onRelease = { layout ->
                    // Removing the video view must not wait for a network read or
                    // native decoder shutdown. Detach callbacks here; the owning
                    // coroutine performs stop/release on IO exactly once.
                    layout.setOnTouchListener(null)
                    layout.setOnKeyListener(null)
                    val attached = surfacePlayer[0]
                    runCatching { attached?.setEventListener(null) }
                    runCatching { attached?.detachViews() }
                    surfacePlayer[0] = null
                    if (videoView === layout) videoView = null
                },
                modifier = Modifier.fillMaxSize().then(
                    if (!focusMode && !embeddedMode) Modifier.padding(
                        top = if (compactMobileControls) 58.dp else 76.dp,
                        bottom = if (compactMobileControls) 112.dp else 148.dp
                    ) else Modifier
                )
            )
        // LibVLC may render opening/keyframe packets while its precise resume
        // seek is settling. Keep them behind the startup shutter; playback and
        // demuxing continue, and controls remain available above this video layer.
        if (restoringInitialPosition && playbackRequested && error == null) Box(Modifier.fillMaxSize().background(Color.Black))
        else if (!playbackRequested && !nativeFrameAvailable) sourceStill.bitmap?.let { VlcRetainedStill(it, resizeMode) }
        DownloadedSubtitleOverlay(
            file = externalSubtitleFile,
            positionMs = position,
            delayMs = subtitleDelayMs,
            enabled = externalSubtitleEnabled && downloadedUsesOverlay,
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
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (event.key) {
                                ComposeKey.DirectionDown -> {
                                    val entered = introAvailable && runCatching { skipIntroRequester.requestFocus() }.getOrDefault(false)
                                    if (!entered) { dpadInteraction++; showControls() }
                                    true
                                }
                                ComposeKey.DirectionCenter,
                                ComposeKey.Enter,
                                ComposeKey.DirectionLeft,
                                ComposeKey.DirectionRight,
                                ComposeKey.DirectionUp -> {
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
            val primaryActionRequester = if (error != null) errorRetryRequester else playRequester
            val topDownRequester = if (seekable) progressRequester else primaryActionRequester
            val firstMediaActionRequester = if (media.catalogType == CatalogType.LIVE_TV && recordingThisChannel) {
                recordingPauseRequester
            } else {
                downloadRequester
            }

            val secondaryActionRequesters = buildList {
                if (media.catalogType != CatalogType.LIVE_TV) {
                    if (introAvailable) add(skipIntroRequester)
                    if (introSettings.target != null) add(introRequester)
                    add(subtitleRequester)
                    if (audioTracks.size > 1) add(audioRequester)
                    if (onChooseAlternateSource == null) add(downloadRequester)
                }
                if (media.catalogType == CatalogType.LIVE_TV) add(refreshRequester)
                add(resizeRequester)
                add(pictureModeRequester)
                add(playerSwitchRequester)
                if (onChooseAlternateSource != null) add(sourceRequester)
                add(controlsTimeoutRequester)
                add(moreRequester)
                if (pipAvailable) add(pipRequester)
            }
            val firstQuickActionRequester = when {
                media.catalogType == CatalogType.LIVE_TV -> programmeGuideRequester
                extraControlsOpen -> secondaryActionRequesters.first()
                else -> extraControlsRequester
            }
            val lastPlaybackActionRequester = when {
                media.nextEpisode != null -> nextRequester
                seekable -> forwardRequester
                else -> playRequester
            }
            val controlRowScrollState = rememberScrollState()
            val playbackDetailLines = buildList {
                add("${if (media.offlinePlayback) "Offline" else if (onChooseAlternateSource != null) "Alternate source" else "IPTV stream"} · ${media.playbackFormat.ifBlank { mediaFormatLabel(media.url) }}")
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
                            modifier = Modifier.playerSecondaryFocus(skipIntroRequester, secondaryActionRequesters,
                                extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onFocused = { controlsFocused = it })
                        if (introSettings.target != null) PlayerChromeIconButton(
                            icon = Icons.Default.Flag, contentDescription = "Intro timing",
                            onClick = {
                                if (seekable && player.isSeekable) introSettings.open(player.time)
                                else modeFeedback = "Intro timing needs a seekable video"
                            },
                            modifier = Modifier.playerSecondaryFocus(introRequester, secondaryActionRequesters,
                                extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                            onFocused = { controlsFocused = it })
                        if (media.catalogType == CatalogType.LIVE_TV) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh stream",
                                onClick = { refreshLiveStream(manual = true) },
                                modifier = Modifier.playerSecondaryFocus(refreshRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester, backRequester),
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
                        if (media.catalogType != CatalogType.LIVE_TV && audioTracks.size > 1) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.AudioFile,
                                contentDescription = "Audio language",
                                onClick = { refreshAudioTracks(); audioDialogOpen = true },
                                modifier = Modifier.playerSecondaryFocus(
                                    audioRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester,
                                    if (seekable) progressRequester else backRequester
                                ),
                                onFocused = { controlsFocused = it }
                            )
                        }
                        if (media.catalogType != CatalogType.LIVE_TV && onChooseAlternateSource == null) {
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
                                onClick = { playerPickerVisible = true },
                                modifier = Modifier
                                    .playerSecondaryFocus(playerSwitchRequester, secondaryActionRequesters, extraControlsRequester, lastPlaybackActionRequester, if (seekable) progressRequester else backRequester),
                                size = if (compactMobileControls) 44.dp else 48.dp,
                                selected = false,
                                onFocused = { controlsFocused = it }
                            )
                        if (onChooseAlternateSource != null) {
                            PlayerChromeIconButton(
                                icon = Icons.Default.VideoSettings,
                                contentDescription = "Source and quality",
                                onClick = ::chooseAlternateSource,
                                modifier = Modifier.playerSecondaryFocus(
                                    sourceRequester, secondaryActionRequesters,
                                    extraControlsRequester, lastPlaybackActionRequester,
                                    if (seekable) progressRequester else backRequester
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

                val liveActions: @Composable RowScope.() -> Unit = {
                    PlayerChromeIconButton(
                        icon = Icons.Default.EventNote,
                        badgeText = media.media.liveSchedule.size.takeIf { it > 0 }?.toString(),
                        contentDescription = "Programme guide",
                        onClick = { programmeGuideOpen = true },
                        modifier = Modifier
                            .focusRequester(programmeGuideRequester)
                            .playerDpadFocusRoutes(
                                left = lastPlaybackActionRequester,
                                right = if (recordingThisChannel) recordingPauseRequester else downloadRequester,
                                up = if (seekable) progressRequester else backRequester
                            ),
                        onFocused = { controlsFocused = it }
                    )
                    if (recordingThisChannel) {
                        PlayerChromeIconButton(
                            icon = if (liveRecording.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (liveRecording.paused) "Resume recording" else "Pause recording",
                            onClick = {
                                if (liveRecording.paused) LiveTvRecorder.resume(context,
                                    playbackElapsedMillis = livePlaybackClock.sample(
                                        android.os.SystemClock.elapsedRealtime(), player.isPlaying))
                                else LiveTvRecorder.pause(context,
                                    livePlaybackClock.sample(android.os.SystemClock.elapsedRealtime(), player.isPlaying))
                            },
                            modifier = Modifier
                                .focusRequester(recordingPauseRequester)
                                .playerDpadFocusRoutes(
                                    left = programmeGuideRequester,
                                    right = downloadRequester,
                                    up = backRequester
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
                            else LiveTvRecorder.start(context, media.media.title, media.url,
                                playbackElapsedMillis = livePlaybackClock.sample(
                                    android.os.SystemClock.elapsedRealtime(), player.isPlaying))
                        },
                        modifier = Modifier
                            .focusRequester(downloadRequester)
                            .playerDpadFocusRoutes(
                                left = if (recordingThisChannel) recordingPauseRequester else programmeGuideRequester,
                                right = if (extraControlsOpen) secondaryActionRequesters.first() else extraControlsRequester,
                                up = backRequester
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
                            PlaybackProgressBar(
                                mediaKey = media.progressKey,
                                active = controlsVisible || (!focusMode && !embeddedMode),
                                position = position,
                                duration = duration,
                                onSeek = {
                                    seekMovieTo(it)
                                },
                                compact = compactMobileControls,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(progressRequester)
                                    .focusProperties {
                                        up = backRequester
                                        down = primaryActionRequester
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
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    formatLivePlaybackElapsed(livePlaybackElapsedMillis),
                                    color = Color.White.copy(alpha = 0.86f),
                                    style = MaterialTheme.typography.labelMedium
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
                                        onClick = currentAdvancePrevious,
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
                                        onClick = {
                                            skipMovie(-10_000L)
                                        },
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
                                    icon = if (playbackRequested && !streamTerminated) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playbackRequested && !streamTerminated) "Pause" else "Play",
                                    onClick = {
                                        requestPlayback(streamTerminated || !playbackRequested)
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
                                        onClick = { skipMovie(10_000L) },
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
                                        onClick = currentAdvanceNext,
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

                                if (media.catalogType == CatalogType.LIVE_TV) {
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
                    if (directionalInput) scope.launch { delay(80L); runCatching { programmeGuideRequester.requestFocus() } }
                }
            }
        }
        PlayerOverlayLayer {
        if (introAvailable && !controlsVisible && (focusMode || embeddedMode) && !queueVisible &&
            !playerOptionsOpen && !programmeGuideOpen && !moreOptionsOpen && !inPictureInPicture && error == null)
            IntroSkipButton(skipIntroRequester, onSkip = ::skipIntro, onControls = ::showControls,
                onReturnToVideo = { runCatching { videoSurfaceFocusRequester.requestFocus() }; Unit },
                remainingSeconds = introSecondsRemaining ?: 0L,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp))
        if (introSettings.editorOpen) IntroTimingDialog(introSettings, duration,
            currentPosition = { player.time.coerceAtLeast(0L) }, onDismiss = {
                introSettings.close()
                returnControlFocus = introRequester
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
            selectedEngine = configuredEngine,
            onDismiss = {
                playerPickerVisible = false
                controlsVisible = true
                val origin = if (playerPickerFromError) errorPlayerRequester else playerSwitchRequester
                playerPickerFromError = false
                scope.launch { delay(80L); runCatching { origin.requestFocus() } }
            },
            onSelect = { selectedEngine ->
                val fromError = playerPickerFromError
                playerPickerFromError = false
                playerPickerVisible = false
                controlsVisible = true
                if (fromError && selectedEngine.resolvePlayerEngine(context,
                        media.series?.id ?: media.progressKey.ifBlank { media.media.id }, playbackDomain) == PlaybackEngine.VLC) {
                    onSelectPlayer(selectedEngine, (if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L))
                    onRetry((if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L))
                } else if (selectedEngine != configuredEngine.pickerChoice()) {
                    onSelectPlayer(selectedEngine, (if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L))
                } else if (fromError) {
                    onRetry((if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L))
                } else {
                    scope.launch { delay(80L); runCatching { playerSwitchRequester.requestFocus() } }
                }
            }
        )
        if (resizePickerVisible) VideoResizeQuickOverlay(
            preview = resizeMode,
            onPreview = { resizePreview = it },
            onSave = {
                VideoResizePreferences.save(context, resizeMode)
                resizePreview = null
                resizePickerVisible = false
                returnControlFocus = resizeRequester
            },
            onSkip = {
                resizePreview = null
                resizePickerVisible = false
                returnControlFocus = resizeRequester
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
                returnControlFocus = pictureModeRequester
            },
            onSkip = {
                appearancePreview = null
                pictureModePickerVisible = false
                returnControlFocus = pictureModeRequester
            }
        )

        if ((buffering || restoringInitialPosition) && playbackRequested && error == null) Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFFE50914))
            Text(if (restoringInitialPosition) "Restoring playback position…" else "Connecting to stream…", color = Color.White)
        }
        error?.takeIf { controlsVisible || (!focusMode && !embeddedMode) }?.let {
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
                    PlayerErrorActions(
                        compact = compactMobileControls,
                        canNavigate = hasPlaybackQueue,
                        onPrevious = onPlayPrevious,
                        onBack = onBack,
                        onRetry = {
                            if (media.catalogType == CatalogType.LIVE_TV) refreshLiveStream(manual = true)
                            else (onManualRetry ?: onRetry)((if (pendingInitialResumePosition > 0L) pendingInitialResumePosition
                                else player.time).coerceAtLeast(0L))
                        },
                        onNext = onPlayNext,
                        onSwitchPlayer = {
                            playerPickerFromError = true
                            playerPickerVisible = true
                        },
                        switchPlayerModifier = Modifier.focusRequester(errorPlayerRequester),
                        onReauthenticate = onReauthenticate?.let { authenticate ->
                            { authenticate((if (pendingInitialResumePosition > 0L) pendingInitialResumePosition else player.time).coerceAtLeast(0L)) }
                        },
                        reauthenticating = reauthenticating,
                        onChooseSource = onChooseAlternateSource?.let { { chooseAlternateSource() } },
                        retryModifier = Modifier.focusRequester(errorRetryRequester)
                    )
                }
            }
        }
        gestureFeedback?.let { (isBrightness, level) ->
            PlayerLevelFeedback(isBrightness, level, compactMobileControls,
                Modifier.align(if (isBrightness) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = if (compactMobileControls) 12.dp else 20.dp))
        }
        modeFeedback?.let { PlayerModeFeedback(it) }
        val countdown = remainingSeconds
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
                    NikTvTextActionButton(
                        onClick = { autoPlayCancelled = true; videoView?.requestFocus() }
                    ) { Text("Cancel") }
                    NikTvPrimaryActionButton(
                        onClick = { if (!advancing) { advancing = true; onPlayNext() } },
                        modifier = Modifier.focusRequester(playNextNowRequester)
                    ) { Text("Play now") }
                }
            }
        }
        if (subtitleDialogOpen) {
            SubtitleSelectionDialog(
                tracks = subtitleTracks.map { (id, label) ->
                    SubtitleTrackOption(id.toString(), label, !externalSubtitleEnabled && selectedSubtitleTrackId == id)
                } + if (externalSubtitleFile != null) listOf(
                    SubtitleTrackOption("downloaded", "Downloaded subtitle", externalSubtitleEnabled)
                ) else emptyList(),
                delayMs = subtitleDelayMs,
                onSelect = { id ->
                    val selection = vlcSubtitleSelection(id, externalSubtitleFile != null)
                    externalSubtitleEnabled = selection.downloaded
                    val nativeId = if (selection.downloaded && !downloadedUsesOverlay)
                        attachedDownloadedTrackId ?: -1 else selection.nativeId
                    player.setSpuTrack(nativeId)
                    selectedSubtitleTrackId = nativeId.takeIf { it >= 0 }
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
                    // The keyed effect selects the native path for styled/non-text formats.
                    // SRT/VTT uses our timed overlay; native embedded tracks stay disabled.
                    player.setSpuTrack(-1)
                    selectedSubtitleTrackId = null
                },
                downloadedSubtitleName = externalSubtitleFile?.name,
                onDeleteDownloadedSubtitle = externalSubtitleFile?.let { file ->
                    {
                        player.setSpuTrack(-1)
                        selectedSubtitleTrackId = null
                        file.delete()
                        externalSubtitleFile = null
                        externalSubtitleEnabled = false
                        refreshSubtitleTracks()
                    }
                }
            )
        }
        if (audioDialogOpen) {
            PlayerAudioTrackDialog(
                tracks = audioTracks.map { (id, label) ->
                    PlayerAudioTrackOption(id.toString(), label, selectedAudioTrackId == id)
                },
                onSelect = { id ->
                    id.toIntOrNull()?.let { trackId ->
                        if (player.setAudioTrack(trackId)) selectedAudioTrackId = trackId
                    }
                    audioDialogOpen = false
                },
                onDismiss = { audioDialogOpen = false }
            )
        }
        }
    }
}
