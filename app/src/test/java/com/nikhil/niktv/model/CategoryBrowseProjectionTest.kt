package com.nikhil.niktv.model

import com.nikhil.niktv.discovery.components.CategoryBrowseInput
import com.nikhil.niktv.discovery.components.organizeCategoryBrowse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBrowseProjectionTest {
    private val news = Category("news", "English News", CatalogType.LIVE_TV)
    private val sports = Category("sports", "Hindi Cricket", CatalogType.LIVE_TV)

    @Test fun retainsPreparedCategoriesDuringAnAdditiveRefresh() {
        val input = CategoryBrowseInput(listOf(news), emptyList())
        val prepared = organizeCategoryBrowse(input, CategoryBrowseMode.COLLECTIONS)

        assertTrue(prepared.canDisplayFor(input))
        assertTrue(prepared.canDisplayFor(input.copy(provider = listOf(news, sports))))
        assertFalse(prepared.canDisplayFor(input.copy(provider = emptyList())))
        assertFalse(prepared.canDisplayFor(input.copy(provider = listOf(news.copy(isAdult = true)))))
    }

    @Test fun recognizesACompletedEmptyProjectionAndInvalidatesChangedCollections() {
        val input = CategoryBrowseInput(listOf(news), emptyList())
        val prepared = organizeCategoryBrowse(input, CategoryBrowseMode.CUSTOM)

        assertTrue(prepared.categories.isEmpty())
        assertTrue(prepared.canDisplayFor(input))
        assertFalse(prepared.canDisplayFor(input.copy(collections = listOf(
            Category("favorites", "Favorites", CatalogType.LIVE_TV, sourceCategoryIds = listOf(news.id))
        ))))
    }
}
