package com.nikhil.niktv.data

import android.content.Context
import androidx.room.withTransaction
import android.util.Base64
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/** Device-only preferences are deliberately excluded from profile/settings backup restoration. */
object CatalogPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("catalog_device", Context.MODE_PRIVATE)
    fun backupEnabled(context: Context) = prefs(context).getBoolean("upload", false)
    fun preferLocal(context: Context) = prefs(context).getBoolean("prefer_local", true)
    fun setPreferLocal(context: Context, value: Boolean) { prefs(context).edit().putBoolean("prefer_local", value).apply() }
    fun setBackupEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("upload", enabled).apply() }
    fun deviceId(context: Context): String = prefs(context).getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs(context).edit().putString("device_id", it).apply()
    }
    fun status(context: Context) = prefs(context).getString("status", "No catalog backup performed on this device.").orEmpty()
    fun status(context: Context, value: String) { prefs(context).edit().putString("status", value).apply() }
    fun fingerprint(context: Context, key: String) = prefs(context).getString("fingerprint:$key", null)
    fun fingerprint(context: Context, key: String, value: String) { prefs(context).edit().putString("fingerprint:$key", value).apply() }
}

@Serializable
internal data class CatalogCheckpoint(
    val version: Int = 1,
    val profileId: String,
    val createdAt: Long,
    val scanCompletedAt: Long = 0,
    val snapshots: List<CatalogSnapshot>
)

@Serializable
internal data class CatalogSnapshotManifest(
    val version: Int = 1,
    val profileId: String,
    val type: CatalogType,
    val generatedAt: Long,
    val fingerprint: String,
    val parts: List<String>
)

data class CatalogCheckpointFile(val path: String, val timestamp: Long, val device: String)

/** Optionally encrypted logical database snapshots, one writer per file; imports merge instead of replacing Room. */
class CatalogBackupManager(context: Context) {
    private val app = context.applicationContext
    private val crypto = GitHubBackupManager(app)
    private val repository = CatalogRepository(app)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun uploadAll(): Int = BackupActivityLog.track(app, "IPTV catalog backup", success = {
        if (it == 0) "No changed catalog snapshots to upload." else "Uploaded $it catalog snapshots to GitHub."
    }) { uploadInternal(ProfileStore(app).profiles.first(), types) }

    suspend fun upload(profile: PortalProfile): Int = BackupActivityLog.track(app, "IPTV catalog backup · ${profile.name}", success = {
        if (it == 0) "No changed ${profile.name} snapshots to upload." else "Uploaded $it ${profile.name} snapshots to GitHub."
    }) { uploadInternal(listOf(profile), types) }

    suspend fun upload(profile: PortalProfile, type: CatalogType): Int =
        BackupActivityLog.track(app, "IPTV catalog backup · ${profile.name} · ${type.title}", success = {
            if (it == 0) "No changed ${type.title} snapshot to upload." else "Uploaded ${profile.name} ${type.title} to GitHub."
        }) { uploadInternal(listOf(profile), listOf(type)) }

    private suspend fun uploadInternal(profiles: List<PortalProfile>, mediaTypes: List<CatalogType>): Int = withContext(Dispatchers.IO) {
        CatalogOperations.check(app, CatalogOperations.BACKUP)
        check(CatalogPreferences.backupEnabled(app)) { "Catalog backup is disabled on this device" }
        val config = config()
        var uploaded = 0
        val failures = mutableListOf<String>()
        for ((profileIndex, profile) in profiles.withIndex()) {
            val profileId = CatalogScanPreferences.id(profile)
            val scanCompleteBefore = if (CatalogScanPreferences.cursor(app, profileId) == -1)
                CatalogScanPreferences.completed(app, profileId) else 0L
            val plans = mediaTypes.associateWith { repository.snapshotPlan(profile, it) }
            val checkpointEnabled = mediaTypes == types && plans.values.sumOf { it.recordCount } in 1..MAX_CHECKPOINT_RECORDS
            val checkpointSnapshots = mutableListOf<CatalogSnapshot>()
            for ((typeIndex, type) in mediaTypes.withIndex()) {
                currentCoroutineContext().ensureActive()
                CatalogOperations.check(app, CatalogOperations.BACKUP)
                while (CatalogPlaybackActivity.playing) {
                    CatalogOperations.check(app, CatalogOperations.BACKUP)
                    CatalogOperations.message(app, CatalogOperations.BACKUP, "Waiting for playback to finish; upload progress retained.")
                    kotlinx.coroutines.delay(1_000L)
                }
                CatalogOperations.message(app, CatalogOperations.BACKUP, "${profile.name} · ${type.title} · Reading Room snapshot")
                CatalogOperations.progress(app, CatalogOperations.BACKUP, CatalogOperationProgress(
                    phase = "Preparing snapshot", mediaType = type.title, category = profile.name,
                    categoryPosition = profileIndex + 1, categoryCount = profiles.size,
                    part = typeIndex, totalParts = mediaTypes.size
                ))
                val plan = requireNotNull(plans[type])
                if (plan.recordCount == 0) continue
                val smallSnapshot = if (plan.recordCount <= LEGACY_FINGERPRINT_MAX_RECORDS)
                    repository.snapshot(profile, type) else null
                if (checkpointEnabled) checkpointSnapshots += requireNotNull(smallSnapshot)
                try {
                    if (uploadSnapshot(config, profile, plan, smallSnapshot)) uploaded++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val label = "${profile.name} · ${type.title}"
                    failures += "$label: ${error.message ?: error.javaClass.simpleName}"
                    BackupActivityLog.record(app, "IPTV catalog backup · ${profile.name}", "Failed", "${type.title}: ${error.message ?: "Upload failed"}")
                }
                CatalogOperations.check(app, CatalogOperations.BACKUP)
            }
            val checkpointRecords = plans.values.sumOf { it.recordCount }
            if (checkpointEnabled) {
                CatalogOperations.check(app, CatalogOperations.BACKUP)
                CatalogOperations.message(app, CatalogOperations.BACKUP, "${profile.name} · Creating dated restore checkpoint")
                runCatching { uploadCheckpoint(config, profile, checkpointSnapshots, scanCompleteBefore) }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        BackupActivityLog.record(app, "Catalog checkpoint · ${profile.name}", "Skipped", error.message ?: "Checkpoint was too large; profile/type backups are complete.")
                    }
            } else if (mediaTypes == types && checkpointRecords > MAX_CHECKPOINT_RECORDS) {
                BackupActivityLog.record(app, "Catalog checkpoint · ${profile.name}", "Skipped",
                    "The complete catalog is stored in partitioned profile/type backups. A single dated checkpoint would be too large.")
            }
        }
        CatalogOperations.check(app, CatalogOperations.BACKUP)
        if (failures.isNotEmpty()) {
            CatalogPreferences.status(app, "Catalog backup partially completed · ${java.util.Date()} · ${failures.size} failed")
            error("${failures.size} catalog backup(s) failed after the remaining profiles and media types were attempted. ${failures.joinToString("; ")}")
        }
        CatalogPreferences.status(app, "Backed up $uploaded catalog snapshots · ${java.util.Date()}")
        CatalogOperations.message(app, CatalogOperations.BACKUP, "Complete · $uploaded changed snapshots uploaded. Unchanged completed files were skipped.")
        CatalogOperations.progress(app, CatalogOperations.BACKUP, CatalogOperationProgress(
            phase = "Complete", totalRecords = uploaded, part = 1, totalParts = 1
        ))
        uploaded
    }

    private suspend fun uploadSnapshot(
        config: GitHubBackupConfig,
        profile: PortalProfile,
        plan: CatalogSnapshotPlan,
        smallSnapshot: CatalogSnapshot?
    ): Boolean {
        val partCount = maxOf(1,
            (plan.itemCount + RECORDS_PER_PART - 1) / RECORDS_PER_PART,
            (plan.episodeCount + EPISODES_PER_PART - 1) / EPISODES_PER_PART)
        // Preserve the legacy fingerprint for small snapshots. This lets an existing
        // Live TV backup remain valid while large catalogs use bounded part hashing.
        val fingerprint = if (smallSnapshot != null) sha256(compressed(smallSnapshot)) else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(PARTITIONED_FINGERPRINT_VERSION.toByteArray())
            repeat(partCount) { index ->
                currentCoroutineContext().ensureActive()
                CatalogOperations.check(app, CatalogOperations.BACKUP)
                CatalogOperations.message(app, CatalogOperations.BACKUP,
                    "${profile.name} · ${plan.type.title} · Preparing part ${index + 1}/$partCount")
                CatalogOperations.progress(app, CatalogOperations.BACKUP, CatalogOperationProgress(
                    phase = "Preparing snapshot", mediaType = plan.type.title, category = profile.name,
                    part = index + 1, totalParts = partCount, totalRecords = plan.recordCount
                ))
                val bytes = compressed(repository.snapshotPart(
                    profile, plan, index, RECORDS_PER_PART, EPISODES_PER_PART))
                digest.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val device = CatalogPreferences.deviceId(app)
        val folder = "catalog-v2/${plan.profileId}/${plan.type.name.lowercase()}/$device"
        val manifestPath = "$folder/manifest.niktv"
        val fingerprintKey = "${config.username}/${config.repository}/$manifestPath/${crypto.catalogKeyRevision()}/${if (config.passphrase.isBlank()) "plain-v1" else "encrypted-v1"}"
        if (CatalogPreferences.fingerprint(app, fingerprintKey) == fingerprint) return false

        val partPaths = (0 until partCount).map { "$folder/part-${it.toString().padStart(5, '0')}.niktv" }
        repeat(partCount) { index ->
            CatalogOperations.check(app, CatalogOperations.BACKUP)
            CatalogOperations.message(app, CatalogOperations.BACKUP,
                "${profile.name} · ${plan.type.title} · Uploading part ${index + 1}/$partCount (${plan.recordCount} records total)")
            CatalogOperations.progress(app, CatalogOperations.BACKUP, CatalogOperationProgress(
                phase = "Uploading", mediaType = plan.type.title, category = profile.name,
                part = index + 1, totalParts = partCount, totalRecords = plan.recordCount
            ))
            val part = smallSnapshot?.let { splitSnapshotPart(it, index) }
                ?: repository.snapshotPart(profile, plan, index, RECORDS_PER_PART, EPISODES_PER_PART)
            put(config, partPaths[index], encodeSnapshot(part, config.passphrase))
        }
        val manifest = CatalogSnapshotManifest(profileId = plan.profileId, type = plan.type,
            generatedAt = System.currentTimeMillis(), fingerprint = fingerprint, parts = partPaths)
        put(config, manifestPath, encodePayload(Base64.encodeToString(compressed(manifest), Base64.NO_WRAP), config.passphrase))
        CatalogPreferences.fingerprint(app, fingerprintKey, fingerprint)
        BackupActivityLog.record(app, "IPTV catalog backup · ${profile.name}", "Completed",
            "${plan.type.title}: ${plan.recordCount} records saved in $partCount part(s).")
        return true
    }

    private fun splitSnapshotPart(snapshot: CatalogSnapshot, index: Int) = snapshot.copy(
        items = snapshot.items.drop(index * RECORDS_PER_PART).take(RECORDS_PER_PART),
        buckets = if (index == 0) snapshot.buckets else emptyList(),
        episodes = snapshot.episodes.drop(index * EPISODES_PER_PART).take(EPISODES_PER_PART)
    )

    private fun encodeSnapshot(snapshot: CatalogSnapshot, passphrase: String): String =
        encodePayload(Base64.encodeToString(compressed(snapshot), Base64.NO_WRAP), passphrase)

    private inline fun <reified T> compressed(value: T): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(json.encodeToString(value).toByteArray()) }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun restoreAll(): Int = BackupActivityLog.track(app, "IPTV catalog restore", success = {
        if (it == 0) "No matching catalog snapshots found for saved profiles." else "Merged $it snapshots. Reopen the profile to reload its catalog."
    }) { restoreInternal(ProfileStore(app).profiles.first(), types) }

    suspend fun restore(profile: PortalProfile): Int = BackupActivityLog.track(app, "IPTV catalog restore · ${profile.name}", success = {
        if (it == 0) "No matching ${profile.name} snapshots found." else "Merged $it ${profile.name} snapshots. Reopen the profile to reload its catalog."
    }) { restoreInternal(listOf(profile), types) }

    suspend fun restore(profile: PortalProfile, type: CatalogType): Int =
        BackupActivityLog.track(app, "IPTV catalog restore · ${profile.name} · ${type.title}", success = {
            if (it == 0) "No matching ${type.title} snapshot found." else "Merged ${profile.name} ${type.title}."
        }) { restoreInternal(listOf(profile), listOf(type)) }

    private suspend fun restoreInternal(profiles: List<PortalProfile>, mediaTypes: List<CatalogType>): Int = withContext(Dispatchers.IO) {
        val config = config()
        var imported = 0
        for ((profileIndex, profile) in profiles.withIndex()) {
            for ((typeIndex, type) in mediaTypes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val id = SearchMetadataDocuments.anonymousProfileId(profile)
                val chunked = restoreChunked(config, profile, type, id)
                if (chunked > 0) {
                    imported += chunked
                    continue
                }
                val folder = "catalog-v1/$id/${type.name.lowercase()}"
                CatalogOperations.message(app, "restore", "${profile.name} · ${type.title} · Finding GitHub snapshots")
                CatalogOperations.progress(app, CatalogOperations.RESTORE, CatalogOperationProgress(
                    phase = "Finding snapshots", mediaType = type.title, category = profile.name,
                    categoryPosition = profileIndex + 1, categoryCount = profiles.size
                ))
                val listing = get(config, folder) ?: continue
                val files = JSONArray(listing)
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val path = file.optString("path")
                    require(path.startsWith("$folder/") && path.substringAfterLast('/').matches(Regex("[a-f0-9-]+\\.niktv")))
                    CatalogOperations.message(app, "restore", "${profile.name} · ${type.title} · Downloading file ${i + 1}/${files.length()}")
                    CatalogOperations.progress(app, CatalogOperations.RESTORE, CatalogOperationProgress(
                        phase = "Downloading", mediaType = type.title, category = profile.name,
                        part = i + 1, totalParts = files.length()
                    ))
                    val encrypted = get(config, path, raw = true) ?: continue
                    val compressed = Base64.decode(decodePayload(encrypted, config.passphrase), Base64.NO_WRAP)
                    val decoded = GZIPInputStream(compressed.inputStream()).use { bounded(it, MAX_EXPANDED) }
                    val snapshot = json.decodeFromString<CatalogSnapshot>(decoded)
                    require(snapshot.type == type && snapshot.profileId == id && snapshot.schemaVersion == 1)
                    CatalogOperations.message(app, "restore", "${profile.name} · ${type.title} · Merging ${snapshot.items.size} Room records")
                    repository.mergeSnapshot(profile, snapshot)
                    imported++
                }
            }
            val id = CatalogScanPreferences.id(profile)
            val resumeIndex = repository.resumeScanIndex(profile)
            CatalogScanPreferences.restoredCursor(app, id, resumeIndex)
            CatalogScanPreferences.status(app, id, if (resumeIndex >= 0)
                "Restored catalog · Resume will continue with ${types[resumeIndex].title} from its last saved page."
            else "Restored catalog · All media types in the snapshot are complete.")
        }
        CatalogPreferences.status(app, "Merged $imported catalog snapshots · ${java.util.Date()}")
        CatalogOperations.message(app, "restore", "Complete · $imported snapshots merged into Room.")
        CatalogOperations.progress(app, CatalogOperations.RESTORE, CatalogOperationProgress(
            phase = "Complete", totalRecords = imported, part = 1, totalParts = 1
        ))
        imported
    }

    private suspend fun restoreChunked(config: GitHubBackupConfig, profile: PortalProfile, type: CatalogType, id: String): Int {
        val folder = "catalog-v2/$id/${type.name.lowercase()}"
        CatalogOperations.message(app, "restore", "${profile.name} · ${type.title} · Finding partitioned GitHub snapshots")
        val listing = get(config, folder) ?: return 0
        val devices = JSONArray(listing)
        var restored = 0
        for (i in 0 until devices.length()) {
            val deviceFolder = devices.getJSONObject(i).optString("path")
            if (!deviceFolder.startsWith("$folder/") || !deviceFolder.substringAfterLast('/').matches(Regex("[a-f0-9-]+"))) continue
            val manifestPath = "$deviceFolder/manifest.niktv"
            val content = get(config, manifestPath, raw = true) ?: continue
            val manifest = json.decodeFromString<CatalogSnapshotManifest>(decodeCompressed(content, config.passphrase))
            require(manifest.version == 1 && manifest.profileId == id && manifest.type == type)
            manifest.parts.forEachIndexed { index, path ->
                require(path.startsWith("$deviceFolder/part-") && path.endsWith(".niktv"))
                CatalogOperations.message(app, "restore", "${profile.name} · ${type.title} · Merging part ${index + 1}/${manifest.parts.size}")
                CatalogOperations.progress(app, CatalogOperations.RESTORE, CatalogOperationProgress(
                    phase = "Merging into local database", mediaType = type.title, category = profile.name,
                    part = index + 1, totalParts = manifest.parts.size
                ))
                val partContent = requireNotNull(get(config, path, raw = true)) { "Catalog backup part is missing." }
                val snapshot = json.decodeFromString<CatalogSnapshot>(decodeCompressed(partContent, config.passphrase))
                require(snapshot.type == type && snapshot.profileId == id && snapshot.schemaVersion == 1)
                repository.mergeSnapshot(profile, snapshot)
            }
            restored++
        }
        return restored
    }

    private fun decodeCompressed(content: String, passphrase: String): String {
        val compressed = Base64.decode(decodePayload(content, passphrase), Base64.NO_WRAP)
        return GZIPInputStream(compressed.inputStream()).use { bounded(it, MAX_EXPANDED) }
    }

    private suspend fun uploadCheckpoint(config: GitHubBackupConfig, profile: PortalProfile, snapshots: List<CatalogSnapshot>, scanCompleteBefore: Long) {
        val id = CatalogScanPreferences.id(profile)
        // Never advertise an in-progress refresh as a complete checkpoint.
        val completeAt = if (CatalogScanPreferences.cursor(app, id) == -1 &&
            CatalogScanPreferences.completed(app, id) == scanCompleteBefore) scanCompleteBefore else 0L
        val canonical = json.encodeToString(snapshots) + ":$completeAt"
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val key = "checkpoint:${config.username}/${config.repository}/$id/${crypto.catalogKeyRevision()}/${config.passphrase.isBlank()}"
        if (CatalogPreferences.fingerprint(app, key) == fingerprint) return
        val timestamp = System.currentTimeMillis()
        val checkpoint = CatalogCheckpoint(profileId = id, createdAt = timestamp, scanCompletedAt = completeAt, snapshots = snapshots)
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(json.encodeToString(checkpoint).toByteArray()) }
        }.toByteArray()
        val path = "catalog-checkpoints-v1/$id/$timestamp-${CatalogPreferences.deviceId(app)}.niktv"
        put(config, path, encodePayload(Base64.encodeToString(bytes, Base64.NO_WRAP), config.passphrase))
        CatalogPreferences.fingerprint(app, key, fingerprint)
        BackupActivityLog.record(app, "Catalog checkpoint · ${profile.name}", "Completed", "Created restore checkpoint at ${java.util.Date(timestamp)}.")
    }

    suspend fun checkpoints(profile: PortalProfile): List<CatalogCheckpointFile> = withContext(Dispatchers.IO) {
        val folder = "catalog-checkpoints-v1/${CatalogScanPreferences.id(profile)}"
        val listing = get(config(), folder) ?: return@withContext emptyList()
        val files = JSONArray(listing)
        (0 until files.length()).mapNotNull { index ->
            val file = files.getJSONObject(index)
            val path = file.optString("path")
            val match = Regex("([0-9]{13})-([a-f0-9-]+)\\.niktv").matchEntire(path.substringAfterLast('/'))
            if (match == null || path.substringBeforeLast('/') != folder) null
            else CatalogCheckpointFile(path, match.groupValues[1].toLong(), match.groupValues[2].take(8))
        }.sortedByDescending { it.timestamp }
    }

    suspend fun restoreCheckpoint(profile: PortalProfile, file: CatalogCheckpointFile): Int =
        BackupActivityLog.track(app, "Catalog checkpoint restore · ${profile.name}", success = { "Merged $it media catalogs. Reopen the profile to refresh the dashboard." }) {
            withContext(Dispatchers.IO) {
                val id = CatalogScanPreferences.id(profile)
                require(file.path.substringBeforeLast('/') == "catalog-checkpoints-v1/$id")
                require(Regex("[0-9]{13}-[a-f0-9-]+\\.niktv").matches(file.path.substringAfterLast('/')))
                val config = config()
                CatalogOperations.message(app, "restore", "${profile.name} · Downloading dated checkpoint")
                val content = requireNotNull(get(config, file.path, raw = true)) { "Checkpoint no longer exists." }
                val compressed = Base64.decode(decodePayload(content, config.passphrase), Base64.NO_WRAP)
                val decoded = GZIPInputStream(compressed.inputStream()).use { bounded(it, MAX_EXPANDED) }
                val checkpoint = json.decodeFromString<CatalogCheckpoint>(decoded)
                CatalogOperations.message(app, "restore", "${profile.name} · Validating and merging checkpoint into Room")
                repository.mergeCheckpoint(profile, checkpoint)
                CatalogOperations.message(app, "restore", "Complete · ${profile.name} checkpoint merged.")
                CatalogScanPreferences.completed(app, id, maxOf(CatalogScanPreferences.completed(app, id), checkpoint.scanCompletedAt))
                CatalogScanPreferences.restoredCursor(app, id, repository.resumeScanIndex(profile))
                CatalogScanPreferences.status(app, id, "Restored checkpoint · ${java.util.Date(checkpoint.createdAt)}" +
                    if (checkpoint.scanCompletedAt > 0) " · full catalog scan included." else " · partial catalog; scan to complete coverage.")
                checkpoint.snapshots.size
            }
        }

    internal fun encodePayload(compressedBase64: String, passphrase: String): String =
        if (passphrase.isBlank()) JSONObject()
            .put("catalogContainerVersion", 1)
            .put("encoding", "gzip-base64")
            .put("payload", compressedBase64)
            .toString()
        else crypto.encryptBackup(compressedBase64, passphrase)

    internal fun decodePayload(content: String, passphrase: String): String {
        val envelope = JSONObject(content)
        if (!envelope.has("catalogContainerVersion")) {
            // Preserve support for existing encrypted catalog snapshots.
            return crypto.decryptCatalogPayload(content, passphrase)
        }
        require(envelope.optInt("catalogContainerVersion", -1) == 1 &&
            envelope.optString("encoding") == "gzip-base64") { "Unsupported catalog backup format." }
        return envelope.getString("payload").also { require(it.isNotBlank()) { "Catalog backup payload is empty." } }
    }

    private fun config(): GitHubBackupConfig = crypto.loadConfig().also {
        require(it.backupMode == BackupMode.GITHUB && it.token.isNotBlank()) { "Configure GitHub backup first." }
        require(it.username.matches(Regex("[A-Za-z0-9-]+")) && it.repository.matches(Regex("[A-Za-z0-9_.-]+")))
        require(it.passphrase.isBlank() || it.passphrase.length >= 12) { "Backup password must be blank or at least 12 characters." }
    }
    private fun request(config: GitHubBackupConfig, path: String, raw: Boolean = false) = Request.Builder()
        .url("https://api.github.com/repos/${config.username}/${config.repository}/contents/$path")
        .header("Authorization", "Bearer ${config.token}")
        .header("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        .header("Cache-Control", "no-cache")

    private fun get(config: GitHubBackupConfig, path: String, raw: Boolean = false): String? =
        http.newCall(request(config, path, raw).build()).execute().use {
            if (it.code == 404) return@use null
            check(it.isSuccessful) { "Catalog download failed (GitHub ${it.code})" }
            bounded(requireNotNull(it.body).byteStream(), MAX_DOWNLOAD)
        }

    private suspend fun put(config: GitHubBackupConfig, path: String, encrypted: String) {
        require(encrypted.toByteArray().size <= MAX_DOWNLOAD) { "Catalog snapshot exceeds the backup size limit." }
        repeat(4) {
            currentCoroutineContext().ensureActive()
            CatalogOperations.check(app, CatalogOperations.BACKUP)
            kotlinx.coroutines.delay(2_000L)
            CatalogOperations.check(app, CatalogOperations.BACKUP)
            check(CatalogPreferences.backupEnabled(app)) { "Catalog backup was disabled" }
            val sha = get(config, path)?.let { JSONObject(it).optString("sha") }
            CatalogOperations.check(app, CatalogOperations.BACKUP)
            val body = JSONObject().put("message", "Update NikTV catalog snapshot")
                .put("content", Base64.encodeToString(encrypted.toByteArray(), Base64.NO_WRAP))
                .apply { if (!sha.isNullOrBlank()) put("sha", sha) }
            http.newCall(request(config, path).put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                if (response.isSuccessful) return
                check(response.code == 409 || response.code == 422) { "Catalog upload failed (GitHub ${response.code})" }
            }
            kotlinx.coroutines.delay(500L)
        }
        error("Catalog backup changed concurrently; retry later.")
    }

    private fun bounded(input: java.io.InputStream, limit: Int): String = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            require(output.size() + n <= limit) { "Catalog backup exceeds the size limit." }
            output.write(buffer, 0, n)
        }
        output.toString("UTF-8")
    }
    companion object {
        private val types = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
        private const val RECORDS_PER_PART = 2_000
        private const val EPISODES_PER_PART = 500
        private const val MAX_CHECKPOINT_RECORDS = 10_000
        private const val LEGACY_FINGERPRINT_MAX_RECORDS = 10_000
        private const val PARTITIONED_FINGERPRINT_VERSION = "partitioned-snapshot-v1\n"
        private const val MAX_DOWNLOAD = 20 * 1024 * 1024
        private const val MAX_EXPANDED = 80 * 1024 * 1024
    }
}
