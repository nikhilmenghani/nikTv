package com.nikhil.niktv.ui

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
