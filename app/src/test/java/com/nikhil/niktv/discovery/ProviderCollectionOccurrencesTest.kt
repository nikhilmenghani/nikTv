package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class ProviderCollectionOccurrencesTest {
    private fun media(id: String) = MediaItem(id, "Channel $id", null, null)

    @Test fun pin65AddsShortcutWhileOriginalIdentityAndNeighboursStayStable() {
        val catalogue = (1..100).map { media(it.toString()) }
        val before = providerCollectionOccurrences(emptyList(), catalogue)
        val after = providerCollectionOccurrences(listOf(catalogue[64]), catalogue)
        assertEquals("pinned:65", after.first().key)
        assertEquals(before, after.drop(1))
        val index = requireNotNull(providerCollectionRestoreIndex(after, before[64].key, "65"))
        assertEquals(65, index)
        assertEquals(listOf("provider:64:0", "provider:65:0", "provider:66:0"), after.slice(index - 1..index + 1).map { it.key })
    }

    @Test fun removingFocusedShortcutFallsBackToOriginalProviderTile() {
        val catalogue = listOf(media("one"), media("two"), media("three"))
        val after = providerCollectionOccurrences(listOf(catalogue[0]), catalogue)
        assertEquals(3, providerCollectionRestoreIndex(after, "pinned:three", "three"))
        assertEquals(2, providerCollectionRestoreIndex(after, "provider:two:0", "two"))
        assertNull(providerCollectionRestoreIndex(after, "pinned:missing", "missing"))
        assertNull(providerCollectionRestoreIndex(after, null, null))
    }

    @Test fun duplicateProviderIdsHaveStableOrdinalsAndDuplicatePinsHaveOneShortcut() {
        val catalogue = listOf(media("a"), media("b"), media("a").copy(title = "Second a"), media("a").copy(title = "Third a"))
        val before = providerCollectionOccurrences(emptyList(), catalogue)
        val after = providerCollectionOccurrences(listOf(catalogue[0], catalogue[0], catalogue[1]), catalogue)
        assertEquals(listOf("pinned:a", "pinned:b"), after.take(2).map { it.key })
        assertEquals(listOf("provider:a:0", "provider:b:0", "provider:a:1", "provider:a:2"), before.map { it.key })
        assertEquals(before, after.drop(2))
        assertEquals(4, providerCollectionRestoreIndex(after, "provider:a:1", "a"))
        assertEquals(2, providerCollectionRestoreIndex(after, "pinned:a-removed", "a"))
        assertEquals(0, providerCollectionRestoreIndex(providerCollectionOccurrences(listOf(media("only-pin")), emptyList()), null, "only-pin"))
    }
}
