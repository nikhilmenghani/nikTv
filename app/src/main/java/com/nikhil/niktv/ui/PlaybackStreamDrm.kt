package com.nikhil.niktv.ui

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nikhil.niktv.model.PlaybackStreamDrm

@androidx.annotation.OptIn(UnstableApi::class)
internal fun MediaItem.Builder.withStreamDrm(drm: PlaybackStreamDrm?, headers: Map<String, String>): MediaItem.Builder = apply {
    if (drm != null) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(drm.scheme).apply {
        drm.licenseUri?.let { setLicenseUri(it); setLicenseRequestHeaders(headers) }
    }.build())
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun DefaultMediaSourceFactory.withStreamDrm(drm: PlaybackStreamDrm?): DefaultMediaSourceFactory = apply {
    drm?.localResponse?.let { response ->
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(drm.scheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(response))
        setDrmSessionManagerProvider { manager }
    }
}
