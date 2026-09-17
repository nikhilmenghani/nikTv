package com.nikhil.niktv.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CatalogBackupFormatTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun passwordFreeCatalogRestoresWithOrWithoutSavedPassword() {
        val manager = CatalogBackupManager(context)
        val payload = "Y2F0YWxvZw=="
        val encoded = manager.encodePayload(payload, "")
        assertFalse(encoded.contains("ciphertext"))
        assertEquals(payload, manager.decodePayload(encoded, ""))
        assertEquals(payload, manager.decodePayload(encoded, "some saved password"))
    }

    @Test fun existingEncryptedCatalogStillRestoresAndRejectsMissingPassword() {
        val manager = CatalogBackupManager(context)
        val password = "test catalog password"
        val encoded = GitHubBackupManager(context).encryptBackup("Y2F0YWxvZw==", password)
        assertEquals("Y2F0YWxvZw==", manager.decodePayload(encoded, password))
        assertThrows(IllegalArgumentException::class.java) { manager.decodePayload(encoded, "") }
    }

    @Test fun unsupportedPlainCatalogFormatIsRejected() {
        val manager = CatalogBackupManager(context)
        assertThrows(IllegalArgumentException::class.java) {
            manager.decodePayload("""{"catalogContainerVersion":2,"encoding":"gzip-base64","payload":"abc"}""", "")
        }
    }
}
