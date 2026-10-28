package com.nikhil.niktv.ui

/** Same capability test as a native live seekbar; never implies a local recording. */
internal fun liveWindowCanSeek(enabled: Boolean, nativeSeekable: Boolean, duration: Long, liveTimeline: Boolean = true): Boolean =
    enabled && nativeSeekable && liveTimeline && duration > 0L
