package com.nikhil.niktv.ui

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** MPV backend for NikTV's existing Media3-based chrome, queue and remote controls. */
@RequiresApi(26)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MpvPlayerAdapter(
    context: Context,
    private val media: PlayingMedia,
    startPosition: Long,
    @androidx.annotation.VisibleForTesting private val liveStartPosition: String = "0"
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val released = AtomicBoolean()
    private val live = media.catalogType == CatalogType.LIVE_TV
    // Native handle and all JNI commands belong to NativePlaybackWorker.
    private var mpv: MPVLib? = null
    private var nativeHasFile = false
    private var pendingPreparePosition: Long? = null
    @Volatile private var replacementEndExpected = false
    @Volatile private var eofReached = false
    @Volatile private var attachedSurface: Surface? = null
    private var nativeSurface: Surface? = null
    @Volatile private var surfaceSize: String? = null
    private var holder: SurfaceHolder? = null
    private var videoOutput: Any? = null
    @Volatile private var requested = false
    @Volatile private var selectedItem = MediaItem.fromUri(media.url)
    private var state = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var loaded = false
    private var firstFrame = false
    private var position = startPosition.coerceAtLeast(0L)
    private var duration = C.TIME_UNSET
    private var buffered = position
    private var tracks = Tracks.EMPTY
    private var size = VideoSize.UNKNOWN
    private var parameters = PlaybackParameters.DEFAULT
    private var selection = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private var volumeValue = 1f
    private var repeat = Player.REPEAT_MODE_OFF
    @Volatile private var lastNativeFailure: String? = null
    private val nativeLogObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (!released.get() && level <= 30 && text.isNotBlank()) {
                val failure = nativePlaybackFailure("$prefix: $text")
                // A trailing generic demux failure must not erase the useful HTTP/codec cause.
                if (lastNativeFailure == null || failure.kind != NativeFailureKind.UNKNOWN)
                    lastNativeFailure = failure.message
            }
        }
    }
    private var subtitleDelayMillis = 0L
    private var videoSizing = VideoResizeMode.FIT
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusHeld = false
    private var focusSuppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var ducked = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusSuppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    ducked = false
                    enqueue { it.setPropertyBoolean("pause", !requested); it.setPropertyDouble("volume", volumeValue * 100.0) }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    enqueue { it.setPropertyDouble("volume", volumeValue * 20.0) }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusSuppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    enqueue { it.setPropertyBoolean("pause", true) }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    requested = false
                    abandonFocus()
                    enqueue { it.setPropertyBoolean("pause", true) }
                }
            }
            publish()
        }, main).build()

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !released.get()) playWhenReady = false
        }
    }
    init {
        androidx.core.content.ContextCompat.registerReceiver(appContext, noisyReceiver,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(value: SurfaceHolder) = attach(value.surface)
        override fun surfaceChanged(value: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val dimensions = "${width}x$height"
            if (surfaceSize == dimensions) return
            surfaceSize = dimensions
            enqueue { it.setPropertyString("android-surface-size", dimensions) }
        }
        override fun surfaceDestroyed(value: SurfaceHolder) {
            attachedSurface = null
            surfaceSize = null
            enqueue { detach(it) }
        }
    }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            if (property == "track-list") enqueue { refreshTracks(it) }
        }
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) {
            main.post {
                if (released.get()) return@post
                when (property) {
                    "time-pos" -> position = (value * 1000).toLong().coerceAtLeast(0L)
                    "duration" -> if (!live) duration = (value * 1000).toLong().coerceAtLeast(0L)
                    "demuxer-cache-time" -> buffered = (value * 1000).toLong().coerceAtLeast(position)
                }
                if (property == "duration") publish()
            }
        }
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "eof-reached" && !replacementEndExpected) {
                eofReached = value
                if (value) main.post { if (!released.get()) { state = Player.STATE_ENDED; publish() } }
            }
            if (property == "paused-for-cache") main.post {
                if (!released.get() && loaded) {
                    state = if (value) Player.STATE_BUFFERING else Player.STATE_READY
                    publish()
                }
            }
        }
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> { eofReached = false; lastNativeFailure = null }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> enqueue { engine ->
                    refreshTracks(engine)
                    val length = engine.getPropertyDouble("duration")
                    main.post {
                        if (!released.get()) {
                            loaded = true
                            error = null
                            if (!live && length != null) duration = (length * 1000).toLong()
                            state = Player.STATE_READY
                            publish()
                        }
                    }
                }
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> enqueue { engine ->
                    val width = engine.getPropertyInt("video-params/w") ?: 0
                    val height = engine.getPropertyInt("video-params/h") ?: 0
                    main.post { if (!released.get()) { size = VideoSize(width, height); publish() } }
                }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post {
                    if (!released.get()) { firstFrame = true; error = null; state = Player.STATE_READY; publish() }
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> enqueue { engine ->
                    if (replacementEndExpected) {
                        replacementEndExpected = false
                        return@enqueue
                    }
                    nativeHasFile = false
                    val ended = eofReached || engine.getPropertyBoolean("eof-reached") == true
                    main.post {
                        if (!released.get() && state != Player.STATE_IDLE) {
                            if (ended) state = Player.STATE_ENDED
                            else {
                                val failure = nativePlaybackFailure(lastNativeFailure)
                                error = PlaybackException(failure.message, failure, failure.playbackCode())
                                state = Player.STATE_IDLE
                            }
                            publish()
                        }
                    }
                }
            }
        }
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_RELEASE, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_GET_AUDIO_ATTRIBUTES
        ).apply {
            if (!live) addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        }.build()
        val item = MediaItemData.Builder("mpv-stream").setMediaItem(selectedItem)
            .setTracks(tracks).setIsSeekable(!live).setIsDynamic(live)
            .setDurationUs(if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1000)
            .apply { if (live) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build()) }
            .build()
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
            .setPlaybackState(state).setPlayerError(error)
            .setPlayWhenReady(requested, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(focusSuppression)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setContentPositionMs(PositionSupplier { position })
            .setContentBufferedPositionMs(PositionSupplier { buffered.coerceAtLeast(position) })
            .setTotalBufferedDurationMs(PositionSupplier { (buffered - position).coerceAtLeast(0L) })
            .setVideoSize(size).setNewlyRenderedFirstFrame(firstFrame)
            .setTrackSelectionParameters(selection).setPlaybackParameters(parameters)
            .setVolume(volumeValue).setRepeatMode(repeat)
            .setSeekBackIncrementMs(10_000L).setSeekForwardIncrementMs(10_000L).build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        error = null
        loaded = false
        firstFrame = false
        state = Player.STATE_BUFFERING
        updateAudioFocus()
        val resumePosition = position
        return enqueueNative {
            val engine = mpv ?: (MPVLib.create(appContext) ?: throw IllegalStateException("MPV initialization failed")).also {
                mpv = it
                mpvPlaybackOptions(live).forEach { (key, value) ->
                    check(it.setOptionString(key, value) >= 0) { "Unsupported MPV option: $key" }
                }
                check(it.setOptionString("tls-ca-file", mpvTlsCertificateFile(appContext).path) >= 0)
                media.streamRequestHeaders.entries.firstOrNull { e -> e.key.equals("User-Agent", true) }
                    ?.value?.let { agent -> it.setOptionString("user-agent", agent) }
                val headers = media.streamRequestHeaders.filterKeys { key -> !key.equals("User-Agent", true) }
                    .entries.joinToString(",") { entry ->
                        (entry.key + ": " + entry.value).replace("\\", "\\\\").replace(",", "\\,")
                    }
                if (headers.isNotEmpty()) it.setOptionString("http-header-fields", headers)
                it.addObserver(observer)
                it.addLogObserver(nativeLogObserver)
                it.init()
                it.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                it.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                it.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                it.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                it.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                it.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
                android.util.Log.i("NikTvMpv", "Native player initialized; demuxer buffer limit=${it.getPropertyInt("options/demuxer-max-bytes")} bytes")
                startHealthSamples()
            }
            pendingPreparePosition = resumePosition
            attachedSurface?.takeIf(Surface::isValid)?.let { attachNative(engine, it) }
            loadWhenSurfaceReady(engine)
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        requested = playWhenReady
        updateAudioFocus()
        return enqueue { it.setPropertyBoolean("pause", !playWhenReady || focusSuppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE) }
    }
    override fun handleStop(): ListenableFuture<*> {
        state = Player.STATE_IDLE
        loaded = false
        abandonFocus()
        return enqueue { pendingPreparePosition = null; nativeHasFile = false; it.command(arrayOf("stop")) }
    }
    override fun handleRelease(): ListenableFuture<*> {
        released.set(true)
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        holder?.removeCallback(surfaceCallback)
        holder = null
        abandonFocus()
        return enqueueNative(allowReleased = true) {
            pendingPreparePosition = null
            mpv?.let {
                it.removeObserver(observer)
                it.removeLogObserver(nativeLogObserver)
                runCatching { it.command(arrayOf("stop")) }
                runCatching { it.detachSurface() }
                it.destroy()
            }
            mpv = null
            android.util.Log.i("NikTvMpv", "Native player released on worker")
        }
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (live) return Futures.immediateVoidFuture()
        position = positionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        return enqueue { it.command(arrayOf("seek", (position / 1000.0).toString(), "absolute+exact")) }
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        require(mediaItems.size == 1) { "NikTV owns the playback queue" }
        selectedItem = mediaItems.first()
        position = startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        state = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        volumeValue = volume
        return enqueue { it.setPropertyDouble("volume", volume * if (ducked) 20.0 else 100.0) }
    }
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters
        return enqueue { it.setPropertyDouble("speed", playbackParameters.speed.toDouble()) }
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return enqueue { it.setPropertyString("loop-file", if (repeatMode == Player.REPEAT_MODE_ONE) "inf" else "no") }
    }
    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        selection = trackSelectionParameters
        return enqueue { engine ->
            listOf(C.TRACK_TYPE_AUDIO to "aid", C.TRACK_TYPE_TEXT to "sid").forEach { (type, property) ->
                val override = trackSelectionParameters.overrides.values.firstOrNull { it.type == type }
                val selected = when {
                    trackSelectionParameters.disabledTrackTypes.contains(type) -> "no"
                    override != null && override.trackIndices.isNotEmpty() -> override.mediaTrackGroup.id.substringAfterLast(':')
                    else -> "auto"
                }
                engine.setPropertyString(property, selected)
            }
            refreshTracks(engine)
        }
    }
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        this.videoOutput = videoOutput
        holder?.removeCallback(surfaceCallback)
        holder = when (videoOutput) {
            is SurfaceView -> videoOutput.holder
            is SurfaceHolder -> videoOutput
            else -> null
        }
        holder?.addCallback(surfaceCallback)
        val surface = if (videoOutput is Surface) videoOutput else holder?.surface
        if (surface != null && surface.isValid) attach(surface)
        return Futures.immediateVoidFuture()
    }
    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput != null && videoOutput !== this.videoOutput) return Futures.immediateVoidFuture()
        holder?.removeCallback(surfaceCallback)
        holder = null
        this.videoOutput = null
        attachedSurface = null
        surfaceSize = null
        return enqueue { detach(it) }
    }

    fun setSubtitleDelay(millis: Long) {
        subtitleDelayMillis = millis
        enqueue { it.setPropertyDouble("sub-delay", millis / 1000.0) }
    }
    fun setVideoSizing(mode: VideoResizeMode) {
        if (videoSizing == mode) return
        videoSizing = mode
        // Crop/zoom inside the existing GPU output. Resizing its Android buffer
        // queue instead can stall rendering and leave audio running ahead.
        enqueue { engine ->
            mpvVideoSizing(mode).forEach { (key, value) -> engine.setPropertyString(key, value) }
        }
    }
    fun setSubtitleAppearance(preset: SubtitleAppearancePreset) {
        enqueue {
            it.setPropertyDouble("sub-font-size", preset.textSizeSp.toDouble())
            it.setPropertyString("sub-color", if (preset == SubtitleAppearancePreset.HIGH_CONTRAST) "#FFFF00" else "#FFFFFF")
            it.setPropertyDouble("sub-pos", if (preset == SubtitleAppearancePreset.HIGH) 86.0 else 96.0)
            it.setPropertyString("sub-border-style", "opaque-box")
            it.setPropertyString("sub-back-color", "#C0000000")
        }
    }
    private fun attach(surface: Surface) {
        attachedSurface = surface
        enqueue { if (attachedSurface === surface && surface.isValid) attachNative(it, surface) }
    }
    private fun attachNative(engine: MPVLib, surface: Surface) {
        if (nativeSurface === surface) return
        detach(engine)
        engine.attachSurface(surface)
        nativeSurface = surface
        surfaceSize?.let { engine.setPropertyString("android-surface-size", it) }
        engine.setPropertyString("vo", "gpu")
        engine.setPropertyString("force-window", "yes")
        android.util.Log.i("NikTvMpv", "Video surface attached")
        loadWhenSurfaceReady(engine)
    }
    private fun loadWhenSurfaceReady(engine: MPVLib) {
        val resumePosition = pendingPreparePosition ?: return
        if (nativeSurface?.isValid != true) return
        pendingPreparePosition = null
        mpvVideoSizing(videoSizing).forEach { (key, value) -> engine.setPropertyString(key, value) }
        engine.setPropertyBoolean("pause", !requested || focusSuppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        engine.setPropertyString("start", if (live) liveStartPosition else (resumePosition / 1000.0).toString())
        replacementEndExpected = nativeHasFile
        nativeHasFile = true
        engine.command(arrayOf("loadfile", selectedItem.localConfiguration!!.uri.toString(), "replace"))
        selectedItem.localConfiguration?.subtitleConfigurations?.firstOrNull()?.uri?.toString()
            ?.let { engine.command(arrayOf("sub-add", it, "select")) }
        engine.setPropertyDouble("sub-delay", subtitleDelayMillis / 1000.0)
        android.util.Log.i("NikTvMpv", "Stream opened after video surface became ready")
    }
    private fun detach(engine: MPVLib) {
        if (nativeSurface == null) return
        engine.setPropertyString("vo", "null")
        engine.detachSurface()
        nativeSurface = null
    }
    private fun startHealthSamples() {
        NativePlaybackWorker.scope.launch {
            while (!released.get()) {
                delay(15_000L)
                if (released.get()) break
                val engine = mpv ?: continue
                if (!requested) continue
                // Observe decoder/output health without probing the provider.
                android.util.Log.i("NikTvMpv",
                    "health positionSecs=${engine.getPropertyDouble("time-pos")} " +
                        "cacheSecs=${engine.getPropertyDouble("demuxer-cache-duration")} " +
                        "dropped=${engine.getPropertyInt("frame-drop-count")} " +
                        "avSyncSecs=${engine.getPropertyDouble("avsync")} " +
                        "syncCorrectionSecs=${engine.getPropertyDouble("total-avsync-change")} " +
                        "audioOutput=${engine.getPropertyString("current-ao")} " +
                        "cachePaused=${engine.getPropertyBoolean("paused-for-cache")}")
            }
        }
    }
    private fun refreshTracks(engine: MPVLib) {
        val groups = (0 until (engine.getPropertyInt("track-list/count") ?: 0)).mapNotNull { index ->
            val prefix = "track-list/$index/"
            val kind = engine.getPropertyString(prefix + "type") ?: return@mapNotNull null
            val id = engine.getPropertyInt(prefix + "id") ?: return@mapNotNull null
            val mime = when (kind) {
                "audio" -> MimeTypes.AUDIO_RAW
                "sub" -> MimeTypes.APPLICATION_SUBRIP
                "video" -> MimeTypes.VIDEO_H264
                else -> return@mapNotNull null
            }
            val format = Format.Builder().setId("mpv:$kind:$id").setSampleMimeType(mime)
                .setLanguage(engine.getPropertyString(prefix + "lang"))
                .setLabel(engine.getPropertyString(prefix + "title"))
                .build()
            Tracks.Group(TrackGroup("mpv:$kind:$id", format), false,
                intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(engine.getPropertyBoolean(prefix + "selected") == true))
        }
        main.post { if (!released.get()) { tracks = Tracks(groups); publish() } }
    }
    private fun updateAudioFocus() {
        if (requested && !released.get()) {
            if (!focusHeld) focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusSuppression = if (focusHeld) Player.PLAYBACK_SUPPRESSION_REASON_NONE
                else Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        } else abandonFocus()
    }
    private fun abandonFocus() {
        if (focusHeld) audioManager.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
        focusSuppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }
    private fun publish() {
        if (!released.get()) invalidateState()
        firstFrame = false
    }
    @androidx.annotation.VisibleForTesting
    internal suspend fun diagnosticFrameDrops(): Int? = kotlinx.coroutines.withContext(NativePlaybackWorker.dispatcher) {
        if (released.get()) null else mpv?.getPropertyInt("frame-drop-count")
    }

    private fun enqueue(action: (MPVLib) -> Unit): ListenableFuture<Void> = enqueueNative { mpv?.let(action) }
    private fun enqueueNative(allowReleased: Boolean = false, action: () -> Unit): ListenableFuture<Void> {
        val future = SettableFuture.create<Void>()
        NativePlaybackWorker.scope.launch {
            try {
                if (allowReleased || !released.get()) action()
                future.set(null)
            } catch (failure: Throwable) {
                // Never include native URL/header text in logs or the UI.
                android.util.Log.w("NikTvMpv", "Native operation failed: ${failure.javaClass.simpleName}")
                if (failure is UnsatisfiedLinkError) android.util.Log.w("NikTvMpv", "Native loader: ${failure.message}")
                main.post {
                    if (!released.get()) {
                        val native = nativePlaybackFailure(failure.message)
                        error = PlaybackException(native.message, native, native.playbackCode())
                        state = Player.STATE_IDLE
                        publish()
                    }
                }
                // Expose failure through Player events, not an uncaught command future.
                future.set(null)
            }
        }
        return future
    }
}
