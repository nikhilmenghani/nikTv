package com.nikhil.niktv.discovery

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal val LocalLibraryTab = staticCompositionLocalOf<FocusRequester?> { null }

/** Cache reads only: selecting a saved item must never start IPTV matching or stream preparation. */
internal suspend fun libraryCachedPresentation(cache: DiscoveryCache, entry: LibraryEntry): DiscoveryCard? =
    withContext(Dispatchers.IO) { readLibraryCachedPresentation(cache, entry) }

private suspend fun readLibraryCachedPresentation(cache: DiscoveryCache, entry: LibraryEntry): DiscoveryCard? {
    if (entry.live) return null
    val seed = providerDetailFallback(entry.media, entry.type)
    val id = entry.media.externalTmdbId ?: entry.media.id.takeIf { entry.favorite?.source == FavoriteSource.TMDB }
        ?.substringAfterLast(':')?.toIntOrNull()
    val json = Json { ignoreUnknownKeys = true }
    if (id != null && id > 0) {
        cache.read("detail-v4:${if (entry.type == CatalogType.SERIES) "tv" else "movie"}:$id")?.let {
            runCatching { json.decodeFromString(DiscoveryDetails.serializer(), it.payload).card }.getOrNull()?.let { return it }
        }
    }
    val title = if (entry.type == CatalogType.SERIES) providerSeriesTmdbTitle(entry.media.title) else providerTmdbTitle(entry.media.title)
    val key = "provider-presentation-v1:${entry.type.name}:${entry.media.externalTmdbId}:$title"
    val public = cache.read(key)?.let { runCatching {
        json.decodeFromString(ListSerializer(DiscoveryCard.serializer()), it.payload).firstOrNull()
    }.getOrNull() }
    return public ?: seed
}

@Composable
internal fun DiscoveryLibraryFeed(state: NikTvState, destination: DiscoveryLibraryDestination, navigationEpoch: Long,
    openFavorite: (FavoriteItem) -> Unit, toggleFavorite: (FavoriteItem) -> Unit,
    openRecent: (RecentItem) -> Unit, openChannel: (MediaItem, List<MediaItem>, PlaybackQueueFilter) -> Unit,
    openCategory: (Category) -> Unit, browseCategories: () -> Unit, configureCategories: () -> Unit,
    browseSaved: (FavoriteKind?) -> Unit, search: () -> Unit,
    refreshProgramme: (MediaItem) -> Unit, enrichGuides: (List<MediaItem>) -> Unit,
    background: (DiscoveryCard?) -> Unit) {
    val profile = state.savedProfile?.cacheKey().orEmpty()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tv = context.isTvLikeDevice(configuration)
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, tv)
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val live = destination == DiscoveryLibraryDestination.LIVE_TV
    val favoritesHero = !live && libraryFavoriteHeroes(state.favorites, profile).isNotEmpty()
    var filter by rememberSaveable(profile, destination) { mutableStateOf<FavoriteKind?>(null) }
    var sort by rememberSaveable(profile, destination) { mutableStateOf(LibrarySort.RECENT) }
    var query by rememberSaveable(profile, destination) { mutableStateOf("") }
    var pinRevision by remember(profile) { mutableIntStateOf(0) }
    val pins = remember(profile, state.categories, pinRevision) { state.categories.associate {
        it.id to IptvPinPreferences.pinnedChannelOrder(context, profile, it.id)
    } }
    val progress = rememberDiscoveryProgressLookup(profile, state.recentlyPlayed, state.playbackProgress)
    val feedIdentity = "$profile:$destination"
    val loadedRows by produceState(feedIdentity to emptyList<LibraryRow>(), state.playbackProgress, progress,
        state.favorites, state.recentlyPlayed, state.browseCachesByType, state.categories, profile, live, filter, query, sort, pins) {
        value = feedIdentity to withContext(Dispatchers.Default) {
        if (live) libraryLiveRows(state, profile, pins) else {
            val savedRows = librarySavedRows(state, profile, filter, query, sort)
            val underway = savedRows.flatMap { it.entries }.filter { progress?.provider(it.type, it.media.id)?.let { fraction -> fraction > 0f && fraction < .98f } == true }
            if (underway.isEmpty()) savedRows else listOf(LibraryRow("continue-saved", "Continue watching", underway)) + savedRows
        }
        }
    }
    val rows = loadedRows.takeIf { it.first == feedIdentity }?.second.orEmpty()
    val latestRows by rememberUpdatedState(rows)
    var returnToHero by rememberSaveable(profile, destination) { mutableStateOf(false) }
    var returnToInfo by rememberSaveable(profile, destination) { mutableStateOf(false) }
    var returning by rememberSaveable(profile, destination) { mutableStateOf(false) }
    var returnEpoch by rememberSaveable(profile, destination) { mutableLongStateOf(0L) }
    var selectedRow by rememberSaveable(profile, destination) { mutableStateOf<String?>(null) }
    var selectedIndex by rememberSaveable(profile, destination) { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(profile, destination) { mutableStateOf<String?>(null) }
    val selected = rows.firstOrNull { it.key == selectedRow }?.entries?.getOrNull(selectedIndex)?.takeIf { it.key == selectedId }
        ?: rows.firstOrNull()?.entries?.firstOrNull()
    val locked = selected != null && (selected.media.isAdult || adultContentLabel(selected.media.title) ||
        selected.category?.isAdult == true) && ParentalControls.enabled(context) && !ParentalControls.unlocked(context)
    val cache = remember(context) { DiscoveryCache(File(context.cacheDir, "discovery_preview")) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val coordinator = remember(scope) { DiscoveryScrollCoordinator(scope) }
    DisposableEffect(coordinator) { onDispose { coordinator.cancel() } }
    val scrolling = list.isScrollInProgress
    val details = LocalOpenProviderDiscovery.current
    val presentationKey = "$profile:$destination:${selected?.key}"
    val cachedPresentation by produceState<Pair<String, DiscoveryCard?>?>(null, presentationKey, locked, scrolling) {
        value = null
        if (!locked && selected != null && !scrolling) {
            delay(150)
            value = presentationKey to libraryCachedPresentation(cache, selected)
        }
    }
    val card = cachedPresentation?.takeIf { it.first == presentationKey && !locked }?.second
    SideEffect { if (!favoritesHero && !scrolling) background(card) }
    DisposableEffect(Unit) { onDispose { background(null) } }
    val entryRequesters = remember(profile, destination) { mutableMapOf<String, FocusRequester>() }
    val rowRequesters = remember(profile, destination) { mutableMapOf<String, suspend () -> Boolean>() }
    val play = remember { FocusRequester() }
    val info = remember { FocusRequester() }
    val tab = LocalLibraryTab.current
    var remove by remember { mutableStateOf<FavoriteItem?>(null) }
    var guide by remember { mutableStateOf<MediaItem?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), profile) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val current = selected?.takeIf { it.live }?.media?.let { state.withCachedLiveGuide(profile, it) }
    val programme = current?.currentLiveProgramme(now)
    val livePresentation = current?.let { liveTvTilePresentation(it.title, selected?.category?.title.orEmpty(), state.savedProfile?.portalType == PortalType.XTREAM) }
    val title = if (locked) "Sensitive content" else if (live) livePresentation?.title ?: "Live TV" else card?.title ?: selected?.media?.title ?: "My List"
    val overview = if (locked) "Unlock to view this content." else if (live) programme?.let(::liveProgrammeSummary)
        ?: "Choose a channel or browse your categories." else card?.overview ?: selected?.media?.description.orEmpty()
    val width = if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp).dp else discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat()).dp
    fun saved(entry: LibraryEntry) = entry.favorite ?: FavoriteItem(entry.kind, entry.media, profileKey = profile, categoryTitle = entry.category?.title)
    fun open(entry: LibraryEntry, hero: Boolean = false) {
        returnToHero = hero; returnToInfo = false
        returning = true; returnEpoch = navigationEpoch
        when {
            entry.favorite != null -> openFavorite(entry.favorite)
            entry.recent != null -> openRecent(entry.recent)
            entry.live -> {
                val row = rows.firstOrNull { it.entries.any { item -> item === entry } }
                val (queue, filter) = libraryLiveQueue(state, profile, entry, row?.entries?.map { it.media }.orEmpty())
                openChannel(entry.media, queue, filter)
            }
        }
    }
    fun showInfo(entry: LibraryEntry) {
        returnToHero = true; returnToInfo = true
        returning = true; returnEpoch = navigationEpoch
        if (entry.favorite?.source == FavoriteSource.TMDB) openFavorite(entry.favorite)
        else details(entry.type, entry.media, rows.firstOrNull { it.key == selectedRow }?.entries?.map { it.media }.orEmpty().ifEmpty { listOf(entry.media) })
    }
    fun closeGuide() { guide = null; scope.launch { withFrameNanos { }; runCatching { info.requestFocus() } } }
    fun restoreTile() { scope.launch { withFrameNanos { }; runCatching { entryRequesters["$selectedRow:$selectedId:$selectedIndex"]?.requestFocus() } } }
    fun move(rowIndex: Int): Boolean {
        if (rowIndex < 0) {
            // The landscape hero can be outside LazyColumn's composed viewport.
            // Reveal it before requesting its action, using the same cancellable coordinator.
            coordinator.move(list, 0, reduceMotion) { runCatching { play.requestFocus() }.getOrDefault(false) }
            return true
        }
        val row = rows.getOrNull(rowIndex) ?: return false
        coordinator.move(list, rowIndex + 2, reduceMotion) { rowRequesters[row.key]?.invoke() == true }
        return true
    }
    LaunchedEffect(Unit) {
        if (returning && returnEpoch == navigationEpoch) {
            repeat(3) { withFrameNanos { } }
            val index = if (returnToHero) -1 else withTimeoutOrNull(2_000L) {
                snapshotFlow { latestRows.indexOfFirst { it.key == selectedRow } }.first { it >= 0 }
            } ?: -1
            if (returnToHero) { runCatching { (if (returnToInfo) info else play).requestFocus() }; returning = false }
            else if (index >= 0) coordinator.move(list, index + 2, reduceMotion) {
                (rowRequesters[selectedRow]?.invoke() == true).also { if (it) returning = false }
            }
        } else returning = false
    }
    DiscoveryVerticalFocusViewport(coordinator) {
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item("hero") {
                if (favoritesHero) DiscoveryFavoritesHero(state, scrolling || list.firstVisibleItemIndex > 0,
                    open = { favorite -> open(LibraryEntry(favorite.media, favorite.kind, favorite = favorite), hero = true) },
                    toggleFavorite = toggleFavorite, presentation = background, playRequester = play, infoRequester = info,
                    showInfo = { favorite -> showInfo(LibraryEntry(favorite.media, favorite.kind, favorite = favorite)) },
                    onDown = { move(0) }, onUp = { runCatching { tab?.requestFocus() == true }.getOrDefault(false) })
                else Column(Modifier.fillMaxWidth().heightIn(min = if (selected == null) 130.dp else if (phone && live) 175.dp else if (phone) 220.dp else if (configuration.screenHeightDp < 600) 185.dp else 240.dp)
                    .padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (live) listOfNotNull("LIVE TV", livePresentation?.context, livePresentation?.quality).joinToString(" · ") else "YOUR COLLECTION", color = Color(0xFFBFC3CD), style = MaterialTheme.typography.labelMedium)
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (phone) 25.sp else 32.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!live && card != null) Text(listOfNotNull(card?.date?.take(4), card?.genres?.take(3)?.joinToString(" · ")?.takeIf { it.isNotBlank() }).joinToString(" · "), color = Color.LightGray)
                    if (overview.isNotBlank()) Text(overview, color = Color(0xFFCFD1D5), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 620.dp), style = MaterialTheme.typography.bodyMedium)
                    if (live && programme?.startTimeMillis != null && programme.endTimeMillis != null && programme.endTimeMillis > programme.startTimeMillis) {
                        val fraction = ((now - programme.startTimeMillis).toFloat() / (programme.endTimeMillis - programme.startTimeMillis)).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(3.dp), color = Color(0xFFE50925))
                    }
                    if (selected != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when(event.key) {
                            Key.DirectionUp -> runCatching { tab?.requestFocus() == true }.getOrDefault(false)
                            Key.DirectionDown -> move(0)
                            else -> false
                        }
                    }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DiscoveryAction(if (live) "Watch" else if (selected?.let { progress?.provider(it.type, it.media.id) }?.let { it > 0f } == true) "Resume" else "Play", Icons.Default.PlayArrow, selected = true,
                            enabled = selected != null, modifier = Modifier.focusRequester(play).focusProperties { up = tab ?: FocusRequester.Default }
                                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) move(0) else false }) { selected?.let { open(it, hero = true) } }
                        DiscoveryAction(if (selected?.favorite != null) "Remove" else "My List", Icons.Default.FavoriteBorder, enabled = selected != null) {
                            selected?.let { if (it.favorite != null) remove = it.favorite else toggleFavorite(saved(it)) }
                        }
                        DiscoveryAction(if (live) "Guide" else "Info", if (live) Icons.Default.CalendarMonth else Icons.Default.Info,
                            enabled = selected != null && !locked, modifier = Modifier.focusRequester(info)) { selected?.let { if (live) guide = it.media else showInfo(it) } }
                    }
                }
            }
            item("controls") {
                DiscoveryHorizontalFocusViewport(16.dp) {
                    LazyRow(Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when(event.key) {
                            Key.DirectionUp -> runCatching { play.requestFocus() }.getOrDefault(false)
                            Key.DirectionDown -> move(0)
                            else -> false
                        }
                    }, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (live) {
                            item { DiscoveryAction("All categories", Icons.Default.GridView, onClick = browseCategories) }
                            item { DiscoveryAction("Choose categories", Icons.Default.Tune, onClick = configureCategories) }
                            item { DiscoveryAction("Search channels", Icons.Default.Search, onClick = search) }
                        } else {
                            items(listOf<FavoriteKind?>(null) + FavoriteKind.entries, key = { it?.name ?: "all" }) { kind ->
                                DiscoveryAction(kind?.let { when(it) { FavoriteKind.CHANNEL -> "Live TV"; FavoriteKind.MOVIE -> "Movies"; FavoriteKind.SERIES -> "Series"; else -> "Episodes" } } ?: "All",
                                    selected = filter == kind) { filter = kind }
                            }
                            item { DiscoveryCollectionSearch(query, { query = it }, phone) }
                            item { Box {
                                DiscoveryAction(sort.title, Icons.Default.Sort) { sortOpen = true }
                                DropdownMenu(sortOpen, { sortOpen = false }) { LibrarySort.entries.forEach { choice ->
                                    NikDropdownMenuItem(text = { Text(choice.title) }, onClick = { sort = choice; sortOpen = false })
                                } }
                            } }
                            item { DiscoveryAction("View all", Icons.Default.GridView) { browseSaved(filter) } }
                        }
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                val rowState = rememberLazyListState()
                var last by rememberSaveable(row.key) { mutableStateOf<String?>(null) }
                val visibleEntries = remember(row) { if (row.category != null) row.entries.take(12) else row.entries }
                val currentKeys by rememberUpdatedState(visibleEntries.mapIndexed { ordinal, item -> "${row.key}:${item.key}:$ordinal" })
                DisposableEffect(row.key) {
                    rowRequesters[row.key] = {
                        val key = last?.takeIf { it in currentKeys } ?: currentKeys.firstOrNull()
                        val target = currentKeys.indexOf(key).coerceAtLeast(0)
                        if (rowState.layoutInfo.visibleItemsInfo.none { it.index == target }) rowState.scrollToItem(target)
                        withFrameNanos { }
                        runCatching { entryRequesters[key]?.requestFocus() == true }.getOrDefault(false)
                    }
                    onDispose { rowRequesters.remove(row.key) }
                }
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (!live || row.category != null) DiscoveryAction("View all", Icons.Default.ChevronRight) { row.category?.let(openCategory) ?: browseSaved(row.entries.firstOrNull()?.kind ?: filter) }
                    }
                    DiscoveryHorizontalFocusViewport(16.dp) {
                        LazyRow(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) when (event.key) {
                                Key.DirectionUp -> move(index - 1); Key.DirectionDown -> move(index + 1); else -> false
                            } else false
                        }, state = rowState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(visibleEntries, key = { ordinal, entry -> "${entry.key}:$ordinal" }) { ordinal, entry ->
                                val key = "${row.key}:${entry.key}:$ordinal"
                                val requester = remember(key) { FocusRequester() }
                                DisposableEffect(key, requester) { entryRequesters[key] = requester; onDispose { if (entryRequesters[key] === requester) entryRequesters.remove(key) } }
                                var focused by remember { mutableStateOf(false) }
                                LaunchedEffect(focused) {
                                    if (focused) {
                                        selectedRow = row.key; selectedId = entry.key; selectedIndex = ordinal; last = key
                                        coordinator.align(list, index + 2, reduceMotion)
                                        if (entry.live) { delay(150); enrichGuides(row.entries.drop((ordinal - 2).coerceAtLeast(0)).take(8).map { it.media }) }
                                    }
                                }
                                val sensitive = (entry.media.isAdult || adultContentLabel(entry.media.title) || entry.category?.isAdult == true) && ParentalControls.enabled(context) && !ParentalControls.unlocked(context)
                                Box(Modifier.width(if (entry.live) (if (phone) 240.dp else 220.dp) else width).onFocusChanged { focused = it.hasFocus }.focusRequester(requester)) {
                                    if (entry.live && !sensitive) {
                                        val channel = state.withCachedLiveGuide(profile, entry.media)
                                        ModernLiveChannelTile(channel, { refreshProgramme(channel) }, xtream = state.savedProfile?.portalType == PortalType.XTREAM,
                                            categoryTitle = entry.category?.title.orEmpty(), guideNow = now, programmeLoading = channel.id in state.liveGuideLoadingIds,
                                            programmeWaitingUntil = state.liveGuideWaitingUntilById[channel.id], programmeStatusNow = now, showQualityBadge = true,
                                            isFavorite = state.favorites.any { it.profileKey == profile && it.kind == FavoriteKind.CHANNEL && it.media.id == channel.id },
                                            onFavorite = { if (entry.favorite != null) remove = entry.favorite else toggleFavorite(saved(entry)) },
                                            isPinned = channel.id in pins[entry.category?.id].orEmpty(), onTogglePin = entry.category?.let { category -> {
                                                IptvPinPreferences.toggleChannel(context, profile, category.id, channel.id); pinRevision++
                                            } }, onClick = { open(entry) }, isTv = tv, compact = true,
                                            onFocusChange = { focused = it })
                                    } else LibrarySavedPoster(entry, state, if (entry.live) (if (phone) 240.dp else 220.dp) else width, sensitive, progress?.provider(entry.type, entry.media.id),
                                        { open(entry) }, { remove = saved(entry) }, { focused = true })
                                }
                            }
                        }
                    }
                }
            }
            if (live) {
                item("category-shortcuts") { Text("Channel categories", Modifier.padding(horizontal = 24.dp), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                items(state.categories.filter { it.type == CatalogType.LIVE_TV }.chunked(if (phone) 2 else 4), key = { group -> "category-group:${group.first().id}" }) { group ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { category -> DiscoveryAction(category.title, modifier = Modifier.weight(1f)) { openCategory(category) } }
                        repeat((if (phone) 2 else 4) - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (rows.isEmpty()) item("empty") {
                Text(if (live) "Open a category to load its channels. Recently watched and cached categories will appear here." else
                    if (query.isNotBlank()) "No saved titles match your search." else "Save movies, series or channels to build your collection.",
                    Modifier.padding(24.dp), color = Color.LightGray)
            }
        }
    }
    remove?.let { favorite -> LibraryRemoveDialog(favorite, { remove = null; restoreTile() }, {
        remove = null; toggleFavorite(favorite); restoreTile()
    }) }
    guide?.let { channel ->
        val guideClose = remember(channel.id) { FocusRequester() }
        LaunchedEffect(channel.id) { withFrameNanos { }; runCatching { guideClose.requestFocus() } }
        AlertDialog(onDismissRequest = ::closeGuide, containerColor = Color(0xFF181B22),
            title = { Text(channel.title) }, text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                val cached = state.withCachedLiveGuide(profile, channel)
                val schedule = cached.liveSchedule.ifEmpty { listOfNotNull(cached.currentLiveProgramme(now)) }
                if (schedule.isEmpty()) Text("No cached programme information. You can fetch an update below.")
                schedule.forEach { Text(liveProgrammeSummary(it), Modifier.padding(vertical = 8.dp)) }
            } }, confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoveryAction("Fetch programme") { refreshProgramme(channel) }
                DiscoveryAction("Close", modifier = Modifier.focusRequester(guideClose), onClick = ::closeGuide)
            } })
    }
}

@Composable
internal fun LibrarySavedPoster(entry: LibraryEntry, state: NikTvState, width: Dp, sensitive: Boolean,
    progress: Float?, open: () -> Unit, remove: () -> Unit, focus: () -> Unit, focusInset: Dp = 0.dp) {
    var menu by remember { mutableStateOf(false) }
    val reduceMotion = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(configuration))
    if (phone && !entry.live) DiscoveryPhoneMediaCard(if (sensitive) "Sensitive content" else entry.media.title,
        if (sensitive) null else entry.media.logo, entry.favorite?.categoryTitle ?: entry.kind.name.lowercase().replaceFirstChar(Char::uppercase), width,
        wide = false, progress = progress, onClick = open, onLongClick = { menu = true }, onFocus = focus)
    else DiscoveryPosterTile(if (sensitive) "Sensitive content" else entry.media.title, if (sensitive) null else entry.media.logo,
        entry.favorite?.categoryTitle ?: entry.kind.name.lowercase().replaceFirstChar(Char::uppercase), width,
        reduceMotion = reduceMotion, aspect = if (entry.live) 16f / 9f else 2f / 3f, focusVerticalInset = focusInset, progress = progress, onClick = open, onLongClick = { menu = true }, onFocus = focus)
    ModernTileActionsMenu(menu, true, dismiss = { menu = false }, toggleFavorite = remove, clear = null,
        trailerItem = entry.media.takeUnless { sensitive }, trailerSeries = entry.type == CatalogType.SERIES,
        playAction = open, viewDescription = null)
}

@Composable
internal fun LibraryRemoveDialog(favorite: FavoriteItem, close: () -> Unit, remove: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
    AlertDialog(onDismissRequest = close, containerColor = Color(0xFF181B22),
        title = { Text("Remove from My List?") }, text = { Text(favorite.media.title) }, confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(close, Modifier.weight(1f).focusRequester(cancel)) { Text("Keep") }
                NikTvPrimaryActionButton(remove, Modifier.weight(1f)) { Text("Remove") }
            }
        })
}
