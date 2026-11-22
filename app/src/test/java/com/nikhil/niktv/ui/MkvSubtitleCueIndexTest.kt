package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class MkvSubtitleCueIndexTest {
    @Test fun longCueUsesItsOwnClusterAndAnExclusiveEnd() {
        val index = MkvSubtitleCueIndex()
        val arabic = MkvSubtitleCueIndex.Entry(3, 500_000, 7_000_000, 800)
        index.add(arabic)
        index.add(MkvSubtitleCueIndex.Entry(4, 500_000, 7_000_000, 1_200))
        assertTrue(index.active(3, 499_999).isEmpty())
        assertEquals(listOf(arabic), index.active(3, 3_000_000))
        assertEquals(listOf(arabic), index.active(3, 6_999_999))
        assertTrue(index.active(3, 7_000_000).isEmpty())
        assertTrue(index.active(null, 3_000_000).isEmpty())
    }

    @Test fun overlappingCueLocationsAreAllRetainedForTheSelectedTrack() {
        val index = MkvSubtitleCueIndex()
        val first = MkvSubtitleCueIndex.Entry(3, 500_000, 5_000_000, 800)
        val second = MkvSubtitleCueIndex.Entry(3, 2_000_000, 7_000_000, 2_800)
        index.add(first)
        index.add(second)
        index.add(first)
        assertEquals(listOf(first, second), index.active(3, 3_000_000))
        assertEquals(listOf(second), index.active(3, 5_000_000))
    }

    @Test fun malformedIndexEntriesCannotScheduleInvalidReads() {
        val index = MkvSubtitleCueIndex()
        listOf(
            MkvSubtitleCueIndex.Entry(-1, 500, 7_000, 800),
            MkvSubtitleCueIndex.Entry(3, -500, 7_000, 800),
            MkvSubtitleCueIndex.Entry(3, 500, 500, 800),
            MkvSubtitleCueIndex.Entry(3, 7_000, 500, 800),
            MkvSubtitleCueIndex.Entry(3, 500, 7_000, -800)
        ).forEach(index::add)
        assertTrue(index.active(3, 3_000).isEmpty())
    }
}
