package com.nikhil.niktv.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class AccountDocument(
    val application: String = "niktv",
    val schemaVersion: Int = 1,
    val accountId: String,
    val records: List<SyncRecord> = emptyList(),
    val profileFiles: Map<String, String> = emptyMap(),
    val profileId: String? = null,
)

data class SyncIdentity(val accountId: String, val login: String)
data class SyncRepository(val owner: String, val branch: String, val url: String)
data class RemoteAccount(val document: AccountDocument, val sha: String?, val needsUpgrade: Boolean = false)

/** Bounded, account-isolated in-memory cache. Hashes are checked against the index
 * from a pinned commit on every run; process death simply causes a cold download. */
internal class ProfileSyncCache(private val maxBytes: Int = 8 * 1024 * 1024) {
    private data class Entry(val sha: String, val document: AccountDocument, val bytes: Int)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0
    @Synchronized fun get(account: String, profile: String, sha: String): AccountDocument? =
        entries["$account:$profile"]?.takeIf { it.sha == sha }?.document
    @Synchronized fun put(account: String, profile: String, sha: String, document: AccountDocument, size: Int) {
        val key = "$account:$profile"
        entries.remove(key)?.let { bytes -= it.bytes }
        if (size > maxBytes) return
        entries[key] = Entry(sha, document, size); bytes += size
        while (bytes > maxBytes) {
            val first = entries.entries.iterator().next()
            bytes -= first.value.bytes; entries.remove(first.key)
        }
    }
}

class SyncFailure(
    override val message: String,
    val reconnect: Boolean = false,
    val retryable: Boolean = false,
    val retryAfterMillis: Long = 0,
    val conflict: Boolean = false,
) : IOException(message)

/** Only the explicitly entered account token reaches this transport. Never logs response bodies. */
internal class GitHubSyncTransport(
    private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build(),
    private val pauseWrites: Boolean = true,
    commitMessage: String = DEFAULT_SYNC_COMMIT_MESSAGE,
    private val profileCache: ProfileSyncCache = ProfileSyncCache(),
) {
    private val commitMessage = sanitizeSyncCommitMessage(commitMessage)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastWriteAt = 0L
    private var fetchedHead: String? = null
    private var fetchedIndex: AccountDocument? = null
    private var fetchedProfiles: Map<String, AccountDocument> = emptyMap()

    suspend fun identity(): SyncIdentity = withContext(Dispatchers.IO) {
        val user = call("GET", "/user")!!
        SyncIdentity(user.getLong("id").toString(), user.getString("login"))
    }

    suspend fun bootstrap(identity: SyncIdentity): SyncRepository = withContext(Dispatchers.IO) {
        val base = "/repos/${identity.login}/niktv-data"
        var repo = call("GET", base, missing = true)
        val created = repo == null
        if (repo == null) {
            repo = try {
                call("POST", "/user/repos", JSONObject().put("name", "niktv-data").put("private", true)
                    .put("auto_init", true).put("description", BOOTSTRAP_MARKER))!!
            } catch (failure: SyncFailure) {
                // Another device can create it after our GET. Revalidate instead of claiming ownership.
                if (failure.reconnect) throw failure
                call("GET", base, missing = true) ?: throw failure
            }
        }
        require(repo.getBoolean("private")) { "niktv-data must be private. Change its visibility in GitHub before connecting." }
        val repository = SyncRepository(identity.login, repo.getString("default_branch"), repo.getString("html_url"))
        val manifest = file(repository, "manifest.json")
        if (manifest != null) {
            validateManifest(JSONObject(manifest.first), identity.accountId)
        } else {
            val root = call("GET", "$base/git/trees/${encode(repository.branch)}", missing = true)
            val tree = root?.optJSONArray("tree")
            val empty = tree == null || tree.length() == 0
            val resumable = repo.optString("description") == BOOTSTRAP_MARKER &&
                tree != null && (0 until tree.length()).all { tree.getJSONObject(it).optString("path") == "README.md" }
            require(created || empty || resumable) {
                "Existing niktv-data has no NikTV application marker. Rename that repository or choose a token for the correct account. Its contents were preserved."
            }
            val marker = JSONObject().put("application", "niktv").put("schemaVersion", 2)
                .put("accountId", identity.accountId).put("dataFile", "account.json")
            try { put(repository, "manifest.json", marker.toString(2), null, initializeEmptyRepository = empty) }
            catch (failure: SyncFailure) {
                if (!failure.conflict) throw failure
                val concurrent = file(repository, "manifest.json") ?: throw failure
                validateManifest(JSONObject(concurrent.first), identity.accountId)
            }
            if (file(repository, "README.md") == null) put(repository, "README.md",
                "# NikTV private account data\n\nVersion 2: account index and profiles/<UUID>/account.json. No authentication tokens, portal credentials, streaming URLs or media files belong here.\n", null)
        }
        repository
    }

    suspend fun checkWrite(repository: SyncRepository) {
        // Creating an unreferenced constant blob proves token Contents write without changing user files.
        call("POST", base(repository) + "/git/blobs", JSONObject().put("content", "NikTV sync access check v1\n")
            .put("encoding", "utf-8"))
    }

    suspend fun fetch(repository: SyncRepository, accountId: String): RemoteAccount {
        val head = call("GET", base(repository) + "/git/ref/heads/${encode(repository.branch)}")!!
            .getJSONObject("object").getString("sha")
        val manifest = file(repository, "manifest.json", head) ?: throw SyncFailure("NikTV manifest is missing. Reconnect after restoring it in GitHub.")
        validateManifest(JSONObject(manifest.first), accountId)
        val content = file(repository, "account.json", head)
        val index = content?.let { decodeDocument(it.first, accountId) } ?: AccountDocument(accountId = accountId)
        val profiles = mutableMapOf<String, AccountDocument>()
        if (index.schemaVersion == 2) {
            require(index.profileId == null && index.records.all { it.profileId.isBlank() || it.collection == "profile" }) {
                "Invalid account index. Remote data was not overwritten."
            }
            for ((id, sha) in index.profileFiles) {
                require(UUID.fromString(id).toString() == id && SHA_PATTERN.matches(sha)) { "Invalid profile file identity." }
                val document = profileCache.get(accountId, id, sha) ?: run {
                    val data = file(repository, "profiles/$id/account.json", head)
                        ?: throw SyncFailure("A profile data file is missing. Restore it in GitHub before retrying; local data is preserved.")
                    require(data.second == sha) { "Profile file hash does not match the account index. Nothing was overwritten." }
                    decodeDocument(data.first, accountId).also { profile ->
                        require(profile.schemaVersion == 2 && profile.profileId == id && profile.profileFiles.isEmpty() &&
                            profile.records.all { it.profileId == id && it.collection != "profile" }) { "Profile data identity mismatch." }
                        profileCache.put(accountId, id, sha, profile, data.first.toByteArray(Charsets.UTF_8).size)
                    }
                }
                profiles[id] = document
            }
        }
        fetchedHead = head; fetchedIndex = index; fetchedProfiles = profiles
        return RemoteAccount(index.copy(records = index.records + profiles.values.flatMap { it.records }), head,
            needsUpgrade = content == null || index.schemaVersion == 1)
    }

    suspend fun upload(repository: SyncRepository, document: AccountDocument, sha: String?) {
        val head = fetchedHead
        require(head != null && head == sha && fetchedIndex?.accountId == document.accountId) { "Fetch account data before uploading." }
        val changes = JSONArray()
        val files = mutableMapOf<String, String>()
        val uploaded = mutableMapOf<String, Pair<AccountDocument, String>>()
        require(document.records.all { it.accountId == document.accountId }) { "Local records belong to another account." }
        val grouped = document.records.filter { it.profileId.isNotBlank() && it.collection != "profile" }.groupBy { it.profileId }
        val profileIds = (grouped.keys + document.records.filter { it.collection == "profile" }.map { it.recordId }).toSortedSet()
        profileIds.forEach { id ->
                val records = grouped[id].orEmpty()
                require(UUID.fromString(id).toString() == id) { "Invalid profile UUID. Nothing was overwritten." }
                val profile = AccountDocument(schemaVersion = 2, accountId = document.accountId,
                    records = records.sortedWith(RECORD_ORDER), profileId = id)
                val old = fetchedProfiles[id]
                if (old != null && old.records.toSet() == records.toSet()) {
                    files[id] = fetchedIndex!!.profileFiles.getValue(id)
                } else {
                    val content = encodeDocument(profile)
                    files[id] = gitBlobSha(content)
                    changes.put(treeContent("profiles/$id/account.json", content))
                    uploaded[id] = profile to content
                }
            }
        // Profile identities/deletions live in the small index so the chooser never
        // needs to download viewing data just to learn who exists.
        val index = AccountDocument(schemaVersion = 2, accountId = document.accountId,
            records = document.records.filter { it.profileId.isBlank() || it.collection == "profile" }.sortedWith(RECORD_ORDER),
            profileFiles = files)
        changes.put(treeContent("account.json", encodeDocument(index)))
        val marker = JSONObject().put("application", "niktv").put("schemaVersion", 2)
            .put("accountId", document.accountId).put("dataFile", "account.json").put("profileLayout", "profiles/<uuid>/account.json")
        if (fetchedIndex!!.schemaVersion == 1) changes.put(treeContent("manifest.json", marker.toString(2)))
        val commit = call("GET", base(repository) + "/git/commits/$head")!!
        val tree = call("POST", base(repository) + "/git/trees", JSONObject()
            .put("base_tree", commit.getJSONObject("tree").getString("sha")).put("tree", changes))!!
        val created = call("POST", base(repository) + "/git/commits", JSONObject().put("message", commitMessage)
            .put("tree", tree.getString("sha")).put("parents", JSONArray().put(head)))!!
        // Fast-forward only: a sibling commit from another device causes a bounded
        // fetch/merge retry. Until this succeeds none of these files are visible.
        call("PATCH", base(repository) + "/git/refs/heads/${encode(repository.branch)}",
            JSONObject().put("sha", created.getString("sha")).put("force", false))
        uploaded.forEach { (id, value) -> profileCache.put(document.accountId, id, files.getValue(id),
            value.first, value.second.toByteArray(Charsets.UTF_8).size) }
    }

    private fun decodeDocument(content: String, accountId: String): AccountDocument {
        validateManifest(JSONObject(content), accountId)
        val document = try { json.decodeFromString<AccountDocument>(content) }
            catch (_: Exception) { throw SyncFailure("Account data is invalid. Restore a known good revision in GitHub before retrying; local data is preserved.") }
        require(document.records.all { it.accountId == accountId }) { "Remote records belong to another account. Local data is preserved." }
        return document
    }

    private fun encodeDocument(document: AccountDocument): String = json.encodeToString(document).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Profile data exceeds 16 MiB. Nothing was overwritten." }
    }
    private fun treeContent(path: String, content: String) = JSONObject().put("path", path)
        .put("mode", "100644").put("type", "blob").put("content", content)

    private fun validateManifest(value: JSONObject, accountId: String) {
        require(value.optString("application") == "niktv") { "This repository is not NikTV account data. It was not modified." }
        require(value.optInt("schemaVersion", -1) in 1..2) { "Unsupported account schema. Update NikTV before syncing. Remote data was not overwritten." }
        require(value.optString("accountId") == accountId) { "NikTV repository account identity mismatch. Data was not modified." }
    }

    private suspend fun file(repository: SyncRepository, path: String, ref: String = repository.branch): Pair<String, String>? {
        val endpoint = base(repository) + "/contents/$path?ref=${encode(ref)}"
        val value = call("GET", endpoint, missing = true) ?: return null
        require(value.optLong("size") <= MAX_BYTES) { "Account data exceeds the supported 16 MiB size. Nothing was overwritten." }
        val content = if (value.optString("encoding") == "base64") {
            String(Base64.decode(value.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
        } else {
            withContext(Dispatchers.IO) {
                http.newCall(request(endpoint).header("Accept", "application/vnd.github.raw+json").build()).readCancellable { response ->
                    if (!response.isSuccessful) throw failure(response.code, response.header("Retry-After"), response.header("X-RateLimit-Remaining"), response.header("X-RateLimit-Reset"), endpoint)
                    val bytes = response.body.byteStream().use { readBounded(it, MAX_BYTES + 1) }
                    require(bytes.size <= MAX_BYTES) { "Account data is too large. Nothing was overwritten." }
                    bytes.toString(Charsets.UTF_8)
                }
            }
        }
        return content to value.getString("sha")
    }

    private suspend fun put(repository: SyncRepository, path: String, content: String, sha: String?, initializeEmptyRepository: Boolean = false) {
        require(content.toByteArray().size <= MAX_BYTES) { "Account data exceeds 16 MiB. Export a backup before reducing history." }
        val payload = JSONObject().put("message", commitMessage)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!initializeEmptyRepository) payload.put("branch", repository.branch)
        if (sha != null) payload.put("sha", sha)
        call("PUT", base(repository) + "/contents/$path", payload)
    }

    private suspend fun call(method: String, path: String, payload: JSONObject? = null, missing: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        if (method != "GET" && pauseWrites) {
            val wait = 1_000 - (android.os.SystemClock.elapsedRealtime() - lastWriteAt)
            if (wait > 0) delay(wait)
            lastWriteAt = android.os.SystemClock.elapsedRealtime()
        }
        val builder = request(path)
        if (payload != null) builder.method(method, payload.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).readCancellable { response ->
            if (missing && response.code == 404) return@readCancellable null
            if (!response.isSuccessful) throw failure(response.code, response.header("Retry-After"), response.header("X-RateLimit-Remaining"), response.header("X-RateLimit-Reset"), path)
            val bytes = response.body.byteStream().use { readBounded(it, MAX_BYTES * 2) }
            require(bytes.size < MAX_BYTES * 2) { "GitHub response is too large. Nothing was overwritten." }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun request(path: String) = Request.Builder().url("https://api.github.com$path")
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2026-03-10").header("User-Agent", "NikTV-account-sync")

    private fun failure(code: Int, retryAfter: String?, remaining: String?, reset: String?, path: String): SyncFailure {
        if (code == 401) return SyncFailure("GitHub token is invalid or revoked. Enter a replacement token; your local data is preserved.", reconnect = true)
        if (code == 429 || (code == 403 && (remaining == "0" || retryAfter != null))) {
            val wait = retryAfter?.toLongOrNull()?.times(1_000)
                ?: reset?.toLongOrNull()?.times(1_000)?.minus(System.currentTimeMillis()) ?: 60_000
            return SyncFailure("GitHub rate limit reached. Sync will retry after the limit resets.", retryable = true, retryAfterMillis = wait.coerceAtLeast(60_000))
        }
        if (code == 409 || code == 422 && (path.contains("/contents/") || path.contains("/git/refs/"))) return SyncFailure("Another device updated the account. Fetching and merging again.", conflict = true, retryable = true)
        if (code == 403 || code == 404) {
            val capability = if (path == "/user/repos") "private repository creation (classic repo scope, or fine-grained Administration / Repository creation write)"
                else "Contents read and write for your private niktv-data repository (and repository access for this token)"
            return SyncFailure("GitHub denied access (HTTP $code). Grant $capability, then reconnect.", reconnect = true)
        }
        return SyncFailure("GitHub request failed (HTTP $code). Your local changes are queued. Retry sync.", retryable = code >= 500)
    }

    private fun base(repository: SyncRepository) = "/repos/${repository.owner}/niktv-data"
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    companion object {
        private val RECORD_ORDER = compareBy<SyncRecord>({ it.profileId }, { it.collection }, { it.recordId })
        private val SHA_PATTERN = Regex("[a-f0-9]{40}")
        private const val BOOTSTRAP_MARKER = "NikTV application data (niktv-sync-v1)"
        private const val MAX_BYTES = 16 * 1024 * 1024
    }
}

internal fun gitBlobSha(content: String): String {
    val bytes = content.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
