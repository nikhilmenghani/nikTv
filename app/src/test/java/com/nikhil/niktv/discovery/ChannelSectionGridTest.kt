package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class ChannelSectionGridTest {
    @Test fun sectionsMapTilesAroundHeadingsAndKeepOriginalOrder() {
        val grid = ChannelSectionGrid(5, 125, 4)
        for (i in 0 until 125) assertEquals(i, grid.tileIndex(grid.gridIndex(i)))
        assertEquals(1, grid.gridIndex(0))
        assertEquals(7, grid.gridIndex(5))
        assertEquals(4, grid.vertical(3, true))
        assertEquals(5, grid.vertical(4, true))
        assertEquals(4, grid.vertical(8, false))
        assertEquals(-1, grid.vertical(0, false))
    }
    @Test fun phoneRowsAndUnpinnedGridHaveNoSkippedChannels() {
        val phone = ChannelSectionGrid(2, 8, 1)
        for (i in 0 until 7) assertEquals(i + 1, phone.vertical(i, true))
        val plain = ChannelSectionGrid(0, 12, 4)
        assertEquals(6, plain.gridIndex(6))
        assertEquals(10, plain.vertical(6, true))
    }
}
