package com.nikhil.niktv.model

import com.nikhil.niktv.data.CategoryCollectionPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CategoryCollectionTest {
    @Test fun customDefinitionsRoundTripAndKeepOriginalProviderIds() {
        val created = CategoryCollectionPreferences.create(" My movies ", CatalogType.MOVIES, listOf("b", "a", "a"))
        assertEquals("My movies", created.title)
        assertEquals(listOf("a", "b"), created.sourceCategoryIds)
        assertEquals(created, Json.decodeFromString<Category>(Json.encodeToString(created)))
    }

    @Test fun hiddenDeletedAndSensitiveSourcesCannotReappearFromSavedDefinition() {
        val definition = Category("@custom:test", "Mixed", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b", "c"))
        val available = listOf(Category("a", "English", CatalogType.MOVIES),
            Category("b", "Private", CatalogType.MOVIES, isAdult = true))
        val visible = CategoryCollectionPreferences.visible(listOf(definition), available).single()
        assertEquals(listOf("a"), visible.sourceCategoryIds)
        assertEquals(listOf("a", "b", "c"), definition.sourceCategoryIds)
        assertTrue(CategoryCollectionPreferences.visible(listOf(definition), emptyList()).isEmpty())
    }
}
