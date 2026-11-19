package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Touch controls are opt-in per viewer and travel with that viewer's preferences. */
internal object PlayerGesturePreferences {
    private const val DOMAIN = "niktv_playback_ui"
    private const val PINCH_TO_ZOOM = "pinch_to_zoom"
    private fun preferences(context: Context, owner: UserScope) =
        UserProfilePreferences.get(context, DOMAIN, owner = owner)

    fun pinchToZoom(context: Context, owner: UserScope?): Boolean =
        owner?.let { preferences(context, it).getBoolean(PINCH_TO_ZOOM, false) } ?: false

    fun observePinchToZoom(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) { trySend(false); close(); return@callbackFlow }
        UserProfilePreferences.warm(context, owner)
        val prefs = preferences(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PINCH_TO_ZOOM) trySend(pinchToZoom(context, owner))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(pinchToZoom(context, owner))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun setPinchToZoom(context: Context, owner: UserScope, enabled: Boolean) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(preferences(context, owner).edit().putBoolean(PINCH_TO_ZOOM, enabled).commit())
    }
}
