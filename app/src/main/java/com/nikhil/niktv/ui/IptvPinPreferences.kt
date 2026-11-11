package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.CatalogType
import org.json.JSONArray

/** Profile-scoped ordering preferences for IPTV destinations and channels. */
internal object IptvPinPreferences {
    private const val FILE = "iptv_pin_preferences"
    private const val CHANNEL_FORMAT = 2
    private const val UNPINNED_ORDER = -1L

    internal fun preferences(context: Context,
        owner: UserScope? = UserSyncStore.get(context).selectedScope.value): SharedPreferences =
        com.nikhil.niktv.data.UserProfilePreferences.get(context, FILE, owner = owner)

    private fun categoryKey(profileKey: String, type: CatalogType) =
        "$profileKey|category|${type.name}"

    private fun channelKey(profileKey: String, categoryId: String) =
        "$profileKey|channel|$categoryId"

    private fun channelEntryPrefix(profileKey: String, categoryId: String) =
        "${channelKey(profileKey, categoryId)}|entry-v2|"

    private fun channelFormatKey(profileKey: String, categoryId: String) =
        "${channelKey(profileKey, categoryId)}|format-v2"

    internal fun snapshot(preferences: SharedPreferences, profileKey: String): IptvPinSnapshot =
        IptvPinSnapshot(preferences.all.filterKeys { it.startsWith("$profileKey|") }, profileKey)

    internal fun pinnedCategories(values: Map<String, *>, profileKey: String, type: CatalogType): Set<String> =
        (values[categoryKey(profileKey, type)] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()

    fun pinnedCategories(context: Context, profileKey: String, type: CatalogType): Set<String> =
        preferences(context).getStringSet(categoryKey(profileKey, type), emptySet()).orEmpty().toSet()

    fun toggleCategory(
        context: Context,
        profileKey: String,
        type: CatalogType,
        categoryId: String
    ): Set<String> {
        val prefs = preferences(context)
        return synchronized(prefs) {
            val updated = pinnedCategories(prefs.all, profileKey, type).toggle(categoryId)
            prefs.edit().putStringSet(categoryKey(profileKey, type), updated).apply()
            updated
        }
    }

    fun pinnedChannels(context: Context, profileKey: String, categoryId: String): Set<String> =
        pinnedChannelOrder(context, profileKey, categoryId).toSet()

    fun pinnedChannelOrder(context: Context, profileKey: String, categoryId: String): List<String> {
        return pinnedChannelOrder(preferences(context).all, profileKey, categoryId)
    }

    private fun usesChannelEntries(values: Map<String, *>, profileKey: String, categoryId: String): Boolean =
        values[channelFormatKey(profileKey, categoryId)] == CHANNEL_FORMAT ||
            values.keys.any { it.startsWith(channelEntryPrefix(profileKey, categoryId)) }

    internal fun pinnedChannelOrder(values: Map<String, *>, profileKey: String, categoryId: String): List<String> {
        val prefix = channelEntryPrefix(profileKey, categoryId)
        if (usesChannelEntries(values, profileKey, categoryId)) {
            return values.entries.mapNotNull { (key, value) ->
                if (!key.startsWith(prefix)) null else (value as? Number)?.toLong()?.takeIf { it >= 0 }?.let {
                    key.removePrefix(prefix) to it
                }
            }.sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first }).map { it.first }
        }
        val key = channelKey(profileKey, categoryId)
        val encoded = values["$key|order"] as? String
        if (encoded != null) {
            return runCatching {
                val array = JSONArray(encoded)
                (0 until array.length()).map { array.getString(it) }.distinct()
            }.getOrDefault(emptyList())
        }
        // Preserve pins created before explicit ordering was stored.
        return (values[key] as? Set<*>)?.filterIsInstance<String>()?.sorted().orEmpty()
    }

    fun toggleChannel(
        context: Context,
        profileKey: String,
        categoryId: String,
        channelId: String
    ): List<String> {
        val prefs = preferences(context)
        return synchronized(prefs) {
            val values = prefs.all
            val current = pinnedChannelOrder(values, profileKey, categoryId)
            val prefix = channelEntryPrefix(profileKey, categoryId)
            val entriesExist = usesChannelEntries(values, profileKey, categoryId)
            val editor = prefs.edit()
            // Migrate with the first edit, retaining the old order without writing during a read.
            // Each channel has its own sync record, including an explicit unpinned state.
            if (!entriesExist) current.forEachIndexed { index, id -> editor.putLong(prefix + id, index.toLong()) }
            val key = channelKey(profileKey, categoryId)
            editor.putInt(channelFormatKey(profileKey, categoryId), CHANNEL_FORMAT)
                .remove(key).remove("$key|order")
            val updated = if (channelId in current) {
                // A migration and first unpin may happen in the same editor. Removing a
                // newly seeded key would be absent before/after and produce no sync record.
                editor.putLong(prefix + channelId, UNPINNED_ORDER)
                current - channelId
            } else {
                val highestOrder = if (entriesExist) values.entries.filter { it.key.startsWith(prefix) }
                    .mapNotNull { (it.value as? Number)?.toLong()?.takeIf { order -> order >= 0 } }.maxOrNull() ?: -1L else current.size.toLong() - 1
                val nextOrder = maxOf(System.currentTimeMillis(), if (highestOrder < Long.MAX_VALUE) highestOrder + 1 else highestOrder)
                editor.putLong(prefix + channelId, nextOrder)
                current + channelId
            }
            editor.apply()
            updated
        }
    }

    private fun Set<String>.toggle(id: String): Set<String> =
        if (id in this) this - id else this + id
}

/** Immutable, viewer-scoped values; one snapshot can serve every category on a screen. */
internal class IptvPinSnapshot internal constructor(private val values: Map<String, *>, private val profileKey: String) {
    fun pinnedCategories(type: CatalogType): Set<String> = IptvPinPreferences.pinnedCategories(values, profileKey, type)
    fun pinnedChannelOrder(categoryId: String): List<String> = IptvPinPreferences.pinnedChannelOrder(values, profileKey, categoryId)
}
