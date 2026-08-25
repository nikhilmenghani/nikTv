package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

internal enum class VideoResizeMode(val label: String) {
    FIT("Fit"), FILL("Fill"), ZOOM("Zoom"), STRETCH("Stretch");
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal data class VideoAppearanceProfile(
    val id: String,
    val name: String,
    val warmth: Float,
    val dimming: Float
)

internal object VideoAppearancePreferences {
    private const val FILE = "video_appearance_profiles"
    private const val ACTIVE = "active"
    private val defaults = listOf(
        VideoAppearanceProfile("movie", "Movie", .07f, .02f),
        VideoAppearanceProfile("standard", "Standard", 0f, 0f),
        VideoAppearanceProfile("natural", "Natural", .03f, .01f),
        VideoAppearanceProfile("night", "Night light", .24f, .16f),
        VideoAppearanceProfile("custom", "Custom", 0f, 0f)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun profiles(context: Context): List<VideoAppearanceProfile> = defaults.map { preset ->
        val p = prefs(context)
        preset.copy(
            name = p.getString("${preset.id}_name", preset.name).orEmpty().ifBlank { preset.name },
            warmth = p.getInt("${preset.id}_warmth", (preset.warmth * 100).roundToInt()) / 100f,
            dimming = p.getInt("${preset.id}_dimming", (preset.dimming * 100).roundToInt()) / 100f
        )
    }
    fun activeId(context: Context) = prefs(context).getString(ACTIVE, "standard") ?: "standard"
    fun setActive(context: Context, id: String) { prefs(context).edit().putString(ACTIVE, id).apply() }
    fun update(context: Context, profile: VideoAppearanceProfile) {
        prefs(context).edit()
            .putString("${profile.id}_name", profile.name)
            .putInt("${profile.id}_warmth", (profile.warmth.coerceIn(0f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_dimming", (profile.dimming.coerceIn(0f, .8f) * 100).roundToInt())
            .apply()
    }
    fun sharedPreferences(context: Context): SharedPreferences = prefs(context)
}

@Composable
internal fun rememberVideoAppearanceProfiles(): Pair<List<VideoAppearanceProfile>, VideoAppearanceProfile> {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        VideoAppearancePreferences.sharedPreferences(context).registerOnSharedPreferenceChangeListener(listener)
        onDispose { VideoAppearancePreferences.sharedPreferences(context).unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val profiles = remember(context, revision) { VideoAppearancePreferences.profiles(context) }
    val active = profiles.firstOrNull { it.id == VideoAppearancePreferences.activeId(context) }
        ?: profiles.first { it.id == "standard" }
    return profiles to active
}

@Composable
internal fun VideoAppearanceOverlay(profile: VideoAppearanceProfile) {
    Box(Modifier.fillMaxSize()) {
        if (profile.warmth > 0f) Box(
            Modifier.fillMaxSize().background(Color(0xFFFF8A3D).copy(alpha = profile.warmth * .34f))
        )
        if (profile.dimming > 0f) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = profile.dimming))
        )
    }
}

@Composable
internal fun PlayerVisualButtons(
    resizeMode: VideoResizeMode,
    onResize: () -> Unit,
    onControlsFocused: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val (profiles, active) = rememberVideoAppearanceProfiles()
    IconButton(
        onClick = onResize,
        modifier = Modifier.playerControlFocus { onControlsFocused(it) }
    ) { Icon(Icons.Default.AspectRatio, "Resize: ${resizeMode.label}", tint = Color.White) }
    IconButton(
        onClick = {
            val next = profiles[(profiles.indexOfFirst { it.id == active.id }.coerceAtLeast(0) + 1) % profiles.size]
            VideoAppearancePreferences.setActive(context, next.id)
        },
        modifier = Modifier.playerControlFocus { onControlsFocused(it) }
    ) { Icon(Icons.Default.Palette, "Picture mode: ${active.name}", tint = Color.White) }
}
