package com.nikhil.niktv.ui

internal const val MPV_DEMUXER_BUFFER_BYTES = 32L * 1024L * 1024L
@androidx.annotation.ChecksSdkIntAtLeast(api = 26, parameter = 0)
internal fun mpvSupportedSdk(sdk: Int): Boolean = sdk >= 26

/** Compressed packet cache limit, independent of decoder/GPU memory and startup. */
internal fun mpvPlaybackOptions(live: Boolean): Map<String, String> = linkedMapOf(
    "config" to "no",
    "load-scripts" to "no",
    "osc" to "no",
    "input-default-bindings" to "no",
    "input-vo-keyboard" to "no",
    // The Android surface must exist before opening GPU output or loading video.
    "vo" to "null",
    "gpu-context" to "android",
    "gpu-api" to "opengl",
    "hwdec" to "mediacodec-copy",
    // Prefer Android's native presentation clock; retain AudioTrack for devices
    // whose AAudio output cannot open this stream/channel layout.
    "ao" to "aaudio,audiotrack",
    "video-sync" to "audio",
    "idle" to "yes",
    "keep-open" to if (live) "no" else "yes",
    "pause" to "yes",
    "cache" to "yes",
    "cache-pause-initial" to "no",
    "cache-pause-wait" to "1",
    "demuxer-max-bytes" to MPV_DEMUXER_BUFFER_BYTES.toString(),
    "demuxer-max-back-bytes" to if (live) "0" else "1048576",
    "network-timeout" to "15",
    "demuxer-readahead-secs" to if (live) "15" else "30",
    "osd-level" to "0",
    "tls-verify" to "yes"
)

internal fun mpvVideoSizing(mode: VideoResizeMode): Map<String, String> = mapOf(
    "keepaspect" to if (mode == VideoResizeMode.STRETCH) "no" else "yes",
    "panscan" to if (mode == VideoResizeMode.FILL || mode == VideoResizeMode.ZOOM) "1" else "0",
    "video-zoom" to if (mode == VideoResizeMode.ZOOM) "0.32192809488736235" else "0"
)
