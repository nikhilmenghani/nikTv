package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt
import com.nikhil.niktv.model.MediaItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.launch
import com.nikhil.niktv.data.artworkRequest

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
    fun activeEntryAt(minutesOfDay: Int): VideoAppearanceScheduleEntry? {
        if (!enabled) return null
        return entries.firstOrNull { it.contains(minutesOfDay) }
    }

    fun profileAt(minutesOfDay: Int): String? =
        activeEntryAt(minutesOfDay)?.profileId
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
    down: FocusRequester? = null
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val destination = when (event.key) {
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        Key.DirectionDown -> down
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
internal fun PlayerQueueOverlay(
    items: List<MediaItem>,
    playingId: String,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Boolean = { false },
    revealProgress: Float = 1f,
    revealDragging: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (MediaItem) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    /*
     * MOBILE_PLAYER_QUEUE_DENSITY_V35
     *
     * The playback queue is shared across TV, tablet and phone. Phones use
     * the same horizontal rail and gesture, but with a shorter sheet, narrower
     * cards, tighter spacing and touch-oriented visual treatment.
     */
    val mobileQueueRail =
        configuration.smallestScreenWidthDp < 600
    val queueSheetMinHeight =
        if (mobileQueueRail) 176.dp else 250.dp
    val queueSheetMaxHeight =
        if (mobileQueueRail) 230.dp else 340.dp
    val queueOuterHorizontalPadding =
        if (mobileQueueRail) 8.dp else 28.dp
    val queueOuterVerticalPadding =
        if (mobileQueueRail) 8.dp else 22.dp
    val queueDismissBoundary =
        if (mobileQueueRail) {
            queueSheetMaxHeight + queueOuterVerticalPadding
        } else {
            380.dp
        }
    val queueSheetPadding =
        if (mobileQueueRail) 12.dp else 20.dp
    val queueSheetCornerRadius =
        if (mobileQueueRail) 18.dp else 24.dp
    val queueHeaderSpacer =
        if (mobileQueueRail) 8.dp else 14.dp
    val queueRailSpacing =
        if (mobileQueueRail) 8.dp else 12.dp
    val queueCardWidth =
        if (mobileQueueRail) 176.dp else 280.dp
    val queueLoadMoreWidth =
        if (mobileQueueRail) 180.dp else 240.dp

    val scope = rememberCoroutineScope()
    val uniqueItems = remember(items) { items.distinctBy { it.id } }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val loadMoreRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val currentIndex =
        uniqueItems.indexOfFirst { it.id == playingId }.coerceAtLeast(0)

    var focusedIndex by remember(playingId) { mutableIntStateOf(currentIndex) }
    var previousItemCount by remember { mutableIntStateOf(uniqueItems.size) }
    var initialFocusApplied by remember(playingId) { mutableStateOf(false) }
    var loadMoreRequested by remember { mutableStateOf(false) }
    var observedLoading by remember { mutableStateOf(false) }
    val renderedReveal by animateFloatAsState(
        targetValue = revealProgress.coerceIn(0f, 1f),
        animationSpec = if (revealDragging) snap() else tween(220),
        label = "playerQueueReveal"
    )

    LaunchedEffect(loadingMore) {
        if (loadMoreRequested) {
            if (loadingMore) {
                observedLoading = true
            } else if (observedLoading) {
                loadMoreRequested = false
                observedLoading = false
            }
        }
    }

    val showLoadMore = hasMore || loadingMore || loadMoreRequested

    fun focusAt(index: Int) {
        val maxIndex = if (showLoadMore) uniqueItems.size else uniqueItems.lastIndex
        if (maxIndex < 0) return

        val target = index.coerceIn(0, maxIndex)
        focusedIndex = target

        val requester = if (target < uniqueItems.size) {
            requesters.getOrPut(uniqueItems[target].id) { FocusRequester() }
        } else {
            loadMoreRequester
        }
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any {
            it.index == target
        }
        if (alreadyVisible && runCatching { requester.requestFocus() }.getOrDefault(false)) {
            return
        }
        scope.launch {
            // Avoid waiting for a full animated scroll before moving focus.
            // Adjacent visible cards focus synchronously; an off-screen edge
            // card is composed with one direct scroll and focused next frame.
            listState.scrollToItem(target)
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }

    LaunchedEffect(playingId, uniqueItems.isNotEmpty()) {
        if (!initialFocusApplied && uniqueItems.isNotEmpty()) {
            initialFocusApplied = true
            val target =
                uniqueItems.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
            listState.scrollToItem(target)
            focusedIndex = target
            delay(120L)
            runCatching {
                requesters.getOrPut(uniqueItems[target].id) {
                    FocusRequester()
                }.requestFocus()
            }
        }
    }

    LaunchedEffect(uniqueItems.size) {
        val oldCount = previousItemCount
        if (uniqueItems.size > oldCount && focusedIndex >= oldCount) {
            loadMoreRequested = false
            observedLoading = false
            previousItemCount = uniqueItems.size
            delay(40L)
            focusAt(oldCount)
        } else {
            previousItemCount = uniqueItems.size
        }
    }

    LaunchedEffect(showLoadMore, uniqueItems.size) {
        if (
            !showLoadMore &&
            uniqueItems.isNotEmpty() &&
            focusedIndex >= uniqueItems.size
        ) {
            delay(40L)
            focusAt(uniqueItems.lastIndex)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionUp -> {
                            onDismiss()
                            true
                        }

                        Key.DirectionLeft -> {
                            focusAt((focusedIndex - 1).coerceAtLeast(0))
                            true
                        }

                        Key.DirectionRight -> {
                            val maxIndex =
                                if (showLoadMore) uniqueItems.size
                                else uniqueItems.lastIndex
                            if (maxIndex >= 0) {
                                focusAt((focusedIndex + 1).coerceAtMost(maxIndex))
                            }
                            true
                        }

                        Key.DirectionDown -> true

                        else -> false
                    }
                }
            }
            .pointerInput(onDismiss) {
                awaitPointerEventScope {
                    var start = Offset.Zero
                    var tracking = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed && !change.previousPressed) {
                            start = change.position
                            tracking = true
                        } else if (!change.pressed && change.previousPressed && tracking) {
                            val delta = change.position - start
                            val swipeDown = delta.y > 64.dp.toPx() &&
                                kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)
                            val outsideTap = delta.getDistance() < 12.dp.toPx() &&
                                start.y < size.height - queueDismissBoundary.toPx()
                            if (swipeDown || outsideTap) onDismiss()
                            tracking = false
                        }
                    }
                }
            }
            .background(Color.Black.copy(alpha = .76f * renderedReveal))
            .padding(
                horizontal = queueOuterHorizontalPadding,
                vertical = queueOuterVerticalPadding
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = queueSheetMinHeight,
                    max = queueSheetMaxHeight
                )
                .graphicsLayer {
                    translationY = size.height * (1f - renderedReveal)
                },
            color = Color(0xF51A1A1A),
            shape =
                androidx.compose.foundation.shape.RoundedCornerShape(
                    queueSheetCornerRadius
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(queueSheetPadding)
            ) {
                Text(
                    "Choose what to play",
                    style =
                        if (mobileQueueRail) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                    color = Color.White
                )
                Text(
                    if (mobileQueueRail) {
                        "Swipe down to close  •  Tap to play"
                    } else {
                        "←/→ Browse  •  ↑ Close  •  OK Play"
                    },
                    style =
                        if (mobileQueueRail) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    color = Color.LightGray
                )
                Spacer(Modifier.height(queueHeaderSpacer))

                LazyRow(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    horizontalArrangement =
                        Arrangement.spacedBy(queueRailSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(
                        uniqueItems,
                        key = { _, item -> "player-queue-${item.id}" }
                    ) { index, item ->
                        val requester =
                            requesters.getOrPut(item.id) { FocusRequester() }
                        var focused by remember(item.id) { mutableStateOf(false) }
                        val current = item.id == playingId
                        val artwork = remember(item.id, item.title, item.logo) {
                            artworkRequest(context, item)
                        }

                        Surface(
                            Modifier
                                .width(queueCardWidth)
                                .fillMaxHeight()
                                .focusRequester(requester)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) focusedIndex = index
                                }
                                .clickable { onSelect(item) }
                                .focusable(),
                            color = when {
                                focused && !mobileQueueRail ->
                                    Color(0xFFE50914)
                                current -> Color(0xFF383838)
                                else -> Color(0xFF242424)
                            },
                            shape =
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    Modifier.fillMaxWidth().weight(1f),
                                    shape =
                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    color = Color(0xFF111111)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (item.logo.isNullOrBlank()) {
                                            Icon(
                                                Icons.Default.Movie,
                                                null,
                                                tint = Color.LightGray
                                            )
                                        } else {
                                            SubcomposeAsyncImage(
                                                model = artwork,
                                                contentDescription = item.title,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            ) {
                                                when (painter.state.value) {
                                                    is AsyncImagePainter.State.Success ->
                                                        SubcomposeAsyncImageContent()
                                                    else -> Icon(
                                                        Icons.Default.Movie,
                                                        null,
                                                        tint = Color.LightGray
                                                    )
                                                }
                                            }
                                        }

                                        if (current) {
                                            Surface(
                                                Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(5.dp),
                                                color =
                                                    Color.Black.copy(alpha = .78f),
                                                shape =
                                                    androidx.compose.foundation.shape
                                                        .RoundedCornerShape(5.dp)
                                            ) {
                                                Row(
                                                    Modifier.padding(
                                                        horizontal = 6.dp,
                                                        vertical = 3.dp
                                                    ),
                                                    verticalAlignment =
                                                        Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        null,
                                                        Modifier.size(14.dp),
                                                        tint = Color.White
                                                    )
                                                    Text(
                                                        "PLAYING",
                                                        color = Color.White,
                                                        style =
                                                            MaterialTheme.typography
                                                                .labelSmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        item.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val episodeLabel = listOfNotNull(
                                        item.seasonNumber?.let { "Season $it" },
                                        item.episodeNumber?.let { "Episode $it" }
                                    ).joinToString(" · ")

                                    if (episodeLabel.isNotBlank()) {
                                        Text(
                                            episodeLabel,
                                            color = Color.White.copy(alpha = .76f),
                                            style =
                                                MaterialTheme.typography.labelMedium
                                        )
                                    }

                                    item.description
                                        ?.trim()
                                        ?.takeIf {
                                            it.isNotBlank() &&
                                                !it.equals(
                                                    item.title.trim(),
                                                    ignoreCase = true
                                                )
                                        }
                                        ?.let {
                                            Text(
                                                it,
                                                color =
                                                    Color.White.copy(alpha = .68f),
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                }
                            }
                        }
                    }

                    if (showLoadMore) {
                        item(key = "player-queue-load-more") {
                            var focused by remember { mutableStateOf(false) }

                            Surface(
                                Modifier
                                    .width(queueLoadMoreWidth)
                                    .fillMaxHeight()
                                    .focusRequester(loadMoreRequester)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) {
                                            focusedIndex = uniqueItems.size
                                        }
                                    }
                                    .clickable(
                                        enabled =
                                            hasMore &&
                                                !loadingMore &&
                                                !loadMoreRequested
                                    ) {
                                        loadMoreRequested = onLoadMore()
                                    }
                                    .focusable(),
                                color =
                                    if (focused && !mobileQueueRail) {
                                        Color(0xFFE50914)
                                    } else {
                                        Color(0xFF303030)
                                    },
                                shape =
                                    androidx.compose.foundation.shape
                                        .RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 14.dp
                                    ),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (loadingMore || loadMoreRequested) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
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
                                    Column {
                                        Text(
                                            if (loadingMore || loadMoreRequested) {
                                                "Loading more…"
                                            } else {
                                                "Load more"
                                            },
                                            color = Color.White,
                                            style =
                                                MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            "Continue this playback list",
                                            color = Color.White.copy(alpha = .70f),
                                            style =
                                                MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
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
    var selected by remember(selectedId) { mutableStateOf(profiles.firstOrNull { it.id == selectedId } ?: profiles.first()) }
    var brightness by remember(selected.id) { mutableFloatStateOf(selected.brightness) }
    var warmth by remember(selected.id) { mutableFloatStateOf(selected.warmth) }
    var coolness by remember(selected.id) { mutableFloatStateOf(selected.coolness) }
    var tint by remember(selected.id) { mutableFloatStateOf(selected.tint) }
    var dimming by remember(selected.id) { mutableFloatStateOf(selected.dimming) }
    val profileRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val brightnessRequester = remember { FocusRequester() }
    val warmthRequester = remember { FocusRequester() }
    val coolnessRequester = remember { FocusRequester() }
    val tintRequester = remember { FocusRequester() }
    val dimmingRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val profileListState = androidx.compose.foundation.lazy.rememberLazyListState()
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
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().focusGroup().padding(end = 24.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        // PROFILE_SCREEN_VISUAL_LANGUAGE_V20
        Surface(
            modifier = Modifier
                .fillMaxWidth(.50f)
                .widthIn(min = 360.dp, max = 660.dp),
            color = Color(0xF2111317),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Color(0xFF30343B)
            ),
            shadowElevation = 14.dp
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Picture mode · ${selected.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFF5F5F7)
                )
                Text(
                    "Adjust while watching the video.",
                    style = MaterialTheme.typography.bodySmall,
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
                                .then(
                                    if (focused) {
                                        Modifier.border(
                                            2.dp,
                                            Color(0xFFF2F3F5),
                                            androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    selected = profile
                                    onSelected(profile.id)
                                }
                                .focusable(),
                            color = when {
                                focused -> Color(0xFF22252B)
                                profile.id == selected.id -> Color(0xFF35191D)
                                else -> Color(0xFF1B1D22)
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                profile.name,
                                Modifier.padding(
                                    horizontal = 11.dp,
                                    vertical = 7.dp
                                ),
                                color = Color(0xFFF5F5F7),
                                style = MaterialTheme.typography.labelMedium
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
                    PlayerEditorSlider("Brightness", brightness, -1f..1f, brightnessRequester, profileRequesters.getOrPut(selected.id) { FocusRequester() }, warmthRequester) { brightness = it }
                    PlayerEditorSlider("Warmth", warmth, 0f..1f, warmthRequester, brightnessRequester, coolnessRequester) { warmth = it }
                    PlayerEditorSlider("Coolness", coolness, 0f..1f, coolnessRequester, warmthRequester, tintRequester) { coolness = it }
                    PlayerEditorSlider("Color tint", tint, -1f..1f, tintRequester, coolnessRequester, dimmingRequester) { tint = it }
                    PlayerEditorSlider("Dimming", dimming, 0f..1f, dimmingRequester, tintRequester, cancelRequester) { dimming = it }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = onDismiss,
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
                            .playerControlFocus { }
                    ) {
                        Text("Cancel")
                    }

                    androidx.compose.material3.Button(
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
                            .playerControlFocus { }
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
            .height(44.dp)
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
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(94.dp),
            color =
                if (focused) Color(0xFFF5F5F7)
                else Color(0xFFD4D7DC),
            style = MaterialTheme.typography.bodyMedium
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
                .height(32.dp)
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
                    .focusProperties { canFocus = false },
                thumb = {
                    Box(
                        Modifier
                            .width(
                                if (focused) 10.dp
                                else 6.dp
                            )
                            .height(26.dp)
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
            modifier = Modifier.width(58.dp),
            color =
                if (focused) Color(0xFFF5F5F7)
                else Color(0xFF9B9FA8),
            style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1
        )
    }
}
