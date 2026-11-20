package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackSurfaceBindingsTest {
    @Test fun oldViewReleaseCannotDetachPlayerReboundByFullscreenTransition() {
        val bindings = PlaybackSurfaceBindings<Any, Any>()
        val player = Any(); val oldView = Any(); val newView = Any()
        bindings.bind(oldView, player)
        bindings.bind(newView, player)
        assertNull(bindings.release(oldView))
        assertSame(player, bindings.player(newView))
        assertSame(player, bindings.release(newView))
        assertNull(bindings.release(newView))
    }
    @Test fun retainedViewTransfersToNewPlayerWithoutLeavingOldOwnership() {
        val bindings = PlaybackSurfaceBindings<Any, Any>()
        val first = Any(); val next = Any(); val retained = Any(); val replacement = Any()
        bindings.bind(retained, first)
        bindings.bind(retained, next)
        bindings.bind(replacement, first)
        assertSame(next, bindings.release(retained))
        assertSame(first, bindings.player(replacement))
    }
}
