package com.nikhil.niktv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.nikhil.niktv.model.PortalProfile
import java.util.UUID

@Serializable enum class UserProfileType { GENERAL, KIDS }
@Serializable data class UserProfile(
    val id: String,
    val name: String,
    val color: Long = 0xFFE50914,
    val avatar: String = "",
    val type: UserProfileType = UserProfileType.GENERAL,
)
@Serializable data class UserScope(val accountId: String, val profileId: String)

/** The wire record has no authentication or device configuration. Lamport revisions, then device
 * and mutation IDs, provide a total ordering even when devices disagree about wall clock time. */
@Serializable
@Entity(tableName = "sync_records", primaryKeys = ["accountId", "profileId", "collection", "recordId"])
data class SyncRecord(
    val accountId: String,
    val profileId: String,
    val collection: String,
    val recordId: String,
    val payload: String,
    val revision: Long,
    val deviceId: String,
    val mutationId: String,
    val deleted: Boolean = false,
    val generation: Long = 0,
)

internal fun SyncRecord.newerThan(other: SyncRecord): Boolean =
    compareValuesBy(this, other, { it.generation }, { it.revision }, { it.deviceId }, { it.mutationId }) > 0

@Entity(tableName = "sync_outbox")
data class SyncOutbox(@PrimaryKey val mutationId: String, val accountId: String, val record: String)
@Entity(tableName = "user_local_preferences", primaryKeys = ["accountId", "profileId", "name"])
@Serializable
data class UserLocalPreference(val accountId: String, val profileId: String, val name: String, val value: String)
@Entity(tableName = "portal_mappings", primaryKeys = ["accountId", "localKey"])
@Serializable
data class LocalPortalMapping(val accountId: String, val localKey: String, val portalId: String)

@Dao
interface UserSyncDao {
    @Query("SELECT * FROM sync_records WHERE accountId = :accountId") suspend fun records(accountId: String): List<SyncRecord>
    @Query("SELECT * FROM sync_records WHERE accountId = :accountId") fun observeRecords(accountId: String): Flow<List<SyncRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: SyncRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(entry: SyncOutbox)
    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId") suspend fun pending(accountId: String): List<SyncOutbox>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId = :accountId") fun observePendingCount(accountId: String): Flow<Int>
    @Query("DELETE FROM sync_outbox WHERE accountId = :accountId AND mutationId IN (:ids)") suspend fun acknowledge(accountId: String, ids: List<String>)
    @Query("SELECT * FROM user_local_preferences WHERE accountId=:accountId AND profileId=:profileId") suspend fun preferences(accountId: String, profileId: String): List<UserLocalPreference>
    @Query("SELECT * FROM user_local_preferences WHERE accountId=:accountId") suspend fun allPreferences(accountId: String): List<UserLocalPreference>
    @Query("SELECT * FROM user_local_preferences WHERE accountId=:accountId AND profileId=:profileId") fun observePreferences(accountId: String, profileId: String): Flow<List<UserLocalPreference>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreference(value: UserLocalPreference)
    @Query("DELETE FROM user_local_preferences WHERE accountId=:accountId AND profileId=:profileId AND name=:name") suspend fun deletePreference(accountId: String, profileId: String, name: String)
    @Query("SELECT * FROM portal_mappings WHERE accountId=:accountId") suspend fun mappings(accountId: String): List<LocalPortalMapping>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMapping(value: LocalPortalMapping)
}

@Database(entities = [SyncRecord::class, SyncOutbox::class, UserLocalPreference::class, LocalPortalMapping::class], version = 1, exportSchema = true)
abstract class UserSyncDatabase : RoomDatabase() { abstract fun syncDao(): UserSyncDao }

/** Account data and the durable mutation queue share a Room transaction. Device-owned portal
 * credentials/sessions and caches remain in their existing local stores. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserSyncStore private constructor(private val context: Context) {
    /** Acquire, run and release on IO. A synchronous preferences caller on the UI thread
     * must never wait for a lock whose holder needs a UI continuation to release it. */
    internal suspend fun <T> withEditLock(action: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            editMutex.withLock { action() }
        }

    internal val db = Room.databaseBuilder(context, UserSyncDatabase::class.java, "niktv-users.db").build()
    internal val dao = db.syncDao()
    internal val editMutex = Mutex()
    private val local = context.getSharedPreferences("niktv_user_selection", Context.MODE_PRIVATE)
    private val deviceId: String = local.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        local.edit().putString("device_id", it).commit()
    }
    private val account = MutableStateFlow(local.getString("account", null))
    private val scope = MutableStateFlow(local.getString("account", null)?.let { accountId ->
        local.getString("profile_$accountId", null)?.let { UserScope(accountId, it) }
    })
    val selectedScope: StateFlow<UserScope?> = scope.asStateFlow()
    val selectedAccount: StateFlow<String?> = account.asStateFlow()
    val pendingChanges: Flow<Int> = account.flatMapLatest { id -> if (id == null) flowOf(0) else dao.observePendingCount(id) }.distinctUntilChanged()
    val profiles: Flow<List<UserProfile>> = account.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeRecords(id).map { records ->
            records.filter { it.collection == "profile" && !it.deleted }
                .mapNotNull { runCatching { json.decodeFromString<UserProfile>(it.payload) }.getOrNull() }
                .sortedWith(compareBy<UserProfile> { if (it.type == UserProfileType.GENERAL) 0 else 1 }.thenBy { it.name })
        }
    }.distinctUntilChanged()

    suspend fun selectAccount(accountId: String) {
        require(accountId.toLongOrNull() != null) { "A numeric GitHub account ID is required." }
        if (account.value == accountId) return
        local.edit().putString("account", accountId).commit()
        account.value = accountId
        // Always land on the chooser; the previous device selection is not synced.
        scope.value = null
    }
    suspend fun selectProfile(profileId: String) {
        val accountId = requireNotNull(account.value) { "Connect an account first." }
        require(dao.records(accountId).any { it.collection == "profile" && it.recordId == profileId && !it.deleted }) { "This profile no longer exists. Sync and select another profile." }
        local.edit().putString("profile_$accountId", profileId).commit()
        scope.value = UserScope(accountId, profileId)
    }
    fun clearSelection() { scope.value = null }
    fun disconnect() { scope.value = null; account.value = null; local.edit().remove("account").commit() }

    /** Deterministic IDs and a retained account marker make simultaneous empty setup idempotent.
     * Profile tombstones count as existing accounts, so deleted defaults are never recreated. */
    suspend fun initializeDefaults(accountId: String) = db.withTransaction {
        val records = dao.records(accountId)
        if (records.any { it.collection == "profile" || it.collection == "account" && it.recordId == DEFAULTS_MARKER }) return@withTransaction
        val family = UserProfile(defaultProfileId(accountId, UserProfileType.GENERAL), "Family", 0xFFE50914, "F", UserProfileType.GENERAL)
        val kids = UserProfile(defaultProfileId(accountId, UserProfileType.KIDS), "Kids", 0xFF27AE60, "K", UserProfileType.KIDS)
        listOf(family, kids).forEach { mutate(UserScope(accountId, it.id), "profile", it.id, json.encodeToString(it)) }
        mutate(UserScope(accountId, ""), "account", DEFAULTS_MARKER, "{\"initialized\":true}")
    }
    suspend fun createProfile(name: String, type: UserProfileType = UserProfileType.GENERAL, color: Long = 0xFF3F51B5, avatar: String = ""): UserProfile {
        val profile = UserProfile(UUID.randomUUID().toString(), name.trim().also { require(it.isNotEmpty()) }, color, avatar, type)
        updateProfile(profile)
        return profile
    }
    suspend fun updateProfile(profile: UserProfile) {
        require(profile.name.isNotBlank()) { "Enter a profile name." }
        UUID.fromString(profile.id)
        val accountId = requireNotNull(account.value)
        db.withTransaction {
            require(dao.records(accountId).none { it.collection == "profile" && it.recordId == profile.id && it.deleted }) { "This profile was deleted on another device. Create a new profile to continue." }
            mutate(UserScope(accountId, profile.id), "profile", profile.id, json.encodeToString(profile.copy(name = profile.name.trim())))
        }
    }
    suspend fun deleteProfile(profileId: String) {
        val accountId = requireNotNull(account.value)
        db.withTransaction {
            dao.records(accountId).filter { it.profileId == profileId && !it.deleted }.forEach {
                mutate(UserScope(accountId, profileId), it.collection, it.recordId, it.payload, deleted = true)
            }
        }
        if (scope.value?.profileId == profileId) clearSelection()
    }
    suspend fun snapshot(accountId: String): List<SyncRecord> = dao.records(accountId)
    suspend fun backupLocalPreferences(accountId: String): List<UserLocalPreference> = dao.allPreferences(accountId).filter { it.name != "offline_media_downloads" }
    suspend fun backupPortalMappings(accountId: String): List<LocalPortalMapping> = dao.mappings(accountId)
    suspend fun restoreLocalBackup(accountId: String, preferences: List<UserLocalPreference>, mappings: List<LocalPortalMapping>) = withEditLock { db.withTransaction {
        require(preferences.all { it.accountId == accountId } && mappings.all { it.accountId == accountId }) { "This backup belongs to another account." }
        mappings.forEach { UUID.fromString(it.portalId); dao.putMapping(it) }
        preferences.filter { it.name in SCOPED_KEYS && it.name != "offline_media_downloads" }.forEach { dao.putPreference(it) }
    } }
    suspend fun restoreBackupRecords(accountId: String, records: List<SyncRecord>) = withEditLock { db.withTransaction {
        require(records.all { it.accountId == accountId }) { "This backup belongs to another account." }
        records.forEach { item -> mutate(UserScope(accountId, item.profileId), item.collection, item.recordId, item.payload, item.deleted) }
        records.map { it.profileId }.distinct().filter { it.isNotBlank() }.forEach { rebuildPreferences(UserScope(accountId, it), dao.records(accountId)) }
    } }
    suspend fun pending(accountId: String): List<SyncRecord> = dao.pending(accountId).map { json.decodeFromString(it.record) }
    suspend fun acknowledge(accountId: String, mutationIds: Collection<String>) {
        mutationIds.chunked(400).forEach { dao.acknowledge(accountId, it) }
    }
    suspend fun merge(accountId: String, records: List<SyncRecord>) = withEditLock { db.withTransaction {
        require(records.all { it.accountId == accountId }) { "Remote data belongs to another account." }
        val current = dao.records(accountId).associateBy { Triple(it.profileId, it.collection, it.recordId) }.toMutableMap()
        val affected = mutableSetOf<UserScope>()
        records.forEach { remote ->
            require(remote.revision >= 0 && remote.generation >= 0 && remote.payload.length <= 1_000_000) { "Invalid sync record." }
            val key = Triple(remote.profileId, remote.collection, remote.recordId)
            val old = current[key]
            if (old == null || remote.newerThan(old)) {
                dao.put(remote); current[key] = remote
                affected += UserScope(accountId, remote.profileId)
            }
        }
        // A validated remote winner proves older queued mutations for that same record are
        // superseded. Do not discard unrelated mutations or anything newer than the download.
        val remoteWinners = records.associateBy { Triple(it.profileId, it.collection, it.recordId) }
        val superseded = dao.pending(accountId).mapNotNull { queued ->
            val pending = json.decodeFromString<SyncRecord>(queued.record)
            val downloaded = remoteWinners[Triple(pending.profileId, pending.collection, pending.recordId)]
            if (downloaded != null && (downloaded.mutationId == pending.mutationId || downloaded.newerThan(pending))) queued.mutationId else null
        }
        superseded.chunked(400).forEach { dao.acknowledge(accountId, it) }
        affected.filter { it.profileId.isNotBlank() }.forEach { rebuildPreferences(it, current.values.toList()) }
    } }

    internal suspend fun mutate(owner: UserScope, collection: String, recordId: String, payload: String, deleted: Boolean = false) {
        val records = dao.records(owner.accountId)
        val revision = (records.maxOfOrNull { it.revision } ?: 0L) + 1L
        val prior = records.firstOrNull { it.profileId == owner.profileId && it.collection == collection && it.recordId == recordId }
        val generation = (prior?.generation ?: 0L) + if (prior != null && prior.deleted != deleted) 1L else 0L
        val record = SyncRecord(owner.accountId, owner.profileId, collection, recordId, payload, revision, deviceId, UUID.randomUUID().toString(), deleted, generation)
        dao.put(record)
        dao.enqueue(SyncOutbox(record.mutationId, owner.accountId, json.encodeToString(record)))
    }

    suspend fun registerPortal(accountId: String, profile: PortalProfile): String = withEditLock { db.withTransaction {
        val key = profile.cacheKey()
        dao.mappings(accountId).firstOrNull { it.localKey == key }?.portalId ?: run {
            // Only built-in remotely provisioned slots have a universal identity. Editable names,
            // usernames, MACs and URLs never determine a shared portal identity.
            val configured = when (profile.portalType.name) {
                "STALKER" -> RemoteCredentials.get("NIKTV_DEFAULT_PORTAL_URL").ifBlank { RemoteCredentials.get("NIKTV_DEFAULT_PORTAL") }
                else -> RemoteCredentials.get("NIKTV_XTREAM_PORTAL_URL")
            }
            val builtin = configured.isNotBlank() && profile.portalUrl.trimEnd('/') == configured.trimEnd('/')
            val id = if (builtin) stableUuid("niktv:$accountId:portal:${profile.portalType}") else UUID.randomUUID().toString()
            dao.putMapping(LocalPortalMapping(accountId, key, id))
            val records = dao.records(accountId)
            records.filter { it.profileId.isNotBlank() && (it.payload.contains("portal:$id") || it.recordId.contains("portal:$id")) }
                .map { it.profileId }.distinct().forEach { rebuildPreferences(UserScope(accountId, it), records) }
            id
        }
    } }
    suspend fun associatePortal(owner: UserScope, profile: PortalProfile) {
        val portalId = registerPortal(owner.accountId, profile)
        db.withTransaction {
            if (dao.records(owner.accountId).any { it.profileId == owner.profileId && it.collection == "portals" && it.recordId == portalId && !it.deleted }) return@withTransaction
            mutate(owner, "portals", portalId, buildJsonObject {
                put("id", portalId); put("type", profile.portalType.name); put("name", profile.portalType.name.lowercase().replaceFirstChar { it.uppercase() })
                put("configuration", "device-private-configuration")
            }.toString())
        }
    }
    suspend fun removePortal(owner: UserScope, profile: PortalProfile) {
        val id = registerPortal(owner.accountId, profile)
        db.withTransaction { mutate(owner, "portals", id, "{}", true) }
    }
    fun portalAssociations(owner: UserScope): Flow<Set<String>> = dao.observeRecords(owner.accountId).map { records ->
        records.filter { it.profileId == owner.profileId && it.collection == "portals" && !it.deleted }.map { it.recordId }.toSet()
    }.distinctUntilChanged()
    suspend fun hasPortalAssociation(owner: UserScope, profile: PortalProfile): Boolean {
        val id = registerPortal(owner.accountId, profile)
        return dao.records(owner.accountId).any { it.profileId == owner.profileId && it.collection == "portals" && it.recordId == id && !it.deleted }
    }
    suspend fun mapPortal(owner: UserScope, portalId: String, profile: PortalProfile) {
        UUID.fromString(portalId)
        withEditLock { db.withTransaction {
            dao.putMapping(LocalPortalMapping(owner.accountId, profile.cacheKey(), portalId))
            rebuildPreferences(owner, dao.records(owner.accountId))
        } }
    }
    internal suspend fun project(base: Preferences, owner: UserScope): Preferences = project(accountPreferences(base, owner.accountId), dao.preferences(owner.accountId, owner.profileId))
    internal fun accountPreferences(base: Preferences, accountId: String): Preferences = base.toMutablePreferences().apply {
        ACCOUNT_KEYS.forEach { name ->
            remove(stringPreferencesKey(name))
            base[stringPreferencesKey("account_${accountId}_$name")]?.let { this[stringPreferencesKey(name)] = it }
        }
    }.toPreferences()
    internal fun project(base: Preferences, values: List<UserLocalPreference>): Preferences = base.toMutablePreferences().apply {
        SCOPED_KEYS.forEach { remove(stringPreferencesKey(it)) }
        values.forEach { this[stringPreferencesKey(it.name)] = it.value }
    }.toPreferences()

    /** A transformed local snapshot and every record-level outbox change commit atomically. */
    internal suspend fun persist(owner: UserScope, before: Preferences, after: Preferences) = db.withTransaction {
        // Late playback callbacks can still hold a removed profile's immutable scope. Keep the
        // deletion authoritative and discard those writes instead of resurrecting child rows.
        if (dao.records(owner.accountId).any { it.collection == "profile" && it.recordId == owner.profileId && it.deleted }) return@withTransaction
        val maps = dao.mappings(owner.accountId)
        SCOPED_KEYS.forEach { name ->
            val key = stringPreferencesKey(name)
            if (before[key] == after[key]) return@forEach
            val value = after[key]
            if (value == null) dao.deletePreference(owner.accountId, owner.profileId, name)
            else dao.putPreference(UserLocalPreference(owner.accountId, owner.profileId, name, value))
            if (name in LOCAL_ONLY_KEYS) return@forEach
            val oldEntries = entries(name, before[key], maps)
            val newEntries = entries(name, value, maps)
            val collection = collection(name)
            (oldEntries.keys + newEntries.keys).forEach { id ->
                if (oldEntries[id] != newEntries[id]) {
                    mutate(owner, collection, id, newEntries[id] ?: oldEntries.getValue(id), deleted = id !in newEntries)
                }
            }
        }
    }

    internal suspend fun importPreferences(owner: UserScope, source: Preferences, migrationId: String) = withEditLock { db.withTransaction {
        if (dao.records(owner.accountId).any { it.collection == "account" && it.recordId == migrationId }) return@withTransaction
        val before = project(source, emptyList())
        val after = before.toMutablePreferences().apply {
            SCOPED_KEYS.forEach { name -> source[stringPreferencesKey(name)]?.let { this[stringPreferencesKey(name)] = it } }
        }
        persist(owner, before, after)
        mutate(UserScope(owner.accountId, ""), "account", migrationId, "{\"imported\":true}")
    } }

    private suspend fun rebuildPreferences(owner: UserScope, records: List<SyncRecord>) {
        val maps = dao.mappings(owner.accountId)
        val localPreferences = dao.preferences(owner.accountId, owner.profileId).associate { it.name to it.value }
        val grouped = records.filter { it.profileId == owner.profileId && !it.deleted && it.collection !in setOf("profile", "portals", "account") }
            .mapNotNull { runCatching { json.parseToJsonElement(it.payload).jsonObject }.getOrNull() }
            .groupBy { it["preference"]?.jsonPrimitive?.content.orEmpty() }
        SCOPED_KEYS.filterNot { it in LOCAL_ONLY_KEYS }.forEach { name ->
            val values = grouped[name].orEmpty()
            val value = when {
                name in LIST_KEYS -> {
                    val previous = runCatching { json.parseToJsonElement(localPreferences[name].orEmpty()).jsonArray }.getOrNull().orEmpty()
                    fun identity(item: JsonElement): String? = entries(name, JsonArray(listOf(item)).toString(), maps).keys.singleOrNull()
                    val indexed = previous.mapNotNull { item -> identity(item)?.let { it to item } }.toMap()
                    val restored = values.mapNotNull { value -> restoreLocal(value["entry"] ?: JsonNull, maps)?.let { item -> mergeLocalMediaMetadata(item, indexed[identity(item)]) } }
                    // Legacy v2/title keys and still-unmapped portal records stay on this device.
                    // They are never used as shared identity or discarded by unrelated sync.
                    val deviceOnly = previous.filter { identity(it) == null }
                    if (restored.isEmpty() && deviceOnly.isEmpty()) null else JsonArray(restored + deviceOnly).toString()
                }
                values.isEmpty() -> null
                name in MAP_KEYS -> JsonObject(values.associate { restoreLocalText(it["mapKey"]!!.jsonPrimitive.content, maps) to (restoreLocal(it["entry"] ?: JsonNull, maps) ?: JsonNull) }).toString()
                else -> values.first()["entry"]?.jsonPrimitive?.content
            }
            if (value == null) dao.deletePreference(owner.accountId, owner.profileId, name)
            else dao.putPreference(UserLocalPreference(owner.accountId, owner.profileId, name, value))
        }
    }

    private fun entries(name: String, raw: String?, maps: List<LocalPortalMapping>): Map<String, String> {
        if (raw == null) return emptyMap()
        val value = runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
        fun wrap(entry: JsonElement, mapKey: String? = null): String = buildJsonObject {
            put("preference", name)
            if (mapKey != null) put("mapKey", portableText(mapKey, maps))
            put("entry", portable(entry, maps))
        }.toString()
        return when {
            name in LIST_KEYS && value is JsonArray -> value.associate { item ->
                val obj = item as? JsonObject
                val localKey = when {
                    obj == null -> item.toString()
                    obj["key"] != null -> obj.getValue("key").jsonPrimitive.content
                    obj["media"] != null -> buildString {
                        val media = obj.getValue("media").jsonObject
                        append(obj["profileKey"]?.jsonPrimitive?.content.orEmpty()); append('|')
                        append(obj["kind"]?.jsonPrimitive?.content.orEmpty()); append('|')
                        append(media["id"]?.jsonPrimitive?.content.orEmpty())
                        if (obj["kind"]?.jsonPrimitive?.content == "EPISODE") {
                            append("|series:"); append((obj["series"] as? JsonObject)?.get("id")?.jsonPrimitive?.content.orEmpty())
                            append("|s:"); append(media["seasonNumber"])
                            append("|e:"); append(media["episodeNumber"])
                        }
                    }
                    obj["series"] != null -> "${obj["profileKey"]?.jsonPrimitive?.content.orEmpty()}|${obj["series"]?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()}"
                    else -> item.toString()
                }
                "$name:${portableText(localKey, maps)}" to wrap(item)
            }.filter { (id, payload) ->
                val unscopedLegacyProgress = name in setOf("playback_progress", "recommendation_progress_v1") &&
                    com.nikhil.niktv.model.CatalogType.entries.any { id.startsWith("$name:${it.name}:") }
                !unscopedLegacyProgress && !id.contains("progress-v2|") && !payload.contains("unmapped:local-only")
            }
            name in MAP_KEYS && value is JsonObject -> value.map { (id, item) -> "$name:${portableText(id, maps)}" to wrap(item, id) }.toMap().filterValues { !it.contains("unmapped:local-only") }
            else -> mapOf(name to wrap(JsonPrimitive(raw)))
        }
    }

    private fun portableText(value: String, maps: List<LocalPortalMapping>): String {
        var result = value
        maps.sortedByDescending { it.localKey.length }.forEach { result = result.replace(it.localKey, "portal:${it.portalId}") }
        // Unmapped credential-bearing identities remain device-only rather than being published.
        return if (result.contains("catalog-v5|") || result.contains("://") || Regex("(?i)(token=|password=|mac=)").containsMatchIn(result)) "unmapped:local-only" else result
    }
    private fun portable(value: JsonElement, maps: List<LocalPortalMapping>): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { portable(it, maps) })
        is JsonObject -> JsonObject(value.filterKeys { it !in SECRET_FIELDS }.mapValues { (name, item) ->
            if (name in IDENTITY_FIELDS && item is JsonPrimitive && item.isString) JsonPrimitive(portableText(item.content, maps))
            else if (name in URL_FIELDS) JsonNull else portable(item, maps)
        })
        is JsonPrimitive -> if (value.isString && (value.content.contains("://") || Regex("(?i)(token=|password=|mac=)").containsMatchIn(value.content))) JsonNull else value
        else -> value
    }
    private fun restoreLocalText(value: String, maps: List<LocalPortalMapping>): String {
        var result = value
        maps.forEach { result = result.replace("portal:${it.portalId}", it.localKey) }
        return result
    }
    private fun restoreLocal(value: JsonElement, maps: List<LocalPortalMapping>): JsonElement? = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (name, item) ->
            if (name in IDENTITY_FIELDS && item is JsonPrimitive && item.isString) JsonPrimitive(restoreLocalText(item.content, maps))
            else restoreLocal(item, maps) ?: JsonNull
        })
        is JsonArray -> JsonArray(value.mapNotNull { restoreLocal(it, maps) })
        else -> value
    }
    private fun mergeLocalMediaMetadata(remote: JsonElement, local: JsonElement?): JsonElement = when {
        remote is JsonObject && local is JsonObject -> JsonObject((remote.keys + local.keys.filter { it in SECRET_FIELDS }).associateWith { name ->
            val incoming = remote[name]
            val cached = local[name]
            when {
                incoming == null -> cached ?: JsonNull
                name in URL_FIELDS && incoming == JsonNull -> cached ?: incoming
                else -> mergeLocalMediaMetadata(incoming, cached)
            }
        })
        else -> remote
    }
    private fun collection(name: String): String = when (name) {
        "favorites" -> "favorites"; "recently_played" -> "history"; "playback_progress" -> "progress"; "watched_series" -> "series"; else -> "preferences"
    }

    companion object {
        private val instances = mutableMapOf<String, UserSyncStore>()
        @Synchronized fun get(context: Context): UserSyncStore = instances.getOrPut(context.applicationContext.filesDir.absolutePath) { UserSyncStore(context.applicationContext) }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val DEFAULTS_MARKER = "defaults-initialized"
        fun defaultProfileId(accountId: String, type: UserProfileType): String = stableUuid("niktv:$accountId:default:$type")
        private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
        internal val LIST_KEYS = setOf("favorites", "recently_played", "playback_progress", "watched_series", "recent_searches", "requested_recommendations_v1", "recommendation_progress_v1")
        internal val MAP_KEYS = setOf("category_filters", "remembered_series_seasons", "browse_layouts", "tmdb_dashboard_sections", "dismissed_recommendations_v1", "portable_preferences")
        internal val LOCAL_ONLY_KEYS = setOf("offline_media_downloads", "local_user_preferences")
        internal val SCOPED_KEYS = LIST_KEYS + MAP_KEYS + LOCAL_ONLY_KEYS + "series_start_season"
        internal val ACCOUNT_KEYS = setOf("saved_profiles", "saved_sessions", "active_profile", "active_session", "active_profile_identity", "playback_urls", "tmdb_iptv_mappings", "paged_search_results", "episode_season_caches")
        private val SECRET_FIELDS = setOf("password", "token", "macAddress", "serialNumber", "username", "portalUrl", "sourceUrl", "url", "endpointUrl", "streamRequestHeaders", "streamDrm", "alternateTarget")
        private val URL_FIELDS = setOf("command", "logo", "licenseUri")
        private val IDENTITY_FIELDS = setOf("profileKey", "key")
    }
}

/** Projects the selected user into the old typed DataStore API. Existing callers retain their
 * serialized names; viewing mutations are redirected into transactional Room records/outbox. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class UserScopedPreferences(
    private val base: DataStore<Preferences>,
    private val users: UserSyncStore,
    private val fixedScope: UserScope? = null,
) : DataStore<Preferences> {
    private fun owner(): UserScope? = fixedScope ?: users.selectedScope.value
    override val data: Flow<Preferences> = if (fixedScope != null) {
        combine(base.data, users.dao.observePreferences(fixedScope.accountId, fixedScope.profileId)) { raw, scoped -> users.project(users.accountPreferences(raw, fixedScope.accountId), scoped) }
    } else combine(users.selectedScope, users.selectedAccount) { selected, account -> selected to account }.flatMapLatest { (selected, account) ->
        when {
            selected != null -> combine(base.data, users.dao.observePreferences(selected.accountId, selected.profileId)) { raw, scoped -> users.project(users.accountPreferences(raw, selected.accountId), scoped) }
            account != null -> base.data.map { raw -> users.project(users.accountPreferences(raw, account), emptyList()) }
            else -> base.data
        }
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val captured = owner()
        val capturedAccount = captured?.accountId ?: users.selectedAccount.value
        if (capturedAccount == null) return base.updateData(transform)
        return users.withEditLock {
            val raw = base.data.first()
            val before = if (captured == null) users.project(users.accountPreferences(raw, capturedAccount), emptyList()) else users.project(raw, captured)
            val after = transform(before)
            if (captured != null) users.persist(captured, before, after)
            base.updateData { latest -> latest.toMutablePreferences().apply {
                (before.asMap().keys + after.asMap().keys).filter { it.name !in UserSyncStore.SCOPED_KEYS && before.asMap()[it] != after.asMap()[it] }.forEach { key ->
                    val value = after.asMap()[key]
                    val target = if (key.name in UserSyncStore.ACCOUNT_KEYS) "account_${capturedAccount}_${key.name}" else key.name
                    if (value == null) remove(stringPreferencesKey(target))
                    else when (value) { is String -> this[stringPreferencesKey(target)] = value; is Int -> this[intPreferencesKey(target)] = value }
                }
            }.toPreferences() }
            after
        }
    }
}
