package com.nikhil.niktv.ui

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaQueueItem

/** Cast queue LOAD has its own autoplay flag, independent of Player.playWhenReady. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class IntentAwareCastConverter(private val playWhenReady: () -> Boolean) : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()
    override fun toMediaItem(item: MediaQueueItem): MediaItem = delegate.toMediaItem(item)
    override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
        val converted = delegate.toMediaQueueItem(item)
        return MediaQueueItem.Builder(requireNotNull(converted.media)).setAutoplay(playWhenReady()).build()
    }
}
