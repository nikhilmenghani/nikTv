package com.nikhil.niktv.ui

import android.content.Context
import com.nikhil.niktv.model.CatalogType

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
        preferences(context).getStringSet(channelKey(profileKey, categoryId), emptySet()).orEmpty().toSet()

    fun toggleChannel(
        context: Context,
        profileKey: String,
        categoryId: String,
        channelId: String
    ): Set<String> {
        val updated = pinnedChannels(context, profileKey, categoryId).toggle(channelId)
        preferences(context).edit().putStringSet(channelKey(profileKey, categoryId), updated).apply()
        return updated
    }

    private fun Set<String>.toggle(id: String): Set<String> =
        if (id in this) this - id else this + id
}
