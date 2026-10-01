package com.nikhil.niktv.model

/** Repair a category contaminated by an older in-flight pagination response. */
internal fun BrowseCatalogCache.validateCategory(categoryId: String, sourceCategoryIds: List<String> = emptyList()): BrowseCatalogCache {
    val allowedIds = sourceCategoryIds.toSet().ifEmpty { setOf(categoryId) }
    val mismatched = itemsByCategory[categoryId].orEmpty().any {
        !it.portalCategoryId.isNullOrBlank() && it.portalCategoryId !in allowedIds
    }
    if (!mismatched) return this
    // Pagination cannot be trusted after categories were mixed. Rebuild only
    // this category, leaving every other loaded category and its pages intact.
    return copy(
        itemsByCategory = itemsByCategory - categoryId,
        pagesByCategory = pagesByCategory - categoryId,
        hasMoreByCategory = hasMoreByCategory - categoryId,
        categoryCachedAtMillis = categoryCachedAtMillis - categoryId
    )
}
