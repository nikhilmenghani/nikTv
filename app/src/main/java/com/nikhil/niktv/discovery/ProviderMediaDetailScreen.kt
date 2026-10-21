package com.nikhil.niktv.discovery

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*

internal data class ProviderDetailNavigation(
    val openDiscovery: (DashboardSurface) -> Unit, val classic: (String) -> Unit,
    val favorite: (FavoriteItem) -> Unit, val playEpisode: (DiscoveryCard, Int, Int) -> Unit,
    val recent: (RecentItem) -> Unit, val watched: (WatchedSeries, MediaItem) -> Unit,
    val category: (Category) -> Unit, val section: (TmdbHomeSection) -> Unit,
    val removeRecent: (RecentItem) -> Unit, val markWatched: (WatchedSeries, MediaItem) -> Unit,
    val dismissEpisode: (WatchedSeries, MediaItem) -> Unit,
    val playProvider: (MediaItem, List<MediaItem>, PlaybackQueueFilter) -> Unit)
internal val LocalProviderDetailNavigation = staticCompositionLocalOf<ProviderDetailNavigation?> { null }

@Composable
private fun ProviderDetailSynopsis(overview: String?, title: String, remoteInfo: Boolean = false,
    requester: FocusRequester = remember { FocusRequester() }, up: (() -> Unit)? = null, down: (() -> Unit)? = null) {
    if (overview.isNullOrBlank() && !remoteInfo) return
    var expanded by rememberSaveable(overview) { mutableStateOf(false) }
    var showInfo by remember(overview) { mutableStateOf(false) }
    var overflowing by remember(overview) { mutableStateOf(false) }
    val host = LocalView.current
    var hadInfo by remember { mutableStateOf(false) }
    LaunchedEffect(showInfo) {
        if (showInfo) hadInfo = true
        else if (hadInfo) {
            hadInfo = false
            repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (!overview.isNullOrBlank()) Text(overview, color = Color(0xFFD6D6DC), style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow })
        if (remoteInfo) DiscoveryAction("More info", Icons.Default.Info,
            modifier = Modifier.padding(top = 6.dp).focusRequester(requester).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { up?.invoke(); up != null }
                    Key.DirectionDown -> { down?.invoke(); down != null }
                    else -> false
                }
            }) { showInfo = true }
        else if (expanded || overflowing) DiscoveryAction(if (expanded) "Read less" else "Read more",
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            modifier = Modifier.padding(top = 6.dp)) { expanded = !expanded }
    }
    if (showInfo) FullDescriptionDialog(title, overview.orEmpty().ifBlank { "No description is available for this series." }) { showInfo = false }
}

/** Provider identity is never replaced by the TMDB identity used for presentation. */
@Composable
internal fun ProviderDetailHeader(item: MediaItem, type: CatalogType, app: NikTvState,
    play: () -> Unit, close: () -> Unit, favorite: () -> Unit, isFavorite: Boolean,
    openTmdbMovie: (TrendingMovie) -> Unit, openTmdbSeries: (TrendingSeries) -> Unit,
    playRequester: FocusRequester = remember { FocusRequester() },
    backRequester: FocusRequester = remember { FocusRequester() },
    down: () -> Unit = {}, playLabel: String = "Play", playEnabled: Boolean = true,
    progressLabel: String? = null, castUp: (() -> Unit)? = null, relatedUp: (() -> Unit)? = null,
    episodeDown: (() -> Unit)? = null,
    heroUp: (() -> Unit)? = null, topDown: (() -> Unit)? = null,
    synopsisRequester: FocusRequester = remember { FocusRequester() },
    synopsisUp: (() -> Unit)? = null, synopsisDown: (() -> Unit)? = null,
    tools: @Composable RowScope.() -> Unit = {},
    layout: @Composable (hero: @Composable () -> Unit, castRow: @Composable () -> Unit, supplementary: @Composable () -> Unit,
        cast: (() -> Boolean)?, related: (() -> Boolean)?) -> Unit = { hero, castRow, supplementary, _, _ ->
        Column { hero(); castRow(); supplementary() }
    }) {
    val context = LocalContext.current
    val navigation = LocalProviderDetailNavigation.current
    val configuration = LocalConfiguration.current
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val repository = remember(context) { DiscoveryRepository(context) }
    var metadata by remember(profile, item.id, type) { mutableStateOf(DiscoveryDetails(providerDetailFallback(item, type))) }
    var related by remember(profile, item.id, type) { mutableStateOf(emptyList<DiscoveryCard>()) }
    var relatedLoading by remember(profile, item.id, type) { mutableStateOf(false) }
    var relatedFailed by remember(profile, item.id, type) { mutableStateOf(false) }
    var loading by remember(profile, item.id, type) { mutableStateOf(true) }
    var failed by remember(profile, item.id, type) { mutableStateOf(false) }
    var retry by remember(profile, item.id, type) { mutableIntStateOf(0) }
    var actor by remember(profile, item.id, type) { mutableStateOf<DiscoveryPerson?>(null) }
    var relatedDetail by remember(profile, item.id, type) { mutableStateOf<DiscoveryCard?>(null) }
    var movieOptions by remember(profile, item.id) { mutableStateOf(false) }
    var actorOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    val actorPages = rememberSaveableStateHolder()
    val host = LocalView.current
    val scope = rememberCoroutineScope()
    val castEntry = remember { FocusRequester() }
    val relatedEntry = remember { FocusRequester() }
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    LaunchedEffect(profile, item.id, type, item.externalTmdbId, retry) {
        loading = true; failed = false
        val request = coroutineContext
        try {
            val result = repository.providerDetails(item, type) { cached -> request.ensureActive(); metadata = cached }
            request.ensureActive(); metadata = result
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { failed = true }
        finally { if (request.isActive) loading = false }
    }
    LaunchedEffect(profile, item.id, type, metadata.card.id, metadata.card.originalLanguage, metadata.card.genres) {
        related = emptyList()
        relatedLoading = metadata.card.id > 0
        relatedFailed = false
        if (metadata.card.id > 0) {
            val request = coroutineContext
            try { val result = repository.related(metadata.card); request.ensureActive(); related = relevantDiscoveryRecommendations(metadata.card, result) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { relatedFailed = true }
            finally { if (request.isActive) relatedLoading = false }
        }
    }
    fun focus(requester: FocusRequester, fallback: () -> Unit = down) {
        if (!runCatching { requester.requestFocus() }.getOrDefault(false)) fallback()
    }
    fun restoreActor() {
        actor = null
        scope.launch {
            repeat(60) {
                withFrameNanos { }
                if (host.hasWindowFocus() && runCatching { actorOrigin?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    val people = metadata.cast.filter { (it.id ?: 0) > 0 }.distinctBy { it.id }
    val landscape = configuration.screenWidthDp >= 600 || context.isTvLikeDevice(configuration)
    val phone = app.discoveryExperienceEnabled && usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration)) &&
        configuration.screenHeightDp >= configuration.screenWidthDp
    val wideSeries = type == CatalogType.SERIES && (configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(configuration))
    val toolbar: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onPreviewKeyEvent { event ->
                if (wideSeries && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    if (topDown != null) { topDown(); true } else runCatching { playRequester.requestFocus() }.getOrDefault(false)
                } else false
            },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                    modifier = Modifier.focusRequester(backRequester), onClick = close)
                Spacer(Modifier.weight(1f))
                tools()
                if (type == CatalogType.MOVIES) Box {
                    DiscoveryAction("Movie options", Icons.Default.MoreHoriz, iconOnly = true) { movieOptions = true }
                    ModernTileActionsMenu(movieOptions, isFavorite, dismiss = { movieOptions = false }, toggleFavorite = favorite,
                        clear = null, trailerItem = item.copy(externalTmdbId = metadata.card.id.takeIf { it > 0 } ?: item.externalTmdbId),
                        recommendationItem = item, recommendationType = type,
                        onTrailerReturned = { runCatching { playRequester.requestFocus() }.getOrDefault(false) })
                }
            }
    }
    val hero: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF090B10))) {
            Box(Modifier.fillMaxWidth()) {
            if (!phone) Box(Modifier.matchParentSize()) { DiscoveryBackdrop(metadata.card, reduceMotion) }
            Column {
            if (!phone) toolbar()
            DiscoveryHero(metadata.card, null, if (landscape) 280.dp else 255.dp, landscape,
                compact = configuration.screenHeightDp < 480, detail = true, loading = false,
                inList = isFavorite, playRequester = playRequester, infoRequester = remember { FocusRequester() },
                play = play, add = favorite, info = {}, onFocus = {},
                onDown = { if (type == CatalogType.SERIES) down() else if (people.isNotEmpty()) focus(castEntry) else if (related.isNotEmpty()) focus(relatedEntry) else down() },
                onUp = { if (wideSeries) { if (heroUp != null) { heroUp(); true } else runCatching { backRequester.requestFocus() }.getOrDefault(false) } else false },
                previous = {}, next = {}, carousel = false, index = 0, count = 0, playLabel = playLabel, playEnabled = playEnabled,
                portraitPhone = phone, phoneHeader = if (phone) toolbar else null, showSynopsis = false, compactDetail = true)
            }
            }
            progressLabel?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = Color.LightGray, style = MaterialTheme.typography.labelMedium) }
            ProviderDetailSynopsis(metadata.card.overview, metadata.card.title, wideSeries, synopsisRequester, synopsisUp, synopsisDown)
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 24.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (failed) DiscoveryAction("Retry TMDB details", Icons.Default.Refresh, modifier = Modifier.padding(horizontal = 24.dp)) { retry++ }
            if (!loading && !failed && metadata.card.id <= 0) Text("TMDB details were not matched. You can still play this title.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
    val castRow: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF090B10))) {
            if (people.isNotEmpty()) {
                if (type != CatalogType.SERIES) Text("Cast", Modifier.padding(start = 24.dp, top = 12.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
                LazyRow(Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { if (castUp != null) castUp() else if (type == CatalogType.SERIES) down() else focus(playRequester); true }
                    else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        if (type == CatalogType.SERIES) (episodeDown ?: down)() else if (related.isNotEmpty()) focus(relatedEntry); true
                    } else false
                }, contentPadding = PaddingValues(start = if (phone) 14.dp else 24.dp, end = if (phone) 14.dp else 24.dp,
                    top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(people, key = { _, person -> person.id!! }) { index, person ->
                        val requester = if (index == 0) castEntry else remember(person.id) { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        Surface(onClick = { actorOrigin = requester; actor = person },
                            modifier = Modifier.width(if (phone) 92.dp else 104.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused },
                            color = if (focused) Color(0xFF303641) else Color(0xFF181C24), shape = RoundedCornerShape(10.dp),
                            border = if (focused) BorderStroke(2.dp, Color.White) else null) {
                            Column(Modifier.padding(8.dp)) {
                                AsyncImage(person.portrait, person.name, Modifier.fillMaxWidth().height(105.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                                Text(person.name, Modifier.height(34.dp * fontScale).padding(top = 4.dp), color = Color.White,
                                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(person.role, Modifier.height(32.dp * fontScale), color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    val supplementary: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF090B10))) {
            if (related.isNotEmpty()) {
                if (type != CatalogType.SERIES) Text("Related", Modifier.padding(start = 24.dp, top = 8.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
                LazyRow(Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { if (relatedUp != null) relatedUp() else if (type == CatalogType.SERIES) down() else focus(if (people.isNotEmpty()) castEntry else playRequester); true }
                    else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { if (type == CatalogType.SERIES) (episodeDown ?: down)(); true } else false
                }, contentPadding = PaddingValues(start = if (phone) 14.dp else 24.dp, end = if (phone) 14.dp else 24.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(related, key = { _, card -> card.key }) { index, card ->
                        DiscoveryPhoneMediaCard(card.title, card.poster, card.date?.take(4).orEmpty(), if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp).dp else 112.dp,
                            wide = false, modifier = if (index == 0) Modifier.focusRequester(relatedEntry) else Modifier,
                            onClick = { relatedDetail = card }, onLongClick = null, onFocus = {})
                    }
                }
            } else if (type == CatalogType.SERIES) {
                var focused by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxWidth().padding(horizontal = if (phone) 14.dp else 24.dp, vertical = 8.dp)
                    .focusRequester(relatedEntry).onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false
                        else when (event.key) {
                            Key.DirectionUp -> { relatedUp?.invoke(); true }
                            Key.DirectionDown -> { (episodeDown ?: down)(); true }
                            else -> false
                        }
                    }.focusable(), shape = RoundedCornerShape(10.dp), color = Color(0xFF181C24),
                    border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58))) {
                    Text(when {
                        loading || relatedLoading -> "Loading recommendations…"
                        failed || relatedFailed -> "Recommendations could not be loaded. Try again later."
                        else -> "No relevant recommendations available."
                    }, Modifier.padding(20.dp), color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    layout(hero, castRow, supplementary,
        if (people.isEmpty()) null else ({ runCatching { castEntry.requestFocus() }.getOrDefault(false) }),
        if (type != CatalogType.SERIES && related.isEmpty()) null else ({ runCatching { relatedEntry.requestFocus() }.getOrDefault(false) }))
    actor?.takeIf { relatedDetail == null }?.let { person ->
        actorPages.SaveableStateProvider("actor:${person.id}") {
            DiscoveryActorDialog(person, load = repository::actorCredits,
                close = ::restoreActor, reduceMotion = reduceMotion, open = { relatedDetail = it })
        }
    }
    relatedDetail?.takeIf { navigation != null }?.let { card ->
        val nav = requireNotNull(navigation)
        Dialog(onDismissRequest = { relatedDetail = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val vm: DiscoveryViewModel = viewModel(key = "provider-related:$profile:${item.id}:${card.key}")
            var initialized by remember { mutableStateOf(false) }
            val current by vm.state.collectAsState()
            LaunchedEffect(card.key) {
                vm.bind(profile, if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES, emptyList())
                vm.openDetails(card); initialized = true
            }
            LaunchedEffect(initialized, current.detail) { if (initialized && current.detail == null) relatedDetail = null }
            DiscoveryPreviewScreen(app, if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES,
                selectSurface = { relatedDetail = null; close(); nav.openDiscovery(it) }, exit = { relatedDetail = null },
                classic = { relatedDetail = null; close(); nav.classic(it) },
                playCard = { selected -> relatedDetail = null; if (selected.series) openTmdbSeries(TrendingSeries(selected.show())) else openTmdbMovie(TrendingMovie(selected.movie())) },
                playEpisode = { selected, season, number -> relatedDetail = null; close(); nav.playEpisode(selected, season, number) },
                openRecent = nav.recent, openWatched = nav.watched,
                toggleFavorite = nav.favorite, openCategory = { relatedDetail = null; close(); nav.category(it) },
                openSection = { relatedDetail = null; close(); nav.section(it) }, removeRecent = nav.removeRecent,
                markWatched = nav.markWatched, dismissEpisode = nav.dismissEpisode, vm = vm)
        }
    }
}

@Composable
internal fun ProviderMovieDetailDialog(app: NikTvState, selected: ProviderMovieDetails,
    play: () -> Unit, close: () -> Unit, favorite: (FavoriteItem) -> Unit,
    download: (MediaItem, CatalogType, MediaItem?) -> Unit,
    removeDownload: (MediaItem, CatalogType) -> Unit,
    openTmdbMovie: (TrendingMovie) -> Unit, openTmdbSeries: (TrendingSeries) -> Unit) {
    val requester = remember(selected.item.id) { FocusRequester() }
    val saved = FavoriteItem(FavoriteKind.MOVIE, selected.item, profileKey = selected.profile)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StableTileFocusViewport {
            LazyColumn(Modifier.fillMaxSize().background(Color(0xFF090B10)).safeDrawingPadding()) {
                item {
                    ProviderDetailHeader(selected.item, CatalogType.MOVIES, app, play, close,
                        favorite = { favorite(saved) }, isFavorite = app.favorites.any { it.key == saved.key },
                        openTmdbMovie = { close(); openTmdbMovie(it) }, openTmdbSeries = { close(); openTmdbSeries(it) }, playRequester = requester,
                        tools = {
                            if (app.offlineDownloads.any { it.catalogType == CatalogType.MOVIES && it.media.id == selected.item.id })
                                DiscoveryAction("Remove download", Icons.Default.Delete) { removeDownload(selected.item, CatalogType.MOVIES) }
                            else DiscoveryAction("Download", Icons.Default.Download) { download(selected.item, CatalogType.MOVIES, null) }
                        })
                }
            }
        }
        LaunchedEffect(selected.item.id) { repeat(12) { withFrameNanos { }; if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    }
}
