@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.providerDisplayTitle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
/** Optional fixture diagnostics; normal navigation builds no trace strings. */
internal val LocalDiscoveryNavigationTrace = staticCompositionLocalOf<((String) -> Unit)?> { null }
private val Background = Color(0xFF090B10)
private val Accent = Color(0xFFE50925)
internal val trialSurfaces = listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)
internal fun DashboardSurface.trialTitle() = when (this) { DashboardSurface.MOVIES -> "Movies"; DashboardSurface.SERIES -> "Series"; else -> "Home" }

private data class RailEntry(val key: String, val title: String, val artwork: String?, val card: DiscoveryCard? = null,
    val subtitle: String = "", val progress: Float? = null, val open: () -> Unit,
    val matchCount: Int? = null, val providerMedia: MediaItem? = null, val providerType: CatalogType? = null,
    val popup: (@Composable (Boolean, () -> Unit, () -> Boolean) -> Unit)? = null,
    val source: DiscoveryTileSource? = null) {
    val displaySource get() = source ?: if (card != null) DiscoveryTileSource.TMDB else providerMedia?.discoveryCatalogSource()
}
private data class Rail(val key: String, val title: String, val entries: List<RailEntry>, val ranked: Boolean = false,
    val section: TmdbHomeSection? = null, val providerType: CatalogType? = null, val providerCategory: Category? = null,
    val recommendation: com.nikhil.niktv.data.HistoryRecommendationRow? = null)

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
    detailVisibilityChanged: (Boolean) -> Unit = {},
    dismissRecommendation: ((com.nikhil.niktv.data.HistoryRecommendationRow) -> Unit)? = null,
    parentModalOpen: Boolean = false
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val navigationTrace = LocalDiscoveryNavigationTrace.current
    val downloadEpisode = LocalDownloadDiscoveryEpisode.current
    val refreshDiscoveryMatches = LocalRefreshDiscoveryMatches.current
    val openProviderSeries = LocalOpenDiscoverySeries.current
    val providerNavigation = LocalProviderDetailNavigation.current
    val configuration = LocalConfiguration.current
    val television = remember(context, configuration.uiMode) { context.isTvLikeDevice(configuration) }
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, television)
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
    var providerCollectionVisit by rememberSaveable(profile, surface) { mutableIntStateOf(0) }
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
    // TV browsing prioritizes one decisive focus change over full-screen presentation work.
    // Explicit detail routes keep their artwork, metadata and actions.
    val fastTvLanding = television && state.detail == null
    LaunchedEffect(vm, profile, surface, fastTvLanding) {
        if (fastTvLanding) { vm.clearFocusedPresentation(); vm.pauseHeroMetadata() }
    }
    val focusRouteOwner = remember(profile, surface) { DiscoveryFocusRouteOwner() }
    val focusRouteToken = remember(profile, surface, state.detail?.key, app.discoveryNavigationId) { Any() }
    SideEffect { focusRouteOwner.claim(focusRouteToken) }
    DisposableEffect(focusRouteOwner, focusRouteToken) {
        onDispose { focusRouteOwner.release(focusRouteToken) }
    }
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
    val focusOrigins = rememberSaveable(profile, surface, saver = DiscoveryFocusOrigins.Saver) { DiscoveryFocusOrigins() }
    val returnRequesters = remember(profile, surface) { mutableMapOf<String, FocusRequester>() }
    val saved = rememberSaveableStateHolder()
    val tabRequester = remember(profile, surface) { FocusRequester() }
    val playRequester = remember(profile, surface, state.detail?.key) { FocusRequester() }
    val parkingRequester = remember(profile, surface) { FocusRequester() }
    val focusParking = remember(profile, surface) { DiscoveryFocusParking() }
    val parkingModifier = remember(parkingRequester, focusParking) {
        Modifier.size(1.dp).focusRequester(parkingRequester)
            .focusProperties { canFocus = focusParking.canFocus }
            .onFocusChanged { focusParking.focusChanged(it.isFocused) }.focusTarget()
            .clearAndSetSemantics { hideFromAccessibility() }
    }
    fun parkRouteFocus() {
        if (remoteNavigation && focusRouteOwner.owns(focusRouteToken) &&
            vm.state.value.ownsFocusRoute(profile, surface, state.detail?.key ?: "landing")) {
            val parked = focusParking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
            navigationTrace?.invoke("focus-park surface=$surface route=${state.detail?.key ?: "landing"} parked=$parked")
        }
    }
    fun closeRoute() {
        if (state.detail != null) { parkRouteFocus(); vm.closeDetails() }
        else {
            // Home stays mounted behind the exit dialog. Keep its real focus
            // target so Cancel returns there instead of an invisible parking node.
            if (surface != DashboardSurface.HOME) parkRouteFocus()
            exit()
        }
    }
    fun selectRoute(destination: DashboardSurface) { parkRouteFocus(); selectSurface(destination) }
    fun classicRoute(destination: String) { parkRouteFocus(); classic(destination) }
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    // The IPTV movie dialog covers this composition without stopping its lifecycle.
    val presentationActive = foreground && app.providerMovieDetails == null && !parentModalOpen
    LaunchedEffect(profile, surface) { vm.bind(profile, surface, app.tmdbSectionsBySurface[surface]) }
    DisposableEffect(vm, profile, surface) {
        vm.resume(profile, surface)
        onDispose { vm.pause() }
    }
    LaunchedEffect(state.hero?.key) { if (state.detail == null) featured = state.hero }
    var lastHomeBack by remember(profile, surface, state.detail?.key) { mutableLongStateOf(0L) }
    LaunchedEffect(foreground, menu, configure, tilePopupOpen, categoryPopup, actor) { lastHomeBack = 0L }
    BackHandler {
        if (phone && surface == DashboardSurface.HOME && state.detail == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastHomeBack != 0L && now - lastHomeBack <= 2_000L) {
                var host: android.content.Context = context
                while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
                (host as? android.app.Activity)?.finish()
            } else {
                lastHomeBack = now
                android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else closeRoute()
    }
    fun details(card: DiscoveryCard, origin: String) {
        parkRouteFocus()
        navigationTrace?.invoke("details-open surface=$surface from=${state.detail?.key ?: "landing"} origin=$origin target=${card.key}")
        focusOrigins.returningTo = origin
        focusOrigins.routes = (focusOrigins.routes + ((state.detail?.key ?: "landing") to origin)) - card.key
        vm.openDetails(card)
    }
    fun dismissActor() {
        actor = null
        val key = actorFocusOrigin
        focusOrigins.returningTo = key
        if (key != null) actorOrigin?.let { focusOrigins.routes = focusOrigins.routes + (it to key) }
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
    val favorites = remember(app.favorites, profile) { discoveryFavoriteLookup(app.favorites, profile) }
    val currentFavorites by rememberUpdatedState(favorites)
    val currentDetails by rememberUpdatedState<(DiscoveryCard, String) -> Unit>({ card, origin -> details(card, origin) })
    val currentPlayCard by rememberUpdatedState(playCard)
    val currentToggleFavorite by rememberUpdatedState(toggleFavorite)
    val currentOpenRecent by rememberUpdatedState(openRecent)
    val currentRemoveRecent by rememberUpdatedState(removeRecent)
    val currentOpenWatched by rememberUpdatedState(openWatched)
    val currentDismissEpisode by rememberUpdatedState(dismissEpisode)
    val currentMarkWatched by rememberUpdatedState(markWatched)
    val currentOpenProvider by rememberUpdatedState(openProvider)
    val currentPlayProvider by rememberUpdatedState(playProvider)
    val currentDownloadProvider by rememberUpdatedState(downloadProvider)
    val currentOpenProviderSeries by rememberUpdatedState(openProviderSeries)
    val currentRefreshMatches by rememberUpdatedState(refreshDiscoveryMatches)
    val currentProviderRecent by rememberUpdatedState(providerNavigation?.recent)
    fun cardEntry(card: DiscoveryCard, row: String): RailEntry {
        val media = card.media
        val favoriteKey = discoveryFavoriteKey(profile, if (card.series) FavoriteKind.SERIES else FavoriteKind.MOVIE, media.id)
        return RailEntry("$row:${card.key}", card.title, card.poster, card,
        subtitle = listOfNotNull(card.date?.take(4), card.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
        open = { currentDetails(card, "$row:${card.key}") },
        matchCount = app.discoveryMatchCounts[discoveryAvailabilityKey(profile, card)],
        popup = { expanded, dismiss, restore ->
            var descriptionOpen by remember(card.key) { mutableStateOf(false) }
            ModernTileActionsMenu(expanded = expanded, isFavorite = favoriteKey in currentFavorites.keys,
                dismiss = dismiss, toggleFavorite = { currentToggleFavorite(favorite(card)) }, clear = null,
                trailerItem = media, trailerSeries = card.series, onTrailerReturned = restore,
                movieForVersionChoice = card.takeUnless { it.series }?.movie(),
                recommendationItem = media, recommendationType = if (card.series) CatalogType.SERIES else CatalogType.MOVIES,
                refreshMatches = currentRefreshMatches?.let { refresh -> { refresh(card) } },
                viewDescription = card.overview?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                openSeries = if (card.series) ({ currentPlayCard(card) }) else null,
                playAction = { currentPlayCard(card) })
            if (descriptionOpen) FullDescriptionDialog(card.title, card.overview.orEmpty()) { descriptionOpen = false; restore() }
        })
    }
    // Focus and prepared-backdrop publications must not rebuild every row, rescan progress,
    // sort episode backlogs or allocate every tile callback on the UI thread.
    val progressLookup = rememberDiscoveryProgressLookup(profile, app.recentlyPlayed, app.playbackProgress)
    val today = currentDiscoveryDate()
    val rails = remember(profile, surface, state.detail?.key, state.feeds, state.sections,
        state.collection, state.related, state.details?.collection?.title,
        app.recentlyPlayed, app.playbackProgress, app.watchedSeries, app.historyRecommendations,
        app.discoveryMatchCounts, providerSelections, providerQueues, progressLookup, today) { buildList {
        val detail = state.detail
        if (detail == null) {
            val recents = app.recentlyPlayed.filter { it.profileKey == profile && it.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES) && (surface == DashboardSurface.HOME ||
                it.kind == if (surface == DashboardSurface.MOVIES) FavoriteKind.MOVIE else FavoriteKind.SERIES) }
                .filterNot { it.media.isAdult }.distinctRecentContent().take(30)
            if (recents.isNotEmpty()) add(Rail("continue", "Continue Watching", recents.map { recent ->
                val progress = progressLookup?.recent(recent)
                RailEntry("continue:${recent.contentIdentity()}", providerDisplayTitle(recent.media.title, if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES), recent.media.logo,
                    providerMedia = recent.series ?: recent.media,
                    providerType = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES,
                    source = recent.discoveryPlaybackSource(),
                    subtitle = recent.lastPlayed?.title ?: recent.categoryTitle.orEmpty(),
                    progress = progress?.takeIf { it.durationMillis > 0 }?.let { (it.positionMillis.toFloat() / it.durationMillis).coerceIn(0f, 1f) },
                    open = { focusOrigins.returningTo = "continue:${recent.contentIdentity()}"; currentOpenRecent(recent) },
                    popup = { expanded, dismiss, restore ->
                        var descriptionOpen by remember(recent.key) { mutableStateOf(false) }
                        val saved = FavoriteItem(recent.kind, recent.media, recent.series, profileKey = profile)
                        ModernTileActionsMenu(expanded, saved.key in currentFavorites.keys, dismiss,
                            { currentToggleFavorite(saved) }, clear = { currentRemoveRecent(recent) },
                            openSeries = if (recent.kind == FavoriteKind.SERIES) currentOpenProviderSeries?.let { open -> { open(recent.series ?: recent.media) } } else null,
                            trailerItem = recent.media.takeUnless { it.episodeNumber != null }, trailerSeries = recent.kind == FavoriteKind.SERIES,
                            alternateSource = if (recent.kind == FavoriteKind.SERIES && recent.lastPlayed != null)
                                AlternateSourceRequest(recent.lastPlayed, CatalogType.SERIES, recent.series ?: recent.media,
                                    recent.lastPlayed.seasonNumber, recent.lastPlayed.episodeNumber) else null,
                            recommendationItem = recent.media, recommendationType = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES,
                            viewDescription = recent.media.description?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                            onTrailerReturned = restore, playAction = { (currentProviderRecent ?: currentOpenRecent)(recent) })
                        if (descriptionOpen) FullDescriptionDialog(recent.media.title, recent.media.description.orEmpty()) { descriptionOpen = false; restore() }
                    })
            }))
            if (surface != DashboardSurface.MOVIES) {
                val episodes = newEpisodeGroups(app.watchedSeries, profile)
                if (episodes.isNotEmpty()) add(Rail("episodes", "New Episodes", episodes.map { group ->
                    val episode = group.latest
                    RailEntry("episodes:${group.key}", providerDisplayTitle(group.watched.series.title, CatalogType.SERIES), group.watched.series.logo,
                        providerMedia = group.watched.series, providerType = CatalogType.SERIES,
                        subtitle = "S${episode.seasonNumber ?: "?"}:E${episode.episodeNumber ?: "?"} · ${episode.title}",
                        open = { focusOrigins.returningTo = "episodes:${group.key}"; currentOpenWatched(group.watched, episode) },
                        popup = { expanded, dismiss, restore ->
                            var descriptionOpen by remember(group.key) { mutableStateOf(false) }
                            val saved = FavoriteItem(FavoriteKind.EPISODE, episode, group.watched.series, profileKey = profile, categoryTitle = group.watched.categoryTitle)
                            ModernTileActionsMenu(expanded, saved.key in currentFavorites.keys, dismiss,
                                { currentToggleFavorite(saved) }, clear = { currentDismissEpisode(group.watched, episode) },
                                markWatched = { currentMarkWatched(group.watched, episode) },
                                recommendationItem = group.watched.series, recommendationType = CatalogType.SERIES, trailerSeries = true,
                                alternateSource = AlternateSourceRequest(episode, CatalogType.SERIES, group.watched.series,
                                    episode.seasonNumber, episode.episodeNumber),
                                openSeries = currentOpenProviderSeries?.let { open -> { open(group.watched.series) } },
                                viewDescription = episode.description?.takeIf(String::isNotBlank)?.let { { descriptionOpen = true } },
                                onTrailerReturned = restore, playAction = { currentOpenWatched(group.watched, episode) })
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
                if (cards.isNotEmpty()) add(Rail("personal:${row.key}", row.title, cards.map { cardEntry(it, "personal:${row.key}") }, recommendation = row))
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
                    RailEntry("$rowKey:${item.id}", providerDisplayTitle(item.title, type), item.logo,
                        providerMedia = item, providerType = type,
                        subtitle = category.title.orEmpty(),
                        progress = progressLookup?.provider(type, item.id),
                        open = { focusOrigins.returningTo = "$rowKey:${item.id}"; currentOpenProvider(type, item, queue) },
                        popup = { expanded, dismiss, restore ->
                            ModernTileActionsMenu(expanded, saved.key in currentFavorites.keys, dismiss,
                                { currentToggleFavorite(saved) }, clear = null, trailerItem = item, trailerSeries = type == CatalogType.SERIES,
                                recommendationItem = item, recommendationType = type,
                                onTrailerReturned = restore, playAction = { currentPlayProvider(type, item, queue) },
                                downloadAction = if (type == CatalogType.MOVIES) ({ currentDownloadProvider(type, item) }) else null)
                        })
                }, providerType = type, providerCategory = category))
                }
            }

        } else {
            if (state.collection.isNotEmpty()) add(Rail("collection", state.details?.collection?.title ?: "Collection", state.collection.map { cardEntry(it, "collection") }))
            if (state.related.isNotEmpty()) add(Rail("related", "Related", state.related.map { cardEntry(it, "related") }))
        }
    } }
    val visibleRails = remember(rails, state.feeds, providerState.pages) { rails.filterNot { row ->
        row.section?.let { it in state.feeds && state.feeds[it].isNullOrEmpty() } == true ||
            row.providerCategory?.let { category -> providerDiscoverySources(listOf(category)).all { source ->
                val page = providerState.pages[category.type]?.get(source.id)
                page != null && page.page > 0 && !page.hasMore && !page.truncated && page.items.isEmpty()
            } } == true
    } }
    val routeKey = "$profile:${surface.name}:${state.detail?.key ?: "landing"}"
    val focusedRail = if (state.detail == null && !fastTvLanding) rails.firstOrNull { row -> row.entries.any { it.key == state.focusedEntryKey } } else null
    val focusedEntry = focusedRail?.entries?.firstOrNull { it.key == state.focusedEntryKey }
    val focusedProvider = focusedEntry?.providerMedia
    val focusedType = focusedEntry?.providerType
    val focusedQueue = focusedRail?.key?.let { providerQueues.second[it] }.orEmpty().ifEmpty { listOfNotNull(focusedProvider) }
    val rawCandidate = if (fastTvLanding) state.hero else
        state.details?.card?.takeIf { it.key == state.detail?.key } ?: state.detail ?: state.focusedPresentation ?: featured ?: state.hero
    val candidate = state.heroPresentation?.takeIf { state.detail == null && it.key == rawCandidate?.key } ?: rawCandidate
    // Selection and actions change immediately. Retain only the previous background
    // while artwork prepares; a slow image must never leave another title's Play active.
    val prepared by preparedDiscoveryCard(candidate.takeUnless { phonePortrait || fastTvLanding }, vm.preparedPresentation, presentationActive && !fastTvLanding)
    SideEffect { if (!fastTvLanding) prepared?.let(vm::retainPreparedPresentation) }
    val displayed = candidate
    var glassBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    providerCollection?.let { name -> CatalogType.entries.firstOrNull { it.name == name } }?.let { type ->
        DisposableEffect(Unit) { detailVisibilityChanged(true); onDispose { detailVisibilityChanged(false) } }
        saved.SaveableStateProvider("provider-collection:${type.name}:$providerCollectionCategory:$providerCollectionVisit") {
            val category = providerSelections[type].orEmpty().firstOrNull { it.id == providerCollectionCategory }
            ProviderDiscoveryCollectionRoute(type, category?.let { listOf(it) } ?: providerSelections[type].orEmpty(),
                category?.title ?: "Your IPTV ${type.title}", app,
                close = { saved.removeState("provider-collection:${type.name}:$providerCollectionCategory:$providerCollectionVisit"); providerCollection = null; providerCollectionCategory = null }, favorite = toggleFavorite)
        }
        return
    }
    // TV pivot scrolling must not compete with explicit vertical row alignment.
    StableTileFocusViewport {
    BoxWithConstraints(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)
        .onGloballyPositioned { glassBounds = it.boundsInRoot() }) {
        val screenHeight = maxHeight
        // Keep native owner focus while outgoing lazy items unpin and dispose. This
        // target has no lazy parent, is invisible to accessibility and is never a
        // normal D-pad destination; the next route's existing entry restores focus.
        Box(parkingModifier)
        if (!phonePortrait && !fastTvLanding) DiscoveryBackdrop(prepared, reduceMotion)
        Column(Modifier.fillMaxSize()) {
            if (phone && !phonePortrait) {
                DiscoveryPhoneNavigation(surface, tabRequester, playRequester,
                    ::closeRoute, ::selectRoute, ::classicRoute,
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
                if (!fastTvLanding) {
                    val activity = HeroActivity(heroVisible && state.detail == null, presentationActive,
                        contentFocused, scrolling, touching, menu || configure || tilePopupOpen || app.categoryManagerOpen || categoryPopup || actor != null)
                    LaunchedEffect(phonePortrait, heroVisible, presentationActive, state.hero?.key) {
                        if (phonePortrait && heroVisible && presentationActive && state.detail == null) vm.prepareHero()
                        else if (phonePortrait) vm.pauseHeroMetadata()
                    }
                    LaunchedEffect(activity, state.heroes.size) {
                        if (activity.mayRotate && state.heroes.size > 1) while (true) { delay(8_000); vm.rotate(1) }
                    }
                    LaunchedEffect(presentationActive, scrolling, state.heroIndex, state.heroes, phonePortrait) {
                        if (presentationActive && !scrolling && state.heroes.isNotEmpty()) {
                            delay(200)
                            val adjacent = state.heroes.getOrNull((state.heroIndex + 1) % state.heroes.size)
                            prefetchDiscoveryArtwork(context, emptyList(), if (phonePortrait) adjacent?.backdrop ?: adjacent?.poster else adjacent?.backdrop,
                                portraitHero = phonePortrait, portraitPoster = phonePortrait && adjacent?.backdrop.isNullOrBlank())
                        }
                    }
                }
                val routeIdentity = state.detail?.key ?: "landing"
                fun ownsFocusRoute() = focusRouteOwner.owns(focusRouteToken) && vm.state.value.ownsFocusRoute(profile, surface, routeIdentity)
                val playOrigin = "$routeIdentity:play"
                SideEffect { returnRequesters[playOrigin] = playRequester }
                var handledNavigationId by rememberSaveable { mutableLongStateOf(-1L) }
                fun awaitingMenuEntry() = (!phone || remoteNavigation) && state.detail == null &&
                    handledNavigationId != app.discoveryNavigationId
                val focusRestoration = remember(app.discoveryNavigationId, state.detail?.key, remoteNavigation) {
                    DiscoveryFocusRestoration(if ((phone && !remoteNavigation) || awaitingMenuEntry()) null else
                        focusOrigins.routes[routeIdentity] ?: focusOrigins.returningTo.takeIf { state.detail == null })
                }
                LaunchedEffect(app.discoveryNavigationId, state.detail?.key, remoteNavigation) {
                    if (phone && !remoteNavigation) return@LaunchedEffect
                    val menuEntry = awaitingMenuEntry()
                    suspend fun enterRoute() {
                        navigationTrace?.invoke("entry-start surface=$surface nav=${app.discoveryNavigationId} handled=$handledNavigationId menuEntry=$menuEntry first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                        if (menuEntry) {
                            coordinator.cancel()
                            listState.scrollToItem(0)
                            navigationTrace?.invoke("entry-scrolled surface=$surface nav=${app.discoveryNavigationId} handled=$handledNavigationId first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                        }
                        var readinessFrames = 0
                        while (!hostView.hasWindowFocus() && !focusRestoration.canceled && readinessFrames++ < 60) withFrameNanos { }
                        if (focusRestoration.canceled || !ownsFocusRoute()) return
                        val key = focusRestoration.target
                        // An attached return tile can focus before the first draw; only
                        // new header/detail entry needs the extra composition frames.
                        if (key == null) { withFrameNanos { }; withFrameNanos { } }
                        if (key != null) repeat(8) {
                            if (focusRestoration.canceled || !ownsFocusRoute()) return
                            val restored = runCatching { returnRequesters[key]?.requestFocus() == true }.getOrDefault(false)
                            if (it == 0 || it == 7 || restored) navigationTrace?.invoke("entry-restore surface=$surface nav=${app.discoveryNavigationId} key=$key restored=$restored first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                            if (restored) return
                            withFrameNanos { }
                        }
                        repeat(20) { attempt ->
                            if (focusRestoration.canceled || !ownsFocusRoute()) return
                            val focused = runCatching { if (state.detail == null) tabRequester.requestFocus() else playRequester.requestFocus() }.getOrDefault(false)
                            if (attempt == 0 || attempt == 19 || focused) navigationTrace?.invoke("entry-focus surface=$surface nav=${app.discoveryNavigationId} handled=$handledNavigationId menuEntry=$menuEntry attempt=$attempt target=${if (state.detail == null) "tab" else "play"} focused=$focused first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                            if (focused) {
                                // A restored row can briefly take focus while the lazy header
                                // composes. Consume this entry only after the selected tab owns it.
                                if (menuEntry) handledNavigationId = app.discoveryNavigationId
                                navigationTrace?.invoke("entry-complete surface=$surface nav=${app.discoveryNavigationId} handled=$handledNavigationId first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                                return
                            }
                            withFrameNanos { }
                        }
                    }
                    // scrollToItem ends before focus transfer: implicit BringIntoView
                    // must remain suppressed until the header owns focus or this entry ends.
                    try {
                        if (menuEntry || focusRestoration.pending) coordinator.withAutomaticRevealSuppressed { enterRoute() } else enterRoute()
                    } finally { focusRestoration.finish() }
                }
                var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
                fun alignFocusedRow(key: String, index: Int) {
                    if (!ownsFocusRoute()) {
                        navigationTrace?.invoke("align-drop surface=$surface route=$routeIdentity key=$key active=${vm.state.value.detail?.key ?: "landing"}")
                        return
                    }
                    val pending = awaitingMenuEntry()
                    navigationTrace?.invoke("align surface=$surface nav=${app.discoveryNavigationId} handled=$handledNavigationId key=$key index=$index previous=$focusedRow pending=$pending first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset} moving=${coordinator.isMoving}")
                    // Explicit menu selection takes precedence over a lazy row restoring
                    // focus; that row must not scroll the header away before it can enter.
                    if (key != "navigation" && pending) return
                    if (focusRestoration.pending) return // Retain the exact saved return offset.
                    // Left/Right changes the item, not the vertical navigation destination.
                    if (focusedRow != key) {
                        focusedRow = key
                        coordinator.align(listState, index, reduceMotion,
                            revealOnly = fastTvLanding && index >= positions.firstRail)
                    }
                }
                val focusHandlers = remember { mutableMapOf<Int, suspend () -> Boolean>() }
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
                    if (!ownsFocusRoute()) return
                    requestedRow = index
                    move = coordinator.move(listState, index, reduceMotion,
                        revealOnly = fastTvLanding && index >= positions.firstRail) {
                        if (!ownsFocusRoute()) false else when (index) {
                            positions.header -> runCatching { tabRequester.requestFocus() }.getOrDefault(false)
                            positions.hero -> runCatching { playRequester.requestFocus() }.getOrDefault(false)
                            else -> focusHandlers[index]?.invoke() == true
                        }
                    }
                }
                fun moveRow(index: Int, delta: Int): Boolean {
                    if (!ownsFocusRoute()) return false
                    val current = if (move?.isActive == true) requestedRow else index
                    val next = adjacentDiscoveryRow(current, delta, navigationRows) ?: return false
                    focusRow(next)
                    return true
                }
                DiscoveryVerticalFocusViewport(coordinator) {
                LazyColumn(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (focusRestoration.pending && event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight))
                        focusRestoration.cancel() // Deliberate navigation wins over a pending return.
                    false
                }.pointerInput(focusRestoration) {
                    awaitPointerEventScope { while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                        if (touching && focusRestoration.pending) focusRestoration.cancel()
                        if (phone && !remoteNavigation && touching) contentFocused = false
                    } }
                }, state = listState, contentPadding = PaddingValues(bottom = if (phone && state.detail == null) 96.dp else 32.dp)) {
                    if (!phone) item("navigation") {
                        Box(Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) moveRow(0, 1) else false
                        }) {
                            DiscoveryTopNavigation(surface, app.savedProfile?.name.orEmpty(), tabRequester, playRequester,
                                back = ::closeRoute,
                                select = { tab -> if (tab != surface) selectRoute(tab) else if (state.detail != null) closeRoute() },
                                classic = ::classicRoute, headerFocus = { if (ownsFocusRoute()) { contentFocused = false; alignFocusedRow("navigation", 0) } },
                                menu = menu, changeMenu = { menu = it }, customize = { configure = true },
                                backdrop = prepared?.backdrop.takeUnless { fastTvLanding }, backdropBounds = glassBounds,
                                categoryManagerOpen = app.categoryManagerOpen, categoriesEnabled = state.detail == null,
                                categoryPopupChanged = { categoryPopup = it })
                        }
                    }
                    item("hero") {
                        val origin = "hero:${displayed?.key}"
                        val infoFocusRequester = remember(origin) { returnRequesters.getOrPut(origin) { FocusRequester() } }
                        val heroHeight = if (phonePortrait) phoneDiscoveryHeroHeight()
                            else if (compactLandscape) (screenHeight * .52f).coerceIn(165.dp, 230.dp)
                            else if (landscape) (screenHeight * .55f).coerceIn(235.dp, 355.dp) else (screenHeight * .45f).coerceIn(255.dp, 355.dp)
                        DiscoveryHero(displayed, if (state.detail == null) state.feeds[if (displayed?.series == true) TmdbHomeSection.TRENDING_SERIES else TmdbHomeSection.TRENDING_MOVIES]
                            .orEmpty().take(10).indexOfFirst { it.key == displayed?.key }.takeIf { it >= 0 }?.plus(1) else null,
                            height = heroHeight, landscape = landscape, compact = compactLandscape, detail = state.detail != null,
                            loading = displayed == null && (state.loading.isNotEmpty() || candidate != null || (state.feeds.isEmpty() && state.errors.isEmpty())),
                            inList = if (focusedProvider != null) focusedProvider.id in favorites.profileMediaIds
                                else displayed?.let { card -> card.media.id in favorites.profileMediaIds } == true,
                            playRequester = playRequester, infoRequester = infoFocusRequester,
                            play = {
                                if (focusedProvider != null) focusedEntry?.open?.invoke()
                                else displayed?.let { focusOrigins.returningTo = playOrigin; focusOrigins.routes = focusOrigins.routes + (routeIdentity to playOrigin); playCard(it) }
                            },
                            add = {
                                if (focusedProvider != null && focusedType != null) toggleFavorite(FavoriteItem(
                                    if (focusedType == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE, focusedProvider, profileKey = profile))
                                else displayed?.let { toggleFavorite(favorite(it)) }
                            },
                            info = {
                                if (focusedProvider != null && focusedType != null) { focusOrigins.returningTo = origin; openProvider(focusedType, focusedProvider, focusedQueue) }
                                else displayed?.let { details(it, origin) }
                            },
                            onFocus = { if (ownsFocusRoute()) { contentFocused = true; focusedRow = "hero" } }, onDown = { moveRow(positions.hero, 1) }, onUp = { moveRow(positions.hero, -1) },
                            previous = { vm.rotate(-1) }, next = { vm.rotate(1) }, carousel = state.detail == null && state.heroes.size > 1,
                            index = state.heroIndex, count = state.heroes.size,
                            categoryActions = if (state.detail == null && !phone) ({
                                DiscoveryCategoryAccess(surface, app.categoryManagerOpen, modifier = if (phone) Modifier.widthIn(min = 144.dp) else Modifier, popupChanged = { categoryPopup = it },
                                    onFocus = { if (ownsFocusRoute()) { contentFocused = true; focusedRow = "hero" } })
                                DiscoveryAction("TMDB categories", Icons.Default.AutoAwesome, modifier = Modifier.then(if (phone) Modifier.widthIn(min = 144.dp) else Modifier).focusRequester(tmdbCategoriesRequester),
                                    onFocus = { if (ownsFocusRoute()) { contentFocused = true; focusedRow = "hero" } }) { restoreTmdbCategories = true; configure = true }
                            }) else null,
                            portraitPhone = phonePortrait,
                            phoneHeader = if (phonePortrait && state.detail == null) ({
                                DiscoveryPhoneHeader(surface, state.detail != null, tabRequester, playRequester,
                                    back = ::closeRoute,
                                    select = { if (it != surface) selectRoute(it) }, classic = ::classicRoute,
                                    menu = menu, changeMenu = { menu = it }, customize = { configure = true }, profile = app.savedProfile?.name.orEmpty(),
                                    categoryManagerOpen = app.categoryManagerOpen, categoryPopupChanged = { categoryPopup = it })
                            }) else null)
                        state.errors.filterKeys { it == "detail" }.values.forEach { Text(it, Modifier.padding(horizontal = 24.dp), color = Color.LightGray, fontSize = 13.sp) }
                        if (candidate == null && state.loading.isEmpty() && (state.feeds.isNotEmpty() || state.errors.isNotEmpty())) Text("Discovery is unavailable. Check your TMDB configuration or retry below.", Modifier.padding(24.dp), color = Color.LightGray)
                    }
                    if (state.detail?.series == true) item("seasons") {
                        val seasonEntry = remember { FocusRequester() }
                        DisposableEffect(Unit) {
                            focusHandlers[positions.season] = { runCatching { seasonEntry.requestFocus() }.getOrDefault(false) }
                            onDispose { focusHandlers.remove(positions.season) }
                        }
                        DiscoverySeriesEpisodes(state, app.seriesStartSeason, seasonEntry, vm::loadSeason,
                            playEpisode, downloadEpisode,
                            onFocus = { if (ownsFocusRoute()) contentFocused = true },
                            up = { moveRow(positions.season, -1) }, down = { moveRow(positions.season, 1) })
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
                        val width = if (landscape) discoveryPosterWidth(configuration.screenWidthDp.toFloat(), screenHeight.value).dp
                            else ((configuration.screenWidthDp - 40) / 3.15f - 10).dp.coerceIn(88.dp, 120.dp)
                        DiscoveryRail(row, width, reduceMotion, returnRequesters, navigationIndex = index, phone = phonePortrait,
                            dismissRow = row.recommendation?.takeIf { dismissRecommendation != null }?.let { recommendation -> {
                                if (remoteNavigation) moveRow(index, -1)
                                dismissRecommendation?.invoke(recommendation)
                            } },
                            popupChanged = { tilePopupOpen = it },
                            prefetch = presentationActive && !fastTvLanding && !scrolling && anchorIndex == index,
                            trackPresentation = !fastTvLanding,
                            presentationEnabled = presentationActive && !fastTvLanding && !scrolling,
                            skeleton = row.section?.let { it !in state.feeds && "feed:${it.name}" !in state.errors } == true ||
                                row.providerType?.let { !providerState.ready || (row.entries.isEmpty() && it !in providerState.errors && it !in providerState.capped) } == true,
                            register = { handler -> focusHandlers[index] = handler }, unregister = { focusHandlers.remove(index) },
                            focused = record@{ entry ->
                                if (!ownsFocusRoute() || !focusRestoration.accepts(entry.key)) {
                                    navigationTrace?.invoke("focus-drop surface=$surface route=$routeIdentity key=${entry.key} active=${vm.state.value.detail?.key ?: "landing"} pending=${focusRestoration.pending}")
                                    return@record
                                }
                                navigationTrace?.invoke("focus-record surface=$surface route=$routeIdentity key=${entry.key} pending=${focusRestoration.pending}")
                                contentFocused = true; focusOrigins.returningTo = entry.key; focusOrigins.routes = focusOrigins.routes + (routeIdentity to entry.key)
                                if (!phonePortrait) alignFocusedRow(row.key, index)
                            },
                            present = { entry ->
                                if (ownsFocusRoute() && focusRestoration.accepts(entry.key)) {
                                    if (entry.providerMedia != null && entry.providerType != null) vm.presentProvider(entry.key, entry.providerMedia, entry.providerType)
                                    else entry.card?.let { featured = it; vm.presentFocusedCard(entry.key, it) }
                                }
                            },
                            move = { delta -> moveRow(index, delta) }, reenter = { focusRow(index) },
                            viewAll = row.providerType?.let { type -> { focusOrigins.returningTo = "${row.key}:all"; providerCollectionVisit++; providerCollectionCategory = row.providerCategory?.id; providerCollection = type.name } }
                                ?: row.section?.let { section -> { focusOrigins.returningTo = "${row.key}:all"; openSection(section) } })
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
                                            if (it.isFocused && ownsFocusRoute() && focusRestoration.accepts(key)) {
                                                selected = key; focusOrigins.returningTo = key
                                                focusOrigins.routes = focusOrigins.routes + (routeIdentity to key)
                                                contentFocused = true; alignFocusedRow("cast", castIndex)
                                            }
                                        }, shape = RoundedCornerShape(12.dp),
                                        color = if (focused) Color(0xFF303641) else Color.Transparent,
                                        border = if (focused) BorderStroke(2.dp, Color.White) else null) {
                                    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        AsyncImage(person.portrait, null, Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF20232A)), contentScale = ContentScale.Crop)
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
                                        DiscoveryAction("Search selected categories", Icons.Default.Search) { providerCollectionVisit++; providerCollectionCategory = null; providerCollection = type.name }
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
            iconOnly = true, modifier = Modifier.align(Alignment.TopStart).padding(12.dp), onClick = ::closeRoute)
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
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tmdbCategoryPairs(surface).groupBy { it.hollywood.series }.forEach { (series, pairs) ->
                    item {
                        Text(if (series) "Series" else "Movies", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Text("Hollywood", Modifier.width(84.dp), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text("Bollywood", Modifier.width(84.dp), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    items(pairs, key = { it.hollywood.name }) { pair ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(pair.title, Modifier.weight(1f), fontSize = 13.sp)
                            listOf(pair.hollywood, pair.bollywood).forEach { section ->
                                NikTvSecondaryActionButton(onClick = { vm.configure(if (section in state.sections) state.sections - section else state.sections + section) },
                                    modifier = Modifier.width(84.dp).heightIn(min = 44.dp).semantics { contentDescription = section.title },
                                    contentPadding = PaddingValues(2.dp)) {
                                    Checkbox(section in state.sections, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { NikTvSecondaryActionButton({ configure = false }) { Text("Close") } }, containerColor = Color(0xFF181B22))

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
    val direction = LocalLayoutDirection.current
    val edgePadding = minOf(contentPadding.calculateLeftPadding(direction), contentPadding.calculateRightPadding(direction))
    DiscoveryHorizontalFocusViewport(edgePadding) {
        LazyRow(modifier, state, contentPadding, horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment, content = content)
    }
}

/** Prepare only the background; selected metadata/actions never wait for image decoding. */
@Composable
private fun preparedDiscoveryCard(card: DiscoveryCard?, initial: DiscoveryCard? = null, enabled: Boolean = true): State<DiscoveryCard?> {
    val context = LocalContext.current
    val request = remember(context, card?.backdrop) {
        card?.backdrop?.let { ImageRequest.Builder(context).data(thumbnailArtworkUrl(it, 1280))
            .size(1280, 720).transformations(DiscoveryBackdropTransformation()).build() }
    }
    return produceState<DiscoveryCard?>(initial, card, request, enabled) {
        if (!enabled) return@produceState
        if (card == null) { value = null; return@produceState }
        if (request == null) { value = card; return@produceState }
        val image = try {
            withTimeoutOrNull(2000) { withContext(Dispatchers.IO) {
                context.imageLoader.execute(request)
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
internal fun DiscoveryBackdrop(card: DiscoveryCard?, reduceMotion: Boolean, alignment: Alignment = Alignment.Center, shaded: Boolean = true) {
    val context = LocalContext.current
    val requested = card?.backdrop?.let { thumbnailArtworkUrl(it, 1280) }
    var ready by remember { mutableStateOf(requested) }
    val currentRequest by rememberUpdatedState(requested)
    Box(Modifier.fillMaxSize()) {
        Crossfade(ready, animationSpec = tween(if (reduceMotion) 0 else 300), label = "discoveryBackdrop") { url ->
            val request = remember(context, url, shaded) { ImageRequest.Builder(context).data(url).size(1280, 720)
                .apply { if (shaded) transformations(DiscoveryBackdropTransformation()) }.crossfade(false).build() }
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment)
        }
        // Hidden decode layer promotes only the latest successfully prepared backdrop.
        if (requested != null && requested != ready) {
            val request = remember(context, requested, shaded) { ImageRequest.Builder(context).data(requested).size(1280, 720)
                .apply { if (shaded) transformations(DiscoveryBackdropTransformation()) }.crossfade(false).build() }
            AsyncImage(request, null,
                Modifier.size(1.dp).graphicsLayer { alpha = 0f }, onSuccess = { if (currentRequest == requested) ready = requested }, onError = { if (currentRequest == requested) ready = null })
        }
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
        DiscoveryPhoneHero(card, rank, height.coerceAtLeast(if (compactDetail) 280.dp else 480.dp), detail, loading, inList,
            playRequester, infoRequester, play, add, info, previous, next, carousel, index, count,
            playLabel, playEnabled, reduceMotion, onFocus, onDown, phoneHeader, showSynopsis, compactDetail, categoryActions,
            shadeArtworkBounds = compactDetail)
        return
    }
    var drag by remember { mutableFloatStateOf(0f) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = if (landscape) 32.dp else 18.dp, vertical = if (compact) 8.dp else 16.dp)
        .pointerInput(carousel) { if (carousel) detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -70f) next() else if (drag > 70f) previous() }) { change, amount -> change.consume(); drag += amount } },
        verticalArrangement = Arrangement.Bottom) {
        // Landing heroes keep the same geometry before feeds, artwork and metadata arrive.
        // Detail pages retain their full, variable-length synopsis.
        if (!detail) Box(Modifier.height(24.dp * fontScale), contentAlignment = Alignment.CenterStart) {
            if (loading) LinearProgressIndicator(Modifier.width(160.dp))
            else rank?.let { Text("#$it · TRENDING TODAY", color = Color.LightGray, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }
        } else if (loading) {
            LinearProgressIndicator(Modifier.width(160.dp)); Spacer(Modifier.height(16.dp))
        }
        var logoReady by remember(card?.titleLogo) { mutableStateOf(false) }
        Box(Modifier.widthIn(max = if (landscape) 560.dp else 400.dp).height((if (landscape && !compact) 76.dp else 64.dp) * fontScale), contentAlignment = Alignment.CenterStart) {
            if (loading && card == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(280.dp).height(20.dp).background(Color.White.copy(alpha = .10f), RoundedCornerShape(4.dp)))
                Box(Modifier.width(180.dp).height(16.dp).background(Color.White.copy(alpha = .07f), RoundedCornerShape(4.dp)))
            } else if (!logoReady) Text(card?.title ?: "Discover something to watch", color = Color.White,
                fontSize = if (landscape && !compact) 32.sp else 26.sp, lineHeight = if (landscape && !compact) 36.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!card?.titleLogo.isNullOrBlank()) AsyncImage(card?.titleLogo, card?.title,
                Modifier.widthIn(max = 400.dp).fillMaxHeight().graphicsLayer { alpha = if (logoReady) 1f else 0f }, contentScale = ContentScale.Fit,
                onSuccess = { logoReady = true }, onError = { logoReady = false })
        }
        val metadata = card?.let { listOfNotNull(if (detail) discoveryReleaseLabel(it) else it.date?.take(4), it.runtime?.let { n -> "${n} min" }, it.genres.take(3).joinToString(" · ").takeIf(String::isNotBlank),
            it.rating?.takeIf { n -> n > 0 }?.let { n -> "★ %.1f".format(n) }, it.certification).joinToString(" · ") }.orEmpty()
        if (detail && card != null) Column(Modifier.widthIn(max = if (landscape) 600.dp else 500.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            discoveryDetailMetadata(card).forEach { line -> Text(line, color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp) }
        } else Box(Modifier.widthIn(max = if (landscape) 600.dp else 500.dp)
            .then(if (!detail) Modifier.height(36.dp * fontScale) else Modifier), contentAlignment = Alignment.TopStart) {
            if (metadata.isNotBlank()) Text(metadata, Modifier.padding(top = if (compact) 4.dp else 6.dp),
                color = Color.LightGray, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else if (loading && !detail) Box(Modifier.padding(top = 6.dp).width(220.dp).height(12.dp)
                .background(Color.White.copy(alpha = .07f), RoundedCornerShape(4.dp)))
        }
        if (showSynopsis) Box(Modifier.widthIn(max = if (landscape) 600.dp else 500.dp)
            .then(if (!detail) Modifier.height((if (compact) 44.dp else 52.dp) * fontScale) else Modifier)) {
            card?.overview?.takeIf(String::isNotBlank)?.let { Text(it, Modifier.padding(top = if (compact) 6.dp else 8.dp),
                color = Color(0xFFD6D8DC), fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 16.sp else 18.sp,
                maxLines = if (detail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
            if (loading && card == null) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(300.dp).height(10.dp).background(Color.White.copy(alpha = .07f), RoundedCornerShape(4.dp)))
                Box(Modifier.width(240.dp).height(10.dp).background(Color.White.copy(alpha = .05f), RoundedCornerShape(4.dp)))
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        val actions: @Composable () -> Unit = {
            CompositionLocalProvider(LocalCollapsingDiscoveryActions provides false) {
            DiscoveryAction(if (detail && playLabel.startsWith("Resume ")) "Resume" else playLabel, Icons.Default.PlayArrow, selected = true,
                modifier = Modifier.then(if (detail) Modifier.width(160.dp).heightIn(min = 48.dp) else Modifier).focusRequester(playRequester), enabled = card != null && playEnabled, onFocus = onFocus, onClick = play)
            }
            DiscoveryAction(if (inList) "In My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add,
                iconOnly = !detail, modifier = if (detail) Modifier.width(160.dp).heightIn(min = 48.dp) else Modifier, enabled = card != null, onFocus = onFocus, onClick = add)
            DiscoveryTrailerAction(card, onFocus = onFocus, modifier = if (detail) Modifier.width(160.dp).heightIn(min = 48.dp) else Modifier)
            if (!detail) DiscoveryAction("Info", Icons.Default.Info, iconOnly = true,
                modifier = Modifier.focusRequester(infoRequester), enabled = card != null, onFocus = onFocus, onClick = info)
            if (landscape) categoryActions?.invoke()
            // Keep carousel arrows compact and its counter width reserved while feeds load.
            if (landscape && (carousel || !detail)) CompositionLocalProvider(LocalCollapsingDiscoveryActions provides false) {
                DiscoveryCarouselControls(index, count, onFocus, previous, next, enabled = carousel, compact = !detail)
            }
        }
        val actionNavigation = Modifier.onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else when (it.key) {
                Key.DirectionDown -> { onDown(); true }
                Key.DirectionUp -> onUp()
                else -> false
            }
        }
        if (landscape && !detail) CompositionLocalProvider(LocalCollapsingDiscoveryActions provides true) {
            // A single, fixed-height action row cannot reflow the page when a label expands.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).then(actionNavigation),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().then(actionNavigation)) { actions() }

        if (!landscape && (carousel || !detail || categoryActions != null)) FlowRow(Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            categoryActions?.invoke()
            if (carousel || !detail) DiscoveryCarouselControls(index, count, onFocus, previous, next, enabled = carousel)
        }
    }
}

@Composable
private fun DiscoveryCarouselControls(index: Int, count: Int, onFocus: () -> Unit, previous: () -> Unit, next: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DiscoveryAction("Previous featured title", Icons.Default.ChevronLeft, iconOnly = true, enabled = enabled, onFocus = onFocus, onClick = previous)
        Text(if (count > 0) "${index + 1} / $count" else "— / —", Modifier.width(((if (compact) 48f else 60f) * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
            color = Color.LightGray, fontSize = 11.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        DiscoveryAction("Next featured title", Icons.Default.ChevronRight, iconOnly = true, enabled = enabled, onFocus = onFocus, onClick = next)
    }
}

@Composable
private fun DiscoveryRail(row: Rail, width: Dp, reduceMotion: Boolean, requesters: MutableMap<String, FocusRequester>, navigationIndex: Int, phone: Boolean = false,
    popupChanged: (Boolean) -> Unit,
    prefetch: Boolean, trackPresentation: Boolean, presentationEnabled: Boolean, skeleton: Boolean,
    register: (suspend () -> Boolean) -> Unit, unregister: () -> Unit, focused: (RailEntry) -> Unit,
    present: (RailEntry) -> Unit, move: (Int) -> Boolean, reenter: () -> Unit, viewAll: (() -> Unit)?, dismissRow: (() -> Unit)? = null) {
    val rowState = rememberLazyListState()
    val dismissRequester = remember(row.key) { FocusRequester() }
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
    var lastPoster by rememberSaveable(row.key) { mutableStateOf<String?>(null) }
    var focusedCard by remember { mutableStateOf<RailEntry?>(null) }
    val currentPresentation by rememberUpdatedState(present)
    LaunchedEffect(focusedCard?.key, presentationEnabled, rowState.isScrollInProgress) {
        // Keep image decoding, metadata publication and a full-screen crossfade out of
        // the row's movement. Focus highlighting itself remains immediate.
        if (presentationEnabled && !rowState.isScrollInProgress) {
            delay(150)
            focusedCard?.let(currentPresentation)
        }
    }
    val entryKeys by rememberUpdatedState(remember(row.entries) { row.entries.map { it.key } })
    DisposableEffect(row.key, navigationIndex) {
        register {
            focusDiscoveryRowEntry(selected, entryKeys, "${row.key}:all".takeIf { row.providerType != null || viewAll != null },
                allIsRowItem = row.providerType == null,
                isFullyVisible = { index ->
                    val layout = rowState.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    item != null && item.offset >= layout.viewportStartOffset + layout.beforeContentPadding &&
                        item.offset + item.size <= layout.viewportEndOffset - layout.afterContentPadding
                },
                reveal = { rowState.scrollToItem(it) },
                request = { key -> runCatching { requesters[key]?.requestFocus() == true }.getOrDefault(false) })
        }
        onDispose(unregister)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, Modifier.weight(1f).padding(start = if (phone) 16.dp else 24.dp, top = 4.dp), color = Color.White,
            fontSize = if (phone) 19.sp else 16.sp, lineHeight = if (phone) 24.sp else 20.sp, fontWeight = FontWeight.SemiBold)
        if (dismissRow != null) DiscoveryAction("Dismiss ${row.title}", Icons.Default.Close, iconOnly = true,
            modifier = Modifier.focusRequester(dismissRequester).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown, Key.DirectionLeft -> { reenter(); true }
                    Key.DirectionUp -> move(-1)
                    else -> false
                }
            }, onClick = dismissRow)
        if (row.providerType != null && viewAll != null) {
            val key = "${row.key}:all"
            val requester = remember(key) { requesters.getOrPut(key) { FocusRequester() } }
            DiscoveryAction("Search / All", Icons.Default.Search,
                modifier = Modifier.focusRequester(requester).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> move(-1)
                        Key.DirectionDown, Key.DirectionLeft -> {
                            selected = lastPoster?.takeIf { it in entryKeys } ?: entryKeys.firstOrNull()
                            if (selected != null) { reenter(); true } else move(1)
                        }
                        else -> false
                    }
                }, onFocus = { selected = key; focused(RailEntry(key, row.title, null, open = viewAll)) }, onClick = viewAll)
        }
        }
        DiscoveryLazyRow(Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus) focusedCard = null }.focusGroup().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                if (event.key == Key.DirectionUp && dismissRow != null) {
                    runCatching { dismissRequester.requestFocus() }.getOrDefault(false)
                } else if (event.key == Key.DirectionUp && row.providerType != null && viewAll != null) {
                    selected = "${row.key}:all"; reenter(); true
                } else move(if (event.key == Key.DirectionUp) -1 else 1)
            } else false
        }, state = rowState, contentPadding = PaddingValues(start = if (phone) 16.dp else 24.dp, top = 10.dp, end = if (phone) 16.dp else 24.dp, bottom = if (phone) 12.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 12.dp)) {
            if (row.entries.isEmpty() && skeleton) items(5) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (row.ranked) Box(Modifier.width(32.dp).height(if (phone) 64.dp else 44.dp)
                        .padding(bottom = if (phone) 50.dp else 46.dp))
                    Box(Modifier.width(tileWidth)
                        .height(if (phone) phoneDiscoveryCardHeight(tileWidth.value, phoneWide, LocalDensity.current.fontScale).dp else tileWidth * 1.5f)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1F27)))
                }
            }
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
                            wide = phoneWide, modifier = Modifier.focusRequester(requester), progress = entry.progress, matchCount = entry.matchCount, source = entry.displaySource,
                            onClick = entry.open, onLongClick = entry.popup?.let { { popupOpen = true } },
                            onFocus = { selected = entry.key; lastPoster = entry.key; if (trackPresentation) focusedCard = entry; focused(entry) })
                        else DiscoveryPosterTile(entry.title, entry.artwork, entry.subtitle, width, reduceMotion = reduceMotion,
                            modifier = Modifier.focusRequester(requester), progress = entry.progress, matchCount = entry.matchCount, source = entry.displaySource,
                            onClick = entry.open, onLongClick = entry.popup?.let { { popupOpen = true } },
                            onFocus = { selected = entry.key; lastPoster = entry.key; if (trackPresentation) focusedCard = entry; focused(entry) })
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
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onFocus: () -> Unit = {}, focusVerticalInset: Dp = 0.dp,
    source: DiscoveryTileSource? = null) {
    var focused by remember { mutableStateOf(false) }
    var artworkFailed by remember(artwork) { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val fastTv = remember(context, configuration.uiMode) { context.isTvLikeDevice(configuration) }
    val scale = if (fastTv) rememberUpdatedState(1f) else animateFloatAsState(if (focused && !reduceMotion) DiscoveryPosterFocusScale else 1f,
        tween(if (reduceMotion) 0 else if (focused) 380 else 320, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "discoveryTileScale")
    val border = if (fastTv) rememberUpdatedState(if (focused) 1f else 0f) else
        animateFloatAsState(if (focused) 1f else 0f, tween(if (reduceMotion) 0 else 180), label = "discoveryTileBorder")
    val shape = remember { RoundedCornerShape(10.dp) }
    val decodeWidth = if (fastTv) with(LocalDensity.current) { width.roundToPx() }.coerceAtLeast(1) else 342
    val decodeHeight = if (fastTv) (decodeWidth / aspect).toInt().coerceAtLeast(1) else if (aspect == 2f / 3f) 513 else 193
    val request = remember(context, artwork, aspect, decodeWidth, decodeHeight) { ImageRequest.Builder(context)
        .data(thumbnailArtworkUrl(artwork, decodeWidth)).size(decodeWidth, decodeHeight).crossfade(false).build() }
    val focusLayer = if (fastTv) Modifier else Modifier.graphicsLayer {
        scaleX = scale.value; scaleY = scale.value; shadowElevation = if (focused) 4.dp.toPx() else 0f; this.shape = shape
    }
    // Scale the legacy overlay treatment to these deliberately smaller posters.
    val textScale = (width.value / 120f).coerceIn(.85f, 1.15f)
    Column(modifier.width(width).semantics {
        if (source != null || matchCount != null) stateDescription = discoveryTileStateDescription(source, matchCount)
    }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick, interactionSource = remember { MutableInteractionSource() }, showIndication = false)) {
        // Reserve focus expansion inside the focusable layout, not only in the
        // grid padding, which scrolling can consume at the viewport edge.
        Box(Modifier.padding(vertical = focusVerticalInset).fillMaxWidth().aspectRatio(aspect)
            .then(focusLayer).tileFocusOutline(true, border, shape, Color(0xFF2B303A), 2.dp)
            .clip(shape).background(Color(0xFF1C222C))) {
            AsyncImage(request,
                null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { artworkFailed = true })
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
            DiscoveryTileIndicators(source, matchCount, Modifier.align(Alignment.TopCenter))
            progress?.let { value -> Canvas(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)) {
                drawRect(Color.DarkGray); drawRect(Accent, size = size.copy(width = size.width * value.coerceIn(0f, 1f)))
            } }
        }
    }
}

internal val LocalCollapsingDiscoveryActions = staticCompositionLocalOf { false }

@Composable
internal fun DiscoveryAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false, iconOnly: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true,
    visualLabel: String = label, horizontalPadding: Dp = 14.dp,
    onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val collapsible = LocalCollapsingDiscoveryActions.current && icon != null
    DiscoveryMobileGlassContainer {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (LocalMobileGlassEnabled.current) 48.dp else 40.dp)
        .widthIn(min = if (LocalMobileGlassEnabled.current) 48.dp else 0.dp)
        .then(if (collapsible) Modifier.semantics { contentDescription = label } else Modifier)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(10.dp), color = if (selected) Accent else if (focused) Color.White
            else if (LocalMobileGlassEnabled.current) Color.Transparent else Color(0xFF242831).copy(alpha = if (LocalDiscoveryGlassButtons.current) .38f else .85f),
        border = if (LocalMobileGlassEnabled.current) null else BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58)),
        contentColor = if (focused && !selected) Color.Black else Color.White) {
        Row(Modifier.padding(horizontal = if (iconOnly || collapsible) 10.dp else horizontalPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (collapsible) 0.dp else 6.dp, Alignment.CenterHorizontally)) {
            icon?.let { Icon(it, if (iconOnly && !collapsible) label else null, Modifier.size(20.dp)) }
            if (collapsible) AnimatedVisibility(focused,
                enter = expandHorizontally(tween(140), expandFrom = Alignment.Start) + fadeIn(tween(100)),
                exit = shrinkHorizontally(tween(140), shrinkTowards = Alignment.Start) + fadeOut(tween(100))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text(visualLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            } else if (!iconOnly) Text(visualLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}
