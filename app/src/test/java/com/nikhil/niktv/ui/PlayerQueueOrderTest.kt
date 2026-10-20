package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueOrderTest {
    private fun channel(id: String) = MediaItem(id, "Channel $id", null, null)
    private val channels = listOf(channel("a"), channel("b"), channel("c"))

    @Test fun pinMovesChannelToFrontWithoutCopyingIt() {
        assertEquals(listOf("c", "a", "b"), orderedPlayerQueue(channels, listOf("c")).map { it.id })
    }

    @Test fun duplicateAndMissingPinsDoNotDuplicateOrAddChannelsOutsideContext() {
        assertEquals(listOf("c", "b", "a"),
            orderedPlayerQueue(channels, listOf("c", "missing", "c", "b")).map { it.id })
    }

    @Test fun repeatedOrderingAndNewPagesKeepOneEntryWithLatestInputMetadata() {
        val first = orderedPlayerQueue(channels, listOf("c"))
        assertEquals(first, orderedPlayerQueue(first, listOf("c")))
        val updated = channels.map { if (it.id == "c") it.copy(description = "Current programme") else it }
        val expanded = orderedPlayerQueue(updated + channel("d"), listOf("c"))
        assertEquals(listOf("c", "a", "b", "d"), expanded.map { it.id })
        assertEquals("Current programme", expanded.first().description)
        assertEquals(listOf("a", "b", "c"), channels.map { it.id })
    }

    @Test fun unpinUsesOriginalProviderOrderAndKeepsFirstProviderOccurrence() {
        assertEquals(channels, orderedPlayerQueue(channels + channels[1].copy(title = "Duplicate")))
        assertEquals(channels, orderedPlayerQueue(channels, emptyList()))
        assertTrue(orderedPlayerQueue(emptyList(), listOf("a")).isEmpty())
    }

    @Test fun nextPreviousAndWrapUseSameUniqueOrderAsVisibleQueue() {
        val ids = orderedPlayerQueue(channels, listOf("c")).map { it.id }
        val cursor = PlaybackNavigationCursor()
        cursor.select(1, ids, "c", 0)
        cursor.commit(1, ids, "c")
        val index = cursor.index(ids, "c")
        assertEquals("a", ids[adjacentQueueIndex(index, ids.size, 1, true)!!])
        assertEquals("b", ids[adjacentQueueIndex(index, ids.size, -1, true)!!])
    }

    @Test fun pinnedSingletonStillCannotNavigateToItself() {
        val queue = orderedPlayerQueue(listOf(channels.first()), listOf("a", "a"))
        assertEquals(1, queue.size)
        assertNull(adjacentQueueIndex(0, queue.size, 1, true))
        assertNull(adjacentQueueIndex(0, queue.size, -1, true))
    }
}
