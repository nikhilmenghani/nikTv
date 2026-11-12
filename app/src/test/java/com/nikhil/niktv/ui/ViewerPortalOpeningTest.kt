package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ViewerPortalOpeningTest {
    private val stalker = PortalProfile("wio", "https://fixture.invalid", "fixture-mac")
    private val xtream = PortalProfile("Nikhil", "https://other.invalid", "", portalType = PortalType.XTREAM,
        username = "fixture-user", password = "fixture-password")

    @Test fun rememberedAvailableConnectionOpensDirectly() {
        assertSame(xtream, preferredViewerPortal(listOf(stalker, xtream), xtream.cacheKey()))
    }

    @Test fun missingOrDisabledLastConnectionFallsBackOnlyWithinAvailableConnections() {
        assertSame(stalker, preferredViewerPortal(listOf(stalker), xtream.cacheKey()))
        assertSame(stalker, preferredViewerPortal(listOf(stalker), null))
        assertNull(preferredViewerPortal(emptyList(), xtream.cacheKey()))
    }

    @Test fun protocolLabelsDoNotChangeStoredConnectionIdentityOrCredentials() {
        assertEquals("Stalker", stalker.displayName)
        assertEquals("Xtream", xtream.displayName)
        val renamed = xtream.copy(name = "Another internal label")
        assertEquals(xtream.cacheKey(), renamed.cacheKey())
        assertEquals("Xtream", renamed.displayName)
        val stored = Json.decodeFromString<PortalProfile>(Json.encodeToString(xtream))
        assertEquals(xtream, stored)
        assertEquals("Nikhil", stored.name)
        assertFalse(Json.encodeToString(xtream).contains("displayName"))
    }
}
