package com.nikhil.niktv.discovery

import com.nikhil.niktv.discovery.components.*

import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*

internal data class ProviderDetailNavigation(
    val openDiscovery: (DashboardSurface) -> Unit, val navigate: (String) -> Unit,
    val favorite: (FavoriteItem) -> Unit, val playEpisode: (DiscoveryCard, Int, Int) -> Unit,
    val recent: (RecentItem) -> Unit, val watched: (WatchedSeries, MediaItem) -> Unit,
    val category: (Category) -> Unit, val section: (TmdbHomeSection) -> Unit,
    val removeRecent: (RecentItem) -> Unit, val markWatched: (WatchedSeries, MediaItem) -> Unit,
    val dismissEpisode: (WatchedSeries, MediaItem) -> Unit,
    val playProvider: (MediaItem, List<MediaItem>, PlaybackQueueFilter) -> Unit)
internal val LocalProviderDetailNavigation = staticCompositionLocalOf<ProviderDetailNavigation?> { null }
internal val LocalProviderSynopsisActions = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
internal val LocalProviderSynopsisPanel = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

internal val LocalProviderEpisodeControls = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
internal val LocalProviderMoreInfo = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalProviderInfoFocus = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
internal fun DetailMoreActions(modifier: Modifier = Modifier, cast: (() -> Unit)? = null, related: (() -> Unit)? = null) {
    val info = LocalProviderMoreInfo.current
    val infoFocus = LocalProviderInfoFocus.current
    var expanded by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val host = LocalView.current
    var hadMenu by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) hadMenu = true
        else if (hadMenu) {
            hadMenu = false
            repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { anchor.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
    }
    Box {
        DiscoveryAction("More", Icons.Default.MoreHoriz, iconOnly = true,
            modifier = modifier.focusRequester(anchor).then(infoFocus?.let { Modifier.focusRequester(it) } ?: Modifier)) { expanded = true }
        SeriesActionsMenu(expanded, { expanded = false }) {
            info?.let { EpisodeSortMenuItem("More info", Icons.Default.Info) { expanded = false; it() } }
            cast?.let { EpisodeSortMenuItem("Cast", Icons.Default.People) { expanded = false; it() } }
            related?.let { EpisodeSortMenuItem("Related", Icons.Default.Movie) { expanded = false; it() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderDetailSynopsis(overview: String?, title: String, remoteInfo: Boolean = false,
    requester: FocusRequester = remember { FocusRequester() }, up: (() -> Unit)? = null, down: (() -> Unit)? = null,
    reduceMotion: Boolean = false, actionsInHero: Boolean = false) {
    val actions = if (actionsInHero) null else LocalProviderSynopsisActions.current
    if (overview.isNullOrBlank() && actions == null) return
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
    Column(Modifier.fillMaxWidth().padding(horizontal = if (remoteInfo) 24.dp else 20.dp, vertical = 6.dp)) {
        if (!overview.isNullOrBlank()) Box(Modifier.widthIn(max = if (remoteInfo) 880.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxWidth()
            .animateContentSize(tween(if (reduceMotion) 0 else 240, easing = FastOutSlowInEasing)).clipToBounds()) {
            if (actionsInHero) InlineEpisodeDescription(overview, expanded = expanded,
                style = MaterialTheme.typography.bodyMedium) { expanded = !expanded }
            else Text(overview, color = Color(0xFFD6D6DC), style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow })
        }
        if (remoteInfo || actions != null || (!actionsInHero && (expanded || overflowing))) FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (remoteInfo && !overview.isNullOrBlank()) DiscoveryAction("More info", Icons.Default.Info,
            modifier = Modifier.heightIn(min = 48.dp).focusRequester(requester).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { up?.invoke(); up != null }
                    Key.DirectionDown -> { down?.invoke(); down != null }
                    else -> false
                }
            }) { showInfo = true }
        else if (!actionsInHero && (expanded || overflowing)) DiscoveryAction(if (expanded) "Read less" else "Read more",
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            modifier = Modifier.heightIn(min = 48.dp)) { expanded = !expanded }
        actions?.invoke()
        }
    }
    if (showInfo) FullDescriptionDialog(title, overview.orEmpty().ifBlank { "No description is available for this series." }) { showInfo = false }
}

/** Provider identity is never replaced by the TMDB identity used for presentation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderDetailHeader(item: MediaItem, type: CatalogType, app: NikTvState,
    play: () -> Unit, close: () -> Unit, favorite: () -> Unit, isFavorite: Boolean,
    openTmdbMovie: (TrendingMovie) -> Unit, openTmdbSeries: (TrendingSeries) -> Unit,
    playRequester: FocusRequester = remember { FocusRequester() },
    backRequester: FocusRequester = remember { FocusRequester() },
    down: () -> Unit = {}, playLabel: String = "Play", playEnabled: Boolean = true,
    progressLabel: String? = null, progressFraction: Float? = null, castUp: (() -> Unit)? = null, relatedUp: (() -> Unit)? = null,
    episodeDown: (() -> Unit)? = null,
    heroUp: (() -> Unit)? = null, topDown: (() -> Unit)? = null,
    synopsisRequester: FocusRequester = remember { FocusRequester() },
    synopsisUp: (() -> Unit)? = null, synopsisDown: (() -> Unit)? = null,
    synopsisAvailable: (Boolean) -> Unit = {},
    seasonCounts: (Map<Int, Int>) -> Unit = {},
    compactSeries: Boolean = false,
    discoveryDetails: DiscoveryDetails? = null,
    metadataSource: DiscoveryDataSource? = null,
    openDiscoveryDetail: ((DiscoveryCard) -> Unit)? = null,
    tools: @Composable RowScope.() -> Unit = {},
    layout: @Composable (hero: @Composable () -> Unit, castRow: @Composable () -> Unit, supplementary: @Composable () -> Unit,
        cast: (() -> Boolean)?, related: (() -> Boolean)?) -> Unit = { hero, castRow, supplementary, _, _ ->
        Column { hero(); if (type == CatalogType.MOVIES) { supplementary(); castRow() } else { castRow(); supplementary() } }
    }) {
    val context = LocalContext.current
    val navigation = LocalProviderDetailNavigation.current
    val configuration = LocalConfiguration.current
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val defaultRepository = remember(context, profile) { DiscoveryRepository(context, providerProfile = profile) }
    val repository = metadataSource ?: defaultRepository
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
    var relatedOrigin by remember(profile, item.id, type) { mutableStateOf<FocusRequester?>(null) }
    val actorPages = rememberSaveableStateHolder()
    val host = LocalView.current
    val scope = rememberCoroutineScope()
    val castEntry = remember { FocusRequester() }
    val relatedEntry = remember { FocusRequester() }
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    LaunchedEffect(profile, item.id, type, item.externalTmdbId, retry, discoveryDetails) {
        if (discoveryDetails != null) {
            metadata = discoveryDetails; loading = false; failed = false
            return@LaunchedEffect
        }
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
    fun dismissRelated() {
        relatedDetail = null
        // Actor credits restore their own selected poster. A direct related-row
        // visit returns to the poster that opened it in the provider detail.
        if (actor == null) scope.launch {
            repeat(60) {
                withFrameNanos { }
                if (host.hasWindowFocus() && runCatching { relatedOrigin?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    val people = metadata.cast.filter { (it.id ?: 0) > 0 }.distinctBy { it.id }
    LaunchedEffect(profile, item.id, metadata.seasonEpisodeCounts) { seasonCounts(metadata.seasonEpisodeCounts) }
    LaunchedEffect(profile, item.id, metadata.card.overview) { synopsisAvailable(!metadata.card.overview.isNullOrBlank()) }
    val television = context.isTvLikeDevice(configuration)
    val seriesDetail = type == CatalogType.SERIES
    val phone = if (seriesDetail) usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, television) &&
        configuration.screenHeightDp >= configuration.screenWidthDp
        else usesDiscoveryPhoneLayout(configuration, television)
    val landscape = if (seriesDetail) configuration.screenWidthDp >= 600 || television else !phone
    val shortLandscapeSeries = seriesDetail && configuration.usesShortWideLayout(television)
    val shortWide = !seriesDetail && configuration.usesShortWideLayout(television)
    val wideSeries = seriesDetail && (configuration.smallestScreenWidthDp >= 600 || television ||
        configuration.screenWidthDp > configuration.screenHeightDp)
    val tabletSeriesOverlay = wideSeries
    val artworkHeight = detailDiscoveryHeroHeight(phone, television)
    val heroHeight = if (compactSeries && phone)
        (configuration.screenHeightDp * .30f).dp.coerceAtLeast(220.dp)
        else if (tabletSeriesOverlay) 160.dp else artworkHeight
    var compactInfoOpen by remember(item.id) { mutableStateOf(false) }
    val compactInfoRequester = remember(item.id) { FocusRequester() }
    if (compactInfoOpen) FullDescriptionDialog(metadata.card.title,
        metadata.card.overview.orEmpty().ifBlank { "No description is available for this series." }) {
        compactInfoOpen = false
        scope.launch {
            repeat(30) {
                withFrameNanos { }
                if (host.hasWindowFocus() && runCatching {
                    (if (wideSeries || compactSeries) synopsisRequester else compactInfoRequester).requestFocus()
                }.getOrDefault(false)) return@launch
            }
        }
    }
    val toolbar: @Composable () -> Unit = {
        CompositionLocalProvider(LocalMobileGlassEnabled provides true) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onPreviewKeyEvent { event ->
                if ((wideSeries || compactSeries) && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    if (topDown != null) { topDown(); true } else runCatching { playRequester.requestFocus() }.getOrDefault(false)
                } else false
            },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                    modifier = Modifier.focusRequester(backRequester), onClick = close)
                Spacer(Modifier.weight(1f))
                if (phone) {
                    val viewer = LocalSelectedUserProfile.current
                    DiscoveryAction("Switch profile: ${viewer?.name.orEmpty()}",
                        visualLabel = viewer?.avatar?.ifBlank { viewer.name.take(1).uppercase() } ?: "N",
                        containerColor = viewer?.let { Color(it.color) }, horizontalPadding = 0.dp,
                        modifier = Modifier.size(48.dp), onClick = LocalOpenUserProfiles.current)
                }
                tools()
                if (type == CatalogType.MOVIES) Box {
                    DiscoveryAction("Movie options", Icons.Default.MoreHoriz, iconOnly = true) { movieOptions = true }
                    ModernTileActionsMenu(movieOptions, isFavorite, dismiss = { movieOptions = false }, toggleFavorite = favorite,
                        clear = null, trailerItem = item.copy(externalTmdbId = metadata.card.id.takeIf { it > 0 } ?: item.externalTmdbId),
                        recommendationItem = item, recommendationType = type,
                        onTrailerReturned = { runCatching { playRequester.requestFocus() }.getOrDefault(false) })
                }
            }
            if (phone) CompositionLocalProvider(LocalMobileGlassEnabled provides true) {
            }
        }
        }
    }
    val hero: @Composable () -> Unit = {
        if (shortLandscapeSeries) {
            CompositionLocalProvider(LocalMobileGlassEnabled provides false,
                LocalDiscoveryHeaderActionSizing provides true, LocalCollapsingDiscoveryActions provides false) {
                Column(Modifier.fillMaxWidth().heightIn(min = (configuration.screenHeightDp * .34f).dp.coerceIn(112.dp, 140.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                            modifier = Modifier.focusRequester(backRequester), onClick = close)
                        Column(Modifier.weight(1f)) {
                            Text(metadata.card.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(discoveryDetailMetadata(metadata.card).joinToString(" · "),
                                color = Color.LightGray, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        tools()
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionDown -> { down(); true }
                            Key.DirectionUp -> { if (heroUp != null) heroUp() else runCatching { backRequester.requestFocus() }; true }
                            else -> false
                        }
                    }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            DiscoveryAction(if (playLabel.startsWith("Resume ")) "Resume" else playLabel, Icons.Default.PlayArrow, selected = true, enabled = playEnabled,
                                modifier = Modifier.focusRequester(playRequester), onClick = play)
                            DiscoveryAction(if (isFavorite) "In My List" else "Add to My List",
                                if (isFavorite) Icons.Default.Check else Icons.Default.Add, iconOnly = true, onClick = favorite)
                            DiscoveryTrailerAction(metadata.card, iconOnly = true)
                            LocalProviderSynopsisActions.current?.invoke()
                        }
                        LocalProviderEpisodeControls.current?.invoke()
                    }
                    progressLabel?.let { status ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(status, color = Color.LightGray, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            progressFraction?.let { fraction -> LinearProgressIndicator(progress = { fraction },
                                modifier = Modifier.width(112.dp).height(2.dp), color = Color(0xFFE50925),
                                trackColor = Color(0xFF343842), drawStopIndicator = {}) }
                        }
                    }
                    LocalProviderSynopsisPanel.current?.invoke()
                }
            }
        } else if (!wideSeries && compactSeries) {
            CompositionLocalProvider(LocalMobileGlassEnabled provides true, LocalHeroActionSizing provides true) {
            Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize()) { DiscoveryBackdrop(metadata.card, reduceMotion, Alignment.TopCenter, shaded = false) }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to Color(0x99090B10), .30f to Color.Transparent, .58f to Color(0x33090B10), 1f to Color(0xFF090B10))))
                Column(Modifier.fillMaxWidth().heightIn(min = heroHeight),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    toolbar()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(metadata.card.title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        discoveryDetailMetadata(metadata.card).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.LightGray, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionDown -> { down(); true }
                                    Key.DirectionUp -> { heroUp?.invoke(); heroUp != null }
                                    else -> false
                                }
                            }) {
                            DiscoveryAction(playLabel, Icons.Default.PlayArrow, selected = true, iconOnly = true,
                                enabled = playEnabled, modifier = Modifier.focusRequester(playRequester), onClick = play)
                            DiscoveryAction(if (isFavorite) "In My List" else "Add to My List",
                                if (isFavorite) Icons.Default.Check else Icons.Default.Add, iconOnly = true, onClick = favorite)
                            DiscoveryTrailerAction(metadata.card, iconOnly = true)
                            LocalProviderSynopsisActions.current?.invoke()

                        }
                        progressLabel?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.labelSmall) }
                        progressFraction?.let { fraction -> LinearProgressIndicator(progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFFE50925),
                            trackColor = Color(0xFF343842), drawStopIndicator = {}) }
                    }
                }
            }
            LocalProviderSynopsisPanel.current?.invoke()
            }
            }
        } else {
        Column(Modifier.fillMaxWidth().then(if (tabletSeriesOverlay) Modifier else Modifier.background(Color(0xFF090B10)))) {
            Box(Modifier.fillMaxWidth()) {
            if (!phone && !tabletSeriesOverlay) Box(Modifier.matchParentSize()) {
                // This screen owns the readability gradients. Applying the feed's
                // pre-shaded bitmap as well would hide the artwork under actions.
                DiscoveryBackdrop(metadata.card, reduceMotion, Alignment.TopCenter, shaded = false)
                if (type == CatalogType.MOVIES) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Color(0x99090B10), .55f to Color(0x66090B10), 1f to Color.Transparent)))
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Color(0x99090B10), .16f to Color(0x33090B10), .36f to Color.Transparent,
                    .64f to Color.Transparent, .84f to Color(0x33090B10), 1f to Color(0x99090B10))))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to Color(0xD9090B10), .3f to Color.Transparent,
                    .6f to Color(0x33090B10), .84f to Color(0x99090B10), 1f to Color(0xFF090B10))))
            }
            Column {
            if (!phone) toolbar()
            DiscoveryHero(metadata.card, null, heroHeight, landscape,
                compact = shortWide || (context.isTvLikeDevice(configuration) && configuration.screenHeightDp < 480), detail = true, loading = false,
                inList = isFavorite, playRequester = playRequester, infoRequester = remember { FocusRequester() },
                play = play, add = favorite, info = {}, onFocus = {},
                onDown = { if (type == CatalogType.SERIES) down() else if (related.isNotEmpty()) focus(relatedEntry) else if (people.isNotEmpty()) focus(castEntry) else down() },
                onUp = { if (wideSeries) { if (heroUp != null) { heroUp(); true } else runCatching { backRequester.requestFocus() }.getOrDefault(false) } else false },
                previous = {}, next = {}, carousel = false, index = 0, count = 0, playLabel = playLabel, playEnabled = playEnabled,
                portraitPhone = phone, phoneHeader = if (phone) toolbar else null, showSynopsis = true, compactDetail = true,
                categoryActions = ({
                    val extraActions = LocalProviderSynopsisActions.current
                    if (extraActions != null) extraActions()
                    else DetailMoreActions(cast = if (people.isNotEmpty()) ({ focus(castEntry) }) else null,
                        related = if (related.isNotEmpty()) ({ focus(relatedEntry) }) else null)
                    LocalProviderEpisodeControls.current?.invoke()
                }))
            progressLabel?.let {
                Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 20.dp else 32.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(it, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    progressFraction?.let { fraction -> LinearProgressIndicator(progress = { fraction },
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(3.dp),
                        color = Color(0xFFE50925), trackColor = Color(0xFF343842), drawStopIndicator = {}) }
                }
            }
            }
            }
            // Expanded panels must not change the artwork bounds or its fade positions.
            LocalProviderSynopsisPanel.current?.let { panel ->
                Column(Modifier.fillMaxWidth().padding(horizontal = if (wideSeries) 24.dp else 20.dp)) { panel() }
            }
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 24.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (failed) DiscoveryAction("Retry TMDB details", Icons.Default.Refresh, modifier = Modifier.padding(horizontal = 24.dp)) { retry++ }
            if (!loading && !failed && metadata.card.id <= 0) Text("TMDB details were not matched. You can still play this title.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        }
    }
    val castRow: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF090B10))) {
            if (people.isNotEmpty()) {
                if (type != CatalogType.SERIES) Text("Cast", Modifier.padding(start = 24.dp, top = 18.dp), color = Color.White, fontWeight = FontWeight.Bold)
                LazyRow(Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { if (castUp != null) castUp() else if (type == CatalogType.SERIES) down() else focus(if (related.isNotEmpty()) relatedEntry else playRequester); true }
                    else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        if (type == CatalogType.SERIES) { (episodeDown ?: down)(); true } else false
                    } else false
                }, contentPadding = if (type == CatalogType.MOVIES) PaddingValues(24.dp) else PaddingValues(start = if (phone) 14.dp else 24.dp, end = if (phone) 14.dp else 24.dp,
                    top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (type == CatalogType.MOVIES) 16.dp else 12.dp)) {
                    itemsIndexed(people, key = { _, person -> person.id!! }) { index, person ->
                        val requester = if (index == 0) castEntry else remember(person.id) { FocusRequester() }
                        DiscoveryCastTile(person, Modifier.focusRequester(requester)) {
                            actorOrigin = requester; actor = person
                        }
                    }
                }
            }
        }
    }
    val supplementary: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF090B10))) {
            if (related.isNotEmpty()) {
                if (type != CatalogType.SERIES) Text("Related", Modifier.padding(start = if (phone) 16.dp else 24.dp, top = 4.dp), color = Color.White,
                    fontSize = if (phone) 19.sp else 16.sp, lineHeight = if (phone) 24.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { if (relatedUp != null) relatedUp() else if (type == CatalogType.SERIES) down() else focus(playRequester); true }
                    else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { if (type == CatalogType.SERIES) (episodeDown ?: down)() else if (people.isNotEmpty()) focus(castEntry); true } else false
                }, contentPadding = PaddingValues(start = if (phone) 16.dp else 24.dp, end = if (phone) 16.dp else 24.dp, top = 10.dp, bottom = if (phone) 12.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 12.dp)) {
                    itemsIndexed(related, key = { _, card -> card.key }) { index, card ->
                        val requester = remember(card.key, index == 0) { if (index == 0) relatedEntry else FocusRequester() }
                        var alternateOpen by remember(card.key) { mutableStateOf(false) }
                        Box {
                        val posterWidth = if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp).dp
                            else if (type == CatalogType.MOVIES || shortWide) discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat()).dp else 112.dp
                        val subtitle = listOfNotNull(card.date?.take(4), card.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
                        val openRelated: () -> Unit = {
                            if (openDiscoveryDetail != null) openDiscoveryDetail(card)
                            else { relatedOrigin = requester; relatedDetail = card }
                        }
                        if (type == CatalogType.MOVIES && !phone) DiscoveryPosterTile(card.title, card.poster, subtitle, posterWidth,
                            source = DiscoveryTileSource.TMDB, reduceMotion = reduceMotion, modifier = Modifier.focusRequester(requester),
                            onClick = openRelated, onLongClick = { alternateOpen = true })
                        else DiscoveryPhoneMediaCard(card.title, card.poster, subtitle, posterWidth,
                            source = DiscoveryTileSource.TMDB,
                            wide = false, modifier = Modifier.focusRequester(requester),
                            onClick = openRelated, onLongClick = { alternateOpen = true }, onFocus = {})
                        AlternateSourceOnlyMenu(alternateOpen, card.media,
                            if (card.series) CatalogType.SERIES else CatalogType.MOVIES, dismiss = { alternateOpen = false })
                        }
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
    Box(Modifier.fillMaxSize().then(if (shortLandscapeSeries) Modifier.clipToBounds() else Modifier)) {
    if (tabletSeriesOverlay) {
        DiscoveryBackdropArtwork(metadata.card.backdrop ?: metadata.card.poster, reduceMotion,
            Alignment.TopCenter, shaded = false, fillWidth = true)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color(0x99090B10), .55f to Color(0x33090B10), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color(0x55090B10),
            (if (shortLandscapeSeries) .32f else .65f) to Color(if (shortLandscapeSeries) 0x66090B10 else 0x22090B10),
            1f to Color(if (shortLandscapeSeries) 0xFF090B10 else 0xBB090B10))))
    }
    CompositionLocalProvider(LocalMobileGlassEnabled provides true,
        LocalDetailActionGlass provides true,
        LocalProviderMoreInfo provides metadata.card.overview?.takeIf(String::isNotBlank)?.let { { compactInfoOpen = true } },
        LocalProviderInfoFocus provides synopsisRequester,
        LocalSeriesArtworkGlass provides (tabletSeriesOverlay && !shortLandscapeSeries)) {
    layout(hero, castRow, supplementary,
        if (people.isEmpty()) null else ({ runCatching { castEntry.requestFocus() }.getOrDefault(false) }),
        if (type != CatalogType.SERIES && related.isEmpty()) null else ({ runCatching { relatedEntry.requestFocus() }.getOrDefault(false) }))
    }
    }
    actor?.takeIf { relatedDetail == null }?.let { person ->
        actorPages.SaveableStateProvider("actor:${person.id}") {
            DiscoveryActorDialog(person, load = repository::actorCredits, loadInfo = repository::actorInfo,
                close = ::restoreActor, reduceMotion = reduceMotion, open = {
                    if (it.key == metadata.card.key) restoreActor()
                    else if (openDiscoveryDetail != null) { actor = null; openDiscoveryDetail(it) }
                    else relatedDetail = it
                })
        }
    }
    relatedDetail?.takeIf { navigation != null }?.let { card ->
        val nav = requireNotNull(navigation)
        Dialog(onDismissRequest = ::dismissRelated, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val vm: DiscoveryViewModel = viewModel(key = "provider-related:$profile:${item.id}:${card.key}")
            ProviderRelatedDetailRoute(profile, card, vm, close = ::dismissRelated) {
            DiscoveryPreviewScreen(app, if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES,
                selectSurface = { relatedDetail = null; close(); nav.openDiscovery(it) }, exit = { relatedDetail = null },
                navigate = { relatedDetail = null; close(); nav.navigate(it) },
                playCard = { selected -> relatedDetail = null; if (selected.series) openTmdbSeries(TrendingSeries(selected.show())) else openTmdbMovie(TrendingMovie(selected.movie())) },
                playEpisode = { selected, season, number -> relatedDetail = null; close(); nav.playEpisode(selected, season, number) },
                openRecent = nav.recent, openWatched = nav.watched,
                toggleFavorite = nav.favorite, openCategory = { relatedDetail = null; close(); nav.category(it) },
                openSection = { relatedDetail = null; close(); nav.section(it) }, removeRecent = nav.removeRecent,
                markWatched = nav.markWatched, dismissEpisode = nav.dismissEpisode, vm = vm, honorLandingNavigation = false)
            }
        }
    }
}

/** Wait for the selected detail to reach the UI before treating null as Back. The
 * initialization coroutine can complete before collectAsState publishes that detail. */
@Composable
internal fun ProviderRelatedDetailRoute(profile: String, card: DiscoveryCard, vm: DiscoveryViewModel,
    close: () -> Unit, content: @Composable () -> Unit) {
    val current by vm.state.collectAsState()
    var detailObserved by remember(vm, profile, card.key) { mutableStateOf(false) }
    val currentClose by rememberUpdatedState(close)
    LaunchedEffect(vm, profile, card.key) {
        vm.bind(profile, if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES, emptyList())
        // The ViewModel survives dialog disposal. Reopening a credit is a new
        // root visit, not another copy of the old detail on its retained stack.
        vm.openRootDetails(card)
    }
    LaunchedEffect(vm, profile, card.key, current.detail?.key) {
        if (current.detail != null) detailObserved = true
        else if (detailObserved) currentClose()
    }
    content()
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
