package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.isTvLikeDevice
import kotlinx.coroutines.*
import java.io.File

/** Presentation never supplies playback identity: actions always retain the provider item/queue. */
internal data class ProviderHeroEntry(val item: MediaItem, val queue: List<MediaItem>, val recent: RecentItem? = null) {
    val artwork get() = recent?.media?.logo?.takeIf(String::isNotBlank) ?: item.logo
}

internal data class ProviderHeroScope(
    val profile: String, val type: CatalogType, val catalogue: String, val sources: List<String>
)

/** The native hero view can detach while its provider route and selected title still exist. */
internal data class RetainedProviderHero(
    val scope: ProviderHeroScope,
    val fallback: List<ProviderHeroEntry> = emptyList(),
    val entries: List<ProviderHeroEntry> = emptyList(),
    val recents: List<RecentItem> = emptyList(),
    val selectedId: String? = null,
) {
    fun restore(nextScope: ProviderHeroScope, nextRecents: List<RecentItem>): RetainedProviderHero? = when {
        nextScope != scope -> null
        nextRecents != recents -> copy(entries = emptyList())
        else -> this
    }

    fun update(nextFallback: List<ProviderHeroEntry>, nextEntries: List<ProviderHeroEntry>,
        nextRecents: List<RecentItem>, selection: String?): RetainedProviderHero =
        copy(fallback = nextFallback, entries = nextEntries, recents = nextRecents,
            selectedId = selection.takeIf { id -> nextEntries.isEmpty() || nextEntries.any { it.item.id == id } }
                ?: nextEntries.firstOrNull()?.item?.id)
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
    metadataRevision: Int = 0, categoryActions: @Composable () -> Unit) {
    val provider by providerVm.state.collectAsStateWithLifecycle()
    val display by presentation
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val sceneArtwork = LocalDiscoverySceneArtwork.current
    val cache = remember(context) { DiscoveryCache(File(context.applicationContext.cacheDir, "discovery_preview")) }
    // Hydration windows and native view attachment can change within the same catalogue.
    val heroScope = remember(profile, type, provider.identity, categories) {
        ProviderHeroScope(profile, type, provider.identity, providerDiscoverySources(categories).map { it.id })
    }
    key(heroScope) {
    var pool by remember(providerVm) {
        mutableStateOf(providerVm.retainedHero?.restore(heroScope, display.seriesRecents)
            ?: RetainedProviderHero(heroScope))
    }
    val fallbackRandom = remember { kotlin.random.Random(kotlin.random.Random.nextInt()) }
    var poolPrepared by remember { mutableStateOf(pool.entries.isNotEmpty()) }
    var selectedId by rememberSaveable { mutableStateOf(pool.selectedId) }
    fun retain(next: RetainedProviderHero) {
        pool = next
        selectedId = next.selectedId
        if (heroScope.catalogue.isNotBlank() && providerVm.state.value.identity == heroScope.catalogue) {
            providerVm.retainedHero = next
        }
    }
    LaunchedEffect(provider.pages[type], categories, provider.profile, display.seriesRecents) {
        if (provider.profile != profile) return@LaunchedEffect
        val recents = display.seriesRecents
        val existingFallback = pool.fallback
        val (fallback, next) = withContext(Dispatchers.Default) {
            val fallback = appendProviderHeroes(existingFallback, categories, provider.pages[type].orEmpty(), fallbackRandom)
            fallback to preferredProviderHeroes(profile, type, recents, fallback)
        }
        retain(pool.update(fallback, next, recents, selectedId))
        poolPrepared = true
    }
    val entries = pool.entries
    val index = entries.indexOfFirst { it.item.id == selectedId }.coerceAtLeast(0)
    val selected = entries.getOrNull(index)
    fun rotateBy(direction: Int) {
        if (entries.isNotEmpty()) retain(pool.copy(selectedId = entries[Math.floorMod(index + direction, entries.size)].item.id))
    }
    val fallback = remember(selected, type) { selected?.let { providerDetailFallback(it.item, type).copy(poster = it.artwork) } }
    var cached by remember(selected?.item) { mutableStateOf<DiscoveryCard?>(null) }
    var presentationReady by remember(selected?.item) { mutableStateOf(false) }
    LaunchedEffect(selected?.item, metadataRevision) {
        // The visible title needs its local artwork even before the activity resumes.
        // Only speculative prefetch and rotation depend on foreground presentation.
        cached = selected?.item?.let { readProviderHeroMetadata(cache, it, type) }
        presentationReady = true
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
    // Resolve the local presentation before painting: showing the provider poster
    // first and then its cached backdrop visibly changes the hero's crop/scale.
    // Metadata/actions remain immediate; this read never starts a network lookup.
    val backdrop = cached?.backdrop?.takeIf(String::isNotBlank).takeIf { presentationReady }
    val poster = (cached?.poster?.takeIf(String::isNotBlank) ?: fallback?.poster).takeIf { presentationReady }
    val picture = backdrop ?: poster
    val headerArtwork = thumbnailArtworkUrl(picture, 1280)
    var heroGlassBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(publishGlass, headerArtwork, display.artwork, heroGlassBounds, framed, portraitPhone) {
        if (!portraitPhone) publishGlass(MobileGlassSource(headerArtwork.takeIf { display.artwork },
            heroGlassBounds, topAligned = framed, fillWidth = framed))
    }
    val baseHeight = trialHeroBaseHeight(configuration.screenHeightDp.toFloat(), framed, context.isTvLikeDevice(configuration))
    Box((if (portraitPhone) Modifier.wrapContentWidth(unbounded = true).requiredWidth(configuration.screenWidthDp.dp) else Modifier.fillMaxWidth()).clipToBounds().onGloballyPositioned {
        heroGlassBounds = it.boundsInWindow()
    }.then(if (landscape && !phone)
        Modifier.height(trialHeroFrameHeight(baseHeight, density.fontScale).dp + inset) else Modifier)) {
        if (display.artwork && !portraitPhone && !sceneArtwork) Box(Modifier.matchParentSize()) {
            DiscoveryBackdropArtwork(headerArtwork, reduceMotion || !display.fadeArtwork,
                alignment = if (framed) Alignment.TopCenter else Alignment.Center, viewportShade = true,
                fillWidth = framed, prepare = presentationReady)
            DiscoveryHeroShade(Modifier.matchParentSize())
        }
        CompositionLocalProvider(LocalMobileGlassEnabled provides true, LocalStableHeroGlass provides !phone,
            LocalMobileGlassArtwork provides headerArtwork.takeIf { phone },
            LocalMobileGlassBounds provides (if (phone) heroGlassBounds else androidx.compose.ui.geometry.Rect.Zero),
            LocalMobileGlassTopAligned provides (framed && !sceneArtwork),
            LocalMobileGlassFillWidth provides (framed && !sceneArtwork)) {
        DiscoveryHero(card?.let {
            if (portraitPhone && display.artwork) it.copy(backdrop = backdrop, poster = poster)
            else it.copy(backdrop = null, poster = null)
        },
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
