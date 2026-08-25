package com.nikhil.niktv.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class MobileUiDesign { AUTO, CLASSIC, YOUTUBE }

object MobileUiPreferences {
    private const val PREFS = "mobile_ui_preferences"
    private const val KEY = "mobile_ui_design"

    fun get(context: Context): MobileUiDesign =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { MobileUiDesign.valueOf(it) }.getOrNull() }
            ?: MobileUiDesign.AUTO

    fun set(context: Context, design: MobileUiDesign) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, design.name).apply()
    }
}

fun MobileUiDesign.usesYouTubeOn(configuration: android.content.res.Configuration): Boolean =
    this == MobileUiDesign.YOUTUBE ||
        (this == MobileUiDesign.AUTO && configuration.smallestScreenWidthDp < 600)

@Composable
fun rememberMobileUiDesign(): State<MobileUiDesign> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(MobileUiPreferences.get(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("mobile_ui_preferences", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mobile_ui_design") state.value = MobileUiPreferences.get(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
