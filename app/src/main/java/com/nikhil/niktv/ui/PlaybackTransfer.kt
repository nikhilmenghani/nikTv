package com.nikhil.niktv.ui

internal data class PlaybackTransfer(val positionMillis: Long, val playWhenReady: Boolean)
internal fun playbackTransfer(positionMillis: Long?, playWhenReady: Boolean) =
    PlaybackTransfer(positionMillis?.coerceAtLeast(0L) ?: 0L, playWhenReady)

/** Apply local intent before loading; IntentAwareCastConverter also supplies the receiver autoplay flag. */
internal fun androidx.media3.common.Player.preparePlaybackTransfer(item: androidx.media3.common.MediaItem, transfer: PlaybackTransfer) {
    playWhenReady = transfer.playWhenReady
    setMediaItem(item, transfer.positionMillis)
    prepare()
}
