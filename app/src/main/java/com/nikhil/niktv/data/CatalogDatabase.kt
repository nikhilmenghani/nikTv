package com.nikhil.niktv.data

import android.content.Context
import androidx.room.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One row per item in a provider page collection. Payload keeps provider-specific fields lossless. */
@Entity(primaryKeys = ["profile", "type", "bucket", "id"], indices = [Index(value = ["profile", "type", "id"])])
@Serializable
data class CatalogItemRow(
    val profile: String, val type: String, val bucket: String, val id: String,
    val position: Int, val payload: String, val observedAt: Long, val deleted: Boolean = false
)

@Entity(primaryKeys = ["profile", "type", "bucket"])
@Serializable
data class CatalogBucketRow(
    val profile: String, val type: String, val bucket: String, val title: String,
    val page: Int = 0, val hasMore: Boolean = true, val observedAt: Long,
    val position: Int = 0
)

@Entity(primaryKeys = ["profile", "series", "season"])
@Serializable
data class CatalogEpisodeRow(val profile: String, val series: String, val season: Int, val payload: String, val observedAt: Long)

@Entity
data class CatalogMigration(@PrimaryKey val key: String)

data class CatalogStoredCount(val type: String, val count: Int)

@Dao
interface CatalogDao {
    @Query("""SELECT a.type, COUNT(DISTINCT a.id) AS count FROM CatalogItemRow a
        WHERE a.profile = :profile AND a.deleted = 0 AND NOT EXISTS
        (SELECT 1 FROM CatalogItemRow b WHERE b.profile = a.profile AND b.type = a.type AND b.id = a.id
          AND (b.observedAt > a.observedAt OR (b.observedAt = a.observedAt AND b.deleted = 1)))
        GROUP BY a.type""")
    fun storedCounts(profile: String): Flow<List<CatalogStoredCount>>
    @Query("""SELECT a.* FROM CatalogItemRow a WHERE a.profile = :profile AND a.type = :type AND a.deleted = 0
        AND NOT EXISTS (SELECT 1 FROM CatalogItemRow b WHERE b.profile = a.profile AND b.type = a.type AND b.id = a.id
          AND (b.observedAt > a.observedAt OR (b.observedAt = a.observedAt AND b.deleted = 1)))
        GROUP BY a.id ORDER BY a.id LIMIT :limit OFFSET :offset""")
    suspend fun storedPage(profile: String, type: String, limit: Int, offset: Int): List<CatalogItemRow>

    @Query("SELECT * FROM CatalogItemRow WHERE profile = :profile AND type = :type ORDER BY position, id")
    suspend fun items(profile: String, type: String): List<CatalogItemRow>
    @Query("SELECT * FROM CatalogItemRow WHERE profile = :profile AND type = :type AND id = :id ORDER BY observedAt DESC, deleted DESC")
    suspend fun item(profile: String, type: String, id: String): List<CatalogItemRow>
    @Query("SELECT * FROM CatalogBucketRow WHERE profile = :profile AND type = :type ORDER BY position, bucket")
    suspend fun buckets(profile: String, type: String): List<CatalogBucketRow>
    @Upsert suspend fun putItems(rows: List<CatalogItemRow>)
    @Upsert suspend fun putBuckets(rows: List<CatalogBucketRow>)
    @Upsert suspend fun putEpisodes(rows: List<CatalogEpisodeRow>)
    @Query("SELECT * FROM CatalogEpisodeRow") suspend fun episodes(): List<CatalogEpisodeRow>
    @Query("SELECT * FROM CatalogEpisodeRow") fun observeEpisodes(): Flow<List<CatalogEpisodeRow>>
    @Query("SELECT COUNT(*) FROM CatalogMigration WHERE `key` = :key") suspend fun migrated(key: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markMigrated(row: CatalogMigration)
    @Query("DELETE FROM CatalogItemRow") suspend fun clearItems()
    @Query("DELETE FROM CatalogBucketRow") suspend fun clearBuckets()
    @Query("DELETE FROM CatalogEpisodeRow") suspend fun clearEpisodes()
}

@Database(entities = [CatalogItemRow::class, CatalogBucketRow::class, CatalogEpisodeRow::class, CatalogMigration::class], version = 1, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    companion object {
        @Volatile private var instance: CatalogDatabase? = null
        fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CatalogDatabase::class.java, "catalog.db")
                .build().also { instance = it }
        }
    }
}

/** Compatibility boundary: existing screens consume their models; persistence is indexed Room rows. */
class CatalogRepository(context: Context, private val db: CatalogDatabase = CatalogDatabase.get(context)) {
    private val app = context.applicationContext
    private val dao = db.catalog()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun migrate(profile: String, type: CatalogType) = db.withTransaction {
        val key = "$profile:${type.name}"
        if (dao.migrated(key) != 0) return@withTransaction
        CatalogDiskCache.read<BrowseCatalogCache>(app, "browse:$profile:${type.name}")?.let { saveBrowse(it) }
        CatalogDiskCache.read<SearchCatalogCache>(app, "search:$profile:${type.name}")?.let { saveSearch(it) }
        dao.markMigrated(CatalogMigration(key))
    }

    suspend fun browse(profile: String, type: CatalogType): BrowseCatalogCache? {
        migrate(profile, type)
        return db.withTransaction {
            val buckets = dao.buckets(profile, type.name).filterNot { it.bucket == SEARCH }
            if (buckets.isEmpty()) return@withTransaction null
            val allRows = dao.items(profile, type.name)
            val latest = allRows.sortedWith(compareByDescending<CatalogItemRow> { it.observedAt }.thenByDescending { it.deleted }).distinctBy { it.id }
            val retired = latest.filter { it.deleted }.map { it.id }.toSet()
            val rows = allRows.filterNot { it.deleted || it.id in retired }.groupBy { it.bucket }
            val allComplete = buckets.any { it.bucket == "*" && it.page > 0 && !it.hasMore }
            val derived = if (allComplete) latest.filterNot { it.deleted }.map { json.decodeFromString<MediaItem>(it.payload) }
                .groupBy { it.portalCategoryId } else emptyMap()
            BrowseCatalogCache(profile, type, buckets.maxOf { it.observedAt },
                buckets.map { Category(it.bucket, it.title, type) },
                buckets.filter { it.page > 0 || allComplete || rows[it.bucket].orEmpty().isNotEmpty() }.associate { b -> b.bucket to
                    (if (allComplete && b.bucket != "*") derived[b.bucket].orEmpty()
                     else rows[b.bucket].orEmpty().map { json.decodeFromString<MediaItem>(it.payload) }) },
                buckets.filter { it.page > 0 || allComplete || rows[it.bucket].orEmpty().isNotEmpty() }.associate { it.bucket to (if (allComplete) 1 else it.page) },
                buckets.filter { it.page > 0 || allComplete || rows[it.bucket].orEmpty().isNotEmpty() }.associate { it.bucket to (if (allComplete) false else it.hasMore) })
        }
    }

    suspend fun search(profile: String, type: CatalogType): SearchCatalogCache? {
        migrate(profile, type)
        return db.withTransaction {
            val rows = dao.items(profile, type.name)
            val buckets = dao.buckets(profile, type.name)
            if (buckets.isEmpty() && rows.isEmpty()) return@withTransaction null
            SearchCatalogCache(profile, type, buckets.maxOfOrNull { it.observedAt } ?: 0L,
                rows.sortedWith(compareByDescending<CatalogItemRow> { it.observedAt }.thenByDescending { it.deleted }).distinctBy { it.id }.filterNot { it.deleted }.map { json.decodeFromString<MediaItem>(it.payload) },
                buckets.filter { !it.hasMore && it.bucket != SEARCH }.map { it.bucket }.toSet())
        }
    }

    suspend fun media(profile: String, type: CatalogType, id: String): MediaItem? {
        migrate(profile, type)
        val rows = dao.item(profile, type.name, id)
        if (rows.firstOrNull()?.deleted == true) return null
        return rows.filterNot { it.deleted }.map { json.decodeFromString<MediaItem>(it.payload) }
            .firstOrNull { !it.command.isNullOrBlank() }
    }

    suspend fun saveBrowse(cache: BrowseCatalogCache) = db.withTransaction {
        val old = dao.items(cache.profileKey, cache.type.name).associateBy { it.bucket to it.id }
        val oldBuckets = dao.buckets(cache.profileKey, cache.type.name).associateBy { it.bucket }
        val categories = (cache.categories + cache.itemsByCategory.keys.filter { id -> cache.categories.none { it.id == id } }
            .map { Category(it, it, cache.type) }).distinctBy { it.id }
        dao.putBuckets(categories.mapIndexed { index, c ->
            val prior = oldBuckets[c.id]
            if (prior != null && prior.observedAt > cache.cachedAtMillis) prior else CatalogBucketRow(cache.profileKey, cache.type.name, c.id, c.title,
                cache.pagesByCategory[c.id] ?: prior?.page ?: 0,
                cache.hasMoreByCategory[c.id] ?: prior?.hasMore ?: true, cache.cachedAtMillis, index)
        })
        val rows = cache.itemsByCategory.flatMap { (bucket, items) -> items.mapIndexed { index, item ->
            val payload = json.encodeToString(item)
            val prior = old[bucket to item.id]
            if (prior != null && prior.observedAt > cache.cachedAtMillis) prior else
                CatalogItemRow(cache.profileKey, cache.type.name, bucket, item.id, index, payload,
                    if (prior?.payload == payload && !prior.deleted) prior.observedAt else cache.cachedAtMillis)
        } }
        dao.putItems(rows.filter { old[it.bucket to it.id] != it })
    }

    suspend fun saveSearch(cache: SearchCatalogCache) = db.withTransaction {
        val old = dao.items(cache.profileKey, cache.type.name).filter { it.bucket == SEARCH }.associateBy { it.id }
        dao.putBuckets(listOf(CatalogBucketRow(cache.profileKey, cache.type.name, SEARCH, "", observedAt = cache.cachedAtMillis)))
        dao.putItems(cache.items.mapIndexed { index, item ->
            val payload = json.encodeToString(item)
            val prior = old[item.id]
            if (prior != null && prior.observedAt > cache.cachedAtMillis) prior else
                CatalogItemRow(cache.profileKey, cache.type.name, SEARCH, item.id, index, payload,
                    if (prior?.payload == payload && !prior.deleted) prior.observedAt else cache.cachedAtMillis)
        }.filter { old[it.id] != it })
    }

    /** Only a fully traversed provider category may retire missing records. Interrupted scans never delete. */
    suspend fun reconcileCategory(profile: String, type: CatalogType, category: String, seen: Set<String>, at: Long) = db.withTransaction {
        val missing = dao.items(profile, type.name).filter { row ->
            !row.deleted && row.id !in seen && (category == "*" || row.bucket == category ||
                json.decodeFromString<MediaItem>(row.payload).portalCategoryId == category)
        }
        dao.putItems(missing.map { it.copy(deleted = true, observedAt = at) })
    }

    suspend fun migrateEpisodes(caches: List<EpisodeSeasonCache>) = db.withTransaction {
        if (dao.migrated("episodes") == 0) {
            caches.forEach { saveEpisodes(it) }
            dao.markMigrated(CatalogMigration("episodes"))
        }
    }
    fun episodes(): Flow<List<EpisodeSeasonCache>> = dao.observeEpisodes().map { rows -> rows.map { json.decodeFromString<EpisodeSeasonCache>(it.payload) } }.flowOn(Dispatchers.IO)
    suspend fun saveEpisodes(cache: EpisodeSeasonCache) {
        dao.putEpisodes(listOf(CatalogEpisodeRow(cache.profileKey, cache.seriesId, cache.season ?: -1,
            json.encodeToString(cache), cache.cachedAtMillis)))
    }

    suspend fun snapshot(profile: PortalProfile, type: CatalogType): CatalogSnapshot {
        migrate(profile.cacheKey(), type)
        return db.withTransaction {
            val key = profile.cacheKey()
            CatalogSnapshot(profileId = SearchMetadataDocuments.anonymousProfileId(profile), type = type,
                items = dao.items(key, type.name).map { it.copy(profile = "") },
                buckets = dao.buckets(key, type.name).map { it.copy(profile = "") },
                episodes = if (type == CatalogType.SERIES) dao.episodes().filter { it.profile == key }.map {
                    val cache = json.decodeFromString<EpisodeSeasonCache>(it.payload).copy(profileKey = "")
                    it.copy(profile = "", payload = json.encodeToString(cache))
                } else emptyList())
        }
    }

    suspend fun mergeSnapshot(profile: PortalProfile, remote: CatalogSnapshot) = db.withTransaction {
        require(remote.schemaVersion == 1 && remote.profileId == SearchMetadataDocuments.anonymousProfileId(profile))
        val local = snapshot(profile, remote.type)
        val merged = mergeCatalogSnapshots(local, remote)
        val key = profile.cacheKey()
        dao.putItems(merged.items.map { row ->
            require(row.type == remote.type.name && json.decodeFromString<MediaItem>(row.payload).id == row.id)
            row.copy(profile = key)
        })
        dao.putBuckets(merged.buckets.map { require(it.type == remote.type.name); it.copy(profile = key) })
        dao.putEpisodes(merged.episodes.map {
            val cache = json.decodeFromString<EpisodeSeasonCache>(it.payload)
            require(cache.seriesId == it.series && (cache.season ?: -1) == it.season)
            it.copy(profile = key, payload = json.encodeToString(cache.copy(profileKey = key)))
        })
    }

    internal suspend fun mergeCheckpoint(profile: PortalProfile, checkpoint: CatalogCheckpoint) = db.withTransaction {
        val types = setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
        val id = SearchMetadataDocuments.anonymousProfileId(profile)
        require(checkpoint.version == 1 && checkpoint.profileId == id)
        require(checkpoint.snapshots.size == types.size && checkpoint.snapshots.map { it.type }.toSet() == types)
        require(checkpoint.snapshots.all { it.profileId == id && it.schemaVersion == 1 })
        checkpoint.snapshots.forEach { mergeSnapshot(profile, it) }
    }

    suspend fun restartScan(profile: PortalProfile, type: CatalogType) {
        migrate(profile.cacheKey(), type)
        db.withTransaction {
            dao.putBuckets(dao.buckets(profile.cacheKey(), type.name).map {
                it.copy(page = 0, hasMore = true, observedAt = System.currentTimeMillis())
            })
        }
    }

    suspend fun clear() = db.withTransaction { dao.clearItems(); dao.clearBuckets(); dao.clearEpisodes() }
    companion object { private const val SEARCH = "@search" }
}

@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int = 1, val profileId: String, val type: CatalogType,
    val items: List<CatalogItemRow>, val buckets: List<CatalogBucketRow>, val episodes: List<CatalogEpisodeRow>
)

/** Union preserves discoveries from other devices; timestamps break conflicts per record, not per file. */
internal fun mergeCatalogSnapshots(local: CatalogSnapshot, remote: CatalogSnapshot): CatalogSnapshot {
    require(local.schemaVersion == remote.schemaVersion && local.profileId == remote.profileId && local.type == remote.type)
    val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun <T, K> merge(a: List<T>, b: List<T>, key: (T) -> K, time: (T) -> Long, tie: (T) -> String): List<T> =
        (a + b).groupBy(key).values.map { versions -> versions.maxWith(compareBy(time, tie)) }
    return local.copy(
        items = merge(local.items, remote.items, { it.bucket to it.id }, { it.observedAt }, { "${it.deleted}:${it.payload}" })
            .sortedWith(compareBy({ it.bucket }, { it.position }, { it.id })),
        buckets = merge(local.buckets, remote.buckets, { it.bucket }, { it.observedAt }, { it.toString() }).sortedBy { it.bucket },
        episodes = (local.episodes + remote.episodes).groupBy { it.series to it.season }.values.map { versions ->
            val sorted = versions.sortedWith(compareByDescending<CatalogEpisodeRow> { it.observedAt }.thenByDescending { it.payload })
            val caches = sorted.map { codec.decodeFromString<EpisodeSeasonCache>(it.payload) }
            val newest = caches.first()
            sorted.first().copy(payload = codec.encodeToString(newest.copy(
                episodes = caches.flatMap { it.episodes }.distinctBy { it.id },
                iptvEpisodes = caches.flatMap { it.iptvEpisodes }.distinctBy { it.id },
                availableSeasons = caches.flatMap { it.availableSeasons }.distinct().sorted()
            )))
        }.sortedWith(compareBy({ it.series }, { it.season }))
    )
}
