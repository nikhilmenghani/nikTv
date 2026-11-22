package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal enum class PlayerQueueLayout(val label: String) { GRID("Grid"), LIST("List") }
internal enum class PlayerQueueWidth(val label: String, val wideFraction: Float, val phoneFraction: Float) {
    SMALL("Small", .35f, .75f), MEDIUM("Medium", .45f, .86f), LARGE("Large", .60f, .96f);
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal object PlayerQueuePreferences {
    private fun prefs(context: Context) = com.nikhil.niktv.data.UserProfilePreferences.get(context, "player_media_browser")
    fun layout(context: Context): PlayerQueueLayout = runCatching {
        PlayerQueueLayout.valueOf(prefs(context).getString("layout", "GRID")!!)
    }.getOrDefault(PlayerQueueLayout.GRID)
    fun setLayout(context: Context, layout: PlayerQueueLayout) {
        prefs(context).edit().putString("layout", layout.name).apply()
    }
    fun preferences(context: Context): SharedPreferences = prefs(context)
    fun width(context: Context): PlayerQueueWidth = runCatching {
        PlayerQueueWidth.valueOf(prefs(context).getString("list_width", "MEDIUM")!!)
    }.getOrDefault(PlayerQueueWidth.MEDIUM)
    fun setWidth(context: Context, width: PlayerQueueWidth) {
        prefs(context).edit().putString("list_width", width.name).apply()
    }
}

@Composable
internal fun rememberPlayerQueueWidth(): PlayerQueueWidth {
    val context = LocalContext.current
    var width by remember(context) { mutableStateOf(PlayerQueuePreferences.width(context)) }
    DisposableEffect(context) {
        val prefs = PlayerQueuePreferences.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> width = PlayerQueuePreferences.width(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return width
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
