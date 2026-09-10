package com.nikhil.niktv.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.R
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ModernSeriesDetailScreen(
    state: NikTvState,
    play: (MediaItem) -> Unit,
    closeSeries: () -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleSeriesWatch: () -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    setUseTmdbEpisodeMetadata: (Boolean) -> Unit,
    downloadForOffline: (MediaItem, CatalogType, MediaItem?) -> Unit,
    removeOfflineDownload: (MediaItem, CatalogType) -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    refreshCatalog: () -> Unit,
    loadMoreEpisodes: () -> Unit
) {
    val series = state.selectedSeries ?: return
    var episodeSortDescending by rememberSaveable(series.id) { mutableStateOf(true) }
    var searchQuery by rememberSaveable(series.id) { mutableStateOf("") }
    var episodeSearchEditing by rememberSaveable(series.id) { mutableStateOf(false) }
    var handledPlaybackReturnFocusId by remember(series.id) { mutableStateOf<String?>(null) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    val episodeSearchRequester = remember(series.id) { FocusRequester() }
    val returningEpisodeRequester = remember(series.id) { FocusRequester() }
    val episodeListState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val episodeContext = LocalContext.current
    val episodeConfiguration = LocalConfiguration.current
    val episodeIsTv = episodeContext.isTvLikeDevice(episodeConfiguration)
    val compactPortrait = episodeConfiguration.screenWidthDp < 600 &&
        episodeConfiguration.orientation == Configuration.ORIENTATION_PORTRAIT
    val mobileEpisodeLayout =
        !episodeIsTv &&
            episodeConfiguration.smallestScreenWidthDp < 600

    fun activateEpisodeSearch() {
        episodeSearchEditing = true
    }

    BackHandler(enabled = episodeSearchEditing) {
        episodeSearchEditing = false
        keyboardController?.hide()
    }

    LaunchedEffect(episodeSearchEditing) {
        if (episodeSearchEditing) {
            delay(80L)
            runCatching { episodeSearchRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val isFavorite = remember(state.favorites, series) {
        state.favorites.any { it.media.id == series.id && it.kind == FavoriteKind.SERIES }
    }
    val isWatched = state.watchedSeries.any { it.series.id == series.id }

    val availableSeasons = state.availableSeriesSeasons
    val selectedSeason = state.selectedSeriesSeason

    val comparator = remember(episodeSortDescending) {
        episodeComparator(episodeSortDescending)
    }

    val seasonFilteredItems = remember(state.items, selectedSeason) {
        state.items
    }

    val filteredEpisodes = remember(seasonFilteredItems, searchQuery, comparator, episodeSortDescending) {
        val query = searchQuery.trim()
        val baseList = if (query.isBlank()) seasonFilteredItems else {
            seasonFilteredItems.filter { ep ->
                val episodeNumber = ep.episodeNumber ?: ep.title.episodeNumberFromTitle()
                val seasonNumber = ep.seasonNumber ?: ep.title.seasonNumberFromTitle()
                val episodeDescription = ep.description?.takeUnless { description ->
                    description.trim().equals(series.description?.trim(), ignoreCase = true)
                }.orEmpty()
                val searchableDetails = buildString {
                    append(ep.title)
                    append(' ')
                    append(episodeDescription)
                    episodeNumber?.let { append(" episode $it e$it") }
                    if (seasonNumber != null && episodeNumber != null) append(" s${seasonNumber}e$episodeNumber")
                }
                val numericQuery = query.toIntOrNull()
                if (numericQuery != null) episodeNumber?.toString()?.contains(query) == true
                else searchableDetails.matchesTitleKeywords(query)
            }
                .sortedByDescending { it.title.titleKeywordScore(query) }
        }
        if (selectedSeason != null) {
            baseList.sortedWith(comparator)
        } else {
            val seasonComp = if (episodeSortDescending) {
                compareByDescending<Map.Entry<Int?, List<MediaItem>>> { it.key ?: -1 }
            } else {
                compareBy<Map.Entry<Int?, List<MediaItem>>>({ it.key == null }, { it.key ?: Int.MAX_VALUE })
            }
            baseList.groupBy { it.seasonNumber }.entries
                .sortedWith(seasonComp)
                .flatMap { (_, eps) -> eps.sortedWith(comparator) }
        }
    }

    val recentEpisode = remember(state.recentlyPlayed, state.playbackProgress, state.items, series) {
        val recentWatched = state.recentlyPlayed.firstOrNull { recent ->
            recent.kind == FavoriteKind.SERIES && (recent.media.id == series.id || recent.series?.id == series.id)
        }?.lastPlayed
            ?.let { last -> state.items.firstOrNull { it.id == last.id } }
        if (recentWatched != null) return@remember recentWatched
        val lastProgress = state.playbackProgress
            .filter { prog -> prog.key.contains(series.id) }
            .maxByOrNull { it.updatedAtMillis }
        if (lastProgress != null) {
            state.items.firstOrNull { it.id in lastProgress.key }
        } else null
    }

    val latestEpisode = state.items.maxWithOrNull(
        compareBy<MediaItem>({ it.seasonNumber ?: it.title.seasonNumberFromTitle() ?: 0 },
            { it.episodeNumber ?: it.title.episodeNumberFromTitle() ?: 0 }, { it.title })
    )
    val primaryEpisodeToPlay = recentEpisode ?: if (episodeSortDescending) latestEpisode ?: state.items.firstOrNull()
        else state.items.firstOrNull()

    LaunchedEffect(state.playbackReturnFocusId, filteredEpisodes) {
        val returningId = state.playbackReturnFocusId ?: return@LaunchedEffect
        if (handledPlaybackReturnFocusId == returningId) return@LaunchedEffect
        // Keep the filter and result position, but do not reopen the search editor or
        // keyboard after returning from playback.
        episodeSearchEditing = false
        keyboardController?.hide()
        val episodeIndex = filteredEpisodes.indexOfFirst { it.id == returningId }
        if (episodeIndex >= 0) {
            episodeListState.scrollToItem(episodeIndex + 2)
            delay(120L)
            runCatching { returningEpisodeRequester.requestFocus() }
            handledPlaybackReturnFocusId = returningId
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090909))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = episodeListState,
            contentPadding = PaddingValues(bottom = 64.dp)
        ) {
            item("series-hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compactPortrait) 275.dp else 340.dp)
                ) {
                    val backdropUrl = series.logo
                    if (!backdropUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = artworkRequest(LocalContext.current, series),
                            contentDescription = series.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0xFF090909))))
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x99000000),
                                        Color(0x22000000),
                                        Color(0xDD090909),
                                        Color(0xFF090909)
                                    )
                                )
                            )
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeriesHeroActionIcon(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            description = "Back to browse",
                            onClick = closeSeries
                        )
                        Spacer(Modifier.weight(1f))
                        SeriesHeroActionIcon(
                            icon = Icons.Default.Search,
                            description = "Search episodes",
                            onClick = { activateEpisodeSearch() }
                        )
                        SeriesHeroActionIcon(
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            description = if (isFavorite) "Remove series from My List" else "Add series to My List",
                            onClick = { toggleFavorite(series) },
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White
                        )
                        SeriesHeroActionIcon(
                            icon = if (isWatched) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            description = if (isWatched) "Stop watching for new episodes" else "Watch for new episodes",
                            onClick = toggleSeriesWatch,
                            tint = if (isWatched) MaterialTheme.colorScheme.primary else Color.White
                        )
                        SeriesHeroActionIcon(
                            icon = Icons.Default.Refresh,
                            description = "Refresh episodes",
                            onClick = refreshCatalog
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = series.title,
                            style = if (mobileEpisodeLayout) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Visible
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            ) {
                                Text(
                                    text = "SERIES",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (availableSeasons.isNotEmpty()) {
                                Text(
                                    text = "${availableSeasons.size} ${if (availableSeasons.size == 1) "Season" else "Seasons"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.LightGray
                                )
                                Text("•", color = Color.Gray)
                            }
                            Text(
                                text = "${state.items.size} Episodes${selectedSeason?.let { " in Season $it" }.orEmpty()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.LightGray
                            )
                        }

                        if (!series.description.isNullOrBlank() && !mobileEpisodeLayout) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = series.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            item("series-controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (primaryEpisodeToPlay != null) {
                            Button(
                                onClick = { play(primaryEpisodeToPlay) },
                                modifier = Modifier.height(42.dp).remoteFocusFrame(RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (recentEpisode != null) "Resume ${primaryEpisodeToPlay.actionEpisodeLabel()}"
                                    else "Play ${primaryEpisodeToPlay.actionEpisodeLabel()}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (availableSeasons.size > 1) {
                            Box {
                                Surface(
                                    onClick = { seasonDropdownExpanded = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF1E2430),
                                    contentColor = Color.White,
                                    modifier = Modifier.height(42.dp).remoteFocusFrame()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = selectedSeason?.let { "Season $it" } ?: "Choose Season",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                }

                                DropdownMenu(
                                    expanded = seasonDropdownExpanded,
                                    onDismissRequest = { seasonDropdownExpanded = false }
                                ) {
                                    val orderedSeasons = if (episodeSortDescending) availableSeasons.reversed() else availableSeasons
                                    orderedSeasons.forEach { season ->
                                        DropdownMenuItem(
                                            text = { Text("Season $season") },
                                            onClick = { loadSeriesSeason(season); seasonDropdownExpanded = false },
                                            trailingIcon = if (selectedSeason == season) {{ Icon(Icons.Default.Check, null) }} else null
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                val latestFirst = !episodeSortDescending
                                episodeSortDescending = latestFirst
                                availableSeasons
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { seasons -> loadSeriesSeason(if (latestFirst) seasons.last() else seasons.first()) }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E2430),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            contentColor = Color.White,
                            modifier = Modifier.height(42.dp).remoteFocusFrame()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (episodeSortDescending) "Latest First" else "Oldest First",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }

                        Surface(
                            onClick = { setUseTmdbEpisodeMetadata(!state.useTmdbEpisodeMetadata) },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E2430),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            contentColor = Color.White,
                            modifier = Modifier.height(42.dp).remoteFocusFrame()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Use TMDB", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Switch(
                                    checked = state.useTmdbEpisodeMetadata,
                                    onCheckedChange = null,
                                    modifier = Modifier.focusProperties { canFocus = false }
                                )
                            }
                        }

                    }

                    if (!mobileEpisodeLayout) Text(
                        text = "${filteredEpisodes.size} ${if (filteredEpisodes.size == 1) "episode" else "episodes"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )

                }
            }

            if (state.items.isEmpty() && state.loading) {
                item("episodes-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Loading episodes from server…", color = Color.LightGray)
                        }
                    }
                }
            } else if (filteredEpisodes.isEmpty()) {
                item("episodes-empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No episodes found for this series." else "No episodes matching “${searchQuery.trim()}”",
                            color = Color.LightGray
                        )
                    }
                }
            } else {
                items(filteredEpisodes, key = { it.id }) { episode ->
                    val progress = remember(state.playbackProgress, episode.id) {
                        state.playbackProgress.firstOrNull { prog ->
                            prog.key.contains(episode.id)
                        } ?: state.playbackProgress.firstOrNull { prog ->
                            prog.key.endsWith(":${episode.id}")
                        }
                    }
                    val isRecent = recentEpisode?.id == episode.id

                    ModernEpisodeCard(
                        episode = episode,
                        series = series,
                        progress = progress,
                        isCurrentResume = isRecent,
                        onClick = { play(episode) },
                        offlineDownload = state.offlineDownloads.firstOrNull { download ->
                            download.catalogType == CatalogType.SERIES && download.media.id == episode.id
                        },
                        offlineRevision = state.offlineDownloadRevision,
                        onDownload = { downloadForOffline(episode, CatalogType.SERIES, series) },
                        onRemoveDownload = { removeOfflineDownload(episode, CatalogType.SERIES) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (state.playbackReturnFocusId == episode.id) Modifier.focusRequester(returningEpisodeRequester) else Modifier)
                            .padding(
                                horizontal = if (mobileEpisodeLayout) 14.dp else 24.dp,
                                vertical = if (mobileEpisodeLayout) 4.dp else 6.dp
                            )
                    )
                }
                if (state.episodeHasMore && searchQuery.isBlank()) {
                    item("episodes-load-more-${state.episodePage}") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                            Button(
                                onClick = loadMoreEpisodes,
                                enabled = !state.episodeLoadingMore,
                                modifier = Modifier.fillMaxWidth().height(48.dp).remoteFocusFrame(RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                            ) {
                                if (state.episodeLoadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.episodeLoadingMore) "Loading…" else "Load more episodes", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (episodeSearchEditing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF5151820),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        episodeSearchEditing = false
                        keyboardController?.hide()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close episode search", tint = Color.White)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(episodeSearchRequester),
                        placeholder = { Text("Episode name or number") },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear episode search")
                            }
                        }} else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModernEpisodeCard(
    episode: MediaItem,
    series: MediaItem,
    progress: PlaybackProgress?,
    isCurrentResume: Boolean,
    onClick: () -> Unit,
    offlineDownload: OfflineMediaDownload?,
    offlineRevision: Long,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val episodeContext = LocalContext.current
    val episodeConfiguration = LocalConfiguration.current
    val isTv = episodeContext.isTvLikeDevice(episodeConfiguration)
    var confirmRemoval by remember { mutableStateOf(false) }
    val offlineInfo = remember(offlineDownload, offlineRevision) {
        offlineDownload?.let { OfflineMediaDownloads.info(episodeContext, it.requestId) }
    }
    val offlineStatus = offlineInfo?.status

    /*
     * MOBILE_SERIES_EPISODE_CARD_V36
     *
     * Phone episode rows prioritize episode identity and synopsis over artwork.
     * Tablet and Fire TV keep the existing 136x78 geometry and trailing play
     * affordance. Phones use a narrower thumbnail, move LAST WATCHED onto the
     * artwork, and allow the title to wrap to two lines.
     */
    val mobileLayout =
        !isTv &&
            episodeConfiguration.smallestScreenWidthDp < 600
    val episodeDescription = episode.description?.takeUnless { description ->
        description.trim().equals(series.description?.trim(), ignoreCase = true)
    }

    val progressFraction = remember(progress) {
        if (progress != null && progress.durationMillis > 0L) {
            (progress.positionMillis.toFloat() / progress.durationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Color(0xFF321417) else if (isCurrentResume) Color(0xFF1B2232) else Color(0xFF121620),
        border = when {
            focused -> BorderStroke(4.dp, Color(0xFFFF2633))
            isCurrentResume -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            else -> null
        },
        shadowElevation = if (focused) 14.dp else 0.dp,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (mobileLayout) 10.dp else 12.dp,
                vertical = if (mobileLayout) 10.dp else 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (mobileLayout) 10.dp else 14.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (mobileLayout) {
                            Modifier
                                .width(104.dp)
                                .aspectRatio(16f / 9f)
                        } else {
                            Modifier
                                .width(136.dp)
                                .height(78.dp)
                        }
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                val imageUrl = episode.logo ?: series.logo
                if (!imageUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = artworkRequest(episodeContext, episode.copy(logo = imageUrl)),
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(if (mobileLayout) 28.dp else 34.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }

                if (progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(Color(0xFFE50914))
                        )
                    }
                }

            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val epNumber = episode.episodeNumber ?: episode.title.episodeNumberFromTitle()
                    val seasonNumber = episode.seasonNumber ?: episode.title.seasonNumberFromTitle()
                    val badge = when {
                        seasonNumber != null && epNumber != null -> "S${seasonNumber}:E${epNumber}"
                        epNumber != null -> "EP $epNumber"
                        else -> null
                    }
                    val airDate = episode.displayAirDate()
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (badge != null) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (airDate != null) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = Color.White.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                            ) {
                                Text(
                                    text = airDate,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (isCurrentResume && !mobileLayout) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "LAST WATCHED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                /*
                 * MOBILE_LAST_WATCHED_BADGE_V37
                 *
                 * The phone thumbnail is intentionally compact. Keep the full
                 * LAST WATCHED label in the metadata column instead of clipping
                 * it inside the 104dp artwork.
                 */
                if (isCurrentResume && mobileLayout) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "LAST WATCHED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = 6.dp,
                                vertical = 2.dp
                            )
                        )
                    }
                }

                Text(
                    text = episode.displayTitle(series),
                    style = if (mobileLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                if (!episodeDescription.isNullOrBlank()) {
                    Text(
                        text = episodeDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = if (offlineDownload == null || offlineStatus in setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)) {
                    onDownload
                } else {
                    { confirmRemoval = true }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    offlineInfo?.percent?.takeIf { offlineStatus == OfflineDownloadStatus.DOWNLOADING }?.let {
                        CircularProgressIndicator(progress = { it / 100f }, Modifier.size(38.dp), strokeWidth = 3.dp)
                    }
                    Icon(
                        when (offlineStatus) {
                            OfflineDownloadStatus.COMPLETE -> Icons.Default.DownloadDone
                            OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.PAUSED -> Icons.Default.Downloading
                            else -> Icons.Default.DownloadForOffline
                        },
                        contentDescription = when (offlineStatus) {
                            OfflineDownloadStatus.COMPLETE -> "Remove offline download"
                            OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.PAUSED -> "Cancel offline download"
                            else -> "Download episode for offline playback"
                        },
                        modifier = Modifier.size(22.dp),
                        tint = if (offlineStatus == OfflineDownloadStatus.COMPLETE) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }

            if (!mobileLayout) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play ${episode.title}",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        title = { Text(if (offlineStatus == OfflineDownloadStatus.COMPLETE) "Delete download?" else "Cancel download?") },
        text = { Text("Remove “${episode.displayTitle(series)}” from offline downloads?") },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Keep") } },
        confirmButton = { Button(onClick = { confirmRemoval = false; onRemoveDownload() }) { Text("Remove") } }
    )
}
