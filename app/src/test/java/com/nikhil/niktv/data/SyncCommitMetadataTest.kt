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

    @Test fun uploadedCommitIncludesOnlyBoundedDeviceAndSoftwareMetadata() = runBlocking {
        val expected = "Sync NikTV account data · Xiaomi M2102J20SI · Android 13 · NikTV v1.2.3 · device abcd1234"
        val message = syncCommitMessage(fixture)
        assertEquals(expected, message)
        var captured: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("/repos/fixture-owner/niktv-data/contents/account.json", request.url.encodedPath)
            captured = JSONObject(Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("fixture")
                .body("{}".toResponseBody()).build()
        }.build()
        GitHubSyncTransport("fixture-auth-token", client, pauseWrites = false, commitMessage = message)
            .upload(SyncRepository("fixture-owner", "actual-branch", "unused"), AccountDocument(accountId = "77"), "old-sha")
        assertEquals(expected, captured!!.getString("message"))
        assertEquals("actual-branch", captured!!.getString("branch"))
        assertEquals("old-sha", captured!!.getString("sha"))
        assertFalse(captured.toString().contains("fixture-auth-token"))
        Unit
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
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            messages += JSONObject(Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()).getString("message")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("fixture")
                .body("{}".toResponseBody()).build()
        }.build()
        val repository = SyncRepository("fixture-owner", "main", "unused")
        val document = AccountDocument(accountId = "77")
        GitHubSyncTransport("fixture-auth-token", client, pauseWrites = false).upload(repository, document, null)
        GitHubSyncTransport("fixture-auth-token", client, pauseWrites = false, commitMessage = "secret token=fixture-secret")
            .upload(repository, document, null)
        assertEquals(listOf(DEFAULT_SYNC_COMMIT_MESSAGE, DEFAULT_SYNC_COMMIT_MESSAGE), messages)
    }
}
