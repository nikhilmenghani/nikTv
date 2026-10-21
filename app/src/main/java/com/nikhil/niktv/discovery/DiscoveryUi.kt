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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
    val matchCount: Int? = null, val providerMedia: MediaItem? = null, val providerType: CatalogType? = null,
    val popup: (@Composable (Boolean, () -> Unit, () -> Boolean) -> Unit)? = null)
private data class Rail(val key: String, val title: String, val entries: List<RailEntry>, val ranked: Boolean = false,
    val section: TmdbHomeSection? = null, val providerType: CatalogType? = null, val providerCategory: Category? = null)

@Composable
internal fun DiscoveryPreviewScreen(
    app: NikTvState, surface: DashboardSurface, selectSurface: (DashboardSurface) -> Unit,
    exit: () -> Unit, classic: (String) -> Unit, playCard: (DiscoveryCard) -> Unit,
    playEpisode: (DiscoveryCard, Int, Int) -> Unit, openRecent: (RecentItem) -> Unit,
    openWatched: (WatchedSeries, MediaItem) -> Unit, toggleFavorite: (FavoriteItem) -> Unit,
    openCategory: (Category) -> Unit, openSection: (TmdbHomeSection) -> Unit,
    removeRecent: (RecentItem) -> Unit, markWatched: (WatchedSeries, MediaItem) -> Unit,
    dismissEpisode: (WatchedSeries, MediaItem) -> Unit,
    vm: DiscoveryViewModel = viewModel(key = "discovery2:${app.savedProfile?.cacheKey()}"),
    detailVisibilityChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val downloadEpisode = LocalDownloadDiscoveryEpisode.current
    val refreshDiscoveryMatches = LocalRefreshDiscoveryMatches.current
    val openProviderSeries = LocalOpenDiscoverySeries.current
    val providerNavigation = LocalProviderDetailNavigation.current
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
    val phonePortrait = phone && configuration.screenHeightDp >= configuration.screenWidthDp
    val remoteNavigation = context.usesRemoteNavigation(configuration) || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val landscape = !phone
    val compactLandscape = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenHeightDp < 480
    val profile = app.savedProfile?.cacheKey().orEmpty()
    // Nested TMDB details use a different discovery VM; they must not rebind the
    // underlying collection or retain another profile's catalogue in memory.
    val providerVm: ProviderDiscoveryViewModel = viewModel(key = "provider-discovery:$profile:${System.identityHashCode(vm)}")
    val providerLoaded by providerVm.state.collectAsStateWithLifecycle()
    val openProvider = LocalOpenProviderDiscovery.current
    val configureProvider = LocalConfigureProviderDiscovery.current
    val playProvider = LocalPlayProviderDiscovery.current
    val downloadProvider = LocalDownloadProviderDiscovery.current
    val providerPageLoader = LocalProviderDiscoveryPageLoader.current
    val providerSelections = remember(profile, surface, app.categoryFilters, app.rawCategoriesByType, app.browseCachesByType.mapValues { it.value.categories }) {
        listOf(CatalogType.MOVIES, CatalogType.SERIES)
            .filter { surface == DashboardSurface.HOME || (it == CatalogType.SERIES) == (surface == DashboardSurface.SERIES) }
            .associateWith { type -> providerDiscoveryCategoryRows(
                app.modernVisibleIptvCategories(type, requireExplicitSelection = surface == DashboardSurface.HOME),
                implicitSelection = app.categoryFilters["$profile|${type.name}"] == null) }
    }
    val expectedProviderCategories = remember(providerSelections) {
        providerSelections.mapValues { providerDiscoverySources(it.value) }.filterValues { it.isNotEmpty() }
    }
    val providerState = providerLoaded.takeIf { it.profile == profile && it.categories == expectedProviderCategories }
        ?: ProviderDiscoveryState(profile = profile, categories = expectedProviderCategories)
    val providerQueues by produceState(providerVm.retainedRows.takeIf { it.first == profile }
        ?: (profile to emptyMap<String, List<MediaItem>>()), profile, providerSelections, providerState.pages) {
        value = profile to withContext(Dispatchers.Default) {
            buildMap { providerSelections.forEach { (type, categories) -> categories.forEach { category ->
                put("provider:${type.name}:${category.id}", providerDiscoveryItems(providerDiscoverySources(listOf(category)), providerState.pages[type].orEmpty()))
            } } }
        }
    }
    SideEffect { if (providerQueues.first == profile) providerVm.retainedRows = providerQueues }
    var providerCollection by rememberSaveable(profile, surface) { mutableStateOf<String?>(null) }
    var providerCollectionCategory by rememberSaveable(profile, surface) { mutableStateOf<String?>(null) }
    LaunchedEffect(profile, app.session, providerSelections, app.cacheIntervalMinutes) {
        providerVm.bind(app.session, providerSelections, app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L, preview = true, pageLoader = providerPageLoader)
    }
    // Keep bounded landing previews for an immediate return from details; nested detail
    // routes release their private catalogue copies. No network work survives disposal.
    DisposableEffect(providerVm) { onDispose { providerVm.pause(release = vm.state.value.detail != null) } }
    val providerLifecycle = LocalLifecycleOwner.current.lifecycle
    val bindProvider by rememberUpdatedState {
        providerVm.bind(app.session, providerSelections, app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L, preview = true, pageLoader = providerPageLoader)
    }
    DisposableEffect(providerLifecycle, providerVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) providerVm.pause()
            else if (event == Lifecycle.Event.ON_START) bindProvider()
        }
        providerLifecycle.addObserver(observer)
        onDispose { providerLifecycle.removeObserver(observer) }
    }
    val loadedState by vm.state.collectAsStateWithLifecycle()
    val state = loadedState.takeIf { it.profile == profile && it.surface == surface } ?: DiscoveryState(profile = profile, surface = surface)
    DisposableEffect(state.detail != null) {
        detailVisibilityChanged(state.detail != null)
        onDispose { detailVisibilityChanged(false) }
    }
    val localScope = rememberCoroutineScope()
    var configure by remember { mutableStateOf(false) }
    val tmdbCategoriesRequester = remember { FocusRequester() }
    var restoreTmdbCategories by remember { mutableStateOf(false) }
    LaunchedEffect(configure) {
        if (!configure && restoreTmdbCategories) {
            restoreTmdbCategories = false
            repeat(30) {
                withFrameNanos { }
                if (hostView.hasWindowFocus() && runCatching { tmdbCategoriesRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var categoryPopup by remember { mutableStateOf(false) }
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
        if (lastDetail.value != null && state.detail == null && (!phone || remoteNavigation)) {
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
                    providerMedia = recent.series ?: recent.media,
                    providerType = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES,
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
                        providerMedia = group.watched.series, providerType = CatalogType.SERIES,
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
            providerSelections.filterValues { it.isNotEmpty() }.forEach { (type, categories) ->
                categories.forEach { category ->
                val rowKey = "provider:${type.name}:${category.id}"
                val queue = providerQueues.second[rowKey].orEmpty().takeIf { providerQueues.first == profile }.orEmpty()
                add(Rail(rowKey, category.title, queue.take(30).map { item ->
                    val saved = FavoriteItem(if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE,
                        item, profileKey = profile, categoryTitle = category.title)
                    RailEntry("$rowKey:${item.id}", item.title, item.logo,
                        providerMedia = item, providerType = type,
                        subtitle = category.title.orEmpty(),
                        progress = providerDiscoveryProgress(app, type, item),
                        open = { returningTo = "$rowKey:${item.id}"; openProvider(type, item, queue) },
                        popup = { expanded, dismiss, restore ->
                            ModernTileActionsMenu(expanded, app.favorites.any { it.key == saved.key }, dismiss,
                                { toggleFavorite(saved) }, clear = null, trailerItem = item, trailerSeries = type == CatalogType.SERIES,
                                recommendationItem = item, recommendationType = type,
                                onTrailerReturned = restore, playAction = { playProvider(type, item, queue) },
                                downloadAction = if (type == CatalogType.MOVIES) ({ downloadProvider(type, item) }) else null)
                        })
                }, providerType = type, providerCategory = category))
                }
            }

        } else {
            if (state.collection.isNotEmpty()) add(Rail("collection", state.details?.collection?.title ?: "Collection", state.collection.map { cardEntry(it, "collection") }))
            if (state.related.isNotEmpty()) add(Rail("related", "Related", state.related.map { cardEntry(it, "related") }))
        }
    }
    val visibleRails = rails.filterNot { row ->
        row.section?.let { it in state.feeds && state.feeds[it].isNullOrEmpty() } == true ||
            row.providerCategory?.let { category -> providerDiscoverySources(listOf(category)).all { source ->
                val page = providerState.pages[category.type]?.get(source.id)
                page != null && page.page > 0 && !page.hasMore && !page.truncated && page.items.isEmpty()
            } } == true
    }
    val routeKey = "$profile:${surface.name}:${state.detail?.key ?: "landing"}"
    val focusedRail = if (state.detail == null) rails.firstOrNull { row -> row.entries.any { it.key == state.focusedEntryKey } } else null
    val focusedEntry = focusedRail?.entries?.firstOrNull { it.key == state.focusedEntryKey }
    val focusedProvider = focusedEntry?.providerMedia
    val focusedType = focusedEntry?.providerType
    val focusedQueue = focusedRail?.key?.let { providerQueues.second[it] }.orEmpty().ifEmpty { listOfNotNull(focusedProvider) }
    val rawCandidate = state.details?.card?.takeIf { it.key == state.detail?.key } ?: state.detail ?: state.focusedPresentation ?: featured ?: state.hero
    val candidate = state.heroPresentation?.takeIf { state.detail == null && it.key == rawCandidate?.key } ?: rawCandidate
    val prepared by preparedDiscoveryCard(candidate.takeUnless { phonePortrait }, vm.preparedPresentation?.takeIf { it.key == candidate?.key && it.title == candidate.title })
    SideEffect { prepared?.let(vm::retainPreparedPresentation) }
    val displayed = if (phonePortrait) candidate else prepared
    var glassBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    providerCollection?.let { name -> CatalogType.entries.firstOrNull { it.name == name } }?.let { type ->
        DisposableEffect(Unit) { detailVisibilityChanged(true); onDispose { detailVisibilityChanged(false) } }
        saved.SaveableStateProvider("provider-collection:${type.name}:$providerCollectionCategory") {
            val category = providerSelections[type].orEmpty().firstOrNull { it.id == providerCollectionCategory }
            ProviderDiscoveryCollectionRoute(type, category?.let { listOf(it) } ?: providerSelections[type].orEmpty(),
                category?.title ?: "Your IPTV ${type.title}", app,
                close = { providerCollection = null; providerCollectionCategory = null }, favorite = toggleFavorite)
        }
        return
    }
    // TV pivot scrolling must not compete with explicit vertical row alignment.
    StableTileFocusViewport {
    BoxWithConstraints(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)
        .onGloballyPositioned { glassBounds = it.boundsInRoot() }) {
        val screenHeight = maxHeight
        if (!phonePortrait) DiscoveryBackdrop(displayed, reduceMotion)
        Column(Modifier.fillMaxSize()) {
            if (phone && !phonePortrait) {
                DiscoveryPhoneNavigation(surface, tabRequester, playRequester,
                    { if (state.detail != null) vm.closeDetails() else exit() }, selectSurface, classic,
                    menu = menu, changeMenu = { menu = it }, categoryManagerOpen = app.categoryManagerOpen,
                    categoriesEnabled = state.detail == null, categoryPopupChanged = { categoryPopup = it })
            }
            saved.SaveableStateProvider(routeKey) {
                val positions = discoveryFeedIndices(!phone, state.detail?.series == true)
                val listState = rememberLazyListState()
                val coordinator = remember { DiscoveryScrollCoordinator(localScope) }
                DisposableEffect(Unit) { onDispose { coordinator.cancel() } }
                val heroVisible by remember { derivedStateOf { (if (!phone) listState.firstVisibleItemIndex <= positions.hero else listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) } }
                val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
                val anchorIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                val activity = HeroActivity(heroVisible && state.detail == null, foreground,
                    contentFocused, scrolling, touching, menu || configure || tilePopupOpen || app.categoryManagerOpen || categoryPopup)
                LaunchedEffect(phonePortrait, heroVisible, foreground, state.hero?.key) {
                    if (phonePortrait && heroVisible && foreground && state.detail == null) vm.prepareHero()
                    else if (phonePortrait) vm.pauseHeroMetadata()
                }
                LaunchedEffect(activity, state.heroes.size) {
                    if (activity.mayRotate && state.heroes.size > 1) while (true) { delay(8_000); vm.rotate(1) }
                }
                LaunchedEffect(foreground, scrolling, state.heroIndex, state.heroes, phonePortrait) {
                    if (foreground && !scrolling && state.heroes.isNotEmpty()) {
                        delay(200)
                        val adjacent = state.heroes.getOrNull((state.heroIndex + 1) % state.heroes.size)
                        prefetchDiscoveryArtwork(context, emptyList(), if (phonePortrait) adjacent?.backdrop ?: adjacent?.poster else adjacent?.backdrop,
                            portraitHero = phonePortrait, portraitPoster = phonePortrait && adjacent?.backdrop.isNullOrBlank())
                    }
                }
                val routeIdentity = state.detail?.key ?: "landing"
                val playOrigin = "$routeIdentity:play"
                SideEffect { returnRequesters[playOrigin] = playRequester }
                var handledNavigationId by rememberSaveable { mutableLongStateOf(-1L) }
                LaunchedEffect(app.discoveryNavigationId, state.detail?.key, state.detail != null && displayed?.key == state.detail?.key, remoteNavigation) {
                    if (phone && !remoteNavigation) return@LaunchedEffect
                    val menuEntry = state.detail == null && handledNavigationId != app.discoveryNavigationId
                    if (menuEntry) {
                        handledNavigationId = app.discoveryNavigationId
                        coordinator.cancel()
                        listState.scrollToItem(0)
                    }
                    var readinessFrames = 0
                    while (!hostView.hasWindowFocus() && readinessFrames++ < 60) withFrameNanos { }
                    withFrameNanos { }; withFrameNanos { }
                    val key = if (menuEntry) null else routeOrigins[routeIdentity] ?: returningTo.takeIf { state.detail == null }
                    if (key != null) repeat(8) {
                        if (runCatching { returnRequesters[key]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                        withFrameNanos { }
                    }
                    repeat(20) {
                        if (runCatching { if (state.detail == null) tabRequester.requestFocus() else playRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                        withFrameNanos { }
                    }
                }
                var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
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
                val firstRailIndex = positions.firstRail
                val castIndex = firstRailIndex + visibleRails.size + 1 + if (state.details?.collection != null) 1 else 0
                val navigationRows = buildList {
                    positions.header?.let(::add)
                    add(positions.hero)
                    if (state.detail?.series == true && state.details?.seasons?.isNotEmpty() == true) add(positions.season)
                    visibleRails.forEachIndexed { i, row -> if (row.entries.isNotEmpty() || row.providerType != null) add(firstRailIndex + i) }
                    if (state.detail != null && state.details?.cast?.any { (it.id ?: 0) > 0 } == true) add(castIndex)
                }
                fun focusRow(index: Int) {
                    requestedRow = index
                    move = coordinator.move(listState, index, reduceMotion) {
                        when (index) {
                            positions.header -> runCatching { tabRequester.requestFocus() }.getOrDefault(false)
                            positions.hero -> runCatching { playRequester.requestFocus() }.getOrDefault(false)
                            else -> focusHandlers[index]?.invoke() == true
                        }
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
                    awaitPointerEventScope { while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                        if (phone && !remoteNavigation && touching) contentFocused = false
                    } }
                }, state = listState, contentPadding = PaddingValues(bottom = if (phone && state.detail == null) 96.dp else 32.dp)) {
                    if (!phone) item("navigation") {
                        Box(Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) moveRow(0, 1) else false
                        }) {
                            DiscoveryTopNavigation(surface, app.savedProfile?.name.orEmpty(), tabRequester, playRequester,
                                back = { if (state.detail != null) vm.closeDetails() else exit() },
                                select = { tab -> if (tab != surface) selectSurface(tab) else if (state.detail != null) vm.closeDetails() },
                                classic = classic, headerFocus = { contentFocused = false; alignFocusedRow("navigation", 0) },
                                menu = menu, changeMenu = { menu = it }, customize = { configure = true },
                                backdrop = displayed?.backdrop, backdropBounds = glassBounds,
                                categoryManagerOpen = app.categoryManagerOpen, categoriesEnabled = state.detail == null,
                                categoryPopupChanged = { categoryPopup = it })
                        }
                    }
                    item("hero") {
                        val origin = "hero:${displayed?.key}"
                        val infoFocusRequester = remember(origin) { returnRequesters.getOrPut(origin) { FocusRequester() } }
                        val heroHeight = if (phonePortrait) (screenHeight * .67f).coerceIn(480.dp, 620.dp)
                            else if (compactLandscape) (screenHeight * .52f).coerceIn(165.dp, 230.dp)
                            else if (landscape) (screenHeight * .55f).coerceIn(235.dp, 355.dp) else (screenHeight * .45f).coerceIn(255.dp, 355.dp)
                        DiscoveryHero(displayed, if (state.detail == null) state.feeds[if (displayed?.series == true) TmdbHomeSection.TRENDING_SERIES else TmdbHomeSection.TRENDING_MOVIES]
                            .orEmpty().take(10).indexOfFirst { it.key == displayed?.key }.takeIf { it >= 0 }?.plus(1) else null,
                            height = heroHeight, landscape = landscape, compact = compactLandscape, detail = state.detail != null,
                            loading = displayed == null && (state.loading.isNotEmpty() || candidate != null),
                            inList = if (focusedProvider != null) app.favorites.any { it.profileKey == profile && it.media.id == focusedProvider.id }
                                else displayed?.let { card -> app.favorites.any { it.profileKey == profile && it.media.id == card.media.id } } == true,
                            playRequester = playRequester, infoRequester = infoFocusRequester,
                            play = {
                                if (focusedProvider != null) focusedEntry?.open?.invoke()
                                else displayed?.let { returningTo = playOrigin; routeOrigins = routeOrigins + (routeIdentity to playOrigin); playCard(it) }
                            },
                            add = {
                                if (focusedProvider != null && focusedType != null) toggleFavorite(FavoriteItem(
                                    if (focusedType == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE, focusedProvider, profileKey = profile))
                                else displayed?.let { toggleFavorite(favorite(it)) }
                            },
                            info = {
                                if (focusedProvider != null && focusedType != null) { returningTo = origin; openProvider(focusedType, focusedProvider, focusedQueue) }
                                else displayed?.let { details(it, origin) }
                            },
                            onFocus = { contentFocused = true; focusedRow = "hero" }, onDown = { moveRow(positions.hero, 1) }, onUp = { moveRow(positions.hero, -1) },
                            previous = { vm.rotate(-1) }, next = { vm.rotate(1) }, carousel = state.detail == null && state.heroes.size > 1,
                            index = state.heroIndex, count = state.heroes.size,
                            categoryActions = if (state.detail == null) ({
                                DiscoveryCategoryAccess(surface, app.categoryManagerOpen, popupChanged = { categoryPopup = it },
                                    onFocus = { contentFocused = true; focusedRow = "hero" })
                                DiscoveryAction("TMDB categories", modifier = Modifier.focusRequester(tmdbCategoriesRequester),
                                    onFocus = { contentFocused = true; focusedRow = "hero" }) { restoreTmdbCategories = true; configure = true }
                            }) else null,
                            portraitPhone = phonePortrait,
                            phoneHeader = if (phonePortrait && state.detail == null) ({
                                DiscoveryPhoneHeader(surface, state.detail != null, tabRequester, playRequester,
                                    back = { if (state.detail != null) vm.closeDetails() else exit() },
                                    select = { if (it != surface) selectSurface(it) }, classic = classic,
                                    menu = menu, changeMenu = { menu = it }, customize = { configure = true },
                                    categoryManagerOpen = app.categoryManagerOpen, categoryPopupChanged = { categoryPopup = it })
                            }) else null)
                        state.errors.filterKeys { it == "detail" }.values.forEach { Text(it, Modifier.padding(horizontal = 24.dp), color = Color.LightGray, fontSize = 13.sp) }
                        if (candidate == null && state.loading.isEmpty()) Text("Discovery is unavailable. Check your TMDB configuration or retry below.", Modifier.padding(24.dp), color = Color.LightGray)
                    }
                    if (state.detail?.series == true) item("seasons") {
                        val episodeCard = state.detail
                        val seasons = state.details?.seasons.orEmpty()
                        var newestFirst by rememberSaveable(episodeCard?.key) { mutableStateOf(true) }
                        val seasonEntry = remember { FocusRequester() }
                        val episodeEntry = remember { FocusRequester() }
                        val episodeState = rememberLazyListState()
                        val episodes = if (newestFirst) state.episodes.sortedByDescending { it.number } else state.episodes.sortedBy { it.number }
                        fun enterEpisodes() { localScope.launch {
                            episodeState.scrollToItem(0)
                            repeat(20) { withFrameNanos { }; if (runCatching { episodeEntry.requestFocus() }.getOrDefault(false)) return@launch }
                        } }
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
                            DisposableEffect(Unit) {
                                focusHandlers[positions.season] = { runCatching { seasonEntry.requestFocus() }.getOrDefault(false) }
                                onDispose { focusHandlers.remove(positions.season) }
                            }
                            DiscoveryLazyRow(Modifier.then(if (landscape) Modifier.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { enterEpisodes(); true }
                                else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) moveRow(positions.season, -1) else false
                            } else Modifier), state = seasonState, contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(seasons) { seasonIndex, season -> DiscoveryAction("Season $season", selected = state.season == season,
                                    modifier = if (season == chosenSeason) Modifier.focusRequester(seasonEntry) else Modifier,
                                    onFocus = { contentFocused = true; alignFocusedRow("seasons", positions.season) }) { vm.loadSeason(season) } }
                            }
                            LaunchedEffect(state.details?.card?.key) { vm.loadSeason(chosenSeason) }
                        }
                        state.errors["season"]?.let { DiscoveryAction(it) { state.season?.let(vm::loadSeason) } }
                        if ("season" in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                        DiscoveryLazyRow(Modifier.then(if (landscape) Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionUp -> runCatching { seasonEntry.requestFocus() }.getOrDefault(false)
                                Key.DirectionDown -> moveRow(positions.season, 1)
                                else -> false
                            }
                        } else Modifier), state = episodeState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(episodes, key = { _, episode -> "${episode.season}:${episode.number}" }) { index, episode ->
                                var description by remember { mutableStateOf(false) }
                                var options by remember { mutableStateOf(false) }
                                val requester = remember(episode.season, episode.number) { FocusRequester() }
                                var hadEpisodePopup by remember { mutableStateOf(false) }
                                LaunchedEffect(options, description) {
                                    if (landscape) {
                                        if (options || description) hadEpisodePopup = true
                                        else if (hadEpisodePopup) {
                                            hadEpisodePopup = false
                                            repeat(30) { withFrameNanos { }
                                                if (hostView.hasWindowFocus() && runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                                            }
                                        }
                                    }
                                }
                                val episodeModifier = Modifier.focusRequester(requester).then(if (index == 0) Modifier.focusRequester(episodeEntry) else Modifier)
                                if (landscape) DiscoveryEpisodeTile(episode.title, episode.still, "S${episode.season} · E${episode.number}", episode.date.orEmpty(),
                                    modifier = episodeModifier.width(200.dp), onFocus = { contentFocused = true },
                                    onClick = { episodeCard?.let { playEpisode(it, episode.season, episode.number) } }, onLongClick = { options = true })
                                else DiscoveryPosterTile(episode.title, episode.still, "E${episode.number}", width = 150.dp,
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
                        val index = rowIndex + positions.firstRail
                        LaunchedEffect(row.section) { row.section?.let(vm::loadFeed) }
                        LaunchedEffect(row.key, providerState.ready, providerState.loading, providerState.errors) {
                            val category = row.providerCategory
                            val type = row.providerType
                            if (category != null && type != null && providerState.ready && type !in providerState.loading && type !in providerState.errors &&
                                app.nowPlaying == null && app.providerMovieDetails == null && app.selectedSeries == null && !app.loading) {
                                val sources = providerDiscoverySources(listOf(category))
                                if (sources.any { !providerDiscoveryFresh(providerState.pages[type]?.get(it.id), System.currentTimeMillis(), app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L) })
                                    providerVm.load(type, categoryIds = sources.mapTo(mutableSetOf()) { it.id })
                            }
                        }
                        val width = if (landscape) minOf(((configuration.screenWidthDp - 64) / 7.4f - 12).dp.coerceIn(92.dp, 132.dp),
                            ((screenHeight.value - 224) / 3f).dp.coerceIn(88.dp, 132.dp))
                            else ((configuration.screenWidthDp - 40) / 3.15f - 10).dp.coerceIn(88.dp, 120.dp)
                        DiscoveryRail(row, width, reduceMotion, returnRequesters, navigationIndex = index, phone = phonePortrait,
                            popupChanged = { tilePopupOpen = it },
                            prefetch = foreground && !scrolling && anchorIndex == index,
                            skeleton = row.section?.let { it !in state.feeds && "feed:${it.name}" !in state.errors } == true ||
                                row.providerType?.let { !providerState.ready || (row.entries.isEmpty() && it !in providerState.errors && it !in providerState.capped) } == true,
                            register = { handler -> focusHandlers[index] = handler }, unregister = { focusHandlers.remove(index) },
                            focused = { entry -> contentFocused = true; returningTo = entry.key; routeOrigins = routeOrigins + (routeIdentity to entry.key); if (!phonePortrait) alignFocusedRow(row.key, index) },
                            present = { entry ->
                                if (entry.providerMedia != null && entry.providerType != null) vm.presentProvider(entry.key, entry.providerMedia, entry.providerType)
                                else entry.card?.let { featured = it; vm.presentFocusedCard(entry.key, it) }
                            },
                            move = { delta -> moveRow(index, delta) },
                            viewAll = row.providerType?.let { type -> { returningTo = "${row.key}:all"; providerCollectionCategory = row.providerCategory?.id; providerCollection = type.name } }
                                ?: row.section?.let { section -> { returningTo = "${row.key}:all"; openSection(section) } })
                        row.providerType?.let { type ->
                            if (row.entries.isEmpty() && type in providerState.errors) {
                                providerState.errors[type]?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = Color.LightGray) }
                                DiscoveryAction("Retry", Icons.Default.Refresh, modifier = Modifier.padding(start = 24.dp)) {
                                    providerVm.load(type, refresh = true, categoryIds = row.providerCategory?.let { providerDiscoverySources(listOf(it)).mapTo(mutableSetOf()) { source -> source.id } })
                                }
                            }
                        }
                        if (row.entries.isEmpty() && row.providerType == null) {
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
                            if (categories.isNotEmpty() || type != CatalogType.LIVE_TV) {
                                Text("IPTV ${type.title}", Modifier.padding(start = 24.dp, top = 18.dp), color = Color.White, fontWeight = FontWeight.Bold)
                                DiscoveryLazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (type != CatalogType.LIVE_TV) item("choose-categories") {
                                        DiscoveryAction("Choose categories", Icons.Default.Tune) { configureProvider(type) }
                                    }
                                    if (type == CatalogType.LIVE_TV) items(categories, key = { it.id }) { category -> DiscoveryAction(category.title) { openCategory(category) } }
                                    else if (providerSelections[type].orEmpty().isNotEmpty()) item("search-selected") {
                                        DiscoveryAction("Search selected categories", Icons.Default.Search) { providerCollectionCategory = null; providerCollection = type.name }
                                    }
                                }
                            }
                        }
                    }
                }
                } // DiscoveryVerticalFocusViewport
            }
        }
        if (phonePortrait && state.detail != null) DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack,
            iconOnly = true, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { vm.closeDetails() }
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
    if (configure) AlertDialog(onDismissRequest = { configure = false }, title = { Text("TMDB categories") },
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
private fun preparedDiscoveryCard(card: DiscoveryCard?, initial: DiscoveryCard? = null): State<DiscoveryCard?> {
    val context = LocalContext.current
    return produceState<DiscoveryCard?>(initial, card) {
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
    val requested = card?.backdrop?.let { thumbnailArtworkUrl(it, 1280) }
    var ready by remember { mutableStateOf(requested) }
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
    playLabel: String = "Play", playEnabled: Boolean = true,
    phoneHeader: (@Composable () -> Unit)? = null, portraitPhone: Boolean = false,
    showSynopsis: Boolean = true, compactDetail: Boolean = false, onUp: () -> Boolean = { false },
    categoryActions: (@Composable () -> Unit)? = null) {
    if (portraitPhone) {
        val context = LocalContext.current
        val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        DiscoveryPhoneHero(card, rank, height.coerceAtLeast(if (compactDetail) 320.dp else 480.dp), detail, loading, inList,
            playRequester, infoRequester, play, add, info, previous, next, carousel, index, count,
            playLabel, playEnabled, reduceMotion, onFocus, onDown, phoneHeader, showSynopsis, compactDetail, categoryActions)
        return
    }
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
        card?.overview?.takeIf { showSynopsis && it.isNotBlank() }?.let { Text(it, Modifier.widthIn(max = if (landscape) 600.dp else 500.dp).padding(top = if (compact) 6.dp else 8.dp),
            color = Color(0xFFD6D8DC), fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 16.sp else 18.sp, maxLines = if (detail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false
                else when (it.key) {
                    Key.DirectionDown -> { onDown(); true }
                    Key.DirectionUp -> onUp()
                    else -> false
                }
            }) {
            DiscoveryAction(playLabel, Icons.Default.PlayArrow, selected = true,
                modifier = Modifier.focusRequester(playRequester), enabled = card != null && playEnabled, onFocus = onFocus, onClick = play)
            DiscoveryAction(if (inList) "In My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add,
                iconOnly = !detail, enabled = card != null, onFocus = onFocus, onClick = add)
            if (!detail) DiscoveryAction("Info", Icons.Default.Info, iconOnly = true,
                modifier = Modifier.focusRequester(infoRequester), enabled = card != null, onFocus = onFocus, onClick = info)
            if (landscape && carousel) {
                DiscoveryCarouselControls(index, count, onFocus, previous, next)
            }
            if (landscape) categoryActions?.invoke()
        }
        if (!landscape && (carousel || categoryActions != null)) FlowRow(Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (carousel) DiscoveryCarouselControls(index, count, onFocus, previous, next)
            categoryActions?.invoke()
        }
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
private fun DiscoveryRail(row: Rail, width: Dp, reduceMotion: Boolean, requesters: MutableMap<String, FocusRequester>, navigationIndex: Int, phone: Boolean = false,
    popupChanged: (Boolean) -> Unit,
    prefetch: Boolean, skeleton: Boolean,
    register: (() -> Boolean) -> Unit, unregister: () -> Unit, focused: (RailEntry) -> Unit,
    present: (RailEntry) -> Unit, move: (Int) -> Boolean, viewAll: (() -> Unit)?) {
    val rowState = rememberLazyListState()
    val popupScope = rememberCoroutineScope()
    val context = LocalContext.current
    val phoneWide = phone && row.key in setOf("continue", "episodes")
    val phoneWidth = if (phoneWide) ((LocalConfiguration.current.screenWidthDp - 32) * .68f).dp.coerceIn(200.dp, 280.dp)
        else phoneDiscoveryPosterWidth(LocalConfiguration.current.screenWidthDp).dp
    val tileWidth = if (phone) phoneWidth else width
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
                ?: "${row.key}:all".takeIf { row.providerType != null }
            key != null && runCatching { requesters[key]?.requestFocus() == true }.getOrDefault(false)
        }
        onDispose(unregister)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, Modifier.weight(1f).padding(start = if (phone) 16.dp else 24.dp, top = 4.dp), color = Color.White,
            fontSize = if (phone) 19.sp else 16.sp, lineHeight = if (phone) 24.sp else 20.sp, fontWeight = FontWeight.SemiBold)
        if (row.providerType != null && viewAll != null) {
            val key = "${row.key}:all"
            val requester = remember(key) { requesters.getOrPut(key) { FocusRequester() } }
            DiscoveryAction("Search / All", Icons.Default.Search,
                modifier = Modifier.focusRequester(requester).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown))
                        move(if (event.key == Key.DirectionUp) -1 else 1) else false
                }, onFocus = { selected = key; focused(RailEntry(key, row.title, null, open = viewAll)) }, onClick = viewAll)
        }
        }
        DiscoveryLazyRow(Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus) focusedCard = null }.focusGroup().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                move(if (event.key == Key.DirectionUp) -1 else 1)
            } else false
        }, state = rowState, contentPadding = PaddingValues(start = if (phone) 16.dp else 24.dp, top = 10.dp, end = if (phone) 16.dp else 24.dp, bottom = if (phone) 12.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 12.dp)) {
            if (row.entries.isEmpty() && skeleton) items(5) { Box(Modifier.width(tileWidth)
                .height(if (phone) phoneDiscoveryCardHeight(tileWidth.value, phoneWide, LocalDensity.current.fontScale).dp else tileWidth * 1.5f)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1F27))) }
            itemsIndexed(row.entries, key = { _, entry -> entry.key }) { index, entry ->
                val requester = remember(entry.key) { requesters.getOrPut(entry.key) { FocusRequester() } }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (row.ranked) Text((index + 1).toString(), color = Color.White.copy(alpha = .9f), fontWeight = FontWeight.Black,
                        fontSize = if (phone) 64.sp else 44.sp, modifier = Modifier.width(if (index == 9) 48.dp else 32.dp).padding(bottom = if (phone) 50.dp else 46.dp))
                    var popupOpen by remember(entry.key) { mutableStateOf(false) }
                    if (popupOpen) DisposableEffect(Unit) {
                        popupChanged(true)
                        onDispose { popupChanged(false) }
                    }
                    Box {
                        if (phone) DiscoveryPhoneMediaCard(entry.title, entry.artwork, entry.subtitle, tileWidth,
                            wide = phoneWide, modifier = Modifier.focusRequester(requester), progress = entry.progress, matchCount = entry.matchCount,
                            onClick = entry.open, onLongClick = entry.popup?.let { { popupOpen = true } },
                            onFocus = { selected = entry.key; focusedCard = entry; focused(entry) })
                        else DiscoveryPosterTile(entry.title, entry.artwork, entry.subtitle, width, reduceMotion = reduceMotion,
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
            if (viewAll != null && row.providerType == null && row.entries.isNotEmpty()) item("view-all") {
                val key = "${row.key}:all"
                val requester = remember(key) { requesters.getOrPut(key) { FocusRequester() } }
                Box(Modifier.width(tileWidth).height(if (phoneWide) tileWidth * .5625f else tileWidth * 1.5f), contentAlignment = Alignment.Center) {
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
        shape = RoundedCornerShape(10.dp), color = if (focused) Color.White else if (selected) Accent
            else Color(0xFF242831).copy(alpha = if (LocalDiscoveryGlassButtons.current) .38f else .85f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58)),
        contentColor = if (focused) Color.Black else Color.White) {
        Row(Modifier.padding(horizontal = if (iconOnly) 10.dp else horizontalPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            icon?.let { Icon(it, if (iconOnly) label else null, Modifier.size(20.dp)) }
            if (!iconOnly) Text(visualLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
