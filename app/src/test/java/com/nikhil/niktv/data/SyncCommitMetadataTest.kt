package com.nikhil.niktv.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class SyncCommitMetadataTest {
    private val fixture = SyncDeviceInfo("Xiaomi", "M2102J20SI", "13", "1.2.3", "abcd1234")

    private fun fixtureClient(branch: String, onCommit: (JSONObject) -> Unit) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath.substringAfter("/niktv-data")
        val body = request.body?.let { JSONObject(Buffer().apply { it.writeTo(this) }.readUtf8()) }
        val response = when {
            path == "/git/ref/heads/$branch" -> """{"object":{"sha":"old-sha"}}"""
            path == "/contents/manifest.json" -> encodedFile("""{"application":"niktv","schemaVersion":1,"accountId":"77"}""")
            path == "/contents/account.json" -> encodedFile("""{"application":"niktv","schemaVersion":1,"accountId":"77","records":[]}""")
            path == "/git/commits/old-sha" -> """{"tree":{"sha":"old-tree"}}"""
            path == "/git/trees" && request.method == "POST" -> """{"sha":"new-tree"}"""
            path == "/git/commits" && request.method == "POST" -> {
                onCommit(requireNotNull(body)); """{"sha":"new-commit"}"""
            }
            path == "/git/refs/heads/$branch" && request.method == "PATCH" -> {
                assertFalse(requireNotNull(body).getBoolean("force")); "{}"
            }
            else -> error("Unexpected fixture request: ${request.method} $path")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(response.toResponseBody()).build()
    }.build()

    private fun encodedFile(content: String) = JSONObject().put("encoding", "base64").put("sha", gitBlobSha(content))
        .put("size", content.toByteArray().size).put("content", android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)).toString()

    @Test fun uploadedCommitIncludesOnlyBoundedDeviceAndSoftwareMetadata() = runBlocking {
        val expected = "Sync NikTV account data · Xiaomi M2102J20SI · Android 13 · NikTV v1.2.3 · device abcd1234"
        val message = syncCommitMessage(fixture)
        assertEquals(expected, message)
        var captured: JSONObject? = null
        val client = fixtureClient("actual-branch") { captured = it }
        val transport = GitHubSyncTransport("fixture-auth-token", client, pauseWrites = false, commitMessage = message)
        val repository = SyncRepository("fixture-owner", "actual-branch", "unused")
        val remote = transport.fetch(repository, "77")
        transport.upload(repository, remote.document, remote.sha)
        assertEquals(expected, captured!!.getString("message"))
        assertEquals("old-sha", captured!!.getJSONArray("parents").getString(0))
        assertFalse(captured.toString().contains("fixture-auth-token"))
    }

    @Test fun customDeviceNamesAreNotSentAndBackupInstallIdentityIsReused() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("device_backup_identity", 0).edit()
            .putString("id", "a1b2c3d4").putString("name", "Location-fixture Toronto").commit()
        val first = currentDeviceSyncCommitMessage(context)
        val second = currentDeviceSyncCommitMessage(context)
        assertEquals(first, second)
        assertTrue(first.contains("device a1b2c3d4"))
        assertFalse(first.contains("Location-fixture"))
        assertFalse(first.contains("Toronto"))
    }

    @Test fun urlCredentialAndHardwareIdentifierFieldsAreDiscarded() {
        val unsafe = listOf("https://fixture.invalid/secret", "password=fixture-secret", "token=fixture-secret",
            "Bearer fixture-secret", "ghp_fixtureSecret", "github_pat_fixtureSecret", "AA:BB:CC:DD:EE:FF",
            "serial=fixture-secret", "android_id=fixture-secret", "pass\u0000word=fixture-secret")
        unsafe.forEach { value ->
            val message = syncCommitMessage(fixture.copy(model = value, installationId = value))
            assertFalse(value, message.contains("fixture"))
            assertFalse(message.contains("AA:BB:CC"))
            assertTrue(message.contains("device unknown"))
        }
    }

    @Test fun metadataIsSingleLineAndBoundedWithoutSplittingIdentityIntoControls() {
        val message = syncCommitMessage(fixture.copy(manufacturer = "Maker\n\r\t\u2028\u202e" + "X".repeat(500),
            model = "Model".repeat(500), androidVersion = "13\nBeta".repeat(100), appVersion = "1.2.3".repeat(500)))
        assertTrue(message.length <= MAX_SYNC_COMMIT_MESSAGE_LENGTH)
        assertFalse(message.any { it.isISOControl() || it == '\u2028' || it == '\u202e' })
        assertTrue(message.endsWith("device abcd1234"))
        assertEquals(DEFAULT_SYNC_COMMIT_MESSAGE, sanitizeSyncCommitMessage("Sync data https://fixture.invalid/token"))
    }

    @Test fun transportDefaultStaysPlainAndUnsafeOverrideCannotLeakIntoCommit() = runBlocking {
        val messages = mutableListOf<String>()
        val client = fixtureClient("main") { messages += it.getString("message") }
        val repository = SyncRepository("fixture-owner", "main", "unused")
        for (message in listOf(DEFAULT_SYNC_COMMIT_MESSAGE, "secret token=fixture-secret")) {
            val transport = GitHubSyncTransport("fixture-auth-token", client, pauseWrites = false, commitMessage = message)
            val remote = transport.fetch(repository, "77")
            transport.upload(repository, remote.document, remote.sha)
        }
        assertEquals(listOf(DEFAULT_SYNC_COMMIT_MESSAGE, DEFAULT_SYNC_COMMIT_MESSAGE), messages)
    }
}
