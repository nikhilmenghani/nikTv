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
    var searchExpanded by remember { mutableStateOf(false) }
    var searchActivation by remember { mutableIntStateOf(0) }
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
    // The lists keep their own state across title/source/modal changes, and the
    // activity's saveable owner retains it while the player covers this screen.
    val titlesScroll = rememberLazyGridState()
    val libraryScroll = rememberLazyGridState()
    val homeSavedState = rememberSaveableStateHolder()
    val episodesScroll = rememberLazyGridState()
    val sourcesScroll = rememberLazyListState()
    val downloadsScroll = rememberLazyListState()
    val filesScroll = rememberLazyListState()
    val transferOpen = state.transfer != null || state.files.isNotEmpty()
    val browsingFeeds = state.title == null && state.browsingFeeds
    val reduceMotion = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val libraryEntries = if (state.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING) state.library.continueWatching else state.library.saved
    val myListSelected = state.libraryPage == TorrentLibraryPage.MY_LIST && !state.downloadsOpen
    val browseTab = state.surface.takeUnless { myListSelected }
    val routeEntry = state.returnFocus ?: explicitEntry ?: when {
        state.title?.series == true && state.sources == null -> "season"
        state.title != null -> "details-back"
        state.downloadsOpen -> "downloads-back"
        state.libraryPage != null -> "library-back"
        state.homeCollection != null -> "collection-back"
        browsingFeeds && state.homeData.hero != null -> "home:hero"
        else -> "search"
    }
    val entryPending = !transferOpen && (state.returnFocus != null || explicitEntry != null || (!view.isInTouchMode && enteringRoute))
    val entryPolicy = remember(routeEntry, entryPending) { InitialFocusEntry() }
    fun requestRouteEntry(): Boolean {
        if (!entryPending || !windowFocused || routeEntry == "search" && imeVisible) return false
        if (routeEntry == "manual" && !searchExpanded && state.title == null) {
            searchExpanded = true
            return false
        }
        val target = requesters[routeEntry] ?: return false
        val success = runCatching { target.requestFocus() }.getOrDefault(false)
        if (success) {
            enteringRoute = false
            explicitEntry = null
            if (state.returnFocus != null) model.focusRestored()
        }
        return success
    }
    fun entryModifier(key: String) = Modifier.focusRequester(requester(key)).onPlaced {
        if (entryPending && routeEntry == key) requestRouteEntry()
    }
    fun navigate(action: () -> Unit) {
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
        if (!view.isInTouchMode) {
            explicitEntry = key
            enteringRoute = true
            parking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
        }
        action()
    }
    fun finishSearch(collapse: Boolean = true) {
        explicitEntry = "search"
        enteringRoute = true
        parking.request { runCatching { parkingRequester.requestFocus() }.getOrDefault(false) }
        if (collapse) searchExpanded = false
    }
    fun back() {
        if (state.title == null && (searchExpanded || state.query.isNotBlank())) {
            finishSearch()
            if (state.query.isNotBlank()) model.cancelSearch(searchOriginSurface)
        }
        else navigate { if (!model.back()) onClose() }
    }
    fun search() {
        if (!searchExpanded) searchOriginSurface = state.surface
        searchExpanded = true; searchActivation++
    }
    fun openRoot(surface: DashboardSurface) {
        searchOriginSurface = surface
        searchExpanded = false
        if (surface == DashboardSurface.HOME) model.home() else model.type(surface == DashboardSurface.SERIES)
    }
    fun openMyList() {
        searchExpanded = false
        model.openLibraryPage(TorrentLibraryPage.MY_LIST, "my-list-tab")
    }
    fun closeCategories() {
        runCatching { requester("tmdb-categories").requestFocus() }
        categories = false
    }
    LaunchedEffect(transferOpen) { if (transferOpen) filesScroll.requestScrollToItem(0) }
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
    LaunchedEffect(state.returnFocus, explicitEntry, state.loading, state.playing, transferOpen) {
        val key = state.returnFocus ?: explicitEntry ?: return@LaunchedEffect
        if (state.playing != null || state.loading != null || transferOpen) return@LaunchedEffect
        // A lazy origin below the first viewport must be composed before focus
        // can return. The preserved list normally already owns this position.
        val index = when {
            key.startsWith("source:") -> state.sources?.results?.indexOfFirst { "source:${it.infoHash}" == key } ?: -1
            key.startsWith("episode:") -> state.episodes.indexOfFirst { "episode:${it.seasonNumber}:${it.episodeNumber}" == key }
            key.startsWith("title:") -> state.titles.indexOfFirst { "title:${it.key}" == key }
            key.startsWith("library:") -> libraryEntries.indexOfFirst { "library:${state.libraryPage?.name}:${it.key}" == key }
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
        val tools: @Composable () -> Unit = {
            DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, focusHint = false,
                modifier = entryModifier("search"), onClick = ::search)
            DiscoveryAction("TMDB categories", Icons.Default.Category, iconOnly = true,
                modifier = entryModifier("tmdb-categories"), onClick = { categories = true })
            DiscoveryAction("Torrent downloads", Icons.Default.Download, iconOnly = true,
                modifier = entryModifier("downloads"), onClick = { navigate(model::openDownloads) })
        }
        Box(Modifier.onFocusChanged { if (it.hasFocus) feedFocused = false }) {
            if (phone) PortalPhoneHeader {
                DiscoveryAction("Switch profile: $viewerName", visualLabel = viewerAvatar,
                    containerColor = viewer?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary, onClick = onProfiles)
                Spacer(Modifier.weight(1f))
                tools()
            } else PortalWideHeader(profile, onProfiles) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, focusHint = false, onClick = ::back)
                TorrentButton("Home", Icons.Default.Home, Modifier.height(if (short) 36.dp else 40.dp),
                    primary = browseTab == DashboardSurface.HOME, onClick = { openRoot(DashboardSurface.HOME) })
                TorrentButton("Movies", Icons.Default.Movie, Modifier.height(if (short) 36.dp else 40.dp),
                    primary = browseTab == DashboardSurface.MOVIES, onClick = { openRoot(DashboardSurface.MOVIES) })
                TorrentButton("Series", Icons.Default.Tv, Modifier.height(if (short) 36.dp else 40.dp),
                    primary = browseTab == DashboardSurface.SERIES, onClick = { openRoot(DashboardSurface.SERIES) })
                TorrentButton("My List", Icons.Default.VideoLibrary, Modifier.height(if (short) 36.dp else 40.dp)
                    .then(entryModifier("my-list-tab")).semantics { selected = myListSelected; role = Role.Tab }
                    .focusProperties { if (myListSelected) down = requester("library-back") },
                    primary = myListSelected, onClick = ::openMyList)
                tools()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    if (!phone && browsingFeeds) DiscoveryBackdrop(presentedCard ?: state.homeData.hero, reduceMotion = reduceMotion)
    Box(Modifier.size(1.dp).focusRequester(parkingRequester)
        .focusProperties { canFocus = parking.canFocus }
        .onFocusChanged { parking.focusChanged(it.isFocused) }.focusTarget()
        .clearAndSetSemantics { hideFromAccessibility() })
    Column(Modifier.fillMaxSize().safeDrawingPadding().focusProperties {
        onEnter = {
            if (entryPending && !entryPolicy.request { requestRouteEntry() }) cancelFocusChange()
        }
    }.focusGroup()) {
        if (!phone || !browsingFeeds) header()
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (browsingFeeds) 0.dp else if (phone) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (browsingFeeds && !searchExpanded) 0.dp else if (short) 6.dp else 10.dp)) {
            if (state.title == null) {
                val showSearch = searchExpanded || (!browsingFeeds && !state.downloadsOpen && state.libraryPage == null)
                Row(Modifier.fillMaxWidth().then(if (browsingFeeds) Modifier.padding(horizontal = if (phone) 16.dp else 24.dp) else Modifier)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, if (showSearch) placeable.height else 0) {
                            if (showSearch) placeable.placeRelative(0, 0)
                        }
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TorrentTextField(state.query, model::query, "Search movies or series", Modifier.weight(1f), keyboardType = KeyboardType.Text,
                        activationRequest = searchActivation, editFinished = {
                            finishSearch(collapse = state.query.isBlank())
                        }, submit = model::search)
                    TorrentButton("Search", Icons.Default.Search,
                        Modifier.size(48.dp).then(entryModifier("submit-search")), iconOnly = true, onClick = { if (state.loading == null) model.search() })
                    TorrentButton("Open magnet", Icons.Default.Link, Modifier.size(48.dp).then(entryModifier("manual")), iconOnly = true, onClick = { manual = true })
                }
                if (state.downloadsOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TorrentButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.size(40.dp).then(entryModifier("downloads-back")),
                            iconOnly = true, onClick = ::back)
                        Text("Torrent downloads", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.downloads.isEmpty()) Text("No torrent downloads yet. Touch and hold a source to download it for later.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = .65f))
                    state.loading?.let { TorrentLoading(it) }
                    state.error?.let { TorrentError(it, model::openDownloads) }
                    LazyColumn(Modifier.weight(1f), state = downloadsScroll, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
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
                    }
                } else if (state.libraryPage != null) {
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
                            phone -> 3
                            short -> kotlin.math.ceil(((maxWidth + 10.dp) /
                                ((maxHeight - 16.dp).coerceAtLeast(72.dp) * 2f / 3f + 10.dp)).toDouble()).toInt().coerceIn(4, 12)
                            else -> 7
                        }
                        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().focusRestorer(), state = libraryScroll,
                            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
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
                        popup = categories || configure || manual || libraryOptions != null || sourceOptions != null,
                        reduceMotion = reduceMotion, phoneHeader = if (phone) header else null)
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
                if (state.titles.isEmpty() && state.loading == null && state.error == null) Text("No titles found. Try another title or year.", color = Color.White.copy(alpha = .7f))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val posterColumns = when {
                    phone -> 3
                    short -> {
                        val maxPosterWidth = (maxHeight - 16.dp).coerceAtLeast(72.dp) * 2f / 3f
                        kotlin.math.ceil(((maxWidth + 10.dp) / (maxPosterWidth + 10.dp)).toDouble()).toInt().coerceIn(4, 12)
                    }
                    else -> 7
                }
                LazyVerticalGrid(GridCells.Fixed(posterColumns), Modifier.fillMaxSize().focusRestorer(), state = titlesScroll,
                    contentPadding = PaddingValues(top = 4.dp, bottom = if (short) 12.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.titles, key = { it.key }) { card ->
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            DiscoveryPosterTile(card.title, card.poster,
                                card.date?.take(4)?.takeIf { it.length == 4 } ?: "Year unavailable", width = maxWidth,
                                modifier = entryModifier("title:${card.key}"), reduceMotion = true,
                                onFocus = { focusedTitle = card.key }, onClick = { navigate { model.open(card) } })
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
                state.title?.let { title -> TorrentTitleHeader(title, state.episode, phone, short, onBack = ::back,
                    onOpenMagnet = { manual = true }, inList = state.library.saved.any { it.tmdbId == title.id && it.series == title.series },
                    toggleSaved = { model.toggleSaved(title) }, backModifier = entryModifier("details-back"), manualModifier = entryModifier("manual")) }
                if (state.title?.series == true && state.sources == null) {
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
                        Box(Modifier.width(seasonWidth)) {
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
                            TorrentListCard("S${episode.seasonNumber}:E${episode.episodeNumber} · ${episode.name.orEmpty()}",
                                episode.airDate.orEmpty(), entryModifier("episode:${episode.seasonNumber}:${episode.episodeNumber}"),
                                artwork = episode.stillUrl, uniform = true, onClick = { navigate { model.episode(episode) } })
                        }
                    }
                } else {
                    val sources = state.sources
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
                    LazyColumn(Modifier.weight(1f).focusRestorer(), state = sourcesScroll, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(sources?.results.orEmpty(), key = { it.infoHash }) { source ->
                            TorrentListCard(source.title, listOfNotNull(source.quality, torrentSize(source.sizeBytes), "${source.seeders} seeders", source.provider).joinToString(" · "),
                                entryModifier("source:${source.infoHash}"), onClick = { model.source(source) }, onLongClick = { sourceOptions = source })
                        }
                        if (sources?.finished == true && sources.errors.isNotEmpty()) item(key = "source-errors") {
                            Text(sources.errors.entries.joinToString("\n") { (source, reason) -> "$source: $reason" },
                                fontSize = 11.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = .65f))
                        }
                        if (sources?.finished == true) item { TorrentButton("Search again", Icons.Default.Refresh, Modifier.height(40.dp), onClick = model::sources) }
                    }
                }
            }
        }
        if (phone && state.title == null) {
            val preferred = state.titles.firstOrNull { it.key == focusedTitle } ?: state.titles.firstOrNull()
            TorrentPhoneDock(browseTab, { openRoot(DashboardSurface.HOME) },
                { openRoot(if (it) DashboardSurface.SERIES else DashboardSurface.MOVIES) }, ::openMyList, portalEntryFocus,
                if (state.downloadsOpen) requester("downloads-back")
                else if (state.libraryPage != null) requester("library-back")
                else if (browsingFeeds) requester(focusedHome ?: if (state.homeData.hero != null) "home:hero" else "search")
                else preferred?.let { requester("title:${it.key}") }, requester("home:dock"), entryModifier("my-list-tab"))
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (transferOpen) {
        val cancelFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = model::cancelTransfer, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp).fillMaxWidth()
                .height((configuration.screenHeightDp * .72f).coerceIn(180f, 520f).dp),
                shape = RoundedCornerShape(16.dp), color = Color(0xF5171B24), border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.transferPurpose == TorrentTransferPurpose.DOWNLOAD) {
                        if (state.files.size > 1 && state.loading == null) "Choose video to download" else "Preparing download"
                    } else if (state.files.size > 1 && state.loading == null) "Choose video" else "Preparing stream", style = MaterialTheme.typography.titleMedium)
                    TorrentTransferPanel(state, model, Modifier.weight(1f), cancelFocus, filesScroll)
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
        }
    }
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
                    TorrentButton("Play", Icons.Default.PlayArrow, Modifier.fillMaxWidth().height(40.dp)) { close(); model.source(source) }
                    TorrentButton("Download for later", Icons.Default.Download, Modifier.fillMaxWidth().height(40.dp)) { close(); model.download(source) }
                }
            }, confirmButton = { TorrentButton("Close", Icons.Default.Close, Modifier.height(40.dp).focusRequester(closeFocus), onClick = ::close) })
    }
    libraryOptions?.let { (entry, origin) ->
        val closeFocus = remember { FocusRequester() }
        val card = entry.card
        val saved = state.library.saved.any { it.key == entry.key }
        fun close() { runCatching { requester(origin).requestFocus() }; libraryOptions = null }
        fun remove(fromSaved: Boolean, action: () -> Unit) {
            val inSaved = origin.startsWith("library:MY_LIST:")
            if (inSaved != fromSaved) { close(); action(); return }
            val entries = if (fromSaved) state.library.saved else state.library.continueWatching
            val index = entries.indexOfFirst { it.key == entry.key }
            val next = entries.getOrNull(index + 1) ?: entries.getOrNull(index - 1)
            val destination = next?.let { origin.removeSuffix(entry.key) + it.key }
                ?: if (origin.startsWith("library:")) "library-back"
                else if (state.homeData.hero != null) "home:hero" else "search"
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

/** Same glass dock, selected mark, reserved border and compact metrics as IPTV/Sports. */
@Composable private fun TorrentPhoneDock(browseTab: DashboardSurface?, openHome: () -> Unit, select: (Boolean) -> Unit,
    openMyList: () -> Unit, portalEntryFocus: FocusRequester?, contentFocus: FocusRequester?, homeFocus: FocusRequester,
    myListModifier: Modifier) {
    val portals = LocalDirectPortalActions.current
    val portalTrigger = portalEntryFocus ?: remember { FocusRequester() }
    val selectedPortal = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    fun close() { runCatching { portalTrigger.requestFocus() }; expanded = false }
    LaunchedEffect(portals.size) { if (portals.size <= 1) expanded = false }
    PortalPhoneDock(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // Keep the dock and editor mounted, but reclaim the dock's height while
        // the IME is visible instead of lifting navigation above the keyboard.
        layout(placeable.width, if (keyboardVisible) 0 else placeable.height) {
            if (!keyboardVisible) placeable.placeRelative(0, 0)
        }
    }) {
        TorrentDockItem("Home", Icons.Default.Home, Modifier.weight(1f).focusRequester(homeFocus),
            browseTab == DashboardSurface.HOME, contentFocus, openHome)
        listOf(false, true).forEach { shows ->
            val label = if (shows) "Series" else "Movies"
            val active = browseTab == if (shows) DashboardSurface.SERIES else DashboardSurface.MOVIES
            TorrentDockItem(label, if (shows) Icons.Default.Tv else Icons.Default.Movie,
                Modifier.weight(1f), active, contentFocus = contentFocus) { select(shows) }
        }
        TorrentDockItem("My List", Icons.Default.VideoLibrary, Modifier.weight(1f).then(myListModifier),
            browseTab == null, contentFocus, openMyList)
        if (portals.size > 1) Box(Modifier.weight(1f)) {
            TorrentDockItem("Portals", Icons.Default.Subscriptions,
                Modifier.fillMaxWidth().focusRequester(portalTrigger), false, contentFocus = contentFocus) { expanded = true }
            DropdownMenu(expanded, ::close, containerColor = Color(0xEE171B24), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.initialFocusEntry(selectedPortal)) {
                portals.forEach { portal -> key(portal.id) {
                    NikDropdownMenuItem(text = { Text(portal.name) }, leadingIcon = { Icon(portal.icon, null) },
                        trailingIcon = { if (portal.selected) Icon(Icons.Default.Check, "Selected portal") },
                        modifier = (if (portal.selected) Modifier.focusRequester(selectedPortal) else Modifier)
                            .semantics { selected = portal.selected },
                        onClick = { close(); if (!portal.selected) portal.open() })
                } }
            }
        }
    }
}

@Composable private fun TorrentDockItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, active: Boolean, contentFocus: FocusRequester?, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = click, modifier = modifier.heightIn(min = 56.dp)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown) && contentFocus != null)
                runCatching { contentFocus.requestFocus() }.getOrDefault(false)
            else false
        }.onFocusChanged { focused = it.isFocused }
        .semantics { selected = active; contentDescription = label; role = Role.Tab }
        .discoveryTabIndicator(active, Color(0xFFFF6778)),
        shape = RoundedCornerShape(8.dp), color = if (active) Color(0x663D3E45) else Color.Transparent,
        border = BorderStroke(2.dp, if (focused) Color.White else Color.Transparent),
        contentColor = if (active) Color.White else Color(0xFFB1B1B9)) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (active) Color(0xFFFF6778) else Color(0xFFB1B1B9))
            Text(label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable private fun TorrentTitleHeader(card: DiscoveryCard, episode: TmdbEpisode?, phone: Boolean, short: Boolean,
    onBack: () -> Unit, onOpenMagnet: () -> Unit, inList: Boolean, toggleSaved: () -> Unit,
    backModifier: Modifier = Modifier, manualModifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().height(if (short) 88.dp else if (phone) 140.dp else 170.dp)) {
        AsyncImage(card.backdrop ?: card.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF2080B12), Color(0x66080B12), Color(0xB0080B12)))))
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorrentButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.size(40.dp).then(backModifier), iconOnly = true, onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.title, fontSize = if (short) 18.sp else 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(card.date?.take(4), if (card.series) "Series" else "Movie", episode?.let { "S${it.seasonNumber}:E${it.episodeNumber}" }).joinToString(" · "),
                    color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
            }
            TorrentButton(if (inList) "Remove from My List" else "Add to My List", if (inList) Icons.Default.Check else Icons.Default.Add,
                Modifier.size(40.dp), iconOnly = true, onClick = toggleSaved)
            TorrentButton("Open magnet", Icons.Default.Link, Modifier.size(40.dp).then(manualModifier), iconOnly = true, onClick = onOpenMagnet)
        }
    }
}

@Composable private fun TorrentTransferPanel(state: TorrentPortalState, model: TorrentPortalViewModel, modifier: Modifier,
    cancelFocus: FocusRequester, filesScroll: androidx.compose.foundation.lazy.LazyListState) {
    val transfer = state.transfer
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.loading != null) TorrentLoading(transfer?.stage ?: state.loading!!)
        transfer?.let {
            Text("${torrentSize(it.downloadedBytes)} / ${torrentSize(it.totalBytes)} · ${torrentSize(it.downloadBytesPerSecond)}/s · ${it.peers} peers", fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
            if (it.totalBytes > 0) LinearProgressIndicator(progress = { (it.downloadedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            it.storageWarning?.let { warning -> Text(warning, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFFFD79A)) }
        }
        (state.error ?: transfer?.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorrentButton("Cancel", Icons.Default.Close, Modifier.height(40.dp).focusRequester(cancelFocus), onClick = model::cancelTransfer)
            if (state.error != null || transfer?.error != null) TorrentButton("Retry", Icons.Default.Refresh, Modifier.height(40.dp), onClick = model::retryTransfer)
        }
        if (state.files.size > 1 && state.loading == null) Text("Choose the video to play", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), state = filesScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.files, key = { it.index }) { file ->
                TorrentListCard(file.name.substringAfterLast('/'), torrentSize(file.sizeBytes), onClick = { model.file(file) })
            }
        }
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
    artwork: String? = null, uniform: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
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
            Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = .7f))
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
    keyboardType: KeyboardType, activationRequest: Int = 0, editFinished: () -> Unit = {}, submit: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White,
        unfocusedBorderColor = Color(0xFF454B58), cursorColor = Color.White,
        focusedPlaceholderColor = Color.White.copy(alpha = .6f), unfocusedPlaceholderColor = Color.White.copy(alpha = .6f))
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldHeight = with(LocalDensity.current) { maxOf(48.dp, 18.sp.toDp() + 20.dp) }
    val ime = WindowInsets.isImeVisible
    val currentIme by rememberUpdatedState(ime)
    var sawIme by remember { mutableStateOf(false) }
    var pendingHide by remember { mutableStateOf(false) }
    var showRequested by remember { mutableStateOf(false) }
    var editSession by remember { mutableIntStateOf(0) }
    var activationKey by remember { mutableStateOf<Int?>(null) }
    var seenActivation by remember { mutableIntStateOf(activationRequest) }
    fun stopEditing(returnControl: Boolean = false) {
        // show() is asynchronous: Back can cancel before the IME window exists.
        // Retain ownership so a late attachment is still hidden by this field.
        pendingHide = pendingHide || currentIme || showRequested
        editing = false; sawIme = false; showRequested = false
        activationKey = null
        keyboard?.hide()
        if (returnControl) editFinished()
    }
    fun activate() {
        sawIme = false; showRequested = false
        activationKey = null
        editing = true; editSession++
    }
    LaunchedEffect(activationRequest) {
        if (activationRequest != seenActivation && activationRequest > 0) activate()
        seenActivation = activationRequest
    }
    LaunchedEffect(editing, editSession) {
        if (editing) {
            // Search/Back can hide one IME window while the user immediately
            // starts another edit. Wait for that owned hide to complete before
            // showing the next session, so old insets cannot close the new one.
            if (pendingHide) snapshotFlow { currentIme }.first { !it }
            pendingHide = false
            withFrameNanos { }
            if (editing) {
                requester.requestFocus()
                showRequested = true
                keyboard?.show()
            }
        }
    }
    LaunchedEffect(ime, editing, showRequested, pendingHide) {
        when {
            !editing -> {
                sawIme = false
                if (ime && pendingHide) keyboard?.hide()
            }
            !showRequested -> Unit
            ime -> sawIme = true
            sawIme -> stopEditing(returnControl = true)
        }
    }
    // Back may arrive before the keyboard window attaches. Once attached,
    // intercept it before the IME so editing ends in the same key event, not
    // in a later inset-observation effect that could swallow the next arrow.
    BackHandler(enabled = editing && !ime, onBack = { stopEditing(returnControl = true) })
    BasicTextField(value, change, modifier.heightIn(min = fieldHeight).focusRequester(requester)
        .onFocusChanged { if (!it.isFocused) { activationKey = null; if (editing) stopEditing() } }
        .onPreInterceptKeyBeforeSoftKeyboard { event ->
            val direction = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (!editing && direction != null) {
                // A cancelled IME may still be detaching. It has no ownership
                // of directional navigation once this editor is read-only.
                if (event.type == KeyEventType.KeyDown) {
                    if (currentIme || pendingHide) keyboard?.hide()
                    focusManager.moveFocus(direction)
                }
                true
            } else if (editing && event.key == Key.Back) {
                if (event.type == KeyEventType.KeyUp) stopEditing(returnControl = true)
                true
            } else false
        }
        .onPreviewKeyEvent { event ->
            val direction = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
            if (direction != null && (vertical || !editing)) {
                // Read-only TextField still handles arrows as cursor movement.
                // Single-line editors have no vertical cursor movement. End
                // editing before moving out, including immediately after Back
                // when Android's IME visibility notification is still pending.
                // Horizontal keys remain cursor controls during active editing.
                if (event.type == KeyEventType.KeyDown) {
                    if (editing) stopEditing()
                    focusManager.moveFocus(direction)
                }
                true
            } else if (event.nativeKeyEvent.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                val code = event.nativeKeyEvent.keyCode
                when {
                    event.type == KeyEventType.KeyDown && !editing -> { activationKey = code; true }
                    event.type == KeyEventType.KeyUp && activationKey == code -> { activate(); true }
                    // Enter DOWN may submit and make the field read-only. Its
                    // matching UP belongs to that submission, not a new click.
                    event.type == KeyEventType.KeyUp && !editing -> true
                    else -> false
                }
            } else false
        }.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() } },
        readOnly = !editing, singleLine = true, interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search,
            keyboardType = keyboardType, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            showKeyboardOnFocus = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { stopEditing(); submit() }),
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = inner, enabled = true,
                singleLine = true, visualTransformation = VisualTransformation.None, interactionSource = interactionSource,
                placeholder = { Text(label, maxLines = 1, fontSize = 13.sp, lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis) }, colors = fieldColors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                container = { OutlinedTextFieldDefaults.Container(enabled = true, isError = false,
                    interactionSource = interactionSource, colors = fieldColors, shape = fieldShape,
                    focusedBorderThickness = 2.dp, unfocusedBorderThickness = 1.dp) })
        })
}

internal fun torrentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 KB"
}
