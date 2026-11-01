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
    val tileLimit: Int = 30, val staticArtwork: Boolean = false, val simpleFocus: Boolean = false, val posterArtwork: Boolean = true, val minimalReveal: Boolean = false, val wideCards: Boolean = false, val nativeRows: Boolean = false, val animateNativeFocus: Boolean = false) {
    CONTROL("Control · same as Home"),
    FEWER_ROWS("4 rows · original tiles", rowLimit = 4),
    FEWER_TILES("8 tiles per row", tileLimit = 8),
    STATIC_ARTWORK("Static header · no focus artwork", staticArtwork = true),
    MINIMAL_SCROLL("Minimal row movement", minimalReveal = true),
    SIMPLE_FOCUS("Immediate focus · no zoom or shadow", simpleFocus = true),
    WIDE_CARDS("4 landscape tiles across", wideCards = true),
    NATIVE_ROWS("Native rows · compact renderer", rowLimit = 4, tileLimit = 8, staticArtwork = true, nativeRows = true),
    NATIVE_MOTION("Native rows · gentle focus motion", rowLimit = 4, tileLimit = 8, staticArtwork = true, nativeRows = true, animateNativeFocus = true),
    TEXT_ONLY("Text tiles · no artwork", staticArtwork = true, simpleFocus = true, posterArtwork = false),
    LEAN("Lean · 4 rows, 8 tiles, static header", rowLimit = 4, tileLimit = 8, staticArtwork = true, simpleFocus = true, minimalReveal = true),
}

/** Bound only the trial preview's requests; callers retain the full selection for search/collections. */
internal fun discoveryTrialProviderScope(categories: List<com.nikhil.niktv.model.Category>, rowLimit: Int): List<com.nikhil.niktv.model.Category> =
    if (rowLimit >= categories.size) categories else categories.take(rowLimit.coerceAtLeast(0))
