package com.nikhil.niktv.discovery.components

import com.nikhil.niktv.data.CategoryCollectionPreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.CategoryBrowseMode
import com.nikhil.niktv.model.CategoryOrganization

internal data class CategoryBrowseInput(val provider: List<Category>, val collections: List<Category>)

internal data class CategoryBrowseProjection(val input: CategoryBrowseInput, val categories: List<Category>) {
    /** Categories already shown remain safe while an additive provider refresh is organized. */
    fun canDisplayFor(next: CategoryBrowseInput): Boolean {
        if (input == next) return true
        if (categories.isEmpty() || input.collections != next.collections || input.provider.size > next.provider.size) return false
        val currentById = next.provider.associateBy(Category::id)
        return input.provider.all { currentById[it.id] == it }
    }
}

internal data class CategoryBrowseProjectionKey(val viewer: UserScope?, val portal: String,
    val type: CatalogType, val mode: CategoryBrowseMode)

/** Used on the main thread. Retain category metadata only, never media or artwork. */
internal object CategoryBrowseProjectionMemory {
    private const val maxEntries = 12
    private val entries = LinkedHashMap<CategoryBrowseProjectionKey, CategoryBrowseProjection>(16, .75f, true)

    fun get(key: CategoryBrowseProjectionKey) = entries[key]

    fun put(key: CategoryBrowseProjectionKey, projection: CategoryBrowseProjection) {
        entries[key] = projection
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
    }
}

internal fun organizeCategoryBrowse(input: CategoryBrowseInput, mode: CategoryBrowseMode): CategoryBrowseProjection =
    CategoryBrowseProjection(input, if (mode == CategoryBrowseMode.CUSTOM)
        CategoryCollectionPreferences.visible(input.collections, input.provider)
    else CategoryOrganization.organize(input.provider, mode))
