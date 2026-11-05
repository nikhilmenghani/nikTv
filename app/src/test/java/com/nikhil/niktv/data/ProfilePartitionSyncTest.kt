package com.nikhil.niktv.data

import android.app.Application
import android.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ProfilePartitionSyncTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private val repository = SyncRepository("owner", "main", "unused")
    private val json = Json { encodeDefaults = true }
    private fun record(profile: String, revision: Long = 1) = SyncRecord("77", profile, "progress", "movie",
        "{\"position\":$revision}", revision, "device", "$profile:$revision")

    /** Git object creation has no visible effect until the fast-forward-only ref update. */
    private inner class Server(initial: AccountDocument) {
        var head = "head0"
        var files = mutableMapOf("manifest.json" to """{"application":"niktv","schemaVersion":1,"accountId":"77"}""",
            "account.json" to json.encodeToString(initial), "unrelated.txt" to "preserve")
        private val snapshots = mutableMapOf(head to files.toMap())
        private val trees = mutableMapOf<String, Map<String, String>>()
        private val commits = mutableMapOf<String, Pair<String, Map<String, String>>>()
        val reads = mutableListOf<String>()
        val changedPaths = mutableListOf<List<String>>()
        var rejectNextPublish = false
        var publications = 0
        private var sequence = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath.substringAfter("/niktv-data")
            val body = request.body?.let { b -> val sink = okio.Buffer(); b.writeTo(sink); JSONObject(sink.readUtf8()) }
            var code = 200
            val response = when {
                request.method == "GET" && path == "/git/ref/heads/main" -> JSONObject().put("object", JSONObject().put("sha", head))
                request.method == "GET" && path.startsWith("/contents/") -> {
                    val name = path.removePrefix("/contents/"); reads += name
                    val source = snapshots[request.url.queryParameter("ref")] ?: files
                    val content = source[name]
                    if (content == null) { code = 404; JSONObject() }
                    else JSONObject().put("encoding", "base64").put("sha", gitBlobSha(content))
                        .put("size", content.toByteArray().size).put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                }
                request.method == "GET" && path.startsWith("/git/commits/") ->
                    JSONObject().put("tree", JSONObject().put("sha", path.substringAfterLast('/')))
                request.method == "POST" && path == "/git/trees" -> {
                    val tree = body!!.getJSONArray("tree")
                    val next = snapshots.getValue(body.getString("base_tree")).toMutableMap()
                    val paths = (0 until tree.length()).map { i ->
                        val item = tree.getJSONObject(i); val name = item.getString("path")
                        next[name] = item.getString("content"); name
                    }
                    changedPaths += paths
                    val id = "tree${++sequence}"; trees[id] = next
                    JSONObject().put("sha", id)
                }
                request.method == "POST" && path == "/git/commits" -> {
                    val id = "commit${++sequence}"
                    commits[id] = body!!.getJSONArray("parents").getString(0) to trees.getValue(body.getString("tree"))
                    JSONObject().put("sha", id)
                }
                request.method == "PATCH" && path == "/git/refs/heads/main" -> {
                    assertFalse(body!!.getBoolean("force"))
                    val next = commits.getValue(body.getString("sha"))
                    if (rejectNextPublish || next.first != head) {
                        rejectNextPublish = false; code = 422; JSONObject()
                    } else {
                        head = body.getString("sha"); files = next.second.toMutableMap(); snapshots[head] = files.toMap()
                        publications++; JSONObject().put("object", JSONObject().put("sha", head))
                    }
                }
                else -> error("Unexpected ${request.method} $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(response.toString().toResponseBody()).build()
        }.build()
        fun transport(cache: ProfileSyncCache = ProfileSyncCache()) = GitHubSyncTransport("fixture", client,
            pauseWrites = false, profileCache = cache)
        fun externalEdit(edit: (MutableMap<String, String>) -> Unit) {
            edit(files); head = "external${++sequence}"; snapshots[head] = files.toMap()
        }
    }

    @Test fun migrationPublishesIndexAndProfilesAtomicallyAndPreservesOtherFiles() = runBlocking {
        val old = AccountDocument(accountId = "77", records = listOf(record(first), record(second)))
        val server = Server(old)
        val api = server.transport()
        val downloaded = api.fetch(repository, "77")
        assertTrue(downloaded.needsUpgrade)
        server.rejectNextPublish = true
        try { api.upload(repository, downloaded.document, downloaded.sha); fail("Expected conflict") }
        catch (error: SyncFailure) { assertTrue(error.conflict) }
        assertEquals(1, JSONObject(server.files.getValue("account.json")).getInt("schemaVersion"))
        assertFalse(server.files.keys.any { it.startsWith("profiles/") })
        val retry = api.fetch(repository, "77")
        api.upload(repository, retry.document, retry.sha)
        assertEquals(1, server.publications)
        assertEquals("preserve", server.files["unrelated.txt"])
        assertEquals(2, JSONObject(server.files.getValue("manifest.json")).getInt("schemaVersion"))
        assertEquals(2, JSONObject(server.files.getValue("account.json")).getJSONObject("profileFiles").length())
        assertTrue(JSONObject(server.files.getValue("account.json")).getJSONArray("records").length() == 0)
        val restored = server.transport().fetch(repository, "77")
        assertFalse(restored.needsUpgrade)
        assertEquals(old.records.toSet(), restored.document.records.toSet())
    }

    @Test fun warmSyncSkipsUnchangedProfilesAndWritesOnlyChangedProfile() = runBlocking {
        val old = AccountDocument(accountId = "77", records = listOf(record(first), record(second)))
        val server = Server(old)
        val cache = ProfileSyncCache()
        var api = server.transport(cache)
        val legacy = api.fetch(repository, "77")
        api.upload(repository, legacy.document, legacy.sha)
        server.reads.clear()
        api = server.transport(cache) // Like the manager's new transport on each interval.
        val remote = api.fetch(repository, "77")
        assertFalse(server.reads.any { it.startsWith("profiles/") })
        val changed = remote.document.copy(records = listOf(record(first, 2), record(second)))
        api.upload(repository, changed, remote.sha)
        assertEquals(setOf("account.json", "profiles/$first/account.json"), server.changedPaths.last().toSet())
        assertEquals(listOf(record(second)), json.decodeFromString<AccountDocument>(server.files.getValue("profiles/$second/account.json")).records)
        // Another device fetches only the changed profile, retaining its cached second person.
        val otherCache = ProfileSyncCache()
        val initial = server.transport(otherCache).fetch(repository, "77")
        assertEquals(2, initial.document.records.size)
        val current = api.fetch(repository, "77")
        api.upload(repository, current.document.copy(records = listOf(record(first, 3), record(second))), current.sha)
        server.reads.clear()
        server.transport(otherCache).fetch(repository, "77")
        assertEquals(listOf("profiles/$first/account.json"), server.reads.filter { it.startsWith("profiles/") })
    }

    @Test fun staleDeviceCannotReplaceOtherProfilesWithItsOldSnapshot() = runBlocking {
        val server = Server(AccountDocument(accountId = "77", records = listOf(record(first), record(second))))
        val a = server.transport(); val migration = a.fetch(repository, "77"); a.upload(repository, migration.document, migration.sha)
        val b = server.transport()
        val aRead = a.fetch(repository, "77"); val bRead = b.fetch(repository, "77")
        a.upload(repository, aRead.document.copy(records = listOf(record(first, 2), record(second))), aRead.sha)
        try { b.upload(repository, bRead.document.copy(records = listOf(record(first), record(second, 2))), bRead.sha); fail("Stale upload accepted") }
        catch (error: SyncFailure) { assertTrue(error.conflict) }
        val refreshed = b.fetch(repository, "77")
        b.upload(repository, refreshed.document.copy(records = listOf(record(first, 2), record(second, 2))), refreshed.sha)
        assertEquals(setOf(record(first, 2), record(second, 2)), server.transport().fetch(repository, "77").document.records.toSet())
    }

    @Test fun cacheNeverCrossesAccountBoundariesAndEvictsOversizedEntries() {
        val cache = ProfileSyncCache(maxBytes = 10)
        val document = AccountDocument(accountId = "77", records = listOf(record(first)))
        cache.put("77", first, "sha", document, 5)
        assertNull(cache.get("88", first, "sha"))
        assertNull(cache.get("77", first, "different"))
        cache.put("77", second, "sha2", document, 8)
        assertNull(cache.get("77", first, "sha"))
        cache.put("77", second, "sha3", document, 11)
        assertNull(cache.get("77", second, "sha3"))
    }

    @Test fun emptyProfileGetsItsOwnFileAndIdentityRemainsInIndex() = runBlocking {
        val identity = SyncRecord("77", first, "profile", first, "{}", 1, "device", "new-person")
        val server = Server(AccountDocument(accountId = "77", records = listOf(identity)))
        val api = server.transport(); val read = api.fetch(repository, "77")
        api.upload(repository, read.document, read.sha)
        val index = json.decodeFromString<AccountDocument>(server.files.getValue("account.json"))
        assertEquals(listOf(identity), index.records)
        val profile = json.decodeFromString<AccountDocument>(server.files.getValue("profiles/$first/account.json"))
        assertEquals(first, profile.profileId)
        assertTrue(profile.records.isEmpty())
        assertEquals(listOf(identity), server.transport().fetch(repository, "77").document.records)
    }

    @Test fun missingOrNewerProfileFileFailsBeforePublishingAnything() = runBlocking {
        val server = Server(AccountDocument(accountId = "77", records = listOf(record(first))))
        val api = server.transport(); val read = api.fetch(repository, "77"); api.upload(repository, read.document, read.sha)
        val original = server.files.getValue("profiles/$first/account.json")
        server.externalEdit { it.remove("profiles/$first/account.json") }
        try { server.transport().fetch(repository, "77"); fail("Missing profile accepted") }
        catch (error: SyncFailure) { assertTrue(error.message.contains("missing")) }
        server.externalEdit { files ->
            val newer = JSONObject(original).put("schemaVersion", 3).toString()
            files["profiles/$first/account.json"] = newer
            files["account.json"] = JSONObject(files.getValue("account.json")).apply {
                getJSONObject("profileFiles").put(first, gitBlobSha(newer))
            }.toString()
        }
        try { server.transport().fetch(repository, "77"); fail("Newer profile schema accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Update NikTV")) }
        assertEquals(1, server.publications)
    }
}
