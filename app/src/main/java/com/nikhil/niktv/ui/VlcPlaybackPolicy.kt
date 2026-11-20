package com.nikhil.niktv.ui

import android.content.Context
import android.net.Uri
import com.nikhil.niktv.data.LanSelectedMediaServer
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer

internal const val VLC_VIDEO_STALL_MILLIS = 7_000L

/** The media clock can run on audio even when the displayed picture is stuck. */
internal class VlcPictureProgress {
    private var pictures: Int? = null
    var positionMillis: Long = 0L
        private set

    fun sample(displayed: Int?, position: Long): Long? {
        if (displayed != null && displayed > 0 && displayed != pictures) {
            pictures = displayed
            positionMillis = position.coerceAtLeast(0L)
        }
        return pictures?.toLong()
    }
}

internal suspend fun MediaPlayer.vlcDisplayedPictures(): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val current = media ?: return@runCatching null
        try { current.stats?.displayedPictures } finally { current.release() }
    }.getOrNull()
}

/** Own the HLS sockets so a hung segment read can be canceled before native teardown. */
internal fun openVlcHlsInput(context: Context, media: PlayingMedia): LanSelectedMediaServer? {
    val address = "127.0.0.1"
    val scheme = Uri.parse(media.url).scheme
    if (media.offlinePlayback || media.externalPlayback || scheme !in setOf("http", "https") ||
        !isHlsPlayback(media.playbackFormat, media.url)) return null
    return runCatching {
        // Give buffered playback first use of the remaining video. The frame
        // watchdog closes the input immediately once playback actually stalls.
        LanSelectedMediaServer(context.applicationContext, address, media, address, readTimeoutSeconds = 30L)
    }.getOrNull()
}

/** HLS has its own downloader; network-caching alone does not control its buffer. */
internal fun isHlsPlayback(format: String, url: String): Boolean =
    format.equals("HLS", ignoreCase = true) ||
        url.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true)

internal fun vlcPlaybackOptions(live: Boolean, offline: Boolean, hls: Boolean = false): List<String> = buildList {
    add("--network-caching=1500")
    if (live && !offline && hls) {
        // These options are consumed by VLC's adaptive demuxer (HLS/DASH), not
        // progressive TS. The delay selects published media behind the live edge;
        // it does not impose a 30-second startup wait.
        add("--adaptive-lowlatency=0")
        add("--adaptive-livedelay=30000")
        add("--adaptive-maxbuffer=30000")
        // Generate MPEG-TS clock references from elementary-stream timestamps.
        // Re-muxed IPTV PCR values can drift and repeatedly force a full rebuffer.
        add("--no-ts-trust-pcr")
    }
}

/** Reconnects belong to LiveStreamRecovery; the native-player timer only owns first startup. */
internal fun vlcInitialStartupTimedOut(live: Boolean, channelHasPlayed: Boolean,
    streamWasPlaying: Boolean, playbackRequested: Boolean, hasError: Boolean): Boolean =
    (!live || !channelHasPlayed) && !streamWasPlaying && playbackRequested && !hasError
