package com.nikhil.niktv.ui

import android.content.Context
import androidx.media3.common.PlaybackException

/** Local hardware preference: deliberately excluded from profile/export settings. */
internal object AudioFailurePreferences {
    private const val FILE = "device_audio_preferences"
    private const val KEY = "video_only_on_audio_failure"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

internal fun shouldRecoverWithoutAudio(
    enabled: Boolean,
    audioDisabled: Boolean,
    errorCode: Int
): Boolean = enabled && !audioDisabled && errorCode in setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
)
