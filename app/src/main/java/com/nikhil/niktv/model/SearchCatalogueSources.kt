package com.nikhil.niktv.model

/** IDs/categories can overlap across providers; never borrow another profile's rows. */
internal fun BrowseCatalogCache?.searchItemsForProfile(profileKey: String?, category: String): List<MediaItem> {
    if (this == null || profileKey == null || this.profileKey != profileKey) return emptyList()
    return if (category == "*") itemsByCategory.values.flatten() else itemsByCategory[category].orEmpty()
}
