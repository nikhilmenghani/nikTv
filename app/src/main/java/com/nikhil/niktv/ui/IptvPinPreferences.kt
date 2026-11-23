package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private fun categoryOrderKey(profileKey: String, type: CatalogType) = "$profileKey|category-order|${type.name}"
    private fun tmdbOrderKey(profileKey: String, surface: DashboardSurface) = "$profileKey|tmdb-order|${surface.name}"
    private fun pinnedOverviewOrderKey(profileKey: String) = "$profileKey|pinned-overview-order"

    internal fun readOrder(value: Any?): List<String> = (value as? String)?.let { encoded ->
        runCatching { val array = JSONArray(encoded); (0 until array.length()).map { array.getString(it) }.distinct() }.getOrNull()
    }.orEmpty()

    internal fun categoryOrder(values: Map<String, *>, profileKey: String, type: CatalogType) = readOrder(values[categoryOrderKey(profileKey, type)])
    internal fun tmdbOrder(values: Map<String, *>, profileKey: String, surface: DashboardSurface) = readOrder(values[tmdbOrderKey(profileKey, surface)])
    internal fun pinnedOverviewOrder(values: Map<String, *>, profileKey: String) = readOrder(values[pinnedOverviewOrderKey(profileKey)])

    suspend fun savePinnedOverviewOrder(context: Context, owner: UserScope?, profileKey: String, ids: List<String>) =
        saveOrder(context, owner, pinnedOverviewOrderKey(profileKey), ids)

    suspend fun saveCategoryOrder(context: Context, owner: UserScope?, profileKey: String, type: CatalogType, ids: List<String>) =
        saveOrder(context, owner, categoryOrderKey(profileKey, type), ids)

    suspend fun saveTmdbOrder(context: Context, owner: UserScope?, profileKey: String, surface: DashboardSurface, ids: List<String>) =
        saveOrder(context, owner, tmdbOrderKey(profileKey, surface), ids)

    private suspend fun saveOrder(context: Context, owner: UserScope?, key: String, ids: List<String>) = withContext(Dispatchers.IO) {
        owner?.let { com.nikhil.niktv.data.UserProfilePreferences.warm(context, it) }
        val prefs = preferences(context, owner)
        synchronized(prefs) {
            check(prefs.edit().putString(key, JSONArray(mergeDisplayOrder(readOrder(prefs.all[key]), ids)).toString()).commit())
        }
        owner?.let { com.nikhil.niktv.data.AccountSyncManager.get(context).requestProfileSync(it.accountId) }
    }

    /** Change ranks only for channels still pinned at commit time; never re-pin a stale dialog item. */
    suspend fun reorderChannels(context: Context, owner: UserScope?, profileKey: String, categoryId: String, ids: List<String>) = withContext(Dispatchers.IO) {
        owner?.let { com.nikhil.niktv.data.UserProfilePreferences.warm(context, it) }
        val prefs = preferences(context, owner)
        synchronized(prefs) {
            val current = pinnedChannelOrder(prefs.all, profileKey, categoryId)
            val updated = orderedForDisplay(current, ids) { it }
            val prefix = channelEntryPrefix(profileKey, categoryId)
            val editor = prefs.edit().putInt(channelFormatKey(profileKey, categoryId), CHANNEL_FORMAT)
            updated.forEachIndexed { index, id -> editor.putLong(prefix + id, index.toLong()) }
            val legacy = channelKey(profileKey, categoryId)
            check(editor.remove(legacy).remove("$legacy|order").commit())
        }
        owner?.let { com.nikhil.niktv.data.AccountSyncManager.get(context).requestProfileSync(it.accountId) }
    }

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
    fun categoryOrder(type: CatalogType): List<String> = IptvPinPreferences.categoryOrder(values, profileKey, type)
    fun tmdbOrder(surface: DashboardSurface): List<String> = IptvPinPreferences.tmdbOrder(values, profileKey, surface)
    fun pinnedOverviewOrder(): List<String> = IptvPinPreferences.pinnedOverviewOrder(values, profileKey)
}
