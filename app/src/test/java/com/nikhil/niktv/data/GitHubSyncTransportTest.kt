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
    private val identity = SyncIdentity("77", "dynamic-owner")
    private fun transport(handler: (String, String) -> Pair<Int, String>) = GitHubSyncTransport("fixture-token", OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val result = handler(request.method, request.url.encodedPath)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(result.first).message("fixture")
            .body(result.second.toResponseBody()).build()
    }.build(), pauseWrites = false)

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
