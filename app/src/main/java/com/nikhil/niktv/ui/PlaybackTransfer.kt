package com.nikhil.niktv.ui

internal data class PlaybackTransfer(val positionMillis: Long, val playWhenReady: Boolean)
internal fun playbackTransfer(positionMillis: Long?, playWhenReady: Boolean) =
    PlaybackTransfer(positionMillis?.coerceAtLeast(0L) ?: 0L, playWhenReady)
