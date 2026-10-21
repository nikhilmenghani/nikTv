package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nikhil.niktv.model.CatalogType

/** Live positions belong to a moving window, so they cannot be transferred to a new stream. */
internal fun playbackRestartPosition(type: CatalogType, requested: Long): Long =
    if (type == CatalogType.LIVE_TV) 0L else requested.coerceAtLeast(0L)

internal fun recoverableLiveSourceFailure(
    live: Boolean,
    hasPlayed: Boolean,
    errorCode: Int,
    httpStatus: Int?,
    playlistStalled: Boolean = false
): Boolean {
    if (!live || !hasPlayed || httpStatus in setOf(401, 403, 429)) return false
    return playlistStalled || errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
            httpStatus in setOf(404, 410, 500, 502, 503, 504))
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun expiredLiveSegment(hasPlayed: Boolean, dataType: Int, status: Int?): Boolean =
    hasPlayed && dataType == C.DATA_TYPE_MEDIA && status in setOf(404, 410)

/** A removed live segment needs a fresh playlist, not retries of the same missing file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LiveSegmentLoadErrorPolicy(private val hasPlayed: () -> Boolean) :
    DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return if (expiredLiveSegment(hasPlayed(), info.mediaLoadData.dataType, status)) C.TIME_UNSET
            else super.getRetryDelayMsFor(info)
    }
}

/** Auto may change engines for device failures; network errors and explicit choices may not. */
internal fun automaticLivePlayerFallback(preference: com.nikhil.niktv.model.PlaybackEngine,
    live: Boolean, requested: Boolean, offline: Boolean, compatibleVlc: Boolean,
    errorCode: Int): Boolean = preference == com.nikhil.niktv.model.PlaybackEngine.AUTO &&
    live && requested && !offline && compatibleVlc && errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_TIMEOUT)

/** Sliding manifests change window coordinates without changing the decoded media clock. */
internal fun liveRecoveryPosition(positionMillis: Long, windowOffsetMillis: Long): Long =
    positionMillis.coerceAtLeast(0L) + windowOffsetMillis.coerceAtLeast(0L)
