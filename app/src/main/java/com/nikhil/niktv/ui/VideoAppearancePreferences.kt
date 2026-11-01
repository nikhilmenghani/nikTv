package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.currentLiveProgramme
import com.nikhil.niktv.model.isMissingLiveProgrammeTitle
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shadow
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.launch
import com.nikhil.niktv.data.artworkRequest

internal enum class VideoResizeMode(val label: String, val badge: String) {
    FIT("Fit", "FIT"),
    FILL("Fill", "FILL"),
    ZOOM("Zoom", "ZOOM"),
    STRETCH("Stretch 16:9", "16:9");
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
    fun activeEntryAt(minutesOfDay: Int): VideoAppearanceScheduleEntry? {
        if (!enabled) return null
        return entries.firstOrNull { it.contains(minutesOfDay) }
    }

    fun profileAt(minutesOfDay: Int): String? {
        if (!enabled) return null
        return activeEntryAt(minutesOfDay)?.profileId ?: fallbackProfileId
    }
}

private fun VideoAppearanceScheduleEntry.contains(minutesOfDay: Int): Boolean = when {
    startMinutes == endMinutes -> false
    startMinutes < endMinutes -> minutesOfDay in startMinutes until endMinutes
    else -> minutesOfDay >= startMinutes || minutesOfDay < endMinutes
}

/*
 * PERSISTENT_PICTURE_MODE_SCHEDULE_V36
 *
 * A schedule entry overrides the player's persisted manual mode only once per
 * occurrence. If the user changes mode after that, the manual choice remains
 * active until a later scheduled window begins.
 *
 * The occurrence date is based on the entry's local start date. Overnight
 * windows therefore keep one identity across midnight but get a new identity
 * the next day.
 */
private fun VideoAppearanceScheduleEntry.occurrenceKey(now: Calendar): String {
    val nowMinutes =
        now.get(Calendar.HOUR_OF_DAY) * 60 +
            now.get(Calendar.MINUTE)
    val startDay = now.clone() as Calendar

    if (startMinutes > endMinutes && nowMinutes < endMinutes) {
        startDay.add(Calendar.DAY_OF_YEAR, -1)
    }

    return buildString {
        append(startDay.get(Calendar.YEAR))
        append(':')
        append(startDay.get(Calendar.DAY_OF_YEAR))
        append(':')
        append(startMinutes)
        append(':')
        append(endMinutes)
        append(':')
        append(profileId)
    }
}

internal fun schedulesOverlap(
    first: VideoAppearanceScheduleEntry,
    second: VideoAppearanceScheduleEntry
): Boolean = (0 until 1440).any { first.contains(it) && second.contains(it) }

internal fun videoAppearanceIcon(profileId: String) = when (profileId) {
    // DEFAULT_UNFILTERED_ICON_V37
    "default" -> Icons.Default.BrightnessMedium
    "movie" -> Icons.Default.Movie
    "standard" -> Icons.Default.Tv
    "natural" -> Icons.Default.Eco
    "bright" -> Icons.Default.LightMode
    "bedroom" -> Icons.Default.Bed
    "night" -> Icons.Default.Nightlight
    else -> Icons.Default.Palette
}

internal object VideoAppearancePreferences {
    private const val FILE = "video_appearance_profiles"
    private const val RECOMMENDED_PRESET_SCHEMA = "recommended_preset_schema"
    private const val CURRENT_RECOMMENDED_PRESET_SCHEMA = 2
    private const val ACTIVE = "active"
    private const val SCHEDULE_APPLIED_OCCURRENCE =
        "schedule_applied_occurrence_v4"
    private const val SCHEDULE_ENABLED = "schedule_enabled"
    private const val SCHEDULE_FALLBACK = "schedule_fallback_v3"
    private const val SCHEDULE_ENTRIES = "schedule_entries_v3"
    private val defaults = listOf(
        // DEFAULT_UNFILTERED_PICTURE_MODE_V36
        // Hard-coded neutral values; this profile is never loaded from or
        // written to editable preference keys.
        VideoAppearanceProfile("default", "Default", 0f, 0f, 0f, 0f, 0f),
        VideoAppearanceProfile("movie", "Movie", -.03f, .10f, 0f, .01f, .02f),
        VideoAppearanceProfile("standard", "Standard", 0f, 0f, 0f, 0f, 0f),
        VideoAppearanceProfile("natural", "Natural", -.02f, .04f, .01f, -.02f, 0f),
        VideoAppearanceProfile("bright", "Bright room", .16f, 0f, .03f, 0f, 0f),
        VideoAppearanceProfile("bedroom", "Bedroom", -.08f, .14f, 0f, 0f, .07f),
        VideoAppearanceProfile("night", "Night light", -.18f, .28f, 0f, 0f, .18f),
        VideoAppearanceProfile("custom", "Custom", 0f, 0f, 0f, 0f, 0f)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val editablePreferenceSuffixes = listOf(
        "name", "brightness", "warmth", "coolness", "tint", "dimming"
    )

    private fun applyRecommendedPresetMigration(context: Context) {
        val p = prefs(context)
        if (
            p.getInt(RECOMMENDED_PRESET_SCHEMA, 0) >=
            CURRENT_RECOMMENDED_PRESET_SCHEMA
        ) return

        val editor = p.edit()
        defaults.filterNot { it.id == "default" }.forEach { profile ->
            editablePreferenceSuffixes.forEach { suffix ->
                editor.remove("${profile.id}_$suffix")
            }
        }
        editor.putInt(
            RECOMMENDED_PRESET_SCHEMA,
            CURRENT_RECOMMENDED_PRESET_SCHEMA
        ).apply()
    }

    fun resetRecommendedProfiles(context: Context) {
        val editor = prefs(context).edit()
        defaults.filterNot { it.id == "default" }.forEach { profile ->
            editablePreferenceSuffixes.forEach { suffix ->
                editor.remove("${profile.id}_$suffix")
            }
        }
        editor.putInt(
            RECOMMENDED_PRESET_SCHEMA,
            CURRENT_RECOMMENDED_PRESET_SCHEMA
        ).apply()
    }

    fun profiles(context: Context): List<VideoAppearanceProfile> {
        applyRecommendedPresetMigration(context)
        return defaults.map { preset ->
        if (preset.id == "default") {
            preset
        } else {
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
      }
    }

    fun activeId(context: Context): String =
        prefs(context).getString(ACTIVE, "default") ?: "default"

    fun setActive(context: Context, id: String) {
        if (defaults.none { it.id == id }) return
        prefs(context).edit().putString(ACTIVE, id).apply()
    }

    fun appliedScheduleOccurrence(context: Context): String? =
        prefs(context).getString(SCHEDULE_APPLIED_OCCURRENCE, null)

    fun applyScheduledOccurrence(
        context: Context,
        profileId: String,
        occurrenceKey: String
    ) {
        if (defaults.none { it.id == profileId }) return
        val p = prefs(context)
        if (p.getString(SCHEDULE_APPLIED_OCCURRENCE, null) == occurrenceKey) {
            return
        }
        p.edit()
            .putString(ACTIVE, profileId)
            .putString(SCHEDULE_APPLIED_OCCURRENCE, occurrenceKey)
            .apply()
    }

    private val defaultScheduleEntries = listOf(
        VideoAppearanceScheduleEntry("bright", 6 * 60, 9 * 60),
        VideoAppearanceScheduleEntry("movie", 18 * 60, 22 * 60),
        VideoAppearanceScheduleEntry("night", 22 * 60, 6 * 60)
    )
    fun defaultSchedule(enabled: Boolean = false) = VideoAppearanceSchedule(
        enabled = enabled,
        // Retained for preference-format compatibility only. Unscheduled time
        // now keeps the persisted player choice instead of applying fallback.
        fallbackProfileId = "default",
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
            .also { editor ->
                if (!schedule.enabled) {
                    editor.remove(SCHEDULE_APPLIED_OCCURRENCE)
                }
            }
            .apply()
    }
    fun update(context: Context, profile: VideoAppearanceProfile) {
        // Default is an immutable unfiltered reference mode.
        if (profile.id == "default") return
        prefs(context).edit()
            .putString("${profile.id}_name", profile.name)
            .putInt("${profile.id}_brightness", (profile.brightness.coerceIn(-1f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_warmth", (profile.warmth.coerceIn(0f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_coolness", (profile.coolness.coerceIn(0f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_tint", (profile.tint.coerceIn(-1f, 1f) * 100).roundToInt())
            .putInt("${profile.id}_dimming", (profile.dimming.coerceIn(0f, 1f) * 100).roundToInt())
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
    val now = remember(clockTick) { Calendar.getInstance() }
    val nowMinutes = remember(now) {
        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }
    val scheduledEntry = remember(schedule, nowMinutes, useSchedule) {
        if (useSchedule) schedule.activeEntryAt(nowMinutes) else null
    }
    val scheduledOccurrenceKey = remember(scheduledEntry, now) {
        scheduledEntry?.occurrenceKey(now)
    }
    val occurrenceAlreadyApplied = remember(
        context,
        revision,
        scheduledOccurrenceKey
    ) {
        scheduledOccurrenceKey != null &&
            VideoAppearancePreferences.appliedScheduleOccurrence(context) ==
                scheduledOccurrenceKey
    }

    /*
     * A scheduled window wins once when that occurrence starts. Afterwards,
     * setActive() is free to persist a manual player override. Leaving a
     * scheduled window does not apply a fallback; it simply arms the next
     * scheduled occurrence.
     */
    LaunchedEffect(
        context,
        useSchedule,
        schedule.enabled,
        scheduledEntry?.profileId,
        scheduledOccurrenceKey
    ) {
        if (
            useSchedule &&
            schedule.enabled &&
            scheduledEntry != null &&
            scheduledOccurrenceKey != null
        ) {
            VideoAppearancePreferences.applyScheduledOccurrence(
                context,
                scheduledEntry.profileId,
                scheduledOccurrenceKey
            )
        }
    }

    val persistedId = VideoAppearancePreferences.activeId(context)
    val effectiveId =
        if (scheduledEntry != null && !occurrenceAlreadyApplied) {
            scheduledEntry.profileId
        } else {
            persistedId
        }
    val active = profiles.firstOrNull { it.id == effectiveId }
        ?: profiles.first { it.id == "default" }
    return profiles to active
}

@Composable
internal fun VideoAppearanceOverlay(profile: VideoAppearanceProfile) {
    Box(Modifier.fillMaxSize()) {
        if (profile.brightness > 0f) Box(
            Modifier.fillMaxSize().background(Color.White.copy(alpha = profile.brightness * .28f))
        )
        if (profile.brightness < 0f) Box(
            Modifier.fillMaxSize().background(
                Color.Black.copy(alpha = kotlin.math.abs(profile.brightness) * .45f)
            )
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
    onEditPictureMode: () -> Unit,
    resizeRequester: FocusRequester,
    pictureModeRequester: FocusRequester,
    pictureSettingsRequester: FocusRequester,
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
            .playerDpadFocusRoutes(leftRequester, pictureModeRequester, downRequester)
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
            val current = profiles.indexOfFirst { it.id == activeProfile.id }.coerceAtLeast(0)
            onPictureMode(profiles[(current + 1) % profiles.size])
        },
        modifier = Modifier.focusRequester(pictureModeRequester)
            .focusProperties {
                left = resizeRequester
                right = pictureSettingsRequester
                down = downRequester
            }
            .playerDpadFocusRoutes(resizeRequester, pictureSettingsRequester, downRequester)
            .playerControlFocus { onControlsFocused(it) }
    ) {
        Icon(
            videoAppearanceIcon(activeProfile.id),
            "Picture mode: ${activeProfile.name}. Select next mode",
            tint = Color.White
        )
    }
    IconButton(
        onClick = onEditPictureMode,
        modifier = Modifier.focusRequester(pictureSettingsRequester)
            .focusProperties {
                left = pictureModeRequester
                right = rightRequester
                down = downRequester
            }
            .playerDpadFocusRoutes(pictureModeRequester, rightRequester, downRequester)
            .playerControlFocus { onControlsFocused(it) }
    ) {
        Icon(Icons.Default.Tune, "Edit picture mode settings", tint = Color.White)
    }
}

/**
 * Fire TV occasionally falls back to geometry-based focus search after the
 * player controls recompose. Route the top control strip explicitly so every
 * D-pad press has a stable destination.
 */
internal fun Modifier.playerDpadFocusRoutes(
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    down: FocusRequester? = null,
    up: FocusRequester? = null
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val destination = when (event.key) {
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        Key.DirectionDown -> down
        Key.DirectionUp -> up
        else -> null
    } ?: return@onPreviewKeyEvent false
    runCatching { destination.requestFocus() }.getOrDefault(false)
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

@Composable
internal fun MobileQueueSwipeHandle(
    visible: Boolean,
    controlsVisible: Boolean,
    onOpen: () -> Unit
) {
    if (!visible) return

    var upwardDrag by remember { mutableFloatStateOf(0f) }
    var opened by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (controlsVisible) 108.dp else 18.dp)
                .width(112.dp)
                .height(38.dp)
                .pointerInput(onOpen) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            upwardDrag = 0f
                            opened = false
                        },
                        onVerticalDrag = { change, amount ->
                            if (amount < 0f) upwardDrag -= amount
                            if (!opened && upwardDrag >= 40.dp.toPx()) {
                                opened = true
                                change.consume()
                                onOpen()
                            }
                        },
                        onDragEnd = {
                            upwardDrag = 0f
                            opened = false
                        },
                        onDragCancel = {
                            upwardDrag = 0f
                            opened = false
                        }
                    )
                }
        )
    }
}


@Composable
private fun QueueArtworkFallback(item: MediaItem) {
    val accent = remember(item.id) {
        listOf(
            Color(0xFF446A81), Color(0xFF526C70), Color(0xFF665C80),
            Color(0xFF76664F), Color(0xFF526782)
        )[(item.id.hashCode() and Int.MAX_VALUE) % 5]
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(accent.copy(alpha = .88f), Color(0xFF161C25)))
        )
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun PlayerQueueOverlay(
    items: List<MediaItem>,
    playingId: String,
    alternateSeries: MediaItem? = null,
    pinnedIds: List<String> = emptyList(),
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((MediaItem) -> Unit)? = null,
    onTogglePin: ((MediaItem) -> Unit)? = null,
    onRefreshProgramme: ((MediaItem) -> Unit)? = null,
    onVisibleChannels: (List<MediaItem>) -> Unit = {},
    liveCategoryTitle: String = "",
    liveXtream: Boolean = false,
    availabilityProfile: String = "",
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Boolean = { false },
    revealProgress: Float = 1f,
    revealDragging: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (MediaItem, Int) -> Unit
) {
    BackHandler(onBack = onDismiss)
    val availability = rememberIptvAvailability(availabilityProfile)

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    /*
     * PLAYER_QUEUE_LAYOUT_V41
     *
     * Media3/ExoPlayer and VLC continue to share one queue surface. Phones use
     * a touch-first, single-row horizontal rail so the swipe-up queue keeps
     * more video visible. Tablets and TV devices keep the existing vertical
     * grid and its explicit same-column D-pad navigation.
     */
    val tvQueueGrid =
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK
        ) ||
            (
                configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_TYPE_MASK
                ) ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            !context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
            )
    val queueLayout = rememberPlayerQueueLayout()
    val listQueue = queueLayout == PlayerQueueLayout.LIST
    val phoneQueue = !tvQueueGrid && configuration.smallestScreenWidthDp < 600
    val listPanelWidth = if (phoneQueue) {
        configuration.screenWidthDp.dp * if (configuration.screenWidthDp < 600) .86f else .38f
    } else {
        (configuration.screenWidthDp.dp * .30f).coerceIn(260.dp, 330.dp)
    }
    val touchQueueRail = !tvQueueGrid && !listQueue
    val liveChannelQueue = onTogglePin != null
    val compactQueueGrid =
        touchQueueRail && configuration.smallestScreenWidthDp < 600
    val queueColumnCount = when {
        listQueue -> 1
        tvQueueGrid -> 3
        compactQueueGrid -> 3
        configuration.screenWidthDp < 900 -> 4
        else -> 5
    }
    /*
     * COMPACT_PLAYER_QUEUE_CARD_V42
     *
     * The phone queue is a single row, so it should size to that row instead
     * of retaining the old multi-row sheet height. The wider/taller card gives
     * episode artwork a useful canvas and enough room for readable metadata.
     */
    val touchQueueSheetHeight =
        (configuration.screenHeightDp * (if (compactQueueGrid) .30f else .27f)).dp.coerceIn(
            if (compactQueueGrid) 132.dp else 145.dp,
            if (compactQueueGrid) 158.dp else 172.dp
        ).let { (it + 32.dp).coerceAtLeast(168.dp) } // Header and focus gutters; preserve card height.
    val queueSheetMinHeight = when {
        listQueue -> 200.dp
        tvQueueGrid -> 200.dp
        touchQueueRail -> touchQueueSheetHeight
        else -> 260.dp
    }
    val desiredQueueSheetMaxHeight =
        (
            configuration.screenHeightDp *
                when {
                    // Keep roughly two channel rows visible, leaving the video above exposed.
                    tvQueueGrid && liveChannelQueue -> if (liveXtream) .52f else .46f
                    tvQueueGrid -> .48f
                    compactQueueGrid -> .64f
                    else -> .54f
                }
            ).dp
    val queueSheetMaxHeight = when {
        listQueue -> (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(200.dp)
        tvQueueGrid -> 232.dp
        touchQueueRail -> touchQueueSheetHeight
        desiredQueueSheetMaxHeight > queueSheetMinHeight ->
            desiredQueueSheetMaxHeight
        else -> queueSheetMinHeight
    }
    val queueOuterHorizontalPadding = when {
        listQueue -> 10.dp
        tvQueueGrid -> 18.dp
        touchQueueRail -> 8.dp
        else -> 28.dp
    }
    val queueOuterVerticalPadding = when {
        listQueue -> 8.dp
        tvQueueGrid -> 8.dp
        touchQueueRail -> 6.dp
        else -> 22.dp
    }
    val queueDismissBoundary =
        queueSheetMaxHeight + queueOuterVerticalPadding
    val queueSheetPadding = when {
        listQueue -> 10.dp
        tvQueueGrid -> 10.dp
        compactQueueGrid -> 10.dp
        touchQueueRail -> 10.dp
        else -> 20.dp
    }
    val queueSheetCornerRadius =
        if (touchQueueRail) 18.dp else 24.dp
    val queueHeaderSpacer = when {
        listQueue -> 6.dp
        tvQueueGrid -> 4.dp
        touchQueueRail -> 3.dp
        else -> 14.dp
    }
    val queueGridHorizontalSpacing = when {
        tvQueueGrid -> 8.dp
        touchQueueRail -> 10.dp
        else -> 12.dp
    }
    val queueGridVerticalSpacing = when {
        listQueue -> 6.dp
        tvQueueGrid -> 7.dp
        touchQueueRail -> 8.dp
        else -> 12.dp
    }
    val queueCardHeight = when {
        listQueue -> 54.dp
        tvQueueGrid -> 72.dp
        else -> (touchQueueSheetHeight - 72.dp).coerceAtLeast(96.dp)
    }
    // Touch queues are a single horizontal rail. Size cards from the usable
    // width so four full tiles remain visible while programme titles get more
    // room than the old height-derived cards allowed.
    val compactQueueCardWidth = if (touchQueueRail) {
        (
            configuration.screenWidthDp.dp -
                (queueOuterHorizontalPadding * 2) -
                (queueSheetPadding * 2) -
                8.dp - // Both ends need room for the focused tile's scale/border.
                (queueGridHorizontalSpacing * 3)
            ) / 4f
    } else {
        queueCardHeight * 1.65f
    }

    val scope = rememberCoroutineScope()
    val uniqueItems = remember(items, pinnedIds) {
        orderedPlayerQueue(items, pinnedIds)
    }
    fun entryKey(index: Int) = "$index:${uniqueItems[index].id}"
    val isEpisodeQueue = uniqueItems.any {
        it.seasonNumber != null || it.episodeNumber != null
    }
    val earliestEpisodeNumber = if (isEpisodeQueue) {
        uniqueItems.mapNotNull { it.episodeNumber }.minOrNull()
    } else null
    val hasEarlierEpisodes = earliestEpisodeNumber != null && earliestEpisodeNumber > 1
    val effectiveHasMore = hasMore || hasEarlierEpisodes
    val showLoadMore = effectiveHasMore || loadingMore
    val leadingLoadPrevious = isEpisodeQueue && (
        hasEarlierEpisodes || (earliestEpisodeNumber == null && touchQueueRail && showLoadMore)
    )
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val loadMoreRequester = remember { FocusRequester() }
    val queueFocusParking = remember { FocusRequester() }
    val layoutToggleRequester = remember { FocusRequester() }
    var layoutToggleFocused by remember { mutableStateOf(false) }
    var previousQueueLayout by remember { mutableStateOf(queueLayout) }
    val currentIndex =
        uniqueItems.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    val initialVisibleItem =
        if (touchQueueRail) {
            currentIndex + if (leadingLoadPrevious) 1 else 0
        } else {
            (currentIndex / queueColumnCount) * queueColumnCount
        }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialVisibleItem
    )

    var focusedIndex by remember(playingId) { mutableIntStateOf(currentIndex) }
    var focusMoveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var previousItemIds by remember {
        mutableStateOf(uniqueItems.mapTo(linkedSetOf()) { it.id })
    }
    var initialFocusApplied by remember(playingId) { mutableStateOf(false) }
    var gridReady by remember(playingId, tvQueueGrid) {
        mutableStateOf(!tvQueueGrid)
    }
    var loadMoreRequested by remember { mutableStateOf(false) }
    var focusFirstLoadedItem by remember { mutableStateOf(false) }
    var favoriteMenuItemId by remember { mutableStateOf<String?>(null) }
    var observedLoading by remember { mutableStateOf(false) }
    var autoAppendPending by remember(playingId) { mutableStateOf(false) }
    var autoAppendRetry by remember(playingId) { mutableStateOf(false) }
    var autoAppendBatch by remember(playingId) { mutableStateOf<List<String>?>(null) }
    var guideNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val renderedReveal by animateFloatAsState(
        targetValue = if (gridReady) revealProgress.coerceIn(0f, 1f) else 0f,
        animationSpec = if (revealDragging) snap() else tween(220),
        label = "playerQueueReveal"
    )

    val currentQueueItems by rememberUpdatedState(uniqueItems)
    val currentVisibleCallback by rememberUpdatedState(onVisibleChannels)
    LaunchedEffect(gridState, liveChannelQueue) {
        if (!liveChannelQueue) return@LaunchedEffect
        snapshotFlow {
            Triple(gridState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                currentQueueItems.getOrNull(visible.index - if (leadingLoadPrevious) 1 else 0)
            }, gridState.isScrollInProgress, guideNow)
        }.collect { (visible, scrolling, _) ->
            if (!scrolling) {
                delay(180L)
                if (!gridState.isScrollInProgress) currentVisibleCallback(visible)
            }
        }
    }
    DisposableEffect(liveChannelQueue) {
        onDispose { if (liveChannelQueue) currentVisibleCallback(emptyList()) }
    }

    // Movie/channel pages append before the user reaches the end. Keep the
    // pagination control out of the grid unless an automatic attempt failed:
    // replacing a focused footer with media otherwise moves the viewport.
    val queueIds = remember(uniqueItems) { uniqueItems.map { it.id } }
    val showPaginationTile = if (isEpisodeQueue) showLoadMore || loadMoreRequested
        else autoAppendRetry && (showLoadMore || loadMoreRequested)
    PrefetchCatalogPage(
        scope = "player-queue:$playingId",
        ids = queueIds,
        enabled = !isEpisodeQueue && gridReady && effectiveHasMore &&
            !autoAppendRetry && favoriteMenuItemId == null,
        loading = loadingMore || autoAppendPending || loadMoreRequested,
        grid = gridState,
        loadMore = {
            autoAppendBatch = queueIds
            autoAppendPending = true
            if (!onLoadMore()) {
                autoAppendPending = false
                autoAppendRetry = true
            }
        }
    )
    LaunchedEffect(autoAppendPending, loadingMore, queueIds) {
        if (autoAppendPending && !loadingMore) {
            // Also handles a page served from memory before a loading frame,
            // or a provider response which contains no additional media IDs.
            delay(180L)
            autoAppendRetry = queueIds == autoAppendBatch
            autoAppendPending = false
        }
    }

    LaunchedEffect(liveChannelQueue) {
        while (liveChannelQueue) {
            guideNow = System.currentTimeMillis()
            delay(5_000L)
        }
    }

    /*
     * Season pages can arrive newest-first from IPTV while the player presents
     * them chronologically. Reaching the earliest loaded episode therefore
     * requests the next provider page, which reveals older episode numbers on
     * the left instead of requiring a right-side Load More detour.
     */
    LaunchedEffect(
        focusedIndex,
        isEpisodeQueue,
        hasMore,
        loadingMore,
        loadMoreRequested
    ) {
        if (
            isEpisodeQueue &&
            focusedIndex == 0 &&
            !leadingLoadPrevious &&
            hasMore &&
            !loadingMore &&
            !loadMoreRequested &&
            !focusFirstLoadedItem
        ) {
            focusFirstLoadedItem = true
            loadMoreRequested = onLoadMore()
            if (!loadMoreRequested) focusFirstLoadedItem = false
        }
    }

    fun focusAt(index: Int) {
        val maxIndex =
            if (showPaginationTile && !leadingLoadPrevious) uniqueItems.size else uniqueItems.lastIndex
        if (maxIndex < 0) return

        val minIndex = if (leadingLoadPrevious) -1 else 0
        val target = index.coerceIn(minIndex, maxIndex)
        focusedIndex = target
        focusMoveJob?.cancel()

        val requester =
            if (target < 0 || target >= uniqueItems.size) {
                loadMoreRequester
            } else {
                requesters.getOrPut(entryKey(target)) {
                    FocusRequester()
                }
            }
        val layoutIndex = target + if (leadingLoadPrevious) 1 else 0
        val alreadyVisible =
            gridState.layoutInfo.visibleItemsInfo.any {
                it.index == layoutIndex
            }

        if (
            alreadyVisible &&
            runCatching { requester.requestFocus() }.getOrDefault(false)
        ) {
            return
        }

        focusMoveJob = scope.launch {
            val scrollIndex = if (tvQueueGrid) (layoutIndex / queueColumnCount) * queueColumnCount else layoutIndex
            if (tvQueueGrid) gridState.animateScrollToItem(scrollIndex)
            else gridState.scrollToItem(scrollIndex)
            repeat(4) {
                withFrameNanos { }
                if (
                    runCatching {
                        requester.requestFocus()
                    }.getOrDefault(false)
                ) {
                    return@launch
                }
            }
        }
    }

    LaunchedEffect(queueLayout) {
        if (queueLayout == previousQueueLayout) return@LaunchedEffect
        previousQueueLayout = queueLayout
        focusMoveJob?.cancel()
        val target = focusedIndex.coerceIn(0, uniqueItems.lastIndex.coerceAtLeast(0))
        if (uniqueItems.isNotEmpty()) gridState.scrollToItem(target + if (leadingLoadPrevious) 1 else 0)
        repeat(4) {
            withFrameNanos { }
            if (runCatching { layoutToggleRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    LaunchedEffect(playingId, uniqueItems.isNotEmpty()) {
        if (!initialFocusApplied && uniqueItems.isNotEmpty()) {
            initialFocusApplied = true
            val target =
                uniqueItems.indexOfFirst { it.id == playingId }
                    .coerceAtLeast(0)
            focusedIndex = target
            gridState.scrollToItem(target + if (leadingLoadPrevious) 1 else 0)
            val requester = requesters.getOrPut(entryKey(target)) {
                FocusRequester()
            }
            repeat(4) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                    gridReady = true
                    return@LaunchedEffect
                }
            }
            gridReady = true
        }
    }

    LaunchedEffect(loadingMore) {
        if (loadMoreRequested) {
            if (loadingMore) {
                observedLoading = true
            } else if (observedLoading) {
                observedLoading = false
                // Let the item-ID effect claim successful pagination first.
                // This fallback runs only when the provider returned no new IDs.
                delay(160L)
                if (focusFirstLoadedItem) {
                    focusFirstLoadedItem = false
                    loadMoreRequested = false
                    if (uniqueItems.isNotEmpty()) {
                        focusAt(
                            if (hasMore && !leadingLoadPrevious) {
                                uniqueItems.size
                            } else {
                                if (leadingLoadPrevious) 0 else uniqueItems.lastIndex
                            }
                        )
                    }
                } else {
                    loadMoreRequested = false
                }
            }
        }
    }

    LaunchedEffect(uniqueItems.map { it.id }) {
        val oldIds = previousItemIds
        val oldFirstId = oldIds.firstOrNull()
        val firstNewIndex = uniqueItems.indexOfFirst { it.id !in oldIds }
        previousItemIds = uniqueItems.mapTo(linkedSetOf()) { it.id }

        if (focusFirstLoadedItem && firstNewIndex >= 0) {
            autoAppendRetry = false
            focusFirstLoadedItem = false
            loadMoreRequested = false
            observedLoading = false
            val targetIndex = if (isEpisodeQueue && oldFirstId != null) {
                (uniqueItems.indexOfFirst { it.id == oldFirstId } - 1)
                    .takeIf { it >= 0 }
                    ?: firstNewIndex
            } else {
                firstNewIndex
            }
            focusedIndex = targetIndex
            val requester = requesters.getOrPut(entryKey(targetIndex)) { FocusRequester() }
            repeat(4) {
                if (
                    runCatching {
                        requester.requestFocus()
                    }.getOrDefault(false)
                ) {
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }
    }

    LaunchedEffect(showPaginationTile, uniqueItems.size) {
        if (
            !showPaginationTile &&
            uniqueItems.isNotEmpty() &&
            focusedIndex >= uniqueItems.size
        ) {
            delay(40L)
            focusAt(uniqueItems.lastIndex)
        }
    }

    @Composable
    fun QueueMediaTile(index: Int, item: MediaItem) {
        val requester =
            requesters.getOrPut(entryKey(index)) { FocusRequester() }
        var focused by remember(entryKey(index)) { mutableStateOf(false) }
        val current = item.id == playingId

        if (liveChannelQueue && !tvQueueGrid && !listQueue) {
            ModernLiveChannelTile(
                item = item,
                availabilityProfile = availabilityProfile,
                onRefreshProgramme = { onRefreshProgramme?.invoke(item) },
                xtream = liveXtream,
                categoryTitle = liveCategoryTitle,
                guideNow = guideNow,
                programmeLoading = false,
                programmeWaitingUntil = null,
                programmeStatusNow = guideNow,
                showQualityBadge = true,
                isFavorite = item.id in favoriteIds,
                onFavorite = { onToggleFavorite?.invoke(item) },
                isPinned = item.id in pinnedIds,
                onTogglePin = onTogglePin?.let { pin -> { pin(item) } },
                onClick = { onSelect(item, index) },
                modifier = Modifier
                    .then(
                        if (touchQueueRail) {
                            Modifier.width(compactQueueCardWidth)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                    .height(queueCardHeight)
                    .focusRequester(requester),
                isTv = tvQueueGrid,
                onFocusChange = { isFocused ->
                    focused = isFocused
                    if (isFocused) focusedIndex = index
                },
                onMenuVisibilityChange = { visible ->
                    favoriteMenuItemId = item.id.takeIf { visible }
                }
            )
            return
        }

        val cardShape =
            androidx.compose.foundation.shape.RoundedCornerShape(
                if (touchQueueRail) 14.dp else 12.dp
            )

        Box {
            Surface(
                Modifier
                    .then(
                        if (touchQueueRail) {
                            Modifier.width(compactQueueCardWidth)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                    .height(queueCardHeight)
                    .clip(cardShape)
                    .focusRequester(requester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) focusedIndex = index
                    }
                    .remoteCombinedClickable(
                        onClick = { onSelect(item, index) },
                        onLongClick = if (
                            onToggleFavorite != null ||
                            onTogglePin != null ||
                            onRefreshProgramme != null
                        ) {
                            { favoriteMenuItemId = item.id }
                        } else null
                    )
                    .focusable(),
                color = when {
                    else -> if (listQueue) Color(0x66171A20) else Color(0xFF171A20)
                },
                border = if (focused) {
                    androidx.compose.foundation.BorderStroke(
                        2.5.dp,
                        Color.White
                    )
                } else {
                    androidx.compose.foundation.BorderStroke(1.dp, if (item.id in availability.flags) IptvAvailabilityAmber else Color(0xFF35383F))
                },
                shape = cardShape
            ) {
                if (tvQueueGrid || listQueue) {
                    CompactPlayerQueueContent(item, current, liveChannelQueue, liveCategoryTitle, liveXtream,
                        guideNow, item.id in favoriteIds, item.id in pinnedIds, availability.flags[item.id], compactList = listQueue)
                } else PlayerMediaTileContent(item = item, current = current, isTv = tvQueueGrid)

            }

            if (
                onToggleFavorite != null ||
                onTogglePin != null ||
                onRefreshProgramme != null
            ) {
                ModernTileActionsMenu(
                    expanded = favoriteMenuItemId == item.id,
                    isFavorite = item.id in favoriteIds,
                    dismiss = { favoriteMenuItemId = null },
                    toggleFavorite = { onToggleFavorite?.invoke(item) },
                    clear = null,
                    availabilityFlag = availability.flags[item.id],
                    toggleAvailability = if (availabilityProfile.isBlank() || !liveChannelQueue) null else ({ availability.toggle(item.id) }),
                    isPinned = item.id in pinnedIds,
                    togglePin = onTogglePin?.let { pin -> { pin(item) } },
                    refreshProgramme = onRefreshProgramme?.let { refresh -> { refresh(item) } },
                    recommendationItem = item,
                    alternateSource = if (!liveChannelQueue) alternateSourceRequest(item,
                        if (isEpisodeQueue) com.nikhil.niktv.model.CatalogType.SERIES else com.nikhil.niktv.model.CatalogType.MOVIES,
                        series = alternateSeries) else null,
                    recommendationType = com.nikhil.niktv.model.CatalogType.LIVE_TV
                )
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && favoriteMenuItemId == null) {
                    // Dismiss on release so the same key-up cannot reach the
                    // underlying player's Back handler and leave playback.
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    return@onPreviewKeyEvent true
                }
                if (layoutToggleFocused && event.type == KeyEventType.KeyDown && favoriteMenuItemId == null) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> { focusAt(focusedIndex); true }
                        Key.DirectionUp, Key.DirectionLeft -> { onDismiss(); true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                }
                if (
                    event.type != KeyEventType.KeyDown ||
                    favoriteMenuItemId != null
                ) {
                    false
                } else {
                    val maxIndex =
                        if (showPaginationTile && !leadingLoadPrevious) {
                            uniqueItems.size
                        } else {
                            uniqueItems.lastIndex
                        }
                    if (touchQueueRail) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                runCatching { layoutToggleRequester.requestFocus() }
                                true
                            }

                            Key.DirectionLeft -> {
                                if (focusedIndex > if (leadingLoadPrevious) -1 else 0) {
                                    focusAt(focusedIndex - 1)
                                }
                                true
                            }

                            Key.DirectionRight -> {
                                val target = focusedIndex + 1
                                if (target <= maxIndex) {
                                    focusAt(target)
                                }
                                true
                            }

                            Key.DirectionDown -> true

                            else -> false
                        }
                    } else {
                        val layoutIndex =
                            focusedIndex + if (leadingLoadPrevious) 1 else 0
                        val currentColumn =
                            if (layoutIndex >= 0) {
                                layoutIndex % queueColumnCount
                            } else {
                                0
                            }

                        when (event.key) {
                            Key.DirectionUp -> {
                                if (layoutIndex < queueColumnCount) {
                                    runCatching { layoutToggleRequester.requestFocus() }
                                } else {
                                    focusAt(focusedIndex - queueColumnCount)
                                }
                                true
                            }

                            Key.DirectionLeft -> {
                                if (listQueue) onDismiss()
                                else if (currentColumn > 0 && focusedIndex > if (leadingLoadPrevious) -1 else 0) {
                                    focusAt(focusedIndex - 1)
                                }
                                true
                            }

                            Key.DirectionRight -> {
                                val target = focusedIndex + 1
                                if (
                                    currentColumn < queueColumnCount - 1 &&
                                    target <= maxIndex
                                ) {
                                    focusAt(target)
                                }
                                true
                            }

                            Key.DirectionDown -> {
                                val target = focusedIndex + queueColumnCount
                                if (target <= maxIndex) {
                                    focusAt(target)
                                }
                                true
                            }

                            else -> false
                        }
                    }
                }
            }
            .focusRequester(queueFocusParking)
            .focusable()
            .pointerInput(onDismiss, gridState) {
                awaitPointerEventScope {
                    var start = Offset.Zero
                    var tracking = false
                    var startedAtGridTop = true
                    while (true) {
                        val event =
                            awaitPointerEvent(PointerEventPass.Initial)
                        val change =
                            event.changes.firstOrNull() ?: continue
                        if (change.pressed && !change.previousPressed) {
                            start = change.position
                            tracking = true
                            startedAtGridTop =
                                touchQueueRail ||
                                    (
                                        gridState.firstVisibleItemIndex == 0 &&
                                            gridState
                                                .firstVisibleItemScrollOffset == 0
                                    )
                        } else if (
                            !change.pressed &&
                            change.previousPressed &&
                            tracking
                        ) {
                            val delta = change.position - start
                            val swipeDown =
                                startedAtGridTop &&
                                    delta.y > 64.dp.toPx() &&
                                    kotlin.math.abs(delta.y) >
                                    kotlin.math.abs(delta.x)
                            val outsideTap =
                                delta.getDistance() < 12.dp.toPx() &&
                                    (if (listQueue) start.x < size.width - listPanelWidth.toPx() - queueOuterHorizontalPadding.toPx() else
                                        start.y < size.height - queueDismissBoundary.toPx())
                            if (swipeDown || outsideTap) {
                                onDismiss()
                            }
                            tracking = false
                        }
                    }
                }
            }
            .background(
                Color.Black.copy(
                    alpha =
                        (if (tvQueueGrid) .08f else .30f) *
                            renderedReveal
                )
            )
            .padding(
                horizontal = queueOuterHorizontalPadding,
                vertical = queueOuterVerticalPadding
            ),
        contentAlignment = if (listQueue) Alignment.CenterEnd else Alignment.BottomCenter
    ) {
        Surface(
            Modifier
                .then(if (listQueue) Modifier.width(listPanelWidth) else Modifier.fillMaxWidth())
                .heightIn(
                    min = queueSheetMinHeight,
                    max = queueSheetMaxHeight
                )
                .graphicsLayer {
                    if (listQueue) translationX = size.width * (1f - renderedReveal)
                    else translationY = size.height * (1f - renderedReveal)
                },
            color = if (listQueue) Color(0xA6191D25) else Color(0xF51A1A1A),
            border = if (listQueue) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .16f)) else null,
            shape =
                androidx.compose.foundation.shape.RoundedCornerShape(
                    queueSheetCornerRadius
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (listQueue) Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .07f), Color.Transparent, Color.Black.copy(alpha = .10f)))) else Modifier)
                    .padding(queueSheetPadding)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        "Choose what to play",
                        style = if (tvQueueGrid || listQueue) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = Color.White,
                        maxLines = 1
                    )
                    if (listQueue) Spacer(Modifier.weight(1f)) else Text(
                        when {
                            listQueue -> if (tvQueueGrid) "↑↓ browse · ← closes" else "Tap to play · Hold for options"
                            tvQueueGrid -> "D-pad browse  •  ↑ View  •  Back closes"
                            touchQueueRail -> "Swipe sideways to browse  •  Swipe down to close"
                            else -> "Swipe to browse  •  Swipe down to close"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    NikTvActionButton(
                        label = if (listQueue) "Tiles" else "List",
                        icon = if (listQueue) Icons.Default.GridView else Icons.Default.ViewList,
                        modifier = Modifier.width(if (listQueue) 76.dp else 92.dp).height(if (listQueue) 40.dp else 44.dp)
                            .focusRequester(layoutToggleRequester)
                            .onFocusChanged { layoutToggleFocused = it.isFocused },
                        onClick = {
                            // Claim focus before replacing the grid. Touch clicks do not
                            // focus buttons automatically, and removal of a focused tile
                            // otherwise lets focus search choose a new tile.
                            focusMoveJob?.cancel()
                            layoutToggleRequester.requestFocus()
                            PlayerQueuePreferences.setLayout(context,
                                if (listQueue) PlayerQueueLayout.GRID else PlayerQueueLayout.LIST)
                        }
                    )
                    if (!isEpisodeQueue && (loadingMore || autoAppendPending)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(queueHeaderSpacer))

                val queueGridContent:
                    androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
                    if (leadingLoadPrevious) {
                        item(key = "player-queue-load-previous") {
                            var focused by remember { mutableStateOf(false) }
                            val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            Surface(
                                modifier = Modifier
                                    .then(
                                        if (touchQueueRail) {
                                            Modifier.width(compactQueueCardWidth)
                                        } else {
                                            Modifier.fillMaxWidth()
                                        }
                                    )
                                    .height(queueCardHeight)
                                    .clip(shape)
                                    .focusRequester(loadMoreRequester)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) focusedIndex = -1
                                    }
                                    .remoteCombinedClickable(
                                        enabled = effectiveHasMore && !loadingMore && !loadMoreRequested,
                                        onClick = {
                                            runCatching { queueFocusParking.requestFocus() }
                                            focusFirstLoadedItem = true
                                            loadMoreRequested = onLoadMore()
                                            if (!loadMoreRequested) focusFirstLoadedItem = false
                                        }
                                    )
                                    .focusable(),
                                color = if (focused) Color(0xFF3B4D68) else Color(0xFF303030),
                                border = if (focused) androidx.compose.foundation.BorderStroke(2.5.dp, Color.White) else null,
                                shape = shape
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (loadingMore || loadMoreRequested) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.Add, null, tint = Color.White)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (loadingMore || loadMoreRequested) "Loading previous…" else "Load previous",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Earlier episodes",
                                        color = Color.White.copy(alpha = .70f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    items(
                        count = uniqueItems.size,
                        key = { index ->
                            "player-queue-${entryKey(index)}"
                        }
                    ) { index ->
                        QueueMediaTile(
                            index = index,
                            item = uniqueItems[index]
                        )
                    }

                    if (showPaginationTile && !leadingLoadPrevious) {
                        item(key = "player-queue-load-more") {
                            var focused by remember {
                                mutableStateOf(false)
                            }
                            val loadMoreShape =
                                androidx.compose.foundation.shape
                                    .RoundedCornerShape(12.dp)

                            Surface(
                                Modifier
                                    .then(
                                        if (touchQueueRail) {
                                            Modifier.width(compactQueueCardWidth)
                                        } else {
                                            Modifier.fillMaxWidth()
                                        }
                                    )
                                    .height(queueCardHeight)
                                    .clip(loadMoreShape)
                                    .focusRequester(loadMoreRequester)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) {
                                            focusedIndex =
                                                uniqueItems.size
                                        }
                                    }
                                    .remoteCombinedClickable(
                                        enabled =
                                            hasMore &&
                                                !loadingMore &&
                                                !loadMoreRequested,
                                        onClick = {
                                            runCatching { queueFocusParking.requestFocus() }
                                            focusFirstLoadedItem = true
                                            loadMoreRequested =
                                                onLoadMore()
                                            if (!loadMoreRequested) {
                                                focusFirstLoadedItem = false
                                            }
                                        }
                                    )
                                    .focusable(),
                                color = if (focused) Color(0xFF3B4D68) else Color(0xFF303030),
                                border = if (focused) androidx.compose.foundation.BorderStroke(2.5.dp, Color.White) else null,
                                shape = loadMoreShape
                            ) {
                                Column(
                                    Modifier.padding(
                                        horizontal = if (tvQueueGrid) 8.dp else 16.dp,
                                        vertical = if (tvQueueGrid) 6.dp else 14.dp
                                    ),
                                    horizontalAlignment =
                                        Alignment.CenterHorizontally,
                                    verticalArrangement =
                                        Arrangement.spacedBy(if (tvQueueGrid) 4.dp else 10.dp)
                                ) {
                                    if (
                                        loadingMore ||
                                        loadMoreRequested
                                    ) {
                                        CircularProgressIndicator(
                                            modifier =
                                                Modifier.size(if (tvQueueGrid) 18.dp else 24.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Add,
                                            null,
                                            tint = Color.White
                                        )
                                    }
                                    Text(
                                        if (
                                            loadingMore ||
                                            loadMoreRequested
                                        ) {
                                            "Loading more…"
                                        } else {
                                            if (isEpisodeQueue) "Load more" else "Retry loading"
                                        },
                                        color = Color.White,
                                        style =
                                            if (tvQueueGrid) {
                                                MaterialTheme.typography.labelLarge
                                            } else {
                                                MaterialTheme.typography.titleSmall
                                            }
                                    )
                                    if (!tvQueueGrid) {
                                        Text(
                                            "Continue this playback list",
                                            color = Color.White.copy(alpha = .70f),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (touchQueueRail) {
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(1),
                        modifier = Modifier
                            .height(queueCardHeight + 4.dp)
                            .fillMaxWidth()
                            .clipToBounds(),
                        state = gridState,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                queueGridHorizontalSpacing
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                queueGridVerticalSpacing
                            ),
                        content = queueGridContent
                    )
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                            remember { object : androidx.compose.foundation.gestures.BringIntoViewSpec {} }
                    ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(queueColumnCount),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds(),
                        state = gridState,
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                queueGridHorizontalSpacing
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                queueGridVerticalSpacing
                            ),
                        content = queueGridContent
                    )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerPictureModeEditor(
    profiles: List<VideoAppearanceProfile>,
    selectedId: String,
    onDismiss: () -> Unit,
    onPreview: (VideoAppearanceProfile) -> Unit,
    onSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val pictureEditorConfiguration = LocalConfiguration.current
    val pictureEditorIsTv =
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK
        ) ||
            (
                pictureEditorConfiguration.uiMode and
                    android.content.res.Configuration.UI_MODE_TYPE_MASK
                ) ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            !context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
            )
    val compactMobileEditor =
        !pictureEditorIsTv &&
            pictureEditorConfiguration.smallestScreenWidthDp < 600

    var selected by remember(selectedId) { mutableStateOf(profiles.firstOrNull { it.id == selectedId } ?: profiles.first()) }
    var brightness by remember(selected.id) { mutableFloatStateOf(selected.brightness) }
    var warmth by remember(selected.id) { mutableFloatStateOf(selected.warmth) }
    var coolness by remember(selected.id) { mutableFloatStateOf(selected.coolness) }
    var tint by remember(selected.id) { mutableFloatStateOf(selected.tint) }
    var dimming by remember(selected.id) { mutableFloatStateOf(selected.dimming) }
    val restoreSelectionAndDismiss = {
        onPreview(selected)
        onDismiss()
    }
    val profileRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val brightnessRequester = remember { FocusRequester() }
    val warmthRequester = remember { FocusRequester() }
    val coolnessRequester = remember { FocusRequester() }
    val tintRequester = remember { FocusRequester() }
    val dimmingRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val profileListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val profileChipShape =
        androidx.compose.foundation.shape.RoundedCornerShape(
            if (compactMobileEditor) 14.dp else 18.dp
        )

    LaunchedEffect(selected, brightness, warmth, coolness, tint, dimming) {
        onPreview(selected.copy(brightness = brightness, warmth = warmth, coolness = coolness, tint = tint, dimming = dimming))
    }
    LaunchedEffect(selected.id) {
        val selectedIndex = profiles.indexOfFirst { it.id == selected.id }
            .coerceAtLeast(0)
        profileListState.scrollToItem(selectedIndex)
        withFrameNanos { }
        val requester = profileRequesters.getOrPut(selected.id) {
            FocusRequester()
        }
        delay(120L)
        repeat(6) { attempt ->
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
            delay(50L * (attempt + 1))
        }
    }
    BackHandler(onBack = restoreSelectionAndDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .padding(
                end = if (compactMobileEditor) 8.dp else 14.dp
            ),
        contentAlignment = Alignment.CenterEnd
    ) {
        /*
         * COMPACT_MOBILE_PICTURE_EDITOR_V39
         *
         * A phone in fullscreen landscape has much less vertical space than
         * tablet/TV layouts. Keep the same controls and focus graph, but spend
         * less height on panel chrome, preset chips and slider rows.
         */
        // PROFILE_SCREEN_VISUAL_LANGUAGE_V20
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (compactMobileEditor) .54f else .42f)
                .widthIn(
                    min = if (compactMobileEditor) 300.dp else 340.dp,
                    max = if (compactMobileEditor) 440.dp else 460.dp
                ),
            color = Color(0xF21A1A1A),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                20.dp
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha = 0.10f)
            ),
            shadowElevation = 0.dp
        ) {
            Column(
                Modifier.padding(
                    horizontal = if (compactMobileEditor) 12.dp else 16.dp,
                    vertical = if (compactMobileEditor) 8.dp else 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (compactMobileEditor) 2.dp else 6.dp
                )
            ) {
                Text(
                    "Picture mode · ${selected.name}",
                    style =
                        if (compactMobileEditor) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                    color = Color(0xFFF5F5F7)
                )
                Text(
                    "Adjust while watching the video.",
                    style =
                        if (compactMobileEditor) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    color = Color(0xFF9B9FA8)
                )
                /*
                 * PICTURE_EDITOR_FOCUS_TRAP_V21
                 *
                 * The editor is a modal focus island. Every D-pad direction
                 * from the profile row resolves to another editor target (or
                 * the same edge chip) so focus cannot fall through to player
                 * controls composed underneath this overlay.
                 */
                androidx.compose.foundation.lazy.LazyRow(
                    state = profileListState,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = profiles,
                        key = { _, profile -> profile.id }
                    ) { index, profile ->
                        var focused by remember(profile.id) {
                            mutableStateOf(false)
                        }
                        val profileRequester =
                            profileRequesters.getOrPut(profile.id) {
                                FocusRequester()
                            }
                        val leftRequester =
                            profiles.getOrNull(index - 1)
                                ?.let { previous ->
                                    profileRequesters.getOrPut(previous.id) {
                                        FocusRequester()
                                    }
                                }
                                ?: profileRequester
                        val rightRequester =
                            profiles.getOrNull(index + 1)
                                ?.let { next ->
                                    profileRequesters.getOrPut(next.id) {
                                        FocusRequester()
                                    }
                                }
                                ?: profileRequester

                        Surface(
                            Modifier
                                .focusRequester(profileRequester)
                                .focusProperties {
                                    up = profileRequester
                                    down = if (selected.id == "default") {
                                        cancelRequester
                                    } else {
                                        brightnessRequester
                                    }
                                    left = leftRequester
                                    right = rightRequester
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            runCatching {
                                                profileRequester.requestFocus()
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            runCatching {
                                                if (selected.id == "default") {
                                                    cancelRequester.requestFocus()
                                                } else {
                                                    brightnessRequester.requestFocus()
                                                }
                                            }
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            runCatching {
                                                leftRequester.requestFocus()
                                            }
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            runCatching {
                                                rightRequester.requestFocus()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged {
                                    focused = it.isFocused
                                }
                                .clip(profileChipShape)
                                .then(
                                    if (focused) {
                                        Modifier.border(
                                            2.dp,
                                            Color(0xFFF2F3F5),
                                            profileChipShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    selected = profile
                                }
                                .focusable(),
                            color = when {
                                focused -> Color(0xFF22252B)
                                profile.id == selected.id -> Color(0xFF303A49)
                                else -> Color(0xFF1B1D22)
                            },
                            shape = profileChipShape
                        ) {
                            Text(
                                profile.name,
                                Modifier.padding(
                                    horizontal =
                                        if (compactMobileEditor) 9.dp else 11.dp,
                                    vertical =
                                        if (compactMobileEditor) 4.dp else 7.dp
                                ),
                                color = Color(0xFFF5F5F7),
                                style =
                                    if (compactMobileEditor) {
                                        MaterialTheme.typography.labelSmall
                                    } else {
                                        MaterialTheme.typography.labelMedium
                                    }
                            )
                        }
                    }
                }
                if (selected.id == "default") {
                    Text(
                        "Default is the unfiltered reference. No brightness, color, tint or dimming filters are applied, and this mode cannot be edited.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    PlayerEditorSlider("Brightness", brightness, -1f..1f, brightnessRequester, profileRequesters.getOrPut(selected.id) { FocusRequester() }, warmthRequester, compactMobileEditor) { brightness = it }
                    PlayerEditorSlider("Warmth", warmth, 0f..1f, warmthRequester, brightnessRequester, coolnessRequester, compactMobileEditor) { warmth = it }
                    PlayerEditorSlider("Coolness", coolness, 0f..1f, coolnessRequester, warmthRequester, tintRequester, compactMobileEditor) { coolness = it }
                    PlayerEditorSlider("Color tint", tint, -1f..1f, tintRequester, coolnessRequester, dimmingRequester, compactMobileEditor) { tint = it }
                    PlayerEditorSlider("Dimming", dimming, 0f..1f, dimmingRequester, tintRequester, cancelRequester, compactMobileEditor) { dimming = it }
                }

                /*
                 * MOBILE_PICTURE_EDITOR_ACTION_DIVIDER_V39
                 *
                 * The action row is visually and spatially independent from
                 * Dimming so the final slider cannot run into Cancel/Save.
                 */
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(
                        top = if (compactMobileEditor) 2.dp else 4.dp
                    ),
                    thickness = 1.dp,
                    color = Color(0xFF30343B)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    NikTvSecondaryActionButton(
                        onClick = restoreSelectionAndDismiss,
                        modifier = Modifier
                            .focusRequester(cancelRequester)
                            .focusProperties {
                                up = if (selected.id == "default") {
                                    profileRequesters.getOrPut(selected.id) {
                                        FocusRequester()
                                    }
                                } else {
                                    dimmingRequester
                                }
                                down = cancelRequester
                                left = cancelRequester
                                right = saveRequester
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        runCatching {
                                            if (selected.id == "default") {
                                                profileRequesters.getOrPut(selected.id) {
                                                    FocusRequester()
                                                }.requestFocus()
                                            } else {
                                                dimmingRequester.requestFocus()
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionDown,
                                    Key.DirectionLeft -> {
                                        runCatching {
                                            cancelRequester.requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        runCatching {
                                            saveRequester.requestFocus()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        Text("Cancel")
                    }

                    NikTvPrimaryActionButton(
                        onClick = {
                            VideoAppearancePreferences.update(
                                context,
                                selected.copy(
                                    brightness = brightness,
                                    warmth = warmth,
                                    coolness = coolness,
                                    tint = tint,
                                    dimming = dimming
                                )
                            )
                            onSelected(selected.id)
                            onDismiss()
                        },
                        modifier = Modifier
                            .focusRequester(saveRequester)
                            .focusProperties {
                                up = if (selected.id == "default") {
                                    profileRequesters.getOrPut(selected.id) {
                                        FocusRequester()
                                    }
                                } else {
                                    dimmingRequester
                                }
                                down = saveRequester
                                left = cancelRequester
                                right = saveRequester
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        runCatching {
                                            if (selected.id == "default") {
                                                profileRequesters.getOrPut(selected.id) {
                                                    FocusRequester()
                                                }.requestFocus()
                                            } else {
                                                dimmingRequester.requestFocus()
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionDown,
                                    Key.DirectionRight -> {
                                        runCatching {
                                            saveRequester.requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        runCatching {
                                            cancelRequester.requestFocus()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        Text(
                            if (selected.id == "custom") {
                                "Save custom mode"
                            } else {
                                "Save changes"
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerEditorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    requester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    compact: Boolean,
    onValue: (Float) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val trackShape =
        androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    val thumbShape =
        androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    val percentage = (value * 100).roundToInt()

    Row(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 36.dp else 44.dp)
            .focusRequester(requester)
            .focusProperties {
                up = upRequester
                down = downRequester
            }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        runCatching { upRequester.requestFocus() }
                        true
                    }
                    Key.DirectionDown -> {
                        runCatching { downRequester.requestFocus() }
                        true
                    }
                    Key.DirectionLeft -> {
                        onValue(
                            (value - .01f).coerceIn(
                                range.start,
                                range.endInclusive
                            )
                        )
                        true
                    }
                    Key.DirectionRight -> {
                        onValue(
                            (value + .01f).coerceIn(
                                range.start,
                                range.endInclusive
                            )
                        )
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (compact) 6.dp else 10.dp
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.width(if (compact) 78.dp else 94.dp),
            color =
                if (focused) Color(0xFFF5F5F7)
                else Color(0xFFD4D7DC),
            style =
                if (compact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            maxLines = 1
        )

        /*
         * SLIDER_THUMB_FOCUS_V28
         *
         * Keep the full slider track visually neutral. D-pad focus is shown
         * only on the current-value marker: a narrow vertical thumb receives
         * the white focus outline instead of outlining the whole progress bar.
         *
         * The Row remains the actual focus/D-pad owner; Slider itself stays
         * non-focusable so the editor focus trap/navigation is unchanged.
         */
        Box(
            modifier = Modifier
                .weight(1f)
                .height(if (compact) 28.dp else 32.dp)
                .background(
                    Color(0xFF15171B),
                    trackShape
                )
                .border(
                    width = 1.dp,
                    color = Color(0xFF30343B),
                    shape = trackShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = value,
                onValueChange = onValue,
                valueRange = range,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 5.dp else 8.dp)
                    .focusProperties { canFocus = false },
                thumb = {
                    Box(
                        Modifier
                            .width(
                                if (focused) {
                                    if (compact) 8.dp else 10.dp
                                } else {
                                    if (compact) 5.dp else 6.dp
                                }
                            )
                            .height(if (compact) 22.dp else 26.dp)
                            .background(
                                if (focused) {
                                    Color(0xFF22252B)
                                } else {
                                    Color(0xFFB9BDC5)
                                },
                                thumbShape
                            )
                            .then(
                                if (focused) {
                                    Modifier.border(
                                        2.dp,
                                        Color(0xFFF2F3F5),
                                        thumbShape
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            )
        }

        Text(
            text = "$percentage%",
            modifier = Modifier.width(if (compact) 46.dp else 58.dp),
            color =
                if (focused) Color(0xFFF5F5F7)
                else Color(0xFF9B9FA8),
            style =
                if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1
        )
    }
}
