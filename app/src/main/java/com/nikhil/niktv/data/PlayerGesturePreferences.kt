package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal enum class PlayerSwipeGesture(val key: String) {
    CHANNELS("swipe_channels"), BRIGHTNESS("swipe_brightness"), VOLUME("swipe_volume")
}
internal data class PlayerSwipeControls(val channels: Boolean = false, val brightness: Boolean = false, val volume: Boolean = false) {
    fun enabled(gesture: PlayerSwipeGesture) = when (gesture) {
        PlayerSwipeGesture.CHANNELS -> channels
        PlayerSwipeGesture.BRIGHTNESS -> brightness
        PlayerSwipeGesture.VOLUME -> volume
    }
    fun with(gesture: PlayerSwipeGesture, enabled: Boolean) = when (gesture) {
        PlayerSwipeGesture.CHANNELS -> copy(channels = enabled)
        PlayerSwipeGesture.BRIGHTNESS -> copy(brightness = enabled)
        PlayerSwipeGesture.VOLUME -> copy(volume = enabled)
    }
}

/** Touch controls are opt-in per viewer and travel with that viewer's preferences. */
internal object PlayerGesturePreferences {
    private const val DOMAIN = "niktv_playback_ui"
    private const val PINCH_TO_ZOOM = "pinch_to_zoom"
    private fun preferences(context: Context, owner: UserScope) =
        UserProfilePreferences.get(context, DOMAIN, owner = owner)

    fun pinchToZoom(context: Context, owner: UserScope?): Boolean =
        owner?.let { preferences(context, it).getBoolean(PINCH_TO_ZOOM, false) } ?: false

    fun swipes(context: Context, owner: UserScope?): PlayerSwipeControls {
        val prefs = owner?.let { preferences(context, it) } ?: return PlayerSwipeControls()
        return PlayerSwipeControls(prefs.getBoolean(PlayerSwipeGesture.CHANNELS.key, false),
            prefs.getBoolean(PlayerSwipeGesture.BRIGHTNESS.key, false), prefs.getBoolean(PlayerSwipeGesture.VOLUME.key, false))
    }
    fun observeSwipes(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) { trySend(PlayerSwipeControls()); close(); return@callbackFlow }
        UserProfilePreferences.warm(context, owner)
        val prefs = preferences(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (PlayerSwipeGesture.entries.any { it.key == key }) trySend(swipes(context, owner))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(swipes(context, owner))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun setSwipe(context: Context, owner: UserScope, gesture: PlayerSwipeGesture, enabled: Boolean) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(preferences(context, owner).edit().putBoolean(gesture.key, enabled).commit())
        AccountSyncManager.get(context).requestProfileSync(owner.accountId)
    }

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
        AccountSyncManager.get(context).requestProfileSync(owner.accountId)
    }
}
