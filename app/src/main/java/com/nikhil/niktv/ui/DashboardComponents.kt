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

@Composable
internal fun DashboardMovieRail(
    title: String,
    movies: List<TrendingMovie>,
    loading: Boolean,
    error: String?,
    open: (TrendingMovie) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        when {
            loading && movies.isEmpty() -> {
                ModernSectionHeader(title, "Loading from TMDB…")
                DashboardDiscoveryLoadingRow()
            }

            movies.isNotEmpty() -> {
                ModernRail(
                    title = title,
                    entries = movies,
                    media = { entry ->
                        entry.tmdb.asMediaItem().let { tmdbMedia ->
                            if (!tmdbMedia.logo.isNullOrBlank()) tmdbMedia
                            else tmdbMedia.copy(logo = entry.iptv?.logo)
                        }
                    },
                    open = open,
                    aspectRatio = { 2f / 3f },
                    subtitle = { entry ->
                        buildList {
                            entry.tmdb.releaseYear?.let { add(it.toString()) }
                            entry.tmdb.voteAverage?.takeIf { it > 0.0 }?.let { add("★ ${String.format(java.util.Locale.US, "%.1f", it)}") }
                            add(
                                if (entry.iptv != null) "IPTV ready"
                                else "IPTV lookup on select"
                            )
                        }.joinToString(" · ")
                    },
                    titleMaxLines = 2,
                    subtitleMaxLines = 2,
                    initialDisplayCount = 10,
                    maximumDisplayCount = 50
                )
            }

            !error.isNullOrBlank() ->
                ModernSectionHeader(title, error)
        }
    }
}

@Composable
internal fun TmdbMatchScreen(
    title: String,
    year: Int?,
    candidates: List<MediaItem>,
    loadingMore: Boolean,
    select: (MediaItem) -> Unit,
    close: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.remoteFocusFrame(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Choose the IPTV version", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(title)
                        year?.let { append(" ($it)") }
                        append(" · ${candidates.size} matches found")
                        if (loadingMore) append(" · Finding more…")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(candidates, key = { it.id }) { candidate ->
                Surface(
                    onClick = { select(candidate) },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = artworkRequest(LocalContext.current, candidate),
                            contentDescription = null,
                            modifier = Modifier.width(72.dp).height(104.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(candidate.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            candidate.description?.takeIf(String::isNotBlank)?.let {
                                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                listOfNotNull(
                                    candidate.portalCategoryId?.let { "Category $it" },
                                    candidate.externalTmdbId?.let { "TMDB $it" },
                                    "IPTV ID ${candidate.id}"
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Select")
                    }
                }
            }
            if (loadingMore) item("loading-more-matches") {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Searching Wio for more matches…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DashboardSeriesRail(
    series: List<TrendingSeries>,
    loading: Boolean,
    error: String?,
    open: (TrendingSeries) -> Unit,
    title: String = "Trending Series"
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        when {
            loading && series.isEmpty() -> {
                ModernSectionHeader(
                    title,
                    "Loading from TMDB…"
                )
                DashboardDiscoveryLoadingRow()
            }

            series.isNotEmpty() -> {
                ModernRail(
                    title = title,
                    entries = series,
                    media = { entry ->
                        entry.tmdb.asMediaItem().let { tmdbMedia ->
                            if (!tmdbMedia.logo.isNullOrBlank()) tmdbMedia
                            else tmdbMedia.copy(logo = entry.iptv?.logo)
                        }
                    },
                    open = open,
                    aspectRatio = { 2f / 3f },
                    subtitle = { entry ->
                        buildList {
                            entry.tmdb.firstAirYear?.let { add(it.toString()) }
                            entry.tmdb.voteAverage?.takeIf { it > 0.0 }?.let { add("★ ${String.format(java.util.Locale.US, "%.1f", it)}") }
                            add(
                                if (entry.iptv != null) "IPTV ready"
                                else "IPTV lookup on select"
                            )
                        }.joinToString(" · ")
                    },
                    titleMaxLines = 2,
                    subtitleMaxLines = 2,
                    initialDisplayCount = 10,
                    maximumDisplayCount = 50
                )
            }

            !error.isNullOrBlank() ->
                ModernSectionHeader("Trending Series", error)
        }
    }
}

@Composable
internal fun DashboardDiscoveryLoadingRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = Color(0xFFE50914)
        )
        Text(
            "Finding titles to browse",
            color = Color.LightGray
        )
    }
}


@Composable
internal fun ModernHero(item: MediaItem?, recentAction: (() -> Unit)?, catalogAction: (() -> Unit)?, profileName: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).height(50.vh())) {
        if (item?.logo != null) AsyncImage(artworkRequest(context, item), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF090909), Color(0xCC090909), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xFF090909)))))
        Column(Modifier.align(Alignment.BottomStart).widthIn(max = 720.dp).padding(horizontal = 28.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item?.title ?: "Welcome to NikTV", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item?.description?.takeIf(String::isNotBlank) ?: if (profileName.isBlank()) "Choose something to watch" else "Streaming from $profileName",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val action = recentAction ?: catalogAction
            if (action != null) Button(onClick = action, modifier = Modifier.remoteFocusFrame(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (recentAction != null) "Resume" else "Play")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> ModernRail(
    title: String,
    entries: List<T>,
    media: (T) -> MediaItem,
    open: (T) -> Unit,
    aspectRatio: (T) -> Float = { 16f / 9f },
    progress: (T) -> PlaybackProgress? = { null },
    remove: ((T) -> Unit)? = null,
    clear: (() -> Unit)? = null,
    subtitle: (T) -> String? = { null },
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    cardWidth: Dp = 180.dp,
    initialDisplayCount: Int = Int.MAX_VALUE,
    maximumDisplayCount: Int = Int.MAX_VALUE,
    loadMore: (() -> Unit)? = null,
    isFavorite: (T) -> Boolean = { false },
    toggleFavorite: ((T) -> Unit)? = null
) {
    var focusedDetails by remember(title) { mutableStateOf<Pair<String, String>?>(null) }
    val maximum = maximumDisplayCount.coerceAtLeast(1)
    val cappedMaximum = minOf(entries.size, maximum)
    var visibleCount by remember(title, initialDisplayCount, maximumDisplayCount) {
        mutableIntStateOf(minOf(cappedMaximum, initialDisplayCount.coerceAtLeast(1)))
    }
    val rowState = rememberLazyListState()
    val itemFocusRequesters = remember(title) { mutableMapOf<String, FocusRequester>() }
    var pendingFocusIndex by remember(title) { mutableStateOf<Int?>(null) }
    var pendingRequestedCount by remember(title) { mutableStateOf<Int?>(null) }
    var preservedFirstVisibleIndex by remember(title) { mutableIntStateOf(0) }
    var preservedFirstVisibleOffset by remember(title) { mutableIntStateOf(0) }

    LaunchedEffect(entries.size, pendingRequestedCount) {
        val requestedCount = pendingRequestedCount ?: return@LaunchedEffect
        val firstNewIndex = pendingFocusIndex ?: return@LaunchedEffect
        if (entries.size > firstNewIndex) {
            visibleCount = minOf(maximum, requestedCount, entries.size)
            pendingRequestedCount = null
        }
    }

    LaunchedEffect(visibleCount, pendingFocusIndex, entries.size) {
        val targetIndex = pendingFocusIndex ?: return@LaunchedEffect
        if (targetIndex >= visibleCount || targetIndex >= entries.size) return@LaunchedEffect
        val entry = entries[targetIndex]
        val key = "${media(entry).id}-${media(entry).title}"
        // The Load More tile occupied targetIndex before the append. Preserve
        // the exact viewport while that tile is replaced by the first newly
        // revealed card. This avoids both LazyRow key relocation and a visible
        // horizontal jump during rapid D-pad navigation.
        rowState.scrollToItem(preservedFirstVisibleIndex, preservedFirstVisibleOffset)
        withFrameNanos { }
        rowState.scrollToItem(preservedFirstVisibleIndex, preservedFirstVisibleOffset)
        withFrameNanos { }
        runCatching { itemFocusRequesters.getOrPut(key) { FocusRequester() }.requestFocus() }
        pendingFocusIndex = null
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ModernSectionHeader(title, action = clear?.let { action -> { TextButton(onClick = action) { Text("Clear", color = Color.LightGray) } } })
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(entries.take(visibleCount), key = { entry -> "${media(entry).id}-${media(entry).title}" }) { entry ->
                val itemKey = "${media(entry).id}-${media(entry).title}"
                ModernPosterCard(
                    item = media(entry),
                    aspectRatio = aspectRatio(entry),
                    modifier = Modifier
                        .width(cardWidth)
                        .onFocusChanged { if (it.hasFocus) focusedDetails = media(entry).title to subtitle(entry).orEmpty() }
                        .focusRequester(itemFocusRequesters.getOrPut(itemKey) { FocusRequester() }),
                    progress = progress(entry),
                    onClick = { open(entry) },
                    titleMaxLines = 2,
                    titleMinLines = 2,
                    focusedScale = 1.08f,
                    isFavorite = isFavorite(entry),
                    toggleFavorite = toggleFavorite?.let { action -> { action(entry) } },
                    removeAction = remove?.let { action -> { action(entry) } }
                ) {
                    subtitle(entry).orEmpty().let {
                        Text(
                            it,
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (visibleCount < maximum && (visibleCount < entries.size || loadMore != null)) {
                item("more-$title") {
                    Surface(
                        onClick = {
                            if (pendingRequestedCount == null) {
                                val requestedCount = minOf(maximum, visibleCount + 10)
                                preservedFirstVisibleIndex = rowState.firstVisibleItemIndex
                                preservedFirstVisibleOffset = rowState.firstVisibleItemScrollOffset
                                pendingFocusIndex = visibleCount
                                if (entries.size >= requestedCount) {
                                    visibleCount = requestedCount
                                } else if (loadMore != null) {
                                    pendingRequestedCount = requestedCount
                                    loadMore.invoke()
                                } else {
                                    visibleCount = minOf(requestedCount, entries.size)
                                }
                            }
                        },
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(16f / 9f)
                            .remoteFocusFrame(RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF202020)
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (pendingRequestedCount != null) {
                                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFFE50914))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(if (pendingRequestedCount != null) "Loading…" else "Load 10 more", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Text("${minOf(visibleCount, entries.size)} loaded", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        HomeTileDetails(focusedDetails?.first.orEmpty(), focusedDetails?.second.orEmpty())
    }
}

@Composable
internal fun ModernArtworkFallback(
    title: String,
    modifier: Modifier = Modifier
) {
    /*
     * MODERN_TYPOGRAPHY_POSTER_V6
     *
     * A missing image becomes a deliberate mini-poster instead of an error
     * state. The title hash picks a stable dark gradient, so different titles
     * get subtle variety while staying inside the NikTV visual language.
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
            .padding(12.dp)
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
            color = Color.White.copy(alpha = 0.11f),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )

        Text(
            title,
            modifier = Modifier.align(Alignment.BottomStart),
            color = Color.White.copy(alpha = 0.96f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModernPosterCard(
    item: MediaItem,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    titleMinLines: Int = 1,
    isFavorite: Boolean = false,
    toggleFavorite: (() -> Unit)? = null,
    removeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    unfocusedScale: Float = 1f,
    focusedScale: Float = 1f,
    footer: (@Composable () -> Unit)? = null
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val touchPressed = !isTv && pressed

    /*
     * TV_SAFE_POSTER_FOCUS_V80
     *
     * TV keeps fixed card bounds so D-pad focus cannot crop or bounce a lazy
     * row/grid. Touch devices retain the existing transient press lift.
     */
    val posterScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue =
            if (isTv) {
                1f
            } else if (touchPressed) {
                if (isTablet) 1.035f else 1.025f
            } else if (focused) {
                focusedScale
            } else {
                unfocusedScale
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = 170
            ),
        label = "catalogPosterScale"
    )
    val artworkModel = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }
    val posterShape = RoundedCornerShape(10.dp)
    val fraction = if (progress != null && progress.durationMillis > 0L)
        (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .then(returningTile.modifier)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            // Keep the focus target's measured and transformed bounds stable.
            // Scaling the focus node itself makes LazyRow/LazyGrid repeatedly
            // bring its changing bounds into view, which looks like a bounce.
            .onFocusChanged { focused = it.isFocused }
            .remoteCombinedClickable(
                onClick = returningTile.open,
                onLongClick = if (toggleFavorite != null || removeAction != null) {
                    { menuOpen = true }
                } else onLongClick,
                interactionSource = interactionSource
            )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = posterScale
                    scaleY = posterScale
                }
                .zIndex(if (focused || touchPressed) 1f else 0f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                .then(
                    if (focused || touchPressed) {
                        Modifier.touchTileShadow(
                            isTv = isTv,
                            elevation = if (isTablet) 10.dp else 6.dp,
                            shape = posterShape,
                            clip = false,
                            ambientColor = Color(0x55000000),
                            spotColor = Color(0x22E50914)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(posterShape)
                .background(Color(0xFF242424))
                .border(
                    when {
                        isTv && focused -> 3.dp
                        !isTv && focused -> 2.dp
                        else -> 1.dp
                    },
                    when {
                        focused -> Color.White
                        touchPressed -> Color(0xFF555A63)
                        else -> Color(0xFF30343B)
                    },
                    posterShape
                ), contentAlignment = Alignment.Center) {
                if (item.logo.isNullOrBlank()) {
                    ModernArtworkFallback(
                        title = item.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SubcomposeAsyncImage(
                        artworkModel,
                        item.title,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        when (painter.state.value) {
                            is coil3.compose.AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent()
                            else ->
                                ModernArtworkFallback(
                                    title = item.title,
                                    modifier = Modifier.fillMaxSize()
                                )
                        }
                    }
                }
                if (fraction > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF333333))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color(0xFFE50914)))
                }
            }
            Text(
                item.title,
                modifier = if (focused && titleMaxLines == 1) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                color = Color.White,
                style =
                    if (isTv) {
                        MaterialTheme.typography.labelMedium
                    } else if (titleMinLines > 1) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                minLines = titleMinLines,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            footer?.invoke()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.align(Alignment.TopEnd),
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(12.dp)
        ) {
            toggleFavorite?.let { action ->
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Remove from My List" else "Add to My List") },
                    leadingIcon = { Icon(if (isFavorite) Icons.Default.HeartBroken else Icons.Default.FavoriteBorder, null) },
                    onClick = { menuOpen = false; action() }
                )
            }
            removeAction?.let { action ->
                DropdownMenuItem(
                    text = { Text("Remove from recent") },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    onClick = { menuOpen = false; action() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModernMediaListCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    supportingText: String? = item.description,
    compact: Boolean = false,
    isCurrentlyPlaying: Boolean = false,
    channelStyle: Boolean = false
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "mediaListProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!isTv && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "mediaListTouchPress"
    )
    val visualProgress =
        if (isTv) focusProgress
        else maxOf(focusProgress, pressProgress)
    val active = focused || pressed
    val scale =
        1f + (
            when {
                isTv -> 0f
                isTablet -> 0.035f
                else -> 0.025f
            } * visualProgress
        )
    val shape = RoundedCornerShape(16.dp)
    val backgroundColor = lerp(
        Color(0xFF15171B),
        if (isTv) Color(0xFF2C3038) else Color(0xFF20242B),
        visualProgress
    )
    val borderColor = lerp(
        Color(0xFF30343B),
        when {
            isTv -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )
    val channelPalette = remember(item.id, item.title) {
        val palettes = listOf(
            Color(0xFF172336) to Color(0xFF35151A),
            Color(0xFF1A2830) to Color(0xFF12171C),
            Color(0xFF2B2030) to Color(0xFF151218),
            Color(0xFF1B2B28) to Color(0xFF101718),
            Color(0xFF302419) to Color(0xFF17120F),
            Color(0xFF22242B) to Color(0xFF32151A)
        )
        palettes[
            ((item.id + item.title).hashCode() and Int.MAX_VALUE) %
                palettes.size
        ]
    }

    Surface(
        modifier = modifier.then(returningTile.modifier)
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 4.dp else 10.dp,
                vertical = if (channelStyle) 4.dp else 0.dp
            )
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .touchTileShadow(
                isTv = isTv,
                elevation =
                    (
                        when {
                            isTablet -> 10f
                            else -> 6f
                        } * visualProgress
                    ).dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x55000000),
                spotColor = Color(0x22E50914)
            )
            .onFocusChanged { focused = it.isFocused }
            .remoteCombinedClickable(
                onClick = returningTile.open,
                onLongClick = { menuOpen = true },
                interactionSource = interactionSource
            ),
        shape = shape,
        color = backgroundColor,
        border = when {
            isCurrentlyPlaying && !(isTv && focused) ->
                BorderStroke(2.dp, Color(0xFFE50914))

            else ->
                BorderStroke(
                    when {
                        isTv && focused -> 3.dp
                        !isTv && focused -> 2.dp
                        else -> 1.dp
                    },
                    borderColor
                )
        }
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (channelStyle) {
                        Modifier.heightIn(min = 94.dp)
                    } else {
                        Modifier
                    }
                )
                .padding(
                    horizontal = if (compact) 8.dp else 10.dp,
                    vertical = if (compact) 8.dp else 9.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (channelStyle) 12.dp
                else if (compact) 8.dp
                else 12.dp
            )
        ) {
            if (isCurrentlyPlaying || (channelStyle && isTv && focused)) {
                Box(
                    Modifier
                        .width(if (channelStyle) 4.dp else 3.dp)
                        .height(
                            if (channelStyle) 62.dp
                            else if (compact) 52.dp
                            else 42.dp
                        )
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFE50914))
                )
            }

            Box(
                modifier = Modifier
                    .then(
                        if (channelStyle) {
                            Modifier
                                .width(84.dp)
                                .height(62.dp)
                        } else {
                            Modifier
                                .width(if (compact) 72.dp else 132.dp)
                                .aspectRatio(16f / 9f)
                        }
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (channelStyle) {
                            Modifier.background(
                                Brush.linearGradient(
                                    listOf(
                                        channelPalette.first,
                                        channelPalette.second
                                    )
                                )
                            )
                        } else {
                            Modifier.background(Color(0xFF242424))
                        }
                    )
                    .border(
                        1.dp,
                        Color(0xFF343840),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (item.logo.isNullOrBlank()) {
                    Icon(
                        if (channelStyle) {
                            Icons.Default.LiveTv
                        } else {
                            Icons.Default.SmartDisplay
                        },
                        contentDescription = null,
                        modifier = Modifier.size(
                            if (channelStyle) 30.dp
                            else if (compact) 30.dp
                            else 42.dp
                        ),
                        tint = Color(0xFFD9DDE5)
                    )
                } else {
                    SubcomposeAsyncImage(
                        model = artworkRequest(context, item),
                        contentDescription = item.title,
                        modifier =
                            if (channelStyle) {
                                Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            } else {
                                Modifier.fillMaxSize()
                            },
                        contentScale =
                            if (channelStyle) {
                                ContentScale.Fit
                            } else {
                                ContentScale.Crop
                            }
                    ) {
                        when (painter.state.value) {
                            is coil3.compose.AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent()

                            else -> Icon(
                                if (channelStyle) {
                                    Icons.Default.LiveTv
                                } else {
                                    Icons.Default.SmartDisplay
                                },
                                contentDescription = null,
                                modifier = Modifier.size(
                                    if (channelStyle) 30.dp
                                    else if (compact) 30.dp
                                    else 42.dp
                                ),
                                tint = Color(0xFFD9DDE5)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(
                    if (channelStyle) 5.dp else 3.dp
                )
            ) {
                Text(
                    text = item.title,
                    modifier =
                        if (channelStyle && focused) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE
                            )
                        } else {
                            Modifier
                        },
                    color =
                        if (active) Color.White
                        else Color(0xFFE1E3E7),
                    style =
                        if (isTv) {
                            MaterialTheme.typography.labelMedium
                        } else if (channelStyle) {
                            MaterialTheme.typography.titleSmall
                        } else if (compact) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                    fontWeight =
                        if (active || isCurrentlyPlaying) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Medium
                        },
                    maxLines =
                        if (isTv) 2
                        else if (channelStyle) 1
                        else if (compact) 2
                        else 1,
                    overflow = TextOverflow.Ellipsis
                )

                supportingText
                    ?.takeIf { text ->
                        text.isNotBlank() &&
                            !(isTv && text.isRedundantTvTileSubtitle())
                    }
                    ?.let { text ->
                        Text(
                            text = text,
                            color =
                                if (active) {
                                    Color(0xFFC4C8D0)
                                } else {
                                    Color(0xFF9298A2)
                                },
                            style =
                                if (channelStyle) {
                                    MaterialTheme.typography.labelSmall
                                } else {
                                    MaterialTheme.typography.bodySmall
                                },
                            maxLines = if (isTv || channelStyle) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
            }

            if (!compact) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (active) Color.White else Color.Gray
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(12.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (isFavorite) {
                            "Remove from My List"
                        } else {
                            "Add to My List"
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isFavorite) {
                            Icons.Default.HeartBroken
                        } else {
                            Icons.Default.FavoriteBorder
                        },
                        contentDescription = null
                    )
                },
                onClick = {
                    menuOpen = false
                    toggleFavorite()
                }
            )
        }
    }
}

internal fun List<PlaybackProgress>.progressFor(item: MediaItem): PlaybackProgress? =
    firstOrNull { it.key.contains(item.id) }

@Composable
internal fun Int.vh(): androidx.compose.ui.unit.Dp = (LocalConfiguration.current.screenHeightDp * this / 100f).dp
