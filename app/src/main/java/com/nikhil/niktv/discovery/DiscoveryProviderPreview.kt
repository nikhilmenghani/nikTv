package com.nikhil.niktv.discovery

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.niktv.discovery.components.modernVisibleIptvCategories
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.ui.IptvPinSnapshot
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.categoryDisplayOrderId
import com.nikhil.niktv.ui.orderedForDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DiscoveryProviderPreview(
    val model: ProviderDiscoveryViewModel,
    val catalogue: CatalogType?,
    val allSelections: Map<CatalogType, List<Category>>,
    val selections: Map<CatalogType, List<Category>>,
    val windowed: Boolean,
    val state: ProviderDiscoveryState,
    val queues: Pair<String, Map<String, List<MediaItem>>>
)

/** Prepare only the active route's provider preview and retain a bounded revisit cache. */
@Composable
internal fun rememberDiscoveryProviderPreview(app: NikTvState, profile: String, surface: DashboardSurface,
    vm: DiscoveryViewModel, scrollVariant: DiscoveryScrollVariant, experiment: Boolean,
    nearbyRow: String?, displayOrder: IptvPinSnapshot): DiscoveryProviderPreview {
    // Nested TMDB details use a different discovery VM; they must not rebind the
    // underlying collection or retain another profile's catalogue in memory.
    val providerVm: ProviderDiscoveryViewModel = viewModel(key = "provider-discovery:$profile:${System.identityHashCode(vm)}")
    val providerLoaded by providerVm.state.collectAsStateWithLifecycle()
    val providerPageLoader = LocalProviderDiscoveryPageLoader.current
    val providerCatalog = discoveryProviderCatalog(surface)
    val fullProviderSelections = remember(profile, surface, app.categoryFilters,
        providerCatalog?.let(app.rawCategoriesByType::get),
        providerCatalog?.let(app.browseCachesByType::get)?.categories, displayOrder) {
        listOf(CatalogType.MOVIES, CatalogType.SERIES)
            .filter { surface != DashboardSurface.HOME && (it == CatalogType.SERIES) == (surface == DashboardSurface.SERIES) }
            .associateWith { type -> orderedForDisplay(providerDiscoveryCategoryRows(
                app.modernVisibleIptvCategories(type, requireExplicitSelection = surface == DashboardSurface.HOME),
                implicitSelection = app.categoryFilters["$profile|${type.name}"] == null), displayOrder.categoryOrder(type), ::categoryDisplayOrderId) }
    }
    // The experiment bounds request/cache scope as well as drawing. Full collections
    // still receive all selected categories, and persistent selections are untouched.
    val providerSelections = remember(fullProviderSelections, scrollVariant.rowLimit) {
        fullProviderSelections.mapValues { (_, categories) -> discoveryTrialProviderScope(categories, scrollVariant.rowLimit) }
    }
    val providerBindingSelections = remember(providerSelections, scrollVariant, nearbyRow) {
        if (!scrollVariant.recycledRows) providerSelections else
            providerSelections.mapValues { (type, categories) ->
                discoveryRecycledProviderScope(categories, nearbyRow?.removePrefix("provider:${type.name}:"))
            }
    }
    val windowedProviderPreview = experiment && providerCatalog != null && scrollVariant.recycledRows
    val providerBindingScope = if (windowedProviderPreview) providerSelections else providerBindingSelections
    val previewWindow = remember(providerBindingSelections) {
        providerBindingSelections.mapValues { providerDiscoverySources(it.value).mapTo(linkedSetOf()) { category -> category.id } }
    }
    val expectedProviderCategories = remember(providerBindingScope) {
        providerBindingScope.mapValues { providerDiscoverySources(it.value) }.filterValues { it.isNotEmpty() }
    }
    val providerRawState = providerLoaded.takeIf { it.profile == profile && it.categories == expectedProviderCategories }
        ?: ProviderDiscoveryState(profile = profile, categories = expectedProviderCategories)
    val providerState = remember(providerRawState, app.xtreamContentPolicy, app.hideSensitiveIptv, app.contentPolicyCategories) {
        providerRawState.withContentPolicy(app)
    }
    val providerRawQueues by produceState(providerVm.retainedRows.takeIf { it.first == profile }
        ?: (profile to emptyMap<String, List<MediaItem>>()), profile, providerSelections, providerRawState.pages, scrollVariant) {
        val retained = providerVm.retainedRows.takeIf { it.first == profile }?.second.orEmpty()
        val previous = value.takeIf { it.first == profile }?.second.orEmpty()
        value = profile to withContext(Dispatchers.Default) {
            buildMap { providerSelections.forEach { (type, categories) -> categories.forEach { category ->
                val key = "provider:${type.name}:${category.id}"
                val sources = providerDiscoverySources(listOf(category))
                val pages = providerRawState.pages[type].orEmpty()
                val items = if (!scrollVariant.recycledRows || sources.any { it.id in pages }) providerDiscoveryItems(sources, pages)
                    else retained[key].orEmpty()
                put(key, if (windowedProviderPreview) previous[key]?.takeIf { it == items } ?: items else items)
            } } }
        }
    }
    LaunchedEffect(providerRawQueues, profile, scrollVariant.recycledRows, providerBindingSelections) {
        val queues = providerRawQueues
        if (queues.first != profile) return@LaunchedEffect
        providerVm.retainedRows = if (!scrollVariant.recycledRows) queues else {
            withContext(Dispatchers.Default) {
                val hot = providerBindingSelections.flatMap { (type, cats) -> cats.map { "provider:${type.name}:${it.id}" } }.toSet()
                val values = queues.second
                profile to (values.filterKeys { it in hot } + values.filter { it.key !in hot && it.value.isNotEmpty() }).entries.take(100).associate { it.toPair() }
            }
        }
    }
    val providerQueues = remember(providerRawQueues, app.xtreamContentPolicy, app.hideSensitiveIptv, app.contentPolicyCategories) {
        val gate = com.nikhil.niktv.ui.XtreamContentGate(app)
        if (!gate.active) providerRawQueues else providerRawQueues.first to providerRawQueues.second.mapValues { (key, items) ->
            val type = if (key.startsWith("provider:SERIES:")) CatalogType.SERIES else CatalogType.MOVIES
            items.filter { gate.allows(it, type) }
        }
    }
    LaunchedEffect(profile, app.session, providerBindingScope, app.cacheIntervalMinutes, windowedProviderPreview) {
        providerVm.bind(app.session, providerBindingScope, app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L, preview = true,
            pageLoader = providerPageLoader, initialPreviewWindow = previewWindow.takeIf { windowedProviderPreview })
    }
    LaunchedEffect(profile, providerState.identity, previewWindow, windowedProviderPreview) {
        if (windowedProviderPreview) providerVm.updatePreviewWindow(previewWindow)
    }
    // Keep bounded landing previews for an immediate return from details; nested detail
    // routes release their private catalogue copies. No network work survives disposal.
    DisposableEffect(providerVm) { onDispose { providerVm.pause(release = vm.state.value.detail != null) } }
    val providerLifecycle = LocalLifecycleOwner.current.lifecycle
    val bindProvider by rememberUpdatedState {
        providerVm.bind(app.session, providerBindingScope, app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L, preview = true, pageLoader = providerPageLoader,
            initialPreviewWindow = previewWindow.takeIf { windowedProviderPreview })
    }
    DisposableEffect(providerLifecycle, providerVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) providerVm.pause()
            else if (event == Lifecycle.Event.ON_START) bindProvider()
        }
        providerLifecycle.addObserver(observer)
        onDispose { providerLifecycle.removeObserver(observer) }
    }
    return DiscoveryProviderPreview(providerVm, providerCatalog, fullProviderSelections, providerSelections,
        windowedProviderPreview, providerState, providerQueues)
}
