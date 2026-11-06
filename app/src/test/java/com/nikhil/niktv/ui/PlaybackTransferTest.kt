package com.nikhil.niktv.ui

import org.junit.Test
import org.junit.Assert.*

class PlaybackTransferTest {
    @Test fun pauseAndPositionSurviveBothDirections() {
        for (playing in listOf(false, true)) {
            val cast = playbackTransfer(75_000L, playing)
            val local = playbackTransfer(cast.positionMillis, cast.playWhenReady)
            assertEquals(75_000L, local.positionMillis)
            assertEquals(playing, local.playWhenReady)
        }
    }
    @Test fun UnknownClockDoesNotProduceNegativeSeek() {
        assertEquals(0L, playbackTransfer(androidx.media3.common.C.TIME_UNSET, false).positionMillis)
    }
    @Test fun reconnectHangIsOwnedByRecoveryInsteadOfInitialStartupTimer() {
        assertTrue(vlcInitialStartupTimedOut(true, false, false, true, false))
        assertFalse(vlcInitialStartupTimedOut(true, true, false, true, false))
        assertFalse(vlcInitialStartupTimedOut(true, false, false, false, false))
        assertTrue(vlcInitialStartupTimedOut(false, true, false, true, false))
        val recovery = LiveStreamRecovery(LiveStreamRecovery.VLC_RETRY_DELAYS_MILLIS)
        assertFalse(recovery.shouldRecover(0, 1000, true, false))
        assertFalse(recovery.shouldRecover(21_000, 1000, true, true))
        assertTrue(recovery.shouldRecover(23_000, 1000, true, true))
        assertFalse(recovery.shouldRecover(24_000, 0, true, true))
        assertTrue(recovery.shouldRecover(29_000, 0, true, true))
    }
    @Test fun castLoadAppliesPauseBeforeMediaIsLoaded() {
        for (playing in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val player = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(androidx.media3.common.Player::class.java)) { _, method, args ->
                when (method.name) {
                    "setPlayWhenReady" -> { calls += "intent:${args!![0]}"; null }
                    "setMediaItem" -> { calls += "load:${args!![1]}"; null }
                    "prepare" -> { calls += "prepare"; null }
                    else -> error("Unexpected player API: ${method.name}")
                }
            } as androidx.media3.common.Player
            player.preparePlaybackTransfer(androidx.media3.common.MediaItem.Builder().setMediaId("fixture").build(), playbackTransfer(75_000, playing))
            assertEquals(listOf("intent:$playing", "load:75000", "prepare"), calls)
        }
    }
}
