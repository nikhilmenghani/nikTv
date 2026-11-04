package com.nikhil.niktv.discovery

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext

/** Load only the cached provider catalogue here; titles load after a provider is opened. */
@Composable
internal fun homeWatchProviders(profile: String, enabled: Boolean): WatchProvidersState {
    if (!enabled) return WatchProvidersState(loading = false)
    val holder: WatchProvidersViewModel = viewModel(key = "home-watch-providers:$profile")
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("watch_providers", 0) }
    val state by holder.model.state.collectAsStateWithLifecycle()
    LaunchedEffect(holder) { holder.model.resume() }
    DisposableEffect(holder, preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "region" || key == "series") holder.model.select(
                region = preferences.getString("region", "IN") ?: "IN", series = preferences.getBoolean("series", false))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener); holder.model.stop() }
    }
    return state
}
