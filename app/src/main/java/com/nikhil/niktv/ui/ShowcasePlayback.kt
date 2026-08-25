package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
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

private fun Context.isShowcaseTvLikeDevice(
    configuration: Configuration
): Boolean =
    packageManager.hasSystemFeature(
        PackageManager.FEATURE_LEANBACK
    ) ||
        configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(
            PackageManager.FEATURE_TOUCHSCREEN
        )

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
    loadMoreCatalog: () -> Unit,
    refreshPlaybackQueue: () -> Unit,
    loadMoreEpisodes: () -> Unit = {}
) {
    val playing = state.nowPlaying ?: return
    val type = playing.catalogType

    /*
     * PLAYBACK_SPECIFIC_PAGINATION_V15
     *
     * Movies/Live TV paginate the active catalog category. Series playback
     * paginates episodes from the selected series/season instead. Keep these
     * states separate so mobile does not accidentally ask the Series catalog
     * for another page while an episode queue is on screen.
     */
    val usesEpisodePagination =
        type == CatalogType.SERIES &&
            playing.series != null &&
            state.selectedSeries?.id == playing.series.id

    val playbackCategoryId =
        playing.media.portalCategoryId

    val catalogPaginationMatchesPlayback =
        !usesEpisodePagination &&
            playbackCategoryId != null &&
            state.selectedType == type &&
            state.selectedCategory?.id == playbackCategoryId

    val playbackHasMore =
        if (usesEpisodePagination) {
            state.episodeHasMore
        } else {
            catalogPaginationMatchesPlayback &&
                state.catalogHasMore
        }

    val playbackLoadingMore =
        if (usesEpisodePagination) {
            state.episodeLoadingMore
        } else {
            catalogPaginationMatchesPlayback &&
                state.catalogLoadingMore
        }

    var fullscreen by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf(playing.media) }

    /*
     * SHOWCASE_BROWSE_CONTROLS_DISMISS_V10
     *
     * Increment whenever the poster rail or Load More becomes the user's
     * active browsing surface. PlayerScreen observes this counter and removes
     * its overlay controls without affecting playback.
     */
    var embeddedControlsDismissRequest by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(playing.media.id) { previewItem = playing.media }

    val queue = remember(
        playing.media.id,
        playing.episodeQueue,
        state.items,
        state.selectedType,
        type
    ) {
        // The playback queue is category-specific. state.items may still point
        // at the first dashboard category and must never replace this list.
        val source = playing.episodeQueue

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

    LaunchedEffect(loadMorePending, playbackLoadingMore, state.items.size) {
        if (!loadMorePending) return@LaunchedEffect

        if (playbackLoadingMore) {
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
    val showcaseContext = LocalContext.current
    val isTv =
        showcaseContext.isShowcaseTvLikeDevice(
            configuration
        )
    val compact =
        configuration.screenWidthDp < 900 ||
            configuration.screenHeightDp < 520
    val mobileUiDesign by rememberMobileUiDesign()
    val youtubeMobile =
        configuration.smallestScreenWidthDp < 600 &&
            mobileUiDesign.usesYouTubeOn(configuration)

    if (youtubeMobile) {
        YouTubeMobileShowcase(
            state = state,
            playingItem = playing.media,
            type = type,
            items = queue,
            fullscreen = fullscreen,
            onFullscreenChanged = { fullscreen = it },
            onBack = onBack,
            onRetry = onRetry,
            onRetryAlternateDecoder = onRetryAlternateDecoder,
            onPlaybackAuthorizationFailure = onPlaybackAuthorizationFailure,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onProgress = onProgress,
            onPlay = { item ->
                if (item.id == playing.media.id) fullscreen = true else play(item)
            },
            onToggleFavorite = { toggleFavorite(playing.media) },
            onRefresh = refreshPlaybackQueue,
            hasMore = playbackHasMore,
            loadingMore = playbackLoadingMore,
            loadMorePending = loadMorePending,
            onLoadMore = {
                if (!playbackLoadingMore && !loadMorePending) {
                    loadMoreStartItemCount = state.items.size
                    loadMoreObservedLoading = false
                    loadMorePending = true

                    if (usesEpisodePagination) {
                        loadMoreEpisodes()
                    } else {
                        loadMoreCatalog()
                    }
                }
            }
        )
        return
    }

    /*
     * SHOWCASE_TV_DETAILS_SPACE_V13
     *
     * Fire TV commonly reports fewer dp vertically than tablets. Give the
     * details pane more width so descriptions wrap less aggressively.
     */
    val playerWidthFraction = when {
        isTv -> 0.64f
        compact -> 0.61f
        else -> 0.69f
    }

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
        /*
         * MOVIE_RAIL_MULTILINE_TITLES_V9
         *
         * Keep the larger readable square artwork from v7, but reserve
         * additional vertical room underneath movie tiles for wrapped titles.
         * This prevents longer names from looking artificially cropped.
         */
        val railHeight = when {
            type == CatalogType.MOVIES &&
                isTv &&
                maxHeight < 500.dp ->
                196.dp

            type == CatalogType.MOVIES &&
                isTv ->
                220.dp

            type == CatalogType.MOVIES &&
                maxHeight < 480.dp ->
                184.dp

            type == CatalogType.MOVIES &&
                compact ->
                204.dp

            type == CatalogType.MOVIES ->
                246.dp

            maxHeight < 480.dp ->
                142.dp

            compact ->
                172.dp

            else ->
                224.dp
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
            playbackEngine = state.playbackEngine,
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
            embeddedControlsDismissRequest =
                embeddedControlsDismissRequest,
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
                hasMore = playbackHasMore,
                loadingMore = playbackLoadingMore,
                loadMorePending = loadMorePending,
                onBrowseFocus = {
                    embeddedControlsDismissRequest++
                },
                onFocused = { previewItem = it },
                onPlay = { item ->
                    /*
                     * SHOWCASE_NOW_PLAYING_FULLSCREEN_V8
                     *
                     * Another tile means "play this item".
                     * Pressing the tile that is already playing enters
                     * fullscreen. Stable queue ordering means the tile itself
                     * remains in the same rail position.
                     */
                    previewItem = item

                    if (item.id == playing.media.id) {
                        fullscreen = true
                    } else {
                        play(item)
                    }
                },
                onLoadMore = {
                    if (!playbackLoadingMore && !loadMorePending) {
                        loadMoreStartItemCount = state.items.size
                        loadMoreFirstVisibleItemIndex = railState.firstVisibleItemIndex
                        loadMoreFirstVisibleItemScrollOffset = railState.firstVisibleItemScrollOffset
                        loadMoreObservedLoading = false
                        loadMorePending = true

                        if (usesEpisodePagination) {
                            loadMoreEpisodes()
                        } else {
                            loadMoreCatalog()
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun YouTubeMobileShowcase(
    state: NikTvState,
    playingItem: MediaItem,
    type: CatalogType,
    items: List<MediaItem>,
    fullscreen: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryAlternateDecoder: (Long) -> Unit,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    loadMorePending: Boolean,
    onLoadMore: () -> Unit
) {
    val playing = state.nowPlaying ?: return
    val favoriteKind = showcaseFavoriteKind(type, state.selectedSeries != null)
    val favorite = state.favorites.any {
        it.kind == favoriteKind && it.media.id == playingItem.id
    }
    val description = playingItem.description?.trim()?.takeIf {
        it.isNotBlank() && !it.equals(playingItem.title.trim(), ignoreCase = true)
    }
    var descriptionExpanded by remember(playingItem.id) { mutableStateOf(false) }
    var descriptionOverflows by remember(playingItem.id) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        if (!fullscreen) Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        ) {
            Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item(key = "details-${playingItem.id}") {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            playingItem.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                ShowcaseInfoBadge(type.title)
                                ShowcaseInfoBadge(showcaseCategoryTitle(state, type))
                            }
                            FilledTonalIconButton(onClick = onToggleFavorite) {
                                Icon(
                                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (favorite) "Remove from My List" else "Add to My List"
                                )
                            }
                            FilledTonalIconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Clear cache and refresh list")
                            }
                        }

                        Text(
                            description ?: "No description is available for this title.",
                            color = if (description == null) Color.Gray else Color(0xFFD6D6D6),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = {
                                if (!descriptionExpanded) descriptionOverflows = it.hasVisualOverflow
                            }
                        )
                        if (descriptionOverflows || descriptionExpanded) {
                            TextButton(
                                onClick = { descriptionExpanded = !descriptionExpanded },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (descriptionExpanded) "Show less" else "Show more")
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Text(
                        if (type == CatalogType.SERIES) "Episodes" else "Up next",
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    YouTubeMobileQueueItem(
                        item = item,
                        playing = item.id == playingItem.id,
                        onClick = { onPlay(item) }
                    )
                }

                if (hasMore || loadingMore || loadMorePending) {
                    item(key = "load-more") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore || loadMorePending) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            } else {
                                OutlinedButton(onClick = onLoadMore) {
                                    Text(
                                        when (type) {
                                            CatalogType.LIVE_TV -> "Load more channels"
                                            CatalogType.MOVIES -> "Load more movies"
                                            CatalogType.SERIES -> "Load more episodes"
                                            CatalogType.RADIO -> "Load more"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Keep one player instance in a stable composition slot. Resizing it between
        // embedded and fullscreen must not recreate the engine or reload the stream.
        PlayerScreen(
            media = playing,
            onBack = if (fullscreen) ({ onFullscreenChanged(false) }) else onBack,
            onRetry = onRetry,
            onRetryAlternateDecoder = onRetryAlternateDecoder,
            onPlaybackAuthorizationFailure = onPlaybackAuthorizationFailure,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onProgress = onProgress,
            controlsTimeoutSeconds = state.playerControlsTimeoutSeconds,
            playbackEngine = state.playbackEngine,
            modifier = if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.TopCenter)
            },
            embeddedMode = !fullscreen,
            fullscreenOverride = fullscreen,
            onFullscreenChanged = onFullscreenChanged
        )
    }
}

@Composable
private fun YouTubeMobileQueueItem(
    item: MediaItem,
    playing: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val artwork = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }

    Surface(
        onClick = onClick,
        color = if (playing) Color(0xFF242424) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFF252525)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (item.logo.isNullOrBlank()) {
                        ShowcaseArtworkFallback(item.title, true, Modifier.fillMaxSize())
                    } else {
                        SubcomposeAsyncImage(
                            model = artwork,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        ) {
                            when (painter.state.value) {
                                is coil3.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                                else -> ShowcaseArtworkFallback(item.title, true, Modifier.fillMaxSize())
                            }
                        }
                    }
                    if (playing) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(5.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                                Text("PLAYING", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.seasonNumber != null || item.episodeNumber != null) {
                    Text(
                        listOfNotNull(
                            item.seasonNumber?.let { "Season $it" },
                            item.episodeNumber?.let { "Episode $it" }
                        ).joinToString(" · "),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Icon(Icons.Default.MoreVert, "More options", tint = Color.LightGray)
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
    val detailsContext = LocalContext.current
    val detailsConfiguration = LocalConfiguration.current
    val isTv =
        detailsContext.isShowcaseTvLikeDevice(
            detailsConfiguration
        )

    /*
     * SHOWCASE_TV_DESCRIPTION_FIT_V13
     *
     * Fire TV has less logical dp-height and a narrower details column than
     * many tablets. Tighten spacing/typography without changing tablet/mobile.
     */
    Column(
        modifier,
        verticalArrangement =
            Arrangement.spacedBy(
                if (isTv) 7.dp else 10.dp
            )
    ) {
        Text(
            if (item.id == playingItem.id) "NOW PLAYING" else "SELECTED",
            color = Color(0xFFE50914),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            item.title,
            color = Color.White,
            style = when {
                isTv ->
                    MaterialTheme.typography.titleLarge

                compact ->
                    MaterialTheme.typography.titleLarge

                else ->
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
            style =
                if (isTv) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            maxLines = when {
                isTv -> 10
                compact -> 4
                detailsConfiguration.screenHeightDp < 600 -> 6
                else -> 12
            },
            overflow = TextOverflow.Ellipsis
        )

        /*
         * Playback/fullscreen buttons intentionally do not live here.
         *
         * D-pad / touch behavior belongs to the poster rail:
         *   - OK/tap another tile -> play it
         *   - OK/tap the active tile -> fullscreen
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
    hasMore: Boolean,
    loadingMore: Boolean,
    loadMorePending: Boolean,
    onBrowseFocus: () -> Unit,
    onFocused: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onLoadMore: () -> Unit
) {
    // Keep the Showcase rail visually consistent across content types.
    val aspectRatio = 1f
    val showcaseConfiguration =
        LocalConfiguration.current
    val showcaseScreenWidthDp =
        showcaseConfiguration.screenWidthDp
    val showcaseContext =
        LocalContext.current
    val isTv =
        showcaseContext.isShowcaseTvLikeDevice(
            showcaseConfiguration
        )

    /*
     * PLAYER_MOVIE_THUMBNAIL_READABILITY_V7
     *
     * Keep square tiles, but make the artwork large enough that poster text
     * and faces remain recognizable. On wide screens (such as 1280px
     * landscape tablets/TVs) use 124dp instead of 100dp.
     */
    val posterWidth = when {
        type == CatalogType.MOVIES && compact ->
            88.dp

        type == CatalogType.MOVIES &&
            showcaseScreenWidthDp >= 1200 ->
            124.dp

        type == CatalogType.MOVIES ->
            112.dp

        compact ->
            136.dp

        else ->
            184.dp
    }
    val showLoadMore =
        hasMore ||
            loadingMore ||
            loadMorePending

    /*
     * SHOWCASE_LOAD_MORE_EXCLUSIVE_FOCUS_V5
     *
     * Keep metadata selection separate from visual focus. When Load More
     * owns focus, the previously browsed movie may remain the details item,
     * but it must not also look selected.
     */
    var loadMoreFocused by remember { mutableStateOf(false) }

    LaunchedEffect(showLoadMore) {
        if (!showLoadMore) {
            loadMoreFocused = false
        }
    }

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
            /*
             * Extra end padding is intentional. The compact Load More control
             * scales up when focused, and without trailing room its right edge
             * / focus glow is clipped by LazyRow's viewport.
             */
            contentPadding = PaddingValues(
                start = 6.dp,
                end = 40.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
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
                    selected =
                        !loadMoreFocused &&
                            selectedItem.id == item.id,
                    playing = playingItem.id == item.id,
                    favorite = state.favorites.any {
                        it.media.id == item.id &&
                            it.kind == showcaseFavoriteKind(type, state.selectedSeries != null)
                    },
                    onFocused = {
                        onBrowseFocus()
                        onFocused(item)
                    },
                    onClick = {
                        /*
                         * Touch does not always produce a focus transition
                         * first, so explicitly dismiss player controls here.
                         */
                        onBrowseFocus()
                        onPlay(item)
                    }
                )
            }

            if (showLoadMore) {
                item(key = "showcase-load-more-${type.name}") {
                    val loadMoreShape =
                        RoundedCornerShape(8.dp)

                    val loadMoreSlotBringIntoViewRequester =
                        remember { BringIntoViewRequester() }

                    val loadMoreScope =
                        rememberCoroutineScope()

                    val loadMoreScale by
                        androidx.compose.animation.core.animateFloatAsState(
                            targetValue =
                                if (isTv) {
                                    1f
                                } else if (loadMoreFocused) {
                                    1.06f
                                } else {
                                    0.96f
                                },
                            animationSpec =
                                androidx.compose.animation.core.tween(
                                    durationMillis = 140
                                ),
                            label = "showcaseCompactLoadMoreScaleV6"
                        )

                    /*
                     * SHOWCASE_LOAD_MORE_EDGE_SAFE_V6
                     *
                     * Reserve substantially more width than the visible
                     * button. We bring this whole slot into view when focus
                     * arrives, leaving room for scale, border and shadow.
                     */
                    Box(
                        modifier = Modifier
                            .width(if (compact) 136.dp else 156.dp)
                            .height(posterWidth)
                            .bringIntoViewRequester(
                                loadMoreSlotBringIntoViewRequester
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                onBrowseFocus()

                                if (
                                    !loadingMore &&
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
                                .onFocusChanged { focusState ->
                                    loadMoreFocused =
                                        focusState.isFocused

                                    if (focusState.isFocused) {
                                        onBrowseFocus()

                                        loadMoreScope.launch {
                                            withFrameNanos { }
                                            loadMoreSlotBringIntoViewRequester
                                                .bringIntoView()
                                        }
                                    }
                                }
                                .then(
                                    if (loadMoreFocused) {
                                        Modifier
                                            .then(
                                                if (!isTv) {
                                                    Modifier.shadow(
                                                        12.dp,
                                                        loadMoreShape,
                                                        ambientColor =
                                                            Color(0xFFE50914),
                                                        spotColor =
                                                            Color(0xFFE50914)
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .border(
                                                3.dp,
                                                Color(0xFFFF3340),
                                                loadMoreShape
                                            )
                                    } else {
                                        Modifier
                                    }
                                ),
                            shape = loadMoreShape,
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
                                loadingMore ||
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
private fun ShowcaseArtworkFallback(
    title: String,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    /*
     * SHOWCASE_TYPOGRAPHY_POSTER_V6
     *
     * Missing artwork should look intentional, not broken. The title hash
     * selects one of several restrained NikTV gradients so a row of missing
     * posters still has visual variety without inventing movie artwork.
     */
    val palette = remember(title) {
        val palettes = listOf(
            Color(0xFF111827) to Color(0xFF3A1014),
            Color(0xFF20242D) to Color(0xFF101827),
            Color(0xFF26172D) to Color(0xFF12121A),
            Color(0xFF162727) to Color(0xFF101416),
            Color(0xFF2B1D16) to Color(0xFF15100D),
            Color(0xFF222222) to Color(0xFF351015)
        )

        palettes[
            (title.hashCode() and Int.MAX_VALUE) %
                palettes.size
        ]
    }

    val initial = remember(title) {
        title
            .trim()
            .firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            ?: "N"
    }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    listOf(palette.first, palette.second)
                )
            )
            .padding(if (compact) 7.dp else 10.dp)
    ) {
        Text(
            "NIKTV",
            modifier = Modifier.align(Alignment.TopStart),
            color = Color(0xFFFF7A82),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )

        Text(
            initial,
            modifier = Modifier.align(Alignment.Center),
            color = Color.White.copy(alpha = 0.12f),
            style =
                if (compact) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displaySmall
                },
            fontWeight = FontWeight.Black,
            maxLines = 1
        )

        Text(
            title,
            modifier = Modifier.align(Alignment.BottomStart),
            color = Color.White.copy(alpha = 0.94f),
            style =
                if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
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
    val configuration = LocalConfiguration.current
    val isTv =
        context.isShowcaseTvLikeDevice(
            configuration
        )
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
        isTv -> 1f
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

                        if (!isTv) {
                            scope.launch {
                                bringIntoViewRequester
                                    .bringIntoView()
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .then(
                    if (focused && !isTv) {
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
                    ShowcaseArtworkFallback(
                        title = item.title,
                        compact = width <= 100.dp,
                        modifier = Modifier.fillMaxSize()
                    )
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
                                ShowcaseArtworkFallback(
                                    title = item.title,
                                    compact = width <= 100.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
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

        /*
         * SHOWCASE_MULTILINE_TITLE_V9
         *
         * The player rail is primarily visual, but the title still needs to
         * be readable. Allow several wrapped lines rather than forcing a
         * single/truncated label beneath otherwise readable artwork.
         */
        Text(
            item.title,
            modifier = Modifier.heightIn(
                min =
                    when {
                        isTv -> 42.dp
                        width <= 100.dp -> 58.dp
                        else -> 46.dp
                    }
            ),
            color =
                if (focused || selected) {
                    Color.White
                } else {
                    Color.LightGray
                },
            style =
                if (isTv) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
            fontWeight =
                if (focused) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
            maxLines =
                if (width <= 100.dp) {
                    4
                } else {
                    3
                },
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun Modifier.showcaseFocusFrame(shape: RoundedCornerShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv =
        context.isShowcaseTvLikeDevice(
            configuration
        )

    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .then(
                        if (!isTv) {
                            Modifier.shadow(
                                12.dp,
                                shape,
                                ambientColor = Color(0xFFE50914),
                                spotColor = Color(0xFFE50914)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .border(
                        3.dp,
                        Color(0xFFFF3340),
                        shape
                    )
            } else {
                Modifier
            }
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
