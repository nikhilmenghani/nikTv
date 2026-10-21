package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test
import com.nikhil.niktv.model.DashboardSurface

class DiscoveryFocusOriginsTest {
    @Test fun unfocusedParkingIsOnlyEligibleDuringItsRequestAndAlwaysCleansUp() {
        val parking = DiscoveryFocusParking()
        assertFalse(parking.canFocus)
        assertTrue(parking.request {
            assertTrue(parking.canFocus)
            assertFalse(parking.request { assertTrue(parking.canFocus); false })
            assertTrue(parking.canFocus)
            true
        })
        assertFalse("Parking must not become a regular D-pad destination", parking.canFocus)
        try { parking.request { error("Fixture request failed") }; fail("Expected fixture failure") }
        catch (_: IllegalStateException) { }
        assertFalse(parking.canFocus)
    }

    @Test fun parkingRemainsEligibleUntilFocusLossSoInvalidationCannotClearItsOwner() {
        val parking = DiscoveryFocusParking()
        assertTrue(parking.request {
            parking.focusChanged(true)
            true
        })
        assertTrue("An active target must remain focusable through the route replacement", parking.canFocus)
        parking.focusChanged(false)
        assertFalse("After transfer parking must be excluded from normal traversal", parking.canFocus)
        assertFalse(parking.request { false })
        assertFalse(parking.canFocus)
    }

    @Test fun returningToTheSameRouteDoesNotReactivateOldLandingCallbacks() {
        val owner = DiscoveryFocusRouteOwner()
        val firstLanding = Any()
        val detail = Any()
        val returnedLanding = Any()
        var active = DiscoveryState(profile = "one", surface = DashboardSurface.SERIES)
        fun originalLandingOwnsFocus() = owner.owns(firstLanding) &&
            active.ownsFocusRoute("one", DashboardSurface.SERIES, "landing")
        owner.claim(firstLanding)
        assertTrue(originalLandingOwnsFocus())
        active = active.copy(detail = DiscoveryCard(2, true, "Selected series"))
        owner.claim(detail)
        assertFalse(originalLandingOwnsFocus())
        owner.release(firstLanding)
        assertTrue("Old disposal must not clear the detail owner", owner.owns(detail))
        active = active.copy(detail = null)
        owner.claim(returnedLanding)
        assertTrue(active.ownsFocusRoute("one", DashboardSurface.SERIES, "landing"))
        assertFalse("Matching route text must not revive the old landing instance", originalLandingOwnsFocus())
        owner.release(detail)
        assertTrue("Detail disposal must not clear the returned landing", owner.owns(returnedLanding))
        owner.release(returnedLanding)
        assertFalse(owner.owns(returnedLanding))
    }

    @Test fun outgoingLandingFocusCannotOverwriteItsOriginAfterTheDetailModelCommits() {
        val origins = DiscoveryFocusOrigins("ranked:tv:2", mapOf("landing" to "ranked:tv:2"))
        var active = DiscoveryState(profile = "one", surface = DashboardSurface.SERIES)
        fun landingFocus(key: String) {
            if (active.ownsFocusRoute("one", DashboardSurface.SERIES, "landing"))
                origins.routes = origins.routes + ("landing" to key)
        }
        assertTrue(active.ownsFocusRoute("one", DashboardSurface.SERIES, "landing"))
        active = active.copy(detail = DiscoveryCard(2, true, "Selected series"))
        landingFocus("episodes:first") // Old lazy content has not been disposed yet.
        assertEquals("ranked:tv:2", origins.routes["landing"])
        assertTrue(active.ownsFocusRoute("one", DashboardSurface.SERIES, "tv:2"))
        active = active.copy(detail = null)
        assertFalse(active.ownsFocusRoute("one", DashboardSurface.SERIES, "tv:2"))
        landingFocus("ranked:tv:2")
        assertEquals("ranked:tv:2", origins.routes["landing"])
    }

    @Test fun oldProfileSurfaceAndNestedDetailCannotOwnTheCurrentFocusRoute() {
        val active = DiscoveryState(profile = "two", surface = DashboardSurface.MOVIES,
            detail = DiscoveryCard(3, false, "Current movie"))
        assertTrue(active.ownsFocusRoute("two", DashboardSurface.MOVIES, "movie:3"))
        assertFalse(active.ownsFocusRoute("one", DashboardSurface.MOVIES, "movie:3"))
        assertFalse(active.ownsFocusRoute("two", DashboardSurface.SERIES, "movie:3"))
        assertFalse(active.ownsFocusRoute("two", DashboardSurface.MOVIES, "movie:2"))
        assertFalse(active.ownsFocusRoute("two", DashboardSurface.MOVIES, "landing"))
    }

    @Test fun lazyAutomaticFocusCannotReplaceTheCapturedSecondTileUntilRestorationEnds() {
        val origins = DiscoveryFocusOrigins("row:second", mapOf("landing" to "row:second"))
        val restoring = DiscoveryFocusRestoration(origins.routes["landing"])
        fun record(key: String) { if (restoring.accepts(key)) origins.routes = origins.routes + ("landing" to key) }
        record("row:first")
        assertEquals("row:second", origins.routes["landing"])
        assertEquals("row:second", restoring.target)
        record("row:second")
        restoring.finish()
        record("row:third")
        assertEquals("row:third", origins.routes["landing"])
    }

    @Test fun deliberateInputCancelsRestorationAndAllowsTheUsersNewSelection() {
        val restoring = DiscoveryFocusRestoration("row:second")
        assertFalse(restoring.accepts("row:third"))
        restoring.cancel()
        assertTrue(restoring.canceled)
        assertFalse(restoring.pending)
        assertTrue(restoring.accepts("row:third"))
        restoring.finish()
        assertTrue(restoring.canceled)
        assertTrue(DiscoveryFocusRestoration(null).accepts("row:first"))
    }

    @Test fun restorationKeepsTheLandingAndNestedDetailDestinations() {
        val origins = DiscoveryFocusOrigins("related:movie:12", mapOf(
            "landing" to "provider:MOVIES:9:34",
            "movie:8" to "related:movie:12",
        ))
        val restored = DiscoveryFocusOrigins.restored(origins.saved())
        assertEquals(origins.returningTo, restored.returningTo)
        assertEquals(origins.routes, restored.routes)
        restored.returningTo = "movie:12:play"
        restored.routes = restored.routes + ("movie:12" to "movie:12:play")
        assertEquals("provider:MOVIES:9:34", restored.routes["landing"])
    }

    @Test fun emptyProfileStateAndMalformedTrailingPairAreSafeToRestore() {
        val restored = DiscoveryFocusOrigins.restored(DiscoveryFocusOrigins().saved())
        assertNull(restored.returningTo)
        assertTrue(restored.routes.isEmpty())
        assertNull(DiscoveryFocusOrigins.restored(emptyList()).returningTo)
        assertEquals(mapOf("landing" to "continue:1"),
            DiscoveryFocusOrigins.restored(listOf("", "landing", "continue:1", "incomplete")).routes)
    }

    @Test fun separateProfileOrSurfaceHoldersCannotChangeOneAnothersOrigins() {
        val movies = DiscoveryFocusOrigins("daily-movies:movie:1", mapOf("landing" to "daily-movies:movie:1"))
        val series = DiscoveryFocusOrigins()
        series.returningTo = "daily-series:tv:2"
        series.routes = mapOf("landing" to series.returningTo!!)
        assertEquals("daily-movies:movie:1", movies.returningTo)
        assertEquals("daily-movies:movie:1", movies.routes["landing"])
    }
}
