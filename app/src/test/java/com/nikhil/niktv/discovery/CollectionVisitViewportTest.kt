package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class CollectionVisitViewportTest {
    @Test fun layoutChangeKeepsTheChannelButDiscardsOldRowPixelOffset() {
        val memory = CollectionVisitViewportMemory()
        memory.markReady("rotation")
        memory.put("rotation", 12, 190, "portrait")
        assertEquals(CollectionViewport(12, 190, true, "portrait"), memory.entry("rotation").forLayout("portrait"))
        assertEquals(CollectionViewport(12, 0, true, "portrait"), memory.entry("rotation").forLayout("landscape"))
        memory.put("rotation", 0, 0, "landscape")
        assertEquals(0, memory.entry("rotation").forLayout("portrait").index)
        assertEquals(0, memory.entry("rotation").forLayout("portrait").offset)
    }

    @Test fun reopeningTheSameCategoryStartsAtTopButPlaybackReturnKeepsPosition() {
        val memory = CollectionVisitViewportMemory()
        val firstVisit = "viewer:portal:LIVE_TV:hindi:${CatalogVisitIds.next()}"
        memory.markReady(firstVisit)
        memory.put(firstVisit, 90, 37)

        assertEquals(CollectionViewport(90, 37, true), memory.entry(firstVisit))
        val nextVisit = "viewer:portal:LIVE_TV:hindi:${CatalogVisitIds.next()}"
        assertEquals(CollectionViewport(), memory.entry(nextVisit))
    }

    @Test fun loadingOnlyPositionIsNotRestoredAsAnEstablishedChannelPosition() {
        val memory = CollectionVisitViewportMemory()
        memory.put("loading", 4, 100)
        assertEquals(CollectionViewport(), memory.entry("loading"))
        memory.put("loading", 0, 0)
        memory.markReady("loading")
        memory.put("loading", 12, 30)
        assertEquals(CollectionViewport(12, 30, true), memory.entry("loading"))
    }

    @Test fun anotherCategoryOrViewerDoesNotInheritThePosition() {
        val memory = CollectionVisitViewportMemory()
        memory.markReady("one:portal:news:1")
        memory.put("one:portal:news:1", 80, 20)
        assertEquals(CollectionViewport(), memory.entry("two:portal:news:1"))
        assertEquals(CollectionViewport(), memory.entry("one:portal:movies:1"))
    }

    @Test fun obsoleteVisitsAreBoundedAndInvalidPositionsClamped() {
        val memory = CollectionVisitViewportMemory(limit = 2)
        memory.markReady("old")
        memory.put("second", 10, 20)
        memory.put("third", -1, -2)
        assertNull(memory.get("old"))
        assertEquals(CollectionViewport(), memory.get("third"))
        assertEquals(CollectionViewport(10, 20), memory.get("second"))
    }
}
