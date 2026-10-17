package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal enum class PlayerQueueLayout(val label: String) { GRID("Grid"), LIST("List") }

internal object PlayerQueuePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("player_media_browser", Context.MODE_PRIVATE)
    fun layout(context: Context): PlayerQueueLayout = runCatching {
        PlayerQueueLayout.valueOf(prefs(context).getString("layout", "GRID")!!)
    }.getOrDefault(PlayerQueueLayout.GRID)
    fun setLayout(context: Context, layout: PlayerQueueLayout) {
        prefs(context).edit().putString("layout", layout.name).apply()
    }
    fun preferences(context: Context): SharedPreferences = prefs(context)
}

@Composable
internal fun rememberPlayerQueueLayout(): PlayerQueueLayout {
    val context = LocalContext.current
    var layout by remember(context) { mutableStateOf(PlayerQueuePreferences.layout(context)) }
    DisposableEffect(context) {
        val prefs = PlayerQueuePreferences.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> layout = PlayerQueuePreferences.layout(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return layout
}
