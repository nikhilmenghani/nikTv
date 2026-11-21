package com.nikhil.niktv.sports

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ExternalVideoRequest
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.sports.player.PlayerScreen
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.initialFocusEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only player for an ordinary Android video handoff or controlled public samples. */
class PlaybackSimulationActivity : ComponentActivity() {
    private var incoming by mutableStateOf<PlaybackHandoff?>(null)
    private var sequence = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receive(intent)
        setContent {
            MaterialTheme(colorScheme = NikColors) {
                PlaybackSimulationScreen(incoming, ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun receive(intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
            incoming = PlaybackHandoff(++sequence, Intent(intent))
        }
    }
}

private data class PlaybackHandoff(val sequence: Int, val intent: Intent)

private sealed interface PlaybackTestRequest {
    data class Sample(val event: PlaybackSimulationEvent, val source: PlaybackSimulationSource) : PlaybackTestRequest
    data class External(val intent: Intent, val live: Boolean) : PlaybackTestRequest
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaybackSimulationScreen(incoming: PlaybackHandoff?, close: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { PlaybackSimulationRepository(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val sourceFocus = remember { mutableMapOf<String, FocusRequester>() }
    var origin by remember { mutableStateOf(playFocus) }
    var url by rememberSaveable { mutableStateOf("") }
    var live by rememberSaveable { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<PlaybackSimulationEvent>>(emptyList()) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var stages by remember { mutableStateOf<List<String>>(emptyList()) }
    var request by remember { mutableStateOf<PlaybackTestRequest?>(null) }
    var media by remember { mutableStateOf<PlayingMedia?>(null) }
    var popup by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var frames by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var epoch by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf<Job?>(null) }

    fun report(stage: String) {
        stages = (stages + stage).takeLast(8)
        android.util.Log.i("PlaybackSimulation", stage)
    }

    fun start(selected: PlaybackTestRequest, source: FocusRequester, decoderRecovery: Int = 0) {
        loading?.cancel()
        val token = ++epoch
        origin = source
        request = selected
        media = null
        popup = true
        preparing = true
        failure = null
        firstFrame = false
        frames = 0
        position = 0L
        stages = emptyList()
        keyboard?.hide()
        loading = scope.launch {
            try {
                val resolved = when (selected) {
                    is PlaybackTestRequest.Sample -> {
                        val stream = repository.resolve(selected.event, selected.source) { stage ->
                            scope.launch { if (epoch == token) report(stage) }
                        }
                        withContext(Dispatchers.IO) { stream.asPlayingMedia(selected.event.title) }
                    }
                    is PlaybackTestRequest.External -> {
                        report("Android video handoff received")
                        val video = withContext(Dispatchers.IO) {
                            ExternalVideoRequest.read(selected.intent, context.contentResolver)
                        }
                        url = video.uri.toString()
                        report("Supplied ${video.format.ifBlank { "video" }} URL accepted")
                        video.playing().copy(
                            catalogType = if (selected.live) CatalogType.LIVE_TV else CatalogType.MOVIES,
                            allowLiveWindowSeeking = selected.live,
                        )
                    }
                }
                if (epoch == token) {
                    media = resolved.copy(directFullscreen = false, decoderRecoveryCount = decoderRecovery)
                    report("NiKTV Media3 player opened · waiting for video")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == token) failure = error.message ?: "The selected video could not be opened."
            } finally {
                if (epoch == token) preparing = false
            }
        }
    }

    fun playInput() {
        try {
            val uri = Uri.parse(url.trim())
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                "Enter a complete HTTP or HTTPS video URL."
            }
            start(PlaybackTestRequest.External(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*"), live), inputFocus)
        } catch (error: IllegalArgumentException) {
            failure = error.message
        }
    }

    fun dismissPlayer() {
        loading?.cancel()
        epoch++
        runCatching { origin.requestFocus() }
        popup = false
        media = null
        preparing = false
    }

    LaunchedEffect(repository) {
        try {
            events = repository.catalogue { stage -> scope.launch { if (request == null && !popup) report(stage) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            catalogueError = error.message ?: "The local sample catalogue could not be loaded."
        }
    }
    LaunchedEffect(incoming?.sequence) {
        incoming?.let { handoff ->
            try {
                start(PlaybackTestRequest.External(normalizeHandoff(handoff.intent), live), playFocus)
            } catch (error: IllegalArgumentException) {
                failure = error.message
            }
        }
    }

    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .testTag("playbackSimulation"), color = NikColors.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
            .initialFocusEntry(playFocus).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Playback test", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                DiscoveryAction("Close", Icons.Default.Close, onClick = close)
            }
            Text("Open the source app’s external player or Share action and choose NikTV playback test, or paste its video URL.",
                color = SportsMuted, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it; if (!popup) failure = null }, Modifier.fillMaxWidth()
                .focusRequester(inputFocus).testTag("playbackUrl"), singleLine = true,
                label = { Text("Supplied video URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { playInput() }), shape = SportsShape)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoveryAction("Play supplied URL", Icons.Default.PlayArrow, modifier = Modifier
                    .focusRequester(playFocus).testTag("playExternal"), onClick = { playInput() })
                Text("Live stream", style = MaterialTheme.typography.bodyMedium)
                Switch(live, { live = it }, modifier = Modifier.testTag("externalLive")
                    .semantics { contentDescription = "Treat supplied URL as live stream" })
            }
            if (!popup) failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("playbackInputError")) }
            HorizontalDivider(color = Color.White.copy(alpha = .12f))
            Text("Controlled public samples", style = MaterialTheme.typography.titleMedium)
            Text("These local fixtures demonstrate bootstrap → catalogue → Base64 envelope → public DASH playback.",
                color = SportsMuted, style = MaterialTheme.typography.bodySmall)
            catalogueError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            events.forEach { event ->
                key(event.id) {
                    Surface(color = SportsSurface, shape = SportsShape) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleSmall)
                            Text(event.description, color = SportsMuted, style = MaterialTheme.typography.bodySmall)
                            event.sources.forEach { source ->
                                val focus = sourceFocus.getOrPut("${event.id}:${source.id}") { FocusRequester() }
                                DiscoveryAction(source.title, Icons.Default.PlayArrow, modifier = Modifier
                                    .focusRequester(focus).testTag("sample:${event.id}:${source.id}"),
                                    onClick = { start(PlaybackTestRequest.Sample(event, source), focus) })
                            }
                        }
                    }
                }
            }
            if (!popup && stages.isNotEmpty()) Text(stages.last(), color = SportsMuted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("playbackStatus"))
        }
    }

    if (popup) {
        val attempt = epoch
        PlaybackTestPopup(media, preparing, failure, firstFrame, frames, position, stages,
            dismiss = ::dismissPlayer,
            retry = { request?.let { start(it, origin, media?.decoderRecoveryCount ?: 0) } },
            retryDecoder = { request?.let { start(it, origin, ((media?.decoderRecoveryCount ?: 0) + 1).coerceAtMost(3)) } },
            firstRendered = {
                scope.launch {
                    if (epoch == attempt && !firstFrame) {
                        firstFrame = true
                        report("First video frame rendered")
                    }
                }
            },
            rendered = { count -> scope.launch { if (epoch == attempt) frames = maxOf(frames, count) } },
            progress = { at -> scope.launch { if (epoch == attempt) position = at.coerceAtLeast(0L) } },
            rejected = { scope.launch { if (epoch == attempt) failure = "The server rejected the supplied link. Obtain a fresh link from the source app." } },
            failed = { scope.launch { if (epoch == attempt) failure = "The video could not keep playing. Retry or supply a fresh link." } })
    }
}

/** Text shares use the same validated request reader as ordinary Android video intents. */
private fun normalizeHandoff(intent: Intent): Intent {
    if (intent.action != Intent.ACTION_SEND || intent.hasExtra(Intent.EXTRA_STREAM)) return intent
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val uri = Uri.parse(text)
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && !text.any(Char::isWhitespace)) {
        "Share a single HTTP or HTTPS video URL."
    }
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaybackTestPopup(
    media: PlayingMedia?, preparing: Boolean, failure: String?, firstFrame: Boolean, frames: Int, position: Long,
    stages: List<String>, dismiss: () -> Unit, retry: () -> Unit, retryDecoder: () -> Unit,
    firstRendered: () -> Unit, rendered: (Int) -> Unit, progress: (Long) -> Unit,
    rejected: () -> Unit, failed: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val height = (LocalConfiguration.current.screenHeightDp.dp * .88f).coerceAtMost(680.dp)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 12.dp).widthIn(max = 1040.dp).fillMaxWidth().height(height)
            .semantics { testTagsAsResourceId = true }
            .initialFocusEntry(closeFocus).testTag("playbackPopup"), shape = RoundedCornerShape(14.dp), color = SportsSurface) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (firstFrame) "Video rendered" else "Waiting for video"} · $frames frames",
                        Modifier.weight(1f).testTag("playbackFrames"), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DiscoveryAction("Retry", Icons.Default.Refresh,
                        modifier = Modifier.focusRequester(retryFocus).testTag("retryPlayback"), onClick = retry)
                    DiscoveryAction("Close", Icons.Default.Close, modifier = Modifier.focusRequester(closeFocus)
                        .testTag("closePlayback"), onClick = dismiss)
                }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black, SportsShape)) {
                    if (media != null) key(media.playbackAttemptId) {
                        PlayerScreen(media, onBack = dismiss,
                            onRetry = { runCatching { retryFocus.requestFocus() }; retry() },
                            onRetryAlternateDecoder = { runCatching { retryFocus.requestFocus() }; retryDecoder() },
                            onPlaybackAuthorizationFailure = { rejected() },
                            onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, at, _ -> progress(at) },
                            playbackEngine = PlaybackEngine.MEDIA3,
                            modifier = Modifier.fillMaxSize(), embeddedMode = true, fullscreenOverride = false,
                            onFullscreenChanged = {}, showLiveRecordingAndGuide = false,
                            onVideoFrameRendered = firstRendered, onVideoFramesRendered = rendered,
                            onManualRetry = { runCatching { retryFocus.requestFocus() }; retry() }, onTerminalPlaybackFailure = failed)
                    }
                    if (preparing) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if (media == null && !preparing) Text("Video has not started", Modifier.align(Alignment.Center), color = SportsMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (firstFrame) "First frame: verified by the player" else "First frame: not received",
                        style = MaterialTheme.typography.bodySmall, color = SportsMuted,
                        modifier = Modifier.weight(1f).testTag("playbackFirstFrame"))
                    Text(if (media?.catalogType == CatalogType.LIVE_TV) "Live timeline" else "Position: $position ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = SportsMuted, modifier = Modifier.testTag("playbackProgress"))
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, modifier = Modifier.testTag("playbackFailure")) }
                Text(stages.takeLast(2).joinToString("\n"), color = SportsMuted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("playbackStatus"))
            }
        }
    }
}
