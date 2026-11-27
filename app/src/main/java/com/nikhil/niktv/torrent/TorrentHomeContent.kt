package com.nikhil.niktv.torrent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.usesPhoneLayout
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
    val longClick: (() -> Unit)? = null, val card: DiscoveryCard? = null,
    val artworkPending: Boolean = false, val open: () -> Unit)
private data class HomeRail(val key: String, val title: String, val entries: List<HomeTile>, val loading: Boolean,
    val error: String?, val load: () -> Unit, val viewAll: (() -> Unit)? = null, val square: Boolean = false) {
    val viewAllKey: String get() = "home:$key:all"

}

/** Pure public-metadata Home, built with the same hero, fades and poster tiles as IPTV. */
@Composable
internal fun TorrentHomeContent(state: TorrentHomeState, library: TorrentLibraryState, actions: TorrentHomeActions, focus: TorrentHomeFocus,
    returnKey: String?, modifier: Modifier = Modifier,
    configuration: TorrentDiscoveryConfiguration, series: Boolean, showLibrary: Boolean,
    displayed: DiscoveryCard? = null,
    presentation: (DiscoveryCard?) -> Unit, contentFocused: Boolean, popup: Boolean, reduceMotion: Boolean,
    nativeFocusRestorer: NativeDiscoveryFocusRestorer? = null) {
    val device = LocalConfiguration.current
    val television = LocalContext.current.isTvLikeDevice(device)
    val phone = device.usesPhoneLayout(television)
    val density = LocalDensity.current
    var activity by remember { mutableStateOf(HeroActivity(false, false, false, false, false, false)) }
    var preview by remember { mutableStateOf<DiscoveryCard?>(null) }
    val publishPreview by rememberUpdatedState(presentation)
    val history = remember(library.continueWatching, showLibrary, phone, actions) {
        val entries = library.continueWatching
        if (!showLibrary || entries.isEmpty()) null else HomeRail("continue", "Continue Watching",
            entries.take(10).map { entry ->
                val key = "home:continue:${entry.key}"
                HomeTile(key, entry.title, entry.poster ?: entry.backdrop ?: entry.episodeStill,
                    entry.subtitle, progress = entry.progress, badge = entry.episodeBadge(),
                    longClick = { actions.libraryOptions(entry, key) }, card = entry.card,
                    open = { actions.resumeLibrary(entry, key) })
            }, false, null, {}, viewAll = if (entries.size > 10) ({
                actions.libraryPage(TorrentLibraryPage.CONTINUE_WATCHING, "home:continue:all")
            }) else null)
    }
    val shelves = remember(state.shelves, actions) { state.shelves.map { shelf ->
        HomeRail(shelf.key, shelf.title, shelf.cards.map { card ->
            val key = "home:${shelf.section.name}:${card.key}"
            HomeTile(key, card.title, card.poster ?: card.backdrop, card.date?.take(4).orEmpty(), card = card, open = { actions.openTitle(card, key) })
        }, shelf.loading || (!shelf.loaded && shelf.cards.isEmpty() && shelf.error == null), shelf.error, { actions.shelf(shelf.section) },
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
            HomeTile(key, genre.name, state.genreArtwork[artwork], artworkPending = artwork !in state.genreArtwork, visible = { actions.genreArtwork(artwork) },
                open = { actions.openCollection(TorrentHomeCollection("genre:${market.name}:${genre.id}",
                    "${market.label} · ${genre.name}", series, genre = genre, market = market), key) })
        }, state.genresLoading || (!state.genresLoaded && state.genresError == null), state.genresError, actions.genres)
    } }
    val providers = remember(configuration.providers, state.providers, state.providersLoading, state.providersError, series, actions) {
        if (!configuration.providers) null else
        HomeRail("home:providers", "Providers", state.providers.map { provider ->
            val key = "home:providers:${provider.id}"
            HomeTile(key, provider.name, provider.logo, open = { actions.openCollection(TorrentHomeCollection(
                "provider:${provider.id}", provider.name, series, provider = provider), key) })
        }, state.providersLoading || (!state.providersLoaded && state.providersError == null), state.providersError, actions.providers, square = true)
    }
    val rows = remember(history, shelves, genres, providers) { listOfNotNull(history) + shelves + genres + listOfNotNull(providers) }
    val nativeRows = remember(rows) {
            rows.map { row ->
                NativeDiscoveryRow(row.key, if (row.entries.isEmpty() && !row.loading && row.error == null)
                    "${row.title} · No titles available" else row.title,
                    row.entries.map { tile ->
                        NativeDiscoveryItem(tile.key, tile.title,
                            listOfNotNull(tile.badge, tile.subtitle.takeIf(String::isNotBlank)).distinct().joinToString(" · "),
                            tile.artwork, tile.open, tile.longClick, tile.progress,
                            source = if (row.square || row.key.contains("genres")) null else DiscoveryTileSource.TMDB,
                            fitArtwork = row.square, onVisible = tile.visible, artworkPending = tile.artworkPending)
                    }, actionLabel = row.error?.let { "Retry" }, action = row.load.takeIf { row.error != null },
                    viewAll = row.viewAll, loading = row.loading)
            }
        }
    val heroes = remember(state.shelves, torrentCurrentDate()) { state.heroes }
    val hero = displayed ?: heroes.getOrNull(state.heroIndex) ?: heroes.firstOrNull()
    if (phone) {
        TorrentPhoneFeed(nativeRows, hero,
            hero != null && library.isSaved(hero),
            bodyFocus = focus.requester("home:native"), playFocus = focus.requester("home:hero"),
            open = { hero?.let { actions.openTitle(it, "home:hero") } },
            save = { hero?.let(actions.toggleSaved) },
            previous = { if (heroes.isNotEmpty()) actions.hero(Math.floorMod(state.heroIndex - 1, heroes.size)) },
            next = { if (heroes.isNotEmpty()) actions.hero((state.heroIndex + 1) % heroes.size) },
            index = state.heroIndex, count = heroes.size, popup = popup, reduceMotion = reduceMotion,
            visible = { id -> rows.firstOrNull { it.key == id }?.load?.invoke() }, focusRestorer = nativeFocusRestorer)
        LaunchedEffect(configuration.sections) { configuration.sections.take(2).forEach(actions.shelf) }
        return
    }
    val selected by rememberUpdatedState<(String, String) -> Unit> { rowKey, itemKey ->
        val row = rows.firstOrNull { it.key == rowKey }
        val tile = row?.entries?.firstOrNull { it.key == itemKey }
        if (tile != null) { preview = tile.card; focus.focused(tile.key) }
        else if (row != null && itemKey == "__row_all__") focus.focused(row.viewAllKey)
    }
    LaunchedEffect(preview, contentFocused, activity.scrolling) {
        val card = preview ?: return@LaunchedEffect
        if (contentFocused && !activity.scrolling) {
            delay(150)
            publishPreview(card)
        }
    }
    val currentHeroIndex by rememberUpdatedState(state.heroIndex)
    LaunchedEffect(activity.mayRotate, popup, reduceMotion, heroes.size) {
        if (activity.mayRotate && !popup && !reduceMotion && heroes.size > 1) while (true) {
            delay(8_000)
            publishPreview(null)
            actions.hero((currentHeroIndex + 1) % heroes.size)
        }
    }
    val heroHeight = trialHeroFrameHeight(
        trialHeroBaseHeight(device.screenHeightDp.toFloat(), true, television), density.fontScale).dp
    // Match IPTV Home: retain native row/card pools and viewport state instead of
    // composing whole poster rails as they enter a nested LazyColumn/LazyRow.
    RecycledDiscoveryRows(nativeRows, modifier.fillMaxSize().onPlaced { focus.placed("home:native") },
        focusEntry = focus.requester("home:native"),
        focusRestorer = nativeFocusRestorer,
        up = { runCatching { focus.requester("search").requestFocus() } },
        visible = { key -> rows.firstOrNull { it.key == key }?.load?.invoke() },
        animateFocus = !reduceMotion, cacheTileDrawing = television,
        containedCaptions = true, cardShadows = true, transparentBackground = true,
        showSkeletons = true, reconcileSkeletons = true, restoreTouchSelection = true,
        restoreSelection = true, restoreViewportOnEntry = true,
        retainFocusWhileLoading = true,
        heroHeightPx = with(density) { heroHeight.roundToPx() },
        focused = { row, item -> selected(row, item) },
        heroActivity = { activity = it },
        heroFocusEntry = { runCatching { focus.requester("home:hero").requestFocus() }.getOrDefault(false) },
        hero = { focused, down, up ->
            Box(Modifier.fillMaxWidth().onPlaced { focus.placed("home:hero") }) {
            // The recycled frame uses IPTV Home's compact hero budget, including on TV.
            // The full-size variant consumes that frame before the action row is measured.
            DiscoveryHero(hero, null, heroHeight, landscape = true, compact = true, detail = false,
                    loading = hero == null && state.shelves.any { it.loading },
                    inList = hero != null && library.isSaved(hero),
                    playRequester = focus.requester("home:hero"), infoRequester = remember { FocusRequester() },
                    play = { hero?.let { actions.openTitle(it, "home:hero") } }, add = { hero?.let(actions.toggleSaved) },
                    info = { hero?.let { actions.openTitle(it, "home:hero") } },
                    onFocus = { preview = null; focus.focused("home:hero"); focused("play") },
                    onDown = down, onUp = { up(); true },
                    previous = { if (heroes.isNotEmpty()) { publishPreview(null); actions.hero(Math.floorMod(state.heroIndex - 1, heroes.size)) } },
                    next = { if (heroes.isNotEmpty()) { publishPreview(null); actions.hero((state.heroIndex + 1) % heroes.size) } },
                    carousel = heroes.size > 1, index = state.heroIndex, count = heroes.size,
                    playLabel = "Open", openDetails = true, showListAction = true)
            }
        })
    LaunchedEffect(configuration.sections) { configuration.sections.take(2).forEach(actions.shelf) }
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
