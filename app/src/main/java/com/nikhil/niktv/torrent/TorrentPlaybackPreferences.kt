package com.nikhil.niktv.torrent

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** One opt-in switch per viewer, stored in the existing portable playback domain. */
internal object TorrentPlaybackPreferences {
    const val KEY = "torrent_prefetch_next"
    private fun prefs(context: Context, owner: UserScope) = UserProfilePreferences.get(context, "niktv_playback_ui", owner = owner)
    fun enabled(context: Context, owner: UserScope?) = owner?.let { prefs(context, it).getBoolean(KEY, false) } ?: false
    fun observe(context: Context, owner: UserScope?): Flow<Boolean> = callbackFlow {
        if (owner == null) { trySend(false); close(); return@callbackFlow }
        UserProfilePreferences.warm(context, owner)
        val preferences = prefs(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) trySend(enabled(context, owner)) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(enabled(context, owner))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
    suspend fun set(context: Context, owner: UserScope, value: Boolean) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(prefs(context, owner).edit().putBoolean(KEY, value).commit())
        AccountSyncManager.get(context).requestProfileSync(owner.accountId)
    }
}
