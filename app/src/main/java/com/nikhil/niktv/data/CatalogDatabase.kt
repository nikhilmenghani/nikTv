package com.nikhil.niktv.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
data class CatalogProfileType(val profile: String, val type: String)
data class CatalogBucketCount(val bucket: String, val count: Int)

data class CatalogScanState(
    val cachedAtMillis: Long = 0L,
    val categories: List<Category> = emptyList(),
    val pagesByCategory: Map<String, Int> = emptyMap(),
    val hasMoreByCategory: Map<String, Boolean> = emptyMap(),
    val itemCountsByCategory: Map<String, Int> = emptyMap(),
    val totalItems: Int = 0
)

/** Compact, rebuildable search metadata. Full provider payloads remain in CatalogItemRow only. */
@Entity(
    primaryKeys = ["profile", "type", "id"],
    indices = [Index(value = ["profile", "type", "normalizedTitle"])]
)
data class CatalogSearchRow(
    val profile: String,
    val type: String,
    val id: String,
    val title: String,
    val normalizedTitle: String,
    val categoryId: String?,
    val externalTmdbId: Int?,
    val channelNumber: Int?,
    val observedAt: Long,
    val deleted: Boolean = false
)

/** Read-only comparison baseline promoted only after a media type finishes syncing. */
@Entity(primaryKeys = ["profile", "type", "category"])
data class CatalogProviderBaselineRow(
    val profile: String,
    val type: String,
    val category: String,
    val title: String,
    val lastPage: Int,
    val pageSize: Int,
    val totalItems: Int,
    val firstPageHash: String,
    val lastPageHash: String,
    val completedAt: Long
)

enum class CatalogUpdateState {
    NOT_CHECKED, CHECKING, NO_CHANGES, UPDATE_AVAILABLE, CHANGED,
    INCOMPLETE, NOT_SCANNED, FAILED
}

/** Latest provider-check verdict. This never participates in scan resumption. */
@Entity(primaryKeys = ["profile", "type"])
data class CatalogTypeUpdateRow(
    val profile: String,
    val type: String,
    val state: String = CatalogUpdateState.NOT_CHECKED.name,
    val detail: String = "Not checked yet.",
    val newPages: Int = 0,
    val newPagesExact: Boolean = false,
    val newCategories: Int = 0,
    val removedCategories: Int = 0,
    val checkedAt: Long = 0L
) {
    val updateState: CatalogUpdateState
        get() = runCatching { CatalogUpdateState.valueOf(state) }
            .getOrDefault(CatalogUpdateState.NOT_CHECKED)
}

/** Device-local rows awaiting confirmation during a from-scratch refresh. Never backed up. */
@Entity(primaryKeys = ["profile", "type", "bucket", "id"])
data class CatalogRefreshCandidateRow(
    val profile: String,
    val type: String,
    val bucket: String,
    val id: String
)

data class CatalogScanCheckpoint(
    val hasData: Boolean,
    val complete: Boolean,
    val currentCategory: String = "",
    val currentPage: Int = 0,
    val updatedAt: Long = 0L
)

@Dao
interface CatalogDao {
    @Query("""SELECT a.type, COUNT(DISTINCT a.id) AS count FROM CatalogItemRow a
        WHERE a.profile = :profile AND a.deleted = 0
        GROUP BY a.type""")
    fun storedCounts(profile: String): Flow<List<CatalogStoredCount>>
    @Query("""SELECT a.* FROM CatalogItemRow a WHERE a.profile = :profile AND a.type = :type AND a.deleted = 0
        AND NOT EXISTS (SELECT 1 FROM CatalogItemRow b WHERE b.profile = a.profile AND b.type = a.type AND b.id = a.id
          AND b.deleted = 0 AND (b.observedAt > a.observedAt OR
          (b.observedAt = a.observedAt AND b.bucket > a.bucket)))
        ORDER BY a.observedAt DESC, a.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun storedPage(profile: String, type: String, limit: Int, offset: Int): List<CatalogItemRow>

    @Query("SELECT * FROM CatalogItemRow WHERE profile = :profile AND type = :type ORDER BY position, id")
    suspend fun items(profile: String, type: String): List<CatalogItemRow>
    @Query("SELECT COUNT(*) FROM CatalogItemRow WHERE profile = :profile AND type = :type AND bucket != '@search'")
    suspend fun snapshotItemCount(profile: String, type: String): Int
    @Query("SELECT * FROM CatalogItemRow WHERE profile = :profile AND type = :type AND bucket != '@search' ORDER BY position, id LIMIT :limit OFFSET :offset")
    suspend fun snapshotItemsPage(profile: String, type: String, limit: Int, offset: Int): List<CatalogItemRow>
    @Query("SELECT * FROM CatalogItemRow WHERE profile = :profile AND type = :type AND id = :id ORDER BY observedAt DESC, deleted DESC")
    suspend fun item(profile: String, type: String, id: String): List<CatalogItemRow>
    @Query("SELECT * FROM CatalogBucketRow WHERE profile = :profile AND type = :type ORDER BY position, bucket")
    suspend fun buckets(profile: String, type: String): List<CatalogBucketRow>
    @Upsert suspend fun putItems(rows: List<CatalogItemRow>)
    @Upsert suspend fun putBuckets(rows: List<CatalogBucketRow>)
    @Upsert suspend fun putEpisodes(rows: List<CatalogEpisodeRow>)
    @Upsert suspend fun putSearchRows(rows: List<CatalogSearchRow>)
    @Query("SELECT * FROM CatalogSearchRow WHERE profile = :profile AND type = :type AND deleted = 0 AND normalizedTitle LIKE '%' || :query || '%' ORDER BY title, id LIMIT :limit")
    suspend fun searchRows(profile: String, type: String, query: String, limit: Int): List<CatalogSearchRow>
    @Query("SELECT COUNT(*) FROM CatalogSearchRow WHERE profile = :profile AND type = :type AND deleted = 0")
    suspend fun searchRowCount(profile: String, type: String): Int
    @Query("SELECT COUNT(DISTINCT id) FROM CatalogItemRow WHERE profile = :profile AND type = :type AND bucket != '@search' AND deleted = 0")
    suspend fun canonicalItemCount(profile: String, type: String): Int
    @Query("""SELECT bucket, COUNT(*) AS count FROM CatalogItemRow
        WHERE profile = :profile AND type = :type AND bucket != '@search' AND deleted = 0
        GROUP BY bucket""")
    suspend fun bucketItemCounts(profile: String, type: String): List<CatalogBucketCount>
    @Query("SELECT DISTINCT profile, type FROM CatalogItemRow WHERE bucket != '@search'")
    suspend fun catalogProfileTypes(): List<CatalogProfileType>
    @Query("DELETE FROM CatalogSearchRow WHERE profile = :profile AND type = :type")
    suspend fun clearSearchRows(profile: String, type: String)
    @Query("DELETE FROM CatalogSearchRow") suspend fun clearAllSearchRows()
    @Query("""SELECT a.* FROM CatalogItemRow a WHERE a.profile = :profile AND a.type = :type
        AND a.bucket != '@search' AND a.deleted = 0 AND NOT EXISTS
        (SELECT 1 FROM CatalogItemRow b WHERE b.profile = a.profile AND b.type = a.type
          AND b.id = a.id AND b.bucket != '@search' AND b.deleted = 0
          AND (b.observedAt > a.observedAt OR (b.observedAt = a.observedAt AND b.bucket > a.bucket)))
        ORDER BY a.id LIMIT :limit OFFSET :offset""")
    suspend fun canonicalRowsPage(profile: String, type: String, limit: Int, offset: Int): List<CatalogItemRow>
    @Query("SELECT * FROM CatalogEpisodeRow") suspend fun episodes(): List<CatalogEpisodeRow>
    @Query("SELECT COUNT(*) FROM CatalogEpisodeRow WHERE profile = :profile")
    suspend fun snapshotEpisodeCount(profile: String): Int
    @Query("SELECT * FROM CatalogEpisodeRow WHERE profile = :profile ORDER BY series, season LIMIT :limit OFFSET :offset")
    suspend fun snapshotEpisodesPage(profile: String, limit: Int, offset: Int): List<CatalogEpisodeRow>
    @Query("SELECT * FROM CatalogEpisodeRow") fun observeEpisodes(): Flow<List<CatalogEpisodeRow>>
    @Query("""SELECT id FROM CatalogItemRow WHERE profile = :profile AND type = :type
        AND bucket = :bucket AND deleted = 0 AND position >= :startPosition AND position < :endPosition
        ORDER BY position, id""")
    suspend fun itemIdsInPositionRange(
        profile: String,
        type: String,
        bucket: String,
        startPosition: Int,
        endPosition: Int
    ): List<String>
    @Query("SELECT COUNT(*) FROM CatalogItemRow WHERE profile = :profile AND type = :type AND bucket = :bucket AND deleted = 0")
    suspend fun bucketItemCount(profile: String, type: String, bucket: String): Int
    @Query("SELECT * FROM CatalogProviderBaselineRow WHERE profile = :profile AND type = :type ORDER BY category")
    suspend fun providerBaselines(profile: String, type: String): List<CatalogProviderBaselineRow>
    @Upsert suspend fun putProviderBaselines(rows: List<CatalogProviderBaselineRow>)
    @Query("DELETE FROM CatalogProviderBaselineRow WHERE profile = :profile AND type = :type")
    suspend fun clearProviderBaselines(profile: String, type: String)
    @Query("SELECT * FROM CatalogTypeUpdateRow WHERE profile = :profile ORDER BY type")
    fun observeUpdateRows(profile: String): Flow<List<CatalogTypeUpdateRow>>
    @Query("SELECT * FROM CatalogTypeUpdateRow WHERE profile = :profile AND type = :type")
    suspend fun updateRow(profile: String, type: String): CatalogTypeUpdateRow?
    @Upsert suspend fun putUpdateRows(rows: List<CatalogTypeUpdateRow>)
    @Query("DELETE FROM CatalogProviderBaselineRow") suspend fun clearProviderBaselines()
    @Query("DELETE FROM CatalogTypeUpdateRow") suspend fun clearUpdateRows()
    @Query("DELETE FROM CatalogTypeUpdateRow WHERE profile = :profile AND type = :type")
    suspend fun clearUpdateRow(profile: String, type: String)
    @Query("DELETE FROM CatalogRefreshCandidateRow WHERE profile = :profile AND type = :type")
    suspend fun clearRefreshCandidates(profile: String, type: String)
    @Query("DELETE FROM CatalogRefreshCandidateRow") suspend fun clearRefreshCandidates()
    @Upsert suspend fun putRefreshCandidates(rows: List<CatalogRefreshCandidateRow>)
    @Query("""INSERT OR REPLACE INTO CatalogRefreshCandidateRow(profile, type, bucket, id)
        SELECT profile, type, bucket, id FROM CatalogItemRow
        WHERE profile = :profile AND type = :type AND deleted = 0""")
    suspend fun stageRefreshCandidates(profile: String, type: String)
    @Query("""DELETE FROM CatalogRefreshCandidateRow WHERE profile = :profile AND type = :type
        AND bucket = :bucket AND id IN (:ids)""")
    suspend fun confirmRefreshItems(profile: String, type: String, bucket: String, ids: List<String>)
    @Query("""UPDATE CatalogItemRow SET deleted = 1,
        observedAt = CASE WHEN observedAt >= :at THEN observedAt + 1 ELSE :at END
        WHERE profile = :profile AND type = :type AND deleted = 0 AND EXISTS
        (SELECT 1 FROM CatalogRefreshCandidateRow candidate
          WHERE candidate.profile = CatalogItemRow.profile AND candidate.type = CatalogItemRow.type
          AND candidate.bucket = CatalogItemRow.bucket AND candidate.id = CatalogItemRow.id)""")
    suspend fun retireRefreshCandidates(profile: String, type: String, at: Long)
    @Query("""SELECT COUNT(*) FROM CatalogRefreshCandidateRow
        WHERE profile = :profile AND type = :type AND bucket = '@generation' AND id = :generation""")
    suspend fun refreshGenerationCount(profile: String, type: String, generation: String): Int
    @Query("""UPDATE CatalogItemRow SET deleted = 1,
        observedAt = CASE WHEN observedAt >= :at THEN observedAt + 1 ELSE :at END
        WHERE profile = :profile AND type = :type AND deleted = 0
        AND observedAt < :startedAt AND bucket IN (:scanBuckets)""")
    suspend fun retireRowsNotObservedSince(
        profile: String,
        type: String,
        scanBuckets: List<String>,
        startedAt: Long,
        at: Long
    )
    @Query("""UPDATE CatalogItemRow SET deleted = 1,
        observedAt = CASE WHEN observedAt >= :at THEN observedAt + 1 ELSE :at END
        WHERE profile = :profile AND type = :type AND deleted = 0
        AND bucket NOT IN (:activeCategories)""")
    suspend fun retireRowsOutsideCategories(
        profile: String,
        type: String,
        activeCategories: List<String>,
        at: Long
    )
    @Query("""UPDATE CatalogSearchRow SET deleted = 1,
        observedAt = CASE WHEN observedAt >= :at THEN observedAt + 1 ELSE :at END
        WHERE profile = :profile AND type = :type AND NOT EXISTS (
            SELECT 1 FROM CatalogItemRow item WHERE item.profile = :profile AND item.type = :type
            AND item.id = CatalogSearchRow.id AND item.deleted = 0)""")
    suspend fun retireOrphanedSearchRows(profile: String, type: String, at: Long)
    @Query("DELETE FROM CatalogBucketRow WHERE profile = :profile AND type = :type AND bucket NOT IN (:activeCategories)")
    suspend fun removeBucketsOutsideCategories(profile: String, type: String, activeCategories: List<String>)
    @Query("SELECT COUNT(*) FROM CatalogMigration WHERE `key` = :key") suspend fun migrated(key: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markMigrated(row: CatalogMigration)
    @Query("DELETE FROM CatalogItemRow") suspend fun clearItems()
    @Query("DELETE FROM CatalogBucketRow") suspend fun clearBuckets()
    @Query("DELETE FROM CatalogBucketRow WHERE profile = :profile AND type = :type")
    suspend fun clearBuckets(profile: String, type: String)
    @Query("DELETE FROM CatalogEpisodeRow") suspend fun clearEpisodes()
}

@Database(entities = [CatalogItemRow::class, CatalogBucketRow::class, CatalogEpisodeRow::class,
    CatalogMigration::class, CatalogSearchRow::class, CatalogProviderBaselineRow::class,
    CatalogTypeUpdateRow::class, CatalogRefreshCandidateRow::class], version = 3, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    companion object {
        @Volatile private var instance: CatalogDatabase? = null
        fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CatalogDatabase::class.java, "catalog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `CatalogSearchRow` (`profile` TEXT NOT NULL,
                    `type` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL,
                    `normalizedTitle` TEXT NOT NULL, `categoryId` TEXT, `externalTmdbId` INTEGER,
                    `channelNumber` INTEGER, `observedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL,
                    PRIMARY KEY(`profile`, `type`, `id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_CatalogSearchRow_profile_type_normalizedTitle` ON `CatalogSearchRow` (`profile`, `type`, `normalizedTitle`)")
                // Search rows are derived data. Removing these full-payload copies is safe;
                // canonical provider rows and their scan cursors remain untouched.
                db.execSQL("DELETE FROM `CatalogItemRow` WHERE `bucket` = '@search'")
                db.execSQL("DELETE FROM `CatalogBucketRow` WHERE `bucket` = '@search'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `CatalogProviderBaselineRow` (
                    `profile` TEXT NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL,
                    `title` TEXT NOT NULL, `lastPage` INTEGER NOT NULL, `pageSize` INTEGER NOT NULL,
                    `totalItems` INTEGER NOT NULL, `firstPageHash` TEXT NOT NULL,
                    `lastPageHash` TEXT NOT NULL, `completedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`profile`, `type`, `category`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `CatalogTypeUpdateRow` (
                    `profile` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL,
                    `detail` TEXT NOT NULL, `newPages` INTEGER NOT NULL,
                    `newPagesExact` INTEGER NOT NULL, `newCategories` INTEGER NOT NULL,
                    `removedCategories` INTEGER NOT NULL, `checkedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`profile`, `type`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `CatalogRefreshCandidateRow` (
                    `profile` TEXT NOT NULL, `type` TEXT NOT NULL, `bucket` TEXT NOT NULL,
                    `id` TEXT NOT NULL, PRIMARY KEY(`profile`, `type`, `bucket`, `id`))""")
                // v2 could retain a live compact-search row after every canonical copy
                // of that ID was tombstoned. Search rows are derived, so retire orphans.
                db.execSQL("""UPDATE `CatalogSearchRow` SET `deleted` = 1
                    WHERE `deleted` = 0 AND NOT EXISTS (
                        SELECT 1 FROM `CatalogItemRow` item
                        WHERE item.`profile` = `CatalogSearchRow`.`profile`
                        AND item.`type` = `CatalogSearchRow`.`type`
                        AND item.`id` = `CatalogSearchRow`.`id`
                        AND item.`bucket` != '@search' AND item.`deleted` = 0)""")
            }
        }
    }
}

/** Isolated Room catalog used by scanner, inspection, backup, and restore tools. */
class CatalogRepository(context: Context, private val db: CatalogDatabase = CatalogDatabase.get(context)) {
    private val app = context.applicationContext
    private val dao = db.catalog()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun MediaItem.asSearchRow(profile: String, type: CatalogType, at: Long) = CatalogSearchRow(
        profile = profile, type = type.name, id = id, title = title,
        normalizedTitle = title.normalizedSearchQuery(), categoryId = portalCategoryId,
        externalTmdbId = externalTmdbId, channelNumber = channelNumber, observedAt = at
    )

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
            val buckets = dao.buckets(profile, type.name)
                .filterNot { it.bucket == SEARCH || it.bucket == EMPTY_BUCKET }
            if (buckets.isEmpty()) return@withTransaction null
            val allRows = dao.items(profile, type.name)
            val liveRows = allRows.filterNot { it.deleted }
            val latest = liveRows.sortedByDescending { it.observedAt }.distinctBy { it.id }
            val rows = liveRows.groupBy { it.bucket }
            val allComplete = buckets.any { it.bucket == "*" && it.page > 0 && !it.hasMore }
            val derived = if (allComplete) latest.map { json.decodeFromString<MediaItem>(it.payload) }
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
                rows.filterNot { it.deleted }.sortedByDescending { it.observedAt }.distinctBy { it.id }
                    .map { json.decodeFromString<MediaItem>(it.payload) },
                buckets.filter { !it.hasMore && it.bucket != SEARCH }.map { it.bucket }.toSet())
        }
    }

    suspend fun media(profile: String, type: CatalogType, id: String): MediaItem? {
        migrate(profile, type)
        val rows = dao.item(profile, type.name, id)
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
        dao.putSearchRows(cache.itemsByCategory.values.flatten().distinctBy { it.id }
            .map { it.asSearchRow(cache.profileKey, cache.type, cache.cachedAtMillis) })
    }

    /**
     * Commits one provider page without reading and rewriting the whole catalogue.
     *
     * A large Stalker catalogue can contain well over 100,000 rows while its API
     * returns only a handful per page.  saveBrowse is appropriate for imports and
     * migrations, but using it for every scanned page makes page N do O(N) work.
     */
    suspend fun saveBrowsePage(
        profile: String,
        type: CatalogType,
        categories: List<Category>,
        category: Category,
        page: Int,
        items: List<MediaItem>,
        hasMore: Boolean,
        observedAt: Long
    ) = db.withTransaction {
        val oldBuckets = dao.buckets(profile, type.name).associateBy { it.bucket }
        val allCategories = (categories + category).distinctBy { it.id }
        dao.putBuckets(allCategories.mapIndexed { index, candidate ->
            val prior = oldBuckets[candidate.id]
            if (candidate.id == category.id) {
                CatalogBucketRow(profile, type.name, candidate.id, candidate.title,
                    page, hasMore, observedAt, index)
            } else {
                prior ?: CatalogBucketRow(profile, type.name, candidate.id, candidate.title,
                    observedAt = observedAt, position = index)
            }
        })
        val pagePosition = (page.coerceAtLeast(1) - 1).toLong() * PAGE_POSITION_STRIDE
        dao.putItems(items.mapIndexed { index, item ->
            CatalogItemRow(profile, type.name, category.id, item.id,
                (pagePosition + index).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                json.encodeToString(item), observedAt)
        })
        dao.putSearchRows(items.map { it.asSearchRow(profile, type, observedAt) })
        if (items.isNotEmpty()) dao.confirmRefreshItems(profile, type.name, category.id, items.map { it.id })
    }

    suspend fun saveSearch(cache: SearchCatalogCache) = db.withTransaction {
        dao.putSearchRows(cache.items.distinctBy { it.id }
            .map { it.asSearchRow(cache.profileKey, cache.type, cache.cachedAtMillis) })
    }

    suspend fun searchIndex(profile: String, type: CatalogType, query: String, limit: Int = 100): List<CatalogSearchRow> =
        run {
            ensureSearchIndex(profile, type)
            dao.searchRows(profile, type.name, query.normalizedSearchQuery(), limit.coerceIn(1, 500))
        }

    suspend fun rebuildMissingSearchIndexes() {
        dao.catalogProfileTypes().forEach { pair ->
            val type = runCatching { CatalogType.valueOf(pair.type) }.getOrNull() ?: return@forEach
            ensureSearchIndex(pair.profile, type)
        }
    }

    suspend fun scanCheckpoint(profile: PortalProfile, type: CatalogType): CatalogScanCheckpoint {
        val key = profile.cacheKey()
        migrate(key, type)
        return db.withTransaction {
            val buckets = dao.buckets(key, type.name).filterNot { it.bucket == SEARCH }
            val scanBuckets = buckets.firstOrNull { it.bucket == "*" }?.let(::listOf) ?: buckets
            val incomplete = scanBuckets.firstOrNull { it.page <= 0 || it.hasMore }
            CatalogScanCheckpoint(
                hasData = scanBuckets.isNotEmpty() && scanBuckets.any { it.page > 0 },
                complete = scanBuckets.isNotEmpty() && incomplete == null,
                currentCategory = incomplete?.title.orEmpty(),
                currentPage = incomplete?.page ?: scanBuckets.maxOfOrNull { it.page } ?: 0,
                updatedAt = buckets.maxOfOrNull { it.observedAt } ?: 0L
            )
        }
    }

    /** Cursor/count-only scanner state; never materializes full provider payloads. */
    suspend fun scanState(profile: PortalProfile, type: CatalogType): CatalogScanState {
        val key = profile.cacheKey()
        migrate(key, type)
        return db.withTransaction {
            val buckets = dao.buckets(key, type.name).filterNot { it.bucket == SEARCH }
            CatalogScanState(
                cachedAtMillis = buckets.maxOfOrNull { it.observedAt } ?: 0L,
                categories = buckets.filterNot { it.bucket == EMPTY_BUCKET }
                    .map { Category(it.bucket, it.title, type) },
                pagesByCategory = buckets.associate { it.bucket to it.page },
                hasMoreByCategory = buckets.associate { it.bucket to it.hasMore },
                itemCountsByCategory = dao.bucketItemCounts(key, type.name)
                    .associate { it.bucket to it.count },
                totalItems = dao.canonicalItemCount(key, type.name)
            )
        }
    }

    internal suspend fun storedBucketItemCount(profile: String, type: CatalogType, bucket: String): Int =
        dao.bucketItemCount(profile, type.name, bucket)

    fun observeUpdateStatuses(profile: PortalProfile): Flow<List<CatalogTypeUpdateRow>> =
        dao.observeUpdateRows(profile.cacheKey())

    internal suspend fun updateStatus(profile: PortalProfile, type: CatalogType): CatalogTypeUpdateRow? =
        dao.updateRow(profile.cacheKey(), type.name)

    internal suspend fun saveUpdateStatus(row: CatalogTypeUpdateRow) {
        dao.putUpdateRows(listOf(row))
    }

    internal suspend fun providerBaselines(
        profile: PortalProfile,
        type: CatalogType
    ): List<CatalogProviderBaselineRow> {
        migrate(profile.cacheKey(), type)
        return dao.providerBaselines(profile.cacheKey(), type.name)
    }

    internal suspend fun storedPageIds(
        profile: PortalProfile,
        type: CatalogType,
        category: String,
        page: Int
    ): List<String> {
        val start = ((page.coerceAtLeast(1) - 1).toLong() * PAGE_POSITION_STRIDE)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val end = (start.toLong() + PAGE_POSITION_STRIDE)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return dao.itemIdsInPositionRange(profile.cacheKey(), type.name, category, start, end)
    }

    /** Build a comparison baseline from a fully committed Room scan, without provider I/O. */
    suspend fun promoteProviderBaseline(
        profile: PortalProfile,
        type: CatalogType,
        completedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val key = profile.cacheKey()
        migrate(key, type)
        return db.withTransaction {
            val buckets = dao.buckets(key, type.name).filterNot { it.bucket == SEARCH }
            val scanBuckets = buckets.firstOrNull { it.bucket == "*" }?.let(::listOf) ?: buckets
            if (scanBuckets.isEmpty() || scanBuckets.any { it.page <= 0 || it.hasMore }) {
                return@withTransaction false
            }
            dao.clearProviderBaselines(key, type.name)
            dao.putProviderBaselines(buckets.map { bucket ->
                val firstIds = if (bucket.page > 0) storedPageIds(profile, type, bucket.bucket, 1) else emptyList()
                val lastIds = if (bucket.page > 0) storedPageIds(profile, type, bucket.bucket, bucket.page) else emptyList()
                CatalogProviderBaselineRow(
                    profile = key,
                    type = type.name,
                    category = bucket.bucket,
                    title = bucket.title,
                    lastPage = bucket.page,
                    // Provider capacity/totals are not reconstructible from filtered, deduplicated rows.
                    pageSize = 0,
                    totalItems = 0,
                    firstPageHash = catalogPageFingerprint(firstIds),
                    lastPageHash = catalogPageFingerprint(lastIds),
                    completedAt = completedAt
                )
            })
            true
        }
    }

    internal suspend fun ensureProviderBaseline(
        profile: PortalProfile,
        type: CatalogType
    ): List<CatalogProviderBaselineRow> {
        val existing = providerBaselines(profile, type)
        if (existing.isNotEmpty()) return existing
        val checkpoint = scanCheckpoint(profile, type)
        if (checkpoint.complete) promoteProviderBaseline(profile, type, checkpoint.updatedAt)
        return providerBaselines(profile, type)
    }

    suspend fun markTypeSynced(profile: PortalProfile, type: CatalogType, at: Long): Boolean {
        if (!promoteProviderBaseline(profile, type, at)) return false
        saveUpdateStatus(CatalogTypeUpdateRow(
            profile = profile.cacheKey(),
            type = type.name,
            state = CatalogUpdateState.NO_CHANGES.name,
            detail = "Synced with provider.",
            checkedAt = at
        ))
        return true
    }

    /** Retire records not seen during a successfully completed refresh generation. */
    suspend fun reconcileSuccessfulRefresh(
        profile: PortalProfile,
        type: CatalogType,
        providerCategories: List<Category>,
        startedAt: Long,
        confirmedEmpty: Boolean = false,
        at: Long = System.currentTimeMillis()
    ): Boolean {
        val active = providerCategories.filter { it.id.isNotBlank() }.distinctBy { it.id }.map { it.id }
        if (active.isEmpty() && !confirmedEmpty) return false
        val key = profile.cacheKey()
        return db.withTransaction {
            val ownsRefreshGeneration = startedAt > 0L &&
                dao.refreshGenerationCount(key, type.name, startedAt.toString()) > 0
            // A restored/legacy resume may safely reconcile category topology, but it
            // must never retire unseen rows inside an active category. Only the Room-
            // backed generation created with the candidate snapshot may do that.
            if (confirmedEmpty && !ownsRefreshGeneration &&
                dao.canonicalItemCount(key, type.name) > 0) {
                return@withTransaction false
            }
            // Candidates were snapshotted atomically with restartScan. Anything not
            // confirmed by a committed provider page is absent from the completed refresh.
            if (ownsRefreshGeneration) dao.retireRefreshCandidates(key, type.name, at)
            dao.clearRefreshCandidates(key, type.name)
            if (confirmedEmpty) {
                dao.clearBuckets(key, type.name)
                dao.putBuckets(listOf(CatalogBucketRow(
                    profile = key,
                    type = type.name,
                    bucket = EMPTY_BUCKET,
                    title = "Empty provider catalog",
                    page = 1,
                    hasMore = false,
                    observedAt = at
                )))
            } else {
                dao.retireRowsOutsideCategories(key, type.name, active, at)
                dao.removeBucketsOutsideCategories(key, type.name, active)
            }
            dao.retireOrphanedSearchRows(key, type.name, at)
            true
        }
    }

    private suspend fun ensureSearchIndex(profile: String, type: CatalogType) {
        db.withTransaction {
            val repairKey = "compact-search-v3:$profile:${type.name}"
            if (dao.migrated(repairKey) == 0) {
                dao.retireOrphanedSearchRows(profile, type.name, System.currentTimeMillis())
                dao.markMigrated(CatalogMigration(repairKey))
            }
        }
        val canonicalCount = dao.canonicalItemCount(profile, type.name)
        if (canonicalCount == 0 || dao.searchRowCount(profile, type.name) >= canonicalCount) return
        var offset = 0
        while (true) {
            val rows = dao.canonicalRowsPage(profile, type.name, INDEX_REBUILD_BATCH, offset)
            if (rows.isEmpty()) break
            dao.putSearchRows(rows.distinctBy { it.id }.map { row ->
                json.decodeFromString<MediaItem>(row.payload).asSearchRow(profile, type, row.observedAt)
            })
            offset += rows.size
        }
    }

    /** Only a fully traversed provider category may retire missing records. Interrupted scans never delete. */
    suspend fun reconcileCategory(profile: String, type: CatalogType, category: String, seen: Set<String>, at: Long) = db.withTransaction {
        val missing = dao.items(profile, type.name).filter { row ->
            !row.deleted && row.id !in seen && (category == "*" || row.bucket == category ||
                json.decodeFromString<MediaItem>(row.payload).portalCategoryId == category)
        }
        dao.putItems(missing.map { row ->
            row.copy(deleted = true, observedAt = maxOf(at, row.observedAt + 1))
        })
        dao.retireOrphanedSearchRows(profile, type.name, at)
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
                items = dao.items(key, type.name).filterNot { it.bucket == SEARCH }.map { it.copy(profile = "") },
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
        val canonicalItems = merged.items.filterNot { it.bucket == SEARCH }
        dao.putItems(canonicalItems.map { row ->
            require(row.type == remote.type.name && json.decodeFromString<MediaItem>(row.payload).id == row.id)
            row.copy(profile = key)
        })
        dao.putSearchRows(canonicalItems.filterNot { it.deleted }.map { row ->
            json.decodeFromString<MediaItem>(row.payload).asSearchRow(key, remote.type, row.observedAt)
        })
        dao.retireOrphanedSearchRows(
            key,
            remote.type.name,
            canonicalItems.maxOfOrNull { it.observedAt } ?: System.currentTimeMillis()
        )
        dao.putBuckets(merged.buckets.filterNot { it.bucket == SEARCH }.map {
            require(it.type == remote.type.name); it.copy(profile = key)
        })
        dao.putEpisodes(merged.episodes.map {
            val cache = json.decodeFromString<EpisodeSeasonCache>(it.payload)
            require(cache.seriesId == it.series && (cache.season ?: -1) == it.season)
                it.copy(profile = key, payload = json.encodeToString(cache.copy(profileKey = key)))
        })
        // The restored snapshot is now the comparison source. A prior device-local
        // baseline, verdict, or in-flight refresh candidate set no longer describes it.
        dao.clearProviderBaselines(key, remote.type.name)
        dao.clearUpdateRow(key, remote.type.name)
        dao.clearRefreshCandidates(key, remote.type.name)
    }

    internal suspend fun mergeCheckpoint(profile: PortalProfile, checkpoint: CatalogCheckpoint) = db.withTransaction {
        val types = setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
        val id = SearchMetadataDocuments.anonymousProfileId(profile)
        require(checkpoint.version == 1 && checkpoint.profileId == id)
        require(checkpoint.snapshots.size == types.size && checkpoint.snapshots.map { it.type }.toSet() == types)
        require(checkpoint.snapshots.all { it.profileId == id && it.schemaVersion == 1 })
        checkpoint.snapshots.forEach { mergeSnapshot(profile, it) }
    }

    suspend fun restartScan(profile: PortalProfile, type: CatalogType): Long {
        migrate(profile.cacheKey(), type)
        return db.withTransaction {
            val key = profile.cacheKey()
            val generation = System.currentTimeMillis()
            dao.clearRefreshCandidates(key, type.name)
            dao.stageRefreshCandidates(key, type.name)
            dao.putRefreshCandidates(listOf(CatalogRefreshCandidateRow(
                profile = key,
                type = type.name,
                bucket = REFRESH_GENERATION_BUCKET,
                id = generation.toString()
            )))
            dao.putBuckets(dao.buckets(key, type.name).map {
                it.copy(page = 0, hasMore = true, observedAt = generation)
            })
            generation
        }
    }

    suspend fun snapshotPlan(profile: PortalProfile, type: CatalogType): CatalogSnapshotPlan {
        val key = profile.cacheKey()
        migrate(key, type)
        return db.withTransaction {
            CatalogSnapshotPlan(
                profileId = SearchMetadataDocuments.anonymousProfileId(profile),
                type = type,
                itemCount = dao.snapshotItemCount(key, type.name),
                episodeCount = if (type == CatalogType.SERIES) dao.snapshotEpisodeCount(key) else 0,
                buckets = dao.buckets(key, type.name).filterNot { it.bucket == SEARCH }.map { it.copy(profile = "") }
            )
        }
    }

    suspend fun snapshotPart(
        profile: PortalProfile,
        plan: CatalogSnapshotPlan,
        part: Int,
        itemsPerPart: Int,
        episodesPerPart: Int
    ): CatalogSnapshot {
        require(part >= 0 && itemsPerPart > 0 && episodesPerPart > 0)
        val key = profile.cacheKey()
        return db.withTransaction {
            CatalogSnapshot(
                profileId = plan.profileId,
                type = plan.type,
                items = dao.snapshotItemsPage(key, plan.type.name, itemsPerPart, part * itemsPerPart)
                    .map { it.copy(profile = "") },
                buckets = if (part == 0) plan.buckets else emptyList(),
                episodes = if (plan.type == CatalogType.SERIES)
                    dao.snapshotEpisodesPage(key, episodesPerPart, part * episodesPerPart).map { row ->
                        val cache = json.decodeFromString<EpisodeSeasonCache>(row.payload).copy(profileKey = "")
                        row.copy(profile = "", payload = json.encodeToString(cache))
                    }
                else emptyList()
            )
        }
    }

    /** Returns the first media type whose restored page cursors are incomplete. */
    suspend fun resumeScanIndex(profile: PortalProfile): Int {
        val key = profile.cacheKey()
        val types = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
        return types.indexOfFirst { type ->
            migrate(key, type)
            val providerBuckets = dao.buckets(key, type.name).filterNot { it.bucket == SEARCH }
            // The scanner uses the provider's aggregate "*" bucket whenever it
            // exists. Category rows are also stored for browsing and may remain
            // at page zero forever; they must not pull a restored resume cursor
            // back to Live TV after the aggregate Live TV scan completed.
            val scannedBuckets = providerBuckets.firstOrNull { it.bucket == "*" }
                ?.let(::listOf)
                ?: providerBuckets
            scannedBuckets.isEmpty() || scannedBuckets.any { it.page <= 0 || it.hasMore }
        }
    }

    suspend fun clear() = db.withTransaction {
        dao.clearItems(); dao.clearBuckets(); dao.clearEpisodes()
        dao.clearAllSearchRows()
        dao.clearProviderBaselines(); dao.clearUpdateRows()
        dao.clearRefreshCandidates()
    }
    companion object {
        private const val SEARCH = "@search"
        internal const val EMPTY_BUCKET = "@empty"
        private const val REFRESH_GENERATION_BUCKET = "@generation"
        private const val PAGE_POSITION_STRIDE = 1_000L
        private const val INDEX_REBUILD_BATCH = 1_000
    }
}

@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int = 1, val profileId: String, val type: CatalogType,
    val items: List<CatalogItemRow>, val buckets: List<CatalogBucketRow>, val episodes: List<CatalogEpisodeRow>
)

data class CatalogSnapshotPlan(
    val profileId: String,
    val type: CatalogType,
    val itemCount: Int,
    val episodeCount: Int,
    val buckets: List<CatalogBucketRow>
) {
    val recordCount: Int get() = itemCount + episodeCount
}

/** Union preserves discoveries from other devices; timestamps break conflicts per record, not per file. */
internal fun mergeCatalogSnapshots(local: CatalogSnapshot, remote: CatalogSnapshot): CatalogSnapshot {
    require(local.schemaVersion == remote.schemaVersion && local.profileId == remote.profileId && local.type == remote.type)
    val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun <T, K> merge(a: List<T>, b: List<T>, key: (T) -> K, time: (T) -> Long, tie: (T) -> String): List<T> =
        (a + b).groupBy(key).values.map { versions -> versions.maxWith(compareBy(time, tie)) }
    return local.copy(
        items = merge(local.items, remote.items, { it.bucket to it.id }, { it.observedAt }, { "${it.deleted}:${it.payload}" })
            .sortedWith(compareBy({ it.bucket }, { it.position }, { it.id })),
        // Page cursors are cumulative scan progress. A newer device-local reset at page 0
        // must not erase a farther cursor restored from another device.
        buckets = (local.buckets + remote.buckets).groupBy { it.bucket }.values.map { versions ->
            versions.maxWith(
                compareBy<CatalogBucketRow> { if (it.hasMore) 0 else 1 }
                    .thenBy { it.page }
                    .thenBy { it.observedAt }
                    .thenBy { it.toString() }
            )
        }.sortedBy { it.bucket },
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
