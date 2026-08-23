package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PlaybackDesign(val title: String) {
    SIDE_LIST("Side list"),
    SHOWCASE("Showcase"),
    FULLSCREEN("Fullscreen")
}

/**
 * UI-only preferences, intentionally separate from portal/session state.
 * Keys are profile + catalog type so each profile can use a different
 * player design for Live TV, Movies and Series.
 */
object PlaybackUiPreferences {
    private const val PREFS_NAME = "niktv_playback_ui"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun profilePart(profileKey: String) = profileKey.ifBlank { "default" }

    private fun designKey(profileKey: String, type: CatalogType) =
        "design|${profilePart(profileKey)}|${type.name}"

    private fun columnsKey(profileKey: String, type: CatalogType) =
        "columns|${profilePart(profileKey)}|${type.name}"

    fun defaultDesign(type: CatalogType): PlaybackDesign = when (type) {
        CatalogType.LIVE_TV -> PlaybackDesign.SIDE_LIST
        CatalogType.MOVIES -> PlaybackDesign.SHOWCASE
        CatalogType.SERIES, CatalogType.RADIO -> PlaybackDesign.FULLSCREEN
    }

    fun supportedDesigns(type: CatalogType): List<PlaybackDesign> = when (type) {
        CatalogType.LIVE_TV -> listOf(
            PlaybackDesign.SIDE_LIST,
            PlaybackDesign.SHOWCASE,
            PlaybackDesign.FULLSCREEN
        )
        CatalogType.MOVIES, CatalogType.SERIES -> listOf(
            PlaybackDesign.SHOWCASE,
            PlaybackDesign.FULLSCREEN
        )
        CatalogType.RADIO -> listOf(PlaybackDesign.FULLSCREEN)
    }

    fun getDesign(context: Context, profileKey: String, type: CatalogType): PlaybackDesign {
        val parsed = prefs(context).getString(designKey(profileKey, type), null)
            ?.let { runCatching { PlaybackDesign.valueOf(it) }.getOrNull() }
        return parsed?.takeIf { it in supportedDesigns(type) } ?: defaultDesign(type)
    }

    fun setDesign(context: Context, profileKey: String, type: CatalogType, design: PlaybackDesign) {
        if (design !in supportedDesigns(type)) return
        prefs(context).edit().putString(designKey(profileKey, type), design.name).apply()
    }

    fun getCatalogColumns(
        context: Context,
        profileKey: String,
        type: CatalogType,
        defaultValue: Int
    ): Int = prefs(context).getInt(columnsKey(profileKey, type), defaultValue)

    fun setCatalogColumns(context: Context, profileKey: String, type: CatalogType, columns: Int) {
        prefs(context).edit()
            .putInt(columnsKey(profileKey, type), columns.coerceAtLeast(1))
            .apply()
    }

    internal fun sharedPreferences(context: Context): SharedPreferences = prefs(context)
}

@Composable
fun rememberPlaybackDesign(profileKey: String, type: CatalogType): State<PlaybackDesign> {
    val context = LocalContext.current
    val prefs = remember(context) { PlaybackUiPreferences.sharedPreferences(context) }
    val value = remember(profileKey, type) {
        mutableStateOf(PlaybackUiPreferences.getDesign(context, profileKey, type))
    }

    DisposableEffect(prefs, profileKey, type) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value.value = PlaybackUiPreferences.getDesign(context, profileKey, type)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(profileKey, type) {
        value.value = PlaybackUiPreferences.getDesign(context, profileKey, type)
    }
    return value
}

@Composable
fun rememberCatalogColumns(
    profileKey: String,
    type: CatalogType,
    defaultValue: Int
): State<Int> {
    val context = LocalContext.current
    val prefs = remember(context) { PlaybackUiPreferences.sharedPreferences(context) }
    val value = remember(profileKey, type, defaultValue) {
        mutableIntStateOf(
            PlaybackUiPreferences.getCatalogColumns(context, profileKey, type, defaultValue)
        )
    }

    DisposableEffect(prefs, profileKey, type, defaultValue) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value.intValue =
                PlaybackUiPreferences.getCatalogColumns(context, profileKey, type, defaultValue)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(profileKey, type, defaultValue) {
        value.intValue =
            PlaybackUiPreferences.getCatalogColumns(context, profileKey, type, defaultValue)
    }
    return value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackDesignSettingsSection(profileKey: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Player layouts",
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    "Choose each content type independently. New layouts can be added later without changing the portal or playback engine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PlaybackDesignRow(
                    profileKey, CatalogType.LIVE_TV, "Live TV",
                    "Side list keeps the current Live TV experience."
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PlaybackDesignRow(
                    profileKey, CatalogType.MOVIES, "Movies",
                    "Showcase uses player-left, details-right and a poster rail."
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PlaybackDesignRow(
                    profileKey, CatalogType.SERIES, "Series",
                    "Keep fullscreen or use Showcase for episodes."
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackDesignRow(
    profileKey: String,
    type: CatalogType,
    title: String,
    description: String
) {
    val context = LocalContext.current
    val selected by rememberPlaybackDesign(profileKey, type)
    val options = remember(type) { PlaybackUiPreferences.supportedDesigns(type) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                when (type) {
                    CatalogType.LIVE_TV -> Icons.Default.LiveTv
                    CatalogType.MOVIES -> Icons.Default.Movie
                    CatalogType.SERIES -> Icons.Default.VideoLibrary
                    CatalogType.RADIO -> Icons.Default.Radio
                },
                null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, design ->
                val shape = playbackSegmentShape(index, options.size)
                SegmentedButton(
                    selected = selected == design,
                    onClick = {
                        PlaybackUiPreferences.setDesign(context, profileKey, type, design)
                    },
                    shape = shape,
                    modifier = Modifier.showcaseFocusFrame(shape)
                ) {
                    Text(
                        when (design) {
                            PlaybackDesign.SIDE_LIST -> "Side list"
                            PlaybackDesign.SHOWCASE -> "Showcase"
                            PlaybackDesign.FULLSCREEN -> "Full"
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogColumnSelector(
    selectedColumns: Int,
    maxColumns: Int,
    onColumnsChanged: (Int) -> Unit,
    selectorFocusRequester: FocusRequester
) {
    val choices = remember(maxColumns) { (2..maxColumns.coerceAtLeast(2)).toList() }

    SingleChoiceSegmentedButtonRow {
        choices.forEachIndexed { index, count ->
            val shape = playbackSegmentShape(index, choices.size)
            SegmentedButton(
                selected = selectedColumns == count,
                onClick = { onColumnsChanged(count) },
                shape = shape,
                modifier = Modifier
                    .then(
                        if (selectedColumns == count) {
                            Modifier.focusRequester(selectorFocusRequester)
                        } else Modifier
                    )
                    .focusProperties { up = FocusRequester.Default }
                    .showcaseFocusFrame(shape)
            ) {
                Text("$count")
            }
        }
    }
}

@Composable
fun MovieBrowseHeaderPlaceholder(categoryTitle: String?) {
    val showInstructions = LocalConfiguration.current.screenWidthDp >= 700

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111111)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Movie, null, Modifier.size(30.dp), tint = Color(0xFFE50914))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Browse movies",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(categoryTitle ?: "Movies", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            if (showInstructions) {
                Text(
                    "←/→ Browse  •  OK Play  •  Hold OK My List",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ShowcasePlaybackScreen(
    state: NikTvState,
    play: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryAlternateDecoder: (Long) -> Unit,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    loadMoreCatalog: () -> Unit
) {
    val playing = state.nowPlaying ?: return
    val type = playing.catalogType
    var fullscreen by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf(playing.media) }

    LaunchedEffect(playing.media.id) { previewItem = playing.media }

    /*
     * IMPORTANT:
     * PlayingMedia.episodeQueue is a snapshot taken when playback starts.
     * For Movies/Live TV that snapshot does NOT grow when loadMoreCatalog()
     * appends another page to state.items.
     *
     * While the player was opened from the currently selected catalog,
     * state.items must therefore remain the source of truth for the rail.
     * Fall back to episodeQueue only for playback opened outside that catalog
     * (for example from search/favorites).
     */
    val queue = remember(
        playing.media.id,
        playing.episodeQueue,
        state.items,
        state.selectedType,
        type
    ) {
        val source = when {
            state.selectedType == type && state.items.isNotEmpty() -> state.items
            playing.episodeQueue.isNotEmpty() -> playing.episodeQueue
            else -> emptyList()
        }

        /*
         * SHOWCASE_STABLE_QUEUE_V3
         *
         * Preserve the catalog/queue order when playback changes.
         * The currently playing movie should become visually "PLAYING"
         * in its existing slot instead of being moved to the far left.
         */
        val orderedSource = source.distinctBy { it.id }

        if (orderedSource.any { it.id == playing.media.id }) {
            orderedSource
        } else {
            // Playback may have been opened from Search/My List. In that
            // case the active item genuinely is not present in the source.
            listOf(playing.media) + orderedSource
        }
    }

    val posterFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val railState = rememberLazyListState()

    var loadMorePending by remember { mutableStateOf(false) }
    var loadMoreObservedLoading by remember { mutableStateOf(false) }
    var loadMoreStartItemCount by remember { mutableIntStateOf(0) }
    var loadMoreFirstVisibleItemIndex by remember { mutableIntStateOf(0) }
    var loadMoreFirstVisibleItemScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(140L)
        val index = queue.indexOfFirst { it.id == playing.media.id }.coerceAtLeast(0)
        railState.scrollToItem(index)
        withTimeoutOrNull(1_000L) {
            snapshotFlow {
                railState.layoutInfo.visibleItemsInfo.any { it.index == index }
            }.first { it }
        }
        withFrameNanos { }
        runCatching {
            posterFocusRequesters
                .getOrPut(playing.media.id) { FocusRequester() }
                .requestFocus()
        }
    }

    LaunchedEffect(loadMorePending, state.catalogLoadingMore, state.items.size) {
        if (!loadMorePending) return@LaunchedEffect

        if (state.catalogLoadingMore) {
            loadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewItems = state.items.size > loadMoreStartItemCount
        val loadFinished = loadMoreObservedLoading || receivedNewItems
        if (!loadFinished) return@LaunchedEffect

        if (receivedNewItems) {
            state.items.getOrNull(loadMoreStartItemCount)?.let { firstNewItem ->
                railState.scrollToItem(
                    loadMoreFirstVisibleItemIndex,
                    loadMoreFirstVisibleItemScrollOffset
                )

                val refreshedCatalog =
                    state.items.distinctBy { it.id }

                val refreshedQueue =
                    if (refreshedCatalog.any { it.id == playing.media.id }) {
                        refreshedCatalog
                    } else {
                        listOf(playing.media) + refreshedCatalog
                    }

                val newIndex =
                    refreshedQueue.indexOfFirst { it.id == firstNewItem.id }

                if (newIndex >= 0) {
                    val visible = withTimeoutOrNull(800L) {
                        snapshotFlow {
                            railState.layoutInfo.visibleItemsInfo.any { it.index == newIndex }
                        }.first { it }
                    } != null

                    if (!visible) railState.animateScrollToItem(newIndex)
                    withFrameNanos { }

                    runCatching {
                        posterFocusRequesters
                            .getOrPut(firstNewItem.id) { FocusRequester() }
                            .requestFocus()
                    }
                    previewItem = firstNewItem
                }
            }
        }

        loadMorePending = false
        loadMoreObservedLoading = false
    }

    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 900 || configuration.screenHeightDp < 520
    val playerWidthFraction = if (compact) 0.61f else 0.69f

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF050505))
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val safePadding = WindowInsets.safeDrawing.asPaddingValues()
        val safeTop = safePadding.calculateTopPadding()
        val safeBottom = safePadding.calculateBottomPadding()
        val safeStart = safePadding.calculateStartPadding(layoutDirection)
        val safeEnd = safePadding.calculateEndPadding(layoutDirection)

        val headerHeight = (if (compact) 50.dp else 58.dp) + safeTop
        val railHeight = when {
            type == CatalogType.MOVIES && maxHeight < 480.dp -> 128.dp
            type == CatalogType.MOVIES && compact -> 148.dp
            type == CatalogType.MOVIES -> 176.dp
            maxHeight < 480.dp -> 142.dp
            compact -> 172.dp
            else -> 224.dp
        } + safeBottom
        val mainHeight = (maxHeight - headerHeight - railHeight).coerceAtLeast(120.dp)
        val playerWidth = maxWidth * playerWidthFraction
        val detailsWidth = maxWidth - playerWidth

        PlayerScreen(
            media = playing,
            onBack = if (fullscreen) {
                { fullscreen = false }
            } else onBack,
            onRetry = onRetry,
            onRetryAlternateDecoder = onRetryAlternateDecoder,
            onPlaybackAuthorizationFailure = onPlaybackAuthorizationFailure,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onProgress = onProgress,
            controlsTimeoutSeconds = state.playerControlsTimeoutSeconds,
            modifier = if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .offset(y = headerHeight)
                    .width(playerWidth)
                    .height(mainHeight)
                    .padding(start = 6.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
            },
            embeddedMode = !fullscreen,
            fullscreenOverride = fullscreen,
            onFullscreenChanged = { fullscreen = it }
        )

        if (!fullscreen) {
            ShowcaseHeader(
                type = type,
                categoryTitle = showcaseCategoryTitle(state, type),
                currentTitle = playing.media.title,
                headerHeight = headerHeight,
                safeTop = safeTop,
                safeStart = safeStart,
                safeEnd = safeEnd
            )

            ShowcaseDetailsPanel(
                state = state,
                item = previewItem,
                playingItem = playing.media,
                type = type,
                compact = compact,
                modifier = Modifier
                    .offset(x = playerWidth, y = headerHeight)
                    .width(detailsWidth)
                    .height(mainHeight)
                    .padding(start = 12.dp, end = safeEnd + 14.dp, top = 12.dp, bottom = 8.dp),
                onToggleFavorite = { toggleFavorite(previewItem) }
            )

            ShowcaseRail(
                state = state,
                type = type,
                items = queue,
                playingItem = playing.media,
                selectedItem = previewItem,
                railState = railState,
                posterFocusRequesters = posterFocusRequesters,
                railHeight = railHeight,
                safeBottom = safeBottom,
                safeStart = safeStart,
                safeEnd = safeEnd,
                compact = compact,
                loadMorePending = loadMorePending,
                onFocused = { previewItem = it },
                onPlay = { item ->
                    /*
                     * Touch and D-pad OK both mean "play this tile".
                     * Keep the movie in its existing rail slot.
                     */
                    previewItem = item

                    if (item.id != playing.media.id) {
                        play(item)
                    }
                },
                onLoadMore = {
                    if (!state.catalogLoadingMore && !loadMorePending) {
                        loadMoreStartItemCount = state.items.size
                        loadMoreFirstVisibleItemIndex = railState.firstVisibleItemIndex
                        loadMoreFirstVisibleItemScrollOffset = railState.firstVisibleItemScrollOffset
                        loadMoreObservedLoading = false
                        loadMorePending = true
                        loadMoreCatalog()
                    }
                }
            )
        }
    }
}

@Composable
private fun ShowcaseHeader(
    type: CatalogType,
    categoryTitle: String,
    currentTitle: String,
    headerHeight: Dp,
    safeTop: Dp,
    safeStart: Dp,
    safeEnd: Dp
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(Color(0xFF080808))
            .padding(
                start = safeStart + 18.dp,
                end = safeEnd + 18.dp,
                top = safeTop + 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${type.title.uppercase()} · ${categoryTitle.uppercase()}",
                color = Color(0xFFE50914),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                currentTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (LocalConfiguration.current.screenWidthDp >= 850) {
            Text(
                "←/→ Browse  •  OK Play/Open  •  ↑ Player  •  Back Exit",
                color = Color.LightGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(18.dp))
        }
        Text("NikTV", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ShowcaseDetailsPanel(
    state: NikTvState,
    item: MediaItem,
    playingItem: MediaItem,
    type: CatalogType,
    compact: Boolean,
    modifier: Modifier,
    onToggleFavorite: () -> Unit
) {
    val favoriteKind = showcaseFavoriteKind(type, state.selectedSeries != null)
    val favorite = state.favorites.any { it.kind == favoriteKind && it.media.id == item.id }
    val description = item.description?.trim()?.takeIf {
        it.isNotBlank() && !it.equals(item.title.trim(), ignoreCase = true)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (item.id == playingItem.id) "NOW PLAYING" else "SELECTED",
            color = Color(0xFFE50914),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            item.title,
            color = Color.White,
            style = if (compact) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineMedium
            },
            fontWeight = FontWeight.ExtraBold,
            maxLines = if (compact) 2 else 3,
            overflow = TextOverflow.Ellipsis
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShowcaseInfoBadge(type.title)
            ShowcaseInfoBadge(showcaseCategoryTitle(state, type))
        }

        item.liveProgramme?.let { programme ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "ON NOW",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    programme.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            description
                ?: "No additional metadata was provided by the IPTV portal for this title.",
            modifier = Modifier.weight(1f),
            color = if (description == null) Color.Gray else Color(0xFFE0E0E0),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) {
                4
            } else if (LocalConfiguration.current.screenHeightDp < 600) {
                6
            } else {
                12
            },
            overflow = TextOverflow.Ellipsis
        )

        /*
         * Playback/fullscreen buttons intentionally do not live here.
         *
         * D-pad / touch behavior belongs to the poster rail:
         *   - OK/tap a tile -> play it
         *   - the active tile stays in place and shows PLAYING
         *
         * Keeping only My List also reduces accidental focus jumps away
         * from the horizontal movie rail.
         */
        FilledTonalButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .fillMaxWidth()
                .showcaseFocusFrame(RoundedCornerShape(10.dp))
        ) {
            Icon(
                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (favorite) "Remove from My List" else "Add to My List")
        }
    }
}

@Composable
private fun ShowcaseInfoBadge(text: String) {
    Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF292929)) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BoxScope.ShowcaseRail(
    state: NikTvState,
    type: CatalogType,
    items: List<MediaItem>,
    playingItem: MediaItem,
    selectedItem: MediaItem,
    railState: LazyListState,
    posterFocusRequesters: MutableMap<String, FocusRequester>,
    railHeight: Dp,
    safeBottom: Dp,
    safeStart: Dp,
    safeEnd: Dp,
    compact: Boolean,
    loadMorePending: Boolean,
    onFocused: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onLoadMore: () -> Unit
) {
    // Keep the Showcase rail visually consistent across content types.
    val aspectRatio = 1f
    val posterWidth = when {
        type == CatalogType.MOVIES && compact -> 72.dp
        type == CatalogType.MOVIES -> 100.dp
        compact -> 136.dp
        else -> 184.dp
    }
    val showLoadMore =
        state.selectedType == type &&
            type in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES) &&
            (state.catalogHasMore || loadMorePending)

    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(railHeight)
            .background(Color(0xFF080808))
            .padding(
                start = safeStart + 12.dp,
                end = safeEnd + 12.dp,
                top = 8.dp,
                bottom = safeBottom + 8.dp
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (type) {
                    CatalogType.LIVE_TV -> "CHANNELS"
                    CatalogType.MOVIES -> "MOVIES"
                    CatalogType.SERIES -> "EPISODES"
                    CatalogType.RADIO -> "STATIONS"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text("${items.size} loaded", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }

        LazyRow(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = railState,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "showcase-${type.name}-${item.id}" }
            ) { _, item ->
                val requester = posterFocusRequesters.getOrPut(item.id) { FocusRequester() }
                ShowcasePosterCard(
                    item = item,
                    width = posterWidth,
                    aspectRatio = aspectRatio,
                    focusRequester = requester,
                    selected = selectedItem.id == item.id,
                    playing = playingItem.id == item.id,
                    favorite = state.favorites.any {
                        it.media.id == item.id &&
                            it.kind == showcaseFavoriteKind(type, state.selectedSeries != null)
                    },
                    onFocused = { onFocused(item) },
                    onClick = { onPlay(item) }
                )
            }

            if (showLoadMore) {
                item(key = "showcase-load-more-${type.name}") {
                    var loadMoreFocused by remember { mutableStateOf(false) }

                    val loadMoreScale by
                        androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (loadMoreFocused) 1.08f else 0.96f,
                            animationSpec =
                                androidx.compose.animation.core.tween(
                                    durationMillis = 140
                                ),
                            label = "showcaseCompactLoadMoreScale"
                        )

                    /*
                     * The outer slot follows the tile row. The control itself
                     * is intentionally compact rather than artwork-sized.
                     */
                    Box(
                        modifier = Modifier
                            .width(if (compact) 98.dp else 116.dp)
                            .height(posterWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                if (
                                    !state.catalogLoadingMore &&
                                    !loadMorePending
                                ) {
                                    onLoadMore()
                                }
                            },
                            modifier = Modifier
                                .width(if (compact) 92.dp else 108.dp)
                                .height(44.dp)
                                .graphicsLayer {
                                    scaleX = loadMoreScale
                                    scaleY = loadMoreScale
                                }
                                .onFocusChanged {
                                    loadMoreFocused = it.isFocused
                                }
                                .showcaseFocusFrame(
                                    RoundedCornerShape(8.dp)
                                ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(
                                horizontal = 10.dp,
                                vertical = 0.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE50914),
                                contentColor = Color.White
                            )
                        ) {
                            if (
                                state.catalogLoadingMore ||
                                loadMorePending
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(17.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Loading",
                                    maxLines = 1,
                                    style =
                                        MaterialTheme.typography.labelMedium
                                )
                            } else {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "Load more",
                                    maxLines = 1,
                                    style =
                                        MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcasePosterCard(
    item: MediaItem,
    width: Dp,
    aspectRatio: Float,
    focusRequester: FocusRequester,
    selected: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val artwork = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }

    /*
     * Netflix-style focus treatment:
     * - normal cards are deliberately a little smaller
     * - the selected card remains slightly emphasized
     * - the actively focused card grows clearly above the rest
     *
     * The LazyRow slot does not change size, so focus movement remains stable.
     */
    val targetScale = when {
        focused -> 1.12f
        selected -> 1.04f
        else -> 0.92f
    }

    val cardScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetScale,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 140),
        label = "showcasePosterScale"
    )

    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .bringIntoViewRequester(bringIntoViewRequester)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        onFocused()
                        scope.launch { bringIntoViewRequester.bringIntoView() }
                    }
                }
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .then(
                    if (focused) {
                        Modifier.shadow(
                            16.dp,
                            RoundedCornerShape(8.dp),
                            ambientColor = Color(0xFFE50914),
                            spotColor = Color(0xFFE50914)
                        )
                    } else Modifier
                ),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF202020),
            border = when {
                focused -> BorderStroke(4.dp, Color(0xFFFF3340))
                selected -> BorderStroke(2.dp, Color(0xFFE50914))
                else -> null
            }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (item.logo.isNullOrBlank()) {
                    Icon(Icons.Default.Movie, null, Modifier.size(38.dp), tint = Color.Gray)
                } else {
                    SubcomposeAsyncImage(
                        model = artwork,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        when (painter.state.value) {
                            is coil3.compose.AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent()
                            else ->
                                Icon(Icons.Default.Movie, null, Modifier.size(38.dp), tint = Color.Gray)
                        }
                    }
                }

                if (playing) {
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFFE50914)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(13.dp), tint = Color.White)
                            Text(
                                "PLAYING",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (favorite) {
                    Icon(
                        Icons.Default.Favorite,
                        "In My List",
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(19.dp),
                        tint = Color.White
                    )
                }

                if (selected && !focused) {
                    Icon(
                        Icons.Default.Check,
                        "Selected",
                        Modifier.align(Alignment.BottomEnd).padding(7.dp).size(18.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Text(
            item.title,
            color = if (focused || selected) Color.White else Color.LightGray,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Modifier.showcaseFocusFrame(shape: RoundedCornerShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .shadow(
                        12.dp,
                        shape,
                        ambientColor = Color(0xFFE50914),
                        spotColor = Color(0xFFE50914)
                    )
                    .border(3.dp, Color(0xFFFF3340), shape)
            } else Modifier
        )
}

private fun playbackSegmentShape(index: Int, count: Int): RoundedCornerShape {
    if (count <= 1) return RoundedCornerShape(8.dp)
    return when (index) {
        0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        count - 1 -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
        else -> RoundedCornerShape(0.dp)
    }
}

private fun showcaseCategoryTitle(state: NikTvState, type: CatalogType): String = when {
    type == CatalogType.SERIES && state.selectedSeries != null -> state.selectedSeries.title
    state.selectedType == type -> state.selectedCategory?.title ?: type.title
    else -> type.title
}

private fun showcaseFavoriteKind(type: CatalogType, hasSelectedSeries: Boolean): FavoriteKind =
    when (type) {
        CatalogType.LIVE_TV, CatalogType.RADIO -> FavoriteKind.CHANNEL
        CatalogType.MOVIES -> FavoriteKind.MOVIE
        CatalogType.SERIES ->
            if (hasSelectedSeries) FavoriteKind.EPISODE else FavoriteKind.SERIES
    }
