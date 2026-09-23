package com.nikhil.niktv.ui

import android.content.Context
import com.nikhil.niktv.model.CatalogType
import org.json.JSONArray

/** Profile-scoped ordering preferences for IPTV destinations and channels. */
internal object IptvPinPreferences {
    private const val FILE = "iptv_pin_preferences"

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun categoryKey(profileKey: String, type: CatalogType) =
        "$profileKey|category|${type.name}"

    private fun channelKey(profileKey: String, categoryId: String) =
        "$profileKey|channel|$categoryId"

    fun pinnedCategories(context: Context, profileKey: String, type: CatalogType): Set<String> =
        preferences(context).getStringSet(categoryKey(profileKey, type), emptySet()).orEmpty().toSet()

    fun toggleCategory(
        context: Context,
        profileKey: String,
        type: CatalogType,
        categoryId: String
    ): Set<String> {
        val updated = pinnedCategories(context, profileKey, type).toggle(categoryId)
        preferences(context).edit().putStringSet(categoryKey(profileKey, type), updated).apply()
        return updated
    }

    fun pinnedChannels(context: Context, profileKey: String, categoryId: String): Set<String> =
        pinnedChannelOrder(context, profileKey, categoryId).toSet()

    fun pinnedChannelOrder(context: Context, profileKey: String, categoryId: String): List<String> {
        val prefs = preferences(context)
        val key = channelKey(profileKey, categoryId)
        val encoded = prefs.getString("$key|order", null)
        if (encoded != null) {
            return runCatching {
                val array = JSONArray(encoded)
                (0 until array.length()).map { array.getString(it) }.distinct()
            }.getOrDefault(emptyList())
        }
        // Preserve pins created before explicit ordering was stored.
        return prefs.getStringSet(key, emptySet()).orEmpty().toList().sorted()
    }

    fun toggleChannel(
        context: Context,
        profileKey: String,
        categoryId: String,
        channelId: String
    ): List<String> {
        val current = pinnedChannelOrder(context, profileKey, categoryId)
        val updated = if (channelId in current) current - channelId else current + channelId
        val key = channelKey(profileKey, categoryId)
        preferences(context).edit()
            .putString("$key|order", JSONArray(updated).toString())
            .putStringSet(key, updated.toSet())
            .apply()
        return updated
    }

    private fun Set<String>.toggle(id: String): Set<String> =
        if (id in this) this - id else this + id
}
