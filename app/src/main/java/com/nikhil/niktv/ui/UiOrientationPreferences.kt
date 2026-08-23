package com.nikhil.niktv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * NIKTV_GLOBAL_ORIENTATION_PREFERENCE_V12
 *
 * Auto:
 * - phone -> portrait
 * - tablet -> landscape
 * - TV -> landscape
 *
 * This is application-wide so it is available before profile selection.
 */
enum class UiOrientationMode(val title: String) {
    DEVICE_DEFAULT("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

object UiOrientationPreferences {
    private const val PREFS_NAME = "niktv_ui_preferences"
    private const val ORIENTATION_KEY = "screen_orientation"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): UiOrientationMode {
        val stored = prefs(context).getString(ORIENTATION_KEY, null)
        return stored
            ?.let { runCatching { UiOrientationMode.valueOf(it) }.getOrNull() }
            ?: UiOrientationMode.DEVICE_DEFAULT
    }

    fun set(context: Context, mode: UiOrientationMode) {
        prefs(context).edit().putString(ORIENTATION_KEY, mode.name).apply()
    }

    internal fun sharedPreferences(context: Context): SharedPreferences =
        prefs(context)
}

@Composable
fun rememberUiOrientationMode(): State<UiOrientationMode> {
    val context = LocalContext.current
    val prefs = remember(context) {
        UiOrientationPreferences.sharedPreferences(context)
    }
    val value = remember(context) {
        mutableStateOf(UiOrientationPreferences.get(context))
    }

    DisposableEffect(prefs, context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "screen_orientation") {
                value.value = UiOrientationPreferences.get(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

private tailrec fun Context.findOrientationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findOrientationActivity()
    else -> null
}

private fun Context.isOrientationTv(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

private fun defaultLandscape(
    context: Context,
    configuration: Configuration
): Boolean =
    context.isOrientationTv(configuration) ||
        configuration.smallestScreenWidthDp >= 600

@Composable
fun ApplyUiOrientation(mode: UiOrientationMode) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findOrientationActivity() }

    val requestedOrientation = when (mode) {
        UiOrientationMode.PORTRAIT ->
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        UiOrientationMode.LANDSCAPE ->
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        UiOrientationMode.DEVICE_DEFAULT ->
            if (defaultLandscape(context, configuration)) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
    }

    LaunchedEffect(activity, requestedOrientation) {
        if (
            activity != null &&
            activity.requestedOrientation != requestedOrientation
        ) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}

@Composable
fun OrientationSettingsSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val selected by rememberUiOrientationMode()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Screen orientation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Auto uses portrait on phones and landscape on tablets and TVs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UiOrientationMode.entries.forEachIndexed { index, mode ->
                    val shape = when (index) {
                        0 -> RoundedCornerShape(
                            topStart = 8.dp,
                            bottomStart = 8.dp
                        )
                        UiOrientationMode.entries.lastIndex ->
                            RoundedCornerShape(
                                topEnd = 8.dp,
                                bottomEnd = 8.dp
                            )
                        else -> RoundedCornerShape(0.dp)
                    }

                    SegmentedButton(
                        selected = selected == mode,
                        onClick = {
                            UiOrientationPreferences.set(context, mode)
                        },
                        shape = shape
                    ) {
                        Text(mode.title, maxLines = 1)
                    }
                }
            }

            val resolved = when (selected) {
                UiOrientationMode.PORTRAIT -> "Portrait"
                UiOrientationMode.LANDSCAPE -> "Landscape"
                UiOrientationMode.DEVICE_DEFAULT ->
                    if (defaultLandscape(context, configuration)) {
                        "Auto · Landscape on this device"
                    } else {
                        "Auto · Portrait on this device"
                    }
            }

            Text(
                resolved,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun OrientationSettingsDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App settings") },
        text = { OrientationSettingsSection() },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
