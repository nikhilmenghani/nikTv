package com.nikhil.niktv.discovery

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalDiscoveryWideCards = staticCompositionLocalOf { false }
internal val LocalSimpleDiscoveryTiles = staticCompositionLocalOf { false }
internal val LocalDiscoveryPosterArtwork = staticCompositionLocalOf { true }
internal val LocalDiscoveryExperiment = staticCompositionLocalOf { false }
internal val LocalOpenDiscoveryExperiment = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalOpenDiscoveryExperimentSurface = staticCompositionLocalOf<(com.nikhil.niktv.model.DashboardSurface) -> Unit> { {} }
internal val LocalDiscoveryExperimentSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Trial-only budgets: full catalogues remain available through View all/Search. */
internal enum class DiscoveryScrollVariant(val title: String, val rowLimit: Int = Int.MAX_VALUE,
    val tileLimit: Int = 30, val staticArtwork: Boolean = false, val simpleFocus: Boolean = false, val posterArtwork: Boolean = true, val minimalReveal: Boolean = false, val wideCards: Boolean = false, val nativeRows: Boolean = false, val animateNativeFocus: Boolean = false,
    val recycledRows: Boolean = false, val sourceCaptions: Boolean = true, val progressIndicators: Boolean = true,
    val containedCaptions: Boolean = false, val cardShadows: Boolean = false, val heroLayout: Boolean = false) {
    CONTROL("Control · same as Home"),
    FEWER_ROWS("4 rows · original tiles", rowLimit = 4),
    FEWER_TILES("8 tiles per row", tileLimit = 8),
    STATIC_ARTWORK("Static header · no focus artwork", staticArtwork = true),
    MINIMAL_SCROLL("Minimal row movement", minimalReveal = true),
    SIMPLE_FOCUS("Immediate focus · no zoom or shadow", simpleFocus = true),
    WIDE_CARDS("4 landscape tiles across", wideCards = true),
    NATIVE_ROWS("Native rows · compact renderer", rowLimit = 4, tileLimit = 8, staticArtwork = true, nativeRows = true),
    NATIVE_MOTION("Native rows · gentle focus motion", rowLimit = 4, tileLimit = 8, staticArtwork = true, nativeRows = true, animateNativeFocus = true),
    RECYCLED_BASELINE("Stage 1 · cached posters, 4 × 8", rowLimit = 4, tileLimit = 8, staticArtwork = true, nativeRows = true, recycledRows = true, sourceCaptions = false, progressIndicators = false),
    RECYCLED_MORE_CARDS("Stage 2 · 30 cards per row", rowLimit = 4, staticArtwork = true, nativeRows = true, recycledRows = true, sourceCaptions = false, progressIndicators = false),
    RECYCLED_MORE_ROWS("Stage 3 · full selected feed", staticArtwork = true, nativeRows = true, recycledRows = true, sourceCaptions = false, progressIndicators = false),
    RECYCLED_SOURCES("Stage 4 · source labels", staticArtwork = true, nativeRows = true, recycledRows = true, progressIndicators = false),
    RECYCLED_PROGRESS("Stage 5 · watch progress", staticArtwork = true, nativeRows = true, recycledRows = true),
    RECYCLED_ROWS("Stage 6 · focus animation", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true),
    RECYCLED_CAPTIONS("Stage 7A · contained captions", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true),
    RECYCLED_STYLING("Stage 7B · borders and shadows", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true),
    RECYCLED_HERO("Stage 8 · hero layout and actions", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true),
    TEXT_ONLY("Text tiles · no artwork", staticArtwork = true, simpleFocus = true, posterArtwork = false),
    LEAN("Lean · 4 rows, 8 tiles, static header", rowLimit = 4, tileLimit = 8, staticArtwork = true, simpleFocus = true, minimalReveal = true),
}

/** Home 2 is the only route allowed to choose an experimental presenter. */
internal fun discoveryVariantForRoute(experiment: Boolean, selected: DiscoveryScrollVariant) =
    if (experiment) selected else DiscoveryScrollVariant.CONTROL

internal val recycledDiscoveryStages = listOf(DiscoveryScrollVariant.RECYCLED_BASELINE,
    DiscoveryScrollVariant.RECYCLED_MORE_CARDS, DiscoveryScrollVariant.RECYCLED_MORE_ROWS,
    DiscoveryScrollVariant.RECYCLED_SOURCES, DiscoveryScrollVariant.RECYCLED_PROGRESS, DiscoveryScrollVariant.RECYCLED_ROWS,
    DiscoveryScrollVariant.RECYCLED_CAPTIONS, DiscoveryScrollVariant.RECYCLED_STYLING, DiscoveryScrollVariant.RECYCLED_HERO)

/** Bound only the trial preview's requests; callers retain the full selection for search/collections. */
internal fun discoveryTrialProviderScope(categories: List<com.nikhil.niktv.model.Category>, rowLimit: Int): List<com.nikhil.niktv.model.Category> =
    if (rowLimit >= categories.size) categories else categories.take(rowLimit.coerceAtLeast(0))

internal fun discoveryRecycledProviderScope(categories: List<com.nikhil.niktv.model.Category>, anchor: String?): List<com.nikhil.niktv.model.Category> {
    val index = categories.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
    return categories.drop((index - 1).coerceAtLeast(0)).take(6)
}
