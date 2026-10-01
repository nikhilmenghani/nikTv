package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.CategoryBrowseMode
import com.nikhil.niktv.model.CatalogType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** User choices, kept separately from rebuildable provider catalogue caches. */
object CategoryCollectionPreferences {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) = context.getSharedPreferences("browse_collections", Context.MODE_PRIVATE)
    fun browseMode(context: Context, profile: String, type: CatalogType): CategoryBrowseMode = runCatching {
        CategoryBrowseMode.valueOf(prefs(context).getString("$profile|${type.name}|mode", "COLLECTIONS")!!)
    }.getOrDefault(CategoryBrowseMode.COLLECTIONS)

    fun saveBrowseMode(context: Context, profile: String, type: CatalogType, mode: CategoryBrowseMode) {
        prefs(context).edit().putString("$profile|${type.name}|mode", mode.name).apply()
    }

    fun read(context: Context, profile: String, type: CatalogType): List<Category> = runCatching {
        json.decodeFromString<List<Category>>(prefs(context).getString("$profile|${type.name}", "[]")!!)
    }.getOrDefault(emptyList())

    fun save(context: Context, profile: String, type: CatalogType, collections: List<Category>) {
        prefs(context).edit().putString("$profile|${type.name}", json.encodeToString(collections)).apply()
    }

    fun create(title: String, type: CatalogType, ids: List<String>) = Category(
        "@custom:${UUID.randomUUID()}", title.trim(), type, sourceCategoryIds = ids.distinct().sorted()
    )

    fun visible(collections: List<Category>, available: List<Category>): List<Category> {
        val allowed = available.filterNot { it.isAdult || adultContentLabel(it.title) }.map { it.id }.toSet()
        return collections.mapNotNull { collection ->
            val ids = collection.sourceCategoryIds.filter { it in allowed }
            if (ids.isEmpty()) null else collection.copy(
                // Changed membership gets a fresh cache without changing the saved definition.
                id = collection.id + ":" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ids.sorted().joinToString("\u0000").toByteArray()).take(8)
                    .joinToString("") { "%02x".format(it) }, sourceCategoryIds = ids
            )
        }
    }
}
