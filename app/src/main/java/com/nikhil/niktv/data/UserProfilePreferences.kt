package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.room.withTransaction
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArraySet

/** Compatibility bridge for the existing synchronous preference controls. Each edit commits
 * the local value and per-key outbox records together before returning to its caller. */
internal object UserProfilePreferences {
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instances = mutableMapOf<String, SharedPreferences>()
    private val portableDomains = setOf("iptv_pin_preferences", "browse_collections", "player_media_browser",
        "sports_browse", "sports_availability_v1", "iptv_channel_availability_v1", "sports_favorites_v2",
        "home2_presentation", "discovery_trial_v1", "watch_providers", "discovery_experience", "niktv_playback_ui", "player_intro_timings")

    fun hasLegacy(context: Context): Boolean = (portableDomains - "sports_favorites_v2" + "sports_library_v1")
        .any { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty() }

    @Synchronized fun get(context: Context, domain: String, portable: Boolean = true,
        owner: UserScope? = UserSyncStore.get(context).selectedScope.value): SharedPreferences {
        if (owner == null) return context.applicationContext.getSharedPreferences(domain, Context.MODE_PRIVATE)
        require(!portable || domain in portableDomains) { "This preference domain is not portable." }
        val instanceKey = "${context.applicationContext.filesDir}|$owner|$domain|$portable"
        return instances.getOrPut(instanceKey) { Scoped(context.applicationContext, domain, owner, portable) }
    }

    private fun encoded(value: Any): JsonObject = buildJsonObject {
        val parsed = if (value is String) runCatching { Json.parseToJsonElement(value) }.getOrNull() else null
        val type = when (value) {
            is String -> if (parsed is JsonObject || parsed is JsonArray) "json" else "string"
            is Int -> "int"; is Long -> "long"; is Float -> "float"; is Boolean -> "boolean"; is Set<*> -> "strings"
            else -> error("Unsupported preference type")
        }
        put("type", type)
        put("value", when (value) {
            is String -> if (type == "json") parsed!! else JsonPrimitive(value)
            is Int -> JsonPrimitive(value); is Long -> JsonPrimitive(value); is Float -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value); is Set<*> -> JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
            else -> error("Unsupported preference type")
        })
    }

    /** Both preference maps and their migration marker commit together; interruptions retry safely. */
    suspend fun importLegacy(context: Context, owner: UserScope,
        migrationId: String = "legacy-browsing-import", overrideExisting: Boolean = false) {
        val users = UserSyncStore.get(context)
        val portalKeys = ProfileStore(context).scoped(owner).profiles.first().map { it.cacheKey() }
        val portable = linkedMapOf<String, JsonElement>()
        val local = linkedMapOf<String, JsonElement>()
        for (domain in portableDomains - "sports_favorites_v2") {
            for ((legacyKey, value) in context.getSharedPreferences(domain, Context.MODE_PRIVATE).all) {
                if (value == null) continue
                val key = if (domain == "iptv_channel_availability_v1") {
                    portalKeys.firstOrNull { portal -> legacyKey.startsWith(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(portal.toByteArray()).joinToString("") { "%02x".format(it) }) }
                        ?.let { portal -> portal + ":" + legacyKey.substringAfter(':', "") } ?: legacyKey
                } else legacyKey
                portable["$domain|$key"] = encoded(value)
            }
        }
        val legacySports = context.getSharedPreferences("sports_library_v1", Context.MODE_PRIVATE)
        listOf("favourites", "playlists").forEach { key ->
            legacySports.getString(key, null)?.let { local["sports_library_v1|$key"] = encoded(it) }
        }
        val json = Json { ignoreUnknownKeys = true }
        val favorites = runCatching { json.decodeFromString<List<com.nikhil.niktv.sports.SportsChannel>>(legacySports.getString("favourites", "[]")!!) }.getOrDefault(emptyList())
        favorites.filter { it.playlist == null && it.direct == null }.forEach { channel ->
            portable["sports_favorites_v2|favorite|${channel.key}"] = encoded(json.encodeToString(channel.copy(image = "", formats = emptyList(), direct = null, playlist = null)))
        }
        users.withEditLock { users.db.withTransaction {
            if (users.dao.records(owner.accountId).any { it.collection == "account" && it.recordId == migrationId }) return@withTransaction
            val rows = users.dao.preferences(owner.accountId, owner.profileId)
            val before = mutablePreferencesOf()
            val after = mutablePreferencesOf()
            for ((name, values) in listOf("portable_preferences" to portable, "local_user_preferences" to local)) {
                val key = stringPreferencesKey(name)
                val current = rows.firstOrNull { it.name == name }?.value
                val old = current?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty()
                if (current != null) before[key] = current
                // New profile choices made before explicit assignment take precedence over legacy defaults.
                after[key] = JsonObject(if (overrideExisting) old + values else values + old).toString()
            }
            users.persist(owner, before, after)
            users.mutate(UserScope(owner.accountId, ""), "account", migrationId, "{\"imported\":true}")
        } }
    }

    private class Scoped(context: Context, private val domain: String, private val owner: UserScope,
        portable: Boolean) : SharedPreferences {
        private val users = UserSyncStore.get(context)
        private val name = if (portable) "portable_preferences" else "local_user_preferences"
        private val key = stringPreferencesKey(name)
        private val prefix = "$domain|"
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
        private val handler = Handler(Looper.getMainLooper())
        init {
            observers.launch {
                var previous: Map<String, JsonElement> = emptyMap()
                users.dao.observePreferences(owner.accountId, owner.profileId).map { rows ->
                    decode(rows.firstOrNull { it.name == name }?.value).filterKeys { it.startsWith(prefix) }
                }.distinctUntilChanged().collect { current ->
                    val changed = (previous.keys + current.keys).filter { previous[it] != current[it] }
                    previous = current
                    if (changed.isNotEmpty()) handler.post {
                        changed.forEach { changedKey -> listeners.forEach { it.onSharedPreferenceChanged(this@Scoped, changedKey.removePrefix(prefix)) } }
                    }
                }
            }
        }
        private fun decode(raw: String?): Map<String, JsonElement> = raw?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject.toMap() }.getOrNull()
        }.orEmpty()
        private suspend fun values(): Map<String, JsonElement> = decode(users.dao.preferences(owner.accountId, owner.profileId).firstOrNull { it.name == name }?.value)
        private fun read(key: String): JsonObject? = runBlocking(Dispatchers.IO) { values()[prefix + key] as? JsonObject }
        private fun storedValue(value: JsonObject?): Any? {
            if (value == null) return null
            val entry = value["value"] ?: return null
            return when (value["type"]?.jsonPrimitive?.content) {
                "string" -> entry.jsonPrimitive.content
                "json" -> entry.toString()
                "int" -> entry.jsonPrimitive.int
                "long" -> entry.jsonPrimitive.long
                "float" -> entry.jsonPrimitive.float
                "boolean" -> entry.jsonPrimitive.boolean
                "strings" -> entry.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> null
            }
        }
        override fun getAll(): MutableMap<String, *> = runBlocking(Dispatchers.IO) {
            values().filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                .mapValues { storedValue(it.value as? JsonObject) }.toMutableMap()
        }
        override fun contains(key: String): Boolean = read(key) != null
        override fun getString(key: String, defValue: String?): String? = storedValue(read(key)) as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (storedValue(read(key)) as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = storedValue(read(key)) as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = storedValue(read(key)) as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = storedValue(read(key)) as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = storedValue(read(key)) as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }
        override fun edit(): SharedPreferences.Editor = Editor()
        private inner class Editor : SharedPreferences.Editor {
            private var clearing = false
            private val changes = linkedMapOf<String, JsonObject?>()
            private fun put(key: String, type: String, value: JsonElement): SharedPreferences.Editor = apply {
                changes[key] = buildJsonObject { put("type", type); put("value", value) }
            }
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value == null) return remove(key)
                val jsonValue = runCatching { Json.parseToJsonElement(value) }.getOrNull()
                return if (jsonValue is JsonObject || jsonValue is JsonArray) put(key, "json", jsonValue)
                    else put(key, "string", JsonPrimitive(value))
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                if (values == null) remove(key) else put(key, "strings", JsonArray(values.sorted().map(::JsonPrimitive)))
            override fun putInt(key: String, value: Int) = put(key, "int", JsonPrimitive(value))
            override fun putLong(key: String, value: Long) = put(key, "long", JsonPrimitive(value))
            override fun putFloat(key: String, value: Float) = put(key, "float", JsonPrimitive(value))
            override fun putBoolean(key: String, value: Boolean) = put(key, "boolean", JsonPrimitive(value))
            override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearing = true }
            override fun apply() { check(commit()) { "Could not save user preferences." } }
            override fun commit(): Boolean = runBlocking(Dispatchers.IO) {
                users.withEditLock {
                    val current = values()
                    val next = current.toMutableMap()
                    if (clearing) next.keys.filter { it.startsWith(prefix) }.forEach(next::remove)
                    changes.forEach { (changedKey, value) ->
                        if (value == null) next.remove(prefix + changedKey) else next[prefix + changedKey] = value
                    }
                    val before = mutablePreferencesOf(key to JsonObject(current).toString())
                    val after = mutablePreferencesOf(key to JsonObject(next).toString())
                    users.persist(owner, before, after)
                    true
                }
            }
        }
    }
}
