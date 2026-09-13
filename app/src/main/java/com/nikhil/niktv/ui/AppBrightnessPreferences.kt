package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal object AppBrightnessPreferences {
    private const val FILE = "app_display_preferences"
    private const val KEY = "app_brightness"
    const val MIN = 0.15f
    const val MAX = 1f

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context): Float =
        preferences(context).getFloat(KEY, MAX).coerceIn(MIN, MAX)

    fun set(context: Context, value: Float) {
        preferences(context).edit().putFloat(KEY, value.coerceIn(MIN, MAX)).apply()
    }

    fun sharedPreferences(context: Context): SharedPreferences = preferences(context)
}

@Composable
internal fun ApplyAppBrightness() {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    var brightness by remember(context) {
        mutableFloatStateOf(AppBrightnessPreferences.get(context))
    }

    DisposableEffect(context, activity) {
        val preferences = AppBrightnessPreferences.sharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "app_brightness") {
                brightness = AppBrightnessPreferences.get(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    DisposableEffect(activity, brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: -1f
        if (window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = brightness
            }
        }
        onDispose {
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = previous
                }
            }
        }
    }
}
