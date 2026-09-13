package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal object AppBrightnessPreferences {
    private const val FILE = "app_display_preferences"
    private const val KEY = "app_brightness"
    private const val KEY_FOLLOW_SYSTEM = "follow_system_brightness"
    const val MIN = 0.15f
    const val MAX = 1f

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context): Float =
        preferences(context).getFloat(KEY, MAX).coerceIn(MIN, MAX)

    fun set(context: Context, value: Float) {
        preferences(context).edit().putFloat(KEY, value.coerceIn(MIN, MAX)).apply()
    }

    fun followsSystem(context: Context): Boolean =
        preferences(context).let { stored ->
            stored.getBoolean(KEY_FOLLOW_SYSTEM, !stored.contains(KEY))
        }

    fun setFollowsSystem(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_FOLLOW_SYSTEM, enabled).apply()
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
    var followsSystem by remember(context) {
        mutableStateOf(AppBrightnessPreferences.followsSystem(context))
    }

    DisposableEffect(context, activity) {
        val preferences = AppBrightnessPreferences.sharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            brightness = AppBrightnessPreferences.get(context)
            followsSystem = AppBrightnessPreferences.followsSystem(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    DisposableEffect(activity, brightness, followsSystem) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: -1f
        if (window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = if (followsSystem) -1f else brightness
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

@Composable
internal fun AppBrightnessQuickButton(
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var brightness by remember(context) {
        mutableFloatStateOf(AppBrightnessPreferences.get(context))
    }
    var followsSystem by remember(context) {
        mutableStateOf(AppBrightnessPreferences.followsSystem(context))
    }

    DisposableEffect(context) {
        val preferences = AppBrightnessPreferences.sharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            brightness = AppBrightnessPreferences.get(context)
            followsSystem = AppBrightnessPreferences.followsSystem(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun update(value: Float) {
        brightness = value.coerceIn(
            AppBrightnessPreferences.MIN,
            AppBrightnessPreferences.MAX
        )
        AppBrightnessPreferences.set(context, brightness)
    }

    Box(modifier) {
        if (showLabel) {
            Row(
                Modifier
                    .remoteCombinedClickable(onClick = { expanded = true })
                    .remoteFocusFrame(RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Brightness6, null, Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Text("Brightness", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.remoteFocusFrame(CircleShape)
            ) {
                Icon(Icons.Default.Brightness6, "App brightness")
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = androidx.compose.ui.graphics.Color(0xFF202020),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (followsSystem) "Brightness · System" else "App brightness · ${(brightness * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Follow system", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = followsSystem,
                        onCheckedChange = {
                            followsSystem = it
                            AppBrightnessPreferences.setFollowsSystem(context, it)
                        }
                    )
                }
                Slider(
                    value = brightness,
                    onValueChange = ::update,
                    enabled = !followsSystem,
                    valueRange = AppBrightnessPreferences.MIN..AppBrightnessPreferences.MAX,
                    steps = 16,
                    modifier = Modifier.width(190.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    IconButton(
                        onClick = { update(brightness - .05f) },
                        enabled = !followsSystem
                    ) {
                        Icon(Icons.Default.Remove, "Dimmer")
                    }
                    Text("${(brightness * 100).toInt()}%")
                    IconButton(
                        onClick = { update(brightness + .05f) },
                        enabled = !followsSystem
                    ) {
                        Icon(Icons.Default.Add, "Brighter")
                    }
                }
            }
        }
    }
}
