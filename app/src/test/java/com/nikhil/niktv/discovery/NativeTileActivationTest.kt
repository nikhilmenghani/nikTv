package com.nikhil.niktv.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTileActivationTest {
    @Test fun shortAndHeldPressCommitExactlyOnceOnRelease() {
        val state = NativeTileActivation()
        state.down(23, 100, false)
        assertEquals(NativeTileActivation.Action.CLICK, state.up(23, 200, false, 500))
        assertEquals(NativeTileActivation.Action.NONE, state.up(23, 201, false, 500))
        state.down(23, 1000, false)
        state.down(23, 1100, false)
        assertEquals(NativeTileActivation.Action.LONG_CLICK, state.up(23, 1600, false, 500))
        assertEquals(NativeTileActivation.Action.NONE, state.up(23, 1601, false, 500))
    }

    @Test fun nativeLongRepeatDoesNotRequireAScheduledMainThreadTimer() {
        val state = NativeTileActivation()
        state.down(66, 0, false)
        state.down(66, 50, true)
        assertEquals(NativeTileActivation.Action.LONG_CLICK, state.up(66, 60, false, 500))
    }

    @Test fun canceledFocusRecyclingAndMismatchedKeysCannotActivateAnotherItem() {
        val state = NativeTileActivation()
        state.down(23, 0, false)
        assertEquals(NativeTileActivation.Action.NONE, state.up(23, 900, true, 500))
        state.down(23, 1000, false)
        state.cancel()
        assertEquals(NativeTileActivation.Action.NONE, state.up(23, 1900, false, 500))
        state.down(23, 2000, false)
        assertEquals(NativeTileActivation.Action.NONE, state.up(66, 2900, false, 500))
        assertEquals(NativeTileActivation.Action.NONE, state.up(23, 2901, false, 500))
    }
}
