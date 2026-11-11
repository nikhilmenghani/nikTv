package com.nikhil.niktv.ui

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.data.UserSyncStore

/** Updates both local edits and remote merges without reopening the current destination. */
@Composable
internal fun rememberIptvPins(profileKey: String): IptvPinSnapshot {
    val context = LocalContext.current.applicationContext
    val users = remember(context) { UserSyncStore.get(context) }
    val selectedOwner by users.selectedScope.collectAsState()
    val owner = LocalViewingOwner.current ?: selectedOwner
    val preferences = remember(context, owner) { IptvPinPreferences.preferences(context, owner) }
    var snapshot by remember(preferences, profileKey) {
        mutableStateOf(IptvPinPreferences.snapshot(preferences, profileKey))
    }
    DisposableEffect(preferences, profileKey) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("$profileKey|")) {
                snapshot = IptvPinPreferences.snapshot(preferences, profileKey)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Close the interval between the initial read and listener registration.
        snapshot = IptvPinPreferences.snapshot(preferences, profileKey)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return snapshot
}
