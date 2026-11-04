package com.nikhil.niktv.discovery

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalDiscoveryWideCards = staticCompositionLocalOf { false }
internal val LocalSimpleDiscoveryTiles = staticCompositionLocalOf { false }
internal val LocalDiscoveryPosterArtwork = staticCompositionLocalOf { true }
internal val LocalDiscoveryExperiment = staticCompositionLocalOf { false }
internal val LocalOpenDiscoveryExperiment = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalOpenPreviousHome = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalOpenPreviousDiscovery = staticCompositionLocalOf<(com.nikhil.niktv.model.DashboardSurface) -> Unit> { {} }
internal val LocalOpenDiscoveryExperimentSurface = staticCompositionLocalOf<(com.nikhil.niktv.model.DashboardSurface) -> Unit> { {} }
internal val LocalDiscoveryExperimentSettings = staticCompositionLocalOf<((() -> Boolean) -> Unit)> { { _ -> } }

/** Trial-only budgets: full catalogues remain available through View all/Search. */
internal enum class DiscoveryScrollVariant(val title: String, val rowLimit: Int = Int.MAX_VALUE,
    val tileLimit: Int = 30, val staticArtwork: Boolean = false, val simpleFocus: Boolean = false, val posterArtwork: Boolean = true, val minimalReveal: Boolean = false, val wideCards: Boolean = false, val nativeRows: Boolean = false, val animateNativeFocus: Boolean = false,
    val recycledRows: Boolean = false, val sourceCaptions: Boolean = true, val progressIndicators: Boolean = true,
    val containedCaptions: Boolean = false, val cardShadows: Boolean = false, val heroLayout: Boolean = false, val heroArtwork: Boolean = false, val heroArtworkFade: Boolean = false, val heroTitleArtwork: Boolean = false, val heroMetadataOnly: Boolean = false, val heroMetadataOffMain: Boolean = false, val heroAutoRotate: Boolean = false, val isolateHero: Boolean = false, val homeTileProportions: Boolean = false, val prepareNextHero: Boolean = false, val stableHeroBounds: Boolean = false, val nativeHeroBounds: Boolean = false, val tvImmediateFocus: Boolean = false, val tvCachedTiles: Boolean = false, val tvPresentation: Boolean = false) {
    CONTROL("Control · same as Home"),
    TV_PRESENTATION_CONTROL("Control - TV presentation", tvPresentation = true),
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
    RECYCLED_HERO_ARTWORK("Stage 9 · static hero artwork", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true),
    RECYCLED_HERO_FADE("Stage 10 · prepared artwork fade", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true),
    RECYCLED_HERO_TITLE("Stage 11 · hero title artwork", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true),
    RECYCLED_HERO_ROTATION("Stage 12 · idle hero rotation", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true),
    RECYCLED_TV_LAYERS("TV - cached tile drawing", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, tvCachedTiles = true),
    RECYCLED_TV_FOCUS("TV - immediate tile focus", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, tvImmediateFocus = true),
    RECYCLED_ISOLATED_HERO("Diagnostic · isolated hero", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, isolateHero = true),
    RECYCLED_STABLE_HERO("Diagnostic · stable hero bounds", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, isolateHero = true, stableHeroBounds = true),
    RECYCLED_NATIVE_HERO_BOUNDS("Diagnostic · native hero bounds", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, isolateHero = true, stableHeroBounds = true, nativeHeroBounds = true),
    RECYCLED_PREPARED_HERO("Diagnostic · prepared next hero", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, isolateHero = true, prepareNextHero = true),
    RECYCLED_HOME_TILES("Diagnostic · Home tile proportions", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroAutoRotate = true, isolateHero = true, homeTileProportions = true),
    RECYCLED_HERO_WORKER("Diagnostic · worker metadata and logo", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroTitleArtwork = true, heroMetadataOffMain = true),
    RECYCLED_HERO_METADATA("Diagnostic · metadata without logo", staticArtwork = true, nativeRows = true, recycledRows = true, animateNativeFocus = true, containedCaptions = true, cardShadows = true, heroLayout = true, heroArtwork = true, heroArtworkFade = true, heroMetadataOnly = true),
    TEXT_ONLY("Text tiles · no artwork", staticArtwork = true, simpleFocus = true, posterArtwork = false),
    LEAN("Lean · 4 rows, 8 tiles, static header", rowLimit = 4, tileLimit = 8, staticArtwork = true, simpleFocus = true, minimalReveal = true),
}

/** Home 2 is the only route allowed to choose an experimental presenter. */
internal fun discoveryVariantForRoute(experiment: Boolean, selected: DiscoveryScrollVariant) =
    if (experiment) selected else DiscoveryScrollVariant.CONTROL

internal fun optimizedDiscoverySurface(surface: com.nikhil.niktv.model.DashboardSurface) =
    surface == com.nikhil.niktv.model.DashboardSurface.HOME || surface == com.nikhil.niktv.model.DashboardSurface.MOVIES

internal fun discoveryPresenterKey(profile: String, surface: com.nikhil.niktv.model.DashboardSurface, television: Boolean): String =
    "presenter:$profile:" + (if (surface == com.nikhil.niktv.model.DashboardSurface.HOME) "" else "${surface.name}:") +
        if (television) "tv" else "touch"

internal fun discoveryPresentationOptionKey(option: String, profile: String, surface: com.nikhil.niktv.model.DashboardSurface, television: Boolean): String =
    if (surface == com.nikhil.niktv.model.DashboardSurface.HOME) option else "$option:$profile:${surface.name}:${if (television) "tv" else "touch"}"

internal fun discoverySurfaceVmKey(profile: String?, surface: com.nikhil.niktv.model.DashboardSurface?, optimized: Boolean): String =
    "discovery2:$profile:$optimized" + if (optimized && surface != com.nikhil.niktv.model.DashboardSurface.HOME) ":$surface" else ""

internal val recycledDiscoveryStages = listOf(DiscoveryScrollVariant.RECYCLED_BASELINE,
    DiscoveryScrollVariant.RECYCLED_MORE_CARDS, DiscoveryScrollVariant.RECYCLED_MORE_ROWS,
    DiscoveryScrollVariant.RECYCLED_SOURCES, DiscoveryScrollVariant.RECYCLED_PROGRESS, DiscoveryScrollVariant.RECYCLED_ROWS,
    DiscoveryScrollVariant.RECYCLED_CAPTIONS, DiscoveryScrollVariant.RECYCLED_STYLING, DiscoveryScrollVariant.RECYCLED_HERO, DiscoveryScrollVariant.RECYCLED_HERO_ARTWORK, DiscoveryScrollVariant.RECYCLED_HERO_FADE, DiscoveryScrollVariant.RECYCLED_HERO_TITLE, DiscoveryScrollVariant.RECYCLED_HERO_ROTATION)

/** Bound only the trial preview's requests; callers retain the full selection for search/collections. */
internal fun discoveryTrialProviderScope(categories: List<com.nikhil.niktv.model.Category>, rowLimit: Int): List<com.nikhil.niktv.model.Category> =
    if (rowLimit >= categories.size) categories else categories.take(rowLimit.coerceAtLeast(0))

internal fun discoveryRecycledProviderScope(categories: List<com.nikhil.niktv.model.Category>, anchor: String?): List<com.nikhil.niktv.model.Category> {
    val index = categories.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
    return categories.drop((index - 1).coerceAtLeast(0)).take(6)
}

/** Late metadata may supply a logo only for the selected hero, never another title. */
internal fun discoveryTrialHeroLogo(card: DiscoveryCard?, prepared: DiscoveryCard?, enabled: Boolean): String? =
    if (!enabled || card == null) null else
        prepared?.takeIf { it.key == card.key }?.titleLogo?.takeIf(String::isNotBlank)
            ?: card.titleLogo?.takeIf(String::isNotBlank)

/** A hero rotation must not invalidate the recycled feed's parent composition. */
internal fun DiscoveryState.recycledFeedState(isolated: Boolean): DiscoveryState =
    if (isolated && detail == null) copy(heroIndex = 0, heroPresentation = null) else this

/** Landscape-only exact hero frame, with enough room for scaled text/actions. */
internal fun trialHeroFrameHeight(base: Float, fontScale: Float): Float =
    maxOf(base, 216f * fontScale.coerceAtLeast(1f) + 24f)

/** Same landscape height policy as Home; the compact frame remains an A/B control. */
internal fun trialHeroBaseHeight(screenHeight: Float, homeFraming: Boolean, television: Boolean = false): Float =
    if (television) (screenHeight * .4f).coerceIn(180f, 235f)
    else if (!homeFraming) (screenHeight * .45f).coerceIn(200f, 300f)
    else if (screenHeight < 480f) (screenHeight * .52f).coerceIn(165f, 230f)
    else (screenHeight * .55f).coerceIn(235f, 355f)

/** Keep the TV rendering experiment out of touch devices and original routes. */
internal fun discoveryNativeFocusMotion(variant: DiscoveryScrollVariant, television: Boolean): Boolean =
    variant.animateNativeFocus && !(television && variant.tvImmediateFocus)

internal fun discoveryCacheTileDrawing(variant: DiscoveryScrollVariant, television: Boolean): Boolean =
    television && variant.tvCachedTiles

/** Hero-only updates must not submit an unchanged recycled feed again. */
internal fun discoveryIsolateHero(experiment: Boolean, variant: DiscoveryScrollVariant): Boolean =
    experiment && (variant.isolateHero || (variant.recycledRows && variant.heroLayout))

internal fun discoveryInitialVariant(experiment: Boolean, stored: String?, television: Boolean): DiscoveryScrollVariant =
    if (!experiment) DiscoveryScrollVariant.CONTROL else
        DiscoveryScrollVariant.entries.firstOrNull { it.name == stored }
            ?: if (television) DiscoveryScrollVariant.RECYCLED_TV_LAYERS else DiscoveryScrollVariant.RECYCLED_HERO_ROTATION
