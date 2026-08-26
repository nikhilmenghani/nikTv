package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt
import com.nikhil.niktv.model.MediaItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
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
    "standard" -> Icons.Default.Tv
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
            .playerControlFocus { onControlsFocused(it) }
    ) {
        Icon(Icons.Default.Settings, "Edit picture mode settings", tint = Color.White)
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


@Composable
internal fun PlayerQueueOverlay(
    items: List<MediaItem>,
    playingId: String,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Boolean = { false },
    onDismiss: () -> Unit,
    onSelect: (MediaItem) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
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

        scope.launch {
            runCatching { listState.animateScrollToItem(target) }
            delay(35L)
            runCatching {
                if (target < uniqueItems.size) {
                    requesters.getOrPut(uniqueItems[target].id) {
                        FocusRequester()
                    }.requestFocus()
                } else {
                    loadMoreRequester.requestFocus()
                }
            }
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
            .background(Color.Black.copy(alpha = .76f))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 250.dp, max = 340.dp),
            color = Color(0xF51A1A1A),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Choose what to play",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Text(
                    "←/→ Browse  •  ↑ Close  •  OK Play",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Spacer(Modifier.height(14.dp))

                LazyRow(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                .width(280.dp)
                                .fillMaxHeight()
                                .focusRequester(requester)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) focusedIndex = index
                                }
                                .clickable { onSelect(item) }
                                .focusable(),
                            color = when {
                                focused -> Color(0xFFE50914)
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
                                    .width(240.dp)
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
                                    if (focused) Color(0xFFE50914)
                                    else Color(0xFF303030),
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
    LaunchedEffect(selected, brightness, warmth, coolness, tint, dimming) {
        onPreview(selected.copy(brightness = brightness, warmth = warmth, coolness = coolness, tint = tint, dimming = dimming))
    }
    LaunchedEffect(selected.id) {
        delay(100L)
        runCatching {
            profileRequesters.getOrPut(selected.id) { FocusRequester() }.requestFocus()
        }
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(.18f)).padding(end = 24.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Surface(Modifier.fillMaxWidth(.50f).widthIn(min = 360.dp, max = 660.dp), color = Color(0xF5181818), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Picture mode · ${selected.name}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Adjust while watching the video.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        Surface(
                            Modifier.focusRequester(profileRequesters.getOrPut(profile.id) { FocusRequester() })
                                .focusProperties { down = brightnessRequester }
                                .clickable {
                                selected = profile; onSelected(profile.id)
                            }.focusable(),
                            color = if (profile.id == selected.id) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                        ) { Text(profile.name, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                PlayerEditorSlider("Brightness", brightness, 0f..0.3f, brightnessRequester, profileRequesters.getOrPut(selected.id) { FocusRequester() }, warmthRequester) { brightness = it }
                PlayerEditorSlider("Warmth", warmth, 0f..0.4f, warmthRequester, brightnessRequester, coolnessRequester) { warmth = it }
                PlayerEditorSlider("Coolness", coolness, 0f..0.4f, coolnessRequester, warmthRequester, tintRequester) { coolness = it }
                PlayerEditorSlider("Color tint", tint, -0.25f..0.25f, tintRequester, coolnessRequester, dimmingRequester) { tint = it }
                PlayerEditorSlider("Dimming", dimming, 0f..0.4f, dimmingRequester, tintRequester, cancelRequester) { dimming = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelRequester).focusProperties { up = dimmingRequester; right = saveRequester }) { Text("Cancel") }
                    androidx.compose.material3.Button(onClick = {
                        VideoAppearancePreferences.update(context, selected.copy(brightness = brightness, warmth = warmth, coolness = coolness, tint = tint, dimming = dimming))
                        onSelected(selected.id); onDismiss()
                    }, modifier = Modifier.focusRequester(saveRequester).focusProperties { up = dimmingRequester; left = cancelRequester }) { Text(if (selected.id == "custom") "Save custom mode" else "Save changes") }
                }
            }
        }
    }
}

@Composable
private fun PlayerEditorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, requester: FocusRequester, upRequester: FocusRequester, downRequester: FocusRequester, onValue: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .focusRequester(requester)
            .focusProperties {
                up = upRequester
                down = downRequester
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
                        onValue((value - .01f).coerceIn(range.start, range.endInclusive))
                        true
                    }
                    Key.DirectionRight -> {
                        onValue((value + .01f).coerceIn(range.start, range.endInclusive))
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.fillMaxWidth(.28f), color = Color.White)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier.weight(1f).focusProperties { canFocus = false }
        )
        Text("${(value * 100).roundToInt()}%", Modifier.fillMaxWidth(.10f), color = Color.LightGray)
    }
}
