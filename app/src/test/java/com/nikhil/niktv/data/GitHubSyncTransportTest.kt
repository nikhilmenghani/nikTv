package com.nikhil.niktv.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class GitHubSyncTransportTest {
    @Test fun passwordCheckFetchesOnlyTheCurrentRosterWithoutProfileHistoryOrWrites() = runBlocking {
        val verifier = ProfilePin.create("1357")
        val profile = UserProfile("fixture", "Fixture", pinVerifier = verifier)
        val record = SyncRecord("77", "fixture", "profile", "fixture",
            kotlinx.serialization.json.Json.encodeToString(profile), 1, "device", "mutation")
        val doc = AccountDocument(schemaVersion = 2, accountId = "77", records = listOf(record),
            profileFiles = mapOf("unused-profile" to "unused-hash"))
        val requests = mutableListOf<String>()
        val api = transport(readOnly = true) { method, path ->
            assertEquals("GET", method)
            requests += path
            when {
                path.endsWith("/git/ref/heads/main") -> 200 to """{"object":{"sha":"pinned-head"}}"""
                path.endsWith("manifest.json") -> encoded("""{"application":"niktv","schemaVersion":2,"accountId":"77"}""")
                path.endsWith("account.json") -> encoded(kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(doc))
                else -> error("Unexpected request: $path")
            }
        }
        assertEquals(listOf(record), api.fetchProfileRoster(SyncRepository("dynamic-owner", "main", "unused"), "77"))
        assertEquals(3, requests.size)
        assertTrue(requests.none { "/profiles/" in it })
    }

    @Test fun missingRemoteRosterNeverMeansNoPassword() = runBlocking {
        val api = transport(readOnly = true) { _, path -> when {
            path.endsWith("/git/ref/heads/main") -> 200 to """{"object":{"sha":"pinned-head"}}"""
            path.endsWith("manifest.json") -> encoded("""{"application":"niktv","schemaVersion":2,"accountId":"77"}""")
            else -> 404 to "{}"
        } }
        try { api.fetchProfileRoster(SyncRepository("dynamic-owner", "main", "unused"), "77"); fail("Missing roster accepted") }
        catch (_: SyncFailure) { }
    }

    private fun encoded(content: String) = 200 to org.json.JSONObject().put("encoding", "base64")
        .put("sha", "fixture-sha").put("content", android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)).toString()

    @Test fun readOnlyTransportBlocksWritesBeforeTheyReachTheNetwork() = runBlocking {
        val api = GitHubSyncTransport("fixture", OkHttpClient.Builder().addInterceptor {
            fail("Read-only transport attempted HTTP write")
            throw AssertionError()
        }.build(), readOnly = true)
        try { api.checkWrite(SyncRepository("fixture", "main", "https://github.com/fixture/niktv-data")); fail("Write accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("blocked")) }
    }

    @Test fun readOnlyBootstrapNeverCreatesRepositoryOrManifest() = runBlocking {
        for (missingRepo in listOf(true, false)) {
            val api = transport(readOnly = true) { method, path ->
                assertEquals("GET", method)
                if (missingRepo || path.endsWith("manifest.json")) 404 to "{}"
                else 200 to """{"private":true,"default_branch":"main","html_url":"https://github.com/dynamic-owner/niktv-data"}"""
            }
            try { api.bootstrap(identity); fail("Missing remote setup accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private val identity = SyncIdentity("77", "dynamic-owner")
    private fun transport(readOnly: Boolean = false, handler: (String, String) -> Pair<Int, String>) = GitHubSyncTransport("fixture-token", OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val result = handler(request.method, request.url.encodedPath)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(result.first).message("fixture")
            .body(result.second.toResponseBody()).build()
    }.build(), pauseWrites = false, readOnly = readOnly)

    @Test fun identityUsesAuthenticatedNumericIdAndLogin() = runBlocking {
        val api = transport { _, path -> assertEquals("/user", path); 200 to """{"id":77,"login":"dynamic-owner"}""" }
        assertEquals(identity, api.identity())
    }

    @Test fun invalidTokenHasActionableSafeReconnectError() = runBlocking {
        val api = transport { _, _ -> 401 to """{"message":"fixture-token"}""" }
        try { api.identity(); fail("Invalid token accepted") }
        catch (error: SyncFailure) {
            assertTrue(error.reconnect); assertTrue(error.message.contains("revoked")); assertFalse(error.message.contains("fixture-token"))
        }
    }

    @Test fun existingPublicRepositoryIsNeverWritten() = runBlocking {
        val api = transport { method, _ -> assertEquals("GET", method); 200 to """{"private":false}""" }
        try { api.bootstrap(identity); fail("Public repository accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("private")) }
    }

    @Test fun unrelatedPrivateRepositoryIsNeverClaimed() = runBlocking {
        val api = transport { method, path ->
            assertEquals("GET", method)
            when {
                path.endsWith("manifest.json") -> 404 to "{}"
                path.contains("/git/trees/") -> 200 to """{"tree":[{"path":"personal.txt"}]}"""
                else -> 200 to """{"private":true,"default_branch":"personal-branch","html_url":"https://github.com/dynamic-owner/niktv-data"}"""
            }
        }
        try { api.bootstrap(identity); fail("Unrelated repository accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("preserved")) }
    }

    @Test fun newerManifestSchemaBlocksAllWrites() = runBlocking {
        val encoded = android.util.Base64.encodeToString("""{"application":"niktv","schemaVersion":3,"accountId":"77"}""".toByteArray(), android.util.Base64.NO_WRAP)
        val api = transport { method, path ->
            assertEquals("GET", method)
            if (path.endsWith("manifest.json")) 200 to """{"encoding":"base64","sha":"one","content":"$encoded"}"""
            else 200 to """{"private":true,"default_branch":"actual-default","html_url":"https://github.com/dynamic-owner/niktv-data"}"""
        }
        try { api.bootstrap(identity); fail("Newer schema accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Update NikTV")) }
    }

    @Test fun repositoryCreationPermissionIsDistinctFromContentsWrite() = runBlocking {
        val api = transport { method, path ->
            when {
                method == "POST" && path == "/user/repos" -> 403 to "{}"
                else -> 404 to "{}"
            }
        }
        try { api.bootstrap(identity); fail("Denied creation accepted") }
        catch (error: SyncFailure) { assertTrue(error.message.contains("repository creation")); assertTrue(error.reconnect) }
    }

    @Test fun contentsWritePermissionIsVerifiedWithoutChangingFiles() = runBlocking {
        val api = transport { method, path -> assertEquals("POST", method); assertTrue(path.endsWith("/git/blobs")); 403 to "{}" }
        try { api.checkWrite(SyncRepository(identity.login, "main", "unused")); fail("Denied write accepted") }
        catch (error: SyncFailure) { assertTrue(error.message.contains("Contents read and write")) }
    }
}
