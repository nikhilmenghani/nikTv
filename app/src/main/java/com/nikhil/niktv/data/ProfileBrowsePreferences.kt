package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal object ProfileBrowsePreferences {
    const val DOMAIN = "profile_browse_v1"
    private const val SHOW_HOME = "show_home"

    private fun preferences(context: Context, owner: UserScope) =
        UserProfilePreferences.get(context, DOMAIN, owner = owner)

    fun showHome(context: Context, owner: UserScope?): Boolean =
        owner?.let { preferences(context, it).getBoolean(SHOW_HOME, false) } ?: false

    fun observeShowHome(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        UserProfilePreferences.warm(context, owner)
        val prefs = preferences(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SHOW_HOME) trySend(showHome(context, owner))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(showHome(context, owner))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun setShowHome(context: Context, owner: UserScope, enabled: Boolean) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(preferences(context, owner).edit().putBoolean(SHOW_HOME, enabled).commit())
    }
}
