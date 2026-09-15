package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerLeaseTest {
    @Test
    fun releaseOnceReleasesOwnedPlayerOnlyOnce() {
        val player = Any()
        var releases = 0
        val lease = PlayerLease(player) {
            assertSame(player, it)
            releases++
        }

        lease.releaseOnce()
        lease.releaseOnce()

        assertEquals(1, releases)
    }

    @Test
    fun independentLeasesReleaseIndependently() {
        var firstReleases = 0
        var secondReleases = 0
        val first = PlayerLease(Any()) { firstReleases++ }
        val second = PlayerLease(Any()) { secondReleases++ }

        first.releaseOnce()
        second.releaseOnce()
        first.releaseOnce()

        assertEquals(1, firstReleases)
        assertEquals(1, secondReleases)
    }

    @Test
    fun activeSelectionAdoptsCurrentLocalPlayerWhenCastIsInactive() {
        val cast = Any()
        val oldLocal = Any()
        val newLocal = Any()

        assertSame(oldLocal, selectActivePlayer(false, cast, oldLocal))
        assertSame(newLocal, selectActivePlayer(false, cast, newLocal))
    }

    @Test
    fun activeSelectionKeepsCastWhenSessionIsActive() {
        val cast = Any()

        assertSame(cast, selectActivePlayer(true, cast, null))
    }
}
