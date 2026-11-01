package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun selectableRatesIncludeSlowMotionNormalAndFastPlayback() {
        assertEquals(.1f, PLAYBACK_SPEED_PRESETS.first())
        assertEquals(4f, PLAYBACK_SPEED_PRESETS.last())
        assertTrue(1f in PLAYBACK_SPEED_PRESETS)
        assertEquals(PLAYBACK_SPEED_PRESETS.sorted().distinct(), PLAYBACK_SPEED_PRESETS)
    }
    @Test fun invalidAndOutOfRangeValuesCannotReachAnEngine() {
        assertEquals(1f, normalizedPlaybackSpeed(Float.NaN))
        assertEquals(1f, normalizedPlaybackSpeed(Float.POSITIVE_INFINITY))
        assertEquals(.1f, normalizedPlaybackSpeed(-4f)); assertEquals(4f, normalizedPlaybackSpeed(99f))
        assertEquals(.1f, adjustedPlaybackSpeed(.1f, -1)); assertEquals(4f, adjustedPlaybackSpeed(4f, 1))
    }
    @Test fun fineAdjustmentsAndLabelsAreStableForFractionalPresets() {
        assertEquals(1.35f, adjustedPlaybackSpeed(1.25f, 1))
        assertEquals(1.15f, adjustedPlaybackSpeed(1.25f, -1))
        assertEquals("1×", playbackSpeedLabel(1f)); assertEquals("0.1×", playbackSpeedLabel(.1f))
        assertEquals("1.25×", playbackSpeedLabel(1.25f))
    }
}
