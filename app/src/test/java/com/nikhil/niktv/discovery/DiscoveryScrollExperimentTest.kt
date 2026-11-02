package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryScrollExperimentTest {
    @Test fun experimentalChoicesCannotChangeOriginalDestinations() {
        DiscoveryScrollVariant.entries.forEach { selected ->
            assertSame(DiscoveryScrollVariant.CONTROL, discoveryVariantForRoute(false, selected))
            assertSame(selected, discoveryVariantForRoute(true, selected))
        }
    }

    @Test fun recycledRebuildAddsExactlyOneFeaturePerStage() {
        fun features(v: DiscoveryScrollVariant) = mapOf(
            "rows" to v.rowLimit, "cards" to v.tileLimit, "sources" to v.sourceCaptions,
            "progress" to v.progressIndicators, "motion" to v.animateNativeFocus,
            "static" to v.staticArtwork, "posters" to v.posterArtwork, "recycled" to v.recycledRows,
            "captions" to v.containedCaptions, "shadows" to v.cardShadows, "hero" to v.heroLayout, "artwork" to v.heroArtwork, "fade" to v.heroArtworkFade, "titleArtwork" to v.heroTitleArtwork, "rotation" to v.heroAutoRotate)
        val baseline = recycledDiscoveryStages.first()
        assertEquals(4, baseline.rowLimit)
        assertEquals(8, baseline.tileLimit)
        assertTrue(baseline.staticArtwork && baseline.posterArtwork && baseline.recycledRows)
        assertFalse(baseline.sourceCaptions || baseline.progressIndicators || baseline.animateNativeFocus)
        val expectedChanges = listOf("cards", "rows", "sources", "progress", "motion", "captions", "shadows", "hero", "artwork", "fade", "titleArtwork", "rotation")
        recycledDiscoveryStages.zipWithNext().forEachIndexed { index, (before, after) ->
            val a = features(before); val b = features(after)
            assertEquals(setOf(expectedChanges[index]), a.keys.filter { a[it] != b[it] }.toSet())
        }
    }

    @Test fun recycledRequestsFollowDistantRowsWithoutTrimmingTheFeed() {
        val selected = (0..499).map { com.nikhil.niktv.model.Category("$it", "Row $it", com.nikhil.niktv.model.CatalogType.MOVIES) }
        assertEquals((249..254).map(Int::toString), discoveryRecycledProviderScope(selected, "250").map { it.id })
        assertEquals((0..5).map(Int::toString), discoveryRecycledProviderScope(selected, null).map { it.id })
        assertEquals(listOf("498", "499"), discoveryRecycledProviderScope(selected, "499").map { it.id })
        assertSame(selected, discoveryTrialProviderScope(selected, DiscoveryScrollVariant.RECYCLED_ROWS.rowLimit))
        assertEquals(500, selected.size)
    }
    @Test fun controlRetainsOriginalBudgetsAndFeatures() {
        val control = DiscoveryScrollVariant.CONTROL
        assertEquals(Int.MAX_VALUE, control.rowLimit)
        assertEquals(30, control.tileLimit)
        assertFalse(control.staticArtwork)
        assertFalse(control.simpleFocus)
    }

    @Test fun isolatedPresetsChangeOnlyTheirNamedVariable() {
        val rows = DiscoveryScrollVariant.FEWER_ROWS
        assertEquals(4, rows.rowLimit)
        assertEquals(30, rows.tileLimit)
        assertFalse(rows.staticArtwork)
        assertFalse(rows.simpleFocus)
        val tiles = DiscoveryScrollVariant.FEWER_TILES
        assertEquals(Int.MAX_VALUE, tiles.rowLimit)
        assertEquals(8, tiles.tileLimit)
        assertFalse(tiles.staticArtwork)
        assertFalse(tiles.simpleFocus)
        val artwork = DiscoveryScrollVariant.STATIC_ARTWORK
        assertEquals(Int.MAX_VALUE, artwork.rowLimit)
        assertEquals(30, artwork.tileLimit)
        assertTrue(artwork.staticArtwork)
        assertFalse(artwork.simpleFocus)
    }

    @Test fun leanCombinesBudgetsWithoutZeroOrUnboundedPreviewTiles() {
        val lean = DiscoveryScrollVariant.LEAN
        assertEquals(4, lean.rowLimit)
        assertEquals(8, lean.tileLimit)
        assertTrue(lean.staticArtwork)
        assertTrue(lean.simpleFocus)
        DiscoveryScrollVariant.entries.forEach {
            assertTrue(it.rowLimit > 0)
            assertTrue(it.tileLimit in 1..30)
        }
    }
    @Test fun nativeMotionRetainsTheSameDataBudgetsAsImmediateNativeRows() {
        val a = DiscoveryScrollVariant.NATIVE_ROWS
        val b = DiscoveryScrollVariant.NATIVE_MOTION
        assertEquals(a.rowLimit, b.rowLimit)
        assertEquals(a.tileLimit, b.tileLimit)
        assertTrue(a.nativeRows && b.nativeRows)
        assertFalse(a.animateNativeFocus)
        assertTrue(b.animateNativeFocus)
    }
    @Test fun previewScopeBoundsFiveHundredCategoriesWithoutChangingBackingSelections() {
        val selected = (1..500).map { number -> com.nikhil.niktv.model.Category(
            "$number", "Category $number", com.nikhil.niktv.model.CatalogType.SERIES,
            sourceCategoryIds = listOf("$number-a", "$number-b")) }
        val control = discoveryTrialProviderScope(selected, DiscoveryScrollVariant.CONTROL.rowLimit)
        assertSame(selected, control)
        assertEquals(500, control.size)
        for (variant in listOf(DiscoveryScrollVariant.NATIVE_ROWS, DiscoveryScrollVariant.NATIVE_MOTION,
            DiscoveryScrollVariant.FEWER_ROWS, DiscoveryScrollVariant.LEAN)) {
            val preview = discoveryTrialProviderScope(selected, variant.rowLimit)
            assertEquals(selected.take(4), preview)
            assertSame(selected.first(), preview.first())
            assertEquals(listOf("1-a", "1-b"), preview.first().sourceCategoryIds)
        }
        assertEquals(500, selected.size)
        assertEquals("500", selected.last().id)
        assertSame(selected, discoveryTrialProviderScope(selected, DiscoveryScrollVariant.FEWER_TILES.rowLimit))
    }
    @Test fun titleArtworkCannotLeakFromAnOldSelectionOrIntoControl() {
        val movie = DiscoveryCard(1, false, "One", titleLogo = "feed-logo")
        val prepared = movie.copy(titleLogo = "prepared-logo")
        assertEquals("prepared-logo", discoveryTrialHeroLogo(movie, prepared, true))
        assertNull(discoveryTrialHeroLogo(movie, prepared, false))
        assertNull(discoveryTrialHeroLogo(null, prepared, true))
        assertEquals("feed-logo", discoveryTrialHeroLogo(movie, prepared.copy(id = 2), true))
        assertEquals("feed-logo", discoveryTrialHeroLogo(movie, prepared.copy(series = true), true))
        assertNull(discoveryTrialHeroLogo(movie.copy(titleLogo = null), prepared.copy(id = 2), true))
        assertEquals("feed-logo", discoveryTrialHeroLogo(movie, prepared.copy(titleLogo = " "), true))
    }

    @Test fun metadataDiagnosticDoesNotRenderALogoOrChangeBaselineGeometry() {
        val v = DiscoveryScrollVariant.RECYCLED_HERO_METADATA
        assertTrue(v.heroMetadataOnly && v.heroLayout && v.heroArtworkFade)
        assertFalse(v.heroTitleArtwork)
        assertEquals(DiscoveryScrollVariant.RECYCLED_HERO_FADE.rowLimit, v.rowLimit)
        assertEquals(DiscoveryScrollVariant.RECYCLED_HERO_FADE.tileLimit, v.tileLimit)
        assertFalse(recycledDiscoveryStages.contains(v))
    }

    @Test fun isolatedFeedIgnoresHeroRotationsButKeepsDetailAndProfileUpdates() {
        val a = DiscoveryState(profile = "one", heroes = listOf(DiscoveryCard(1, false, "One"), DiscoveryCard(2, false, "Two")))
        val b = a.copy(heroIndex = 1, heroPresentation = a.heroes[1])
        assertEquals(a.recycledFeedState(true), b.recycledFeedState(true))
        assertNotEquals(a.recycledFeedState(false), b.recycledFeedState(false))
        assertNotEquals(a.recycledFeedState(true), b.copy(profile = "two").recycledFeedState(true))
        val detail = b.copy(detail = b.heroes[1])
        assertSame(detail, detail.recycledFeedState(true))
    }

    @Test fun stableHeroFrameKeepsBaselineAndAllowsLargerFonts() {
        assertEquals(300f, trialHeroFrameHeight(300f, 1f), 0f)
        assertEquals(300f, trialHeroFrameHeight(300f, .8f), 0f)
        assertEquals(456f, trialHeroFrameHeight(300f, 2f), 0f)
        assertTrue(DiscoveryScrollVariant.RECYCLED_STABLE_HERO.stableHeroBounds)
        assertFalse(DiscoveryScrollVariant.RECYCLED_ISOLATED_HERO.stableHeroBounds)
        assertFalse(DiscoveryScrollVariant.RECYCLED_STABLE_HERO.prepareNextHero)
        assertFalse(DiscoveryScrollVariant.RECYCLED_STABLE_HERO.homeTileProportions)
    }

    @Test fun immediateFocusCandidateIsTvOnlyAndRetainsPhase12Content() {
        val candidate = DiscoveryScrollVariant.RECYCLED_TV_FOCUS
        val control = DiscoveryScrollVariant.RECYCLED_HERO_ROTATION
        assertFalse(discoveryNativeFocusMotion(candidate, true))
        assertTrue(discoveryNativeFocusMotion(candidate, false))
        assertTrue(discoveryNativeFocusMotion(control, true))
        assertFalse(discoveryNativeFocusMotion(DiscoveryScrollVariant.CONTROL, true))
        assertEquals(control.rowLimit, candidate.rowLimit)
        assertEquals(control.tileLimit, candidate.tileLimit)
        assertTrue(candidate.recycledRows && candidate.containedCaptions && candidate.cardShadows)
        assertTrue(candidate.sourceCaptions && candidate.progressIndicators)
        assertTrue(candidate.heroArtwork && candidate.heroArtworkFade && candidate.heroAutoRotate)
        assertTrue(candidate.heroTitleArtwork)
        assertFalse(candidate.isolateHero || candidate.prepareNextHero || candidate.homeTileProportions)
        assertFalse(candidate.stableHeroBounds || candidate.nativeHeroBounds)
        assertFalse(recycledDiscoveryStages.contains(candidate))
    }

    @Test fun cachedTileDrawingIsTvOnlyAndIndependentOfFocusMotion() {
        val candidate = DiscoveryScrollVariant.RECYCLED_TV_LAYERS
        assertTrue(discoveryCacheTileDrawing(candidate, true))
        assertFalse(discoveryCacheTileDrawing(candidate, false))
        assertFalse(discoveryCacheTileDrawing(DiscoveryScrollVariant.RECYCLED_HERO_ROTATION, true))
        assertFalse(discoveryCacheTileDrawing(DiscoveryScrollVariant.CONTROL, true))
        assertTrue(discoveryNativeFocusMotion(candidate, true))
        assertTrue(candidate.heroAutoRotate && candidate.heroArtworkFade && candidate.containedCaptions)
        assertFalse(candidate.tvImmediateFocus || candidate.prepareNextHero || candidate.isolateHero)
    }

}
