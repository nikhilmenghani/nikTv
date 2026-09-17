package com.nikhil.niktv.data

import android.content.Context
import android.util.Base64
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
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

/** Optionally encrypted logical database snapshots, one writer per file; imports merge instead of replacing Room. */
class CatalogBackupManager(context: Context) {
    private val app = context.applicationContext
    private val crypto = GitHubBackupManager(app)
    private val repository = CatalogRepository(app)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun uploadAll(): Int = BackupActivityLog.track(app, "IPTV catalog backup", success = {
        if (it == 0) "No changed catalog snapshots to upload." else "Uploaded $it catalog snapshots to GitHub."
    }) { uploadAllInternal() }

    private suspend fun uploadAllInternal(): Int = withContext(Dispatchers.IO) {
        check(CatalogPreferences.backupEnabled(app)) { "Catalog backup is disabled on this device" }
        val config = config()
        var uploaded = 0
        for (profile in ProfileStore(app).profiles.first()) {
            for (type in types) {
                currentCoroutineContext().ensureActive()
                while (CatalogPlaybackActivity.playing) kotlinx.coroutines.delay(1_000L)
                val snapshot = repository.snapshot(profile, type)
                if (snapshot.items.isEmpty() && snapshot.episodes.isEmpty()) continue
                val bytes = ByteArrayOutputStream().also { output ->
                    GZIPOutputStream(output).use { it.write(json.encodeToString(snapshot).toByteArray()) }
                }.toByteArray()
                val path = "catalog-v1/${snapshot.profileId}/${type.name.lowercase()}/${CatalogPreferences.deviceId(app)}.niktv"
                // Changing the backup password must rewrite even unchanged catalog data.
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val fingerprintKey = "${config.username}/${config.repository}/$path/${crypto.catalogKeyRevision()}/${if (config.passphrase.isBlank()) "plain-v1" else "encrypted-v1"}"
                if (CatalogPreferences.fingerprint(app, fingerprintKey) == fingerprint) continue
                val encrypted = encodePayload(Base64.encodeToString(bytes, Base64.NO_WRAP), config.passphrase)
                put(config, path, encrypted)
                CatalogPreferences.fingerprint(app, fingerprintKey, fingerprint)
                uploaded++
            }
        }
        CatalogPreferences.status(app, "Backed up $uploaded catalog snapshots · ${java.util.Date()}")
        uploaded
    }

    suspend fun restoreAll(): Int = BackupActivityLog.track(app, "IPTV catalog restore", success = {
        if (it == 0) "No matching catalog snapshots found for saved profiles." else "Merged $it snapshots. Reopen the profile to reload its catalog."
    }) { restoreAllInternal() }

    private suspend fun restoreAllInternal(): Int = withContext(Dispatchers.IO) {
        val config = config()
        var imported = 0
        for (profile in ProfileStore(app).profiles.first()) {
            for (type in types) {
                currentCoroutineContext().ensureActive()
                val id = SearchMetadataDocuments.anonymousProfileId(profile)
                val folder = "catalog-v1/$id/${type.name.lowercase()}"
                val listing = get(config, folder) ?: continue
                val files = JSONArray(listing)
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val path = file.optString("path")
                    require(path.startsWith("$folder/") && path.substringAfterLast('/').matches(Regex("[a-f0-9-]+\\.niktv")))
                    val encrypted = get(config, path, raw = true) ?: continue
                    val compressed = Base64.decode(decodePayload(encrypted, config.passphrase), Base64.NO_WRAP)
                    val decoded = GZIPInputStream(compressed.inputStream()).use { bounded(it, MAX_EXPANDED) }
                    val snapshot = json.decodeFromString<CatalogSnapshot>(decoded)
                    require(snapshot.type == type && snapshot.profileId == id && snapshot.schemaVersion == 1)
                    repository.mergeSnapshot(profile, snapshot)
                    imported++
                }
            }
        }
        CatalogPreferences.status(app, "Merged $imported catalog snapshots · ${java.util.Date()}")
        imported
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
            check(CatalogPreferences.backupEnabled(app)) { "Catalog backup was disabled" }
            val sha = get(config, path)?.let { JSONObject(it).optString("sha") }
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
        private const val MAX_DOWNLOAD = 20 * 1024 * 1024
        private const val MAX_EXPANDED = 80 * 1024 * 1024
    }
}
