package com.nikhil.niktv.data

import android.content.Context

/** Opt-in per profile and channel; direct playback is the default. */
internal object LiveRewindPreferences {
    private fun preferences(context: Context) = context.getSharedPreferences("live_rewind", Context.MODE_PRIVATE)
    private fun key(profileKey: String, channelId: String) = "$profileKey|$channelId"
    fun enabled(context: Context, profileKey: String, channelId: String): Boolean =
        profileKey.isNotBlank() && preferences(context).getBoolean(key(profileKey, channelId), false)
    fun setEnabled(context: Context, profileKey: String, channelId: String, enabled: Boolean) {
        if (profileKey.isBlank()) return
        preferences(context).edit().putBoolean(key(profileKey, channelId), enabled).apply()
    }
}
