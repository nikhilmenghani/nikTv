package com.nikhil.niktv.sports

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.Base64
import kotlinx.serialization.json.*

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SportsPlayer(stream: SportsStream, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failure by remember(stream) { mutableStateOf<String?>(null) }
    val player = remember(stream) { runCatching { sportsPlayer(context, stream) }.getOrElse {
        failure = "This stream could not be initialized. Choose another stream."
        null
    } }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failure = "Playback failed (${error.errorCodeName}). Choose another stream." }
        }
        player?.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.removeListener(listener); player?.release() }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text("Streams") }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(stream.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        failure?.let {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Choose another stream") }
        }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.player = player
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
            requestFocus()
        } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun sportsPlayer(context: android.content.Context, stream: SportsStream): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setUserAgent(SPORTS_USER_AGENT)
        .setDefaultRequestProperties(stream.headers).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    val sources = DefaultMediaSourceFactory(context).setDataSourceFactory(http)
    val item = MediaItem.Builder().setUri(stream.link)
    val path = stream.link.substringBefore('?').lowercase(java.util.Locale.ROOT)
    if (path.endsWith(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD)
    if (path.endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
    if (stream.drmType.isNotBlank()) {
        val uuid = when (stream.drmType.lowercase(java.util.Locale.ROOT)) {
            "clearkey", "org.w3.clearkey" -> C.CLEARKEY_UUID
            "widevine", "com.widevine.alpha" -> C.WIDEVINE_UUID
            "playready", "com.microsoft.playready" -> C.PLAYREADY_UUID
            else -> throw SportsException("This DRM format is unsupported.")
        }
        val license = stream.drmLicense.substringBefore('*')
        val drm = MediaItem.DrmConfiguration.Builder(uuid)
        if (license.startsWith("https://") || license.startsWith("http://")) drm.setLicenseUri(license).setLicenseRequestHeaders(stream.headers)
        else {
            if (uuid != C.CLEARKEY_UUID) throw SportsException("This source did not provide a license server.")
            val manager = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(sportsClearKeyResponse(license)))
            sources.setDrmSessionManagerProvider { manager }
        }
        item.setDrmConfiguration(drm.build())
    }
    return ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply {
        setMediaItem(item.build()); playWhenReady = true; prepare()
    }
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
