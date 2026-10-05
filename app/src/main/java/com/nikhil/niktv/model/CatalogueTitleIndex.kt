package com.nikhil.niktv.model

import java.lang.ref.WeakReference

/** One lightweight title index; it does not keep a second full catalogue alive. */
internal class CatalogueTitleIndex(items: List<MediaItem>, private val profileKey: String? = null, private val type: CatalogType? = null) {
    private val source = WeakReference(items)
    private val titles = items.map { it.title.normalizedTitleKeywords() }
    private val positions = items.mapIndexed { index, item -> item.id to index }.toMap()
    fun isFor(items: List<MediaItem>): Boolean = source.get() === items
    fun isScopedTo(profile: String?, catalogType: CatalogType): Boolean = profileKey == profile && type == catalogType
    fun findCandidates(candidates: List<MediaItem>, query: String): List<MediaItem> {
        val original = source.get()
        val match = normalizedTitleKeywordMatcher(query)
        return candidates.filter { item ->
            val index = positions[item.id]
            val title = if (index != null && original?.get(index)?.title == item.title) titles[index]
                else item.title.normalizedTitleKeywords()
            match(title)
        }
    }
    fun find(items: List<MediaItem>, query: String, category: String): List<MediaItem> {
        require(isFor(items))
        val match = normalizedTitleKeywordMatcher(query)
        return items.filterIndexed { index, item ->
            (category == "*" || item.portalCategoryId == category) && match(titles[index])
        }
    }
}
