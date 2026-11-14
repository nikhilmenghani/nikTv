package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class SeriesEpisodeGridTest {
    @Test fun twoColumnListIsLimitedToRoomyTabletWindows() {
        assertEquals(2, seriesEpisodeListColumns(800, 1280, false))
        assertEquals(1, seriesEpisodeListColumns(800, 800, false))
        assertEquals(1, seriesEpisodeListColumns(393, 900, false))
        assertEquals(1, seriesEpisodeListColumns(720, 1280, true))
    }
    @Test fun twoColumnListMovesAcrossThenDownAndHandlesOddCounts() {
        assertEquals(1, seriesEpisodeGridMove(0, 7, 2, dx = 1))
        assertEquals(3, seriesEpisodeGridMove(1, 7, 2, dy = 1))
        assertEquals(1, seriesEpisodeGridMove(3, 7, 2, dy = -1))
        assertEquals(6, seriesEpisodeGridMove(5, 7, 2, dy = 1))
        assertEquals(6, seriesEpisodeGridMove(6, 7, 2, dx = 1))
        assertEquals(2, seriesEpisodeViewportIndex(5, 2))
        assertEquals(5, seriesEpisodeLastVisible(2, 2, 7))
    }
    @Test fun directionsFollowRowsAndColumns() {
        assertEquals(6, seriesEpisodeGridMove(5, 20, 5, dx = 1))
        assertEquals(5, seriesEpisodeGridMove(6, 20, 5, dx = -1))
        assertEquals(11, seriesEpisodeGridMove(6, 20, 5, dy = 1))
        assertEquals(1, seriesEpisodeGridMove(6, 20, 5, dy = -1))
    }
    @Test fun topAndLeftEdgesLeadToToolbarWithoutWrapping() {
        assertNull(seriesEpisodeGridMove(3, 20, 5, dy = -1))
        assertNull(seriesEpisodeGridMove(10, 20, 5, dx = -1))
        assertEquals(9, seriesEpisodeGridMove(9, 20, 5, dx = 1))
    }
    @Test fun incompleteLastRowRemainsReachableAndStopsAtBottom() {
        assertEquals(11, seriesEpisodeGridMove(9, 12, 5, dy = 1))
        assertEquals(11, seriesEpisodeGridMove(11, 12, 5, dy = 1))
        assertEquals(11, seriesEpisodeGridMove(11, 12, 5, dx = 1))
        assertEquals(0, seriesEpisodeGridMove(0, 1, 5, dy = 1))
    }
    @Test fun resumeAndPaginationTranslateEpisodeIndicesToRows() {
        assertEquals(199, seriesEpisodeViewportIndex(998, 5))
        assertEquals(999, seriesEpisodeLastVisible(199, 5, 1000))
        assertEquals(11, seriesEpisodeLastVisible(2, 5, 12))
        assertEquals(-1, seriesEpisodeLastVisible(-1, 5, 12))
    }
}
