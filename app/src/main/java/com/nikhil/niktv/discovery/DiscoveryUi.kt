@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nikhil.niktv.discovery

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.imageLoader
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal val LocalOpenDiscovery = staticCompositionLocalOf<(DashboardSurface) -> Unit> { {} }
internal val LocalRefreshDiscoveryMatches = staticCompositionLocalOf<((DiscoveryCard) -> Unit)?> { null }
internal val LocalOpenDiscoverySeries = staticCompositionLocalOf<((MediaItem) -> Unit)?> { null }
internal val LocalDownloadDiscoveryEpisode = staticCompositionLocalOf<(DiscoveryCard, Int, Int) -> Unit> { { _, _, _ -> } }
internal val LocalPlayDiscoveryEpisode = staticCompositionLocalOf<(DiscoveryCard, Int, Int) -> Unit> { { _, _, _ -> } }
private val Background = Color(0xFF090B10)
private val Accent = Color(0xFFE50925)
internal val trialSurfaces = listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)
internal fun DashboardSurface.trialTitle() = when (this) { DashboardSurface.MOVIES -> "Movies 2"; DashboardSurface.SERIES -> "Series 2"; else -> "Home 2" }

private data class RailEntry(val key: String, val title: String, val artwork: String?, val card: DiscoveryCard? = null,
    val subtitle: String = "", val progress: Float? = null, val open: () -> Unit,
    val matchCount: Int? = null,
    val popup: (@Composable (Boolean, () -> Unit, () -> Boolean) -> Unit)? = null)
private data class Rail(val key: String, val title: String, val entries: List<RailEntry>, val ranked: Boolean = false,
    val section: TmdbHomeSection? = null)

@Composable
internal fun DiscoveryPreviewScreen(
    app: NikTvState, surface: DashboardSurface, selectSurface: (DashboardSurface) -> Unit,
    exit: () -> Unit, classic: (String) -> Unit, playCard: (DiscoveryCard) -> Unit,
    playEpisode: (DiscoveryCard, Int, Int) -> Unit, openRecent: (RecentItem) -> Unit,
    openWatched: (WatchedSeries, MediaItem) -> Unit, toggleFavorite: (FavoriteItem) -> Unit,
    openCategory: (Category) -> Unit, openSection: (TmdbHomeSection) -> Unit,
    removeRecent: (RecentItem) -> Unit, markWatched: (WatchedSeries, MediaItem) -> Unit,
    dismissEpisode: (WatchedSeries, MediaItem) -> Unit,
    vm: DiscoveryViewModel = viewModel(key = "discovery2:${app.savedProfile?.cacheKey()}")
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val downloadEpisode = LocalDownloadDiscoveryEpisode.current
    val refreshDiscoveryMatches = LocalRefreshDiscoveryMatches.current
    val openProviderSeries = LocalOpenDiscoverySeries.current
    val providerNavigation = LocalProviderDetailNavigation.current
    val configuration = LocalConfiguration.current
    val phone = configuration.smallestScreenWidthDp < 600 && !context.isTvLikeDevice(configuration)
    val landscape = !phone
    val compactLandscape = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenHeightDp < 480
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val loadedState by vm.state.collectAsStateWithLifecycle()
    val state = loadedState.takeIf { it.profile == profile && it.surface == surface } ?: DiscoveryState(profile = profile, surface = surface)
    val localScope = rememberCoroutineScope()
    var configure by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var actor by remember(profile, surface) { mutableStateOf<DiscoveryPerson?>(null) }
    var actorOrigin by remember(profile, surface) { mutableStateOf<String?>(null) }
    var actorFocusOrigin by remember(profile, surface) { mutableStateOf<String?>(null) }
    var tilePopupOpen by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var contentFocused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var featured by remember(profile, surface) { mutableStateOf<DiscoveryCard?>(null) }
    var returningTo by rememberSaveable(profile, surface) { mutableStateOf<String?>(null) }
    var routeOrigins by rememberSaveable(profile, surface) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lastDetail = remember { mutableStateOf<String?>(null) }
    val returnRequesters = remember(profile, surface) { mutableMapOf<String, FocusRequester>() }
    val saved = rememberSaveableStateHolder()
    val tabRequester = remember(profile, surface) { FocusRequester() }
    val playRequester = remember(profile, surface, state.detail?.key) { FocusRequester() }
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(profile, surface) { vm.bind(profile, surface, app.tmdbSectionsBySurface[surface]) }
    DisposableEffect(vm, profile, surface) {
        vm.resume(profile, surface)
        onDispose { vm.pause() }
    }
    LaunchedEffect(state.hero?.key) { if (state.detail == null) featured = state.hero }
    LaunchedEffect(state.detail?.key) {
        if (lastDetail.value != null && state.detail == null) {
            withFrameNanos { }; returningTo?.let { runCatching { returnRequesters[it]?.requestFocus() } }
        }
        lastDetail.value = state.detail?.key
    }
    BackHandler { if (state.detail != null) vm.closeDetails() else exit() }
    fun details(card: DiscoveryCard, origin: String) {
        returningTo = origin
        routeOrigins = (routeOrigins + ((state.detail?.key ?: "landing") to origin)) - card.key
        vm.openDetails(card)
    }
    fun dismissActor() {
        actor = null
        val key = actorFocusOrigin
        returningTo = key
        if (key != null) actorOrigin?.let { routeOrigins = routeOrigins + (it to key) }
        localScope.launch {
            repeat(60) {
                withFrameNanos { }
                if (hostView.hasWindowFocus() && runCatching { returnRequesters[key]?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    LaunchedEffect(state.detail?.key) { if (state.detail == null) actor = null }
    fun favorite(card: DiscoveryCard) = FavoriteItem(if (card.series) FavoriteKind.SERIES else FavoriteKind.MOVIE,
        card.media, profileKey = profile, source = FavoriteSource.TMDB)
    fun cardEntry(card: DiscoveryCard, row: String) = RailEntry("$row:${card.key}", card.title, card.poster, card,
        subtitle = listOfNotNull(card.date?.take(4), card.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
        open = { details(card, "$row:${card.key}") },
        matchCount = app.discoveryMatchCounts[discoveryAvailabilityKey(profile, card)],
        popup = { expanded, dismiss, restore ->
            var descriptionOpen by remember(card.key) { mutableStateOf(false) }
            ModernTileActionsMenu(expanded = expanded, isFavorite = app.favorites.any { it.key == favorite(card).key },
                dismiss = dismiss, toggleFavorite = { toggleFavorite(favorite(card)) }, clear = null,
                trailerItem = card.media, trailerSeries = card.series, onTrailerReturned = restore,
                recommendationItem = card.media, recommendationType = if (card.series) CatalogType.SERIES else CatalogType.MOVIES,
                refreshMatches = refreshDiscoveryMatches?.let { refresh -> { refresh(card) } },
                viewDescription = card.overview?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                openSeries = if (card.series) ({ playCard(card) }) else null,
                playAction = { playCard(card) })
            if (descriptionOpen) FullDescriptionDialog(card.title, card.overview.orEmpty()) { descriptionOpen = false; restore() }
        })
    val rails = buildList {
        val detail = state.detail
        if (detail == null) {
            val recents = app.recentlyPlayed.filter { it.profileKey == profile && it.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES) && (surface == DashboardSurface.HOME ||
                it.kind == if (surface == DashboardSurface.MOVIES) FavoriteKind.MOVIE else FavoriteKind.SERIES) }
                .filterNot { it.media.isAdult }.sortedByDescending { it.playedAtMillis }.take(30)
            if (recents.isNotEmpty()) add(Rail("continue", "Continue Watching", recents.map { recent ->
                val progress = discoveryRecentProgress(recent, app.playbackProgress)
                RailEntry("continue:${recent.key}", recent.media.title, recent.media.logo,
                    subtitle = recent.lastPlayed?.title ?: recent.categoryTitle.orEmpty(),
                    progress = progress?.takeIf { it.durationMillis > 0 }?.let { (it.positionMillis.toFloat() / it.durationMillis).coerceIn(0f, 1f) },
                    open = { returningTo = "continue:${recent.key}"; openRecent(recent) },
                    popup = { expanded, dismiss, restore ->
                        var descriptionOpen by remember(recent.key) { mutableStateOf(false) }
                        val saved = FavoriteItem(recent.kind, recent.media, recent.series, profileKey = profile)
                        ModernTileActionsMenu(expanded, app.favorites.any { it.key == saved.key }, dismiss,
                            { toggleFavorite(saved) }, clear = { removeRecent(recent) },
                            openSeries = if (recent.kind == FavoriteKind.SERIES) openProviderSeries?.let { open -> { open(recent.series ?: recent.media) } } else null,
                            trailerItem = recent.media.takeUnless { it.episodeNumber != null }, trailerSeries = recent.kind == FavoriteKind.SERIES,
                            recommendationItem = recent.media, recommendationType = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES,
                            viewDescription = recent.media.description?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                            onTrailerReturned = restore, playAction = { (providerNavigation?.recent ?: openRecent)(recent) })
                        if (descriptionOpen) FullDescriptionDialog(recent.media.title, recent.media.description.orEmpty()) { descriptionOpen = false; restore() }
                    })
            }))
            if (surface != DashboardSurface.MOVIES) {
                val episodes = newEpisodeGroups(app.watchedSeries, profile)
                if (episodes.isNotEmpty()) add(Rail("episodes", "New Episodes", episodes.map { group ->
                    val episode = group.latest
                    RailEntry("episodes:${group.key}", group.watched.series.title, group.watched.series.logo,
                        subtitle = "S${episode.seasonNumber ?: "?"}:E${episode.episodeNumber ?: "?"} · ${episode.title}",
                        open = { returningTo = "episodes:${group.key}"; openWatched(group.watched, episode) },
                        popup = { expanded, dismiss, restore ->
                            var descriptionOpen by remember(group.key) { mutableStateOf(false) }
                            val saved = FavoriteItem(FavoriteKind.EPISODE, episode, group.watched.series, profileKey = profile, categoryTitle = group.watched.categoryTitle)
                            ModernTileActionsMenu(expanded, app.favorites.any { it.key == saved.key }, dismiss,
                                { toggleFavorite(saved) }, clear = { dismissEpisode(group.watched, episode) },
                                markWatched = { markWatched(group.watched, episode) },
                                recommendationItem = group.watched.series, recommendationType = CatalogType.SERIES, trailerSeries = true,
                                openSeries = openProviderSeries?.let { open -> { open(group.watched.series) } },
                                viewDescription = episode.description?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                                onTrailerReturned = restore, playAction = { openWatched(group.watched, episode) })
                            if (descriptionOpen) FullDescriptionDialog(episode.title, episode.description.orEmpty()) { descriptionOpen = false; restore() }
                        })
                }))
            }
            if (surface != DashboardSurface.SERIES) add(Rail("daily-movies", "Trending Movies Today",
                state.feeds[TmdbHomeSection.TRENDING_MOVIES].orEmpty().take(10).map { cardEntry(it, "daily-movies") }, true, TmdbHomeSection.TRENDING_MOVIES))
            if (surface != DashboardSurface.MOVIES) add(Rail("daily-series", "Trending Series Today",
                state.feeds[TmdbHomeSection.TRENDING_SERIES].orEmpty().take(10).map { cardEntry(it, "daily-series") }, true, TmdbHomeSection.TRENDING_SERIES))
            if (surface == DashboardSurface.HOME) app.historyRecommendations.filter { it.profile == profile }.forEach { row ->
                val cards = (row.movies.map { it.discovery() } + row.series.map { it.discovery() }).filter { it.released() }
                if (cards.isNotEmpty()) add(Rail("personal:${row.key}", row.title, cards.map { cardEntry(it, "personal:${row.key}") }))
            }
            state.sections.filterNot { it in setOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES) }.forEach { section ->
                add(Rail("section:${section.name}", section.title, state.feeds[section].orEmpty().map { cardEntry(it, "section:${section.name}") }, section = section))
            }
        } else {
            if (state.collection.isNotEmpty()) add(Rail("collection", state.details?.collection?.title ?: "Collection", state.collection.map { cardEntry(it, "collection") }))
            if (state.related.isNotEmpty()) add(Rail("related", "Related", state.related.map { cardEntry(it, "related") }))
        }
    }
    val visibleRails = rails.filterNot { row -> row.section?.let { it in state.feeds && state.feeds[it].isNullOrEmpty() } == true }
    val routeKey = "$profile:${surface.name}:${state.detail?.key ?: "landing"}"
    val candidate = state.details?.card?.takeIf { it.key == state.detail?.key } ?: state.detail ?: featured ?: state.hero
    val displayed by preparedDiscoveryCard(candidate)
    // TV pivot scrolling must not compete with explicit vertical row alignment.
    StableTileFocusViewport {
    BoxWithConstraints(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val screenHeight = maxHeight
        DiscoveryBackdrop(displayed, reduceMotion)
        Column(Modifier.fillMaxSize()) {
            if (!phone) {
                DiscoveryTopNavigation(surface, app.savedProfile?.name.orEmpty(), tabRequester, playRequester,
                    back = { if (state.detail != null) vm.closeDetails() else exit() },
                    select = { tab -> if (tab != surface) selectSurface(tab) else if (state.detail != null) vm.closeDetails() },
                    classic = classic, headerFocus = { contentFocused = false }, menu = menu,
                    changeMenu = { menu = it }, customize = { configure = true })
            } else Column(Modifier.fillMaxWidth().background(Background.copy(alpha = .82f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true) { if (state.detail != null) vm.closeDetails() else exit() }
                    Image(androidx.compose.ui.res.painterResource(com.nikhil.niktv.R.drawable.niktv_logo_foreground), "NikTV", Modifier.padding(start = 8.dp).size(36.dp))
                    Spacer(Modifier.weight(1f))
                    DiscoveryAction("Search", Icons.Default.Search, iconOnly = true) { classic("Search") }
                    DiscoveryBrowseMenu(menu, { menu = it }, surface, classic, { configure = true })
                }
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    trialSurfaces.forEach { tab ->
                        DiscoveryAction(tab.trialTitle(), visualLabel = tab.trialTitle().removeSuffix(" 2"), horizontalPadding = 4.dp,
                            selected = tab == surface, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = tab.trialTitle() }
                                .then(if (tab == surface) Modifier.focusRequester(tabRequester).focusProperties { down = playRequester } else Modifier),
                            onFocus = { contentFocused = false }) { if (tab != surface) selectSurface(tab) else if (state.detail != null) vm.closeDetails() }
                    }
                    DiscoveryAction("Live TV", horizontalPadding = 4.dp, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { classic("Live TV") }
                }
            }
            saved.SaveableStateProvider(routeKey) {
                val listState = rememberLazyListState()
                val coordinator = remember { DiscoveryScrollCoordinator(localScope) }
                DisposableEffect(Unit) { onDispose { coordinator.cancel() } }
                val heroVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
                val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
                val anchorIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                val activity = HeroActivity(heroVisible && state.detail == null, foreground,
                    contentFocused, scrolling, touching, menu || configure || tilePopupOpen)
                LaunchedEffect(activity, state.heroes.size) {
                    if (activity.mayRotate && state.heroes.size > 1) while (true) { delay(8_000); vm.rotate(1) }
                }
                LaunchedEffect(foreground, scrolling, state.heroIndex, state.heroes) {
                    if (foreground && !scrolling && state.heroes.isNotEmpty()) {
                        delay(200)
                        prefetchDiscoveryArtwork(context, emptyList(), state.heroes.getOrNull((state.heroIndex + 1) % state.heroes.size)?.backdrop)
                    }
                }
                val routeIdentity = state.detail?.key ?: "landing"
                val playOrigin = "$routeIdentity:play"
                SideEffect { returnRequesters[playOrigin] = playRequester }
                LaunchedEffect(state.detail?.key, state.detail != null && displayed?.key == state.detail?.key) {
                    var readinessFrames = 0
                    while (!hostView.hasWindowFocus() && readinessFrames++ < 60) withFrameNanos { }
                    withFrameNanos { }; withFrameNanos { }
                    val key = routeOrigins[routeIdentity] ?: returningTo.takeIf { state.detail == null }
                    if (key != null) repeat(8) {
                        if (runCatching { returnRequesters[key]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                        withFrameNanos { }
                    }
                    repeat(20) {
                        if (runCatching { if (state.detail == null) tabRequester.requestFocus() else playRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                        withFrameNanos { }
                    }
                }
                var focusedRow by remember { mutableStateOf<String?>(null) }
                fun alignFocusedRow(key: String, index: Int) {
                    // Left/Right changes the item, not the vertical navigation destination.
                    if (focusedRow != key) {
                        focusedRow = key
                        coordinator.align(listState, index, reduceMotion)
                    }
                }
                val focusHandlers = remember { mutableMapOf<Int, () -> Boolean>() }
                var move by remember { mutableStateOf<Job?>(null) }
                var requestedRow by remember { mutableIntStateOf(0) }
                val firstRailIndex = if (state.detail?.series == true) 2 else 1
                val castIndex = firstRailIndex + visibleRails.size + 1 + if (state.details?.collection != null) 1 else 0
                val navigationRows = buildList {
                    add(0)
                    if (state.detail?.series == true && state.details?.seasons?.isNotEmpty() == true) add(1)
                    visibleRails.forEachIndexed { i, row -> if (row.entries.isNotEmpty()) add(firstRailIndex + i) }
                    if (state.detail != null && state.details?.cast?.any { (it.id ?: 0) > 0 } == true) add(castIndex)
                }
                fun focusRow(index: Int) {
                    requestedRow = index
                    move = coordinator.move(listState, index, reduceMotion) {
                        if (index <= 0) runCatching { playRequester.requestFocus() }.getOrDefault(false)
                        else focusHandlers[index]?.invoke() == true
                    }
                }
                fun moveRow(index: Int, delta: Int): Boolean {
                    val current = if (move?.isActive == true) requestedRow else index
                    val next = adjacentDiscoveryRow(current, delta, navigationRows) ?: return false
                    focusRow(next)
                    return true
                }
                DiscoveryVerticalFocusViewport(coordinator) {
                LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { val event = awaitPointerEvent(PointerEventPass.Initial); touching = event.changes.any { it.pressed } } }
                }, state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item("hero") {
                        val origin = "hero:${displayed?.key}"
                        val infoFocusRequester = remember(origin) { returnRequesters.getOrPut(origin) { FocusRequester() } }
                        val heroHeight = if (compactLandscape) (screenHeight * .52f).coerceIn(165.dp, 230.dp)
                            else if (landscape) (screenHeight * .55f).coerceIn(235.dp, 355.dp) else (screenHeight * .45f).coerceIn(255.dp, 355.dp)
                        DiscoveryHero(displayed, if (state.detail == null) state.feeds[if (displayed?.series == true) TmdbHomeSection.TRENDING_SERIES else TmdbHomeSection.TRENDING_MOVIES]
                            .orEmpty().take(10).indexOfFirst { it.key == displayed?.key }.takeIf { it >= 0 }?.plus(1) else null,
                            height = heroHeight, landscape = landscape, compact = compactLandscape, detail = state.detail != null,
                            loading = displayed == null && (state.loading.isNotEmpty() || candidate != null),
                            inList = displayed?.let { card -> app.favorites.any { it.profileKey == profile && it.media.id == card.media.id } } == true,
                            playRequester = playRequester, infoRequester = infoFocusRequester,
                            play = { displayed?.let { returningTo = playOrigin; routeOrigins = routeOrigins + (routeIdentity to playOrigin); playCard(it) } },
                            add = { displayed?.let { toggleFavorite(favorite(it)) } },
                            info = { displayed?.let { details(it, origin) } },
                            onFocus = { contentFocused = true; focusedRow = "hero" }, onDown = { moveRow(0, 1) },
                            previous = { vm.rotate(-1) }, next = { vm.rotate(1) }, carousel = state.detail == null && state.heroes.size > 1,
                            index = state.heroIndex, count = state.heroes.size)
                        state.errors.filterKeys { it == "detail" }.values.forEach { Text(it, Modifier.padding(horizontal = 24.dp), color = Color.LightGray, fontSize = 13.sp) }
                        if (candidate == null && state.loading.isEmpty()) Text("Discovery is unavailable. Check your TMDB configuration or retry below.", Modifier.padding(24.dp), color = Color.LightGray)
                    }
                    if (state.detail?.series == true) item("seasons") {
                        val episodeCard = state.detail
                        val seasons = state.details?.seasons.orEmpty()
                        var newestFirst by rememberSaveable(episodeCard?.key) { mutableStateOf(true) }
                        if (seasons.isNotEmpty()) {
                            val chosenSeason = state.season ?: if (app.seriesStartSeason == SeriesStartSeason.LAST) seasons.last() else seasons.first()
                            val seasonState = rememberLazyListState(initialFirstVisibleItemIndex = seasons.indexOf(chosenSeason).coerceAtLeast(0))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Episodes", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                                DiscoveryAction(if (newestFirst) "Newest first" else "Oldest first") {
                                    newestFirst = !newestFirst
                                    val target = if (newestFirst) seasons.last() else seasons.first()
                                    vm.loadSeason(target)
                                    localScope.launch { seasonState.animateScrollToItem(seasons.indexOf(target).coerceAtLeast(0)) }
                                }
                            }
                            val seasonEntry = remember { FocusRequester() }
                            DisposableEffect(Unit) {
                                focusHandlers[1] = { runCatching { seasonEntry.requestFocus() }.getOrDefault(false) }
                                onDispose { focusHandlers.remove(1) }
                            }
                            DiscoveryLazyRow(state = seasonState, contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(seasons) { seasonIndex, season -> DiscoveryAction("Season $season", selected = state.season == season,
                                    modifier = if (season == chosenSeason) Modifier.focusRequester(seasonEntry) else Modifier,
                                    onFocus = { contentFocused = true; alignFocusedRow("seasons", 1) }) { vm.loadSeason(season) } }
                            }
                            LaunchedEffect(state.details?.card?.key) { vm.loadSeason(chosenSeason) }
                        }
                        state.errors["season"]?.let { DiscoveryAction(it) { state.season?.let(vm::loadSeason) } }
                        if ("season" in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                        DiscoveryLazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val episodes = if (newestFirst) state.episodes.sortedByDescending { it.number } else state.episodes.sortedBy { it.number }
                            items(episodes, key = { "${it.season}:${it.number}" }) { episode ->
                                var description by remember { mutableStateOf(false) }
                                var options by remember { mutableStateOf(false) }
                                DiscoveryPosterTile(episode.title, episode.still, "E${episode.number}", width = if (landscape) 180.dp else 150.dp,
                                    aspect = 16f / 9f, reduceMotion = reduceMotion,
                                    onClick = { episodeCard?.let { playEpisode(it, episode.season, episode.number) } },
                                    onLongClick = { options = true }, onFocus = { contentFocused = true })
                                if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(episode.title) },
                                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NikTvSecondaryActionButton({ options = false; episodeCard?.let { downloadEpisode(it, episode.season, episode.number) } }, Modifier.fillMaxWidth()) { Text("Download") }
                                        if (!episode.overview.isNullOrBlank()) NikTvSecondaryActionButton({ options = false; description = true }, Modifier.fillMaxWidth()) { Text("Description") }
                                    } }, confirmButton = { NikTvSecondaryActionButton({ options = false }) { Text("Close") } }, containerColor = Color(0xFF181B22))
                                if (description) FullDescriptionDialog(episode.title, episode.overview.orEmpty()) { description = false }
                            }
                        }
                    }
                    itemsIndexed(visibleRails, key = { _, row -> row.key }) { rowIndex, row ->
                        val index = rowIndex + if (state.detail?.series == true) 2 else 1
                        LaunchedEffect(row.section) { row.section?.let(vm::loadFeed) }
                        val width = if (landscape) minOf(((configuration.screenWidthDp - 64) / 7.4f - 12).dp.coerceIn(92.dp, 132.dp),
                            ((screenHeight.value - 224) / 3f).dp.coerceIn(88.dp, 132.dp))
                            else ((configuration.screenWidthDp - 40) / 3.15f - 10).dp.coerceIn(88.dp, 120.dp)
                        DiscoveryRail(row, width, reduceMotion, returnRequesters, navigationIndex = index,
                            popupChanged = { tilePopupOpen = it },
                            prefetch = foreground && !scrolling && anchorIndex == index,
                            skeleton = row.section?.let { it !in state.feeds && "feed:${it.name}" !in state.errors } == true,
                            register = { handler -> focusHandlers[index] = handler }, unregister = { focusHandlers.remove(index) },
                            focused = { entry -> contentFocused = true; returningTo = entry.key; routeOrigins = routeOrigins + (routeIdentity to entry.key); alignFocusedRow(row.key, index) },
                            present = { entry -> entry.card?.let { featured = it } },
                            move = { delta -> moveRow(index, delta) },
                            viewAll = row.section?.let { section -> { returningTo = "${row.key}:all"; openSection(section) } })
                        if (row.entries.isEmpty()) {
                            val key = "feed:${row.section?.name}"
                            if (key in state.errors) DiscoveryAction("Retry ${row.title}", modifier = Modifier.padding(start = 24.dp)) { row.section?.let { vm.loadFeed(it, true) } }
                            else if (key !in state.loading) Text("No released titles available", Modifier.padding(start = 24.dp, bottom = 12.dp), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    if (state.detail != null) {
                        item("related-load") {
                            LaunchedEffect(state.detail?.key) { vm.loadSupplement("related") }
                            DiscoverySupplementStatus("related", state, vm)
                        }
                        state.details?.collection?.let { collection -> item("collection-banner") {
                            LaunchedEffect(collection.id) { vm.loadSupplement("collection") }
                            DiscoveryAction(collection.title, Icons.Default.Collections, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                                val detailKey = state.detail?.key
                                vm.loadSupplement("collection")
                                localScope.launch {
                                    val loaded = vm.state.first { it.detail?.key != detailKey || it.collection.isNotEmpty() || "extra:collection" in it.errors }
                                    if (loaded.detail?.key == detailKey && loaded.collection.isNotEmpty()) focusRow(1)
                                }
                            }
                            DiscoverySupplementStatus("collection", state, vm)
                        } }
                        val people = state.details?.cast.orEmpty().distinctBy { it.id ?: it.name }
                        if (people.isNotEmpty()) item("cast") {
                            val detailKey = state.detail?.key.orEmpty()
                            var selected by rememberSaveable(detailKey) { mutableStateOf<String?>(null) }
                            fun personKey(person: DiscoveryPerson) = "cast:$detailKey:${person.id ?: person.name}"
                            val currentPeople by rememberUpdatedState(people)
                            DisposableEffect(castIndex, detailKey) {
                                focusHandlers[castIndex] = {
                                    val key = selected?.takeIf { key -> currentPeople.any { personKey(it) == key } }
                                        ?: currentPeople.firstOrNull { (it.id ?: 0) > 0 }?.let(::personKey)
                                    runCatching { returnRequesters[key]?.requestFocus() == true }.getOrDefault(false)
                                }
                                onDispose { focusHandlers.remove(castIndex) }
                            }
                            Text("Cast", Modifier.padding(start = 24.dp, top = 18.dp), color = Color.White, fontWeight = FontWeight.Bold)
                            DiscoveryLazyRow(Modifier.onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp, Key.DirectionDown))
                                    moveRow(castIndex, if (it.key == Key.DirectionUp) -1 else 1) else false
                            }, contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(people, key = { personKey(it) }) { person ->
                                    val key = personKey(person)
                                    val requester = remember(key) { returnRequesters.getOrPut(key) { FocusRequester() } }
                                    var focused by remember { mutableStateOf(false) }
                                    Surface(onClick = { if (person.id != null) { actorOrigin = detailKey; actorFocusOrigin = key; actor = person } },
                                        enabled = (person.id ?: 0) > 0,
                                        modifier = Modifier.width(102.dp).focusRequester(requester).onFocusChanged {
                                            focused = it.isFocused
                                            if (it.isFocused) {
                                                selected = key; returningTo = key
                                                routeOrigins = routeOrigins + (routeIdentity to key)
                                                contentFocused = true; alignFocusedRow("cast", castIndex)
                                            }
                                        }, shape = RoundedCornerShape(12.dp),
                                        color = if (focused) Color(0xFF303641) else Color.Transparent,
                                        border = if (focused) BorderStroke(2.dp, Color.White) else null) {
                                    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        AsyncImage(person.portrait, person.name, Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF20232A)), contentScale = ContentScale.Crop)
                                        Text(person.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(person.role, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    }
                                }
                            }
                        }
                        if (state.detail?.series == false) item("reviews") {
                            LaunchedEffect(state.detail?.key) { vm.loadSupplement("reviews") }
                            DiscoverySupplementStatus("reviews", state, vm)
                            if (state.reviews.isNotEmpty()) {
                                Text("Reviews", Modifier.padding(start = 24.dp, top = 12.dp), color = Color.White, fontWeight = FontWeight.Bold)
                                DiscoveryLazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(state.reviews, key = { "${it.author}:${it.text.hashCode()}" }) { review ->
                                        var full by remember { mutableStateOf(false) }
                                        Surface(onClick = { full = true }, modifier = Modifier.width(if (landscape) 280.dp else 250.dp).height(140.dp), color = Color(0xFF1C2027), shape = RoundedCornerShape(14.dp)) {
                                            Column(Modifier.padding(16.dp)) { Text(review.author, color = Color.White, fontWeight = FontWeight.Bold); Text(review.text.take(600), color = Color.LightGray, fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                                        }
                                        if (full) FullDescriptionDialog(review.author, review.text) { full = false }
                                    }
                                }
                            }
                        }
                    } else item("categories") {
                        val types = when (surface) { DashboardSurface.MOVIES -> listOf(CatalogType.MOVIES); DashboardSurface.SERIES -> listOf(CatalogType.SERIES); else -> listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES) }
                        types.forEach { type ->
                            val categories = app.modernVisibleIptvCategories(type, requireExplicitSelection = surface == DashboardSurface.HOME).filterNot { adultContentLabel(it.title) }
                            if (categories.isNotEmpty()) {
                                Text("IPTV ${type.title}", Modifier.padding(start = 24.dp, top = 18.dp), color = Color.White, fontWeight = FontWeight.Bold)
                                DiscoveryLazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(categories, key = { it.id }) { category -> DiscoveryAction(category.title) { openCategory(category) } }
                                }
                            }
                        }
                    }
                }
                } // DiscoveryVerticalFocusViewport
            }
        }
    }
    }
    actor?.takeIf { actorOrigin == state.detail?.key }?.let { person ->
        saved.SaveableStateProvider("actor:$profile:${person.id}:$actorOrigin") {
            DiscoveryActorDialog(person, load = vm::actorCredits, close = ::dismissActor, reduceMotion = reduceMotion, open = { card ->
                if (card.key == actorOrigin) dismissActor()
                else details(card, "actor:${person.id}:${card.key}")
            })
        }
    }
    if (configure) AlertDialog(onDismissRequest = { configure = false }, title = { Text("Customize ${surface.trialTitle()}") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(TmdbHomeSection.entries.filter { surface == DashboardSurface.HOME || it.series == (surface == DashboardSurface.SERIES) }) { section ->
                Row(Modifier.fillMaxWidth().toggleablePreview { vm.configure(if (section in state.sections) state.sections - section else state.sections + section) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(section in state.sections, onCheckedChange = { vm.configure(if (it) state.sections + section else state.sections - section) }); Text(section.title)
                }
            }
        } }, confirmButton = { NikTvSecondaryActionButton({ configure = false }) { Text("Close") } }, containerColor = Color(0xFF181B22))

}

private fun Modifier.toggleablePreview(action: () -> Unit) = clickable(onClick = action)

/** Inner rows retain edge reveal even while their vertical parent owns a transition. */
@Composable
private fun DiscoveryLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: LazyListScope.() -> Unit,
) {
    StableTileFocusViewport {
        LazyRow(modifier, state, contentPadding, horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment, content = content)
    }
}

/** Promote metadata and its prepared image together; canceled old decodes cannot change the hero. */
@Composable
private fun preparedDiscoveryCard(card: DiscoveryCard?): State<DiscoveryCard?> {
    val context = LocalContext.current
    return produceState<DiscoveryCard?>(null, card) {
        if (card == null) { value = null; return@produceState }
        val url = card.backdrop?.let { thumbnailArtworkUrl(it, 1280) }
        if (url == null) { value = card; return@produceState }
        val image = try {
            withTimeoutOrNull(2000) { withContext(Dispatchers.IO) {
                context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(1280, 720).transformations(DiscoveryBackdropTransformation()).build())
            } }
        } catch (c: CancellationException) { throw c }
          catch (_: Exception) { null }
        value = if (image is coil3.request.SuccessResult) card else card.copy(backdrop = null)
    }
}

@Composable
private fun DiscoverySupplementStatus(section: String, state: DiscoveryState, vm: DiscoveryViewModel) {
    state.errors["extra:$section"]?.let { DiscoveryAction(it, modifier = Modifier.padding(24.dp)) { vm.loadSupplement(section) } }
    if ("extra:$section" in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
}

@Composable
internal fun DiscoveryBackdrop(card: DiscoveryCard?, reduceMotion: Boolean) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf<String?>(null) }
    val requested = card?.backdrop?.let { thumbnailArtworkUrl(it, 1280) }
    val currentRequest by rememberUpdatedState(requested)
    Box(Modifier.fillMaxSize()) {
        Crossfade(ready, animationSpec = tween(if (reduceMotion) 0 else 300), label = "discoveryBackdrop") { url ->
            AsyncImage(ImageRequest.Builder(context).data(url).size(1280, 720).transformations(DiscoveryBackdropTransformation()).crossfade(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // Hidden decode layer promotes only the latest successfully prepared backdrop.
        if (requested != null && requested != ready) AsyncImage(ImageRequest.Builder(context).data(requested).size(1280, 720).transformations(DiscoveryBackdropTransformation()).crossfade(false).build(), null,
            Modifier.size(1.dp).graphicsLayer { alpha = 0f }, onSuccess = { if (currentRequest == requested) ready = requested }, onError = { if (currentRequest == requested) ready = null })
        LaunchedEffect(requested) { if (requested == null) ready = null }
    }
}

@Composable
internal fun DiscoveryHero(card: DiscoveryCard?, rank: Int?, height: Dp, landscape: Boolean, compact: Boolean, detail: Boolean, loading: Boolean,
    inList: Boolean, playRequester: FocusRequester, infoRequester: FocusRequester,
    play: () -> Unit, add: () -> Unit, info: () -> Unit, onFocus: () -> Unit, onDown: () -> Unit,
    previous: () -> Unit, next: () -> Unit, carousel: Boolean, index: Int, count: Int,
    playLabel: String = "Play", playEnabled: Boolean = true) {
    var drag by remember { mutableFloatStateOf(0f) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = if (landscape) 32.dp else 18.dp, vertical = if (compact) 8.dp else 16.dp)
        .pointerInput(carousel) { if (carousel) detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -70f) next() else if (drag > 70f) previous() }) { change, amount -> change.consume(); drag += amount } },
        verticalArrangement = Arrangement.Bottom) {
        if (loading) { LinearProgressIndicator(Modifier.width(160.dp)); Spacer(Modifier.height(16.dp)) }
        rank?.let { Text("#$it · TRENDING TODAY", color = Color.LightGray, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }
        var logoReady by remember(card?.titleLogo) { mutableStateOf(false) }
        Box(Modifier.widthIn(max = if (landscape) 560.dp else 400.dp).height((if (landscape && !compact) 76.dp else 64.dp) * fontScale), contentAlignment = Alignment.CenterStart) {
            if (!logoReady) Text(card?.title ?: "Discover something to watch", color = Color.White,
                fontSize = if (landscape && !compact) 32.sp else 26.sp, lineHeight = if (landscape && !compact) 36.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!card?.titleLogo.isNullOrBlank()) AsyncImage(card?.titleLogo, card?.title,
                Modifier.widthIn(max = 400.dp).fillMaxHeight().graphicsLayer { alpha = if (logoReady) 1f else 0f }, contentScale = ContentScale.Fit,
                onSuccess = { logoReady = true }, onError = { logoReady = false })
        }
        val metadata = card?.let { listOfNotNull(it.date?.take(4), it.runtime?.let { n -> "${n} min" }, it.genres.take(3).joinToString(" · ").takeIf(String::isNotBlank),
            it.rating?.takeIf { n -> n > 0 }?.let { n -> "★ %.1f".format(n) }, it.certification).joinToString(" · ") }.orEmpty()
        if (metadata.isNotBlank()) Text(metadata, Modifier.padding(top = if (compact) 4.dp else 6.dp), color = Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        card?.overview?.takeIf(String::isNotBlank)?.let { Text(it, Modifier.widthIn(max = if (landscape) 600.dp else 500.dp).padding(top = if (compact) 6.dp else 8.dp),
            color = Color(0xFFD6D8DC), fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 16.sp else 18.sp, maxLines = if (detail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false }) {
            DiscoveryAction(playLabel, Icons.Default.PlayArrow, selected = true,
                modifier = Modifier.focusRequester(playRequester), enabled = card != null && playEnabled, onFocus = onFocus, onClick = play)
            DiscoveryAction(if (inList) "In My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add,
                iconOnly = !detail, enabled = card != null, onFocus = onFocus, onClick = add)
            if (!detail) DiscoveryAction("Info", Icons.Default.Info, iconOnly = true,
                modifier = Modifier.focusRequester(infoRequester), enabled = card != null, onFocus = onFocus, onClick = info)
            if (landscape && carousel) {
                DiscoveryCarouselControls(index, count, onFocus, previous, next)
            }
        }
        if (carousel && !landscape) DiscoveryCarouselControls(index, count, onFocus, previous, next, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DiscoveryCarouselControls(index: Int, count: Int, onFocus: () -> Unit, previous: () -> Unit, next: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DiscoveryAction("Previous featured title", Icons.Default.ChevronLeft, iconOnly = true, onFocus = onFocus, onClick = previous)
        Text("${index + 1} / $count", color = Color.LightGray, fontSize = 11.sp)
        DiscoveryAction("Next featured title", Icons.Default.ChevronRight, iconOnly = true, onFocus = onFocus, onClick = next)
    }
}

@Composable
private fun DiscoveryRail(row: Rail, width: Dp, reduceMotion: Boolean, requesters: MutableMap<String, FocusRequester>, navigationIndex: Int,
    popupChanged: (Boolean) -> Unit,
    prefetch: Boolean, skeleton: Boolean,
    register: (() -> Boolean) -> Unit, unregister: () -> Unit, focused: (RailEntry) -> Unit,
    present: (RailEntry) -> Unit, move: (Int) -> Boolean, viewAll: (() -> Unit)?) {
    val rowState = rememberLazyListState()
    val popupScope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(prefetch, rowState.firstVisibleItemIndex, rowState.isScrollInProgress, row.entries) {
        if (prefetch && !rowState.isScrollInProgress) { delay(200); prefetchDiscoveryArtwork(context, row.entries.drop(rowState.firstVisibleItemIndex).take(8).map { it.artwork }) }
    }
    var selected by rememberSaveable(row.key) { mutableStateOf<String?>(null) }
    var focusedCard by remember { mutableStateOf<RailEntry?>(null) }
    LaunchedEffect(focusedCard?.key) { delay(150); focusedCard?.let(present) }
    val entries by rememberUpdatedState(row.entries)
    DisposableEffect(row.key, navigationIndex) {
        register {
            val key = selected?.takeIf { it == "${row.key}:all" || entries.any { entry -> entry.key == it } } ?: entries.firstOrNull()?.key
            key != null && runCatching { requesters[key]?.requestFocus() == true }.getOrDefault(false)
        }
        onDispose(unregister)
    }
    Column {
        Text(row.title, Modifier.padding(start = 24.dp, top = 4.dp), color = Color.White, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        DiscoveryLazyRow(Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus) focusedCard = null }.focusGroup().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                move(if (event.key == Key.DirectionUp) -1 else 1)
            } else false
        }, state = rowState, contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (row.entries.isEmpty() && skeleton) items(5) { Box(Modifier.width(width).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1F27))) }
            itemsIndexed(row.entries, key = { _, entry -> entry.key }) { index, entry ->
                val requester = remember(entry.key) { requesters.getOrPut(entry.key) { FocusRequester() } }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (row.ranked) Text((index + 1).toString(), color = Color.White.copy(alpha = .9f), fontWeight = FontWeight.Black,
                        fontSize = 44.sp, modifier = Modifier.width(if (index == 9) 48.dp else 32.dp).padding(bottom = 46.dp))
                    var popupOpen by remember(entry.key) { mutableStateOf(false) }
                    if (popupOpen) DisposableEffect(Unit) {
                        popupChanged(true)
                        onDispose { popupChanged(false) }
                    }
                    Box {
                        DiscoveryPosterTile(entry.title, entry.artwork, entry.subtitle, width, reduceMotion = reduceMotion,
                            modifier = Modifier.focusRequester(requester), progress = entry.progress, matchCount = entry.matchCount,
                            onClick = entry.open, onLongClick = entry.popup?.let { { popupOpen = true } },
                            onFocus = { selected = entry.key; focusedCard = entry; focused(entry) })
                        var restoreJob by remember(entry.key) { mutableStateOf<Job?>(null) }
                        val requestNow = { runCatching { requester.requestFocus() }.getOrDefault(false) }
                        val restore = {
                            val restored = requestNow()
                            if (!restored) {
                                restoreJob?.cancel()
                                restoreJob = popupScope.launch {
                                    repeat(12) { withFrameNanos { }; if (requestNow()) return@launch }
                                }
                            }
                            restored
                        }
                        entry.popup?.invoke(popupOpen, {
                            popupOpen = false
                            popupScope.launch { withFrameNanos { }; restore() }
                        }, restore)
                    }
                }
            }
            if (viewAll != null && row.entries.isNotEmpty()) item("view-all") {
                val key = "${row.key}:all"
                val requester = remember(key) { requesters.getOrPut(key) { FocusRequester() } }
                Box(Modifier.width(width).height(width * 1.5f), contentAlignment = Alignment.Center) {
                    DiscoveryAction("View all", modifier = Modifier.focusRequester(requester),
                        onFocus = { selected = key; focused(RailEntry(key, row.title, null, open = viewAll)) }, onClick = viewAll)
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryPosterTile(title: String, artwork: String?, subtitle: String, width: Dp,
    modifier: Modifier = Modifier, aspect: Float = 2f / 3f, reduceMotion: Boolean = false, progress: Float? = null, matchCount: Int? = null,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    var artworkFailed by remember(artwork) { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused && !reduceMotion) 1.05f else 1f,
        tween(if (reduceMotion) 0 else if (focused) 380 else 320, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "discoveryTileScale")
    val border = animateFloatAsState(if (focused) 1f else 0f, tween(if (reduceMotion) 0 else 180), label = "discoveryTileBorder")
    val shape = RoundedCornerShape(10.dp)
    val context = LocalContext.current
    // Scale the legacy overlay treatment to these deliberately smaller posters.
    val textScale = (width.value / 120f).coerceIn(.85f, 1.15f)
    Column(modifier.width(width).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick, interactionSource = remember { MutableInteractionSource() }, showIndication = false)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).graphicsLayer {
            scaleX = scale.value; scaleY = scale.value; shadowElevation = if (focused) 4.dp.toPx() else 0f; this.shape = shape
        }.tileFocusOutline(true, border, shape, Color(0xFF2B303A), 2.dp).clip(shape).background(Color(0xFF1C222C))) {
            AsyncImage(ImageRequest.Builder(context).data(thumbnailArtworkUrl(artwork, 342)).size(342, if (aspect == 2f / 3f) 513 else 193).crossfade(false).build(),
                title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { artworkFailed = true })
            if (artwork.isNullOrBlank() || artworkFailed) Column(Modifier.align(Alignment.Center).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Movie, null, Modifier.size(28.dp), tint = Color.Gray)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .4f to Color.Transparent,
                .72f to Color.Black.copy(alpha = .55f), 1f to Color.Black.copy(alpha = .96f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(start = 7.dp, end = 7.dp, top = 8.dp, bottom = if (progress != null) 9.dp else 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = (10f * textScale).sp,
                    lineHeight = (12f * textScale).sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFFCFD1D5),
                    fontSize = (8.5f * textScale).sp, lineHeight = (10.5f * textScale).sp)
            }
            matchCount?.let { count ->
                Text(count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Accent, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
            progress?.let { value -> Canvas(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)) {
                drawRect(Color.DarkGray); drawRect(Accent, size = size.copy(width = size.width * value.coerceIn(0f, 1f)))
            } }
        }
    }
}

@Composable
internal fun DiscoveryAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false, iconOnly: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true,
    visualLabel: String = label, horizontalPadding: Dp = 14.dp,
    onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 40.dp)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(10.dp), color = if (focused) Color.White else if (selected) Accent else Color(0xFF242831).copy(alpha = .85f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58)),
        contentColor = if (focused) Color.Black else Color.White) {
        Row(Modifier.padding(horizontal = if (iconOnly) 10.dp else horizontalPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            icon?.let { Icon(it, if (iconOnly) label else null, Modifier.size(20.dp)) }
            if (!iconOnly) Text(visualLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
