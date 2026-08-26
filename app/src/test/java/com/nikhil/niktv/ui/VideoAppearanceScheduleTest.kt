package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAppearanceScheduleTest {
    @Test
    fun `fallback applies outside scheduled windows`() {
        val schedule = VideoAppearanceSchedule(
            enabled = true,
            fallbackProfileId = "standard",
            entries = listOf(VideoAppearanceScheduleEntry("movie", 18 * 60, 22 * 60))
        )

        assertEquals("standard", schedule.profileAt(12 * 60))
        assertEquals("movie", schedule.profileAt(20 * 60))
    }

    @Test
    fun `overnight window wraps across midnight`() {
        val schedule = VideoAppearanceSchedule(
            enabled = true,
            fallbackProfileId = "standard",
            entries = listOf(VideoAppearanceScheduleEntry("night", 22 * 60, 6 * 60))
        )

        assertEquals("night", schedule.profileAt(23 * 60))
        assertEquals("night", schedule.profileAt(5 * 60 + 45))
        assertEquals("standard", schedule.profileAt(6 * 60))
    }

    @Test
    fun `touching boundaries do not overlap`() {
        val morning = VideoAppearanceScheduleEntry("bright", 6 * 60, 9 * 60)
        val daytime = VideoAppearanceScheduleEntry("natural", 9 * 60, 12 * 60)
        val conflict = VideoAppearanceScheduleEntry("movie", 8 * 60 + 45, 10 * 60)

        assertFalse(schedulesOverlap(morning, daytime))
        assertTrue(schedulesOverlap(morning, conflict))
    }
}
