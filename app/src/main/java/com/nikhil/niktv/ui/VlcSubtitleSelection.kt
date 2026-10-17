package com.nikhil.niktv.ui

/** Exactly one subtitle renderer may be selected. Native IDs are supplied by VLC. */
internal data class VlcSubtitleSelection(val nativeId: Int, val downloaded: Boolean)
internal fun vlcSubtitleSelection(id: String?, hasDownloaded: Boolean): VlcSubtitleSelection =
    if (id == "downloaded" && hasDownloaded) VlcSubtitleSelection(-1, true)
    else VlcSubtitleSelection(id?.toIntOrNull()?.takeIf { it >= 0 } ?: -1, false)

internal fun downloadedSubtitleUsesOverlay(extension: String): Boolean = extension.lowercase() in setOf("srt", "vtt")

/** Native IDs can be sparse and arrive out of order; never assume the final track is ours. */
internal fun attachedSubtitleTrackId(before: Set<Int>, tracks: List<Int>, selected: Int): Int? {
    val added = tracks.filter { it >= 0 && it !in before }
    return selected.takeIf { it in added } ?: added.singleOrNull()
}
