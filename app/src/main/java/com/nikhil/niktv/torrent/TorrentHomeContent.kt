package com.nikhil.niktv.torrent

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.usesPhoneLayout
import com.nikhil.niktv.ui.usesShortWideLayout
import kotlinx.coroutines.delay

internal data class TorrentHomeActions(
    val openTitle: (DiscoveryCard, String) -> Unit,
    val openCollection: (TorrentHomeCollection, String) -> Unit,
    val hero: (Int) -> Unit,
    val shelf: (TmdbHomeSection) -> Unit,
    val genres: () -> Unit,
    val providers: () -> Unit,
    val genreArtwork: (GenreArtworkKey) -> Unit,
    val toggleSaved: (DiscoveryCard) -> Unit,
    val resumeLibrary: (TorrentLibraryEntry, String) -> Unit,
    val libraryPage: (TorrentLibraryPage, String) -> Unit,
    val libraryOptions: (TorrentLibraryEntry, String) -> Unit
)
internal data class TorrentHomeFocus(
    val modifier: (String) -> Modifier,
    val requester: (String) -> FocusRequester,
    val placed: (String) -> Unit,
    val focused: (String) -> Unit,
    val handoff: (String, () -> Unit) -> Unit
)
private data class HomeTile(val key: String, val title: String, val artwork: String?, val subtitle: String = "",
    val visible: (() -> Unit)? = null, val progress: Float? = null, val badge: String? = null,
    val longClick: (() -> Unit)? = null, val card: DiscoveryCard? = null, val open: () -> Unit)
private data class HomeRail(val key: String, val title: String, val entries: List<HomeTile>, val loading: Boolean,
    val error: String?, val load: () -> Unit, val viewAll: (() -> Unit)? = null, val square: Boolean = false, val wide: Boolean = false) {
    val viewAllKey: String get() = "home:$key:all"
    fun indexOf(itemKey: String?): Int =
        if (viewAll != null && entries.isNotEmpty() && itemKey == viewAllKey) entries.size else entries.indexOfFirst { it.key == itemKey }
}

/** Pure public-metadata Home, built with the same hero, fades and poster tiles as IPTV. */
@Composable
internal fun TorrentHomeContent(state: TorrentHomeState, library: TorrentLibraryState, actions: TorrentHomeActions, focus: TorrentHomeFocus,
    returnKey: String?, modifier: Modifier = Modifier,
    configuration: TorrentDiscoveryConfiguration, series: Boolean, showLibrary: Boolean,
    displayed: DiscoveryCard? = null,
    presentation: (DiscoveryCard?) -> Unit, contentFocused: Boolean, popup: Boolean, reduceMotion: Boolean,
    phoneHeader: (@Composable () -> Unit)? = null) {
    val device = LocalConfiguration.current
    val television = LocalContext.current.isTvLikeDevice(device)
    val phone = device.usesPhoneLayout(television)
    val short = device.usesShortWideLayout(television)
    val density = LocalDensity.current
    val scroll = rememberLazyListState()
    val rowSelection = remember { mutableStateMapOf<String, String>() }
    var preview by remember { mutableStateOf<DiscoveryCard?>(null) }
    val publishPreview by rememberUpdatedState(presentation)
    val tileWidth = if (phone) phoneDiscoveryPosterWidth(device.screenWidthDp).dp else if (short) 92.dp else 120.dp
    val history = remember(library.continueWatching, showLibrary, phone, actions) {
        val entries = library.continueWatching
        if (!showLibrary || entries.isEmpty()) null else HomeRail("continue", "Continue Watching",
            entries.take(10).map { entry ->
                val key = "home:continue:${entry.key}"
                HomeTile(key, entry.title, if (phone)
                    entry.episodeStill ?: entry.backdrop ?: entry.poster else entry.poster ?: entry.backdrop,
                    entry.subtitle, progress = entry.progress, badge = entry.episodeBadge(),
                    longClick = { actions.libraryOptions(entry, key) }, card = entry.card,
                    open = { actions.resumeLibrary(entry, key) })
            }, false, null, {}, viewAll = if (entries.size > 10) ({
                actions.libraryPage(TorrentLibraryPage.CONTINUE_WATCHING, "home:continue:all")
            }) else null, wide = true)
    }
    val shelves = remember(state.shelves, actions) { state.shelves.map { shelf ->
        HomeRail(shelf.key, shelf.title, shelf.cards.map { card ->
            val key = "home:${shelf.section.name}:${card.key}"
            HomeTile(key, card.title, card.poster, card.date?.take(4).orEmpty(), card = card, open = { actions.openTitle(card, key) })
        }, shelf.loading, shelf.error, { actions.shelf(shelf.section) },
            viewAll = if (shelf.hasMore) ({ actions.openCollection(TorrentHomeCollection(shelf.key, shelf.title,
                shelf.section.series, section = shelf.section), "home:${shelf.key}:all") }) else null)
    } }
    val genres = remember(configuration.genres, configuration.genreMarkets, state.genres, state.genreArtwork,
        state.genresLoading, state.genresError, series, actions) { GenreMarket.entries.filter {
        configuration.genres && it in configuration.genreMarkets
    }.map { market ->
        HomeRail("home:genres:${market.name}", market.heading, state.genres.map { genre ->
            val key = "home:genres:${market.name}:${genre.id}"
            val artwork = GenreArtworkKey(series, genre.id, market)
            HomeTile(key, genre.name, state.genreArtwork[artwork], visible = { actions.genreArtwork(artwork) },
                open = { actions.openCollection(TorrentHomeCollection("genre:${market.name}:${genre.id}",
                    "${market.label} · ${genre.name}", series, genre = genre, market = market), key) })
        }, state.genresLoading, state.genresError, actions.genres)
    } }
    val providers = remember(configuration.providers, state.providers, state.providersLoading, state.providersError, series, actions) {
        if (!configuration.providers) null else
        HomeRail("home:providers", "Providers", state.providers.map { provider ->
            val key = "home:providers:${provider.id}"
            HomeTile(key, provider.name, provider.logo, open = { actions.openCollection(TorrentHomeCollection(
                "provider:${provider.id}", provider.name, series, provider = provider), key) })
        }, state.providersLoading, state.providersError, actions.providers, square = true)
    }
    val rows = remember(history, shelves, genres, providers) { listOfNotNull(history) + shelves + genres + listOfNotNull(providers) }
    LaunchedEffect(preview, contentFocused, scroll.isScrollInProgress) {
        val card = preview ?: return@LaunchedEffect
        if (contentFocused && !scroll.isScrollInProgress) {
            delay(150) // Let fast D-pad browsing settle before swapping the backdrop.
            publishPreview(card)
        }
    }
    fun target(row: HomeRail): String? = rowSelection[row.key]?.takeIf { row.indexOf(it) >= 0 }
        ?: row.entries.firstOrNull()?.key ?: "home:${row.key}:retry".takeIf { row.error != null }
    fun move(from: Int, delta: Int) {
        val indices = if (delta > 0) (from + 1)..rows.lastIndex else (from - 1 downTo 0)
        val next = indices.firstOrNull { index -> index in rows.indices && target(rows[index]) != null }
        val key = next?.let { target(rows[it]) } ?: if (delta < 0) {
            if (state.hero != null) "home:hero" else "search"
        } else if (phone) "home:dock" else "search"
        focus.handoff(key) {
            val position = next?.plus(1) ?: 0.takeIf { delta < 0 }
            if (position != null && scroll.layoutInfo.visibleItemsInfo.none { it.index == position }) {
                scroll.requestScrollToItem(position)
            }
        }
    }
    LaunchedEffect(configuration.sections) {
        configuration.sections.take(2).forEach(actions.shelf)
    }
    val returnPosition = when (returnKey) {
        null -> null
        "home:hero", "search", "tmdb-categories", "downloads" -> 0
        else -> rows.indexOfFirst { it.indexOf(returnKey) >= 0 || returnKey == "home:${it.key}:retry" }
            .takeIf { it >= 0 }?.plus(1)
    }
    LaunchedEffect(returnKey, returnPosition) {
        if (returnPosition != null && scroll.layoutInfo.visibleItemsInfo.none { it.index == returnPosition }) {
            scroll.requestScrollToItem(returnPosition)
        }
    }
    val heroes = state.heroes
    val hero = displayed ?: state.hero
    val heroVisible by remember { derivedStateOf { scroll.firstVisibleItemIndex == 0 } }
    val foreground = LocalWindowInfo.current.isWindowFocused
    val currentHeroIndex by rememberUpdatedState(state.heroIndex)
    val activity = HeroActivity(heroVisible, foreground, contentFocused, scroll.isScrollInProgress, false, popup)
    LaunchedEffect(activity, state.heroes.size, reduceMotion) {
        if (activity.mayRotate && !reduceMotion && state.heroes.size > 1) while (true) {
            delay(8_000)
            presentation(null)
            actions.hero((currentHeroIndex + 1) % state.heroes.size)
        }
    }
    val heroHeight = if (phone) phoneDiscoveryHeroHeight() else trialHeroFrameHeight(
        trialHeroBaseHeight(device.screenHeightDp.toFloat(), true, television), density.fontScale).dp
    LazyColumn(modifier.fillMaxSize(), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        item("home:hero") {
            Box(Modifier.fillMaxWidth().onPlaced { focus.placed("home:hero") }) {
                DiscoveryHero(hero, null, heroHeight, landscape = !phone, compact = short, detail = false,
                    loading = hero == null && state.shelves.any { it.loading },
                    inList = hero != null && library.saved.any { it.tmdbId == hero.id && it.series == hero.series },
                    playRequester = focus.requester("home:hero"), infoRequester = remember { FocusRequester() },
                    play = { hero?.let { actions.openTitle(it, "home:hero") } }, add = { hero?.let(actions.toggleSaved) },
                    info = { hero?.let { actions.openTitle(it, "home:hero") } }, onFocus = { preview = null; focus.focused("home:hero") },
                    onDown = { move(-1, 1) }, onUp = { runCatching { focus.requester("search").requestFocus() }.getOrDefault(false) },
                    previous = { if (heroes.isNotEmpty()) { presentation(null); actions.hero((state.heroIndex - 1 + heroes.size) % heroes.size) } },
                    next = { if (heroes.isNotEmpty()) { presentation(null); actions.hero((state.heroIndex + 1) % heroes.size) } }, carousel = heroes.size > 1,
                    index = state.heroIndex, count = heroes.size, playLabel = "Open", portraitPhone = phone,
                    openDetails = true, showListAction = true, phoneHeader = phoneHeader)
            }
        }
        items(rows.size, key = { rows[it].key }) { index ->
            val row = rows[index]
            LaunchedEffect(row.key) { row.load() }
            val width = if (phone && row.wide) ((device.screenWidthDp - 32) * .68f).dp.coerceIn(200.dp, 280.dp) else tileWidth
            TorrentHomeRail(row, width, phone, focus, returnKey, reduceMotion,
                selected = { key, card -> rowSelection[row.key] = key; preview = card; focus.focused(key) },
                move = { delta -> move(index, delta) })
        }
    }
}

@Composable private fun TorrentHomeRail(row: HomeRail, width: Dp, phone: Boolean, focus: TorrentHomeFocus,
    returnKey: String?, reduceMotion: Boolean, selected: (String, DiscoveryCard?) -> Unit, move: (Int) -> Unit) {
    val index = row.indexOf(returnKey)
    val wanted = index.coerceAtLeast(0)
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = wanted)
    val height = when {
        row.square -> width
        phone -> phoneDiscoveryCardHeight(width.value, row.wide, LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
        else -> width * 1.5f
    }
    LaunchedEffect(returnKey, index) {
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.requestScrollToItem(index)
    }
    Column(Modifier.padding(horizontal = if (phone) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, Modifier.weight(1f), color = Color.White, fontSize = if (phone) 19.sp else 16.sp,
                lineHeight = if (phone) 24.sp else 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (row.loading && row.entries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        row.error?.let { message ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                DiscoveryAction("Retry", Icons.Default.Refresh, modifier = focus.modifier("home:${row.key}:retry"), onClick = row.load)
            }
        }
        if (row.entries.isEmpty() && !row.loading && row.error == null) Text("No titles available yet", fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
        LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 6.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                    move(if (event.key == Key.DirectionUp) -1 else 1); true
                } else false
            }) {
            if (row.entries.isEmpty() && row.loading) items(5) {
                Box(Modifier.size(width, height)
                    .background(Color.White.copy(alpha = .07f), RoundedCornerShape(10.dp)))
            }
            items(row.entries, key = { it.key }, contentType = { "poster" }) { tile ->
                tile.visible?.let { visible -> LaunchedEffect(tile.key) { visible() } }
                TorrentLibraryPoster(tile.title, tile.artwork, tile.subtitle, width, progress = tile.progress,
                    badge = tile.badge, aspect = if (row.square) 1f else if (phone && row.wide) 16f / 9f else 2f / 3f,
                    modifier = focus.modifier(tile.key), reduceMotion = reduceMotion, onFocus = { selected(tile.key, tile.card) }, onClick = tile.open,
                    onLongClick = tile.longClick)
            }
            if (row.entries.isNotEmpty()) row.viewAll?.let { open ->
                item(key = row.viewAllKey, contentType = "view-all") {
                    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
                        DiscoveryAction("View all",
                            modifier = focus.modifier(row.viewAllKey).semantics { contentDescription = "View all ${row.title}" },
                            onFocus = { selected(row.viewAllKey, null) }, onClick = open)
                    }
                }
            }
        }
    }
}

internal fun TorrentLibraryEntry.episodeBadge(): String? = season?.let { seasonNumber ->
    episode?.let { "S$seasonNumber:E$it" }
}

/** Existing poster card plus an inset episode badge; its focus frame still owns the exact poster bounds. */
@Composable internal fun TorrentLibraryPoster(title: String, artwork: String?, subtitle: String, width: Dp,
    modifier: Modifier = Modifier, progress: Float? = null, badge: String? = null, aspect: Float = 2f / 3f,
    reduceMotion: Boolean = true, onFocus: () -> Unit = {}, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.width(width)) {
        val device = LocalConfiguration.current
        val phone = device.usesPhoneLayout(LocalContext.current.isTvLikeDevice(device))
        if (phone && aspect != 1f) DiscoveryPhoneMediaCard(title, artwork, subtitle, width, wide = aspect > 1f,
            modifier = modifier, progress = progress, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick)
        else DiscoveryPosterTile(title, artwork, subtitle, width, aspect = aspect, reduceMotion = reduceMotion,
                modifier = modifier, progress = progress, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick)
        if (badge != null) Text(badge, Modifier.align(Alignment.TopEnd).padding(6.dp)
            .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .75f)).padding(horizontal = 5.dp, vertical = 3.dp),
            color = Color.White, fontSize = 10.sp, lineHeight = 12.sp)
    }
}
