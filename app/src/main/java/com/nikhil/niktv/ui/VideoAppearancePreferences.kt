package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt

internal enum class VideoResizeMode(val label: String) {
    FIT("Fit"), FILL("Fill"), ZOOM("Zoom"), STRETCH("Stretch");
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal data class VideoAppearanceProfile(
    val id: String,
    val name: String,
    val brightness: Float,
    val warmth: Float,
    val coolness: Float,
    val tint: Float,
    val dimming: Float
)

internal data class VideoAppearanceScheduleEntry(
    val profileId: String,
    val startMinutes: Int,
    val endMinutes: Int
)

internal data class VideoAppearanceSchedule(
    val enabled: Boolean,
    val fallbackProfileId: String,
    val entries: List<VideoAppearanceScheduleEntry>
) {
    fun profileAt(minutesOfDay: Int): String? {
        if (!enabled) return null
        return entries.firstOrNull { it.contains(minutesOfDay) }?.profileId ?: fallbackProfileId
    }
}

private fun VideoAppearanceScheduleEntry.contains(minutesOfDay: Int): Boolean = when {
    startMinutes == endMinutes -> false
    startMinutes < endMinutes -> minutesOfDay in startMinutes until endMinutes
    else -> minutesOfDay >= startMinutes || minutesOfDay < endMinutes
}

internal fun schedulesOverlap(
    first: VideoAppearanceScheduleEntry,
    second: VideoAppearanceScheduleEntry
): Boolean = (0 until 1440).any { first.contains(it) && second.contains(it) }

internal fun videoAppearanceIcon(profileId: String) = when (profileId) {
    "movie" -> Icons.Default.Movie
    "standard" -> Icons.Default.Tune
    "natural" -> Icons.Default.Eco
    "bright" -> Icons.Default.LightMode
    "bedroom" -> Icons.Default.Bed
    "night" -> Icons.Default.Nightlight
    else -> Icons.Default.Palette
}

internal object VideoAppearancePreferences {
    private const val FILE = "video_appearance_profiles"
    private const val ACTIVE = "active"
    private const val SCHEDULE_ENABLED = "schedule_enabled"
    private const val SCHEDULE_FALLBACK = "schedule_fallback_v3"
    private const val SCHEDULE_ENTRIES = "schedule_entries_v3"
    private val defaults = listOf(
        VideoAppearanceProfile("movie", "Movie", .02f, .08f, 0f, 0f, .03f),
        VideoAppearanceProfile("standard", "Standard", 0f, 0f, 0f, 0f, 0f),
        VideoAppearanceProfile("natural", "Natural", .01f, .03f, 0f, -.02f, .01f),
        VideoAppearanceProfile("bright", "Bright room", .12f, 0f, .02f, 0f, 0f),
        VideoAppearanceProfile("bedroom", "Bedroom", 0f, .12f, 0f, 0f, .07f),
        VideoAppearanceProfile("night", "Night light", 0f, .25f, 0f, 0f, .18f),
        VideoAppearanceProfile("custom", "Custom", 0f, 0f, 0f, 0f, 0f)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun profiles(context: Context): List<VideoAppearanceProfile> = defaults.map { preset ->
        val p = prefs(context)
        preset.copy(
            name = p.getString("${preset.id}_name", preset.name).orEmpty().ifBlank { preset.name },
            brightness = p.getInt("${preset.id}_brightness", (preset.brightness * 100).roundToInt()) / 100f,
            warmth = p.getInt("${preset.id}_warmth", (preset.warmth * 100).roundToInt()) / 100f,
            coolness = p.getInt("${preset.id}_coolness", (preset.coolness * 100).roundToInt()) / 100f,
            tint = p.getInt("${preset.id}_tint", (preset.tint * 100).roundToInt()) / 100f,
            dimming = p.getInt("${preset.id}_dimming", (preset.dimming * 100).roundToInt()) / 100f
        )
    }
    fun activeId(context: Context) = prefs(context).getString(ACTIVE, "standard") ?: "standard"
    fun setActive(context: Context, id: String) { prefs(context).edit().putString(ACTIVE, id).apply() }
    private val defaultScheduleEntries = listOf(
        VideoAppearanceScheduleEntry("bright", 6 * 60, 9 * 60),
        VideoAppearanceScheduleEntry("movie", 18 * 60, 22 * 60),
        VideoAppearanceScheduleEntry("night", 22 * 60, 6 * 60)
    )
    fun defaultSchedule(enabled: Boolean = false) = VideoAppearanceSchedule(
        enabled = enabled,
        fallbackProfileId = "standard",
        entries = defaultScheduleEntries
    )
    fun schedule(context: Context): VideoAppearanceSchedule {
        val stored = prefs(context).getString(SCHEDULE_ENTRIES, null)
        val entries = stored?.split(';')?.mapNotNull { encoded ->
            val pieces = encoded.split(',', limit = 3)
            val minutes = pieces.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 1439) ?: return@mapNotNull null
            val endMinutes = pieces.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 1439) ?: return@mapNotNull null
            val profileId = pieces.getOrNull(2)?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            VideoAppearanceScheduleEntry(profileId, minutes, endMinutes)
        }.orEmpty().ifEmpty { defaultScheduleEntries }
        return VideoAppearanceSchedule(
            enabled = prefs(context).getBoolean(SCHEDULE_ENABLED, false),
            fallbackProfileId = prefs(context).getString(SCHEDULE_FALLBACK, "standard") ?: "standard",
            entries = entries
        )
    }
    fun setSchedule(context: Context, schedule: VideoAppearanceSchedule) {
        val normalized = schedule.entries
            .map {
                it.copy(
                    startMinutes = it.startMinutes.coerceIn(0, 1439),
                    endMinutes = it.endMinutes.coerceIn(0, 1439)
                )
            }
        prefs(context).edit()
            .putBoolean(SCHEDULE_ENABLED, schedule.enabled)
            .putString(SCHEDULE_FALLBACK, schedule.fallbackProfileId)
            .putString(SCHEDULE_ENTRIES, normalized.joinToString(";") { "${it.startMinutes},${it.endMinutes},${it.profileId}" })
            .apply()
    }
    fun update(context: Context, profile: VideoAppearanceProfile) {
        prefs(context).edit()
            .putString("${profile.id}_name", profile.name)
            .putInt("${profile.id}_brightness", (profile.brightness.coerceIn(0f, .4f) * 100).roundToInt())
            .putInt("${profile.id}_warmth", (profile.warmth.coerceIn(0f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_coolness", (profile.coolness.coerceIn(0f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_tint", (profile.tint.coerceIn(-1f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_dimming", (profile.dimming.coerceIn(0f, .8f) * 100).roundToInt())
            .apply()
    }
    fun sharedPreferences(context: Context): SharedPreferences = prefs(context)
}

@Composable
internal fun rememberVideoAppearanceProfiles(
    useSchedule: Boolean = false
): Pair<List<VideoAppearanceProfile>, VideoAppearanceProfile> {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        VideoAppearancePreferences.sharedPreferences(context).registerOnSharedPreferenceChangeListener(listener)
        onDispose { VideoAppearancePreferences.sharedPreferences(context).unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val profiles = remember(context, revision) { VideoAppearancePreferences.profiles(context) }
    var clockTick by remember { mutableIntStateOf(0) }
    if (useSchedule) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                delay(30_000L)
                clockTick++
            }
        }
    }
    val schedule = remember(context, revision, clockTick, useSchedule) {
        VideoAppearancePreferences.schedule(context)
    }
    val nowMinutes = remember(clockTick) {
        val now = Calendar.getInstance()
        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }
    val effectiveId = (if (useSchedule) schedule.profileAt(nowMinutes) else null)
        ?: VideoAppearancePreferences.activeId(context)
    val active = profiles.firstOrNull { it.id == effectiveId }
        ?: profiles.first { it.id == "standard" }
    return profiles to active
}

@Composable
internal fun VideoAppearanceOverlay(profile: VideoAppearanceProfile) {
    Box(Modifier.fillMaxSize()) {
        if (profile.brightness > 0f) Box(
            Modifier.fillMaxSize().background(Color.White.copy(alpha = profile.brightness * .28f))
        )
        if (profile.warmth > 0f) Box(
            Modifier.fillMaxSize().background(Color(0xFFFF8A3D).copy(alpha = profile.warmth * .34f))
        )
        if (profile.coolness > 0f) Box(
            Modifier.fillMaxSize().background(Color(0xFF5C8DFF).copy(alpha = profile.coolness * .30f))
        )
        if (profile.tint != 0f) Box(
            Modifier.fillMaxSize().background(
                (if (profile.tint > 0f) Color(0xFFFF5CB8) else Color(0xFF55D68A))
                    .copy(alpha = kotlin.math.abs(profile.tint) * .18f)
            )
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
    profiles: List<VideoAppearanceProfile>,
    activeProfile: VideoAppearanceProfile,
    onPictureMode: (VideoAppearanceProfile) -> Unit,
    resizeRequester: FocusRequester,
    pictureModeRequester: FocusRequester,
    leftRequester: FocusRequester,
    rightRequester: FocusRequester,
    downRequester: FocusRequester,
    onControlsFocused: (Boolean) -> Unit
) {
    IconButton(
        onClick = onResize,
        modifier = Modifier.focusRequester(resizeRequester)
            .focusProperties {
                left = leftRequester
                right = pictureModeRequester
                down = downRequester
            }
            .playerControlFocus { onControlsFocused(it) }
    ) {
        Icon(
            when (resizeMode) {
                VideoResizeMode.FIT -> Icons.Default.FitScreen
                VideoResizeMode.FILL -> Icons.Default.CropFree
                VideoResizeMode.ZOOM -> Icons.Default.ZoomIn
                VideoResizeMode.STRETCH -> Icons.Default.AspectRatio
            },
            "Resize: ${resizeMode.label}",
            tint = Color.White
        )
    }
    IconButton(
        onClick = {
            val next = profiles[(profiles.indexOfFirst { it.id == activeProfile.id }.coerceAtLeast(0) + 1) % profiles.size]
            onPictureMode(next)
        },
        modifier = Modifier.focusRequester(pictureModeRequester)
            .focusProperties {
                left = resizeRequester
                right = rightRequester
                down = downRequester
            }
            .playerControlFocus { onControlsFocused(it) }
    ) {
        Icon(
            videoAppearanceIcon(activeProfile.id),
            "Picture mode: ${activeProfile.name}",
            tint = Color.White
        )
    }
}

@Composable
internal fun PlayerModeFeedback(label: String) {
    Box(
        Modifier.fillMaxSize().padding(top = 82.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = Color.Black.copy(alpha = .78f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
