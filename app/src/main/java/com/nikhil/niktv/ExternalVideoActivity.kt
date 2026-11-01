package com.nikhil.niktv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Direct player entry: no portal login, catalogue lookup, download or profile mutation. */
class ExternalVideoActivity : ComponentActivity(), PlayerPictureInPictureHost {
    private val model by viewModels<ExternalVideoViewModel>()
    private var playerActive = false
    override var pipModeListener: ((Boolean) -> Unit)? = null
    private val pipSupported get() = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private fun pipParams() = if (Build.VERSION.SDK_INT >= 26) PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9)).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(playerActive); setSeamlessResizeEnabled(true) }
        }.build() else null
    override fun setPlayerActiveForPip(active: Boolean) {
        playerActive = active
        if (pipSupported) pipParams()?.let(::setPictureInPictureParams)
    }
    override fun enterPlayerPictureInPicture(): Boolean = if (Build.VERSION.SDK_INT < 26 || !pipSupported || !playerActive) false
        else pipParams()?.let { runCatching { enterPictureInPictureMode(it) }.getOrDefault(false) } ?: false
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26 until 31 && playerActive) enterPlayerPictureInPicture()
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(inPip, config); pipModeListener?.invoke(inPip)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.open(intent, restore = savedInstanceState != null)
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme(colorScheme = NikColors) {
                Surface(Modifier.fillMaxSize(), color = NikColors.background) {
                    val media = state.media
                    when {
                        media != null -> PlayerScreen(media, ::finish,
                            { model.retry(it, media.playbackAttemptId) }, { model.retryDecoder(it, media.playbackAttemptId) },
                            { model.retry(it, media.playbackAttemptId) },
                            {}, {}, { key, at, duration -> model.progress(media.playbackAttemptId, key, at, duration) }, playbackEngine = state.engine,
                            onPlaybackEngineChanged = model::engine, controlsTimeoutSeconds = state.controlsTimeout,
                            onControlsTimeoutChanged = model::controlsTimeout,
                            onPlaybackIntentChanged = { model.playbackIntent(media.playbackAttemptId, it) }, startFullscreen = true)
                        state.error != null -> AlertDialog(onDismissRequest = ::finish,
                            title = { Text("Could not open video") }, text = { Text(state.error!!) },
                            confirmButton = { NikTvActionButton("Close", onClick = ::finish) })
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); model.open(intent)
    }
    override fun onStart() {
        super.onStart()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onStop() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}

internal data class ExternalVideoState(val media: PlayingMedia? = null, val error: String? = null,
    val engine: PlaybackEngine = PlaybackEngine.AUTO, val controlsTimeout: Int = 3)

internal class ExternalVideoViewModel(app: android.app.Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val mutable = MutableStateFlow(ExternalVideoState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var generation = 0
    private var request: ExternalVideoRequest? = null
    private var position = saved.get<Long>("position") ?: 0L
    init {
        viewModelScope.launch { store.playbackEngine.collect { value -> mutable.update { it.copy(engine = value) } } }
        viewModelScope.launch { store.playerControlsTimeoutSeconds.collect { value -> mutable.update { it.copy(controlsTimeout = value) } } }
    }
    fun open(intent: Intent, restore: Boolean = false) {
        if (restore && (request != null || loading?.isActive == true)) return
        loading?.cancel()
        val token = ++generation
        mutable.update { it.copy(media = null, error = null) }
        loading = viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { ExternalVideoRequest.read(intent, getApplication<android.app.Application>().contentResolver) }
                val preferredEngine = store.playbackEngine.first()
                val timeout = store.playerControlsTimeoutSeconds.first()
                if (token != generation) return@launch
                val resume = if (restore && saved.get<String>("uri") == selected.uri.toString()) position else 0L
                position = resume; request = selected; saved["uri"] = selected.uri.toString(); saved["position"] = resume
                mutable.update { it.copy(engine = preferredEngine, controlsTimeout = timeout,
                    media = selected.playing(resume, if (restore) saved["playing"] ?: true else true)) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (token == generation) mutable.update { it.copy(error = if (error is SecurityException)
                    "Access to this file was not granted. Open it again from your file manager."
                    else if (error is IllegalArgumentException) error.message else "The selected video could not be read. Open it again from your file manager.") }
            }
        }
    }
    fun retry(at: Long, attempt: String) {
        if (mutable.value.media?.playbackAttemptId != attempt) return
        mutable.update { it.copy(media = request?.playing(at, it.media?.playWhenReady ?: true), error = null) }
    }
    fun retryDecoder(at: Long, attempt: String) {
        if (mutable.value.media?.playbackAttemptId != attempt) return
        mutable.update { state -> state.copy(media = state.media?.copy(
        resumePositionMillis = at.coerceAtLeast(0), playbackAttemptId = java.util.UUID.randomUUID().toString(),
        decoderRecoveryCount = (state.media.decoderRecoveryCount + 1).coerceAtMost(3))) }
    }
    fun progress(attempt: String, key: String, at: Long, duration: Long) {
        if (mutable.value.media?.playbackAttemptId != attempt || mutable.value.media?.progressKey != key) return
        position = at.coerceAtLeast(0); saved["position"] = position
    }
    fun playbackIntent(attempt: String, play: Boolean) {
        if (mutable.value.media?.playbackAttemptId != attempt) return
        saved["playing"] = play; mutable.update { it.copy(media = it.media?.copy(playWhenReady = play)) }
    }
    fun engine(engine: PlaybackEngine) { mutable.update { it.copy(engine = engine) }; viewModelScope.launch { store.setPlaybackEngine(engine) } }
    fun controlsTimeout(seconds: Int) { mutable.update { it.copy(controlsTimeout = seconds) }; viewModelScope.launch { store.setPlayerControlsTimeoutSeconds(seconds) } }
}
