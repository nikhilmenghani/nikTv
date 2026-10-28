package com.nikhil.niktv.sports

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlaybackStreamDrm
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.sports.player.PlayerScreen
import com.nikhil.niktv.ui.findActivity
import androidx.compose.ui.platform.LocalContext
import java.util.Base64
import kotlinx.serialization.json.*

/** Use the same chrome, touch/remote input and playback lifecycle as IPTV live TV. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SportsPlayer(stream: SportsStream, title: String, onBack: () -> Unit,
    onVideoFrameRendered: () -> Unit = {},
    streams: List<SportsStream> = emptyList(), selectedIndex: Int = -1,
    navigationIndex: Int = selectedIndex, onSelectStream: ((Int) -> Unit)? = null,
    onVideoFramesRendered: ((Int) -> Unit)? = null,
    sourceAttemptId: Long = 0L, onRetryFresh: (() -> Unit)? = null,
    onTerminalFailure: (() -> Unit)? = null, onCancelPendingFallback: (() -> Unit)? = null) {
    val preferences: SportsPlaybackSettingsViewModel = viewModel()
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val currentSettings = settings
    if (currentSettings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var attempt by remember(stream, sourceAttemptId) { mutableIntStateOf(0) }
    var decoderRecoveries by remember(stream, sourceAttemptId) { mutableIntStateOf(0) }
    var authorizationRetries by remember(stream, sourceAttemptId) { mutableIntStateOf(0) }
    var decoderSession by remember(stream, sourceAttemptId) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val source = remember(stream, title) { runCatching { stream.asPlayingMedia(title) } }
    val original = source.getOrNull()
    if (original == null) {
        if (onTerminalFailure != null) {
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
            var reported by remember(stream, sourceAttemptId) { mutableStateOf(false) }
            LaunchedEffect(stream, sourceAttemptId, lifecycleState) {
                if (!reported && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    reported = true
                    onTerminalFailure()
                }
            }
        }
        AlertDialog(onDismissRequest = onBack, title = { Text("Unable to play this stream") },
            text = { Text("This stream's playback configuration is unsupported. Please choose another stream.") },
            confirmButton = { TextButton(onClick = onBack) { Text("Choose another stream") } })
        return
    }
    val queue = remember(streams) { sportsStreamQueue(streams) }
    val committedMedia = remember(original, sourceAttemptId, attempt, decoderRecoveries, authorizationRetries, decoderSession) {
        original.copy(playbackAttemptId = java.util.UUID.randomUUID().toString(), decoderSessionId = decoderSession,
            decoderRecoveryCount = decoderRecoveries, authorizationRetryCount = authorizationRetries)
    }
    val activeId = remember(original, sourceAttemptId) { "sports-active:$sourceAttemptId:${original.media.id}" }
    val displayQueue = remember(queue, selectedIndex, activeId) {
        queue.mapIndexed { index, item -> if (index == selectedIndex) item.copy(id = activeId) else item }
    }
    val media = remember(committedMedia, displayQueue, selectedIndex, activeId) {
        committedMedia.let { playing ->
            val stable = playing.copy(media = playing.media.copy(id = activeId))
            if (onSelectStream == null) stable else stable.copy(
                episodeQueue = displayQueue,
                previousEpisode = displayQueue.getOrNull(sportsAdjacentStream(selectedIndex, -1, displayQueue.size) ?: -1),
                nextEpisode = displayQueue.getOrNull(sportsAdjacentStream(selectedIndex, 1, displayQueue.size) ?: -1))
        }
    }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previousOrientation != null) activity?.requestedOrientation = previousOrientation }
    }
    key(sourceAttemptId) { PlayerScreen(media, onBack = onBack, onRetry = { attempt++ }, onRetryAlternateDecoder = { decoderRecoveries++; attempt++ },
        playbackDomain = com.nikhil.niktv.ui.PlaybackDomain.SPORTS,
        onManualRetry = {
            if (onRetryFresh != null) onRetryFresh() else {
                decoderRecoveries = 0; decoderSession = java.util.UUID.randomUUID().toString(); attempt++
            }
        },
        onPlaybackAuthorizationFailure = { authorizationRetries++; attempt++ },
        onPlayPrevious = { sportsAdjacentStream(navigationIndex, -1, queue.size)?.let { onSelectStream?.invoke(it) } },
        onPlayNext = { sportsAdjacentStream(navigationIndex, 1, queue.size)?.let { onSelectStream?.invoke(it) } },
        onPlayQueueItem = { _, index -> if (index in queue.indices) onSelectStream?.invoke(index) },
        liveQueueCategoryTitle = "Streams", onProgress = { _, _, _ -> },
        controlsTimeoutSeconds = currentSettings.timeout, onControlsTimeoutChanged = preferences::setTimeout,
        playbackEngine = currentSettings.engine, onPlaybackEngineChanged = preferences::setEngine,
        onPlaybackIntentChanged = { if (!it) onCancelPendingFallback?.invoke() },
        startFullscreen = true, showLiveRecordingAndGuide = false, onVideoFrameRendered = onVideoFrameRendered,
        onVideoFramesRendered = onVideoFramesRendered, onTerminalPlaybackFailure = onTerminalFailure) }
}

internal fun sportsAdjacentStream(index: Int, direction: Int, count: Int): Int? =
    if (count < 2 || index !in 0 until count) null else Math.floorMod(index + direction, count)

/** Occurrences stay distinct even if two source entries share a URL or title. */
internal fun sportsStreamQueue(streams: List<SportsStream>) = streams.mapIndexed { index, source ->
    val identity = java.security.MessageDigest.getInstance("SHA-256").digest(source.link.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    com.nikhil.niktv.model.MediaItem("sports-source:$index:$identity",
        source.title.ifBlank { "Source ${index + 1}" }, null, null)
}

internal fun SportsStream.playbackDrm(): PlaybackStreamDrm? {
    if (drmType.isBlank()) return null
    val uuid = when (drmType.lowercase(java.util.Locale.ROOT)) {
        "clearkey", "org.w3.clearkey" -> C.CLEARKEY_UUID
        "widevine", "com.widevine.alpha" -> C.WIDEVINE_UUID
        "playready", "com.microsoft.playready" -> C.PLAYREADY_UUID
        else -> throw SportsException("This DRM format is unsupported.")
    }
    val license = drmLicense.substringBefore('*')
    if (license.startsWith("https://") || license.startsWith("http://")) return PlaybackStreamDrm(uuid, licenseUri = license)
    if (uuid != C.CLEARKEY_UUID) throw SportsException("This source did not provide a license server.")
    return PlaybackStreamDrm(uuid, localResponse = sportsClearKeyResponse(license))
}

internal fun SportsStream.asPlayingMedia(title: String): PlayingMedia {
    val identity = java.security.MessageDigest.getInstance("SHA-256").digest(link.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    val headers = if (headers.keys.any { it.equals("User-Agent", true) }) headers else headers + ("User-Agent" to SPORTS_USER_AGENT)
    val path = link.substringBefore('?').lowercase(java.util.Locale.ROOT)
    return PlayingMedia(com.nikhil.niktv.model.MediaItem("sports:$identity", title.ifBlank { this.title }, null, null),
        link, CatalogType.LIVE_TV, progressKey = "sports:$identity", directFullscreen = true, streamRequestHeaders = headers,
        playbackFormat = when { path.endsWith(".mpd") -> "DASH"; path.endsWith(".m3u8") -> "HLS"; else -> "" },
        streamDrm = playbackDrm(), allowLiveWindowSeeking = true)
}

internal fun sportsClearKeyResponse(value: String): ByteArray {
    if (value.trim().startsWith('{')) {
        val obj = Json.parseToJsonElement(value).jsonObject
        require(obj["keys"] is JsonArray)
        return value.toByteArray()
    }
    val keys = value.split(',').map { pair ->
        val pieces = pair.trim().split(':', limit = 2)
        require(pieces.size == 2 && pieces.all { it.matches(Regex("[a-fA-F0-9]{32}")) })
        fun base64(hex: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        buildJsonObject { put("kty", "oct"); put("kid", base64(pieces[0])); put("k", base64(pieces[1])) }
    }
    return buildJsonObject { put("keys", JsonArray(keys)); put("type", "temporary") }.toString().toByteArray()
}
