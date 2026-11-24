package com.nikhil.niktv.ui

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore

internal data class IptvPinObservation(
    val profileKey: String,
    val viewingOwner: UserScope?,
    val snapshot: State<IptvPinSnapshot>,
)

internal val LocalIptvPinObservation = staticCompositionLocalOf<IptvPinObservation?> { null }

/** Updates both local edits and remote merges without reopening the current destination. */
@Composable
internal fun rememberIptvPinObservation(profileKey: String): IptvPinObservation {
    val context = LocalContext.current.applicationContext
    val users = remember(context) { UserSyncStore.get(context) }
    val selectedOwner by users.selectedScope.collectAsState()
    val viewingOwner = LocalViewingOwner.current
    val owner = viewingOwner ?: selectedOwner
    val preferences = remember(context, owner) { IptvPinPreferences.preferences(context, owner) }
    val snapshot = remember(preferences, profileKey) {
        mutableStateOf(IptvPinPreferences.snapshot(preferences, profileKey))
    }
    DisposableEffect(preferences, profileKey) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("$profileKey|")) {
                snapshot.value = IptvPinPreferences.snapshot(preferences, profileKey)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Close the interval between the initial read and listener registration.
        snapshot.value = IptvPinPreferences.snapshot(preferences, profileKey)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(profileKey, viewingOwner, snapshot) { IptvPinObservation(profileKey, viewingOwner, snapshot) }
}

@Composable
internal fun rememberIptvPins(profileKey: String): IptvPinSnapshot {
    val observation = LocalIptvPinObservation.current
    val viewingOwner = LocalViewingOwner.current
    return if (observation?.profileKey == profileKey && observation.viewingOwner == viewingOwner) {
        observation.snapshot.value
    } else rememberIptvPinObservation(profileKey).snapshot.value
}
