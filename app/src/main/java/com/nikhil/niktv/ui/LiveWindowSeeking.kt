package com.nikhil.niktv.ui

/** Same capability test as a native live seekbar; never implies a local recording. */
internal fun liveWindowCanSeek(enabled: Boolean, nativeSeekable: Boolean, duration: Long, liveTimeline: Boolean = true): Boolean =
    enabled && nativeSeekable && liveTimeline && duration > 0L

internal fun providerLiveWindowSeekingEnabled(type: com.nikhil.niktv.model.CatalogType, offline: Boolean): Boolean =
    type == com.nikhil.niktv.model.CatalogType.LIVE_TV && !offline
