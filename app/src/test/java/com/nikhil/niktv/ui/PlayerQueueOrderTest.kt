package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueOrderTest {
    private fun channel(id: String) = MediaItem(id, "Channel $id", null, null)
    private val channels = listOf(channel("a"), channel("b"), channel("c"))

    @Test fun pinAddsShortcutAndPreservesProviderOccurrence() {
        assertEquals(listOf("c", "a", "b", "c"), orderedPlayerQueue(channels, listOf("c")).map { it.id })
    }

    @Test fun duplicateAndMissingPinsDoNotDuplicateOrAddChannelsOutsideContext() {
        assertEquals(listOf("c", "b", "a", "b", "c"),
            orderedPlayerQueue(channels, listOf("c", "missing", "c", "b")).map { it.id })
    }

    @Test fun repeatedOrderingAndNewPagesKeepProviderOrderWithLatestMetadata() {
        val first = orderedPlayerQueue(channels, listOf("c"))
        assertEquals(first, orderedPlayerQueue(first, listOf("c")))
        val updated = channels.map { if (it.id == "c") it.copy(description = "Current programme") else it }
        val expanded = orderedPlayerQueue(updated + channel("d"), listOf("c"))
        assertEquals(listOf("c", "a", "b", "c", "d"), expanded.map { it.id })
        assertEquals("Current programme", expanded.first().description)
        assertEquals(listOf("a", "b", "c"), channels.map { it.id })
    }

    @Test fun unpinUsesOriginalProviderOrderAndKeepsFirstProviderOccurrence() {
        assertEquals(channels, orderedPlayerQueue(channels + channels[1].copy(title = "Duplicate")))
        assertEquals(channels, orderedPlayerQueue(channels, emptyList()))
        assertEquals(channels, orderedPlayerQueue(orderedPlayerQueue(channels, listOf("c")), emptyList()))
        assertTrue(orderedPlayerQueue(emptyList(), listOf("a")).isEmpty())
    }

    @Test fun nextPreviousAndWrapUseSameOrderAsVisibleQueue() {
        val ids = orderedPlayerQueue(channels, listOf("c")).map { it.id }
        val cursor = PlaybackNavigationCursor()
        cursor.select(1, ids, "c", 0)
        cursor.commit(1, ids, "c")
        val index = cursor.index(ids, "c")
        assertEquals("a", ids[adjacentQueueIndex(index, ids.size, 1, true)!!])
        assertEquals("c", ids[adjacentQueueIndex(index, ids.size, -1, true)!!])
    }

    @Test fun providerSelectionSurvivesRemovingItsPinnedShortcut() {
        val ids = orderedPlayerQueue(channels, listOf("c")).map { it.id }
        val cursor = PlaybackNavigationCursor()
        cursor.select(2, ids, "c", 3)
        cursor.commit(2, ids, "c")
        assertEquals(2, cursor.index(channels.map { it.id }, "c"))
    }
    @Test fun channel65RemainsBetween64And66AfterPinning() {
        val provider = (1..100).map { channel(it.toString()) }
        val queue = orderedPlayerQueue(provider, listOf("65"))
        assertEquals("65", queue.first().id)
        assertEquals(provider, queue.drop(1))
        val ids = queue.map { it.id }
        val cursor = PlaybackNavigationCursor()
        cursor.select(7, ids, "65", 65)
        cursor.commit(7, ids, "65")
        assertEquals(65, cursor.index(ids, "65"))
        assertEquals("64", ids[adjacentQueueIndex(65, ids.size, -1, true)!!])
        assertEquals("66", ids[adjacentQueueIndex(65, ids.size, 1, true)!!])
    }

}
