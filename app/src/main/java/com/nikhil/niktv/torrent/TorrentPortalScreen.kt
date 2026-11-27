package com.nikhil.niktv.torrent

import android.view.KeyEvent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.discovery.components.FullDescriptionDialog
import com.nikhil.niktv.discovery.components.ModernEpisodeCard
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.tmdbCategoryPairs
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentPortalScreen(model: TorrentPortalViewModel, profile: String, onProfiles: () -> Unit, onClose: () -> Unit,
    portalEntryFocus: FocusRequester? = null) {
    val state by model.state.collectAsState()
    val configuration = LocalConfiguration.current
    val television = LocalContext.current.isTvLikeDevice(configuration)
    val phone = configuration.usesPhoneLayout(television)
    val short = configuration.usesShortWideLayout(television)
    var manual by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf(false) }
    var browseMenu by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchActivationState = remember { PortalSearchActivation() }
    var searchActivation by remember { mutableIntStateOf(0) }
    var searchOwnsFocus by remember { mutableStateOf(false) }
    var searchOriginSurface by remember { mutableStateOf(state.surface) }
    var presentedCard by remember(state.surface) { mutableStateOf<DiscoveryCard?>(null) }
    var feedFocused by remember { mutableStateOf(false) }
    var sourceOptions by remember { mutableStateOf<TorrentSource?>(null) }
    var libraryOptions by remember { mutableStateOf<Pair<TorrentLibraryEntry, String>?>(null) }
    val portals = LocalDirectPortalActions.current
    val viewer = LocalSelectedUserProfile.current
    val viewerName = viewer?.name ?: profile
    val viewerAvatar = viewer?.avatar?.ifBlank { viewer.name.take(1).uppercase() }
        ?: profile.take(1).ifBlank { "N" }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val homeFocusRestorer = remember(state.surface) { NativeDiscoveryFocusRestorer() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeVisible = WindowInsets.isImeVisible
    val parking = remember { DiscoveryFocusParking() }
    val parkingRequester = remember { FocusRequester() }
    var enteringRoute by remember { mutableStateOf(false) }
    var explicitEntry by remember { mutableStateOf<String?>(null) }
    var focusedTitle by remember { mutableStateOf<String?>(null) }
    var focusedHome by remember(state.surface) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var bottomChrome by remember { mutableStateOf(0.dp) }
    val chromeDensity = LocalDensity.current
    var glassSource by remember { mutableStateOf<MobileGlassSource?>(null) }
    val publishGlass = remember { { source: MobileGlassSource? -> glassSource = source } }

    // The lists keep their own state across title/source/modal changes, and the
    // activity's saveable owner retains it while the player covers this screen.
    val titlesScroll = rememberLazyGridState()
    val libraryScroll = rememberLazyGridState()
    val homeSavedState = rememberSaveableStateHolder()
    val episodesScroll = rememberLazyGridState()
    val sourcesScroll = rememberLazyListState()
    val downloadsScroll = rememberLazyListState()
    val transferOpen = state.transfer != null || state.files.isNotEmpty()
    val browsingFeeds = state.title == null && state.browsingFeeds
    val homeHeroes = remember(state.homeData.shelves, torrentCurrentDate()) { state.homeData.heroes }
    val homeHero = homeHeroes.getOrNull(state.homeData.heroIndex) ?: homeHeroes.firstOrNull()
    val reduceMotion = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val libraryEntries = if (state.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING) state.library.continueWatching else state.library.saved
    val myListSelected = state.libraryPage == TorrentLibraryPage.MY_LIST && !state.downloadsOpen
    val browseTab = state.surface.takeUnless { myListSelected }
    val routeEntry = state.returnFocus ?: explicitEntry ?: when {
        state.title?.series == true && state.sources == null -> "season"
        state.title?.series == false -> "movie-sources"
        state.title != null -> "details-back"
        state.downloadsOpen -> "downloads-back"
        state.libraryPage != null -> "library-back"
        state.homeCollection != null -> "collection-back"
        browsingFeeds && homeHero != null -> "home:hero"
        else -> "search"
    }
    val entryPending = !transferOpen && (state.returnFocus != null || explicitEntry != null || (!view.isInTouchMode && enteringRoute))
    val entryPolicy = remember(routeEntry, entryPending) { InitialFocusEntry() }
    fun requestRouteEntry(): Boolean {
        if (searchOwnsFocus || !entryPending || !windowFocused || routeEntry == "search" && imeVisible) return false
        val targetKey = when {
            browsingFeeds && state.title == null && routeEntry.startsWith("home:") && routeEntry != "home:hero" -> "home:native"
            phone && myListSelected && routeEntry.startsWith("library:") -> "library-native"
            else -> routeEntry
        }
        val target = requesters[targetKey] ?: return false
        // onPlaced and SideEffect can still hold the previous composition's
        // pending target after a successful handoff. They must share the same
        // one-shot owner as onEnter, rather than reclaiming the old tile later.
        return entryPolicy.request {
            val success = if (targetKey == "home:native") homeFocusRestorer.restore()
                else runCatching { target.requestFocus() }.getOrDefault(false)
            traceTorrentFocus { "entry target=$targetKey success=$success explicit=$explicitEntry returning=${state.returnFocus}" }
            if (success) {
                enteringRoute = false
                explicitEntry = null
                if (state.returnFocus != null) model.focusRestored()
            }
            success
        }
    }
    fun entryModifier(key: String) = Modifier.focusRequester(requester(key)).onPlaced {
        if (entryPending && routeEntry == key) requestRouteEntry()
    }.onFocusChanged { traceTorrentFocus { "focus key=$key focused=${it.isFocused} pending=$entryPending entry=$routeEntry" } }
    fun navigate(action: () -> Unit) {
        searchOwnsFocus = false
        explicitEntry = null
        if (!view.isInTouchMode) {
            enteringRoute = true
            // The old tile is about to unmount. Retain focus in the same
            // non-navigable handoff owner used by discovery until the new
            // route's real target attaches, so Profile cannot become a fallback.
            parking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
        }
        action()
    }
    fun handoff(key: String, action: () -> Unit) {
        traceTorrentFocus { "handoff target=$key touch=${view.isInTouchMode}" }
        searchOwnsFocus = false
        if (!view.isInTouchMode) {
            explicitEntry = key
            enteringRoute = true
            parking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
        }
        action()
    }
    fun finishSearch(collapse: Boolean = true) {
        searchOwnsFocus = false
        // A visible touch editor keeps ownership after the IME closes.
        // Parking focus here races the user's next tap with header restoration.
        if (!view.isInTouchMode) {
            explicitEntry = "search"
            enteringRoute = true
            parking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
        }
        if (collapse) searchExpanded = false
    }
    fun back() {
        if (state.title == null && (searchExpanded || state.query.isNotBlank())) {
            finishSearch()
            if (state.query.isNotBlank()) {
                if (state.homeCollection != null || state.libraryPage != null || state.downloadsOpen) model.query("")
                else model.cancelSearch(searchOriginSurface)
            }
        }
        else navigate { if (!model.back()) onClose() }
    }
    fun search() {
        if (!searchExpanded) searchOriginSurface = state.surface
        searchExpanded = true; searchActivation++
    }
    fun editSearch() {
        // Explicit editing supersedes restoration to the result that opened
        // details, including a result which is still outside the lazy viewport.
        searchOwnsFocus = true
        enteringRoute = false
        explicitEntry = null
        model.focusRestored()
    }
    fun openRoot(surface: DashboardSurface) {
        searchOwnsFocus = false
        searchOriginSurface = surface
        searchExpanded = false
        if (surface == DashboardSurface.HOME) model.home() else model.type(surface == DashboardSurface.SERIES)
    }
    fun openMyList() {
        searchOwnsFocus = false
        searchExpanded = false
        model.openLibraryPage(TorrentLibraryPage.MY_LIST, "my-list-tab")
    }
    fun closeCategories() {
        runCatching { requester("tmdb-categories").requestFocus() }
        categories = false
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Short); model.dismissNotice() }
    }
    BackHandler(onBack = ::back)
    val searchResults = state.titles.takeIf { state.homeCollection == null }
    LaunchedEffect(state.homeCollection?.key, searchResults) {
        // A collection appends pages to the same grid. Only a new destination
        // or a replacement set of search results should reset its scroll.
        if (state.title == null && state.returnFocus == null) titlesScroll.requestScrollToItem(0)
    }
    LaunchedEffect(state.season) { episodesScroll.requestScrollToItem(0) }
    LaunchedEffect(state.directSearchRevision) {
        if (state.directSearchRevision > 0) sourcesScroll.requestScrollToItem(0)
    }
    LaunchedEffect(state.title?.key, state.episode?.seasonNumber, state.episode?.episodeNumber) {
        if (state.returnFocus == null) sourcesScroll.requestScrollToItem(0)
    }
    LaunchedEffect(state.returnFocus, explicitEntry, state.loading, state.playing, transferOpen) {
        val key = state.returnFocus ?: explicitEntry ?: return@LaunchedEffect
        if (state.playing != null || state.loading != null || transferOpen) return@LaunchedEffect
        // A lazy origin below the first viewport must be composed before focus
        // can return. The preserved list normally already owns this position.
        val index = when {
            key.startsWith("source:") -> (if (state.title == null) state.directSources else state.sources)?.results?.indexOfFirst { "source:${it.infoHash}" == key }
                ?.takeIf { it >= 0 }?.plus(if (state.title == null) 0 else if (state.title?.series == false || phone) 1 else 0) ?: -1
            key.startsWith("episode:") -> state.episodes.indexOfFirst { "episode:${it.seasonNumber}:${it.episodeNumber}" == key }
            key.startsWith("title:") -> state.titles.indexOfFirst { "title:${it.key}" == key }
            key.startsWith("library:") -> libraryEntries.indexOfFirst { "library:${state.libraryPage?.name}:${it.key}" == key }
                .let { if (it >= 0 && phone && myListSelected) it + 2 else it }
            key.startsWith("download:") -> state.downloads.indexOfFirst { "download:${it.key}" == key }
            else -> -1
        }
        if (index >= 0) when {
            key.startsWith("source:") && sourcesScroll.layoutInfo.visibleItemsInfo.none { it.index == index } -> sourcesScroll.scrollToItem(index)
            key.startsWith("episode:") && episodesScroll.layoutInfo.visibleItemsInfo.none { it.index == index } -> episodesScroll.scrollToItem(index)
            key.startsWith("title:") && titlesScroll.layoutInfo.visibleItemsInfo.none { it.index == index } -> titlesScroll.scrollToItem(index)
            key.startsWith("library:") && libraryScroll.layoutInfo.visibleItemsInfo.none { it.index == index } -> libraryScroll.scrollToItem(index)
            key.startsWith("download:") && downloadsScroll.layoutInfo.visibleItemsInfo.none { it.index == index } -> downloadsScroll.scrollToItem(index)
        }
    }
    // Attachment-driven requests run before drawing. They complement onEnter
    // when the requested lazy item has just become visible after restoration.
    SideEffect {
        if (view.isInTouchMode) enteringRoute = false
        requestRouteEntry()
    }
    LaunchedEffect(windowFocused, imeVisible, entryPending, routeEntry) {
        if (windowFocused && !imeVisible) requestRouteEntry()
    }

    val header: @Composable () -> Unit = {
        val searchButton: @Composable () -> Unit = {
            DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, focusHint = false,
                modifier = entryModifier("search"),
                onClick = { if (phone && searchExpanded) model.search() else search() })
        }
        val tools: @Composable (Modifier) -> Unit = { menuModifier ->
            Box {
                PortalOptionsButton("Torrent options", menuModifier.then(entryModifier("tmdb-categories")).then(entryModifier("downloads")).then(entryModifier("manual")),
                    selected = browseMenu) { browseMenu = true }
                PortalOptionsMenu(browseMenu, { browseMenu = false }) {
                    NikDropdownMenuItem(text = { Text("Open magnet or torrent") }, leadingIcon = { Icon(Icons.Default.Link, null) },
                        onClick = { browseMenu = false; manual = true })
                    NikDropdownMenuItem(text = { Text("Search titles (TMDB)") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (!state.advancedSearch) ({ Icon(Icons.Default.Check, null) }) else null,
                        onClick = { browseMenu = false; model.advancedSearch(false); search() })
                    NikDropdownMenuItem(text = { Text("Search torrent websites") },
                        leadingIcon = { Icon(Icons.Default.ManageSearch, null) },
                        trailingIcon = if (state.advancedSearch) ({ Icon(Icons.Default.Check, null) }) else null,
                        onClick = { browseMenu = false; model.advancedSearch(true); search() })
                    NikDropdownMenuItem(text = { Text("TMDB categories") }, leadingIcon = { Icon(Icons.Default.Category, null) },
                        onClick = { browseMenu = false; categories = true })
                    NikDropdownMenuItem(text = { Text("Torrent downloads") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { browseMenu = false; navigate(model::openDownloads) })
                }
            }
        }
        Box(Modifier.onFocusChanged { if (it.hasFocus) feedFocused = false }) {
            if (phone) PortalPhoneHeader {
                DiscoveryAction("Switch profile: $viewerName", visualLabel = viewerAvatar,
                    containerColor = viewer?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
                    horizontalPadding = 0.dp,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Switch profile: $viewerName" },
                    onClick = onProfiles)
                TorrentTextField(state.query, model::query, if (state.advancedSearch) "Torrent websites" else when (state.surface) {
                        DashboardSurface.MOVIES -> "Search movies"
                        DashboardSurface.SERIES -> "Search series"
                        else -> "Movies or series"
                    },
                    Modifier.weight(1f).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (searchExpanded) placeable.placeRelative(0, 0)
                        }
                    }, keyboardType = KeyboardType.Text, activationRequest = searchActivation,
                    editFinished = { finishSearch(collapse = false) }, editRequested = ::editSearch,
                    activationState = searchActivationState, submit = model::search)
                searchButton()
                tools(Modifier)
            } else PortalWideNavigation(profile, onProfiles, ::back,
                items = listOf(
                    PortalMenuItem("Home", Icons.Default.Home, browseTab == DashboardSurface.HOME) { openRoot(DashboardSurface.HOME) },
                    PortalMenuItem("Movies", Icons.Default.Movie, browseTab == DashboardSurface.MOVIES) { openRoot(DashboardSurface.MOVIES) },
                    PortalMenuItem("Series", Icons.Default.Tv, browseTab == DashboardSurface.SERIES) { openRoot(DashboardSurface.SERIES) },
                    PortalMenuItem("My List", Icons.Default.VideoLibrary, myListSelected,
                        entryModifier("my-list-tab").focusProperties { if (myListSelected) down = requester("library-back") }, ::openMyList)),
                backModifier = if (state.title != null) entryModifier("details-back") else Modifier,
                searchModifier = entryModifier("search"), searchOpen = searchExpanded && state.title == null,
                openSearch = { if (state.title != null) { model.home(); search() } else if (searchExpanded) model.search() else search() },
                closeSearch = {
                    searchOwnsFocus = false; searchExpanded = false
                    if (state.homeCollection != null || state.libraryPage != null || state.downloadsOpen) model.query("")
                    else model.cancelSearch(searchOriginSurface)
                }, query = { fieldModifier ->
                    TorrentTextField(state.query, model::query, if (state.advancedSearch) "Search torrent websites" else when (state.surface) {
                        DashboardSurface.MOVIES -> "Search movies"; DashboardSurface.SERIES -> "Search series"; else -> "Search movies or series"
                    }, fieldModifier, KeyboardType.Text, activationRequest = searchActivation,
                        editFinished = { searchOwnsFocus = false }, editRequested = ::editSearch, compact = true,
                        activationState = searchActivationState, submit = model::search)
                }, options = tools)
        }
    }
    CompositionLocalProvider(LocalPublishMobileGlassSource provides publishGlass,
        LocalPhoneBottomChromeHeight provides bottomChrome,
        LocalMobileGlassArtwork provides glassSource?.artwork,
        LocalMobileGlassBounds provides (glassSource?.bounds ?: androidx.compose.ui.geometry.Rect.Zero),
        LocalMobileGlassMovingBounds provides glassSource?.movingBounds,
        LocalMobileGlassFit provides (glassSource?.fit ?: false),
        LocalMobileGlassTopAligned provides (glassSource?.topAligned ?: false),
        LocalMobileGlassFillWidth provides (glassSource?.fillWidth ?: false)) {
    Box(Modifier.fillMaxSize()) {
    if (!phone && (browsingFeeds || state.title != null)) {
        DiscoveryBackdrop(state.title ?: presentedCard ?: homeHero, reduceMotion = reduceMotion)
        if (state.title != null) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .25f to Color.Transparent, .46f to Color(0x40090B10),
            .75f to Color(0xB3090B10), 1f to Color(0xFF090B10))))
    }
    Box(Modifier.size(1.dp).focusRequester(parkingRequester)
        .focusProperties { canFocus = parking.canFocus }
        .onFocusChanged { parking.focusChanged(it.isFocused) }.focusTarget()
        .clearAndSetSemantics { hideFromAccessibility() })
    Column(Modifier.fillMaxSize().safeDrawingPadding().focusProperties {
        onEnter = {
            if (!searchOwnsFocus && entryPending && !requestRouteEntry()) cancelFocusChange()
        }
    }.focusGroup()) {
        if (!phone) header()
        val phoneDetails = phone && state.title != null
        Column(Modifier.weight(1f).fillMaxWidth()
            .padding(top = if (phone && state.title == null && !browsingFeeds && !myListSelected) 64.dp else 0.dp)
            .padding(horizontal = if (phoneDetails || state.title?.series == false || (phone && myListSelected) || (browsingFeeds && state.title == null)) 0.dp
            else if (phone) 16.dp else if (short) 12.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (browsingFeeds && !searchExpanded && !phoneDetails) 0.dp else if (short) 6.dp else 10.dp)) {
            if (state.title == null) {
                if (state.downloadsOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TorrentButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.size(40.dp).then(entryModifier("downloads-back")),
                            iconOnly = true, onClick = ::back)
                        Text("Torrent downloads", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.downloads.isEmpty() && state.fileDownloads.isEmpty()) Text("No torrent downloads yet. Touch and hold a source to download it for later.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = .65f))
                    state.loading?.let { TorrentLoading(it) }
                    state.error?.let { TorrentError(it, model::openDownloads) }
                    LazyColumn(Modifier.weight(1f), state = downloadsScroll, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = if (phone) bottomChrome + 16.dp else 16.dp)) {
                        items(state.downloads, key = { it.key }) { download ->
                            val info = state.downloadInfo[download.key]
                            val complete = info?.status == OfflineDownloadStatus.COMPLETE
                            Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .055f)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    download.media.logo?.let { AsyncImage(it, null, Modifier.size(64.dp, 80.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(download.series?.title ?: download.media.title, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (download.series != null) Text(download.media.title, fontSize = 12.sp, lineHeight = 16.sp, color = Color.LightGray)
                                        Text(info?.status?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Checking download…", fontSize = 11.sp, color = Color.LightGray)
                                        info?.percent?.let { percent ->
                                            if (!complete) LinearProgressIndicator(progress = { (percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    TorrentButton("Play download", Icons.Default.PlayArrow, Modifier.size(40.dp).then(entryModifier("download:${download.key}")),
                                        iconOnly = true, enabled = complete, onClick = { navigate { model.playDownload(download) } })
                                    TorrentButton("Delete download", Icons.Default.Delete, Modifier.size(40.dp), iconOnly = true, onClick = {
                                        runCatching { requester("downloads-back").requestFocus() }; model.removeDownload(download)
                                    })
                                }
                            }
                        }
                        items(state.fileDownloads, key = { "file:${it.id}" }) { file ->
                            val complete = file.state == "complete"
                            Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .055f)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.Description, null, Modifier.size(28.dp), tint = Color.LightGray)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(file.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                        Text(file.error ?: "${file.state.replaceFirstChar(Char::uppercase)} · ${torrentSize(file.bytes)} / ${torrentSize(file.sizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                                        if (!complete && file.state != "failed") LinearProgressIndicator(
                                            progress = { (file.bytes.toDouble() / file.sizeBytes).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                    TorrentButton("Open file", Icons.Default.OpenInNew, Modifier.size(40.dp), iconOnly = true,
                                        enabled = complete, onClick = { model.openFileDownload(file) })
                                    TorrentButton("Delete file", Icons.Default.Delete, Modifier.size(40.dp), iconOnly = true,
                                        onClick = { runCatching { requester("downloads-back").requestFocus() }; model.removeFileDownload(file) })
                                }
                            }
                        }
                    }
                } else if (state.libraryPage != null) {
                    if (phone && myListSelected) {
                        TorrentPhoneLibrary(libraryEntries, reduceMotion, ::requester,
                            open = { entry, origin ->
                                navigate {
                                    val card = entry.card
                                    if (card == null) model.resumeLibrary(entry, origin) else model.open(card, origin)
                                }
                            },
                            options = { entry, origin -> libraryOptions = entry to origin })
                    } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TorrentButton("Back", Icons.AutoMirrored.Filled.ArrowBack,
                            Modifier.size(40.dp).then(entryModifier("library-back")), iconOnly = true, onClick = ::back)
                        Text(if (state.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING) "Continue Watching" else "My List",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (libraryEntries.isEmpty()) Text(
                        if (myListSelected) "Use + on a title to add it to My List." else "No titles here yet.",
                        color = Color.White.copy(alpha = .65f), fontSize = 13.sp)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val columns = when {
                            phone -> 2
                            short -> kotlin.math.ceil(((maxWidth + 10.dp) /
                                ((maxHeight - 16.dp).coerceAtLeast(72.dp) * 2f / 3f + 10.dp)).toDouble()).toInt().coerceIn(4, 12)
                            else -> 7
                        }
                        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().focusRestorer(), state = libraryScroll,
                            contentPadding = PaddingValues(top = 4.dp, bottom = if (phone) bottomChrome + 16.dp else 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(libraryEntries, key = { it.key }) { entry ->
                                val key = "library:${state.libraryPage?.name}:${entry.key}"
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    TorrentLibraryPoster(entry.title, entry.poster ?: entry.backdrop, entry.subtitle, maxWidth,
                                        modifier = entryModifier(key), progress = if (state.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING) entry.progress else null,
                                        badge = entry.episodeBadge(), onLongClick = { libraryOptions = entry to key }, onClick = {
                                            val card = entry.card
                                            navigate { if (state.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING || card == null)
                                                model.resumeLibrary(entry, key) else model.open(card, key) }
                                        })
                                }
                            }
                        }
                    }
                    }
                } else if (state.advancedSearch && state.directSources != null) {
                    val results = requireNotNull(state.directSources)
                    Text("Torrent websites · ${torrentSearchScope(state.surface).label}",
                        color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    if (results.pending.isNotEmpty()) TorrentLoading("Searching ${results.pending.joinToString(" · ")}")
                    else Text(if (results.results.isEmpty()) "No torrents found."
                        else "${results.results.size} torrents · Search finished",
                        color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    state.error?.let { TorrentError(it, model::search) }
                    LazyColumn(Modifier.weight(1f).focusRestorer(), state = sourcesScroll,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = if (phone) bottomChrome + 16.dp else 20.dp)) {
                        items(results.results, key = { it.infoHash }) { source ->
                            TorrentListCard(source.title,
                                listOfNotNull(source.kind.label, source.quality, torrentSize(source.sizeBytes),
                                    "${source.seeders} seeders", source.provider).joinToString(" · "),
                                entryModifier("source:${source.infoHash}"),
                                actionIcon = if (source.kind.media && source.playable) Icons.Default.PlayArrow else Icons.Default.Download,
                                onClick = { if (source.kind.media && source.playable) model.source(source) else model.download(source) },
                                onLongClick = { sourceOptions = source })
                        }
                        if (results.errors.isNotEmpty()) item("direct-errors") {
                            Text(results.errors.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                        if (results.finished) item("direct-search-again") {
                            TorrentButton("Search again", Icons.Default.Refresh, Modifier.height(40.dp), onClick = model::search)
                        }
                    }
                } else if (browsingFeeds) {
                    homeSavedState.SaveableStateProvider("torrent-home:${state.surface}") {
                    val latestNavigate by rememberUpdatedState<(() -> Unit) -> Unit>(::navigate)
                    val actions = remember(model) { TorrentHomeActions(
                        openTitle = { card, key -> latestNavigate { model.open(card, key) } },
                        openCollection = { target, key -> latestNavigate { model.homeCollection(target, key) } },
                        hero = model::homeHero, shelf = model::homeShelf, genres = model::homeGenres,
                        providers = model::homeProviders, genreArtwork = model::homeGenreArtwork,
                        toggleSaved = model::toggleSaved,
                        resumeLibrary = { entry, key -> latestNavigate { model.resumeLibrary(entry, key) } },
                        libraryPage = { page, key -> latestNavigate { model.openLibraryPage(page, key) } },
                        libraryOptions = { entry, key -> libraryOptions = entry to key }) }
                    TorrentHomeContent(state.homeData, state.library, actions,
                        TorrentHomeFocus(::entryModifier, ::requester,
                            placed = { key -> if (entryPending && routeEntry == key) requestRouteEntry() },
                            focused = { focusedHome = it; feedFocused = true }, handoff = ::handoff),
                        state.returnFocus ?: explicitEntry, Modifier.weight(1f),
                        configuration = state.discoveryConfiguration, series = state.surface == DashboardSurface.SERIES,
                        showLibrary = state.surface == DashboardSurface.HOME, displayed = presentedCard,
                        presentation = { presentedCard = it }, contentFocused = feedFocused,
                        popup = browseMenu || categories || configure || manual || libraryOptions != null || sourceOptions != null,
                        nativeFocusRestorer = homeFocusRestorer,
                        reduceMotion = reduceMotion)
                    }
                } else {
                state.homeCollection?.let { collection ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TorrentButton("Back", Icons.AutoMirrored.Filled.ArrowBack,
                            Modifier.size(40.dp).then(entryModifier("collection-back")), iconOnly = true, onClick = ::back)
                        Text(collection.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                }
                val pagingCollection = state.homeCollection != null && state.collectionPage > 0
                if (state.loading != null && !transferOpen && !pagingCollection) TorrentLoading(state.loading!!)
                if (!transferOpen && !pagingCollection) state.error?.let {
                    TorrentError(it, if (state.homeCollection != null) model::retryHomeCollection else model::search)
                }
                if (state.titles.isEmpty() && state.loading == null && state.error == null) Text(if (state.advancedSearch) "Enter a query to search torrent websites." else "No titles found. Try another title or year.", color = Color.White.copy(alpha = .7f))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val posterColumns = when {
                    phone -> 2
                    short -> {
                        val maxPosterWidth = (maxHeight - 16.dp).coerceAtLeast(72.dp) * 2f / 3f
                        kotlin.math.ceil(((maxWidth + 10.dp) / (maxPosterWidth + 10.dp)).toDouble()).toInt().coerceIn(4, 12)
                    }
                    else -> 7
                }
                val skeletonWidth = (maxWidth - 10.dp * (posterColumns - 1)) / posterColumns
                val skeletonWidthPx = with(LocalDensity.current) { skeletonWidth.roundToPx() }
                LazyVerticalGrid(GridCells.Fixed(posterColumns), Modifier.fillMaxSize().focusRestorer(), state = titlesScroll,
                    contentPadding = PaddingValues(top = 4.dp, bottom = if (phone) bottomChrome + 20.dp else if (short) 12.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.titles.isEmpty() && state.loading != null) {
                        items(2, key = { "titles-loading:$it" }, span = { GridItemSpan(maxLineSpan) }) {
                            key(skeletonWidthPx) {
                                androidx.compose.ui.viewinterop.AndroidView(
                                    factory = { DiscoveryRowSkeleton(it, skeletonWidthPx) },
                                    modifier = Modifier.fillMaxWidth().height(skeletonWidth * 1.5f)
                                        .semantics { contentDescription = "Loading titles" })
                            }
                        }
                    }
                    items(state.titles, key = { it.key }) { card ->
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val subtitle = listOfNotNull(if (card.series) "Series" else "Movie", card.date?.take(4)).joinToString(" · ")
                            val open = { navigate { model.open(card) } }
                            val focused = { focusedTitle = card.key }
                            val modifier = entryModifier("title:${card.key}")
                            if (phone) DiscoveryPhoneMediaCard(card.title, card.poster, subtitle, maxWidth, wide = false,
                                modifier = modifier, onClick = open, onLongClick = null, onFocus = focused)
                            else DiscoveryPosterTile(card.title, card.poster, subtitle, width = maxWidth,
                                modifier = modifier, reduceMotion = true, onFocus = focused, onClick = open)
                        }
                    }
                    if (state.homeCollection != null && state.collectionHasMore) item(span = { GridItemSpan(maxLineSpan) }, key = "collection-more") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val label = when {
                                state.loading != null -> "Loading more…"
                                state.error != null -> "Retry load more"
                                else -> "Load more"
                            }
                            TorrentButton(label, Icons.Default.ExpandMore, Modifier.size(164.dp, 40.dp),
                                onClick = model::loadMoreHome)
                            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                }
                }
            } else {
                val title = requireNotNull(state.title)
                val showingEpisodes = title.series && state.sources == null
                val inList = state.library.isSaved(title)
                val toggleSaved = { model.toggleSaved(title) }
                val openMagnet = { manual = true }
                if (!phone && title.series) TorrentTitleHeader(title, state.episode, short, onOpenMagnet = openMagnet,
                    inList = inList, toggleSaved = toggleSaved, manualModifier = entryModifier("manual"))
                if (phone && title.series) {
                    val firstEpisode = state.episodes.firstOrNull()
                    fun showSources() {
                        val first = state.sources?.results?.firstOrNull()
                        if (first != null && !view.isInTouchMode) handoff("source:${first.infoHash}") {
                            sourcesScroll.requestScrollToItem(1)
                        } else sourcesScroll.requestScrollToItem(0)
                    }
                    TorrentPhoneDetailHero(title, state.episode, state.loading != null, reduceMotion,
                        inList, toggleSaved, viewerName, viewerAvatar, viewer?.let { Color(it.color) },
                        onProfiles, ::back, openMagnet, entryModifier("details-back"), entryModifier("manual"),
                        playLabel = if (showingEpisodes) "Play first episode" else "Sources",
                        enabled = !showingEpisodes || firstEpisode != null,
                        play = { if (showingEpisodes) firstEpisode?.let { navigate { model.episode(it) } } else showSources() },
                        down = { if (showingEpisodes) runCatching { requester("season").requestFocus() } else showSources() })
                }
                if (showingEpisodes) {
                    Column(Modifier.weight(1f).then(if (phone) Modifier.padding(horizontal = 14.dp) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var seasonsOpen by remember(state.title!!.key) { mutableStateOf(false) }
                        val seasonFocus = requester("season")
                        val selectedSeasonFocus = remember(state.season) { FocusRequester() }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val hasPrefetch = LocalViewingOwner.current != null
                        val prefetchWidth = minOf(168.dp, (maxWidth - 12.dp).coerceAtLeast(0.dp) * .56f)
                        val seasonWidth = if (hasPrefetch) minOf(132.dp, (maxWidth - 12.dp - prefetchWidth).coerceAtLeast(0.dp))
                            else minOf(132.dp, maxWidth)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            if (phone) Box(entryModifier("season")) {
                                SeriesSeasonDropdown(state.seasons, state.season, seasonFocus,
                                    choose = { if (it != state.season) model.season(it) })
                            } else Box(Modifier.width(seasonWidth)) {
                                TorrentButton(state.season?.let { "Season $it" } ?: "Seasons", Icons.Default.ExpandMore,
                                    Modifier.width(seasonWidth).height(40.dp).then(entryModifier("season")),
                                    onClick = { if (state.seasons.isNotEmpty()) seasonsOpen = true })
                                if (seasonsOpen) {
                                    PortalOptionsMenu(true, { seasonsOpen = false }) {
                                        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { selectedSeasonFocus.requestFocus() } }
                                        state.seasons.forEach { season ->
                                            DropdownMenuItem(text = { Text("Season $season") },
                                                modifier = if (season == state.season) Modifier.focusRequester(selectedSeasonFocus) else Modifier,
                                                onClick = { seasonFocus.requestFocus(); seasonsOpen = false; model.season(season) })
                                        }
                                    }
                                }
                            }
                            if (hasPrefetch) TorrentButton(
                                "Prefetch next · ${if (state.prefetchEnabled) "On" else "Off"}",
                                if (state.prefetchEnabled) Icons.Default.Check else Icons.Default.Remove,
                                Modifier.width(prefetchWidth).height(40.dp).semantics { selected = state.prefetchEnabled },
                                onClick = { model.prefetch(!state.prefetchEnabled) })
                        }
                        }
                        Text("${state.episodes.size} episodes", color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium)
                        state.nextStatus?.let { Text(it, color = Color.White.copy(alpha = .55f), fontSize = 11.sp, lineHeight = 14.sp) }
                        if (state.loading != null) TorrentLoading(state.loading!!)
                        state.error?.let { TorrentError(it) { state.season?.let(model::season) } }
                        if (state.episodes.isEmpty() && state.loading == null && state.error == null) Text("No episodes available for this season.")
                        LazyVerticalGrid(GridCells.Fixed(if (phone) 1 else 2), Modifier.weight(1f).focusRestorer(), state = episodesScroll,
                            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)) {
                            items(state.episodes, key = { "${it.seasonNumber}:${it.episodeNumber}" }) { episode ->
                                val key = "episode:${episode.seasonNumber}:${episode.episodeNumber}"
                                val open = { navigate { model.episode(episode) } }
                                if (phone) {
                                    val series = title.media
                                    val saved = state.library.continueWatching.firstOrNull {
                                        it.tmdbId == title.id && it.series && it.season == episode.seasonNumber && it.episode == episode.episodeNumber
                                    }
                                    val media = series.copy(id = "${title.key}:$key",
                                        title = episode.name?.takeIf(String::isNotBlank) ?: "Episode ${episode.episodeNumber}",
                                        logo = episode.stillUrl, description = episode.overview,
                                        seasonNumber = episode.seasonNumber, episodeNumber = episode.episodeNumber, episodeAirDate = episode.airDate)
                                    ModernEpisodeCard(media, series,
                                        progress = saved?.let { PlaybackProgress(it.key, it.positionMillis, it.durationMillis) },
                                        isCurrentResume = saved != null, onClick = open, offlineDownload = null, offlineRevision = 0,
                                        onDownload = null, onRemoveDownload = null, modifier = entryModifier(key),
                                        showAlternateSources = false)
                                } else TorrentListCard("S${episode.seasonNumber}:E${episode.episodeNumber} · ${episode.name.orEmpty()}",
                                    episode.airDate.orEmpty(), entryModifier(key), artwork = episode.stillUrl, uniform = true, onClick = open)
                            }
                        }
                    }
                } else {
                    val sources = state.sources
                    val resultStatus: @Composable () -> Unit = {
                        if (state.loading != null && !transferOpen) TorrentLoading(sources?.pending?.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Searching ", separator = " · ") ?: state.loading!!)
                        if (!transferOpen) state.error?.let { TorrentError(it, model::sources) }
                        if (sources != null && sources.finished && (state.loading == null || transferOpen)) {
                            val providerCount = TorrentSearchProvider.entries.count { state.title?.series != true || it != TorrentSearchProvider.TORRENTCLAW }
                            val allFailed = sources.errors.size >= providerCount
                            Text(if (sources.results.isEmpty() && allFailed) "Sources could not be checked. Try again or open a magnet link."
                                else if (sources.results.isEmpty() && sources.errors.isNotEmpty()) "No seeded sources returned. Some sources were unavailable."
                                else if (sources.results.isEmpty()) "No seeded sources found. Try again or open a magnet link."
                                else "${sources.results.size} sources · Search finished", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .65f))
                        }
                    }
                    if (!phone && title.series) resultStatus()
                    LazyColumn(Modifier.weight(1f).focusRestorer(), state = sourcesScroll,
                        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        if (!title.series) {
                            item("movie-details") {
                                TorrentMovieDetails(title, inList, toggleSaved, ::back,
                                    showBack = phone, playFocus = requester("movie-sources"),
                                    backFocus = requester("details-back"), magnetFocus = requester("manual"), openMagnet = openMagnet,
                                    openRelated = { related -> navigate { model.openRelated(related) } },
                                    sources = {
                                        val first = state.sources?.results?.firstOrNull()
                                        if (first != null && !view.isInTouchMode) handoff("source:${first.infoHash}") {
                                            sourcesScroll.requestScrollToItem(1)
                                        } else sourcesScroll.requestScrollToItem(1)
                                    })
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { resultStatus() }
                            }
                        }
                        if (phone && title.series) {
                            item("source-status") {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.episode?.let {
                                        Text("S${it.seasonNumber}:E${it.episodeNumber} · ${it.name.orEmpty()}",
                                            style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                                    }
                                    resultStatus()
                                }
                            }
                        }
                        val sourcePadding = if (phone) Modifier.padding(horizontal = 16.dp) else Modifier
                        items(sources?.results.orEmpty(), key = { it.infoHash }) { source ->
                            TorrentListCard(source.title, listOfNotNull(source.quality, torrentSize(source.sizeBytes), "${source.seeders} seeders", source.provider).joinToString(" · "),
                                sourcePadding.then(entryModifier("source:${source.infoHash}")), onClick = { model.source(source) }, onLongClick = { sourceOptions = source })
                        }
                        if (sources?.finished == true && sources.errors.isNotEmpty()) item(key = "source-errors") {
                            Text(sources.errors.entries.joinToString("\n") { (source, reason) -> "$source: $reason" },
                                modifier = sourcePadding, fontSize = 11.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = .65f))
                        }
                        if (sources?.finished == true) item {
                            TorrentButton("Search again", Icons.Default.Refresh, sourcePadding.height(40.dp), onClick = model::sources)
                        }
                    }
                }
            }
        }
    }
        if (phone && state.title == null) Column(Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .onSizeChanged { bottomChrome = with(chromeDensity) { it.height.toDp() } }) {
            val preferred = state.titles.firstOrNull { it.key == focusedTitle } ?: state.titles.firstOrNull()
            TorrentPhoneDock(browseTab, myListSelected, { openRoot(DashboardSurface.HOME) },
                { openRoot(if (it) DashboardSurface.SERIES else DashboardSurface.MOVIES) }, ::openMyList, portalEntryFocus,
                if (state.downloadsOpen) requester("downloads-back")
                else if (state.libraryPage != null) requester("library-back")
                else if (browsingFeeds) requester("home:native")
                else preferred?.let { requester("title:${it.key}") }, requester("home:dock"), entryModifier("my-list-tab"))
        }
    if (phone && state.title == null) Box(Modifier.align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) { header() }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    }
    if (transferOpen) TorrentPreparationDialog(state, model::cancelTransfer, model::retryTransfer, model::file)
    if (categories) TorrentCategoriesDialog(state.surface, state.homeCollection?.section, ::closeCategories,
        customize = { categories = false; configure = true }) { section ->
        closeCategories()
        navigate { model.homeCollection(TorrentHomeCollection("section:${section.name}", section.title,
            section.series, section = section), "tmdb-categories") }
    }
    if (configure) TorrentConfigurationDialog(state.surface, state.discoveryConfiguration,
        close = { runCatching { requester("tmdb-categories").requestFocus() }; configure = false },
        save = { value -> runCatching { requester("tmdb-categories").requestFocus() }; configure = false; model.configureDiscovery(value) })
    sourceOptions?.let { source ->
        val closeFocus = remember { FocusRequester() }
        fun close() { runCatching { requester("source:${source.infoHash}").requestFocus() }; sourceOptions = null }
        AlertDialog(onDismissRequest = ::close, modifier = Modifier.initialFocusEntry(closeFocus), containerColor = Color(0xF5171B24),
            title = { Text(source.title, maxLines = 2, fontSize = 17.sp) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source.kind.media) TorrentButton("Play", Icons.Default.PlayArrow, Modifier.fillMaxWidth().height(40.dp),
                        enabled = source.seeders > 0) { close(); model.source(source) }
                    TorrentButton("Download for later", Icons.Default.Download, Modifier.fillMaxWidth().height(40.dp)) { close(); model.download(source) }
                }
            }, confirmButton = { TorrentButton("Close", Icons.Default.Close, Modifier.height(40.dp).focusRequester(closeFocus), onClick = ::close) })
    }
    libraryOptions?.let { (entry, origin) ->
        val closeFocus = remember { FocusRequester() }
        val card = entry.card
        val saved = state.library.saved.any { it.key == entry.key }
        fun close() {
            val target = when {
                phone && myListSelected && origin != "library-back" -> "library-native"
                phone && browsingFeeds && origin.startsWith("home:") && origin != "home:hero" -> "home:native"
                else -> origin
            }
            runCatching { requester(target).requestFocus() }
            libraryOptions = null
        }
        fun remove(fromSaved: Boolean, action: () -> Unit) {
            val inSaved = origin.startsWith("library:MY_LIST:")
            if (inSaved != fromSaved) { close(); action(); return }
            val entries = if (fromSaved) state.library.saved else state.library.continueWatching
            val index = entries.indexOfFirst { it.key == entry.key }
            val next = entries.getOrNull(index + 1) ?: entries.getOrNull(index - 1)
            val destination = next?.let { origin.removeSuffix(entry.key) + it.key }
                ?: if (origin.startsWith("library:")) "library-back"
                else if (homeHero != null) "home:hero" else "search"
            handoff(destination) { libraryOptions = null; action() }
        }
        AlertDialog(onDismissRequest = ::close, modifier = Modifier.initialFocusEntry(closeFocus),
            containerColor = Color(0xF5171B24), title = { Text(entry.title, fontSize = 18.sp, maxLines = 2) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card != null) TorrentButton("Open details", Icons.Default.Info, Modifier.fillMaxWidth().height(40.dp)) {
                    close(); navigate { model.open(card, origin) }
                }
                TorrentButton(if (saved) "Remove from My List" else "Add to My List",
                    if (saved) Icons.Default.Check else Icons.Default.Add, Modifier.fillMaxWidth().height(40.dp)) {
                    if (saved) remove(true) { model.removeSaved(entry) } else { model.toggleSaved(entry); close() }
                }
                if (state.library.continueWatching.any { it.key == entry.key })
                    TorrentButton("Remove from Continue Watching", Icons.Default.Delete, Modifier.fillMaxWidth().height(40.dp)) {
                        remove(false) { model.removeContinueWatching(entry) }
                    }
            } }, confirmButton = { TorrentButton("Close", Icons.Default.Close,
                Modifier.height(40.dp).focusRequester(closeFocus), onClick = ::close) })
    }
    if (manual) {
        var source by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { manual = false }, containerColor = Color(0xFF171B24),
            title = { Text("Open torrent") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste a magnet link or a .torrent URL.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .7f))
                TorrentTextField(source, { source = it }, "Magnet or torrent URL", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Uri) {}
            } },
            confirmButton = { TorrentButton("Open", Icons.Default.PlayArrow, Modifier.height(42.dp), primary = true, enabled = source.isNotBlank()) {
                manual = false; model.manual(source)
            } }, dismissButton = { TorrentButton("Cancel", null, Modifier.height(42.dp)) { manual = false } })
    }
}

/** Filters public TMDB collections locally; it never fetches every category. */
@Composable private fun TorrentCategoriesDialog(surface: DashboardSurface, selected: TmdbHomeSection?, close: () -> Unit,
    customize: () -> Unit, open: (TmdbHomeSection) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<Boolean?>(null) }
    val sections = TmdbHomeSection.entries.filter {
        (series == null || it.series == series) && it.title.contains(filter.trim(), ignoreCase = true)
    }
    val initial = remember { selected ?: TmdbHomeSection.entries.first() }
    val initialFocus = remember { FocusRequester() }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = TmdbHomeSection.entries.indexOf(initial))
    val keyboardVisible = WindowInsets.isImeVisible
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height((maxHeight * if (keyboardVisible) 1f else .86f).coerceAtMost(600.dp))
                .initialFocusEntry(initialFocus), shape = RoundedCornerShape(16.dp),
                color = Color(0xF5171B24), border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
                Column(Modifier.padding(if (keyboardVisible) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (keyboardVisible) 6.dp else 8.dp)) {
                    if (!keyboardVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("TMDB categories", Modifier.weight(1f), fontSize = 18.sp, lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold)
                        TorrentButton("Close", Icons.Default.Close, Modifier.size(36.dp), iconOnly = true, onClick = close)
                    }
                    if (!keyboardVisible) TorrentButton("Customize ${surface.displayTitle()}", Icons.Default.Tune,
                        Modifier.fillMaxWidth().height(42.dp), onClick = customize)
                    TorrentTextField(filter, { filter = it }, "Filter category names", Modifier.fillMaxWidth()) {}
                    if (!keyboardVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "All", false to "Movies", true to "Series").forEach { (value, label) ->
                            TorrentButton(label, null, Modifier.weight(1f).height(36.dp), primary = series == value,
                                onClick = { series = value })
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll,
                        verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                        items(sections, key = { it.name }) { section ->
                            var focused by remember { mutableStateOf(false) }
                            Surface(onClick = { open(section) }, modifier = Modifier.fillMaxWidth()
                                .then(if (section == initial) Modifier.focusRequester(initialFocus) else Modifier)
                                .onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
                                color = if (focused) Color(0xFF303A49) else if (section == selected) Color.White.copy(alpha = .12f) else Color.White.copy(alpha = .055f),
                                border = BorderStroke(2.dp, if (focused) Color.White else Color.Transparent)) {
                                Row(Modifier.heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(if (section.series) Icons.Default.Tv else Icons.Default.Movie, null,
                                        Modifier.size(18.dp), tint = Color.White.copy(alpha = .7f))
                                    Text(section.title, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (section == selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                }
                            }
                        }
                        if (sections.isEmpty()) item { Text("No matching categories", fontSize = 13.sp,
                            color = Color.White.copy(alpha = .65f), modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable private fun TorrentConfigurationDialog(surface: DashboardSurface, initial: TorrentDiscoveryConfiguration,
    close: () -> Unit, save: (TorrentDiscoveryConfiguration) -> Unit) {
    var sections by remember { mutableStateOf(initial.sections) }
    var genreMarkets by remember { mutableStateOf(if (initial.genres) initial.genreMarkets else emptySet()) }
    var providers by remember { mutableStateOf(initial.providers) }
    val pairs = remember(surface) { tmdbCategoryPairs(surface) }
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.safeDrawingPadding().padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.9f)
            .initialFocusEntry(closeFocus), shape = RoundedCornerShape(16.dp), color = Color(0xF5171B24),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${surface.displayTitle()} · Torrent categories", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Only this Torrent screen is affected.", fontSize = 12.sp, color = Color.LightGray)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text("Hollywood", Modifier.width(72.dp), fontSize = 10.sp, color = Color.LightGray)
                    Text("Bollywood", Modifier.width(72.dp), fontSize = 10.sp, color = Color.LightGray)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pairs, key = { it.hollywood.name }) { pair ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pair.title + if (surface == DashboardSurface.HOME) { if (pair.hollywood.series) " · Series" else " · Movies" } else "",
                                Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            listOf(pair.hollywood, pair.bollywood).forEach { section ->
                                TorrentConfigurationToggle(section.title, section in sections, Modifier.width(72.dp)) {
                                    sections = if (section in sections) sections - section else sections + section
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 5.dp)) }
                    items(GenreMarket.entries, key = { "genres:${it.name}" }) { market ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(market.heading, Modifier.weight(1f), fontSize = 13.sp)
                            TorrentConfigurationToggle(market.heading, market in genreMarkets, Modifier.width(72.dp)) {
                                genreMarkets = if (market in genreMarkets) genreMarkets - market else genreMarkets + market
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Providers", Modifier.weight(1f), fontSize = 13.sp)
                            TorrentConfigurationToggle("Providers", providers, Modifier.width(72.dp)) { providers = !providers }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorrentButton("Clear", Icons.Default.ClearAll, Modifier.weight(1f).height(40.dp)) { sections = emptyList(); genreMarkets = emptySet(); providers = false }
                    TorrentButton("Cancel", Icons.Default.Close, Modifier.weight(1f).height(40.dp).focusRequester(closeFocus), onClick = close)
                    TorrentButton("Apply", Icons.Default.Check, Modifier.weight(1f).height(40.dp), primary = true) {
                        save(TorrentDiscoveryConfiguration(sections, genres = genreMarkets.isNotEmpty(), providers = providers, genreMarkets = genreMarkets))
                    }
                }
            }
        }
    }
}

@Composable private fun TorrentConfigurationToggle(label: String, selected: Boolean, modifier: Modifier, change: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = change, modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = label; this.selected = selected }, shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0x663D3E45) else Color.White.copy(alpha = .04f),
        border = BorderStroke(2.dp, if (focused) Color.White else Color.Transparent)) {
        Box(contentAlignment = Alignment.Center) { Icon(if (selected) Icons.Default.Check else Icons.Default.Remove, null, Modifier.size(19.dp)) }
    }
}

@Composable private fun TorrentPhoneLibrary(entries: List<TorrentLibraryEntry>,
    reduceMotion: Boolean, requester: (String) -> FocusRequester,
    open: (TorrentLibraryEntry, String) -> Unit, options: (TorrentLibraryEntry, String) -> Unit) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val index = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val entry = entries.getOrNull(index)
    fun move(delta: Int) {
        if (entries.isNotEmpty()) selectedKey = entries[Math.floorMod(index + delta, entries.size)].key
    }
    val rows = remember(entries, open, options) {
        listOf(false, true).mapNotNull { series ->
            entries.filter { it.series == series }.takeIf { it.isNotEmpty() }?.let { saved ->
                NativeDiscoveryRow(if (series) "saved-series" else "saved-movies", if (series) "Series" else "Movies",
                    saved.map { value ->
                        val origin = "library:MY_LIST:${value.key}"
                        NativeDiscoveryItem(origin, value.title, value.subtitle, value.poster ?: value.backdrop,
                            onClick = { open(value, origin) }, onLongClick = { options(value, origin) },
                            source = DiscoveryTileSource.TMDB)
                    })
            }
        }.ifEmpty { listOf(NativeDiscoveryRow("saved-empty", "Use + on a title to add it to My List.", emptyList())) }
    }
    TorrentPhoneFeed(rows, entry?.card, inList = true,
        bodyFocus = requester("library-native"), playFocus = requester("library-back"),
        open = { entry?.let { open(it, "library-back") } },
        save = { entry?.let { options(it, "library-back") } },
        previous = { move(-1) }, next = { move(1) }, index = index, count = entries.size,
        popup = false, reduceMotion = reduceMotion)
}

@Composable private fun TorrentPhoneDock(browseTab: DashboardSurface?, myListSelected: Boolean,
    openHome: () -> Unit, select: (Boolean) -> Unit, openMyList: () -> Unit,
    portalEntryFocus: FocusRequester?, contentFocus: FocusRequester?, homeFocus: FocusRequester, myListModifier: Modifier) {
    val portalTrigger = portalEntryFocus ?: remember { FocusRequester() }
    val keyboardVisible = WindowInsets.isImeVisible
    DiscoveryPhoneBottomNavigation(
        selected = if (myListSelected) DiscoveryPhoneDestination.LIBRARY else browseTab?.phoneTab(),
        select = { destination ->
            when (destination) {
                DiscoveryPhoneDestination.HOME -> openHome()
                DiscoveryPhoneDestination.MOVIES -> select(false)
                DiscoveryPhoneDestination.SERIES -> select(true)
                DiscoveryPhoneDestination.LIBRARY -> openMyList()
                else -> Unit
            }
        },
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, if (keyboardVisible) 0 else placeable.height) {
                if (!keyboardVisible) placeable.placeRelative(0, 0)
            }
        },
        showLiveTv = false, portalTrigger = portalTrigger
    ) { destination ->
        Modifier.then(when (destination) {
            DiscoveryPhoneDestination.HOME -> Modifier.focusRequester(homeFocus)
            DiscoveryPhoneDestination.LIBRARY -> myListModifier
            else -> Modifier
        }).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown) && contentFocus != null)
                runCatching { contentFocus.requestFocus() }.getOrDefault(false)
            else false
        }
    }
}

@Composable private fun TorrentMovieDetails(card: DiscoveryCard, inList: Boolean, save: () -> Unit,
    back: () -> Unit, showBack: Boolean, playFocus: FocusRequester, backFocus: FocusRequester, magnetFocus: FocusRequester,
    openMagnet: () -> Unit, openRelated: (DiscoveryCard) -> Unit, sources: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { DiscoveryRepository(context.applicationContext) }
    val metadata = remember(repository, card) {
        object : DiscoveryDataSource by repository {
            override suspend fun providerDetails(item: com.nikhil.niktv.model.MediaItem, type: CatalogType,
                stale: (DiscoveryDetails) -> Unit): DiscoveryDetails = repository.details(card, stale)
        }
    }
    ProviderDetailHeader(card.media, CatalogType.MOVIES, app = null,
        play = sources, close = back, favorite = save, isFavorite = inList,
        openTmdbMovie = { openRelated(it.tmdb.discovery()) },
        openTmdbSeries = { openRelated(it.tmdb.discovery()) },
        playRequester = playFocus, backRequester = backFocus,
        playLabel = "Sources", metadataSource = metadata,
        showBackButton = showBack, showMovieOptions = false, initialCard = card,
        openDiscoveryDetail = { if (it.torrentPublished()) openRelated(it) },
        tools = { DiscoveryAction("Open magnet", Icons.Default.Link, iconOnly = true,
            modifier = Modifier.focusRequester(magnetFocus), onClick = openMagnet) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TorrentPhoneDetailHero(card: DiscoveryCard, episode: TmdbEpisode?, loading: Boolean,
    reduceMotion: Boolean, inList: Boolean, toggleSaved: () -> Unit, profileName: String, avatar: String,
    profileColor: Color?, openProfile: () -> Unit, back: () -> Unit, openMagnet: () -> Unit,
    backModifier: Modifier, magnetModifier: Modifier, playLabel: String, enabled: Boolean,
    play: () -> Unit, down: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    val toolbar: @Composable () -> Unit = {
        PortalPhoneHeader {
            DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, focusHint = false,
                modifier = backModifier, onClick = back)
            Spacer(Modifier.weight(1f))
            DiscoveryAction("Switch profile: $profileName", visualLabel = avatar, containerColor = profileColor,
                horizontalPadding = 0.dp, modifier = Modifier.size(48.dp), onClick = openProfile)
            DiscoveryAction("Open magnet", Icons.Default.Link, iconOnly = true,
                modifier = magnetModifier, onClick = openMagnet)
        }
    }
    var synopsisOpen by remember(card.key) { mutableStateOf(false) }
    if (card.series) {
        val height = (LocalConfiguration.current.screenHeightDp * .30f).dp.coerceAtLeast(220.dp)
        DiscoveryCompactPhoneSeriesHero(card, height, reduceMotion, toolbar) {
            FlowRow(Modifier.fillMaxWidth().onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { down(); true }
                else false
            }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoveryAction(playLabel, if (episode == null) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.List,
                    iconOnly = true, selected = true, enabled = enabled, modifier = Modifier.focusRequester(playFocus), onClick = play)
                DiscoveryAction(if (inList) "In My List" else "Add to My List",
                    if (inList) Icons.Default.Check else Icons.Default.Add, iconOnly = true, onClick = toggleSaved)
                DiscoveryTrailerAction(card, iconOnly = true)
                if (!card.overview.isNullOrBlank()) DiscoveryAction("Description", Icons.Default.Info, iconOnly = true,
                    modifier = Modifier.focusRequester(infoFocus)) { synopsisOpen = true }
            }

        }
    } else DiscoveryPhoneHero(card, rank = null, height = detailDiscoveryHeroHeight(portraitPhone = true),
        detail = true, loading = loading, inList = inList, playRequester = playFocus, infoRequester = infoFocus,
        play = play, add = toggleSaved, info = {}, previous = {}, next = {}, carousel = false,
        index = 0, count = 0, playLabel = playLabel, playEnabled = enabled, reduceMotion = reduceMotion,
        onFocus = {}, onDown = down, header = toolbar, compactDetail = true, shadeArtworkBounds = true,
        showSynopsis = false, categoryActions = if (!card.overview.isNullOrBlank()) ({
            DiscoveryAction("Description", Icons.Default.Info, iconOnly = true,
                modifier = Modifier.focusRequester(infoFocus)) { synopsisOpen = true }
        }) else null)
    if (synopsisOpen && !card.overview.isNullOrBlank()) FullDescriptionDialog(card.title, card.overview) {
        synopsisOpen = false
        runCatching { infoFocus.requestFocus() }
    }
}

@Composable private fun TorrentTitleHeader(card: DiscoveryCard, episode: TmdbEpisode?, short: Boolean,
    onOpenMagnet: () -> Unit, inList: Boolean, toggleSaved: () -> Unit, manualModifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (short) 74.dp else 170.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.title, fontSize = if (short) 18.sp else 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((discoveryDetailMetadata(card) + listOfNotNull(episode?.let { "S${it.seasonNumber}:E${it.episodeNumber}" })).joinToString(" · "),
                color = Color.White.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TorrentButton(if (inList) "Remove from My List" else "Add to My List", if (inList) Icons.Default.Check else Icons.Default.Add,
            Modifier.size(40.dp), iconOnly = true, onClick = toggleSaved)
        TorrentButton("Open magnet", Icons.Default.Link, Modifier.size(40.dp).then(manualModifier), iconOnly = true, onClick = onOpenMagnet)
    }
}

@Composable private fun TorrentLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun TorrentError(text: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        TorrentButton("Retry", Icons.Default.Refresh, Modifier.height(38.dp), onClick = retry)
    }
}

@Composable private fun TorrentListCard(title: String, detail: String, modifier: Modifier = Modifier,
    artwork: String? = null, uniform: Boolean = false, onLongClick: (() -> Unit)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.PlayArrow, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick,
            interactionSource = remember { MutableInteractionSource() }, showIndication = false), shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF303A49) else Color.White.copy(alpha = .055f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, Color.White.copy(alpha = if (focused) .95f else .15f))) {
        Row(Modifier.heightIn(min = if (uniform) 78.dp else 0.dp).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artwork != null) AsyncImage(artwork, null, Modifier.size(width = 104.dp, height = 58.dp)
                .clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                    minLines = if (uniform) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank() || uniform) Text(detail, fontSize = 11.sp, lineHeight = 14.sp,
                    color = Color.White.copy(alpha = .65f), minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(actionIcon, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = .7f))
        }
    }
}

@Composable private fun TorrentButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true, iconOnly: Boolean = false, onClick: () -> Unit) {
    if (iconOnly) DiscoveryAction(label, icon, iconOnly = true, modifier = modifier, enabled = enabled, focusHint = label !in setOf("Back", "Search"), onClick = onClick)
    else NikTvActionButton(label, onClick,
        modifier.width((label.length * 7 + if (icon != null) 42 else 24).coerceIn(88, 180).dp), primary, icon, enabled)
}

/** D-pad focus is read-only. Touch or OK activates the editor and IME together. */
@Composable internal fun TorrentTextField(value: String, change: (String) -> Unit, label: String, modifier: Modifier,
    submit: () -> Unit) = TorrentTextField(value, change, label, modifier, KeyboardType.Text, submit = submit)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun TorrentTextField(value: String, change: (String) -> Unit, label: String, modifier: Modifier,
    keyboardType: KeyboardType, activationRequest: Int = 0, editFinished: () -> Unit = {},
    editRequested: () -> Unit = {}, compact: Boolean = false, activationState: PortalSearchActivation? = null, submit: () -> Unit) =
    PortalSearchField(value, change, label, modifier, keyboardType, activationRequest, editFinished, editRequested, compact, activationState, submit)

internal fun torrentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 KB"
}
