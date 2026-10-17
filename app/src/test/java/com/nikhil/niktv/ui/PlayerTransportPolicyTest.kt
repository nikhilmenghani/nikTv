package com.nikhil.niktv.ui

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class PlayerTransportPolicyTest {
    @Test fun channelButtonsUseSameDirectionsAsMediaNextPrevious() {
        assertEquals(1, liveChannelTransportDirection(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.ACTION_DOWN, 0))
        assertEquals(-1, liveChannelTransportDirection(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertEquals(1, liveChannelTransportDirection(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0))
        assertEquals(-1, liveChannelTransportDirection(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_DOWN, 0))
    }
    @Test fun repeatsAndReleaseAreConsumedWithoutDoubleSwitchAndDpadIsUntouched() {
        assertEquals(0, liveChannelTransportDirection(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.ACTION_DOWN, 1))
        assertEquals(0, liveChannelTransportDirection(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.ACTION_UP, 0))
        assertNull(liveChannelTransportDirection(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0))
    }
}
