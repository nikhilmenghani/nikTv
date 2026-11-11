package com.nikhil.niktv.discovery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.*
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.isTvLikeDevice
import kotlinx.coroutines.*
import java.io.File

/** Presentation never supplies playback identity: actions always retain the provider item/queue. */
internal data class ProviderHeroEntry(val item: MediaItem, val queue: List<MediaItem>, val recent: RecentItem? = null) {
    val artwork get() = recent?.media?.logo?.takeIf(String::isNotBlank) ?: item.logo
}

/** Snapshot read by the nested hero composition, independently of recycled-row submissions. */
internal data class ProviderHeroPresentation(val favoriteIds: Set<String>, val progress: DiscoveryProgressLookup?,
    val active: Boolean, val mayRotate: Boolean, val artwork: Boolean, val fadeArtwork: Boolean,
    val rotate: Boolean, val prepareNext: Boolean, val seriesRecents: List<RecentItem> = emptyList())

internal fun providerHeroWaiting(profile: String, type: CatalogType, categories: List<Category>,
    state: ProviderDiscoveryState, poolPrepared: Boolean): Boolean {
    if (categories.isEmpty() || type in state.errors) return false
    if (state.profile != profile || !state.ready || !poolPrepared || type in state.loading) return true
    return providerDiscoverySources(categories).any { (state.pages[type]?.get(it.id)?.page ?: 0) == 0 }
}

internal fun appendProviderHeroes(existing: List<ProviderHeroEntry>, categories: List<Category>,
    pages: Map<String, ProviderDiscoveryPage>, random: kotlin.random.Random? = null): List<ProviderHeroEntry> {
    if (existing.size >= 10) return existing
    val seen = existing.mapTo(mutableSetOf()) { it.item.id }
    val result = existing.toMutableList()
    val candidates = buildList {
    for (category in categories) {
        val queue = providerDiscoveryItems(providerDiscoverySources(listOf(category)), pages)
        for (item in queue) {
            if (seen.add(item.id)) add(ProviderHeroEntry(item, queue))
        }
    }
    }
    result.addAll((if (random == null) candidates else candidates.shuffled(random)).take(10 - result.size))
    return if (result.size == existing.size) existing else result
}

/** Continue Watching drives presentation; provider IDs/commands still own all actions. */
internal fun preferredProviderHeroes(profile: String, type: CatalogType, recents: List<RecentItem>,
    fallback: List<ProviderHeroEntry>): List<ProviderHeroEntry> {
    if (profile.isBlank()) return fallback
    val kind = when (type) { CatalogType.MOVIES -> FavoriteKind.MOVIE; CatalogType.SERIES -> FavoriteKind.SERIES; else -> return fallback }
    val seen = mutableSetOf<String>()
    val recent = recents.asSequence().filter { it.profileKey == profile && it.kind == kind && it.alternateTarget == null }
        .sortedByDescending { it.playedAtMillis }.mapNotNull { entry ->
            val item = if (type == CatalogType.SERIES) entry.series ?: entry.media else entry.media
            if (item.isAdult || entry.media.isAdult || item.discoveryCatalogSource() != DiscoveryTileSource.IPTV || !seen.add(item.id)) null
            else {
                val loaded = fallback.firstOrNull { it.item.id == item.id }
                ProviderHeroEntry(loaded?.item ?: item, loaded?.queue ?: listOf(item), entry)
            }
        }.take(10).toList()
    return recent.ifEmpty { fallback }
}

/** Provider catalog island. Page arrivals do not rebind Home's TMDB hero. */
@Composable
internal fun ProviderDiscoveryHero(profile: String, type: CatalogType, categories: List<Category>,
    providerVm: ProviderDiscoveryViewModel, landscape: Boolean, phone: Boolean, portraitPhone: Boolean, homeFrame: Boolean,
    reduceMotion: Boolean, presentation: State<ProviderHeroPresentation>,
    playRequester: FocusRequester, infoRequester: FocusRequester,
    play: (MediaItem, List<MediaItem>) -> Unit,
    add: (MediaItem) -> Unit, info: (MediaItem, List<MediaItem>) -> Unit,
    focused: (String) -> Unit, down: () -> Unit, up: () -> Unit,
    categoryActions: @Composable () -> Unit) {
    val provider by providerVm.state.collectAsStateWithLifecycle()
    val display by presentation
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val sceneArtwork = LocalDiscoverySceneArtwork.current
    val cache = remember(context) { DiscoveryCache(File(context.applicationContext.cacheDir, "discovery_preview")) }
    // A changed profile or selected-category set creates a new pool. Window movement does not.
    key(profile, type, provider.identity) {
    var entries by remember { mutableStateOf(emptyList<ProviderHeroEntry>()) }
    var fallbackEntries by remember { mutableStateOf(emptyList<ProviderHeroEntry>()) }
    val fallbackRandom = remember { kotlin.random.Random(kotlin.random.Random.nextInt()) }
    var poolPrepared by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(provider.pages[type], categories, provider.profile, display.seriesRecents) {
        if (provider.profile != profile) return@LaunchedEffect
        val (fallback, next) = withContext(Dispatchers.Default) {
            val fallback = appendProviderHeroes(fallbackEntries, categories, provider.pages[type].orEmpty(), fallbackRandom)
            fallback to preferredProviderHeroes(profile, type, display.seriesRecents, fallback)
        }
        fallbackEntries = fallback; entries = next
        poolPrepared = true
        if (next.none { it.item.id == selectedId }) selectedId = next.firstOrNull()?.item?.id
    }
    val index = entries.indexOfFirst { it.item.id == selectedId }.coerceAtLeast(0)
    val selected = entries.getOrNull(index)
    fun rotateBy(direction: Int) {
        if (entries.isNotEmpty()) selectedId = entries[Math.floorMod(index + direction, entries.size)].item.id
    }
    val fallback = remember(selected, type) { selected?.let { providerDetailFallback(it.item, type).copy(poster = it.artwork) } }
    var cached by remember(selected?.item?.id) { mutableStateOf<DiscoveryCard?>(null) }
    LaunchedEffect(selected?.item, display.active) {
        if (display.active) cached = selected?.item?.let { readProviderHeroMetadata(cache, it, type) }
    }
    val card = cached ?: fallback
    val seriesResume = remember(profile, type, selected?.item, display.seriesRecents, display.progress) {
        selected?.item?.takeIf { type == CatalogType.SERIES }?.let { item ->
            providerSeriesResumeRecent(profile, item, display.seriesRecents) { display.progress?.recent(it) }
        }
    }
    LaunchedEffect(display.rotate, display.active, display.mayRotate, selectedId, entries.size, reduceMotion) {
        if (display.rotate && display.active && display.mayRotate && !reduceMotion && entries.size > 1) {
            delay(8_000); rotateBy(1)
        }
    }
    LaunchedEffect(display.prepareNext, display.active, display.mayRotate, selectedId, entries.size) {
        if (display.prepareNext && display.active && display.mayRotate && entries.size > 1) {
            delay(1_000)
            // One adjacent artwork only; no provider URL resolution or metadata lookup.
            prepareTrialHeroArtwork(context, entries[(index + 1) % entries.size].artwork, sceneArtwork)
        }
    }
    val framed = homeFrame && landscape && !phone
    val inset = LocalHeroNavigationInset.current
    val publishGlass = LocalPublishMobileGlassSource.current
    val headerArtwork = thumbnailArtworkUrl(card?.backdrop ?: card?.poster, 1280)
    var heroGlassBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val baseHeight = trialHeroBaseHeight(configuration.screenHeightDp.toFloat(), framed, context.isTvLikeDevice(configuration))
    Box((if (portraitPhone) Modifier.wrapContentWidth(unbounded = true).requiredWidth(configuration.screenWidthDp.dp) else Modifier.fillMaxWidth()).clipToBounds().onGloballyPositioned {
        heroGlassBounds = it.boundsInWindow()
        if (!portraitPhone) publishGlass(MobileGlassSource(headerArtwork.takeIf { display.artwork }, it.boundsInWindow(), topAligned = framed, fillWidth = framed))
    }.then(if (landscape && !phone)
        Modifier.height(trialHeroFrameHeight(baseHeight, density.fontScale).dp + inset) else Modifier)) {
        if (display.artwork && !portraitPhone && !sceneArtwork) key(selectedId, card?.backdrop ?: card?.poster) {
            val picture = card?.backdrop ?: card?.poster
            val request = remember(picture) { ImageRequest.Builder(context).data(thumbnailArtworkUrl(picture, 1280))
                .size(1280, 720).transformations(DiscoveryBackdropTransformation(verticalShade = false)).crossfade(false).build() }
            var ready by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (!display.fadeArtwork || ready) 1f else 0f,
                tween(if (reduceMotion) 0 else 300), label = "providerHeroArtwork")
            AsyncImage(request, null, (if (framed) Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .aspectRatio(16f / 9f).align(Alignment.TopCenter) else Modifier.matchParentSize()).graphicsLayer { this.alpha = alpha },
                contentScale = if (framed) ContentScale.FillWidth else ContentScale.Crop,
                onSuccess = { ready = true })
            DiscoveryHeroShade(Modifier.matchParentSize())
        }
        CompositionLocalProvider(LocalMobileGlassEnabled provides true,
            LocalMobileGlassArtwork provides headerArtwork,
            LocalMobileGlassBounds provides (if (sceneArtwork) LocalDiscoverySceneBounds.current else heroGlassBounds),
            LocalMobileGlassTopAligned provides (framed && !sceneArtwork),
            LocalMobileGlassFillWidth provides (framed && !sceneArtwork)) {
        DiscoveryHero(card?.let { if (portraitPhone && display.artwork) it else it.copy(backdrop = null, poster = null) },
            rank = null, height = if (portraitPhone) phoneDiscoveryHeroHeight() - inset else baseHeight.dp + inset, landscape = landscape, compact = true, detail = false,
            contentInset = if (landscape && !phone && !sceneArtwork) 18.dp else 0.dp,
            loading = selected == null && providerHeroWaiting(profile, type, categories, provider, poolPrepared),
            inList = selected?.item?.id in display.favoriteIds,
            playRequester = playRequester, infoRequester = infoRequester,
            play = { selected?.let { focused("play"); play(it.item, it.queue) } },
            add = { selected?.let { add(it.item) } },
            info = { selected?.let { focused("info"); info(it.item, it.queue) } },
            onFocus = { focused("play") }, onDown = down, onUp = { up(); true },
            previous = { rotateBy(-1) }, next = { rotateBy(1) }, carousel = entries.size > 1,
            index = index, count = entries.size, playEnabled = selected != null,
            playLabel = if (if (type == CatalogType.SERIES) seriesResume != null
                else selected?.item?.let { (display.progress?.provider(type, it.id) ?: 0f) > 0f } == true) "Resume" else "Play",
            portraitPhone = portraitPhone, categoryActions = if (phone) null else categoryActions,
            trailerMedia = selected?.item,
            emptyTitle = if (categories.isEmpty()) "Choose IPTV categories" else "No titles available in these categories")
        }
    }
    }
}
