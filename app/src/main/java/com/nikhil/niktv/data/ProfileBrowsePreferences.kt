package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal enum class ViewerLayout(val label: String) {
    STANDARD("Standard"), EASY("Easy")
}

internal object ProfileBrowsePreferences {
    const val DOMAIN = "profile_browse_v1"
    private const val LAYOUT = "layout"

    private fun preferences(context: Context, owner: UserScope) =
        UserProfilePreferences.get(context, DOMAIN, owner = owner)

    fun layout(context: Context, owner: UserScope?): ViewerLayout {
        val stored = owner?.let { preferences(context, it).getString(LAYOUT, null) }
        return ViewerLayout.entries.firstOrNull { it.name == stored } ?: ViewerLayout.STANDARD
    }

    fun observeLayout(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) {
            trySend(ViewerLayout.STANDARD)
            close()
            return@callbackFlow
        }
        UserProfilePreferences.warm(context, owner)
        val prefs = preferences(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LAYOUT) trySend(layout(context, owner))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(layout(context, owner))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun setLayout(context: Context, owner: UserScope, layout: ViewerLayout) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(preferences(context, owner).edit().putString(LAYOUT, layout.name).commit())
    }
}
